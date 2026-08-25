# Order Processing Performance Improvement Plan

## Goal

Improve order-processing throughput without creating coin loss, duplicate rewards, or overaction on Instagram targets.

This plan is intentionally conservative. The current code has several safety mechanisms that must stay intact:

- one runner coroutine per account in `TaskRunnerService`
- per-order settlement mutex in `TaskRepository`
- local `pending_earnings` rows before backend confirmation
- backend-confirmed wallet/account reconciliation
- duplicate target protection through `account_logs`
- claim release when an account cannot fulfil an order
- no parallel Instagram actions for the same account

## Current Hot Path

Primary files:

- `android-client/app/src/main/java/com/feedpilot/client/service/TaskRunnerService.kt`
- `android-client/app/src/main/java/com/feedpilot/client/data/repository/TaskRepository.kt`
- `android-client/app/src/main/java/com/feedpilot/client/data/repository/AppOrderRepository.kt`
- `android-client/app/src/main/java/com/feedpilot/client/data/local/Daos.kt`
- `android-client/app/src/main/java/com/feedpilot/client/data/local/Entities.kt`

Current per-account loop:

1. Validate account session and network.
2. Sync runner settings.
3. Query daily action counts.
4. Claim local/backend work.
5. Reorder batch for selected mode/streaks.
6. For each task:
   - check local duplicate eligibility
   - execute Instagram action
   - apply local pending reward on success
   - report/settle backend progress
   - reconcile wallet/account coins
   - log success/failure
   - wait action delay or cooldown
7. Refresh wallet as fallback when live coin sync is disconnected.

## Bottlenecks

### 1. Missing Room Indexes

Hot queries frequently filter by `status`, `accountId`, `orderId`, `taskType`, `target`, and `timestampMs`. The entities currently have little or no indexing on these fields.

Impact:

- slower local claim selection as `tasks` grows
- slower duplicate checks as `account_logs` grows
- slower daily-limit checks
- more DB work on every runner pass

Risk level: Low.

### 2. Repeated Daily-Limit Counts

`TaskRunnerService` calls `accountLogDao.getDailyCountForAccountAction(...)` for each allowed action type every loop pass.

Impact:

- repeated aggregate queries even when no action has happened
- cost multiplies by account count and selected task types

Risk level: Low if cached carefully.

### 3. Claim Request Fanout In Random Mode

`TaskRepository.claimFromBackend(...)` sends one backend claim call per requested task type when Random mode allows multiple types. The calls are concurrent, which is better than sequential, but still multiplies backend load.

Impact:

- more HTTP requests per idle/pass cycle
- higher chance of server rate limiting
- more duplicate local fanout work if many accounts poll together

Risk level: Medium. Requires backend API support for best fix.

### 4. Per-Action Backend Settlement

Each backend order success reports progress immediately through `settleOrder(...)`.

Impact:

- one backend progress request per action
- wallet/account reconciliation work per action
- mutex serialization per order can become visible if multiple accounts work the same order

Risk level: Medium to High. This area directly affects coin safety.

### 5. Observed Count Lookup During Settlement

`observedTargetCount(...)` can add Instagram profile/post lookup calls for Follow/Like orders.

Impact:

- extra Instagram call after the real action
- slower completion loop
- more pressure on Instagram session/rate limits

Risk level: Medium. Backend progress accuracy depends on this data.

## Implementation Phases

## Phase 1: Low-Risk Local Performance

### 1. Add Room Indexes

Update `Entities.kt`:

- `tasks(status, createdAt)`
- `tasks(accountId, status, createdAt)`
- `tasks(orderId, status)`
- `tasks(accountId, taskType, status, createdAt)`
- `account_logs(accountId, target)`
- `account_logs(accountId, action, success, timestampMs)`
- `pending_earnings(orderId, accountId)`
- `pending_earnings(taskId)`

Add a Room migration and bump `AppDatabase` version.

Acceptance checks:

- `assembleDebug` passes
- migration test passes if available
- existing installed app upgrades without clearing data
- claiming tasks still returns expected rows

### 2. Cache Daily Counts Per Runner

In `TaskRunnerService.launchAccountRun(...)`:

- load daily counts once per account at runner start
- store counts in an in-memory map keyed by accountId/action
- increment the action count only after successful log insert
- refresh counts when date changes or every 5 minutes as a fallback

Do not use the cache across app restarts.

Acceptance checks:

- daily limits still stop exactly at configured limits
- changing task modes during a run still respects limits
- midnight/date boundary resets correctly

### 3. Clean Remaining Mojibake Comments

Some comments still contain replacement/mojibake characters in runner/repository files. Clean them while touching nearby code.

Acceptance checks:

- `rg -n "�|Â|â|ð|Ã" android-client/app/src/main/java android-client/app/build.gradle.kts` returns no meaningful matches

