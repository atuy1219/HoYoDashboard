package com.atuy.hoyodashboard

import android.util.LruCache
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
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

internal val LocalJapaneseTranslation = staticCompositionLocalOf { false }

private val translationCache = LruCache<String, String>(120)
private val translationRequests = Semaphore(2)

@Composable
internal fun JapaneseContent(value: String, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val enabled = LocalJapaneseTranslation.current
    val original = displayText(value)
    val translation by produceState<TranslationResult?>(null, original, enabled) {
        this.value = null
        if (enabled && original.count(Char::isLetter) >= 3) {
            this.value = try {
                TranslationResult(translateJapanese(original))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                TranslationResult(original, failed = true)
            }
        }
    }
    Column {
        Text(if (enabled) translation?.text ?: original else original, style = style)
        if (enabled && translation?.failed == true) {
            Text("日本語訳を取得できませんでした", color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
    }
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
