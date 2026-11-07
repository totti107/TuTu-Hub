package com.arena.tv.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object ArenaColors {
    val Bg = Color(0xFF060607)
    val Panel = Color(0xFF0F0F11)
    val Surface = Color(0xFF17181B)
    val SurfaceHi = Color(0xFF26272B)
    val Outline = Color(0xFF34353A)
    val Accent = Color(0xFF22C55E)
    val AccentDim = Color(0xFF14532D)
    val Live = Color(0xFFEF4444)
    val Gold = Color(0xFFFACC15)
    val Text = Color(0xFFF3F3F4)
    val Muted = Color(0xFF8E9097)
    val Blue = Color(0xFF3B82F6)
}

private val scheme = darkColorScheme(
    primary = ArenaColors.Accent,
    onPrimary = Color(0xFF04140A),
    primaryContainer = ArenaColors.AccentDim,
    onPrimaryContainer = Color(0xFFBBF7D0),
    secondary = ArenaColors.Blue,
    background = ArenaColors.Bg,
    onBackground = ArenaColors.Text,
    surface = ArenaColors.Bg,
    onSurface = ArenaColors.Text,
    surfaceVariant = ArenaColors.SurfaceHi,
    onSurfaceVariant = ArenaColors.Muted,
    surfaceContainer = ArenaColors.Surface,
    surfaceContainerHigh = ArenaColors.SurfaceHi,
    surfaceContainerHighest = Color(0xFF2E2F34),
    surfaceContainerLow = ArenaColors.Surface,
    outline = ArenaColors.Outline,
    outlineVariant = ArenaColors.Outline,
    error = ArenaColors.Live,
)

private val typography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 22.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = labelSmall.copy(letterSpacing = 0.4.sp),
        // Line height follows the font size, so big custom sizes never get clipped.
        bodyLarge = bodyLarge.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
        bodyMedium = bodyMedium.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
    )
}

val ScoreStyle = TextStyle(fontWeight = FontWeight.Black, fontSize = 20.sp)

@Composable
fun ArenaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography) {
        // Default text/icon colour everywhere (wide layouts don't sit inside a Surface/Scaffold).
        // Text colour everywhere, and a clearly visible focus outline for TV remotes.
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides ArenaColors.Text,
            androidx.compose.foundation.LocalIndication provides FocusRing,
            content = content,
        )
    }
}
