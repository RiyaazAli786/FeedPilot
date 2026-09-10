using Microsoft.EntityFrameworkCore;
using FeedPilot.Api.Domain;

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
                    "CREATE UNIQUE INDEX IF NOT EXISTS \"UX_WalletTransactions_WalletId_Reference\" ON \"WalletTransactions\" (\"WalletId\", \"Reference\") WHERE \"Reference\" IS NOT NULL;",
                    "DROP INDEX IF EXISTS \"IX_Devices_InstallationId\";",
                    "CREATE INDEX IF NOT EXISTS \"IX_Devices_InstallationId\" ON \"Devices\" (\"InstallationId\");",
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
                    "ALTER TABLE \"RunnerSettings\" ADD COLUMN IF NOT EXISTS \"ClaimTimeoutMinutes\" integer NOT NULL DEFAULT 10;",
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
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ReservedCount\" integer NOT NULL DEFAULT 0;",
                    "ALTER TABLE \"AppOrders\" ADD COLUMN IF NOT EXISTS \"ReservedAt\" timestamp with time zone;",
                    // Claim eligibility moved from `CompletedCount < Quantity` to
                    // `CompletedCount + ReservedCount < Quantity` (see AppOrder.ReservedCount) — the
                    // old partial indexes' predicate no longer matches that WHERE clause, so Postgres
                    // would stop using them for the claim query. Drop + recreate with the new
                    // predicate rather than leaving a stale index the planner can't use.
                    "DROP INDEX IF EXISTS \"IX_AppOrders_Claimable_Priority\";",
                    "DROP INDEX IF EXISTS \"IX_AppOrders_Claimable_Engine\";",
                    "CREATE INDEX IF NOT EXISTS \"IX_AppOrders_Claimable_Priority\" ON \"AppOrders\" (\"Priority\" DESC, \"CreatedAt\" ASC) WHERE \"CompletedCount\" + \"ReservedCount\" < \"Quantity\";",
                    "CREATE INDEX IF NOT EXISTS \"IX_AppOrders_Claimable_Engine\" ON \"AppOrders\" (\"OrderType\", \"Status\", \"Priority\" DESC, \"CreatedAt\" ASC) WHERE \"CompletedCount\" + \"ReservedCount\" < \"Quantity\";",
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
                    "CREATE INDEX IF NOT EXISTS \"IX_SubscriptionRequests_AppId_DeviceId\" ON \"SubscriptionRequests\" (\"AppId\", \"DeviceId\");",
                    // Auto-provisions the AppInstallations table (cryptographic per-install identity).
                    "CREATE TABLE IF NOT EXISTS \"AppInstallations\" (" +
                    "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_AppInstallations\" PRIMARY KEY," +
                    "    \"InstallationId\" character varying(128) NOT NULL," +
                    "    \"PublicKey\" character varying(512) NOT NULL DEFAULT ''," +
                    "    \"UserId\" uuid NULL," +
                    "    \"Status\" integer NOT NULL DEFAULT 1," +
                    "    \"CreatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')," +
                    "    \"LastSeenAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')," +
                    "    \"RevokedAt\" timestamp with time zone" +
                    ");",
                    "CREATE UNIQUE INDEX IF NOT EXISTS \"UX_AppInstallations_InstallationId\" ON \"AppInstallations\" (\"InstallationId\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_AppInstallations_UserId\" ON \"AppInstallations\" (\"UserId\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_AppInstallations_Status\" ON \"AppInstallations\" (\"Status\");",
                    // Auto-provisions the InstallationChallenges table (single-use signature challenges).
                    "CREATE TABLE IF NOT EXISTS \"InstallationChallenges\" (" +
                    "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_InstallationChallenges\" PRIMARY KEY," +
                    "    \"InstallationId\" character varying(128) NOT NULL," +
                    "    \"Challenge\" character varying(128) NOT NULL," +
                    "    \"CreatedAt\" timestamp with time zone NOT NULL DEFAULT (now() at time zone 'utc')," +
                    "    \"ExpiresAt\" timestamp with time zone NOT NULL," +
                    "    \"UsedAt\" timestamp with time zone" +
                    ");",
                    "CREATE INDEX IF NOT EXISTS \"IX_InstallationChallenges_InstallationId\" ON \"InstallationChallenges\" (\"InstallationId\");",
                    "CREATE INDEX IF NOT EXISTS \"IX_InstallationChallenges_ExpiresAt\" ON \"InstallationChallenges\" (\"ExpiresAt\");"
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
                    "CREATE UNIQUE INDEX IF NOT EXISTS UX_WalletTransactions_WalletId_Reference ON WalletTransactions (WalletId, Reference) WHERE Reference IS NOT NULL;",
                    "DROP INDEX IF EXISTS IX_Devices_InstallationId;",
                    "CREATE INDEX IF NOT EXISTS IX_Devices_InstallationId ON Devices (InstallationId);",
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
                    "ALTER TABLE RunnerSettings ADD COLUMN ClaimTimeoutMinutes INTEGER NOT NULL DEFAULT 10;",
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
                    "ALTER TABLE AppOrders ADD COLUMN ReservedCount INTEGER NOT NULL DEFAULT 0;",
                    "ALTER TABLE AppOrders ADD COLUMN ReservedAt TEXT;",
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
                    "CREATE INDEX IF NOT EXISTS IX_PickedUsernames_DeviceId_AppId ON PickedUsernames (DeviceId, AppId);",
                    // Auto-provisions the AppInstallations table (cryptographic per-install identity).
                    "CREATE TABLE IF NOT EXISTS AppInstallations (" +
                    "    Id TEXT NOT NULL CONSTRAINT PK_AppInstallations PRIMARY KEY," +
                    "    InstallationId TEXT NOT NULL," +
                    "    PublicKey TEXT NOT NULL DEFAULT ''," +
                    "    UserId TEXT," +
                    "    Status INTEGER NOT NULL DEFAULT 1," +
                    "    CreatedAt TEXT NOT NULL," +
                    "    LastSeenAt TEXT NOT NULL," +
                    "    RevokedAt TEXT" +
                    ");",
                    "CREATE UNIQUE INDEX IF NOT EXISTS UX_AppInstallations_InstallationId ON AppInstallations (InstallationId);",
                    "CREATE INDEX IF NOT EXISTS IX_AppInstallations_UserId ON AppInstallations (UserId);",
                    "CREATE INDEX IF NOT EXISTS IX_AppInstallations_Status ON AppInstallations (Status);",
                    // Auto-provisions the InstallationChallenges table (single-use signature challenges).
                    "CREATE TABLE IF NOT EXISTS InstallationChallenges (" +
                    "    Id TEXT NOT NULL CONSTRAINT PK_InstallationChallenges PRIMARY KEY," +
                    "    InstallationId TEXT NOT NULL," +
                    "    Challenge TEXT NOT NULL," +
                    "    CreatedAt TEXT NOT NULL," +
                    "    ExpiresAt TEXT NOT NULL," +
                    "    UsedAt TEXT" +
                    ");",
                    "CREATE INDEX IF NOT EXISTS IX_InstallationChallenges_InstallationId ON InstallationChallenges (InstallationId);",
                    "CREATE INDEX IF NOT EXISTS IX_InstallationChallenges_ExpiresAt ON InstallationChallenges (ExpiresAt);"
                };

                foreach (var sql in sqls)
                {
                    try { db.Database.ExecuteSqlRaw(sql); } catch { }
                }
            }

            try { EnsureFeedBridgeSchema(db, isPostgres, isSqlite); } catch { }
            try { BackfillAppInstallationsFromDevices(db); } catch { }
        }
        catch { }
    }

    private static void EnsureFeedBridgeSchema(AppDbContext db, bool isPostgres, bool isSqlite)
    {
        if (isPostgres)
        {
            var sqls = new[]
            {
                "CREATE TABLE IF NOT EXISTS \"FeedBridgeDeviceStates\" (" +
                "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_FeedBridgeDeviceStates\" PRIMARY KEY," +
                "    \"UserId\" uuid NULL," +
                "    \"AppId\" character varying(128) NOT NULL DEFAULT 'unknown'," +
                "    \"DeviceId\" character varying(128) NOT NULL," +
                "    \"InstallationId\" character varying(128) NOT NULL," +
                "    \"AppInstanceId\" text," +
                "    \"DeviceModel\" text," +
                "    \"AndroidVersion\" text," +
                "    \"AppVersion\" text," +
                "    \"LastSeenAt\" timestamp with time zone NOT NULL," +
                "    \"BridgeEnabled\" boolean NOT NULL DEFAULT true," +
                "    \"BatteryLevel\" integer," +
                "    \"NetworkType\" character varying(64)," +
                "    \"ActiveAccountUsername\" character varying(128)," +
                "    \"LoggedInAccountsJson\" text," +
                "    \"CapabilitiesJson\" text," +
                "    \"FailureCount\" integer NOT NULL DEFAULT 0," +
                "    \"CooldownUntil\" timestamp with time zone," +
                "    \"CreatedAt\" timestamp with time zone NOT NULL," +
                "    \"UpdatedAt\" timestamp with time zone NOT NULL" +
                ");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeDeviceStates_AppId_DeviceId\" ON \"FeedBridgeDeviceStates\" (\"AppId\", \"DeviceId\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeDeviceStates_InstallationId\" ON \"FeedBridgeDeviceStates\" (\"InstallationId\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeDeviceStates_LastSeenAt\" ON \"FeedBridgeDeviceStates\" (\"LastSeenAt\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeDeviceStates_BridgeEnabled_LastSeenAt\" ON \"FeedBridgeDeviceStates\" (\"BridgeEnabled\", \"LastSeenAt\");",

                "CREATE TABLE IF NOT EXISTS \"FeedBridgeAccountStates\" (" +
                "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_FeedBridgeAccountStates\" PRIMARY KEY," +
                "    \"AccountId\" uuid NULL," +
                "    \"UserId\" uuid NULL," +
                "    \"AppId\" character varying(128) NOT NULL DEFAULT 'unknown'," +
                "    \"DeviceId\" character varying(128) NOT NULL," +
                "    \"InstallationId\" character varying(128) NOT NULL," +
                "    \"InstagramUsername\" character varying(128) NOT NULL," +
                "    \"InstagramUserId\" character varying(64)," +
                "    \"Status\" integer NOT NULL DEFAULT 0," +
                "    \"LastValidatedAt\" timestamp with time zone," +
                "    \"LastUsedAt\" timestamp with time zone," +
                "    \"IsBusy\" boolean NOT NULL DEFAULT false," +
                "    \"FailureCount\" integer NOT NULL DEFAULT 0," +
                "    \"CooldownUntil\" timestamp with time zone," +
                "    \"LastErrorCode\" character varying(64)," +
                "    \"LastErrorMessage\" character varying(512)," +
                "    \"CreatedAt\" timestamp with time zone NOT NULL," +
                "    \"UpdatedAt\" timestamp with time zone NOT NULL" +
                ");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeAccountStates_Status_IsBusy_CooldownUntil\" ON \"FeedBridgeAccountStates\" (\"Status\", \"IsBusy\", \"CooldownUntil\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeAccountStates_AppId_DeviceId\" ON \"FeedBridgeAccountStates\" (\"AppId\", \"DeviceId\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeAccountStates_AccountId\" ON \"FeedBridgeAccountStates\" (\"AccountId\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeAccountStates_InstagramUsername\" ON \"FeedBridgeAccountStates\" (\"InstagramUsername\");",

                "CREATE TABLE IF NOT EXISTS \"FeedBridgeJobs\" (" +
                "    \"Id\" uuid NOT NULL CONSTRAINT \"PK_FeedBridgeJobs\" PRIMARY KEY," +
                "    \"ExternalRequestId\" character varying(128)," +
                "    \"ClientName\" character varying(64) NOT NULL DEFAULT 'GetIGFeed'," +
                "    \"TargetUsername\" character varying(128)," +
                "    \"TargetUserId\" character varying(64)," +
                "    \"Limit\" integer NOT NULL DEFAULT 12," +
                "    \"MaxId\" text," +
                "    \"IncludeStories\" boolean NOT NULL DEFAULT false," +
                "    \"Status\" integer NOT NULL DEFAULT 0," +
                "    \"AssignedAccountId\" uuid NULL," +
                "    \"AssignedAccountStateId\" uuid NULL," +
                "    \"AssignedDeviceId\" character varying(128)," +
                "    \"AssignedInstallationId\" character varying(128)," +
                "    \"RequestJson\" text," +
                "    \"ResponseJson\" text," +
                "    \"ErrorCode\" character varying(64)," +
                "    \"ErrorMessage\" character varying(512)," +
                "    \"Attempt\" integer NOT NULL DEFAULT 1," +
                "    \"CreatedAt\" timestamp with time zone NOT NULL," +
                "    \"AssignedAt\" timestamp with time zone," +
                "    \"ExpiresAt\" timestamp with time zone NOT NULL," +
                "    \"CompletedAt\" timestamp with time zone," +
                "    \"UpdatedAt\" timestamp with time zone NOT NULL" +
                ");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeJobs_Status_ExpiresAt\" ON \"FeedBridgeJobs\" (\"Status\", \"ExpiresAt\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeJobs_AssignedDeviceId_Status\" ON \"FeedBridgeJobs\" (\"AssignedDeviceId\", \"Status\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeJobs_ExternalRequestId\" ON \"FeedBridgeJobs\" (\"ExternalRequestId\");",
                "CREATE INDEX IF NOT EXISTS \"IX_FeedBridgeJobs_CreatedAt\" ON \"FeedBridgeJobs\" (\"CreatedAt\");"
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
                "CREATE TABLE IF NOT EXISTS FeedBridgeDeviceStates (Id TEXT NOT NULL CONSTRAINT PK_FeedBridgeDeviceStates PRIMARY KEY, UserId TEXT, AppId TEXT NOT NULL DEFAULT 'unknown', DeviceId TEXT NOT NULL, InstallationId TEXT NOT NULL, AppInstanceId TEXT, DeviceModel TEXT, AndroidVersion TEXT, AppVersion TEXT, LastSeenAt TEXT NOT NULL, BridgeEnabled INTEGER NOT NULL DEFAULT 1, BatteryLevel INTEGER, NetworkType TEXT, ActiveAccountUsername TEXT, LoggedInAccountsJson TEXT, CapabilitiesJson TEXT, FailureCount INTEGER NOT NULL DEFAULT 0, CooldownUntil TEXT, CreatedAt TEXT NOT NULL, UpdatedAt TEXT NOT NULL);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeDeviceStates_AppId_DeviceId ON FeedBridgeDeviceStates (AppId, DeviceId);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeDeviceStates_InstallationId ON FeedBridgeDeviceStates (InstallationId);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeDeviceStates_LastSeenAt ON FeedBridgeDeviceStates (LastSeenAt);",
                "CREATE TABLE IF NOT EXISTS FeedBridgeAccountStates (Id TEXT NOT NULL CONSTRAINT PK_FeedBridgeAccountStates PRIMARY KEY, AccountId TEXT, UserId TEXT, AppId TEXT NOT NULL DEFAULT 'unknown', DeviceId TEXT NOT NULL, InstallationId TEXT NOT NULL, InstagramUsername TEXT NOT NULL, InstagramUserId TEXT, Status INTEGER NOT NULL DEFAULT 0, LastValidatedAt TEXT, LastUsedAt TEXT, IsBusy INTEGER NOT NULL DEFAULT 0, FailureCount INTEGER NOT NULL DEFAULT 0, CooldownUntil TEXT, LastErrorCode TEXT, LastErrorMessage TEXT, CreatedAt TEXT NOT NULL, UpdatedAt TEXT NOT NULL);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeAccountStates_Status_IsBusy_CooldownUntil ON FeedBridgeAccountStates (Status, IsBusy, CooldownUntil);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeAccountStates_AppId_DeviceId ON FeedBridgeAccountStates (AppId, DeviceId);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeAccountStates_AccountId ON FeedBridgeAccountStates (AccountId);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeAccountStates_InstagramUsername ON FeedBridgeAccountStates (InstagramUsername);",
                "CREATE TABLE IF NOT EXISTS FeedBridgeJobs (Id TEXT NOT NULL CONSTRAINT PK_FeedBridgeJobs PRIMARY KEY, ExternalRequestId TEXT, ClientName TEXT NOT NULL DEFAULT 'GetIGFeed', TargetUsername TEXT, TargetUserId TEXT, Limit INTEGER NOT NULL DEFAULT 12, MaxId TEXT, IncludeStories INTEGER NOT NULL DEFAULT 0, Status INTEGER NOT NULL DEFAULT 0, AssignedAccountId TEXT, AssignedAccountStateId TEXT, AssignedDeviceId TEXT, AssignedInstallationId TEXT, RequestJson TEXT, ResponseJson TEXT, ErrorCode TEXT, ErrorMessage TEXT, Attempt INTEGER NOT NULL DEFAULT 1, CreatedAt TEXT NOT NULL, AssignedAt TEXT, ExpiresAt TEXT NOT NULL, CompletedAt TEXT, UpdatedAt TEXT NOT NULL);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeJobs_Status_ExpiresAt ON FeedBridgeJobs (Status, ExpiresAt);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeJobs_AssignedDeviceId_Status ON FeedBridgeJobs (AssignedDeviceId, Status);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeJobs_ExternalRequestId ON FeedBridgeJobs (ExternalRequestId);",
                "CREATE INDEX IF NOT EXISTS IX_FeedBridgeJobs_CreatedAt ON FeedBridgeJobs (CreatedAt);"
            };

            foreach (var sql in sqls)
            {
                try { db.Database.ExecuteSqlRaw(sql); } catch { }
            }
        }
    }

    /// <summary>
    /// One-time, idempotent seed of AppInstallations from the existing (untrusted) Devices rows,
    /// keyed by InstallationId. PublicKey stays empty for these — they were never cryptographically
    /// registered; Status stays Active so nothing here is treated as revoked by default. Devices can
    /// have multiple rows sharing one InstallationId (see DevicesController's dedup/merge logic); the
    /// first one encountered wins, which is fine since this is just a seed — real ownership is
    /// corrected the moment that install calls POST api/installations/register for real.
    /// Done in C# rather than raw SQL to avoid depending on a Postgres UUID-generation extension
    /// (e.g. gen_random_uuid()/pgcrypto) that may not be enabled on every deployment.
    /// </summary>
    private static void BackfillAppInstallationsFromDevices(AppDbContext db)
    {
        var existingIds = new HashSet<string>(db.AppInstallations.Select(x => x.InstallationId));
        var candidates = db.Devices
            .Where(d => d.InstallationId != null && d.InstallationId != "")
            .Select(d => new { d.InstallationId, d.UserId, d.RegisteredAt, d.LastSeenAt })
            .ToList();

        var toAdd = new List<AppInstallation>();
        foreach (var d in candidates)
        {
            if (!existingIds.Add(d.InstallationId)) continue;
            toAdd.Add(new AppInstallation
            {
                InstallationId = d.InstallationId,
                PublicKey = string.Empty,
                UserId = d.UserId,
                Status = InstallationStatus.Active,
                CreatedAt = d.RegisteredAt,
                LastSeenAt = d.LastSeenAt
            });
        }

        if (toAdd.Count > 0)
        {
            db.AppInstallations.AddRange(toAdd);
            db.SaveChanges();
        }
    }
}
