# FeedPilot Project Context

Reviewed against repository source: 2026-10-24.
Baseline commit: `4836680` (Store APKs and assets on the server instead of B2).
This is a source inspection, not a successful build or production health report.

## Purpose and scope

FeedPilot is a full-stack Instagram account, feed monitoring, and engagement task platform. The backend manages users, devices, accounts, orders, coins, referrals, subscriptions/upgrades, and releases. Android clients hold Instagram sessions and execute work. A device feed bridge lets an upstream caller request Instagram data through an available Android session.

The repository also includes a backend-hosted admin dashboard, a SwiftUI iOS starter, and a Chrome extension for opening saved Instagram sessions. Android is the main implemented mobile client; iOS does not have equivalent functionality.

## Repository map

| Path | Responsibility |
| --- | --- |
| `backend/src/FeedPilot.Api/Program.cs` | Service registration, database selection, auth, middleware, endpoints, startup schema handling |
| `backend/src/FeedPilot.Api/Controllers/` | App, device, bridge, and administrative HTTP APIs (42 controllers) |
| `backend/src/FeedPilot.Api/Services/` | Wallet/order logic, identity, signing, feed bridge, storage, Telegram logging, hosted workers |
| `backend/src/FeedPilot.Api/Domain/` and `Dtos/` | Persisted entities, enums, request/response contracts |
| `backend/src/FeedPilot.Api/Data/` | EF context and custom schema migrator |
| `backend/src/FeedPilot.Api/wwwroot/dashboard/` | Admin UI, update UI, backup UI |
| `android-client/app/src/main/java/com/feedpilot/client/` | Kotlin app source |
| Android `feature/`, `ui/`, `RootViewModel.kt`, `MainActivity.kt` | Compose screens, navigation, application UI state, Exception Users panel |
| Android `data/`, `di/` | Room models/DAOs, API clients, repositories, Hilt wiring |
| Android `task/`, `service/`, `worker/`, `bridge/` | Engagement execution, foreground services, scheduled work, bridge runner |
| Android `identity/`, `security/`, `common/` | Installation identity, guards, secure storage, session/crypto utilities |
| `android-client/app/src/test/` | JVM tests and some network diagnostic tests |
| `ios-client/FeedPilot/` | SwiftUI views, models, session, URLSession API client |
| `chrome-extension/` | Manifest V3 Instagram session opener |
| `chrome-extension/feedpilot-instagram-session/` | Second unpacked extension copy; check which users install before changing either |
| `deploy/` | Production deployment suite for `bridge.reelsflow.io` (Docker, Nginx, TLS, Postgres, APK distribution) |
| `render.yaml`, `backend/Dockerfile` | Secondary / staging deployment blueprint and container |
| `deploy-bridgefeed.bat` | One-click VPS deployment pipeline |
| `docs/` | Architecture, API, build, deployment, bridge design, performance plan |

## Actual build configuration

| Component | Current source configuration |
| --- | --- |
| Backend | `net10.0`; Docker SDK/runtime images are .NET 10 |
| Backend packages | EF Core and JWT bearer 9.0.0; Npgsql EF provider 9.0.2; Swashbuckle 7.2.0 |
| Android | Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Java/JVM 17 |
| Android SDK | Minimum 24; compile and target 35 |
| Android data/network | Room 2.6.1, Retrofit 2.11.0, OkHttp 4.12.0 |
| Android UI/background | Compose BOM 2024.12.01, Hilt 2.52, WorkManager 2.9.1 |
| Android local DB | Room schema version 21; schema export disabled |
| iOS | SwiftUI source starter; requires creation/integration of an Xcode target on macOS |

Debug, staging, and release Android variants all currently point to `https://bridge.reelsflow.io/`. Debug and staging have distinct package suffixes and enable HTTP body logging by default; release disables it by default. `LOG_HTTP_RESPONSES` can override this at build time. Do not assume a debug build connects to localhost.

## Main execution flows

### Identity and account sessions

App authentication uses the backend auth APIs and JWTs. Device, installation, app package, and hardware identities have separate roles, especially for cloned installations. Inspect `DeviceIdentity`, `CloneDataIsolationGuard`, `InstallationRepository`, and backend identity services together when changing account binding.

Instagram session handling spans WebView/direct login, cookie storage, `InstagramWebClient`, `InstagramRepository`, and `AccountRepository`. `AccountSessionGate` and `AccountSessionMerge` have dedicated tests. Preserve the relationship between a selected account and its own cookies when handling login, refresh, or account switching.

`AppFeatureFlags.ENFORCE_LOGIN_AND_START_PRECONDITIONS` is currently `true`.

### Orders, execution, and earnings

App orders and provider-imported orders are coordinated by the backend. `SmmPanelSyncService` handles provider synchronization; `StaleClaimSweepService` frees stale work claims.

Core API routes include:

