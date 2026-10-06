package com.yunx.app.data.download

import com.yunx.app.AppContext
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.Log
import com.yunx.app.util.LogRedactor
import com.yunx.app.util.WindowsKeepAwake
import com.yunx.app.util.WindowsToastNotifier
import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.security.FileCredentialCipher
import com.yunx.app.data.security.CredentialCipher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlin.math.min

/** 实时下载统计（用于 UI 展示速度/剩余时间/线程数） */
data class DownloadStats(
    val speed: Long = 0L,        // 字节/秒
    val remainMillis: Long = -1L, // 剩余时间（毫秒），未知为 -1
    val chunkCount: Int = 1,      // 分片（线程）数
    /**
     * 分片合并进度（0~100）：-1 表示不在合并阶段。
     * 合并只活在内存里、不写 DB —— 进程退出时合并本来就会中断，无需在库里留状态。
     */
    val mergePercent: Int = -1
)

private const val TAG = "YunX-DL"

// plan.txt 解析（用于判断旧分片计划能否复用）：格式 `chunks=N total=N main=N`
private val PLAN_CHUNKS = Regex("chunks=(\\d+)")
private val PLAN_TOTAL = Regex("total=(\\d+)")
private val PLAN_MAIN = Regex("main=(\\d+)")

/** 单文件 Range 分片的安全并发上限。迅雷等 CDN 对单文件并发 Range 有阈值，
 *  超过约 8 个并发会把多余请求降级为 200 整文件（忽略 Range），
 *  进而触发整任务回退单流、速度暴跌。压在安全上限内，所有分片都能稳定拿到 206。 */
private const val RANGE_WORKERS_CAP = 8

/** 错峰建连上限（序号）：第 i 个分片首次请求前延迟 (min(i, STAGGER_CAP) * STAGGER_MS) */
private const val STAGGER_CAP = 8
private const val STAGGER_MS = 25L

/** 慢连接抢占的采样间隔：看门狗每隔这么久刷新一次每路瞬时速度，再据此判定是否换连接 */
private const val PREEMPT_TICK_MS = 5_000L

// ---------- ★ 慢连接抢占（治「收尾塌到 KB 级」，勿删）----------
// 网盘 CDN 是**按连接**限速的，且个别连接会落在慢节点上：实测多数连接 40~80KB/s，少数只有 3~7KB/s。
// 慢分片如果正好是收尾时唯一在跑的那几路，总速就塌到 KB 级。抢占 = 断开这条慢连接、换一条新连接续传。
// 开关、判定阈值与判定时长改由「设置 → 实验性功能」调节（见 DownloadTuning）：
// 默认 = 开 / 12KB/s / 15s，与改动前完全一致；关闭后看门狗不再抢占（等价于上游该功能之前的行为）。
/** 同一分片两次抢占之间的冷却期（换完连接要给新连接爬坡时间，避免反复重连） */
private const val PREEMPT_COOLDOWN_MS = 10_000L
/** 剩余不足这个数就不再折腾（换连接本身也有握手成本） */
private const val PREEMPT_MIN_REMAIN = 128 * 1024L
/** 单个分片最多被抢占几次：全局都慢时（如整站限速）避免无意义的重连风暴 */
private const val PREEMPT_MAX = 3
/** 每轮看门狗最多抢占几路，避免同一时刻大批连接同时重建 */
private const val PREEMPT_PER_TICK = 2
/** 收尾判定：在飞分片不超过这个数就认为「其余连接已无事可做」，放宽抢占门槛 */
private const val PREEMPT_ENDGAME_INFLIGHT = 3
/** 收尾时的最小存活时间：比常规门槛短得多，但也要避开刚建连的爬坡期 */
private const val PREEMPT_ENDGAME_MIN_AGE_MS = 3_000L

/** RANGE_IGNORED 容忍次数：CDN 偶发 200（限流中间态）前 N 次不触发整任务回退，继续领新片；超过才回退单流 */
private const val RANGE_IGNORED_TOLERANCE = 3

/** 重试区间（主池 part_i 或弹性区间 seg_{start}_{end}） */
private data class RetryRange(val start: Long, val end: Long, val file: File)

/**
 * 弹性区动态分片分配器（IDM 式）。
 * 核心矛盾：块太大会让尾部只剩少数几个大块 → 空闲线程空转（"最后一点特别慢"）；
 * 块太小会产生海量 Range 请求 → 拖慢快连接。故不再用固定块，改为**实时速度自适应 + 尾部收缩**：
 * - 按字节顺序发块（物理相邻），保持连接复用（不搞"中点劈分"，避免中后段掉速）；
 * - 起步块 = 主池 chunkSize（快连接不会一上来被切成碎片）；
 * - 稳定后块 ≈ 单 worker 下载 TARGET_SECONDS 秒的量：快则大块（省 Range 开销），慢则小块（充分并行摊平拖尾）；
 * - 尾部收缩：剩余字节 <= workers*块 时，把块压到 remaining/workers，让全部连接并行冲完最后一段，
 *   彻底消除"其他分片下完、只剩最后一个线程慢慢爬"的拖尾塌缩。
 */
private class ElasticAllocator(
    private val total: Long,
    private val elasticStart: Long,
    private val workers: Int,
    private val chunkSize: Long
) {
    private val lock = Any()
    private var nextStart = elasticStart

    /** 最近实测总速度（字节/秒），由外部实时注入；<=0 表示尚未探测到 */
    @Volatile
    var recentSpeedBps: Long = 0L

    /** 领取下一个弹性块（按字节顺序；块大小随速度与剩余量动态收缩） */
    fun take(): LongRange? = synchronized(lock) {
        if (nextStart >= total) return null
        val remaining = total - nextStart
        val base = baseBlockSize()
        // ★ 尾部收缩：剩余不足 workers 个整块时，均分到约 workers 份，让全部连接忙到最后；
        //   下限 MIN_TAIL_BLOCK（不碎成海量请求），上限 base（块不会越滚越大）。
        val w = workers.coerceAtLeast(1)
        val block = if (remaining <= w * base) {
            (remaining / w).coerceIn(MIN_TAIL_BLOCK, base)
        } else {
            base
        }
        val s = nextStart
        val e = minOf(s + block - 1, total - 1)
        nextStart = e + 1
        s..e
    }

    /** 速度自适应块大小：块 ≈ 单 worker 下载 TARGET_SECONDS 秒的量，夹在 [MIN, MAX]；未探测到速度时沿用主池 chunkSize */
    private fun baseBlockSize(): Long {
        val w = workers.coerceAtLeast(1)
        val perWorker = (recentSpeedBps / w).coerceAtLeast(0L)
        if (perWorker <= 0L) return chunkSize.coerceIn(MIN_ELASTIC_BLOCK, MAX_ELASTIC_BLOCK)
        return (perWorker * TARGET_SECONDS).coerceIn(MIN_ELASTIC_BLOCK, MAX_ELASTIC_BLOCK)
    }

    /** 断点续传：跳过已下载前缀（nextStart 只前进） */
    fun skipTo(start: Long) = synchronized(lock) {
        if (start > nextStart) nextStart = start
    }

    companion object {
        /** 常态块下限：慢连接稳定态每块至少 256KB（避免过多请求）；快连接起步也不低于此 */
        const val MIN_ELASTIC_BLOCK = 256 * 1024L
        /** 常态块上限：快连接单块封顶 4MB（过大块会降低动态性/连接复用友好度） */
        const val MAX_ELASTIC_BLOCK = 4 * 1024 * 1024L
        /** 尾部收缩下限：最后一段可细到 64KB，尽量榨干全部连接；再小则 Range 开销不划算 */
        const val MIN_TAIL_BLOCK = 64 * 1024L
        /** 自适应支点：每块 ≈ 单 worker 下载此秒数（快则大块、慢则小块） */
        const val TARGET_SECONDS = 5L
    }
}

/**
 * 一个在飞分片的采样状态（慢连接抢占的判定依据，**永久结构，勿删**）。
 *
 * 累加已收字节（每个读块一次 `AtomicLong.addAndGet`，开销可忽略）、记住起点/块大小，供看门狗协程
 * 每 PREEMPT_TICK_MS 刷新一次瞬时速度（[lastBps]）；[preempt] / [preemptCount] / [lastPreemptAtMs]
 * 决定该路是否换连接续传——删掉它们收尾长尾就会回来。
 */
private class InflightChunk(val start: Long, val size: Long) {
    val bytes = AtomicLong(0L)
    val startedAtMs = System.currentTimeMillis()

    /** 上一次快照时的字节数与时刻（只由看门狗协程读写） */
    var lastBytes = 0L
    var lastAtMs = startedAtMs

    /** 本次快照算出的瞬时速度（只由看门狗协程读写） */
    var lastBps = 0L

    /** ★ 慢连接抢占标志：置位后 ChunkDownloader 断开当前连接、换新连接从已收字节续传（不丢数据） */
    val preempt = AtomicBoolean(false)

    /** 本分片已被抢占次数（上限 PREEMPT_MAX） */
    var preemptCount = 0

    /** 上次被抢占的时刻（冷却期用） */
    var lastPreemptAtMs = 0L

    val elapsedMs: Long get() = System.currentTimeMillis() - startedAtMs
}

/**
 * 下载任务管理器：
 * - 任务持久化（Room），状态流转 PENDING → DOWNLOADING → COMPLETED / PAUSED / FAILED；
 * - 分片多线程下载（每片一个协程，信号量限并发）；
 * - 断点续传：part 文件保留，暂停/重启后从已有大小继续；
 * - 完成后合并分片并保存到公共 Download 目录。
 */
