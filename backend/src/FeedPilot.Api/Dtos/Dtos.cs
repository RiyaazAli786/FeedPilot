using System.ComponentModel.DataAnnotations;
using FeedPilot.Api.Domain;
using TaskStatus = FeedPilot.Api.Domain.TaskStatus;

namespace FeedPilot.Api.Dtos;

// ---------- Auth ----------
public record RegisterRequest([Required] string Name, [Required, EmailAddress] string Email, [Required, MinLength(6)] string Password);
public record LoginRequest([Required, EmailAddress] string Email, [Required] string Password);
public record RefreshRequest([Required] string RefreshToken);
public record AuthResponse(Guid UserId, string Name, string Email, string AccessToken, string RefreshToken, DateTime ExpiresAt);

/// <summary>
/// Attaches a real email + password to the caller's own device-bound account, so it survives a
/// reinstall that lands on a different device fingerprint (or a different device entirely) —
/// the user logs back in with these credentials instead of getting a brand-new empty wallet.
/// Only valid against an account that is still on the reserved device placeholder email; a
/// caller that has already claimed (or registered normally) must go through ForgotPassword
/// instead, not silently overwrite an existing real email.
/// </summary>
public record ClaimAccountRequest([Required, EmailAddress] string Email, [Required, MinLength(6)] string Password);

/// <summary>
/// Passwordless sign-in for an app install. Each clone (X-App-Id) and each installation
/// (InstallationId) gets its own account and wallet automatically, so the app never has to
/// show a login screen. The install id is the credential.
/// </summary>
public record DeviceAuthRequest(
    [Required] string InstallationId,
    string? DeviceId,
    string? AppInstanceId,
    string? AndroidId,
    string? AndroidVersion,
    string? AppVersion);

public record DeviceRestoreConfigDto(bool Enabled);
public record AdminDeviceRestoreRequest(
    string? OldAppId,
    [Required] string OldDeviceId,
    string? NewAppId,
    [Required] string NewDeviceId);

public record AdminClearDeviceOrderClaimsRequest(
    string? AppId,
    string? DeviceId);

/// <summary>
/// A passwordless device account that still holds coins but is no longer the active user for
/// any registered device row — most commonly because a client-side identity bug (or a hardware
/// fingerprint change) made a later device-auth call mint a fresh account instead of resuming
/// this one. Surfaced on the Device Restore panel so an admin can merge it back with `restore`.
/// </summary>
public record OrphanedDeviceAccountDto(
    Guid UserId, string Email, string AppId, string OldDeviceId, string Name,
    long Coins, long LifetimeCoins, DateTime CreatedAt);

// ---------- Subscription / Upgrade ----------
/// <summary>A purchasable tier, as shown on the upgrade screen.</summary>
public record PlanDto(SubscriptionPlan Plan, string Name, int PriceInr, int CoinMultiplier, IReadOnlyList<string> Features);

/// <summary>UPI payee shown in the payment popup so the user knows where to pay.</summary>
public record PaymentInfoDto(string UpiId, string PayeeName, bool Enabled);

/// <summary>
/// The caller's current subscription plus any purchase awaiting admin verification, so the app
/// can show "Pending approval" on the plan the user paid for.
/// </summary>
public record SubscriptionDto(
    SubscriptionPlan Plan, string Name, int CoinMultiplier, DateTime? ExpiresAt, bool Active,
    SubscriptionPlan? PendingPlan, SubscriptionRequestStatus? PendingStatus);

/// <summary>What the app submits after paying by UPI: the chosen tier and the payment's UTR.</summary>
public record SubscriptionPurchaseRequest([Required] SubscriptionPlan Plan, [Required, MaxLength(64)] string Utr);

// Admin dashboard view + moderation of purchase requests.
public record AdminSubscriptionDto(
    Guid Id, Guid UserId, string UserEmail, string AppId, string DeviceId,
    SubscriptionPlan Plan, int PriceInr, string Upi, string Utr,
    SubscriptionRequestStatus Status, string? AdminNote, DateTime CreatedAt, DateTime? ProcessedAt);
public record UpdateSubscriptionRequest(SubscriptionRequestStatus? Status, string? AdminNote);

// ---------- Password reset ----------
public record ForgotPasswordRequest([Required, EmailAddress] string Email);

public record PasswordResetRequestDto(
    Guid Id, Guid? UserId, string Email, string AppId, string DeviceId,
    PasswordResetStatus Status, string? AdminNote, DateTime RequestedAt, DateTime? ProcessedAt);

/// <summary>
/// Admin action on a reset request. Supplying <see cref="NewPassword"/> sets it immediately;
/// the admin then has to pass it to the user out of band, as there is no mail delivery.
/// </summary>
public record ResolvePasswordResetRequest(
    PasswordResetStatus Status,
    [MinLength(6)] string? NewPassword,
    [MaxLength(512)] string? AdminNote);

// ---------- Accounts ----------
public record CreateAccountRequest([Required] string Username, string? ProfilePictureUrl, string? SessionData, string? DeviceId, Guid? Id = null);
public record AccountDto(Guid Id, string Username, string? ProfilePictureUrl, AccountStatus Status,
    DateTime? LastLogin, DateTime? LastActive, long CoinsEarned, string? SessionData = null,
    DateTime? UpgradedAt = null);

public record LeaderboardItemDto(Guid Id, string Username, string? ProfilePictureUrl, long CoinsEarned, int Rank);
public record LeaderboardResponseDto(List<LeaderboardItemDto> Items, int Page, int PageSize, int TotalItems);

