package com.feedpilot.client.feature.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.feedpilot.client.ui.components.PurpleTopBar
import com.feedpilot.client.ui.theme.AppTheme

/**
 * Branded 6-digit verification screen. A single hidden text field captures the code while a row of
 * boxes shows progress — FeedPilot's own look, not a copy of any third-party UI.
 */
@Composable
fun VerifyCodeScreen(
    destination: String,
    onBack: () -> Unit,
    onVerified: () -> Unit,
    viewModel: VerifyCodeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(state.verified) { if (state.verified) onVerified() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        PurpleTopBar(title = "Verify it's you", onBack = onBack)

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Enter the code",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "We sent a $CODE_LENGTH-digit code to $destination.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            // Hidden field drives the visible boxes. Box width is derived from the available
            // space rather than fixed, so CODE_LENGTH boxes always fit on narrow (~360dp) screens
            // instead of overflowing past the edge.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 10.dp
                val boxWidth = ((maxWidth - gap * (CODE_LENGTH - 1)) / CODE_LENGTH).coerceIn(36.dp, 48.dp)

                BasicTextField(
                    value = state.code,
                    onValueChange = viewModel::onCodeChange,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(focusRequester),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.Transparent)
                )

                Row(
                    Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(gap)
                ) {
                    repeat(CODE_LENGTH) { i ->
                        CodeBox(
                            char = state.code.getOrNull(i),
                            focused = i == state.code.length,
                            boxWidth = boxWidth
                        )
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(24.dp))
            if (state.resendSeconds > 0) {
                Text(
                    "Resend code in ${state.resendSeconds}s",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
            } else {
                TextButton(onClick = viewModel::resend) { Text("Resend code") }
            }
        }

        Button(
            onClick = { keyboard?.hide(); viewModel.verify() },
            enabled = !state.verifying,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppTheme.brand.orange),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 20.dp)
                .height(54.dp)
        ) {
            if (state.verifying) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text("Verify", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun CodeBox(char: Char?, focused: Boolean, boxWidth: androidx.compose.ui.unit.Dp) {
    val border = if (focused) AppTheme.brand.orange else MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(boxWidth, boxWidth * 1.2f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            char?.toString() ?: "",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
