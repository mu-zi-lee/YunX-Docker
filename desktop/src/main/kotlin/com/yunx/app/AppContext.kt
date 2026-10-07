package com.yunx.app

import com.yunx.app.util.Log
import java.io.File
import java.util.prefs.Preferences

/**
 * 桌面应用上下文门面：替代 Android Context。
 * 数据/缓存/临时目录、以及非设置类的杂项偏好（onboarding、忽略版本、迅雷设备指纹）。
 */
object AppContext {

    /** 应用数据根目录：默认 <用户目录>/.yunx-desktop；可用环境变量 YUNX_DESKTOP_DATA_DIR 覆盖（测试/沙箱环境用） */
    val dataDir: File = System.getenv("YUNX_DESKTOP_DATA_DIR")?.let { File(it) }
        ?: System.getProperty("yunx.dataDir")?.let { File(it) }
        ?: File(System.getProperty("user.home"), ".yunx-desktop")

    /** 旧版数据根目录（.yunx-pc）：首次启动时整体迁移到新目录 */
    private val legacyDataDir: File = File(System.getProperty("user.home"), ".yunx-pc")

    /** 缓存目录（下载分片等可丢弃数据） */
    val cacheDir: File = File(dataDir, "cache")

    /** 下载分片目录（原 externalCacheDir/download_tmp） */
    val downloadTmpDir: File = File(cacheDir, "download_tmp")

    /** 合并暂存目录（原 cacheDir/merged_*） */
    val mergeDir: File = File(cacheDir, "merge")

    /** 杂项文件目录（日志等） */
    val filesDir: File = File(dataDir, "files")

    /** 杂项偏好（原 "yunx_prefs" SharedPreferences：onboarding_shown / ignored_version） */
    val miscPrefs: Preferences = Preferences.userRoot().node("${System.getProperty("yunx.preferenceRoot", "yunx")}/misc")

    /** 迅雷设备指纹偏好（原 "xunlei_device_fp"） */
    val xunleiFpPrefs: Preferences = Preferences.userRoot().node("${System.getProperty("yunx.preferenceRoot", "yunx")}/xunlei_fp")

    fun init() {
        migrateLegacyDataDir()
        listOf(dataDir, cacheDir, downloadTmpDir, mergeDir, filesDir).forEach { it.mkdirs() }
    }

    /**
     * 旧数据目录迁移：<用户目录>/.yunx-pc → .yunx-desktop。
     * 仅在新目录不存在、且未用环境变量覆盖时执行；整体改名（不复制，下载分片可能很大），
     * 改名失败则退回使用新建的空目录（登录态与任务需重新登录/重新添加）。
     */
    private fun migrateLegacyDataDir() {
        if (System.getenv("YUNX_DESKTOP_DATA_DIR") != null || System.getProperty("yunx.dataDir") != null) return
        if (dataDir.exists() || !legacyDataDir.exists()) return
        if (!legacyDataDir.renameTo(dataDir)) {
            Log.w("YunX-DataDir", "旧数据目录迁移失败：${legacyDataDir.absolutePath}")
            return
        }
        // 日志文件一并改名，避免迁移后旧日志成为游离文件
        File(filesDir, "yunx-pc.log").takeIf { it.exists() }
            ?.renameTo(File(filesDir, "yunx-desktop.log"))
        Log.i("YunX-DataDir", "已迁移数据目录：${legacyDataDir.name} -> ${dataDir.name}")
    }
}
