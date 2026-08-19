package com.feedpilot.client.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ---------- Auth ----------
@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(val name: String, val email: String, val password: String)

/** Attaches a real email + password to the caller's own passwordless device account. */
@Serializable
data class ClaimAccountRequest(val email: String, val password: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

// ---------- Subscription / Upgrade ----------
@Serializable
data class PlanDto(
    val plan: String,
    val name: String,
    val priceInr: Int,
    val coinMultiplier: Int,
    val features: List<String> = emptyList()
)

@Serializable
data class SubscriptionDto(
    val plan: String,
    val name: String,
    val coinMultiplier: Int,
    val expiresAt: String? = null,
    val active: Boolean = false,
    /** A plan the user has paid for and is awaiting admin approval, if any. */
    val pendingPlan: String? = null,
    val pendingStatus: String? = null
)

/** UPI payee for the payment popup. */
@Serializable
data class PaymentInfoDto(
    val upiId: String,
    val payeeName: String,
    val enabled: Boolean = false
)

/** Submitted after paying by UPI: the chosen tier + the payment's UTR. */
@Serializable
data class SubscriptionPurchaseRequest(val plan: String, val utr: String)

/** Passwordless sign-in tied to this install; each app/clone gets its own account. */
@Serializable
data class DeviceAuthRequest(
    val installationId: String,
    val deviceId: String,
    val appInstanceId: String? = null,
    val androidVersion: String? = null,
    val appVersion: String? = null
)

@Serializable
data class AuthResponse(
    val userId: String,
    val name: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String
)

// ---------- Accounts ----------
@Serializable
data class CreateAccountRequest(
    @SerialName("username") val username: String,
    @SerialName("profilePictureUrl") val profilePictureUrl: String? = null,
    @SerialName("sessionData") val sessionData: String? = null,
    @SerialName("deviceId") val deviceId: String? = null,
    // The backend's own id for this account, once known. Lets the server match this row by id
    // instead of by username, so correcting a numeric placeholder handle to the real one updates
    // the existing row instead of forking a second one with the coin/history reset to zero.
    @SerialName("id") val id: String? = null
)

@Serializable
data class AccountDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("profilePictureUrl") val profilePictureUrl: String? = null,
    @SerialName("status") val status: String,
    @SerialName("lastLogin") val lastLogin: String? = null,
    @SerialName("lastActive") val lastActive: String? = null,
    @SerialName("coinsEarned") val coinsEarned: Long = 0,
    @SerialName("sessionData") val sessionData: String? = null,
    @SerialName("upgradedAt") val upgradedAt: String? = null
)

@Serializable
data class LeaderboardItemDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("profilePictureUrl") val profilePictureUrl: String? = null,
    @SerialName("coinsEarned") val coinsEarned: Long,
    @SerialName("rank") val rank: Int
)

@Serializable
data class LeaderboardResponseDto(
    @SerialName("items") val items: List<LeaderboardItemDto>,
    @SerialName("page") val page: Int,
    @SerialName("pageSize") val pageSize: Int,
    @SerialName("totalItems") val totalItems: Int
)

// ---------- Tasks ----------
@Serializable
data class TaskDto(
    val id: String,
    val orderId: String,
    val accountId: String? = null,
    val taskType: String,
    val targetId: String,
    val status: String,
    val retryCount: Int = 0,
    val rewardCoins: Int = 1,
    val createdAt: String
)

@Serializable
data class TaskResultRequest(
    val taskId: String,
    val accountId: String,
    val success: Boolean,
    val message: String? = null
)

@Serializable
data class ManualActionResultRequest(
    val accountId: String,
    val taskType: String,
    val target: String,
    val message: String? = null
)

@Serializable
data class CompletedTaskDto(
    val accountId: String,
    val taskType: String,
    val targetId: String,
    val completedAt: String
)

/**
 * One direct-to-Instagram HTTP call's outcome, relayed to the backend so it can forward it to
 * Telegram — see [com.feedpilot.client.data.remote.InstagramCallTelemetry]. Deliberately carries
 * no request headers/cookies/body: only a truncated, already-redacted response snippet.
 */
@Serializable
data class InstagramCallLogRequest(
    val method: String,
    val url: String,
    val statusCode: Int,
    val responseSnippet: String? = null
)

@Serializable
data class TaskResultResponse(
    val taskId: String,
    val status: String,
    val coinsAwarded: Int,
    val walletBalance: Long
)

