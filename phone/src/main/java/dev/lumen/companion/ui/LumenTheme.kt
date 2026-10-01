package dev.lumen.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.companion.R

/**
 * The companion's look, from the Meta Ray-Ban Display UI Toolkit the glasses' web apps use
 * (its CSS custom properties, `--uit-*`, read from a Toolkit build): the same colors, radii and
 * spacing, and Noto Sans. The Toolkit's type scale is for a 600 px HUD; the phone's is about
 * two thirds of it.
 */
object Lumen {
    val window = Color(0xFF000000) // --uit-color-background-window
    val surface = Color(0xFF27282D) // --uit-color-background-surface
    val elevation1 = Color(0xFF30333A) // --uit-color-background-elevation1
    val elevation2 = Color(0xFF41454E) // --uit-color-background-elevation2
    val bar = Color(0xFF111113) // --uit-color-button-ai-response-background
    val textPrimary = Color(0xFFFFFFFF) // --uit-color-text-primary
    val textSecondary = Color(0xFFCFD3D9) // --uit-color-text-secondary
    val textPlaceholder = Color(0xFFAAAFB9) // --uit-color-text-placeholder
    val accent = Color(0xFF2694FE) // --uit-color-persistent-action
    val positive = Color(0xFF26A756) // --uit-color-persistent-positive
    val warning = Color(0xFFC58600) // --uit-color-persistent-warning
    val negative = Color(0xFFFF5668) // --uit-color-persistent-negative
    val purple = Color(0xFF9081FF) // --uit-color-accent-purple
    val border = Color(0x40FFFFFF) // --uit-color-border-primary

    val radiusRow = 16.dp // --uit-corner-radius-xsmall
    val radiusCard = 24.dp // --uit-corner-radius-small
    val spacingSmall = 8.dp // --uit-spacing-small
    val spacingSmMed = 12.dp // --uit-spacing-sm-med
    val spacingMedium = 16.dp // --uit-spacing-medium
    val spacingMedLg = 20.dp // --uit-spacing-med-lg
    val spacingLarge = 24.dp // --uit-spacing-large

    val noto = FontFamily(
        Font(R.font.noto_sans_regular, FontWeight.Normal),
        Font(R.font.noto_sans_medium, FontWeight.Medium),
        Font(R.font.noto_sans_bold, FontWeight.Bold),
    )
}

private val typography = Typography(
    headlineMedium = TextStyle(fontFamily = Lumen.noto, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = Lumen.noto, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Lumen.noto, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Lumen.noto, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Lumen.noto, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Lumen.noto, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Lumen.noto, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontFamily = Lumen.noto, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val colors = darkColorScheme(
    primary = Lumen.accent,
    onPrimary = Lumen.textPrimary,
    background = Lumen.window,
    onBackground = Lumen.textPrimary,
    surface = Lumen.surface,
    onSurface = Lumen.textPrimary,
    surfaceVariant = Lumen.elevation1,
    onSurfaceVariant = Lumen.textSecondary,
    surfaceContainer = Lumen.bar,
    outline = Lumen.border,
    error = Lumen.negative,
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
