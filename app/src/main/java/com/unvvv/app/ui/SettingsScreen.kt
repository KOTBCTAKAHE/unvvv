package com.unvvv.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.unvvv.app.data.Prefs
import com.unvvv.app.record.RecordService
import com.unvvv.app.record.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    var gb by remember { mutableIntStateOf(Prefs.storageGb) }
    var seg by remember { mutableIntStateOf(Prefs.segmentMin) }
    var lat by remember { mutableIntStateOf(Prefs.latency) }
    var used by remember { mutableLongStateOf(0L) }
    var free by remember { mutableLongStateOf(0L) }
    val active by RecordService.active.collectAsState()

    LaunchedEffect(gb) {
        withContext(Dispatchers.IO) {
            Storage.cleanup(ctx)
            used = Storage.usedBytes(ctx)
            free = Storage.freeBytes(ctx)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Настройки") }) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Видео") {
                Text("Задержка / плавность", color = MaterialTheme.colorScheme.secondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Минимальная задержка", "Баланс", "Максимально плавно").forEachIndexed { i, t ->
                        FilterChip(lat == i, { lat = i; Prefs.latency = i }, label = { Text(t) })
                    }
                }
                Text(
                    "Если видео подтормаживает или рассыпается — выберите «Максимально плавно». Применяется при следующем открытии камеры.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Section("Место под записи") {
                Text("Занято: ${fmtSize(used)} · свободно на телефоне: ${fmtSize(free)}", color = MaterialTheme.colorScheme.secondary)
                Text("Когда лимит заполнен, самые старые записи удаляются автоматически.", style = MaterialTheme.typography.labelSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 4, 8, 16, 32, 64, 128, 256).forEach { v ->
                        FilterChip(gb == v, { gb = v; Prefs.storageGb = v }, label = { Text("$v ГБ") })
                    }
                }
            }

            Section("Длина одного файла записи") {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(5, 10, 15, 30, 60).forEach { v ->
                        FilterChip(seg == v, { seg = v; Prefs.segmentMin = v }, label = { Text("$v мин") })
                    }
                }
            }

            Section("Запись") {
                Text(
                    if (active.isEmpty()) "Сейчас ничего не пишется." else "Сейчас пишется камер: ${active.size}",
                    color = MaterialTheme.colorScheme.secondary,
                )
                Button(
                    onClick = { RecordService.stopAll() },
                    enabled = active.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Остановить всю запись") }
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    Text(
                        "Чтобы телефон не останавливал запись в фоне, разрешите работу без ограничений батареи.",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedButton(onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text("Отключить ограничение батареи") }
                }
            }

            Section("Подсказки") {
                Text(
                    "• Долгое нажатие на камеру — изменить, включить постоянную запись или удалить.\n" +
                        "• В полноэкранном режиме: щипок — зум, двойной тап — приблизить, HD/SD — качество.\n" +
                        "• Стандартный адрес UNV: rtsp://IP:554/unicast/c1/s0/live (s1 — лёгкий поток).\n" +
                        "• Для регистратора (NVR) укажите номер канала или сразу количество каналов.\n" +
                        "• Если камера не показывает — проверьте логин/пароль и что телефон в той же сети.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            val ver = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
            Text("Версия $ver", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
