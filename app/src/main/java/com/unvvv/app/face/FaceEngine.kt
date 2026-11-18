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
import android.graphics.PointF
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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Две нейросети на телефоне:
 *  1) ML Kit Face Detection — находит лица и глаза в кадре;
 *  2) FaceNet-512 (TensorFlow Lite) — превращает выровненное лицо 160×160 в «отпечаток» из 512 чисел.
 * Отпечатки сравниваются с базой людей (косинусное сходство).
 *
 * Чтобы ловить людей по всему кадру (в том числе мелких и по краям), кадр анализируется
 * не целиком одним проходом, а ещё и перекрывающимися плитками: в плитке дальнее лицо
 * занимает бóльшую долю картинки, и детектор его замечает. Дубли убираются по IoU.
 * Для устойчивого «отпечатка» лицо выравнивается по глазам и усредняется с зеркальной копией.
 */
object FaceEngine {
    private const val SIZE = 160
    private const val EMB = 512
    private const val MIN_RECOG = 36  // px на кадре — меньше уже не узнать
    private const val MIN_EVENT = 52  // px — меньше не создаём событие о незнакомом
    private const val MARGIN = 0.05f  // запас лучшего совпадения над вторым (меньше — риск спутать людей)
    private const val TILE_MIN = 900  // от этой ширины/высоты включается разбиение на плитки
    private const val MAX_FACES = 12  // верхний предел лиц за кадр

    class Box(val rect: RectF, val label: String?, val known: Boolean)
    class Live(val time: Long, val boxes: List<Box>)

