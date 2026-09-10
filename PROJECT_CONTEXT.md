# FeedPilot Project Context

Last built: 2026-09-10

## What This Repo Is

**FeedPilot** is a production-grade full-stack social engagement, growth automation, and Instagram device execution platform. It consists of:

1. **ASP.NET Core 9 Backend API (`backend/src/FeedPilot.Api`)**:
   - Central microservice handling authentication, multi-tenant accounts, engagement tasks, order fulfillment, wallet transactions, multi-level referrals, SMM panel synchronization, APK auto-updates, and encrypted database backups.
   - Hosts the **Instagram Device Feed Bridge** that turns real Android client devices into residential execution nodes for upstream services (such as **GetIGFeed**).
   - In-browser administrative dashboard, APK release center, and database backup manager served from `wwwroot/dashboard`.

2. **Native Android Client (`android-client`)**:
   - Modern Android application built with **Kotlin**, **Jetpack Compose**, **Hilt (Dependency Injection)**, **Room (Local DB)**, **Retrofit 2 / OkHttp 3**, **WorkManager**, and **Kotlin Coroutines / Flow**.
   - Manages multiple Instagram account sessions on-device, executes engagement tasks (Follow, Like, Comment, Repost, Save Post, Story View), monitors watched Instagram handles, and executes background feed bridge jobs.

3. **Instagram Device Feed Bridge Subsystem**:
   - High-availability bridge linking **GetIGFeed** to real Android hardware running real Instagram sessions.
   - Upstream service calls `POST /api/bridge/feed` with `X-Bridge-Key`.
   - FeedPilot selects an active, non-busy device and account, creates a `FeedBridgeJob`, dispatches it via polling or foreground service, executes the feed query directly on the Android device via `FeedBridgeRunner`, and returns a normalized `web_profile_info` response.

4. **Native iOS Starter Client (`ios-client`)**:
   - SwiftUI starter client targeting iOS, sharing the same ASP.NET Core backend contracts (`/api/auth/device`, watched handles, wallet).

5. **Chrome Extension (`chrome-extension`)**:
   - Manifest V3 browser extension ("FeedPilot Instagram Session Opener") allowing dashboard operators to inspect profiles and manipulate sessions directly from the web admin dashboard.

6. **External SMM Integration & Order Processing Engine**:
   - Background worker `SmmPanelSyncService` with bidirectional order pulling and status updating against external providers (e.g., `smmorigin.com`).
   - Concurrency control, claim timeouts, stale claim sweeping (`StaleClaimSweepService`), and anti-race refund protection.

---

## Workspace & Directory Structure

