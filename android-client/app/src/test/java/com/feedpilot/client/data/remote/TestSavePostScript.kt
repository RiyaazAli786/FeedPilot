package com.feedpilot.client.data.remote

import com.feedpilot.client.common.InstagramCrypto
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Kotlin test script for testing [InstagramWebClient.savePost] directly using session cookies
 * loaded from scripts/test_sessionid.json.
 *
 * Usage:
 *   ./gradlew test --tests "*TestSavePostScript*"
 */
class TestSavePostScript {

    companion object {
        private const val TARGET_POST_URL = "https://www.instagram.com/p/Db5idCvRk5i/"
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val instagramWebClient = InstagramWebClient(
        client = okHttpClient,
        context = null
    )

    @Test
    fun testSavePostInInstagramWebClient(): Unit = runBlocking {
        println("====================================================")
        println("  TESTING InstagramWebClient.savePost IN KOTLIN    ")
        println("====================================================")

        val sessionCookies = loadSessionCookies()
        if (sessionCookies.isBlank()) {
            println("❌ SKIPPED: Could not find valid session cookies in scripts/test_sessionid.json")
            return@runBlocking
        }

        println("Target Post URL: $TARGET_POST_URL")
        println("Session Cookie string: ${sessionCookies.take(50)}...")

        val result = instagramWebClient.savePost(
            mediaCodeOrId = TARGET_POST_URL,
            sessionCookies = sessionCookies
        )

        println("\n====================================================")
        println("Result OK: ${result.ok}")
        println("Reason/Error: ${result.reason}")
        println("Updated Cookies: ${result.updatedCookies?.take(50)}")
        println("====================================================")

        assertTrue("InstagramWebClient.savePost should succeed: ${result.reason}", result.ok)
    }

    private fun loadSessionCookies(): String {
        val rootDir = File(".").canonicalFile
        val possibleFiles = listOf(
            File(rootDir, "../scripts/test_sessionid.json"),
            File(rootDir, "scripts/test_sessionid.json"),
            File("C:/CoreProjects/FeedPilot/scripts/test_sessionid.json")
        )

        val sessionFile = possibleFiles.firstOrNull { it.exists() } ?: return ""

        val raw = sessionFile.readText()
        return InstagramCrypto.parseJsonCookies(raw)
    }
}