// ---------- Wallet ----------
@Serializable
data class WalletDto(
    val totalCoins: Long,
    val lifetimeCoins: Long,
    val pendingCoins: Long,
    val withdrawnCoins: Long,
    val updatedAt: String
)

@Serializable
data class WalletTransactionDto(
    val id: String,
    val coins: Long,
    val type: String,
    val reference: String? = null,
    val createdDate: String
)

// ---------- Coin transfer (user-to-user, by username search) ----------
@Serializable
data class TransferSearchResultDto(
    val username: String,
    val profilePictureUrl: String? = null,
    val lastActive: String? = null
)

/** One autocomplete suggestion as the sender types a partial username. */
@Serializable
data class TransferSuggestionDto(
    val username: String,
    val profilePictureUrl: String? = null,
    val lastActive: String? = null
)

@Serializable
data class TransferCoinsRequest(
    val receiverUsername: String,
    val coins: Long,
    val note: String? = null
)

@Serializable
data class TransferCoinsResponse(
    val transferId: String,
    val receiverUsername: String,
    val coins: Long,
    val senderBalance: Long
)

@Serializable
data class CoinTransferDto(
    val id: String,
    val senderUsername: String,
    val receiverUsername: String,
    val coins: Long,
    val initiatedBy: String,
    val note: String? = null,
    val createdAt: String
)

// ---------- Withdrawals ----------
@Serializable
data class WithdrawRequest(
    val coins: Long,
    val paymentMethod: String,
    val upiId: String? = null,
    val bankDetails: String? = null,
    val usdtAddress: String? = null
)

@Serializable
data class WithdrawalDto(
    val id: String,
    val coins: Long,
    val amount: Double,
    val paymentMethod: String,
    val status: String,
    val createdAt: String,
    val processedAt: String? = null
)

// ---------- Devices ----------
@Serializable
data class RegisterDeviceRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("installationId") val installationId: String,
    @SerialName("appInstanceId") val appInstanceId: String? = null,
    @SerialName("androidVersion") val androidVersion: String? = null,
    @SerialName("appVersion") val appVersion: String? = null,
    @SerialName("deviceModel") val deviceModel: String? = null,
    @SerialName("activeAccount") val activeAccount: String? = null,
    @SerialName("loggedInAccounts") val loggedInAccounts: List<String>? = null
)

@Serializable
data class DeviceDto(
    @SerialName("id") val id: String,
    @SerialName("appId") val appId: String? = null,
    @SerialName("deviceId") val deviceId: String,
    @SerialName("installationId") val installationId: String,
    @SerialName("appInstanceId") val appInstanceId: String? = null,
    @SerialName("activeAccount") val activeAccount: String? = null,
    @SerialName("loggedInAccounts") val loggedInAccounts: List<String>? = null,
    @SerialName("registeredAt") val registeredAt: String
)

// ---------- Targets ----------
@Serializable
data class TargetMediaDto(
    val id: String,
    val imageUrl: String,
    val likes: Long,
    val comments: Long,
    val reposts: Long = 0L,
    val link: String? = null,
    val videoUrl: String? = null,
    val isVideo: Boolean = false,
    val ownerUsername: String? = null,
    val caption: String? = null
)

@Serializable
data class TargetProfileDto(
    val username: String,
    val fullName: String? = null,
    val avatarUrl: String? = null,
    val followers: Long = 0,
    val following: Long = 0,
    val posts: Long = 0,
    val isPrivate: Boolean = false,
    val media: List<TargetMediaDto> = emptyList()
)

// ---------- Update ----------
@Serializable
data class VersionDto(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val releaseNotes: String? = null,
    val forceUpdate: Boolean = false
)

// ---------- App Orders ----------
// Orders placed from the app go to the backend, which prices them, debits coins, and
// manages fulfilment. The client never names a price.
@Serializable
data class PlaceAppOrderRequest(
    val orderType: String,
    val targetUrl: String,
    val targetUsername: String? = null,
    val quantity: Int,
    /** For a Follow order, the target profile's exact follower count fetched right before ordering — the baseline progress is measured against. */
    val startCount: Int? = null,
    val comments: List<String>? = null
)

@Serializable
data class AppOrderDto(
    val id: String,
    val orderType: String,
    val targetUrl: String,
    val targetUsername: String? = null,
    val quantity: Int,
    val completedCount: Int = 0,
    val startCount: Int = 0,
    val coinsSpent: Long = 0,
    val status: String,
    val providerName: String? = null,
    val providerOrderId: String? = null,
    val errorMessage: String? = null,
    val createdAt: String? = null,
    val completedAt: String? = null,
    val workerCoinsAwarded: Int = 0
)