```text
FeedPilot/
├── Implementation.md               # Historical functional specification of the Android client
├── README.md                       # High-level repo summary and build commands
├── PROJECT_CONTEXT.md              # Living, complete operational & technical project context
├── context.md                      # Comprehensive architecture, DB schema & subsystem guide
├── render.yaml                     # Render blueprint for Dockerized backend deployment
├── deploy.bat                      # Quick git deployment helper
├── build.bat                       # Root build script
├── start-dev.bat                   # Local dev server launcher
├── start-dev-server.ps1            # PowerShell local dev environment script
├── deploy-auto-update-b2.ps1       # B2 release publisher & auto-update metadata updater
├── deploy-auto-update-b2.bat       # Wrapper for B2 release publisher
├── deploy-auto-update.bat          # Legacy update release script
│
├── backend/                        # ASP.NET Core 9 Web API
│   ├── FeedPilot.sln               # Visual Studio / dotnet Solution
│   ├── Dockerfile                  # Multi-stage production container build
│   ├── DEPLOY.md                   # Render deployment walkthrough
│   └── src/FeedPilot.Api/
│       ├── Controllers/            # 42 REST controllers
│       ├── Data/                   # AppDbContext and DatabaseSchemaMigrator
│       ├── Domain/                 # Entities (User, Account, Order, FeedBridgeJob, etc.) & Enums
│       ├── Dtos/                   # Request/response DTO contracts
│       ├── Hubs/                   # SignalR hubs (CoinSyncHub)
│       ├── Services/               # Domain services & hosted background workers
│       ├── wwwroot/                # Static assets, admin dashboard, APK release center
│       ├── appsettings.json        # Base configuration
│       └── Program.cs              # Dependency injection, middleware pipeline, startup
│
├── android-client/                 # Native Android Application (Kotlin / Jetpack Compose)
│   ├── build.gradle.kts            # Root Gradle build script
│   ├── settings.gradle.kts         # Gradle project settings
│   ├── gradlew.bat                 # Windows Gradle wrapper
│   └── app/
│       ├── src/main/java/com/feedpilot/client/
│       │   ├── bridge/             # FeedBridgeRunner (Instagram execution on device)
│       │   ├── common/             # Constants, DeviceIdentity, Preferences
│       │   ├── data/               # Local Room DB, Daos, Remote Retrofit API, Repositories
│       │   ├── di/                 # Hilt dependency injection modules
│       │   ├── feature/            # Compose UI feature screens (accounts, orders, wallet, etc.)
│       │   ├── identity/           # Cryptographic app installation key generation & challenges
│       │   ├── security/           # Keystore encryption & secure storage
│       │   ├── service/            # TaskRunnerService, FeedBridgeService, UpgradeRunnerService
│       │   ├── task/               # Task execution handlers (Like, Follow, Comment, etc.)
│       │   ├── ui/                 # App navigation, themes, components
│       │   └── worker/             # WorkManager workers (FeedBridge, FeedWatcher, Sync, Update)
│       └── src/main/AndroidManifest.xml
│
├── ios-client/                     # SwiftUI iOS Starter
│   ├── README.md                   # iOS build and Xcode integration guide
│   └── FeedPilot/                  # Swift source files (API models, views, networking)
│
├── chrome-extension/               # Chrome Extension (Manifest V3)
│   ├── manifest.json               # MV3 manifest
│   ├── background.js               # Cookie & tab manipulation service worker
│   ├── content.js                  # Content script injecting session helpers
│   └── icons/                      # Extension icons
│
├── docs/                           # Technical Specifications & Guides
│   ├── ARCHITECTURE.md             # Subsystem architecture overview
│   ├── API.md                      # Core endpoint catalog
│   ├── BUILD.md                    # Build guides for Backend, Android, and iOS
│   ├── DEPLOYMENT.md               # Production deployment guide for Render & Postgres
│   ├── INSTAGRAM_DEVICE_BRIDGE_ARCHITECTURE.md # Exhaustive specification of the device bridge
│   ├── IOS.md                      # iOS implementation notes
│   └── order-processing-performance-plan.md    # Performance & Room indexing roadmap
│
└── scripts/
    └── test_follow_drop_check.js   # Diagnostic node script testing Instagram follow stickiness
```

---

## Runtime & Tech Stack

| Subsystem | Framework / Technology | Language | Runtime Version |
| :--- | :--- | :--- | :--- |
| **Backend API** | ASP.NET Core 9, EF Core, SignalR | C# 13 | `.NET 9.0` |
| **Database** | PostgreSQL (Render Managed) / SQLite (Local) | SQL | Npgsql 9.x / Microsoft.Data.Sqlite |
| **Android Client**| Jetpack Compose, Hilt, Room, Retrofit, WorkManager | Kotlin | Android API 26-35 (JDK 17) |
| **iOS Client** | SwiftUI, URLSession, Combine | Swift 5.9+ | iOS 16.0+ |
| **Admin UI** | Vanilla HTML5, CSS3, JavaScript (ES6+), Tailwind CDN | HTML/JS | Evergreen Browsers |
| **Cloud Storage** | Backblaze B2 (S3-compatible API) | REST | Upgrade screenshots & APK assets |
| **Realtime** | ASP.NET Core SignalR (`/hubs/coins`) | WebSocket | Live balance and task sync |

---

## Core Subsystems & Architectures

### 1. Instagram Device Feed Bridge Subsystem

The **Feed Bridge** eliminates datacenter IP blocks and anti-bot challenges encountered by web scraping proxies by delegating Instagram private API queries directly to real Android devices logged into active Instagram accounts.

