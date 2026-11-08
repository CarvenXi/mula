package dev.dash.mula

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import org.json.JSONObject

/** 下载前台服务：常驻进度通知（HyperOS 会把它显示在通知栏/超级岛区域） */
class DownloadService : Service() {

    private var islandShown = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = build("准备下载", 0, true, "0%")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "文件下载进度"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** OS3（超岛协议）才注入岛参数；更低版本走普通焦点通知 */
    private fun islandProtocol(): Int = runCatching {
        Settings.System.getInt(contentResolver, "notification_focus_protocol", 0)
    }.getOrDefault(0)

    private fun islandJson(name: String, percent: Int, text: String, allowFloat: Boolean): String {
        val param = JSONObject()
            .put("protocol", 1)
            .put("business", "download")
            .put("enableFloat", allowFloat)
            .put("updatable", true)
            .put("ticker", "下载中 $percent%")
            .put("aodTitle", "下载中 $percent%")

        val bigArea = JSONObject().apply {
            put(
                "imageTextInfoLeft",
                JSONObject().apply {
                    put("type", 1)
                    put(
                        "textInfo",
                        JSONObject().apply {
                            put("frontTitle", "下载中")
                            put("title", "$percent%")
                            put("content", text)
                            put("useHighLight", false)
                        },
                    )
                },
            )
            put(
                "textInfo",
                JSONObject().apply {
                    put("frontTitle", name)
                    put("title", "$percent%")
                    put("content", text)
                },
            )
        }
        val smallArea = JSONObject().apply {
            put(
                "textInfo",
                JSONObject().apply {
                    put("frontTitle", "$percent%")
                    put("title", "下载中")
                    put("content", name)
                },
            )
        }
        val island = JSONObject()
            .put("islandProperty", 1)
            .put("bigIslandArea", bigArea)
            .put("smallIslandArea", smallArea)

        val baseInfo = JSONObject().apply {
            put("title", "正在下载 $name")
            put("content", text)
            put("colorTitle", "#006EFF")
            put("type", 2)
        }
        val hintInfo = JSONObject().apply {
            put("type", 1)
            put("title", "下载中")
        }
        param.put("param_island", island)
        param.put("baseInfo", baseInfo)
        param.put("hintInfo", hintInfo)

        return JSONObject().put("param_v2", param).toString()
    }

    private fun build(
        title: String,
        progress: Int,
        indeterminate: Boolean,
        text: String,
        islandName: String? = null,
        taskId: Long = -1L,
        allowFloat: Boolean = false,
        paused: Boolean = false,
        ongoing: Boolean = true,
    ): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        val pending = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)

        if (taskId > 0L) {
            val action = if (paused) {
                DownloadActionReceiver.ACTION_RESUME to "继续"
            } else {
                DownloadActionReceiver.ACTION_PAUSE to "暂停"
            }
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                    ),
                    action.second,
                    broadcast(taskId, action.first, 1),
                ).build(),
            )
            builder.addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        android.R.drawable.ic_menu_close_clear_cancel,
                    ),
                    "取消",
                    broadcast(taskId, DownloadActionReceiver.ACTION_CANCEL, 2),
                ).build(),
            )
        }
        val notification = builder.build()
        // 岛参数每次带上（这样岛上能看到进度），但只有第一次允许浮出
        if (islandName != null && islandProtocol() >= 3) {
            runCatching {
                notification.extras.putString(
                    "miui.focus.param",
                    islandJson(islandName, progress, text, allowFloat && !islandShown),
                )
                notification.extras.putString("miui.focus.business", "download")
                islandShown = true
            }
        }
        return notification
    }

    /** 开始下载：允许岛浮出一次 */
    fun showStart(name: String, taskId: Long) {
        islandShown = false
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    build("正在下载", 0, true, name, name, taskId, allowFloat = true),
                )
            islandShown = true
        }
    }

    /** 进度更新：同步刷新岛内容（不浮出） */
    fun update(name: String, progress: Float, text: String, taskId: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    build(name, (progress * 100).toInt(), false, text, name, taskId),
                )
        }
    }

    fun updatePaused(name: String, progress: Float, taskId: Long) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    build("已暂停", (progress * 100).toInt(), false, name, name, taskId, paused = true),
                )
        }
    }

    private fun broadcast(taskId: Long, action: String, requestCode: Int): android.app.PendingIntent =
        android.app.PendingIntent.getBroadcast(
            this,
            requestCode,
            android.content.Intent(this, DownloadActionReceiver::class.java)
                .setAction(action)
                .putExtra(DownloadActionReceiver.EXTRA_ID, taskId),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun finish(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    build("下载完成", 100, false, text, null, -1L, ongoing = false),
                )
        }
        runCatching {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    fun fail(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build("下载失败", 0, false, text, null, -1L, ongoing = false))
        }
        runCatching {
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelf()
        }
    }

    companion object {
        const val CHANNEL_ID = "mula_download"
        const val NOTIFICATION_ID = 4201
        @Volatile
        var instance: DownloadService? = null

        fun start(context: Context) {
            runCatching {
                val intent = Intent(context, DownloadService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }
}
