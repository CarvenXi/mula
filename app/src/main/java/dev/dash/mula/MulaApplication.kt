package dev.dash.mula

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import dev.dash.mula.engine.GeckoChildBootstrap

/**
 * 主进程什么都不做。GeckoView 的子进程（:tabN / :gpu …）也会创建本 Application，
 * 这里在它们启动最早期把插件 dex / so 接入，并让 Gecko 子进程看到的 APK 路径、原生库目录指向插件
 * （GeckoView 用 Context 的 packageResourcePath / applicationInfo 去找 omni.ja 和 libxul）。
 */
class MulaApplication : Application() {
    private var geckoChild: GeckoChildBootstrap.Payload? = null
    private var geckoChildInfo: ApplicationInfo? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        geckoChild = runCatching { GeckoChildBootstrap.attach(this) }.getOrNull()
    }

    override fun getPackageResourcePath(): String =
        geckoChild?.apk?.absolutePath ?: super.getPackageResourcePath()

    override fun getPackageCodePath(): String =
        geckoChild?.apk?.absolutePath ?: super.getPackageCodePath()

    override fun getApplicationInfo(): ApplicationInfo {
        val base = super.getApplicationInfo()
        val child = geckoChild ?: return base
        return geckoChildInfo ?: ApplicationInfo(base).apply {
            sourceDir = child.apk.absolutePath
            publicSourceDir = child.apk.absolutePath
            nativeLibraryDir = child.libDir.absolutePath
        }.also { geckoChildInfo = it }
    }
}
