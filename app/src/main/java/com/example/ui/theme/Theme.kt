package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val InacapDarkColorScheme = darkColorScheme(
    primary = InacapRed,
    onPrimary = Color.White,
    primaryContainer = InacapRedDark,
    onPrimaryContainer = Color.White,
    secondary = InacapSlate,
    onSecondary = Color.White,
    tertiary = IoTManualAmber,
    background = InacapCharcoal,
    onBackground = Color(0xFFF5F5F5),
    surface = InacapDarkSurface,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2A),
    onSurfaceVariant = Color(0xFFD0D0CC),
    error = IoTOccupiedRed,
    onError = Color.White
)

private val InacapLightColorScheme = lightColorScheme(
    primary = InacapRed,
    onPrimary = Color.White,
    primaryContainer = InacapRedContainer,
    onPrimaryContainer = OnInacapRedContainer,
    secondary = InacapCharcoal,
    onSecondary = Color.White,
    secondaryContainer = InacapDarkSurface,
    onSecondaryContainer = Color.White,
    tertiary = IoTManualAmber,
    onTertiary = Color.White,
    background = InacapLightBackground,
    onBackground = InacapCharcoal,
    surface = InacapCardSurface,
    onSurface = InacapCharcoal,
    surfaceVariant = InacapSurfaceVariant,
    onSurfaceVariant = InacapSlate,
    error = IoTOccupiedRed,
    onError = Color.White
)

@Composable
fun InacapIoTTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) InacapDarkColorScheme else InacapLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    InacapIoTTheme(darkTheme = darkTheme, content = content)
}
