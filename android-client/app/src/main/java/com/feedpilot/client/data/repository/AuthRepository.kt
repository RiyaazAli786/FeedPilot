package com.feedpilot.client.data.repository

import com.feedpilot.client.common.BackupCodeFileStore
import com.feedpilot.client.common.DeviceIdentity
import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.SecureStorage
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: ApiService,
    private val secureStorage: SecureStorage,
    private val deviceIdentity: DeviceIdentity,
    private val backupCodeFileStore: BackupCodeFileStore
) {
    // Seeded from encrypted storage, so a returning user stays signed in across launches.
    private val _isLoggedIn = MutableStateFlow(
        secureStorage.get(SecureStorage.KEY_ACCESS_TOKEN) != null
    )
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    private val _userEmail = MutableStateFlow(secureStorage.get(SecureStorage.KEY_USER_EMAIL))
    /** Email of the signed-in user, shown on the logout confirmation. */
    val userEmail: StateFlow<String?> = _userEmail
    private val recoverDeviceSessionAfterExpiry = AtomicBoolean(false)

    /**
     * Queues a password reset for an admin to action on the dashboard. The backend answers
     * identically whether or not the address exists, so this never reveals account existence.
     */
    suspend fun forgotPassword(email: String): Resource<String> = try {
        val res = api.forgotPassword(ForgotPasswordRequest(email.trim()))
        Resource.Success(res.message)
    } catch (t: Throwable) {
        Resource.Error(t.message ?: "Could not send the reset request", t)
    }

    suspend fun login(email: String, password: String): Resource<Unit> = safeAuth {
        api.login(LoginRequest(email, password))
    }

    suspend fun register(name: String, email: String, password: String): Resource<Unit> = safeAuth {
        api.register(RegisterRequest(name, email, password))
    }

    /**
     * Turns the current passwordless device account into one the user can log back into after a
     * reinstall — see AuthController.Claim. Keeps the same account/wallet; only sets a real
     * email + password on it, so [login] can recover it later even if device auth lands on a
     * different account next time (a different device, or the same device with a hardware
     * fingerprint that didn't survive the reinstall).
     */
    suspend fun claimAccount(email: String, password: String): Resource<Unit> = safeAuth {
        api.claimAccount(ClaimAccountRequest(email, password))
    }

    /**
     * Derives the opaque (email, password) pair a Backup Code maps to. Shared by
     * [generateBackupCode] and [restoreWithBackupCode] so the mapping can never drift between the
     * two, and by every screen that offers either action (Settings, and StartScreen's
     * brand-new-install prompt) so they all resolve the same code to the same account. The
     * @backup.feedpilot domain (not @device.feedpilot) is what makes [isDeviceAccountSession]
     * correctly report false for these accounts, keeping the device-fingerprint self-heal in
     * RootViewModel from ever silently swapping a restored session back to a fresh device-bound one.
     */
    private fun backupCredentialsFor(code: String): Pair<String, String> =
        "backup-$code@backup.feedpilot" to code

    /**
     * Single-tap data-recovery setup: mints a random code and uses it as opaque credentials
     * against the existing email+password [claimAccount]/[AuthController.Claim] endpoint,
     * without ever surfacing "email" or "password" to the user — the code IS the credential.
     *
     * Also saves a plain-text copy to the device's public Downloads folder via
     * [BackupCodeFileStore] — a fallback for an operator who never copies the code shown on
     * screen. See that class's doc for why this is safe (write-only, never auto-read) despite
     * living in storage a same-package clone could otherwise reach.
     */
    suspend fun generateBackupCode(): Resource<String> {
        if (!deviceIdentity.isOriginalApp) {
            return Resource.Error("Backup and restore are only available in the original FeedPilot app.")
        }
        val code = java.util.UUID.randomUUID().toString()
        val (email, password) = backupCredentialsFor(code)
        return when (val res = claimAccount(email, password)) {
            is Resource.Success -> {
                backupCodeFileStore.save(code)
                Resource.Success(code)
            }
            is Resource.Error -> res
            Resource.Loading -> Resource.Loading
        }
    }

    /** Restores the account tied to a previously generated Backup Code — see [generateBackupCode]. */
    suspend fun restoreWithBackupCode(code: String): Resource<Unit> {
        if (!deviceIdentity.isOriginalApp) {
            return Resource.Error("Restore is only available in the original FeedPilot app.")
        }
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return Resource.Error("Enter a backup code.")
        val (email, password) = backupCredentialsFor(trimmed)
        return login(email, password)
    }

    /**
     * Signs the app in automatically with no login screen: if there is no session yet, it
     * claims (or reclaims) the account tied to this official FeedPilot device via passwordless
     * device auth. The same device binding is recovered after clear data or reinstall.
     */
    suspend fun ensureDeviceSession(appVersion: String): Boolean {
        if (secureStorage.get(SecureStorage.KEY_ACCESS_TOKEN) != null) {
            _isLoggedIn.value = true
            registerDevice(appVersion)
            ensureBackupCode()
            return true
        }
        if (deviceIdentity.isOriginalApp) {
            val restored = backupCodeFileStore.readLatestCode()
                ?.let { restoreWithBackupCode(it) } is Resource.Success
            if (restored) {
                registerDevice(appVersion)
                return true
            }
        }
        return try {
            persist(api.deviceAuth(
                DeviceAuthRequest(
                    // See DeviceIdentity.accountBindingInstallationId: it is backed by a token
                    // outside app-private storage so this device can recover after clear data
                    // or reinstall.
                    installationId = deviceIdentity.accountBindingInstallationId,
                    deviceId = deviceIdentity.stableAppInstallationId,
                    appInstanceId = deviceIdentity.appInstanceId,
                    androidVersion = deviceIdentity.androidVersion,
                    appVersion = appVersion
                )
            ))
            registerDevice(appVersion)
            ensureBackupCode()
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Silently mints and saves a Backup Code the first time this install authenticates, so every
     * install always has a recoverable code — and its Downloads-folder safety-net copy via
     * [BackupCodeFileStore] — without the operator needing to know the feature exists or
     * remember to tap "Backup My Coins" themselves.
     *
     * Called from both branches of [ensureDeviceSession] (not just the fresh-auth one) so a
     * failed first attempt — a network hiccup right after device auth succeeded — gets retried on
     * a later launch instead of never running again, since the "already have a token" fast path
     * would otherwise skip it forever. The [SecureStorage.KEY_BACKUP_CODE] check keeps this a
     * cheap local read once a code exists, and [isDeviceAccountSession] skips the (harmless but
     * wasted) network round trip once this account has already been claimed — by this path or by
     * a manual Settings tap — since [AuthController.Claim] rejects a second claim outright.
     */
    private suspend fun ensureBackupCode() {
        if (!deviceIdentity.isOriginalApp) return
        if (secureStorage.get(SecureStorage.KEY_BACKUP_CODE) != null) return
        if (!isDeviceAccountSession()) return
        when (val res = generateBackupCode()) {
            is Resource.Success -> secureStorage.put(SecureStorage.KEY_BACKUP_CODE, res.data)
            else -> Unit // best-effort — a manual "Backup My Coins" tap in Settings still covers this if it keeps failing
        }
    }

    suspend fun logout() {
        runCatching { api.logout() }
        secureStorage.clear()
        _isLoggedIn.value = false
        _userEmail.value = null
    }

    suspend fun deleteCurrentAccount(): Resource<Unit> = try {
        api.deleteCurrentAccount()
        secureStorage.clear()
        _isLoggedIn.value = false
        _userEmail.value = null
        Resource.Success(Unit)
    } catch (t: Throwable) {
        Resource.Error(t.message ?: "Could not delete account", t)
    }

    /** The access token currently stamped on requests, if any. */
    fun currentAccessToken(): String? = secureStorage.get(SecureStorage.KEY_ACCESS_TOKEN)

    /**
     * Exchanges the stored refresh token for a fresh access token. Returns the new access
     * token, or null when there is no refresh token or the backend rejects it. Called from the
     * OkHttp [TokenAuthenticator] the moment a request comes back 401, so an expired access
     * token recovers transparently instead of failing the call.
     */
    sealed interface RefreshResult {
        data class Success(val token: String) : RefreshResult
        object InvalidSession : RefreshResult
        object NetworkError : RefreshResult
    }

    suspend fun refreshTokensResult(): RefreshResult {
        val refresh = secureStorage.get(SecureStorage.KEY_REFRESH_TOKEN) ?: return RefreshResult.InvalidSession
        return try {
            val res = api.refresh(RefreshRequest(refresh))
            persist(res)
            RefreshResult.Success(res.accessToken)
        } catch (t: Throwable) {
            if (t is retrofit2.HttpException && (t.code() == 401 || t.code() == 400)) {
                RefreshResult.InvalidSession
            } else {
                RefreshResult.NetworkError
            }
        }
    }

    /**
     * Exchanges the stored refresh token for a fresh access token. Returns the new access
     * token, or null when there is no refresh token or the backend rejects it.
     */
    suspend fun refreshTokens(): String? {
        return when (val res = refreshTokensResult()) {
            is RefreshResult.Success -> res.token
            else -> null
        }
    }

    /**
     * Ends the session locally when the refresh token is also dead (expired, or the backend
     * was reset). Flipping [isLoggedIn] sends the UI back to the login screen instead of
     * looping on 401s.
     */
    fun onSessionExpired() {
        secureStorage.clear()
        recoverDeviceSessionAfterExpiry.set(true)
        _isLoggedIn.value = false
        _userEmail.value = null
    }

    fun consumeDeviceSessionRecoveryRequest(): Boolean =
        recoverDeviceSessionAfterExpiry.getAndSet(false)

    fun isDeviceAccountSession(): Boolean =
        secureStorage.get(SecureStorage.KEY_USER_EMAIL)
            ?.endsWith("@device.feedpilot", ignoreCase = true) != false

    suspend fun reloadDeviceSession(appVersion: String): Boolean {
        if (!isDeviceAccountSession()) return false
        return try {
            val res = api.deviceAuth(
                DeviceAuthRequest(
                    // Must match ensureDeviceSession's DeviceAuthRequest — same account lookup.
                    installationId = deviceIdentity.accountBindingInstallationId,
                    deviceId = deviceIdentity.stableAppInstallationId,
                    appInstanceId = deviceIdentity.appInstanceId,
                    androidVersion = deviceIdentity.androidVersion,
                    appVersion = appVersion
                )
            )
            persist(res)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Registers this installation with the backend for multi-instance tracking. */
    suspend fun registerDevice(
        appVersion: String,
        activeAccount: String? = null,
        loggedInAccounts: List<String>? = null
    ) {
        runCatching {
            api.registerDevice(
                RegisterDeviceRequest(
                    // Dashboard/device rows use the same clone-scoped id as order claims and
                    // request headers. Original app recovery happens before this through the
                    // backup-code restore path; clones intentionally get a fresh private id.
                    deviceId = deviceIdentity.stableAppInstallationId,
                    installationId = deviceIdentity.hardwareDeviceId,
                    appInstanceId = deviceIdentity.appInstanceId,
                    androidVersion = deviceIdentity.androidVersion,
                    appVersion = appVersion,
                    deviceModel = deviceIdentity.deviceLabel,
                    activeAccount = activeAccount,
                    loggedInAccounts = loggedInAccounts
                )
            )
        }
    }

    private inline fun safeAuth(block: () -> AuthResponse): Resource<Unit> = try {
        persist(block())
        Resource.Success(Unit)
    } catch (t: Throwable) {
        Resource.Error(t.message ?: "Authentication failed", t)
    }

    /** Stores a fresh set of tokens and marks the session active. */
    private fun persist(res: AuthResponse): AuthResponse {
        secureStorage.put(SecureStorage.KEY_ACCESS_TOKEN, res.accessToken)
        secureStorage.put(SecureStorage.KEY_REFRESH_TOKEN, res.refreshToken)
        secureStorage.put(SecureStorage.KEY_USER_ID, res.userId)
        secureStorage.put(SecureStorage.KEY_USER_EMAIL, res.email)
        secureStorage.put(SecureStorage.KEY_USER_NAME, res.name)
        _isLoggedIn.value = true
        _userEmail.value = res.email
        return res
    }
}
