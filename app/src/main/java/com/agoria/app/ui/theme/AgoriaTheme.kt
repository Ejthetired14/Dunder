package com.agoria.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Amber = Color(0xFFF2B84B)
private val AmberDark = Color(0xFF6A4A0B)
private val Teal = Color(0xFF5FD3C4)
private val TealDark = Color(0xFF164A44)
private val Ink = Color(0xFF0F1115)
private val InkSoft = Color(0xFF171A21)
private val InkRaised = Color(0xFF212633)
private val InkOutline = Color(0xFF343C4B)
private val Paper = Color(0xFFF6F1E7)
private val PaperSoft = Color(0xFFFFFBF2)
private val PaperRaised = Color(0xFFFFFFFF)
private val PaperOutline = Color(0xFFD8CEBB)
private val Coral = Color(0xFFFF6B6B)
private val CoralDark = Color(0xFF7A1F1F)
private val Violet = Color(0xFFB69CFF)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1A00),
    primaryContainer = AmberDark,
    onPrimaryContainer = Color(0xFFFFE4B0),
    secondary = Teal,
    onSecondary = Color(0xFF00201C),
    secondaryContainer = TealDark,
    onSecondaryContainer = Color(0xFFC7FFF7),
    tertiary = Violet,
    onTertiary = Color(0xFF211341),
    tertiaryContainer = Color(0xFF3A2B63),
    onTertiaryContainer = Color(0xFFEDE2FF),
    background = Ink,
    onBackground = Color(0xFFE9ECF2),
    surface = InkSoft,
    onSurface = Color(0xFFE9ECF2),
    surfaceVariant = InkRaised,
    onSurfaceVariant = Color(0xFFAEB7C7),
    surfaceContainer = InkRaised,
    surfaceContainerHigh = Color(0xFF2A303C),
    outline = InkOutline,
    outlineVariant = Color(0xFF262D38),
    error = Coral,
    onError = Color(0xFF2B0000),
    errorContainer = CoralDark,
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5A00),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDA6),
    onPrimaryContainer = Color(0xFF2A1A00),
    secondary = Color(0xFF00695E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC7FFF7),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF6B46C1),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE2FF),
    onTertiaryContainer = Color(0xFF211341),
    background = Paper,
    onBackground = Color(0xFF1C1B18),
    surface = PaperSoft,
    onSurface = Color(0xFF1C1B18),
    surfaceVariant = Color(0xFFEDE6D8),
    onSurfaceVariant = Color(0xFF4F4A40),
    surfaceContainer = Color(0xFFF1EADC),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = PaperOutline,
    outlineVariant = Color(0xFFE5DDCE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val AgoriaShapes = Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
    extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp),
)

private val AgoriaTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun AgoriaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AgoriaTypography,
        shapes = AgoriaShapes,
        content = content,
    )
}