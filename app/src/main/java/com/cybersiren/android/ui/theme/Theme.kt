package com.cybersiren.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF39FF14),
    onPrimary = Color.Black,
    secondary = Color(0xFF2ECB10),
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = Color(0xFF39FF14),
    surface = Color(0xFF111111),
    onSurface = Color(0xFF39FF14),
    error = Color(0xFFFF5555),
    onError = Color.Black
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF008000),
    onPrimary = Color.White,
    secondary = Color(0xFF006600),
    onSecondary = Color.White,
    background = Color.White,
    onBackground = Color(0xFF008000),
    surface = Color(0xFFF8F8F8),
    onSurface = Color(0xFF008000),
    error = Color(0xFFCC0000),
    onError = Color.White
)

@Composable
fun BitchatTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {

    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
