# FeedPilot

FeedPilot is a full-stack social feed and engagement app with its own isolated
backend, mobile clients, deployment config, and release flow.

The repository contains:

- ASP.NET Core backend for auth, accounts, wallet/coins, referrals, app orders, updates, and watched Instagram handles.
- Native Android app built with Kotlin, Jetpack Compose, Hilt, Room, Retrofit, and WorkManager.
- SwiftUI iOS starter that targets the same backend API.
- Render deployment blueprint for the backend.

## Key Features

- Web login and direct Instagram login flows.
- Multiple Instagram accounts per device/user.
- Account upgrade flow and coin schema for FeedPilot users.
- App-order functionality only.
- Referral and coin transfer functionality.
- Auto-update metadata and APK release flow.
- In-app random activity timing and switching controls.
- Dark, light, and system theme support.
- Watched Instagram handle cards with scheduled background feed capture.
- Backend hosted on Render using ASP.NET Core.

## Layout

```text
FeedPilot/
  android-client/       Kotlin Android app
  backend/              ASP.NET Core API and dashboard static assets
  ios-client/           SwiftUI iOS starter
  docs/                 Current FeedPilot docs
  render.yaml           Render blueprint
```

## Build

Backend:

```powershell
dotnet build backend\FeedPilot.sln
```

Android debug APK:

```powershell
cd android-client
.\gradlew.bat assembleDebug
```

Android release APK:

```powershell
cd android-client
.\gradlew.bat assembleRelease
```

Current release artifact path:

```text
android-client/app/build/outputs/apk/release/app-release.apk
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [API](docs/API.md)
- [Build](docs/BUILD.md)
- [Deployment](docs/DEPLOYMENT.md)
- [iOS](docs/IOS.md)

## Security Notes

Set production secrets through Render environment variables. Do not commit production
JWT secrets, request signing secrets, SMM panel API keys, database URLs, or release keystores.
