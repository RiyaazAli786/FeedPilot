package com.feedpilot.client.data.repository

import com.feedpilot.client.common.Resource
import com.feedpilot.client.common.apiErrorMessage
import com.feedpilot.client.data.remote.ApiService
import com.feedpilot.client.data.remote.dto.AppOrderDto
import com.feedpilot.client.data.remote.dto.BatchProgressItem
import com.feedpilot.client.data.remote.dto.BatchReportProgressRequest
import com.feedpilot.client.data.remote.dto.BatchReportProgressResponse
import com.feedpilot.client.data.remote.dto.ClaimOrdersRequest
import com.feedpilot.client.data.remote.dto.ClaimedOrderDto
import com.feedpilot.client.data.remote.dto.OrderQuoteDto
import com.feedpilot.client.data.remote.dto.PagedOrdersDto
import com.feedpilot.client.data.remote.dto.PlaceAppOrderRequest
import com.feedpilot.client.data.remote.dto.PlaceAppOrderResponse
import com.feedpilot.client.data.remote.dto.ReportProgressRequest
import org.json.JSONObject
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What happened the last time this device asked the backend for orders — distinct from a plain
 * empty list so callers (see [TaskRepository.lastClaimOutcome] / [TaskRunnerService.idleWait])
 * can tell "no orders exist right now" apart from "the request failed," which used to collapse
 * into the same silent `emptyList()` and made a rate-limited or network-broken device look
 * identical to one with a genuinely empty queue.
 */
sealed class ClaimOutcome {
    data class Success(val orders: List<ClaimedOrderDto>) : ClaimOutcome()
    data class RateLimited(val retryAfterSeconds: Long?) : ClaimOutcome()
    data class NetworkError(val message: String?) : ClaimOutcome()
    data class ServerError(val code: Int) : ClaimOutcome()
}

/**
 * Places orders through the FeedPilot backend rather than an SMM panel.
 *
 * The backend prices the order, debits coins, and drives fulfilment from its dashboard, so
 * the app holds no provider credentials and cannot spend real panel balance on its own.
 */
