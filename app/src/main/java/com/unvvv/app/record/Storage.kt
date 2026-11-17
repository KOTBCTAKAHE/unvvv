package com.unvvv.app.record

import android.content.Context
import com.unvvv.app.data.Camera
import com.unvvv.app.data.Prefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Записи лежат в  Android/data/com.unvvv.app/files/records/<id камеры>/ГГГГММДД_ччммсс.ts */
object Storage {
    data class Rec(val file: File, val camId: String, val start: Long, val end: Long, val size: Long)

    private fun fmt() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun root(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "records").apply { mkdirs() }

    fun newFile(ctx: Context, cam: Camera): File {
        val dir = File(root(ctx), cam.id).apply { mkdirs() }
        return File(dir, fmt().format(Date()) + ".ts")
    }

    fun list(ctx: Context): List<Rec> {
        val f = fmt()
        val out = ArrayList<Rec>()
        root(ctx).listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                if (!file.name.endsWith(".ts")) return@forEach
                val start = runCatching { f.parse(file.name.removeSuffix(".ts"))?.time }.getOrNull() ?: return@forEach
                out += Rec(file, dir.name, start, maxOf(file.lastModified(), start), file.length())
            }
        }
        out.sortByDescending { it.start }
        return out
    }

    fun usedBytes(ctx: Context): Long =
        root(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(ctx: Context): Long = root(ctx).usableSpace

    /** Удаляет самые старые файлы, если превышен лимит или на телефоне мало места. */
    @Synchronized
    fun cleanup(ctx: Context) {
        val limit = Prefs.storageGb.toLong() * 1024 * 1024 * 1024
        val minFree = 500L * 1024 * 1024
        val all = list(ctx).sortedBy { it.start }.toMutableList()
        var used = all.sumOf { it.size }
        val now = System.currentTimeMillis()
        while (all.isNotEmpty() && (used > limit || freeBytes(ctx) < minFree)) {
            val r = all.removeAt(0)
            if (now - r.file.lastModified() < 15_000) continue // файл ещё пишется
            used -= r.size
            r.file.delete()
        }
    }

    fun delete(rec: Rec) {
        rec.file.delete()
    }
}
