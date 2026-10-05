package com.yunx.app.data.prefs

import com.yunx.app.data.download.DownloadPlatform
import java.util.prefs.Preferences

/**
 * 应用设置（桌面版以 java.util.prefs 持久化，键与原 SharedPreferences 完全一致）。
 */
class SettingsRepository {

    private val prefs: Preferences = Preferences.userRoot().node("yunx/settings")

    /** 下载线程数（通用/手动添加，分片并发数），默认 32，上限 512 */
    var downloadThreads: Int
        get() = downloadThreadsFor(DownloadPlatform.GENERIC)
        set(value) = setDownloadThreads(DownloadPlatform.GENERIC, value)

    /** 获取指定平台的下载线程数；迅雷固定 8，其余默认 32、上限 512 */
    fun downloadThreadsFor(platform: String): Int {
        if (platform == DownloadPlatform.XUNLEI) return XUNLEI_DOWNLOAD_THREADS
        return prefs.getInt(prefsKey(platform), DEFAULT_DOWNLOAD_THREADS)
            .coerceIn(1, MAX_DOWNLOAD_THREADS)
    }

    /** 设置指定平台的下载线程数；迅雷不可修改 */
    fun setDownloadThreads(platform: String, value: Int) {
        if (platform == DownloadPlatform.XUNLEI) return
        prefs.putInt(prefsKey(platform), value.coerceIn(1, MAX_DOWNLOAD_THREADS))
    }

    private fun prefsKey(platform: String): String =
        if (platform.isBlank() || platform == DownloadPlatform.GENERIC) "download_threads"
        else "download_threads_$platform"

    /** 自定义下载保存目录（桌面版为绝对路径字符串；原 Android 为 SAF tree Uri）；null/空 = 系统默认「下载」目录 */
    var downloadDirUri: String?
        get() = prefs.get("download_dir_uri", null)
        set(value) {
            if (value.isNullOrBlank()) prefs.remove("download_dir_uri") else prefs.put("download_dir_uri", value)
        }

    /** 最大同时下载任务数（默认 1：前台任务吃满带宽，其余排队） */
    var maxConcurrentDownloads: Int
        get() = prefs.getInt("max_concurrent_downloads", DEFAULT_MAX_CONCURRENT_DOWNLOADS)
        set(value) {
            prefs.putInt("max_concurrent_downloads", value.coerceIn(1, 10))
        }

    /** 下载速度限制（字节/秒；0 = 不限速） */
    var downloadSpeedLimit: Long
        get() = prefs.getLong("download_speed_limit", 0L)
        set(value) {
            prefs.putLong("download_speed_limit", value.coerceAtLeast(0L))
        }

    /** 下载失败后自动重试次数（默认 3，范围 0-10） */
    var downloadRetryCount: Int
        get() = prefs.getInt("download_retry_count", DEFAULT_DOWNLOAD_RETRY_COUNT)
        set(value) {
            prefs.putInt("download_retry_count", value.coerceIn(0, 10))
        }

    /** 下载时阻止电脑休眠（Windows：任务运行中 SetThreadExecutionState 阻止系统睡眠） */
    var keepAwakeWhileDownloading: Boolean
        get() = prefs.getBoolean("keep_awake_downloading", prefs.getBoolean("keep_download_when_locked", true))
        set(value) {
            prefs.putBoolean("keep_awake_downloading", value)
        }

    /** 通知中心进度样式（Windows 通知中心 toast：true 时进度条附带下载速度） */
    var notificationShowSpeed: Boolean
        get() = prefs.getBoolean("notification_show_speed", true)
        set(value) {
            prefs.putBoolean("notification_show_speed", value)
        }

    /** 夸克取链方式：true=免转存（直接换下载直链，不写入网盘，默认）；false=先转存到临时目录再取链 */
    var quarkNoSaveDownload: Boolean
        get() = prefs.getBoolean("quark_no_save_download", true)
        set(value) {
            prefs.putBoolean("quark_no_save_download", value)
        }

    /** 后台剪贴板分享链接检测（主窗口失焦时轮询剪贴板，检测到网盘链接弹出提示） */
    var clipboardLinkDetection: Boolean
        get() = prefs.getBoolean("clipboard_link_detection", true)
        set(value) {
            prefs.putBoolean("clipboard_link_detection", value)
        }

