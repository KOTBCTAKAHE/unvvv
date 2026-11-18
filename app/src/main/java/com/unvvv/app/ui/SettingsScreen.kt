package com.unvvv.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unvvv.app.data.Prefs
import com.unvvv.app.record.RecordService
import com.unvvv.app.record.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@SuppressLint("BatteryLife")
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

    val ver = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = { AppHeader("Настройки", subtitle = ver?.let { "Версия $it" }) },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Label("Видео")
            Panel {
                SettingRow("Задержка и плавность", "Применяется при следующем открытии камеры", Icons.Default.Speed)
                ChoiceRow(
                    options = listOf(0, 1, 2),
                    selected = lat,
                    label = { listOf("Минимальная задержка", "Баланс", "Максимально плавно")[it] },
                    onSelect = { lat = it; Prefs.latency = it },
                )
                Hint("Если видео подтормаживает или рассыпается — выберите «Максимально плавно».")
            }

            Label("Хранилище")
            Panel {
                SettingRow("Место под записи", "Старые записи удаляются автоматически", Icons.Default.Storage, Ui.Accent2)
                val limit = gb * 1024L * 1024 * 1024
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmtSize(used), style = MaterialTheme.typography.headlineSmall.merge(Ui.Tabular))
                    Text(
                        "  из $gb ГБ",
                        style = MaterialTheme.typography.bodyMedium.merge(Ui.Tabular),
                        color = Ui.Muted,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Meter(
                    if (limit > 0) used.toFloat() / limit else 0f,
                    color = if (limit > 0 && used > limit / 10 * 9) Brush.linearGradient(listOf(Ui.Warning, RecRed)) else Ui.AccentGradient,
                )
                Hint("Свободно на телефоне: ${fmtSize(free)}")
                ChoiceRow(
                    options = listOf(2, 4, 8, 16, 32, 64, 128, 256),
                    selected = gb,
                    label = { "$it ГБ" },
                    onSelect = { gb = it; Prefs.storageGb = it },
                )
            }

            Label("Файлы записи")
            Panel {
                SettingRow("Длина одного файла", "Короче — проще пересылать", Icons.Default.ContentCut, Ui.Violet)
                ChoiceRow(
                    options = listOf(5, 10, 15, 30, 60),
                    selected = seg,
                    label = { "$it мин" },
                    onSelect = { seg = it; Prefs.segmentMin = it },
                )
            }

            Label("Запись")
            Panel {
                SettingRow(
                    "Состояние",
                    if (active.isEmpty()) "Сейчас ничего не пишется" else "Пишется камер: ${active.size}",
                    Icons.Default.FiberManualRecord,
                    RecRed,
                ) {
                    if (active.isNotEmpty()) Pill("REC ${active.size}", RecRed, dot = true, pulse = true)
                }
                SecondaryButton(
                    "Остановить всю запись",
                    onClick = { RecordService.stopAll() },
                    modifier = Modifier.fillMaxWidth(),
                    icon = Icons.Default.Stop,
                    enabled = active.isNotEmpty(),
                    color = RecRed,
                )
                val pm = ctx.getSystemService(PowerManager::class.java)
                if (pm != null && !pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
                    SettingRow(
                        "Работа в фоне",
                        "Разрешите работу без ограничений батареи, чтобы телефон не останавливал запись",
                        Icons.Default.BatteryChargingFull,
                        Ui.Warning,
                    )
                    SecondaryButton("Отключить ограничение батареи", onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                            )
                        }
                    }, modifier = Modifier.fillMaxWidth(), color = Ui.Warning)
                }
            }

            Label("Подсказки")
            Panel(spacing = 12.dp) {
                SettingRow("Долгое нажатие на камеру", "Изменить, включить постоянную запись или удалить", Icons.Default.TouchApp)
                SettingRow("Полноэкранный режим", "Щипок — зум, двойной тап — приблизить, HD/SD — качество", Icons.Default.ZoomIn, Ui.Accent2)
                SettingRow("Адрес UNV", "rtsp://IP:554/unicast/c1/s0/live (s1 — лёгкий поток)", Icons.Default.Link, Ui.Violet)
                SettingRow("Регистратор (NVR)", "Укажите номер канала или сразу количество каналов", Icons.Default.Router, Ui.Success)
                SettingRow("Камера не показывает?", "Проверьте логин/пароль и что телефон в той же сети", Icons.Default.Info, Ui.Warning)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Column(Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp)) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
            color = Ui.Muted,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ui.Muted)
}
