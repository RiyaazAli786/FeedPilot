# Instagram Device Feed Bridge Architecture

## Goal

FeedPilot should act as a device-backed Instagram feed execution bridge for
GetIGFeed.

When a user requests a feed from GetIGFeed, GetIGFeed should not blindly use its
own stored session pool first. It should ask FeedPilot for a device-backed feed
execution. FeedPilot should find an active Android installation with a valid
logged-in Instagram account, run the Instagram feed API from that Android device,
collect the response, and return it to GetIGFeed.

This keeps Instagram activity tied to the real logged-in device/session and gives
FeedPilot central visibility over device health, account health, job assignment,
timeouts, failures, and cooldowns.

## Existing Project Context

FeedPilot already has these useful building blocks:

- Android account/session handling through `InstagramRepository`.
- Android feed execution through `InstagramRepository.getUserProfileDetails()`
  and `InstagramRepository.getUserFeed()`.
- Scheduled Android feed capture through `FeedWatcherWorker`.
- Backend account/session/device tables.
- Backend SignalR support.
- Backend feed service through `InstagramFeedService`.
- GetIGFeed already has a session/proxy pool and `/api/user-feed` endpoint.

The missing piece is a reliable bridge that lets the backend delegate feed work
to an active Android device and wait for the result.

## Recommended High-Level Flow

```text
External client
  |
  v
GetIGFeed /api/user-feed
  |
  | signed internal request
  v
FeedPilot /api/bridge/feed
  |
  | select active device + valid IG account
  v
FeedBridgeJob
  |
  | poll or SignalR dispatch
  v
Android FeedPilot app
  |
  | InstagramRepository executes real feed call
  v
FeedPilot backend stores result
  |
  v
GetIGFeed receives normalized response
```

## Design Principle

Instagram session secrets should remain owned by FeedPilot and the Android app.
GetIGFeed should receive feed data, not raw Instagram cookies.

Avoid this:

```text
Android -> FeedPilot -> GetIGFeed -> Instagram
```

Prefer this:

```text
GetIGFeed -> FeedPilot -> Android -> Instagram -> FeedPilot -> GetIGFeed
```

## MVP Transport Choice

Use polling first.

Polling is simpler to build, easier to debug on Android, and works reliably
across mobile networks, battery optimization, proxy networks, and containerized VPS hosting. SignalR can be added later as a latency optimization because the backend
already has SignalR configured.

Recommended MVP polling cadence:

- Heartbeat: every 30 seconds while bridge runner is enabled.
- Job poll: every 3 to 5 seconds while app/service is active.
- Feed job timeout: 25 to 35 seconds.
- Session validation TTL: 5 to 10 minutes.

## Backend Components

### 1. Bridge Controller

Create a backend controller:

```text
FeedPilot.Api/Controllers/BridgeFeedController.cs
```

Responsibilities:

- Accept feed requests from GetIGFeed.
- Authenticate internal bridge requests.
- Normalize request input.
- Select an eligible Android device/account.
- Create a bridge job.
- Wait for completion or timeout.
- Return the Android result or a controlled error.

Primary endpoint:

```http
POST /api/bridge/feed
```

Request:

```json
{
  "requestId": "external-request-id",
  "username": "instagram_username",
  "userId": "123456789",
  "limit": 12,
  "maxId": null,
  "includeStories": false,
  "client": "GetIGFeed"
}
```

Response:

```json
{
  "success": true,
  "source": "android-device",
  "jobId": "uuid",
  "deviceId": "device-id",
  "accountUsername": "logged_in_account",
  "durationMs": 1840,
  "data": {
    "profile": {},
    "feed": {}
  }
}
```

Failure response:

```json
{
  "success": false,
  "code": "NO_ACTIVE_DEVICE",
  "message": "No active Android device with a valid Instagram session is available."
}
```

### 2. Device Job Controller

Create a controller for Android devices:

```text
FeedPilot.Api/Controllers/BridgeJobsController.cs
```

Endpoints:

```http
POST /api/bridge/devices/heartbeat
GET  /api/bridge/jobs/poll
POST /api/bridge/jobs/{id}/complete
POST /api/bridge/jobs/{id}/fail
```

All Android bridge endpoints should use the normal app JWT plus request signing.
They should also verify `X-Device-Id`, `X-App-Id`, and `X-Installation-Id`.

### 3. Bridge Job Service

Create:

```text
FeedPilot.Api/Services/FeedBridgeService.cs
```

Responsibilities:

- Select an eligible device/account.
- Create bridge jobs.
- Mark jobs assigned/completed/failed/timed out.
- Apply device/account cooldowns.
- Expose job wait logic for `/api/bridge/feed`.

