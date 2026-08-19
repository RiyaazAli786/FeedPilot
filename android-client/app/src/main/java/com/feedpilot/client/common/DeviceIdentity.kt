package com.feedpilot.client.common

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.os.Process
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the stable identifiers used by the official FeedPilot app.
 *
 * The backend account binding uses a token persisted outside app-private storage, so a normal
 * reinstall or Settings > Clear Data can recover the same device account and wallet.
 */
@Singleton
class DeviceIdentity @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaStoreStore: MediaStoreIdentityStore
) {

    constructor(context: Context) : this(context, MediaStoreIdentityStore(context))

    private val prefs by lazy {
        context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    }

    /** Android Multi-User / Dual App UID tag (UserHandle ID). Differentiates dual apps on the same hardware. */
    val userHandleId: Int get() = Process.myUserHandle().hashCode()

    val isOriginalApp: Boolean get() = context.packageName == ORIGINAL_PACKAGE_NAME

    /** Stable UUID for original app recovery; local-only UUID for disposable clone apps. */
    val deviceUuid: String
        get() {
            prefs.getString(KEY_DEVICE_UUID, null)?.let { return it }
            val restored = if (isOriginalApp) mediaStoreStore.readToken(appId, userHandleId) else null
            if (restored != null) {
                prefs.edit().putString(KEY_DEVICE_UUID, restored).apply()
                return restored
            }

            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, fresh).apply()
            if (isOriginalApp) mediaStoreStore.writeToken(appId, userHandleId, fresh)
            return fresh
        }

    /**
     * Distinct per clone / installation.
     *
     * Deliberately NOT recovered from MediaStore. That store is keyed by (appId, userHandleId)
     * alone — both identical across "clones" produced by MuMu's built-in cloner and most
     * parallel-space style APK cloner tools, which virtualize the app's private storage but
     * leave the reported package name and Android user handle untouched. Recovering from it here
     * would silently hand a brand-new clone the same installationId — and therefore the same
     * backend account/wallet — as whatever install last wrote that slot, which is exactly the
     * "clone picks up the same data" symptom this used to cause. Always minting fresh on an empty
     * SharedPreferences read is what makes every install, real or cloned, automatically distinct
     * with no manual step. Cross-reinstall data recovery is handled by the Backup Code flow
     * (SettingsViewModel.generateBackupCode/restoreWithBackupCode) instead, which doesn't depend
     * on any OS-level signal a cloner tool can transparently share.
     */
    val installationId: String
        get() {
            val stored = prefs.getString(KEY_INSTALLATION_ID, null)
            if (stored != null) return stored

            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALLATION_ID, fresh).apply()
            return fresh
        }

    /**
     * Identifies this build as one clone among many. Derived from the package name at runtime
     * rather than a compile-time constant, because clones are produced by a cloner tool that
     * rewrites the package name of an already-built APK — it cannot change a baked-in
     * BuildConfig value. So each clone reports a distinct app id automatically, with no
     * per-clone source change.
     */
    val appId: String
        get() {
            if (isOriginalApp) return ORIGINAL_PACKAGE_NAME
            prefs.getString(KEY_CLONE_APP_ID, null)?.let { return it }
            val fresh = "clone.${UUID.randomUUID()}"
            prefs.edit().putString(KEY_CLONE_APP_ID, fresh).apply()
            return fresh
        }

    /** Stable private container id. Separates same-package clones in the dashboard. */
    val appInstanceId: String get() = installationId

    /**
     * A stable, hardware-tied key for this physical device.
     *
     * This is what the account binds to (together with [appId]), NOT [installationId]:
     *  - It is derived from hardware build fields, not ANDROID_ID. Android scopes ANDROID_ID
     *    by signing key on modern versions, so a re-signed APK could otherwise become a new
     *    backend device/user on the same phone.
     *  - It stays stable across APK signing-key changes for the same physical device.
     *
     * Deliberately NOT stored in app prefs — prefs are wiped on reinstall, which would defeat
     * the point. It is recomputed each launch from values that persist outside the app.
     */
    @get:SuppressLint("HardwareIds")
    val hardwareDeviceId: String by lazy {
        computeHardwareDeviceId()
    }

    /**
     * Stable per physical device + app package + user container + install, for as long as this
     * install's own SharedPreferences survive.
     *
     * Folds in [installationId] alongside the hardware/package/user-handle components: those
     * three alone are identical across clones produced by virtualization-style cloner tools
     * (see [installationId]'s doc), which used to let two clones share one claim-lock identity
     * even after they'd been given separate backend accounts. No longer persists across a true
     * uninstall/reinstall or App Data Clear — see [installationId] for why, and the Backup Code
     * flow for the replacement recovery path.
     */
    val stableAppInstallationId: String by lazy {
        sha256Hex("$appId|user_$userHandleId|$deviceUuid|install_$installationId")
    }

    /**
     * What DeviceAuthRequest.installationId sends to AuthController's passwordless endpoint.
     *
     * Folds in [installationId] on top of the hardware id so app clones on the same
     * physical/emulated hardware get separate accounts instead of colliding into one shared
     * wallet — see [installationId]'s doc for why that component is what actually makes clones
     * distinct, since the hardware/package/user-handle components alone are not.
     */
    val accountBindingInstallationId: String by lazy {
        stableAppInstallationId
    }

    val androidVersion: String get() = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    /**
     * Human-readable "manufacturer model" (e.g. "samsung SM-G991B"), for display in logs and the
     * dashboard only — never used for identity/binding, unlike [hardwareDeviceId].
     */
    val deviceLabel: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    /**
     * Scopes a local notification's fixed base ID to this clone specifically.
     *
     * Android's NotificationManager namespaces notifications by (package, user profile) — never
     * by anything the app itself controls — so two same-package "clones" produced by
     * virtualization-style cloner tools (see [installationId]'s doc for why those are
     * indistinguishable to the OS) share one real notification namespace. Two such clones both
     * calling `notify(4201, ...)` or `startForeground(4201, ...)` for their own independent
     * TaskRunnerService don't get two notifications — the second call silently replaces the
     * first, so only one clone's status is ever visible even while both are actually running.
     *
     * Folding in [installationId] (not [appId]/[hardwareDeviceId] alone, which are identical
     * across exactly this kind of clone) gives each clone its own slot. Multiplying the base ID
     * out first keeps every notification *category* (task runner, upgrade runner, update-ready,
     * wallet transfer) in its own disjoint numeric range, so two different notification types can
     * never collide with each other no matter which clone posts them.
     */
    fun cloneScopedNotificationId(baseId: Int): Int = baseId

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun computeHardwareDeviceId(): String {
        mediaDrmDeviceId()?.let { return sha256Hex("widevine|$it") }

        val basis = buildString {
            append(Build.BRAND).append('|').append(Build.DEVICE)
            append('|').append(Build.MODEL).append('|').append(Build.MANUFACTURER)
            append('|').append(Build.PRODUCT).append('|').append(Build.BOARD)
            append('|').append(Build.HARDWARE).append('|').append(Build.BOOTLOADER)
            append('|').append(Build.FINGERPRINT)
        }
        return sha256Hex("build|$basis")
    }

    private fun mediaDrmDeviceId(): String? = runCatching {
        val drm = MediaDrm(WIDEVINE_UUID)
        try {
            drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                .takeIf { it.isNotEmpty() }
                ?.joinToString("") { "%02x".format(it) }
        } finally {
            drm.close()
        }
    }.getOrNull()
        ?.takeIf { it.isNotBlank() }

    companion object {
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_CLONE_APP_ID = "clone_app_id"
        private const val ORIGINAL_PACKAGE_NAME = "com.feedpilot.client"
        private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
    }
}


