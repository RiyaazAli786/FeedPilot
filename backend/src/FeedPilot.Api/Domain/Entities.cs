namespace FeedPilot.Api.Domain;

public class User
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Name { get; set; } = string.Empty;
    public string Email { get; set; } = string.Empty;
    public string PasswordHash { get; set; } = string.Empty;
    public string? RefreshToken { get; set; }
    public DateTime? RefreshTokenExpiresAt { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    /// <summary>Current paid tier. Drives the coin-earn multiplier via <see cref="Services.PlanCatalog"/>.</summary>
    public SubscriptionPlan Plan { get; set; } = SubscriptionPlan.Free;
    /// <summary>When the paid plan lapses back to Free. Null for Free.</summary>
    public DateTime? PlanExpiresAt { get; set; }

    /// <summary>Unique referral code generated for this user account (e.g. TF-X9A2B4).</summary>
    public string? ReferralCode { get; set; }
    /// <summary>Which user referred this account, if any.</summary>
    public Guid? ReferredByUserId { get; set; }

    public Wallet? Wallet { get; set; }
    public ICollection<Account> Accounts { get; set; } = new List<Account>();
}

/// <summary>A managed social account belonging to a user.</summary>
public class Account
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }
    public string Username { get; set; } = string.Empty;
    public string? ProfilePictureUrl { get; set; }

    /// <summary>Opaque, encrypted session payload. Never returned to clients in plaintext.</summary>
    public string? SessionData { get; set; }
    public string? DeviceId { get; set; }

    /// <summary>Which cloned app build the account was added from.</summary>
    public string AppId { get; set; } = ClientIdentityDefaults.Unknown;
    public AccountStatus Status { get; set; } = AccountStatus.Active;
    public DateTime? LastLogin { get; set; }
    public DateTime? LastActive { get; set; }
    public long CoinsEarned { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;

    /// <summary>
    /// When this account last completed the upgrade checklist. The +1 coin bonus and the
    /// "already upgraded" gate both key off this being within the last 24 hours — see
    /// AccountsController.Upgrade and TasksController.SubmitResult.
    /// </summary>
    public DateTime? UpgradedAt { get; set; }

    public User? User { get; set; }
}

public class EngagementTask
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid OrderId { get; set; }
    public Guid? AccountId { get; set; }
    public TaskType TaskType { get; set; }

    /// <summary>Abstract target identifier / URL supplied by the backend order.</summary>
    public string TargetId { get; set; } = string.Empty;
    public TaskStatus Status { get; set; } = TaskStatus.Pending;
    public int RetryCount { get; set; }
    public int RewardCoins { get; set; } = 1;
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime? CompletedAt { get; set; }
}

