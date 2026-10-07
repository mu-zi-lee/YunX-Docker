package com.yunx.macos

import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.*
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.XunleiDeviceFingerprint
import com.yunx.app.util.DesktopActions
import com.yunx.server.Credentials
import com.yunx.server.ServerService
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.prefs.Preferences

class MacPowerEvents : DownloadSystemEvents {
    private var process: Process? = null
    @Synchronized override fun started(keepAwake: Boolean) {
        if (keepAwake && process?.isAlive != true)
            process = runCatching { ProcessBuilder("/usr/bin/caffeinate", "-i", "-w", ProcessHandle.current().pid().toString()).start() }.getOrNull()
    }
    @Synchronized override fun finished() {
        process?.destroy()
        process = null
    }
}

class MacRuntime {
    val prefs: Preferences = Preferences.userRoot().node("${System.getProperty("yunx.preferenceRoot", "yunx-macos")}/settings")
    val db = AppDatabase.get()
    val credentials = Credentials(File(AppContext.dataDir, "accounts.enc"))
    private val power = MacPowerEvents()
    val downloads = DownloadManager(
        db.downloadTaskDao(), ChunkDownloader { HttpClients.downloadClient() },
        threadProvider = { platform -> if (platform == "xunlei") 8 else prefs.getInt("threads", 32) },
        saveDirProvider = { prefs.get("directory", DesktopActions.defaultDownloadDir.absolutePath) },
        concurrencyProvider = { prefs.getInt("concurrency", 3) },
        speedLimitProvider = { prefs.getLong("speedLimit", 0) },
        systemEvents = power
    )
    val service = ServerService(db, downloads, credentials)
    init {
        Files.setPosixFilePermissions(AppContext.dataDir.toPath(), PosixFilePermissions.fromString("rwx------"))
        Files.setPosixFilePermissions(File(AppContext.dataDir, "credential.key").toPath(), PosixFilePermissions.fromString("rw-------"))
        runBlocking { db.downloadTaskDao().markInterruptedAsPaused() }
        applyProxy()
    }
    fun applyProxy() {
        HttpClients.setProxy(prefs.get("proxyHost", "").takeIf { it.isNotBlank() }, prefs.getInt("proxyPort", 8080))
    }
    fun exportAccounts(target: File, password: String) {
        val content = service.exportAccounts(password)
        val temporary = Files.createTempFile(target.parentFile.toPath(), ".yunx-backup-", ".tmp")
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"))
            Files.writeString(temporary, content)
            Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun importAccounts(source: File, password: String): Int {
        require(source.isFile && source.length() <= 2 * 1024 * 1024) { "备份文件不存在或超过 2 MiB" }
        return service.importAccounts(source.readText(), password)
    }
    suspend fun shutdown() {
        downloads.shutdown()
        power.finished()
        prefs.flush()
    }
    companion object {
        fun prepare(preferenceRoot: String = "yunx-macos") {
            System.setProperty("yunx.dataDir", File(System.getProperty("user.home"), "Library/Application Support/YunX").absolutePath)
            System.setProperty("yunx.preferenceRoot", preferenceRoot)
            AppContext.init()
            Files.setPosixFilePermissions(AppContext.dataDir.toPath(), PosixFilePermissions.fromString("rwx------"))
            XunleiDeviceFingerprint.init()
        }
    }
}
