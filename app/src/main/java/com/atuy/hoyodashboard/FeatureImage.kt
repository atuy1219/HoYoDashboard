package com.atuy.hoyodashboard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale

internal fun publicImageUrl(value: String): String? {
    val url = runCatching { URI(value) }.getOrNull() ?: return null
    val host = url.host?.lowercase(Locale.ROOT) ?: return null
    val domains = listOf("hoyolab.com", "hoyoverse.com", "mihoyo.com", "miyoushe.com", "bh3.com")
    if (url.scheme != "https" || url.userInfo != null || url.port !in listOf(-1, 443) ||
        domains.none { host == it || host.endsWith(".$it") }
    ) return null
    return url.toASCIIString()
}

private val images = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

@Composable
internal fun FeatureImage(url: String?, wide: Boolean = false) {
    val source = url?.let(::publicImageUrl) ?: return
    val bitmap by produceState<Bitmap?>(null, source) {
        value = images.get(source) ?: withContext(Dispatchers.IO) {
            val fetched = runCatching {
                val connection = URI(source).toURL().openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 6_000
                    connection.readTimeout = 6_000
                    if (connection.responseCode != 200) return@runCatching null
                    val bytes = connection.inputStream.use { input ->
                        val buffer = ByteArray(8192)
                        val output = ByteArrayOutputStream()
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (output.size() + count > 4 * 1024 * 1024) return@runCatching null
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1024) {
                        options.inSampleSize *= 2
                    }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
            fetched?.also { images.put(source, it) }
        }
    }
    val modifier = if (wide) Modifier.fillMaxWidth().aspectRatio(2.4f) else Modifier.size(46.dp)
    bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit) }
        ?: Box(modifier)
}
