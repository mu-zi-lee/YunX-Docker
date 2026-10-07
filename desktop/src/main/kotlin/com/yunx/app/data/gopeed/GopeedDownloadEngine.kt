package com.yunx.app.data.gopeed

import com.yunx.app.data.download.ExternalDownloadEngine
import java.io.File

object GopeedDownloadEngine : ExternalDownloadEngine {
    override fun isInstalled() = GopeedEngine.isInstalled()
    override fun isRunning() = GopeedEngine.state.value == GopeedEngine.State.RUNNING
    override fun start(directory: File) = GopeedEngine.start(directory)
    override fun settingsSignature() = GopeedEngine.settingsSignature()
    override fun applyRuntimeConfig(maxRunning: Int) = GopeedEngine.applyRuntimeConfig(maxRunning)
    override fun createTask(url: String, saveDir: String, fileName: String, headers: Map<String, String>, connections: Int, label: String) =
        GopeedEngine.createTask(url, saveDir, fileName, headers, connections, label)
    override fun pauseTask(id: String) = GopeedEngine.pauseTask(id)
    override fun continueTask(id: String) = GopeedEngine.continueTask(id)
    override fun deleteTask(id: String) = GopeedEngine.deleteTask(id)
    override fun taskStatus(id: String) = GopeedEngine.taskStatus(id).let {
        ExternalDownloadEngine.TaskView(it.id, it.status, it.downloaded, it.total, it.speed)
    }
    override fun taskConnections(id: String) = GopeedEngine.taskConnections(id)
    override fun taskDetail(id: String) = GopeedEngine.taskDetail(id).let {
        ExternalDownloadEngine.TaskDetail(it.name, it.fileCount, it.folder)
    }
}
