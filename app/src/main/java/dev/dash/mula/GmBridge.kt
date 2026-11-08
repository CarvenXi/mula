package dev.dash.mula

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 用户脚本的原始能力桥：GM_setValue / GM_xmlhttpRequest / GM_notification / GM_openInTab。
 * 跨域请求由原生发起，绕开网页的 CORS 限制（与油猴一致）。
 */
class GmBridge(
    private val context: Context,
    private val webViewProvider: () -> WebView?,
    private val onOpenTab: (String) -> Unit,
) {

    private val prefs = context.getSharedPreferences("userscript_gm", Context.MODE_PRIVATE)
    private val pool = Executors.newFixedThreadPool(3)

    @JavascriptInterface
    fun getValue(scriptId: String, key: String): String? = prefs.getString("$scriptId::$key", null)

    @JavascriptInterface
    fun setValue(scriptId: String, key: String, value: String) {
        prefs.edit().putString("$scriptId::$key", value).apply()
    }

    @JavascriptInterface
    fun deleteValue(scriptId: String, key: String) {
        prefs.edit().remove("$scriptId::$key").apply()
    }

    @JavascriptInterface
    fun listValues(scriptId: String): String {
        val prefix = "$scriptId::"
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        return JSONArray(keys).toString()
    }

    @JavascriptInterface
    fun xhr(reqId: String, scriptId: String, url: String, method: String, headers: String, body: String) {
        pool.execute {
            var status = 0
            var text = ""
            var finalUrl = url
            try {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method.ifBlank { "GET" }.uppercase()
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept-Encoding", "identity")
                    runCatching {
                        val json = JSONObject(headers.ifBlank { "{}" })
                        json.keys().forEach { key -> setRequestProperty(key, json.optString(key)) }
                    }
                }
                if (body.isNotBlank() && connection.requestMethod !in listOf("GET", "HEAD")) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                status = connection.responseCode
                finalUrl = connection.url.toString()
                text = runCatching {
                    (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                connection.disconnect()
            } catch (e: Exception) {
                status = -1
                text = e.message ?: "request failed"
            }
            val payload = JSONObject.quote(text)
            val js = "window.__mulaXhrDone && window.__mulaXhrDone(" +
                "${JSONObject.quote(reqId)}, $status, $payload, ${JSONObject.quote(finalUrl)})"
            webViewProvider()?.post {
                runCatching { webViewProvider()?.evaluateJavascript(js, null) }
            }
        }
    }

    @JavascriptInterface
    fun toast(message: String) {
        android.os.Handler(context.mainLooper).post {
            runCatching {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    @JavascriptInterface
    fun openTab(url: String) {
        android.os.Handler(context.mainLooper).post { onOpenTab(url) }
    }
}
