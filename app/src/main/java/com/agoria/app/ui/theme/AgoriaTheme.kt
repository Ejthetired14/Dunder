package com.agoria.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One pixel look for the whole app, taken from the office map: ink and navy backgrounds, cream
 * text, amber and teal accents, square corners, monospace headings. The system light/dark setting
 * is ignored on purpose, so every page matches the office.
 */
private val Ink = Color(0xFF14161F)
private val Navy = Color(0xFF232842)
private val NavyHigh = Color(0xFF2C3254)
private val Cream = Color(0xFFF3E5C5)
private val Tan = Color(0xFFD9B98B)
private val Amber = Color(0xFFFFC83D)
private val Teal = Color(0xFF5E9AA0)
private val TealDark = Color(0xFF2E6B69)
private val Red = Color(0xFFD9534F)

private val PixelColors = darkColorScheme(
    primary = Amber,
    onPrimary = Ink,
    primaryContainer = Color(0xFF4A3B1E),
    onPrimaryContainer = Cream,
    secondary = Teal,
    onSecondary = Ink,
    secondaryContainer = TealDark,
    onSecondaryContainer = Cream,
    tertiary = Color(0xFFC9A2F0),
    onTertiary = Ink,
    tertiaryContainer = Color(0xFF45366B),
    onTertiaryContainer = Cream,
    background = Ink,
    onBackground = Cream,
    surface = Color(0xFF1B1F2E),
    onSurface = Cream,
    surfaceVariant = Navy,
    onSurfaceVariant = Tan,
    surfaceTint = Color.Transparent, // no tonal elevation tint: flat fills only
    surfaceContainer = Navy,
    surfaceContainerHigh = NavyHigh,
    surfaceContainerHighest = NavyHigh,
    outline = Color(0xFF8A7DA3),
    outlineVariant = Color(0xFF453A55),
    error = Red,
    onError = Color.White,
    errorContainer = Color(0xFF5A1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Square corners everywhere. */
private val PixelShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

/** Headings and labels are monospace (pixel feel); body text stays a readable sans. */
private val Mono = FontFamily.Monospace

private val PixelTypography = Typography(
    displaySmall = TextStyle(fontFamily = Mono, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    headlineSmall = TextStyle(fontFamily = Mono, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = Mono, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    titleMedium = TextStyle(fontFamily = Mono, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
)

@Composable
fun AgoriaTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = PixelColors,
        typography = PixelTypography,
        shapes = PixelShapes,
        content = content,
    )
}
