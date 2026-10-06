package com.yunx.app.util

import com.yunx.app.AppContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面版日志导出工具：
 * 将运行日志（<dataDir>/files/yunx-desktop.log）快照导出为带时间戳的文本文件。
 */
object LogExporter {

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

    /** 导出日志快照到 <dataDir>/cache/logs/yunx_log_<ts>.txt；失败返回 null */
    fun export(): File? = runCatching {
        val dir = File(AppContext.cacheDir, "logs")
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "yunx_log_${timestamp()}.txt")
        val header = buildString {
            appendLine("YunX PC 运行日志")
            appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("系统: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("Java: ${System.getProperty("java.version")}")
            appendLine("========================================")
        }
        FileOutputStream(target).use { out ->
            out.write(header.toByteArray(Charsets.UTF_8))
            val logFile = File(AppContext.filesDir, "yunx-desktop.log")
            if (logFile.exists()) {
                logFile.inputStream().use { it.copyTo(out) }
            } else {
                out.write("（暂无日志文件）\n".toByteArray(Charsets.UTF_8))
            }
        }
        target
    }.getOrNull()

    /** 导出日志到系统「下载」目录；成功返回 true */
    fun saveToDownloads(): Boolean = runCatching {
        val dir = DesktopActions.defaultDownloadDir
        if (!dir.exists()) dir.mkdirs()
        val target = File(dir, "yunx_log_${timestamp()}.txt")
        val logFile = File(AppContext.filesDir, "yunx-desktop.log")
        val header = "YunX PC 日志导出 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n"
        FileOutputStream(target).use { out ->
            out.write(header.toByteArray(Charsets.UTF_8))
            if (logFile.exists()) {
                logFile.inputStream().use { it.copyTo(out) }
            } else {
                out.write("（暂无日志文件）\n".toByteArray(Charsets.UTF_8))
            }
        }
        true
    }.getOrDefault(false)

    /** 清空运行日志（桌面版仅截断本地日志文件） */
    fun clearLog(): Boolean = runCatching {
        val logFile = File(AppContext.filesDir, "yunx-desktop.log")
        if (logFile.exists()) logFile.writeText("")
        true
    }.getOrDefault(false)

    /**
     * 打包全部诊断日志（`yunx_diagnostic_logs_yyyyMMdd_HHmmss.zip`）；诊断模式没开或还没写过返回 null。
     *
     * 真正的打包在 [DiagnosticLog.exportZip]（它先 flush 再压，保证最后几行也在包里），
     * 这里只是把「日志导出」这套对外 API 收在同一个对象里，调用方不用同时认识两个工具。
     */
    fun exportDiagnosticZip(): File? = DiagnosticLog.exportZip()
}
