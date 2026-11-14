package com.tacomatow.monitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Instrument-cluster styling. Dark is the default because the phone lives
 * on a dash mount at night; light is tuned for midday glare.
 */

// --- Dark: charcoal dash, mint accent, warm gauges ---
private val DarkColors = darkColorScheme(
    primary = Color(0xFF5FD3A8),
    onPrimary = Color(0xFF04231A),
    primaryContainer = Color(0xFF164033),
    onPrimaryContainer = Color(0xFFB7F2DA),
    secondary = Color(0xFFE0B15C),
    onSecondary = Color(0xFF2A1E00),
    background = Color(0xFF0D1013),
    onBackground = Color(0xFFE6EAED),
    surface = Color(0xFF161B21),
    onSurface = Color(0xFFE6EAED),
    surfaceVariant = Color(0xFF212933),
    onSurfaceVariant = Color(0xFFA9B4BF),
    outline = Color(0xFF39434E),
    outlineVariant = Color(0xFF282F38),
    error = Color(0xFFFF6B5E),
    onError = Color(0xFF2A0703),
)

// --- Light: warm paper, deep green accent ---
private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6B4F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDEEDF),
    onPrimaryContainer = Color(0xFF00291D),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color.White,
    background = Color(0xFFF5F4F0),
    onBackground = Color(0xFF1A1D1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1D1F),
    surfaceVariant = Color(0xFFEDECE7),
    onSurfaceVariant = Color(0xFF565D63),
    outline = Color(0xFFC3C2BC),
    outlineVariant = Color(0xFFDFDED8),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** Semantic gauge colours, resolved per theme. */
data class TowPalette(
    val ok: Color,
    val okSurface: Color,
    val warn: Color,
    val warnSurface: Color,
    val crit: Color,
    val critSurface: Color,
    val idle: Color,
    val idleSurface: Color,
    val track: Color,
)

private val DarkPalette = TowPalette(
    ok = Color(0xFF5FD3A8),
    okSurface = Color(0xFF12261F),
    warn = Color(0xFFF0BE63),
    warnSurface = Color(0xFF2C2313),
    crit = Color(0xFFFF6B5E),
    critSurface = Color(0xFF331714),
    idle = Color(0xFF8D99A5),
    idleSurface = Color(0xFF1B222A),
    track = Color(0x33FFFFFF),
)

private val LightPalette = TowPalette(
    ok = Color(0xFF166B45),
    okSurface = Color(0xFFE4F3EA),
    warn = Color(0xFFA96A00),
    warnSurface = Color(0xFFFDF1DC),
    crit = Color(0xFFC0301F),
    critSurface = Color(0xFFFBE4E0),
    idle = Color(0xFF6E767D),
    idleSurface = Color(0xFFECEDEA),
    track = Color(0x22000000),
)

val LocalTowPalette = staticCompositionLocalOf { LightPalette }

private val TowTypography = Typography().let { base ->
    base.copy(
        displayLarge = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Light,
            fontSize = 76.sp,
            lineHeight = 78.sp,
            letterSpacing = (-3).sp,
        ),
        headlineMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 30.sp,
            lineHeight = 34.sp,
            letterSpacing = (-0.5).sp,
        ),
        headlineSmall = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp,
            lineHeight = 26.sp,
            letterSpacing = (-0.2).sp,
        ),
        titleMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            lineHeight = 22.sp,
        ),
        labelLarge = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            letterSpacing = 0.2.sp,
        ),
        // Used for gauge captions — wide tracking reads as instrumentation.
        labelMedium = TextStyle(
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 1.4.sp,
        ),
        labelSmall = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 1.1.sp,
        ),
        bodySmall = TextStyle(
            fontSize = 12.sp,
            lineHeight = 17.sp,
            letterSpacing = 0.1.sp,
        ),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    )
}

private val TowShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TacomaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalTowPalette provides if (darkTheme) DarkPalette else LightPalette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = TowTypography,
            shapes = TowShapes,
            content = content,
        )
    }
}
