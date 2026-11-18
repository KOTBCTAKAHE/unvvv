package com.unvvv.app.face

import android.content.Context
import android.graphics.Bitmap
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
import kotlin.math.max

/** Один снимок лица человека: отпечаток ArcFace и картинка выровненного лица. */
class Sample(
    val emb: FloatArray,
    val img: String?,
    /** Добавлен автоматически (самообучение на камерах), а не вручную. */
    val auto: Boolean = false,
    val time: Long = System.currentTimeMillis(),
)

class Person(
    val id: String,
    val name: String,
    val samples: List<Sample>,
    val thumb: String?,
) {
    /** Средний отпечаток всех образцов. */
    val centroid: FloatArray? = if (samples.isEmpty()) null else {
        val c = FloatArray(samples[0].emb.size)
        samples.forEach { FaceMath.addScaled(c, it.emb, 1f) }
        FaceMath.normalize(c)
    }

    val autoCount get() = samples.count { it.auto }

    /**
     * Сходство отпечатка с человеком: половина — с «центром» всех его образцов,
     * половина — с самым похожим образцом. Так учитываются и общее сходство, и разные условия съёмки.
     */
    fun score(e: FloatArray): Float {
        val c = centroid ?: return -1f
        var best = -1f
        for (s in samples) best = max(best, FaceMath.dot(s.emb, e))
        return 0.5f * FaceMath.dot(c, e) + 0.5f * best
    }

    fun with(name: String = this.name, samples: List<Sample> = this.samples, thumb: String? = this.thumb) =
        Person(id, name, samples, thumb)
}

class FaceEvent(
    val id: String,
    val time: Long,
    val camId: String,
    val personId: String?,
    val score: Float,
    /** Отпечаток (ArcFace, если [ver] = 2; 1 — старый FaceNet до обновления; 0 — пересчитать не удалось). */
    val emb: FloatArray,
    val snap: String,
    val face: String,
    val ver: Int = FaceDb.VER,
) {
    /** Отпечаток, пригодный для сравнения (только новой нейросетью). */
    val arc: FloatArray? get() = if (ver == FaceDb.VER) emb else null

    fun with(personId: String? = this.personId, score: Float = this.score, emb: FloatArray = this.emb, ver: Int = this.ver) =
        FaceEvent(id, time, camId, personId, score, emb, snap, face, ver)
}

/** Хранилище людей и событий распознавания (всё только на телефоне). */
object FaceDb {
    /** Версия отпечатков: 2 — ArcFace MobileFaceNet. */
    const val VER = 2
    private const val MAX_EVENTS = 600
    private const val MAX_MANUAL = 80
    private const val MAX_AUTO = 30

