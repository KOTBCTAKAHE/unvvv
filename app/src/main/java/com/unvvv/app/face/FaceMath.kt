package com.unvvv.app.face

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Чистая математика распознавания — без Android, чтобы её можно было проверять на обычной JVM
 * против эталонной реализации (InsightFace).
 *
 *  - декодирование выходов детектора SCRFD (якоря, расстояния → рамка и 5 точек), NMS;
 *  - выравнивание лица: подобие (поворот + масштаб + сдвиг) по 5 точкам к шаблону ArcFace;
 *  - оценки качества (размер, поворот головы) и сравнение с базой.
 */
object FaceMath {
    /** Шаблон ArcFace 112×112: левый глаз, правый глаз, нос, левый и правый угол рта. */
    val ARC_DST = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f,
    )

    private val STRIDES = intArrayOf(8, 16, 32)

    /** Шаблон для квадрата [size]; scale < 1 — больше фона вокруг лица. */
    fun template(size: Int, scale: Float = 1f): FloatArray {
        val k = size / 112f
        return FloatArray(10) { i -> ((ARC_DST[i] - 56f) * scale + 56f) * k }
    }

    /**
     * Подобие по методу наименьших квадратов, переводящее точки [src] в [dst] (по 5 точек x,y).
     * Возвращает [a, b, tx, ty]: x' = a·x − b·y + tx, y' = b·x + a·y + ty.
     */
    fun similarity(src: FloatArray, dst: FloatArray): FloatArray {
        val n = src.size / 2
        var mx = 0.0; var my = 0.0; var ux = 0.0; var uy = 0.0
        for (i in 0 until n) {
            mx += src[2 * i]; my += src[2 * i + 1]
            ux += dst[2 * i]; uy += dst[2 * i + 1]
        }
        mx /= n; my /= n; ux /= n; uy /= n
        var varS = 0.0; var sa = 0.0; var sb = 0.0
        for (i in 0 until n) {
            val sx = src[2 * i] - mx; val sy = src[2 * i + 1] - my
            val dx = dst[2 * i] - ux; val dy = dst[2 * i + 1] - uy
            varS += sx * sx + sy * sy
            sa += sx * dx + sy * dy
            sb += sx * dy - sy * dx
        }
        if (varS < 1e-9) return floatArrayOf(1f, 0f, (ux - mx).toFloat(), (uy - my).toFloat())
        val a = sa / varS
        val b = sb / varS
        val tx = ux - (a * mx - b * my)
        val ty = uy - (b * mx + a * my)
        return floatArrayOf(a.toFloat(), b.toFloat(), tx.toFloat(), ty.toFloat())
    }

    /** Найденное лицо: рамка x1,y1,x2,y2; уверенность; 5 точек (10 чисел). */
    class Det(val box: FloatArray, val score: Float, val kps: FloatArray) {
        val w get() = box[2] - box[0]
        val h get() = box[3] - box[1]
        val area get() = max(0f, w) * max(0f, h)

        /** Переводит координаты: x·s + ox, y·s + oy. */
        fun mapped(s: Float, ox: Float, oy: Float) = Det(
            FloatArray(4) { i -> box[i] * s + if (i % 2 == 0) ox else oy },
            score,
            FloatArray(10) { i -> kps[i] * s + if (i % 2 == 0) ox else oy },
        )
    }

    /**
     * Декодирует выходы SCRFD для квадратного входа [size].
     * scores[i] — (N,1), boxes[i] — (N,4), kps[i] — (N,10) для шагов 8, 16, 32; по 2 якоря на точку.
     */
    fun decodeScrfd(
        scores: Array<FloatArray>,
        boxes: Array<FloatArray>,
        kps: Array<FloatArray>,
        size: Int,
        thresh: Float,
    ): List<Det> {
        val out = ArrayList<Det>()
        for (si in STRIDES.indices) {
            val st = STRIDES[si].toFloat()
            val fw = size / STRIDES[si]
            val sc = scores[si]
            val bb = boxes[si]
            val kp = kps[si]
            for (k in sc.indices) {
                val s = sc[k]
                if (s < thresh) continue
                val loc = k / 2
                val cx = (loc % fw) * st
                val cy = (loc / fw) * st
                val b = FloatArray(4)
                b[0] = cx - bb[4 * k] * st
                b[1] = cy - bb[4 * k + 1] * st
                b[2] = cx + bb[4 * k + 2] * st
                b[3] = cy + bb[4 * k + 3] * st
                val p = FloatArray(10)
                for (j in 0 until 5) {
                    p[2 * j] = cx + kp[10 * k + 2 * j] * st
                    p[2 * j + 1] = cy + kp[10 * k + 2 * j + 1] * st
                }
                out += Det(b, s, p)
            }
        }
        return out
    }

    fun iou(a: FloatArray, b: FloatArray): Float {
        val l = max(a[0], b[0]); val t = max(a[1], b[1])
        val r = min(a[2], b[2]); val bo = min(a[3], b[3])
        if (r <= l || bo <= t) return 0f
        val inter = (r - l) * (bo - t)
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }

    /** Подавление дублей: оставляем самое уверенное из пересекающихся. */
    fun nms(dets: List<Det>, th: Float = 0.4f): List<Det> {
        val keep = ArrayList<Det>()
        for (d in dets.sortedByDescending { it.score }) {
            if (keep.none { iou(it.box, d.box) > th }) keep += d
        }
        return keep
    }

    /** Доля пересечения от меньшей рамки (вложенность). */
    fun containment(a: FloatArray, b: FloatArray): Float {
        val l = max(a[0], b[0]); val t = max(a[1], b[1])
        val r = min(a[2], b[2]); val bo = min(a[3], b[3])
        if (r <= l || bo <= t) return 0f
        val sa = (a[2] - a[0]) * (a[3] - a[1])
        val sb = (b[2] - b[0]) * (b[3] - b[1])
        return (r - l) * (bo - t) / min(sa, sb).coerceAtLeast(1e-6f)
    }

    /**
     * Лицо из плитки касается её внутреннего края — значит, оно разрезано;
     * целиком его видит соседняя плитка или полный кадр.
     */
    fun cutByTile(b: FloatArray, x0: Int, y0: Int, x1: Int, y1: Int, w: Int, h: Int, m: Float = 4f): Boolean =
        (x0 > 0 && b[0] < x0 + m) || (y0 > 0 && b[1] < y0 + m) || (x1 < w && b[2] > x1 - m) || (y1 < h && b[3] > y1 - m)

    /** Слияние детекций полного кадра и плиток: убираем дубли и вложенные рамки. */
    fun mergeDets(dets: List<Det>, iouTh: Float = 0.4f, containTh: Float = 0.6f): List<Det> {
        val keep = ArrayList<Det>()
        for (d in dets.sortedByDescending { it.score }) {
            if (keep.none { iou(it.box, d.box) > iouTh || containment(it.box, d.box) > containTh }) keep += d
        }
        return keep
    }

    // ---------- геометрия и качество лица ----------

    /** Расстояние между центрами глаз, px. */
    fun iod(kps: FloatArray) = hypot(kps[2] - kps[0], kps[3] - kps[1])

    /**
     * Поворот головы влево-вправо: 0 — анфас, 1 — профиль.
     * Нос проецируется на линию глаз: у анфаса он посередине.
     */
    fun yaw(kps: FloatArray): Float {
        val ex = kps[2] - kps[0]; val ey = kps[3] - kps[1]
        val l2 = ex * ex + ey * ey
        if (l2 < 1e-6f) return 1f
        val t = ((kps[4] - kps[0]) * ex + (kps[5] - kps[1]) * ey) / l2
        return min(1f, abs(t - 0.5f) * 2f)
    }

    /**
     * Качество 0…1 для взвешивания и отбора: размер лица, уверенность детектора, поворот, резкость.
     * [sharp] — средний квадрат градиента яркости выровненного лица 112×112.
     */
    fun quality(kps: FloatArray, detScore: Float, sharp: Float): Float {
        val size = ((iod(kps) - 8f) / 32f).coerceIn(0f, 1f)        // 8 px → 0, 40 px → 1
        val pose = (1f - yaw(kps) / 0.8f).coerceIn(0f, 1f)          // профиль → 0
        val conf = ((detScore - 0.4f) / 0.4f).coerceIn(0f, 1f)
        val sh = ((sharp - 20f) / 120f).coerceIn(0f, 1f)
        return (0.15f + 0.85f * sqrt(size * pose)) * (0.6f + 0.25f * conf + 0.15f * sh)
    }

    /** Чёткость 0…1 по резкости выровненного лица 112×112 (замерено на LFW: размытые ≈ 50, чёткие ≈ 150). */
    fun clarity(sharp: Float) = ((sharp - 30f) / 120f).coerceIn(0f, 1f)

    /**
     * Порог узнавания для трека. У размытых лиц сходство с чужими ниже — порог можно снизить
     * (замер: +3.6 п.п. узнаваний при той же доле ложных). По одному кадру требуем чуть больше.
     */
    fun threshold(base: Float, clarity: Float, hits: Int) =
        base - 0.06f * (1f - clarity.coerceIn(0f, 1f)) + if (hits < 2) 0.03f else 0f

    // ---------- отпечатки ----------

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = min(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }

    fun normalize(v: FloatArray): FloatArray {
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n).coerceAtLeast(1e-9f)
        for (i in v.indices) v[i] /= n
        return v
    }

    /** Сумма a + w·b на месте (для накопления отпечатков трека). */
    fun addScaled(acc: FloatArray, b: FloatArray, w: Float) {
        for (i in acc.indices) acc[i] += b[i] * w
    }

    /**
     * Сходство с человеком по сходствам со всеми его образцами:
     * лучшее из (максимум − 0.04) и среднего пяти лучших — устойчиво к единичным выбросам.
     */
    fun personScore(sims: FloatArray): Float {
        if (sims.isEmpty()) return -1f
        val s = sims.sortedArrayDescending()
        val k = min(5, s.size)
        var m = 0f
        for (i in 0 until k) m += s[i]
        return max(s[0] - 0.04f, m / k)
    }
}
