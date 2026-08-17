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
Telegram__Enabled=true
Telegram__BotToken=<bot token from BotFather>
Telegram__ChatId=<numeric Telegram destination chat id>
Payments__UpiId=<UPI id>
Payments__PayeeName=<payee name>
SmmPanel__ApiKey=<panel key if enabled>
AssetStorage__Provider=B2
```

Telegram logging is already wired for backend API requests, app-reported Instagram calls, and client crash reports. Add the bot to your Telegram group/channel, send one message there, then open:

```text
https://api.telegram.org/bot<token>/getUpdates
```

Use the numeric `chat.id` value as `Telegram__ChatId`. Group ids usually start with `-100`. Keep `Telegram__BotToken` only in Render environment variables and rotate it in BotFather if it was shared anywhere.

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