    private lateinit var dir: File
    private lateinit var imgDir: File
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "facedb").apply { isDaemon = true } }

    val people = MutableStateFlow<List<Person>>(emptyList())
    val events = MutableStateFlow<List<FaceEvent>>(emptyList())

    /** Есть данные старой версии — их нужно пересчитать новой нейросетью (делает FaceEngine). */
    @Volatile
    var needsMigration = false
        private set

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "faces").apply { mkdirs() }
        imgDir = File(dir, "img").apply { mkdirs() }
        val pf = File(dir, "people.json")
        people.value = runCatching {
            val text = pf.readText()
            if (text.trimStart().startsWith("[")) {
                // старый формат: отпечатки FaceNet — не годятся, оставляем людей без образцов до миграции
                val a = JSONArray(text)
                needsMigration = true
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    Person(o.getString("id"), o.getString("name"), emptyList(), o.optString("thumb").ifEmpty { null })
                }
            } else {
                val root = JSONObject(text)
                val a = root.getJSONArray("people")
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    val ss = o.getJSONArray("samples")
                    Person(
                        o.getString("id"), o.getString("name"),
                        (0 until ss.length()).map { j ->
                            val s = ss.getJSONObject(j)
                            Sample(decode(s.getString("e")), s.optString("img").ifEmpty { null }, s.optBoolean("auto"), s.optLong("t"))
                        },
                        o.optString("thumb").ifEmpty { null },
                    )
                }
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
                    o.optInt("v", 1),
                )
            }
        }.getOrDefault(emptyList())
        if (events.value.any { it.ver == 1 }) needsMigration = true
    }

    fun img(name: String) = File(imgDir, name)

    fun saveJpeg(bmp: Bitmap, quality: Int = 85): String {
        val name = UUID.randomUUID().toString() + ".jpg"
        FileOutputStream(img(name)).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        return name
    }

    // ---------- сравнение ----------

    fun dot(a: FloatArray, b: FloatArray) = FaceMath.dot(a, b)

    /** Результат сравнения с базой: лучший человек, его сходство и отрыв от второго. */
    class Match(val person: Person?, val best: Float, val margin: Float)

    fun match(e: FloatArray): Match {
        var best: Person? = null
        var b1 = -1f
        var b2 = -1f
        for (p in people.value) {
            val s = p.score(e)
            if (s < -0.5f) continue
            if (s > b1) {
                b2 = b1; b1 = s; best = p
            } else if (s > b2) b2 = s
        }
        return Match(best, b1.coerceAtLeast(0f), if (b2 < -0.5f) 1f else b1 - b2)
    }

    // ---------- люди ----------

    /** Убирает почти одинаковые снимки и ограничивает число образцов. */
    private fun merged(old: List<Sample>, add: List<Sample>): List<Sample> {
        val out = ArrayList(old)
        for (s in add) {
            if (out.any { FaceMath.dot(it.emb, s.emb) > 0.97f }) continue
            out += s
        }
        val manual = out.filter { !it.auto }.takeLast(MAX_MANUAL)
        val auto = out.filter { it.auto }.takeLast(MAX_AUTO)
        return out.filter { it in manual || it in auto }
    }

    @Synchronized
    fun createPerson(name: String, samples: List<Sample>, thumb: String?): Person {
        val p = Person(UUID.randomUUID().toString(), name.trim().ifBlank { "Без имени" }, merged(emptyList(), samples), thumb)
        people.value = people.value + p
        savePeople()
        return p
    }

    @Synchronized
    fun addSamples(personId: String, samples: List<Sample>, thumb: String? = null) {
        people.value = people.value.map {
            if (it.id == personId) it.with(samples = merged(it.samples, samples), thumb = it.thumb ?: thumb) else it
        }
        savePeople()
    }

    @Synchronized
    fun clearAuto(personId: String) {
        val removed = ArrayList<Sample>()
        people.value = people.value.map { p ->
            if (p.id != personId) p else {
                removed += p.samples.filter { it.auto }
                p.with(samples = p.samples.filter { !it.auto })
            }
        }
        savePeople()
        removed.mapNotNull { it.img }.forEach { deleteIfUnused(it) }
    }

    @Synchronized
    private fun removeSample(personId: String, emb: FloatArray) {
        people.value = people.value.map { p ->
            if (p.id == personId) p.with(samples = p.samples.filterNot { it.emb.contentEquals(emb) }) else p
        }
        savePeople()
    }

    @Synchronized
    fun rename(personId: String, name: String) {
        people.value = people.value.map { if (it.id == personId) it.with(name = name.trim().ifBlank { it.name }) else it }
        savePeople()
    }

    @Synchronized
    fun deletePerson(personId: String) {
        val p = people.value.firstOrNull { it.id == personId } ?: return
        people.value = people.value.filterNot { it.id == personId }
        events.value = events.value.map { if (it.personId == personId) it.with(personId = null) else it }
        savePeople()
        saveEvents()
        (p.samples.mapNotNull { it.img } + listOfNotNull(p.thumb)).forEach { deleteIfUnused(it) }
    }

    // ---------- события ----------

    @Synchronized
    fun addEvent(e: FaceEvent) {
        val all = listOf(e) + events.value
        events.value = all.take(MAX_EVENTS)
        saveEvents()
        all.drop(MAX_EVENTS).forEach {
            deleteIfUnused(it.snap)
            deleteIfUnused(it.face)
        }
    }

    /** Трек узнали позже, чем записали «незнакомого», — исправляем событие (без добавления образца). */
    @Synchronized
    fun setEventPerson(eventId: String, personId: String, score: Float) {
        events.value = events.value.map { if (it.id == eventId) it.with(personId = personId, score = score) else it }
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
        ev.arc?.let { addSamples(personId, listOf(Sample(it, ev.face)), ev.face) }
        val th = Prefs.faceStrict + 0.02f
        var relabeled = 0
        events.value = events.value.map { e ->
            val a = e.arc
            when {
                e.id == eventId -> e.with(personId = personId)
                e.personId == null && a != null -> {
                    val m = match(a)
                    if (m.person?.id == personId && m.best >= th && m.margin >= 0.06f) {
                        relabeled++
                        e.with(personId = personId, score = m.best)
                    } else e
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
        events.value = events.value.map { if (it.id == eventId) it.with(personId = null) else it }
        saveEvents()
    }

    @Synchronized
    fun deleteEvent(eventId: String) {
        val ev = events.value.firstOrNull { it.id == eventId } ?: return
        events.value = events.value.filterNot { it.id == eventId }
        saveEvents()
        deleteIfUnused(ev.snap)
        deleteIfUnused(ev.face)
    }

    @Synchronized
    fun clearEvents() {
        val old = events.value
        events.value = emptyList()
        saveEvents()
        old.forEach {
            deleteIfUnused(it.snap)
            deleteIfUnused(it.face)
        }
    }

    /** Замена всего содержимого (после пересчёта базы новой нейросетью). */
    @Synchronized
    fun replaceAll(newPeople: List<Person>, newEvents: List<FaceEvent>) {
        people.value = newPeople
        events.value = newEvents
        needsMigration = false
        savePeople()
        saveEvents()
    }

    private fun deleteIfUnused(name: String) {
        val used = people.value.any { p -> p.thumb == name || p.samples.any { it.img == name } } ||
            events.value.any { it.face == name || it.snap == name }
        if (!used) img(name).delete()
    }

    // ---------- сохранение ----------

    private fun savePeople() {
        val snapshot = people.value
        io.execute {
            runCatching {
                val a = JSONArray()
                snapshot.forEach { p ->
                    val ss = JSONArray()
                    p.samples.forEach { s ->
                        ss.put(JSONObject().put("e", encode(s.emb)).put("img", s.img ?: "").put("auto", s.auto).put("t", s.time))
                    }
                    a.put(JSONObject().put("id", p.id).put("name", p.name).put("thumb", p.thumb ?: "").put("samples", ss))
                }
                atomicWrite(File(dir, "people.json"), JSONObject().put("v", VER).put("model", "insightface-w600k-mbf").put("people", a).toString())
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
                            .put("emb", encode(e.emb)).put("snap", e.snap).put("face", e.face).put("v", e.ver)
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
