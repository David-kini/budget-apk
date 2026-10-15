package cd.smartbudget.fc.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF0B6E4F)
private val GreenLight = Color(0xFF6FD3A8)
private val Gold = Color(0xFFF2C14E)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9EFDD),
    onPrimaryContainer = Color(0xFF002116),
    secondary = Color(0xFF4C6358),
    tertiary = Color(0xFF8A6A00),
    tertiaryContainer = Gold,
    background = Color(0xFFF7FAF8),
    surface = Color(0xFFF7FAF8),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF003826),
    primaryContainer = Color(0xFF005139),
    onPrimaryContainer = Color(0xFFC9EFDD),
    secondary = Color(0xFFB3CCBF),
    tertiary = Gold,
    background = Color(0xFF101413),
    surface = Color(0xFF101413),
    error = Color(0xFFFFB4AB),
)

@Composable
fun SmartBudgetTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
