# Android Social Engagement Client

## Overview

Android Social Engagement Client is a scalable Android application designed to manage multiple user accounts, synchronize with a centralized backend, track completed tasks, maintain a wallet system, and provide an automatic update mechanism.

The application is designed with scalability, security, and maintainability in mind.

---

# Features

## 1. Multi Account Management

### Features

- Login multiple accounts
- Save login session securely
- Restore sessions after application restart
- Logout individual account
- Remove account
- Switch active account
- Refresh expired session
- Store encrypted credentials
- Account profile information
- Session validation

### Account Information

Each account contains:

- Account ID
- Username
- Profile Picture
- Session Token
- Login Time
- Last Active
- Device ID
- Status

---

# 2. Task Execution System

The application communicates with a centralized backend server.

The backend provides pending tasks.

Example task types:

- Like
- Follow

Task Flow

```
App Starts

↓

Authenticate Device

↓

Download Pending Tasks

↓

Assign Task

↓

Execute Task

↓

Upload Result

↓

Receive Coin

↓

Fetch Next Task
```

Task information includes

- Task ID
- Order ID
- Account ID
- Task Type
- Target URL/Identifier
- Retry Count
- Status
- Timestamp

---

# 3. Coin System

Every successfully completed task rewards coins.

Example

| Action | Coin |
|---------|------|
| Success | 1 |

Wallet contains

- Total Coins
- Lifetime Coins
- Pending Coins
- Withdrawn Coins

---

# 4. Withdrawal System

Exchange Rate

```
5 Coins = ₹1 INR
```

Example

```
500 Coins

↓

₹100 Withdrawable
```

Withdrawal Request Fields

- User ID
- Wallet Balance
- Coins Requested
- Amount
- Payment Method
- UPI ID
- Bank Details
- Status

Withdrawal Status

- Pending
- Processing
- Approved
- Rejected
- Completed

---

# 5. Multi Instance Support

Application should support multiple cloned installations.

Each installation should generate

- Unique Device UUID
- Installation ID
- App Instance ID

Tracking Keys

```
Device ID
Installation ID
Android ID
Account ID
```

Backend should identify

```
Device

↓

Application Instance

↓

Logged Account

↓

Task History
```

---

## Original App Auto Restore, Clone Apps Fresh

FeedPilot uses a split identity policy:

- The original release package (`com.feedpilot.client`) is recoverable.
- Clone packages are disposable and do not restore after app data clear, uninstall, or reinstall.

The original app keeps the normal app id:

```text
appId = com.feedpilot.client
```

Its backend device account is keyed by the hardware-stable device identity, so when app-private
storage is cleared or the original app is reinstalled, device auth signs back into the same
server account and `AccountRepository.refreshFromServer()` repopulates local Room data.

Clone apps use a locally generated clone app id:

```text
appId = clone.<uuid>
```

That clone id is stored only inside the clone's private SharedPreferences. It is intentionally
not recovered from MediaStore or backup-code flows. If clone app data is cleared or the clone is
uninstalled/reinstalled, the clone generates a new app id, receives a fresh backend device
account, and starts with no restored wallet/accounts.

Runtime rules:

- `DeviceIdentity.isOriginalApp` is true only when the runtime package name is
  `com.feedpilot.client`.
- Original app: auto device-session recovery remains enabled and backup-code UI can be shown.
- Clone app: auto device auth still creates a fresh disposable account, but backup-code
  generation/restore is blocked and the restore UI is hidden.
- Request headers continue to send `X-App-Id`, `X-Device-Id`, and `X-Hardware-Id`; backend rows
  remain separated by app id/device id.

Original auto-restore order:

1. If secure tokens still exist, keep the current session.
2. If app-private data was cleared, read the newest `FeedPilot_Backup_Code_*.txt` from Downloads
   and restore that account automatically.
3. If no backup-code file exists, use passwordless device auth with the original app identity.

This order matters because generating a backup code converts the backend user from
`@device.feedpilot` to `@backup.feedpilot`. After that conversion, plain device auth can no
longer find the old user row, so the original app must try the saved backup code first.

Limit: some virtualization-style clone tools can report the same runtime package name as the
original app. Android does not provide a universal signal to distinguish those from the original
package. For reliable "clone has no restore" behavior, use clone tools that rewrite the package
name, or add a build-time/installer marker for clone distributions.

---

# 6. Auto Update

Application checks for updates periodically.

Flow

```
Launch App

↓

Check Version API

↓

Compare Version

↓

Download APK

↓

Verify SHA256

↓

Install Update
```

Update Features

- Silent download
- Progress indicator
- Resume download
- SHA256 verification
- Release notes
- Force update support
- Optional update support

---

# 7. Account Status

Each account should have live status.

Statuses

```
Active

Suspended

Checkpoint

Captcha

Verification Required

Session Expired

Login Failed

Disabled

Temporary Restricted

Unknown
```

Status should sync with backend.

Dashboard should show

- Green = Active
- Yellow = Warning
- Red = Suspended

---

# 8. Premium UI

Design Language

Material Design 3

Color Palette

- White
- Dark Gray
- Blue Accent

Animations

- Smooth page transitions
- Skeleton loading
- Lottie animations
- Pull to refresh

Responsive Layout

Supports

- Phones
- Tablets
- Foldables

---

# Screens

## Login Screen

Features

- Login
- Remember Session
- Add Account
- Session Restore

---

## Dashboard

Display

- Total Accounts
- Active Accounts
- Wallet
- Coins Today
- Completed Tasks
- Running Tasks
- Version
- Update Available

---

## Account Manager

Displays