```text
 Upstream Service (GetIGFeed)
           │
           │ POST /api/bridge/feed (Header: X-Bridge-Key)
           ▼
┌─────────────────────────────────────────────────────────────┐
│ FeedPilot ASP.NET Core Backend                              │
│                                                             │
│ 1. Verify X-Bridge-Key matches FEEDPILOT_BRIDGE_KEY         │
│ 2. FeedBridgeService.ExecuteFeedAsync()                     │
│ 3. Match candidate FeedBridgeDeviceState & AccountState    │
│ 4. Insert FeedBridgeJob (Status: Pending, TTL: 40s)         │
│ 5. Await TaskCompletionSource or completion signal          │
└──────────────────────────┬──────────────────────────────────┘
                           │
             Polling via GET /api/bridge/jobs/poll
             or Foreground Service Dispatch
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ Android Device (FeedPilot App)                              │
│                                                             │
│ 1. FeedBridgeRunner / FeedBridgeWorker receives job         │
│ 2. Resolves local Instagram account cookie session          │
│ 3. InstagramRepository.getUserProfileDetails()              │
│ 4. InstagramRepository.getUserFeed() (Limit / maxId)        │
│ 5. Optional Story & Highlight extraction                    │
│ 6. POST /api/bridge/jobs/{id}/complete with BridgeFeedResult│
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│ FeedPilot Backend                                           │
│                                                             │
│ 1. Update FeedBridgeJob (Status: Completed)                 │
│ 2. Release device/account busy lock                         │
│ 3. Return BridgeFeedResponse to GetIGFeed within 35s SLA    │
└─────────────────────────────────────────────────────────────┘
```

#### Bridge Endpoints:
- `POST /api/bridge/feed`: Public external endpoint guarded by `X-Bridge-Key`.
- `POST /api/bridge/devices/heartbeat`: Android device reports battery, network, active account, and installed accounts.
- `GET /api/bridge/jobs/poll`: Android device claims next pending bridge job.
- `POST /api/bridge/jobs/{id}/complete`: Android device submits profile, feed, stories, and highlights.
- `POST /api/bridge/jobs/{id}/fail`: Android device reports fatal error code (`NO_LOCAL_SESSION`, `CHALLENGE_REQUIRED`, etc.).

---

### 2. Multi-Account Management & Installation Identity

- **Multi-Account**: Users link multiple Instagram accounts per device. Encrypted session cookies are preserved locally in Room and synced to the backend `Accounts` table.
- **Hardware & Clone Grouping**: Devices report `stableAppInstallationId`, `accountBindingInstallationId`, and `HardwareId`. Cloned app installs are grouped cleanly in the admin panel under their parent hardware device.
- **Cryptographic Installation Identity**:
  - Devices generate an asymmetric key pair on install.
  - `POST /api/installations/register` saves public key.
  - Sensitive operations can challenge the installation via single-use nonces verified by `InstallationChallengeService`.

---

### 3. Task Execution & Order Processing Engine

- **Order Types**: Follow, Like, Comment, Repost, Save Post, Story View.
- **Claim & Fulfillment Pipeline**:
  - Orders are placed in-app or imported via external SMM panel (`SmmPanelSyncService`).
  - Mobile workers claim batches (`POST /api/orders/processing/claim`) with concurrency locks.
  - `TaskRunnerService` runs one coroutine per account with configurable delays and streak pacing (`RunnerSettings`).
  - Workers report batch progress (`POST /api/orders/processing/progress-batch`).
  - Coins are reserved locally in `pending_earnings` and finalized upon backend acknowledgment.
  - Background `StaleClaimSweepService` releases abandoned orders whose claims exceeded `ClaimTimeoutMinutes`.

---

### 4. Wallet, Coins & Referral System

- **Coins Schema**: Users earn coins by performing tasks (standard rates vs. upgraded rates) and spend coins to buy app orders.
- **Upgrades**: Accounts can undergo an upgrade flow (`POST /api/accounts/{id}/upgrade`), verified by screenshot uploads to Backblaze B2, granting a 24-hour coin multiplier.
- **Multi-Level Referrals**:
  - 3-tier referral tree (Level 1: 50%, Level 2: 25%, Level 3: 10% of bonus pool).
  - Referral codes (e.g. `TF-XXXXXX`) attributed upon device registration.
- **Withdrawals**: Coin-to-fiat/crypto conversions supporting UPI, Bank Transfer, and USDT (BEP-20) with admin settlement workflow.
- **Coin Transfers**: Direct user-to-user or admin-initiated balance transfers with double-entry audit logging (`CoinTransfers` & `WalletTransactions`).

---

### 5. Watched Instagram Handles

- Users register handles they want to monitor (`POST /api/watched-handles`).
- Android `FeedWatcherWorker` periodically wakes up, resolves recent posts via an active Instagram session, and submits new post IDs to `POST /api/watched-handles/{id}/feed`.
- Posts are stored idempotently with unique compound keys `(WatchedInstagramHandleId, PostId)`.

---

### 6. Admin Suite & Security Architecture

