package com.unvvv.app.face

import android.graphics.Bitmap
import android.content.Context
import android.util.Base64
import com.unvvv.app.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors

class Person(
    val id: String,
    val name: String,
    val embs: List<FloatArray>,
    val thumb: String?,
)

class FaceEvent(
    val id: String,
    val time: Long,
    val camId: String,
    val personId: String?,
    val score: Float,
    val emb: FloatArray,
    val snap: String,
    val face: String,
)

/** Хранилище людей и событий распознавания (всё только на телефоне). */
object FaceDb {
    private const val MAX_EVENTS = 600
    private const val MAX_SAMPLES = 60

    private lateinit var dir: File
    private lateinit var imgDir: File
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "facedb").apply { isDaemon = true } }

    val people = MutableStateFlow<List<Person>>(emptyList())
    val events = MutableStateFlow<List<FaceEvent>>(emptyList())

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "faces").apply { mkdirs() }
        imgDir = File(dir, "img").apply { mkdirs() }
        people.value = runCatching {
            val a = JSONArray(File(dir, "people.json").readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                val e = o.getJSONArray("embs")
                Person(
                    o.getString("id"), o.getString("name"),
                    (0 until e.length()).map { decode(e.getString(it)) },
                    o.optString("thumb").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
        events.value = runCatching {
            val a = JSONArray(File(dir, "events.json").readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                FaceEvent(
                    o.getString("id"), o.getLong("time"), o.getString("cam"),
                    o.optString("person").ifEmpty { null }, o.optDouble("score", 0.0).toFloat(),
                    decode(o.getString("emb")), o.getString("snap"), o.getString("face"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun img(name: String) = File(imgDir, name)

    fun saveJpeg(bmp: Bitmap, quality: Int = 85): String {
        val name = UUID.randomUUID().toString() + ".jpg"
        FileOutputStream(img(name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return name
    }

    // ---------- сравнение ----------

    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** Лучшее совпадение среди известных людей. person == null, если ниже порога. */
    fun match(e: FloatArray): Pair<Person?, Float> {
        var best: Person? = null
        var bestScore = -1f
        for (p in people.value) {
            // среднее из 3 лучших образцов устойчивее, чем один максимум
            val scores = p.embs.map { dot(it, e) }.sortedDescending()
            if (scores.isEmpty()) continue
            val top = scores.take(3)
            val s = maxOf(scores[0] - 0.05f, top.average().toFloat())
            if (s > bestScore) {
                bestScore = s
                best = p
            }
        }
        return if (best != null && bestScore >= Prefs.faceThreshold) best to bestScore else null to bestScore.coerceAtLeast(0f)
    }

    // ---------- люди ----------

    @Synchronized
    fun createPerson(name: String, embs: List<FloatArray>, thumb: String?): Person {
        val p = Person(UUID.randomUUID().toString(), name.trim().ifBlank { "Без имени" }, embs.takeLast(MAX_SAMPLES), thumb)
        people.value = people.value + p
        savePeople()
        return p
    }

    @Synchronized
    fun addSamples(personId: String, embs: List<FloatArray>, thumb: String? = null) {
        people.value = people.value.map {
            if (it.id == personId) Person(it.id, it.name, (it.embs + embs).takeLast(MAX_SAMPLES), it.thumb ?: thumb) else it
        }
        savePeople()
    }

    @Synchronized
    fun removeSample(personId: String, emb: FloatArray) {
        people.value = people.value.map { p ->
            if (p.id == personId) Person(p.id, p.name, p.embs.filterNot { it.contentEquals(emb) }, p.thumb) else p
        }
        savePeople()
    }

    @Synchronized
    fun rename(personId: String, name: String) {
        people.value = people.value.map { if (it.id == personId) Person(it.id, name.trim().ifBlank { it.name }, it.embs, it.thumb) else it }
        savePeople()
    }

    @Synchronized
    fun deletePerson(personId: String) {
        people.value.firstOrNull { it.id == personId }?.thumb?.let { t ->
            if (events.value.none { it.face == t }) img(t).delete()
        }
        people.value = people.value.filterNot { it.id == personId }
        events.value = events.value.map { if (it.personId == personId) it.withPerson(null) else it }
        savePeople()
        saveEvents()
    }

    // ---------- события ----------

    @Synchronized
    fun addEvent(e: FaceEvent) {
        val all = listOf(e) + events.value
        val keep = all.take(MAX_EVENTS)
        val dropped = all.drop(MAX_EVENTS)
        events.value = keep
        val usedThumbs = people.value.mapNotNull { it.thumb }.toSet()
        dropped.forEach {
            img(it.snap).delete()
            if (it.face !in usedThumbs) img(it.face).delete()
        }
        saveEvents()
    }

    /**
     * Отметить событие как человека [personId]: лицо добавляется в образцы человека,
     * а похожие незнакомые события переразмечаются автоматически. Возвращает число переразмеченных.
     */
    @Synchronized
    fun assignEvent(eventId: String, personId: String): Int {
        val ev = events.value.firstOrNull { it.id == eventId } ?: return 0
        if (people.value.none { it.id == personId }) return 0
        if (ev.personId != null && ev.personId != personId) removeSample(ev.personId, ev.emb)
        addSamples(personId, listOf(ev.emb), ev.face)
        val updated = people.value.first { it.id == personId }
        var relabeled = 0
        events.value = events.value.map { e ->
            when {
                e.id == eventId -> e.withPerson(personId, e.score)
                e.personId == null && bestFor(updated, e.emb) >= Prefs.faceThreshold -> {
                    relabeled++
                    e.withPerson(personId, bestFor(updated, e.emb))
                }
                else -> e
            }
        }
        saveEvents()
        return relabeled
    }

    @Synchronized
    fun unassignEvent(eventId: String) {
        val ev = events.value.firstOrNull { it.id == eventId } ?: return
        ev.personId?.let { removeSample(it, ev.emb) }
        events.value = events.value.map { if (it.id == eventId) it.withPerson(null) else it }
        saveEvents()
    }

    @Synchronized
    fun deleteEvent(eventId: String) {
        val ev = events.value.firstOrNull { it.id == eventId } ?: return
        events.value = events.value.filterNot { it.id == eventId }
        img(ev.snap).delete()
        if (people.value.none { it.thumb == ev.face }) img(ev.face).delete()
        saveEvents()
    }

    @Synchronized
    fun clearEvents() {
        val usedThumbs = people.value.mapNotNull { it.thumb }.toSet()
        events.value.forEach {
            img(it.snap).delete()
            if (it.face !in usedThumbs) img(it.face).delete()
        }
        events.value = emptyList()
        saveEvents()
    }

    private fun bestFor(p: Person, e: FloatArray): Float = p.embs.maxOfOrNull { dot(it, e) } ?: 0f

    private fun FaceEvent.withPerson(pid: String?, sc: Float = score) =
        FaceEvent(id, time, camId, pid, sc, emb, snap, face)

    // ---------- сохранение ----------

    private fun savePeople() {
        val snapshot = people.value
        io.execute {
            runCatching {
                val a = JSONArray()
                snapshot.forEach { p ->
                    val e = JSONArray()
                    p.embs.forEach { e.put(encode(it)) }
                    a.put(JSONObject().put("id", p.id).put("name", p.name).put("thumb", p.thumb ?: "").put("embs", e))
                }
                atomicWrite(File(dir, "people.json"), a.toString())
            }
        }
    }

    private fun saveEvents() {
        val snapshot = events.value
        io.execute {
            runCatching {
                val a = JSONArray()
                snapshot.forEach { e ->
                    a.put(
                        JSONObject().put("id", e.id).put("time", e.time).put("cam", e.camId)
                            .put("person", e.personId ?: "").put("score", e.score.toDouble())
                            .put("emb", encode(e.emb)).put("snap", e.snap).put("face", e.face)
                    )
                }
                atomicWrite(File(dir, "events.json"), a.toString())
            }
        }
    }

    private fun atomicWrite(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    private fun encode(v: FloatArray): String {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putFloat(it) }
        return Base64.encodeToString(b.array(), Base64.NO_WRAP)
    }

    private fun decode(s: String): FloatArray {
        val b = ByteBuffer.wrap(Base64.decode(s, Base64.NO_WRAP)).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining() / 4) { b.getFloat() }
    }
}
