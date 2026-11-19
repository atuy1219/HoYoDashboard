package com.atuy.hoyodashboard

import android.util.LruCache
import android.text.Html
import android.text.style.URLSpan
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
internal val LocalGameReferenceHandler = staticCompositionLocalOf<((GameTextReference) -> Unit)?> { null }

private val translationCache = LruCache<String, String>(120)
private val translationRequests = Semaphore(2)

@Composable
internal fun JapaneseContent(value: String, style: TextStyle = MaterialTheme.typography.bodyMedium, modifier: Modifier = Modifier) {
    val enabled = LocalJapaneseTranslation.current
    val gameReferenceHandler = LocalGameReferenceHandler.current
    val uriHandler = LocalUriHandler.current
    val parsed = remember(value) { parseLinks(value) }
    var missingReference by remember(value) { mutableStateOf<GameTextReference?>(null) }
    val translation by produceState<TranslationResult?>(null, parsed, enabled) {
        this.value = null
        if (enabled && likelyForeignText(parsed.text)) {
            this.value = try {
                TranslationResult(parsed.mapText(::translateFragment))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                TranslationResult(parsed, failed = true)
            }
        }
    }
    Column(modifier) {
        val displayed = if (enabled) translation?.content ?: parsed else parsed
        fun openLink(link: TextLink) {
            val id = gameReferenceId(link.url)
            if (id != null) {
                val reference = GameTextReference(id, link.label)
                if (gameReferenceHandler != null) gameReferenceHandler(reference)
                else {
                    val url = gameReferenceUrl(id)
                    if (url == null || runCatching { uriHandler.openUri(url) }.isFailure) missingReference = reference
                }
            } else runCatching { uriHandler.openUri(link.url) }
        }
        val color = MaterialTheme.colorScheme.primary
        val annotated = remember(displayed, color, uriHandler, gameReferenceHandler) {
            buildAnnotatedString {
                append(displayed.text)
                displayed.links.forEach { link ->
                    val styles = TextLinkStyles(style = SpanStyle(
                        color = color, textDecoration = TextDecoration.Underline
                    ))
                    if (gameReferenceId(link.url) != null) {
                        addLink(LinkAnnotation.Clickable(link.url, styles, linkInteractionListener = { openLink(link) }),
                            link.start, link.end)
                    } else {
                        addLink(LinkAnnotation.Url(link.url, styles, linkInteractionListener = { openLink(link) }),
                            link.start, link.end)
                    }
                }
            }
        }
        Text(annotated, style = style)
        if (enabled && translation?.failed == true) {
            Text("日本語訳を取得できませんでした", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
    }
    missingReference?.let { reference ->
        AlertDialog(
            onDismissRequest = { missingReference = null },
            title = { Text(reference.label.ifBlank { "参照先" }) },
            text = { Text("参照先の説明データがありません。") },
            confirmButton = { TextButton(onClick = { missingReference = null }) { Text("閉じる") } }
        )
    }
}

@Composable
internal fun OpenWebLink(url: String, label: String = "リンクを開く") {
    val handler = LocalUriHandler.current
    TextButton(onClick = { runCatching { handler.openUri(url) } }) { Text(label) }
}

private fun parseLinks(value: String): LinkedText {
    val markup = value.replace(Regex("(?is)<(style|script)\\b[^>]*>.*?</\\1>"), "")
    val source = if (markup.hasGameReferences()) markup.replace("\\n", "\n") else markup
    val html = if ('<' in source || '&' in source) Html.fromHtml(
        if (markup.hasGameReferences()) source.replace("\n", "<br>") else source,
        Html.FROM_HTML_MODE_COMPACT
    ) else null
    val text = html?.toString() ?: source
    val webLinks = html?.getSpans(0, html.length, URLSpan::class.java).orEmpty().mapNotNull { span ->
        val start = html?.getSpanStart(span) ?: -1
        val end = html?.getSpanEnd(span) ?: -1
        webLink(span.url, relativeToHoYoLab = true)?.takeIf { start >= 0 && end > start }
            ?.let { TextLink(start, end, it, text.substring(start, end)) }
    }
    val parsed = parseGameReferences(text, webLinks)
    val links = parsed.links.toMutableList()
    for (match in embeddedWebLink.findAll(parsed.text)) {
        val raw = match.value.trimEnd('.', ',', ')', '。', '、', '！', '）')
        val end = match.range.first + raw.length
        if (links.none { it.start < end && it.end > match.range.first }) {
            webLink(raw)?.let { links += TextLink(match.range.first, end, it, raw) }
        }
    }
    return LinkedText(parsed.text, links.sortedBy { it.start })
}

private data class TranslationResult(val content: LinkedText, val failed: Boolean = false)

private suspend fun translateFragment(value: String): String {
    val trimmed = value.trim()
    if (!likelyForeignText(trimmed)) return value
    val start = value.indexOf(trimmed)
    return value.substring(0, start) + translateJapanese(trimmed) + value.substring(start + trimmed.length)
}

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
