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
            file = File(dir, "diag.txt").apply { writeText("") }
            log("diag at ${file?.absolutePath}")
        }
    }

    fun log(message: String) {
        runCatching {
            file?.appendText("${time.format(Date())}  $message\n")
        }
    }
}
