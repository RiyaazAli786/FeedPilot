package com.feedpilot.client.common

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-encrypted key/value storage backed by Jetpack Security's EncryptedSharedPreferences.
 * Used for auth tokens and encrypted account session payloads.
 *
 * Opening the encrypted store can fail on a real device even though it always succeeds on an
 * emulator, and it used to take the whole app down with it: AuthRepository reads a token in its
 * constructor, so the throw landed on the main thread before the first frame and the app died
 * on launch with no visible error. Known causes:
 *
 *  - The prefs file was restored from a backup (Smart Switch / Google) but the master key it
 *    was encrypted under lives in the Android Keystore and is never restored with it.
 *  - The keystore entry was invalidated by a lockscreen change or a Knox/secure-folder action.
 *  - The keyset was left half-written by a kill during first-run key generation.
 *
 * None of those are worth crashing over: everything kept here is re-derivable (the session is
 * re-obtained from the backend by device auth, which binds to the hardware id, so the user
 * keeps their account and coins). So a failure is recovered from by discarding the unreadable
 * keyset and starting a fresh one, and if even that fails the app runs on plain prefs rather
 * than not running at all.
 */
@Singleton
class SecureStorage @Inject constructor(@ApplicationContext private val context: Context) {

    private val prefs: SharedPreferences by lazy { openResilient() }

    fun put(key: String, value: String?) {
        runCatching { prefs.edit().putString(key, value).apply() }
            .onFailure { Log.e(TAG, "put($key) failed", it) }
    }

    fun get(key: String): String? =
        runCatching { prefs.getString(key, null) }
            .onFailure { Log.e(TAG, "get($key) failed", it) }
            .getOrNull()

    fun clear() {
        runCatching { prefs.edit().clear().apply() }
            .onFailure { Log.e(TAG, "clear() failed", it) }
    }

    /**
     * Opens the encrypted store, and on failure wipes the unreadable keyset and tries once
     * more before giving up and falling back to unencrypted prefs.
     */
    private fun openResilient(): SharedPreferences {
        createEncrypted()?.let { return it }

        Log.e(TAG, "Encrypted prefs unreadable - discarding the keyset and regenerating")
        discardKeyset()
        createEncrypted()?.let { return it }

        // Last resort. Losing encryption is bad; refusing to start is worse, and the fallback
        // only ever holds a token that device auth will re-issue on the next launch anyway.
        Log.e(TAG, "Falling back to plain prefs - secure storage unavailable on this device")
        return context.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
    }

    private fun createEncrypted(): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            Constants.SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ).also {
            // create() can succeed while the first actual read is what blows up on a keyset
            // that decrypts to garbage, so prove the store works before handing it out.
            it.getString(KEY_ACCESS_TOKEN, null)
        }
    } catch (e: KeyPermanentlyInvalidatedException) {
        Log.e(TAG, "Master key invalidated", e)
        null
    } catch (e: Throwable) {
        // Deliberately Throwable: Tink surfaces this as GeneralSecurityException, IOException,
        // KeyStoreException, IllegalArgumentException and, on some OEM keystores, as an Error.
        Log.e(TAG, "Could not open encrypted prefs", e)
        null
    }

    /** Removes the encrypted file and the keystore entry so the next create() starts clean. */
    private fun discardKeyset() {
        runCatching { context.deleteSharedPreferences(Constants.SECURE_PREFS) }
            .onFailure { Log.e(TAG, "Could not delete ${Constants.SECURE_PREFS}", it) }
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                .deleteEntry(MASTER_KEY_ALIAS)
        }.onFailure { Log.e(TAG, "Could not delete the master key entry", it) }
    }

    companion object {
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_USER_NAME = "user_name"
        /** Cached locally so Settings can display an already-generated Backup Code again
         *  without needing to regenerate one; the code itself lives on the backend as the
         *  account's password, this is just a display convenience. */
        const val KEY_BACKUP_CODE = "backup_code"

        private const val TAG = "SecureStorage"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private val MASTER_KEY_ALIAS: String = MasterKey.DEFAULT_MASTER_KEY_ALIAS
        private const val FALLBACK_PREFS = "feedpilot_prefs_fallback"
    }
}
