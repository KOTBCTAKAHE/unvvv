package com.unvvv.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.unvvv.app.data.Camera
import com.unvvv.app.data.CameraRepo
import com.unvvv.app.data.Prefs
import com.unvvv.app.scan.Found
import com.unvvv.app.scan.Scanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val found = remember { mutableStateMapOf<String, Found>() }
    var scanning by remember { mutableStateOf(true) }
    var round by remember { mutableIntStateOf(0) }
    var noWifi by remember { mutableStateOf(false) }
    val cams by CameraRepo.cameras.collectAsState()
    var addOne by remember { mutableStateOf<Found?>(null) }
    var addAll by remember { mutableStateOf(false) }

    LaunchedEffect(round) {
        scanning = true
        noWifi = Scanner.localNet(ctx) == null
        Scanner.scan(ctx) { f ->
            withContext(Dispatchers.Main) {
                found[f.ip] = found[f.ip]?.merge(f) ?: f
            }
        }
        scanning = false
    }

    val existing = cams.map { it.host }.toSet()
    val list = found.values.sortedWith(
        compareBy<Found>({ !it.onvif }, { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 })
    )
    val fresh = list.filter { it.ip !in existing }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Поиск камер") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(enabled = !scanning, onClick = { round++ }) { Icon(Icons.Default.Refresh, "Повторить") }
                },
            )
        },
        bottomBar = {
            if (fresh.isNotEmpty() && !scanning) {
                Button(
                    onClick = { addAll = true },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                ) { Text("Добавить все новые (${fresh.size})") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (noWifi) {
                Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        "Телефон не подключён к Wi-Fi. Камеры ищутся только в локальной сети — подключитесь к той же сети, что и камеры.",
                        Modifier.padding(12.dp),
                    )
                }
            }
            Text(
                when {
                    scanning -> "Ищу камеры (ONVIF и порт RTSP 554)… Найдено: ${list.size}"
                    list.isEmpty() -> "Ничего не найдено. Проверьте, что телефон в той же сети, что и камеры, или добавьте камеру вручную по IP."
                    else -> "Найдено устройств: ${list.size}. Нажмите на устройство, чтобы добавить."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.ip }) { f ->
                    val added = f.ip in existing
                    ListItem(
                        headlineContent = { Text(f.name ?: "Устройство") },
                        supportingContent = { Text(listOfNotNull(f.ip, f.model).joinToString(" · ")) },
                        leadingContent = { Icon(Icons.Default.Videocam, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            if (added) {
                                Icon(Icons.Default.CheckCircle, "Добавлена", tint = MaterialTheme.colorScheme.primary)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (f.onvif) AssistChip(onClick = { addOne = f }, label = { Text("ONVIF") })
                                    if (f.rtsp) AssistChip(onClick = { addOne = f }, label = { Text("RTSP") })
                                }
                            }
                        },
                        modifier = Modifier.clickable { addOne = f },
                    )
                }
            }
        }
    }

    addOne?.let { f ->
        CameraDialog(null, onDismiss = { addOne = null }, presetHost = f.ip, presetName = f.title())
    }

    if (addAll) {
        var user by remember { mutableStateOf(Prefs.lastUser) }
        var pass by remember { mutableStateOf(Prefs.lastPass) }
        AlertDialog(
            onDismissRequest = { addAll = false },
            title = { Text("Добавить ${fresh.size} устр.") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Логин и пароль камер (одинаковые для всех). Потом можно изменить у каждой камеры долгим нажатием.")
                    OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true)
                    OutlinedTextField(
                        pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.lastUser = user.trim()
                    Prefs.lastPass = pass
                    CameraRepo.addAll(fresh.map { Camera(name = it.title(), host = it.ip, user = user.trim(), pass = pass) })
                    addAll = false
                    onBack()
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { addAll = false }) { Text("Отмена") } },
        )
    }
}
