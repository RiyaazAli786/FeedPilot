# FeedPilot — Complete Workspace & Architecture Context

> Living reference for the entire FeedPilot ecosystem. Rebuilt and validated from active source code on 2026-10-24 (Baseline commit `4836680`).  
> Companion references:
> - [PROJECT_CONTEXT.md](file:///c:/CoreProjects/FeedPilot/PROJECT_CONTEXT.md) (source inspection & operational boundaries)
> - [README.md](file:///c:/CoreProjects/FeedPilot/README.md) (repository overview & build commands)
> - [docs/ARCHITECTURE.md](file:///c:/CoreProjects/FeedPilot/docs/ARCHITECTURE.md) (subsystem architecture)
> - [docs/API.md](file:///c:/CoreProjects/FeedPilot/docs/API.md) (core endpoint inventory)
> - [docs/BUILD.md](file:///c:/CoreProjects/FeedPilot/docs/BUILD.md) (compilation instructions)
> - [docs/DEPLOYMENT.md](file:///c:/CoreProjects/FeedPilot/docs/DEPLOYMENT.md) (VPS deployment runbook)
> - [docs/INSTAGRAM_DEVICE_BRIDGE_ARCHITECTURE.md](file:///c:/CoreProjects/FeedPilot/docs/INSTAGRAM_DEVICE_BRIDGE_ARCHITECTURE.md) (feed bridge deep-dive)
> - [Implementation.md](file:///c:/CoreProjects/FeedPilot/Implementation.md) (Android Social Engagement Client specification)

---

## Executive Summary

**FeedPilot** is a distributed, full-stack Instagram automation, feed monitoring, and account execution platform. It coordinates a fleet of physical Android devices acting as residential execution nodes to bypass anti-bot challenges and proxy blacklists, serving clean Instagram profile feeds and executing social engagement tasks.

### Core Ecosystem Capabilities
1. **ASP.NET Core 10 Web API Backend** (`backend/src/FeedPilot.Api`):
   - Multi-tenant auth, account session vaults, wallet/coin ledger, app orders, referrals, and subscriptions.
   - Dynamic schema migrator (`DatabaseSchemaMigrator.cs`) supporting PostgreSQL and SQLite.
   - SignalR real-time coin sync hub at `/hubs/coin-sync`.
   - Asynchronous bounded Telegram logging channel (`TelegramRequestLogger`).
   - Self-hosted APK & upgrade asset storage fallback serving `.apk` files directly from `/apk` path.
2. **Native Android Application** (`android-client`):
   - Built with Kotlin 2.0.21, Jetpack Compose, Material 3, Hilt, Room (schema v21), Retrofit, OkHttp 4.12, and WorkManager.
   - Multi-account management with isolated encrypted cookie jars (`InstagramWebClient`, `AccountSessionGate`).
   - Clone isolation via runtime package naming (`DeviceIdentity.appId`).
   - Background engagement worker (`TaskRunnerService`) executing follow, like, comment, repost, save, and story actions with randomized jitter and human-like streak controls.
   - Residential Feed Bridge worker (`FeedBridgeRunner`, `FeedBridgeWorker`, `FeedBridgeService`) fulfilling external live feed queries on device.
   - Exception Users manager (`ExceptionUsersPanel`, `ExceptionUsersViewModel`, `ExceptionUsersApi`).
3. **Instagram Device Feed Bridge**:
   - Residential gateway serving upstream consumers (such as **GetIGFeed**) via `POST /api/bridge/feed` with `X-Bridge-Key`.
   - Real-time device selection algorithm with 30s request budget, 10s per-device attempt, device failover, and 40s job expiry.
   - Device status tracking, local TSV telemetry logging (`bridge-history.tsv`), and foreground alerts (`BridgeJobNotifier`).
   - **Recent Bridge Fixes & Enhancements**:
     - *Heartbeat Account Mapping*: Fixed `FeedBridgeRunner.kt` so all accounts with valid session cookies report `status = "Active"` and `isLoggedIn = true` to the backend, regardless of which account is marked active in Room.
     - *Nullable Poll Deserialization*: Changed `ApiService.pollBridgeJob` return type to `Response<BridgeJobDto?>` to handle server HTTP 200 with JSON `null` without throwing `SerializationException`.
     - *Decoupled Heartbeat & Token Initialization*: Injected `AuthRepository` to auto-ensure device session and decoupled 5s periodic heartbeats from poll requests, removing error loops.
     - *Power & Connectivity Locks*: Added `PARTIAL_WAKE_LOCK` and high-performance `WifiLock` in `FeedBridgeService.kt` to ensure uninterrupted polling when screen is locked or in Android Doze mode.
     - *Backend Stale State Recovery*: In `FeedBridgeService.cs`, active heartbeats immediately clear stale `CooldownUntil` and `IsBusy` flags.
     - *Release APK*: Built and signed release artifact at `android-client/app/build/outputs/apk/release/app-release.apk`.
4. **In-Browser Operations Suite** (`backend/src/FeedPilot.Api/wwwroot/dashboard`):
   - Cryptographic HMAC-SHA256 challenge-response authentication.
   - 60-second idle auto-lock screen.
   - App release center, database backup/restore, user wallet credit management, device telemetry, and picked username management.
5. **Multi-Target Deployment Infrastructure**:
   - **Primary Production VPS**: Hosted at `https://bridge.reelsflow.io` (`200.234.41.82`) using Docker host networking, Nginx reverse proxy, Let's Encrypt TLS, local PostgreSQL `bridgefeed` database, and persistent directory mounts (`/app/wwwroot/apk`, `/app/wwwroot/upgrade`, `/app/wwwroot/comments`, `/app/wwwroot/generator`). One-click deployment via `deploy-bridgefeed.bat` and `deploy/`.

6. **Auxiliary Clients**:
   - SwiftUI iOS starter (`ios-client`) for device auth and watched handle tracking.
   - Manifest V3 Chrome Extensions (`chrome-extension/`) for cookie synchronization and live Instagram session inspection.

---

## 1. System Architecture Topology

```text
                               ┌────────────────────────────────────────────────────────┐
                               │                   Upstream Consumer                    │
                               │             (e.g., GetIGFeed Microservice)             │
                               └───────────────────────────┬────────────────────────────┘
                                                           │
                                                           │ POST /api/bridge/feed
                                                           │ Header: X-Bridge-Key
                                                           ▼
┌───────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ FeedPilot ASP.NET Core 10 Web API (https://bridge.reelsflow.io / :5081)                                              │
│                                                                                                                       │
│   ┌───────────────────────────┐  ┌───────────────────────────┐  ┌───────────────────────────┐  ┌───────────────────┐  │
│   │ BridgeFeedController      │  │ OrderProcessingController │  │ AuthController            │  │ Admin Suite       │  │
│   │ & BridgeJobsController    │  │ & TasksController         │  │ & AccountsController      │  │ Dashboard, Backup,│  │
│   │ & PickedUsernameController│  │ & Comments/Generator      │  │ & Installation/Device     │  │ App Releases, Logs │  │
│   └─────────────┬─────────────┘  └─────────────┬─────────────┘  └─────────────┬─────────────┘  └─────────┬─────────┘  │
│                 │                              │                              │                          │            │
│   ┌─────────────▼─────────────┐  ┌─────────────▼─────────────┐  ┌─────────────▼─────────────┐  ┌─────────▼─────────┐  │
│   │ FeedBridgeService         │  │ SmmPanelSyncService       │  │ TokenService              │  │ DatabaseSchema-   │  │
│   │ & JobSweepService (40s)   │  │ & StaleClaimSweepService  │  │ & WalletService           │  │ Migrator          │  │
│   └─────────────┬─────────────┘  └─────────────┬─────────────┘  └─────────────┬─────────────┘  └─────────┬─────────┘  │
│                 │                              │                              │                          │            │
│                 │                              │                              ▼                          │            │
│                 │                              │                 ┌──────────────────────────┐            │            │
│                 │                              │                 │ TelegramRequestLogger    │            │            │
│                 │                              │                 │ (Channel<string> 500 cap)├────────┐   │            │
│                 │                              │                 └──────────────────────────┘        │   │            │
└─────────────────┼──────────────────────────────┼──────────────────────────────┼──────────────────────┼───┼────────────┘
                  │                              │                              │                      │   │
                  │ Polling / Heartbeats         │ Claims / Progress Batches    │ Device Registration  │   │ EF Core
                  │ GET /api/bridge/jobs/poll    │ POST /api/orders/.../progress│ JWT / Signing        │   │
                  ▼                              ▼                              ▼                      │   ▼
┌──────────────────────────────────────────────────────────────────────────────┐                       │ ┌──────────────┐
│ Android Residential Device Fleet (com.feedpilot.client)                      │                       │ │ Persistence  │
│                                                                              │                       │ │ Layer        │
│  - FeedBridgeRunner / FeedBridgeWorker                                       │                       │ │              │
│    * Executes residential Instagram queries via local cookie jar             │                       │ │ - PostgreSQL │
│    * BridgeModeBadge, BridgeJobNotifier, ExceptionUsersPanel                 │                       │ │   bridgefeed │
│    * Local TSV event log: bridge-history.tsv (1,000 entries)                 │                       │ │ - Local Disk │
│  - TaskRunnerService (Foreground engagement worker)                          │                       │ │   /apk/ &    │
│    * Follow, Like, Comment, Repost, Story View, Save                         │                       │ │   /storage/  │
│    * Human-like randomized jitter delays (ActionDelayMinMs to MaxMs)         │                       │ │ - SignalR    │
│    * Pending earnings tracking & settlement reconciliation                   │                       │ │   WebSocket  │
│  - FeedWatcherWorker (Watches target profiles & captures post IDs)           │                       │ └──────────────┘
│  - Room Local SQLite (DB v21: encrypted cookies, accounts, logs, tasks)      │                       ▼
└──────────────────────────────────────────────────────────────────────────────┘              ┌─────────────────┐
                                                                                              │ Telegram Group  │
                                                                                              │ Alerts & Logs   │
                                                                                              └─────────────────┘
```

---

## 2. Directory Layout & Subsystem Responsibilities

```text
FeedPilot/
├── README.md                           # Repository summary, key features & build commands
├── PROJECT_CONTEXT.md                  # Operational boundaries, security snapshot & verification notes
├── context.md                          # Exhaustive workspace architecture and ecosystem context
├── Implementation.md                   # Formal specification for Android Social Engagement Client
├── deploy-bridgefeed.bat               # One-click master deployment to bridge.reelsflow.io
├── deploy-auto-update.bat              # Local automated update script
├── deploy-auto-update-b2.ps1           # Legacy Backblaze B2 APK release pipeline
├── start-dev-server.ps1                # Local backend dev server launcher
│
├── deploy/                             # VPS Deployment Suite (bridge.reelsflow.io)
│   ├── config.env                      # Deployment targets: DOMAIN, SERVER_IP, SSH_PORT, APP_PORT (5081)
│   ├── common.ps1                      # SSH / SCP transport utilities & pinned host-key verification
│   ├── deploy-backend.ps1              # Packages backend tarball, deploys container & updates DB
│   ├── deploy-android.ps1              # Builds signed release APK, uploads to /apk, seeds release API
│   ├── .deploy-secrets.env.example     # Secrets template (SSH_PASSWORD, Jwt__Secret, Admin__ApiKey)
│   └── remote/
│       └── setup.sh                    # Idempotent server provisioner: Docker, Nginx, Certbot, Postgres
│
├── backend/
│   ├── FeedPilot.sln                   # .NET 10 solution file
│   ├── Dockerfile                      # Multi-stage Linux container (SDK 10.0 -> ASP.NET 10.0 runtime)
│   └── src/FeedPilot.Api/
│       ├── Program.cs                  # Pipeline, rate limiting, static files (/apk), SignalR, Telegram
│       ├── Controllers/                # 42 Controllers (Auth, Bridge, Orders, Wallet, Admin, Picked)
│       ├── Data/                       # AppDbContext, DatabaseSchemaMigrator
│       ├── Domain/                     # Entities (User, Account, AppOrder, FeedBridgeJob, etc.)
│       ├── Dtos/                       # Request/Response contracts (BridgeFeedResponse, ClaimDto, etc.)
│       ├── Hubs/                       # SignalR Hubs (CoinSyncHub at /hubs/coin-sync)
│       ├── Services/                   # FeedBridgeService, WalletService, TelegramLogging, Sweepers
│       └── wwwroot/                    # Web dashboards & static assets
│           ├── apk/                    # Local storage for release APKs and update-metadata.json
│           ├── dashboard/              # Admin UI (index.html, Update.html, backup.html)
│           ├── upgrade/                # Gendered upgrade profile assets (male/female posts, bios, etc.)
│           ├── comments/               # Service-specific comment text files
│           └── generator/              # Username generator datasets
│
├── android-client/
│   ├── build.gradle.kts                # Root Gradle configuration (Kotlin 2.0.21, AGP 8.7.3)
│   ├── release-update.properties       # Release versioning metadata (versionCode, versionName, notes)
│   └── app/
│       ├── build.gradle.kts            # App dependencies: Compose, Hilt, Room v21, Retrofit, WorkManager
│       └── src/main/java/com/feedpilot/client/
│           ├── MainActivity.kt         # Single-activity Compose host, route navigation & observers
│           ├── RootViewModel.kt        # Top-level state: session validation, theme, network telemetry
│           ├── bridge/                 # FeedBridgeRunner (Executes Instagram feed queries on device)
│           ├── common/                 # DeviceIdentity (package-derived clone appId), Preferences
│           ├── data/
│           │   ├── local/              # AppDatabase (Room v21), AccountDao, TaskDao, OrderDao
│           │   ├── remote/             # Retrofit ApiService, InstagramWebClient, ExceptionUsersApi
│           │   └── repository/         # InstagramRepository, TaskRepository, AppOrderRepository
│           ├── di/                     # Hilt Dependency Injection (DatabaseModule, NetworkModule)
│           ├── feature/                # Compose screens (bridge, tasks, orders, wallet, accounts, etc.)
│           │   ├── bridge/             # BridgeLogsScreen, ExceptionUsersPanel, ExceptionUsersViewModel
│           │   └── tasks/              # TasksScreen, TaskItem, streak indicators
│           ├── identity/               # Cryptographic installation keys & challenge response
│           ├── service/                # TaskRunnerService, FeedBridgeService, UpgradeRunnerService
│           ├── ui/components/          # BridgeModeBadge, SearchAccountDropdown, AppTopBar
│           └── worker/                 # WorkManager workers (FeedBridgeWorker, FeedWatcherWorker)
│
├── ios-client/
│   ├── README.md                       # iOS integration runbook
│   └── FeedPilot/                      # SwiftUI starter views & FeedPilotApiClient (points to bridge.reelsflow.io)
│
├── chrome-extension/
│   ├── manifest.json                   # Chrome Manifest V3 extension
│   ├── background.js                   # Cookie synchronization & tab management
│   ├── content.js                      # Admin dashboard helper
│   └── feedpilot-instagram-session/    # Unpacked session inspection copy
│
└── docs/
    ├── ARCHITECTURE.md                 # System architecture overview
    ├── API.md                          # Core API endpoint inventory
    ├── BUILD.md                        # Compilation instructions
    ├── DEPLOYMENT.md                   # VPS deployment guide
    ├── INSTAGRAM_DEVICE_BRIDGE_ARCHITECTURE.md # Exhaustive feed bridge specification
    ├── IOS.md                          # iOS client guidelines
    └── order-processing-performance-plan.md   # Order settlement concurrency design
```

---

## 3. Technology Stack & Build Configurations

| Layer | Technology | Current Version / Baseline |
| :--- | :--- | :--- |
| **Backend Framework** | ASP.NET Core Web API | **.NET 10** (`net10.0`) |
| **Containerization** | Docker Linux Container | `mcr.microsoft.com/dotnet/sdk:10.0` & `aspnet:10.0` |
| **Data Access & ORM** | Entity Framework Core | EF Core 9.0.0, Npgsql 9.0.2 (Postgres), Sqlite 9.0.0 |
| **Real-Time WebSockets**| ASP.NET Core SignalR | Endpoint: `/hubs/coin-sync` |
| **Security & Auth** | JWT + HMAC Challenge | `Microsoft.AspNetCore.Authentication.JwtBearer` 9.0.0, BCrypt |
| **Android Language** | Kotlin | **2.0.21** (JVM toolchain 17) |
| **Android Build Tool** | Android Gradle Plugin (AGP)| **8.7.3** |
| **Android SDK Support**| Min SDK 24 / Target SDK 35 | Compile SDK 35 |
| **Android UI System** | Jetpack Compose | Compose BOM **2024.12.01**, Material 3 |
| **Android Dependency Inj**| Google Hilt | **2.52** |
| **Android Local DB** | Android Room | **2.6.1** (Schema Version **21**) |
| **Android Networking** | Retrofit & OkHttp | Retrofit 2.11.0, OkHttp 4.12.0 |
| **Background Scheduling**| Android WorkManager | **2.9.1** |
| **Primary Base URL** | All variants (debug, prod) | `https://bridge.reelsflow.io/` |

---

## 4. Complete Backend Controller & Service Catalog

The ASP.NET Core backend implements **42 controllers** structured around 7 operational domains:

### 1. App Authentication & Identity
- `AuthController`: User registration, username/password login, refresh tokens, device authentication, and backup/restore.
- `AccountsController`: Management of connected Instagram accounts, encrypted session payload storage, and switching.
- `InstallationController`: Public key registration and challenge-response for hardware and cloned installations.
- `DevicesController`: Telemetry and hardware registration (battery level, Android OS version, model).

### 2. Device Feed Bridge
- `BridgeFeedController`: Main upstream ingestion (`POST /api/bridge/feed` with `X-Bridge-Key`). Dispatches live feed requests to residential devices.
- `BridgeJobsController`: Android device interaction endpoints:
  * `POST /api/bridge/devices/heartbeat`: Telemetry & active account reporting.
  * `GET /api/bridge/jobs/poll`: Job retrieval by assigned Android worker.
  * `POST /api/bridge/jobs/{id}/complete`: Submits parsed Instagram profile/feed JSON.
  * `POST /api/bridge/jobs/{id}/fail`: Reports job execution failure and triggers failover.
- `InstagramCacheController`: Cached profile and feed lookup to reduce on-device requests.

### 3. Orders, Automation & Task Execution
- `OrderProcessingController`: Core high-throughput work engine:
  * `POST /api/orders/processing/claim`: Devices claim batches of pending orders.
  * `POST /api/orders/processing/{id}/progress`: Single-action progress update.
  * `POST /api/orders/processing/progress-batch`: High-performance batched progress reporting.
  * `POST /api/orders/processing/{id}/release`: Releases abandoned/failed claims.
- `TasksController`: App task queries, pending task lists, and execution logs.
- `OrdersController`: User-facing order creation, pricing calculator, and order history.
- `CommentsController`: Pre-compiled comment lists for engagement tasks.
- `GeneratorController`: Brazilian username generator datasets.

### 4. Wallet, Economics & Referrals
- `WalletController`: Coin balance lookups and append-only transaction history.
- `CoinTransferController`: Direct peer-to-peer user coin transfers.
- `WithdrawController`: User payout requests (supporting UPI, Bank Wire, and USDT BEP-20).
- `ReferralController`: Affiliate link generation, referral trees, and commission accrual.
- `SubscriptionController`: VIP upgrade packages and paid tier management.

### 5. Exception Users & Picked Usernames
- `PickedUsernameController`: Client-facing endpoint for managing priority/exception Instagram handles.
- `AdminPickedUsernamesController`: Administrative endpoint for reviewing and curating the exception users list.

### 6. Admin Operations Suite
- `AdminAuthController`: HMAC-SHA256 challenge-response login issuing temporary `X-Admin-Session`.
- `AdminDashboardController`: Overview metrics (active devices, jobs/hour, queue depth, error rates) and 60s idle passcode lock.
- `AdminBackupController` & `AdminBackupAuthController`: Database snapshot export, schema inspection, and restore.
- `AdminAppReleasesController`: Release APK uploads (fallback to local `wwwroot/apk` when B2 is unconfigured) and version flags.
- `AdminOrdersController`, `AdminWalletsController`, `AdminAccountsController`, `AdminDevicesController`, `AdminWithdrawalsController`, `AdminSubscriptionsController`, `AdminWatchedHandlesController`, `AdminReferralsController`, `AdminPasswordResetsController`, `AdminRunnerSettingsController`, `AdminSmmProviderController`, `AdminInstagramSessionsController`, `AdminInstagramFeedController`, `UpgradeDashboardController`.

### 7. Background Hosted Services & Infrastructure
- `FeedBridgeService`: Core orchestration for bridge job distribution, device selection, attempt timeouts (10s), and failover.
- `FeedBridgeJobSweepService`: Hosted background worker cancelling expired jobs (>40s).
- `StaleClaimSweepService`: Proactively frees order claims abandoned by unresponsive devices.
- `SmmPanelSyncService`: Syncs order fulfillment with external SMM panels.
- `TelegramRequestLogger`: High-performance background channel worker pushing one-line request/status summaries to Telegram.

---

## 5. Database Schema & Persistence Architecture

The persistence layer is managed by [AppDbContext.cs](file:///c:/CoreProjects/FeedPilot/backend/src/FeedPilot.Api/Data/AppDbContext.cs) and updated dynamically on startup via [DatabaseSchemaMigrator.cs](file:///c:/CoreProjects/FeedPilot/backend/src/FeedPilot.Api/Data/DatabaseSchemaMigrator.cs) without requiring conventional EF migration files.

### Core Database Entities:
1. **`User`**: Base user entity. Stores username, password hash (BCrypt), subscription tier, referral code, and links to `Wallet`.
2. **`Account`**: Managed Instagram account cards. Holds encrypted cookie session data (`SessionData`), hardware ID, app clone ID (`AppId`), and validation state.
3. **`AppOrder`**: Engagement order. Holds target URL, required quantity, completed count, unit price (`CoinsSpent`), claim locks (`ProcessingDeviceId`, `ProcessingStartedAt`), and status (`Pending`, `InReview`, `Completed`, `Cancelled`).
4. **`EngagementTask`**: Atomic unit of work (Follow, Like, Comment, Repost, Save Post, Story View).
5. **`Wallet` & `WalletTransaction`**: Financial ledger tracking `CoinsBalance`, `PendingCoins`, `LifetimeCoins`, and `WithdrawnCoins`.
6. **`Withdrawal`**: Cash-out requests tracking payout method (UPI, Bank Wire, USDT BEP-20), destination account, and payout status.
7. **`Device` & `AppInstallation`**: Device metadata (model, OS version, public ECDSA key, last heartbeat, assigned accounts).
8. **`FeedBridgeDeviceState`**: Live bridge worker telemetry (battery, network connection type, assigned Instagram account, cooldowns).
9. **`FeedBridgeAccountState`**: Health metrics for Instagram sessions participating in the residential bridge.
10. **`FeedBridgeJob`**: Upstream feed query record (job ID, target username/ID, status `Pending`/`Completed`/`Failed`, JSON result payload, attempt count, expires at).
11. **`WatchedInstagramHandle` & `WatchedInstagramPost`**: User-monitored profile handles and discovered media post IDs.
12. **`AppRelease`**: APK release catalog (`VersionCode`, `VersionName`, `ApkUrl`, `Sha256`, `SizeBytes`, `ReleaseNotes`, `ForceUpdate`).
13. **`PickedUsername`**: Exception / priority Instagram target usernames.
14. **`RunnerSettings` & `SmmProviderConfig`**: Singleton system configuration rows governing delays, coin rates, and SMM panel APIs.

---

## 6. Key Subsystem Workflows & Operational Mechanics

### A. Instagram Residential Feed Bridge
1. **Upstream Request**: Consumer calls `POST /api/bridge/feed` with `X-Bridge-Key`.
2. **Device Selection Algorithm**:
   - Device and active account must have posted a heartbeat within the last 15 seconds.
   - Device must not have a currently active job assigned.
   - Prefers the most recently active device with a matching or compatible account session.
3. **Execution Budget & Failover**:
   - Global request budget is **30 seconds**.
   - Each device attempt has a **10-second limit** if alternate devices exist.
   - Jobs are assigned via `FeedBridgeJob` with a strict **40-second absolute expiration**.
   - If a device fails or times out, the backend transparently dispatches to the next eligible device.
4. **On-Device Execution (`FeedBridgeRunner.kt`)**:
   - Android client receives job via polling (`GET /api/bridge/jobs/poll`).
   - Uses `InstagramWebClient` to query Instagram web endpoints with native headers and residential IP.
   - Parses profile info, media list, stories, and carousel items into structured JSON.
   - Completes job via `POST /api/bridge/jobs/{id}/complete` or fails via `/fail`.
5. **Telemetry & Visibility**:
   - `BridgeJobNotifier` posts notifications during resolution.
   - `BridgeLogsScreen` displays local TSV history (`bridge-history.tsv`, max 1,000 entries).
   - `BridgeModeBadge` indicates live status (`Idle`, `Running`, `Success`, `Failed`).

### B. Task Automation Engine (`TaskRunnerService.kt`)
1. User adds or selects an Instagram account.
2. Android service claims batch of orders (`POST /api/orders/processing/claim`).
3. Executes actions sequentially:
   - Random delay between actions (`ActionDelayMinMs` to `ActionDelayMaxMs`).
   - Streak break pauses after consecutive actions to emulate authentic user behavior.
4. Successful actions record pending earnings locally.
5. Batch settlements report to `POST /api/orders/processing/progress-batch`.
6. SignalR pushes live coin updates to all connected client screens via `/hubs/coin-sync`.

### C. Exception Users Management
- Integrated via `ExceptionUsersApi.kt`, `ExceptionUsersViewModel.kt`, and `ExceptionUsersPanel.kt` inside the Bridge screen.
- Allows operators to add, remove, and monitor usernames that require special routing, error exclusion, or priority execution.

### D. Observability & Telegram Logging (`TelegramLogging.cs`)
- Asynchronous channel-based queue (`Channel.CreateBounded<string>(500)` with `DropOldest`).
- Captures API requests, HTTP status codes, latencies, and client crash reports.
- Non-blocking: background HTTP worker dispatches to Telegram Bot API without delaying client response times.

### E. App Clone Isolation Architecture
- Cloned APKs generate unique package names.
- `DeviceIdentity.appId` computes the clone identifier dynamically from the package name at runtime, ensuring isolated cookie jars and independent account binding without build-time code edits.

---

## 7. Deployment & Infrastructure Runbook

### Primary Production VPS (`bridge.reelsflow.io`)
- **Server IP**: `200.234.41.82` (Port 22 SSH)
- **Container Port**: `5081` (Loopback only, reverse-proxied by Nginx with Let's Encrypt TLS)
- **Database**: PostgreSQL (`bridgefeed`) on localhost:5432
- **Persistent Storage Volumes**:
  * `/app/wwwroot/apk` -> Server release APK storage (downloadable at `https://bridge.reelsflow.io/apk/*.apk`)
  * `/app/wwwroot/upgrade` -> Gendered profile avatar/bio upgrade assets
  * `/app/wwwroot/comments` -> Task comment templates
  * `/app/wwwroot/generator` -> Username datasets
- **Deploy Scripts**:
  * One-Click Full Deploy: `.\deploy-bridgefeed.bat`
  * Backend Only: `pwsh deploy/deploy-backend.ps1`
  * Android Release Build & Publish: `pwsh deploy/deploy-android.ps1`

### Required Environment Variables:
```ini
ASPNETCORE_ENVIRONMENT=Production
Jwt__Secret=<32+ char strong random secret>
Admin__ApiKey=<Admin dashboard API key>
Admin__DashboardPasscode=<Dashboard lock passcode>
Admin__BackupPasscode=<Backup/restore authorization key>
DATABASE_URL=postgresql://bridgefeed:<password>@127.0.0.1:5432/bridgefeed
RequestSigning__Secret=1705ed0eea571f7a850efb27d0334772e99f82d7cbc45abb873f858e9d8c79bc
Telegram__Enabled=true
Telegram__BotToken=<Bot token from @BotFather>
Telegram__ChatId=<Destination chat id, e.g. -100xxxxxxxxxx>
AssetStorage__Provider=Local
```

---

## 8. Development & Build Commands

### Backend (.NET 10)
```powershell
# Build entire backend solution
dotnet build backend\FeedPilot.sln

# Run backend locally (SQLite dev database)
$env:ASPNETCORE_ENVIRONMENT = 'Development'
dotnet run --project backend\src\FeedPilot.Api\FeedPilot.Api.csproj
```

### Android Client (Kotlin / Gradle)
```powershell
cd android-client

# Compile Debug APK
.\gradlew.bat assembleDebug

# Run Unit Tests
.\gradlew.bat testDebugUnitTest

# Assemble Release APK (requires release.keystore)
.\gradlew.bat assembleRelease

# Bump release version and prepare update metadata
.\gradlew.bat prepareReleaseUpdate
```

Release APK output:
`android-client/app/build/outputs/apk/release/app-release.apk`
