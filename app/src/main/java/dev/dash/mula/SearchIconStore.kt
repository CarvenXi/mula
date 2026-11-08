package dev.dash.mula

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 搜索引擎图标：网络拉取后写入 filesDir 持久化，之后离线可用。 */
object SearchIconStore {

    private val memory = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(context: Context, url: String): ImageBitmap? {
        if (url.isBlank()) return null
        memory[url]?.let { return it }

        val dir = File(context.filesDir, "search_icons")
        val file = File(dir, url.hashCode().toUInt().toString(16) + ".png")
        if (file.exists()) {
            val cached = runCatching { BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap() }.getOrNull()
            if (cached != null) {
                memory[url] = cached
                return cached
            }
        }

        val bytes = runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) mafari")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    null
                } else {
                    connection.inputStream.use { it.readBytes() }
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return null

        val image = runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull() ?: return null

        runCatching {
            dir.mkdirs()
            file.writeBytes(bytes)
        }
        memory[url] = image
        return image
    }
}

@Composable
fun rememberSearchIcon(url: String): ImageBitmap? {
    val context = LocalContext.current
    var icon by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        icon = if (url.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { SearchIconStore.load(context, url) }
        }
    }
    return icon
}