### 4. Optional Background Sweeper

Create:

```text
FeedPilot.Api/Services/FeedBridgeJobSweepService.cs
```

Responsibilities:

- Mark expired jobs as timed out.
- Release stuck account/device locks.
- Clear stale `IsBusy` state.
- Apply cooldown to devices that repeatedly accept jobs but do not complete.

## Database Model

### FeedBridgeDeviceState

Tracks whether an Android app installation is online and bridge-capable.

```text
Id
UserId
AppId
DeviceId
InstallationId
AppInstanceId
DeviceModel
AndroidVersion
AppVersion
LastSeenAt
IsOnline
BridgeEnabled
BatteryLevel
NetworkType
ActiveAccountUsername
LoggedInAccountsJson
CapabilitiesJson
FailureCount
CooldownUntil
CreatedAt
UpdatedAt
```

Indexes:

```text
(AppId, DeviceId)
(InstallationId)
(LastSeenAt)
(BridgeEnabled, LastSeenAt)
```

### FeedBridgeAccountState

Tracks Instagram account eligibility for bridge execution.

```text
Id
AccountId
UserId
AppId
DeviceId
InstallationId
InstagramUsername
InstagramUserId
Status
LastValidatedAt
LastUsedAt
IsBusy
FailureCount
CooldownUntil
LastErrorCode
LastErrorMessage
CreatedAt
UpdatedAt
```

Recommended statuses:

```text
Active
SessionExpired
Checkpoint
Captcha
RateLimited
TemporaryRestricted
Disabled
Unknown
```

Indexes:

```text
(Status, IsBusy, CooldownUntil)
(AppId, DeviceId)
(AccountId)
(InstagramUsername)
```

### FeedBridgeJob

Represents one requested feed execution.

```text
Id
ExternalRequestId
ClientName
TargetUsername
TargetUserId
Limit
MaxId
IncludeStories
Status
AssignedAccountId
AssignedDeviceId
AssignedInstallationId
RequestJson
ResponseJson
ErrorCode
ErrorMessage
Attempt
CreatedAt
AssignedAt
ExpiresAt
CompletedAt
UpdatedAt
```

Recommended statuses:

```text
Pending
Assigned
Running
Completed
Failed
TimedOut
Cancelled
```

Indexes:

```text
(Status, ExpiresAt)
(AssignedDeviceId, Status)
(ExternalRequestId)
(CreatedAt)
```

## Device Selection Algorithm

Eligibility filters:

1. Device has `BridgeEnabled = true`.
2. Device `LastSeenAt` is within the online threshold, usually 60 seconds.
3. Device is not in cooldown.
4. Account status is `Active`.
5. Account `LastValidatedAt` is fresh or the device can validate before running.
6. Account is not busy.
7. Account is not in cooldown.

Selection order:

```text
lowest account FailureCount
then oldest LastUsedAt
then oldest device LastSeenAt within active window
then random tie breaker
```

This avoids hammering one account repeatedly and spreads requests across the
pool.

## Android App Components

### 1. Bridge Runner

Create:

```text
android-client/app/src/main/java/com/feedpilot/client/bridge/FeedBridgeRunner.kt
```

Responsibilities:

- Send heartbeat.
- Poll for bridge jobs.
- Run one job at a time per installation.
- Use existing `InstagramRepository`.
- Report success/failure.
- Respect backend cooldown and enabled/disabled flags.

### 2. Bridge Worker

Create a WorkManager worker or foreground-capable service:

```text
android-client/app/src/main/java/com/feedpilot/client/worker/FeedBridgeWorker.kt
```

MVP behavior:

- Runs periodically.
- Sends heartbeat.
- Polls one job.
- Executes it.
- Completes it.

If low latency is important, add a foreground service later for active bridge
mode.

### 3. Android API Methods

Add to `ApiService.kt`:

```kotlin
@POST("api/bridge/devices/heartbeat")
suspend fun sendBridgeHeartbeat(@Body body: BridgeHeartbeatRequest): BridgeHeartbeatResponse

@GET("api/bridge/jobs/poll")
suspend fun pollBridgeJob(
    @Query("deviceId") deviceId: String,
    @Query("installationId") installationId: String
): BridgeJobDto?

@POST("api/bridge/jobs/{id}/complete")
suspend fun completeBridgeJob(
    @Path("id") id: String,
    @Body body: CompleteBridgeJobRequest
): BridgeJobResultDto

@POST("api/bridge/jobs/{id}/fail")
suspend fun failBridgeJob(
    @Path("id") id: String,
    @Body body: FailBridgeJobRequest
): BridgeJobResultDto
```