    /** 接受预发布版更新：检查更新时把 GitHub Pre-release 也算作新版本（默认关闭） */
    var acceptPrereleaseUpdate: Boolean
        get() = prefs.getBoolean("accept_prerelease_update", false)
        set(value) {
            prefs.putBoolean("accept_prerelease_update", value)
        }

    /**
     * 关闭主窗口时的行为：
     * - "ask"：每次询问（默认）
     * - "exit"：直接退出
     * - "tray"：最小化到系统托盘
     */
    var closeBehavior: String
        get() = prefs.get("close_behavior", CLOSE_BEHAVIOR_ASK)
        set(value) {
            prefs.put("close_behavior", value)
        }

    /** 桌面图标样式（桌面版无 activity-alias，保留设置项占位） */
    var appIconVariant: Int
        get() = prefs.getInt("app_icon_variant", 0)
        set(value) {
            prefs.putInt("app_icon_variant", value.coerceIn(0, 1))
        }

    /** 忽略 SSL 证书校验（抓包调试用；桌面版 OkHttp 会实际生效） */
    var ignoreSslCert: Boolean
        get() = prefs.getBoolean("ignore_ssl_cert", false)
        set(value) {
            prefs.putBoolean("ignore_ssl_cert", value)
        }

    /** 百度网盘大文件限速提示：是否已选择「不再显示」 */
    var baiduLimitHintDismissed: Boolean
        get() = prefs.getBoolean("baidu_limit_hint_dismissed", false)
        set(value) {
            prefs.putBoolean("baidu_limit_hint_dismissed", value)
        }

    /** 深色模式：0=跟随系统，1=浅色，2=深色 */
    var darkMode: Int
        get() = prefs.getInt("dark_mode", 0)
        set(value) {
            prefs.putInt("dark_mode", value.coerceIn(0, 2))
        }

    /** 主题色模式：0=默认蓝色，1=默认蓝色，2=自定义种子色（桌面无动态取色，0 与 1 等价） */
    var themeColorMode: Int
        get() = prefs.getInt("theme_color_mode", 0)
        set(value) {
            prefs.putInt("theme_color_mode", value.coerceIn(0, 2))
        }

    /** 自定义主题种子色（ARGB 值） */
    var themeSeedColor: Long
        get() = prefs.getLong("theme_seed_color", DEFAULT_SEED_COLOR)
        set(value) {
            prefs.putLong("theme_seed_color", value)
        }

    /**
     * 自定义 GitHub 下载镜像前缀（如 "https://gh.dpik.top/"）。
     * null/空字符串表示使用内置默认镜像（UpdateChecker.MIRROR_PREFIX）。
     */
    var githubMirrorPrefix: String?
        get() = prefs.get("github_mirror_prefix", null)
        set(value) {
            if (value.isNullOrBlank()) prefs.remove("github_mirror_prefix") else prefs.put("github_mirror_prefix", value)
        }

    /**
     * 代理模式：三选一（直连 / 系统代理 / 手动配置）。
     * 兼容旧值：`proxy_mode` 尚未写入时按旧的 `proxy_enabled` 迁移一次
     * （true → manual，false → direct），迁移后写入 `proxy_mode`。
     */
    var proxyMode: String
        get() {
            val saved = prefs.get("proxy_mode", null)
            if (saved != null) return normalizeProxyMode(saved)
            val migrated =
                if (prefs.getBoolean("proxy_enabled", false)) PROXY_MODE_MANUAL else PROXY_MODE_DIRECT
            prefs.put("proxy_mode", migrated)
            return migrated
        }
        set(value) {
            prefs.put("proxy_mode", normalizeProxyMode(value))
        }

    /** 是否启用 HTTP 代理（旧键，仅用于 [proxyMode] 的兼容迁移，新逻辑不再写入） */
    var proxyEnabled: Boolean
        get() = prefs.getBoolean("proxy_enabled", false)
        set(value) {
            prefs.putBoolean("proxy_enabled", value)
        }

    /** 代理主机地址（如 "127.0.0.1"），空串表示未配置；仅 [PROXY_MODE_MANUAL] 使用 */
    var proxyHost: String
        get() = prefs.get("proxy_host", "") ?: ""
        set(value) {
            prefs.put("proxy_host", value)
        }