/// <summary>
/// An order placed from the mobile app. The app never talks to an SMM panel directly —
/// it posts here, coins are debited, and the order is fulfilled under admin control from
/// the backend dashboard.
/// </summary>
public class AppOrder
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }

    public TaskType OrderType { get; set; }

    /// <summary>Profile URL for follows, post URL for likes.</summary>
    public string TargetUrl { get; set; } = string.Empty;
    public string? TargetUsername { get; set; }

    public int Quantity { get; set; }
    public int CompletedCount { get; set; }
    public int StartCount { get; set; }

    /// <summary>Coins debited when the order was accepted. Priced by the server, not the client.</summary>
    public long CoinsSpent { get; set; }

    /// <summary>Comment strings for comment tasks, separated by newline.</summary>
    public string? CommentText { get; set; }

    public AppOrderStatus Status { get; set; } = AppOrderStatus.Pending;

    /// <summary>
    /// Set once a refund has actually been credited for this order — guards the Cancel and
    /// admin Reject/Cancel refund paths so a race between the two (or two overlapping calls to
    /// the same one) can never credit the same order's refund twice.
    /// </summary>
    public bool RefundIssued { get; set; }

    // ----- Fulfilment bookkeeping, filled in by an admin from the dashboard -----
    public string? ProviderName { get; set; }
    public string? ProviderServiceId { get; set; }
    public string? ProviderOrderId { get; set; }
    public string? AdminNote { get; set; }
    public string? ErrorMessage { get; set; }

    /// <summary>Which cloned app build and install placed the order.</summary>
    public string AppId { get; set; } = ClientIdentityDefaults.Unknown;
    public string DeviceId { get; set; } = ClientIdentityDefaults.Unknown;

    // ----- External SMM panel orders -----
    /// <summary>True when this order was imported from an external SMM panel (e.g. smmorigin.com).</summary>
    public bool IsExternal { get; set; }

    /// <summary>The panel's own numeric order ID. Used to sync status back to the panel.</summary>
    public string? ExternalOrderId { get; set; }

    /// <summary>Human-readable service name the panel reported (e.g. "Ig Like"), for display only.</summary>
    public string? ProviderServiceName { get; set; }

    /// <summary>The panel customer who placed this order — distinct from <see cref="TargetUsername"/>, which is who gets followed/liked.</summary>
    public string? ProviderUsername { get; set; }

    /// <summary>What the panel charged its customer for this order, for admin visibility only — never used in our own coin pricing.</summary>
    public decimal? ProviderChargeAmount { get; set; }
    public string? ProviderChargeCurrency { get; set; }

    /// <summary>When the panel says the order was created, which can predate when we pulled it.</summary>
    public DateTime? ProviderCreatedAt { get; set; }

    public string? PanelLastPushedStatus { get; set; }
    public int? PanelLastPushedRemains { get; set; }
    public int? PanelLastPushedStartCount { get; set; }
    public string? PanelLastPushedReason { get; set; }
    public DateTime? PanelLastPushedAt { get; set; }

    // ----- Work claiming, so two devices never fulfil the same order -----
    /// <summary>Device currently holding the claim; null when the order is free.</summary>
    public string? ProcessingDeviceId { get; set; }

    /// <summary>When the claim was taken. A claim older than the configured timeout is stale.</summary>
    public DateTime? ProcessingStartedAt { get; set; }

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
    public DateTime? CompletedAt { get; set; }

    /// <summary>Higher priority orders are claimed first (default 0).</summary>
    public int Priority { get; set; } = 0;

    public User? User { get; set; }
}

public class Wallet
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }
    public long Coins { get; set; }
    public long LifetimeCoins { get; set; }
    public long PendingCoins { get; set; }
    public long WithdrawnCoins { get; set; }
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    public User? User { get; set; }
    public ICollection<WalletTransaction> Transactions { get; set; } = new List<WalletTransaction>();
}

public class WalletTransaction
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid WalletId { get; set; }
    public long Coins { get; set; }
    public WalletTransactionType Type { get; set; }
    public string? Reference { get; set; }
    public DateTime CreatedDate { get; set; } = DateTime.UtcNow;

    public Wallet? Wallet { get; set; }
}

public class Withdrawal
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid WalletId { get; set; }
    public long Coins { get; set; }
    public decimal Amount { get; set; }
    public PaymentMethod PaymentMethod { get; set; }
    public string? UpiId { get; set; }
    public string? BankDetails { get; set; }
    public string? UsdtAddress { get; set; }
    public WithdrawalStatus Status { get; set; } = WithdrawalStatus.Pending;

    /// <summary>Which cloned app build and install raised the request.</summary>
    public string AppId { get; set; } = ClientIdentityDefaults.Unknown;
    public string DeviceId { get; set; } = ClientIdentityDefaults.Unknown;

    /// <summary>
    /// Set once the coins have actually been taken off the balance. Coins are only reserved
    /// at request time; the debit happens when an admin marks the payment complete.
    /// </summary>
    public bool CoinsSettled { get; set; }

    public string? AdminNote { get; set; }
    public string? PaymentReference { get; set; }

    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime? ProcessedAt { get; set; }

    public Wallet? Wallet { get; set; }
}

/// <summary>Shared default so entities need no reference to the HTTP layer.</summary>
public static class ClientIdentityDefaults
{
    public const string Unknown = "unknown";
}

/// <summary>
/// A user's request to activate a paid plan, paid for by UPI out of band. The user pays the
/// configured UPI id and submits the payment's UTR; an admin verifies it on the dashboard and
/// approves, which is what actually activates the plan. Nothing activates automatically.
/// </summary>
public class SubscriptionRequest
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }
    public SubscriptionPlan Plan { get; set; }
    /// <summary>Price snapshot at request time, so a later catalogue change can't rewrite history.</summary>
    public int PriceInr { get; set; }
    /// <summary>The UPI id the user was told to pay (also snapshotted).</summary>
    public string Upi { get; set; } = string.Empty;
    /// <summary>The UPI transaction reference (UTR) the user submitted as proof of payment.</summary>
    public string Utr { get; set; } = string.Empty;
    public SubscriptionRequestStatus Status { get; set; } = SubscriptionRequestStatus.Pending;

    public string AppId { get; set; } = ClientIdentityDefaults.Unknown;
    public string DeviceId { get; set; } = ClientIdentityDefaults.Unknown;

    public string? AdminNote { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime? ProcessedAt { get; set; }

    public User? User { get; set; }
}