/// <summary>True when this Instagram username is already linked under a different clone/app
/// install running on the same physical device (matched by X-Hardware-Id) — catches the case a
/// single-install-scoped local check can't: the same account added twice via App Cloner/Dual Apps.</summary>
public record CheckDuplicateAccountResponse(bool IsDuplicate);

/// <summary>Admin-side view — adds the owning user and device, and omits the session payload.</summary>
public record AdminAccountDto(
    Guid Id, string Username, string? ProfilePictureUrl, AccountStatus Status,
    Guid UserId, string UserEmail, string AppId, string? DeviceId,
    bool HasSession, DateTime? LastLogin, DateTime? LastActive, long CoinsEarned, DateTime CreatedAt,
    /// <summary>
    /// The user's actual wallet balance (shared across every device/account logged into this
    /// user) — the amount a coin transfer really moves, as opposed to <see cref="CoinsEarned"/>
    /// which is a per-account display tally and can diverge from it.
    /// </summary>
    long WalletCoins = 0);

public record AdminInstagramSessionDto(
    Guid Id,
    string Username,
    string FullName,
    string? ProfilePictureUrl,
    long FollowerCount,
    long FollowingCount,
    long MediaCount,
    bool IsPrivate,
    bool IsVerified,
    string AppId,
    string? DeviceId,
    string UserEmail,
    DateTime? LastLogin,
    DateTime? LastActive,
    bool ProfileSynced);

/// <summary>Admin edit — only the fields supplied are changed. Session data is never editable here.</summary>
public record AdminInstagramBrowserCookieDto(
    string Name,
    string Value,
    string Domain,
    string Path,
    bool Secure,
    bool HttpOnly,
    string SameSite,
    long ExpirationDate);

public record AdminInstagramBrowserSessionDto(
    Guid AccountId,
    string Username,
    string ProfileUrl,
    List<AdminInstagramBrowserCookieDto> Cookies);

public record UpdateAdminAccountRequest(
    [MaxLength(64)] string? Username, string? ProfilePictureUrl, AccountStatus? Status);

// ---------- Tasks ----------
public record TaskDto(Guid Id, Guid OrderId, Guid? AccountId, TaskType TaskType, string TargetId,
    TaskStatus Status, int RetryCount, int RewardCoins, DateTime CreatedAt);
public record TaskResultRequest([Required] Guid TaskId, [Required] Guid AccountId, bool Success, string? Message);
public record ManualActionResultRequest([Required] Guid AccountId, [Required] TaskType TaskType, [Required] string Target, string? Message);
public record TaskResultResponse(Guid TaskId, TaskStatus Status, int CoinsAwarded, long WalletBalance);
public record CompletedTaskDto(Guid AccountId, string TaskType, string TargetId, DateTime CompletedAt);

// ---------- App Orders ----------
/// <summary>
/// What the app sends to place an order. Deliberately carries no price — the server
/// computes the coin cost so a client cannot name its own.
/// </summary>
public record PlaceAppOrderRequest(
    [Required] TaskType OrderType,
    [Required, MaxLength(512)] string TargetUrl,
    [MaxLength(128)] string? TargetUsername,
    [Required, Range(1, 1_000_000)] int Quantity,
    /// <summary>
    /// For a Follow order, the target profile's exact follower count at the moment the app
    /// fetched it — the baseline progress is measured against, same as an externally-sourced
    /// order's StartCount. Optional and client-supplied only as a starting point; never trusted
    /// for pricing.
    /// </summary>
    [Range(0, int.MaxValue)] int? StartCount = null,
    IReadOnlyList<string>? Comments = null);

public record AppOrderDto(
    Guid Id,
    TaskType OrderType,
    string TargetUrl,
    string? TargetUsername,
    int Quantity,
    int CompletedCount,
    int StartCount,
    long CoinsSpent,
    AppOrderStatus Status,
    string? ProviderName,
    string? ProviderOrderId,
    string? ErrorMessage,
    DateTime CreatedAt,
    DateTime? CompletedAt,
    int WorkerCoinsAwarded = 0);

public record PlaceAppOrderResponse(AppOrderDto Order, long WalletBalance);

public record CompletedAccountDto(
    Guid TaskId,
    Guid? AccountId,
    string Username,
    string DeviceId,
    string AppId,
    string Action,
    DateTime CompletedAt
);

/// <summary>One page of an order listing, plus enough to render pager controls (current
/// page/size, how many rows and pages exist in total).</summary>
public record PagedOrdersDto(
    IReadOnlyList<AppOrderDto> Items, int Page, int PageSize, int TotalCount, int TotalPages);

/// <summary>Admin-side view — adds the owning user, internal notes, and external panel info.</summary>
public record AdminAppOrderDto(
    Guid Id,
    Guid UserId,
    string UserEmail,
    string UserName,
    string AppId,
    string DeviceId,
    TaskType OrderType,
    string TargetUrl,
    string? TargetUsername,
    int Quantity,
    int CompletedCount,
    long CoinsSpent,
    AppOrderStatus Status,
    string? ProviderName,
    string? ProviderServiceId,
    string? ProviderOrderId,
    string? AdminNote,
    string? ErrorMessage,
    int StartCount,
    bool IsExternal,
    string? ExternalOrderId,
    string? ProviderServiceName,
    string? ProviderUsername,
    decimal? ProviderChargeAmount,
    string? ProviderChargeCurrency,
    DateTime? ProviderCreatedAt,
    string? ProcessingDeviceId,
    DateTime? ProcessingStartedAt,
    DateTime CreatedAt,
    DateTime UpdatedAt,
    DateTime? CompletedAt,
    string? CommentText = null,
    int Priority = 0);

