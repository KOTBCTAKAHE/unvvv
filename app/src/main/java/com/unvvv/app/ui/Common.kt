package com.unvvv.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** false, когда приложение свёрнуто — тогда видео не грузим. */
val LocalVideoActive = compositionLocalOf { true }

private val Colors = darkColorScheme(
    primary = Color(0xFF5CE1E6),
    onPrimary = Color(0xFF02181B),
    primaryContainer = Color(0xFF0F3A3F),
    onPrimaryContainer = Color(0xFFB4F6F8),
    secondary = Color(0xFF8B98A9),
    onSecondary = Color(0xFF07090D),
    secondaryContainer = Color(0xFF113238),
    onSecondaryContainer = Color(0xFFB4F6F8),
    tertiary = Color(0xFFA78BFA),
    onTertiary = Color(0xFF160B33),
    background = Color(0xFF07090D),
    onBackground = Color(0xFFE8EDF3),
    surface = Color(0xFF0B0F15),
    onSurface = Color(0xFFE8EDF3),
    surfaceVariant = Color(0xFF18202B),
    onSurfaceVariant = Color(0xFF9AA7B8),
    surfaceContainerLowest = Color(0xFF07090D),
    surfaceContainerLow = Color(0xFF0D1218),
    surfaceContainer = Color(0xFF111720),
    surfaceContainerHigh = Color(0xFF161E29),
    surfaceContainerHighest = Color(0xFF1E2835),
    outline = Color(0xFF2E3A4A),
    outlineVariant = Color(0xFF1E2733),
    error = Color(0xFFFF4D5E),
    onError = Color.White,
    scrim = Color(0xCC000000),
)

private val Type = Typography().run {
    copy(
        headlineLarge = headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = AppShapes, content = content)
}

val RecRed = Color(0xFFFF4D5E)

/** Полноэкранный режим без системных панелей + экран не гаснет. */
@Composable
fun Immersive() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val w = (view.context as Activity).window
        val c = WindowCompat.getInsetsController(w, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            c.show(WindowInsetsCompat.Type.systemBars())
            w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private val ru = Locale.forLanguageTag("ru")

fun fmtSize(b: Long): String = when {
    b >= 1024L * 1024 * 1024 -> String.format(ru, "%.1f ГБ", b / 1024.0 / 1024 / 1024)
    b >= 1024L * 1024 -> String.format(ru, "%.0f МБ", b / 1024.0 / 1024)
    else -> String.format(ru, "%.0f КБ", b / 1024.0)
}

fun fmtClock(t: Long): String = SimpleDateFormat("HH:mm:ss", ru).format(Date(t))
fun fmtDay(t: Long): String = SimpleDateFormat("d MMMM yyyy, EEEE", ru).format(Date(t))
fun fmtDayShort(t: Long): String = SimpleDateFormat("dd.MM HH:mm:ss", ru).format(Date(t))

fun fmtDur(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
}