@Serializable
data class PlaceAppOrderResponse(
    val order: AppOrderDto,
    val walletBalance: Long = 0
)

/**
 * Runner pacing configured on the admin dashboard — see RunnerSettingsController /
 * AdminRunnerSettingsController. Polled by SettingsRepository and cached locally so
 * TaskRunnerService always has a value even when the last poll failed or is offline.
 */
@Serializable
data class RunnerSettingsDto(
    val actionDelayMinMs: Long,
    val actionDelayMaxMs: Long,
    val fetchDelayMs: Long,
    val cooldownSeconds: Int,
    val autoPartialCancelledTasks: Boolean = true,
    val coinsPerInr: Int = 5,
    val minWithdrawalInr: Int = 100,
    val claimBatchSize: Int = 10,
    val claimTimeoutMinutes: Int = 10,
    val followCoinsNormal: Int = 1,
    val followCoinsUpgraded: Int = 2,
    val likeCoinsNormal: Int = 1,
    val likeCoinsUpgraded: Int = 2,
    val commentCoinsNormal: Int = 2,
    val commentCoinsUpgraded: Int = 4,
    val repostCoinsNormal: Int = 1,
    val repostCoinsUpgraded: Int = 2,
    val savePostCoinsNormal: Int = 1,
    val savePostCoinsUpgraded: Int = 2,
    val storyViewCoinsNormal: Int = 1,
    val storyViewCoinsUpgraded: Int = 2,
    val pricePerFollow: Int = 8,
    val pricePerLike: Int = 3,
    val pricePerComment: Int = 10,
    val pricePerRepost: Int = 12,
    val pricePerSavePost: Int = 6,
    val pricePerStoryView: Int = 4,
    val referralCommissionPercent: Int = 10,
    val supportContactUrl: String? = null,
    val telegramChannelUrl: String? = null,
    val upiEnabled: Boolean = true,
    val bankEnabled: Boolean = true,
    val usdtBep20Enabled: Boolean = false,
    val coinsPerUsdt: Int = 400,
    val minWithdrawalUsdt: Double = 5.0,
    val followStreakCount: Int = 5,
    val likeStreakCount: Int = 5,
    val commentStreakCount: Int = 3,
    val repostStreakCount: Int = 3,
    val savePostStreakCount: Int = 5,
    val storyViewStreakCount: Int = 5,
    val maxFollowsPerDay: Int = 200,
    val maxLikesPerDay: Int = 200,
    val maxCommentsPerDay: Int = 50,
    val maxRepostsPerDay: Int = 50,
    val maxSavePostsPerDay: Int = 50,
    val maxStoryViewsPerDay: Int = 500,
    val dailyLimitCooldownMinutes: Int = 60
)

@Serializable
data class ReferralStatsDto(
    val referralCode: String,
    val referredByCode: String? = null,
    val totalReferredUsers: Int = 0,
    val totalReferralCoinsEarned: Long = 0L,
    val referralBonusCoins: Int = 100,
    val referralLevel1Percent: Int = 50,
    val referralLevel2Percent: Int = 25,
    val referralLevel3Percent: Int = 10
)

@Serializable
data class ApplyReferralRequest(
    val referralCode: String
)

@Serializable
data class ApplyReferralResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class PickUsernameRequest(
    val username: String,
    val deviceId: String,
    val appId: String? = null
)

@Serializable
data class PickUsernameResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class CheckPickedUsernameResponse(
    val isPicked: Boolean
)

@Serializable
data class PickedUsernameDto(
    val username: String,
    val pickedAt: String
)

