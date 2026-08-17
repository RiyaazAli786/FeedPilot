package com.feedpilot.client.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

/**
 * Drives [HanumanSmmClient] against a real HTTP server on localhost, so the SMM
 * integration can be exercised end to end without touching a live panel or spending
 * balance. Every response below is copied from an actual smmorigin/hanumansmm reply.
 */
class HanumanSmmClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HanumanSmmClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = HanumanSmmClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun orderApiUrl() = server.url("/api/v2").toString()

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    /** Decodes a recorded `application/x-www-form-urlencoded` body into a map. */
    private fun formFields(body: String): Map<String, String> =
        body.split("&").filter { it.isNotBlank() }.associate { pair ->
            val (name, value) = pair.split("=", limit = 2)
            URLDecoder.decode(name, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }

    private fun placeFollowOrder(
        apiUrl: String = orderApiUrl(),
        apiKey: String = "test-key",
        serviceId: String = "341",
        targetUrl: String = "https://www.instagram.com/yrashi038",
        quantity: Int = 100
    ) = runBlocking { client.placeOrder(apiUrl, apiKey, serviceId, targetUrl, quantity) }

    // ---------------------------------------------------------------- placeOrder

    @Test
    fun `placeOrder returns order id when the panel accepts the order`() {
        // The panel returns `order` as a bare number, not a string.
        enqueue("""{"order":23501}""")

        val result = placeFollowOrder()

        assertTrue(result.success)
        assertEquals("23501", result.orderId)
        assertNull(result.errorMessage)
    }

    @Test
    fun `placeOrder sends the documented add parameters`() {
        enqueue("""{"order":23501}""")

        placeFollowOrder(serviceId = "341", quantity = 100)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v2", recorded.path)

        val fields = formFields(recorded.body.readUtf8())
        assertEquals("test-key", fields["key"])
        assertEquals("add", fields["action"])
        assertEquals("341", fields["service"])
        assertEquals("https://www.instagram.com/yrashi038", fields["link"])
        assertEquals("100", fields["quantity"])
    }

    @Test
    fun `placeOrder surfaces the panel's own error text`() {
        val message = "Min amount of this order is: 100 And Max Amount of order is: 300000"
        enqueue("""{"error":"$message"}""")

        val result = placeFollowOrder(quantity = 1)

        assertFalse(result.success)
        assertEquals(message, result.errorMessage)
        assertNull(result.orderId)
    }

    @Test
    fun `placeOrder reports an invalid key rather than a canned message`() {
        enqueue("""{"error":"Invalid API key"}""", code = 401)

        val result = placeFollowOrder(apiKey = "wrong-key")

        assertFalse(result.success)
        assertEquals("Invalid API key", result.errorMessage)
    }

    /**
     * `action=add` is not idempotent: a second attempt after an unreadable reply would
     * place — and charge for — the same order twice.
     */
    @Test
    fun `placeOrder never retries after a failure`() {
        enqueue("""{"error":"Not enough balance on your account"}""")

        val result = placeFollowOrder()

        assertFalse(result.success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `placeOrder reports non-JSON responses instead of throwing`() {
        enqueue("<html><body>502 Bad Gateway</body></html>", code = 502)

        val result = placeFollowOrder()

        assertFalse(result.success)
        assertTrue(result.errorMessage!!.startsWith("Unexpected response"))
    }

    @Test
    fun `placeOrder reports an empty body`() {
        enqueue("", code = 200)

        val result = placeFollowOrder()

        assertFalse(result.success)
        assertTrue(result.errorMessage!!.startsWith("Empty response"))
    }

    @Test
    fun `placeOrder rejects bad input without calling the panel`() {
        val blankKey = placeFollowOrder(apiKey = "  ")
        val blankService = placeFollowOrder(serviceId = "")
        val zeroQuantity = placeFollowOrder(quantity = 0)

        assertEquals("SMM API key is not configured", blankKey.errorMessage)
        assertEquals("SMM service ID is not configured", blankService.errorMessage)
        assertEquals("Quantity must be greater than zero", zeroQuantity.errorMessage)
        assertEquals(0, server.requestCount)
    }

    /**
     * The admin API has no order-creation route, so a provider row still pointing at one
     * is remapped onto the same host's user API.
     */
    @Test
    fun `placeOrder remaps an admin url onto the user api`() {
        enqueue("""{"order":23501}""")

        placeFollowOrder(apiUrl = server.url("/adminapi/v2").toString())

        assertEquals("/api/v2", server.takeRequest().path)
    }

    @Test
    fun `placeOrder expands a bare handle into a profile url`() {
        enqueue("""{"order":23501}""")

        placeFollowOrder(targetUrl = "@yrashi038")

        val fields = formFields(server.takeRequest().body.readUtf8())
        assertEquals("https://www.instagram.com/yrashi038", fields["link"])
    }

    @Test
    fun `placeOrder passes a post url through untouched for likes`() {
        enqueue("""{"order":23502}""")

        val postUrl = "https://www.instagram.com/p/C_post_id/"
        placeFollowOrder(serviceId = "336", targetUrl = postUrl)

        val fields = formFields(server.takeRequest().body.readUtf8())
        assertEquals(postUrl, fields["link"])
        assertEquals("336", fields["service"])
    }

    // ---------------------------------------------------------------- pullOrders

    @Test
    fun `pullOrders parses the admin envelope`() {
        enqueue(
            """
            {"data":{"count":1,"list":[
              {"id":"23501","service_id":"171","service_name":"Instagram Followers",
               "link":"https://www.instagram.com/yrashi038","quantity":10,
               "remains":8,"status":"pending"}
            ]},"error_message":"","error_code":0}
            """.trimIndent()
        )

        val orders = runBlocking {
            client.pullOrders(serviceIds = "171", limit = 100, apiUrl = server.url("/orders/pull").toString())
        }

        assertEquals(1, orders.size)
        val order = orders.single()
        assertEquals("23501", order.id)
        assertEquals("171", order.serviceId)
        assertEquals("Instagram Followers", order.serviceName)
        assertEquals("https://www.instagram.com/yrashi038", order.link)
        assertEquals(10, order.quantity)
        assertEquals(8, order.remains)
        assertEquals("pending", order.status)
    }

    @Test
    fun `pullOrders posts service ids and limit as json with the api key header`() {
        enqueue("""{"data":{"count":0,"list":[]},"error_message":"","error_code":0}""")

        runBlocking {
            client.pullOrders(
                serviceIds = "171",
                limit = 100,
                apiUrl = server.url("/orders/pull").toString(),
                apiKey = "admin-key"
            )
        }

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("admin-key", recorded.getHeader("X-Api-Key"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))

        val body = JSONObject(recorded.body.readUtf8())
        assertEquals("171", body.getString("service_ids"))
        assertEquals(100, body.getInt("limit"))
    }

    @Test
    fun `pullOrders returns empty when the queue is drained`() {
        enqueue("""{"data":{"count":0,"list":[]},"error_message":"","error_code":0}""")

        val orders = runBlocking {
            client.pullOrders("171", 100, server.url("/orders/pull").toString())
        }

        assertTrue(orders.isEmpty())
    }

    /** The panel answers HTTP 200 with a non-zero error_code on failures. */
    @Test
    fun `pullOrders treats a non-zero error code as a failure`() {
        enqueue("""{"data":[],"error_message":"Page not found.","error_code":100}""", code = 200)

        val orders = runBlocking {
            client.pullOrders("171", 100, server.url("/orders/pull").toString())
        }

        assertTrue(orders.isEmpty())
    }

    @Test
    fun `pullOrders skips the call when input is unusable`() {
        val blank = runBlocking { client.pullOrders("  ", 100, server.url("/orders/pull").toString()) }
        val zeroLimit = runBlocking { client.pullOrders("171", 0, server.url("/orders/pull").toString()) }

        assertTrue(blank.isEmpty())
        assertTrue(zeroLimit.isEmpty())
        assertEquals(0, server.requestCount)
    }

    /**
     * The live admin API returns `id` and `service_id` as numbers and may send
     * `start_count: null`, so parsing must coerce rather than assume JSON strings.
     */
    @Test
    fun `pullOrders coerces numeric ids from the live payload shape`() {
        enqueue(
            """
            {"data":{"count":1,"list":[
              {"id":906386,"external_id":null,"user":"pexmonn","creation_type":"api",
               "charge":{"value":"0.03","currency_code":"USD"},
               "link":"https://www.instagram.com/target_user","start_count":null,
               "quantity":100,"service_id":170,"service_type":"default",
               "service_name":"Instagram Followers [App Data]","status":"pending","remains":100}
            ]},"error_message":"","error_code":0}
            """.trimIndent()
        )

        val order = runBlocking {
            client.pullOrders("170", 100, server.url("/orders/pull").toString())
        }.single()

        assertEquals("906386", order.id)
        assertEquals("170", order.serviceId)
        assertEquals(100, order.quantity)
        assertEquals(100, order.remains)
    }

    @Test
    fun `pullOrders falls back to quantity when remains is absent`() {
        enqueue(
            """{"data":{"count":1,"list":[
                 {"id":"1","service_id":"172","link":"https://www.instagram.com/p/abc/","quantity":50}
               ]},"error_message":"","error_code":0}"""
        )

        val orders = runBlocking {
            client.pullOrders("172", 100, server.url("/orders/pull").toString())
        }

        assertEquals(50, orders.single().remains)
    }
}
