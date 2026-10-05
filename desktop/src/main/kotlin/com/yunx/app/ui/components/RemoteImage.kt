package com.yunx.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 普通网络图片（公告封面 / 发布者头像 / 正文图集）：加载器复用 [RemoteImageLoader]。
 *
 * 三种状态都有明确外观，永远不会抛异常、也不会留一个空洞：
 * - 加载中：只显示 [MaterialTheme.colorScheme.surfaceVariant] 底色（列表里不闪图标，避免噪音）；
 * - 成功：铺满容器（[contentScale] 默认 Crop，配合 [shape] 做圆角 / 圆形裁切）；
 * - 失败或地址为空：显示 [fallback] 图标（未指定则保留底色占位）。
 *
 * 尺寸两种给法：
 * - **固定尺寸**（头像、列表缩略图、弹窗封面）：调用方传 `Modifier.size(...)` / `height(...)`，
 *   必须让宽高**都有界**（`fillMaxSize` 遇到无界高度会退化成图片固有尺寸）；
 * - **只给宽度、高度随图片比例**：传 `Modifier.fillMaxWidth()` 且 [autoHeight] = true。
 *
 * [autoHeight] 的高度是**自己算的**，不依赖 `Modifier.aspectRatio`：
 * 用 [BoxWithConstraints] 拿可用宽度，按位图比例算高度、再用调用方给的 `maxHeight` 夹一次，
 * 最后 `Modifier.size(w, h)` 落一个明确尺寸，图片永远画在框内；加载完成前用 [placeholderRatio] 占位，
 * 避免高度从 0 跳变。
 *
 * **底框紧贴图片**：高度被上限压缩后图片会等比缩小、比可用宽度窄，此时底框宽度会同步收窄到
 * 实际绘制宽度（`min(可用宽度, 高度 × 比例)`），不会在窄图两侧留下比图片更宽的底色；
 * 由于底框即图片本身大小、且父级按起始端对齐，缩小后的图片自然靠左（弹窗与详情页一致）。
 *
 * 与上游差异：Android `Bitmap` → 桌面 `ImageBitmap`（Skia 解码，见 [RemoteImageLoader]）。
 */
@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: ImageVector? = null,
    fallbackTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    autoHeight: Boolean = false,
    placeholderRatio: Float = 16f / 9f,
    /**
     * 固定高度模式：高度锁定为给定值、宽度按图片比例算（`高度 × 宽高比`），
     * 供「等高横向排列、放不下再换行」的图集使用（配合 `FlowRow`）。
     * 与 [autoHeight] 互斥（本参数非空时优先生效）；极宽的长图会把宽度夹到可用宽度、高度随之变矮。
     */
    fixedHeight: Dp? = null,
    /** 非空时图片可点击（如单击放大查看）：点击区 = 图片本身大小，桌面显示手型光标 */
    onClick: (() -> Unit)? = null
) {
    val link = url?.trim().orEmpty()
    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand)
    } else {
        Modifier
    }
    var failed by remember(link) { mutableStateOf(false) }
    // 已缓存过的图直接当初始值：重组 / 回退到本页时不会先闪一下占位色
    val bitmap by produceState<ImageBitmap?>(
        initialValue = if (link.isEmpty()) null else RemoteImageLoader.cached(link),
        link
    ) {
        if (link.isEmpty()) {
            value = null
            return@produceState
        }
        if (value == null) {
            val loaded = RemoteImageLoader.load(link)
            failed = loaded == null
            value = loaded
        }
    }
    val image = bitmap

    val fixedH = fixedHeight
    if (fixedH != null) {
        // 等高模式：高度固定为 fixedH，宽度按图片比例；超过可用宽度时夹住、高度随之变矮（超宽长图不溢出）
        BoxWithConstraints(modifier = modifier.then(clickModifier), contentAlignment = Alignment.Center) {
            val ratio = if (image != null && image.height > 0) {
                image.width.toFloat() / image.height.toFloat()
            } else {
                placeholderRatio
            }
            val natural = if (ratio > 0f) fixedH * ratio else fixedH
            val width = if (constraints.hasBoundedWidth) minOf(natural, maxWidth) else natural
            val height = if (ratio > 0f) width / ratio else fixedH
            ImageFrame(
                image = image,
                failed = failed,
                contentDescription = contentDescription,
                shape = shape,
                contentScale = contentScale,
                fallback = fallback,
                fallbackTint = fallbackTint,
                modifier = Modifier.size(width, height)
            )
        }
        return
    }

    if (autoHeight) {
        // 容器包裹图片本身（不强制撑满可用宽度）：底框宽度 == 实际绘制宽度，
        // 不会在窄图两侧留下比图片更宽的底色；父级按起始端对齐，缩小后的图自然靠左。
        BoxWithConstraints(modifier = modifier.then(clickModifier), contentAlignment = Alignment.Center) {
            val ratio = if (image != null && image.height > 0) {
                image.width.toFloat() / image.height.toFloat()
            } else {
                placeholderRatio
            }
            // 有界性判断用 constraints.hasBoundedWidth/Height，而不是拿 Dp 去比 `Dp.Infinity`：
            // 无界时 BoxWithConstraints 给的 maxWidth 是一个巨大的有限 Dp（≈Int.MAX_VALUE/density），
            // 比不出 Infinity 来。宽度无界就兜一个默认宽度，别把高度算成天文数字。
            val width = if (constraints.hasBoundedWidth) maxWidth else DefaultAutoWidth
            val natural = if (ratio > 0f) width / ratio else width
            // 调用方可用 heightIn(max = …) 给上限；高度无界时不夹
            val height = if (constraints.hasBoundedHeight) minOf(natural, maxHeight) else natural
            // 高度被上限压缩后，图片等比缩小、比可用宽度窄：底框宽度收窄到实际绘制宽度；
            // 未被压缩时 `height * ratio == width`，与原来等价。
            val frameWidth = if (ratio > 0f) minOf(width, height * ratio) else width
            ImageFrame(
                image = image,
                failed = failed,
                contentDescription = contentDescription,
                shape = shape,
                contentScale = contentScale,
                fallback = fallback,
                fallbackTint = fallbackTint,
                modifier = Modifier.size(frameWidth, height)
            )
        }
        return
    }

    ImageFrame(
        image = image,
        failed = failed,
        contentDescription = contentDescription,
        shape = shape,
        contentScale = contentScale,
        fallback = fallback,
        fallbackTint = fallbackTint,
        modifier = modifier.then(clickModifier)
    )
}

/** 图片本体（底色占位 + 载入后的图 / 失败图标）：两种尺寸路径共用，保证外观完全一致 */
@Composable
private fun ImageFrame(
    image: ImageBitmap?,
    failed: Boolean,
    contentDescription: String?,
    shape: Shape,
    contentScale: ContentScale,
    fallback: ImageVector?,
    fallbackTint: Color,
    modifier: Modifier
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(color = MaterialTheme.colorScheme.surfaceVariant, shape = shape),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else if (failed && fallback != null) {
            Icon(
                imageVector = fallback,
                contentDescription = contentDescription,
                tint = fallbackTint,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** [RemoteImage] autoHeight 模式下宽度无界时的兜底宽度（正常调用方都会给 fillMaxWidth） */
private val DefaultAutoWidth = 240.dp
