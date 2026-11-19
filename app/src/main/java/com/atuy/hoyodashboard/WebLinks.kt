package com.atuy.hoyodashboard

import java.net.URI
import java.util.Locale

internal fun webLink(value: String, relativeToHoYoLab: Boolean = false): String? {
    val trimmed = value.trim()
    val absolute = when {
        trimmed.startsWith("//") -> "https:$trimmed"
        relativeToHoYoLab && trimmed.startsWith("/") -> "https://www.hoyolab.com$trimmed"
        else -> trimmed
    }
    val uri = runCatching { URI(absolute) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http") ||
        uri.host.isNullOrBlank() || uri.userInfo != null
    ) return null
    return uri.toASCIIString()
}

internal fun isImageLink(value: String): Boolean {
    val path = runCatching { URI(value).path.orEmpty() }.getOrDefault("")
    return path.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp", "gif", "avif", "bmp")
}

internal val embeddedWebLink = Regex("""(?i)https?://[^\s<>"「」『』]+""")

internal fun likelyForeignText(text: String): Boolean {
    if (text.any { it in '\u3040'..'\u30ff' } || webLink(text) != null) return false
    if (text.matches(Regex("[A-Z0-9_.%+★☆-]+")) && (text.length <= 4 || '_' in text || text.any(Char::isDigit))) return false
    return Regex("[A-Za-z]{3,}").containsMatchIn(text) ||
        text.count { it in '\u4e00'..'\u9fff' } >= 8 ||
        text.any { it in '\u0400'..'\u052f' || it in '\uac00'..'\ud7af' || it in '\u0e00'..'\u0e7f' }
}
