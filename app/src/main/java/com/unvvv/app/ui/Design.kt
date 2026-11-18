package com.unvvv.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Фирменные цвета и градиенты. */
object Ui {
    val Bg = Color(0xFF07090D)
    val Accent = Color(0xFF5CE1E6)
    val Accent2 = Color(0xFF7C9CFF)
    val Violet = Color(0xFFA78BFA)
    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Muted = Color(0xFF8B98A9)
    val Line = Color(0xFF1E2733)
    val OnAccent = Color(0xFF03171A)
    val AccentGradient: Brush = Brush.linearGradient(listOf(Accent, Accent2))

    /** Цифры одинаковой ширины — время и размеры не «прыгают». */
    val Tabular = TextStyle(fontFeatureSettings = "tnum")
    val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, letterSpacing = 0.2.sp)
}

/** Фон приложения: почти чёрный с мягким аква-свечением сверху. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .fillMaxSize()
            .background(Ui.Bg)
            .drawBehind {
                if (size.minDimension <= 0f) return@drawBehind
                drawRect(
                    Brush.radialGradient(
                        listOf(Ui.Accent.copy(alpha = 0.11f), Color.Transparent),
                        center = Offset(size.width * 0.1f, 0f),
                        radius = size.width * 0.95f,
                    )
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(Ui.Accent2.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(size.width, size.height * 0.08f),
                        radius = size.width * 0.75f,
                    )
                )
            },
        content = content,
    )
}

/** Крупный заголовок экрана с подзаголовком и кнопками-иконками. */
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = if (onBack != null) 12.dp else 20.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            HeaderAction(Icons.AutoMirrored.Filled.ArrowBack, "Назад", onBack)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = if (onBack != null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium.merge(Ui.Tabular),
                    color = Ui.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Квадратная «стеклянная» кнопка-иконка для заголовков. */
@Composable
fun HeaderAction(
    icon: ImageVector,
    desc: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .size(44.dp)
            .clip(shape)
            .background(if (active) Ui.Accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (active) Ui.Accent.copy(alpha = 0.45f) else Ui.Line, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, desc,
            tint = when {
                !enabled -> Ui.Muted.copy(alpha = 0.4f)
                active -> Ui.Accent
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Подпись раздела: мелкие прописные буквы с разрядкой. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.SemiBold),
            color = Ui.Muted,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/** Карточка-панель с мягким градиентом и тонкой обводкой. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.surfaceContainer)
                )
            )
            .border(1.dp, Ui.Line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Цветная плашка под иконку. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = Ui.Accent, size: Dp = 40.dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f))
    }
}

/** Маленький бейдж-статус: «REC», «ЛИЦА», «ONVIF»… */
@Composable
fun Pill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = false,
    pulse: Boolean = false,
    solid: Boolean = false,
    container: Color? = null,
) {
    val a = if (pulse) {
        val t = rememberInfiniteTransition(label = "pill")
        t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "pillA").value
    } else 1f
    val fg = if (solid) Color.White else color
    Row(
        modifier
            .clip(CircleShape)
            .background(container ?: if (solid) color else color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Box(
                Modifier
                    .size(6.dp)
                    .graphicsLayer { alpha = a }
                    .clip(CircleShape)
                    .background(fg)
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp).merge(Ui.Tabular),
            maxLines = 1,
        )
    }
}

/** Пустой экран: светящаяся иконка, заголовок, текст и кнопки. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(128.dp)
                .background(Brush.radialGradient(listOf(Ui.Accent.copy(alpha = 0.24f), Color.Transparent)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(76.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(Ui.AccentGradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = Ui.OnAccent, modifier = Modifier.size(38.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ui.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** Главная кнопка с градиентом. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(16.dp)
    val fg = if (enabled) Ui.OnAccent else Ui.Muted
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (enabled) Ui.AccentGradient else SolidColor(MaterialTheme.colorScheme.surfaceContainerHighest))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = fg, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
        }
    }
}

/** Второстепенная кнопка: тёмное стекло с обводкой. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
) {
    val shape = RoundedCornerShape(16.dp)
    val base = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val fg = if (enabled) base else Ui.Muted.copy(alpha = 0.5f)
    Box(
        modifier
            .height(52.dp)
            .clip(shape)
            .background(if (color == Color.Unspecified) MaterialTheme.colorScheme.surfaceContainerHigh else color.copy(alpha = 0.10f))
            .border(1.dp, if (color == Color.Unspecified) Ui.Line else color.copy(alpha = if (enabled) 0.4f else 0.15f), shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = fg, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp))
        }
    }
}

/** Строка настройки: иконка, заголовок, пояснение и элемент справа. */
@Composable
fun SettingRow(
    title: String,
    sub: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = Ui.Accent,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon, iconTint, 38.dp)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = Ui.Muted)
        }
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

/** Горизонтальный ряд вариантов-«таблеток» с одним выбранным. */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    padding: Dp = 0.dp,
) {
    Row(
        modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = padding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .clip(shape)
                    .background(if (sel) Ui.Accent.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, if (sel) Ui.Accent.copy(alpha = 0.55f) else Ui.Line, shape)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            ) {
                Text(
                    label(o),
                    color = if (sel) Ui.Accent else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Индикатор заполненности (например, места под записи). */
@Composable
fun Meter(fraction: Float, modifier: Modifier = Modifier, color: Brush = Ui.AccentGradient) {
    val f = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        if (f > 0f) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(f.coerceAtLeast(0.02f))
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

class NavItem(val key: Int, val label: String, val icon: ImageVector)

/** Плавающая нижняя навигация-«капсула». */
@Composable
fun FloatingNavBar(items: List<NavItem>, selected: Int, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(Color(0xF2111720))
                .border(1.dp, Ui.Line, shape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                val sel = item.key == selected
                val bg by animateColorAsState(if (sel) Ui.Accent.copy(alpha = 0.15f) else Color.Transparent, label = "navBg")
                val fg by animateColorAsState(if (sel) Ui.Accent else Ui.Muted, label = "navFg")
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(20.dp))
                        .background(bg)
                        .clickable { onSelect(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, item.label, tint = fg, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        item.label,
                        color = fg,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