### 4. Android Execution Logic

For a feed job:

```text
1. Resolve local active account/session.
2. If backend assigned a specific account, switch/select that account.
3. Validate session if stale.
4. Resolve target profile:
   instagram.getUserProfileDetails(username)
5. Fetch target feed:
   instagram.getUserFeed(profile.id or targetUserId, maxId)
6. Return profile + feed payload to backend.
```

If session validation fails, report a specific failure code so the backend can
mark the account correctly.

## GetIGFeed Integration

Add an adapter service:

```text
D:\GetIGFeed\src\services\feedPilotBridge.service.js
```

Environment variables:

```text
FEEDPILOT_BRIDGE_ENABLED=true
FEEDPILOT_BRIDGE_URL=https://feedpilot-api.example.com
FEEDPILOT_BRIDGE_KEY=strong-shared-secret
FEEDPILOT_BRIDGE_TIMEOUT_MS=35000
FEEDPILOT_BRIDGE_FALLBACK=true
```

Modify:

```text
D:\GetIGFeed\src\controllers\userFeed.controller.js
```

Recommended flow:

```text
1. Receive /api/user-feed request.
2. If bridge enabled and request is first page:
   call FeedPilot /api/bridge/feed.
3. If bridge succeeds:
   return bridge result after mapping to GetIGFeed response shape.
4. If bridge fails with retryable error and fallback enabled:
   use existing GetIGFeed pool/fallback path.
5. If bridge fails with non-retryable error:
   return controlled error.
```

Retryable bridge errors:

```text
NO_ACTIVE_DEVICE
NO_ACTIVE_SESSION
JOB_TIMEOUT
DEVICE_OFFLINE
TEMPORARY_NETWORK_ERROR
```

Non-retryable bridge errors:

```text
INVALID_TARGET
PRIVATE_PROFILE_NOT_ACCESSIBLE
TARGET_NOT_FOUND
BRIDGE_AUTH_FAILED
```

## Authentication And Security

Use two different auth layers:

### GetIGFeed to FeedPilot

Use an internal bridge key or signed request.

Minimum viable headers:

```http
X-Bridge-Client: GetIGFeed
X-Bridge-Timestamp: 2026-09-10T12:00:00Z
X-Bridge-Signature: hmac-sha256(...)
```

Signature payload:

```text
METHOD + "\n" + PATH + "\n" + TIMESTAMP + "\n" + BODY_SHA256
```

Reject when:

- Timestamp skew is greater than 5 minutes.
- Signature is invalid.
- Client is unknown.
- Request id was already consumed, if replay protection is enabled.

### Android to FeedPilot

Use existing app JWT and request signing. Also verify device identity headers:

```http
Authorization: Bearer <jwt>
X-App-Id
X-Device-Id
X-Hardware-Id
X-Installation-Id
X-Signature
```

## Error Codes

Bridge-level errors:

```text
BRIDGE_DISABLED
BRIDGE_AUTH_FAILED
INVALID_REQUEST
NO_ACTIVE_DEVICE
NO_ACTIVE_SESSION
JOB_TIMEOUT
JOB_CANCELLED
DEVICE_OFFLINE
DEVICE_BUSY
ACCOUNT_BUSY
```

Instagram/account errors:

```text
IG_SESSION_EXPIRED
IG_CHECKPOINT_REQUIRED
IG_CAPTCHA_REQUIRED
IG_RATE_LIMITED
IG_TEMPORARY_RESTRICTED
IG_TARGET_NOT_FOUND
IG_PRIVATE_PROFILE
IG_EMPTY_FEED
IG_UNKNOWN_ERROR
```

Network errors:

```text
NETWORK_TIMEOUT
NETWORK_DNS_FAILED
NETWORK_PROXY_FAILED
NETWORK_INSTAGRAM_UNREACHABLE
```

## Timeout Strategy

Recommended initial values:

```text
FeedPilot waits for Android job: 30 seconds
GetIGFeed waits for FeedPilot: 35 seconds
Android Instagram call timeout: 20 seconds
Job expiry: 40 seconds
Device online threshold: 60 seconds
Session validation TTL: 10 minutes
```

Important rule:

GetIGFeed timeout must be slightly longer than FeedPilot job wait timeout.

## Cooldown Strategy

Apply cooldowns to protect accounts and avoid repeated bad routing.

Suggested cooldowns:

