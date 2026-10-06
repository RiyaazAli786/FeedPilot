package com.feedpilot.client.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Instagram timestamps are epoch seconds; show the date/time in the device's timezone. */
fun formatPostCreatedTime(takenAt: Long): String =
    if (takenAt <= 0L || takenAt > Long.MAX_VALUE / 1000L) "Created time unavailable"
    else "Created: " + SimpleDateFormat("dd MMM yyyy, HH:mm z", Locale.getDefault())
        .format(Date(takenAt * 1000L))
