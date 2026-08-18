package com.feedpilot.client.common

/** App-wide constants. Base URL is injected from BuildConfig per build type. */
object Constants {
    const val DATABASE_NAME = "feedpilot.db"
    const val SECURE_PREFS = "feedpilot_secure_prefs"
    const val SETTINGS_STORE = "feedpilot_settings"

    /** 5 coins = ₹1 INR (mirrors the backend exchange rate). */
    const val COINS_PER_RUPEE = 5
    const val MIN_WITHDRAWAL_COINS = 500L

    const val SYNC_WORK_NAME = "task_sync_work"
    const val UPDATE_WORK_NAME = "update_check_work"

    /** Notification channel for the foreground order runner. */
    const val RUNNER_CHANNEL_ID = "task_runner"

    /** Notification channel for the foreground upgrade-checklist runner. */
    const val UPGRADE_RUNNER_CHANNEL_ID = "upgrade_runner"

    /** Notification channel for verified APK update prompts. */
    const val UPDATE_CHANNEL_ID = "app_updates"

    /** Notification channel for incoming coin transfers landing in this wallet. */
    const val WALLET_CHANNEL_ID = "wallet_transfers"

    /** Notification channel for CSV account login imports. */
    const val CSV_IMPORT_CHANNEL_ID = "csv_account_import"
}