- **HMAC Challenge-Response Authentication**:
  - The admin dashboard (`/dashboard/index.html`) and database backup UI (`/dashboard/backup.html`) never send raw admin keys.
  - They request a cryptographic challenge (`POST /api/admin/auth/challenge`), compute `HMAC-SHA256(Key, Nonce)`, and exchange it for a sliding-window session token (`X-Admin-Session` or `X-Backup-Session`).
  - Idle timeout auto-locks the UI after 60 seconds.
- **Encrypted Database Backup & Restore**:
  - Full schema and table export/import via `POST /api/admin/backup/database` guarded by a separate `Admin:BackupPasscode`.
- **Request Signing**:
  - Optional `X-Request-Signature` verification with timestamp tolerance to lock `/api/**` to official Android builds.

---

## API Route Surface

### Public & System
- `GET /`: Health check / redirect.
- `GET /hubs/coins`: SignalR real-time wallet hub.
- `GET /dashboard/*`: Admin Web Panel, Release Center (`Update.html`), and Backup (`backup.html`).

### Authentication (`/api/auth`)
- `POST /api/auth/device`: Device registration and anonymous authentication.
- `POST /api/auth/register`: User account registration.
- `POST /api/auth/login`: Email/password login returning JWT access and refresh tokens.
- `POST /api/auth/refresh`: Refresh expired JWT.
- `POST /api/auth/claim`: Claim anonymous device account into registered user profile.
- `POST /api/auth/logout`: Invalidate session.
- `DELETE /api/auth/account`: Delete user account.

### Managed Accounts (`/api/accounts`)
- `GET /api/accounts`: List linked Instagram accounts.
- `POST /api/accounts`: Save/sync Instagram account credentials.
- `DELETE /api/accounts/{id}`: Unlink account.
- `POST /api/accounts/{id}/refresh`: Refresh account session.
- `POST /api/accounts/{id}/upgrade`: Submit upgrade proof for coin multiplier.
- `GET /api/accounts/check-duplicate`: Duplicate handle validation.

### Orders & Tasks
- `POST /api/orders`: Place engagement order (coins debited immediately).
- `GET /api/orders`: List user's active/completed orders.
- `GET /api/orders/quote`: Calculate price for order quantity.
- `POST /api/orders/{id}/cancel`: Cancel pending order with coin refund.
- `POST /api/orders/processing/claim`: Worker claims batch of orders.
- `POST /api/orders/processing/{id}/progress`: Report single order progress.
- `POST /api/orders/processing/progress-batch`: Report batch progress and settle rewards.
- `GET /api/tasks`: Legacy pending task retrieval.
- `POST /api/tasks/{id}/result`: Submit task completion result.

### Instagram Device Bridge (`/api/bridge`)
- `POST /api/bridge/feed`: External feed execution endpoint (Guarded by `X-Bridge-Key`).
- `POST /api/bridge/devices/heartbeat`: Device state, capabilities, and account sync.
- `GET /api/bridge/jobs/poll`: Device polls for available bridge job.
- `POST /api/bridge/jobs/{id}/complete`: Device submits completed feed result.
- `POST /api/bridge/jobs/{id}/fail`: Device reports failure.

### Wallet, Transfers & Referrals
- `GET /api/wallet`: Fetch coin balance and statistics.
- `GET /api/wallet/history`: Ledger of wallet transactions.
- `POST /api/wallet/transfer`: Transfer coins to another user.
- `GET /api/wallet/transfer/history`: Audit log of transfers.
- `POST /api/withdraw`: Submit withdrawal request (UPI/Bank/USDT).
- `GET /api/referral`: Multi-tier referral statistics and affiliate code.
- `POST /api/referral/apply`: Link device to an affiliate code.

### Watched Handles
- `GET /api/watched-handles`: List tracked Instagram profiles.
- `POST /api/watched-handles`: Add profile to watch list.
- `GET /api/watched-handles/{id}`: Profile details and follower count.
- `PATCH /api/watched-handles/{id}`: Toggle watch or update interval.
- `DELETE /api/watched-handles/{id}`: Stop watching.
- `GET /api/watched-handles/{id}/posts`: View captured feed posts.
- `POST /api/watched-handles/{id}/feed`: Worker submits discovered post IDs.

### Version & Updates
- `GET /api/version`: Query current release version and force-update status.
- `GET /api/apk/latest`: Redirect to latest APK download URL.

