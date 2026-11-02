package com.vmm5.notes.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.vmm5.notes.sync.Prefs

/**
 * Two looks, chosen in Settings → Look:
 *  - Tango (default): Material 3 in Tango's colours, orange tabby primary,
 *    cream and warm greys, and the pixel cat on the screens.
 *  - Minimal: neutral greys with one quiet slate accent, and no cat.
 * Light and dark follow the system. Tag colours stay fixed (blue / green /
 * amber) so they match the stick's screen.
 */
object TangoColors {
    val Orange = Color(0xFFE8893A)
    val OrangeDeep = Color(0xFFB85E1C)
    val Cream = Color(0xFFF6E8CF)
    val Pink = Color(0xFFE59AA6)
}

/** True in the Minimal look: the cat is not drawn, wording stays plain. */
val LocalMinimal = staticCompositionLocalOf { false }

@Composable
fun VmmTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val style by remember { Prefs(context).appStyle }.collectAsState(0)
    val minimal = style == 1
    val dark = isSystemInDarkTheme()
    val scheme = when {
        minimal && dark -> darkColorScheme(
            primary = Color(0xFFB4C3D1), onPrimary = Color(0xFF1C2630), primaryContainer = Color(0xFF34404B), onPrimaryContainer = Color(0xFFD9E3EC),
            secondary = Color(0xFFBDBDBD), onSecondary = Color(0xFF262626), secondaryContainer = Color(0xFF3A3A3A), onSecondaryContainer = Color(0xFFE6E6E6),
            tertiary = Color(0xFFB4C3D1), onTertiary = Color(0xFF1C2630),
            background = Color(0xFF141414), onBackground = Color(0xFFE8E8E8),
            surface = Color(0xFF141414), onSurface = Color(0xFFE8E8E8),
            surfaceVariant = Color(0xFF2A2A2A), onSurfaceVariant = Color(0xFFB8B8B8),
            surfaceContainer = Color(0xFF1E1E1E), outline = Color(0xFF7A7A7A),
        )
        minimal -> lightColorScheme(
            primary = Color(0xFF3F4F5E), onPrimary = Color.White, primaryContainer = Color(0xFFDDE4EA), onPrimaryContainer = Color(0xFF16202A),
            secondary = Color(0xFF5E5E5E), onSecondary = Color.White, secondaryContainer = Color(0xFFE8E8E8), onSecondaryContainer = Color(0xFF1F1F1F),
            tertiary = Color(0xFF3F4F5E), onTertiary = Color.White,
            background = Color(0xFFFAFAFA), onBackground = Color(0xFF1A1A1A),
            surface = Color(0xFFFAFAFA), onSurface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFFEDEDED), onSurfaceVariant = Color(0xFF4A4A4A),
            surfaceContainer = Color(0xFFF2F2F2), outline = Color(0xFF8A8A8A),
        )
        dark -> darkColorScheme(
            primary = Color(0xFFF2A35B), onPrimary = Color(0xFF2B1A0C), primaryContainer = Color(0xFF5C3A1A), onPrimaryContainer = Color(0xFFFFDCC0),
            secondary = Color(0xFFD9BFA3), onSecondary = Color(0xFF3A2B1D), secondaryContainer = Color(0xFF4A3A2C), onSecondaryContainer = Color(0xFFF6E8CF),
            tertiary = Color(0xFFE59AA6), onTertiary = Color(0xFF3A1A20),
            background = Color(0xFF1A1613), onBackground = Color(0xFFEFE6DC),
            surface = Color(0xFF1A1613), onSurface = Color(0xFFEFE6DC),
            surfaceVariant = Color(0xFF2E2621), onSurfaceVariant = Color(0xFFC9BAAA),
            surfaceContainer = Color(0xFF241E1A), outline = Color(0xFF8A7A6C),
        )
        else -> lightColorScheme(
            primary = Color(0xFFB85E1C), onPrimary = Color.White, primaryContainer = Color(0xFFFFDCC0), onPrimaryContainer = Color(0xFF3A1F08),
            secondary = Color(0xFF6E5A47), onSecondary = Color.White, secondaryContainer = Color(0xFFF3E2CE), onSecondaryContainer = Color(0xFF2B1F14),
            tertiary = Color(0xFFA34A58), onTertiary = Color.White,
            background = Color(0xFFFCF6EE), onBackground = Color(0xFF1F1A16),
            surface = Color(0xFFFCF6EE), onSurface = Color(0xFF1F1A16),
            surfaceVariant = Color(0xFFEFE1D2), onSurfaceVariant = Color(0xFF53463B),
            surfaceContainer = Color(0xFFF4EADF), outline = Color(0xFF857668),
        )
    }
    CompositionLocalProvider(LocalMinimal provides minimal) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
