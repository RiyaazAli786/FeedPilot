package com.feedpilot.client.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.feedpilot.client.data.remote.InstagramLoginResult
import com.feedpilot.client.data.repository.AccountRepository
import com.feedpilot.client.data.repository.AddAccountOutcome
import com.feedpilot.client.common.TotpCode
import com.feedpilot.client.ui.components.LoginColors
import com.feedpilot.client.feature.login.UsernameSuggestionPanel
import com.feedpilot.client.ui.components.PurpleTopBar
import com.feedpilot.client.ui.components.loginTextFieldColors
import com.feedpilot.client.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddAccountUiState(
    val username: String = "",
    val sessionToken: String = "",
    val twoFactorSecret: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    /** Non-null while Instagram is waiting for a one-time code for this login attempt. */
    val twoFactor: TwoFactorUiState? = null,
    /** Set once, for the screen to show as a toast and then consume — see [consumeDuplicateToast]. */
    val duplicateToast: String? = null
)

/** The code prompt Instagram's two-factor or email verification challenge puts in front of the login. */
data class TwoFactorUiState(
    val challenge: InstagramLoginResult.TwoFactorRequired? = null,
    val emailChallenge: InstagramLoginResult.EmailCodeRequired? = null,
    val code: String = "",
    val submitting: Boolean = false,
    val error: String? = null
) {
    /** Six digits for an app, email, or SMS code, eight for a recovery code. */
    val canSubmit: Boolean get() = code.length >= MIN_CODE_LENGTH && !submitting

    companion object {
        const val MIN_CODE_LENGTH = 6
        const val MAX_CODE_LENGTH = 8
    }
}

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AddAccountUiState())
    val state: StateFlow<AddAccountUiState> = _state

    fun onUsername(v: String) = _state.update { it.copy(username = v, error = null) }
    fun onToken(v: String) = _state.update { it.copy(sessionToken = v) }
    fun onTwoFactorSecret(v: String) = _state.update { it.copy(twoFactorSecret = v, error = null) }

    /**
     * Stores a session captured by the web-login flow, reusing the form's own state so the
     * existing progress overlay, error banner and post-save navigation all apply unchanged.
     */
    fun saveWebSession(sessionCookies: String, pageUsername: String? = null) {
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            val outcome = accountRepository.addAccountFromWebSession(sessionCookies, pageUsername)
            applyOutcome(outcome)
        }
    }

    fun save() {
        val s = _state.value
        if (s.username.isBlank()) {
            _state.update { it.copy(error = "Username is required") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true, error = null) }
            val outcome = accountRepository.addAccountWithCredentials(
                s.username.trim(),
                s.sessionToken.ifBlank { null }
            )
            applyOutcomeWithOptionalTotp(outcome, s.twoFactorSecret)
        }
    }

    fun pickUsername(username: String, onComplete: (com.feedpilot.client.common.Resource<Unit>) -> Unit) {
        viewModelScope.launch {
            val res = accountRepository.pickUsername(username)
            onComplete(res)
        }
    }

    fun onTwoFactorCode(raw: String) {
        val digits = raw.filter(Char::isDigit).take(TwoFactorUiState.MAX_CODE_LENGTH)
        _state.update { s ->
            s.copy(twoFactor = s.twoFactor?.copy(code = digits, error = null))
        }
    }

    /** Sends the code the user typed, keeping the prompt open so a rejected code can be retyped. */
    fun submitTwoFactorCode() {
        val prompt = _state.value.twoFactor ?: return
        if (prompt.submitting) return
        if (prompt.code.length < TwoFactorUiState.MIN_CODE_LENGTH) {
            _state.update {
                it.copy(twoFactor = prompt.copy(error = "Enter the ${TwoFactorUiState.MIN_CODE_LENGTH}-digit code"))
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(twoFactor = prompt.copy(submitting = true, error = null)) }
            val outcome = if (prompt.emailChallenge != null) {
                accountRepository.submitEmailCode(prompt.emailChallenge, prompt.code)
            } else if (prompt.challenge != null) {
                accountRepository.submitTwoFactorCode(prompt.challenge, prompt.code)
            } else return@launch
            applyOutcome(outcome, retryOf = prompt)
        }
    }

    fun dismissTwoFactor() {
        _state.update { it.copy(twoFactor = null, isSaving = false) }
    }

    fun resendEmailCode() {
        val prompt = _state.value.twoFactor ?: return
        val emailChallenge = prompt.emailChallenge ?: return
        if (prompt.submitting) return
        viewModelScope.launch {
            _state.update { it.copy(twoFactor = prompt.copy(submitting = true, error = null)) }
            val updatedChallenge = accountRepository.resendEmailCode(emailChallenge)
            _state.update { s ->
                s.copy(twoFactor = prompt.copy(
                    emailChallenge = updatedChallenge,
                    submitting = false,
                    error = "A new verification code was sent to ${updatedChallenge.hint}!"
                ))
            }
        }
    }

    fun consumeDuplicateToast() {
        _state.update { it.copy(duplicateToast = null) }
    }

    /**
     * @param retryOf the prompt this outcome answers, when it came from submitting a code. A
     *   failure then belongs on the prompt — reporting it on the form behind a dismissed dialog is
     *   what made a wrong code look like the login silently doing nothing.
     */
    private fun applyOutcome(outcome: AddAccountOutcome, retryOf: TwoFactorUiState? = null) {
        _state.update { s ->
            when (outcome) {
                AddAccountOutcome.Added ->
                    s.copy(isSaving = false, saved = true, twoFactor = null, error = null)

                is AddAccountOutcome.NeedsTwoFactor -> s.copy(
                    isSaving = false,
                    error = null,
                    twoFactor = TwoFactorUiState(challenge = outcome.challenge)
                )

                is AddAccountOutcome.NeedsEmailCode -> s.copy(
                    isSaving = false,
                    error = null,
                    twoFactor = TwoFactorUiState(emailChallenge = outcome.challenge)
                )

                is AddAccountOutcome.Failed -> if (retryOf != null) {
                    s.copy(isSaving = false, twoFactor = retryOf.copy(submitting = false, error = outcome.message))
                } else {
                    s.copy(isSaving = false, error = outcome.message)
                }

                is AddAccountOutcome.AlreadyExists -> {
                    // Web login opens this screen's ViewModel scoped to its own nav entry, so a
                    // toast requested from there would never reach a Context to show it against —
                    // the error banner/overlay both entry points already render is the one path
                    // guaranteed to reach the user regardless of which flow they came in through.
                    val msg = "Account already exists — @${outcome.username} is already linked"
                    s.copy(isSaving = false, twoFactor = null, error = msg, duplicateToast = msg)
                }
            }
        }
    }

    private suspend fun applyOutcomeWithOptionalTotp(
        outcome: AddAccountOutcome,
        twoFactorSecret: String,
        attemptsLeft: Int = 1
    ) {
        if (outcome is AddAccountOutcome.NeedsTwoFactor && twoFactorSecret.isNotBlank()) {
            val code = TotpCode.generate(twoFactorSecret)
            if (code == null) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        error = null,
                        twoFactor = TwoFactorUiState(
                            challenge = outcome.challenge,
                            error = "The 2FA secret key is not a valid authenticator secret."
                        )
                    )
                }
                return
            }

            _state.update {
                it.copy(
                    isSaving = true,
                    twoFactor = TwoFactorUiState(
                        challenge = outcome.challenge,
                        code = code,
                        submitting = true
                    )
                )
            }
            val submitted = accountRepository.submitTwoFactorCode(outcome.challenge, code)
            if (submitted is AddAccountOutcome.NeedsTwoFactor && attemptsLeft > 0) {
                applyOutcomeWithOptionalTotp(submitted, twoFactorSecret, attemptsLeft - 1)
            } else {
                applyOutcome(submitted, retryOf = _state.value.twoFactor)
            }
            return
        }

        applyOutcome(outcome)
    }
}

