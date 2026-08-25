package com.feedpilot.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = PremiumGoldDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF3E4BE),
    onPrimaryContainer = Color(0xFF2E220C),
    secondary = PremiumCopper,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5D5C3),
    onSecondaryContainer = Color(0xFF3D1909),
    tertiary = PremiumMint,
    tertiaryContainer = Color(0xFFD9F7EE),
    onTertiaryContainer = Color(0xFF06241C),
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = Color(0xFFF0E8D9),
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightBorder,
    outlineVariant = Color(0xFFECE2CF),
    error = StatusRed
)

private val DarkColors = darkColorScheme(
    primary = PremiumGold,
    onPrimary = OnAccent,
    primaryContainer = Color(0xFF3C2F13),
    onPrimaryContainer = PremiumGoldBright,
    secondary = PremiumCopper,
    onSecondary = Color(0xFF1B0D06),
    secondaryContainer = Color(0xFF432312),
    onSecondaryContainer = Color(0xFFFFD8BE),
    tertiary = PremiumMint,
    tertiaryContainer = Color(0xFF0D342C),
    onTertiaryContainer = Color(0xFFB9F8E4),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkBorder,
    outlineVariant = Color(0xFF2D2B22),
    error = StatusRed
)

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
    val onNavUnselected: Color,
    val premiumSurface: Color,
    val hairline: Color,
    val mutedGold: Color
)

val LocalBrandColors = androidx.compose.runtime.staticCompositionLocalOf {
    BrandColors(
        headerGradient = Brush.verticalGradient(listOf(HeaderTop, HeaderMid, HeaderBottom)),
        pillBackground = Color(0x1FF5D98B),
        headerContentColor = DarkOnSurface,
        coinGold = PremiumGold,
        diamondBlue = PremiumBlue,
        magenta = PremiumGoldBright,
        orange = PremiumCopper,
        navBar = NavSurface,
        onNavSelected = PremiumGoldBright,
        onNavUnselected = Color(0x99F7F1E2),
        premiumSurface = DarkSurfaceElevated,
        hairline = DarkBorder,
        mutedGold = PremiumGoldDeep
    )
}

@Composable
fun FeedPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val brand = if (darkTheme) {
        BrandColors(
            headerGradient = Brush.verticalGradient(listOf(HeaderTop, HeaderMid, HeaderBottom)),
            pillBackground = Color(0x24F5D98B),
            headerContentColor = DarkOnSurface,
            coinGold = PremiumGold,
            diamondBlue = PremiumBlue,
            magenta = PremiumGoldBright,
            orange = PremiumCopper,
            navBar = NavSurface,
            onNavSelected = PremiumGoldBright,
            onNavUnselected = Color(0x99F7F1E2),
            premiumSurface = DarkSurfaceElevated,
            hairline = DarkBorder,
            mutedGold = PremiumGoldDeep
        )
    } else {
        BrandColors(
            headerGradient = Brush.verticalGradient(listOf(Color(0xFFFFFCF5), Color(0xFFF0E6D2))),
            pillBackground = Color(0x1A9C7732),
            headerContentColor = LightOnSurface,
            coinGold = PremiumGoldDeep,
            diamondBlue = PremiumBlue,
            magenta = PremiumGoldDeep,
            orange = PremiumCopper,
            navBar = LightSurfaceElevated,
            onNavSelected = PremiumGoldDeep,
            onNavUnselected = Color(0xFF756E60),
            premiumSurface = LightSurfaceElevated,
            hairline = LightBorder,
            mutedGold = Color(0xFFB08A42)
        )
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalBrandColors provides brand) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = Typography,
            shapes = AppShapes,
            content = content
        )
    }
}

object AppTheme {
    val brand: BrandColors
        @Composable get() = LocalBrandColors.current
}