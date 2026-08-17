package com.feedpilot.client.common

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID
import java.util.regex.Pattern
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object InstagramCrypto {

    /** Modern Android 14 Mobile Chrome User-Agent (Samsung Galaxy S23 Ultra) */
    const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"
    const val INSTAGRAM_APP_ID = "936619743392459"
    const val ASBD_ID = "359341"
    const val SHARED_DATA_URL = "https://www.instagram.com/api/v1/web/data/shared_data/"

    /** Client Hints matching Android 14 Mobile Chrome browser */
    const val SEC_CH_UA = "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\""
    const val SEC_CH_UA_MOBILE = "?1"
    const val SEC_CH_UA_PLATFORM = "\"Android\""
    const val SEC_CH_UA_PLATFORM_VERSION = "\"14.0.0\""
    const val SEC_CH_UA_MODEL = "\"SM-S918B\""
    const val ACCEPT_LANGUAGE = "en-US,en;q=0.9"

    data class DeviceFingerprint(
        val userAgent: String,
        val platform: String = "\"Android\"",
        val platformVersion: String,
        val model: String,
        val isMobile: String = "?1"
    )

    /**
     * Cache of the fingerprint built from the device's *real* WebView user agent — populated the
     * first time [getDeviceFingerprint] is called on the main thread (see [FeedPilotApp] pre-warm)
     * and reused for the rest of the process. `WebSettings.getDefaultUserAgent()` is main-thread
     * only; calling it from the IO-dispatcher coroutines that run authenticated Instagram calls
     * (current_user, users/{id}/info, …) throws there and used to silently fall back to a
     * hardcoded Chrome UA on every one of those calls — a different string than whatever real UA
     * the login WebView used when Instagram bound the session's cookies to it, which is exactly
     * what Instagram's "useragent mismatch" (400) response is complaining about. Caching only the
     * real-UA fingerprint (never the fallback) means every call after the first main-thread one
     * — regardless of which thread it runs on — sees the same UA the session was created with.
     */
    @Volatile
    private var cachedFingerprint: DeviceFingerprint? = null

    /**
     * Dynamically extracts the active host device's OS version, model, and system WebUserAgent.
     */
    fun getDeviceFingerprint(context: android.content.Context? = null): DeviceFingerprint {
        cachedFingerprint?.let { return it }
        return try {
            val androidRelease = android.os.Build.VERSION.RELEASE.ifBlank { "14" }
            val model = android.os.Build.MODEL.ifBlank { "SM-S918B" }

            val systemUserAgent = if (context != null) {
                try {
                    android.webkit.WebSettings.getDefaultUserAgent(context)
                } catch (_: Exception) { null }
            } else null

            val sanitized = sanitizeWebViewUserAgent(systemUserAgent)
            val userAgent = sanitized
                ?: "Mozilla/5.0 (Linux; Android $androidRelease; $model) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.6778.135 Mobile Safari/537.36"

            val fingerprint = DeviceFingerprint(
                userAgent = userAgent,
                platformVersion = "\"$androidRelease.0.0\"",
                model = "\"$model\""
            )
            // Only a fingerprint built from the real system UA is worth pinning for later calls;
            // a fallback-built one is left uncached so a subsequent main-thread call can still
            // populate the cache with the real value instead of being locked out by it.
            if (sanitized != null) cachedFingerprint = fingerprint
            fingerprint
        } catch (_: Exception) {
            DeviceFingerprint(
                userAgent = DEFAULT_USER_AGENT,
                platformVersion = SEC_CH_UA_PLATFORM_VERSION,
                model = SEC_CH_UA_MODEL
            )
        }
    }

    /**
     * Strips the tokens that mark a string as an Android WebView user agent.
     *
     * `WebSettings.getDefaultUserAgent()` returns something like
     * `…; SM-S918B Build/UP1A…; wv) AppleWebKit/537.36 … Version/4.0 Chrome/131… Mobile Safari…`.
     * The `; wv)` and `Version/4.0` tokens tell Instagram (and Google) the page is running inside
     * an embedded browser, which they refuse to serve the login form to — the WebView then shows
     * "this browser may not be secure" or just a blank page. Removing those tokens leaves a plain
     * mobile-Chrome UA that renders the real login page. Returns null for null/blank input so the
     * caller can fall back to its hard-coded Chrome string.
     */
    fun sanitizeWebViewUserAgent(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            // "…Build/XXX; wv)" and "…; wv)" -> "…)"
            .replace(Regex("\\s*Build/[^;)]+;?\\s*wv\\)"), ")")
            .replace("; wv)", ")")
            .replace(" wv)", ")")
            // The "Version/4.0" token only appears on WebView UAs, never on real Chrome.
            .replace(Regex("\\s*Version/\\d+\\.\\d+"), "")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    /**
     * Generates Instagram jazoest parameter: "2" + sum of ASCII values of given ID (or random UUID).
     */
    fun createJazoest(phoneId: String = ""): String {
        val id = phoneId.ifBlank { UUID.randomUUID().toString() }
        var sum = 0
        for (b in id.toByteArray(StandardCharsets.US_ASCII)) {
            sum += b.toInt() and 0xFF
        }
        return "2$sum"
    }

    /**
     * Generates Instagram AJAX identifier string.
     */
    fun generateInstagramAjax(): String {
        val rand = SecureRandom()
        val high = rand.nextInt(900_000_000) + 100_000_000
        val low = rand.nextInt(10)
        return "${high.toLong() * 10L + low}"
    }

    /**
     * Parses username from raw handle or Instagram profile URL.
     */
    fun parseUsername(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim().removePrefix("@")

        if (!trimmed.contains("instagram.com")) {
            return if (isValidUsername(trimmed)) trimmed else null
        }

        val pattern = Pattern.compile("instagram\\.com/([A-Za-z0-9._]+)", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(trimmed)
        if (matcher.find()) {
            val matched = matcher.group(1)
            if (matched != null && isValidUsername(matched)) {
                return matched
            }
        }
        return null
    }

    private fun isValidUsername(username: String): Boolean {
        return username.matches(Regex("^[A-Za-z0-9._]{1,30}$"))
    }

    /**
     * Extracts post shortcode from URL or returns clean code.
     */
    fun getCodeFromUrl(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val trimmed = input.trim()
        val matcher = Pattern.compile("(?:/p/|/reels/|/reel/|/tv/)([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE).matcher(trimmed)
        if (matcher.find()) {
            return matcher.group(1) ?: trimmed
        }
        val clean = trimmed.substringBefore("?").removePrefix("@").trim('/')
        val lastSegment = clean.substringAfterLast('/')
        return if (lastSegment.isNotBlank()) lastSegment else clean
    }

    /**
     * Converts Instagram post shortcode (e.g., "C123abc") into numeric Media ID.
     */
    fun getIdFromCode(shortcode: String): String {
        val code = getCodeFromUrl(shortcode)
        if (code.isBlank()) return ""

        val alphabet = mapOf(
            'A' to 0, 'B' to 1, 'C' to 2, 'D' to 3, 'E' to 4, 'F' to 5, 'G' to 6, 'H' to 7,
            'I' to 8, 'J' to 9, 'K' to 10, 'L' to 11, 'M' to 12, 'N' to 13, 'O' to 14, 'P' to 15,
            'Q' to 16, 'R' to 17, 'S' to 18, 'T' to 19, 'U' to 20, 'V' to 21, 'W' to 22, 'X' to 23,
            'Y' to 24, 'Z' to 25, 'a' to 26, 'b' to 27, 'c' to 28, 'd' to 29, 'e' to 30, 'f' to 31,
            'g' to 32, 'h' to 33, 'i' to 34, 'j' to 35, 'k' to 36, 'l' to 37, 'm' to 38, 'n' to 39,
            'o' to 40, 'p' to 41, 'q' to 42, 'r' to 43, 's' to 44, 't' to 45, 'u' to 46, 'v' to 47,
            'w' to 48, 'x' to 49, 'y' to 50, 'z' to 51, '0' to 52, '1' to 53, '2' to 54, '3' to 55,
            '4' to 56, '5' to 57, '6' to 58, '7' to 59, '8' to 60, '9' to 61, '-' to 62, '_' to 63
        )

        var id = 0UL
        for (c in code) {
            val valIndex = alphabet[c] ?: return code
            id = id * 64UL + valIndex.toULong()
        }
        return id.toString()
    }

    /**
     * Formats encrypted password string `#PWD_INSTAGRAM_BROWSER:{version}:{timestamp}:{encrypted_payload}`
     * performing AES-256-GCM password encryption and payload construction.
     */
    fun encryptInstagramPassword(
        password: String,
        keyId: Int,
        publicKeyHex: String,
        version: String = "10",
        timestamp: String = (System.currentTimeMillis() / 1000).toString()
    ): String {
        if (keyId == 0 || publicKeyHex.isBlank()) {
            return "#PWD_INSTAGRAM:0:$timestamp:$password"
        }
        return try {
            val rawKey = ByteArray(32)
            SecureRandom().nextBytes(rawKey)

            val iv = ByteArray(12) // all zeroes
            val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
            val aad = timestamp.toByteArray(StandardCharsets.UTF_8)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(rawKey, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            cipher.updateAAD(aad)

            val cipherOutput = cipher.doFinal(passwordBytes)

            val tagLen = 16
            val cipherTextLen = cipherOutput.size - tagLen
            val cipherText = ByteArray(cipherTextLen)
            val tag = ByteArray(tagLen)
            System.arraycopy(cipherOutput, 0, cipherText, 0, cipherTextLen)
            System.arraycopy(cipherOutput, cipherTextLen, tag, 0, tagLen)

            val publicKeyBytes = hexToBytes(publicKeyHex)

            // Wrap the AES key for Instagram's key exactly as the browser does: a libsodium sealed
            // box (X25519 + XSalsa20-Poly1305). The fake XOR that used to sit here produced a key
            // Instagram could never decrypt, so every direct login came back authenticated:false.
            val sealedKey = sealAesKey(rawKey, publicKeyBytes)
            val sealedKeyLen = sealedKey.size

            val baos = ByteArrayOutputStream()
            baos.write(1) // version flag
            baos.write(keyId)
            baos.write(sealedKeyLen and 0xFF)
            baos.write((sealedKeyLen shr 8) and 0xFF)
            baos.write(sealedKey)
            baos.write(tag)
            baos.write(cipherText)

            val resultBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            "#PWD_INSTAGRAM_BROWSER:$version:$timestamp:$resultBase64"
        } catch (e: Exception) {
            "#PWD_INSTAGRAM:0:$timestamp:$password"
        }
    }

    /**
     * libsodium `crypto_box_seal(rawKey, instagramPublicKey)`: an 80-byte sealed box
     * (ephemeral X25519 public + 16-byte MAC + the 32-byte key). Tink provides the vetted X25519;
     * the rest of the NaCl stack is [NaclSealedBox], pinned by libsodium test vectors.
     */
    private fun sealAesKey(rawKey: ByteArray, publicKey: ByteArray): ByteArray {
        // Clamp the ephemeral secret per RFC 7748 so base-point and shared use the identical
        // scalar — the box then opens correctly regardless of Tink's internal clamping.
        val ephemeralSecret = ByteArray(32).also { SecureRandom().nextBytes(it) }
        ephemeralSecret[0] = (ephemeralSecret[0].toInt() and 248).toByte()
        ephemeralSecret[31] = (ephemeralSecret[31].toInt() and 127).toByte()
        ephemeralSecret[31] = (ephemeralSecret[31].toInt() or 64).toByte()

        return NaclSealedBox.seal(rawKey, publicKey, ephemeralSecret, TinkScalarmult)
    }

    /** X25519 backed by Tink's vetted implementation (RFC 7748). */
    private object TinkScalarmult : NaclSealedBox.Scalarmult {
        override fun base(secretKey: ByteArray): ByteArray =
            com.google.crypto.tink.subtle.X25519.publicFromPrivate(secretKey)

        override fun shared(secretKey: ByteArray, peerPublicKey: ByteArray): ByteArray =
            com.google.crypto.tink.subtle.X25519.computeSharedSecret(secretKey, peerPublicKey)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Parses JSON cookie arrays/objects (from extensions like Cookie-Editor, EditThisCookie, Get cookies.txt)
     * into standard HTTP Cookie header strings ("sessionid=123; ds_user_id=456; csrftoken=789").
     */
    fun parseJsonCookies(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val trimmed = input.trim()

        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) {
            return trimmed
        }

        try {
            val cookieMap = mutableMapOf<String, String>()

            if (trimmed.startsWith("[")) {
                val jsonArray = org.json.JSONArray(trimmed)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val name = obj.optString("name")?.ifBlank { null }
                        ?: obj.optString("key")?.ifBlank { null }
                    val value = obj.optString("value")?.ifBlank { null }
                    if (!name.isNullOrBlank() && !value.isNullOrBlank()) {
                        cookieMap[name] = value
                    }
                }
            } else if (trimmed.startsWith("{")) {
                val jsonObj = org.json.JSONObject(trimmed)
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObj.optString(key)?.ifBlank { null }
                    if (!key.isNullOrBlank() && !value.isNullOrBlank()) {
                        cookieMap[key] = value
                    }
                }
            }

            if (cookieMap.isNotEmpty()) {
                return cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }
        } catch (_: Exception) {
            // Return raw string on parse fallback
        }

        return trimmed
    }
}
