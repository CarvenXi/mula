package dev.dash.mula

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 通知栏 / 焦点通知上的暂停、取消按钮 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1L)
        if (id <= 0L) return
        when (intent.action) {
            ACTION_PAUSE -> Downloader.pause(id)
            ACTION_RESUME -> Downloader.resume(context, id)
            ACTION_CANCEL -> Downloader.delete(context, id)
        }
    }

    companion object {
        const val ACTION_PAUSE = "dev.dash.mula.download.PAUSE"
        const val ACTION_RESUME = "dev.dash.mula.download.RESUME"
        const val ACTION_CANCEL = "dev.dash.mula.download.CANCEL"
        const val EXTRA_ID = "task_id"
    }
}
