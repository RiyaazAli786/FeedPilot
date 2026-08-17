# FeedPilot Android

Native Android app built with Kotlin, Jetpack Compose, Hilt, Room, Retrofit, and WorkManager.

## Build

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

Set your Android SDK path in local-only `local.properties`:

```properties
sdk.dir=C\:\\Program Files (x86)\\Android\\android-sdk
```

## Backend URL

`API_BASE_URL` is configured per build type in `app/build.gradle.kts`.

Current placeholder:

```text
https://feedpilot-api-ount.onrender.com/
```

## Main Features

- web login and direct Instagram login
- multiple account cards and account switching
- upgrade flow
- app orders
- wallet, transfer, referral, withdrawal
- automatic APK update checks
- dark/light/system theme
- in-app random activity timing and switching controls
- watched Instagram handles with scheduled feed capture

## Generated APKs

Generated APKs are ignored by Git.

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

## Signing

Use your own release keystore for production. Keystores are ignored by Git.
