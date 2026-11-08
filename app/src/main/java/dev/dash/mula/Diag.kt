package dev.dash.mula

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 极简运行日志：写到 /sdcard/Android/media/<pkg>/diag.txt，方便闪退后看最后一步。 */
object Diag {

    private var file: File? = null
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        runCatching {
            val dir = context.externalMediaDirs.firstOrNull() ?: context.filesDir
            dir.mkdirs()
            val current = File(dir, "diag.txt")
            // 上一次运行的日志留作 diag-prev.txt：闪退后重新打开应用时 diag.txt 会被清空，不留的话就看不到崩溃前的记录
            if (current.isFile && current.length() > 0) {
                runCatching { current.copyTo(File(dir, "diag-prev.txt"), overwrite = true) }
            }
            file = current.apply { writeText("") }
            log("diag at ${file?.absolutePath}")
            logLastExitReasons(context)
        }
    }

    /**
     * 记录上次进程的退出原因（API 30+）。原生崩溃（GeckoView 的 SIGSEGV / SIGABRT 等）不会走 Java 的
     * UncaughtExceptionHandler，只有这里能拿到：reason=CRASH_NATIVE 时 description 里带信号信息，
     * 并附上系统给的 tombstone 摘要（含崩溃线程和堆栈）。包含 Gecko 子进程（:tabN / :gpu 等）。
     */
    private fun logLastExitReasons(context: Context) {
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 6)
            infos.forEach { info ->
                val reason = when (info.reason) {
                    android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH(java)"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
                    android.app.ApplicationExitInfo.REASON_ANR -> "ANR"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
                    android.app.ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    else -> "reason=${info.reason}"
                }
                log("exit-info: ${time.format(Date(info.timestamp))} process=${info.processName} $reason status=${info.status} desc=${info.description}")
                if (info.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    runCatching {
                        info.traceInputStream?.use { stream ->
                            // tombstone 是 protobuf，里面的字符串（信号、so 名、符号）直接按可见字符抽出来就够定位
                            val bytes = stream.readBytes()
                            val text = String(bytes, 0, minOf(bytes.size, 512 * 1024), Charsets.ISO_8859_1)
                            val strings = Regex("[\\x20-\\x7E]{6,}").findAll(text).map { it.value }.take(120).toList()
                            log("tombstone strings:\n" + strings.joinToString("\n"))
                        }
                    }
                }
            }
        }
    }

    fun log(message: String) {
        runCatching {
            file?.appendText("${time.format(Date())}  $message\n")
        }
    }
}