/// <summary>
/// A user asking for their password to be reset. There is no email delivery in this system,
/// so requests are queued for an admin to action from the dashboard.
/// </summary>
public class PasswordResetRequest
{
    public Guid Id { get; set; } = Guid.NewGuid();

    /// <summary>Null when the address matched no account — recorded anyway, so repeated
    /// requests for an unknown address are visible rather than silently dropped.</summary>
    public Guid? UserId { get; set; }

    public string Email { get; set; } = string.Empty;
    public string AppId { get; set; } = ClientIdentityDefaults.Unknown;
    public string DeviceId { get; set; } = ClientIdentityDefaults.Unknown;

    public PasswordResetStatus Status { get; set; } = PasswordResetStatus.Pending;
    public string? AdminNote { get; set; }

    public DateTime RequestedAt { get; set; } = DateTime.UtcNow;
    public DateTime? ProcessedAt { get; set; }
}

public class Device
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid? UserId { get; set; }
    public string AppId { get; set; } = ClientIdentityDefaults.Unknown;
    public string DeviceId { get; set; } = string.Empty;
    public string InstallationId { get; set; } = string.Empty;
    public string? AppInstanceId { get; set; }
    public string? AndroidId { get; set; }
    public string? AndroidVersion { get; set; }
    public string? AppVersion { get; set; }
    /// <summary>Human-readable "manufacturer model" (e.g. "samsung SM-G991B"), display-only.</summary>
    public string? DeviceModel { get; set; }
    /// <summary>The currently active Instagram/user account logged in on this app device instance.</summary>
    public string? ActiveAccount { get; set; }
    /// <summary>JSON array of all accounts logged in / configured on this device (e.g. ["user1", "user2"]).</summary>
    public string? LoggedInAccountsJson { get; set; }
    public string? ReferralCode { get; set; }
    public Guid? ReferredByUserId { get; set; }
    public DateTime RegisteredAt { get; set; } = DateTime.UtcNow;
    public DateTime LastSeenAt { get; set; } = DateTime.UtcNow;
}

/// <summary>Release metadata used by the auto-update flow.</summary>
public class AppRelease
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public int VersionCode { get; set; }
    public string VersionName { get; set; } = string.Empty;
    public string ApkUrl { get; set; } = string.Empty;
    public string Sha256 { get; set; } = string.Empty;
    public long SizeBytes { get; set; }
    public string? ReleaseNotes { get; set; }
    public bool ForceUpdate { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}

public class InstagramUserIdCache
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public string Username { get; set; } = string.Empty;
    public string UserId { get; set; } = string.Empty;
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}

/// <summary>
/// A profile the user wants to watch. This is intentionally separate from the logged-in
/// Instagram accounts that perform work; users can track public/profile handles without making
/// them runnable accounts.
/// </summary>
public class WatchedInstagramHandle
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid UserId { get; set; }
    public string AppId { get; set; } = ClientIdentityDefaults.Unknown;
    public string DeviceId { get; set; } = ClientIdentityDefaults.Unknown;
    public string Username { get; set; } = string.Empty;
    public string? ProfilePictureUrl { get; set; }
    public string? FullName { get; set; }
    public bool IsPrivate { get; set; }
    public long FollowerCount { get; set; }
    public long FollowingCount { get; set; }
    public long MediaCount { get; set; }
    public bool WatchEnabled { get; set; } = true;
    public int PollIntervalMinutes { get; set; } = 60;
    public DateTime? LastFetchedAt { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    public User? User { get; set; }
    public ICollection<WatchedInstagramPost> Posts { get; set; } = new List<WatchedInstagramPost>();
}

