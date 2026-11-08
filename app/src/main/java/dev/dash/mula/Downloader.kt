package dev.dash.mula

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class DownloadState { RUNNING, PAUSED, DONE, FAILED }

data class DownloadTask(
    val id: Long = System.nanoTime(),
    val name: String,
    val url: String,
    val userAgent: String? = null,
    val mime: String? = null,
    val uri: Uri? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val state: DownloadState = DownloadState.RUNNING,
    val error: String? = null,
)

/** 内置下载器：断点续传、暂停/继续、失败重试，默认写入 Download/mafari */
object Downloader {

    val tasks = mutableStateListOf<DownloadTask>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val numberFormat = DecimalFormat("0.0")

    fun fileNameFrom(url: String, disposition: String?): String {
        val raw = disposition?.let { d ->
            Regex("filename\\*?=(?:UTF-8''|\")?([^\";]+)").find(d)?.groupValues?.get(1)?.trim()
        } ?: runCatching { Uri.parse(url).lastPathSegment }.getOrNull()
        val name = runCatching { URLDecoder.decode(raw ?: "", "UTF-8") }
            .getOrDefault(raw ?: "")
            .ifBlank { "download_${System.currentTimeMillis()}" }
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
    }

    fun start(context: Context, url: String, userAgent: String?, fileName: String, mimeType: String?) {
        val task = DownloadTask(name = fileName, url = url, userAgent = userAgent, mime = mimeType)
        tasks.add(0, task)
        DownloadService.start(context)
        DownloadService.instance?.showStart(fileName, task.id)
        launch(context, task.id)
    }

    fun pause(id: Long) {
        jobs.remove(id)?.cancel()
        update(id) { it.copy(state = DownloadState.PAUSED) }
        val task = tasks.firstOrNull { it.id == id }
        if (task != null) {
            val progress = progressOf(task)
            DownloadService.instance?.updatePaused(task.name, progress, id)
        }
    }

    fun resume(context: Context, id: Long) {
        update(id) { it.copy(state = DownloadState.RUNNING, error = null) }
        launch(context, id)
    }

    fun retry(context: Context, id: Long) {
        update(id) { it.copy(state = DownloadState.RUNNING, error = null) }
        launch(context, id)
    }

