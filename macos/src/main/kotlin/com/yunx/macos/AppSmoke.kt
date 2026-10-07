package com.yunx.macos

import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.awt.Window
import java.awt.event.WindowEvent
import java.io.File

internal suspend fun lifecycleSmoke(runtime: MacRuntime, window: Window, visible: () -> Boolean) {
    val url = requireNotNull(System.getenv("YUNX_SMOKE_URL"))
    require(url.startsWith("http://127.0.0.1:")) { "Smoke source must be local" }
    val id = withContext(Dispatchers.IO) { runtime.downloads.enqueue(url, "smoke 中文 空格.bin") }
    val initial = withTimeout(15000) {
        runtime.downloads.tasks.first { list -> list.any { it.id == id && it.downloadedSize > 0 } }.first { it.id == id }.downloadedSize
    }
    window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
    delay(900)
    check(!visible()) { "Window did not hide" }
    val after = withContext(Dispatchers.IO) { runtime.db.downloadTaskDao().get(id)!! }
    check(after.downloadedSize > initial) { "Download stopped while window was hidden" }
    withContext(Dispatchers.IO) {
        check(ProcessBuilder("/usr/bin/open", "-a", requireNotNull(System.getenv("YUNX_SMOKE_APP_BUNDLE"))).start().waitFor() == 0)
    }
    withTimeout(10000) { while (!visible()) delay(100) }
    runtime.downloads.pause(id)
    val paused = withTimeout(10000) {
        runtime.downloads.tasks.first { list -> list.any { it.id == id && it.status == 2 } }.first { it.id == id }
    }
    check(paused.downloadedSize > 0 && paused.downloadedSize < 4 * 1024 * 1024)
    println("LIFECYCLE_SMOKE_OK: hidden download, Dock reopen, paused task=$id bytes=${paused.downloadedSize}")
}

internal suspend fun resumeSmoke(runtime: MacRuntime) {
    val task = runtime.downloads.tasks.first().first { it.fileName == "smoke 中文 空格.bin" }
    check(task.status == 2 && task.downloadedSize > 0) { "Task was not restored as paused" }
    runtime.downloads.start(task.id)
    val finished = withTimeout(60000) {
        runtime.downloads.tasks.first { rows -> rows.any { it.id == task.id && it.status in listOf(3, 4) } }.first { it.id == task.id }
    }
    check(finished.status == 3) { finished.errorMsg }
    val expected = ByteArray(4 * 1024 * 1024) { (it % 251).toByte() }
    check(File(finished.savePath).readBytes().contentEquals(expected)) { "Downloaded bytes differ after process restart" }
    withContext(Dispatchers.IO) { check(DesktopActions.revealFile(finished.savePath)) { "Finder reveal failed" } }
    println("RESUME_SMOKE_OK: restored progress, matching file bytes, Finder reveal")
}