/// <summary>A feed item captured for a watched Instagram handle.</summary>
public class WatchedInstagramPost
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid WatchedInstagramHandleId { get; set; }
    public string PostId { get; set; } = string.Empty;
    public string? Code { get; set; }
    public string? Caption { get; set; }
    public string? MediaUrl { get; set; }
    public string? Permalink { get; set; }
    public int MediaType { get; set; }
    public long LikeCount { get; set; }
    public long CommentCount { get; set; }
    public DateTime? TakenAt { get; set; }
    public DateTime FetchedAt { get; set; } = DateTime.UtcNow;

    public WatchedInstagramHandle? Handle { get; set; }
}

/// <summary>
/// Singleton row (always Id=1) controlling every Android client's runner pacing: the gap
/// between individual actions, how often an idle account re-polls for work, and — in Random
/// mode — how many consecutive same-type actions run before switching type and cooling down.
/// Dashboard-editable (see AdminRunnerSettingsController) rather than per-device local settings,
/// so an operator can retune every running device at once without a client release.
/// </summary>
public class RunnerSettings
{
    public int Id { get; set; } = 1;
    public long ActionDelayMinMs { get; set; } = 1_500;
    public long ActionDelayMaxMs { get; set; } = 4_000;
    public long FetchDelayMs { get; set; } = 15_000;
    public int CooldownSeconds { get; set; } = 30;
    public bool AutoPartialCancelledTasks { get; set; } = true;
    public int CoinsPerInr { get; set; } = 5;
    public int MinWithdrawalInr { get; set; } = 100;

    // ----- Withdraw Payment Methods (Dashboard-configurable visibility & rates) -----
    public bool UpiEnabled { get; set; } = true;
    public bool BankEnabled { get; set; } = true;
    public bool UsdtBep20Enabled { get; set; }
    public int CoinsPerUsdt { get; set; } = 400;
    public decimal MinWithdrawalUsdt { get; set; } = 5m;
    /// <summary>How many orders each worker device claims per pass. Tune this from the
    /// dashboard to match device load: fewer accounts per device = higher batch is fine;
    /// many accounts per clone = lower batch so all accounts share work evenly.</summary>
    public int ClaimBatchSize { get; set; } = 10;

    // ----- Per-Action Coin Reward Schema (Dashboard-configurable) -----
    public int FollowCoinsNormal { get; set; } = 1;
    public int FollowCoinsUpgraded { get; set; } = 2;
    public int LikeCoinsNormal { get; set; } = 1;
    public int LikeCoinsUpgraded { get; set; } = 2;
    public int CommentCoinsNormal { get; set; } = 2;
    public int CommentCoinsUpgraded { get; set; } = 4;
    public int RepostCoinsNormal { get; set; } = 1;
    public int RepostCoinsUpgraded { get; set; } = 2;
    public int SavePostCoinsNormal { get; set; } = 1;
    public int SavePostCoinsUpgraded { get; set; } = 2;
    public int StoryViewCoinsNormal { get; set; } = 1;
    public int StoryViewCoinsUpgraded { get; set; } = 2;

    // ----- Per-Action App-Order Price Config (Dashboard-configurable buy rates) -----
    public int PricePerFollow { get; set; } = 8;
    public int PricePerLike { get; set; } = 3;
    public int PricePerComment { get; set; } = 10;
    public int PricePerRepost { get; set; } = 12;
    public int PricePerSavePost { get; set; } = 6;
    public int PricePerStoryView { get; set; } = 4;

    // ----- Refer & Earn Multi-Level Pool Settings -----
    public int ReferralBonusCoins { get; set; } = 100;
    public int ReferralLevel1Percent { get; set; } = 50;
    public int ReferralLevel2Percent { get; set; } = 25;
    public int ReferralLevel3Percent { get; set; } = 10;

    // ----- Per-Activity Streak Counts (Dashboard-configurable, per action type) -----
    /// <summary>How many consecutive Follow actions run before switching type and cooling down (Random mode).</summary>
    public int FollowStreakCount    { get; set; } = 5;
    public int LikeStreakCount      { get; set; } = 5;
    public int CommentStreakCount   { get; set; } = 3;
    public int RepostStreakCount    { get; set; } = 3;
    public int SavePostStreakCount  { get; set; } = 5;
    public int StoryViewStreakCount { get; set; } = 5;