@Serializable
data class PagedPickedUsernamesDto(
    val items: List<PickedUsernameDto>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class CheckDuplicateAccountResponse(
    val isDuplicate: Boolean
)

/** One page of an order listing, plus what's needed to drive pager controls. */
@Serializable
data class PagedOrdersDto(
    val items: List<AppOrderDto>,
    val page: Int,
    val pageSize: Int,
    val totalCount: Int,
    val totalPages: Int
)

@Serializable
data class OrderQuoteDto(
    val orderType: String,
    val quantity: Int,
    val coins: Long,
    val minQuantity: Int,
    val maxQuantity: Int
)

// ---------- Order processing (worker devices) ----------
@Serializable
data class ClaimOrdersRequest(
    val deviceId: String,
    val batchSize: Int = 10,
    val excludeOrderIds: List<String>? = null,
    val accountId: String? = null,
    val accountHandle: String? = null,
    /** Mirrors the account's TaskMode — null/absent means all order types (Random). */
    val taskTypes: List<String>? = null
)

@Serializable
data class ClaimedOrderDto(
    val id: String,
    val orderType: String,
    val targetUrl: String,
    val targetUsername: String? = null,
    val quantity: Int,
    val completedCount: Int = 0,
    val startCount: Int = 0,
    val remaining: Int = 0,
    val commentText: String? = null,
    val providerServiceId: String? = null
)

@Serializable
data class ReportProgressRequest(
    val deviceId: String,
    /** Running total for the order, not a delta — makes a retried report idempotent. */
    val completed: Int,
    val release: Boolean = true,
    val errorMessage: String? = null,
    /** Which linked account performed the work, so its own CoinsEarned tally gets credited too. */
    val accountId: String? = null,
    /** Machine-readable terminal failure reason for worker-side unrecoverable errors. */
    val failureCode: String? = null,
    /** Current observed public count for the target, e.g. current followers for follow orders. */
    val observedCount: Int? = null
)

@Serializable
data class BatchProgressItem(
    val orderId: String,
    val completed: Int,
    val release: Boolean = true,
    val errorMessage: String? = null,
    val accountId: String? = null,
    val failureCode: String? = null,
    val observedCount: Int? = null
)

@Serializable
data class BatchReportProgressRequest(
    val deviceId: String,
    val reports: List<BatchProgressItem>
)

@Serializable
data class BatchReportProgressResult(
    val orderId: String,
    val success: Boolean,
    val completedCount: Int,
    val status: String,
    val error: String? = null
)

@Serializable
data class BatchReportProgressResponse(
    val results: List<BatchReportProgressResult> = emptyList(),
    val totalCoinsAwarded: Int = 0
)

// ---------- Password reset ----------
@Serializable
data class ForgotPasswordRequest(val email: String)

@Serializable
data class ForgotPasswordResponse(val message: String)

// ---------- Instagram Cache ----------
@Serializable
data class ResolveCacheRequest(val username: String, val userId: String)

@Serializable
data class ResolveCacheResponse(val username: String, val userId: String)

// ---------- Watched Instagram Handles ----------
@Serializable
data class CreateWatchedHandleRequest(
    val username: String,
    val pollIntervalMinutes: Int = 60,
    val watchEnabled: Boolean = true
)

@Serializable
data class UpdateWatchedHandleRequest(
    val pollIntervalMinutes: Int? = null,
    val watchEnabled: Boolean? = null,
    val profilePictureUrl: String? = null,
    val fullName: String? = null,
    val isPrivate: Boolean? = null,
    val followerCount: Long? = null,
    val followingCount: Long? = null,
    val mediaCount: Long? = null
)

@Serializable
data class WatchedHandleDto(
    val id: String,
    val username: String,
    val profilePictureUrl: String? = null,
    val fullName: String? = null,
    val isPrivate: Boolean = false,
    val followerCount: Long = 0,
    val followingCount: Long = 0,
    val mediaCount: Long = 0,
    val watchEnabled: Boolean = true,
    val pollIntervalMinutes: Int = 60,
    val lastFetchedAt: String? = null,
    val createdAt: String,
    val savedPostCount: Int = 0
)

@Serializable
data class WatchedPostDto(
    val id: String,
    val watchedHandleId: String,
    val postId: String,
    val code: String? = null,
    val caption: String? = null,
    val mediaUrl: String? = null,
    val permalink: String? = null,
    val mediaType: Int = 0,
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val takenAt: String? = null,
    val fetchedAt: String
)

@Serializable
data class SaveWatchedFeedRequest(
    val posts: List<SaveWatchedPostRequest>,
    val profilePictureUrl: String? = null,
    val fullName: String? = null,
    val isPrivate: Boolean? = null,
    val followerCount: Long? = null,
    val followingCount: Long? = null,
    val mediaCount: Long? = null
)

@Serializable
data class SaveWatchedPostRequest(
    val postId: String,
    val code: String? = null,
    val caption: String? = null,
    val mediaUrl: String? = null,
    val permalink: String? = null,
    val mediaType: Int = 0,
    val likeCount: Long = 0,
    val commentCount: Long = 0,
    val takenAt: String? = null
)

