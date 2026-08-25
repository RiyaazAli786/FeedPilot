package com.feedpilot.client.common

import android.content.Context
import android.os.Process
import android.system.Os
import android.util.Log
import com.feedpilot.client.data.local.AccountDao
import com.feedpilot.client.data.local.AccountLogDao
import com.feedpilot.client.data.local.PendingEarningDao
import com.feedpilot.client.data.local.TaskDao
import com.feedpilot.client.data.local.WalletDao
import com.feedpilot.client.data.local.WithdrawalDao
import com.feedpilot.client.data.local.dao.OrderHistoryDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloneDataIsolationGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val taskDao: TaskDao,
    private val accountLogDao: AccountLogDao,
    private val orderHistoryDao: OrderHistoryDao,
    private val walletDao: WalletDao,
    private val withdrawalDao: WithdrawalDao,
    private val pendingEarningDao: PendingEarningDao
) {
    suspend fun enforce() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedSchemaVersion = prefs.getInt(KEY_IDENTITY_SCHEMA_VERSION, 0)
        if (savedSchemaVersion < CURRENT_IDENTITY_SCHEMA_VERSION) {
            Log.w(
                TAG,
                "Identity schema changed $savedSchemaVersion->$CURRENT_IDENTITY_SCHEMA_VERSION; clearing stale local identity data"
            )
            wipeCopiedLocalState()
            prefs.edit()
                .clear()
                .putInt(KEY_IDENTITY_SCHEMA_VERSION, CURRENT_IDENTITY_SCHEMA_VERSION)
                .apply()
        }

        val alias = prefs.getString(KEY_ALIAS, null)
        val savedContainer = prefs.getString(KEY_CONTAINER_FINGERPRINT, null)
        val savedRuntime = prefs.getString(KEY_RUNTIME_FINGERPRINT, null)
        val savedNonce = prefs.getString(KEY_CONTAINER_NONCE, null)
        val hasLegacyGuardWithoutContainer = alias != null && savedContainer == null && savedNonce == null
        val currentContainer = currentContainerFingerprint()
        val currentRuntime = currentRuntimeFingerprint()
        Log.i(
            TAG,
            "guard schema=$savedSchemaVersion alias=${alias != null} savedContainer=${savedContainer?.take(10)} currentContainer=${currentContainer.take(10)} savedRuntime=${savedRuntime?.take(10)} currentRuntime=${currentRuntime.take(10)} uid=${Process.myUid()} dataDir=${context.applicationInfo.dataDir}"
        )

        if (hasLegacyGuardWithoutContainer && containerPathLooksVirtualized()) {
            Log.w(TAG, "Clone data guard detected a copied legacy install in a virtual container; clearing local app data")
            wipeCopiedLocalState()
            prefs.edit().clear().apply()
        } else if (savedContainer != null && savedContainer != currentContainer) {
            Log.w(TAG, "Clone data guard container changed; clearing copied local app data")
            wipeCopiedLocalState()
            prefs.edit().clear().apply()
        } else if (savedRuntime == null && alias != null && containerPathLooksVirtualized()) {
            Log.w(TAG, "Clone data guard detected copied install in a virtual container; clearing local app data")
            wipeCopiedLocalState()
            prefs.edit().clear().apply()
        } else if (savedRuntime != null && savedRuntime != currentRuntime) {
            Log.w(TAG, "Clone data guard runtime changed; clearing copied local app data")
            wipeCopiedLocalState()
            prefs.edit().clear().apply()
        } else if (savedNonce != null && !markerFile(savedNonce).readTextOrNull().contentEquals(savedContainer)) {
            Log.w(TAG, "Clone data guard marker file is missing or mismatched; clearing copied local app data")
            wipeCopiedLocalState()
            prefs.edit().clear().apply()
        }

        val currentAlias = prefs.getString(KEY_ALIAS, null)
        val currentNonce = prefs.getString(KEY_CONTAINER_NONCE, null)
        val currentFingerprint = currentContainer
        if (currentAlias == null || currentNonce == null) {
            // Whenever the guard generation is recreated — fresh install or a confirmed copy just
            // wiped above — the EC installation identity must be reset in lockstep with it.
            runCatching { deleteInstallationDataStore() }

            val freshAlias = "$KEY_ALIAS_PREFIX${UUID.randomUUID()}"
            val freshNonce = UUID.randomUUID().toString()
            writeMarker(freshNonce, currentFingerprint)
            prefs.edit()
                .putString(KEY_ALIAS, freshAlias)
                .putLong(KEY_CREATED_AT, System.currentTimeMillis())
                .putString(KEY_CONTAINER_FINGERPRINT, currentFingerprint)
                .putString(KEY_RUNTIME_FINGERPRINT, currentRuntime)
                .putString(KEY_CONTAINER_NONCE, freshNonce)
                .putInt(KEY_IDENTITY_SCHEMA_VERSION, CURRENT_IDENTITY_SCHEMA_VERSION)
                .apply()
        } else if (!markerFile(currentNonce).readTextOrNull().contentEquals(currentFingerprint)) {
            writeMarker(currentNonce, currentFingerprint)
        } else if (prefs.getString(KEY_RUNTIME_FINGERPRINT, null) == null) {
            prefs.edit()
                .putString(KEY_RUNTIME_FINGERPRINT, currentRuntime)
                .apply()
        } else if (prefs.getInt(KEY_IDENTITY_SCHEMA_VERSION, 0) < CURRENT_IDENTITY_SCHEMA_VERSION) {
            prefs.edit()
                .putInt(KEY_IDENTITY_SCHEMA_VERSION, CURRENT_IDENTITY_SCHEMA_VERSION)
                .apply()
        }
    }

    private suspend fun wipeCopiedLocalState() {
        runCatching { taskDao.clearAll() }
        runCatching { accountLogDao.clearAll() }
        runCatching { orderHistoryDao.clearAll() }
        runCatching { walletDao.clearTransactions() }
        runCatching { walletDao.clearWallet() }
        runCatching { withdrawalDao.clearAll() }
        runCatching { pendingEarningDao.clearAll() }
        runCatching { accountDao.clearAll() }

        runCatching { context.deleteDatabase("feedpilot.db") }

        listOf(
            "device_identity",
            "running_accounts",
            Constants.SECURE_PREFS,
            SECURE_FALLBACK_PREFS,
            "account_storage_owner",
            "wallet_storage_owner",
            "picked_usernames_cache",
            "picked_usernames",
            "clone_data_isolation"
        ).forEach { name ->
            runCatching {
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
                context.deleteSharedPreferences(name)
            }
        }

        runCatching { deleteInstallationDataStore() }

        val suffix = context.packageName.replace('.', '_')
        listOf(
            "accounts_backup_$suffix.json",
            "wallet_backup_$suffix.json"
        ).forEach { name ->
            runCatching { context.deleteFile(name) }
        }
    }

    private fun currentContainerFingerprint(): String = sha256Hex(
        listOf(
            context.packageName,
            context.applicationInfo.dataDir,
            context.filesDir.absolutePath,
            context.noBackupFilesDir.absolutePath,
            statIdentity(context.applicationInfo.dataDir),
            statIdentity(context.filesDir.absolutePath),
            statIdentity(context.noBackupFilesDir.absolutePath)
        ).joinToString("|")
    )

    private fun currentRuntimeFingerprint(): String = sha256Hex(
        listOf(
            context.packageName,
            context.applicationInfo.uid.toString(),
            Process.myUid().toString(),
            packageManagerUidFor(context.packageName).toString(),
            context.applicationInfo.sourceDir.orEmpty(),
            context.applicationInfo.publicSourceDir.orEmpty(),
            context.applicationInfo.nativeLibraryDir.orEmpty(),
            context.applicationInfo.dataDir,
            context.filesDir.absolutePath,
            context.noBackupFilesDir.absolutePath,
            statIdentity(context.applicationInfo.dataDir),
            statIdentity(context.filesDir.absolutePath),
            statIdentity(context.noBackupFilesDir.absolutePath),
            uidOwnerPackages().sorted().joinToString(",")
        ).joinToString("|")
    )

    private fun containerPathLooksVirtualized(): Boolean {
        if (Process.myUid() != context.applicationInfo.uid) return true
        if (Process.myUid() != packageManagerUidFor(context.packageName)) return true
        if (uidOwnerLooksVirtualized()) return true

        val paths = listOf(
            context.applicationInfo.sourceDir,
            context.applicationInfo.publicSourceDir,
            context.applicationInfo.nativeLibraryDir,
            context.applicationInfo.dataDir,
            context.filesDir.absolutePath,
            context.noBackupFilesDir.absolutePath
        ).joinToString("|").lowercase()

        return listOf(
            "clone",
            "cloner",
            "parallel",
            "dual",
            "multi",
            "virtual",
            "island",
            "sandbox",
            "clonehub",
            "clone_hub"
        ).any { marker -> paths.contains(marker) }
    }

    private fun uidOwnerLooksVirtualized(): Boolean {
        val uidPackages = uidOwnerPackages()
        if (uidPackages.isEmpty()) return false
        if (context.packageName !in uidPackages) return true

        return uidPackages
            .filterNot { it == context.packageName }
            .any { packageName ->
                CLONE_CONTAINER_MARKERS.any { marker ->
                    packageName.lowercase().contains(marker)
                }
            }
    }

    private fun uidOwnerPackages(): List<String> =
        runCatching {
            context.packageManager.getPackagesForUid(Process.myUid()).orEmpty().toList()
        }.getOrDefault(emptyList())

    @Suppress("DEPRECATION")
    private fun packageManagerUidFor(packageName: String): Int =
        runCatching {
            context.packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrDefault(context.applicationInfo.uid)

    private fun statIdentity(path: String?): String {
        if (path.isNullOrBlank()) return "-"
        return runCatching {
            val stat = Os.stat(path)
            "${stat.st_dev}:${stat.st_ino}:${stat.st_uid}:${stat.st_gid}"
        }.getOrDefault("-")
    }

    private fun markerFile(nonce: String): File =
        File(context.noBackupFilesDir, "$MARKER_PREFIX$nonce")

    private fun writeMarker(nonce: String, fingerprint: String) {
        runCatching {
            context.noBackupFilesDir.mkdirs()
            markerFile(nonce).writeText(fingerprint)
        }.onFailure { Log.w(TAG, "Could not write clone data guard marker", it) }
    }

    private fun File.readTextOrNull(): String? =
        runCatching { takeIf { it.isFile }?.readText()?.trim() }.getOrNull()

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Deletes InstallationRepository's Preferences DataStore file directly, rather than injecting
     * InstallationRepository itself here — that would create a circular Hilt dependency (that
     * repository is meant to run only after this guard has finished). Jetpack Preferences DataStore
     * has stored its backing file at this path (`filesDir/datastore/<name>.preferences_pb`) since
     * androidx.datastore 1.0; this must be revisited if that on-disk convention ever changes.
     */
    private fun deleteInstallationDataStore() {
        File(context.filesDir, "datastore/$INSTALLATION_DATASTORE_FILE").delete()
    }

    private companion object {
        const val TAG = "CloneDataIsolation"
        const val PREFS_NAME = "clone_data_isolation"
        const val KEY_ALIAS = "keystore_alias"
        const val KEY_CREATED_AT = "created_at"
        const val KEY_CONTAINER_FINGERPRINT = "container_fingerprint"
        const val KEY_RUNTIME_FINGERPRINT = "runtime_fingerprint_v2"
        const val KEY_CONTAINER_NONCE = "container_nonce"
        const val KEY_IDENTITY_SCHEMA_VERSION = "identity_schema_version"
        const val CURRENT_IDENTITY_SCHEMA_VERSION = 3
        const val KEY_ALIAS_PREFIX = "feedpilot_clone_guard_"
        const val MARKER_PREFIX = "feedpilot_clone_guard_"
        const val SECURE_FALLBACK_PREFS = "feedpilot_prefs_fallback"
        const val INSTALLATION_DATASTORE_FILE = "installation_identity_store.preferences_pb"
        const val ORIGINAL_PACKAGE_NAME = "com.feedpilot.client"
        val CLONE_CONTAINER_MARKERS = listOf(
            "clone",
            "cloner",
            "parallel",
            "dual",
            "multi",
            "virtual",
            "island",
            "sandbox",
            "clonehub",
            "clone_hub",
            "multibox"
        )
    }
}
