package ru.privatediary.ui.theme

import androidx.activity.compose.LocalActivity
import android.os.Build
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun DiaryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val activity = LocalActivity.current
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) darkColorScheme(primary = Color(0xFFB7CDB3)) else lightColorScheme(
        primary = Color(0xFF526953), background = Color(0xFFF8F6F0), surface = Color(0xFFF8F6F0))
    MaterialTheme(colorScheme = colors, content = content)
}
