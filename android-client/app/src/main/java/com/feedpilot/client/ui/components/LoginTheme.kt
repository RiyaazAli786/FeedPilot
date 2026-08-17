package com.feedpilot.client.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Shared palette for the Instagram-style login screens.
 *
 * Those screens were laid out against a fixed light palette, but a text field's *content* colour
 * still came from the theme. In dark mode that is near-white, drawn on a hard-coded near-white
 * container — so typed credentials were invisible. Everything the user reads is derived from the
 * colour scheme here instead; the values were picked so light mode looks the same as before
 * (surface #FFFFFF, field #F4F4F7, text #17171C, border #E5E5EA) while dark mode stays legible.
 *
 * Instagram's own brand colours — the logo gradient, the pink "Log in via Web" button, the blue
 * primary button — are deliberately *not* in here: they must not change with the theme.
 */
object LoginColors {
    /** Page background. White in light mode, charcoal in dark. */
    val surface: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface

    /** Body copy and anything that must read as primary text. */
    val text: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurface

    /** Labels, placeholders, helper text, icon tints. */
    val muted: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.onSurfaceVariant

    /** Dividers and unfocused field borders. */
    val border: Color
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.outline
}

/**
 * Field colours for the login forms. Sets the text, cursor and placeholder colours explicitly —
 * leaving them to default is what made typed input invisible on the hard-coded light container.
 */
@Composable
fun loginTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = LoginColors.text,
    unfocusedTextColor = LoginColors.text,
    cursorColor = LoginColors.text,
    focusedBorderColor = LoginColors.muted,
    unfocusedBorderColor = LoginColors.border,
    focusedContainerColor = MaterialTheme.colorScheme.background,
    unfocusedContainerColor = MaterialTheme.colorScheme.background,
    focusedPlaceholderColor = LoginColors.muted,
    unfocusedPlaceholderColor = LoginColors.muted,
    focusedTrailingIconColor = LoginColors.muted,
    unfocusedTrailingIconColor = LoginColors.muted
)
