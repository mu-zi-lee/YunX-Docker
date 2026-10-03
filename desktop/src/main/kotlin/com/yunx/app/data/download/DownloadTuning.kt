package com.yunx.app.data.download

import com.yunx.app.data.prefs.SettingsRepository

/**
 * 下载引擎调优参数（进程内单例，@Volatile 注入，参考 HttpClients.setHttp2Enabled 的写法）。
 *
 * 这些参数集中在「设置 → 实验性功能」调节，[applyFrom] 在应用启动与设置变更时装配。
 * 下载引擎读取本对象的字段：
 * - [bufferSize]：网络读缓冲大小，[ChunkDownloader] 在每次分片请求时据此建缓冲（对新任务生效）；
 * - [preemptEnabled] / [preemptMinBps] / [preemptMinAgeMs]：慢连接抢占开关、判定阈值与判定时长，
 *   由 [DownloadManager] 的看门狗每个采样周期读取（改动后下一周期即生效）。
 *
 * 默认值与改动前完全一致：读缓冲 64KB、抢占开启、阈值 12KB/s、判定 15s。
 */
object DownloadTuning {

    /** 网络读缓冲大小（字节）：默认 64KB */
    @Volatile
    var bufferSize: Int = SettingsRepository.DEFAULT_DOWNLOAD_BUFFER_SIZE

    /** 慢连接抢占开关：默认开 */
    @Volatile
    var preemptEnabled: Boolean = SettingsRepository.DEFAULT_SLOW_PREEMPT_ENABLED

    /** 慢连接判定阈值（字节/秒）：默认 12KB/s */
    @Volatile
    var preemptMinBps: Long = SettingsRepository.DEFAULT_SLOW_PREEMPT_MIN_BPS

    /** 慢连接判定时长（毫秒）：默认 15s */
    @Volatile
    var preemptMinAgeMs: Long = SettingsRepository.DEFAULT_SLOW_PREEMPT_MIN_AGE_MS

    /** 从设置重新装配调优参数（应用启动、实验性功能页设置变更 / 重置时调用）。 */
    fun applyFrom(settings: SettingsRepository) {
        bufferSize = settings.downloadBufferSize
        preemptEnabled = settings.slowPreemptEnabled
        preemptMinBps = settings.slowPreemptMinBps
        preemptMinAgeMs = settings.slowPreemptMinAgeMs
    }

    /**
     * 慢连接判定阈值 = max(用户设定的绝对下限, 本任务平均单连接速度 / 2)。
     * DownloadManager 看门狗每个采样周期据此判定；纯函数，无副作用。
     */
    @JvmStatic
    fun preemptFloorBps(avgPerConnBps: Long): Long = maxOf(preemptMinBps, avgPerConnBps / 2)
}
