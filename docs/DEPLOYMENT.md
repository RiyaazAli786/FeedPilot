# Deployment

FeedPilot backend is prepared for Render using `render.yaml`.

## Render Setup

1. Create a new Render Blueprint from this repository.
2. Use the root `render.yaml`.
3. Set required environment variables.

Required production values:

```text
ASPNETCORE_ENVIRONMENT=Production
Jwt__Secret=<strong random secret>
Admin__ApiKey=<admin dashboard secret>
Admin__DashboardPasscode=<dashboard lock passcode>
Admin__BackupPasscode=<separate backup/restore key>
DATABASE_URL=<Render Postgres internal URL>
```

Optional values:

```text
RequestSigning__Secret=<same value embedded in app build>
Payments__UpiId=<UPI id>
Payments__PayeeName=<payee name>
SmmPanel__ApiKey=<panel key if enabled>
AssetStorage__Provider=B2
```

## Android API URL

Set `API_BASE_URL` in `android-client/app/build.gradle.kts` for debug, staging, and release.

Current placeholder:

```text
https://feedpilot-api-ount.onrender.com/
```

## Auto Update

The Android Gradle task `prepareReleaseUpdate` generates release APK metadata:

```powershell
cd android-client
.\gradlew.bat prepareReleaseUpdate
```
