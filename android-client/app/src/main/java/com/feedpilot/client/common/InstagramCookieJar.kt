package com.feedpilot.client.common

object InstagramCookieJar {
    private val cookieAttributes = setOf(
        "path",
        "domain",
        "expires",
        "max-age",
        "secure",
        "httponly",
        "samesite",
        "priority",
        "partitioned"
    )

    fun parseCookieHeader(header: String?): LinkedHashMap<String, String> {
        val cookies = LinkedHashMap<String, String>()
        if (header.isNullOrBlank()) return cookies
        header.split(";").forEach { putCookiePair(cookies, it) }
        return cookies
    }

    fun parseSetCookieHeaders(headers: List<String>): LinkedHashMap<String, String> {
        val cookies = LinkedHashMap<String, String>()
        headers.forEach { header ->
            putCookiePair(cookies, header.substringBefore(";"))
        }
        return cookies
    }

    fun join(cookies: Map<String, String>): String {
        val clean = LinkedHashMap<String, String>()
        cookies.forEach { (name, value) -> putCookiePair(clean, "$name=$value") }
        return clean.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun putCookiePair(cookies: MutableMap<String, String>, rawPair: String) {
        val parts = rawPair.trim().split("=", limit = 2)
        if (parts.size != 2) return

        val name = parts[0].trim()
        val value = parts[1].trim()
        if (!isCookieName(name)) return

        if (value.isBlank()) {
            cookies.remove(name)
        } else if (isCookieValue(value)) {
            cookies[name] = value
        }
    }

    private fun isCookieName(name: String): Boolean {
        if (name.isBlank() || name.startsWith("$")) return false
        if (name.any { it.isWhitespace() || it.code < 0x21 || it.code == 0x7f }) return false
        return name.lowercase() !in cookieAttributes
    }

    private fun isCookieValue(value: String): Boolean =
        value.none { it == ';' || it == '\r' || it == '\n' || it.code == 0x7f }
}
