package com.feedpilot.client.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val id: String,
    val username: String,
    val profilePictureUrl: String?,
    val status: String,
    val lastLogin: String?,
    val lastActive: String?,
    val coinsEarned: Long,
    val sessionCookies: String = "",
    /**
     * Whether [sessionCookies] was last confirmed to actually be signed in on instagram.com —
     * distinct from merely being non-blank. Drives the green-dot indicator on the account card
     * and which account the upgrade flow treats as "active". Local-only; the backend has no
     * opinion on Instagram login state.
     */
    val isLoggedIn: Boolean = false,
    /** ISO-8601 timestamp of this account's last successful upgrade, mirrored from the backend. */
    val upgradedAt: String? = null,
    /**
     * Epoch millis of the last time the "profile"/"bio" upgrade-checklist task was actually
     * verified through this app — local-only, the backend has no concept of these. Without this,
     * the checklist read a task as complete just because Instagram currently shows a photo/bio,
     * even if it was set months ago and the user never touched today's checklist; gating on a
     * fresh timestamp instead matches how "posts" (last-24h upload count) and "story" (inherently
     * expires after 24h) already behave. Must be preserved through any upsert built from a fresh
     * backend DTO (see AccountRepository) — the DTO carries no such field to copy from.
     */
    val profileTaskCompletedAtMs: Long? = null,
    val bioTaskCompletedAtMs: Long? = null,
    /**
     * "male" or "female" — which gendered asset pool (posts/profiles/stories/bios) this
     * account's upgrade tasks pull from. Local-only, set from the radio buttons on the Upgrade
     * panel; the backend has no concept of it, so it must be preserved through any upsert built
     * from a fresh backend DTO the same way profileTaskCompletedAtMs is (see AccountRepository).
     */
    val gender: String = "male"
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val id: String,
    val orderId: String,
    val accountId: String?,
    val taskType: String,
    val targetId: String,
    val status: String,
    val retryCount: Int,
    val rewardCoins: Int,
    val startCount: Int = 0,
    val commentText: String? = null,
    val serviceId: String? = null,
    val createdAt: String
)

@Entity(tableName = "wallet")
data class WalletEntity(
    @PrimaryKey val id: String = "wallet",
    val totalCoins: Long,
    val lifetimeCoins: Long,
    val pendingCoins: Long,
    val withdrawnCoins: Long,
    val updatedAt: String,
    /** The server's own totalCoins as of the last refresh — kept separately from [totalCoins]
     *  so WalletRepository.refreshFromServer() can tell "local is ahead because of a not-yet-
     *  confirmed optimistic credit" apart from "the server total itself dropped" (e.g. an admin
     *  deduction from the dashboard), which must never be shadowed by a locally-propped value. */
    val lastServerCoins: Long = 0
)

@Entity(tableName = "wallet_transactions")
data class WalletTransactionEntity(
    @PrimaryKey val id: String,
    val coins: Long,
    val type: String,
    val reference: String?,
    val createdDate: String
)

@Entity(tableName = "withdrawals")
data class WithdrawalEntity(
    @PrimaryKey val id: String,
    val coins: Long,
    val amount: Double,
    val paymentMethod: String,
    val status: String,
    val createdAt: String,
    val processedAt: String?
)

/** Per-account action log entry: records every follow/like success or failure. */
@Entity(tableName = "account_logs")
data class AccountLogEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val action: String,          // "Follow" | "Like"
    val target: String,          // username or post URL
    val success: Boolean,
    val message: String,         // outcome detail or error response
    val timestampMs: Long
)

/** Unconfirmed earned task rewards queued locally until server settlement is acknowledged. */
@Entity(tableName = "pending_earnings")
data class PendingEarningEntity(
    @PrimaryKey val id: String,
    val taskId: String,
    val orderId: String?,
    val accountId: String,
    val rewardCoins: Long,
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "watched_handles")
data class WatchedHandleEntity(
    @PrimaryKey val id: String,
    val username: String,
    val profilePictureUrl: String?,
    val fullName: String?,
    val isPrivate: Boolean,
    val followerCount: Long,
    val followingCount: Long,
    val mediaCount: Long,
    val watchEnabled: Boolean,
    val pollIntervalMinutes: Int,
    val lastFetchedAt: String?,
    val createdAt: String,
    val savedPostCount: Int
)

@Entity(
    tableName = "watched_posts",
    indices = [Index(value = ["watchedHandleId", "postId"], unique = true)]
)
data class WatchedPostEntity(
    @PrimaryKey val id: String,
    val watchedHandleId: String,
    val postId: String,
    val code: String?,
    val caption: String?,
    val mediaUrl: String?,
    val permalink: String?,
    val mediaType: Int,
    val likeCount: Long,
    val commentCount: Long,
    val takenAt: String?,
    val fetchedAt: String
)
