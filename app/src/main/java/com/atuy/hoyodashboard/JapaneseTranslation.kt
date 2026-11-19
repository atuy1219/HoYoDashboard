package com.atuy.hoyodashboard

import android.util.LruCache
import android.text.Html
import android.text.style.URLSpan
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.google.android.gms.tasks.Task
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val LocalJapaneseTranslation = staticCompositionLocalOf { true }

private val translationCache = LruCache<String, String>(120)
private val translationRequests = Semaphore(2)

@Composable
internal fun JapaneseContent(value: String, style: TextStyle = MaterialTheme.typography.bodyMedium, modifier: Modifier = Modifier) {
    val enabled = LocalJapaneseTranslation.current
    val parsed = remember(value) { parseLinks(value) }
    val original = parsed.text
    val translation by produceState<TranslationResult?>(null, original, enabled) {
        this.value = null
        if (enabled && likelyForeignText(original)) {
            this.value = try {
                TranslationResult(translateJapanese(original))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                TranslationResult(original, failed = true)
            }
        }
    }
    Column(modifier) {
        val translated = if (enabled) translation?.text else null
        if (translated == null || translated == original) {
            val color = MaterialTheme.colorScheme.primary
            val handler = LocalUriHandler.current
            val annotated = remember(parsed, color, handler) {
                buildAnnotatedString {
                    append(original)
                    parsed.links.forEach { link ->
                        addLink(LinkAnnotation.Url(link.url, TextLinkStyles(style = SpanStyle(
                            color = color, textDecoration = TextDecoration.Underline
                        )), linkInteractionListener = { runCatching { handler.openUri(link.url) }; Unit }),
                            link.start, link.end)
                    }
                }
            }
            Text(annotated, style = style)
        } else {
            Text(translated, style = style)
            parsed.links.distinctBy { it.url }.forEach { link ->
                OpenWebLink(link.url, original.substring(link.start, link.end).takeIf { it != link.url } ?: "リンクを開く")
            }
        }
        if (enabled && translation?.failed == true) {
            Text("日本語訳を取得できませんでした", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun OpenWebLink(url: String, label: String = "リンクを開く") {
    val handler = LocalUriHandler.current
    TextButton(onClick = { runCatching { handler.openUri(url) } }) { Text(label) }
}

private data class TextLink(val start: Int, val end: Int, val url: String)
private data class LinkedText(val text: String, val links: List<TextLink>)

private fun parseLinks(value: String): LinkedText {
    val html = if ('<' in value || '&' in value) Html.fromHtml(
        value.replace(Regex("(?is)<(style|script)\\b[^>]*>.*?</\\1>"), ""),
        Html.FROM_HTML_MODE_COMPACT
    ) else null
    val text = html?.toString() ?: value
    val links = html?.getSpans(0, html.length, URLSpan::class.java).orEmpty().mapNotNull { span ->
        val start = html?.getSpanStart(span) ?: -1
        val end = html?.getSpanEnd(span) ?: -1
        webLink(span.url, relativeToHoYoLab = true)?.takeIf { start >= 0 && end > start }?.let { TextLink(start, end, it) }
    }.toMutableList()
    for (match in embeddedWebLink.findAll(text)) {
        val raw = match.value.trimEnd('.', ',', ')', '。', '、', '！', '）')
        val end = match.range.first + raw.length
        if (links.none { it.start < end && it.end > match.range.first }) {
            webLink(raw)?.let { links += TextLink(match.range.first, end, it) }
        }
    }
    return LinkedText(text, links.sortedBy { it.start })
}

private data class TranslationResult(val text: String, val failed: Boolean = false)

private suspend fun translateJapanese(text: String): String = translationRequests.withPermit {
    translationCache.get(text)?.let { return@withPermit it }
    val identifier = LanguageIdentification.getClient()
    val language = try {
        identifier.identifyLanguage(text.take(2_000)).awaitResult()
    } finally {
        identifier.close()
    }
    val source = TranslateLanguage.fromLanguageTag(language)
    if (source == null || source == TranslateLanguage.JAPANESE) {
        if (source == TranslateLanguage.JAPANESE) translationCache.put(text, text)
        return@withPermit text
    }
    val translator = Translation.getClient(TranslatorOptions.Builder()
        .setSourceLanguage(source).setTargetLanguage(TranslateLanguage.JAPANESE).build())
    try {
        translator.downloadModelIfNeeded().awaitResult()
        val translated = buildString {
            for ((index, paragraph) in text.split('\n').withIndex()) {
                if (index > 0) append('\n')
                for (segment in paragraph.chunked(3_000)) {
                    append(translator.translate(segment).awaitResult())
                }
            }
        }
        translationCache.put(text, translated)
        translated
    } finally {
        translator.close()
    }
}

private suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            when {
                task.isSuccessful -> continuation.resume(task.result)
                task.isCanceled -> continuation.cancel()
                else -> continuation.resumeWithException(task.exception ?: IllegalStateException())
            }
        }
    }
}
