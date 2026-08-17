using Microsoft.EntityFrameworkCore;

namespace FeedPilot.Api.Data;

public static class DatabaseSchemaMigrator
{
    public static void EnsureSchemaUpToDate(AppDbContext db)
    {
        try
        {
            var isPostgres = db.Database.IsNpgsql();
            var isSqlite = db.Database.IsSqlite();

            if (isPostgres)
            {
                var sqls = new[]
                {
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"ActiveAccount\" text;",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"LoggedInAccountsJson\" text;",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"AppId\" character varying(128) NOT NULL DEFAULT 'unknown';",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"AppInstanceId\" text;",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"AndroidId\" text;",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"AndroidVersion\" text;",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"AppVersion\" text;",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"DeviceModel\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"IsExternal\" boolean NOT NULL DEFAULT false;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ExternalOrderId\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProviderName\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProviderServiceId\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProviderOrderId\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProcessingDeviceId\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProcessingStartedAt\" timestamp with time zone;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"AdminNote\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ErrorMessage\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"StartCount\" integer NOT NULL DEFAULT 0;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProviderServiceName\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProviderUsername\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProviderChargeAmount\" numeric;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProviderChargeCurrency\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ProviderCreatedAt\" timestamp with time zone;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"PanelLastPushedStatus\" character varying(32);",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"PanelLastPushedRemains\" integer;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"PanelLastPushedStartCount\" integer;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"PanelLastPushedReason\" character varying(512);",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"PanelLastPushedAt\" timestamp with time zone;",
                    "ALTER TABLE \"Accounts\" ADD COLUMN IF NOT EXISTS \"UpgradedAt\" timestamp with time zone;",
                    // Retrofits the uniqueness a fresh EnsureCreated() DB already gets from the
                    // model (see AppDbContext). Guards an already-provisioned DB against a panel
                    // order ever being imported twice, even if two syncs raced before this landed.
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"CommentText\" text;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"RefundIssued\" boolean NOT NULL DEFAULT false;",
                    "CREATE UNIQUE INDEX IF NOT EXISTS \"UX_AppOrders_ExternalOrderId\" ON \"AppOrders\" (\"ExternalOrderId\") WHERE \"ExternalOrderId\" IS NOT NULL;",
                    // Auto-provisions the InstagramUserIdCaches table
                    "CREATE TABLE IF NOT EXISTS \"InstagramUserIdCaches\" (" +
                    "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_InstagramUserIdCaches\" PRIMARY KEY," +
                    "    \"Username\" character varying(128) NOT NULL," +
                    "    \"UserId\" character varying(64) NOT NULL," +
                    "    \"CreatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')" +
                    ");",
                    "CREATE UNIQUE INDEX IF NOT EXISTS \"IX_InstagramUserIdCaches_Username\" ON \"InstagramUserIdCaches\" (\"Username\");",
                    // FeedPilot watched profiles: app-owned cards plus captured feed post ids.
                    "CREATE TABLE IF NOT EXISTS \"WatchedInstagramHandles\" (" +
                    "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_WatchedInstagramHandles\" PRIMARY KEY," +
                    "    \"UserId\" uuid NOT NULL," +
                    "    \"AppId\" character varying(128) NOT NULL DEFAULT 'unknown'," +
                    "    \"DeviceId\" character varying(128) NOT NULL DEFAULT 'unknown'," +
                    "    \"Username\" character varying(128) NOT NULL," +
                    "    \"ProfilePictureUrl\" character varying(512)," +
                    "    \"FullName\" character varying(256)," +
                    "    \"IsPrivate\" boolean NOT NULL DEFAULT false," +
                    "    \"FollowerCount\" bigint NOT NULL DEFAULT 0," +
                    "    \"FollowingCount\" bigint NOT NULL DEFAULT 0," +
                    "    \"MediaCount\" bigint NOT NULL DEFAULT 0," +
                    "    \"WatchEnabled\" boolean NOT NULL DEFAULT true," +
                    "    \"PollIntervalMinutes\" integer NOT NULL DEFAULT 60," +
                    "    \"LastFetchedAt\" timestamp with time zone," +
                    "    \"CreatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')," +
                    "    \"UpdatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')" +
                    ");",
                    "ALTER TABLE \"WatchedInstagramHandles\" ADD COLUMN IF NOT EXISTS \"AppId\" character varying(128) NOT NULL DEFAULT 'unknown';",
                    "ALTER TABLE \"WatchedInstagramHandles\" ADD COLUMN IF NOT EXISTS \"DeviceId\" character varying(128) NOT NULL DEFAULT 'unknown';",
                    "ALTER TABLE \"WatchedInstagramHandles\" ADD COLUMN IF NOT EXISTS \"FollowerCount\" bigint NOT NULL DEFAULT 0;",
                    "ALTER TABLE \"WatchedInstagramHandles\" ADD COLUMN IF NOT EXISTS \"FollowingCount\" bigint NOT NULL DEFAULT 0;",
                    "ALTER TABLE \"WatchedInstagramHandles\" ADD COLUMN IF NOT EXISTS \"MediaCount\" bigint NOT NULL DEFAULT 0;",
                    "DROP INDEX IF EXISTS \"UX_WatchedInstagramHandles_User_Username\";",
                    "CREATE UNIQUE INDEX IF NOT EXISTS \"UX_WatchedInstagramHandles_User_App_Device_Username\" ON \"WatchedInstagramHandles\" (\"UserId\", \"AppId\", \"DeviceId\", \"Username\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_WatchedInstagramHandles_App_Device\" ON \"WatchedInstagramHandles\" (\"AppId\", \"DeviceId\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_WatchedInstagramHandles_Due\" ON \"WatchedInstagramHandles\" (\"WatchEnabled\", \"LastFetchedAt\");",
                    "CREATE TABLE IF NOT EXISTS \"WatchedInstagramPosts\" (" +
                    "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_WatchedInstagramPosts\" PRIMARY KEY," +
                    "    \"WatchedInstagramHandleId\" uuid NOT NULL," +
                    "    \"PostId\" character varying(128) NOT NULL," +
                    "    \"Code\" character varying(128)," +
                    "    \"Caption\" text," +
                    "    \"MediaUrl\" character varying(1024)," +
                    "    \"Permalink\" character varying(512)," +
                    "    \"MediaType\" integer NOT NULL DEFAULT 0," +
                    "    \"LikeCount\" bigint NOT NULL DEFAULT 0," +
                    "    \"CommentCount\" bigint NOT NULL DEFAULT 0," +
                    "    \"TakenAt\" timestamp with time zone," +
                    "    \"FetchedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')," +
                    "    CONSTRAINT \"FK_WatchedInstagramPosts_WatchedInstagramHandles_WatchedInstagramHandleId\" FOREIGN KEY (\"WatchedInstagramHandleId\") REFERENCES \"WatchedInstagramHandles\" (\"Id\") ON DELETE CASCADE" +
                    ");",
                    "CREATE UNIQUE INDEX IF NOT EXISTS \"UX_WatchedInstagramPosts_Handle_Post\" ON \"WatchedInstagramPosts\" (\"WatchedInstagramHandleId\", \"PostId\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_WatchedInstagramPosts_TakenAt\" ON \"WatchedInstagramPosts\" (\"TakenAt\");",
                    // Auto-provisions the RunnerSettings table (dashboard-editable runner pacing).
                    "CREATE TABLE IF NOT EXISTS \"RunnerSettings\" (" +
                    "    \"Id\" integer NOT NULL CONSTRAINT \"PK_RunnerSettings\" PRIMARY KEY," +
                    "    \"ActionDelayMinMs\" bigint NOT NULL DEFAULT 1500," +
                    "    \"ActionDelayMaxMs\" bigint NOT NULL DEFAULT 4000," +
                    "    \"FetchDelayMs\" bigint NOT NULL DEFAULT 15000," +
                    "    \"StreakLength\" integer NOT NULL DEFAULT 4," +
                    "    \"CooldownSeconds\" integer NOT NULL DEFAULT 30," +
                    "    \"AutoPartialCancelledTasks\" boolean NOT NULL DEFAULT true," +
                    "    \"CoinsPerInr\" integer NOT NULL DEFAULT 5," +
                    "    \"MinWithdrawalInr\" integer NOT NULL DEFAULT 100," +
                    "    \"UpdatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')" +
                    ");",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"AutoPartialCancelledTasks\" boolean NOT NULL DEFAULT true;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"CoinsPerInr\" integer NOT NULL DEFAULT 5;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"MinWithdrawalInr\" integer NOT NULL DEFAULT 100;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"ClaimBatchSize\" integer NOT NULL DEFAULT 10;",
                    "ALTER TABLE \"Users\" ADD COLUMN IF NOT EXISTS \"ReferralCode\" text;",
                    "ALTER TABLE \"Users\" ADD COLUMN IF NOT EXISTS \"ReferredByUserId\" uuid;",
                    "CREATE UNIQUE INDEX IF NOT EXISTS \"UX_Users_ReferralCode\" ON \"Users\" (\"ReferralCode\") WHERE \"ReferralCode\" IS NOT NULL;",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"ReferralCode\" text;",
                    "ALTER TABLE \"Devices\" ADD COLUMN IF NOT EXISTS \"ReferredByUserId\" uuid;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"FollowCoinsNormal\" integer NOT NULL DEFAULT 1;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"FollowCoinsUpgraded\" integer NOT NULL DEFAULT 2;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"LikeCoinsNormal\" integer NOT NULL DEFAULT 1;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"LikeCoinsUpgraded\" integer NOT NULL DEFAULT 2;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"CommentCoinsNormal\" integer NOT NULL DEFAULT 2;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"CommentCoinsUpgraded\" integer NOT NULL DEFAULT 4;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"RepostCoinsNormal\" integer NOT NULL DEFAULT 1;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"RepostCoinsUpgraded\" integer NOT NULL DEFAULT 2;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"SavePostCoinsNormal\" integer NOT NULL DEFAULT 1;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"SavePostCoinsUpgraded\" integer NOT NULL DEFAULT 2;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"StoryViewCoinsNormal\" integer NOT NULL DEFAULT 1;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"StoryViewCoinsUpgraded\" integer NOT NULL DEFAULT 2;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"ReferralCommissionPercent\" integer NOT NULL DEFAULT 10;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"ReferralBonusCoins\" integer NOT NULL DEFAULT 100;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"ReferralLevel1Percent\" integer NOT NULL DEFAULT 50;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"ReferralLevel2Percent\" integer NOT NULL DEFAULT 25;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"ReferralLevel3Percent\" integer NOT NULL DEFAULT 10;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"SupportContactUrl\" text;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"PricePerFollow\" integer NOT NULL DEFAULT 8;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"PricePerLike\" integer NOT NULL DEFAULT 3;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"PricePerComment\" integer NOT NULL DEFAULT 10;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"PricePerRepost\" integer NOT NULL DEFAULT 12;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"PricePerSavePost\" integer NOT NULL DEFAULT 6;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"PricePerStoryView\" integer NOT NULL DEFAULT 4;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"TelegramChannelUrl\" text;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"UpiEnabled\" boolean NOT NULL DEFAULT true;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"BankEnabled\" boolean NOT NULL DEFAULT true;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"UsdtBep20Enabled\" boolean NOT NULL DEFAULT false;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"CoinsPerUsdt\" integer NOT NULL DEFAULT 400;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"MinWithdrawalUsdt\" numeric(18,2) NOT NULL DEFAULT 5;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"StreakMinLength\" integer NOT NULL DEFAULT 3;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"StreakMaxLength\" integer NOT NULL DEFAULT 5;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"FollowStreakCount\" integer NOT NULL DEFAULT 5;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"LikeStreakCount\" integer NOT NULL DEFAULT 5;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"CommentStreakCount\" integer NOT NULL DEFAULT 3;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"RepostStreakCount\" integer NOT NULL DEFAULT 3;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"SavePostStreakCount\" integer NOT NULL DEFAULT 5;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"StoryViewStreakCount\" integer NOT NULL DEFAULT 5;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"MaxFollowsPerDay\" integer NOT NULL DEFAULT 200;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"MaxLikesPerDay\" integer NOT NULL DEFAULT 200;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"MaxCommentsPerDay\" integer NOT NULL DEFAULT 50;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"MaxRepostsPerDay\" integer NOT NULL DEFAULT 50;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"MaxSavePostsPerDay\" integer NOT NULL DEFAULT 50;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"MaxStoryViewsPerDay\" integer NOT NULL DEFAULT 500;",
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"DailyLimitCooldownMinutes\" integer NOT NULL DEFAULT 60;",
                    "ALTER TABLE \"Withdrawals\" ADD COLUMN IF NOT EXISTS \"UsdtAddress\" character varying(128);",
                    "CREATE TABLE IF NOT EXISTS \"PickedUsernames\" (" +
                    "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_PickedUsernames\" PRIMARY KEY," +
                    "    \"Username\" character varying(128) NOT NULL," +
                    "    \"UserId\" uuid NULL," +
                    "    \"DeviceId\" character varying(128) NOT NULL DEFAULT ''," +
                    "    \"PickedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')" +
                    ");",
                    "CREATE INDEX IF NOT EXISTS \"IX_PickedUsernames_Username\" ON \"PickedUsernames\" (\"Username\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_PickedUsernames_Username_DeviceId\" ON \"PickedUsernames\" (\"Username\", \"DeviceId\");",
                    "ALTER TABLE \"PickedUsernames\" ADD COLUMN IF NOT EXISTS \"AppId\" character varying(128) NOT NULL DEFAULT 'unknown';",
                    "CREATE INDEX IF NOT EXISTS \"IX_PickedUsernames_DeviceId_AppId\" ON \"PickedUsernames\" (\"DeviceId\", \"AppId\");",
                    // Auto-provisions the SmmProviderConfigs table (dashboard-editable SMM panel integration).
                    "CREATE TABLE IF NOT EXISTS \"SmmProviderConfigs\" (" +
                    "    \"Id\" integer NOT NULL CONSTRAINT \"PK_SmmProviderConfigs\" PRIMARY KEY," +
                    "    \"BaseUrl\" character varying(256) NOT NULL DEFAULT ''," +
                    "    \"ApiKey\" character varying(256) NOT NULL DEFAULT ''," +
                    "    \"FollowServiceId\" integer NOT NULL DEFAULT 171," +
                    "    \"LikeServiceId\" integer NOT NULL DEFAULT 172," +
                    "    \"CommentServiceId\" integer NOT NULL DEFAULT 177," +
                    "    \"RepostServiceId\" integer NOT NULL DEFAULT 175," +
                    "    \"SavePostServiceId\" integer NOT NULL DEFAULT 176," +
                    "    \"PollIntervalMinutes\" integer NOT NULL DEFAULT 5," +
                    "    \"UpdatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')" +
                    ");",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"CommentServiceId\" integer NOT NULL DEFAULT 177;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"CommentCustomServiceId\" integer NOT NULL DEFAULT 178;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"RepostServiceId\" integer NOT NULL DEFAULT 175;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"SavePostServiceId\" integer NOT NULL DEFAULT 176;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"StoryViewServiceId\" integer NOT NULL DEFAULT 179;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"FetchIntervalSeconds\" integer NOT NULL DEFAULT 10;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"FetchBatchSize\" integer NOT NULL DEFAULT 100;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"StatusPushIntervalSeconds\" integer NOT NULL DEFAULT 60;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"StatusPushBatchSize\" integer NOT NULL DEFAULT 100;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"StatusPushMaxBatchesPerPass\" integer NOT NULL DEFAULT 5;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"CancelPullIntervalSeconds\" integer NOT NULL DEFAULT 60;",
                    "ALTER TABLE \"SmmProviderConfigs\" ADD COLUMN IF NOT EXISTS \"CancelPullBatchSize\" integer NOT NULL DEFAULT 100;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"Priority\" integer NOT NULL DEFAULT 0;",
                    "CREATE INDEX IF NOT EXISTS \"IX_AppOrders_Claimable_Priority\" ON \"AppOrders\" (\"Priority\" DESC, \"CreatedAt\" ASC) WHERE \"CompletedCount\" < \"Quantity\";",
                    "CREATE INDEX IF NOT EXISTS \"IX_AppOrders_Claimable_Engine\" ON \"AppOrders\" (\"OrderType\", \"Status\", \"Priority\" DESC, \"CreatedAt\" ASC) WHERE \"CompletedCount\" < \"Quantity\";",
                    // Auto-provisions the CoinTransfers table (user-to-user transfer audit trail).
                    "CREATE TABLE IF NOT EXISTS \"CoinTransfers\" (" +
                    "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_CoinTransfers\" PRIMARY KEY," +
                    "    \"SenderUserId\" uuid NOT NULL," +
                    "    \"SenderUsername\" character varying(128) NOT NULL," +
                    "    \"ReceiverUserId\" uuid NOT NULL," +
                    "    \"ReceiverUsername\" character varying(128) NOT NULL," +
                    "    \"Coins\" bigint NOT NULL," +
                    "    \"InitiatedBy\" character varying(32) NOT NULL DEFAULT 'user'," +
                    "    \"Note\" character varying(256)," +
                    "    \"CreatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')" +
                    ");",
                    "CREATE INDEX IF NOT EXISTS \"IX_CoinTransfers_SenderUserId\" ON \"CoinTransfers\" (\"SenderUserId\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_CoinTransfers_ReceiverUserId\" ON \"CoinTransfers\" (\"ReceiverUserId\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_CoinTransfers_CreatedAt\" ON \"CoinTransfers\" (\"CreatedAt\");",
                    // Auto-provisions the SubscriptionRequests table for older deployments.
                    "CREATE TABLE IF NOT EXISTS \"SubscriptionRequests\" (" +
                    "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_SubscriptionRequests\" PRIMARY KEY," +
                    "    \"UserId\" uuid NOT NULL," +
                    "    \"Plan\" integer NOT NULL DEFAULT 0," +
                    "    \"PriceInr\" integer NOT NULL DEFAULT 0," +
                    "    \"Upi\" character varying(128) NOT NULL DEFAULT ''," +
                    "    \"Utr\" character varying(64) NOT NULL DEFAULT ''," +
                    "    \"Status\" integer NOT NULL DEFAULT 0," +
                    "    \"AppId\" character varying(128) NOT NULL DEFAULT 'unknown'," +
                    "    \"DeviceId\" character varying(128) NOT NULL DEFAULT 'unknown'," +
                    "    \"AdminNote\" character varying(512)," +
                    "    \"CreatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')," +
                    "    \"ProcessedAt\" timestamp with time zone" +
                    ");",
                    "CREATE INDEX IF NOT EXISTS \"IX_SubscriptionRequests_UserId\" ON \"SubscriptionRequests\" (\"UserId\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_SubscriptionRequests_Status\" ON \"SubscriptionRequests\" (\"Status\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_SubscriptionRequests_AppId_DeviceId\" ON \"SubscriptionRequests\" (\"AppId\", \"DeviceId\");"
                };

                foreach (var sql in sqls)
                {
                    try { db.Database.ExecuteSqlRaw(sql); } catch { }
                }
            }
            else if (isSqlite)
            {
                var sqls = new[]
                {
                    "ALTER TABLE Devices ADD COLUMN ActiveAccount TEXT;",
                    "ALTER TABLE Devices ADD COLUMN LoggedInAccountsJson TEXT;",
                    "ALTER TABLE Devices ADD COLUMN AppId TEXT NOT NULL DEFAULT 'unknown';",
                    "ALTER TABLE Devices ADD COLUMN DeviceModel TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN IsExternal INTEGER NOT NULL DEFAULT 0;",
                    "ALTER TABLE AppOrders ADD COLUMN ExternalOrderId TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN StartCount INTEGER NOT NULL DEFAULT 0;",
                    "ALTER TABLE AppOrders ADD COLUMN ProviderServiceName TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN ProviderUsername TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN ProviderChargeAmount NUMERIC;",
                    "ALTER TABLE AppOrders ADD COLUMN ProviderChargeCurrency TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN ProviderCreatedAt TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN PanelLastPushedStatus TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN PanelLastPushedRemains INTEGER;",
                    "ALTER TABLE AppOrders ADD COLUMN PanelLastPushedStartCount INTEGER;",
                    "ALTER TABLE AppOrders ADD COLUMN PanelLastPushedReason TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN PanelLastPushedAt TEXT;",
                    "ALTER TABLE Accounts ADD COLUMN UpgradedAt TEXT;",
                    "ALTER TABLE AppOrders ADD COLUMN CommentText TEXT;",
                    "CREATE UNIQUE INDEX IF NOT EXISTS UX_AppOrders_ExternalOrderId ON AppOrders (ExternalOrderId) WHERE ExternalOrderId IS NOT NULL;",
                    // Auto-provisions the InstagramUserIdCaches table for SQLite
                    "CREATE TABLE IF NOT EXISTS InstagramUserIdCaches (" +
                    "    Id TEXT NOT NULL CONSTRAINT PK_InstagramUserIdCaches PRIMARY KEY," +
                    "    Username TEXT NOT NULL," +
                    "    UserId TEXT NOT NULL," +
                    "    CreatedAt TEXT NOT NULL" +
                    ");",
                    "CREATE UNIQUE INDEX IF NOT EXISTS IX_InstagramUserIdCaches_Username ON InstagramUserIdCaches (Username);",
                    // FeedPilot watched profiles: app-owned cards plus captured feed post ids.
                    "CREATE TABLE IF NOT EXISTS WatchedInstagramHandles (" +
                    "    Id TEXT NOT NULL CONSTRAINT PK_WatchedInstagramHandles PRIMARY KEY," +
                    "    UserId TEXT NOT NULL," +
                    "    AppId TEXT NOT NULL DEFAULT 'unknown'," +
                    "    DeviceId TEXT NOT NULL DEFAULT 'unknown'," +
                    "    Username TEXT NOT NULL," +
                    "    ProfilePictureUrl TEXT," +
                    "    FullName TEXT," +
                    "    IsPrivate INTEGER NOT NULL DEFAULT 0," +
                    "    FollowerCount INTEGER NOT NULL DEFAULT 0," +
                    "    FollowingCount INTEGER NOT NULL DEFAULT 0," +
                    "    MediaCount INTEGER NOT NULL DEFAULT 0," +
                    "    WatchEnabled INTEGER NOT NULL DEFAULT 1," +
                    "    PollIntervalMinutes INTEGER NOT NULL DEFAULT 60," +
                    "    LastFetchedAt TEXT," +
                    "    CreatedAt TEXT NOT NULL," +
                    "    UpdatedAt TEXT NOT NULL" +
                    ");",
                    "ALTER TABLE WatchedInstagramHandles ADD COLUMN AppId TEXT NOT NULL DEFAULT 'unknown';",
                    "ALTER TABLE WatchedInstagramHandles ADD COLUMN DeviceId TEXT NOT NULL DEFAULT 'unknown';",
                    "ALTER TABLE WatchedInstagramHandles ADD COLUMN FollowerCount INTEGER NOT NULL DEFAULT 0;",
                    "ALTER TABLE WatchedInstagramHandles ADD COLUMN FollowingCount INTEGER NOT NULL DEFAULT 0;",
                    "ALTER TABLE WatchedInstagramHandles ADD COLUMN MediaCount INTEGER NOT NULL DEFAULT 0;",
                    "DROP INDEX IF EXISTS UX_WatchedInstagramHandles_User_Username;",
                    "CREATE UNIQUE INDEX IF NOT EXISTS UX_WatchedInstagramHandles_User_App_Device_Username ON WatchedInstagramHandles (UserId, AppId, DeviceId, Username);",
                    "CREATE INDEX IF NOT EXISTS IX_WatchedInstagramHandles_App_Device ON WatchedInstagramHandles (AppId, DeviceId);",
                    "CREATE INDEX IF NOT EXISTS IX_WatchedInstagramHandles_Due ON WatchedInstagramHandles (WatchEnabled, LastFetchedAt);",
                    "CREATE TABLE IF NOT EXISTS WatchedInstagramPosts (" +
                    "    Id TEXT NOT NULL CONSTRAINT PK_WatchedInstagramPosts PRIMARY KEY," +
                    "    WatchedInstagramHandleId TEXT NOT NULL," +
                    "    PostId TEXT NOT NULL," +
                    "    Code TEXT," +
                    "    Caption TEXT," +
                    "    MediaUrl TEXT," +
                    "    Permalink TEXT," +
                    "    MediaType INTEGER NOT NULL DEFAULT 0," +
                    "    LikeCount INTEGER NOT NULL DEFAULT 0," +
                    "    CommentCount INTEGER NOT NULL DEFAULT 0," +
                    "    TakenAt TEXT," +
                    "    FetchedAt TEXT NOT NULL," +
                    "    CONSTRAINT FK_WatchedInstagramPosts_WatchedInstagramHandles_WatchedInstagramHandleId FOREIGN KEY (WatchedInstagramHandleId) REFERENCES WatchedInstagramHandles (Id) ON DELETE CASCADE" +
                    ");",
                    "CREATE UNIQUE INDEX IF NOT EXISTS UX_WatchedInstagramPosts_Handle_Post ON WatchedInstagramPosts (WatchedInstagramHandleId, PostId);",
                    "CREATE INDEX IF NOT EXISTS IX_WatchedInstagramPosts_TakenAt ON WatchedInstagramPosts (TakenAt);",
                    // Auto-provisions the RunnerSettings table (dashboard-editable runner pacing).
                    "CREATE TABLE IF NOT EXISTS RunnerSettings (" +
                    "    Id INTEGER NOT NULL CONSTRAINT PK_RunnerSettings PRIMARY KEY," +
                    "    ActionDelayMinMs INTEGER NOT NULL DEFAULT 1500," +
                    "    ActionDelayMaxMs INTEGER NOT NULL DEFAULT 4000," +
                    "    FetchDelayMs INTEGER NOT NULL DEFAULT 15000," +
                    "    StreakLength INTEGER NOT NULL DEFAULT 4," +
                    "    CooldownSeconds INTEGER NOT NULL DEFAULT 30," +
                    "    AutoPartialCancelledTasks INTEGER NOT NULL DEFAULT 1," +
                    "    CoinsPerInr INTEGER NOT NULL DEFAULT 5," +
                    "    MinWithdrawalInr INTEGER NOT NULL DEFAULT 100," +
                    "    ClaimBatchSize INTEGER NOT NULL DEFAULT 10," +
                    "    UpdatedAt TEXT NOT NULL" +
                    ");",
                    "ALTER TABLE RunnerSettings ADD COLUMN AutoPartialCancelledTasks INTEGER NOT NULL DEFAULT 1;",
                    "ALTER TABLE RunnerSettings ADD COLUMN CoinsPerInr INTEGER NOT NULL DEFAULT 5;",
                    "ALTER TABLE RunnerSettings ADD COLUMN MinWithdrawalInr INTEGER NOT NULL DEFAULT 100;",
                    "ALTER TABLE RunnerSettings ADD COLUMN ClaimBatchSize INTEGER NOT NULL DEFAULT 10;",
                    "ALTER TABLE RunnerSettings ADD COLUMN SupportContactUrl TEXT;",
                    "ALTER TABLE RunnerSettings ADD COLUMN TelegramChannelUrl TEXT;",
                    "ALTER TABLE RunnerSettings ADD COLUMN UpiEnabled INTEGER NOT NULL DEFAULT 1;",
                    "ALTER TABLE RunnerSettings ADD COLUMN BankEnabled INTEGER NOT NULL DEFAULT 1;",
                    "ALTER TABLE RunnerSettings ADD COLUMN UsdtBep20Enabled INTEGER NOT NULL DEFAULT 0;",
                    "ALTER TABLE RunnerSettings ADD COLUMN CoinsPerUsdt INTEGER NOT NULL DEFAULT 400;",
                    "ALTER TABLE RunnerSettings ADD COLUMN MinWithdrawalUsdt REAL NOT NULL DEFAULT 5;",
                    "ALTER TABLE RunnerSettings ADD COLUMN StreakMinLength INTEGER NOT NULL DEFAULT 3;",
                    "ALTER TABLE RunnerSettings ADD COLUMN StreakMaxLength INTEGER NOT NULL DEFAULT 5;",
                    "ALTER TABLE RunnerSettings ADD COLUMN FollowStreakCount INTEGER NOT NULL DEFAULT 5;",
                    "ALTER TABLE RunnerSettings ADD COLUMN LikeStreakCount INTEGER NOT NULL DEFAULT 5;",
                    "ALTER TABLE RunnerSettings ADD COLUMN CommentStreakCount INTEGER NOT NULL DEFAULT 3;",
                    "ALTER TABLE RunnerSettings ADD COLUMN RepostStreakCount INTEGER NOT NULL DEFAULT 3;",
                    "ALTER TABLE RunnerSettings ADD COLUMN SavePostStreakCount INTEGER NOT NULL DEFAULT 5;",
                    "ALTER TABLE RunnerSettings ADD COLUMN StoryViewStreakCount INTEGER NOT NULL DEFAULT 5;",
                    "ALTER TABLE RunnerSettings ADD COLUMN MaxFollowsPerDay INTEGER NOT NULL DEFAULT 200;",
                    "ALTER TABLE RunnerSettings ADD COLUMN MaxLikesPerDay INTEGER NOT NULL DEFAULT 200;",
                    "ALTER TABLE RunnerSettings ADD COLUMN MaxCommentsPerDay INTEGER NOT NULL DEFAULT 50;",
                    "ALTER TABLE RunnerSettings ADD COLUMN MaxRepostsPerDay INTEGER NOT NULL DEFAULT 50;",
                    "ALTER TABLE RunnerSettings ADD COLUMN MaxSavePostsPerDay INTEGER NOT NULL DEFAULT 50;",
                    "ALTER TABLE RunnerSettings ADD COLUMN MaxStoryViewsPerDay INTEGER NOT NULL DEFAULT 500;",
                    "ALTER TABLE RunnerSettings ADD COLUMN DailyLimitCooldownMinutes INTEGER NOT NULL DEFAULT 60;",
                    "ALTER TABLE Withdrawals ADD COLUMN UsdtAddress TEXT;",
                    "ALTER TABLE RunnerSettings ADD COLUMN PricePerFollow INTEGER NOT NULL DEFAULT 8;",
                    "ALTER TABLE RunnerSettings ADD COLUMN PricePerLike INTEGER NOT NULL DEFAULT 3;",
                    "ALTER TABLE RunnerSettings ADD COLUMN PricePerComment INTEGER NOT NULL DEFAULT 10;",
                    "ALTER TABLE RunnerSettings ADD COLUMN PricePerRepost INTEGER NOT NULL DEFAULT 12;",
                    "ALTER TABLE RunnerSettings ADD COLUMN PricePerSavePost INTEGER NOT NULL DEFAULT 6;",
                    "ALTER TABLE RunnerSettings ADD COLUMN PricePerStoryView INTEGER NOT NULL DEFAULT 4;",
                    // Auto-provisions the SmmProviderConfigs table (dashboard-editable SMM panel integration).
                    "CREATE TABLE IF NOT EXISTS SmmProviderConfigs (" +
                    "    Id INTEGER NOT NULL CONSTRAINT PK_SmmProviderConfigs PRIMARY KEY," +
                    "    BaseUrl TEXT NOT NULL DEFAULT ''," +
                    "    ApiKey TEXT NOT NULL DEFAULT ''," +
                    "    FollowServiceId INTEGER NOT NULL DEFAULT 171," +
                    "    LikeServiceId INTEGER NOT NULL DEFAULT 172," +
                    "    CommentServiceId INTEGER NOT NULL DEFAULT 177," +
                    "    RepostServiceId INTEGER NOT NULL DEFAULT 175," +
                    "    SavePostServiceId INTEGER NOT NULL DEFAULT 176," +
                    "    PollIntervalMinutes INTEGER NOT NULL DEFAULT 5," +
                    "    UpdatedAt TEXT NOT NULL" +
                    ");",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN CommentServiceId INTEGER NOT NULL DEFAULT 177;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN CommentCustomServiceId INTEGER NOT NULL DEFAULT 178;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN RepostServiceId INTEGER NOT NULL DEFAULT 175;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN SavePostServiceId INTEGER NOT NULL DEFAULT 176;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN StoryViewServiceId INTEGER NOT NULL DEFAULT 179;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN FetchIntervalSeconds INTEGER NOT NULL DEFAULT 10;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN FetchBatchSize INTEGER NOT NULL DEFAULT 100;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN StatusPushIntervalSeconds INTEGER NOT NULL DEFAULT 60;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN StatusPushBatchSize INTEGER NOT NULL DEFAULT 100;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN StatusPushMaxBatchesPerPass INTEGER NOT NULL DEFAULT 5;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN CancelPullIntervalSeconds INTEGER NOT NULL DEFAULT 60;",
                    "ALTER TABLE SmmProviderConfigs ADD COLUMN CancelPullBatchSize INTEGER NOT NULL DEFAULT 100;",
                    "ALTER TABLE AppOrders ADD COLUMN Priority INTEGER NOT NULL DEFAULT 0;",
                    "ALTER TABLE AppOrders ADD COLUMN RefundIssued INTEGER NOT NULL DEFAULT 0;",
                    // Auto-provisions the CoinTransfers table (user-to-user transfer audit trail).
                    "CREATE TABLE IF NOT EXISTS CoinTransfers (" +
                    "    Id TEXT NOT NULL CONSTRAINT PK_CoinTransfers PRIMARY KEY," +
                    "    SenderUserId TEXT NOT NULL," +
                    "    SenderUsername TEXT NOT NULL," +
                    "    ReceiverUserId TEXT NOT NULL," +
                    "    ReceiverUsername TEXT NOT NULL," +
                    "    Coins INTEGER NOT NULL," +
                    "    InitiatedBy TEXT NOT NULL DEFAULT 'user'," +
                    "    Note TEXT," +
                    "    CreatedAt TEXT NOT NULL" +
                    ");",
                    "CREATE INDEX IF NOT EXISTS IX_CoinTransfers_SenderUserId ON CoinTransfers (SenderUserId);",
                    "CREATE INDEX IF NOT EXISTS IX_CoinTransfers_ReceiverUserId ON CoinTransfers (ReceiverUserId);",
                    "CREATE INDEX IF NOT EXISTS IX_CoinTransfers_CreatedAt ON CoinTransfers (CreatedAt);",
                    // Auto-provisions the SubscriptionRequests table for SQLite.
                    "CREATE TABLE IF NOT EXISTS SubscriptionRequests (" +
                    "    Id TEXT NOT NULL CONSTRAINT PK_SubscriptionRequests PRIMARY KEY," +
                    "    UserId TEXT NOT NULL," +
                    "    Plan INTEGER NOT NULL DEFAULT 0," +
                    "    PriceInr INTEGER NOT NULL DEFAULT 0," +
                    "    Upi TEXT NOT NULL DEFAULT ''," +
                    "    Utr TEXT NOT NULL DEFAULT ''," +
                    "    Status INTEGER NOT NULL DEFAULT 0," +
                    "    AppId TEXT NOT NULL DEFAULT 'unknown'," +
                    "    DeviceId TEXT NOT NULL DEFAULT 'unknown'," +
                    "    AdminNote TEXT," +
                    "    CreatedAt TEXT NOT NULL," +
                    "    ProcessedAt TEXT" +
                    ");",
                    "CREATE INDEX IF NOT EXISTS IX_SubscriptionRequests_UserId ON SubscriptionRequests (UserId);",
                    "CREATE INDEX IF NOT EXISTS IX_SubscriptionRequests_Status ON SubscriptionRequests (Status);",
                    "CREATE INDEX IF NOT EXISTS IX_SubscriptionRequests_AppId_DeviceId ON SubscriptionRequests (AppId, DeviceId);",
                    "CREATE TABLE IF NOT EXISTS PickedUsernames (" +
                    "    Id TEXT NOT NULL CONSTRAINT PK_PickedUsernames PRIMARY KEY," +
                    "    Username TEXT NOT NULL," +
                    "    UserId TEXT," +
                    "    DeviceId TEXT NOT NULL DEFAULT ''," +
                    "    PickedAt TEXT NOT NULL" +
                    ");",
                    "CREATE INDEX IF NOT EXISTS IX_PickedUsernames_Username ON PickedUsernames (Username);",
                    "CREATE INDEX IF NOT EXISTS IX_PickedUsernames_Username_DeviceId ON PickedUsernames (Username, DeviceId);",
                    "ALTER TABLE PickedUsernames ADD COLUMN AppId TEXT NOT NULL DEFAULT 'unknown';",
                    "CREATE INDEX IF NOT EXISTS IX_PickedUsernames_DeviceId_AppId ON PickedUsernames (DeviceId, AppId);"
                };

                foreach (var sql in sqls)
                {
                    try { db.Database.ExecuteSqlRaw(sql); } catch { }
                }
            }
        }
        catch { }
    }
}
