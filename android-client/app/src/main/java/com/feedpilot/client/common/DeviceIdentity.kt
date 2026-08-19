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

    val isOriginalApp: Boolean get() =
        context.packageName == ORIGINAL_PACKAGE_NAME && !isLikelyVirtualCloneRuntime()

    /** Stable UUID for original app recovery, and for detectable virtual-clone recovery. */
    val deviceUuid: String
        get() {
            prefs.getString(KEY_DEVICE_UUID, null)?.let { return it }
            val persistenceKey = identityPersistenceKey(KEY_DEVICE_UUID)
            val restored = persistenceKey?.let { mediaStoreStore.readToken(it, userHandleId) }
            if (restored != null) {
                prefs.edit().putString(KEY_DEVICE_UUID, restored).apply()
                return restored
            }

            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_UUID, fresh).apply()
            if (persistenceKey != null) mediaStoreStore.writeToken(persistenceKey, userHandleId, fresh)
            return fresh
        }

    /**
     * Distinct per clone / installation.
     *
     * Recovered only when the persistence key is precise enough for this runtime:
     * the original app uses the official package namespace, while detectable virtual-clone
     * runtimes use a hash of their container/runtime signals.
     *
     * Plain cloned APKs that simply change the package name still mint a fresh local ID on an
     * empty SharedPreferences read, which keeps separate clones from silently sharing the same
     * backend account/wallet. Cross-install account recovery remains the Backup Code flow
     * (SettingsViewModel.generateBackupCode/restoreWithBackupCode).
     */
    val installationId: String
        get() {
            val stored = prefs.getString(KEY_INSTALLATION_ID, null)
            if (stored != null) return stored

            val persistenceKey = identityPersistenceKey(KEY_INSTALLATION_ID)
            val restored = persistenceKey?.let { mediaStoreStore.readToken(it, userHandleId) }
            if (restored != null) {
                prefs.edit().putString(KEY_INSTALLATION_ID, restored).apply()
                return restored
            }

            val fresh = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALLATION_ID, fresh).apply()
            if (persistenceKey != null) mediaStoreStore.writeToken(persistenceKey, userHandleId, fresh)
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
            val persistenceKey = identityPersistenceKey(KEY_CLONE_APP_ID)
            val restored = persistenceKey?.let { mediaStoreStore.readToken(it, userHandleId) }
            if (restored != null) {
                prefs.edit().putString(KEY_CLONE_APP_ID, restored).apply()
                return restored
            }

            val fresh = "clone.${UUID.randomUUID()}"
            prefs.edit().putString(KEY_CLONE_APP_ID, fresh).apply()
            if (persistenceKey != null) mediaStoreStore.writeToken(persistenceKey, userHandleId, fresh)
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
     * Stable per physical device + app package + user container + install/runtime.
     *
     * Folds in [installationId] alongside the hardware/package/user-handle components: those
     * three alone are identical across clones produced by virtualization-style cloner tools
     * (see [installationId]'s doc), which used to let two clones share one claim-lock identity
     * even after they'd been given separate backend accounts. For the original app this
     * intentionally stays hardware-tied so a data clear/reinstall returns to the same device
     * account; clones keep [installationId] in the hash so clone installs remain distinct.
     */
    val stableAppInstallationId: String by lazy {
        if (isOriginalApp) {
            sha256Hex("$appId|user_$userHandleId|hardware_$hardwareDeviceId")
        } else {
            sha256Hex("$appId|user_$userHandleId|$deviceUuid|install_$installationId")
        }
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

    /**
     * Clone Hub and similar virtual cloners can keep reporting the original package name while
     * running us inside the cloner app's UID/container. Treat that environment as non-original so
     * recovery features stay limited to the direct FeedPilot install.
     */
    private fun isLikelyVirtualCloneRuntime(): Boolean {
        val packageName = context.packageName
        val uidPackages = runCatching {
            context.packageManager.getPackagesForUid(Process.myUid())?.toList().orEmpty()
        }.getOrDefault(emptyList())
        if (uidPackages.isNotEmpty() && uidPackages.none { it == packageName }) return true
        if (uidPackages.any { it.hasCloneRuntimeMarker() }) return true

        val appInfo = context.applicationInfo
        val runtimeValues = listOfNotNull(
            appInfo.dataDir,
            appInfo.sourceDir,
            appInfo.publicSourceDir,
            appInfo.nativeLibraryDir,
            appInfo.processName
        )
        return runtimeValues.any { it.hasCloneRuntimeMarker() }
    }

    private fun identityPersistenceKey(name: String): String? {
        if (isOriginalApp) {
            return if (name == KEY_DEVICE_UUID) ORIGINAL_PACKAGE_NAME else "$ORIGINAL_PACKAGE_NAME.$name"
        }
        return cloneRuntimeIdentityKey()?.let { "clone.$name.$it" }
    }

    private fun cloneRuntimeIdentityKey(): String? {
        if (!isLikelyVirtualCloneRuntime()) return null

        val appInfo = context.applicationInfo
        val packageName = context.packageName
        val uidPackages = runCatching {
            context.packageManager.getPackagesForUid(Process.myUid())?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val runtimeValues = listOfNotNull(
            appInfo.dataDir,
            appInfo.sourceDir,
            appInfo.publicSourceDir,
            appInfo.nativeLibraryDir,
            appInfo.processName
        )
        val mismatchedUidPackages = uidPackages
            .filter { it != packageName }
            .sorted()
        val markedValues = (uidPackages + runtimeValues)
            .filter { it.hasCloneRuntimeMarker() }
        val basis = (mismatchedUidPackages + markedValues + runtimeValues)
            .distinct()
            .joinToString("|")

        return basis.takeIf { it.isNotBlank() }?.let { sha256Hex(it).take(24) }
    }

    private fun String.hasCloneRuntimeMarker(): Boolean {
        val value = lowercase()
        return CLONE_RUNTIME_MARKERS.any { marker -> value.contains(marker) }
    }

    companion object {
        private const val KEY_DEVICE_UUID = "device_uuid"
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val KEY_CLONE_APP_ID = "clone_app_id"
        private const val ORIGINAL_PACKAGE_NAME = "com.feedpilot.client"
        private val CLONE_RUNTIME_MARKERS = listOf(
            "clonehub",
            "clone.hub",
            "appclone",
            "app.clone",
            "parallel",
            "dualspace",
            "dual.space",
            "multispace",
            "multi.space",
            "virtualapp",
            "virtual.app",
            "vmos",
            "mumu"
        )
        private val WIDEVINE_UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
    }
}


