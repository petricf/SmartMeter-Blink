package de.smartmeter.blink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealContainer,
    onPrimaryContainer = TealOnContainer,
    error = WarningRed,
)

@Composable
fun SmartMeterBlinkTheme(content: @Composable () -> Unit)
{
    MaterialTheme(
        colorScheme = LightColors,
        typography = Typography,
        content = content,
    )
}