public record UpdateAppOrderRequest(
    AppOrderStatus? Status,
    [MaxLength(128)] string? ProviderName,
    [MaxLength(64)] string? ProviderServiceId,
    [MaxLength(64)] string? ProviderOrderId,
    [MaxLength(512)] string? AdminNote,
    [Range(0, 1_000_000)] int? CompletedCount,
    string? ErrorMessage = null,
    string? CommentText = null,
    [Range(-100, 100)] int? Priority = null);

public record AdminPlaceAppOrderRequest(
    [Required] Guid UserId,
    [Required] TaskType OrderType,
    [Required, MaxLength(512)] string TargetUrl,
    [MaxLength(128)] string? TargetUsername,
    [Required, Range(1, 1_000_000)] int Quantity,
    [Range(0, int.MaxValue)] int? StartCount = null,
    IReadOnlyList<string>? Comments = null,
    [MaxLength(128)] string? AppId = null,
    [MaxLength(128)] string? DeviceId = null,
    [Range(-100, 100)] int Priority = 0,
    bool DebitWallet = false);

/// <summary>
/// Order totals for the dashboard. <paramref name="Apps"/> lists every app-id that has placed an
/// order, so the dashboard's app filter offers the same choices here as on the other tabs.
/// </summary>
public record AppOrderStatsDto(
    int Total, int Pending, int Approved, int Submitted, int InProgress,
    int Completed, int Rejected, int Failed, int NotFound, int Canceled,
    long CoinsSpent, int TotalQuantity,
    IReadOnlyList<string> Apps);

public record PagedResult<T>(IReadOnlyList<T> Items, int Total, int Page, int PageSize);

// ---------- Runner settings (dashboard-controlled Android runner pacing & coin pricing) ----------
public record RunnerSettingsDto(
    long ActionDelayMinMs, long ActionDelayMaxMs, long FetchDelayMs,
    int CooldownSeconds, bool AutoPartialCancelledTasks = true,
    int CoinsPerInr = 5, int MinWithdrawalInr = 100,
    int ClaimBatchSize = 10,
    int ClaimTimeoutMinutes = 10,
    int FollowCoinsNormal = 1, int FollowCoinsUpgraded = 2,
    int LikeCoinsNormal = 1, int LikeCoinsUpgraded = 2,
    int CommentCoinsNormal = 2, int CommentCoinsUpgraded = 4,
    int RepostCoinsNormal = 1, int RepostCoinsUpgraded = 2,
    int SavePostCoinsNormal = 1, int SavePostCoinsUpgraded = 2,
    int StoryViewCoinsNormal = 1, int StoryViewCoinsUpgraded = 2,
    int PricePerFollow = 8, int PricePerLike = 3,
    int PricePerComment = 10, int PricePerRepost = 12,
    int PricePerSavePost = 6, int PricePerStoryView = 4,
    int ReferralBonusCoins = 100,
    int ReferralLevel1Percent = 50,
    int ReferralLevel2Percent = 25,
    int ReferralLevel3Percent = 10,
    string? SupportContactUrl = null,
    string? TelegramChannelUrl = null,
    bool UpiEnabled = true,
    bool BankEnabled = true,
    bool UsdtBep20Enabled = false,
    int CoinsPerUsdt = 400,
    decimal MinWithdrawalUsdt = 5m,
    int FollowStreakCount = 5,
    int LikeStreakCount = 5,
    int CommentStreakCount = 3,
    int RepostStreakCount = 3,
    int SavePostStreakCount = 5,
    int StoryViewStreakCount = 5,
    int MaxFollowsPerDay = 200,
    int MaxLikesPerDay = 200,
    int MaxCommentsPerDay = 50,
    int MaxRepostsPerDay = 50,
    int MaxSavePostsPerDay = 50,
    int MaxStoryViewsPerDay = 500,
    int DailyLimitCooldownMinutes = 60);

public record UpdateRunnerSettingsRequest(
    [Range(500, 120_000)] long ActionDelayMinMs,
    [Range(500, 120_000)] long ActionDelayMaxMs,
    [Range(3_000, 600_000)] long FetchDelayMs,
    [Range(1, 3_600)] int CooldownSeconds,
    bool AutoPartialCancelledTasks = true,
    [Range(1, 1000)] int CoinsPerInr = 5,
    [Range(1, 10000)] int MinWithdrawalInr = 100,
    [Range(1, 50)] int ClaimBatchSize = 10,
    [Range(1, 60)] int ClaimTimeoutMinutes = 10,
    [Range(1, 1000)] int FollowCoinsNormal = 1,
    [Range(1, 1000)] int FollowCoinsUpgraded = 2,
    [Range(1, 1000)] int LikeCoinsNormal = 1,
    [Range(1, 1000)] int LikeCoinsUpgraded = 2,
    [Range(1, 1000)] int CommentCoinsNormal = 2,
    [Range(1, 1000)] int CommentCoinsUpgraded = 4,
    [Range(1, 1000)] int RepostCoinsNormal = 1,
    [Range(1, 1000)] int RepostCoinsUpgraded = 2,
    [Range(1, 1000)] int SavePostCoinsNormal = 1,
    [Range(1, 1000)] int SavePostCoinsUpgraded = 2,
    [Range(1, 1000)] int StoryViewCoinsNormal = 1,
    [Range(1, 1000)] int StoryViewCoinsUpgraded = 2,
    [Range(1, 1000)] int PricePerFollow = 8,
    [Range(1, 1000)] int PricePerLike = 3,
    [Range(1, 1000)] int PricePerComment = 10,
    [Range(1, 1000)] int PricePerRepost = 12,
    [Range(1, 1000)] int PricePerSavePost = 6,
    [Range(1, 1000)] int PricePerStoryView = 4,
    [Range(0, 100000)] int ReferralBonusCoins = 100,
    [Range(0, 100)] int ReferralLevel1Percent = 50,
    [Range(0, 100)] int ReferralLevel2Percent = 25,
    [Range(0, 100)] int ReferralLevel3Percent = 10,
    [MaxLength(500)] string? SupportContactUrl = null,
    [MaxLength(500)] string? TelegramChannelUrl = null,
    bool UpiEnabled = true,
    bool BankEnabled = true,
    bool UsdtBep20Enabled = false,
    [Range(1, 100000)] int CoinsPerUsdt = 400,
    [Range(0, 100000)] decimal MinWithdrawalUsdt = 5m,
    [Range(1, int.MaxValue)] int FollowStreakCount = 5,
    [Range(1, int.MaxValue)] int LikeStreakCount = 5,
    [Range(1, int.MaxValue)] int CommentStreakCount = 3,
    [Range(1, int.MaxValue)] int RepostStreakCount = 3,
    [Range(1, int.MaxValue)] int SavePostStreakCount = 5,
    [Range(1, int.MaxValue)] int StoryViewStreakCount = 5,
    [Range(0, 100000)] int MaxFollowsPerDay = 200,
    [Range(0, 100000)] int MaxLikesPerDay = 200,
    [Range(0, 100000)] int MaxCommentsPerDay = 50,
    [Range(0, 100000)] int MaxRepostsPerDay = 50,
    [Range(0, 100000)] int MaxSavePostsPerDay = 50,
    [Range(0, 100000)] int MaxStoryViewsPerDay = 500,
    [Range(1, 1440)] int DailyLimitCooldownMinutes = 60);

