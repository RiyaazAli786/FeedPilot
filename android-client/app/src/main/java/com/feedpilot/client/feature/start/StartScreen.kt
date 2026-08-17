package com.feedpilot.client.feature.start

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.SecureStorage
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.repository.AuthRepository
import com.feedpilot.client.data.repository.SettingsRepository
import com.feedpilot.client.data.repository.WalletRepository
import com.feedpilot.client.ui.components.RestoreCodeDialog
import com.feedpilot.client.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StartViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val walletRepository: WalletRepository,
    private val accountRepository: AccountRepository,
    private val secureStorage: SecureStorage
) : ViewModel() {

    val termsAccepted: StateFlow<Boolean> = settingsRepository.settings
        .map { it.termsAccepted }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun acceptTerms(onAccepted: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setTermsAccepted(true)
            onAccepted()
        }
    }

    /**
     * Restores a Backup Code from the very first screen a brand-new install lands on — a fresh
     * install, a data clear, and a reinstall are all indistinguishable from here (each starts
     * with zero linked accounts), so this is offered unconditionally rather than trying to guess
     * which case it is. See AuthRepository.restoreWithBackupCode for the actual mechanism.
     */
    fun restoreWithBackupCode(code: String, onResult: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            when (val res = authRepository.restoreWithBackupCode(code)) {
                is Resource.Success -> {
                    // Re-cache the code on this install too — otherwise Settings' "Backup Code"
                    // section would think this install was never secured, even though it just was.
                    secureStorage.put(SecureStorage.KEY_BACKUP_CODE, code.trim())
                    walletRepository.refresh()
                    // Retried, not fire-and-forget: this is the account_logs completed-task
                    // backfill's first and highest-stakes chance to land before the operator taps
                    // Start — see AccountRepository.refreshWithRetry's doc for why that matters.
                    accountRepository.refreshWithRetry()
                    onResult(true, "Account restored — your coins are back.")
                }
                is Resource.Error -> onResult(false, res.message ?: "Could not restore with that code.")
                Resource.Loading -> Unit
            }
        }
    }
}

@Composable
fun StartScreen(
    onNavigateToLogin: () -> Unit,
    viewModel: StartViewModel = hiltViewModel()
) {
    val termsAccepted by viewModel.termsAccepted.collectAsStateWithLifecycle()
    var showTermsDialog by remember { mutableStateOf(false) }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var restoreMessage by remember { mutableStateOf<String?>(null) }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val gradientBg = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF07141D),
            Color(0xFF050A10),
            Color(0xFF020407)
        )
    )

    val emblemGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF26F0DF), Color(0xFF4D7CFF), Color(0xFF9B5CFF))
    )

    val primaryGradient = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF0BC9BF),
            Color(0xFF2554D9),
            Color(0xFF1A2540)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(gradientBg)
            // The activity is edge-to-edge, so the gradient fills the whole screen but content
            // must stay clear of the status and navigation bars — without this the bottom
            // "Safe & Secure" row is drawn underneath the navigation bar and gets clipped.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            Spacer(Modifier.height(16.dp))

            // Center Content: emblem + title + tagline
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .scale(scale)
                        .size(110.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color(0xFF25D9D0).copy(alpha = 0.28f), Color.Transparent)
                            )
                        )
                        .border(width = 3.dp, brush = emblemGradient, shape = CircleShape)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.FlightTakeoff,
                        contentDescription = "FeedPilot Emblem",
                        tint = Color(0xFF8DEDE8),
                        modifier = Modifier.size(54.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    text = "FeedPilot",
                    fontWeight = FontWeight.Black,
                    fontSize = 38.sp,
                    color = Color.White,
                    letterSpacing = 1.2.sp
                )

                Spacer(Modifier.height(8.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF0E1B25).copy(alpha = 0.92f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF25D9D0).copy(alpha = 0.42f))
                ) {
                    Text(
                        text = "Instant Followers • Real Likes • Automated Coins",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        // Narrow screens cannot fit this on one line; centre it so the wrap reads
                        // as two balanced lines instead of a ragged left edge.
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }

            // Bottom Section: Sign in with Instagram Button
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(primaryGradient)
                        .clickable {
                            if (termsAccepted) {
                                onNavigateToLogin()
                            } else {
                                showTermsDialog = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FlightTakeoff,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Sign in with Instagram",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "Safe & Secure • Direct Instagram OAuth",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Offered unconditionally on the very first screen a brand-new install lands
                // on — see StartViewModel.restoreWithBackupCode for why a fresh install, a data
                // clear, and a reinstall can't be told apart here, so this can't be shown only
                // when it's "needed".
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = 1.5.dp,
                            color = Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .clickable { showRestoreDialog = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Restore a previous account",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Privacy Policy Terms Acceptance Dialog
        if (showTermsDialog) {
            PrivacyPolicyDialog(
                onAccept = {
                    viewModel.acceptTerms {
                        showTermsDialog = false
                        onNavigateToLogin()
                    }
                },
                onDismiss = { showTermsDialog = false }
            )
        }

        if (showRestoreDialog) {
            RestoreCodeDialog(
                isRestoring = isRestoring,
                onDismiss = { if (!isRestoring) showRestoreDialog = false },
                onRestore = { code ->
                    isRestoring = true
                    viewModel.restoreWithBackupCode(code) { success, message ->
                        isRestoring = false
                        restoreMessage = message
                        if (success) showRestoreDialog = false
                    }
                }
            )
        }

        restoreMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { restoreMessage = null },
                confirmButton = {
                    TextButton(onClick = { restoreMessage = null }) { Text("OK") }
                },
                text = { Text(message) }
            )
        }
    }
}

