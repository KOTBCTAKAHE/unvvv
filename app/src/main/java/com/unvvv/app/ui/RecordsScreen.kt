package com.unvvv.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.unvvv.app.data.CameraRepo
import com.unvvv.app.record.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.videolan.libvlc.util.VLCVideoLayout

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordsScreen(filter: String?, onFilter: (String?) -> Unit, onPlay: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val cams by CameraRepo.cameras.collectAsState()
    var recs by remember { mutableStateOf<List<Storage.Rec>?>(null) }
    var used by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    var toDelete by remember { mutableStateOf<Storage.Rec?>(null) }

    LaunchedEffect(tick) {
        val (l, u) = withContext(Dispatchers.IO) { Storage.list(ctx) to Storage.usedBytes(ctx) }
        recs = l
        used = u
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            tick++
        }
    }

    val names = cams.associate { it.id to it.name }
    val all = recs.orEmpty()
    val chipIds = (cams.map { it.id } + all.map { it.camId }).distinct().filter { id -> all.any { it.camId == id } }
    val shown = all.filter { filter == null || it.camId == filter }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            AppHeader(
                title = "Записи",
                subtitle = "${fmtSize(used)} · файлов: ${shown.size}",
            ) {
                HeaderAction(Icons.Default.Refresh, "Обновить", onClick = { tick++ })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (chipIds.isNotEmpty()) {
                ChoiceRow(
                    options = listOf<String?>(null) + chipIds,
                    selected = filter,
                    label = { id -> if (id == null) "Все камеры" else names[id] ?: "Удалённая" },
                    onSelect = onFilter,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                    padding = 16.dp,
                )
            }
            when {
                recs == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                shown.isEmpty() -> Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyState(
                        Icons.Default.VideoLibrary,
                        "Записей пока нет",
                        "Постоянная запись: долгое нажатие на камеру → «Постоянная запись».\nРазовая: откройте камеру и нажмите красную кнопку ●.",
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    shown.groupBy { fmtDay(it.start) }.forEach { (day, dayRecs) ->
                        item(key = "h_$day") {
                            SectionLabel(day) {
                                Text(
                                    "${dayRecs.size} · ${fmtSize(dayRecs.sumOf { it.size })}",
                                    style = MaterialTheme.typography.labelSmall.merge(Ui.Tabular),
                                    color = Ui.Muted,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                        items(dayRecs, key = { it.file.path }) { r ->
                            RecordRow(
                                r = r,
                                camName = names[r.camId] ?: "Удалённая камера",
                                onPlay = { onPlay(r.camId, r.file.path) },
                                onDelete = { toDelete = r },
                            )
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { r ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить запись?") },
            text = { Text("${fmtDay(r.start)}, ${fmtClock(r.start)} · ${fmtSize(r.size)}") },
            confirmButton = {
                TextButton(onClick = {
                    Storage.delete(r)
                    toDelete = null
                    tick++
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun RecordRow(r: Storage.Rec, camName: String, onPlay: () -> Unit, onDelete: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, Ui.Line, shape)
            .clickable(onClick = onPlay)
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 62.dp, height = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.linearGradient(listOf(Ui.Accent.copy(alpha = 0.28f), Ui.Accent2.copy(alpha = 0.16f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayArrow, null, tint = Ui.Accent, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${fmtClock(r.start)} – ${fmtClock(r.end)}",
                style = MaterialTheme.typography.titleSmall.merge(Ui.Tabular),
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "$camName · ${fmtSize(r.size)}",
                style = MaterialTheme.typography.bodySmall,
                color = Ui.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Pill(fmtDur(r.end - r.start), Ui.Accent)
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Удалить", tint = Ui.Muted) }
    }
}

private val speeds = listOf(1f, 2f, 4f, 8f, 16f, 0.5f)

@Composable
fun PlayerScreen(camId: String, path: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val loaded by produceState<List<Storage.Rec>?>(null, camId) {
        value = withContext(Dispatchers.IO) { Storage.list(ctx).filter { it.camId == camId }.sortedBy { it.start } }
    }
    val list = loaded
    if (list == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var index by remember(list) { mutableIntStateOf(list.indexOfFirst { it.file.path == path }.coerceAtLeast(0)) }
    val rec = list.getOrNull(index)
    if (rec == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val camName = CameraRepo.get(camId)?.name ?: "Камера"
    val fp = remember { FilePlayer() }
    DisposableEffect(fp) { onDispose { fp.release() } }
    LaunchedEffect(rec.file.path) { fp.open(rec.file) }
    LaunchedEffect(fp.ended) { if (fp.ended && index < list.lastIndex) index++ }

    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(controls, poke, fp.playing) {
        if (controls && fp.playing) {
            delay(4000)
            controls = false
        }
    }
    Immersive()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { c -> VLCVideoLayout(c).also { fp.attach(it) } }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = { o -> fp.seekBy(if (o.x < size.width / 2) -10_000 else 10_000) },
                    )
                }
        )
        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .safeDrawingPadding()
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(camName, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(
                            "${fmtDayShort(rec.start + fp.time)} · файл ${index + 1} из ${list.size}",
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(onClick = {
                        runCatching {
                            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", rec.file)
                            val send = Intent(Intent.ACTION_SEND)
                                .setType("video/mp2t")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            ctx.startActivity(Intent.createChooser(send, "Отправить запись"))
                        }.onFailure { Toast.makeText(ctx, "Не удалось отправить", Toast.LENGTH_SHORT).show() }
                    }) { Icon(Icons.Default.Share, "Отправить", tint = Color.White) }
                }

                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .safeDrawingPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val len = fp.length.coerceAtLeast(1L).toFloat()
                    Slider(
                        value = (dragging ?: fp.time.toFloat()).coerceIn(0f, len),
                        onValueChange = { dragging = it; poke++ },
                        onValueChangeFinished = {
                            dragging?.let { fp.seek(it.toLong()) }
                            dragging = null
                        },
                        valueRange = 0f..len,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${fmtDur(dragging?.toLong() ?: fp.time)} / ${fmtDur(fp.length)}",
                            color = Color.White, style = MaterialTheme.typography.labelMedium,
                        )
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(enabled = index > 0, onClick = { index--; poke++ }) {
                                Icon(Icons.Default.SkipPrevious, "Предыдущий файл", tint = Color.White)
                            }
                            IconButton(onClick = { fp.seekBy(-10_000); poke++ }) { Icon(Icons.Default.Replay10, "-10 с", tint = Color.White) }
                            IconButton(onClick = {
                                if (fp.ended) fp.open(rec.file) else fp.toggle()
                                poke++
                            }, modifier = Modifier.size(60.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) {
                                Icon(
                                    if (fp.playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Пауза",
                                    tint = Color.White, modifier = Modifier.size(40.dp),
                                )
                            }
                            IconButton(onClick = { fp.seekBy(10_000); poke++ }) { Icon(Icons.Default.Forward10, "+10 с", tint = Color.White) }
                            IconButton(enabled = index < list.lastIndex, onClick = { index++; poke++ }) {
                                Icon(Icons.Default.SkipNext, "Следующий файл", tint = Color.White)
                            }
                        }
                        TextButton(onClick = {
                            val i = speeds.indexOf(fp.rate)
                            fp.applyRate(speeds[(i + 1) % speeds.size])
                            poke++
                        }) {
                            val r = fp.rate
                            Text(
                                if (r < 1f) "${r}x" else "${r.toInt()}x",
                                color = Color.White, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}