// ---------- Refer & Earn ----------
public record ReferralStatsDto(
    string ReferralCode,
    string? ReferredByCode,
    int TotalReferredUsers,
    long TotalReferralCoinsEarned,
    int ReferralBonusCoins,
    int ReferralLevel1Percent,
    int ReferralLevel2Percent,
    int ReferralLevel3Percent);

public record ApplyReferralRequest(
    [Required, MaxLength(32)] string ReferralCode);

public record ApplyReferralResponse(
    bool Success,
    string Message);

// ---------- Admin: Referral lookup ----------
/// <summary>One autocomplete suggestion as the admin types a partial referral code, email, or username.</summary>
public record AdminReferralSuggestionDto(string Label, string MatchedOn);

/// <summary>A user referred directly (level 1) by the looked-up referrer.</summary>
public record AdminReferredUserDto(
    Guid UserId, string Email, string Name, DateTime JoinedAt, SubscriptionPlan Plan,
    long CoinsEarnedFromThisUser);

public record AdminReferralLookupDto(
    Guid UserId, string Email, string Name, string ReferralCode, DateTime CreatedAt,
    SubscriptionPlan Plan, string? ReferredByCode, long WalletCoins,
    int TotalReferredUsers, long TotalReferralCoinsEarned,
    List<AdminReferredUserDto> ReferredUsers);

/// <summary>One row of the system-wide "every referral relationship" list — a referred user
/// paired with whoever referred them, independent of any specific lookup.</summary>
public record AdminReferralRowDto(
    Guid UserId, string Email, string Name, DateTime CreatedAt, SubscriptionPlan Plan,
    Guid ReferrerUserId, string ReferrerEmail, string? ReferrerCode,
    long CoinsEarnedFromThisUser);

// ---------- Order processing (worker devices) ----------
public record HasPendingOrdersResponse(bool HasPending, int Count);

/// <summary>Asks for up to BatchSize claimable orders. DeviceId identifies the claim holder.</summary>
public record ClaimOrdersRequest(
    [Required, MaxLength(128)] string DeviceId,
    [Range(1, 200)] int BatchSize = 10,
    List<Guid>? ExcludeOrderIds = null,
    /// <summary>When non-null/non-empty, only orders whose OrderType is in this list are returned.
    /// Mirrors the account's selected task mode (Follow, Like, Comment, Repost, SavePost, or all).
    /// </summary>
    List<string>? TaskTypes = null);

/// <summary>An order handed to a worker, with only what is needed to fulfil it.</summary>
public record ClaimedOrderDto(
    Guid Id,
    TaskType OrderType,
    string TargetUrl,
    string? TargetUsername,
    int Quantity,
    int CompletedCount,
    int StartCount,
    /// <summary>How many actions still owed — Quantity minus CompletedCount.</summary>
    int Remaining,
    string? CommentText = null,
    string? ProviderServiceId = null);

/// <summary>
/// Reports work done against a claim. Completed is the running total for the order, so a
/// retried report is idempotent rather than double-counting.
/// </summary>
public record ReportProgressRequest(
    [Required, MaxLength(128)] string DeviceId,
    [Range(0, 1_000_000)] int Completed,
    bool Release = true,
    [MaxLength(512)] string? ErrorMessage = null,
    /// <summary>
    /// Which linked Instagram account actually performed the work. Optional only for backward
    /// compatibility with older client builds; without it the per-account CoinsEarned tally
    /// (shown on the account's card in the app) cannot be credited, only the wallet total.
    /// </summary>
    Guid? AccountId = null,
    /// <summary>Machine-readable terminal failure reason for worker-side unrecoverable errors.</summary>
    [MaxLength(64)] string? FailureCode = null,
    /// <summary>Current observed public count for the target, e.g. current followers for follow orders.</summary>
    [Range(0, int.MaxValue)] int? ObservedCount = null);

