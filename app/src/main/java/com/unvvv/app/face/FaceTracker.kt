package com.unvvv.app.face

import kotlin.math.hypot
import kotlin.math.max

/** Лицо, найденное в одном кадре. */
class Obs(
    /** Рамка x1, y1, x2, y2 в координатах кадра. */
    val box: FloatArray,
    /** Отпечаток лица; null — лицо слишком мелкое, чтобы узнавать. */
    val emb: FloatArray?,
    /** Качество 0…1 (размер, поворот, резкость) — вес кадра при накоплении. */
    val quality: Float,
    /** Чёткость выровненного лица 0…1 — от неё зависит порог узнавания. */
    val clarity: Float = 1f,
    /** Что угодно, что нужно сохранить вместе с лучшим кадром (например, вырезанное лицо). */
    val payload: Any? = null,
)

/**
 * Один человек, которого видно в нескольких кадрах подряд.
 * Отпечатки всех его кадров копятся с весом по качеству — решение «кто это» принимается
 * по сумме, а не по одному кадру. Это резко снижает число ошибок.
 */
class Track(val id: Long, var box: FloatArray, val born: Long) {
    var lastSeen = born
    /** Кадров с отпечатком. */
    var hits = 0
        internal set
    private var sum: FloatArray? = null
    var weight = 0f
        private set
    private var claritySum = 0f
    var best: Obs? = null
        internal set
    var lastEmb: FloatArray? = null
        private set

    // состояние решения — ведёт FaceEngine
    var personId: String? = null
    var score = 0f
    var unknown = false
    var eventDone = false
    var learned = false

    val bestQuality get() = best?.quality ?: -1f
    val meanQuality get() = if (hits == 0) 0f else weight / hits
    val clarity get() = if (hits == 0) 0f else claritySum / hits

    /** Нормированный накопленный отпечаток или null. */
    fun aggregate(): FloatArray? = sum?.let { FaceMath.normalize(it.copyOf()) }

    internal fun add(o: Obs, release: (Any?) -> Unit) {
        box = o.box
        val e = o.emb
        if (e != null) {
            val w = 0.05f + o.quality
            val s = sum ?: FloatArray(e.size).also { sum = it }
            FaceMath.addScaled(s, e, w)
            weight += o.quality
            claritySum += o.clarity
            hits++
            lastEmb = e
            val b = best
            if (b == null || o.quality > b.quality) {
                if (b != null) release(b.payload)
                best = o
                return
            }
        }
        release(o.payload)
    }
}

/**
 * Простой многообъектный трекер для редких кадров (≈1 кадр/с): связывает лица между кадрами
 * по пересечению рамок, расстоянию и сходству отпечатков.
 */
class FaceTracker(
    private val maxAgeMs: Long = 4_000,
    private val release: (Any?) -> Unit = {},
) {
    val tracks = ArrayList<Track>()
    private var nextId = 1L

    class Update(
        /** Трек для каждого наблюдения (в том же порядке). */
        val assigned: List<Track>,
        /** Треки, которые давно не видны и удалены. */
        val expired: List<Track>,
    )

    private fun center(b: FloatArray) = floatArrayOf((b[0] + b[2]) / 2f, (b[1] + b[3]) / 2f)

    /** Насколько наблюдение похоже на трек; null — точно не он. */
    private fun affinity(t: Track, o: Obs): Float? {
        val iou = FaceMath.iou(t.box, o.box)
        val size = max(max(t.box[2] - t.box[0], t.box[3] - t.box[1]), max(o.box[2] - o.box[0], o.box[3] - o.box[1])).coerceAtLeast(1f)
        val ct = center(t.box)
        val co = center(o.box)
        val dist = hypot(ct[0] - co[0], ct[1] - co[1]) / size
        val te = t.aggregate()
        val oe = o.emb
        return if (te != null && oe != null) {
            val sim = FaceMath.dot(te, oe)
            when {
                (iou >= 0.1f || dist <= 2.5f) && sim >= 0.25f -> sim + 0.5f * iou
                sim >= 0.5f && dist <= 8f -> sim
                else -> null
            }
        } else {
            if (iou >= 0.2f || dist <= 1.0f) 0.2f * iou + 0.2f / (1f + dist) else null
        }
    }

    fun update(obs: List<Obs>, now: Long): Update {
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for (ti in tracks.indices) for (oi in obs.indices) {
            affinity(tracks[ti], obs[oi])?.let { pairs += Triple(it, ti, oi) }
        }
        pairs.sortByDescending { it.first }
        val usedT = BooleanArray(tracks.size)
        val result = arrayOfNulls<Track>(obs.size)
        for ((_, ti, oi) in pairs) {
            if (usedT[ti] || result[oi] != null) continue
            usedT[ti] = true
            result[oi] = tracks[ti]
        }
        for (oi in obs.indices) {
            val t = result[oi] ?: Track(nextId++, obs[oi].box, now).also { tracks += it }
            t.lastSeen = now
            t.add(obs[oi], release)
            result[oi] = t
        }
        val expired = tracks.filter { now - it.lastSeen > maxAgeMs }
        if (expired.isNotEmpty()) {
            tracks.removeAll(expired.toSet())
            expired.forEach { release(it.best?.payload) }
        }
        return Update(result.map { it!! }, expired)
    }

    fun clear() {
        tracks.forEach { release(it.best?.payload) }
        tracks.clear()
    }
}
