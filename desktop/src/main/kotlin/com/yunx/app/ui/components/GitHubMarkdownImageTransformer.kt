package com.yunx.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import com.mikepenz.markdown.model.PlaceholderConfig
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
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.svg.SVGDOM
import org.jetbrains.skia.svg.SVGLengthContext
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * mikepenz Markdown 的自定义 ImageTransformer（与上游 Android 版同名同职责）：
 * 用项目已有 OkHttp 自己拉取 README / 更新说明里的图片，不引入 Coil。
 *
 * - 内存 LRU 缓存（128 张，accessOrder）防重复请求与重复解码；
 * - Semaphore(4) 限制并发，避免一次出现多图时抢占带宽；
 * - 进行中的请求按 URL 去重（[inFlight]），同一 URL 不会重复下载；
 * - 网络与解码都在 IO 线程，组合只读缓存状态，不阻塞 UI；
 * - 镜像：mirrorPrefix 非空且为 GitHub 域时先试镜像 URL，失败回退直连；
 * - 任何异常返回 null，库显示占位，不崩。
 *
 * 与上游差异：桌面版没有 BitmapFactory，改用 Skia（skiko）解码（与 Main.kt 的 loadWindowIcon 同一套用法）；
 * 上游的 inSampleSize 降采样在 Skia 无对应 API，且桌面窗口尺寸远大于手机屏，故按原尺寸解码，
 * 内存由 128 张 LRU 上限约束。
 *
 * **SVG**（README 顶部的 shields.io 徽章等）由 skiko 自带的 SVGDOM 光栅化（无需新增依赖）：
 * 后缀 / 响应 Content-Type / 内容嗅探任一命中即走 SVG 路径，与位图共用同一套缓存 / 去重 / 镜像逻辑。
 *
 * 相对链接已由 [preprocessReadme] 补全为 raw.githubusercontent.com 绝对 URL。
 *
 * HTML `<img width=...>` 的尺寸与 `<div align="center">` 的居中意图，由 [preprocessReadme] 编码进
 * 图片 URL 的 fragment（`#yunx-w=430&c=1`），这里解析后：
 * - 网络请求与缓存键一律用**剥离标记后的真实 URL**（带标记与不带标记不会重复下载）；
 * - 用 [SizedPainter] 把目标绘制尺寸交给库的 [intrinsicSize]，占位与测量据此得到正确尺寸；
 * - 居中意图通过 [ImageData.alignment] = [Alignment.Center] 表达（该占位框高度即图片高度，
 *   故 2D 居中与「水平居中」等价）。
 */
object GitHubMarkdownImageTransformer : ImageTransformer {

    private const val MAX_ENTRIES = 128

    private val cacheLock = Any()

    /** 各图「自然尺寸」（CSS px）侧表：键集与 [cache] 严格一致（成对写入、随淘汰成对移除），容量同样受 [MAX_ENTRIES] 约束 */
    private val naturalSizes = HashMap<String, Size>()