public record BatchProgressItem(
    Guid OrderId,
    [Range(0, 1_000_000)] int Completed,
    bool Release = true,
    [MaxLength(512)] string? ErrorMessage = null,
    Guid? AccountId = null,
    [MaxLength(64)] string? FailureCode = null,
    [Range(0, int.MaxValue)] int? ObservedCount = null);

public record BatchReportProgressRequest(
    [Required, MaxLength(128)] string DeviceId,
    List<BatchProgressItem> Reports);

public record BatchReportProgressResult(
    Guid OrderId,
    bool Success,
    int CompletedCount,
    AppOrderStatus Status,
    string? Error = null);

public record BatchReportProgressResponse(
    List<BatchReportProgressResult> Results,
    int TotalCoinsAwarded);

// ---------- Wallet ----------
public record WalletDto(long TotalCoins, long LifetimeCoins, long PendingCoins, long WithdrawnCoins, DateTime UpdatedAt);
public record WalletTransactionDto(Guid Id, long Coins, WalletTransactionType Type, string? Reference, DateTime CreatedDate);

/// <summary>Admin-only balance adjustment. Negative values debit.</summary>
public record AdminCreditRequest(
    [Required, EmailAddress] string Email,
    long Coins,
    [MaxLength(256)] string? Reason);

// ---------- Coin transfer (user-to-user, by username search) ----------
/// <summary>A username search hit — enough for the sender to confirm who they're sending to.</summary>
public record TransferSearchResultDto(string Username, string? ProfilePictureUrl, DateTime? LastActive);

/// <summary>One autocomplete suggestion as the sender types a partial username.</summary>
public record TransferSuggestionDto(string Username, string? ProfilePictureUrl, DateTime? LastActive);

/// <summary>Self-service transfer: the caller sends from their own wallet to a searched username.</summary>
public record TransferCoinsRequest(
    [Required, MaxLength(128)] string ReceiverUsername,
    [Required, Range(1, long.MaxValue)] long Coins,
    [MaxLength(256)] string? Note);

public record TransferCoinsResponse(Guid TransferId, string ReceiverUsername, long Coins, long SenderBalance);

/// <summary>Admin-initiated transfer between two searched usernames — moves coins, mints nothing.</summary>
public record AdminTransferCoinsRequest(
    [Required, MaxLength(128)] string SenderUsername,
    [Required, MaxLength(128)] string ReceiverUsername,
    [Required, Range(1, long.MaxValue)] long Coins,
    [MaxLength(256)] string? Note);

public record CoinTransferDto(
    Guid Id, string SenderUsername, string ReceiverUsername, long Coins,
    string InitiatedBy, string? Note, DateTime CreatedAt);

public record SmmProviderConfigDto(
    string BaseUrl, string ApiKey, int FollowServiceId, int LikeServiceId,
    int CommentServiceId, int CommentCustomServiceId, int RepostServiceId, int SavePostServiceId,
    int StoryViewServiceId, int PollIntervalMinutes, DateTime UpdatedAt,
    int FetchIntervalSeconds = 10, int FetchBatchSize = 100,
    int StatusPushIntervalSeconds = 60, int StatusPushBatchSize = 100, int StatusPushMaxBatchesPerPass = 5,
    int CancelPullIntervalSeconds = 60, int CancelPullBatchSize = 100);

public record UpdateSmmProviderConfigRequest(
    [Required, MaxLength(256)] string BaseUrl,
    [MaxLength(256)] string? ApiKey,
    [Range(1, int.MaxValue)] int FollowServiceId,
    [Range(1, int.MaxValue)] int LikeServiceId,
    [Range(1, int.MaxValue)] int CommentServiceId,
    [Range(1, int.MaxValue)] int CommentCustomServiceId = 178,
    [Range(1, int.MaxValue)] int RepostServiceId = 175,
    [Range(1, int.MaxValue)] int SavePostServiceId = 176,
    [Range(1, int.MaxValue)] int StoryViewServiceId = 179,
    [Range(1, 1440)] int PollIntervalMinutes = 5,
    [Range(5, 3600)] int FetchIntervalSeconds = 10,
    [Range(1, 500)] int FetchBatchSize = 100,
    [Range(5, 3600)] int StatusPushIntervalSeconds = 60,
    [Range(1, 500)] int StatusPushBatchSize = 100,
    [Range(1, 20)] int StatusPushMaxBatchesPerPass = 5,
    [Range(5, 3600)] int CancelPullIntervalSeconds = 60,
    [Range(1, 500)] int CancelPullBatchSize = 100);

// ---------- Withdrawals ----------
public record WithdrawRequest([Required, Range(1, long.MaxValue)] long Coins, [Required] PaymentMethod PaymentMethod,
    string? UpiId, string? BankDetails, string? UsdtAddress);
public record WithdrawalDto(Guid Id, long Coins, decimal Amount, PaymentMethod PaymentMethod,
    WithdrawalStatus Status, DateTime CreatedAt, DateTime? ProcessedAt, bool CoinsSettled = false);

public record AdminWithdrawalDto(
    Guid Id, Guid UserId, string UserEmail, string AppId, string DeviceId,
    long Coins, decimal Amount, PaymentMethod PaymentMethod, string? UpiId, string? BankDetails, string? UsdtAddress,
    WithdrawalStatus Status, bool CoinsSettled, string? AdminNote, string? PaymentReference,
    DateTime CreatedAt, DateTime? ProcessedAt);

public record UpdateWithdrawalRequest(
    WithdrawalStatus? Status,
    [MaxLength(512)] string? AdminNote,
    [MaxLength(128)] string? PaymentReference);