    /** Найденное лицо в координатах полного кадра. */
    private class Det(
        val box: RectF,
        val leftEye: PointF?,
        val rightEye: PointF?,
        val angleY: Float,
        val angleZ: Float,
    ) {
        val area get() = box.width() * box.height()
    }

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
                .setMinFaceSize(0.08f)
                .build()
        )
    }
    private val accurateDetector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.04f)
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
        val dets = detectAll(frame)
        val boxes = ArrayList<Box>()
        for (d in dets) {
            val b = d.box
            val nr = RectF(
                (b.left - content.left) / content.width(),
                (b.top - content.top) / content.height(),
                (b.right - content.left) / content.width(),
                (b.bottom - content.top) / content.height(),
            )
            val fw = min(b.width(), b.height())
            if (fw < MIN_RECOG) {
                boxes += Box(nr, null, false)
                continue
            }
            val aligned = align(frame, d)
            val sharp = sharpnessOf(aligned)
            val emb = embedTta(aligned)
            val m = FaceDb.matchBest(emb)
            // чем мельче/размытее лицо, тем строже требуем совпадения — меньше ложных узнаваний
            val penalty = (if (fw < MIN_EVENT) 0.04f else 0f) + (if (sharp < LOW_SHARP) 0.04f else 0f)
            val accept = m.person != null && m.best >= Prefs.faceThreshold + penalty && m.margin >= MARGIN
            val person = if (accept) m.person else null
            boxes += Box(nr, person?.name ?: "Незнакомый", person != null)
            val rect = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
            val frontal = abs(d.angleY) < 32 && abs(d.angleZ) < 32 && fw >= MIN_EVENT && sharp >= LOW_SHARP
            if (person != null || frontal) onFace(cam, frame, rect, aligned, emb, person, m.best)
            aligned.recycle()
        }
        live.value = live.value + (cam.id to Live(System.currentTimeMillis(), boxes))
    }

    // ---------- детекция по всему кадру ----------

    /** Полный кадр + перекрывающиеся плитки, дубли убираются по IoU. */
    private fun detectAll(frame: Bitmap): List<Det> {
        val out = ArrayList<Det>()
        out += detectIn(frame, 0, 0, frame.width, frame.height, accurateDetector)
        if (frame.width >= TILE_MIN) {
            for (t in tiles(frame.width, frame.height)) {
                out += detectIn(frame, t.left, t.top, t.width(), t.height(), fastDetector)
            }
        }
        return nms(out).sortedByDescending { it.area }.take(MAX_FACES)
    }

    /** Детектирует лица в прямоугольнике кадра и переводит координаты в координаты полного кадра. */
    private fun detectIn(frame: Bitmap, ox: Int, oy: Int, w: Int, h: Int, det: FaceDetector): List<Det> {
        if (w <= 0 || h <= 0) return emptyList()
        val whole = ox == 0 && oy == 0 && w == frame.width && h == frame.height
        val bmp = if (whole) frame else Bitmap.createBitmap(frame, ox, oy, w, h)
        return try {
            val faces = Tasks.await(det.process(InputImage.fromBitmap(bmp, 0)), 5, TimeUnit.SECONDS)
            faces.map { f ->
                val b = f.boundingBox
                val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
                val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                Det(
                    RectF(
                        (b.left + ox).toFloat().coerceIn(0f, frame.width.toFloat()),
                        (b.top + oy).toFloat().coerceIn(0f, frame.height.toFloat()),
                        (b.right + ox).toFloat().coerceIn(0f, frame.width.toFloat()),
                        (b.bottom + oy).toFloat().coerceIn(0f, frame.height.toFloat()),
                    ),
                    le?.let { PointF(it.x + ox, it.y + oy) },
                    re?.let { PointF(it.x + ox, it.y + oy) },
                    f.headEulerAngleY, f.headEulerAngleZ,
                )
            }
        } catch (_: Throwable) {
            emptyList()
        } finally {
            if (!whole) bmp.recycle()
        }
    }

    private fun tiles(w: Int, h: Int): List<Rect> {
        val cols = if (w >= 1000) 3 else 2
        val rows = 2
        val stepX = w / cols
        val stepY = h / rows
        val ox = (stepX * 0.2f).toInt()
        val oy = (stepY * 0.2f).toInt()
        val list = ArrayList<Rect>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val l = max(0, c * stepX - ox)
            val t = max(0, r * stepY - oy)
            val rt = min(w, (c + 1) * stepX + ox)
            val bt = min(h, (r + 1) * stepY + oy)
            list += Rect(l, t, rt, bt)
        }
        return list
    }

    /** Non-Maximum Suppression: оставляем крупнейшее из пересекающихся лиц. */
    private fun nms(dets: List<Det>): List<Det> {
        val sorted = dets.sortedByDescending { it.area }
        val kept = ArrayList<Det>()
        for (d in sorted) {
            if (kept.none { iou(it.box, d.box) > 0.4f }) kept += d
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left); val t = max(a.top, b.top)
        val r = min(a.right, b.right); val bo = min(a.bottom, b.bottom)
        if (r <= l || bo <= t) return 0f
        val inter = (r - l) * (bo - t)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    // ---------- выравнивание и отпечаток ----------

    private const val LOW_SHARP = 60f

    /**
     * Поворачивает лицо так, чтобы глаза были горизонтально, и вырезает квадрат 160×160.
     * Масштаб и центр берутся от расстояния между глазами — кроп получается одинаковым
     * для одного человека независимо от того, как детектор очертил рамку.
     */
    private fun align(src: Bitmap, d: Det): Bitmap {
        val b = d.box
        var angle = 0f
        var cx = b.centerX()
        var cy = b.centerY()
        var size = max(b.width(), b.height()) * 1.25f
        val l = d.leftEye; val r = d.rightEye
        if (l != null && r != null) {
            val a = if (l.x < r.x) l else r
            val c = if (l.x < r.x) r else l
            val iod = hypot((c.x - a.x).toDouble(), (c.y - a.y).toDouble()).toFloat()
            if (iod > 1f) {
                angle = Math.toDegrees(atan2((c.y - a.y).toDouble(), (c.x - a.x).toDouble())).toFloat()
                cx = (a.x + c.x) / 2f
                cy = (a.y + c.y) / 2f + iod * 0.30f // чуть ниже глаз, к носу
                size = iod * 3.2f
            }
        }
        if (size < 1f) size = max(b.width(), b.height()).coerceAtLeast(1f)
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

    private fun luma(p: Int) = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

    /** Резкость: средний квадрат градиента яркости. Размытые/мелкие лица дают низкое значение. */
    private fun sharpnessOf(face: Bitmap): Float {
        face.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        var acc = 0.0
        var cnt = 0
        for (y in 1 until SIZE) for (x in 1 until SIZE) {
            val i = y * SIZE + x
            val gx = luma(pixels[i]) - luma(pixels[i - 1])
            val gy = luma(pixels[i]) - luma(pixels[i - SIZE])
            acc += (gx * gx + gy * gy).toDouble()
            cnt++
        }
        return if (cnt == 0) 0f else (acc / cnt).toFloat()
    }

    private fun runModel(): FloatArray {
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

    /** Заполняет вход модели из [pixels]; при mirror=true отражает по горизонтали. */
    private fun fillInput(mirror: Boolean) {
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
        val m = mean.toFloat()
        val s = std.toFloat()
        for (y in 0 until SIZE) {
            val row = y * SIZE
            for (x in 0 until SIZE) {
                val p = pixels[row + (if (mirror) SIZE - 1 - x else x)]
                input.putFloat((((p shr 16) and 0xFF) - m) / s)
                input.putFloat((((p shr 8) and 0xFF) - m) / s)
                input.putFloat(((p and 0xFF) - m) / s)
            }
        }
        input.rewind()
    }

    /** Отпечаток лица, усреднённый с зеркальной копией (устойчивее к ракурсу и шуму). */
    private fun embedTta(face: Bitmap): FloatArray {
        face.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        fillInput(false)
        val a = runModel()
        fillInput(true)
        val b = runModel()
        val v = FloatArray(EMB) { a[it] + b[it] }
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
                val b = f.boundingBox
                val det = Det(
                    RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat()),
                    f.getLandmark(FaceLandmark.LEFT_EYE)?.position,
                    f.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
                    f.headEulerAngleY, f.headEulerAngleZ,
                )
                val aligned = align(photo, det)
                embedTta(aligned) to aligned
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
