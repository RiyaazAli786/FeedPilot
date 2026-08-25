package com.feedpilot.client.identity

import android.content.Context
import android.util.Base64
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.feedpilot.client.common.DeviceIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.installationDataStore by preferencesDataStore(name = "installation_identity_store")

/**
 * Foundational-phase installation identity repository (see IdentityIsolation.md). Deliberately
 * reuses [DeviceIdentity.installationId] verbatim rather than minting a second, competing UUID —
 * that value is already the single source of truth sent as the X-Installation-Id header and used
 * throughout the backend's Devices table, and this repository must not fork it.
 *
 * Every network-touching method here is best-effort: nothing in the app depends on it succeeding
 * in this phase, so failures are swallowed rather than propagated.
 */
@Singleton
class InstallationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceIdentity: DeviceIdentity,
    private val keystoreManager: KeystoreManager,
    private val installationApi: InstallationApi
) {
    private object Keys {
        val REGISTERED = booleanPreferencesKey("registered_with_backend")
    }

    fun currentInstallationId(): String = deviceIdentity.installationId

    /** Local-only Keystore keygen. No network. Must run after CloneDataIsolationGuard.enforce(). */
    fun ensureKeyMaterial() {
        keystoreManager.ensureKeyPair()
    }

    fun identitySnapshot(): InstallationIdentity =
        InstallationIdentity(currentInstallationId(), keystoreManager.publicKeyBase64())

    /**
     * Best-effort, idempotent registration; swallows every failure so no caller needs to guard it.
     *
     * [forceRefresh] is used immediately after an auth transition. The install may already have
     * uploaded its key before a user session existed, so forcing the same idempotent register call
     * lets the backend associate the AppInstallation row with the newly authenticated user.
     */
    suspend fun registerIfNeeded(forceRefresh: Boolean = false) {
        runCatching {
            val alreadyRegistered = context.installationDataStore.data
                .map { it[Keys.REGISTERED] ?: false }
                .first()
            if (alreadyRegistered && !forceRefresh) return@runCatching

            ensureKeyMaterial()
            installationApi.register(
                InstallationRegisterRequest(currentInstallationId(), keystoreManager.publicKeyBase64())
            )
            context.installationDataStore.edit { it[Keys.REGISTERED] = true }
        }
    }

    /** Signs a server-issued challenge. Unused by any caller in this phase — forward compatibility only. */
    fun signChallenge(challengeBase64: String): String =
        Base64.encodeToString(
            keystoreManager.sign(Base64.decode(challengeBase64, Base64.NO_WRAP)),
            Base64.NO_WRAP
        )
}