    /** 代理端口（默认 7890，范围 1-65535）；仅 [PROXY_MODE_MANUAL] 使用 */
    var proxyPort: Int
        get() = prefs.getInt("proxy_port", DEFAULT_PROXY_PORT)
        set(value) {
            prefs.putInt("proxy_port", value.coerceIn(1, 65535))
        }

    /**
     * 是否启用 HTTP/2（默认 false：仅使用 HTTP/1.1；开启后允许 ALPN 协商到 h2）。
     * HTTP/2 理论上更快，但实测差异通常不大，故默认关闭。
     */
    var http2Enabled: Boolean
        get() = prefs.getBoolean("http2_enabled", false)
        set(value) {
            prefs.putBoolean("http2_enabled", value)
        }

    /**
     * 下载网络读缓冲大小（字节，默认 64KB）。
     * 原为固定值 64KB（见 ChunkDownloader.BUFFER_SIZE），此处改为可在「设置 → 实验性功能」调节；
     * 下载引擎在每次分片请求时按此值建缓冲（对新任务生效）。可选档位 16/32/64/128/256 KB。
     */
    var downloadBufferSize: Int
        get() = prefs.getInt("download_buffer_size", DEFAULT_DOWNLOAD_BUFFER_SIZE)
            .coerceIn(MIN_DOWNLOAD_BUFFER_SIZE, MAX_DOWNLOAD_BUFFER_SIZE)
        set(value) {
            prefs.putInt(
                "download_buffer_size",
                value.coerceIn(MIN_DOWNLOAD_BUFFER_SIZE, MAX_DOWNLOAD_BUFFER_SIZE)
            )
        }

    /** 慢连接抢占开关（默认开，与现状一致；关闭后下载引擎不再把慢分片换连接） */
    var slowPreemptEnabled: Boolean
        get() = prefs.getBoolean("slow_preempt_enabled", DEFAULT_SLOW_PREEMPT_ENABLED)
        set(value) {
            prefs.putBoolean("slow_preempt_enabled", value)
        }

    /** 慢连接判定阈值（字节/秒，默认 12KB/s；范围 4–256 KB/s） */
    var slowPreemptMinBps: Long
        get() = prefs.getLong("slow_preempt_min_bps", DEFAULT_SLOW_PREEMPT_MIN_BPS)
            .coerceIn(MIN_SLOW_PREEMPT_MIN_BPS, MAX_SLOW_PREEMPT_MIN_BPS)
        set(value) {
            prefs.putLong(
                "slow_preempt_min_bps",
                value.coerceIn(MIN_SLOW_PREEMPT_MIN_BPS, MAX_SLOW_PREEMPT_MIN_BPS)
            )
        }

    /** 慢连接判定时长（毫秒，默认 15s；范围 5–60s） */
    var slowPreemptMinAgeMs: Long
        get() = prefs.getLong("slow_preempt_min_age_ms", DEFAULT_SLOW_PREEMPT_MIN_AGE_MS)
            .coerceIn(MIN_SLOW_PREEMPT_MIN_AGE_MS, MAX_SLOW_PREEMPT_MIN_AGE_MS)
        set(value) {
            prefs.putLong(
                "slow_preempt_min_age_ms",
                value.coerceIn(MIN_SLOW_PREEMPT_MIN_AGE_MS, MAX_SLOW_PREEMPT_MIN_AGE_MS)
            )
        }

    /** 主页快捷方式（默认关）：开启后在解析页以横向快捷方式展示收藏，点击直达解析 */
    var homeShortcutEnabled: Boolean
        get() = prefs.getBoolean("home_shortcut_enabled", false)
        set(value) {
            prefs.putBoolean("home_shortcut_enabled", value)
        }

    /**
     * 下载引擎选择（见「设置 → 下载引擎」）：
     * - [ENGINE_BUILTIN]（默认）：内置分片下载器（Range 并发 / 断点续传 / 自适应分片）；
     * - [ENGINE_GOPEED]：外部 Gopeed 引擎（以 exe 子进程运行，通过其本地 HTTP API 驱动）。
     * 只影响**新任务**由谁执行；已存在的任务保持原有归属。非法值（手改偏好）一律按内置处理。
     */
    var downloadEngine: String
        get() = when (prefs.get("download_engine", ENGINE_BUILTIN)) {
            ENGINE_GOPEED -> ENGINE_GOPEED
            else -> ENGINE_BUILTIN
        }
        set(value) {
            prefs.put("download_engine", if (value == ENGINE_GOPEED) ENGINE_GOPEED else ENGINE_BUILTIN)
        }

