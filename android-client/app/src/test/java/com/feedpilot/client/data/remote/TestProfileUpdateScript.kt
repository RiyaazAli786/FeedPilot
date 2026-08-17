package com.feedpilot.client.data.remote

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Test
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Test script to update Instagram Profile Picture and Biography using a test session cookie string.
 *
 * Usage:
 * Provide your captured session cookies in [TEST_SESSION] (containing sessionid, csrftoken, ds_user_id)
 * and run this test via `./gradlew test --tests "*TestProfileUpdateScript*"`
 */
class TestProfileUpdateScript {

    companion object {
        // Replace with your active test session cookies
        private const val TEST_SESSION = "sessionid=YOUR_SESSION_ID; ds_user_id=YOUR_USER_ID; csrftoken=YOUR_CSRF_TOKEN;"
        private const val TARGET_USERNAME = "your_instagram_username"
        private const val NEW_BIO_TEXT = "Test bio updated via automated test script ✨"
        
        // Minimal valid JPEG byte array for testing profile picture upload
        private val MINIMAL_JPEG_BYTES = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00.toByte(), 0x10.toByte(),
            0x4A.toByte(), 0x46.toByte(), 0x49.toByte(), 0x46.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x01.toByte(), 0x01.toByte(), 0x00.toByte(), 0x48.toByte(), 0x00.toByte(), 0x48.toByte(),
            0x00.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xDB.toByte(), 0x00.toByte(), 0x43.toByte(),
            0x00.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0x00.toByte(), 0x0B.toByte(), 0x08.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x01.toByte(), 0x01.toByte(), 0x01.toByte(),
            0x11.toByte(), 0x00.toByte(), 0xFF.toByte(), 0xC4.toByte(), 0x00.toByte(), 0x14.toByte(),
            0x00.toByte(), 0x01.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x09.toByte(),
            0xFF.toByte(), 0xDA.toByte(), 0x00.toByte(), 0x08.toByte(), 0x01.toByte(), 0x01.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x3F.toByte(), 0x00.toByte(), 0x7F.toByte(), 0x00.toByte(),
            0xFF.toByte(), 0xD9.toByte()
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Test
    fun testUpdateBiographyAndProfilePicture() {
        if (TEST_SESSION.contains("YOUR_SESSION_ID")) {
            println("SKIPPED: Please provide a valid TEST_SESSION cookie string before running.")
            return
        }

        println("=== 1. Testing Biography Update ===")
        val bioSuccess = updateBiography(TEST_SESSION, TARGET_USERNAME, NEW_BIO_TEXT)
        println("Biography Update Result: $bioSuccess")

        println("=== 2. Testing Profile Picture Update ===")
        val picSuccess = updateProfilePicture(TEST_SESSION, MINIMAL_JPEG_BYTES)
        println("Profile Picture Update Result: $picSuccess")
    }

    private fun updateBiography(sessionCookies: String, username: String, biography: String): Boolean {
        val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
        val timestamp = System.currentTimeMillis() / 1000
        val fbDtsg = "NAfzDAr4VAkqIhEKXwJ3fO5q7V51NQ5qK9LkLk_GoY2SaMnx0zc7Thw:17858449030071790:$timestamp"
        val jazoest = "2" + UUID.randomUUID().toString().replace("-", "").take(5)

        val formBody = FormBody.Builder()
            .add("biography", biography)
            .add("chaining_enabled", "on")
            .add("external_url", "")
            .add("first_name", "")
            .add("username", username)
            .add("jazoest", jazoest)
            .add("fb_dtsg", fbDtsg)
            .build()

        val request = Request.Builder()
            .url("https://www.instagram.com/api/v1/web/accounts/edit/")
            .post(formBody)
            .header("Accept", "*/*")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-CSRFToken", csrfToken)
            .header("Cookie", sessionCookies)
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/accounts/edit/")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                println("Response Code: ${response.code}, Body: $body")
                response.isSuccessful && body.contains("\"status\":\"ok\"")
            }
        } catch (e: Exception) {
            println("Failed to update biography: ${e.message}")
            false
        }
    }

    private fun updateProfilePicture(sessionCookies: String, photoBytes: ByteArray): Boolean {
        val csrfToken = extractCookie(sessionCookies, "csrftoken") ?: ""
        val timestamp = System.currentTimeMillis() / 1000
        val fbDtsg = "NAfzDAr4VAkqIhEKXwJ3fO5q7V51NQ5qK9LkLk_GoY2SaMnx0zc7Thw:17858449030071790:$timestamp"
        val jazoest = "2" + UUID.randomUUID().toString().replace("-", "").take(5)

        val mediaType = "image/jpeg".toMediaTypeOrNull()
        val filePart = photoBytes.toRequestBody(mediaType)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("profile_pic", "profilepic.jpg", filePart)
            .addFormDataPart("fb_dtsg", fbDtsg)
            .addFormDataPart("jazoest", jazoest)
            .build()

        val request = Request.Builder()
            .url("https://www.instagram.com/api/v1/web/accounts/web_change_profile_picture/")
            .post(requestBody)
            .header("Accept", "*/*")
            .header("X-IG-App-ID", "936619743392459")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-CSRFToken", csrfToken)
            .header("Cookie", sessionCookies)
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/accounts/edit/")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                println("Response Code: ${response.code}, Body: $body")
                response.isSuccessful && body.contains("\"status\":\"ok\"")
            }
        } catch (e: Exception) {
            println("Failed to update profile picture: ${e.message}")
            false
        }
    }

    private fun extractCookie(cookies: String, key: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")
    }
}