@Singleton
class AppOrderRepository @Inject constructor(
    private val api: ApiService,
    private val sessionGate: AccountSessionGate,
    private val authRepository: dagger.Lazy<AuthRepository>
) {
    /** Every call here is on behalf of a signed-in account, so none of them run without one. */
    private suspend fun signedIn(): Boolean = sessionGate.isSignedIn()

    suspend fun placeOrder(
        orderType: String,
        targetUrl: String,
        targetUsername: String?,
        quantity: Int,
        startCount: Int? = null,
        comments: List<String>? = null
    ): Resource<PlaceAppOrderResponse> = try {
        if (!signedIn()) {
            Resource.Error(NO_ACCOUNT)
        } else {
            val res = api.placeAppOrder(
                PlaceAppOrderRequest(
                    orderType = orderType,
                    targetUrl = targetUrl.trim(),
                    targetUsername = targetUsername?.trim(),
                    quantity = quantity,
                    startCount = startCount,
                    comments = comments
                )
            )
            Resource.Success(res)
        }
    } catch (t: Throwable) {
        Resource.Error(readApiError(t), t)
    }

    /** Server-side price for an order, so the app never has to guess the coin cost. */
    suspend fun quote(orderType: String, quantity: Int): Resource<OrderQuoteDto> = try {
        if (!signedIn()) Resource.Error(NO_ACCOUNT)
        else Resource.Success(api.quoteOrder(orderType, quantity))
    } catch (t: Throwable) {
        Resource.Error(readApiError(t), t)
    }

    /** One page of orders this user placed, newest first. */
    suspend fun myOrders(page: Int = 1, pageSize: Int = 50): Resource<PagedOrdersDto> = try {
        // An empty page, not an error: with no account there are no orders to have placed.
        if (!signedIn()) Resource.Success(emptyPage(page, pageSize))
        else Resource.Success(api.getMyOrders(page, pageSize))
    } catch (t: Throwable) {
        Resource.Error(readApiError(t), t)
    }

    /**
     * One page of orders imported from the smmorigin.com panel, newest first. Unlike every other
     * call here this is not scoped to a signed-in account — it is the same anonymous endpoint the
     * admin queue itself reads from, so browsing it doesn't require an Instagram account to be
     * linked yet.
     */
    suspend fun externalOrders(page: Int = 1, pageSize: Int = 50): Resource<PagedOrdersDto> = try {
        Resource.Success(api.getExternalOrders(page, pageSize))
    } catch (t: Throwable) {
        Resource.Error(readApiError(t), t)
    }

    private fun emptyPage(page: Int, pageSize: Int) =
        PagedOrdersDto(items = emptyList(), page = page, pageSize = pageSize, totalCount = 0, totalPages = 0)

    suspend fun cancelOrder(id: String): Resource<AppOrderDto> = try {
        if (!signedIn()) Resource.Error(NO_ACCOUNT)
        else Resource.Success(api.cancelAppOrder(id))
    } catch (t: Throwable) {
        Resource.Error(readApiError(t), t)
    }

    /**
     * Claims up to [batchSize] pending orders from the backend queue for this device to execute.
     *
     * Reports [ClaimOutcome.Success] with an empty list rather than an error when there is
     * simply nothing to do — a real failure (rate-limited, network, server error) comes back as
     * one of the other [ClaimOutcome] variants instead of being silently swallowed.
     */
    suspend fun claimOrders(
        deviceId: String,
        batchSize: Int,
        excludeOrderIds: List<String>? = null,
        accountId: String? = null,
        accountHandle: String? = null,
        taskTypes: List<String>? = null
    ): ClaimOutcome {
        if (!signedIn()) {
            val ok = authRepository.get().ensureDeviceSession(com.feedpilot.client.BuildConfig.VERSION_NAME)
            if (!ok) return ClaimOutcome.Success(emptyList())
        }
        return try {
            ClaimOutcome.Success(api.claimOrders(ClaimOrdersRequest(
                deviceId = deviceId,
                batchSize = batchSize,
                excludeOrderIds = excludeOrderIds?.takeIf { it.isNotEmpty() },
                accountId = accountId,
                accountHandle = accountHandle,
                taskTypes = taskTypes?.takeIf { it.isNotEmpty() }
            )))
        } catch (t: Throwable) {
            val outcome = classifyClaimFailure(t)
            if (outcome is ClaimOutcome.ServerError && outcome.code == 401) {
                val recovered = authRepository.get().reloadDeviceSession(com.feedpilot.client.BuildConfig.VERSION_NAME)
                if (recovered) {
                    return try {
                        ClaimOutcome.Success(api.claimOrders(ClaimOrdersRequest(
                            deviceId = deviceId,
                            batchSize = batchSize,
                            excludeOrderIds = excludeOrderIds?.takeIf { it.isNotEmpty() },
                            accountId = accountId,
                            accountHandle = accountHandle,
                            taskTypes = taskTypes?.takeIf { it.isNotEmpty() }
                        )))
                    } catch (t2: Throwable) {
                        classifyClaimFailure(t2)
                    }
                }
            }
            outcome
        }
    }

    private fun classifyClaimFailure(t: Throwable): ClaimOutcome = when {
        t is HttpException && t.code() == 429 ->
            ClaimOutcome.RateLimited(t.response()?.headers()?.get("Retry-After")?.toLongOrNull())
        t is HttpException -> ClaimOutcome.ServerError(t.code())
        t is UnknownHostException || t is ConnectException || t is SocketTimeoutException || t is SocketException ->
            ClaimOutcome.NetworkError(t.message)
        else -> ClaimOutcome.NetworkError(t.message)
    }

    /**
     * Reports the running completed total. The backend marks the order Completed once the
     * target is reached, otherwise releases it back to Pending for the next device.
     */
    suspend fun reportProgress(
        orderId: String,
        deviceId: String,
        completed: Int,
        release: Boolean = true,
        errorMessage: String? = null,
        accountId: String? = null,
        failureCode: String? = null,
        observedCount: Int? = null
    ): Resource<AppOrderDto> {
        return try {
            Resource.Success(
                api.reportOrderProgress(
                    orderId,
                    ReportProgressRequest(deviceId, completed, release, errorMessage, accountId, failureCode, observedCount)
                )
            )
        } catch (t: Throwable) {
            if (t is HttpException && t.code() == 401) {
                val recovered = authRepository.get().reloadDeviceSession(com.feedpilot.client.BuildConfig.VERSION_NAME)
                if (recovered) {
                    try {
                        return Resource.Success(
                            api.reportOrderProgress(
                                orderId,
                                ReportProgressRequest(deviceId, completed, release, errorMessage, accountId, failureCode, observedCount)
                            )
                        )
                    } catch (_: Throwable) {}
                }
            }
            Resource.Error(readApiError(t), t)
        }
    }

    /**
     * Reports multiple progress items in a single HTTP request batch.
     */
    suspend fun reportProgressBatch(
        deviceId: String,
        reports: List<BatchProgressItem>
    ): Resource<BatchReportProgressResponse> {
        if (reports.isEmpty()) return Resource.Success(BatchReportProgressResponse())
        return try {
            Resource.Success(
                api.reportOrderProgressBatch(
                    BatchReportProgressRequest(deviceId, reports)
                )
            )
        } catch (t: Throwable) {
            if (t is HttpException && t.code() == 401) {
                val recovered = authRepository.get().reloadDeviceSession(com.feedpilot.client.BuildConfig.VERSION_NAME)
                if (recovered) {
                    try {
                        return Resource.Success(
                            api.reportOrderProgressBatch(
                                BatchReportProgressRequest(deviceId, reports)
                            )
                        )
                    } catch (_: Throwable) {}
                }
            }
            Resource.Error(readApiError(t), t)
        }
    }

    /**
     * Drops a claim without reporting progress — used when a runner is stopped.
     *
     * Ungated, like [reportProgress]: both settle work this device already claimed, and they have
     * to keep working if the account is unlinked mid-run or the orders stay stuck as Processing
     * until the server expires them.
     */
    suspend fun releaseOrder(orderId: String, deviceId: String): Boolean = try {
        api.releaseOrder(orderId, ClaimOrdersRequest(deviceId = deviceId, batchSize = 1))
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Surfaces the backend's own refusal text — "Not enough coins…", "Quantity must be…" —
     * instead of a bare "HTTP 400", which tells the user nothing actionable.
     */
    private fun readApiError(t: Throwable): String = t.apiErrorMessage("Order failed")

    private companion object {
        const val NO_ACCOUNT = "Link an Instagram account first."
    }
}
