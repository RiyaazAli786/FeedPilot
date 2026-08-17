package com.feedpilot.client.common

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Parses an ISO-8601 UTC timestamp as the backend sends it (`yyyy-MM-ddTHH:mm:ss[.fff][Z]`) to
 * epoch millis, or null if it doesn't parse.
 */
fun parseIsoInstantMillis(iso: String): Long? = runCatching {
    val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    // Trim fractional seconds and a trailing 'Z'/offset the backend may include.
    val cleaned = iso.substringBefore('.').removeSuffix("Z")
    fmt.parse(cleaned)?.time
}.getOrNull()

/**
 * Whether an `UpgradedAt` timestamp (ISO-8601 UTC, as returned by the backend) is still within
 * its rolling 24h window — the single source of truth for "is this account currently upgraded"
 * on the client. Mirrors the backend's own `DateTime.UtcNow - upgradedAt < TimeSpan.FromHours(24)`
 * check in AccountsController.Upgrade / TasksController.SubmitResult, so the account naturally
 * reads as un-upgraded again once 24h have passed, without any extra bookkeeping.
 */
fun isUpgradedWithin24h(upgradedAtIso: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (upgradedAtIso.isNullOrBlank()) return false
    val parsed = parseIsoInstantMillis(upgradedAtIso) ?: return false
    return nowMillis - parsed < 24L * 60 * 60 * 1000
}

/**
 * Whether a locally recorded epoch-millis timestamp (e.g. `AccountEntity.profileTaskCompletedAtMs`)
 * falls within the last rolling 24h — the same window the backend uses for `UpgradedAt`. Used to
 * tell "the profile/bio task was verified done through this app today" apart from "the account
 * merely happens to already have a bio/photo on Instagram", which says nothing about *when* it
 * was set and would otherwise let the checklist item read as complete forever.
 */
fun isWithinLast24h(atMillis: Long?, nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (atMillis == null) return false
    return nowMillis - atMillis < 24L * 60 * 60 * 1000
}
