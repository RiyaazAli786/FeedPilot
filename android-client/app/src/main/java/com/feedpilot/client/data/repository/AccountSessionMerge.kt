package com.feedpilot.client.data.repository

/** Backend snapshots have no session revision, so they cannot supersede a device session. */
internal fun mergeAccountSessionCookies(localCookies: String?, remoteCookies: String): String =
    localCookies?.takeIf { it.isNotBlank() } ?: remoteCookies
