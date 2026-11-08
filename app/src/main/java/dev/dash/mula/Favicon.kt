package dev.dash.mula

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** 抓网站 favicon，带内存缓存。 */
object FaviconLoader {

    private val cache = ConcurrentHashMap<String, ImageBitmap?>()

    suspend fun load(host: String): ImageBitmap? {
        if (host.isBlank()) return null
        Diag.log("favicon start $host")
        cache[host]?.let { return it }
        val bitmap = runCatching {
            val urls = listOf(
                "https://$host/favicon.ico",
                "https://www.google.com/s2/favicons?sz=64&domain=$host",
            )
            var result: Bitmap? = null
            for (url in urls) {
                result = fetch(url)
                if (result != null) break
            }
            result
        }.getOrNull()
        Diag.log("favicon done $host -> ${bitmap != null}")
        val image = bitmap?.asImageBitmap()
        // ConcurrentHashMap 不收 null，之前这里直接 NPE
        if (image != null) cache[host] = image
        return image
    }

    private fun fetch(url: String): Bitmap? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) mafari")
        }
        return try {
            if (connection.responseCode !in 200..299) {
                null
            } else {
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}

@Composable
fun rememberFavicon(url: String): ImageBitmap? {
    val host = remember(url) { hostOf(url) }
    var icon by remember(host) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(host) {
        icon = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            FaviconLoader.load(host)
        }
    }
    return icon
}

/** 加载油猴 @icon 之类的远程图标 */
object RemoteIconLoader {

    private val cache = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(url: String): ImageBitmap? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        cache[url]?.let { return it }
        val bitmap = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) mafari")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    null
                } else {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
        if (bitmap != null) cache[url] = bitmap
        return bitmap
    }
}

@Composable
fun rememberRemoteIcon(url: String): ImageBitmap? {
    var icon by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        icon = if (url.isBlank()) {
            null
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                RemoteIconLoader.load(url)
            }
        }
    }
    return icon
}