class DownloadManager(
    private val dao: DownloadTaskDao,
    private val downloader: ChunkDownloader,
    /** 下载线程数提供者（按平台，可在设置中修改，动态生效），默认 32 */
    private val threadProvider: (String) -> Int = { 32 },
    /** 自定义下载保存目录提供者（SAF tree Uri，可空）；null 时保存到系统默认 Download */
    private val saveDirProvider: () -> String? = { null },
    /** 最大同时下载任务数提供者（默认 3）：限制后台并发任务，避免占满带宽/耗尽路由器连接 */
    private val concurrencyProvider: () -> Int = { 3 },
    /** 全局下载速度限制提供者（字节/秒；0 = 不限速） */
    private val speedLimitProvider: () -> Long = { 0L },
    /** 下载失败后自动重试次数提供者（默认 3，上限 10） */
    private val retryCountProvider: () -> Int = { 3 },
    /** 下载期间阻止系统休眠开关（Windows：SetThreadExecutionState，开启时任务运行中系统不睡眠） */
    private val keepAwakeProvider: () -> Boolean = { true },
    /** 通知栏显示下载速度开关（false 时仅显示通知，隐藏速度） */
    private val showSpeedProvider: () -> Boolean = { true },
    /**
     * 是否启用外部 Gopeed 引擎（设置页「下载引擎」选择 Gopeed 时为 true）。
     * 只决定**新任务**的归属；实际还会再判一次平台（GitHub 除外）与内核是否已导入。
     */
    private val engineEnabledProvider: () -> Boolean = { false }
) {
    private val credentialCipher: CredentialCipher = FileCredentialCipher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前实际下载中的任务数（用于最大同时下载任务数限制） */
    private val activeDownloads = java.util.concurrent.atomic.AtomicInteger(0)

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒 */
    private val speedLimiter = SpeedLimiter()

    /**
     * ★ 全进程在飞分片信号量（跨任务共享）。
     *   这里钉的是「同时在飞的下载请求数」，而不是设置里的线程数：分片 IO 都是**同步阻塞**的
     *   `call.execute()`，设置写 512 也只是「允许 512 路同时下」，真正的闸门是这道信号量
     *   （容量 [MAX_INFLIGHT_CHUNKS]，按最大堆预算推导、封顶 512）。
     *   旧实现是「每任务一个容量 = 自身 worker 数的信号量」，永不阻塞 ⇒ 等于不限流，多个任务叠加即撑爆堆。
     *   主池 / 弹性区 / 失败重试三条路径统一过闸。★ 绝不手动 release。
     */
    private val inflightLimiter = Semaphore(MAX_INFLIGHT_CHUNKS)

    /**
     * 保存前存储权限检查（Android 9- 写公共 Download 需 WRITE_EXTERNAL_STORAGE 运行时授权）。
     * UI 层注入：无权限时动态申请并等待授权结果；已授权/Android 10+ 直接返回 true。
     * 授权后会自动继续保存（同一协程 await 授权结果再往下走）。
     */
    var storagePermissionProvider: suspend () -> Boolean = { true }

    /**
     * 运行中的任务 Job：value 为 CompletableDeferred，注册/移除全程由 jobsLock 保护，
     * 保证 start/pause/remove 之间无 TOCTOU 竞态（防止"暂停/删除瞬间任务继续跑"）。
     */
    private val activeJobs = ConcurrentHashMap<Long, CompletableDeferred<Job>>()
    private val jobsLock = Any()

    /** 前台服务计数：有任务在下载时保持前台（避免切后台限速/进程被杀） */
    private val activeTaskCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 前台通知进度节流（毫秒）：2 秒更新一次，避免频繁刷新系统通知 */
    private val notifyThrottleMs = 2000L
    private val lastNotifyTs = AtomicLong(0)

    /** 内存高压日志节流（毫秒时间戳，30s 一次） */
    @Volatile
    private var lastMemLogTs = 0L

    /** 合并阶段进度上报节流（毫秒）：分片合并回调很密，百分比不变时最多这么久报一次 */
    private val mergeReportIntervalMs = 300L

    /** Windows 通知中心聚合进度的每任务快照（id → 名称/总量/已完成） */
    private class ToastMeta(val name: String, val total: Long) {
        @Volatile var done = 0L
    }
    private val toastMeta = ConcurrentHashMap<Long, ToastMeta>()

    /** 更新 Windows 通知中心进度（聚合所有活动任务，1s 节流；非 Windows no-op） */
    private fun notifyProgress(id: Long, fileName: String, new: Long, total: Long) {
        if (total <= 0) return
        // ★ 内存观测（与进度回调同频，自带 30s 节流）：已用堆超 3/4 时打一行，并把「在飞分片
        //   已用/上限」一起带上，导出日志即可看到在飞上限是否真的生效（OOM 前的堆时间线）
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastMemLogTs >= 30_000L) {
            val rt = Runtime.getRuntime()
            val used = rt.totalMemory() - rt.freeMemory()
            if (used * 4 > rt.maxMemory() * 3) {
                lastMemLogTs = nowMs
                Log.w(TAG, "内存高压 used=${used / 1024 / 1024}MB max=${rt.maxMemory() / 1024 / 1024}MB " +
                    "tasks=${_stats.value.size} inflightCap=$MAX_INFLIGHT_CHUNKS " +
                    "inflightUsed=${MAX_INFLIGHT_CHUNKS - inflightLimiter.availablePermits}")
            }
        }
        val meta = toastMeta[id] ?: ToastMeta(fileName, total).also { toastMeta[id] = it }
        meta.done = new
        val doneSum = toastMeta.values.sumOf { it.done }
        val totalSum = toastMeta.values.sumOf { it.total }
        val first = toastMeta.values.firstOrNull()?.name ?: fileName
        val line1 = if (toastMeta.size > 1) "$first 等 ${toastMeta.size} 个任务" else first
        val statusLine = "${formatSize(doneSum)} / ${formatSize(totalSum)}"
        // 速度直接取下载页显示的那份（SpeedRecorder 的 250ms 窗口测速）之和，
        // 不再另算一套 EMA —— 否则两处数字永远对不上
        val speed = _stats.value.values.sumOf { it.speed }
        val speedText = if (showSpeedProvider() && speed > 0) "${formatSpeed(speed)} ↓" else ""
        WindowsToastNotifier.updateProgress(doneSum, totalSum, line1, statusLine, speedText, "正在下载")
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) { value /= 1024; i++ }
        return if (i >= 2) "%.2f %s".format(value, units[i]) else "%.0f %s".format(value, units[i])
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return ""
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSec.toDouble()
        var i = 0
        while (value >= 1024 && i < units.size - 1) {
            value /= 1024
            i++
        }
        return String.format("%.1f %s", value, units[i])
    }

    /**
     * 刷新每路在飞分片的瞬时速度（抢占判定的依据，不产生日志）。
     * 每个采样周期调一次：`lastBps = 本周期新增字节 / 本周期耗时`。
     */
    private fun sampleInflightChunks(diag: Map<String, InflightChunk>) {
        val now = System.currentTimeMillis()
        for (d in diag.values) {
            val bytes = d.bytes.get()
            d.lastBps = (bytes - d.lastBytes).coerceAtLeast(0L) * 1000 / (now - d.lastAtMs).coerceAtLeast(1L)
            d.lastBytes = bytes
            d.lastAtMs = now
        }
    }

    /**
     * ★ 慢连接抢占（永久逻辑）：把「跑得远低于同伴」的在飞分片换到新连接上续传。
     *
     * 判定阈值 = max(设置里的绝对下限, 本任务平均单连接速度 / 2)：
     * 用相对值是为了适配不同 CDN 的限速档次（夸克单连接几十 KB/s、迅雷更低），绝对下限兜住
     * 「收尾只剩一两路、平均值被自己拉低」的退化情况。命中后只把 [InflightChunk.preempt] 置位，
     * 由 ChunkDownloader 断开连接并从已收字节续传——不丢数据、不退避、不改变对外结果。
     *
     * 开关 / 阈值 / 判定时长均取自 [DownloadTuning]（默认 开 / 12KB/s / 15s，与改动前一致）；
     * 关闭后本方法直接返回，不再做抢占。
     */
    private fun preemptSlowChunks(
        id: Long,
        downloaded: Long,
        elapsedMs: Long,
        workers: Int,
        diag: Map<String, InflightChunk>
    ) {
        if (!DownloadTuning.preemptEnabled) return
        if (diag.isEmpty()) return
        val avgPerConn = if (elapsedMs > 0 && workers > 0) downloaded * 1000 / elapsedMs / workers else 0L
        val floor = DownloadTuning.preemptFloorBps(avgPerConn)
        val now = System.currentTimeMillis()
        var taken = 0
        for (d in diag.values.sortedBy { it.lastBps }) {
            if (taken >= PREEMPT_PER_TICK) break
            if (d.preemptCount >= PREEMPT_MAX) continue
            if (now - d.lastPreemptAtMs < PREEMPT_COOLDOWN_MS) continue
            // 收尾（在飞 ≤ PREEMPT_ENDGAME_INFLIGHT）时放宽「跑够久」和「剩余够多」两条门槛
            if (diag.size <= PREEMPT_ENDGAME_INFLIGHT) {
                if (d.elapsedMs < PREEMPT_ENDGAME_MIN_AGE_MS) continue
            } else {
                if (d.elapsedMs < DownloadTuning.preemptMinAgeMs) continue
                if (d.size - d.bytes.get() < PREEMPT_MIN_REMAIN) continue
            }
            if (d.lastBps >= floor) continue
            d.preemptCount++
            d.lastPreemptAtMs = now
            d.preempt.set(true)
            taken++
            Log.w(TAG, "runTask: id=$id 抢占慢连接 起点=${d.start} 块=${diagSize(d.size)} 已收=${diagSize(d.bytes.get())} " +
                "瞬时=${formatSpeed(d.lastBps)} 阈值=${formatSpeed(floor)} 第${d.preemptCount}/$PREEMPT_MAX 次（换连接续传）")
        }
    }

    /** 字节数转可读文本（抢占日志用） */
    private fun diagSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2fGB", bytes / 1073741824.0)
        bytes >= 1024L * 1024 -> String.format("%.1fMB", bytes / 1048576.0)
        bytes >= 1024L -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    /**
     * 进度落盘节流：多 worker 并发回调下，每 progressPersistIntervalMs 最多写一次 DB。
     * - force / (total>0 且 new>=total)：完成时强制写，确保最终进度准确；
     * - total<=0（大小未知）时仅按时间节流；
     * - 用 lastAt 的 CAS 保证并发下同一任务只有一个回调写库（避免多线程重复 UPDATE）。
     */
    private suspend fun persistProgressIfDue(
        id: Long,
        new: Long,
        total: Long,
        force: Boolean,
        lastAt: AtomicLong
    ) {
        val now = System.currentTimeMillis()
        val last = lastAt.get()
        if (force || (total > 0 && new >= total) || now - last >= progressPersistIntervalMs) {
            if (lastAt.compareAndSet(last, now)) {
                dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
            }
        }
    }

    /** 完成任务并写入平均速度（字节/秒）：avg = total / 本次运行耗时 */
    private suspend fun completeWithAvg(id: Long, savedPath: String, total: Long) {
        val start = taskStartTimes.remove(id) ?: 0L
        val elapsedSec = ((System.currentTimeMillis() - start) / 1000.0).coerceAtLeast(1.0)
        val avg = if (total > 0 && elapsedSec > 0) (total / elapsedSec).toLong() else 0L
        dao.complete(id, DownloadTaskEntity.STATUS_COMPLETED, savedPath, avg)
    }

    /** 每个任务一把互斥锁：暂停后立即恢复时避免新旧协程并发写分片 */
    private val taskLocks = ConcurrentHashMap<Long, Mutex>()

    /** 任务请求头（Cookie/UA），暂停后恢复仍需使用 */
    private val taskHeaders = ConcurrentHashMap<Long, Map<String, String>>()

    /** 已知文件大小（API 返回，避免探测失败）；-1 表示未知 */
    private val taskSizes = ConcurrentHashMap<Long, Long>()

    /** 镜像主 URL 的回退直连（仅 GitHub 等显式传入）；主 URL 探测失败时整任务切到此 URL 重下 */
    private val taskFallbackUrls = ConcurrentHashMap<Long, String>()

    /** 任务开始时间（毫秒）：完成时计算平均速度用（暂停/恢复会重置，表示最近一次运行段均值） */
    private val taskStartTimes = ConcurrentHashMap<Long, Long>()

    /** 任务下载完成后的清理回调（如删除网盘临时转存文件；下载成功后才触发） */
    private val taskCallbacks = ConcurrentHashMap<Long, suspend () -> Unit>()

    /** 实时下载统计（速度/剩余时间/线程数） */
    private val _stats = MutableStateFlow<Map<Long, DownloadStats>>(emptyMap())
    val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    /** 进度落盘节流（毫秒）：updateProgress 写库会触发全表 Flow 重发 → 主线程全列表重组；
     *  按字节（256KB）节流时高速下载每秒写库几十次，主线程重组洪峰 → ANR。
     *  改为按时间节流落盘，UI 进度由内存 _stats 高频展示、DB 低频持久化（断点续传最多丢几百 ms 进度）。 */
    private val progressPersistIntervalMs = 500L

    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /**
     * 当前解析中的分享链接；解析开始时由 ResolveViewModel 写入，任务入队时若调用方未显式
     * 传入 shareUrl 则用此值兜底填充 task.shareUrl，供「复制分享链接」右键菜单使用。
     */
    @Volatile
    var currentShareUrl: String = ""

    /** 入队并立即开始下载 */
    suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        /** 已知文件大小（字节）；-1 表示未知，需探测 */
        size: Long = -1L,
        /** 下载来源平台标识（按平台应用下载线程数设置）；通用/手动添加传空串 */
        platform: String = "",
        /** 分享链接（用于右键菜单「复制分享链接」）；空串时用 currentShareUrl 兜底 */
        shareUrl: String = "",
        /** 镜像主 URL 不可达时的回退直连（默认空=不回退）；仅 GitHub 等镜像下载传入 */
        fallbackUrl: String = "",
        /** 下载成功完成后的清理回调（如删除网盘临时转存文件）；失败/取消不触发。
         * 注意：必须是最后一个参数（调用点有大量尾随 lambda 用法，放它之后会编译失败）。 */
        onComplete: suspend () -> Unit = {}
    ): Long {
        // 磁力链接只能交给 Gopeed 引擎（见 magnetBlockReason）；平台标识同时落库，便于事后区分
        val isMagnet = MagnetLink.isMagnet(url)
        val taskPlatform = if (isMagnet) DownloadPlatform.MAGNET else platform
        // 文件名兜底：空白时从 URL 推导，避免保存时变成时间戳。
        // 磁力链接走 dn= 里自带的显示名（元数据到手后再换成真正的种子名，见 completeEngineTask）
        val safeName = fileName.ifBlank {
            if (isMagnet) {
                MagnetLink.displayName(url)
            } else {
                url.substringAfterLast('/').substringBefore('?')
                    .ifBlank { "download_${System.currentTimeMillis()}" }
            }
        }
        val effectiveShareUrl = shareUrl.ifBlank { currentShareUrl }
        Log.d(TAG, "enqueue: origin=${LogRedactor.url(url)} fileName=$safeName headers=${headers.keys} size=$size shareUrl=$effectiveShareUrl")
        val id = try {
            dao.insert(
                DownloadTaskEntity(
                    url = url,
                    fileName = safeName,
                    requestHeadersJson = encodeHeaders(headers),
                    platform = taskPlatform,
                    shareUrl = effectiveShareUrl
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "enqueue: dao.insert FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
        Log.d(TAG, "enqueue: dao.insert returned id=$id, calling start()")
        // 保存请求头（Cookie/UA），暂停后恢复仍需携带
        if (headers.isNotEmpty()) taskHeaders[id] = headers
        if (size > 0) taskSizes[id] = size
        if (fallbackUrl.isNotBlank()) taskFallbackUrls[id] = fallbackUrl
        taskCallbacks[id] = onComplete
        // 磁力但引擎不可用：立刻落一条**带原因的失败任务**并结束。若照常走内置下载器，
        // 它会把 magnet: 当普通 URL 发 HTTP 请求，最后抛一个和「该去导入内核」毫无关系的协议错误。
        val blocked = magnetBlockReason(isMagnet)
        if (blocked != null) {
            Log.w(TAG, "磁力任务被拦下：id=$id 原因=$blocked")
            taskCallbacks.remove(id)
            // 不会有下载协程来消费这几份内存数据了，直接清掉
            taskHeaders.remove(id)
            taskSizes.remove(id)
            taskFallbackUrls.remove(id)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, blocked)
            return id
        }
        // 引擎分流：选了 Gopeed 且内核已导入时由外部引擎执行（GitHub 除外，见 shouldUseEngine）
        if (shouldUseEngine(taskPlatform)) {
            startViaEngine(id, url, safeName, headers, taskPlatform)
        } else {
            start(id, headers)
        }
        return id
    }

    /**
     * 磁力链接是否可下：返回 null = 可以（交给引擎），否则是给用户看的拦截原因。
     *
     * 磁力（BT）只有 Gopeed 内核里有实现（内核注册了 hls/http/bt/ed2k 四个协议），内置分片下载器
     * 是纯 HTTP Range 实现，喂它 magnet: 只会得到没意义的报错 —— 所以这里宁可拦住并说清楚要做什么。
     */
    private fun magnetBlockReason(isMagnet: Boolean): String? {
        if (!isMagnet) return null
        if (!GopeedEngine.isInstalled()) {
            return "磁力下载需要先在「设置 → 下载 → 下载引擎」里导入 Gopeed 内核"
        }
        if (!engineEnabledProvider()) {
            return "磁力下载需要先在「设置 → 下载 → 下载引擎」里把下载引擎切换为 Gopeed"
        }
        return null
    }

    /**
     * 重新下载：用原直链新建任务（任务卡右键菜单「重新下载」）。
     * 先做 Range 探测校验直链有效性：403/404/网络错误视为直链已过期，返回 false 由 UI 提示。
     * 磁力链接跳过探测——getTotalSize 是 HTTP Range 探测，喂它 magnet: 必然失败，
     * 会让「重新下载」永远提示直链失效；磁力直接重新入队，引擎不可用时由 enqueue 的拦截说明原因。
     */
    suspend fun redownload(id: Long): Boolean {
        val task = dao.get(id) ?: return false
        val headers = loadPersistedHeaders(id)
        if (!MagnetLink.isMagnet(task.url)) {
            val valid = runCatching { downloader.getTotalSize(task.url, headers) != null }.getOrDefault(false)
            if (!valid) return false
        }
        enqueue(task.url, task.fileName, headers, task.totalSize, task.platform)
        return true
    }

    /** 开始/恢复下载（断点续传） */
    fun start(id: Long, headers: Map<String, String> = emptyMap()) {
        // 引擎任务：转发「继续」给 Gopeed（绝不能让内置下载器用同一 URL 再下一次）
        val engineId = engineTaskIds[id]
        if (engineId != null) {
            resumeEngineTask(id, engineId)
            return
        }
        // 恢复时未传 headers：沿用入队时保存的（Cookie/UA 对直链下载是必需的）
        val effectiveHeaders = headers.ifEmpty { taskHeaders[id] ?: emptyMap() }
        Log.d(TAG, "start: id=$id headers=${effectiveHeaders.keys}")
        synchronized(jobsLock) {
            // 原子注册：检查 + 占位 + launch + complete 在同一锁内完成，
            // pause/remove 要么拿到已注册的 job，要么拿不到（视为未运行）
            val existing = activeJobs[id]
            if (existing != null) {
                // job 仍活跃（正在下载/收尾）：忽略本次 start，避免重复启动
                if (existing.isCompleted && existing.getCompleted().isActive) return
                // job 已结束但 finally 尚未清理（暂停后立即恢复的残留）：
                // 移除旧引用，继续注册新 job，保证"点开始"立即生效
                activeJobs.remove(id)
            }
            val deferred = CompletableDeferred<Job>()
            activeJobs[id] = deferred
            val job = scope.launch {
                try {
                    // 任务开始：有任务在下载时保持前台服务（避免切后台限速/进程被杀）
                    onTaskStarted(id)
                    // 任务级互斥：同一任务串行执行，暂停后立刻恢复不会并发写分片
                    taskLocks.getOrPut(id) { Mutex() }.withLock {
                        val restoredHeaders = if (effectiveHeaders.isNotEmpty()) {
                            effectiveHeaders
                        } else {
                            loadPersistedHeaders(id)
                        }
                        if (restoredHeaders.isNotEmpty()) taskHeaders[id] = restoredHeaders
                        runTaskWithRetry(id, restoredHeaders)
                    }
                } catch (e: CancellationException) {
                    // 主动暂停/删除：part 文件保留（或由 remove 清理）；状态已由调用方设置
                    _stats.update { it - id }
                } catch (e: Exception) {
                    _stats.update { it - id }
                    // 协程已被取消（暂停/删除）：不标记失败，避免覆盖 PAUSED 状态
                    if (isTaskActive()) {
                        Log.e(TAG, "task $id failed: ${e.message ?: e.javaClass.simpleName}", e)
                        dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                        dao.updateError(id, e.message ?: e.javaClass.simpleName)
                    } else {
                        Log.w(TAG, "task $id cancelled: ${e.message}")
                    }
                } finally {
                    // 任务结束（成功/失败/暂停/删除）：无任务时停止前台服务
                    onTaskFinished(id)
                    // 只移除自己注册的 deferred：
                    // 若暂停后立即恢复（新 job 已注册到同一 id），不能误删新任务的注册，
                    // 否则新任务将无法再被暂停/删除（后台继续下载）
                    synchronized(jobsLock) {
                        if (activeJobs[id] === deferred) activeJobs.remove(id)
                    }
                    // 注意：taskLocks 不在此清理 —— 若新任务已 getOrPut 拿到锁，
                    // 旧任务 finally 的 remove 会误删新任务的锁导致并发写分片
                }
            }
            // launch 是同步返回 Job 的，锁内 complete，pause/remove 的 await 立即返回
            deferred.complete(job)
        }
    }

    /** 任务开始/结束计数 + Windows 系统集成（阻止休眠 / 通知中心进度） */
    private suspend fun onTaskStarted(id: Long) {
        activeTaskCount.getAndIncrement()
        if (keepAwakeProvider()) WindowsKeepAwake.acquire()
        WindowsToastNotifier.sessionStart()
    }

    private fun onTaskFinished(id: Long) {
        toastMeta.remove(id)
        if (activeTaskCount.decrementAndGet() <= 0) {
            activeTaskCount.set(0)
            WindowsKeepAwake.release()
            WindowsToastNotifier.sessionEnd()
        }
    }

    /** 暂停下载（保留 part 文件与请求头） */
    fun pause(id: Long) {
        Log.d(TAG, "pause: id=$id")
        // 引擎任务：转发「暂停」给 Gopeed，进度以引擎侧为准
        val engineId = engineTaskIds[id]
        if (engineId != null) {
            pauseEngineTask(id, engineId)
            return
        }
        // 立即中断该任务所有分片网络请求（不依赖协程取消传播，阻塞 IO 马上停止）
        downloader.cancelCalls(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        _stats.update { it - id }
        scope.launch {
            // 等协程真正退出（确保没有半截写入）后，以磁盘 part/seg 真实大小为准回写进度：
            // 暂停瞬间最后一次 onBytes 可能被取消丢弃，DB 落后于磁盘 → 恢复时进度回跳
            deferred?.let { runCatching { it.await().cancelAndJoin() } }
            val real = chunkDirOf(id).listFiles()
                ?.filter {
                    it.name.startsWith("part_") ||
                        (it.name.startsWith("seg_") && it.name.endsWith(".part"))
                }
                ?.sumOf { it.length() } ?: 0L
            val t = dao.get(id)
            if (t != null && real > t.downloadedSize) {
                dao.updateProgress(id, DownloadTaskEntity.STATUS_PAUSED, real, t.totalSize)
            } else {
                dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
            }
        }
    }

    /**
     * 删除任务：取消下载 + 清 DB + 清 part 文件。
     * @param deleteLocal 同时删除已保存到本地的文件（savePath）
     */
    fun remove(id: Long, deleteLocal: Boolean = false) {
        Log.d(TAG, "remove: id=$id deleteLocal=$deleteLocal")
        // 引擎任务：顺带通知引擎删除（失败不阻断本地清理）
        engineTaskIds.remove(id)?.let { engineId ->
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { GopeedEngine.deleteTask(engineId) } }
                    .onFailure { Log.w(TAG, "通知引擎删除任务失败：${it.message}") }
            }
        }
        engineDestPaths.remove(id)
        finishEngineTask(id)
        // 立即中断该任务所有分片网络请求
        downloader.cancelCalls(id)
        _stats.update { it - id }
        taskHeaders.remove(id)
        taskFallbackUrls.remove(id)
        // 删除任务同样触发清理回调（如删除网盘临时转存文件）：
        // 用户放弃下载时云盘里已转存的临时文件也应一并清理
        val cleanup = taskCallbacks.remove(id)
        taskLocks.remove(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        scope.launch {
            // 若任务正在下载：取消并等待协程真正退出，
            // 确保没有后台残留下载、part 文件无 fd 占用（否则删了仍占空间）
            if (deferred != null) {
                deferred.await().cancelAndJoin()
            }
            if (deleteLocal) {
                dao.get(id)?.savePath?.let {
                    val deleted = DownloadSaver.delete(it)
                    Log.d(TAG, "remove: id=$id 删除本地文件 ${if (deleted) "成功" else "失败/未找到"} ($it)")
                }
            }
            dao.delete(id)
            chunkDirOf(id).deleteRecursively()
            // 删除任务后清理云盘转存（与下载成功完成同语义）；失败不阻断
            cleanup?.let { runCatching { it() } }
        }
    }

    // ---------- 外部 Gopeed 引擎（exe 子进程 + 本地 HTTP API）----------

    /** 本地任务 id → 引擎任务 ID（内存快判；持久化真源是 download_task.engineTaskId） */
    private val engineTaskIds = ConcurrentHashMap<Long, String>()

    /** 引擎任务的预期落盘绝对路径（完成时写库；进程重启后回退为「当前下载目录 + 文件名」） */
    private val engineDestPaths = ConcurrentHashMap<Long, String>()

    /** 已计入保活/通知计数的引擎任务：保证 onTaskStarted / onTaskFinished 恰好各配一次 */
    private val engineRunningIds = ConcurrentHashMap.newKeySet<Long>()

    private val engineSyncLock = Any()
    private var engineSyncJob: Job? = null

    /** 引擎进度轮询间隔（毫秒）：本地 HTTP 调用，开销极小 */
    private val engineSyncIntervalMs = 1000L

    /**
     * 上次下发给引擎的运行配置签名（并发上限 + 连接数 + 代理，见 [GopeedEngine.settingsSignature]）。
     * null 表示尚未下发过；签名变化（含用户在设置里改代理/线程数）时重新下发。
     */
    @Volatile
    private var lastEngineConfigKey: String? = null

    /**
     * 把「最大同时下载数 + 下载线程数 + 代理」下发给引擎，并顺带下发 BT 不做种。
     *
     * 引擎内的并发是原生调度：超出上限的任务被置为 `wait` 排队，有任务结束时引擎自己补位。
     * 但「调大上限」时引擎不会主动放行队列，所以在 [GopeedEngine.applyRuntimeConfig] 里按空位数补唤醒。
     * 失败不阻断下载（引擎用库里的旧值），只有引擎在跑却拿不到值才算失败。
     */
    private suspend fun syncEngineRuntimeConfig() {
        val desired = concurrencyProvider().coerceAtLeast(1)
        // 签名：并发上限 + 设置里影响引擎的其它项（线程数/代理）。任一项变了都要重新下发。
        val key = "$desired|${GopeedEngine.settingsSignature()}"
        if (key == lastEngineConfigKey) return
        val applied = runCatching {
            withContext(Dispatchers.IO) { GopeedEngine.applyRuntimeConfig(desired) }
        }.onFailure { Log.w(TAG, "下发引擎运行配置失败（并发/连接数/代理）：${it.message}") }.getOrNull()
        // null = 引擎没在跑（下次启动会带上新值）；此时不写缓存，等引擎起来后会重试
        if (applied != null) lastEngineConfigKey = key
    }

    init {
        // 进程重启后接管历史引擎任务：必要时拉起引擎并继续轮询进度
        scope.launch { ensureEngineSync() }
    }

    /**
     * 新任务是否交给外部引擎：平台不是 GitHub（引擎无法按镜像回退）、设置里选了 Gopeed、
     * 且内核已导入。放在 enqueue 末尾统一判断，所有调用点自动生效。
     */
    private fun shouldUseEngine(platform: String): Boolean =
        platform != DownloadPlatform.GITHUB && engineEnabledProvider() && GopeedEngine.isInstalled()

    /** 引擎落盘基准目录：优先设置里的自定义下载目录，否则系统「下载」目录 */
    private fun engineBaseDir(): File {
        val custom = saveDirProvider()?.takeIf { it.isNotBlank() }
        return if (custom != null) File(custom) else DesktopActions.defaultDownloadDir
    }

    /** 新建引擎任务（enqueue 的引擎分流入口） */
    private suspend fun startViaEngine(
        id: Long,
        url: String,
        fileName: String,
        headers: Map<String, String>,
        platform: String
    ) {
        val base = engineBaseDir()
        // 与内置下载器同一套路径净化规则：防目录穿越、非法字符替换、名字截断
        val safe = DownloadPathPolicy.sanitize(fileName, "download_${System.currentTimeMillis()}")
        val destDir = if (safe != null && safe.relativeDirectory.isNotBlank()) {
            File(base, safe.relativeDirectory)
        } else {
            base
        }
        val name = safe?.fileName ?: fileName
        // 磁力（BT）在引擎解析出元数据前没有名字：交给引擎自己命名（真实种子名稍后写回本地记录）；
        // 连接数是 http 协议的分片并发参数，BT 不吃
        val isMagnet = platform == DownloadPlatform.MAGNET
        val engineId = try {
            withContext(Dispatchers.IO) {
                GopeedEngine.start(base)
                // 在建首个任务前把「最大同时下载数」/不做种推给引擎，让引擎从一开始就按上限排队
                syncEngineRuntimeConfig()
                GopeedEngine.createTask(
                    url = url,
                    saveDir = destDir.absolutePath,
                    fileName = if (isMagnet) "" else name,
                    headers = headers,
                    // 分片并发沿用按平台的线程数设置（映射到引擎的 extra.connections）
                    connections = if (isMagnet) 0 else threadProvider(platform),
                    label = id.toString()
                )
            }
        } catch (e: Exception) {
            // 建任务失败按失败任务落库，不静默回退内置（避免用户以为在用引擎、实际在用内置）
            Log.e(TAG, "startViaEngine: id=$id 创建引擎任务失败：${e.message}", e)
            dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            dao.updateError(id, e.message ?: e.javaClass.simpleName)
            return
        }
        engineTaskIds[id] = engineId
        engineDestPaths[id] = File(destDir, name).absolutePath
        runCatching { dao.updateEngineTaskId(id, engineId) }
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        beginEngineTask(id)
        Log.i(TAG, "startViaEngine: id=$id engineId=$engineId dest=${engineDestPaths[id]}")
    }

    /** 转发暂停给引擎 */
    private fun pauseEngineTask(id: Long, engineId: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GopeedEngine.pauseTask(engineId) } }
                .onFailure { Log.w(TAG, "引擎暂停失败：${it.message}") }
            dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
            finishEngineTask(id)
        }
    }

    /** 转发继续给引擎 */
    private fun resumeEngineTask(id: Long, engineId: String) {
        scope.launch {
            val ok = runCatching { withContext(Dispatchers.IO) { GopeedEngine.continueTask(engineId) } }
            if (ok.isFailure) {
                val msg = ok.exceptionOrNull()?.message ?: "引擎继续失败"
                Log.w(TAG, "引擎继续失败：$msg")
                dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                dao.updateError(id, msg)
                return@launch
            }
            taskStartTimes[id] = System.currentTimeMillis()
            dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
            beginEngineTask(id)
        }
    }

    /** 计入保活/通知计数（幂等）并确保同步协程在跑 */
    private fun beginEngineTask(id: Long) {
        if (engineRunningIds.add(id)) {
            scope.launch { onTaskStarted(id) }
        }
        ensureEngineSync()
    }

    /** 结束计数（幂等）并从实时统计里移除 */
    private fun finishEngineTask(id: Long) {
        if (engineRunningIds.remove(id)) onTaskFinished(id)
        _stats.update { it - id }
    }

    /** 确保引擎同步协程在跑（单例，幂等） */
    private fun ensureEngineSync() {
        synchronized(engineSyncLock) {
            if (engineSyncJob?.isActive == true) return
            engineSyncJob = scope.launch { engineSyncLoop() }
        }
    }

    /**
     * 引擎进度轮询：逐条查 `/status` 并回写本地任务（Gopeed 没有推送订阅，
     * 上游 Android 版同样是轮询；本地 HTTP 开销极小，此处 1s 一次）。
     * 没有可同步的任务时退出循环，下次建任务由 [beginEngineTask] 重新拉起。
     */
    private suspend fun engineSyncLoop() {
        while (isTaskActive()) {
            val syncable = runCatching { dao.listSyncableEngineTasks() }.getOrDefault(emptyList())
            if (syncable.isEmpty()) return
            // 引擎没在跑（应用重启 / 手动停过）时按需拉起
            if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) {
                val started = runCatching {
                    withContext(Dispatchers.IO) { GopeedEngine.start(engineBaseDir()) }
                }.isSuccess
                if (!started) {
                    Log.w(TAG, "引擎同步：启动引擎失败，稍后重试")
                    delay(engineSyncIntervalMs)
                    continue
                }
                // 引擎刚拉起：重置缓存，确保运行配置重新推送（bolt 里可能是旧值）
                lastEngineConfigKey = null
            }
            // 引擎运行中：按需同步「最大同时下载数」（设置变化时 1s 内生效）
            syncEngineRuntimeConfig()
            for (task in syncable) {
                val engineId = task.engineTaskId
                // 内存快判表回填：进程重启后也要能暂停/继续/删除引擎任务
                engineTaskIds[task.id] = engineId
                val viewResult = runCatching {
                    withContext(Dispatchers.IO) { GopeedEngine.taskStatus(engineId) }
                }
                val view = viewResult.getOrNull()
                if (view == null) {
                    Log.w(TAG, "引擎同步：查询任务 $engineId 失败：${viewResult.exceptionOrNull()?.message}")
                    continue
                }
                when (view.status) {
                    GopeedEngine.TaskStatus.DONE -> completeEngineTask(task, view)
                    GopeedEngine.TaskStatus.ERROR -> {
                        dao.updateStatus(task.id, DownloadTaskEntity.STATUS_FAILED)
                        dao.updateError(task.id, "Gopeed 引擎下载失败")
                        finishEngineTask(task.id)
                    }
                    GopeedEngine.TaskStatus.PAUSE -> {
                        dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PAUSED)
                        finishEngineTask(task.id)
                    }
                    GopeedEngine.TaskStatus.WAIT -> {
                        // 超出「最大同时下载任务数」，被引擎排在队列里等空位
                        // → 本地记成「等待中」。★ 不能落到下面的 else：那会显示成 0% 的「下载中」，看着像卡死。
                        // 排队期间进度不动、状态也只写一次，避免每秒重复写库触发无谓的 UI 刷新。
                        if (task.status != DownloadTaskEntity.STATUS_PENDING) {
                            dao.updateStatus(task.id, DownloadTaskEntity.STATUS_PENDING)
                        }
                        _stats.update { it - task.id }
                    }
                    else -> {
                        // ready / running：真正在传输：更新进度、速度、通知
                        dao.updateProgress(
                            task.id,
                            DownloadTaskEntity.STATUS_DOWNLOADING,
                            view.downloaded,
                            view.total
                        )
                        val remain = if (view.speed > 0 && view.total > view.downloaded) {
                            (view.total - view.downloaded) * 1000 / view.speed
                        } else {
                            -1L
                        }
                        // 显示**实际**并发连接数（配置里传了 32 不代表引擎真开了 32 条连接）；
                        // 取不到（老版本内核无 /stats）时回退到设置里的线程数
                        val realConnections = runCatching {
                            withContext(Dispatchers.IO) { GopeedEngine.taskConnections(engineId) }
                        }.getOrNull()
                        _stats.update {
                            it + (task.id to DownloadStats(
                                speed = view.speed,
                                remainMillis = remain,
                                chunkCount = realConnections ?: threadProvider(task.platform)
                            ))
                        }
                        notifyProgress(task.id, task.fileName, view.downloaded, view.total)
                    }
                }
            }
            delay(engineSyncIntervalMs)
        }
    }

    /** 引擎侧任务完成：写完成态 + 平均速度，触发清理回调并收尾保活 */
    private suspend fun completeEngineTask(task: DownloadTaskEntity, view: GopeedEngine.TaskView) {
        val total = if (view.total > 0) view.total else task.totalSize
        engineDestPaths.remove(task.id)
        // 磁力（BT）任务的真实名字/落盘结构只有引擎解析完元数据才知道：
        // 引擎侧用种子名建目录 ⇒ 多文件种子落成 <下载目录>/<种子名>/...，单文件种子落成 <下载目录>/<种子名>。
        // 本地记录里的 fileName 是元数据到手前自己起的显示名，直接拿它拼路径会指向不存在的文件
        // （打开文件 / 删除本地文件 / 重新下载全会错）。非磁力任务读一次详情也无害，故不做分支区别对待。
        val detail = runCatching {
            withContext(Dispatchers.IO) { GopeedEngine.taskDetail(task.engineTaskId) }
        }.onFailure { Log.w(TAG, "读取引擎任务详情失败，用本地文件名兜底：id=${task.id} ${it.message}") }.getOrNull()
        val realName = detail?.name?.takeIf { it.isNotBlank() } ?: task.fileName
        val savedPath = File(engineBaseDir(), realName).absolutePath
        completeWithAvg(task.id, savedPath, total)
        if (realName != task.fileName) {
            // 磁力：把界面上占位的显示名换成真正的种子名（失败只记日志，不影响已完成状态）
            runCatching { dao.updateFileName(task.id, realName) }
                .onFailure { Log.w(TAG, "回写种子名失败：id=${task.id} ${it.message}") }
        }
        taskCallbacks.remove(task.id)?.let { runCatching { it() } }
        taskHeaders.remove(task.id)
        taskSizes.remove(task.id)
        taskFallbackUrls.remove(task.id)
        finishEngineTask(task.id)
        Log.i(
            TAG,
            "引擎任务完成：id=${task.id} path=$savedPath name=$realName " +
                "files=${detail?.fileCount ?: -1} folder=${detail?.folder ?: false}"
        )
    }

    // ---------- 内部实现 ----------

    private fun encodeHeaders(headers: Map<String, String>): String {
        val json = JSONObject().apply { headers.forEach { (name, value) -> put(name, value) } }.toString()
        return credentialCipher.encrypt(json, "download.requestHeaders")
    }

    private suspend fun loadPersistedHeaders(id: Long): Map<String, String> {
        val stored = dao.get(id)?.requestHeadersJson.orEmpty()
        if (stored.isBlank()) return emptyMap()
        return runCatching {
            val jsonText = credentialCipher.decrypt(stored, "download.requestHeaders")
            val json = JSONObject(jsonText)
            buildMap {
                json.keys().forEach { name -> put(name, json.getString(name)) }
            }.also {
                if (!credentialCipher.isEncrypted(stored)) {
                    dao.updateRequestHeaders(id, encodeHeaders(it))
                }
            }
        }.getOrElse {
            dao.updateRequestHeaders(id, encodeHeaders(emptyMap()))
            emptyMap()
        }
    }

    /** 当前协程是否仍活跃（暂停/删除触发取消后为 false） */
    private suspend fun isTaskActive(): Boolean = coroutineContext[Job]?.isActive == true

    /** 等待并发许可：当前下载任务数 >= 上限时轮询等待（暂停/取消可退出等待） */
    private suspend fun awaitConcurrencySlot() {
        val max = concurrencyProvider().coerceAtLeast(1)
        while (isTaskActive() && activeDownloads.get() >= max) {
            delay(300)
        }
    }

    /**
     * 执行任务并支持失败自动重试（断点续传，part 文件保留）。
     * 同时负责「最大同时下载任务数」并发许可的获取/释放。
     */
    private suspend fun runTaskWithRetry(id: Long, headers: Map<String, String>) {
        var attempts = 0
        val maxRetries = retryCountProvider().coerceIn(0, 10)
        while (true) {
            // 并发许可：排队等待，直到有空闲下载槽位（或任务被暂停/取消）
            awaitConcurrencySlot()
            if (!isTaskActive()) return
            activeDownloads.incrementAndGet()
            try {
                try {
                    runTask(id, headers)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempts++
                    if (isTaskActive() && attempts <= maxRetries) {
                        Log.d(TAG, "runTaskWithRetry: id=$id 失败，自动重试 $attempts/$maxRetries：${e.message}")
                        // 逐次递增延迟，避免失败风暴
                        delay(1200L * attempts)
                    } else {
                        throw e
                    }
                }
            } finally {
                activeDownloads.decrementAndGet()
            }
        }
    }

    private suspend fun runTask(id: Long, headers: Map<String, String>) {
        // 协程已被取消（暂停/删除）：直接退出，不写状态
        if (!isTaskActive()) return
        var task = dao.get(id) ?: return
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        taskStartTimes[id] = System.currentTimeMillis()
        Log.d(TAG, "runTask: id=$id fileName=${task.fileName}")

        // HLS（m3u8 转码流，如 UC play）：不走 Range 分片，直接拉分片合并
        if (task.url.contains(".m3u8", true) || task.url.contains(".m3u", true)) {
            Log.d(TAG, "runTask: id=$id HLS 转码流下载 origin=${LogRedactor.url(task.url)}")
            hlsDownload(id, task, headers)
            return
        }

        // 总大小以服务器探测为准（Range0-0 的 Content-Range 是真实总大小），
        // 避免各平台传入的 size 与实际不符导致分片区间错误 → 文件截断/膨胀损坏
        // 镜像主 URL 探测失败且带回退直连时（GitHub 下载），整任务切到原始直连重下，避免镜像挂掉整任务失败
        val fallbackUrl = taskFallbackUrls[id]
        var probedSize = downloader.getTotalSize(task.url, headers)
        if (probedSize == null && !fallbackUrl.isNullOrBlank()) {
            Log.w(TAG, "runTask: id=$id 镜像主 URL 不可达，回退原始直连下载")
            task = task.copy(url = fallbackUrl)
            probedSize = downloader.getTotalSize(task.url, headers)
        }
        val total = probedSize
            ?: taskSizes[id]?.takeIf { it > 0 }
        if (total == null) {
            // 服务器不返回文件大小（Range/Content-Length 均缺失）：降级为流式下载（开放区间 Range）
            Log.w(TAG, "runTask: id=$id 无法获取总大小，降级流式下载 origin=${LogRedactor.url(task.url)}")
            streamDownload(id, task, headers)
            return
        }
        Log.d(TAG, "getTotalSize: id=$id total=$total origin=${LogRedactor.url(task.url)}")
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, total)
        // 取到大小后再次检查取消（暂停可能发生在 getTotalSize 期间）
        if (!isTaskActive()) return

        val threadCount = threadProvider(task.platform).coerceAtLeast(1)
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        val planFile = File(chunkDir, "plan.txt")
        // ★ 分片计划：part_$i 按索引命名，但区间由 chunks/total 推导 ⇒ 计划一变旧 part 就错位、不可信。
        //   规则：total 变了（文件被换掉）必须整目录重下；**只是线程数变了则沿用旧计划**，
        //   避免"改一下线程数就把已下载的几 GB 全删掉重下"（这正是"自动重新下载"的来源之一）。
        val storedPlan = planFile.takeIf { it.exists() }?.readText().orEmpty()
        val storedTotal = PLAN_TOTAL.find(storedPlan)?.groupValues?.get(1)?.toLongOrNull()
        val storedChunks = PLAN_CHUNKS.find(storedPlan)?.groupValues?.get(1)?.toIntOrNull()
        val storedMain = PLAN_MAIN.find(storedPlan)?.groupValues?.get(1)?.toIntOrNull()
        val canReusePlan = storedTotal != null && storedTotal == total &&
            storedChunks != null && storedChunks > 0 &&
            storedMain != null && storedMain in 1..storedChunks
        val chunkCount: Int
        val mainPoolCount: Int
        if (canReusePlan) {
            chunkCount = storedChunks!!
            mainPoolCount = storedMain!!
            if (chunkCount != chunkCountFor(total, threadCount)) {
                Log.i(TAG, "runTask: id=$id 线程数已改，沿用旧分片计划续传（chunks=$chunkCount）")
            }
        } else {
            if (storedPlan.isNotBlank()) {
                Log.w(TAG, "runTask: id=$id 分片计划不可复用（旧 total=$storedTotal，新 total=$total），清空旧 part 重下")
                chunkDir.deleteRecursively()
                chunkDir.mkdirs()
            }
            chunkCount = chunkCountFor(total, threadCount)
            mainPoolCount = (chunkCount * 0.7).toInt().coerceIn(1, chunkCount) // 主池片数（70%）
            planFile.writeText("chunks=$chunkCount total=$total main=$mainPoolCount")
        }
        val chunkSize = ceil(total.toDouble() / chunkCount).toLong()
        val elasticStart = mainPoolCount * chunkSize                          // 弹性区起始字节
        // 有效并发：仅迅雷（CDN 对单文件并发 Range 有阈值，约 8 个，超过会降级 200 整文件）封顶安全上限；
        // 其他平台保持用户设置的线程数（满并发）
        val isXunlei = headers["User-Agent"]?.contains("xunlei", ignoreCase = true) == true ||
            task.url.contains("xunlei", ignoreCase = true)
        val effectiveWorkers = if (isXunlei) {
            min(threadCount, RANGE_WORKERS_CAP).coerceAtLeast(1)
        } else {
            threadCount.coerceAtLeast(1)
        }
        // ★ 实际 worker 再钳一道「全进程在飞上限」：真实并行度还受内存预算约束，超出的 worker
        //   只会排队等信号量、白白多占协程与排队 Call；钳掉后吞吐不变（分片盈余仍由任务池 + 弹性区提供）。
        //   注意：threadCount 仍原样传给 chunkCountFor —— plan.txt 签名不能变，否则断点续传失效。
        val actualWorkers = min(effectiveWorkers, MAX_INFLIGHT_CHUNKS)
        Log.d(TAG, "分片规划: id=$id chunks=$chunkCount main=$mainPoolCount elasticStart=$elasticStart " +
            "size=$chunkSize threads=$threadCount effectiveWorkers=$effectiveWorkers " +
            "actualWorkers=$actualWorkers inflightCap=$MAX_INFLIGHT_CHUNKS isXunlei=$isXunlei")

        // 注册实时统计：线程数 = 实际并发（受安全上限与内存预算约束）
        _stats.update { it + (id to DownloadStats(0L, -1L, actualWorkers)) }

        // 统计已有 part/seg 大小（断点续传起点；主池 + 弹性区均按磁盘真实长度）
        val downloaded = AtomicLong(0)
        (0 until mainPoolCount).forEach { i ->
            downloaded.addAndGet(File(chunkDir, "part_$i").length())
        }
        chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
            ?.forEach { downloaded.addAndGet(it.length()) }
        // ★ 钳制到 total：防旧 job 残留累加导致显示"已下载 > 总大小"
        val init = minOf(downloaded.get(), total)
        downloaded.set(init)
        // ★ 以**磁盘真实长度**为准双向回写 DB：
        //   - init > DB：恢复时 DB 滞后于磁盘（暂停瞬间未上报的字节）→ 避免进度回跳；
        //   - init < DB：DB 残留了更高的旧值（例如上一次合并失败/中断后留下的 100%）→
        //     必须压下来，否则界面会一直显示 100% 却实际在重新下载，看起来就是「下载到 100% 卡死」。
        if (init != task.downloadedSize) {
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, init, total)
        }
        val lastPersistAt = AtomicLong(0L)
        val speedRecorder = SpeedRecorder()

        // ---------- 任务池（主池 70% 等分）+ 弹性区（30%，空闲线程中点劈分） ----------
        val results = arrayOfNulls<ChunkResult?>(mainPoolCount)
        val nextIdx = AtomicInteger(0)
        val fallback = AtomicBoolean(false)              // 任一分片检测到「服务器忽略 Range」→ 整任务回退单流
        val failReason = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val rangeIgnoredCount = AtomicInteger(0)         // RANGE_IGNORED 累计次数（偶发 200 容忍）

        // ★ 弹性区分配器（IDM 式动态分片）：按字节顺序发块、区间物理相邻，替代中点劈分（根治中后段掉速）。
        //   续传：不完整 seg 删除重下；完整 seg 前缀推进 nextStart（弹性区按序分配，完成块天然是字节前缀）。
        //   块大小随实时速度自适应并尾部收缩，保证全部连接忙到最后一块，消除"末尾只剩少数大块 → 拖尾特别慢"。
        val elasticAllocator = ElasticAllocator(total, elasticStart, actualWorkers, chunkSize)
        if (elasticStart < total) {
            // 不完整 seg 删除（重下）
            chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }?.forEach { f ->
                val name = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = name.substringBefore('_').toLongOrNull() ?: return@forEach
                val e = name.substringAfter('_').toLongOrNull() ?: return@forEach
                if (f.length() < (e - s + 1)) f.delete()
            }
            // 推进到已完整前缀末尾（只前进，跳过已下载弹性块）
            val doneSegs = chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }
                ?.mapNotNull { f ->
                    val name = f.name.removePrefix("seg_").removeSuffix(".part")
                    val s = name.substringBefore('_').toLongOrNull() ?: return@mapNotNull null
                    val e = name.substringAfter('_').toLongOrNull() ?: return@mapNotNull null
                    if (f.length() >= (e - s + 1)) s to e else null
                }?.sortedBy { it.first } ?: emptyList()
            var resumeNext = elasticStart
            for ((s, e) in doneSegs) {
                if (s == resumeNext) resumeNext = e + 1 else break
            }
            elasticAllocator.skipTo(resumeNext)
        }
        val elasticResults = ConcurrentHashMap<String, ChunkResult>()
        // 在飞分片表（key = m<片号> / seg@<起点> / retry@<起点>）：仅供看门狗算瞬时速度与抢占判定
        val inflightChunks = ConcurrentHashMap<String, InflightChunk>()

        val allOk = coroutineScope {
            // ★ worker 数已钳到 actualWorkers；在飞槽位由全进程共享的 inflightLimiter 控制
            //   （绝不手动 release，也不要改回「每任务一个信号量」）
            val workers = List(actualWorkers) {
                async(chunkIoDispatcher) {
                    // 阶段 1：主池循环领取
                    while (true) {
                        if (fallback.get()) break
                        val i = nextIdx.getAndIncrement()
                        if (i >= mainPoolCount) break
                        // 错峰建连：首请求前按序号微延迟，平摊 TCP/TLS 突发（仅影响首请求，不影响稳态并发）
                        if (i > 0) delay(min(i.toLong(), STAGGER_CAP.toLong()) * STAGGER_MS)
                        inflightLimiter.withPermit {
                            if (fallback.get()) return@withPermit
                            val start = i * chunkSize
                            val end = min(start + chunkSize - 1, total - 1)
                            // 登记在飞分片（key=m<片号>）：看门狗据此算单路瞬时速度、判定慢连接抢占
                            val diagKey = "m${i + 1}"
                            val diag = InflightChunk(start, end - start + 1)
                            inflightChunks[diagKey] = diag
                            val res = try {
                                downloader.downloadChunk(
                                    taskId = id, url = task.url, start = start, end = end,
                                    partFile = File(chunkDir, "part_$i"), headers = headers,
                                    preempt = diag.preempt
                                ) { bytes ->
                                    speedLimiter.awaitAllow(bytes)
                                    diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                    // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                    val new = minOf(downloaded.addAndGet(bytes), total)
                                    if (!isTaskActive()) return@downloadChunk
                                    speedRecorder.onBytes(new)?.let { speed ->
                                        // ★ 实时速度注入弹性分配器：块大小随真实吞吐自适应（IDM 式动态分片）
                                        elasticAllocator.recentSpeedBps = speed
                                        val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                        _stats.update { it + (id to DownloadStats(speed, remain, actualWorkers)) }
                                    }
                                    notifyProgress(id, task.fileName, new, total)
                                    persistProgressIfDue(id, new, total, force = false, lastAt = lastPersistAt)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failReason.compareAndSet(null, "分片 ${i + 1}/$mainPoolCount：${e.message ?: e.javaClass.simpleName}")
                                ChunkResult.FAILED
                            } finally {
                                inflightChunks.remove(diagKey)
                            }
                            results[i] = res
                            when (res) {
                                ChunkResult.RANGE_IGNORED -> {
                                    // 偶发 200（CDN 限流中间态）不算真降级：前 N 次不触发回退，继续领新片；
                                    // 持续 RANGE_IGNORED 才回退单流
                                    val n = rangeIgnoredCount.incrementAndGet()
                                    Log.w(TAG, "runTask: id=$id 分片${i + 1} 检测到服务器忽略Range（累计 $n/$RANGE_IGNORED_TOLERANCE）")
                                    if (n >= RANGE_IGNORED_TOLERANCE) fallback.compareAndSet(false, true)
                                }
                                ChunkResult.FAILED -> failReason.compareAndSet(null, "分片 ${i + 1}/$mainPoolCount 下载失败")
                                else -> {}
                            }
                        }
                    }
                    // 阶段 2：主池取空 → 弹性区按字节顺序领取自适应块（空闲线程逐个平滑转入，并发形态不突变）
                    while (!fallback.get()) {
                        val range = elasticAllocator.take() ?: break
                        val s = range.first
                        val e = range.last
                        val key = "${s}_${e}"
                        // 登记在飞弹性块（key=seg@<起点>）：看门狗据此算单路瞬时速度、判定慢连接抢占
                        val diagKey = "seg@$s"
                        val diag = InflightChunk(s, e - s + 1)
                        inflightChunks[diagKey] = diag
                        val res = try {
                            inflightLimiter.withPermit {
                                if (fallback.get()) return@withPermit ChunkResult.FAILED
                                downloader.downloadChunk(
                                    taskId = id, url = task.url, start = s, end = e,
                                    partFile = File(chunkDir, "seg_$key.part"), headers = headers,
                                    preempt = diag.preempt
                                ) { bytes ->
                                    speedLimiter.awaitAllow(bytes)
                                    diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                    // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                    val new = minOf(downloaded.addAndGet(bytes), total)
                                    if (!isTaskActive()) return@downloadChunk
                                    speedRecorder.onBytes(new)?.let { speed ->
                                        // ★ 实时速度注入弹性分配器：块大小随真实吞吐自适应（IDM 式动态分片）
                                        elasticAllocator.recentSpeedBps = speed
                                        val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                        _stats.update { it + (id to DownloadStats(speed, remain, actualWorkers)) }
                                    }
                                    notifyProgress(id, task.fileName, new, total)
                                    persistProgressIfDue(id, new, total, force = false, lastAt = lastPersistAt)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ChunkResult.FAILED
                        } finally {
                            inflightChunks.remove(diagKey)
                        }
                        elasticResults[key] = res
                        when (res) {
                            ChunkResult.RANGE_IGNORED -> {
                                val n = rangeIgnoredCount.incrementAndGet()
                                Log.w(TAG, "runTask: id=$id 弹性区间 $key 检测到服务器忽略Range（累计 $n/$RANGE_IGNORED_TOLERANCE）")
                                if (n >= RANGE_IGNORED_TOLERANCE) fallback.compareAndSet(false, true)
                            }
                            ChunkResult.FAILED -> failReason.compareAndSet(null, "弹性区间 ${s}-${e} 下载失败")
                            else -> {}
                        }
                    }
                }
            }
            // 看门狗：周期刷新每路瞬时速度，并判定是否把慢连接换掉（worker 全部跑完即停）
            val preemptJob = launch(Dispatchers.IO) {
                val runStartMs = System.currentTimeMillis()
                while (true) {
                    delay(PREEMPT_TICK_MS)
                    // ★ 慢连接抢占（永久逻辑，勿删）：先刷新瞬时速度，再以本任务的平均单连接速度为参照
                    sampleInflightChunks(inflightChunks)
                    preemptSlowChunks(id, downloaded.get(), System.currentTimeMillis() - runStartMs, actualWorkers, inflightChunks)
                }
            }
            workers.awaitAll()
            preemptJob.cancel()
            !fallback.get() && results.all { it == ChunkResult.OK } &&
                elasticResults.values.all { it == ChunkResult.OK }
        }

        // ---------- 收尾 ----------
        if (fallback.get()) {
            // 服务器忽略 Range：回退单条整文件流（只下一次，不按分片重复下载整文件）
            Log.w(TAG, "runTask: id=$id 回退单流整文件下载（避免重复下载整文件）")
            singleStreamFallback(id, task, headers, total, chunkDir, failReason)
            return
        }
        if (!allOk) Log.w(TAG, "runTask: id=$id 有分片未成功（${failReason.get()}），按缺失区间补齐")
        // ★ 一律以「磁盘真实长度」为准收集缺失区间（不能只信 worker 的返回值）：
        //   分片可能报了 OK 但长度不足（连接提前关闭 / 抢占竞态 / 旧 job 残留），
        //   那种情况只在合并阶段的大小校验才会暴露，会白白多跑一轮整任务重试。
        val missing = buildList {
            val seen = HashSet<Long>()
            for (i in 0 until mainPoolCount) {
                val f = File(chunkDir, "part_$i")
                val s = i * chunkSize
                val e = min(s + chunkSize - 1, total - 1)
                if (f.length() < (e - s + 1) && seen.add(s)) add(RetryRange(s, e, f))
            }
            // 弹性区：worker 报失败的区间（文件可能已被删除，必须以 worker 结果为准）
            elasticResults.forEach { (key, res) ->
                if (res != ChunkResult.OK) {
                    val s = key.substringBefore('_').toLong()
                    val e = key.substringAfter('_').toLong()
                    if (seen.add(s)) add(RetryRange(s, e, File(chunkDir, "seg_$key.part")))
                }
            }
            // 弹性区：磁盘上长度不足的区间（worker 报 OK 也不放行）
            chunkDir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".part") }?.forEach { f ->
                val key = f.name.removePrefix("seg_").removeSuffix(".part")
                val s = key.substringBefore('_').toLongOrNull() ?: return@forEach
                val e = key.substringAfter('_').toLongOrNull() ?: return@forEach
                if (f.length() < (e - s + 1) && seen.add(s)) add(RetryRange(s, e, f))
            }
        }
        if (missing.isNotEmpty()) {
            Log.e(TAG, "runTask: id=$id 缺失区间 ${missing.size} 个 reason=${failReason.get()}，并行补齐")
            val retryOk = coroutineScope {
                val retryIdx = AtomicInteger(0)
                val retryResults = arrayOfNulls<ChunkResult?>(missing.size)
                val retryWorkers = List(min(actualWorkers, missing.size)) {
                    async(chunkIoDispatcher) {
                        while (true) {
                            if (!isTaskActive()) break
                            val pos = retryIdx.getAndIncrement()
                            if (pos >= missing.size) break
                            val m = missing[pos]
                            // 重试区间同样登记：重试期间的慢连接也会被看门狗采样、抢占
                            val diagKey = "retry@${m.start}"
                            val diag = InflightChunk(m.start, m.end - m.start + 1)
                            inflightChunks[diagKey] = diag
                            val res = try {
                                // ★ 重试同样走全进程在飞信号量：少这一处会让「主池 + 弹性区 + 重试」
                                //   三路并发叠加，正是 OOM 的成因之一
                                inflightLimiter.withPermit {
                                    downloader.downloadChunk(
                                        taskId = id, url = task.url, start = m.start, end = m.end,
                                        partFile = m.file, headers = headers,
                                        preempt = diag.preempt
                                    ) { bytes ->
                                        speedLimiter.awaitAllow(bytes)
                                        diag.bytes.addAndGet(bytes)   // 采样：供看门狗算瞬时速度
                                        // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
                                        val new = minOf(downloaded.addAndGet(bytes), total)
                                        if (!isTaskActive()) return@downloadChunk
                                        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
                                        notifyProgress(id, task.fileName, new, total)
                                    }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                ChunkResult.FAILED
                            } finally {
                                inflightChunks.remove(diagKey)
                            }
                            retryResults[pos] = res
                            if (res != ChunkResult.OK) {
                                failReason.compareAndSet(null, "区间 ${m.start}-${m.end} 重试仍失败")
                            }
                        }
                    }
                }
                retryWorkers.awaitAll()
                retryResults.all { it == ChunkResult.OK }
            }
            if (retryOk) {
                Log.d(TAG, "runTask: id=$id 重试补齐所有区间，开始合并")
                finishDownload(id, chunkDir, finalChunkFiles(chunkDir, mainPoolCount), task.fileName, total)
                return
            }
            // 重试仍失败：回退单流
            Log.w(TAG, "runTask: id=$id 分片重试失败，回退单流整文件下载")
            singleStreamFallback(id, task, headers, total, chunkDir, failReason)
            return
        }
        Log.d(TAG, "runTask: id=$id 所有区间完成，开始合并")
        finishDownload(id, chunkDir, finalChunkFiles(chunkDir, mainPoolCount), task.fileName, total)
    }

    /** 最终合并文件列表：主池 part_0..part_{n-1}（连续前半段）+ 弹性区 seg_{start}_{end} 按 start 排序（后半段） */
    private fun finalChunkFiles(chunkDir: File, mainPoolCount: Int): List<File> {
        val mainFiles = (0 until mainPoolCount).map { File(chunkDir, "part_$it") }
        val elasticFiles = chunkDir.listFiles { f ->
            f.name.startsWith("seg_") && f.name.endsWith(".part")
        }?.sortedBy { it.name.removePrefix("seg_").substringBefore('_').toLong() }
            ?: emptyList()
        return mainFiles + elasticFiles
    }

    /**
     * 回退：单条整文件流下载（服务器忽略 Range 时）。
     * 写入**独立**的 full_single.bin（从 0 开始），不复用 part_0，避免与已下分片错位/重复。
     */
    private suspend fun singleStreamFallback(
        id: Long,
        task: DownloadTaskEntity,
        headers: Map<String, String>,
        total: Long,
        chunkDir: File,
        failReason: java.util.concurrent.atomic.AtomicReference<String?>
    ) {
        val fullFile = File(chunkDir, "full_single.bin").apply { delete() } // 全新整文件，从 0 开始
        val fullDownloaded = AtomicLong(0)
        val fullLastAt = AtomicLong(0L)
        val ok = downloader.downloadFull(id, task.url, fullFile, headers, total) { bytes ->
            speedLimiter.awaitAllow(bytes)
            // ★ 钳制到 total：任何竞态都不可能让显示超过总大小
            val new = minOf(fullDownloaded.addAndGet(bytes), total)
            if (!isTaskActive()) return@downloadFull
            persistProgressIfDue(id, new, total, force = false, lastAt = fullLastAt)
            notifyProgress(id, task.fileName, new, total)
        }
        if (!ok) throw IllegalStateException(failReason.get() ?: "分片与单流下载均失败")
        finishDownload(id, chunkDir, listOf(fullFile), task.fileName, total)
    }

    /** 流式降级下载：总大小未知时单分片开放区间下载（Range: bytes=from-），读到 EOF */
    private suspend fun streamDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, 0)
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        val partFile = File(chunkDir, "part_0")
        val downloaded = AtomicLong(partFile.length())
        val streamLastAt = AtomicLong(0L)
        val ok = downloader.downloadChunk(
            taskId = id,
            url = task.url,
            start = 0,
            end = Long.MAX_VALUE,
            partFile = partFile,
            headers = headers
        ) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            if (!isTaskActive()) return@downloadChunk
            // 大小未知：只更新已下载量（total=0 表示未知）
            persistProgressIfDue(id, new, 0, force = false, lastAt = streamLastAt)
            // 前台通知进度（2 秒节流，total 未知时仅更新标题）
            notifyProgress(id, task.fileName, new, 0)
        }
        if (ok != ChunkResult.OK) {
            // Range 被 CDN 拒绝（416/403）或忽略（200 整文件）：回退为无 Range 完整 GET
            Log.w(TAG, "streamDownload: id=$id Range 失败，回退完整 GET 下载")
            downloaded.set(0)
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, 0, 0)
            val ok2 = downloader.downloadFull(
                taskId = id,
                url = task.url,
                partFile = partFile,
                headers = headers
            ) { bytes ->
                speedLimiter.awaitAllow(bytes)
                val new = downloaded.addAndGet(bytes)
                if (!isTaskActive()) return@downloadFull
                persistProgressIfDue(id, new, 0, force = false, lastAt = streamLastAt)
            }
            if (!ok2) throw IllegalStateException("下载失败（Range 与完整下载均失败）")
        }
        if (!isTaskActive()) return
        finishDownload(id, chunkDir, listOf(partFile), task.fileName, 0)
    }

    /** HLS（m3u8 转码流，如 UC play）下载：拉取分片合并 → 保存 → 完成回调 */
    private suspend fun hlsDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        val hlsFile = File(AppContext.mergeDir, "hls_$id")
        hlsFile.delete()
        val downloaded = AtomicLong(0)
        val hlsLastAt = AtomicLong(0L)
        val ok = HlsDownloader.download(task.url, headers, hlsFile) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            persistProgressIfDue(id, new, 0, force = false, lastAt = hlsLastAt)
            notifyProgress(id, task.fileName, new, 0)
        }
        if (!isTaskActive()) return
        if (!ok) {
            hlsFile.delete()
            throw IllegalStateException("HLS 转码流下载失败")
        }
        // Android 9- 保存前检查存储权限（动态申请，授权后继续；无权限则报错提示）
        if (!storagePermissionProvider()) {
            hlsFile.delete()
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        val savedPath = withContext(Dispatchers.IO) {
            DownloadSaver.save(task.fileName, hlsFile, saveDirProvider())
        }
            ?: throw IllegalStateException("保存到下载目录失败")
        val hlsTotal = dao.get(id)?.totalSize ?: 0L
        completeWithAvg(id, savedPath, hlsTotal)
        Log.d(TAG, "hlsDownload: id=$id 下载完成 savedPath=$savedPath size=${hlsFile.length()}")
        taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        _stats.update { it - id }
        hlsFile.delete()
    }

    /**
     * 分片流式合并 → **直接写入最终保存位置** → 校验通过后清空分片目录。
     *
     * ★ 旧实现先合并到私有缓存 `merge/merged_$id` 再复制到目标目录，峰值占用 3 份
     *   （分片 + 合并副本 + 目标副本），大文件在磁盘吃紧时 ENOSPC，且多一次全量拷贝；
     *   现直接写向最终目标，一次成型。
     * ★ 合并期间**不删除分片**（见 [ChunkDownloader.mergeChunksToStream]）：合并失败/暂停时
     *   分片仍在，重试只是重新合并一遍；若边写边删，失败后就只能整文件重下 ——
     *   这正是「进度到 100% 卡住、随后自动重新下载」的成因。峰值磁盘占用因此 ≈ 2 份。
     * ★ 完整性校验：分片非空 + 写入字节 == total，任一不符即 abort 半成品并抛错，绝不保存损坏文件。
     */
    private suspend fun finishDownload(
        id: Long,
        chunkDir: File,
        chunkFiles: List<File>,
        fileName: String,
        total: Long
    ) {
        if (!isTaskActive()) return
        // 1) 分片完整性
        for (part in chunkFiles) {
            if (!part.exists() || part.length() <= 0) {
                Log.e(TAG, "finishDownload: id=$id 分片缺失/为空 $part")
                throw IllegalStateException("分片文件缺失或为空，拒绝合并（防止文件损坏）")
            }
        }
        // 2) 保存前权限检查（桌面版恒为 true，保留扩展点）
        if (!storagePermissionProvider()) {
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        // 3) 流式写入最终位置：边合并边删分片，不再产生中间合并副本
        // ★ 同步阻塞写入必须切 IO 线程：任务跑在 Dispatchers.Default（CPU 池），
        //   大文件写盘若占满 Default 线程会让整个下载器协程饿死（"100% 卡死保存不了"）
        // 合并阶段单独上报进度（界面显示「合并中 n%」）：大文件合并要几十秒，
        // 一直停在 100% 不动会让用户以为卡死。进度只走内存态 stats、不写 DB ——
        // 进程退出时合并本就中断，库里不需要再多一个会卡住的状态。
        val mergeTotal = if (total > 0) total else chunkFiles.sumOf { it.length() }
        var mergeLastPercent = -1
        var mergeLastAtMs = 0L
        fun reportMergeProgress(done: Long) {
            if (mergeTotal <= 0) return
            val percent = (done * 100 / mergeTotal).toInt().coerceIn(0, 100)
            val now = System.currentTimeMillis()
            if (percent == mergeLastPercent && now - mergeLastAtMs < mergeReportIntervalMs) return
            mergeLastPercent = percent
            mergeLastAtMs = now
            _stats.update { it + (id to DownloadStats(mergePercent = percent)) }
        }
        reportMergeProgress(0L)
        val savedPath = withContext(Dispatchers.IO) {
            val dest = DownloadSaver.openDestination(fileName, saveDirProvider())
                ?: throw IllegalStateException("无法创建下载目标（下载目录不可用）")
            try {
                val out = dest.open() ?: throw IllegalStateException("无法打开下载目标输出流")
                val written = out.use {
                    downloader.mergeChunksToStream(chunkFiles, it, ::reportMergeProgress)
                }
                if (total > 0 && written != total) {
                    throw IllegalStateException("文件大小校验失败：期望 $total 字节，实际 $written 字节（已拒绝保存损坏文件）")
                }
                dest.commit()
                dest.path
            } catch (e: Exception) {
                // 失败/取消：删掉半成品目标文件；★ 分片一律保留（mergeChunksToStream 不再边写边删），
                // 于是重试只需重新合并一遍，绝不会退化成「从头重下整个文件」
                dest.abort()
                // ★ 清掉内存里的「合并中」进度：否则任务失败后卡片会一直停在「合并中 100%」，看着像卡死
                _stats.update { it + (id to DownloadStats()) }
                throw e
            }
        }
        completeWithAvg(id, savedPath, total)
        Log.d(TAG, "finishDownload: id=$id 下载完成 savedPath=$savedPath size=$total")
        taskCallbacks.remove(id)?.let { cb ->
            runCatching { cb() }
        }
        _stats.update { it - id }
        chunkDir.deleteRecursively()
    }

    /**
     * 速度采样器：取近 [WINDOW_MS] 秒滑动窗口的平均速度，平滑多线程下载的速度波动。
     * 多线程并发下瞬时速率波动大，短窗口估算剩余时长会剧烈跳动；
     * 改用 5 秒窗口均值后，剩余时长更稳定可靠。
     */
    private class SpeedRecorder {
        private data class Sample(val timeMs: Long, val bytes: Long)

        private val samples = ArrayDeque<Sample>()
        private var lastEmit = 0L

        @Synchronized
        fun onBytes(total: Long): Long? {
            val now = System.currentTimeMillis()
            samples.addLast(Sample(now, total))
            // 剔除窗口外的旧样本，但始终保留至少 2 个（下载起步阶段窗口尚短）
            while (samples.size > 2 && now - samples.first().timeMs > WINDOW_MS) {
                samples.removeFirst()
            }
            // 250ms 发射一次，避免高频刷新 UI/通知
            if (now - lastEmit < 250) return null
            val first = samples.first()
            val elapsed = now - first.timeMs
            val speed = if (elapsed > 0) {
                ((total - first.bytes) * 1000 / elapsed).coerceAtLeast(0)
            } else 0L
            lastEmit = now
            return speed
        }

        private companion object {
            const val WINDOW_MS = 5000L
        }
    }

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒；0 = 不限速 */
    private inner class SpeedLimiter {
        @Volatile
        private var tokens = 0L
        @Volatile
        private var lastRefillNanos = System.nanoTime()

        @Synchronized
        private fun refill(limit: Long) {
            val now = System.nanoTime()
            val elapsedSec = ((now - lastRefillNanos).coerceAtLeast(0) / 1_000_000_000.0)
            lastRefillNanos = now
            tokens = minOf(limit, tokens + (elapsedSec * limit).toLong())
        }

        /** 消耗 bytes 字节额度；不足则挂起等待（限速生效） */
        suspend fun awaitAllow(bytes: Long) {
            val limit = speedLimitProvider().coerceAtLeast(0L)
            if (limit <= 0L) return
            while (true) {
                val waitMs = synchronized(this) {
                    refill(limit)
                    if (bytes <= tokens) {
                        tokens -= bytes
                        return
                    }
                    ((bytes - tokens) * 1000 / limit).coerceIn(1L, 200L)
                }
                // 锁外挂起等待，避免持锁阻塞其他任务
                delay(waitMs)
            }
        }
    }

    /** 下载临时文件缓存根目录：外部缓存（/storage/emulated/0/Android/data/com.yunx.app/cache），
     *  与最终保存目录解耦，系统可自动清理；外部存储不可用时回退内部缓存目录。 */
    private fun cacheBase(): File = AppContext.downloadTmpDir

    /** 分片临时文件目录：cacheBase()/download_tmp/$id */
    private fun chunkDirOf(id: Long): File = File(cacheBase(), "download_tmp/$id")

    /** 分片数规划（任务池模型）：分片数 = 线程数 × 8，远多于并发线程数。
     *  worker 循环领取盈余块，任一分片慢时其他线程继续领新片，根治"尾部并发塌缩"；
     *  保留 256KB 单片下限（小文件也能切出数倍于线程数的盈余片，同时避免过多小片）与 512 封顶。 */
    private fun chunkCountFor(total: Long, threads: Int): Int {
        if (total <= 0) return 1
        // ★ 单片下限 1MB → 256KB：1MB 会把小文件（如 33.7MB）的分片数夹到 ≈ 线程数，失去盈余；
        //   低单连接速率（如夸克 10KB/s）下每个 1MB 重片耗时约 100s，尾部极易空转。
        //   降到 256KB 后同样文件能切出 4~8 倍于线程数的片，工作窃取把尾部空转压到最后一个 256KB。
        //   大文件仍受 want / 512 封顶约束，单片自然变大，吞吐不受影响。
        val minChunkBytes = 256 * 1024L
        val bySize = when {
            total < 5 * 1024 * 1024 -> 1          // < 5MB 不分片
            total < 50 * 1024 * 1024 -> 8         // < 50MB
            total < 500 * 1024 * 1024 -> 32       // < 500MB
            else -> 64                            // ≥ 500MB 基础值
        }
        // 任务池：每线程平均领 8 片，天然抗慢片拖尾（比 1:1 映射多 8 倍盈余）
        val want = maxOf(bySize, threads * 8)
        return minOf(want, (total / minChunkBytes).toInt().coerceAtLeast(1), 512)
    }

    companion object {
        /**
         * 读缓冲预算占最大堆的比例：取 1/8。
         * 下载客户端固定 HTTP/1.1（无 HTTP/2 每流 16MB 窗口）后，每路在飞只占一份 64KB 读缓冲，
         * 同样的堆预算可以安全地多放路数。
         */
        private const val BUFFER_BUDGET_DIVISOR = 8

        /** 在飞分片下限：低于 8 路会让慢 CDN 明显掉速 */
        private const val INFLIGHT_MIN = 8

        /** 在飞分片硬上限：512（与设置页最高档位一致 ⇒ 用户选 512 就是真的 512 路） */
        private const val INFLIGHT_MAX = 512

        /**
         * 按堆预算推导「全进程在飞分片上限」：maxHeap / 8 / 单路读缓冲大小，夹在 [8, 512]。
         * 纯函数（无副作用），便于核对边界。
         */
        internal fun inflightChunksFor(maxHeapBytes: Long, bufferSize: Int = BUFFER_SIZE): Int =
            (maxHeapBytes / BUFFER_BUDGET_DIVISOR / bufferSize.coerceAtLeast(1))
                .toInt()
                .coerceIn(INFLIGHT_MIN, INFLIGHT_MAX)

        /**
         * 全进程在飞分片上限（进程启动时按最大堆算一次）。
         * 由 `inflightLimiter` 用作信号量容量：无论用户怎么调线程数、同时开几个任务，
         * 同时在飞的下载请求数都不会超过它。
         *
         * ★ 缓冲大小取自运行时设置（「设置 → 实验性功能」允许把读缓冲调到远超默认的 64KB）：
         *   缓冲越大、在飞路数上限越低，**总缓冲内存始终被压在堆预算的 1/[BUFFER_BUDGET_DIVISOR] 以内**，
         *   不会因为用户把缓冲调到 1MB/4MB 就把内存撑爆。默认 64KB 时结果与改动前完全一致。
         */
        val MAX_INFLIGHT_CHUNKS: Int =
            inflightChunksFor(Runtime.getRuntime().maxMemory(), DownloadTuning.bufferSize)

        /**
         * 分片阻塞 IO 的专用线程池（进程级），worker 与 [ChunkDownloader] 内部的 `withContext` 都跑在它上面。
         *
         * 为什么不能直接用 `Dispatchers.IO`：它的并行度被钉在 `max(64, 核数)`，超出的 worker 只能在队列里
         * 干等 —— 于是「设置里 256/512 线程」永远只跑得出 64 路。线程**按需创建**（最多 [MAX_INFLIGHT_CHUNKS]
         * 条）、空闲 30 秒回收，只有真用到大并发时才会存在那么多线程。
         * 必须 core = max 而不是 core = 0 + 无界队列：后者在 ThreadPoolExecutor 里只会养出 1 个 worker。
         */
        internal val chunkIoDispatcher: CoroutineDispatcher =
            ThreadPoolExecutor(
                MAX_INFLIGHT_CHUNKS,
                MAX_INFLIGHT_CHUNKS,
                30L,
                TimeUnit.SECONDS,
                LinkedBlockingQueue<Runnable>()
            ) { r -> Thread(r, "yunx-chunk-io").apply { isDaemon = true } }
                .apply { allowCoreThreadTimeOut(true) }
                .asCoroutineDispatcher()
    }
}
