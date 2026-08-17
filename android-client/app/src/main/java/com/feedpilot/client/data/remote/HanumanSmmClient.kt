package com.feedpilot.client.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class SmmOrderResult(
    val success: Boolean,
    val orderId: String? = null,
    val errorMessage: String? = null
)

data class SmmServiceDetails(
    val serviceId: String,
    val name: String,
    val rate: Double,
    val min: Int,
    val max: Long,
    val category: String
)

data class SmmStatusInfo(
    val orderId: String,
    val status: String,
    val remains: String,
    val charge: String,
    val startCount: String,
    val currency: String
)

data class SmmAdminOrder(
    val id: String,
    val link: String,
    val quantity: Int,
    val remains: Int,
    val serviceId: String,
    val serviceName: String,
    val status: String,
    val comments: String? = null
)

@Singleton
class HanumanSmmClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    /**
     * Places a live SMM order dynamically using the provider's API URL, API Key, and Service ID.
     *
     * Sent as a single `action=add` request to the panel's **user** API (`/api/v2`) — the only
     * endpoint that creates orders. This deliberately does not retry against other base URLs:
     * a request that reached the panel but failed to read back would otherwise place — and
     * charge for — the same order twice.
     */
    suspend fun placeOrder(
        apiUrl: String,
        apiKey: String,
        serviceId: String,
        targetUrl: String,
        quantity: Int
    ): SmmOrderResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanService = serviceId.trim()
        val endpoint = normalizeOrderApiUrl(apiUrl)

        if (cleanKey.isEmpty()) {
            return@withContext SmmOrderResult(false, errorMessage = "SMM API key is not configured")
        }
        if (cleanService.isEmpty()) {
            return@withContext SmmOrderResult(false, errorMessage = "SMM service ID is not configured")
        }
        if (quantity <= 0) {
            return@withContext SmmOrderResult(false, errorMessage = "Quantity must be greater than zero")
        }

        val formBody = FormBody.Builder()
            .add("key", cleanKey)
            .add("action", "add")
            .add("service", cleanService)
            .add("link", normalizeTargetLink(targetUrl))
            .add("quantity", quantity.toString())
            .build()

        val request = Request.Builder()
            .url(endpoint)
            .post(formBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isBlank()) {
                    return@withContext SmmOrderResult(
                        false,
                        errorMessage = "Empty response from $endpoint (HTTP ${response.code})"
                    )
                }

                val json = try {
                    JSONObject(bodyStr)
                } catch (e: Exception) {
                    return@withContext SmmOrderResult(
                        false,
                        errorMessage = "Unexpected response from $endpoint: ${bodyStr.take(200)}"
                    )
                }

                // `order` comes back as a bare number, so read it as a value rather than a string.
                val orderId = json.opt("order")?.toString()
                    ?.takeIf { it.isNotBlank() && it != "null" }
                if (orderId != null) {
                    return@withContext SmmOrderResult(true, orderId = orderId)
                }

                val panelError = listOf("error", "error_message", "message")
                    .firstNotNullOfOrNull { field ->
                        json.optString(field).takeIf { it.isNotBlank() }
                    }
                SmmOrderResult(
                    false,
                    errorMessage = panelError ?: "HTTP ${response.code}: ${bodyStr.take(200)}"
                )
            }
        } catch (e: Exception) {
            SmmOrderResult(false, errorMessage = e.localizedMessage ?: e.toString())
        }
    }

    /**
     * `action=add` is served only by a panel's user API (`/api/v2`). The admin API
     * (`/adminapi/v2`) exposes no order-creation route at all, so a provider row still
     * pointing at an admin URL is remapped onto the same host's user API.
     */
    private fun normalizeOrderApiUrl(apiUrl: String): String {
        val trimmed = apiUrl.trim().trimEnd('/')
        if (trimmed.isEmpty()) return DEFAULT_ORDER_API_URL
        val adminPath = Regex("/(?:admin/)?adminapi/v\\d+$", RegexOption.IGNORE_CASE)
        return adminPath.replace(trimmed, "/api/v2")
    }

    private fun normalizeTargetLink(targetUrl: String): String {
        val trimmed = targetUrl.trim()
        return if (trimmed.startsWith("http", ignoreCase = true)) trimmed
        else "https://www.instagram.com/${trimmed.removePrefix("@")}"
    }

    /**
     * Cancels an active SMM order on the panel via API.
     */
    suspend fun cancelOrder(
        apiUrl: String,
        apiKey: String,
        orderId: String
    ): SmmOrderResult = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val cleanUrl = apiUrl.trim()

        val targetEndpoints = listOf(
            cleanUrl,
            "https://smmorigin.com/adminapi/v2",
            "https://smmorigin.com/api/v2"
        ).distinct()

        var lastError = "Could not cancel order on SMM panel"

        for (endpoint in targetEndpoints) {
            try {
                val formBody = FormBody.Builder()
                    .add("key", cleanKey)
                    .add("api_key", cleanKey)
                    .add("action", "cancel")
                    .add("order", orderId)
                    .add("orders", orderId)
                    .build()

                val request = Request.Builder()
                    .url(endpoint)
                    .post(formBody)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    val bodyStr = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}: $bodyStr"
                        return@use
                    }
                    val json = JSONObject(bodyStr)
                    if (json.optBoolean("cancel", false) || json.has("status") || bodyStr.contains("success") || json.has("cancel")) {
                        return@withContext SmmOrderResult(true, orderId = orderId)
                    } else if (json.has("error")) {
                        lastError = json.optString("error")
                    } else if (json.has("error_message")) {
                        lastError = json.optString("error_message")
                    } else {
                        lastError = bodyStr
                    }
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: e.toString()
            }
        }

        SmmOrderResult(false, errorMessage = lastError)
    }

    /**
     * Checks real-time pending status for a single order ID dynamically.
     */
    suspend fun checkOrderStatus(
        apiUrl: String,
        apiKey: String,
        orderId: String
    ): SmmStatusInfo? = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("key", apiKey)
                .add("action", "status")
                .add("order", orderId)
                .build()

            val request = Request.Builder()
                .url(apiUrl)
                .post(formBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    return@withContext SmmStatusInfo(
                        orderId = orderId,
                        status = json.optString("status", "Unknown"),
                        remains = json.optString("remains", "0"),
                        charge = json.optString("charge", "0"),
                        startCount = json.optString("start_count", "0"),
                        currency = json.optString("currency", "INR")
                    )
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks real-time pending status for multiple order IDs dynamically.
     */
    suspend fun checkMultipleOrdersStatus(
        apiUrl: String,
        apiKey: String,
        orderIds: List<String>
    ): Map<String, SmmStatusInfo> = withContext(Dispatchers.IO) {
        if (orderIds.isEmpty()) return@withContext emptyMap()
        try {
            val joinedIds = orderIds.take(100).joinToString(",")
            val formBody = FormBody.Builder()
                .add("key", apiKey)
                .add("action", "status")
                .add("orders", joinedIds)
                .build()

            val request = Request.Builder()
                .url(apiUrl)
                .post(formBody)
                .build()

            val mapResult = mutableMapOf<String, SmmStatusInfo>()
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val json = JSONObject(bodyStr)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val obj = json.optJSONObject(key)
                        if (obj != null) {
                            mapResult[key] = SmmStatusInfo(
                                orderId = key,
                                status = obj.optString("status", "Unknown"),
                                remains = obj.optString("remains", "0"),
                                charge = obj.optString("charge", "0"),
                                startCount = obj.optString("start_count", "0"),
                                currency = obj.optString("currency", "INR")
                            )
                        }
                    }
                }
            }
            mapResult
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * Fetches details of a specific service ID dynamically from the provider.
     */
    suspend fun fetchServiceDetails(
        apiUrl: String,
        apiKey: String,
        serviceId: String
    ): SmmServiceDetails? = withContext(Dispatchers.IO) {
        try {
            val formBody = FormBody.Builder()
                .add("key", apiKey)
                .add("action", "services")
                .build()

            val request = Request.Builder()
                .url(apiUrl)
                .post(formBody)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val array = org.json.JSONArray(bodyStr)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        if (obj.optString("service") == serviceId) {
                            return@withContext SmmServiceDetails(
                                serviceId = obj.optString("service"),
                                name = obj.optString("name"),
                                rate = obj.optDouble("rate", 0.0),
                                min = obj.optInt("min", 1),
                                max = obj.optLong("max", 10000000L),
                                category = obj.optString("category")
                            )
                        }
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Fetches active orders directly from SMM Admin API (e.g., https://smmorigin.com/adminapi/v2/orders).
     */
    suspend fun fetchAdminOrders(
        apiUrl: String = ADMIN_ORDERS_URL,
        apiKey: String = DEFAULT_ADMIN_KEY,
        statusFilter: String = "pending"
    ): List<SmmAdminOrder> = withContext(Dispatchers.IO) {
        try {
            val cleanKey = apiKey.trim()
            val targetUrl = if (apiUrl.endsWith("/orders")) {
                "$apiUrl?status=$statusFilter"
            } else if (apiUrl.endsWith("/v2") || apiUrl.endsWith("/v2/")) {
                "${apiUrl.removeSuffix("/")}/orders?status=$statusFilter"
            } else {
                "$apiUrl?status=$statusFilter"
            }

            val request = Request.Builder()
                .url(targetUrl)
                .addHeader("X-Api-Key", cleanKey)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful) return@withContext emptyList()
                val json = JSONObject(bodyStr)
                val dataObj = json.optJSONObject("data") ?: return@withContext emptyList()
                val listArr = dataObj.optJSONArray("list") ?: return@withContext emptyList()

                val result = mutableListOf<SmmAdminOrder>()
                for (i in 0 until listArr.length()) {
                    result.add(parseAdminOrder(listArr.getJSONObject(i)))
                }
                result
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Pulls the next batch of orders queued for processing from the SMM Admin API
     * (`POST https://smmorigin.com/adminapi/v2/orders/pull`).
     *
     * [serviceIds] is a comma-separated list of service IDs — e.g. `171` for Followers
     * and `172` for Likes. Pull each type separately so the caller knows which task type
     * every returned order belongs to.
     */
    suspend fun pullOrders(
        serviceIds: String,
        limit: Int = 100,
        apiUrl: String = ADMIN_PULL_URL,
        apiKey: String = DEFAULT_ADMIN_KEY
    ): List<SmmAdminOrder> = withContext(Dispatchers.IO) {
        val cleanServiceIds = serviceIds.trim()
        if (cleanServiceIds.isEmpty() || limit <= 0) return@withContext emptyList()

        try {
            val payload = JSONObject()
                .put("service_ids", cleanServiceIds)
                .put("limit", limit)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("X-Api-Key", apiKey.trim())
                .post(payload)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful || bodyStr.isBlank()) return@withContext emptyList()

                val json = JSONObject(bodyStr)
                // The panel answers 200 OK with a non-zero error_code on failures.
                if (json.optInt("error_code", 0) != 0) return@withContext emptyList()

                val listArr = json.optJSONObject("data")?.optJSONArray("list")
                    ?: return@withContext emptyList()

                val result = mutableListOf<SmmAdminOrder>()
                for (i in 0 until listArr.length()) {
                    result.add(parseAdminOrder(listArr.getJSONObject(i)))
                }
                result
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseAdminOrder(item: JSONObject): SmmAdminOrder {
        val quantity = item.optInt("quantity", 1)
        var commentsText = item.optString("comments").ifBlank {
            item.optString("comment").ifBlank {
                item.optString("custom_data")
            }
        }

        if (commentsText.isBlank()) {
            val orderButtons = item.optJSONArray("order_buttons")
            if (orderButtons != null) {
                for (i in 0 until orderButtons.length()) {
                    val btn = orderButtons.optJSONObject(i) ?: continue
                    val userData = btn.optJSONArray("user_data")
                    if (userData != null && userData.length() > 0) {
                        val list = mutableListOf<String>()
                        for (j in 0 until userData.length()) {
                            val str = userData.optString(j)
                            if (str.isNotBlank()) list.add(str.trim())
                        }
                        if (list.isNotEmpty()) {
                            commentsText = list.joinToString("\n")
                            break
                        }
                    }
                }
            }
        }

        return SmmAdminOrder(
            id = item.optString("id").ifBlank { item.optString("order_id") },
            link = item.optString("link").ifBlank { item.optString("url") },
            quantity = quantity,
            remains = item.optInt("remains", quantity),
            serviceId = item.optString("service_id").ifBlank { item.optString("service") },
            serviceName = item.optString("service_name"),
            status = item.optString("status"),
            comments = commentsText.ifBlank { null }
        )
    }

    /**
     * Pulls pending cancel tasks from SMM Admin API
     * (`POST https://smmorigin.com/adminapi/v2/cancel/pull`).
     */
    suspend fun pullCancelTasks(
        limit: Int = 100,
        apiUrl: String = ADMIN_CANCEL_PULL_URL,
        apiKey: String = DEFAULT_ADMIN_KEY
    ): List<SmmAdminOrder> = withContext(Dispatchers.IO) {
        try {
            val targetUrl = if (apiUrl.isBlank()) {
                "https://smmorigin.com/adminapi/v2/cancel/pull"
            } else if (apiUrl.endsWith("/cancel/pull")) {
                apiUrl
            } else {
                "${apiUrl.trimEnd('/')}/cancel/pull"
            }

            val payload = JSONObject()
                .put("limit", limit)
                .toString()
                .toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(targetUrl)
                .apply {
                    if (apiKey.isNotBlank()) addHeader("X-Api-Key", apiKey.trim())
                }
                .post(payload)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                if (!response.isSuccessful || bodyStr.isBlank()) return@withContext emptyList()

                val json = JSONObject(bodyStr)
                if (json.optInt("error_code", 0) != 0) return@withContext emptyList()

                val listArr = json.optJSONObject("data")?.optJSONArray("list")
                    ?: json.optJSONArray("data")
                    ?: return@withContext emptyList()

                val result = mutableListOf<SmmAdminOrder>()
                for (i in 0 until listArr.length()) {
                    result.add(parseAdminOrder(listArr.getJSONObject(i)))
                }
                result
            }
        } catch (e: Exception) {
            Log.w("HanumanSmmClient", "pullCancelTasks failed", e)
            emptyList()
        }
    }

    /**
     * Fetches cancelled orders directly from SMM Admin API (`GET https://smmorigin.com/adminapi/v2/orders?order_status=canceled`).
     */
    suspend fun fetchCancelledOrders(
        apiUrl: String = ADMIN_ORDERS_URL,
        apiKey: String = DEFAULT_ADMIN_KEY
    ): List<SmmAdminOrder> = fetchAdminOrders(apiUrl, apiKey, statusFilter = "canceled")

    /**
     * Updates status and remains of orders on SMM Admin API
     * (`POST https://smmorigin.com/adminapi/v2/orders/update`).
     */
    suspend fun updateOrders(
        ordersToUpdate: List<SmmOrderUpdatePayload>,
        apiUrl: String = ADMIN_UPDATE_URL,
        apiKey: String = DEFAULT_ADMIN_KEY
    ): Boolean = withContext(Dispatchers.IO) {
        if (ordersToUpdate.isEmpty()) return@withContext true
        try {
            val targetUrl = if (apiUrl.isBlank()) {
                "https://smmorigin.com/adminapi/v2/orders/update"
            } else if (apiUrl.endsWith("/orders/update")) {
                apiUrl
            } else {
                "${apiUrl.trimEnd('/')}/orders/update"
            }

            val ordersArray = JSONArray()
            for (item in ordersToUpdate) {
                val obj = JSONObject()
                val idNum = item.id.toIntOrNull()
                if (idNum != null) obj.put("id", idNum) else obj.put("id", item.id)
                obj.put("status", item.status)
                obj.put("remains", item.remains)
                ordersArray.put(obj)
            }
            val payload = JSONObject().put("orders", ordersArray).toString().toRequestBody(JSON_MEDIA_TYPE)

            val request = Request.Builder()
                .url(targetUrl)
                .apply {
                    if (apiKey.isNotBlank()) addHeader("X-Api-Key", apiKey.trim())
                }
                .post(payload)
                .build()

            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string() ?: ""
                Log.i("HanumanSmmClient", "updateOrders code=${response.code} body=$bodyStr")
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("HanumanSmmClient", "updateOrders failed", e)
            false
        }
    }

    companion object {
        // --- Admin API: provider-side queue we FULFIL (pull orders, report progress). ---
        // Authenticated with the X-Api-Key header. It has no order-creation route.
        // Intentionally blank: no provider URL/key/service-ids ship in the app. The operator
        // configures their own admin queue in Settings — shipping these would leak a live key.
        const val ADMIN_ORDERS_URL = ""
        const val ADMIN_PULL_URL = ""
        const val ADMIN_CANCEL_PULL_URL = "https://smmorigin.com/adminapi/v2/cancel/pull"
        const val ADMIN_UPDATE_URL = "https://smmorigin.com/adminapi/v2/orders/update"
        const val DEFAULT_ADMIN_KEY = ""

        const val PULL_FOLLOW_SERVICE_ID = "171"
        const val PULL_LIKE_SERVICE_ID = "172"
        const val PULL_REPOST_SERVICE_ID = "175"
        const val PULL_SAVE_SERVICE_ID = "176"
        const val PULL_COMMENT_RANDOM_SERVICE_ID = "177"
        const val PULL_COMMENT_CUSTOM_SERVICE_ID = "178"
        const val PULL_STORY_VIEW_SERVICE_ID = "179"

        // --- User API: the panel we BUY from (action=add / status / services / balance). ---
        // Authenticated with the `key` form field. Service IDs live in a different ID space
        // from the admin queue above, so the two sets must not be interchanged.
        // Intentionally blank: no provider credentials ship in the app. The operator enters
        // their own SMM panel URL, API key and service ids in Settings.
        const val DEFAULT_ORDER_API_URL = ""
        const val DEFAULT_ORDER_API_KEY = ""
        const val DEFAULT_FOLLOW_SERVICE_ID = ""
        const val DEFAULT_LIKE_SERVICE_ID = ""

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

data class SmmOrderUpdatePayload(
    val id: String,
    val status: String,
    val remains: Int
)