- Username
- Status
- Last Login
- Coins Earned
- Remove
- Refresh Session

---

## Wallet

Display

- Balance
- Today's Coins
- Lifetime Coins
- Withdraw Button

---

## Withdrawal Screen

Input

- Coins
- UPI
- Bank
- Confirm

History

- Pending
- Completed
- Failed

---

## Settings

Options

- Device Information (Device Model & Device Name with Copy buttons & Toast feedback)
- Account Claim / Secure Coins
- Account Restore & Recovery
- History & Coin Transfer
- Check for Updates
- Support / Telegram Channel
- Delete Account
- Theme (System, Light, Dark)
- Notifications & Auto Update
- Clear Cache

---

## Action Log & Web Viewer

Features

- Live execution log per account with activity filtering (Follow, Like, Repost, Save, Comment)
- Target handle and post shortcode URL resolution
- **Action Log WebViewer (`ActionLogWebViewDialog`)**: "View" button for each log entry to open posts or profiles directly inside an embedded, full-featured WebView dialog.

---

## Premium Branding & Visual Aesthetics

- **Launcher Icon (`ic_launcher_*.xml`)**: 3D minted gold medallion with dual-beveled rim, star crown monogram, inner recessed coin face, and obsidian-gold gradient.
- **Splash Screen (`SplashScreen.kt`)**: Glassmorphic dark purple obsidian background, multi-ring pulsing gold emblem, smooth animated progress, and dynamic version label (`v2.0.39`).

---

# Backend Architecture

```
Android Apps

↓

REST API

↓

Authentication Service

↓

Task Queue

↓

Wallet Service

↓

Withdrawal Service

↓

Notification Service

↓

PostgreSQL

↓

Redis

↓

Admin Panel
```

---

# Suggested Technology Stack

## Android

- Kotlin
- Jetpack Compose
- MVVM
- Coroutines
- Hilt
- Room Database
- Retrofit
- WorkManager
- Coil

Backend

- ASP.NET Core 9
- Entity Framework Core
- PostgreSQL
- Redis
- SignalR

Storage

- Backblaze B2
- Cloudflare R2
- AWS S3

Authentication

- JWT
- Refresh Token

Hosting

- Docker
- Nginx
- Ubuntu Server

---

# Database Tables

## Users

- Id
- Name
- Email
- PasswordHash

---

## Accounts

- Id
- UserId
- Username
- SessionData
- Status
- LastLogin

---

## Tasks

- Id
- OrderId
- TaskType
- TargetId
- Status
- RetryCount

---

## Wallet

- Id
- UserId
- Coins
- LifetimeCoins

---

## WalletTransactions

- Id
- WalletId
- Coins
- Type
- CreatedDate

---

## Withdrawals

- Id
- WalletId
- Coins
- Amount
- Status

---

## Devices

- Id
- DeviceId
- InstallationId
- AndroidVersion
- AppVersion

---

# API Modules

Authentication

```
POST /login
POST /logout
POST /refresh
```

Accounts

```
GET /accounts
POST /accounts
DELETE /accounts/{id}
```

Tasks

```
GET /tasks
POST /tasks/result
```

Wallet

```
GET /wallet
GET /wallet/history
```

Withdraw

```
POST /withdraw
GET /withdraw/history
```

Update

```
GET /version
GET /apk/latest
```

---

# Security

- HTTPS Only
- JWT Authentication
- Certificate Pinning
- SQL Injection Protection
- XSS Protection
- AES Encryption
- Encrypted Local Storage
- Device Registration
- API Rate Limiting
- Request Signing

---

# Logging

Application Logs

- Login
- Logout
- Sync
- API Calls
- Errors
- Wallet
- Withdrawals
- Updates

Crash Reporting

- Firebase Crashlytics (or equivalent)

---

# Notifications

- Task Available
- Update Available
- Withdrawal Approved
- Wallet Updated
- Account Status Changed

---

# Estimated Development Timeline

| Module | Duration |
|----------|-----------|
| UI/UX | 2 Weeks |
| Authentication | 1 Week |
| Account Management | 2 Weeks |
| Task Engine | 2 Weeks |
| Wallet | 1 Week |
| Withdrawal | 1 Week |
| Backend APIs | 3 Weeks |
| Admin Panel | 2 Weeks |
| Testing | 2 Weeks |
| Deployment | 1 Week |

**Total:** 14–17 Weeks (approximately 3.5–4 months)

---

# Estimated Development Cost

| Team | Estimated Cost (INR) |
|------|----------------------:|
| Freelance Developer | ₹3,50,000 – ₹7,00,000 |
| Small Agency | ₹8,00,000 – ₹15,00,000 |
| Professional Software Company | ₹15,00,000 – ₹30,00,000+ |

Costs vary depending on scope, UI quality, backend infrastructure, testing, and maintenance.

---

# Future Enhancements

- Referral System
- Achievement Badges
- Multi-language Support
- Dark Mode
- Analytics Dashboard
- Push Notifications
- In-app Chat Support
- Offline Sync
- AI-based Risk Detection
- Remote Configuration
- Feature Flags
- Admin Analytics
- Role-based Access Control

---

# Project Structure

```
android-client/
│
├── app/
├── core/
├── data/
├── domain/
├── feature/
│   ├── login/
│   ├── dashboard/
│   ├── accounts/
│   ├── wallet/
│   ├── withdraw/
│   ├── settings/
│   └── updates/
├── network/
├── database/
├── ui/
├── common/
├── worker/
├── di/
└── build.gradle
```

---

# License

Private Proprietary Software.

Unauthorized copying, modification, distribution, or commercial use is prohibited.