### Admin Suite (`/api/admin/*`)
- `POST /api/admin/auth/challenge` & `POST /api/admin/auth/login`: Admin session handshake.
- `POST /api/admin/backup/auth/challenge` & `login`: Backup session handshake.
- `POST /api/admin/backup/database`: Database export & restore.
- `GET /api/admin/dashboard`: Global high-level operational statistics.
- `GET|POST|PUT|DELETE /api/admin/orders/*`: Admin order inspection and intervention.
- `GET|POST|DELETE /api/admin/devices/*`: Device hardware, clone tracking, and recovery.
- `GET|PUT /api/admin/runner-settings`: Global runtime pacing, streak limits, and delays.
- `GET|PUT /api/admin/smm-provider`: External SMM panel connection settings.
- `GET|POST /api/admin/app-releases`: Manage APK version rollouts.

---

## Environment Variables & Configuration

Key configuration parameters (set via `appsettings.json` or environment variables):

| Variable | Description | Default / Example |
| :--- | :--- | :--- |
| `ASPNETCORE_ENVIRONMENT` | Hosting environment | `Production` / `Development` |
| `PORT` | Listening HTTP port | `10000` (Render default) |
| `DATABASE_URL` | PostgreSQL connection URL | `postgres://user:pass@host:5432/db` |
| `ConnectionStrings__Default` | Fallback SQLite/Postgres connection string | `Data Source=feedpilot.db` |
| `Jwt__Secret` | 256-bit secret for signing user JWTs | **Required in production** |
| `Admin__ApiKey` | Master key for admin dashboard access | **Required in production** |
| `Admin__BackupPasscode` | Separate master key for database backup/restore | **Required in production** |
| `Admin__DashboardPasscode` | Idle-lock PIN for admin dashboard | `dev-dashboard-passcode` |
| `FEEDPILOT_BRIDGE_KEY` | Shared secret for `X-Bridge-Key` bridge access | Set to allow GetIGFeed access |
| `RequestSigning__Secret` | Android HMAC client signature secret | Optional |
| `Telegram__Enabled` | Enable real-time error & audit Telegram logging | `false` |
| `Telegram__BotToken` | Telegram Bot API token | Sensitive |
| `Telegram__ChatId` | Target Telegram group/chat ID | Numeric string |
| `AssetStorage__Provider` | Proof screenshot storage backend | `B2` / `Local` |
| `AssetStorage__KeyId` | Backblaze B2 Application Key ID | Sensitive |
| `AssetStorage__ApplicationKey` | Backblaze B2 Application Key | Sensitive |
| `AssetStorage__BucketName` | B2 Target Bucket Name | e.g. `feedpilot-assets` |
| `SmmPanel__ApiKey` | External SMM panel API key | Sensitive |

---

## Build & Operational Commands

### 1. Backend (`FeedPilot.Api`)
```powershell
# Restore & build solution
dotnet build backend\FeedPilot.sln

# Run backend locally with hot reload
cd backend\src\FeedPilot.Api
dotnet run
```

### 2. Android Client (`android-client`)
```powershell
cd android-client

# Assemble debug build
.\gradlew.bat assembleDebug

# Assemble release APK
.\gradlew.bat assembleRelease
# Output artifact: android-client/app/build/outputs/apk/release/app-release.apk
```

### 3. Automated Update Deployment (B2)
```powershell
# Publish release APK to Backblaze B2 and update backend version metadata
.\deploy-auto-update-b2.ps1 -BackendUrl "https://your-api.onrender.com" -AdminKey "YOUR_ADMIN_KEY" -ReleaseNotes "Bug fixes and bridge enhancements" -ForceUpdate "false"
```

### 4. Diagnostics & Scripts
```powershell
# Test Instagram follow action stickiness and verification delay
node scripts/test_follow_drop_check.js --username target_handle --execute
```

---

## Current Health & Verification

- **Backend Solution**: Builds cleanly (`dotnet build backend\FeedPilot.sln`) with **0 Warnings** and **0 Errors** targeting `.NET 9.0`.
- **Database Schema**: Fully automated dynamic schema migrator (`DatabaseSchemaMigrator.cs`) ensures table, index, and column parity across PostgreSQL and SQLite without requiring EF Core CLI tooling in production containers.
- **Bridge Integration**: Validated contract alignment between FeedPilot (`BridgeFeedController.cs`, `FeedBridgeRunner.kt`) and GetIGFeed (`feedPilotBridge.service.js`, `mapFeedPilotBridgeResponse.js`).
