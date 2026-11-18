package com.unvvv.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unvvv.app.data.Camera
import com.unvvv.app.data.CameraRepo
import com.unvvv.app.data.Prefs
import com.unvvv.app.record.RecordService
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamerasScreen(onOpen: (String) -> Unit, onScan: () -> Unit) {
    val cams by CameraRepo.cameras.collectAsState()
    val recording by RecordService.active.collectAsState()
    var cols by remember { mutableIntStateOf(Prefs.gridCols) }
    var edit by remember { mutableStateOf<Camera?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    val ctx = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = runCatching {
            ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            Toast.makeText(ctx, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
        } else {
            importText = text
            importing = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Камеры") },
                actions = {
                    IconButton(onClick = { importText = ""; importing = true }) {
                        Icon(Icons.Default.UploadFile, "Импорт списка")
                    }
                    IconButton(onClick = onScan) { Icon(Icons.Default.Search, "Поиск в сети") }
                    IconButton(onClick = {
                        cols = if (cols >= 3) 1 else cols + 1
                        Prefs.gridCols = cols
                    }) { Icon(Icons.Default.GridView, "Сетка") }
                },
            )
        },
        floatingActionButton = {
            if (cams.isNotEmpty()) FloatingActionButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, "Добавить")
            }
        },
    ) { pad ->
        if (cams.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Default.Videocam, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(16.dp))
                Text("Камер пока нет", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Найдите камеры UNV в вашей Wi-Fi сети или добавьте по IP-адресу.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Search, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Найти камеры в сети")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Добавить вручную")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { importText = ""; importing = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Импорт списком (ip:пароль)")
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(cams, key = { it.id }, contentType = { "cam" }) { c ->
                    CameraTile(
                        cam = c,
                        sub = true,
                        compact = cols > 2,
                        rec = c.id in recording,
                        onClick = { onOpen(c.id) },
                        onLong = { edit = c },
                    )
                }
            }
        }
    }

    if (adding) CameraDialog(null, onDismiss = { adding = false })
    edit?.let { CameraDialog(it, onDismiss = { edit = null }) }

    if (importing) {
        ImportDialog(
            initial = importText,
            onPickFile = { filePicker.launch("*/*") },
            onImport = { text ->
                val r = CameraRepo.importText(text)
                importing = false
                RecordService.ensure(ctx)
                val msg = when {
                    r.added == 0 && r.skipped == 0 -> "Ничего не найдено. Формат строки: ip:пароль"
                    r.added == 0 -> "Все ${r.skipped} уже добавлены"
                    r.skipped > 0 -> "Добавлено ${r.added}, пропущено (дубли) ${r.skipped}"
                    else -> "Добавлено камер: ${r.added}"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            },
            onDismiss = { importing = false },
        )
    }
}

