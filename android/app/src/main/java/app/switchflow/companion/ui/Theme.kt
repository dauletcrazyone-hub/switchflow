package app.switchflow.companion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Same palette as the Windows app (desktop/src/styles/index.css).
object SF {
    val Bg = Color(0xFF080B12)
    val Bg2 = Color(0xFF111722)
    val Surface = Color(0xFF192231)
    val Accent = Color(0xFF478BFF)
    val Accent2 = Color(0xFF75C8FF)
    val Success = Color(0xFF35C98A)
    val Warning = Color(0xFFF2B450)
    val Danger = Color(0xFFFF6B6B)
    val Text = Color(0xFFF6F8FC)
    val Text2 = Color(0xFF9CAAC0)

    val LightBg = Color(0xFFF5F7FB)
    val LightSurface = Color(0xFFFFFFFF)
    val LightText = Color(0xFF17202F)
    val LightAccent = Color(0xFF3478F6)
}

private val Dark = darkColorScheme(
    primary = SF.Accent,
    secondary = SF.Accent2,
    background = SF.Bg,
    surface = SF.Surface,
    surfaceVariant = SF.Bg2,
    onPrimary = Color.White,
    onBackground = SF.Text,
    onSurface = SF.Text,
    onSurfaceVariant = SF.Text2,
    error = SF.Danger,
)

private val Light = lightColorScheme(
    primary = SF.LightAccent,
    secondary = SF.Accent2,
    background = SF.LightBg,
    surface = SF.LightSurface,
    surfaceVariant = Color(0xFFEEF1F7),
    onPrimary = Color.White,
    onBackground = SF.LightText,
    onSurface = SF.LightText,
    onSurfaceVariant = Color(0xFF5B6678),
    error = Color(0xFFE04848),
)

private val Type = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun SwitchFlowTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
