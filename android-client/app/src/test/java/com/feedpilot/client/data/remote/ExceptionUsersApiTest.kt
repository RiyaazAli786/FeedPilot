package com.feedpilot.client.data.remote

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import java.util.concurrent.TimeUnit

class ExceptionUsersApiTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun listingUsesReadOnlySearchAndServerPagination() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"users":["example.user"],"total":27,"page":2,"pageSize":25,"pages":2}"""))
            server.start()
            val result = createExceptionUsersApi(json, server.url("/").toString()).list("example", 2)
            assertEquals(listOf("example.user"), result.users)
            assertEquals(27, result.total)
            assertEquals(2, result.page)
            assertEquals(2, result.pages)
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("GET", request.method)
            assertEquals("/api/exception-users", request.requestUrl!!.encodedPath)
            assertEquals("example", request.requestUrl!!.queryParameter("search"))
            assertEquals("2", request.requestUrl!!.queryParameter("page"))
            assertEquals("25", request.requestUrl!!.queryParameter("pageSize"))
            assertNull(request.requestUrl!!.queryParameter("username")) // Legacy GET would ADD a user.
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertNull(request.getHeader("X-App-Id"))
        }
    }

    @Test fun serverClampedEmptyPageIsPreserved() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"success":true,"users":[],"total":0,"page":1,"pageSize":25,"pages":1}"""))
            server.start()
            val result = createExceptionUsersApi(json, server.url("/").toString()).list("missing", 8)
            assertEquals(1, result.page)
            assertEquals(1, result.pages)
            assertTrue(result.users.isEmpty())
        }
    }

    @Test fun addRenameAndRemoveMatchGetIGFeedContract() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"username":"example","added":true,"feedSourceMode":"bridge_then_pool"}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"username":"renamed"}"""))
            server.enqueue(MockResponse().setBody("""{"success":true,"username":"renamed","deleted":true}"""))
            server.start()
            val api = createExceptionUsersApi(json, server.url("/").toString())
            assertEquals(true, api.add(ExceptionUsername("example")).added)
            assertEquals("renamed", api.update("example", ExceptionUsername("renamed")).username)
            assertEquals(true, api.remove("renamed").deleted)
            val add = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", add.method)
            assertEquals("/api/exception-users", add.path)
            assertEquals("""{"username":"example"}""", add.body.readUtf8())
            val edit = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("PUT", edit.method)
            assertEquals("/api/exception-users/example", edit.path)
            assertEquals("""{"username":"renamed"}""", edit.body.readUtf8())
            val remove = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("DELETE", remove.method)
            assertEquals("/api/exception-users/renamed", remove.path)
        }
    }

    @Test fun duplicateRenameRetainsServiceError() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"error":"Exception user already exists."}"""))
            server.start()
            val api = createExceptionUsersApi(json, server.url("/").toString())
            try {
                api.update("example", ExceptionUsername("existing"))
                fail("Expected an HTTP conflict")
            } catch (error: HttpException) {
                assertEquals(409, error.code())
                assertTrue(error.response()!!.errorBody()!!.string().contains("already exists"))
            }
        }
    }
}
