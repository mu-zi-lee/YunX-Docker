package com.yunx.app.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.update.UpdateChecker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * 通用远程图片加载器（公告封面 / 发布者头像 / 正文图集）：用项目已有 OkHttp 自己加载，
 * 刻意**不引入 Coil**（沿用 README 图片渲染的约定）。
 *
 * 与 [GitHubMarkdownImageTransformer] 的分工：后者服务于 Markdown 正文内嵌图，额外承担
 * HTML 尺寸标记、SVG 光栅化与 `<text>` 补绘等渲染器适配；本对象只负责「URL → ImageBitmap」，
 * 供普通 [RemoteImage] 使用。二者各自持有 LRU 缓存（同图跨场景重复加载的场景很少，不做耦合）。
 *
 * 能力与保护：
 * - 内存 LRU 缓存（[MAX_ENTRIES] 张）防重复请求，[cached] 让已加载过的图在重组时立即出图、不闪占位；
 * - [Semaphore] 限制同时在飞的请求数，滚动长列表时不会瞬间打满连接；
 * - 进行中的请求按 URL 去重（[inFlight]），同一 URL 不会重复下载；
 * - 镜像：显式设置 [mirrorPrefix] 时先试镜像 URL，失败回退直连（仅 GitHub 系域名生效）；
 * - SVG 直接跳过（Skia 位图解码不支持，与上游 Android 版 BitmapFactory 的取舍一致），
 *   任何异常返回 null 由调用方显示占位。
 *
 * 与上游差异：上游用 Android `BitmapFactory` + `inSampleSize` 降采样；桌面改用 Skia（skiko）
 * `Image.makeFromEncoded`，按原尺寸解码（桌面窗口尺寸远大于手机屏，内存由 128 张 LRU 上限约束）。
 */
object RemoteImageLoader {

    private const val MAX_ENTRIES = 128

    private val cacheLock = Any()

    /** 内存 LRU 缓存（accessOrder = true）：超过上限淘汰最久未使用的图片 */
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean =
            size > MAX_ENTRIES
    }

    /** 并发上限（列表滚动时同时出现的图片可能很多） */
    private val semaphore = Semaphore(4)

    /** 进行中的加载：同一 URL 的并发请求共享同一个 Deferred，只发一次网络请求 */
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    /** 加载协程作用域：与组合生命周期解耦，图片滚出屏幕时已发起的下载仍会完成并进入缓存 */
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 镜像前缀（由调用方读取设置后设置；null / 空 = 直连）。只对 GitHub 系域名生效 */
    @Volatile
    var mirrorPrefix: String? = null

    /** 取已缓存的图（没有则 null）：调用方用它做首帧直出，避免闪一下占位色 */
    fun cached(url: String): ImageBitmap? = synchronized(cacheLock) { cache[url.trim()] }

    /** 加载远程图片；失败（网络 / 非 2xx / 不是图片 / 不支持 svg）返回 null，不抛异常 */
    suspend fun load(url: String): ImageBitmap? {
        val link = url.trim()
        if (link.isEmpty()) return null
        cached(link)?.let { return it }
        val deferred = inFlight.computeIfAbsent(link) { key ->
            loaderScope.async { fetchAndDecode(key) }
        }
        // 加载结束（无论成功失败）后移除进行中标记，避免 map 无限增长
        deferred.invokeOnCompletion { inFlight.remove(link, deferred) }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            // 组合退出（滚动/切页）：只取消等待，底层下载继续，完成后仍会写入缓存
            throw e
        }
    }

    /** 拉取 + 解码；镜像候选失败自动回退直连，全部失败返回 null（调用方显示占位） */
    private suspend fun fetchAndDecode(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        // SVG（图床图标 / 徽章）Skia 位图解码不支持，提前跳过省一次网络请求；
        // 带 query 的 svg（如 badge.svg?raw=1）必须先去 query/fragment 再取后缀，否则会误取成 "raw=1"
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase()
        if (ext == "svg") return@withContext null
        // 镜像前缀仅对 GitHub 域生效；外链图（图床 / imgur 等）直接直连，避免无谓的失败镜像请求
        val useMirror = !mirrorPrefix.isNullOrBlank() && isGitHubDomain(url)
        val candidates = buildList {
            if (useMirror) add(UpdateChecker.mirrorUrl(url, mirrorPrefix!!))
            add(url)
        }
        val client = HttpClients.downloadClient()
        for (candidate in candidates) {
            val bytes: ByteArray? = runCatching {
                semaphore.withPermit {
                    val req = Request.Builder()
                        .url(candidate)
                        .header("User-Agent", "YunX-Desktop")
                        .build()
                    client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withPermit null
                        resp.body?.bytes()
                    }
                }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                val bitmap = runCatching {
                    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                }.getOrNull()
                if (bitmap != null) {
                    synchronized(cacheLock) { cache[url] = bitmap }
                    return@withContext bitmap
                }
            }
        }
        null
    }

    /** 是否为 GitHub 系域名（仅这些域套镜像前缀才有意义）；严格匹配防止 evilgithub.com 等仿冒域被误套 */
    private fun isGitHubDomain(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "github.com" || host.endsWith(".github.com") ||
            host == "raw.githubusercontent.com" || host.endsWith(".githubusercontent.com")
    }
}