```text
IG_RATE_LIMITED: 30 to 60 minutes
IG_CHECKPOINT_REQUIRED: until manual refresh
IG_CAPTCHA_REQUIRED: until manual refresh
IG_SESSION_EXPIRED: until re-login/refresh
NETWORK_TIMEOUT: 2 to 5 minutes
DEVICE_OFFLINE after assigned job: 5 minutes
```

## Response Normalization

Android should return a raw-ish structured payload:

```json
{
  "profile": {
    "id": "123",
    "username": "target",
    "fullName": "Target User",
    "profilePicUrl": "...",
    "followerCount": 100,
    "followingCount": 20,
    "mediaCount": 12,
    "isPrivate": false,
    "isVerified": false
  },
  "feed": {
    "items": []
  }
}
```

FeedPilot can store this as `ResponseJson`.

GetIGFeed should map it to its current `/api/user-feed` shape so external
clients do not need to change immediately.

## Observability

Log these events:

```text
bridge.request.received
bridge.device.selected
bridge.job.created
bridge.job.polled
bridge.job.running
bridge.job.completed
bridge.job.failed
bridge.job.timeout
bridge.fallback.used
```

Metrics to track:

```text
active devices
active sessions
jobs per minute
success rate
timeout rate
average duration
failures by error code
account cooldown count
device cooldown count
```

Admin dashboard should show:

- Active bridge devices.
- Last heartbeat time.
- Logged-in Instagram accounts per device.
- Current job.
- Recent jobs.
- Failure/cooldown reason.
- Toggle to enable/disable bridge per device.

## Rollout Plan

### Phase 1: Backend Job Model

- Add database entities and schema migration/backfill.
- Add bridge request DTOs.
- Add `FeedBridgeService`.
- Add polling endpoints.
- Add job timeout sweeper.

### Phase 2: Android Polling Worker

- Add bridge DTOs to Android.
- Add API methods.
- Add `FeedBridgeRunner`.
- Add `FeedBridgeWorker`.
- Execute one feed job at a time.
- Return structured result.

### Phase 3: GetIGFeed Adapter

- Add `feedPilotBridge.service.js`.
- Add environment config.
- Call FeedPilot before current pool execution.
- Add fallback behavior.
- Add response mapper.

### Phase 4: Admin And Monitoring

- Add dashboard visibility.
- Add device/account enable toggles.
- Add recent job table.
- Add error counts.

### Phase 5: SignalR Upgrade

- Add bridge hub.
- Keep polling fallback.
- Dispatch jobs directly to connected devices.
- Use SignalR for low latency, polling for reliability.

## MVP Definition Of Done

The MVP is complete when:

1. Android devices send bridge heartbeats.
2. FeedPilot can list active bridge-capable devices.
3. GetIGFeed can call `POST /api/bridge/feed`.
4. FeedPilot creates a job and assigns it to an active Android device.
5. Android executes `InstagramRepository.getUserProfileDetails()` and
   `InstagramRepository.getUserFeed()`.
6. Android posts the result back to FeedPilot.
7. FeedPilot returns the result to GetIGFeed.
8. Failed/expired jobs are marked cleanly.
9. Account/device cooldown prevents repeated bad selection.
10. GetIGFeed can fallback to its existing pool when bridge has no active device.

## Suggested File Map

FeedPilot backend:

```text
backend/src/FeedPilot.Api/Controllers/BridgeFeedController.cs
backend/src/FeedPilot.Api/Controllers/BridgeJobsController.cs
backend/src/FeedPilot.Api/Services/FeedBridgeService.cs
backend/src/FeedPilot.Api/Services/FeedBridgeJobSweepService.cs
backend/src/FeedPilot.Api/Domain/Entities.cs
backend/src/FeedPilot.Api/Dtos/Dtos.cs
backend/src/FeedPilot.Api/Data/AppDbContext.cs
backend/src/FeedPilot.Api/Data/DatabaseSchemaMigrator.cs
```

FeedPilot Android:

```text
android-client/app/src/main/java/com/feedpilot/client/bridge/FeedBridgeRunner.kt
android-client/app/src/main/java/com/feedpilot/client/worker/FeedBridgeWorker.kt
android-client/app/src/main/java/com/feedpilot/client/data/remote/ApiService.kt
android-client/app/src/main/java/com/feedpilot/client/data/remote/dto/Dtos.kt
```

GetIGFeed:

```text
src/services/feedPilotBridge.service.js
src/controllers/userFeed.controller.js
src/utils/mapFeedPilotBridgeResponse.js
.env.example
API.md
```

## Final Recommendation

Build the polling bridge first. It gives the fastest path to a working,
observable system with low risk. Once the job model and Android execution path
are stable, add SignalR for faster assignment while keeping polling as the
fallback path.