    fun delete(context: Context, id: Long) {
        jobs.remove(id)?.cancel()
        val task = tasks.firstOrNull { it.id == id }
        task?.uri?.let { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
        runCatching { tempFile(context, id).delete() }
        tasks.removeAll { it.id == id }
    }

    private fun tempFile(context: Context, id: Long) =
        java.io.File(context.cacheDir, "mafari_dl_$id.part")

    private fun launch(context: Context, id: Long) {
        jobs.remove(id)?.cancel()
        jobs[id] = scope.launch {
            var attempt = 0
            while (attempt < MAX_ATTEMPTS) {
                try {
                    downloadOnce(context, id)
                    val finished = tasks.firstOrNull { it.id == id }
                    update(id) { it.copy(state = DownloadState.DONE) }
                    DownloadService.instance?.finish(finished?.name ?: "文件")
                    return@launch
                } catch (e: CancellationException) {
                    return@launch
                } catch (e: Exception) {
                    attempt++
                    val message = e.message ?: e.javaClass.simpleName
                    if (attempt >= MAX_ATTEMPTS) {
                        update(id) { it.copy(state = DownloadState.FAILED, error = message) }
                        DownloadService.instance?.fail("下载失败：$message")
                        return@launch
                    }
                    update(id) { it.copy(error = message) }
                    delay(1200L * attempt)
                }
            }
        }
    }

    private suspend fun downloadOnce(context: Context, id: Long) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        val file = tempFile(context, id)
        var existing = if (file.exists()) file.length() else 0L

        val connection = (URL(task.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", task.userAgent ?: "Mozilla/5.0 mafari")
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Accept-Encoding", "identity")
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val length = connection.contentLengthLong.coerceAtLeast(0)
            val append = existing > 0 && code == 206
            if (!append) {
                existing = 0
                runCatching { file.delete() }
            }
            update(id) {
                it.copy(
                    bytes = existing,
                    total = if (append) existing + length else length,
                    state = DownloadState.RUNNING,
                )
            }
            // 多线程分段下载（服务器支持 Range 且文件 > 1MB 时）
            if (existing == 0L && append.not() && length > 1024 * 1024 && supportsRange(task)) {
                downloadSegmented(context, id, task, file, length)
                return finishToMediaStore(context, id, task, file)
            }

            connection.inputStream.use { input ->
                java.io.FileOutputStream(file, append).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var written = existing
                    var lastUpdate = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        written += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 250) {
                            lastUpdate = now
                            update(id) { it.copy(bytes = written) }
                            val total = tasks.firstOrNull { it.id == id }?.total ?: 0L
                            val progress = if (total > 0) written.toFloat() / total else 0f
                            DownloadService.instance?.update(
                                task.name,
                                progress,
                                "${(progress * 100).toInt()}% · ${sizeText(written)}",
                                id,
                            )
                        }
                    }
                    out.flush()
                    out.fd.sync()
                    update(id) { it.copy(bytes = written) }
                }
            }
            // 下载完成后一次性入库到 /sdcard/Download/mafari
            val uri = createEntry(context, task) ?: throw IllegalStateException("无法创建文件")
            context.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
            update(id) { it.copy(uri = uri) }
            runCatching { file.delete() }
        } finally {
            connection.disconnect()
        }
    }

    /** HEAD 一下看是否支持 Range，顺便拿长度 */
    private fun supportsRange(task: DownloadTask): Boolean = runCatching {
        val head = (URL(task.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 10000
            instanceFollowRedirects = true
            setRequestProperty("Range", "bytes=0-0")
            setRequestProperty("User-Agent", task.userAgent ?: "Mozilla/5.0 mafari")
            setRequestProperty("Accept-Encoding", "identity")
        }
        val code = head.responseCode
        head.disconnect()
        code == 206
    }.getOrDefault(false)

    private suspend fun downloadSegmented(
        context: Context,
        id: Long,
        task: DownloadTask,
        file: java.io.File,
        total: Long,
    ) {
        val threads = SEGMENTS.coerceAtMost(((total / (256 * 1024)) + 1).toInt()).coerceAtLeast(1)
        val chunk = total / threads
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(total)
            val done = AtomicLong(0)
            var lastUpdate = 0L
            coroutineScope {
                repeat(threads) { index ->
                    launch(Dispatchers.IO) {
                        val start = index * chunk
                        val end = if (index == threads - 1) total - 1 else start + chunk - 1
                        val connection = (URL(task.url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 15000
                            readTimeout = 30000
                            instanceFollowRedirects = true
                            setRequestProperty("Range", "bytes=$start-$end")
                            setRequestProperty("User-Agent", task.userAgent ?: "Mozilla/5.0 mafari")
                            setRequestProperty("Accept-Encoding", "identity")
                        }
                        try {
                            val code = connection.responseCode
                            if (code !in 200..299) throw IllegalStateException("HTTP $code")
                            connection.inputStream.use { input ->
                                val buffer = ByteArray(128 * 1024)
                                var position = start
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read <= 0) break
                                    synchronized(raf) {
                                        raf.seek(position)
                                        raf.write(buffer, 0, read)
                                    }
                                    position += read
                                    val written = done.addAndGet(read.toLong())
                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdate > 300) {
                                        lastUpdate = now
                                        update(id) {
                                            it.copy(
                                                bytes = written,
                                                total = total,
                                                state = DownloadState.RUNNING,
                                            )
                                        }
                                        DownloadService.instance?.update(
                                            task.name,
                                            (written.toFloat() / total).coerceIn(0f, 1f),
                                            "${(written * 100 / total)}% · ${sizeText(written)}",
                                            id,
                                        )
                                    }
                                }
                            }
                        } finally {
                            connection.disconnect()
                        }
                    }
                }
            }
            update(id) { it.copy(bytes = total, total = total) }
        }
    }

    private fun finishToMediaStore(context: Context, id: Long, task: DownloadTask, file: java.io.File) {
        val uri = createEntry(context, task) ?: throw IllegalStateException("无法创建文件")
        context.contentResolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
        }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        update(id) { it.copy(uri = uri) }
        runCatching { file.delete() }
    }

    private fun createEntry(context: Context, task: DownloadTask): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, task.name)
            put(MediaStore.Downloads.MIME_TYPE, task.mime ?: "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/mafari")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }.getOrNull()

    private fun update(id: Long, transform: (DownloadTask) -> DownloadTask) {
        val index = tasks.indexOfFirst { it.id == id }
        if (index >= 0) tasks[index] = transform(tasks[index])
    }

    /** 保存 data:image/...;base64 的内嵌图片到系统下载目录 */
    fun saveDataUri(context: Context, dataUri: String, fileName: String): Boolean = runCatching {
        val comma = dataUri.indexOf(',')
        if (comma < 0) return@runCatching false
        val meta = dataUri.substringAfter("data:").substringBefore(',')
        if (!meta.contains("base64", ignoreCase = true)) return@runCatching false
        val bytes = android.util.Base64.decode(dataUri.substring(comma + 1), android.util.Base64.DEFAULT)
        val mime = meta.substringBefore(';').ifBlank { "image/png" }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/mafari")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return@runCatching false
        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        context.contentResolver.update(
            uri,
            ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
            null,
            null,
        )
        tasks.add(
            0,
            DownloadTask(
                name = fileName,
                url = "",
                mime = mime,
                uri = uri,
                bytes = bytes.size.toLong(),
                total = bytes.size.toLong(),
                state = DownloadState.DONE,
            ),
        )
        true
    }.getOrDefault(false)

    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${numberFormat.format(bytes / 1024f)} KB"
        else -> "${numberFormat.format(bytes / 1024f / 1024f)} MB"
    }

    fun progressOf(task: DownloadTask): Float {
        if (task.state == DownloadState.DONE) return 1f
        if (task.total <= 0) return 0f
        return (task.bytes.toFloat() / task.total).coerceIn(0f, 1f)
    }

    private const val MAX_ATTEMPTS = 3
    private const val SEGMENTS = 4
}
