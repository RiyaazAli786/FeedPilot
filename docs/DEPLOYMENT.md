# Deployment

FeedPilot backend is deployed to a production Linux VPS with Docker, Nginx reverse proxy, PostgreSQL, and TLS certificates managed via Let's Encrypt.

## Production Setup

Target: `https://bridge.reelsflow.io` (port 5081 proxied by Nginx).

### Automated VPS Deployment

Use the deployment pipeline under `deploy/`:

```powershell
.\deploy-bridgefeed.bat
```

Or execute individual deployment stages:

```powershell
# Deploy backend Docker container and migrate database
.\deploy\deploy-backend.ps1

# Build and distribute Android release APK to server
.\deploy\deploy-android.ps1
```

### Environment Configuration

Required production environment variables:

```text
ASPNETCORE_ENVIRONMENT=Production
Jwt__Secret=<strong random secret>
Admin__ApiKey=<admin dashboard secret>
Admin__DashboardPasscode=<dashboard lock passcode>
Admin__BackupPasscode=<separate backup/restore key>
DATABASE_URL=postgresql://<user>:<password>@<host>:<port>/<database>
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
AssetStorage__Provider=Local
```

Telegram logging is wired for backend API requests, app-reported Instagram calls, and client crash reports. Add the bot to your Telegram group/channel, send one message there, then open:

```text
https://api.telegram.org/bot<token>/getUpdates
```

Use the numeric `chat.id` value as `Telegram__ChatId`. Group ids usually start with `-100`. Keep secrets in production environment files (`deploy/.deploy-secrets.env` or server `/var/www/bridgefeed/.env`) and never commit them to source control.

## Android API URL

Configured in `android-client/app/build.gradle.kts`:

```text
https://bridge.reelsflow.io/
```

## Auto Update

The Android Gradle task `prepareReleaseUpdate` generates release APK metadata:

```powershell
cd android-client
.\gradlew.bat prepareReleaseUpdate
```
