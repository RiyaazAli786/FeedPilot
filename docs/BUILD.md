# Build

## Requirements

- .NET SDK 9
- Android SDK and JDK 17
- Gradle wrapper from `android-client`
- macOS with Xcode for iOS builds

## Backend

```powershell
dotnet restore backend\FeedPilot.sln
dotnet build backend\FeedPilot.sln
dotnet run --project backend\src\FeedPilot.Api\FeedPilot.Api.csproj
```

## Android

Create `android-client/local.properties`:

```properties
sdk.dir=C\:\\Program Files (x86)\\Android\\android-sdk
```

Build debug:

```powershell
cd android-client
.\gradlew.bat assembleDebug
```

Build release:

```powershell
cd android-client
.\gradlew.bat assembleRelease
```

Release APK:

```text
android-client/app/build/outputs/apk/release/app-release.apk
```

## iOS

Open `ios-client/FeedPilot` files in an Xcode iOS app target named `FeedPilot`.
Use bundle id `com.feedpilot.client`.
