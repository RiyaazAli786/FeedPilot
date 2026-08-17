# FeedPilot Backend

ASP.NET Core 9 API for FeedPilot.

## Run Locally

```powershell
dotnet restore FeedPilot.sln
dotnet build FeedPilot.sln
dotnet run --project src\FeedPilot.Api\FeedPilot.Api.csproj
```

Swagger is available in Development at `/swagger`.

## Production Configuration

Set these through Render environment variables:

```text
ASPNETCORE_ENVIRONMENT=Production
Jwt__Secret=<strong random secret>
Admin__ApiKey=<admin dashboard secret>
DATABASE_URL=<Postgres internal URL>
```

Do not commit real secrets in `appsettings.json`.

## Main Modules

- JWT/device auth
- multiple Instagram accounts
- wallet and coin transactions
- app orders and worker progress
- referral tracking
- subscription/upgrade requests
- APK update metadata
- watched Instagram handles and saved feed post IDs