- `POST /api/orders/processing/claim`
- `POST /api/orders/processing/{id}/progress`
- `POST /api/orders/processing/progress-batch`
- `POST /api/orders/processing/{id}/release`

Android execution runs through `TaskRunnerService`, `TaskRepository`, `AppOrderRepository`, and the engagement engine. Work types include follow, like, comment, repost, save, and story view.

Changes to throughput must preserve these existing constraints:

- Serialize Instagram actions for each account.
- Preserve duplicate target checks and account action logs.
- Keep per-order settlement locking.
- Record pending earnings before backend confirmation and reconcile them against confirmed awards.
- Release claims when work cannot continue and preserve retry behavior after failed settlement.
- Keep backend order/coin updates consistent under concurrent workers.

Backend order progress uses PostgreSQL transaction/locking paths and a separate gate for other database providers. A SQLite-only check does not establish correctness under PostgreSQL concurrency.

### Wallet and realtime

`WalletService` coordinates balances and transactions, with app-facing wallet, transfer, withdrawal, referral, and subscription APIs. `CoinSyncHub` is mapped at **`/hubs/coin-sync`**. The request pipeline flushes pending wallet broadcasts after downstream request handling.

Local pending earnings are distinct from confirmed backend balances; a successful Instagram action alone is insufficient to establish final coin settlement.

### Device feed bridge

An upstream caller submits `POST /api/bridge/feed` with `X-Bridge-Key`. The expected key comes from `Bridge:Key` or `FEEDPILOT_BRIDGE_KEY`; an absent key disables acceptance. The backend selects device/account state, queues a job, and waits for completion.

Android reports availability and polls jobs through:

- `POST /api/bridge/devices/heartbeat`
- `GET /api/bridge/jobs/poll`
- `POST /api/bridge/jobs/{id}/complete`
- `POST /api/bridge/jobs/{id}/fail`

Bridge selection requires device and account heartbeats within 15 seconds, matches app/device/installation identity, and excludes devices with an assigned/running job. It prefers the most recently seen eligible device and that device's active account. Accounts are never reassigned across installations as a fallback. Explicit heartbeat account snapshots invalidate removed sessions. Android fails jobs without a matching local session rather than substituting an arbitrary account.

Offline backend selection checks: `dotnet run --project backend/tests/FeedPilot.BridgeChecks` (requires .NET 10).

`FeedBridgeRunner` executes through local Instagram sessions and maps returned data with `BridgeFeedResultMapper`. Backend `FeedBridgeService` has a 30-second total request budget. When another device is available, each attempt gets up to 10 seconds before trying a different device; the last available device can use the remaining budget. Failed/empty feeds also trigger failover, and each device is attempted once per request. Jobs expire at their attempt deadline; duplicate claims and late completions are rejected. `FeedBridgeJobSweepService` handles stale jobs.

The Android bridge pill shows running target, finished/returning result, and backend-acknowledged result or failure. Outcomes stay visible for four seconds, then idle heartbeats restore "Bridge mode on". The foreground service and background worker share a mutex to prevent simultaneous execution.

Settings includes a device-local `bridgeEnabled` preference (default true to preserve existing behavior). `BridgeModeController` reapplies it on app launch, starts/stops the foreground service, and schedules/cancels bridge work. Disabled mode sends an offline heartbeat, retries delivery if offline, and prevents polling/result submission locally. The backend cancels this device's unfinished assignments without penalizing its account session and can fail over to another device. Re-enabling registers availability through a fresh heartbeat; a saved Instagram session is required for readiness. The pill and details show the actual fetching account separately from the target handle.

Android's Bridge tab is available only while the saved bridge setting is enabled, including swipe navigation. `BridgeLogStore` records status transitions with timestamp, fetching account, target and job ID into device-local `bridge-history.tsv`; it retains the newest 1,000 entries and suppresses repeated identical heartbeat statuses. `BridgeLogsScreen` searches all retained entries, displays 25 per page, and copies individual entries or all search matches. Disabling hides the tab while retaining history for the next enable.

`BridgeJobNotifier` alerts when the shared runner starts resolving a request (service or worker), displays the fetching account and target, and updates the same notification with the outcome. Tapping opens Bridge logs. It honors notification and bridge settings plus Android notification permission; disabling bridge cancels the request notification. Android 13+ receives a one-time permission prompt when bridge mode is enabled.

### Watched handles and upgrades

Watched handles/posts have backend persistence, Android repositories/screens, and `FeedWatcherWorker`. Watched post uniqueness is enforced locally by handle/post ID. The iOS starter supports device auth and watched handles/posts.

Upgrade functionality spans Android upgrade screens/runner, backend account/subscription and dashboard endpoints, and `UpgradeAssetStorage`. Render configuration selects B2 storage; local behavior depends on configuration.

## Persistence and startup

`AppDbContext` includes users, accounts, devices, tasks, wallets/transactions, withdrawals, app orders/releases, subscriptions, referrals on user records, watched handles/posts, runner settings, provider configuration, coin transfers, picked usernames, installations/challenges, and bridge device/account/job state.

