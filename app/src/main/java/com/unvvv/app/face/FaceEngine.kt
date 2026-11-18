package com.unvvv.app.face

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.unvvv.app.App
import com.unvvv.app.R
import com.unvvv.app.data.Camera
import com.unvvv.app.data.Prefs
import com.unvvv.app.record.RecordService
import com.unvvv.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Распознавание людей на камерах.
 *
 * 1) SCRFD ищет лица по всему кадру: целиком и перекрывающимися плитками ~560 px —
 *    так находятся даже лица 18–30 px у краёв и вдали.
 * 2) Каждое лицо выравнивается по 5 точкам к шаблону ArcFace и превращается в отпечаток.
 * 3) Трекер связывает лица между кадрами; отпечатки копятся с весом по качеству,
 *    и решение «кто это» принимается по накопленному отпечатку, а не по одному кадру.
 * 4) Узнавание требует и порога сходства, и отрыва от второго кандидата.
 * 5) Уверенно узнанные лица с камер могут пополнять образцы человека (самообучение).
 */
object FaceEngine {
    private const val TAG = "FaceEngine"

    /** Сторона сохраняемого выровненного лица; центральные 112×112 идут в ArcFace. */
    const val CHIP = 160
    private val CHIP_T = FaceMath.template(CHIP, 0.7f)
    private const val DET_TH = 0.5f
    private const val TILE = 560
    private const val MAX_FACES = 16
    private const val MIN_IOD_RECOG = 9f     // px между глазами — меньше только рамка
    private const val MIN_IOD_ENROLL = 16f
    private const val MARGIN = 0.06f         // отрыв лучшего человека от второго
    private const val UNKNOWN_MIN_Q = 0.30f

    class Box(val rect: RectF, val label: String?, val known: Boolean)
    class Live(val time: Long, val boxes: List<Box>)

    /** Последние найденные лица по камерам — для рамок в живом просмотре. */
    val live = MutableStateFlow<Map<String, Live>>(emptyMap())

    /** Пересчёт базы под новую нейросеть: (сделано, всего) или null. */
    val migration = MutableStateFlow<Pair<Int, Int>?>(null)

    /** Ошибка загрузки нейросетей (null — всё в порядке). */
    val error = MutableStateFlow<String?>(null)