    // ----- Per-Activity Daily Limits & Cooldown (Dashboard-configurable) -----
    public int MaxFollowsPerDay { get; set; } = 200;
    public int MaxLikesPerDay { get; set; } = 200;
    public int MaxCommentsPerDay { get; set; } = 50;
    public int MaxRepostsPerDay { get; set; } = 50;
    public int MaxSavePostsPerDay { get; set; } = 50;
    public int MaxStoryViewsPerDay { get; set; } = 500;
    public int DailyLimitCooldownMinutes { get; set; } = 60;

    // ----- Support / Community Links (Dashboard-configurable, live in app Settings) -----
    public string? SupportContactUrl { get; set; }
    public string? TelegramChannelUrl { get; set; }

    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
}

/// <summary>
/// Singleton row (always Id=1) holding the external SMM panel (smmorigin.com) integration
/// settings — base URL, API key, and the Follow/Like service IDs. Dashboard-editable (see
/// AdminSmmProviderController) so an operator can rotate the panel's API key or point at a
/// different provider without a redeploy. Seeded from the "SmmPanel" appsettings/env-var
/// section on first read (see SmmProviderConfigStore) so an existing deployment's configured
/// values carry over automatically instead of resetting to empty.
/// </summary>
/// <summary>
/// A record of coins moved from one user's wallet to another's — by username search, either
/// self-service from the app (<see cref="CoinTransferController"/>) or admin-initiated from the
/// dashboard (<see cref="AdminWalletsController"/>). Purely an audit trail; the actual balance
/// change is two <see cref="WalletTransaction"/> rows (TransferOut on the sender, TransferIn on
/// the receiver) written in the same DB transaction as this row.
/// </summary>
public class CoinTransfer
{
    public Guid Id { get; set; } = Guid.NewGuid();
    public Guid SenderUserId { get; set; }
    /// <summary>The linked account username the sender was identified by at transfer time.</summary>
    public string SenderUsername { get; set; } = string.Empty;
    public Guid ReceiverUserId { get; set; }
    /// <summary>The searched-for username that resolved to the receiver.</summary>
    public string ReceiverUsername { get; set; } = string.Empty;
    public long Coins { get; set; }
    /// <summary>"user" (sent from the app by its own owner) or "admin" (moved from the dashboard).</summary>
    public string InitiatedBy { get; set; } = "user";
    public string? Note { get; set; }
    public DateTime CreatedAt { get; set; } = DateTime.UtcNow;
}

public class SmmProviderConfig
{
    public int Id { get; set; } = 1;
    public string BaseUrl { get; set; } = "https://smmorigin.com/admin/adminapi/v2/";
    public string ApiKey { get; set; } = string.Empty;
    public int FollowServiceId { get; set; } = 171;
    public int LikeServiceId { get; set; } = 172;
    public int CommentServiceId { get; set; } = 177;
    public int CommentCustomServiceId { get; set; } = 178;
    public int RepostServiceId { get; set; } = 175;
    public int SavePostServiceId { get; set; } = 176;
    public int StoryViewServiceId { get; set; } = 179;
    /// <summary>Legacy single shared cadence — superseded by the independent per-operation
    /// intervals below. Kept only so already-persisted rows and backups round-trip cleanly;
    /// no longer read by <see cref="Services.SmmPanelSyncService"/>.</summary>
    public int PollIntervalMinutes { get; set; } = 5;

    // ----- Independent per-operation sync cadence (Dashboard-configurable) -----
    /// <summary>How often to pull new/updated orders from the panel (POST /orders/pull).</summary>
    public int FetchIntervalSeconds { get; set; } = 10;
    /// <summary>Max orders requested per /orders/pull call.</summary>
    public int FetchBatchSize { get; set; } = 100;
    /// <summary>How often to push local order status/remains changes back to the panel.</summary>
    public int StatusPushIntervalSeconds { get; set; } = 60;
    /// <summary>Max orders pushed per /orders/update call.</summary>
    public int StatusPushBatchSize { get; set; } = 100;
    /// <summary>Max consecutive batches worked through in one pass when more than one
    /// batch's worth of orders changed since the last push.</summary>
    public int StatusPushMaxBatchesPerPass { get; set; } = 5;
    /// <summary>How often to pull newly-cancelled orders from the panel (POST /cancel/pull).</summary>
    public int CancelPullIntervalSeconds { get; set; } = 60;
    /// <summary>Max cancelled orders requested per /cancel/pull call.</summary>
    public int CancelPullBatchSize { get; set; } = 100;

    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;
}