// ---------- Devices ----------
public record RegisterDeviceRequest([Required] string DeviceId, [Required] string InstallationId,
    string? AppInstanceId, string? AndroidId, string? AndroidVersion, string? AppVersion,
    string? DeviceModel, string? ActiveAccount, List<string>? LoggedInAccounts);

public record SyncDeviceAccountsRequest([Required] string DeviceId, [Required] string InstallationId,
    string? ActiveAccount, List<string>? LoggedInAccounts);

public record DeviceDto(Guid Id, string AppId, string DeviceId, string InstallationId, string? AppInstanceId,
    string? ActiveAccount, List<string>? LoggedInAccounts, DateTime RegisteredAt);

/// <summary>Admin-side device view enriched with linked user, wallet balance, and active logged-in accounts.</summary>
public record AdminDeviceDto(
    Guid Id,
    string AppId,
    string DeviceId,
    string InstallationId,
    string? AppInstanceId,
    string? AndroidId,
    string? AndroidVersion,
    string? AppVersion,
    string? DeviceModel,
    string? ActiveAccount,
    List<string>? LoggedInAccounts,
    Guid? UserId,
    string? UserEmail,
    long Coins,
    DateTime RegisteredAt,
    DateTime LastSeenAt,
    /// <summary>
    /// How many distinct app/device rows (InstallationId groups per <see cref="AdminDevicesController.BuildDeviceGroups"/>)
    /// share this row's InstallationId (the stable hardware fingerprint) — i.e. how many clones,
    /// including this one, are running on the same physical device. 1 when this device has no
    /// known clones.
    /// </summary>
    int CloneCount);

// ---------- Update ----------
public record VersionDto(int VersionCode, string VersionName, string ApkUrl, string Sha256, long SizeBytes,
    string? ReleaseNotes, bool ForceUpdate);

public record AppReleaseItemDto(
    int VersionCode,
    string VersionName,
    string ApkName,
    string ApkUrl,
    string DownloadUrl,
    string Sha256,
    long SizeBytes,
    string? ReleaseNotes,
    bool ForceUpdate,
    DateTime CreatedAt);

// ---------- Backup / Restore ----------
/// <summary>
/// A full export of everything a backend migration must not lose: who owns what device, every
/// linked Instagram account, every wallet and its coin history, and every order (bought or being
/// fulfilled). Deliberately its own flat DTO set rather than the raw entities — the entities carry
/// navigation properties (User.Wallet, Wallet.Transactions, …) that would either recurse or dump
/// the same row twice depending on which side serializes first.
/// </summary>
public record BackupPayload(
    DateTime ExportedAt,
    List<BackupUser> Users,
    List<BackupDevice> Devices,
    List<BackupAccount> Accounts,
    List<BackupWallet> Wallets,
    List<BackupWalletTransaction> WalletTransactions,
    List<BackupAppOrder> AppOrders,
    List<BackupWithdrawal> Withdrawals,
    // Added after the sections above — all defaulted to null (treated as empty on restore) so a
    // backup file exported before these existed still restores cleanly instead of failing to
    // deserialize. Export always fills every one of these in.
    List<BackupTask>? Tasks = null,
    List<BackupSubscriptionRequest>? SubscriptionRequests = null,
    List<BackupPasswordResetRequest>? PasswordResetRequests = null,
    List<BackupCoinTransfer>? CoinTransfers = null,
    List<BackupRunnerSettings>? RunnerSettings = null,
    List<BackupSmmProviderConfig>? SmmProviderConfigs = null,
    List<BackupPickedUsername>? PickedUsernames = null);

/// <summary>
/// Single endpoint wrapper for database backup/restore. Use Action="backup" to return a
/// BackupPayload, or Action="restore" with Backup populated to upsert that payload.
/// </summary>
public record DatabaseBackupRestoreRequest([Required] string Action, BackupPayload? Backup = null);

public record DatabaseBackupRestoreResponse(
    string Action,
    DateTime CompletedAt,
    BackupPayload? Backup = null,
    RestoreResult? Restore = null);

public record BackupUser(
    Guid Id, string Name, string Email, string PasswordHash, string? RefreshToken,
    DateTime? RefreshTokenExpiresAt, DateTime CreatedAt, SubscriptionPlan Plan, DateTime? PlanExpiresAt);

public record BackupDevice(
    Guid Id, Guid? UserId, string DeviceId, string InstallationId, string? AppInstanceId,
    string? AndroidId, string? AndroidVersion, string? AppVersion, string? ActiveAccount,
    string? LoggedInAccountsJson, DateTime RegisteredAt, DateTime LastSeenAt,
    // Defaulted so a backup file exported before this field existed still restores cleanly.
    string? DeviceModel = null,
    // Defaulted so a backup file exported before devices had an app-id column still restores.
    string AppId = ClientIdentityDefaults.Unknown);

public record BackupAccount(
    Guid Id, Guid UserId, string Username, string? ProfilePictureUrl, string? SessionData,
    string? DeviceId, string AppId, AccountStatus Status, DateTime? LastLogin, DateTime? LastActive,
    long CoinsEarned, DateTime CreatedAt,
    // Defaulted so a backup file exported before this field existed still restores cleanly.
    DateTime? UpgradedAt = null);

public record BackupWallet(
    Guid Id, Guid UserId, long Coins, long LifetimeCoins, long PendingCoins, long WithdrawnCoins,
    DateTime UpdatedAt);

public record BackupWalletTransaction(
    Guid Id, Guid WalletId, long Coins, WalletTransactionType Type, string? Reference, DateTime CreatedDate);

