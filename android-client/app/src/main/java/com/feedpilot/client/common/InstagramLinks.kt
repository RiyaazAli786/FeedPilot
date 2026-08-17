package com.feedpilot.client.common

/**
 * Pulls a display handle out of an Instagram order link.
 *
 * Links coming off the SMM admin queue carry tracking query strings (`?igsh=…`) and may be
 * post or reel URLs rather than profiles, so both are normalised here instead of showing
 * the raw URL in order history.
 */
fun extractInstagramHandle(link: String): String {
    val path = link.substringAfter("instagram.com/", "")
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
    if (path.isBlank()) return link.take(40)

    val segments = path.split('/')
    return when (segments.first()) {
        "p", "reel", "reels", "tv" -> segments.getOrNull(1) ?: path
        else -> segments.first()
    }
}