/**
 * Asks for the Instagram one-time code. Stays up across a rejected code so the user can simply
 * retype it — the challenge is still valid — and blocks dismissal only while a code is in flight.
 */
@Composable
private fun TwoFactorDialog(
    prompt: TwoFactorUiState,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onResendEmailCode: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val isEmail = prompt.emailChallenge != null
    val targetUsername = prompt.emailChallenge?.username ?: prompt.challenge?.username ?: ""
    val dialogTitle = if (isEmail) "Security Verification Required" else "Two-factor authentication"
    val sourceText = if (isEmail) {
        "sent to ${prompt.emailChallenge?.hint ?: "your email"}"
    } else {
        prompt.challenge?.hint?.let { "from $it" } ?: "Instagram is asking for"
    }

    AlertDialog(
        onDismissRequest = { if (!prompt.submitting) onDismiss() },
        icon = {
            Icon(
                if (isEmail) Icons.Filled.MarkEmailUnread else Icons.Filled.VerifiedUser,
                contentDescription = null,
                tint = Color(0xFF4CB5F9)
            )
        },
        title = { Text(dialogTitle, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the ${TwoFactorUiState.MIN_CODE_LENGTH}-digit code $sourceText to finish " +
                        "logging in as @$targetUsername.",
                    fontSize = 13.sp,
                    color = LoginColors.muted
                )

                OutlinedTextField(
                    value = prompt.code,
                    onValueChange = onCodeChange,
                    enabled = !prompt.submitting,
                    placeholder = { Text("000000", color = LoginColors.muted, letterSpacing = 4.sp) },
                    singleLine = true,
                    isError = prompt.error != null,
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp,
                        textAlign = TextAlign.Center
                    ),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = loginTextFieldColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )

                prompt.error?.let { err ->
                    Text(
                        err,
                        color = if (err.contains("sent")) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                if (isEmail && onResendEmailCode != null) {
                    TextButton(
                        onClick = onResendEmailCode,
                        enabled = !prompt.submitting,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    ) {
                        Text(
                            "Didn't receive code? Resend Email",
                            fontSize = 12.sp,
                            color = Color(0xFF4CB5F9),
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else if (!isEmail) {
                    Text(
                        "A recovery code from your saved list works here too.",
                        fontSize = 11.sp,
                        color = LoginColors.muted
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = prompt.canSubmit,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4CB5F9),
                    contentColor = Color.White
                )
            ) {
                if (prompt.submitting) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Verify", fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !prompt.submitting) {
                Text("Cancel", color = LoginColors.muted)
            }
        }
    )
}

@Composable
fun InstagramIcon(modifier: Modifier = Modifier) {
    val instagramGradient = Brush.linearGradient(
        colors = listOf(
            Color(0xFF833AB4),
            Color(0xFFFD1D1D),
            Color(0xFFF77737),
            Color(0xFFFFDC80)
        )
    )

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(instagramGradient)
            .padding(14.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(width = 3.5.dp, color = Color.White, shape = RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(width = 3.5.dp, color = Color.White, shape = CircleShape)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 4.dp)
                    .size(5.dp)
                    .background(Color.White, shape = CircleShape)
            )
        }
    }
}

@Composable
fun AddAccountScreen(
    onBack: () -> Unit,
    onAccountAdded: (() -> Unit)? = null,
    onWebLogin: ((twoFactorSecret: String) -> Unit)? = null,
    viewModel: AddAccountViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val instagramWebClient = remember(context) { com.feedpilot.client.data.remote.InstagramWebClient(okhttp3.OkHttpClient(), context) }

    var isSuggestionsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(state.duplicateToast) {
        state.duplicateToast?.let { msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            viewModel.consumeDuplicateToast()
        }
    }

    var showJsonDialog by remember { mutableStateOf(false) }
    var jsonInput by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }

    LaunchedEffect(state.saved) {
        if (state.saved) {
            onAccountAdded?.invoke() ?: onBack()
        }
    }

    var selectedTab by remember { mutableIntStateOf(0) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(LoginColors.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .imePadding()
        ) {
            PurpleTopBar(title = "Log in", onBack = onBack)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .height(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { selectedTab = 0 },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Suggestion",
                        color = if (selectedTab == 0) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { selectedTab = 1 },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Login",
                        color = if (selectedTab == 1) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (selectedTab == 0) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    UsernameSuggestionPanel(
                        instagramWebClient = instagramWebClient,
                        onUsernamePicked = { picked ->
                            viewModel.onUsername(picked)
                            selectedTab = 1
                        }
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(Modifier.height(12.dp))

                    // Top Language Selector Dropdown
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.clickable { }
                    ) {
                        Text(
                            text = "English (US)",
                            fontSize = 13.sp,
                            color = LoginColors.muted,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Select Language",
                            tint = LoginColors.muted,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Surface(
                        modifier = Modifier.size(84.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = AppTheme.brand.orange.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, AppTheme.brand.orange.copy(alpha = 0.45f))
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(AppTheme.brand.headerGradient),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.FlightTakeoff,
                                contentDescription = null,
                                tint = AppTheme.brand.headerContentColor,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(28.dp))

                    OutlinedTextField(
                        value = state.twoFactorSecret,
                        onValueChange = viewModel::onTwoFactorSecret,
                        placeholder = {
                            Text(
                                "2FA secret key (optional)",
                                color = LoginColors.muted,
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        leadingIcon = {
                            Icon(
                                Icons.Filled.VerifiedUser,
                                contentDescription = null,
                                tint = LoginColors.muted
                            )
                        },
                        colors = loginTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { onWebLogin?.invoke(state.twoFactorSecret) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AppTheme.brand.orange,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Language,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Log in via Web",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                        Text(
                            text = "  OR DIRECT LOGIN  ",
                            fontSize = 11.sp,
                            color = AppTheme.brand.orange,
                            fontWeight = FontWeight.Bold
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                    }

                    Spacer(Modifier.height(20.dp))

                    // Username Field
                    OutlinedTextField(
                        value = state.username,
                        onValueChange = viewModel::onUsername,
                        placeholder = {
                            Text(
                                "Username, email or mobile number",
                                color = LoginColors.muted,
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        colors = loginTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(12.dp))

                    // Password Field
                    OutlinedTextField(
                        value = state.sessionToken,
                        onValueChange = viewModel::onToken,
                        placeholder = {
                            Text(
                                "Password",
                                color = LoginColors.muted,
                                fontSize = 14.sp
                            )
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = if (showPassword) "Hide password" else "Show password",
                                    tint = LoginColors.muted
                                )
                            }
                        },
                        colors = loginTextFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(16.dp))

                    // Error Banner
                    state.error?.let { err ->
                        val urlMatch = Regex("https?://[^\\s]+").find(err)?.value

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onErrorContainer,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = err,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                                if (urlMatch != null) {
                                    OutlinedButton(
                                        onClick = { try { uriHandler.openUri(urlMatch) } catch (_: Exception) {} },
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open Verification Link", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    Button(
                        onClick = viewModel::save,
                        enabled = !state.isSaving,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Text("Log in", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    // Forgotten Password Link
                    Text(
                        text = "Forgotten password?",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = LoginColors.text,
                        modifier = Modifier
                            .clickable {
                                try {
                                    uriHandler.openUri("https://www.instagram.com/accounts/password/reset/")
                                } catch (_: Exception) {}
                            }
                            .padding(8.dp)
                    )

                    Spacer(Modifier.height(28.dp))

                    // Advanced Option: Paste JSON Session Cookies
                    TextButton(
                        onClick = {
                            val clipText = clipboardManager.getText()?.text ?: ""
                            if (clipText.startsWith("[") || clipText.startsWith("{")) {
                                viewModel.onToken(clipText)
                            } else {
                                jsonInput = state.sessionToken
                                showJsonDialog = true
                            }
                        }
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Code, contentDescription = null, modifier = Modifier.size(16.dp), tint = LoginColors.muted)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Import / Paste JSON Cookies",
                                fontSize = 12.sp,
                                color = LoginColors.muted,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }

        // Full Screen Progress Loader Overlay while Logging In
        if (state.isSaving) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LoginColors.surface,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF4CB5F9), strokeWidth = 3.dp)
                        Column {
                            Text("Connecting Instagram Account...", fontWeight = FontWeight.Bold)
                            Text("Validating session and profile details", fontSize = 12.sp, color = LoginColors.muted)
                        }
                    }
                }
            }
        }

        // Instagram accepted the password and wants a one-time code — ask for it here rather than
        // ending the login with a message the user can do nothing about.
        state.twoFactor?.let { prompt ->
            TwoFactorDialog(
                prompt = prompt,
                onCodeChange = viewModel::onTwoFactorCode,
                onSubmit = viewModel::submitTwoFactorCode,
                onResendEmailCode = viewModel::resendEmailCode,
                onDismiss = viewModel::dismissTwoFactor
            )
        }

        // JSON Cookie Import Dialog
        if (showJsonDialog) {
            AlertDialog(
                onDismissRequest = { showJsonDialog = false },
                title = { Text("Paste JSON Session Cookies", fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Paste raw JSON exported from browser extensions (Cookie-Editor, EditThisCookie, Get cookies.txt).",
                            fontSize = 12.sp,
                            color = LoginColors.muted
                        )
                        OutlinedTextField(
                            value = jsonInput,
                            onValueChange = { jsonInput = it },
                            placeholder = { Text("[{\"name\":\"sessionid\", ...}]") },
                            maxLines = 8,
                            minLines = 4,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.onToken(jsonInput)
                            showJsonDialog = false
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) { Text("Apply JSON Cookies", fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showJsonDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
