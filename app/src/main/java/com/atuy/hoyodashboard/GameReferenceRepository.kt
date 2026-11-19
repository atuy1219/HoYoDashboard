package com.atuy.hoyodashboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.LinkedHashMap

internal val gameReferenceRepository = GameReferenceRepository()

internal class GameReferenceRepository(
    private val download: (String) -> String? = ::downloadReferencePage
) {
    private val cache = object : LinkedHashMap<String, GameReferenceEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, GameReferenceEntry>?) =
            size > 128
    }

    suspend fun get(id: String): GameReferenceEntry? {
        val url = gameReferenceUrl(id) ?: return null
        synchronized(cache) { cache[url] }?.let { return it }
        return withContext(Dispatchers.IO) {
            val page = download(url) ?: return@withContext null
            val entry = parseGameReferencePage(url, page) ?: return@withContext null
            synchronized(cache) { cache[url] = entry }
            entry
        }
    }
}

internal fun parseGameReferencePage(url: String, body: String): GameReferenceEntry? {
    val page = Jsoup.parse(body, url)
    if (page.selectFirst("link[rel=canonical]")?.attr("abs:href") != url) return null
    val title = page.selectFirst("h1")?.text()?.takeIf { it.isNotBlank() } ?: return null
    val field = page.select("article.text-field").firstOrNull { article ->
        article.selectFirst(".text-field__role")?.text() in setOf("Description", "説明")
    } ?: return null
    val description = field.selectFirst(".gametext-source pre")?.wholeText()?.takeIf { it.isNotBlank() }
        ?: field.selectFirst(".gametext-rendered")?.html()?.takeIf { it.isNotBlank() }
        ?: return null
    return GameReferenceEntry(title, description.replace("\\n", "<br>").replace("\n", "<br>"), null)
}

private fun downloadReferencePage(url: String): String? {
    val connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("Accept", "text/html")
        setRequestProperty("Accept-Language", "ja-JP,ja")
    }
    try {
        val status = connection.responseCode
        if (status == HttpURLConnection.HTTP_NOT_FOUND) return null
        if (status != HttpURLConnection.HTTP_OK) throw IOException("HTTP $status")
        return connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size() + count > 2 * 1024 * 1024) throw IOException("Reference page too large")
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
    } finally {
        connection.disconnect()
    }
}