`Program.cs` resolves the connection string and chooses:

- In-memory storage when there is no connection string in Development.
- SQLite for a connection string starting with `Data Source`.
- PostgreSQL otherwise.

Production requires a database connection and rejects a missing/placeholder JWT secret. Startup calls `EnsureCreated()` and `DatabaseSchemaMigrator.EnsureSchemaUpToDate()`, migrates eligible assets, and seeds bundled APK metadata if present. This repository uses custom startup schema handling; do not assume a conventional EF migrations directory/workflow.

Android Room migrations live in `di/DatabaseModule.kt`. Task, account-log, and pending-earning indexes already exist in `data/local/Entities.kt`.

## Auth and operational boundaries

Backend app APIs use JWT auth, with optional HMAC request signing. Installation registration/challenges provide additional identity verification. Admin/backup endpoints have separate authentication flows; inspect their controllers and `AdminApiKey` before changing access checks.

Request signing covers method, path, and timestamp, not body contents. Its embedded Android key is extractable from the APK and must not be treated as proof of an uncompromised client. Review middleware exemptions when connecting a new caller.

Current rate limiting applies to admin authentication paths (8 requests/minute per IP); ordinary app/device traffic is uncapped in `Program.cs`. Swagger and the dashboard dev-key endpoint are Development-only. Static dashboard assets receive no-store headers. Forwarded proxy headers are used for scheme/IP handling.

Keep session material, deployment secrets, keystores, and diagnostic account data out of context documents and tool output. Root diagnostic files can contain session data and are not documentation sources.

## Development commands and side effects

Backend build from repository root:

```powershell
dotnet build backend\FeedPilot.sln
```

Run the backend with Development configuration:

```powershell
$env:ASPNETCORE_ENVIRONMENT = 'Development'
dotnet run --project backend\src\FeedPilot.Api\FeedPilot.Api.csproj
```

Android commands from `android-client/`:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleRelease
```

Use JDK 17 via `JAVA_HOME` or Android Studio and configure the Android SDK locally. Release builds require the configured keystore. APK output is under `android-client/app/build/outputs/apk/{variant}/`.

Use direct build commands when only compilation is intended:

- Root `build.bat` invokes the Android build helper, which runs cleanup and can install/launch the release APK on an attached ADB device.
- `start-dev-server.ps1` can stop an existing process listening on port 5000.
- Gradle `prepareReleaseUpdate`/`bumpVersion` requests change release version properties during configuration.
- Deployment/release scripts can publish artifacts and change external state; inspect them before running.

Some Android tests are diagnostic calls rather than isolated unit tests. Inspect their dependencies before treating the entire test suite as offline or side-effect free. No backend test project was found in the source file inventory.

## Documentation drift and unresolved verification

Source wins over older prose. Known discrepancies in existing documents:

- Backend READMEs/context still refer to .NET 9; target and container are .NET 10.
- Older project context lists Android minimum API 26 and OkHttp 3; current values are 24 and 4.12.0.
- Older context calls the hub `/hubs/coins`; actual route is `/hubs/coin-sync`.
- The performance plan describes missing Room indexes; indexes are now present. Reassess each proposed improvement against current code.
- Earlier context lists `Implementation.md` and a `scripts/` diagnostic file that were absent from the inspected source inventory.

This review did not run compilation, tests, deployment, upstream Instagram calls, or production health checks. Configured URLs and integration code establish intended connections, not service availability or successful operation.

## Where to start for future changes

| Change | Read first |
| --- | --- |
| Login/session bug | `AccountRepository`, `InstagramRepository`, `InstagramWebClient`, session gate/merge tests |
| Runner performance or coin mismatch | `TaskRunnerService`, `TaskRepository`, `OrderProcessingController`, `WalletService`, Room DAOs/migrations |
| Device bridge | `BridgeFeedController`, `BridgeJobsController`, `FeedBridgeService`, `FeedBridgeRunner`, result mapper/tests |
| Backend/client API contract | Relevant controller, backend `Dtos/Dtos.cs`, Android `ApiService` and DTOs |
| Admin dashboard | `wwwroot/dashboard/index.html`, corresponding admin controllers/authentication |
| APK update/release | Android Gradle build, `release-update.properties`, release scripts, `VersionController`, `AdminAppReleasesController` |
| Deployment/database | `Program.cs`, `DatabaseSchemaMigrator`, Dockerfile, `render.yaml` |

Further references: [README](README.md), [Architecture](docs/ARCHITECTURE.md), [API](docs/API.md), [Build](docs/BUILD.md), [Deployment](docs/DEPLOYMENT.md), [Bridge design](docs/INSTAGRAM_DEVICE_BRIDGE_ARCHITECTURE.md), [Performance plan](docs/order-processing-performance-plan.md), and [Extended historical context](context.md).
