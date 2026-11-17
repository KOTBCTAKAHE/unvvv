package com.unvvv.app.face

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.unvvv.app.App
import com.unvvv.app.R
import com.unvvv.app.data.Camera
import com.unvvv.app.data.Prefs
import com.unvvv.app.record.RecordService
import com.unvvv.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Две нейросети на телефоне:
 *  1) ML Kit Face Detection — находит лица и глаза в кадре;
 *  2) FaceNet-512 (TensorFlow Lite) — превращает выровненное лицо 160×160 в «отпечаток» из 512 чисел.
 * Отпечатки сравниваются с базой людей (косинусное сходство).
 */
object FaceEngine {
    private const val SIZE = 160
    private const val EMB = 512
    private const val MIN_RECOG = 40 // px — меньше уже не узнать
    private const val MIN_EVENT = 56 // px — меньше не создаём событие о незнакомом

    class Box(val rect: RectF, val label: String?, val known: Boolean)
    class Live(val time: Long, val boxes: List<Box>)

    /** Последние найденные лица по камерам — для рамок в живом просмотре. */
    val live = MutableStateFlow<Map<String, Live>>(emptyMap())

    private lateinit var app: Context
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "faces").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val busy = ConcurrentHashMap<String, Boolean>()
    private var interpreter: Interpreter? = null
    private val input: ByteBuffer = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)

    private val fastDetector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.03f)
                .build()
        )
    }
    private val accurateDetector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    // анти-спам уведомлений
    private val lastKnown = HashMap<String, Long>()
    private val recentUnknown = ArrayList<Pair<Long, FloatArray>>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    private fun interp(): Interpreter {
        interpreter?.let { return it }
        val fd = app.assets.openFd("facenet512.tflite")
        val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        return Interpreter(buf, Interpreter.Options().setNumThreads(4)).also { interpreter = it }
    }

    fun canAccept(camId: String) = busy[camId] != true

    /** Кадр с камеры. content — область кадра, занятая картинкой (без чёрных полос). */
    fun submit(cam: Camera, frame: Bitmap, content: RectF) {
        if (busy.put(cam.id, true) == true) {
            frame.recycle()
            return
        }
        exec.execute {
            try {
                analyze(cam, frame, content)
            } catch (_: Throwable) {
            } finally {
                frame.recycle()
                busy[cam.id] = false
            }
        }
    }

    private fun analyze(cam: Camera, frame: Bitmap, content: RectF) {
        val faces = Tasks.await(fastDetector.process(InputImage.fromBitmap(frame, 0)), 5, TimeUnit.SECONDS)
        val boxes = ArrayList<Box>()
        for (f in faces) {
            val b = f.boundingBox
            val nr = RectF(
                (b.left - content.left) / content.width(),
                (b.top - content.top) / content.height(),
                (b.right - content.left) / content.width(),
                (b.bottom - content.top) / content.height(),
            )
            if (b.width() < MIN_RECOG) {
                boxes += Box(nr, null, false)
                continue
            }
            val aligned = align(frame, f)
            val emb = embed(aligned)
            val (person, score) = FaceDb.match(emb)
            boxes += Box(nr, person?.name ?: "Незнакомый", person != null)
            val frontal = abs(f.headEulerAngleY) < 30 && abs(f.headEulerAngleZ) < 30 && b.width() >= MIN_EVENT
            if (person != null || frontal) onFace(cam, frame, b, aligned, emb, person, score)
            aligned.recycle()
        }
        live.value = live.value + (cam.id to Live(System.currentTimeMillis(), boxes))
    }

    /** Поворачивает лицо так, чтобы глаза были горизонтально, и вырезает квадрат 160×160. */
    private fun align(src: Bitmap, f: Face): Bitmap {
        val b = f.boundingBox
        val l = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val r = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        var angle = 0f
        var cx = b.exactCenterX()
        var cy = b.exactCenterY()
        if (l != null && r != null) {
            val a = if (l.x < r.x) l else r
            val c = if (l.x < r.x) r else l
            angle = Math.toDegrees(atan2((c.y - a.y).toDouble(), (c.x - a.x).toDouble())).toFloat()
            // центр между глазами чуть выше центра лица — сдвигаем к центру рамки по горизонтали
            cx = (cx + (a.x + c.x) / 2f) / 2f
        }
        val size = max(b.width(), b.height()) * 1.2f
        cy += b.height() * 0.02f
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val m = Matrix().apply {
            postTranslate(-cx, -cy)
            postRotate(-angle)
            postScale(SIZE / size, SIZE / size)
            postTranslate(SIZE / 2f, SIZE / 2f)
        }
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun embed(face: Bitmap): FloatArray {
        face.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        var sum = 0.0
        var sq = 0.0
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val bl = p and 0xFF
            sum += r + g + bl
            sq += (r * r + g * g + bl * bl).toDouble()
        }
        val n = pixels.size * 3
        val mean = sum / n
        val std = max(sqrt(max(sq / n - mean * mean, 0.0)), 1.0 / sqrt(n.toDouble()))
        input.rewind()
        for (p in pixels) {
            input.putFloat((((p shr 16) and 0xFF) - mean).toFloat() / std.toFloat())
            input.putFloat((((p shr 8) and 0xFF) - mean).toFloat() / std.toFloat())
            input.putFloat(((p and 0xFF) - mean).toFloat() / std.toFloat())
        }
        input.rewind()
        val out = Array(1) { FloatArray(EMB) }
        interp().run(input, out)
        val v = out[0]
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm).coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= norm
        return v
    }

    /** Отпечаток самого крупного лица на фото (для добавления человека из галереи). */
    suspend fun enroll(photo: Bitmap): Pair<FloatArray, Bitmap>? = suspendCancellableCoroutine { cont ->
        exec.execute {
            val res = runCatching {
                val faces = Tasks.await(accurateDetector.process(InputImage.fromBitmap(photo, 0)), 10, TimeUnit.SECONDS)
                val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return@runCatching null
                val aligned = align(photo, f)
                embed(aligned) to aligned
            }.getOrNull()
            cont.resume(res)
        }
    }

    // ---------- события и уведомления ----------

    private fun onFace(cam: Camera, frame: Bitmap, box: Rect, face: Bitmap, emb: FloatArray, person: Person?, score: Float) {
        val now = System.currentTimeMillis()
        if (person != null) {
            val k = cam.id + "|" + person.id
            if (now - (lastKnown[k] ?: 0L) < 5 * 60_000L) return
            lastKnown[k] = now
        } else {
            recentUnknown.removeAll { now - it.first > 3 * 60_000L }
            val th = Prefs.faceThreshold - 0.08f
            if (recentUnknown.any { FaceDb.dot(it.second, emb) >= th }) return
            recentUnknown += now to emb
        }

        // снимок кадра с рамкой
        val scale = min(1f, 960f / frame.width)
        val snap = Bitmap.createBitmap((frame.width * scale).toInt(), (frame.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(snap).apply {
            drawBitmap(frame, Matrix().apply { postScale(scale, scale) }, Paint(Paint.FILTER_BITMAP_FLAG))
            val pnt = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = if (person != null) Color.rgb(34, 197, 94) else Color.rgb(239, 68, 68)
            }
            drawRect(box.left * scale, box.top * scale, box.right * scale, box.bottom * scale, pnt)
        }
        val ev = FaceEvent(
            UUID.randomUUID().toString(), now, cam.id, person?.id, score, emb,
            FaceDb.saveJpeg(snap, 80), FaceDb.saveJpeg(face, 90),
        )
        FaceDb.addEvent(ev)

        if (person == null && Prefs.recordOnUnknown) RecordService.recordFor(app, cam.id, 2 * 60_000L)
        if ((person == null && Prefs.notifyUnknown) || (person != null && Prefs.notifyKnown)) notify(cam, person, snap, face)
        snap.recycle()
    }

    @SuppressLint("MissingPermission")
    private fun notify(cam: Camera, person: Person?, snap: Bitmap, face: Bitmap) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            app, 2,
            Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 3)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = person?.let { "${it.name} — ${cam.name}" } ?: "Незнакомый человек — ${cam.name}"
        val n = NotificationCompat.Builder(app, App.CHANNEL_FACES)
            .setSmallIcon(R.drawable.ic_face)
            .setContentTitle(title)
            .setContentText(if (person == null) "Нажмите, чтобы посмотреть и отметить, кто это" else "Узнан на камере")
            .setLargeIcon(face.copy(Bitmap.Config.ARGB_8888, false))
            .setStyle(NotificationCompat.BigPictureStyle().bigPicture(snap.copy(Bitmap.Config.ARGB_8888, false)))
            .setPriority(if (person == null) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify((System.currentTimeMillis() % 100000).toInt() + 1000, n) }
    }
}
