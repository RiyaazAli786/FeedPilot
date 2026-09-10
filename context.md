# FeedPilot — Complete Workspace & Architecture Context

> Living reference for the entire FeedPilot ecosystem. Last updated on 2026-09-10.  
> Companion docs: [README.md](file:///c:/CoreProjects/FeedPilot/README.md), [PROJECT_CONTEXT.md](file:///c:/CoreProjects/FeedPilot/PROJECT_CONTEXT.md), [ARCHITECTURE.md](file:///c:/CoreProjects/FeedPilot/docs/ARCHITECTURE.md), [API.md](file:///c:/CoreProjects/FeedPilot/docs/API.md), [BUILD.md](file:///c:/CoreProjects/FeedPilot/docs/BUILD.md), [DEPLOYMENT.md](file:///c:/CoreProjects/FeedPilot/docs/DEPLOYMENT.md), [INSTAGRAM_DEVICE_BRIDGE_ARCHITECTURE.md](file:///c:/CoreProjects/FeedPilot/docs/INSTAGRAM_DEVICE_BRIDGE_ARCHITECTURE.md).

---

## Executive Summary

**FeedPilot** is a full-stack social growth, engagement, and Instagram device execution platform. It unifies:
1. An **ASP.NET Core 9** backend API with Entity Framework Core, SignalR real-time hubs, and dynamic schema migration.
2. A **Native Android application** (Kotlin, Jetpack Compose, Room, Hilt, WorkManager) executing multi-account Instagram automation and acting as a physical execution node.
3. An **Instagram Device Feed Bridge** that serves residential, logged-in Instagram feeds directly to external services (such as **GetIGFeed**), bypassing anti-bot challenges and proxy blacklists.
4. An **In-Browser Admin Operations Suite** with cryptographic HMAC challenge-response authentication, idle screen lock, APK release distribution, and database backup/restore.
5. A **SwiftUI iOS client starter** and a **Manifest V3 Chrome Extension** for session inspection and profile navigation.

---

## 1. System Architecture

```text
                               ┌──────────────────────────────────────────────┐
                               │             Upstream Consumer                │
                               │        (e.g., GetIGFeed Microservice)        │
                               └──────────────────────┬───────────────────────┘
                                                      │
                                                      │ POST /api/bridge/feed
                                                      │ (Header: X-Bridge-Key)
                                                      ▼
┌─────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ FeedPilot ASP.NET Core 9 Backend (backend/src/FeedPilot.Api)                                                    │
│                                                                                                                 │
│   ┌────────────────────────┐  ┌─────────────────────────┐  ┌────────────────────────┐  ┌────────────────────┐   │
│   │ BridgeFeedController   │  │ OrderProcessingController│  │ AuthController         │  │ Admin Suite        │   │
│   │ & BridgeJobsController │  │ & TasksController       │  │ & AccountsController   │  │ Dashboard & Backup │   │
│   └───────────┬────────────┘  └────────────┬────────────┘  └───────────┬────────────┘  └─────────┬──────────┘   │
│               │                            │                           │                         │              │
│   ┌───────────▼────────────┐  ┌────────────▼────────────┐  ┌───────────▼────────────┐  ┌─────────▼──────────┐   │
│   │ FeedBridgeService      │  │ SmmPanelSyncService     │  │ TokenService           │  │ DatabaseSchema-    │   │
│   │ & JobSweepService      │  │ & StaleClaimSweepService│  │ & WalletService        │  │ Migrator           │   │
│   └───────────┬────────────┘  └────────────┬────────────┘  └───────────┬────────────┘  └─────────┬──────────┘   │
└───────────────┼────────────────────────────┼───────────────────────────┼─────────────────────────┼──────────────┘
                │                            │                           │                         │
                │ Polling / Heartbeat        │ Work Claims / Results     │ Device Registration     │ EF Core
                ▼                            ▼                           ▼                         ▼
┌────────────────────────────────────────────────────────────────────────┐ ┌──────────────────────────────────────┐
│ Android Device Fleet (com.feedpilot.client)                            │ │ Persistence Layer                    │
│                                                                        │ │                                      │
│  - FeedBridgeRunner / FeedBridgeWorker                                 │ │ - PostgreSQL Database (Production)   │
│  - TaskRunnerService (Follow, Like, Comment, Repost, Story)            │ │   or SQLite (Local Development)      │
│  - FeedWatcherWorker (Watches target profiles & captures post IDs)     │ │ - Backblaze B2 (Screenshots / APKs)  │
│  - Room Database (Local sessions, encrypted cookies, accounts)        │ │ - SignalR CoinSyncHub WebSocket      │
└────────────────────────────────────────────────────────────────────────┘ └──────────────────────────────────────┘
```

---

## 2. Directory Layout & Subsystem Responsibilities

```text
FeedPilot/
├── Implementation.md               # Specification of the Android Social Engagement Client
├── README.md                       # High-level repo summary and build commands
├── PROJECT_CONTEXT.md              # Technical project context snapshot
├── context.md                      # Complete system context & living architecture reference
├── render.yaml                     # Render cloud service deployment specification
├── deploy-auto-update-b2.ps1       # Automated APK compilation, upload to B2, and release registration
├── backend/
│   ├── FeedPilot.sln               # .NET 9 solution file
│   ├── Dockerfile                  # Multi-stage containerization build for Render/Linux
│   └── src/FeedPilot.Api/
│       ├── Controllers/            # 42 Controllers (Auth, Accounts, Orders, Tasks, Bridge, Admin)
│       ├── Data/                   # AppDbContext, DatabaseSchemaMigrator
│       ├── Domain/                 # Entities (User, Account, Order, Task, Wallet, FeedBridgeJob)
│       ├── Dtos/                   # Data Transfer Objects
│       ├── Hubs/                   # SignalR WebSocket Hubs (CoinSyncHub)
│       ├── Services/               # Domain business logic & background hosted workers
│       ├── wwwroot/                # Admin dashboards (index.html, Update.html, backup.html)
│       └── Program.cs              # DI container, middleware configuration, and startup
│
├── android-client/
│   ├── build.gradle.kts            # Root gradle project configuration
│   └── app/
│       ├── build.gradle.kts        # Application dependencies (Compose, Room, Hilt, Retrofit)
│       └── src/main/java/com/feedpilot/client/
│           ├── bridge/             # FeedBridgeRunner (Executes Instagram feed queries on device)
│           ├── common/             # DeviceIdentity, Constants, Preferences
│           ├── data/
│           │   ├── local/          # AppDatabase, DAOs (AccountDao, TaskDao, OrderDao)
│           │   ├── remote/         # Retrofit ApiService, InstagramWebClient, DTOs
│           │   └── repository/     # InstagramRepository, TaskRepository, AppOrderRepository
│           ├── di/                 # Hilt Dependency Injection modules
│           ├── feature/            # 20 Jetpack Compose feature modules (orders, wallet, tasks, etc.)
│           ├── identity/           # Cryptographic installation identity & challenge verification
│           ├── service/            # TaskRunnerService, FeedBridgeService, UpgradeRunnerService
│           └── worker/             # WorkManager workers (FeedBridgeWorker, FeedWatcherWorker)
│
├── ios-client/
│   ├── README.md                   # iOS build and Xcode integration notes
│   └── FeedPilot/                  # Swift source code and views
│
├── chrome-extension/
│   ├── manifest.json               # Chrome Manifest V3
│   ├── background.js               # Cookie synchronization and tab management
│   └── content.js                  # Injected script for admin dashboard integration
│
└── docs/
    ├── ARCHITECTURE.md             # Subsystem architectural overview
    ├── API.md                      # Core endpoint catalog
    ├── BUILD.md                    # Compilation procedures
    ├── DEPLOYMENT.md               # Production deployment guide
    └── INSTAGRAM_DEVICE_BRIDGE_ARCHITECTURE.md # Exhaustive specification of the Instagram bridge
```

---

## 3. Database Entities & Relationships

Managed via [AppDbContext.cs](file:///c:/CoreProjects/FeedPilot/backend/src/FeedPilot.Api/Data/AppDbContext.cs) and migrated automatically via [DatabaseSchemaMigrator.cs](file:///c:/CoreProjects/FeedPilot/backend/src/FeedPilot.Api/Data/DatabaseSchemaMigrator.cs).

### Core Entities:
- **`User`**: System user account. Holds paid plan tier (`SubscriptionPlan`), referral code, parent affiliate (`ReferredByUserId`), and links to `Wallet` and `Account` collection.
- **`Account`**: Managed Instagram account belonging to a user. Stores encrypted cookie session payload (`SessionData`), hardware ID, app clone ID (`AppId`), and upgrade state.
- **`AppOrder`**: Engagement order created in-app or imported from external SMM panels. Tracks quantity, target URL, pricing (`CoinsSpent`), claim locks (`ProcessingDeviceId`, `ProcessingStartedAt`), and fulfillment status.
- **`EngagementTask`**: Atomic engagement unit assigned to worker accounts (Follow, Like, Comment, Repost, Save Post, Story View).
- **`Wallet` & `WalletTransaction`**: User coin balances (active, pending, lifetime, withdrawn) and append-only financial ledger.
- **`Withdrawal`**: Cash-out requests supporting UPI, Bank Wire, and USDT (BEP-20) with manual admin verification.
- **`Device` & `AppInstallation`**: Tracks device hardware models, Android versions, app clone installations, and public cryptographic identity keys.
- **`FeedBridgeDeviceState`**: Live telemetry of Android bridge workers (battery, network type, active account, cooldowns, failures).
- **`FeedBridgeAccountState`**: Instagram account health tracking on bridge devices (validation status, busy flag, cooldowns).
- **`FeedBridgeJob`**: Asynchronous bridge feed execution job (status, target username/ID, request/response JSON, attempt count, 40s expiry).
- **`WatchedInstagramHandle` & `WatchedInstagramPost`**: Profiles tracked by users and the feed posts discovered by background workers.
- **`RunnerSettings`**: Singleton row governing global execution delays, cooldowns, streaks, coin rewards, and withdrawal thresholds.
- **`SmmProviderConfig`**: Singleton configuration for external SMM panel synchronization.

---

## 4. Key Subsystem Workflows

### A. Instagram Device Feed Bridge Flow
1. **Request**: External client calls `POST /api/bridge/feed` with `X-Bridge-Key`.
2. **Device Selection**: `FeedBridgeService` selects the healthiest available Android device having a valid session for the requested or compatible account.
3. **Job Creation**: A `FeedBridgeJob` is recorded with `Status = Pending` and `ExpiresAt = UtcNow + 40s`.
4. **Execution**:
   - The device receives the job through `GET /api/bridge/jobs/poll` or immediate push.
   - `FeedBridgeRunner.kt` invokes `InstagramRepository` on-device, executing real mobile/web requests using the local cookie jar.
   - The Android client parses profile info, timeline media, stories, and highlights.
5. **Completion**: The client calls `POST /api/bridge/jobs/{id}/complete` with payload.
6. **Delivery**: The backend transforms the result and returns an HTTP 200 `BridgeFeedResponse` to the caller.

### B. Task Execution & Runner Flow
1. The user logs into Instagram accounts on the Android client.
2. `TaskRunnerService` runs as a foreground service with a persistent notification.
3. For each active account, the runner queries the backend for pending tasks or claims batches of `AppOrder` items (`POST /api/orders/processing/claim`).
4. Actions execute sequentially with randomized delays (`ActionDelayMinMs` to `ActionDelayMaxMs`) and streak controls to mimic human activity and protect account longevity.
5. Successful actions record pending earnings locally and batch-report to the backend (`POST /api/orders/processing/progress-batch`).

### C. Admin HMAC Challenge-Response Security
1. Operator navigates to `/dashboard/index.html` or `/dashboard/backup.html`.
2. Browser calls `POST /api/admin/auth/challenge` to obtain a cryptographic nonce and token.
3. Browser computes `HMAC-SHA256(AdminApiKey, Nonce)`.
4. Browser submits token + hash to `POST /api/admin/auth/login`.
5. Backend verifies HMAC and issues a short-lived `X-Admin-Session` token (renewed on interaction, auto-locked after 60 seconds of inactivity).

---

## 5. Development & Deployment

### Local Development:
```powershell
# Run backend
cd backend\src\FeedPilot.Api
dotnet run

# Build Android App
cd android-client
.\gradlew.bat assembleDebug
```

### Production Deployment (Render):
- Managed via [render.yaml](file:///c:/CoreProjects/FeedPilot/render.yaml).
- Dockerfile builds the ASP.NET Core 9 binary and serves static admin dashboard files.
- Production secrets (`Jwt__Secret`, `Admin__ApiKey`, `DATABASE_URL`, `FEEDPILOT_BRIDGE_KEY`) are managed securely via Render environment variables.
