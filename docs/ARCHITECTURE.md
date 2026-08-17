# FeedPilot Architecture

FeedPilot is split into independent services and clients:

- `backend/src/FeedPilot.Api`: ASP.NET Core 9 API, EF Core, JWT auth, static dashboard assets, Render-ready Dockerfile.
- `android-client`: Kotlin Android app using MVVM, Compose, Room, Retrofit, Hilt, WorkManager.
- `ios-client`: SwiftUI starter sharing backend API contracts.

## Backend Modules

- Auth: register/login/refresh/device auth/backup restore.
- Accounts: multiple Instagram account cards and session storage.
- Wallet: coin balance, transactions, withdrawals, transfers.
- Orders: app-created orders, pricing, claiming, worker progress.
- Referral: referral code, bonus, multi-level stats.
- Subscription/upgrade: paid plan request and approval flow.
- Updates: APK metadata and release center.
- Watched handles: user-owned Instagram handles and saved feed post IDs.

## Android Modules

- Login/connect: web login and direct login flows.
- Accounts: multiple account management and switching.
- Orders/tasks: app order creation and runner execution.
- Settings: theme, update checks, backup/restore, local random activity controls.
- Feed watcher: periodic WorkManager job that fetches watched profile feeds and saves post IDs.

## Watched Handle Flow

1. User adds an Instagram handle in the app.
2. App stores it as a card through `POST /api/watched-handles`.
3. `FeedWatcherWorker` runs periodically.
4. Worker uses an active Instagram session to resolve profile/feed data.
5. Worker posts captured feed items to `POST /api/watched-handles/{id}/feed`.
6. Backend upserts posts using unique `(WatchedInstagramHandleId, PostId)`.
