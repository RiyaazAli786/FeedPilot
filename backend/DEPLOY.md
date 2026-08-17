# Deploying the FeedPilot API to Render

The backend is production-ready: PostgreSQL, secrets from environment variables, `$PORT`
binding, proxy/TLS headers, and no seeded or mock data.

## Database first (Render free tier allows only ONE free Postgres per account)

The blueprint does **not** create a database — Render rejects a second free one with
*"cannot have more than one active free tier database."* Pick one:

- **Reuse your existing free database** (simplest). The app creates its own tables
  (`Users`, `AppOrders`, …) inside it; they won't clash with another app's tables.
- **Or** delete the old free database (if unused) and **New → PostgreSQL** to make a fresh one.

Either way, open that database in Render and copy its **Internal Database URL** (starts with
`postgres://`). You'll paste it as `DATABASE_URL` below.

## Option A — Blueprint (recommended)

The repo ships a [`render.yaml`](../render.yaml) that provisions **the web service** and asks
you for the database URL.

1. Push this repo to GitHub/GitLab.
2. In Render: **New → Blueprint**, select the repo, **Apply**.
3. When prompted, set **`DATABASE_URL`** to the Internal Database URL from the step above.
4. Render builds `backend/Dockerfile` and wires:
   - `Jwt__Secret`, `Admin__ApiKey` → strong random values (auto-generated once)
   - `ASPNETCORE_ENVIRONMENT=Production`
5. When live, visit `https://<your-service>.onrender.com/` → `{"service":"FeedPilot API","status":"ok"}`.

## Option B — Manual

1. **New → PostgreSQL** (free plan). Copy its **Internal Connection URL**.
2. **New → Web Service** → your repo → **Docker**.
   - Root Directory: `backend`
   - Dockerfile Path: `Dockerfile`
3. Add environment variables:

   | Key | Value |
   |-----|-------|
   | `ASPNETCORE_ENVIRONMENT` | `Production` |
   | `DATABASE_URL` | the Postgres Internal URL from step 1 |
   | `Jwt__Secret` | a random string ≥ 32 chars |
   | `Admin__ApiKey` | a random string (guards the dashboard) |

4. Deploy.

## After it's live

- **Dashboard:** `https://<service>.onrender.com/dashboard` — enter the `Admin__ApiKey`
  value (auto-connect is dev-only; production always asks for the key).
- **Android app:** point the release build's `API_BASE_URL` at
  `https://<service>.onrender.com/` in `android-client/app/build.gradle.kts`.

## Notes

- **Database schema** is created on first boot via `EnsureCreated()`. This is fine for the
  initial deploy. For later schema changes, add EF Core migrations and switch the startup call
  to `db.Database.Migrate()` — otherwise new columns won't be added to an existing database.
- **Config comes from env vars.** `Jwt__Secret` and `Admin__ApiKey` are never in source; the
  app refuses to start in Production on the placeholder secret.
- **Free Postgres** on Render expires after 90 days and the free web service sleeps when idle —
  fine for testing; use paid plans for real traffic.
- The `DATABASE_URL` may be a `postgres://…` URL; the app converts it to the Npgsql form
  automatically (see `Services/ConnectionStringHelper.cs`).
