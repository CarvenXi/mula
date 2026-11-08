package dev.dash.mula.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Downloads the standalone GeckoView plugin APK on demand. GeckoView needs more than DEX:
 * the plugin APK also supplies Android resources/assets and ABI native libraries, so the
 * loader builds a resource-aware Context rather than treating the APK as a plain JAR.
 */
class GeckoPluginManager(context: Context) {
    private val app = context.applicationContext
    private val root = File(app.filesDir, "engine-plugins/gecko").apply { mkdirs() }
    /** New downloads wait here until the next process start; active sessions keep their APK stable. */
    private val apk = File(root, "gecko-engine.apk")
    private val activeApk = File(root, "gecko-engine-active.apk")
    private val nativeMarker = File(root, "native-libraries.sha256")
    private val optimized = File(root, "dex").apply { mkdirs() }
    private val native = File(root, "lib").apply { mkdirs() }

    fun isInstalled(): Boolean = listOf(apk, activeApk).any { it.isFile && it.length() > 1_000_000L }
    fun installedSize(): Long = (if (apk.isFile) apk else activeApk).let { if (it.isFile) it.length() else 0L }

    suspend fun install(apkUrl: String, expectedSha256: String, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            require(apkUrl.startsWith("https://")) { "GeckoView 插件下载地址必须使用 HTTPS" }
            val checksum = expectedSha256.takeIf { it.matches(Regex("(?i)[0-9a-f]{64}")) } ?: fetchChecksum(apkUrl)
            val part = File(root, "gecko-engine.apk.part")
            try {
                val connection = URL(apkUrl).openConnection().apply {
                    connectTimeout = 20_000; readTimeout = 240_000
                    setRequestProperty("User-Agent", "Mula-EngineManager")
                }
                val total = connection.contentLengthLong
                connection.getInputStream().use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count); received += count
                            onProgress(received, total)
                        }
                    }
                }
                require(part.length() > 1_000_000L) { "GeckoView 插件包体积异常" }
                ZipFile(part).use { zip ->
                    require(zip.getEntry("classes.dex") != null) { "GeckoView 插件缺少 classes.dex" }
                    require(zip.getEntry("AndroidManifest.xml") != null) { "GeckoView 插件不是有效 APK" }
                    require(zip.entries().asSequence().any { it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }) {
                        "GeckoView 插件未包含 arm64-v8a 原生引擎库"
                    }
                }
                val actual = sha256(part)
                require(actual.equals(checksum, true)) { "GeckoView 插件 SHA-256 校验失败" }
                val backup = File(root, "gecko-engine.apk.bak")
                backup.delete()
                if (apk.exists()) check(apk.renameTo(backup)) { "无法备份当前 GeckoView 插件" }
                try {
                    check(part.renameTo(apk)) { "无法安装 GeckoView 插件" }
                    apk.setReadable(true, true); apk.setWritable(false, false)
                    // This is the pending version. An active GeckoRuntime continues to use
                    // gecko-engine-active.apk; pending updates are activated on next process start.
                    backup.delete()
                } catch (error: Exception) {
                    apk.delete()
                    if (backup.exists()) backup.renameTo(apk)
                    throw error
                }
            } finally { part.delete() }
        }

    private fun fetchChecksum(url: String): String {
        val connection = URL("$url.sha256").openConnection().apply {
            connectTimeout = 15_000; readTimeout = 20_000
            setRequestProperty("User-Agent", "Mula-EngineManager")
        }
        val body = connection.getInputStream().bufferedReader().use { it.readText() }
        return Regex("(?i)\\b[0-9a-f]{64}\\b").find(body)?.value
            ?: error("无法读取 GeckoView 插件 SHA-256")
    }


    /** Prepare a pending plugin off the UI thread before the engine is selected. */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        check(isInstalled()) { "GeckoView 内核尚未安装" }
        synchronized(loaderLock) {
            if (sharedLoader == null) {
                if (apk.isFile) {
                    if (activeApk.exists()) check(activeApk.delete()) { "无法替换旧 GeckoView 插件快照" }
                    check(apk.renameTo(activeApk)) { "无法激活 GeckoView 插件" }
                    activeApk.setReadable(true, true)
                    activeApk.setWritable(false, false)
                }
                ensureNativeLibraries(supportedAbi())
            }
        }
    }

    fun createClassLoader(): ClassLoader {
        check(isInstalled()) { "GeckoView 内核尚未安装" }
        synchronized(loaderLock) {
            val current = sharedLoader
            if (current != null && sharedApkPath == activeApk.absolutePath) return current

            // Promote a pending update only when this process has no loaded GeckoRuntime.
            if (apk.isFile) {
                if (activeApk.exists()) check(activeApk.delete()) { "无法替换旧 GeckoView 插件快照" }
                check(apk.renameTo(activeApk)) { "无法激活 GeckoView 插件" }
                activeApk.setReadable(true, true)
                activeApk.setWritable(false, false)
            }
            check(activeApk.isFile) { "GeckoView 插件文件不存在" }
            val abi = supportedAbi()
            ensureNativeLibraries(abi)
            return DexClassLoader(activeApk.absolutePath, optimized.absolutePath, File(native, abi).absolutePath, app.classLoader).also {
                sharedLoader = it
                sharedApkPath = activeApk.absolutePath
            }
        }
    }

    /** Context exposing the plugin APK's compiled resources/assets and extracted native libs. */
    fun createPluginContext(loader: ClassLoader, activityContext: Context): Context {
        check(activeApk.isFile) { "GeckoView 插件尚未初始化，请先创建内核会话" }
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assets, activeApk.absolutePath) as Int
        check(cookie != 0) { "无法挂载 GeckoView 插件资源" }
        val baseResources = app.resources
        val resources = Resources(assets, baseResources.displayMetrics, baseResources.configuration)
        val archiveAppInfo = app.packageManager.getPackageArchiveInfo(
            activeApk.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_META_DATA.toLong()),
        )?.applicationInfo ?: app.applicationInfo
        val appInfo = ApplicationInfo(archiveAppInfo).apply {
            packageName = PLUGIN_PACKAGE
            sourceDir = activeApk.absolutePath
            publicSourceDir = activeApk.absolutePath
            nativeLibraryDir = File(native, supportedAbi()).absolutePath
            dataDir = app.dataDir.absolutePath
            className = null
        }
        fun wrap(base: Context, applicationContext: Context?): Context = object : ContextWrapper(base) {
            private var pluginTheme: Resources.Theme? = null
            override fun getAssets(): AssetManager = assets
            override fun getResources(): Resources = resources
            override fun getClassLoader(): ClassLoader = loader
            override fun getApplicationInfo(): ApplicationInfo = appInfo
            override fun getApplicationContext(): Context = applicationContext ?: this
            override fun getPackageName(): String = PLUGIN_PACKAGE
            override fun getPackageResourcePath(): String = activeApk.absolutePath
            override fun getTheme(): Resources.Theme {
                if (pluginTheme == null) {
                    pluginTheme = resources.newTheme().also { it.applyStyle(android.R.style.Theme_Material_NoActionBar, true) }
                }
                return pluginTheme!!
            }
        }
        val pluginAppContext = wrap(app, null)
        return wrap(activityContext, pluginAppContext)
    }

    /** GeckoView 插件只打包 arm64-v8a。 */
    private fun supportedAbi(): String = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" }
        ?: error("GeckoView 插件仅支持 arm64-v8a 设备")

    private fun ensureNativeLibraries(abi: String) {
        val fingerprint = "${activeApk.length()}:${activeApk.lastModified()}"
        val markerMatches = nativeMarker.isFile && nativeMarker.readText().trim() == fingerprint
        val target = File(native, abi)
        if (!markerMatches || target.listFiles()?.any { it.extension == "so" } != true) {
            native.deleteRecursively(); native.mkdirs()
            extractNativeLibraries(activeApk, abi)
            nativeMarker.writeText(fingerprint)
        }
    }

    private fun extractNativeLibraries(sourceApk: File, abi: String) {
        val target = File(native, abi).apply { mkdirs() }
        ZipFile(sourceApk).use { zip ->
            val entries = zip.entries()
            val prefix = "lib/$abi/"
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.startsWith(prefix) && entry.name.endsWith(".so")) {
                    val output = File(target, entry.name.substringAfterLast('/'))
                    zip.getInputStream(entry).use { input -> output.outputStream().use { input.copyTo(it) } }
                }
            }
        }
        require(target.listFiles()?.any { it.extension == "so" } == true) { "GeckoView 插件没有包含当前 ABI 的原生库：$abi" }
    }

    fun uninstall() {
        apk.delete(); activeApk.delete(); optimized.deleteRecursively(); native.deleteRecursively(); nativeMarker.delete()
        synchronized(loaderLock) { sharedLoader = null; sharedApkPath = null }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val PLUGIN_PACKAGE = "dev.dash.mula.geckoplugin"
        private val loaderLock = Any()
        @Volatile private var sharedLoader: ClassLoader? = null
        @Volatile private var sharedApkPath: String? = null
    }
}