/** Быстрый импорт списка камер: вставить текст или выбрать .txt-файл. Формат строки — `ip:пароль`. */
@Composable
private fun ImportDialog(
    initial: String,
    onPickFile: () -> Unit,
    onImport: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    val preview = remember(text) { com.unvvv.app.data.parseCameraList(text).distinctBy { it.host } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Импорт списка камер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Одна строка — одна камера, формат ip:пароль. " +
                        "Логин admin, порт 554, канал 1 — по умолчанию UNV. " +
                        "Пустые строки и строки с «#» пропускаются.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                OutlinedButton(onClick = onPickFile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.UploadFile, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Выбрать файл .txt")
                }
                OutlinedTextField(
                    text, { text = it },
                    label = { Text("Список") },
                    placeholder = { Text("192.168.1.10:parol123\n192.168.1.11:parol123") },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                )
                Text(
                    if (preview.isEmpty()) "Распознано камер: 0"
                    else "Распознано камер: ${preview.size} (первая: ${preview.first().host})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = preview.isNotEmpty(), onClick = { onImport(text) }) {
                Text("Добавить (${preview.size})")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CameraTile(cam: Camera, sub: Boolean, compact: Boolean, rec: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
    ) {
        if (LocalVideoActive.current) LiveVideo(cam, sub, Modifier.fillMaxSize(), compact = compact)
        Box(Modifier.matchParentSize().combinedClickable(onClick = onClick, onLongClick = onLong))
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (rec) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(RecRed))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                cam.name,
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Добавление / редактирование камеры. Долгое нажатие на камеру открывает этот диалог. */
@Composable
fun CameraDialog(
    cam: Camera?,
    onDismiss: () -> Unit,
    presetHost: String = "",
    presetName: String = "",
) {
    val ctx = LocalContext.current
    val newId = remember { UUID.randomUUID().toString() }
    var name by remember { mutableStateOf(cam?.name ?: presetName) }
    var host by remember { mutableStateOf(cam?.host ?: presetHost) }
    var user by remember { mutableStateOf(cam?.user ?: Prefs.lastUser) }
    var pass by remember { mutableStateOf(cam?.pass ?: Prefs.lastPass) }
    var port by remember { mutableStateOf((cam?.port ?: 554).toString()) }
    var channel by remember { mutableStateOf((cam?.channel ?: 1).toString()) }
    var count by remember { mutableStateOf("1") }
    var custom by remember { mutableStateOf(cam?.customUrl ?: "") }
    var record by remember { mutableStateOf(cam?.record ?: false) }
    var showPass by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(cam?.customUrl?.isNotBlank() == true) }
    var preview by remember { mutableStateOf<Camera?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun build(ch: Int = channel.toIntOrNull() ?: 1, nm: String = name) = Camera(
        id = cam?.id ?: newId,
        name = nm.trim().ifBlank { "Камера ${host.trim()}" },
        host = host.trim(),
        port = port.toIntOrNull() ?: 554,
        user = user.trim(),
        pass = pass,
        channel = ch,
        customUrl = custom.trim(),
        record = record,
    )

    val valid = host.isNotBlank() || custom.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (cam == null) "Новая камера" else "Камера") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                preview?.let {
                    LiveVideo(
                        it, sub = true,
                        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                        compact = true,
                    )
                }
                OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    host, { host = it }, label = { Text("IP-адрес") }, singleLine = true,
                    placeholder = { Text("192.168.1.13") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
                    visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showPass = !showPass }) {
                            Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) }, label = { Text("RTSP порт") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        channel, { channel = it.filter(Char::isDigit) }, label = { Text("Канал") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                if (cam == null) {
                    OutlinedTextField(
                        count, { count = it.filter(Char::isDigit) },
                        label = { Text("Сколько каналов добавить (NVR)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(record, { record = it })
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Постоянная запись")
                        Text(
                            "Пишет в фоне, даже когда приложение закрыто",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                TextButton(onClick = { advanced = !advanced }) {
                    Text(if (advanced) "Скрыть свой адрес" else "Указать свой RTSP-адрес")
                }
                if (advanced) {
                    OutlinedTextField(
                        custom, { custom = it }, label = { Text("RTSP URL (необязательно)") },
                        placeholder = { Text("rtsp://192.168.1.13:554/unicast/c1/s0/live") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (valid) {
                    Text(
                        build().url(false),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                Prefs.lastUser = user.trim()
                Prefs.lastPass = pass
                val n = if (cam == null) (count.toIntOrNull() ?: 1).coerceIn(1, 64) else 1
                if (n == 1) {
                    CameraRepo.upsert(build())
                } else {
                    val first = channel.toIntOrNull() ?: 1
                    val base = name.trim().ifBlank { "NVR ${host.trim()}" }
                    CameraRepo.addAll((0 until n).map { i ->
                        build(first + i, "$base · канал ${first + i}").copy(id = UUID.randomUUID().toString())
                    })
                }
                RecordService.ensure(ctx)
                onDismiss()
            }) { Text("Сохранить") }
        },
        dismissButton = {
            Row {
                if (cam != null) TextButton(onClick = { confirmDelete = true }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
                TextButton(enabled = valid, onClick = { preview = build() }) { Text("Проверить") }
            }
        },
    )

    if (confirmDelete && cam != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить «${cam.name}»?") },
            text = { Text("Записи этой камеры останутся в разделе «Записи».") },
            confirmButton = {
                TextButton(onClick = {
                    CameraRepo.remove(cam.id)
                    RecordService.manual.value = RecordService.manual.value - cam.id
                    confirmDelete = false
                    onDismiss()
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } },
        )
    }
}
