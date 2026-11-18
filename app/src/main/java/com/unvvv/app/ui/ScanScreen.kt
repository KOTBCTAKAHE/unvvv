package com.unvvv.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
        containerColor = Color.Transparent,
        topBar = {
            AppHeader(
                title = "Поиск камер",
                subtitle = if (scanning) "ONVIF и RTSP 554 в вашей сети" else "Найдено устройств: ${list.size}",
                onBack = onBack,
            ) {
                HeaderAction(Icons.Default.Refresh, "Повторить", onClick = { round++ }, enabled = !scanning)
            }
        },
        bottomBar = {
            if (fresh.isNotEmpty() && !scanning) {
                PrimaryButton(
                    "Добавить все новые (${fresh.size})",
                    onClick = { addAll = true },
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                    icon = Icons.Default.Add,
                )
            }
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "radar") { Radar(scanning, list.size) }
            if (noWifi) item(key = "wifi") {
                Panel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SettingRow(
                        "Нет Wi-Fi",
                        "Камеры ищутся только в локальной сети — подключитесь к той же сети, что и камеры.",
                        Icons.Default.WifiOff,
                        Ui.Warning,
                    )
                }
            }
            item(key = "status") {
                Text(
                    when {
                        scanning -> "Сканирую сеть…"
                        list.isEmpty() -> "Ничего не найдено. Проверьте, что телефон в той же сети, что и камеры, или добавьте камеру вручную по IP."
                        else -> "Нажмите на устройство, чтобы добавить."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ui.Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            items(list, key = { it.ip }) { f ->
                val added = f.ip in existing
                val shape = RoundedCornerShape(18.dp)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .border(1.dp, if (added) Ui.Success.copy(alpha = 0.35f) else Ui.Line, shape)
                        .clickable { addOne = f }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconBadge(Icons.Default.Videocam, if (added) Ui.Success else Ui.Accent, 44.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name ?: "Устройство", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(f.ip, f.model).joinToString(" · "),
                            style = Ui.Mono,
                            color = Ui.Muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    if (added) {
                        Pill("ДОБАВЛЕНА", Ui.Success)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (f.onvif) Pill("ONVIF", Ui.Accent)
                            if (f.rtsp) Pill("RTSP", Ui.Accent2)
                        }
                    }
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

/** Анимированный «радар» поиска с числом найденных устройств. */
@Composable
private fun Radar(active: Boolean, count: Int) {
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "radarP")
    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(200.dp)) {
            val r = size.minDimension / 2f
            drawCircle(Brush.radialGradient(listOf(Ui.Accent.copy(alpha = 0.10f), Color.Transparent)), radius = r)
            for (i in 1..3) drawCircle(Ui.Accent.copy(alpha = 0.14f), radius = r * i / 3f, style = Stroke(1.dp.toPx()))
            drawLine(Ui.Accent.copy(alpha = 0.08f), Offset(0f, r), Offset(size.width, r), 1.dp.toPx())
            drawLine(Ui.Accent.copy(alpha = 0.08f), Offset(r, 0f), Offset(r, size.height), 1.dp.toPx())
            if (active) {
                rotate(p * 360f) {
                    drawArc(Ui.Accent.copy(alpha = 0.16f), startAngle = -60f, sweepAngle = 60f, useCenter = true)
                    drawLine(Ui.Accent.copy(alpha = 0.7f), center, Offset(size.width, r), 2.dp.toPx())
                }
                for (k in 0..1) {
                    val q = (p + k * 0.5f) % 1f
                    drawCircle(Ui.Accent.copy(alpha = (1f - q) * 0.35f), radius = r * q, style = Stroke(2.dp.toPx()))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$count", style = MaterialTheme.typography.headlineLarge.merge(Ui.Tabular))
            Text(if (active) "ищу…" else "найдено", style = MaterialTheme.typography.labelMedium, color = Ui.Muted)
        }
    }
}
