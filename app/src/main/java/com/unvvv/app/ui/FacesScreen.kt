package com.unvvv.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.unvvv.app.data.CameraRepo
import com.unvvv.app.data.Prefs
import com.unvvv.app.face.FaceDb
import com.unvvv.app.face.FaceEngine
import com.unvvv.app.face.FaceEvent
import com.unvvv.app.face.Person
import com.unvvv.app.record.RecordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

// ---------- картинки из файлов (с кэшем) ----------

private object ImgCache {
    val cache = LruCache<String, ImageBitmap>(80)
    fun get(f: File): ImageBitmap? {
        cache.get(f.path)?.let { return it }
        val b = BitmapFactory.decodeFile(f.path)?.asImageBitmap() ?: return null
        cache.put(f.path, b)
        return b
    }
}

@Composable
fun FileImage(file: File, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val img by produceState<ImageBitmap?>(ImgCache.cache.get(file.path), file.path) {
        if (value == null) value = withContext(Dispatchers.IO) { ImgCache.get(file) }
    }
    val i = img
    if (i != null) Image(i, null, modifier, contentScale = contentScale)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
private fun Avatar(thumb: String?, size: Dp) {
    if (thumb != null) {
        FileImage(FaceDb.img(thumb), Modifier.size(size).clip(CircleShape))
    } else {
        Box(
            Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Person, null) }
    }
}

/** Загрузка фото из галереи с учётом поворота (EXIF) и уменьшением до ~1600 px. */
private fun loadPhoto(ctx: Context, uri: Uri): Bitmap? = runCatching {
    val cr = ctx.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var s = 1
    while (max(bounds.outWidth, bounds.outHeight) / s > 1600) s *= 2
    val bmp = cr.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
    } ?: return null
    val o = cr.openInputStream(uri)?.use {
        ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
    val deg = when (o) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}.getOrNull()

/** Обрабатывает фото → отпечатки лиц. Возвращает (отпечатки, файл миниатюры). */
private suspend fun processPhotos(ctx: Context, uris: List<Uri>): Pair<List<FloatArray>, String?> {
    val embs = ArrayList<FloatArray>()
    var thumb: String? = null
    for (u in uris) {
        val bmp = withContext(Dispatchers.IO) { loadPhoto(ctx, u) } ?: continue
        val r = FaceEngine.enroll(bmp) ?: continue
        embs += r.first
        if (thumb == null) thumb = withContext(Dispatchers.IO) { FaceDb.saveJpeg(r.second, 90) }
        r.second.recycle()
        bmp.recycle()
    }
    return embs to thumb
}