    /**
     * 重置「实验性功能」页全部设置为默认值：
     * HTTP/2 关闭、读缓冲 64KB、慢连接抢占开启（12KB/s、15s）、主页快捷方式关闭。
     * 运行时同步（HttpClients.setHttp2Enabled / DownloadTuning.applyFrom）由调用方负责。
     */
    fun resetExperimentalFeatures() {
        http2Enabled = DEFAULT_HTTP2_ENABLED
        downloadBufferSize = DEFAULT_DOWNLOAD_BUFFER_SIZE
        slowPreemptEnabled = DEFAULT_SLOW_PREEMPT_ENABLED
        slowPreemptMinBps = DEFAULT_SLOW_PREEMPT_MIN_BPS
        slowPreemptMinAgeMs = DEFAULT_SLOW_PREEMPT_MIN_AGE_MS
        homeShortcutEnabled = false
    }

    /** 归一化代理模式：非法值一律回退为直连 */
    private fun normalizeProxyMode(value: String): String = when (value) {
        PROXY_MODE_SYSTEM, PROXY_MODE_MANUAL -> value
        else -> PROXY_MODE_DIRECT
    }

    companion object {
        /** 关闭行为：每次询问 */
        const val CLOSE_BEHAVIOR_ASK = "ask"
        /** 关闭行为：直接退出 */
        const val CLOSE_BEHAVIOR_EXIT = "exit"
        /** 关闭行为：最小化到系统托盘 */
        const val CLOSE_BEHAVIOR_TRAY = "tray"

        /** 代理模式：不使用代理（直连） */
        const val PROXY_MODE_DIRECT = "direct"
        /** 代理模式：使用系统代理（读 Windows 系统代理设置） */
        const val PROXY_MODE_SYSTEM = "system"
        /** 代理模式：手动配置代理（主机 + 端口） */
        const val PROXY_MODE_MANUAL = "manual"

        /** 下载引擎：内置分片下载器（默认） */
        const val ENGINE_BUILTIN = "builtin"
        /** 下载引擎：外部 Gopeed 引擎（exe 子进程 + 本地 HTTP API） */
        const val ENGINE_GOPEED = "gopeed"

        const val DEFAULT_DOWNLOAD_THREADS = 32
        const val MAX_DOWNLOAD_THREADS = 512
        const val XUNLEI_DOWNLOAD_THREADS = 8
        const val DEFAULT_MAX_CONCURRENT_DOWNLOADS = 1
        const val DEFAULT_DOWNLOAD_RETRY_COUNT = 3
        const val DEFAULT_PROXY_PORT = 7890

        /** HTTP/2 默认关闭（与现状一致：仅使用 HTTP/1.1） */
        const val DEFAULT_HTTP2_ENABLED = false

        /** 下载读缓冲默认 64KB（与 ChunkDownloader.BUFFER_SIZE 一致，保证默认行为不变） */
        const val DEFAULT_DOWNLOAD_BUFFER_SIZE = 64 * 1024
        const val MIN_DOWNLOAD_BUFFER_SIZE = 16 * 1024
        const val MAX_DOWNLOAD_BUFFER_SIZE = 256 * 1024

        /** 慢连接抢占默认开启（与现状一致），阈值 12KB/s、判定 15s */
        const val DEFAULT_SLOW_PREEMPT_ENABLED = true
        const val DEFAULT_SLOW_PREEMPT_MIN_BPS = 12L * 1024
        const val MIN_SLOW_PREEMPT_MIN_BPS = 4L * 1024
        const val MAX_SLOW_PREEMPT_MIN_BPS = 256L * 1024
        const val DEFAULT_SLOW_PREEMPT_MIN_AGE_MS = 15_000L
        const val MIN_SLOW_PREEMPT_MIN_AGE_MS = 5_000L
        const val MAX_SLOW_PREEMPT_MIN_AGE_MS = 60_000L

        /** 默认主题种子色：Material Blue（与内置默认方案一致） */
        const val DEFAULT_SEED_COLOR = 0xFF415F91L
    }
}