    private lateinit var app: Context
    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "faces").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val busy = ConcurrentHashMap<String, Boolean>()
    private var det: Scrfd? = null
    private var arc: ArcFace? = null

    // всё ниже — только из потока exec
    private val trackers = HashMap<String, FaceTracker>()
    private val trackEvent = HashMap<Long, String>()
    private val lastKnown = HashMap<String, Long>()
    private val recentUnknown = ArrayList<Pair<Long, FloatArray>>()

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (FaceDb.needsMigration) exec.execute { migrate() }
    }

    private fun models(): Pair<Scrfd, ArcFace>? {
        val d = det
        val a = arc
        if (d != null && a != null) return d to a
        return runCatching {
            val nd = d ?: Scrfd(app).also { det = it }
            val na = a ?: ArcFace(app).also { arc = it }
            error.value = null
            nd to na
        }.onFailure {
            Log.e(TAG, "models", it)
            error.value = it.message ?: it.javaClass.simpleName
        }.getOrNull()
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
            } catch (t: Throwable) {
                Log.w(TAG, "analyze", t)
            } finally {
                frame.recycle()
                busy[cam.id] = false
            }
        }
    }

    // ---------- детекция по всему кадру ----------

    /** Полный кадр + перекрывающиеся плитки; лица, разрезанные краем плитки, отбрасываются. */
    private fun detectAll(d: Scrfd, frame: Bitmap): List<FaceMath.Det> {
        val w = frame.width
        val h = frame.height
        val all = ArrayList<FaceMath.Det>()
        all += d.detect(frame, Rect(0, 0, w, h), DET_TH)
        if (max(w, h) > TILE) {
            val cols = ceil(w / TILE.toFloat()).toInt()
            val rows = ceil(h / TILE.toFloat()).toInt()
            val sx = w.toFloat() / cols
            val sy = h.toFloat() / rows
            for (r in 0 until rows) for (c in 0 until cols) {
                val x0 = max(0f, c * sx - sx * 0.2f).toInt()
                val y0 = max(0f, r * sy - sy * 0.2f).toInt()
                val x1 = min(w.toFloat(), (c + 1) * sx + sx * 0.2f).toInt()
                val y1 = min(h.toFloat(), (r + 1) * sy + sy * 0.2f).toInt()
                for (dd in d.detect(frame, Rect(x0, y0, x1, y1), DET_TH)) {
                    if (!FaceMath.cutByTile(dd.box, x0, y0, x1, y1, w, h)) all += dd
                }
            }
        }
        return FaceMath.mergeDets(all).take(MAX_FACES)
    }

    private fun chipOf(src: Bitmap, kps: FloatArray) = Aligner.chip(src, kps, CHIP, CHIP_T)

    // ---------- анализ кадра ----------

    private fun analyze(cam: Camera, frame: Bitmap, content: RectF) {
        val (d, a) = models() ?: return
        val dets = detectAll(d, frame)
        val obs = ArrayList<Obs>(dets.size)
        for (x in dets) {
            if (FaceMath.iod(x.kps) < MIN_IOD_RECOG) {
                obs += Obs(x.box, null, 0f)
                continue
            }
            val chip = chipOf(frame, x.kps)
            val sharp = Aligner.sharpness(chip, ArcFace.SIZE)
            obs += Obs(x.box, a.embed(chip), FaceMath.quality(x.kps, x.score, sharp), FaceMath.clarity(sharp), chip)
        }
        val tracker = trackers.getOrPut(cam.id) { FaceTracker(4_000) { (it as? Bitmap)?.recycle() } }
        val now = System.currentTimeMillis()
        val upd = tracker.update(obs, now)
        upd.expired.forEach { trackEvent.remove(it.id) }

        val names = FaceDb.people.value.associate { it.id to it.name }
        val decided = HashSet<Long>()
        val boxes = ArrayList<Box>(obs.size)
        for ((i, t) in upd.assigned.withIndex()) {
            if (t.hits > 0 && decided.add(t.id)) decide(cam, frame, t, now)
            val b = obs[i].box
            val nr = RectF(
                (b[0] - content.left) / content.width(),
                (b[1] - content.top) / content.height(),
                (b[2] - content.left) / content.width(),
                (b[3] - content.top) / content.height(),
            )
            val pid = t.personId
            val label = when {
                pid != null -> names[pid] ?: "?"
                t.unknown -> "Незнакомый"
                else -> null
            }
            boxes += Box(nr, label, pid != null)
        }
        live.value = live.value + (cam.id to Live(now, boxes))
    }

    /** Решение по треку: узнан / незнакомый / ещё мало данных. */
    private fun decide(cam: Camera, frame: Bitmap, t: Track, now: Long) {
        val agg = t.aggregate() ?: return
        val th = Prefs.faceStrict
        val m = FaceDb.match(agg)
        val need = FaceMath.threshold(th, t.clarity, t.hits)
        val person = m.person
        if (person != null && m.best >= need && m.margin >= MARGIN) {
            val changed = t.personId != person.id
            t.personId = person.id
            t.score = m.best
            t.unknown = false
            if (changed) onKnown(cam, frame, t, person, m.best, now)
            if (!t.learned && Prefs.faceAutoLearn && t.hits >= 3 && m.best >= th + 0.10f && m.margin >= 0.12f) {
                t.learned = true
                learn(t, person)
            }
            return
        }
        // гистерезис: уже узнанного «отпускаем» только при явном падении сходства
        if (t.personId != null && m.best >= th - 0.06f) return
        t.personId = null
        t.score = m.best
        val enough = t.hits >= 3 || (t.hits >= 2 && now - t.born >= 1_500)
        if (enough && t.bestQuality >= UNKNOWN_MIN_Q) {
            t.unknown = true
            if (!t.eventDone) {
                t.eventDone = true
                onUnknown(cam, frame, t, agg, m.best, now)
            }
        }
    }

    private fun onKnown(cam: Camera, frame: Bitmap, t: Track, person: Person, score: Float, now: Long) {
        // этот трек раньше записали как незнакомого — исправляем то событие
        trackEvent[t.id]?.let { evId ->
            FaceDb.setEventPerson(evId, person.id, score)
            t.eventDone = true
            return
        }
        val k = cam.id + "|" + person.id
        if (now - (lastKnown[k] ?: 0L) < 5 * 60_000L) {
            t.eventDone = true
            return
        }
        lastKnown[k] = now
        t.eventDone = true
        createEvent(cam, frame, t, person, score, now)
    }

    private fun onUnknown(cam: Camera, frame: Bitmap, t: Track, agg: FloatArray, score: Float, now: Long) {
        recentUnknown.removeAll { now - it.first > 3 * 60_000L }
        if (recentUnknown.any { FaceMath.dot(it.second, agg) >= Prefs.faceStrict - 0.05f }) return
        recentUnknown += now to agg
        createEvent(cam, frame, t, null, score, now)
    }

    /** Добавляет хороший кадр уверенно узнанного человека в его образцы (если он что-то новое). */
    private fun learn(t: Track, person: Person) {
        val b = t.best ?: return
        val e = b.emb ?: return
        val chip = b.payload as? Bitmap ?: return
        if (b.quality < 0.55f || chip.isRecycled) return
        val fresh = FaceDb.people.value.firstOrNull { it.id == person.id } ?: return
        if (fresh.samples.any { FaceMath.dot(it.emb, e) >= 0.85f }) return
        FaceDb.addSamples(person.id, listOf(Sample(e, FaceDb.saveJpeg(chip, 92), auto = true)))
    }

    // ---------- события и уведомления ----------

    private fun createEvent(cam: Camera, frame: Bitmap, t: Track, person: Person?, score: Float, now: Long) {
        val chip = t.best?.payload as? Bitmap ?: return
        if (chip.isRecycled) return
        val agg = t.aggregate() ?: return
        val box = t.box
        val scale = min(1f, 960f / frame.width)
        val snap = Bitmap.createBitmap((frame.width * scale).toInt(), (frame.height * scale).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(snap).apply {
            drawBitmap(frame, Matrix().apply { postScale(scale, scale) }, Paint(Paint.FILTER_BITMAP_FLAG))
            val pnt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f
                color = if (person != null) Color.rgb(52, 211, 153) else Color.rgb(255, 77, 94)
            }
            drawRect(box[0] * scale, box[1] * scale, box[2] * scale, box[3] * scale, pnt)
        }
        val ev = FaceEvent(
            UUID.randomUUID().toString(), now, cam.id, person?.id, score, agg,
            FaceDb.saveJpeg(snap, 80), FaceDb.saveJpeg(chip, 92),
        )
        FaceDb.addEvent(ev)
        if (person == null) trackEvent[t.id] = ev.id

        if (person == null && Prefs.recordOnUnknown) RecordService.recordFor(app, cam.id, 2 * 60_000L)
        if ((person == null && Prefs.notifyUnknown) || (person != null && Prefs.notifyKnown)) notify(cam, person, snap, chip)
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

    // ---------- добавление людей по фото ----------

    sealed class Enroll {
        class Ok(val emb: FloatArray, val chip: Bitmap) : Enroll()
        class Fail(val reason: String) : Enroll()
    }

    /** Отпечаток самого крупного лица на фото (для добавления человека из галереи). */
    suspend fun enroll(photo: Bitmap): Enroll = suspendCancellableCoroutine { cont ->
        exec.execute {
            val res: Enroll = runCatching {
                val (d, a) = models() ?: return@runCatching Enroll.Fail("нейросеть не загрузилась")
                var dets = d.detect(photo, Rect(0, 0, photo.width, photo.height), DET_TH)
                if (dets.isEmpty() && max(photo.width, photo.height) > 1200) dets = detectAll(d, photo)
                val f = dets.maxByOrNull { it.area } ?: return@runCatching Enroll.Fail("лицо не найдено")
                when {
                    FaceMath.iod(f.kps) < MIN_IOD_ENROLL -> Enroll.Fail("лицо слишком мелкое")
                    FaceMath.yaw(f.kps) > 0.75f -> Enroll.Fail("лицо в профиль")
                    else -> {
                        val chip = chipOf(photo, f.kps)
                        Enroll.Ok(a.embed(chip), chip)
                    }
                }
            }.getOrElse { Enroll.Fail(it.message ?: "ошибка") }
            cont.resume(res)
        }
    }

    // ---------- пересчёт старой базы новой нейросетью ----------

    /**
     * Отпечаток из сохранённой картинки лица (старые события и миниатюры):
     * ищем лицо заново; если не нашлось — берём известное положение глаз в старой вырезке.
     */
    private fun reembed(d: Scrfd, a: ArcFace, name: String): FloatArray? {
        val f = FaceDb.img(name)
        if (!f.exists()) return null
        val src = BitmapFactory.decodeFile(f.path) ?: return null
        val pad = src.width / 5
        val padded = Bitmap.createBitmap(src.width + 2 * pad, src.height + 2 * pad, Bitmap.Config.ARGB_8888)
        Canvas(padded).apply {
            drawColor(Color.BLACK)
            drawBitmap(src, pad.toFloat(), pad.toFloat(), null)
        }
        return try {
            val dets = d.detect(padded, Rect(0, 0, padded.width, padded.height), 0.3f)
            val best = dets.maxByOrNull { it.area }
            val chip = if (best != null) {
                chipOf(padded, best.kps)
            } else {
                // старая вырезка 160×160: глаза на линии y = 65, x = 55 и 105
                val kx = src.width / 160f
                val ky = src.height / 160f
                val eyes = floatArrayOf(pad + 55f * kx, pad + 65f * ky, pad + 105f * kx, pad + 65f * ky)
                Aligner.chip(padded, eyes, CHIP, CHIP_T.copyOf(4))
            }
            val e = a.embed(chip)
            chip.recycle()
            e
        } finally {
            padded.recycle()
            src.recycle()
        }
    }

    private fun migrate() {
        val (d, a) = models() ?: return
        val people0 = FaceDb.people.value
        val events0 = FaceDb.events.value
        val todo = events0.count { it.ver == 1 } + people0.size
        var done = 0
        migration.value = 0 to todo
        try {
            val events = events0.map { e ->
                if (e.ver != 1) e else {
                    val emb = runCatching { reembed(d, a, e.face) }.getOrNull()
                    migration.value = ++done to todo
                    if (emb != null) e.with(emb = emb, ver = FaceDb.VER) else e.with(ver = 0)
                }
            }
            val people = people0.map { p ->
                val samples = ArrayList(p.samples)
                p.thumb?.let { th -> runCatching { reembed(d, a, th) }.getOrNull()?.let { samples += Sample(it, th) } }
                events.filter { it.personId == p.id }.forEach { e -> e.arc?.let { samples += Sample(it, e.face) } }
                migration.value = ++done to todo
                val uniq = ArrayList<Sample>()
                samples.forEach { s -> if (uniq.none { FaceMath.dot(it.emb, s.emb) > 0.97f }) uniq += s }
                p.with(samples = uniq)
            }
            FaceDb.replaceAll(people, events)
        } catch (t: Throwable) {
            Log.e(TAG, "migrate", t)
        } finally {
            migration.value = null
        }
    }
}
