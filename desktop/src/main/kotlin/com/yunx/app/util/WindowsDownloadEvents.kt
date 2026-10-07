package com.yunx.app.util

import com.yunx.app.data.download.DownloadSystemEvents

object WindowsDownloadEvents : DownloadSystemEvents {
    override fun started(keepAwake: Boolean) {
        if (keepAwake) WindowsKeepAwake.acquire()
        WindowsToastNotifier.sessionStart()
    }
    override fun finished() {
        WindowsKeepAwake.release()
        WindowsToastNotifier.sessionEnd()
    }
    override fun progress(done: Long, total: Long, title: String, detail: String, speed: String) =
        WindowsToastNotifier.updateProgress(done, total, title, detail, speed, "正在下载")
}