@Composable
private fun PrivacyPolicyDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                // usePlatformDefaultWidth = false hands the card the whole window as its
                // constraint, so with this much policy text it grew to the full screen height and
                // the title and the Accept row ended up flush against the edges. Reserving the
                // system bars and a margin first caps the card short of them; the body below
                // scrolls to absorb whatever is left over.
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = 24.dp)
                .fillMaxWidth(0.92f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
            ) {
                // Header Title
                Text(
                    text = "Privacy Policy",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )

                Spacer(Modifier.height(14.dp))

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(weight = 1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "FeedPilot respects the privacy of its users and is committed to protecting the personal information you provide. Since the collection and processing of personal information is an unavoidable part of mobile phone and internet based processes, it is necessary to read this document in order to fully understand the policies and privacy.",
                        fontSize = 13.sp,
                        color = Color(0xFF222222),
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "What information is collected by us:",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF222222)
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "1. By logging into the program, your Android ID will be saved on our server so that we can store information such as the number of coins, the number of crystals, the number of your invitees, etc. on your Android ID. If the data is deleted from your device or the application is deleted and reinstalled, your data will be saved and your coins will not be lost.",
                        fontSize = 13.sp,
                        color = Color(0xFF333333),
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "2. When logging into the Instagram account, we may use cookies to collect information. A cookie is a file that is created by the browser at the request of a site and allows the site to store your information and reactions on the site. Cookies help us communicate with Instagram and send your requests such as follows, likes, comments, etc. directly through the program.",
                        fontSize = 13.sp,
                        color = Color(0xFF333333),
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = "3. Your IP address",
                        fontSize = 13.sp,
                        color = Color(0xFF333333),
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "4. Your username and user ID on Instagram",
                        fontSize = 13.sp,
                        color = Color(0xFF333333),
                        lineHeight = 18.sp
                    )

                    Spacer(Modifier.height(10.dp))

                    Text(
                        text = "Acceptance of this document will mean acceptance of policies that may change in the future.",
                        fontSize = 13.sp,
                        color = Color(0xFF222222),
                        lineHeight = 18.sp
                    )
                }

                Spacer(Modifier.height(18.dp))

                // Bottom Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Accept Button (Golden Yellow matching screenshot)
                    Button(
                        onClick = onAccept,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF25D9D0),
                            contentColor = Color(0xFF031013)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                    ) {
                        Text(
                            text = "Accept",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    // Cancel Button
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.height(44.dp)
                    ) {
                        Text(
                            text = "Cancel",
                            color = Color(0xFF666666),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
