package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val WalkieDarkColorScheme = darkColorScheme(
    primary = PttNeonGreen,
    onPrimary = LcdInkDark,
    primaryContainer = LcdGreenDeep,
    onPrimaryContainer = TextPrimaryLight,
    secondary = BrandAmber,
    onSecondary = ChassisObsidian,
    secondaryContainer = GunmetalCard,
    onSecondaryContainer = TextPrimaryLight,
    tertiary = VolumeBlueGlow,
    onTertiary = TextPrimaryLight,
    background = ChassisObsidian,
    onBackground = TextPrimaryLight,
    surface = ChassisBodyDark,
    onSurface = TextPrimaryLight,
    surfaceVariant = GunmetalCard,
    onSurfaceVariant = TextSecondaryLight,
    outline = ChassisBevelHighlight,
    error = StatusDangerRed,
    onError = TextPrimaryLight
)

@Composable
fun MamunWalkieTalkieTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = WalkieDarkColorScheme,
        typography = Typography,
        content = content
    )
}
