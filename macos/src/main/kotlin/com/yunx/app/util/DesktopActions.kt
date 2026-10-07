package com.yunx.app.util

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI

object DesktopActions {
    val defaultDownloadDir: File get() = File(System.getProperty("user.home"), "Downloads")
    fun openFile(path: String): Boolean = runCatching {
        require(File(path).exists())
        Desktop.getDesktop().open(File(path))
        true
    }.getOrDefault(false)
    fun revealFile(path: String): Boolean = runCatching {
        require(File(path).exists())
        ProcessBuilder("/usr/bin/open", "-R", path).start().waitFor() == 0
    }.getOrDefault(false)
    fun openUrl(url: String) { Desktop.getDesktop().browse(URI(url)) }
    fun pickFile(save: Boolean, defaultName: String = "YunX-accounts.yunx"): File? {
        val dialog = FileDialog(null as Frame?, if (save) "导出加密账号备份" else "导入加密账号备份",
            if (save) FileDialog.SAVE else FileDialog.LOAD)
        return try {
            if (save) dialog.file = defaultName
            dialog.isVisible = true
            dialog.file?.let { File(dialog.directory, it) }
        } finally { dialog.dispose() }
    }
    fun pickDirectory(): String? {
        val key = "apple.awt.fileDialogForDirectories"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "true")
            val dialog = FileDialog(null as Frame?, "选择下载目录", FileDialog.LOAD)
            return try {
                dialog.isVisible = true
                dialog.file?.let { File(dialog.directory, it).absolutePath }
            } finally { dialog.dispose() }
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
