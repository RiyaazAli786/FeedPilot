package com.feedpilot.client.data

import com.feedpilot.client.common.extractInstagramHandle
import com.feedpilot.client.data.local.*
import com.feedpilot.client.data.remote.dto.*
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun AccountDto.toEntity() = AccountEntity(
    id = id,
    username = username,
    profilePictureUrl = profilePictureUrl,
    status = status,
    lastLogin = lastLogin,
    lastActive = lastActive,
    coinsEarned = coinsEarned,
    sessionCookies = sessionData ?: "",
    upgradedAt = upgradedAt
)

fun TaskDto.toEntity() = TaskEntity(
    id = id,
    orderId = orderId,
    accountId = accountId,
    taskType = taskType,
    targetId = targetId,
    status = status,
    retryCount = retryCount,
    rewardCoins = rewardCoins,
    createdAt = createdAt
)

fun WalletDto.toEntity() = WalletEntity(
    totalCoins = totalCoins,
    lifetimeCoins = lifetimeCoins,
    pendingCoins = pendingCoins,
    withdrawnCoins = withdrawnCoins,
    updatedAt = updatedAt,
    lastServerCoins = totalCoins
)

fun WalletTransactionDto.toEntity() = WalletTransactionEntity(
    id = id,
    coins = coins,
    type = type,
    reference = reference,
    createdDate = createdDate
)

fun WithdrawalDto.toEntity() = WithdrawalEntity(
    id = id,
    coins = coins,
    amount = amount,
    paymentMethod = paymentMethod,
    status = status,
    createdAt = createdAt,
    processedAt = processedAt
)

/**
 * Maps one page of backend orders into local history rows, keyed so a later fetch of the same
 * order merges into the same row instead of duplicating it.
 *
 * The id scheme has to match what already gets written elsewhere for the same order, since
 * Room upserts by primary key, not by [OrderHistoryEntity.smmOrderId]: "app_order_" for a
 * user's own placed orders (see OrdersViewModel.placeOrder, which now uses the same scheme) and
 * "backend_ext_" for externally-sourced orders (see TaskRepository.claimFromBackend, which
 * writes that id the moment a device claims one to fulfil).
 */
fun AppOrderDto.toHistoryEntity(source: OrderSource): OrderHistoryEntity {
    val idPrefix = if (source == OrderSource.EXTERNAL) "backend_ext_" else "app_order_"
    return OrderHistoryEntity(
        id = "$idPrefix$id",
        smmOrderId = id,
        providerNickname = if (source == OrderSource.EXTERNAL) "SMM Origin" else "FeedPilot Backend",
        providerUrl = if (source == OrderSource.EXTERNAL) "api/orders/external" else "api/orders",
        targetUsername = extractInstagramHandle(targetUrl).ifBlank { targetUsername?.trim().orEmpty() },
        orderType = orderType,
        quantity = quantity,
        completedCount = completedCount,
        coinsSpent = coinsSpent,
        status = status,
        timestamp = parseIsoToMillis(createdAt) ?: System.currentTimeMillis(),
        errorMessage = errorMessage,
        source = source.name
    )
}

/**
 * .NET's DateTime serializes as ISO-8601 UTC with up to 7 fractional digits ("...ss.fffffffZ").
 * java.time.Instant.parse handles that natively but needs API 26 — this app's minSdk is 24 with
 * no core-library desugaring configured, so fractional digits are truncated to millisecond
 * precision (padded if shorter) and parsed with SimpleDateFormat instead.
 */
fun parseIsoToMillis(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return runCatching {
        val trimmed = iso.trimEnd('Z')
        val normalized = if (trimmed.contains('.')) {
            val (datePart, fraction) = trimmed.split('.', limit = 2)
            "$datePart.${fraction.take(3).padEnd(3, '0')}"
        } else {
            "$trimmed.000"
        }
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        format.parse(normalized)?.time
    }.getOrNull()
}
