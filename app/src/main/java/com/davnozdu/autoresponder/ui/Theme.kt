package com.davnozdu.autoresponder.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/** Тема приложения: следует системной тёмной/светлой + корректная строка состояния. */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    // Спокойная синяя палитра: синим показываем действие и исправное состояние,
    // янтарным — внимание, красным оставляем только реальные ошибки.
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFFB6C4FF),
            onPrimary = Color(0xFF14265F),
            primaryContainer = Color(0xFF263B82),
            onPrimaryContainer = Color(0xFFE0E5FF),
            secondary = Color(0xFFBEC6E0),
            secondaryContainer = Color(0xFF3D465F),
            background = Color(0xFF101218),
            surface = Color(0xFF171A22),
            surfaceVariant = Color(0xFF444750)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF3F51B5),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFDDE2FF),
            onPrimaryContainer = Color(0xFF101B55),
            secondary = Color(0xFF59627A),
            secondaryContainer = Color(0xFFE0E5F5),
            background = Color(0xFFF8F9FF),
            surface = Color.White,
            surfaceVariant = Color(0xFFE8EAF2)
        )
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
}
