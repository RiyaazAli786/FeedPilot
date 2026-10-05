package com.feedpilot.client.data.remote

import com.feedpilot.client.common.InstagramCookieJar
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Explicit opt-in live login diagnostic. Credentials and session stay in ignored build output. */
class InstagramLoginFeedDiagnosticTest {
    @Test fun `two factor session fetches public feeds`() = runBlocking {
        val configured = System.getenv("FEEDPILOT_LOGIN_DIAGNOSTIC_FILE").orEmpty()
        assumeTrue("Live login diagnostic not configured", configured.isNotBlank())
        val file = File(configured)
        val input = JSONObject(file.readText())
        val client = InstagramWebClient(OkHttpClient.Builder().build(), null)
        val statusFile = File(file.parentFile, "status.txt")
        var result = client.login(input.getString("username"), input.getString("password"))
        if (result is InstagramLoginResult.TwoFactorRequired) {
            val challenge = result
            statusFile.writeText("TWO_FACTOR_REQUIRED: ${challenge.hint.orEmpty()}")
            val codeFile = File(file.parentFile, "code.txt")
            val deadline = System.currentTimeMillis() + 300_000
            while (!codeFile.exists() && System.currentTimeMillis() < deadline) delay(1000)
            check(codeFile.exists()) { "A fresh two-factor code is required to finish the live test" }
            val code = codeFile.readText().trim()
            codeFile.delete()
            result = client.submitTwoFactorCode(challenge, code)
        }
        if (result !is InstagramLoginResult.Success) {
            val message = when (result) {
                is InstagramLoginResult.Failure -> result.message
                is InstagramLoginResult.EmailCodeRequired -> "Email verification is required"
                else -> "Login did not complete"
            }
            statusFile.writeText("LOGIN_FAILED: $message")
            error(message)
        }
        val cookies = result.sessionData
        check(InstagramCookieJar.parseCookieHeader(cookies)["sessionid"].orEmpty().isNotBlank())
        File(file.parentFile, "session.txt").writeText(cookies)
        statusFile.writeText("LOGIN_SUCCESS; TESTING_FEEDS")
        for (handle in listOf("personalbrandlaunch", "instagram")) {
            val feed = client.fetchUserFeed(handle, sessionCookies = cookies)
            assertTrue("@$handle should return posts after two-factor login", feed?.items?.isNotEmpty() == true)
            println("LOGIN_FEED handle=$handle posts=${feed!!.items.size} hasMore=${feed.hasMore}")
        }
        statusFile.writeText("LOGIN_AND_FEEDS_PASSED")
    }
}
