package com.feedpilot.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Gold,
    onPrimary = OnAccent,
    primaryContainer = GoldDeep,
    onPrimaryContainer = Color.White,
    secondary = Orange,
    onSecondary = OnAccent,
    secondaryContainer = Color(0xFFDDE8FF),
    onSecondaryContainer = Color(0xFF071A45),
    tertiary = Gold,
    tertiaryContainer = Color(0xFFD8F8F5),
    onTertiaryContainer = Color(0xFF042326),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightBorder,
    error = StatusRed
)

private val DarkColors = darkColorScheme(
    primary = Gold,
    onPrimary = OnAccent,
    primaryContainer = GoldDeep,
    onPrimaryContainer = Color.White,
    secondary = Orange,
    onSecondary = OnAccent,
    secondaryContainer = Color(0xFF0E245A),
    onSecondaryContainer = GoldBright,
    tertiary = Gold,
    tertiaryContainer = Color(0xFF052D32),
    onTertiaryContainer = GoldBright,
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkBorder,
    error = StatusRed
)

/** Extra brand colors that dynamically adapt between Light and Dark themes. */
data class BrandColors(
    val headerGradient: Brush,
    val pillBackground: Color,
    val headerContentColor: Color,
    val coinGold: Color,
    val diamondBlue: Color,
    val magenta: Color,
    val orange: Color,
    val navBar: Color,
    val onNavSelected: Color,
    val onNavUnselected: Color
)

val LocalBrandColors = androidx.compose.runtime.staticCompositionLocalOf {
    BrandColors(
        headerGradient = Brush.verticalGradient(listOf(HeaderTop, HeaderBottom)),
        pillBackground = Color(0x33FFFFFF),
        headerContentColor = Color.White,
        coinGold = CoinGold,
        diamondBlue = DiamondBlue,
        magenta = AccentText,
        orange = Orange,
        navBar = NavSurface,
        onNavSelected = Gold,
        onNavUnselected = Color(0x99FFFFFF)
    )
}

@Composable
fun FeedPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val brand = if (darkTheme) {
        BrandColors(
            headerGradient = Brush.verticalGradient(listOf(HeaderTop, HeaderBottom)),
            pillBackground = Color(0x33FFFFFF),
            headerContentColor = Color.White,
            coinGold = CoinGold,
            diamondBlue = DiamondBlue,
            magenta = AccentText,
            orange = Orange,
            navBar = NavSurface,
            onNavSelected = Gold,
            onNavUnselected = Color(0x99FFFFFF)
        )
    } else {
        BrandColors(
            headerGradient = Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFFEBEBF0))),
            pillBackground = Color(0x12000000),
            headerContentColor = LightOnSurface,
            coinGold = CoinGold,
            diamondBlue = DiamondBlue,
            magenta = Gold,
            orange = Orange,
            navBar = Color(0xFFFFFFFF),
            onNavSelected = GoldDeep,
            onNavUnselected = Color(0xFF64717B)
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalBrandColors provides brand) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            content = content
        )
    }
}

/** Convenience accessor for brand colors inside composables. */
object AppTheme {
    val brand: BrandColors
        @Composable get() = LocalBrandColors.current
}
