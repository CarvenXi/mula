package dev.dash.mula.engine

import android.content.Context
import android.os.Build
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Runtime installer for separately published engine APKs. The downloaded artifact is an APK
 * (not a raw AAR): Android can load its DEX and native libraries without installing another app.
 * The APK is produced by :servo-engine-plugin and signed/hashed by the release pipeline.
 */
class ServoPluginManager(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "engine-plugins/servo").apply { mkdirs() }
    private val apk = File(root, "servo-engine.apk")
    private val optimized = File(root, "dex").apply { mkdirs() }
    private val native = File(root, "lib").apply { mkdirs() }

    fun isInstalled(): Boolean = apk.isFile && apk.length() > 0L

    suspend fun install(
        apkUrl: String,
        expectedSha256: String,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) = withContext(Dispatchers.IO) {
        require(apkUrl.startsWith("https://")) { "引擎下载地址必须使用 HTTPS" }
        val checksum = expectedSha256.takeIf { it.matches(Regex("(?i)[0-9a-f]{64}")) }
            ?: fetchChecksum(apkUrl)
        val part = File(root, "servo-engine.apk.part")
        try {
            val connection = URL(apkUrl).openConnection().apply {
                connectTimeout = 20_000; readTimeout = 180_000
                setRequestProperty("User-Agent", "Mafari-EngineManager")
            }
            val total = connection.contentLengthLong
            connection.getInputStream().use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var count: Int
                    var received = 0L
                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count == 0) continue
                        output.write(buffer, 0, count); received += count
                        onProgress(received, total)
                    }
                }
            }
            require(part.length() > 1_000_000L) { "引擎包体积异常" }
            ZipFile(part).use { zip ->
                require(zip.getEntry("classes.dex") != null) { "引擎包缺少 classes.dex" }
                require(zip.getEntry("AndroidManifest.xml") != null) { "引擎包不是有效 APK" }
            }
            val actual = MessageDigest.getInstance("SHA-256").digest(part.readBytes())
                .joinToString("") { "%02x".format(it) }
            require(actual.equals(checksum, true)) { "SHA-256 校验失败：下载文件与发布校验值不一致" }
            val backup = File(root, "servo-engine.apk.bak")
            if (backup.exists()) backup.delete()
            if (apk.exists()) check(apk.renameTo(backup)) { "无法备份当前内核" }
            try {
                check(part.renameTo(apk)) { "无法安装引擎包" }
                apk.setReadable(true, true)
                apk.setWritable(false, false)
                native.deleteRecursively(); native.mkdirs()
                extractNativeLibraries()
                backup.delete()
            } catch (installError: Exception) {
                apk.delete()
                if (backup.exists()) backup.renameTo(apk)
                throw installError
            }
        } finally { part.delete() }
    }


    /** SHA file is published beside the APK as servo-engine.apk.sha256. */
    private fun fetchChecksum(apkUrl: String): String {
        val checksumUrl = "$apkUrl.sha256"
        val connection = URL(checksumUrl).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Mafari-EngineManager")
        }
        val body = connection.getInputStream().bufferedReader().use { it.readText() }
        return Regex("(?i)\\b[0-9a-f]{64}\\b").find(body)?.value
            ?: error("无法读取 Servo 插件 SHA-256：$checksumUrl")
    }

    fun createClassLoader(): ClassLoader {
        check(isInstalled()) { "Servo 内核尚未安装" }
        val abi = supportedPluginAbi()
        if (File(native, abi).listFiles()?.isEmpty() != false) extractNativeLibraries(abi)
        return DexClassLoader(apk.absolutePath, optimized.absolutePath, File(native, abi).absolutePath, app.classLoader)
    }

    private fun supportedPluginAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "armeabi-v7a" }
        ?: error("当前设备 ABI 暂不受 Servo 插件支持")

    private fun extractNativeLibraries(abi: String = supportedPluginAbi()) {
        val target = File(native, abi).apply { mkdirs() }
        ZipFile(apk).use { zip ->
            val prefix = "lib/$abi/"
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(".so")) {
                    val output = File(target, entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        require(target.listFiles()?.any { it.extension == "so" } == true) { "Servo 插件没有包含当前 ABI 的原生库：$abi" }
    }

    fun installedSize(): Long = if (apk.exists()) apk.length() else 0L
    fun uninstall() { apk.delete(); optimized.deleteRecursively(); native.deleteRecursively() }
}
