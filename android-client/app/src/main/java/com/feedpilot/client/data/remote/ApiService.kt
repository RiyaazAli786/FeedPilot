package com.feedpilot.client.data.remote

import com.feedpilot.client.data.remote.dto.*
import retrofit2.http.*

interface ApiService {

    // ---------- Auth ----------
    @POST("api/auth/register")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("api/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @POST("api/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AuthResponse

    @POST("api/auth/device")
    suspend fun deviceAuth(@Body body: DeviceAuthRequest): AuthResponse

    @POST("api/auth/claim")
    suspend fun claimAccount(@Body body: ClaimAccountRequest): AuthResponse

    @POST("api/auth/logout")
    suspend fun logout()

    @DELETE("api/auth/account")
    suspend fun deleteCurrentAccount()

    @POST("api/auth/forgot-password")
    suspend fun forgotPassword(@Body body: ForgotPasswordRequest): ForgotPasswordResponse

    // ---------- Accounts ----------
    @GET("api/accounts")
    suspend fun getAccounts(): List<AccountDto>

    @POST("api/accounts")
    suspend fun createAccount(@Body body: CreateAccountRequest): AccountDto

    /** True when [username] is already linked under a different clone on this same physical
     * device (matched server-side via the X-Hardware-Id header) — catches App Cloner/Dual Apps
     * duplicate-account farming that a purely local, single-install check can't see. */
    @GET("api/accounts/check-duplicate")
    suspend fun checkDuplicateAccount(@Query("username") username: String): CheckDuplicateAccountResponse

    @DELETE("api/accounts/{id}")
    suspend fun deleteAccount(@Path("id") id: String)

    @POST("api/accounts/{id}/refresh")
    suspend fun refreshAccountSession(@Path("id") id: String): AccountDto

    /** 24h-gated: 409 when this account was already upgraded within the last 24 hours. */
    @POST("api/accounts/{id}/upgrade")
    suspend fun upgradeAccount(@Path("id") id: String): AccountDto

    @GET("api/accounts/leaderboard")
    suspend fun getLeaderboard(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("sortOrder") sortOrder: String
    ): LeaderboardResponseDto

    // ---------- Tasks ----------
    @GET("api/tasks")
    suspend fun getPendingTasks(@Query("limit") limit: Int = 20): List<TaskDto>

    @POST("api/tasks/result")
    suspend fun submitTaskResult(@Body body: TaskResultRequest): TaskResultResponse

    @POST("api/tasks/manual-result")
    suspend fun submitManualActionResult(@Body body: ManualActionResultRequest): TaskResultResponse

    @GET("api/tasks/completed")
    suspend fun getCompletedTasks(): List<CompletedTaskDto>

    // ---------- Client-side call logging ----------
    @POST("api/log/instagram-call")
    suspend fun logInstagramCall(@Body body: InstagramCallLogRequest)

    // ---------- Runner settings (dashboard-controlled) ----------
    @GET("api/runner-settings")
    suspend fun getRunnerSettings(): RunnerSettingsDto

    // ---------- App Orders ----------
    @POST("api/orders")
    suspend fun placeAppOrder(@Body body: PlaceAppOrderRequest): PlaceAppOrderResponse

    @GET("api/orders")
    suspend fun getMyOrders(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50
    ): PagedOrdersDto

    @GET("api/orders/external")
    suspend fun getExternalOrders(
        @Query("page") page: Int = 1,
        @Query("pageSize") pageSize: Int = 50
    ): PagedOrdersDto

    @GET("api/orders/quote")
    suspend fun quoteOrder(
        @Query("orderType") orderType: String,
        @Query("quantity") quantity: Int
    ): OrderQuoteDto

    @POST("api/orders/{id}/cancel")
    suspend fun cancelAppOrder(@Path("id") id: String): AppOrderDto

    @DELETE("api/orders/{id}")
    suspend fun deleteAppOrder(@Path("id") id: String): retrofit2.Response<Unit>

    // ---------- Order processing ----------
    @POST("api/orders/processing/claim")
    suspend fun claimOrders(@Body body: ClaimOrdersRequest): List<ClaimedOrderDto>

    @POST("api/orders/processing/{id}/progress")
    suspend fun reportOrderProgress(
        @Path("id") id: String,
        @Body body: ReportProgressRequest
    ): AppOrderDto

    @POST("api/orders/processing/progress-batch")
    suspend fun reportOrderProgressBatch(
        @Body body: BatchReportProgressRequest
    ): BatchReportProgressResponse

    @POST("api/orders/processing/{id}/release")
    suspend fun releaseOrder(
        @Path("id") id: String,
        @Body body: ClaimOrdersRequest
    ): AppOrderDto

    // ---------- Wallet ----------
    @GET("api/wallet")
    suspend fun getWallet(): WalletDto

    @GET("api/wallet/history")
    suspend fun getWalletHistory(@Query("limit") limit: Int = 50): List<WalletTransactionDto>

    // ---------- Coin transfer ----------
    @GET("api/wallet/transfer/suggest")
    suspend fun suggestTransferUsernames(@Query("query") query: String): List<TransferSuggestionDto>

    @GET("api/wallet/transfer/search")
    suspend fun searchTransferUsername(@Query("username") username: String): TransferSearchResultDto

    @POST("api/wallet/transfer")
    suspend fun transferCoins(@Body body: TransferCoinsRequest): TransferCoinsResponse

    @GET("api/wallet/transfer/history")
    suspend fun getTransferHistory(): List<CoinTransferDto>

    // ---------- Referral ----------
    @GET("api/referral")
    suspend fun getReferralStats(): ReferralStatsDto

    @POST("api/referral/apply")
    suspend fun applyReferralCode(@Body body: ApplyReferralRequest): ApplyReferralResponse

    // ---------- Picked Usernames ----------
    @POST("api/picked-usernames/pick")
    suspend fun pickUsername(@Body body: PickUsernameRequest): PickUsernameResponse

    @GET("api/picked-usernames/check")
    suspend fun checkPickedUsername(
        @Query("username") username: String,
        @Query("deviceId") deviceId: String? = null,
        @Query("appId") appId: String? = null
    ): CheckPickedUsernameResponse

    @GET("api/picked-usernames")
    suspend fun getPickedUsernames(
        @Query("deviceId") deviceId: String,
        @Query("appId") appId: String? = null,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): PagedPickedUsernamesDto

    @DELETE("api/picked-usernames/{username}")
    suspend fun deletePickedUsername(
        @Path("username") username: String,
        @Query("deviceId") deviceId: String,
        @Query("appId") appId: String? = null
    ): retrofit2.Response<Unit>

    // ---------- Withdraw ----------
    @POST("api/withdraw")
    suspend fun withdraw(@Body body: WithdrawRequest): WithdrawalDto

    @GET("api/withdraw/history")
    suspend fun getWithdrawHistory(): List<WithdrawalDto>

    // ---------- Devices ----------
    @POST("api/devices/register")
    suspend fun registerDevice(@Body body: RegisterDeviceRequest): DeviceDto

    // ---------- Targets ----------
    // ---------- Subscription / Upgrade ----------
    @GET("api/subscription/plans")
    suspend fun getPlans(): List<PlanDto>

    @GET("api/subscription")
    suspend fun getSubscription(): SubscriptionDto

    @GET("api/subscription/payment")
    suspend fun getPaymentInfo(): PaymentInfoDto

    @POST("api/subscription/request")
    suspend fun requestUpgrade(@Body body: SubscriptionPurchaseRequest): SubscriptionDto

    // ---------- Update ----------
    @GET("api/version")
    suspend fun getLatestVersion(): VersionDto

    // ---------- Instagram Cache ----------
    @GET("api/instagram/resolve-cache")
    suspend fun getInstagramResolveCache(@Query("username") username: String): retrofit2.Response<ResolveCacheResponse>

    @POST("api/instagram/resolve-cache")
    suspend fun saveInstagramResolveCache(@Body body: ResolveCacheRequest): ResolveCacheResponse

    // ---------- Watched Instagram Handles ----------
    @GET("api/watched-handles")
    suspend fun getWatchedHandles(): List<WatchedHandleDto>

    @POST("api/watched-handles")
    suspend fun createWatchedHandle(@Body body: CreateWatchedHandleRequest): WatchedHandleDto

    @PATCH("api/watched-handles/{id}")
    suspend fun updateWatchedHandle(
        @Path("id") id: String,
        @Body body: UpdateWatchedHandleRequest
    ): WatchedHandleDto

    @DELETE("api/watched-handles/{id}")
    suspend fun deleteWatchedHandle(@Path("id") id: String)

    @GET("api/watched-handles/{id}/posts")
    suspend fun getWatchedPosts(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50
    ): List<WatchedPostDto>

    @POST("api/watched-handles/{id}/feed")
    suspend fun saveWatchedFeed(
        @Path("id") id: String,
        @Body body: SaveWatchedFeedRequest
    ): List<WatchedPostDto>
}

