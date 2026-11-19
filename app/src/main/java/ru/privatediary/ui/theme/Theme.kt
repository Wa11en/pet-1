package ru.privatediary.ui.theme

import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

private val PaperColors = lightColorScheme(
    primary = Color(0xFF315848),
    onPrimary = Color(0xFFFFFDF6),
    primaryContainer = Color(0xFFDFE9D8),
    onPrimaryContainer = Color(0xFF173A2A),
    inversePrimary = Color(0xFFB3CEAA),
    secondary = Color(0xFF646C55),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE7E9D9),
    onSecondaryContainer = Color(0xFF2A3220),
    tertiary = Color(0xFF9A5E47),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF3DFD0),
    onTertiaryContainer = Color(0xFF3F2419),
    background = Color(0xFFF7F4EC),
    onBackground = Color(0xFF253B2F),
    surface = Color(0xFFFBF8F1),
    onSurface = Color(0xFF253B2F),
    surfaceVariant = Color(0xFFE9EDDF),
    onSurfaceVariant = Color(0xFF65705F),
    surfaceTint = Color(0xFF315848),
    inverseSurface = Color(0xFF2D382F),
    inverseOnSurface = Color(0xFFF0F2E7),
    error = Color(0xFFAF3E37),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DAD4),
    onErrorContainer = Color(0xFF45201A),
    outline = Color(0xFF778173),
    outlineVariant = Color(0xFFDDDCD1),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFFFDF8),
    surfaceDim = Color(0xFFE8E4D8),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F6EE),
    surfaceContainer = Color(0xFFF1EEE5),
    surfaceContainerHigh = Color(0xFFEDE9DE),
    surfaceContainerHighest = Color(0xFFE7E3D8),
)

private val EveningColors = darkColorScheme(
    primary = Color(0xFFB3CEAA),
    onPrimary = Color(0xFF183522),
    primaryContainer = Color(0xFF2E4B35),
    onPrimaryContainer = Color(0xFFD5E6CB),
    inversePrimary = Color(0xFF375A41),
    secondary = Color(0xFFC4CAAF),
    onSecondary = Color(0xFF2D351F),
    secondaryContainer = Color(0xFF434D34),
    onSecondaryContainer = Color(0xFFE2E9CA),
    tertiary = Color(0xFFE4AD91),
    onTertiary = Color(0xFF4F2C1C),
    tertiaryContainer = Color(0xFF69422F),
    onTertiaryContainer = Color(0xFFFFDCCA),
    background = Color(0xFF141D17),
    onBackground = Color(0xFFE6EBDC),
    surface = Color(0xFF18221A),
    onSurface = Color(0xFFE6EBDC),
    surfaceVariant = Color(0xFF364333),
    onSurfaceVariant = Color(0xFFBAC5B5),
    surfaceTint = Color(0xFFB3CEAA),
    inverseSurface = Color(0xFFE0E6D8),
    inverseOnSurface = Color(0xFF293326),
    error = Color(0xFFFFB4A8),
    onError = Color(0xFF690A0A),
    errorContainer = Color(0xFF8B2C25),
    onErrorContainer = Color(0xFFFFDAD5),
    outline = Color(0xFF859281),
    outlineVariant = Color(0xFF3B4A3A),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF394339),
    surfaceDim = Color(0xFF141D17),
    surfaceContainerLowest = Color(0xFF101710),
    surfaceContainerLow = Color(0xFF1C271E),
    surfaceContainer = Color(0xFF202C22),
    surfaceContainerHigh = Color(0xFF29352A),
    surfaceContainerHighest = Color(0xFF334033),
)

/** Editorial accents sit outside the semantic Material roles used by controls and text. */
@Immutable
data class DiaryAccentColors(
    val paper: Color,
    val ink: Color,
    val cover: Color,
    val onCover: Color,
    val mutedCover: Color,
    val accent: Color,
    val accentContainer: Color,
    val rule: Color,
    val glow: Color,
)

private val LocalDiaryAccentColors = staticCompositionLocalOf {
    DiaryAccentColors(
        paper = Color(0xFFF7F4EC), ink = Color(0xFF253B2F), cover = Color(0xFF264F40),
        onCover = Color(0xFFFFF8E8), mutedCover = Color(0xFFC3D2C3), accent = Color(0xFF9A5E47),
        accentContainer = Color(0xFFF3DFD0), rule = Color(0xFFDDDCD1), glow = Color(0xFFDCC69A),
    )
}

object DiaryStyle {
    val colors: DiaryAccentColors
        @Composable @ReadOnlyComposable get() = LocalDiaryAccentColors.current
}

@Composable
fun DiaryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = if (dark) EveningColors else PaperColors
    // Keep the quiet paper surfaces while adapting control accents to the wallpaper on Android 12+.
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        val dynamic = if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        base.copy(
            primary = lerp(base.primary, dynamic.primary, 0.26f),
            primaryContainer = lerp(base.primaryContainer, dynamic.primaryContainer, 0.18f),
            secondary = lerp(base.secondary, dynamic.secondary, 0.16f),
            secondaryContainer = lerp(base.secondaryContainer, dynamic.secondaryContainer, 0.12f),
            tertiary = lerp(base.tertiary, dynamic.tertiary, 0.12f),
            tertiaryContainer = lerp(base.tertiaryContainer, dynamic.tertiaryContainer, 0.10f),
            surfaceTint = lerp(base.surfaceTint, dynamic.surfaceTint, 0.26f),
        )
    } else base
    val accents = DiaryAccentColors(
        paper = colors.background,
        ink = colors.onBackground,
        cover = if (dark) Color(0xFF2B4435) else Color(0xFF264F40),
        onCover = Color(0xFFFFF8E8),
        mutedCover = if (dark) Color(0xFFBDCFBC) else Color(0xFFC3D2C3),
        accent = colors.tertiary,
        accentContainer = colors.tertiaryContainer,
        rule = colors.outlineVariant,
        glow = if (dark) Color(0xFFD8BD86) else Color(0xFFDCC69A),
    )
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalDiaryAccentColors provides accents) {
        MaterialTheme(colorScheme = colors, typography = DiaryTypography, shapes = DiaryShapes, content = content)
    }
}
