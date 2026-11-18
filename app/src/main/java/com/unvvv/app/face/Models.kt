package com.unvvv.app.face

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Нейросети InsightFace через ONNX Runtime:
 *  - SCRFD-500M — находит лица и 5 опорных точек (глаза, нос, уголки рта);
 *  - ArcFace MobileFaceNet (WebFace600K) — превращает выровненное лицо 112×112 в отпечаток из 512 чисел.
 * Оба класса не потокобезопасны: вызываются только из потока FaceEngine.
 */
internal object Ort {
    val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }

    fun session(ctx: Context, asset: String, threads: Int): OrtSession {
        val bytes = ctx.assets.open(asset).use { it.readBytes() }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return env.createSession(bytes, opts)
    }

    fun floats(fb: FloatBuffer): FloatArray {
        val d = fb.duplicate()
        d.rewind()
        return FloatArray(d.remaining()).also { d.get(it) }
    }

    fun direct(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
}

/** Детектор лиц SCRFD с квадратным входом 640×640. */
internal class Scrfd(ctx: Context) {
    companion object {
        const val SIZE = 640
    }

    private val session = Ort.session(ctx, "scrfd_500m.onnx", 2)
    private val inputName = session.inputNames.first()
    private val canvasBmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(canvasBmp)
    private val px = IntArray(SIZE * SIZE)
    private val arr = FloatArray(3 * SIZE * SIZE)
    private val buf = Ort.direct(3 * SIZE * SIZE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()

    /**
     * Ищет лица в прямоугольнике [src] изображения [img] (вписывается в 640×640 от левого верхнего угла).
     * Координаты результата — в системе [img].
     */
    fun detect(img: Bitmap, src: Rect, thresh: Float): List<FaceMath.Det> {
        if (src.width() <= 0 || src.height() <= 0) return emptyList()
        val s = min(SIZE.toFloat() / src.width(), SIZE.toFloat() / src.height())
        dst.set(0, 0, (src.width() * s).roundToInt().coerceIn(1, SIZE), (src.height() * s).roundToInt().coerceIn(1, SIZE))
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(img, src, dst, paint)
        canvasBmp.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val n = SIZE * SIZE
        for (i in 0 until n) {
            val p = px[i]
            arr[i] = (((p shr 16) and 0xFF) - 127.5f) / 128f
            arr[n + i] = (((p shr 8) and 0xFF) - 127.5f) / 128f
            arr[2 * n + i] = ((p and 0xFF) - 127.5f) / 128f
        }
        buf.rewind()
        buf.put(arr)
        buf.rewind()

        val scores = arrayOfNulls<FloatArray>(3)
        val boxes = arrayOfNulls<FloatArray>(3)
        val kps = arrayOfNulls<FloatArray>(3)
        OnnxTensor.createTensor(Ort.env, buf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                for (i in 0 until res.size()) {
                    val v = res[i] as OnnxTensor
                    val shape = v.info.shape
                    val last = shape.last().toInt()
                    val total = shape.fold(1L) { a, b -> a * b }.toInt()
                    val anchors = total / last
                    // по числу якорей определяем шаг: 8 → (640/8)²·2, 16 → …, 32 → …
                    val si = when (anchors) {
                        (SIZE / 8) * (SIZE / 8) * 2 -> 0
                        (SIZE / 16) * (SIZE / 16) * 2 -> 1
                        (SIZE / 32) * (SIZE / 32) * 2 -> 2
                        else -> -1
                    }
                    if (si < 0) continue
                    val data = Ort.floats(v.floatBuffer)
                    when (last) {
                        1 -> scores[si] = data
                        4 -> boxes[si] = data
                        10 -> kps[si] = data
                    }
                }
            }
        }
        if (scores.any { it == null } || boxes.any { it == null } || kps.any { it == null }) return emptyList()
        val dets = FaceMath.decodeScrfd(
            Array(3) { scores[it]!! }, Array(3) { boxes[it]!! }, Array(3) { kps[it]!! }, SIZE, thresh,
        )
        return FaceMath.nms(dets).map { it.mapped(1f / s, src.left.toFloat(), src.top.toFloat()) }
    }
}

/** Распознаватель ArcFace: отпечаток 512 чисел, сравнение — косинус. */
internal class ArcFace(ctx: Context) {
    companion object {
        const val SIZE = 112
        const val DIM = 512
    }

    private val session = Ort.session(ctx, "arcface_mbf.onnx", 2)
    private val inputName = session.inputNames.first()
    private val px = IntArray(SIZE * SIZE)
    private val arr = FloatArray(3 * SIZE * SIZE)
    private val buf = Ort.direct(3 * SIZE * SIZE)

    /**
     * Отпечаток выровненного лица. [chip] — квадрат FaceEngine.CHIP (160) с лицом по шаблону;
     * в нейросеть идёт центральный квадрат 112×112. Усредняется с зеркальной копией.
     */
    fun embed(chip: Bitmap): FloatArray {
        val off = (chip.width - SIZE) / 2
        chip.getPixels(px, 0, SIZE, off, off, SIZE, SIZE)
        val a = run(false)
        val b = run(true)
        for (i in a.indices) a[i] += b[i]
        return FaceMath.normalize(a)
    }

    private fun run(mirror: Boolean): FloatArray {
        val n = SIZE * SIZE
        for (y in 0 until SIZE) {
            val row = y * SIZE
            for (x in 0 until SIZE) {
                val p = px[row + if (mirror) SIZE - 1 - x else x]
                val i = row + x
                arr[i] = (((p shr 16) and 0xFF) - 127.5f) / 127.5f
                arr[n + i] = (((p shr 8) and 0xFF) - 127.5f) / 127.5f
                arr[2 * n + i] = ((p and 0xFF) - 127.5f) / 127.5f
            }
        }
        buf.rewind()
        buf.put(arr)
        buf.rewind()
        OnnxTensor.createTensor(Ort.env, buf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            session.run(mapOf(inputName to t)).use { res ->
                val out = Ort.floats((res[0] as OnnxTensor).floatBuffer)
                return FaceMath.normalize(if (out.size == DIM) out else out.copyOf(DIM))
            }
        }
    }
}

/** Выравнивание лица в квадрат по 5 точкам (подобие к шаблону ArcFace). */
internal object Aligner {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun chip(src: Bitmap, kps: FloatArray, size: Int, template: FloatArray): Bitmap {
        val m = FaceMath.similarity(kps, template)
        val mat = Matrix().apply { setValues(floatArrayOf(m[0], -m[1], m[2], m[1], m[0], m[3], 0f, 0f, 1f)) }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            drawBitmap(src, mat, paint)
        }
        return out
    }

    /** Резкость центральной части лица: средний квадрат градиента яркости. */
    fun sharpness(chip: Bitmap, inner: Int): Float {
        val off = (chip.width - inner) / 2
        val p = IntArray(inner * inner)
        chip.getPixels(p, 0, inner, off, off, inner, inner)
        fun l(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        var acc = 0.0
        var cnt = 0
        for (y in 1 until inner) for (x in 1 until inner) {
            val i = y * inner + x
            val gx = l(p[i]) - l(p[i - 1])
            val gy = l(p[i]) - l(p[i - inner])
            acc += (gx * gx + gy * gy).toDouble()
            cnt++
        }
        return if (cnt == 0) 0f else (acc / cnt).toFloat()
    }
}