// ---------- экран ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacesScreen() {
    val ctx = LocalContext.current
    val cams by CameraRepo.cameras.collectAsState()
    val people by FaceDb.people.collectAsState()
    val events by FaceDb.events.collectAsState()
    val watching by RecordService.watching.collectAsState()
    var onlyUnknown by remember { mutableStateOf(false) }
    var openEvent by remember { mutableStateOf<FaceEvent?>(null) }
    var openPerson by remember { mutableStateOf<Person?>(null) }
    var addPerson by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val names = people.associate { it.id to it.name }
    val camNames = cams.associate { it.id to it.name }
    val shown = if (onlyUnknown) events.filter { it.personId == null } else events

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Лица") },
                actions = { IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Tune, "Настройки распознавания") } },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { Header("Распознавание на камерах") }
            if (cams.isEmpty()) item { Hint("Сначала добавьте камеры.") }
            items(cams, key = { "c_" + it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            when {
                                c.id in watching -> "Работает в фоне"
                                c.faces -> "Запускается…"
                                else -> "Выключено"
                            }
                        )
                    },
                    trailingContent = {
                        Switch(c.faces, { on ->
                            CameraRepo.upsert(c.copy(faces = on))
                            RecordService.ensure(ctx)
                        })
                    },
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Header("Знакомые люди (${people.size})", Modifier.weight(1f))
                    TextButton(onClick = { addPerson = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Добавить")
                    }
                }
            }
            if (people.isEmpty()) {
                item {
                    Hint("Добавьте 3–10 фото человека из галереи или отмечайте людей в событиях ниже — чем больше снимков с ваших камер, тем точнее узнавание.")
                }
            } else {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(people, key = { it.id }) { p ->
                            Column(
                                Modifier.width(72.dp).clickable { openPerson = p },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Avatar(p.thumb, 64.dp)
                                Spacer(Modifier.height(4.dp))
                                Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${p.embs.size} фото", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            }
                        }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
                    Header("События", Modifier.weight(1f))
                    FilterChip(onlyUnknown, { onlyUnknown = !onlyUnknown }, label = { Text("Незнакомые") })
                    IconButton(enabled = events.isNotEmpty(), onClick = { confirmClear = true }) {
                        Icon(Icons.Default.DeleteSweep, "Очистить")
                    }
                }
            }
            if (shown.isEmpty()) {
                item { Hint("Пока пусто. Включите распознавание на камере — здесь появятся все замеченные лица.") }
            }
            items(shown.take(300), key = { it.id }) { e ->
                val who = e.personId?.let { names[it] }
                ListItem(
                    leadingContent = { FileImage(FaceDb.img(e.face), Modifier.size(48.dp).clip(CircleShape)) },
                    headlineContent = {
                        Text(
                            who ?: "Незнакомый",
                            color = if (who == null) RecRed else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    supportingContent = {
                        Text(
                            "${camNames[e.camId] ?: "Камера"} · ${fmtDayShort(e.time)} · сходство ${(e.score * 100).roundToInt()}%",
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.clickable { openEvent = e },
                )
            }
        }
    }

    openEvent?.let { e -> EventDialog(e, people, camNames[e.camId], onDismiss = { openEvent = null }) }
    openPerson?.let { p ->
        val fresh = people.firstOrNull { it.id == p.id }
        if (fresh == null) openPerson = null else PersonDialog(fresh, onDismiss = { openPerson = null })
    }
    if (addPerson) AddPersonDialog(onDismiss = { addPerson = false })
    if (showSettings) FaceSettingsDialog(onDismiss = { showSettings = false })
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Очистить все события?") },
            text = { Text("Знакомые люди останутся.") },
            confirmButton = {
                TextButton(onClick = {
                    FaceDb.clearEvents()
                    confirmClear = false
                }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun EventDialog(e: FaceEvent, people: List<Person>, camName: String?, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var newName by remember { mutableStateOf("") }
    val current = e.personId?.let { id -> people.firstOrNull { it.id == id } }

    fun done(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current?.name ?: "Кто это?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FileImage(
                    FaceDb.img(e.snap),
                    Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                    ContentScale.Fit,
                )
                Text(
                    "${camName ?: "Камера"} · ${fmtDay(e.time)}, ${fmtClock(e.time)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (people.isNotEmpty()) {
                    Text(if (current == null) "Это:" else "Исправить на:")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(people.filter { it.id != current?.id }, key = { it.id }) { p ->
                            AssistChip(
                                onClick = {
                                    val n = FaceDb.assignEvent(e.id, p.id)
                                    done(if (n > 0) "Запомнил. Также узнал в $n событиях" else "Запомнил: ${p.name}")
                                },
                                label = { Text(p.name) },
                                leadingIcon = { Avatar(p.thumb, 20.dp) },
                            )
                        }
                    }
                }
                Text("Новый человек:")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, singleLine = true, placeholder = { Text("Имя") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = newName.isNotBlank(), onClick = {
                        val p = FaceDb.createPerson(newName, emptyList(), e.face)
                        val n = FaceDb.assignEvent(e.id, p.id)
                        done(if (n > 0) "Добавлен ${p.name}. Также узнал в $n событиях" else "Добавлен ${p.name}")
                    }) { Text("OK") }
                }
                if (current != null) {
                    OutlinedButton(onClick = {
                        FaceDb.unassignEvent(e.id)
                        done("Отметка снята")
                    }, modifier = Modifier.fillMaxWidth()) { Text("Это не ${current.name}") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        dismissButton = {
            TextButton(onClick = {
                FaceDb.deleteEvent(e.id)
                onDismiss()
            }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
        },
    )
}

@Composable
private fun AddPersonDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val (embs, thumb) = processPhotos(ctx, uris)
            busy = false
            if (embs.isEmpty()) {
                Toast.makeText(ctx, "На фото не найдено лиц", Toast.LENGTH_LONG).show()
            } else {
                FaceDb.createPerson(name, embs, thumb)
                Toast.makeText(ctx, "Добавлено: лиц ${embs.size} из ${uris.size} фото", Toast.LENGTH_LONG).show()
                onDismiss()
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Новый человек") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Выберите 3–10 фото, где хорошо видно лицо (анфас, разное освещение). Можно также отмечать людей прямо в событиях с камер.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Обрабатываю фото…")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Выбрать фото") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun PersonDialog(p: Person, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember(p.id) { mutableStateOf(p.name) }
    var busy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val (embs, thumb) = processPhotos(ctx, uris)
            busy = false
            if (embs.isEmpty()) Toast.makeText(ctx, "На фото не найдено лиц", Toast.LENGTH_LONG).show()
            else {
                FaceDb.addSamples(p.id, embs, thumb)
                Toast.makeText(ctx, "Добавлено лиц: ${embs.size}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.thumb, 40.dp)
                Spacer(Modifier.width(12.dp))
                Text(p.name)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Образцов лица: ${p.embs.size}", color = MaterialTheme.colorScheme.secondary)
                OutlinedButton(
                    enabled = !busy,
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (busy) "Обрабатываю…" else "Добавить ещё фото") }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Удалить человека", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                if (name.isNotBlank() && name != p.name) FaceDb.rename(p.id, name)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Закрыть") } },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить ${p.name}?") },
            text = { Text("События с этим человеком станут «незнакомыми».") },
            confirmButton = {
                TextButton(onClick = {
                    FaceDb.deletePerson(p.id)
                    confirmDelete = false
                    onDismiss()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun FaceSettingsDialog(onDismiss: () -> Unit) {
    var th by remember { mutableFloatStateOf(Prefs.faceThreshold) }
    var nu by remember { mutableStateOf(Prefs.notifyUnknown) }
    var nk by remember { mutableStateOf(Prefs.notifyKnown) }
    var rec by remember { mutableStateOf(Prefs.recordOnUnknown) }
    var hd by remember { mutableStateOf(Prefs.faceHd) }

    @Composable
    fun SwitchRow(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Switch(v, on)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Распознавание") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Строгость совпадения: ${(th * 100).roundToInt()}%")
                Slider(th, { th = it }, valueRange = 0.40f..0.80f, steps = 7, onValueChangeFinished = { Prefs.faceThreshold = th })
                Text(
                    "Если знакомых путает с чужими — увеличьте. Если своих не узнаёт — уменьшите или добавьте больше фото.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                SwitchRow("Уведомлять о незнакомых", "С фото кадра", nu) { nu = it; Prefs.notifyUnknown = it }
                SwitchRow("Уведомлять о знакомых", "Не чаще раза в 5 минут на человека", nk) { nk = it; Prefs.notifyKnown = it }
                SwitchRow("Запись при незнакомом", "Камера пишет 2 минуты", rec) { rec = it; Prefs.recordOnUnknown = it }
                SwitchRow("Анализ в HD", "Видит лица дальше, но сильнее греет телефон", hd) {
                    hd = it
                    Prefs.faceHd = it
                    RecordService.reload.value++
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Готово") } },
    )
}
