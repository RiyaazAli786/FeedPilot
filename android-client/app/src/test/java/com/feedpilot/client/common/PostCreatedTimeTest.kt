package com.feedpilot.client.common

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class PostCreatedTimeTest {
    @Test
    fun usesInstagramEpochSecondsAndLocalTimezone() {
        val originalZone = TimeZone.getDefault()
        val originalLocale = Locale.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT+05:30"))
            Locale.setDefault(Locale.US)
            assertEquals("Created: 01 Jan 2024, 05:30 GMT+05:30", formatPostCreatedTime(1704067200L))
        } finally {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun missingTimestampDoesNotShowFetchTime() {
        assertEquals("Created time unavailable", formatPostCreatedTime(0L))
        assertEquals("Created time unavailable", formatPostCreatedTime(-1L))
        assertEquals("Created time unavailable", formatPostCreatedTime(Long.MAX_VALUE))
    }
}