    /** 内存 LRU 缓存（accessOrder = true）：超过上限淘汰最久未使用的图片 */
    private val cache = object : LinkedHashMap<String, ImageBitmap>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            if (size <= MAX_ENTRIES) return false
            eldest?.key?.let { naturalSizes.remove(it) }
            return true
        }
    }

    /** 并发上限（README 里常一次出现多张图） */
    private val semaphore = Semaphore(4)

    /** 进行中的加载：同一 URL 的并发请求共享同一个 Deferred，只发一次网络请求 */
    private val inFlight = ConcurrentHashMap<String, Deferred<ImageBitmap?>>()

    /** 加载协程作用域：与组合生命周期解耦，图片滚出屏幕时已发起的下载仍会完成并进入缓存 */
    private val loaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 镜像前缀（由调用方读取 SettingsRepository.githubMirrorPrefix 后设置；null 表示直连） */
    @Volatile
    var mirrorPrefix: String? = null

    @Composable
    override fun transform(link: String): ImageData? {
        val target = parseImageTarget(link)
        // produceState：命中缓存则首帧直接出图；未命中先返回 null（库显示占位），IO 加载完成后重组替换。
        // 缓存查找与网络请求都用剥离尺寸标记后的真实 URL。
        val bitmap by produceState<ImageBitmap?>(initialValue = cached(target.url), target.url) {
            value = loadImage(target.url)
        }
        val image = bitmap ?: return null
        // 布局尺寸一律用「自然尺寸」（CSS px）：SVG 会按倍率放大光栅化以获得清晰边缘，
        // 而 Compose 把 painter.intrinsicSize 的像素值 1:1 当作布局像素，直接用位图尺寸会把徽章放大一倍。
        val bitmapSize = Size(image.width.toFloat(), image.height.toFloat())
        val natural = synchronized(cacheLock) { naturalSizes[target.url] } ?: bitmapSize
        if (!target.marked) {
            return if (natural == bitmapSize) ImageData(painter = BitmapPainter(image))
            else ImageData(painter = SizedPainter(BitmapPainter(image), natural))
        }
        // 带尺寸/居中标记（来自 HTML img）：用包装 Painter 承载「目标绘制尺寸」，居中交给 alignment
        val targetSize = with(LocalDensity.current) { target.resolveSize(natural, density) }
        return ImageData(
            painter = SizedPainter(BitmapPainter(image), targetSize),
            // 撑满可用宽度：只有占位够宽，alignment 的居中才有意义（否则只是在图片自身宽度内居中）
            modifier = Modifier.fillMaxWidth(),
            alignment = if (target.center) Alignment.Center else Alignment.CenterStart,
            // Inside：不大于原始（目标）尺寸放大，容器更窄时等比缩小，避免把 430px 截图放大铺满
            contentScale = ContentScale.Inside,
        )
    }

    /**
     * 覆盖库默认 intrinsicSize：带尺寸标记的图片返回其目标尺寸（px），库据此算占位与测量。
     * 普通 painter 保持库默认行为（直接取 painter.intrinsicSize），不在这里另行推算。
     */
    @Composable
    override fun intrinsicSize(painter: Painter): Size {
        if (painter is SizedPainter) return painter.targetSize
        return painter.intrinsicSize
    }

    /**
     * 覆盖库默认占位尺寸。
     *
     * 0.33.0 默认实现在「拿不到图片尺寸」时返回「整行宽 × 180sp」的占位框，而 badge (SVG)、
     * 404 图片等永远拿不到尺寸，会在 README 里留下大片空白（顶部 badge 段一次 6 张即撑出上千像素）。
     * 这里在尺寸未知时只占 1×1，图片加载完成后由库按真实尺寸重排。
     *
     * 尺寸已知时，占位宽度取**整行宽**（而不是 min(图片宽, 行宽)）：库把所有图片都当作行内内容，
     * 占位宽度就是图片可用的横向空间，撑满行宽后 [ImageData.alignment] 才能把窄图（如 120px 图标）
     * 真正居中；高度按「缩放后实际绘制高度」预留，避免多余空白。
     *
     * 注意不能返回 0 尺寸：行内占位为 0 时 Skia 量不到任何 rect，Compose 的 TextLinkScope
     * 在给「图片链接」（如 [![badge](url)](link)）算包围盒时会抛 NullPointerException。
     *
     * 已知取舍：占位宽度取整行宽，使得窄图（如 shields.io 徽章）在 Markdown 段落里会各自独占一行。
     * 改成「图片自身宽度」可以让徽章并排，但行内内容盒随之收窄，`<div align="center">` 里靠
     * [ImageData.modifier] + [Alignment.Center] 实现的居中会失效（实测图标会变左对齐），故保持现状。
     */
    override fun placeholderConfig(
        density: Density,
        containerSize: Size,
        intrinsicImageSize: Size
    ): PlaceholderConfig {
        if (containerSize.isUnspecified || intrinsicImageSize.isUnspecified ||
            containerSize.width <= 0f || intrinsicImageSize.width <= 0f || intrinsicImageSize.height <= 0f
        ) {
            return PlaceholderConfig(size = Size(1f, 1f))
        }
        return PlaceholderConfig(
            size = with(density) {
                // 行宽不够时等比缩小；行宽足够时按图片自身高度预留（不放大）
                val scale = minOf(1f, containerSize.width / intrinsicImageSize.width)
                Size(
                    containerSize.width.toSp().value,
                    (intrinsicImageSize.height * scale).toSp().value
                )
            }
        )
    }

    // ---------------------------------------------------------------------------------------------
    // HTML img 尺寸/居中标记
    // ---------------------------------------------------------------------------------------------

    /** 尺寸标记键：写进图片 URL fragment，如 `#yunx-w=430&yunx-h=200&c=1` */
    private const val MARK_W = "yunx-w"
    private const val MARK_H = "yunx-h"
    private const val MARK_CENTER = "c"

    /** 解析后的图片目标：真实 URL + 目标宽高（dp，单位与 HTML width/height 一致）+ 是否居中 */
    private class ImageTarget(
        val url: String,
        val widthDp: Float?,
        val heightDp: Float?,
        val center: Boolean,
    ) {
        /** 是否携带标记（需要按目标尺寸绘制，而非原始尺寸铺满） */
        val marked: Boolean get() = widthDp != null || heightDp != null || center

        /**
         * 目标绘制尺寸（px）。只给单边时按图片自然宽高比补另一边；都没给就用自然尺寸。
         * 这样 HTML 只写 width 时高度会自动等比，不会拉伸变形。
         * [natural] 传图片的自然尺寸（SVG 已按倍率光栅化过，不能用位图像素尺寸，否则会放大一倍）。
         */
        fun resolveSize(natural: Size, density: Float): Size {
            val naturalW = natural.width
            val naturalH = natural.height
            val width = widthDp?.let { it * density }
            val height = heightDp?.let { it * density }
            return when {
                width != null && height != null -> Size(width, height)
                width != null -> Size(width, naturalH * width / naturalW)
                height != null -> Size(naturalW * height / naturalH, height)
                else -> Size(naturalW, naturalH)
            }
        }
    }

    /**
     * 解析 URL fragment 里的尺寸/居中标记。只识别自家标记（键名限白名单），
     * 真实 fragment（如 `#section`）原样当普通 URL 处理。
     */
    private fun parseImageTarget(link: String): ImageTarget {
        val hash = link.indexOf('#')
        if (hash < 0) return ImageTarget(link, null, null, false)
        val pairs = link.substring(hash + 1).split('&').mapNotNull { part ->
            val i = part.indexOf('=')
            if (i <= 0) null else part.substring(0, i) to part.substring(i + 1)
        }
        if (pairs.isEmpty() || pairs.any { it.first != MARK_W && it.first != MARK_H && it.first != MARK_CENTER }) {
            return ImageTarget(link, null, null, false)
        }
        val map = pairs.toMap()
        val width = map[MARK_W]?.toFloatOrNull()?.takeIf { it > 0f }
        val height = map[MARK_H]?.toFloatOrNull()?.takeIf { it > 0f }
        val center = map[MARK_CENTER] == "1"
        if (width == null && height == null && !center) return ImageTarget(link, null, null, false)
        // 请求与缓存一律使用剥离标记后的真实 URL
        return ImageTarget(link.substring(0, hash), width, height, center)
    }

    /**
     * 承载「目标绘制尺寸」的 Painter 包装（不用全局 map 关联 URL 与 painter，避免重组后取到过期尺寸）。
     * - [intrinsicSize] 报告目标尺寸：库的占位计算与 ContentScale 据此工作；
     * - [onDraw] 按上层给定的 DrawScope 尺寸委托绘制（BitmapPainter 会据此缩放位图），
     *   因此 ContentScale.Inside 在容器更窄时仍能正常等比缩小。
     */
    private class SizedPainter(
        private val delegate: Painter,
        val targetSize: Size,
    ) : Painter() {
        override val intrinsicSize: Size get() = targetSize

        override fun DrawScope.onDraw() {
            val drawSize = size
            with(delegate) { draw(size = drawSize) }
        }
    }

    /** 取图：先查缓存，再复用进行中的请求 */
    private suspend fun loadImage(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        val deferred = inFlight.computeIfAbsent(url) { key ->
            loaderScope.async { fetchAndDecode(key) }
        }
        // 加载结束（无论成功失败）后移除进行中标记，避免 map 无限增长
        deferred.invokeOnCompletion { inFlight.remove(url, deferred) }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            // 组合退出（滚动/切页）：只取消等待，底层下载继续，完成后仍会写入缓存
            throw e
        }
    }

    /** 拉取 + 解码；镜像候选失败自动回退直连，全部失败返回 null（库显示占位） */
    private suspend fun fetchAndDecode(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cached(url)?.let { return@withContext it }
        // SVG 判定提示之一：URL 后缀（先去 query/fragment，带 query 的 `badge.svg?raw=1` 才不会误取成 "raw=1"）。
        // 无后缀的端点（如 img.shields.io/...）识别不了，由响应 Content-Type 与内容嗅探兜底。
        val urlSvgHint = isSvgUrl(url)
        // 镜像前缀仅对 GitHub 域生效；外链图（imgur 等）直接直连，避免无谓的失败镜像请求
        val useMirror = !mirrorPrefix.isNullOrBlank() && isGitHubDomain(url)
        val candidates = buildList {
            if (useMirror) add(UpdateChecker.mirrorUrl(url, mirrorPrefix!!))
            add(url)
        }
        val client = HttpClients.downloadClient()
        for (candidate in candidates) {
            val payload: Payload? = runCatching {
                semaphore.withPermit {
                    val request = Request.Builder()
                        .url(candidate)
                        .header("User-Agent", "YunX-Desktop")
                        .build()
                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) return@withPermit null
                        val body = resp.body ?: return@withPermit null
                        Payload(body.bytes(), body.contentType()?.toString().orEmpty())
                    }
                }
            }.getOrNull()
            if (payload != null && payload.bytes.isNotEmpty()) {
                val svgHint = urlSvgHint || payload.contentType.contains("svg", ignoreCase = true)
                decode(payload.bytes, svgHint)?.let { decoded ->
                    // 缓存最终 ImageBitmap（SVG 与位图共用同一套缓存/去重/镜像逻辑）
                    put(url, decoded.image, decoded.naturalSize)
                    return@withContext decoded.image
                }
            }
        }
        null
    }

    /** 下载到的原始响应：字节 + 响应 Content-Type（用于识别无后缀的 SVG） */
    private class Payload(val bytes: ByteArray, val contentType: String)

    /** 解码结果：最终位图 + 该图的「自然尺寸」（CSS px，SVG 放大光栅化后布局仍按此尺寸） */
    private class Decoded(val image: ImageBitmap, val naturalSize: Size)

    /** URL 是否以 .svg 结尾（先去掉 query 与 fragment） */
    private fun isSvgUrl(url: String): Boolean =
        url.substringBefore('?').substringBefore('#').substringAfterLast('.').lowercase() == "svg"

    /** 内容嗅探：去掉前导空白/BOM 后以 `<svg` 开头，或声明为 XML 且含 `<svg` */
    private fun looksLikeSvg(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (head.startsWith("<svg", ignoreCase = true)) return true
        return head.startsWith("<?xml", ignoreCase = true) && head.contains("<svg", ignoreCase = true)
    }

    /**
     * 解码字节为 Compose 位图：
     * - SVG（后缀/Content-Type 提示，或内容嗅探命中）走 skiko 的 SVGDOM 光栅化；
     * - 其余走 Skia 位图解码，失败再兜底试一次 SVG 解析（后缀与 Content-Type 都没提示的 SVG）。
     * 全部失败返回 null，库显示占位。
     */
    private fun decode(bytes: ByteArray, svgHint: Boolean): Decoded? {
        if (svgHint || looksLikeSvg(bytes)) return rasterizeSvg(bytes)
        val bitmap = runCatching {
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        }.getOrNull() ?: return rasterizeSvg(bytes)
        return Decoded(bitmap, Size(bitmap.width.toFloat(), bitmap.height.toFloat()))
    }

    // ---------------------------------------------------------------------------------------------
    // SVG 光栅化（skiko 自带 SVGDOM，无需新增依赖）
    // ---------------------------------------------------------------------------------------------

    /** 光栅化放大倍数：矢量放大不糊，2 倍后仍按自然尺寸布局（见 [transform] 的说明） */
    private const val SVG_RASTER_SCALE = 2f

    /** 单张 SVG 光栅化后的总像素上限（宽 × 高），防止超大 SVG 爆内存 */
    private const val SVG_MAX_PIXELS = 2_000_000f

    /** 无法从 SVG 中读出尺寸时的兜底：按 shields.io 长条徽章的比例（120 × 20），绝不退化成 0 尺寸 */
    private const val SVG_FALLBACK_WIDTH = 120f
    private const val SVG_FALLBACK_HEIGHT = 20f

    /** SVG 根标签 / 长度属性：`width="88"`、`height='20px'` 等 */
    private val svgRootTagRegex = Regex("(?is)<svg\\b[^>]*>")
    private val svgLengthRegex = Regex("^([0-9]*\\.?[0-9]+)\\s*(px)?$")

    /**
     * 把 SVG 字节光栅化为位图（SVGDOM → 栅格 Surface → 快照）。
     * 尺寸按 [SVG_RASTER_SCALE] 倍放大以获得清晰边缘，总像素不超过 [SVG_MAX_PIXELS]
     * （超限则等比缩小倍率，宽高各自至少 1px，绝不出现 0 尺寸——0 尺寸会让 Compose 的
     * TextLinkScope 抛 NPE）；解析/渲染失败返回 null，由调用方降级为占位。
     */
    private fun rasterizeSvg(bytes: ByteArray): Decoded? = runCatching {
        Data.makeFromBytes(bytes, 0, bytes.size).use { data ->
            SVGDOM(data).use { dom ->
                val natural = svgNaturalSize(bytes, dom)
                val scale = minOf(
                    SVG_RASTER_SCALE,
                    sqrt(SVG_MAX_PIXELS / (natural.width * natural.height))
                )
                val width = (natural.width * scale).roundToInt().coerceAtLeast(1)
                val height = (natural.height * scale).roundToInt().coerceAtLeast(1)
                // 容器固定为「自然尺寸」：shields.io 徽章只声明 width/height 而没有 viewBox，
                // Skia 不会把内容缩放到容器大小。要放大清晰度必须缩放画布（而非容器），
                // 否则内容在放大后的位图里只占一角，再按自然尺寸绘制回来反而会缩水一半。
                dom.setContainerSize(natural.width, natural.height)
                Surface.makeRasterN32Premul(width, height).use { surface ->
                    // SVGDOM 不画根标签 style 里的 background/background-color（如 star-history 图的
                    // `background:#0d1117`），深色主题下会得到透明底 → 图表文字看不清。这里先铺一层底色
                    // （按光栅化画布尺寸），失败也不影响后续图形绘制。
                    runCatching {
                        svgBackgroundColor(bytes)?.let { bg ->
                            surface.canvas.drawRect(
                                Rect.makeWH(width.toFloat(), height.toFloat()),
                                Paint().apply { color = bg }
                            )
                        }
                    }
                    surface.canvas.scale(width / natural.width, height / natural.height)
                    dom.render(surface.canvas)
                    // skiko 的 SVGDOM 不画 <text>，这里补绘一遍（失败不影响已画好的底色）。
                    // 注意：画布此时已经带上了上面的放大变换，补绘直接用同一坐标系即可，不能再乘一次。
                    // natural 作为「视口尺寸」传入，用于解析 x/y 的百分比坐标。
                    runCatching { drawSvgTexts(bytes, surface.canvas, natural) }
                    Decoded(surface.makeImageSnapshot().toComposeImageBitmap(), natural)
                }
            }
        }
    }.getOrNull()

    /**
     * 读取 SVG 的「自然尺寸」（CSS px），依次尝试：
     * 1. 根 `<svg>` 标签上的 width/height（shields.io 徽章会写，如 `width="88" height="20"`）；
     * 2. 根节点的 viewBox（只给 viewBox 的矢量图常见）；
     * 3. SVGDOM 的 intrinsicSize（按 96 DPI 解析相对单位）；
     * 4. 兜底 120 × 20。
     */
    private fun svgNaturalSize(bytes: ByteArray, dom: SVGDOM): Size {
        val rootTag = svgRootTagRegex.find(String(bytes, 0, minOf(bytes.size, 8192), Charsets.UTF_8))?.value
        if (rootTag != null) {
            val w = parseSvgLength(htmlAttr(rootTag, "width"))
            val h = parseSvgLength(htmlAttr(rootTag, "height"))
            if (w != null && h != null) return Size(w, h)
        }
        val viewBox = runCatching { dom.root?.viewBox }.getOrNull()
        if (viewBox != null && viewBox.width > 0f && viewBox.height > 0f) {
            return Size(viewBox.width, viewBox.height)
        }
        val root = dom.root
        if (root != null) {
            val intrinsic = root.getIntrinsicSize(SVGLengthContext(SVG_FALLBACK_WIDTH, SVG_FALLBACK_HEIGHT, 96f))
            if (intrinsic.x > 0f && intrinsic.y > 0f) return Size(intrinsic.x, intrinsic.y)
        }
        return Size(SVG_FALLBACK_WIDTH, SVG_FALLBACK_HEIGHT)
    }

    /**
     * 解析 SVG 根标签声明的底色（`style="background:#0d1117"` 或 `background` / `background-color` 属性），
     * 返回不透明 ARGB。SVGDOM 不绘制该声明，故 [rasterizeSvg] 需要自己铺底。
     * 解析不到（无声明 / 渐变 url(...) / 颜色名不支持）返回 null，调用方跳过即可。
     */
    private fun svgBackgroundColor(bytes: ByteArray): Int? {
        val head = String(bytes, 0, minOf(bytes.size, 8192), Charsets.UTF_8)
        val rootTag = svgRootTagRegex.find(head)?.value ?: return null
        val fromStyle = htmlAttr(rootTag, "style")
            ?.split(';')
            ?.firstNotNullOfOrNull { decl ->
                val colon = decl.indexOf(':')
                if (colon <= 0) return@firstNotNullOfOrNull null
                val key = decl.substring(0, colon).trim().lowercase()
                if (key != "background" && key != "background-color") null
                else decl.substring(colon + 1).trim()
            }
        val raw = fromStyle
            ?: htmlAttr(rootTag, "background")
            ?: htmlAttr(rootTag, "background-color")
            ?: return null
        val rgb = parseSvgColor(raw) ?: return null
        return (0xFF shl 24) or rgb
    }

    /**
     * 解析 SVG 长度属性为 px 数值：只接受纯数值或 `px` 单位。
     * 百分比与 em/em 等相对单位无法在此确定真实尺寸（返回 null，交给下层兜底），只取数值会得到错误比例。
     */
    private fun parseSvgLength(raw: String?): Float? {
        val text = raw?.trim()?.lowercase() ?: return null
        val match = svgLengthRegex.find(text) ?: return null
        return match.groupValues[1].toFloatOrNull()?.takeIf { it > 0f }
    }

    /** 带符号的长度属性：`x="-7"`、`dx="-.09em"` 等相对/负值都要保留（文本定位常用负坐标） */
    private val svgSignedLengthRegex = Regex("^([-+]?[0-9]*\\.?[0-9]+)\\s*(px)?$")

    /** 解析带符号长度（纯数值 / px）；空、百分比、em 等返回 null */
    private fun parseSvgSignedLength(raw: String?): Float? {
        val text = raw?.trim()?.lowercase() ?: return null
        return svgSignedLengthRegex.find(text)?.groupValues?.get(1)?.toFloatOrNull()
    }

    /**
     * 解析长度并支持 `em` 单位（`1em` = 当前 font-size）。
     * 用于 `font-size`、`dx`/`dy` 这类相对量；其余单位退回 [parseSvgSignedLength]。
     */
    private fun parseSvgLengthEm(raw: String?, fontSize: Float): Float? {
        val text = raw?.trim()?.lowercase() ?: return null
        if (text.endsWith("em")) {
            return text.dropLast(2).trim().toFloatOrNull()?.times(fontSize)
        }
        return parseSvgSignedLength(text)
    }

    /** 解析 x / y 坐标：支持百分比（相对视口宽/高）与普通长度；缺省返回 null，由调用方按 0 处理 */
    private fun parseSvgAxis(raw: String?, viewport: Float, fontSize: Float): Float? {
        val text = raw?.trim() ?: return null
        if (text.endsWith("%")) {
            val percent = text.dropLast(1).toFloatOrNull() ?: return null
            return percent / 100f * viewport
        }
        return parseSvgLengthEm(text, fontSize)
    }

    // ---------------------------------------------------------------------------------------------
    // SVG <text> 补绘
    // ---------------------------------------------------------------------------------------------

    /**
     * skiko 0.8.18 的 SVGDOM 不渲染 SVG 的 `<text>`：`org.jetbrains.skia.svg` 绑定里根本没有文本节点类，
     * 实测 shields.io 徽章经 [rasterizeSvg] 光栅化后只剩两块底色矩形，文字整块丢失（徽章于是看着像色块）。
     * 这里在 SVGDOM 画完底色之后，自己解析 `<text>` 并按 SVG 的定位规则补绘一遍，徽章文字才读得出来。
     *
     * 覆盖范围：x / y（含百分比）、dx / dy（含 em）、font-size / font-family / font-weight、fill（含
     * fill-opacity / opacity、currentColor）、text-anchor、textLength、`style="..."` 内联声明（优先级高于
     * 同名呈现属性，与 CSS 一致）、`<tspan>` 子节点（按顺序拼接内容，支持 dx/dy、x/y 覆盖与 font-size/fill
     * 覆盖），以及祖先 `<g>`/`<svg>` 与自身上的 translate / scale / matrix 变换（沿继承链累积）。
     * 不支持的写法（rotate/skew、渐变填色、stroke、CSS 类规则）退化为「按已有信息尽力绘制」，最差只是该段
     * 文字不显示；调用处还包了 runCatching —— 文字补绘失败绝不能连累已经画好的底色。
     *
     * 绘制用的是与底色同一张画布的当前坐标系（调用时画布已带好光栅化放大），因此这里不再缩放。
     * [viewport] 为 SVG 自然尺寸，用于解析 x / y 的百分比坐标。
     */
    private fun drawSvgTexts(bytes: ByteArray, canvas: Canvas, viewport: Size) {
        val items = parseSvgTexts(String(bytes, Charsets.UTF_8), viewport)
        if (items.isEmpty()) return
        val fontMgr = FontMgr.default
        for (item in items) drawSvgTextItem(canvas, fontMgr, item)
    }

    /**
     * 画一个 `<text>`：先把各段（tspan）按各自的字体量宽求和，再按 text-anchor 定位，
     * 最后逐段顺序绘制（段间按 dx / dy 平移、按行宽推进）。
     */
    private fun drawSvgTextItem(canvas: Canvas, fontMgr: FontMgr, item: SvgTextItem) {
        if (item.runs.isEmpty()) return
        val laid = ArrayList<LaidRun>(item.runs.size)
        for (run in item.runs) {
            val typeface = matchTypeface(fontMgr, run.families, run.bold) ?: continue
            laid.add(LaidRun(run, TextLine.make(run.text, Font(typeface, run.fontSize))))
        }
        if (laid.isEmpty()) return
        val totalWidth = laid.fold(0f) { acc, l -> acc + l.line.width }
        if (totalWidth <= 0f) return
        val anchorOffset = when (item.anchor) {
            SVG_ANCHOR_MIDDLE -> -totalWidth / 2f
            SVG_ANCHOR_END -> -totalWidth
            else -> 0f
        }
        canvas.save()
        canvas.concat(item.matrix)
        val paint = Paint().apply { isAntiAlias = true }
        if (laid.none { it.run.x != null || it.run.y != null }) {
            // 各段共用起点：整体做一次定位（含 textLength 的横向缩放近似），段内只按 dx / dy 微调
            canvas.translate(item.x + item.dx + anchorOffset, item.y + item.dy)
            val k = item.textLength?.takeIf { it > 0f }?.let { it / totalWidth } ?: 0f
            if (k > 0f && k != 1f) canvas.scale(k, 1f)
            var cx = 0f
            var cy = 0f
            for (l in laid) {
                cx += l.run.dx
                cy += l.run.dy
                paint.color = l.run.color
                canvas.drawTextLine(l.line, cx, cy, paint)
                cx += l.line.width
            }
        } else {
            // 有 tspan 用 x / y 绝对定位：逐段按绝对坐标摆放（textLength 在此不做缩放近似）
            var cx = item.x + item.dx + anchorOffset
            var cy = item.y + item.dy
            for (l in laid) {
                l.run.x?.let { cx = it }
                l.run.y?.let { cy = it }
                cx += l.run.dx
                cy += l.run.dy
                paint.color = l.run.color
                canvas.drawTextLine(l.line, cx, cy, paint)
                cx += l.line.width
            }
        }
        canvas.restore()
    }

    /** 已排版的一段：原始 run + 用它自己的字体量出的 TextLine */
    private class LaidRun(val run: SvgTextRun, val line: TextLine)

    /** 按字体族列表匹配可用字体；都匹配不上（如内嵌的 xkcd 手写体）则回退系统默认字体，保证文字仍能画出来 */
    private fun matchTypeface(fontMgr: FontMgr, families: List<String>, bold: Boolean): Typeface? {
        val style = if (bold) FontStyle.BOLD else FontStyle.NORMAL
        for (family in families) {
            if (family.isBlank()) continue
            fontMgr.matchFamilyStyle(family, style)?.let { return it }
        }
        // 通用族名（sans-serif 等）与 null 在部分 skiko 版本/环境下匹配不到（实测返回 null），
        // legacyMakeTypeface("") 总能拿到系统默认字体（如 Segoe UI），兜底后文字不会因字体缺失整段消失。
        return fontMgr.matchFamilyStyle(null, style)
            ?: runCatching { fontMgr.legacyMakeTypeface("", style) }.getOrNull()
    }

    /** SVG 里一个待补绘的文本（样式已按 `<g>`/`<svg>` 继承链解析完毕，内含若干段） */
    private class SvgTextItem(
        val runs: List<SvgTextRun>,
        val x: Float,
        val y: Float,
        val dx: Float,
        val dy: Float,
        val textLength: Float?,
        val anchor: Int,
        val matrix: Matrix33,
    )

    /** `<text>` 里的一段：来自直接文本或某个 `<tspan>`，携带该段的定位、字体与颜色（覆盖继承值） */
    private class SvgTextRun(
        val text: String,
        val x: Float?,
        val y: Float?,
        val dx: Float,
        val dy: Float,
        val fontSize: Float,
        val families: List<String>,
        val bold: Boolean,
        val color: Int,
    )

    /** 解析 `<tspan>` 时栈上保存的上下文：当前样式 + 该元素的定位属性 */
    private class RunContext(
        val style: SvgTextStyle,
        val x: Float?,
        val y: Float?,
        val dx: Float,
        val dy: Float,
    )

    /** `<text>` / `<g>` 上会被后代继承的样式 + 累积变换 */
    private class SvgTextStyle(
        val fill: Int?,
        val color: Int,
        val opacity: Float,
        val fontSize: Float,
        val families: List<String>,
        val bold: Boolean,
        val anchor: Int,
        val matrix: FloatArray,
    ) {
        /**
         * 叠加本元素的样式（没写的沿用继承值），并把本元素的 transform 累积进矩阵。
         * `style="..."` 内联声明优先于同名呈现属性（与 CSS 一致）：先合并成一份属性表再读取。
         */
        fun inherit(attrs: Map<String, String>): SvgTextStyle {
            val m = mergedAttrs(attrs)
            // color（currentColor 的取值）本身也是可继承属性
            val newColor = m["color"]?.let { parseSvgColor(it) } ?: color
            val fillDecl = m["fill"]
            return SvgTextStyle(
                // 显式 fill="none" 要能覆盖掉继承来的颜色（解析为 null），故不能简单用 `?: fill`
                fill = if (fillDecl == null) fill else resolveFill(fillDecl, newColor),
                color = newColor,
                opacity = opacity *
                    (m["fill-opacity"]?.toFloatOrNull() ?: 1f) *
                    (m["opacity"]?.toFloatOrNull() ?: 1f),
                fontSize = m["font-size"]?.let { parseSvgLengthEm(it, fontSize) } ?: fontSize,
                families = m["font-family"]
                    ?.split(',')
                    ?.map { it.trim().trim('\'', '"') }
                    ?.filter { it.isNotEmpty() }
                    ?: families,
                bold = m["font-weight"]?.let { it.equals("bold", true) || (it.toIntOrNull() ?: 0) >= 600 } ?: bold,
                anchor = m["text-anchor"]?.let { parseSvgAnchor(it) } ?: anchor,
                matrix = parseSvgTransform(m["transform"], matrix),
            )
        }
    }

    /**
     * 合并呈现属性与 `style` 内联声明：同名键以 style 为准（lowercase 键名）。
     * style 值里的空格（如 `font-size:16px`）与结尾分号都容错处理。
     */
    private fun mergedAttrs(attrs: Map<String, String>): Map<String, String> {
        val style = attrs["style"] ?: return attrs
        val merged = HashMap(attrs)
        for (decl in style.split(';')) {
            val colon = decl.indexOf(':')
            if (colon <= 0) continue
            val key = decl.substring(0, colon).trim().lowercase()
            val value = decl.substring(colon + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) merged[key] = value
        }
        return merged
    }

    /** 解析 fill 值：`currentColor` 取继承的 color，其余交给 [parseSvgColor]（none / 渐变 → null） */
    private fun resolveFill(raw: String, currentColor: Int): Int? {
        val value = raw.trim()
        if (value.equals("currentColor", ignoreCase = true)) return currentColor
        return parseSvgColor(value)
    }

    private const val SVG_ANCHOR_START = 0
    private const val SVG_ANCHOR_MIDDLE = 1
    private const val SVG_ANCHOR_END = 2

    /** 仿射矩阵单位阵 [a,b,c,d,e,f] */
    private val SVG_IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

    /** SVG 初始样式：fill = color = black、font-size = medium ≈ 16、text-anchor = start、无变换 */
    private val SVG_DEFAULT_STYLE = SvgTextStyle(
        fill = 0x000000,
        color = 0x000000,
        opacity = 1f,
        fontSize = 16f,
        families = emptyList(),
        bold = false,
        anchor = SVG_ANCHOR_START,
        matrix = SVG_IDENTITY,
    )

    /** SVG 任意标签：group1 = 结束斜杠，group2 = 标签名，group3 = 属性串（属性值里的 `>` 不算标签结束） */
    private val svgTagRegex = Regex("(?s)<(/?)([A-Za-z][\\w:.-]*)((?:[^>\"']|\"[^\"]*\"|'[^']*')*)>")
    private val svgAttrRegex = Regex("([A-Za-z_:][-\\w:.]*)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')")
    private val svgTransformRegex = Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)")
    private val svgRgbRegex = Regex("^rgba?\\(([^)]*)\\)$")

    /** SVG/CSS 常见颜色名（shields.io 只会输出十六进制，这里只为通用性兜底） */
    private val svgNamedColors = mapOf(
        "white" to 0xFFFFFF,
        "black" to 0x000000,
        "red" to 0xFF0000,
        "green" to 0x008000,
        "blue" to 0x0000FF,
        "gray" to 0x808080,
        "grey" to 0x808080,
        "lightgrey" to 0xD3D3D3,
        "lightgray" to 0xD3D3D3,
        "yellow" to 0xFFFF00,
        "orange" to 0xFFA500,
        "purple" to 0x800080,
    )

    /**
     * 从 SVG 源码里抽出所有 `<text>`：沿 `<g>`/`<svg>` 维护继承样式与累积变换，
     * 文本内容取起始标签到对应 `</text>` 之间的内容，再交给 [collectTextRuns] 处理 `<tspan>` 子节点。
     */
    private fun parseSvgTexts(svg: String, viewport: Size): List<SvgTextItem> {
        val items = ArrayList<SvgTextItem>()
        val stack = ArrayList<SvgTextStyle>()
        stack.add(SVG_DEFAULT_STYLE)
        var from = 0
        while (true) {
            val m = svgTagRegex.find(svg, from) ?: break
            val closing = m.groupValues[1] == "/"
            val tag = m.groupValues[2].lowercase()
            when (tag) {
                "g", "svg" -> {
                    if (closing) {
                        if (stack.size > 1) stack.removeAt(stack.size - 1)
                    } else {
                        stack.add(stack.last().inherit(svgAttrs(m.groupValues[3])))
                    }
                    from = m.range.last + 1
                }
                "text" -> {
                    val end = if (closing) -1 else svg.indexOf("</text>", m.range.last + 1)
                    if (end < 0) {
                        from = m.range.last + 1
                    } else {
                        val attrs = svgAttrs(m.groupValues[3])
                        val inner = svg.substring(m.range.last + 1, end)
                        buildSvgTextItem(inner, stack.last(), attrs, viewport)?.let { items.add(it) }
                        from = end + "</text>".length
                    }
                }
                else -> from = m.range.last + 1
            }
        }
        return items
    }

    /**
     * 组装一个 `<text>`：样式叠加自身属性（style 优先），定位属性（x/y/dx/dy/textLength）取自自身，
     * 文本内容递归展开 `<tspan>` 得到若干段。全空（如仅一个空格）或不可见（fill=none / opacity≈0）返回 null。
     */
    private fun buildSvgTextItem(
        inner: String,
        parent: SvgTextStyle,
        attrs: Map<String, String>,
        viewport: Size,
    ): SvgTextItem? {
        val style = parent.inherit(attrs)
        val runs = ArrayList<SvgTextRun>()
        collectTextRuns(inner, style, viewport, runs)
        if (runs.isEmpty()) return null
        val m = mergedAttrs(attrs)
        // SVG 的 x/y 是锚点坐标（缺省 0），y 是基线；dx/dy 是相对位移（em 以本元素字号换算）
        return SvgTextItem(
            runs = runs,
            x = parseSvgAxis(m["x"], viewport.width, style.fontSize) ?: 0f,
            y = parseSvgAxis(m["y"], viewport.height, style.fontSize) ?: 0f,
            dx = parseSvgLengthEm(m["dx"], style.fontSize) ?: 0f,
            dy = parseSvgLengthEm(m["dy"], style.fontSize) ?: 0f,
            textLength = parseSvgSignedLength(m["textlength"])?.takeIf { it > 0f },
            anchor = style.anchor,
            matrix = toMatrix33(style.matrix),
        )
    }

    /**
     * 递归展开 `<text>` 内部内容为若干绘制段：沿 `<tspan>` 维护样式与定位属性（x/y 覆盖、dx/dy 累积、
     * font-size/fill 覆盖），每个非空白文本节点产出一段。其余标签（如 `<a>`）只透传内容。
     */
    private fun collectTextRuns(inner: String, base: SvgTextStyle, viewport: Size, out: MutableList<SvgTextRun>) {
        val stack = ArrayList<RunContext>()
        stack.add(RunContext(base, null, null, 0f, 0f))
        var from = 0
        while (true) {
            val m = svgTagRegex.find(inner, from) ?: break
            // 标签之前的文本节点属于当前最内层元素
            emitTextRun(inner.substring(from, m.range.first), stack.last(), out)
            val closing = m.groupValues[1] == "/"
            if (m.groupValues[2].lowercase() == "tspan") {
                if (closing) {
                    if (stack.size > 1) stack.removeAt(stack.size - 1)
                } else {
                    val attrs = svgAttrs(m.groupValues[3])
                    val parent = stack.last()
                    val style = parent.style.inherit(attrs)
                    val a = mergedAttrs(attrs)
                    stack.add(
                        RunContext(
                            style = style,
                            x = parseSvgAxis(a["x"], viewport.width, style.fontSize),
                            y = parseSvgAxis(a["y"], viewport.height, style.fontSize),
                            dx = parseSvgLengthEm(a["dx"], style.fontSize) ?: 0f,
                            dy = parseSvgLengthEm(a["dy"], style.fontSize) ?: 0f,
                        )
                    )
                }
            }
            from = m.range.last + 1
        }
        emitTextRun(inner.substring(from), stack.last(), out)
    }

    /** 产出一段文本：空白（如 `<text> </text>`）或不可见直接跳过，否则按当前上下文解析颜色 */
    private fun emitTextRun(text: String, ctx: RunContext, out: MutableList<SvgTextRun>) {
        val decoded = decodeXml(text)
        if (decoded.isBlank()) return
        val rgb = ctx.style.fill ?: return
        val alpha = ctx.style.opacity.coerceIn(0f, 1f)
        if (alpha <= 0.01f) return
        out.add(
            SvgTextRun(
                text = decoded,
                x = ctx.x,
                y = ctx.y,
                dx = ctx.dx,
                dy = ctx.dy,
                fontSize = ctx.style.fontSize,
                families = ctx.style.families,
                bold = ctx.style.bold,
                color = ((alpha * 255f).roundToInt() shl 24) or (rgb and 0xFFFFFF),
            )
        )
    }

    /** 解析标签属性串为「小写键 → 值」 */
    private fun svgAttrs(raw: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (m in svgAttrRegex.findAll(raw)) {
            val doubleQuoted = m.groupValues[3]
            val singleQuoted = m.groupValues[4]
            map[m.groupValues[1].lowercase()] = if (doubleQuoted.isEmpty() && singleQuoted.isNotEmpty()) singleQuoted else doubleQuoted
        }
        return map
    }

    private fun parseSvgAnchor(raw: String): Int = when (raw.trim().lowercase()) {
        "middle" -> SVG_ANCHOR_MIDDLE
        "end" -> SVG_ANCHOR_END
        else -> SVG_ANCHOR_START
    }

    /**
     * 解析 transform 为仿射矩阵并与 [base] 叠加（SVG 的变换按出现顺序左乘）。
     * rotate/skew 之类不做处理（保持已有矩阵），避免算错比不画更糟。
     */
    private fun parseSvgTransform(value: String?, base: FloatArray): FloatArray {
        if (value.isNullOrBlank()) return base
        var matrix = base
        for (t in svgTransformRegex.findAll(value)) {
            val nums = t.groupValues[2]
                .split(',', ' ', '\t', '\n')
                .mapNotNull { it.trim().toFloatOrNull() }
            val local = when {
                t.groupValues[1] == "matrix" && nums.size >= 6 ->
                    floatArrayOf(nums[0], nums[1], nums[2], nums[3], nums[4], nums[5])
                t.groupValues[1] == "translate" && nums.isNotEmpty() ->
                    floatArrayOf(1f, 0f, 0f, 1f, nums[0], nums.getOrElse(1) { 0f })
                t.groupValues[1] == "scale" && nums.isNotEmpty() ->
                    floatArrayOf(nums[0], 0f, 0f, nums.getOrElse(1) { nums[0] }, 0f, 0f)
                else -> null
            } ?: continue
            matrix = mulAffine(matrix, local)
        }
        return matrix
    }

    /** 两个仿射矩阵相乘：先应用 [a] 再应用 [b] */
    private fun mulAffine(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[0] * b[0] + a[2] * b[1],
        a[1] * b[0] + a[3] * b[1],
        a[0] * b[2] + a[2] * b[3],
        a[1] * b[2] + a[3] * b[3],
        a[0] * b[4] + a[2] * b[5] + a[4],
        a[1] * b[4] + a[3] * b[5] + a[5],
    )

    /** 仿射 [a,b,c,d,e,f] → skiko 的 3×3（行主序） */
    private fun toMatrix33(m: FloatArray): Matrix33 =
        Matrix33(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f)

    /** 解析 SVG 填色为 RGB（0xRRGGBB）；none / currentColor / 渐变等无法解析的返回 null */
    private fun parseSvgColor(raw: String): Int? {
        val value = raw.trim().lowercase()
        if (value.isEmpty() || value == "none" || value == "currentcolor" || value.startsWith("url(")) return null
        svgNamedColors[value]?.let { return it }
        if (value.startsWith("#")) {
            val hex = value.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return null
                    val g = hex[1].digitToIntOrNull(16) ?: return null
                    val b = hex[2].digitToIntOrNull(16) ?: return null
                    (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
                }
                6 -> hex.toIntOrNull(16)
                else -> null
            }
        }
        val parts = svgRgbRegex.find(value)?.groupValues?.get(1)?.split(',') ?: return null
        if (parts.size < 3) return null
        val channels = parts.take(3).map { part ->
            val p = part.trim()
            (if (p.endsWith("%")) (p.dropLast(1).toFloatOrNull()?.times(2.55f)) else p.toFloatOrNull())
                ?.roundToInt()?.coerceIn(0, 255) ?: return null
        }
        return (channels[0] shl 16) or (channels[1] shl 8) or channels[2]
    }

    private fun decodeXml(s: String): String = s
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    /** 是否为 GitHub 系域名（仅这些域套镜像前缀才有意义）；严格匹配防止 evilgithub.com 等仿冒域被误套 */
    private fun isGitHubDomain(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host == "github.com" || host.endsWith(".github.com") ||
            host == "raw.githubusercontent.com" || host.endsWith(".githubusercontent.com")
    }

    private fun cached(url: String): ImageBitmap? = synchronized(cacheLock) { cache[url] }

    private fun put(url: String, image: ImageBitmap, naturalSize: Size) {
        synchronized(cacheLock) {
            // naturalSizes 与 cache 必须成对写入：两者键集严格一致，淘汰逻辑才不会有漏网条目
            naturalSizes[url] = naturalSize
            cache[url] = image
        }
    }

    // ---------------------------------------------------------------------------------------------
    // README 预处理
    // ---------------------------------------------------------------------------------------------

    /** Markdown 图片：![alt](url) */
    private val imageRegex = Regex("!\\[([^]]*)\\]\\(([^)]+)\\)")

    /**
     * Markdown 链接，含 `[![badge](img)](链接)` 这种嵌套图片的写法。
     * 若用小括号前的 `[^]]+` 直接取 label，会在嵌套写法里停在图片的 `]` 上，
     * 把图片 URL 误当链接目标、并顺手跳过真正的链接目标（外层链接保持相对路径）。
     */
    private val linkRegex = Regex("(?<!!)\\[((?:!\\[[^]]*\\]\\([^)]*\\))|[^\\[\\]]*)\\]\\(([^)]+)\\)")

    private val summaryTagRegex = Regex("(?is)<summary>(.*?)</summary>")
    private val inlineBoldTagRegex = Regex("(?i)</?b>")

    /**
     * 一次扫描所有 HTML 标签：group1 = 结束斜杠，group2 = 标签名。
     * `<img>` 转 Markdown 图片（附尺寸/居中标记），其余仅用于排版的标签丢弃、保留内部文本。
     */
    private val htmlTagRegex = Regex(
        "(?is)<\\s*(/?)\\s*(img|div|center|details|summary|picture|source|a|b|i|br|span)\\b[^>]*>"
    )

    /** 围栏代码块（``` / ~~~）起始行 */
    private val fenceLineRegex = Regex("^\\s{0,3}(`{3,}|~{3,})")

    /**
     * README 预处理：补全相对链接，并把渲染器不支持的 HTML 转成 Markdown 等价写法。
     *
     * - **行尾归一化**：CRLF/CR → LF。JetBrains Markdown 的 HTML 块以「空行」收尾，
     *   而 CRLF 文档里的空行是 `\r`，导致 HTML 块一直吞到文档末尾 —— 0.33.0 不渲染 HTML_BLOCK，
     *   整篇 README 会变成空白（上游 Android README 即 CRLF，实测整篇被解析成单个 HTML_BLOCK）。
     * - **HTML 图片**：`<img src="..." alt="...">` → `![alt](绝对 URL)`（渲染器只认 Markdown 图片语法）；
     *   `width/height` 与所在 `<div align="center">` 的居中意图编码进 URL fragment
     *   （`#yunx-w=120&c=1`），由 [transform] 解析后还原为绘制尺寸与居中。
     * - **折叠块**：`<summary><b>问题</b></summary>` → `**问题**`，其余排版标签（div/details/picture/source/a…）去掉，
     *   保留内部文本，避免「问题标题」「Star History」这类内容整块消失。
     * - **`<picture>`**：按 [isDarkTheme] 从内部 `<source>` 里挑选匹配 `prefers-color-scheme` 的变体
     *   （见 [normalizePictures]），否则退回块内 `<img src>` —— 否则永远渲染不带 theme 的浅色版图表。
     * - **图表 theme 参数**：对白名单图表服务（Star History / github-readme-stats 等）的图片 URL
     *   补齐或改写 `theme=dark|light`（见 [applyChartTheme]），让「普通 Markdown 图片」写的图表
     *   也能随主题变深浅，不再在深色界面里留下刺眼的白色卡片；`<picture>` 选择逻辑仍先跑。
     * - **相对链接**：`[text](./x)` / `![alt](img.png)` 补全为 github.com/blob 与 raw.githubusercontent.com；
     *   绝对 URL、`#锚点`、含转义括号的链接保持原样。
     * - 围栏代码块与行内代码原样保留，不做上述改写（避免改坏示例代码）。
     */
    fun preprocessReadme(md: String, owner: String, repo: String, branch: String, isDarkTheme: Boolean): String {
        val blobBase = "https://github.com/$owner/$repo/blob/$branch/"
        val rawBase = "https://raw.githubusercontent.com/$owner/$repo/$branch/"
        val normalized = md.replace("\r\n", "\n").replace('\r', '\n')
        val sb = StringBuilder(normalized.length + 256)
        for ((text, isCode) in splitCodeSegments(normalized)) {
            sb.append(if (isCode) text else transformProse(text, blobBase, rawBase, isDarkTheme))
        }
        return sb.toString()
    }

    /** 对「非代码」文本做 HTML 归一化 + 相对链接补全 */
    private fun transformProse(text: String, blobBase: String, rawBase: String, isDarkTheme: Boolean): String {
        val htmlNormalized = normalizeHtml(text, rawBase, isDarkTheme)
        // 先图片后链接：链接标签里可能嵌图片，顺序反了会把图片当普通文本
        val images = imageRegex.replace(htmlNormalized) { m ->
            val alt = m.groupValues[1]
            // 补全相对路径后，给按主题自适应的图表服务补齐/改写 theme 参数（普通 Markdown 图片与
            // <picture> 选出的变体都会走到这里；<source> 选择已在 normalizeHtml 里先跑完）
            val resolved = resolveRel(rawBase, m.groupValues[2].trim())
            "![$alt](${applyChartTheme(resolved, isDarkTheme)})"
        }
        return linkRegex.replace(images) { m ->
            val label = m.groupValues[1]
            "[$label](${resolveRel(blobBase, m.groupValues[2].trim())})"
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 图表类图片的 theme 参数补齐（按主题自适应的图表服务）
    // ---------------------------------------------------------------------------------------------

    /**
     * 「按主题自适应的图表服务」主机白名单：只对这些主机改写/补齐 `theme` 参数，避免误改用户其他图片 URL。
     * - `api.star-history.com`：仅 `/chart` 端点（Star History 图表）；
     * - `github-readme-stats` / `github-readme-streak-stats` 系列（首个子域即以它们开头，
     *   覆盖 `github-readme-stats.vercel.app` 与其常见镜像域）。
     */
    private fun isThemedChartHost(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        if (host == "api.star-history.com") return uri.path?.startsWith("/chart") == true
        val firstLabel = host.substringBefore('.')
        return firstLabel.startsWith("github-readme-stats") ||
            firstLabel.startsWith("github-readme-streak-stats")
    }

    /**
     * 给白名单内的图表 URL 补齐 / 改写 `theme` 参数（深色 `dark` / 浅色 `light`）。
     *
     * 处理顺序很关键：**先剥离 fragment**。`#yunx-w=430&c=1` 是 [withSizeMarker] 写入的尺寸/居中标记，
     * 网络请求前会被 [parseImageTarget] 剥掉，也是缓存键的依据 —— `theme` 只能插进 `?`/`&` 组成的查询串，
     * 绝不能落到 `#` 之后；改完查询串再把 fragment 原样接回。
     * 已有 `theme=` 时改写其值（切主题后结果才正确）；没有则按「已有查询串用 `&`、没有用 `?`」追加。
     * 幂等：`theme` 已与当前主题一致时，重写结果与原 URL 相同。主机不在白名单内原样返回。
     */
    private fun applyChartTheme(url: String, isDarkTheme: Boolean): String {
        if (!isThemedChartHost(url)) return url
        val theme = if (isDarkTheme) "dark" else "light"
        val hashIndex = url.indexOf('#')
        val main = if (hashIndex < 0) url else url.substring(0, hashIndex)
        val fragment = if (hashIndex < 0) "" else url.substring(hashIndex)
        val queryIndex = main.indexOf('?')
        val base = if (queryIndex < 0) main else main.substring(0, queryIndex)
        val query = if (queryIndex < 0) "" else main.substring(queryIndex + 1)
        val parts = ArrayList<String>()
        if (query.isNotEmpty()) parts.addAll(query.split('&'))
        var replaced = false
        for (i in parts.indices) {
            if (parts[i].substringBefore('=').equals("theme", ignoreCase = true)) {
                parts[i] = "theme=$theme"
                replaced = true
                break
            }
        }
        if (!replaced) parts.add("theme=$theme")
        return base + "?" + parts.joinToString("&") + fragment
    }

    /** 把 HTML 转成渲染器能显示的 Markdown（图片）或纯文本（其余排版标签），并跟踪居中上下文 */
    private fun normalizeHtml(text: String, rawBase: String, isDarkTheme: Boolean): String {
        // <summary><b>问题</b></summary> → **问题**（先处理，避免下面把 summary 内部当普通标签拆散）
        val summaryFixed = summaryTagRegex.replace(text) { m ->
            "**" + m.groupValues[1].replace(inlineBoldTagRegex, "").trim() + "**"
        }
        // <picture> 按当前明暗主题挑选 <source> 变体，替换成单个 <img>（随后与普通 img 走同一条转换路径）
        val pictureFixed = normalizePictures(summaryFixed, isDarkTheme)
        val sb = StringBuilder(pictureFixed.length)
        // <div align="center"> / <center> 的嵌套深度：深度 > 0 时其中的 <img> 标记为居中
        var centerDepth = 0
        var last = 0
        for (m in htmlTagRegex.findAll(pictureFixed)) {
            sb.append(pictureFixed, last, m.range.first)
            last = m.range.last + 1
            val tag = m.value
            val closing = m.groupValues[1] == "/"
            when (m.groupValues[2].lowercase()) {
                "img" -> {
                    val src = htmlAttr(tag, "src")
                    if (src == null) {
                        // 没有 src：原样保留（渲染器会当纯文本显示）
                        sb.append(tag)
                    } else {
                        val alt = htmlAttr(tag, "alt").orEmpty()
                        val url = resolveRel(rawBase, src)
                        val marked = withSizeMarker(
                            url,
                            htmlAttr(tag, "width"),
                            htmlAttr(tag, "height"),
                            centerDepth > 0
                        )
                        sb.append("![$alt]($marked)")
                    }
                }
                "div", "center" -> {
                    if (closing) {
                        if (centerDepth > 0) centerDepth--
                    } else if (m.groupValues[2].lowercase() == "center" || isCenterTag(tag)) {
                        centerDepth++
                    }
                }
                // 其余仅用于排版的标签直接去掉，保留内部文本
                else -> Unit
            }
        }
        sb.append(pictureFixed, last, pictureFixed.length)
        return sb.toString()
    }

    // ---------------------------------------------------------------------------------------------
    // HTML <picture> / <source>：按当前明暗主题挑选图片变体
    // ---------------------------------------------------------------------------------------------

    /** `<picture ...> ... </picture>` 块（块内不含嵌套 picture，非贪婪即可） */
    private val pictureBlockRegex = Regex("(?is)<picture\\b[^>]*>(.*?)</picture>")

    /** `<source ...>` 标签 */
    private val sourceTagRegex = Regex("(?is)<source\\b[^>]*>")

    /** `<img ...>` 标签（取块内第一个作为兜底） */
    private val pictureImgTagRegex = Regex("(?is)<img\\b[^>]*>")

    /**
     * 把 `<picture>...</picture>` 归一化为单个 `<img>`：按当前主题从内部 `<source>` 里挑选变体。
     * 选择规则（大小写不敏感）：
     * - 深色主题 → `media` 含 `dark`（`prefers-color-scheme: dark`）的 `<source>` 的 srcset；
     * - 浅色主题 → `media` 含 `light` 的 `<source>` 的 srcset；
     * - 都不匹配 → 块内 `<img src>`；连 `<img>` 都没有时退回「无 media 的 `<source>`」或首个 `<source>`。
     * 结果保留原 `<img>` 的 alt/width/height 等属性，只替换 src，从而继续走普通 img 的尺寸/居中标记逻辑。
     */
    private fun normalizePictures(text: String, isDarkTheme: Boolean): String {
        if (!text.contains("<picture", ignoreCase = true)) return text
        return pictureBlockRegex.replace(text) { m ->
            val inner = m.groupValues[1]
            var themed: String? = null      // 命中当前主题的 <source>
            var noMedia: String? = null     // 无 media 的 <source>（默认候选）
            var anySource: String? = null   // 任意带 srcset 的 <source>
            for (s in sourceTagRegex.findAll(inner)) {
                val srcset = firstSrcsetUrl(htmlAttr(s.value, "srcset")) ?: continue
                if (anySource == null) anySource = srcset
                val media = htmlAttr(s.value, "media")
                if (media.isNullOrBlank()) {
                    if (noMedia == null) noMedia = srcset
                } else if (themed == null && mediaMatchesTheme(media, isDarkTheme)) {
                    themed = srcset
                }
            }
            val imgTag = pictureImgTagRegex.find(inner)?.value
            val fallback = imgTag?.let { htmlAttr(it, "src")?.takeIf { v -> v.isNotBlank() } }
            val url = themed ?: fallback ?: noMedia ?: anySource ?: return@replace ""
            if (imgTag == null) "<img src=\"$url\" alt=\"\">"
            else replaceAttr(imgTag, "src", url) ?: "<img src=\"$url\" alt=\"\">"
        }
    }

    /** `media` 是否匹配当前主题：深色看 `dark`、浅色看 `light` */
    private fun mediaMatchesTheme(media: String, isDarkTheme: Boolean): Boolean {
        val value = media.lowercase()
        return if (isDarkTheme) value.contains("dark") else value.contains("light")
    }

    /** 取 srcset 第一个候选的 URL：候选以逗号分隔，URL 与可选描述符以空白分隔 */
    private fun firstSrcsetUrl(srcset: String?): String? {
        val value = srcset?.trim().orEmpty()
        if (value.isEmpty()) return null
        val first = value.substringBefore(',').trim()
        if (first.isEmpty()) return null
        return first.split(Regex("\\s+"), limit = 2).first().ifBlank { return null }
    }

    /** 替换标签里的属性值（双/单引号均可）；属性不存在返回 null */
    private fun replaceAttr(tag: String, name: String, value: String): String? {
        val match = Regex("(?i)\\b$name\\s*=\\s*(\"[^\"]*\"|'[^']*')").find(tag) ?: return null
        val attrName = match.value.substringBefore('=').trimEnd()
        return tag.substring(0, match.range.first) + attrName + "=\"" + value + "\"" +
            tag.substring(match.range.last + 1)
    }

    /** 是否是「居中」容器标签：align="center" 或 style 含 text-align:center */
    private fun isCenterTag(tag: String): Boolean {
        if (htmlAttr(tag, "align")?.trim().equals("center", ignoreCase = true)) return true
        val style = htmlAttr(tag, "style")?.replace(" ", "") ?: return false
        return style.contains("text-align:center", ignoreCase = true)
    }

    /**
     * 把 `<img>` 的 width/height 与居中意图编码进图片 URL 的 fragment：
     * `#yunx-w=430&yunx-h=200&c=1`。fragment 不会随请求发给服务器，[transform] 也会在
     * 请求与缓存前剥掉它，因此不影响真实图片 URL。无任何信息可编码时原样返回。
     */
    private fun withSizeMarker(url: String, width: String?, height: String?, center: Boolean): String {
        val w = width?.trim()?.toFloatOrNull()?.takeIf { it > 0f }
        val h = height?.trim()?.toFloatOrNull()?.takeIf { it > 0f }
        if (w == null && h == null && !center) return url
        val marker = buildString {
            if (w != null) append(if (isEmpty()) "yunx-w=" else "&yunx-w=").append(w.toInt())
            if (h != null) append(if (isEmpty()) "yunx-h=" else "&yunx-h=").append(h.toInt())
            if (center) append(if (isEmpty()) "c=1" else "&c=1")
        }
        // 覆盖图片 src 自带 fragment 的情况，避免拼出两个 '#'
        return url.substringBefore('#') + "#" + marker
    }

    /** 取 HTML 标签属性值（双引号/单引号均可），不存在返回 null */
    private fun htmlAttr(tag: String, name: String): String? =
        Regex("(?i)\\b$name\\s*=\\s*\"([^\"]*)\"").find(tag)?.groupValues?.get(1)
            ?: Regex("(?i)\\b$name\\s*=\\s*'([^']*)'").find(tag)?.groupValues?.get(1)

    /**
     * 把文档切成「代码段」与「普通段」：围栏代码块（``` / ~~~）与行内代码（`x`）内的内容原样保留。
     * 返回 (片段, 是否代码段)。
     */
    private fun splitCodeSegments(md: String): List<Pair<String, Boolean>> {
        val segments = ArrayList<Pair<String, Boolean>>()
        val lines = md.split("\n")
        var buf = StringBuilder()
        var fence: String? = null

        fun flush(protected: Boolean) {
            if (buf.isNotEmpty()) {
                segments += buf.toString() to protected
                buf = StringBuilder()
            }
        }

        lines.forEachIndexed { index, line ->
            val marker = fenceLineRegex.find(line)?.groupValues?.get(1)
            when {
                // 开围栏：先收尾普通段，再从本行开始收集代码段
                fence == null && marker != null -> {
                    flush(false)
                    fence = marker
                    buf.append(line)
                }
                // 闭围栏：同一标记才收尾代码段
                fence != null -> {
                    buf.append(line)
                    if (marker != null && marker.first() == fence!!.first()) {
                        flush(true)
                        fence = null
                    }
                }
                else -> buf.append(line)
            }
            if (index != lines.lastIndex) buf.append('\n')
        }
        // 未闭合的围栏按代码段收尾，避免把示例代码当普通文本改写
        flush(fence != null)
        // 普通段里再切出行内代码
        return segments.flatMap { (text, isCode) ->
            if (isCode) listOf(text to true) else splitInlineCode(text)
        }
    }

    /** 把普通段按行内代码（`x`）切开，行内代码同样原样保留 */
    private fun splitInlineCode(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val regex = Regex("`+[^`\\n]*`+")
        var last = 0
        for (m in regex.findAll(text)) {
            if (m.range.first > last) out += text.substring(last, m.range.first) to false
            out += m.value to true
            last = m.range.last + 1
        }
        if (last < text.length) out += text.substring(last) to false
        if (out.isEmpty()) out += text to false
        return out
    }

    /**
     * 把 README 相对链接补全为绝对 URL。
     * - 绝对 URL（http/https/mailto）原样返回；
     * - `#锚点` 原样返回（补成 blob URL 会跳到错误位置）；
     * - 相对路径用 URI.resolve 处理 `./`、`../`（上溯目录），避免 `../` 被当作普通路径段拼错。
     */
    private fun resolveRel(base: String, rel: String): String {
        if (rel.isEmpty()) return rel
        if (rel.startsWith("http://") || rel.startsWith("https://") || rel.startsWith("mailto:")) return rel
        if (rel.startsWith("#")) return rel
        // 含转义括号的链接（如 [a](b\(c\))）按原样保留，不做路径补全，避免被错误补全
        if (rel.contains('\\')) return rel
        return runCatching { java.net.URI(base).resolve(rel).toString() }.getOrDefault(base + rel)
    }
}
