package com.unvvv.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF00131F),
    secondary = Color(0xFF94A3B8),
    background = Color(0xFF0E1116),
    surface = Color(0xFF0E1116),
    surfaceVariant = Color(0xFF1B2028),
    surfaceContainer = Color(0xFF151A21),
    surfaceContainerHigh = Color(0xFF1B2028),
    surfaceContainerHighest = Color(0xFF232A34),
    error = Color(0xFFEF4444),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

val RecRed = Color(0xFFEF4444)

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
