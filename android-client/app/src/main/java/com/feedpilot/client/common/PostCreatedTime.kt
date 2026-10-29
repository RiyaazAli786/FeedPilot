package com.feedpilot.client.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Instagram timestamps are epoch seconds; show the date/time in the device's timezone. */
fun formatPostCreatedTime(takenAt: Long): String =
    if (takenAt <= 0L || takenAt > Long.MAX_VALUE / 1000L) "Created time unavailable"
    else "Created: " + SimpleDateFormat("dd MMM yyyy, HH:mm z", Locale.getDefault())
        .format(Date(takenAt * 1000L))

/** "5 hours ago" style label for an epoch-seconds timestamp; null when the timestamp is unusable. */
fun formatTimeAgo(takenAt: Long, nowMillis: Long = System.currentTimeMillis()): String? {
    if (takenAt <= 0L || takenAt > Long.MAX_VALUE / 1000L) return null
    val seconds = ((nowMillis - takenAt * 1000L) / 1000L).coerceAtLeast(0L)
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        minutes < 1 -> "just now"
        hours < 1 -> "$minutes minute${if (minutes == 1L) "" else "s"} ago"
        hours < 48 -> "$hours hour${if (hours == 1L) "" else "s"} ago"
        else -> "${hours / 24} days ago"
    }
}