public record BackupAppOrder(
    Guid Id, Guid UserId, TaskType OrderType, string TargetUrl, string? TargetUsername,
    int Quantity, int CompletedCount, long CoinsSpent, AppOrderStatus Status,
    string? ProviderName, string? ProviderServiceId, string? ProviderOrderId, string? AdminNote,
    string? ErrorMessage, string AppId, string DeviceId, int StartCount, bool IsExternal, string? ExternalOrderId,
    string? ProcessingDeviceId, DateTime? ProcessingStartedAt, DateTime CreatedAt, DateTime UpdatedAt,
    DateTime? CompletedAt,
    // The external SMM panel sync fields — defaulted so a backup file exported before they
    // existed still restores cleanly.
    string? ProviderServiceName = null, string? ProviderUsername = null,
    decimal? ProviderChargeAmount = null, string? ProviderChargeCurrency = null,
    DateTime? ProviderCreatedAt = null);

public record BackupWithdrawal(
    Guid Id, Guid WalletId, long Coins, decimal Amount, PaymentMethod PaymentMethod, string? UpiId,
    string? BankDetails, WithdrawalStatus Status, string AppId, string DeviceId, bool CoinsSettled,
    string? AdminNote, string? PaymentReference, DateTime CreatedAt, DateTime? ProcessedAt,
    string? UsdtAddress = null);

/// <summary>The follow/like work queue fanned out from an AppOrder to individual devices.</summary>
public record BackupTask(
    Guid Id, Guid OrderId, Guid? AccountId, TaskType TaskType, string TargetId,
    TaskStatus Status, int RetryCount, int RewardCoins, DateTime CreatedAt, DateTime? CompletedAt);

/// <summary>A user's paid-plan purchase request awaiting admin verification.</summary>
public record BackupSubscriptionRequest(
    Guid Id, Guid UserId, SubscriptionPlan Plan, int PriceInr, string Upi, string Utr,
    SubscriptionRequestStatus Status, string AppId, string DeviceId, string? AdminNote,
    DateTime CreatedAt, DateTime? ProcessedAt);

/// <summary>A queued "forgot password" request awaiting admin action.</summary>
public record BackupPasswordResetRequest(
    Guid Id, Guid? UserId, string Email, string AppId, string DeviceId,
    PasswordResetStatus Status, string? AdminNote, DateTime RequestedAt, DateTime? ProcessedAt);

/// <summary>Audit trail row for a user-to-user (or admin-initiated) coin transfer.</summary>
public record BackupCoinTransfer(
    Guid Id, Guid SenderUserId, string SenderUsername, Guid ReceiverUserId, string ReceiverUsername,
    long Coins, string InitiatedBy, string? Note, DateTime CreatedAt);

/// <summary>The singleton (Id=1) dashboard-editable runner pacing config.</summary>
public record BackupRunnerSettings(
    int Id, long ActionDelayMinMs, long ActionDelayMaxMs, long FetchDelayMs,
    int CooldownSeconds, DateTime UpdatedAt, bool AutoPartialCancelledTasks = true,
    int CoinsPerInr = 5, int MinWithdrawalInr = 100,
    bool UpiEnabled = true, bool BankEnabled = true, bool UsdtBep20Enabled = false,
    int CoinsPerUsdt = 400, decimal MinWithdrawalUsdt = 5m,
    int ClaimBatchSize = 10, int ClaimTimeoutMinutes = 10);

/// <summary>The singleton (Id=1) dashboard-editable SMM panel integration config, API key included.</summary>
public record BackupSmmProviderConfig(
    int Id, string BaseUrl, string ApiKey, int FollowServiceId, int LikeServiceId,
    int CommentServiceId, int RepostServiceId, int SavePostServiceId, int StoryViewServiceId,
    int PollIntervalMinutes, DateTime UpdatedAt,
    int FetchIntervalSeconds = 10, int FetchBatchSize = 100,
    int StatusPushIntervalSeconds = 60, int StatusPushBatchSize = 100, int StatusPushMaxBatchesPerPass = 5,
    int CancelPullIntervalSeconds = 60, int CancelPullBatchSize = 100);

public record BackupPickedUsername(
    Guid Id, string Username, Guid? UserId, string DeviceId, DateTime PickedAt);

/// <summary>Result of a restore — so the admin can see what actually happened, not just "200 OK".</summary>
public record RestoreResult(
    int UsersUpserted, int DevicesUpserted, int AccountsUpserted, int WalletsUpserted,
    int WalletTransactionsUpserted, int AppOrdersUpserted, int WithdrawalsUpserted,
    int TasksUpserted, int SubscriptionRequestsUpserted, int PasswordResetRequestsUpserted,
    int CoinTransfersUpserted, int RunnerSettingsUpserted, int SmmProviderConfigsUpserted,
    int PickedUsernamesUpserted = 0);

// ---------- Picked Usernames ----------
/// <summary>
/// AppId scopes a pick to one clone: DeviceId here is the hardware fingerprint (shared by every
/// clone on the phone), so without AppId two clones on the same device would see each other's
/// picks. Optional only so older clients that predate this field keep working (falls back to the
/// "unknown" app id, which just means their rows won't be split further).
/// </summary>
public record PickUsernameRequest([Required] string Username, [Required] string DeviceId, string? AppId = null);
public record PickUsernameResponse(bool Success, string Message);
public record CheckPickedUsernameResponse(bool IsPicked);
public record PickedUsernameDto(string Username, DateTime PickedAt);

/// <summary>Admin-assigned pick: reserves a username for one specific device+clone up front, so
/// the app never suggests it there. Rejected if the username is already picked anywhere.</summary>
public record AdminAssignPickedUsernameRequest([Required] string Username, [Required] string DeviceId, string? AppId);
public record AdminPickedUsernameDto(Guid Id, string Username, string DeviceId, string AppId, DateTime PickedAt);
public record PagedResponse<T>(List<T> Items, int TotalCount, int Page, int PageSize);

