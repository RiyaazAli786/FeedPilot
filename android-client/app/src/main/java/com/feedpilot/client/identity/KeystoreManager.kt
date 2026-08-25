package com.feedpilot.client.identity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages this installation's EC (secp256r1) Keystore identity — the cryptographic proof half of
 * IdentityIsolation.md's design. The private key never leaves Android Keystore; every method here
 * either reads the public certificate or asks Keystore to sign on the key's behalf.
 *
 * A fixed alias is used (not one derived from the installation id) per the design doc's guidance —
 * this key's lifecycle is instead tied to [com.feedpilot.client.common.CloneDataIsolationGuard],
 * which deletes and regenerates it whenever it concludes the local container was copied.
 */
@Singleton
class KeystoreManager @Inject constructor() {

    fun keyExists(alias: String = ALIAS): Boolean = runCatching {
        keyStore().containsAlias(alias)
    }.getOrDefault(false)

    /** Generates the EC key pair if absent; idempotent and local-only (no network). */
    fun ensureKeyPair(alias: String = ALIAS) {
        if (keyExists(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .build()
        )
        generator.generateKeyPair()
    }

    /** X.509 SubjectPublicKeyInfo of the installation's public key, Base64-encoded. */
    fun publicKeyBase64(alias: String = ALIAS): String {
        val certificate = keyStore().getCertificate(alias) ?: error("No certificate for Keystore alias $alias")
        return Base64.encodeToString(certificate.publicKey.encoded, Base64.NO_WRAP)
    }

    /** Signs with SHA256withECDSA. Keystore performs the signing internally — raw key material is never read. */
    fun sign(data: ByteArray, alias: String = ALIAS): ByteArray {
        val entry = keyStore().getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return Signature.getInstance("SHA256withECDSA").apply {
            initSign(entry.privateKey)
            update(data)
        }.sign()
    }

    fun deleteKey(alias: String = ALIAS) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "feedpilot_installation_identity"
    }
}
