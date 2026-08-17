# FeedPilot API

Base URL for production should be your Render service URL, for example:

```text
https://feedpilot-api-ount.onrender.com/
```

All normal app endpoints use bearer JWT auth after device/login auth.

## Auth

- `POST /api/auth/device`
- `POST /api/auth/register`
- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/claim`
- `POST /api/auth/logout`
- `DELETE /api/auth/account`

## Accounts

- `GET /api/accounts`
- `POST /api/accounts`
- `DELETE /api/accounts/{id}`
- `POST /api/accounts/{id}/refresh`
- `POST /api/accounts/{id}/upgrade`
- `GET /api/accounts/check-duplicate`

## Orders

- `POST /api/orders`
- `GET /api/orders`
- `GET /api/orders/quote`
- `POST /api/orders/{id}/cancel`
- `POST /api/orders/processing/claim`
- `POST /api/orders/processing/{id}/progress`
- `POST /api/orders/processing/progress-batch`

## Wallet And Referral

- `GET /api/wallet`
- `GET /api/wallet/history`
- `POST /api/wallet/transfer`
- `GET /api/wallet/transfer/history`
- `GET /api/referral`
- `POST /api/referral/apply`

## Updates

- `GET /api/version`
- `GET /api/apk/latest`

## Admin Backup

Requires both `X-Admin-Session` and `X-Backup-Session`.

- `POST /api/admin/backup/database`

Backup request:

```json
{ "action": "backup" }
```

Restore request:

```json
{ "action": "restore", "backup": { "...": "backup payload from action=backup" } }
```

## Watched Handles

- `GET /api/watched-handles`
- `POST /api/watched-handles`
- `GET /api/watched-handles/{id}`
- `PATCH /api/watched-handles/{id}`
- `DELETE /api/watched-handles/{id}`
- `GET /api/watched-handles/{id}/posts`
- `POST /api/watched-handles/{id}/feed`

`POST /api/watched-handles/{id}/feed` saves feed post IDs idempotently.
