package com.unvvv.app.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.unvvv.app.data.CameraRepo
import com.unvvv.app.face.FaceEngine
import com.unvvv.app.record.RecordService
import kotlinx.coroutines.delay
import kotlin.math.min

@Composable
fun LiveScreen(camId: String, onBack: () -> Unit, onSwitch: (String) -> Unit, onRecords: () -> Unit) {
    val ctx = LocalContext.current
    val cams by CameraRepo.cameras.collectAsState()
    val cam = cams.firstOrNull { it.id == camId }
    if (cam == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val recSet by RecordService.active.collectAsState()
    val manual by RecordService.manual.collectAsState()
    var hd by remember { mutableStateOf(true) }
    var muted by remember { mutableStateOf(true) }
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableStateOf(0) }
    var scale by remember(camId) { mutableFloatStateOf(1f) }
    var offset by remember(camId) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var aspect by remember(camId) { mutableFloatStateOf(16f / 9f) }
    val liveFaces by FaceEngine.live.collectAsState()

    Immersive()
    LaunchedEffect(controls, poke) {
        if (controls) {
            delay(4000)
            controls = false
        }
    }

    val recording = cam.id in recSet
    val idx = cams.indexOfFirst { it.id == camId }

    fun clamp(o: Offset, s: Float): Offset {
        val mx = (s - 1f) * size.width / 2f
        val my = (s - 1f) * size.height / 2f
        return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size = it }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        ) {
            if (LocalVideoActive.current) {
                LiveVideo(
                    cam, sub = !hd, audio = true, muted = muted,
                    modifier = Modifier.fillMaxSize(),
                    onAspect = { aspect = it },
                )
            }
            FaceOverlay(liveFaces[cam.id], aspect)
        }
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(camId) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val s = (scale * zoom).coerceIn(1f, 8f)
                        scale = s
                        offset = if (s <= 1f) Offset.Zero else clamp(offset + pan, s)
                    }
                }
                .pointerInput(camId) {
                    detectTapGestures(
                        onTap = { controls = !controls },
                        onDoubleTap = { p ->
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                                val c = Offset(size.width / 2f, size.height / 2f)
                                offset = clamp((c - p) * 1.5f, 2.5f)
                            }
                        },
                    )
                }
        )

        if (recording || cam.faces) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .safeDrawingPadding()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (cam.faces) Pill("ЛИЦА", Ui.Violet, container = Color.Black.copy(alpha = 0.55f))
                if (recording) Pill("REC", RecRed, dot = true, pulse = true, solid = true)
            }
        }

        AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .safeDrawingPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White) }
                    Text(
                        cam.name, color = Color.White, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    Pill(
                        if (hd) "HD" else "SD",
                        if (hd) Ui.Accent else Color.White,
                        Modifier.clickable { hd = !hd; poke++ }.padding(end = 4.dp),
                        container = Color.White.copy(alpha = 0.14f),
                    )
                    if (recording || cam.faces) Spacer(Modifier.width(if (recording && cam.faces) 128.dp else 72.dp))
                }

                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))))
                )
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .safeDrawingPadding()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(40.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(40.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(enabled = cams.size > 1, onClick = { onSwitch(cams[(idx - 1 + cams.size) % cams.size].id) }) {
                        Icon(Icons.Default.ChevronLeft, "Предыдущая", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { muted = !muted; poke++ }) {
                        Icon(
                            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            "Звук", tint = Color.White,
                        )
                    }
                    FilledIconButton(
                        onClick = {
                            poke++
                            if (cam.record) {
                                Toast.makeText(ctx, "У камеры включена постоянная запись", Toast.LENGTH_SHORT).show()
                            } else {
                                RecordService.toggleManual(ctx, cam.id)
                            }
                        },
                        modifier = Modifier.size(64.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (recording) Color.White else RecRed,
                        ),
                    ) {
                        if (recording && cam.id in manual) {
                            Icon(Icons.Default.Stop, "Остановить запись", tint = RecRed, modifier = Modifier.size(32.dp))
                        } else {
                            Icon(
                                Icons.Default.FiberManualRecord, "Запись",
                                tint = if (recording) RecRed else Color.White, modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                    IconButton(onClick = onRecords) { Icon(Icons.Default.VideoLibrary, "Записи", tint = Color.White) }
                    IconButton(enabled = cams.size > 1, onClick = { onSwitch(cams[(idx + 1) % cams.size].id) }) {
                        Icon(Icons.Default.ChevronRight, "Следующая", tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                }
            }
        }
    }
}

/** Рамки лиц поверх живого видео (данные от фонового распознавания). */
@Composable
private fun FaceOverlay(data: FaceEngine.Live?, aspect: Float) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(data) {
        visible = data != null && System.currentTimeMillis() - data.time < 2500
        if (visible) {
            delay(2500)
            visible = false
        }
    }
    if (!visible || data == null || data.boxes.isEmpty()) return
    val labelPaint = remember {
        android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 34f
            isAntiAlias = true
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val va = if (aspect > 0f) aspect else 16f / 9f
        val sa = size.width / size.height
        val cw = if (va > sa) size.width else size.height * va
        val ch = if (va > sa) size.width / va else size.height
        val ox = (size.width - cw) / 2f
        val oy = (size.height - ch) / 2f
        for (b in data.boxes) {
            val c = when {
                b.known -> Ui.Success
                b.label == null -> Color.White.copy(alpha = 0.6f)
                else -> RecRed
            }
            val l = ox + b.rect.left * cw
            val t = oy + b.rect.top * ch
            val w = (b.rect.right - b.rect.left) * cw
            val h = (b.rect.bottom - b.rect.top) * ch
            val sw = 3.dp.toPx()
            val len = min(w, h) * 0.28f
            drawRoundRect(
                c.copy(alpha = 0.22f),
                topLeft = Offset(l, t),
                size = Size(w, h),
                cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
            fun seg(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(c, Offset(x1, y1), Offset(x2, y2), sw, cap = StrokeCap.Round)
            seg(l, t, l + len, t); seg(l, t, l, t + len)
            seg(l + w, t, l + w - len, t); seg(l + w, t, l + w, t + len)
            seg(l, t + h, l + len, t + h); seg(l, t + h, l, t + h - len)
            seg(l + w, t + h, l + w - len, t + h); seg(l + w, t + h, l + w, t + h - len)
            b.label?.let { text ->
                val pad = 8.dp.toPx()
                val lh = labelPaint.textSize * 1.6f
                val top = (t - lh - 6.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(
                    c.copy(alpha = 0.9f),
                    topLeft = Offset(l, top),
                    size = Size(labelPaint.measureText(text) + pad * 2, lh),
                    cornerRadius = CornerRadius(lh / 2f),
                )
                drawContext.canvas.nativeCanvas.drawText(text, l + pad, top + lh / 2f + labelPaint.textSize * 0.35f, labelPaint)
            }
        }
    }
}