// ---------- Common ----------
public record ApiError(string Message, string? Code = null);

// ---------- Instagram Cache ----------
public record ResolveCacheRequest([Required] string Username, [Required] string UserId);
public record ResolveCacheResponse(string Username, string UserId);

// ---------- Watched Instagram Handles ----------
public record CreateWatchedHandleRequest(
    [Required, MaxLength(128)] string Username,
    [Range(15, 1440)] int PollIntervalMinutes = 60,
    bool WatchEnabled = true);

public record UpdateWatchedHandleRequest(
    [Range(15, 1440)] int? PollIntervalMinutes = null,
    bool? WatchEnabled = null,
    [MaxLength(512)] string? ProfilePictureUrl = null,
    [MaxLength(256)] string? FullName = null,
    bool? IsPrivate = null,
    long? FollowerCount = null,
    long? FollowingCount = null,
    long? MediaCount = null);

public record WatchedHandleDto(
    Guid Id,
    string AppId,
    string DeviceId,
    string Username,
    string? ProfilePictureUrl,
    string? FullName,
    bool IsPrivate,
    long FollowerCount,
    long FollowingCount,
    long MediaCount,
    bool WatchEnabled,
    int PollIntervalMinutes,
    DateTime? LastFetchedAt,
    DateTime CreatedAt,
    int SavedPostCount);

public record AdminWatchedHandleDeviceDto(
    string AppId,
    string DeviceId,
    int HandleCount,
    int MonitoringCount,
    DateTime? LastFetchedAt,
    DateTime? LastSeenAt,
    IReadOnlyList<AdminWatchedHandleDto> Handles);

public record AdminWatchedHandleDto(
    Guid Id,
    string Username,
    string? FullName,
    bool WatchEnabled,
    int PollIntervalMinutes,
    DateTime? LastFetchedAt,
    int SavedPostCount,
    DateTime CreatedAt);

public record WatchedPostDto(
    Guid Id,
    Guid WatchedHandleId,
    string PostId,
    string? Code,
    string? Caption,
    string? MediaUrl,
    string? Permalink,
    int MediaType,
    long LikeCount,
    long CommentCount,
    DateTime? TakenAt,
    DateTime FetchedAt);

public record SaveWatchedFeedRequest(
    List<SaveWatchedPostRequest> Posts,
    string? ProfilePictureUrl = null,
    string? FullName = null,
    bool? IsPrivate = null,
    long? FollowerCount = null,
    long? FollowingCount = null,
    long? MediaCount = null);

public record SaveWatchedPostRequest(
    [Required, MaxLength(128)] string PostId,
    [MaxLength(128)] string? Code = null,
    string? Caption = null,
    [MaxLength(1024)] string? MediaUrl = null,
    [MaxLength(512)] string? Permalink = null,
    int MediaType = 0,
    long LikeCount = 0,
    long CommentCount = 0,
    DateTime? TakenAt = null);

// ---------- Client-side call logging ----------
/// <summary>
/// One direct-to-Instagram HTTP call's outcome, reported by the device so it can be relayed to
/// Telegram — those calls never pass through this backend otherwise. Deliberately carries no
/// request headers, cookies, or request body: only the response is logged, and even that gets
/// redacted server-side before it reaches Telegram (see TelegramRequestLogger.RedactAndTruncate).
/// </summary>
public record ClientApiLogRequest(
    [Required] string Method,
    [Required] string Url,
    int StatusCode,
    string? ResponseSnippet);

// ---------- Dashboard idle lock ----------
/// <summary>
/// One-time nonce for the passcode challenge-response (see AdminDashboardController): the browser
/// hashes the passcode against <see cref="Nonce"/> instead of ever transmitting it, and
/// <see cref="Token"/> is how the server finds the matching nonce again on verify.
/// </summary>
public record DashboardPasscodeChallengeResponse(string Token, string Nonce);

/// <summary>
/// <see cref="Hash"/> is Base64(HMAC-SHA256(key: passcode, message: the challenge's nonce)), not
/// the passcode itself. <see cref="Token"/> identifies which challenge it answers.
/// </summary>
public record VerifyDashboardPasscodeRequest([Required] string Token, [Required] string Hash);
public record DashboardLockStatusResponse(bool Enabled);
public record DashboardFeatureFlagsResponse(bool ShowJsonUploadFeature);

// ---------- Admin session auth ----------
/// <summary>
/// Challenge-response login for the admin API key / backup passcode, same shape as the dashboard
/// idle-lock's challenge above: the browser HMACs the secret against <see cref="Nonce"/> instead of
/// ever transmitting it, and <see cref="Token"/> is how the server finds that nonce again on login.
/// </summary>
public record AdminLoginChallengeResponse(string Token, string Nonce);
public record VerifyAdminLoginRequest([Required] string Token, [Required] string Hash);

/// <summary>Bearer token for subsequent admin requests (<c>X-Admin-Session</c>) — the raw admin key
/// is used once, at login, and never stored or replayed after this.</summary>
public record AdminSessionResponse(string SessionToken, DateTime ExpiresAt);

public record BackupLoginChallengeResponse(string Token, string Nonce);
public record VerifyBackupLoginRequest([Required] string Token, [Required] string Hash);

/// <summary>Bearer token for subsequent backup requests (<c>X-Backup-Session</c>).</summary>
public record BackupSessionResponse(string SessionToken, DateTime ExpiresAt);

public record ClientCrashLogRequest(
    [Required] string Title,
    string? Summary,
    [Required] string StackTrace);

