package ai.openclaw.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Omnitrix palette
val OmnitrixGreen = Color(0xFF00FF41)
val OmnitrixBrightGreen = Color(0xFF39FF14)
val OmnitrixDarkGreen = Color(0xFF006B1E)
val OmnitrixDimGreen = Color(0xFF3A5F3A)

private val OmnitrixColors = Colors(
    primary = OmnitrixGreen,
    primaryVariant = OmnitrixDarkGreen,
    secondary = OmnitrixBrightGreen,
    background = Color.Black,
    surface = Color(0xFF0A0F0A),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = OmnitrixGreen,
    onSurface = OmnitrixGreen,
    error = Color.Red,
    onError = Color.Black,
)

@Composable
fun WearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = OmnitrixColors,
    ) {
        content()
    }
}
