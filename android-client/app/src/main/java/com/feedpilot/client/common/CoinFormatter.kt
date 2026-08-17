package com.feedpilot.client.common

import java.util.Locale

/**
 * Formats coin values:
 * - Below 10,000: returns exact number string e.g. "9999", "500"
 * - 10,000 and above: masks to "10k", "10.5k", "1M", "1.5M", "1B", etc.
 */
fun Number.formatCoins(): String {
    val value = this.toLong()
    return when {
        value >= 1_000_000_000L -> {
            val d = value / 1_000_000_000.0
            if (d % 1.0 == 0.0) "${d.toLong()}B" else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.') + "B"
        }
        value >= 1_000_000L -> {
            val d = value / 1_000_000.0
            if (d % 1.0 == 0.0) "${d.toLong()}M" else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.') + "M"
        }
        value >= 10_000L -> {
            val d = value / 1_000.0
            if (d % 1.0 == 0.0) "${d.toLong()}k" else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.') + "k"
        }
        else -> value.toString()
    }
}