## Phase 2: Backend Claim Efficiency

### 4. Add Fair Multi-Type Claim API

Backend should accept multiple task types in one claim request and return a fair mix.

Suggested behavior:

- request body already has `taskTypes`; backend should distribute across those types in one transaction
- preserve per-device/account lease rules
- exclude recently released order ids
- never return an order whose remaining quantity is zero
- avoid returning several units of the same target to the same account if backend has enough alternatives

Client change:

- replace per-type concurrent claim fanout with one `claimOrders(...)` call for Random mode
- keep old fanout behind a fallback flag until backend behavior is verified

Acceptance checks:

- Random mode receives multiple task types when available
- one backend request per claim pass
- no increase in repeated target skips
- no rate-limit regression

## Phase 3: Settlement Optimization Without Coin Risk

### 5. Add Settlement Queue, Not Parallel Settlement

Do not parallelize settlement freely. Instead, queue completed backend-order tasks per `(orderId, accountId)` and flush safely.

Rules:

- local reward is still written immediately to `pending_earnings`
- completed tasks remain `Completed` until backend confirms
- one in-flight settlement per `orderId` remains enforced
- if flush fails, leave pending rows intact for retry
- never mark `Reported` before backend success

Start with a tiny delay window, for example 500-1000 ms, to coalesce quick same-order completions without delaying visible credit.

Acceptance checks:

- killing app after Instagram success but before backend response does not lose coins
- backend zero-award response does not clear pending earning incorrectly
- duplicate retry does not award twice
- multiple accounts on the same order do not under-credit either account

### 6. Use Batch Progress Only After Settlement Queue Is Proven

`AppOrderRepository.reportProgressBatch(...)` already exists. Use it only after Phase 5 proves local grouping is correct.

Batch constraints:

- group reports by order/account safely
- each item must include stable `clientTaskId` or equivalent idempotency key
- backend must return per-item worker coin award, wallet balance, account coins, and status
- client must reconcile each item independently

Acceptance checks:

- partial batch failure retries only failed items
- successful items are marked reported exactly once
- wallet balance remains monotonic except true backend/admin corrections

## Phase 4: Observed Count Throttling

### 7. Throttle `observedTargetCount(...)`

Reduce Instagram lookup frequency.

Safe options, in preferred order:

1. Fetch observed count only for the first report of an order if backend has no baseline.
2. Fetch observed count every N confirmed completions.
3. Fetch observed count only near completion.
4. Move observed-count verification to backend async job.

Acceptance checks:

- backend order progress remains accurate
- SMM start/remains updates still work
- no increase in NotFound/private false positives
- fewer Instagram profile/post detail calls in logs

## Safety Tests

Create focused tests around these cases:

1. Two accounts complete tasks for the same order at nearly the same time.
2. Backend progress call fails after local reward is added.
3. App process dies after Instagram success but before backend settlement.
4. Backend returns zero coins for a task with pending earning.
5. User stops runner with pending/assigned backend orders.
6. Same account receives same target from two different orders.
7. Daily limit is reached mid-run.
8. Random mode receives mixed task types.

Expected invariant:

- Instagram action happens at most once per `(accountId, taskType, canonicalTarget)`.
- Local pending reward is never cleared until backend confirms.
- Backend-confirmed coin response is the only source that finalizes payment.
- Order progress is reported monotonically and never compounded from already reported local rows.

## Metrics To Add Before Major Changes

Add lightweight timing logs around:

- claim duration
- local DB claim duration
- Instagram execute duration
- observed count duration
- progress report duration
- wallet reconciliation duration
- idle wait reason
- skipped duplicate/unfulfillable count

Recommended log prefix:

```text
runner_perf account=<id> order=<id> step=<step> ms=<duration> result=<result>
```

These logs should be controlled by a debug/runtime flag so release builds are not noisy.

## Rollout Order

1. Add indexes and migration.
2. Add runner performance timings.
3. Cache daily-limit counts.
4. Implement backend fair multi-type claim API and client fallback.
5. Add settlement queue with current single progress endpoint.
6. Enable progress batch endpoint only after settlement queue tests pass.
7. Throttle observed count lookups.

## Explicit Non-Goals

Do not:

- run multiple Instagram actions concurrently on the same account
- remove action delay/cooldown safeguards
- mark completed tasks as reported before backend confirmation
- clear pending earnings on failed or zero-award backend responses
- remove per-order settlement mutex
- depend only on local wallet totals for final coin accounting

## First PR Recommendation

Start with Phase 1 only:

- add Room indexes and migration
- cache daily action counts inside each active account runner
- add performance timing logs
- clean nearby mojibake comments

This gives measurable speed improvement with the lowest chance of coin/accounting regressions.
