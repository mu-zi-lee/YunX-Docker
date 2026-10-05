package com.yunx.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.BackHandler
import kotlin.math.abs
import kotlin.math.pow

/** 最小 / 最大绝对缩放（1 = 图片原始像素大小），与图片本身尺寸无关 */
private const val MIN_ABS_SCALE = 0.05f
private const val MAX_ABS_SCALE = 8f

/** 滚轮每格（scrollDelta = 1）的缩放倍率 */
private const val WHEEL_STEP = 1.15f

/**
 * 图片查看器（全窗口覆盖层）：单击公告图片后放大查看。
 *
 * 交互（对齐专业看图工具的习惯）：
 * - **滚轮缩放**：鼠标在图片上 → 以鼠标位置为锚点缩放；鼠标在图片外（画布/背景）→ 以视图中心缩放；
 * - **拖拽平移**：任意缩放级别均可拖，且整个预览区域（不只图片本身）都能拖；
 * - **双击**：复位到「适应窗口」并回到居中；**单击图片外**、右上角关闭按钮、Esc 均可关闭。
 *
 * 变换以**图片中心**为原点：图片中心 = 容器中心 + [offset]，绘制尺寸 = 原始像素 × 绝对缩放，
 * 因此缩放锚点数学（保持锚点下的图片内容不动）是线性的：
 * `offset' = d - (d - offset) × (scale'/scale)`（`d` = 锚点相对容器中心的位移）。
 *
 * 渲染用「容器大小元素 + `ContentScale.Fit` + `graphicsLayer`」而非把元素设成图片原始尺寸：
 * 后者在 4000px 大图上会申请同尺寸的图层纹理（显存爆炸），前者的图层始终只有容器大小。
 * 因此传给 `graphicsLayer` 的相对缩放 = 绝对缩放 / fitRatio（fitRatio = 适应窗口的相对倍率）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImageViewerOverlay(
    url: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val link = url?.trim().orEmpty()
    if (link.isEmpty()) return

    val bitmap by produceState<ImageBitmap?>(initialValue = RemoteImageLoader.cached(link), link) {
        if (value == null) value = RemoteImageLoader.load(link)
    }

    // Esc 关闭（注册在本页 BackHandler 之后，故优先于「返回上一页」）
    BackHandler { onDismiss() }

    var container by remember { mutableStateOf(IntSize.Zero) }
    var absScale by remember(link) { mutableFloatStateOf(1f) }
    var offset by remember(link) { mutableStateOf(Offset.Zero) }
    var initialized by remember(link) { mutableStateOf(false) }

    val imageW = (bitmap?.width ?: 0).toFloat()
    val imageH = (bitmap?.height ?: 0).toFloat()
    val containerW = container.width.toFloat()
    val containerH = container.height.toFloat()
    // 适应窗口的相对倍率：图片原始像素 → 铺满容器所需的缩放
    val fitRatio = if (imageW > 0f && imageH > 0f && containerW > 0f && containerH > 0f) {
        minOf(containerW / imageW, containerH / imageH)
    } else {
        1f
    }
    // 打开时的绝对缩放：大图适应窗口，小图保持 100%（不放大糊掉）
    val initialScale = minOf(1f, fitRatio)
    val viewCenter = Offset(containerW / 2f, containerH / 2f)

    // 尺寸就绪后按「适应窗口」初始化一次
    LaunchedEffect(link, imageW, imageH, containerW, containerH) {
        if (!initialized && imageW > 0f && imageH > 0f && containerW > 0f && containerH > 0f) {
            absScale = initialScale
            offset = Offset.Zero
            initialized = true
        }
    }

    /** 以 [anchor]（容器坐标）为锚点把绝对缩放乘以 [factor]，保持锚点下的图片内容不动 */
    fun zoomAt(anchor: Offset, factor: Float) {
        val next = (absScale * factor).coerceIn(MIN_ABS_SCALE, MAX_ABS_SCALE)
        if (next == absScale) return
        val d = anchor - viewCenter
        val p = (d - offset) / absScale
        offset = d - p * next
        absScale = next
    }

    /** 某点（容器坐标）是否落在当前绘制的图片矩形内 */
    fun insideImage(point: Offset): Boolean {
        val halfW = imageW * absScale / 2f
        val halfH = imageH * absScale / 2f
        if (halfW <= 0f || halfH <= 0f) return false
        val rel = point - viewCenter - offset
        return abs(rel.x) <= halfW && abs(rel.y) <= halfH
    }

    Box(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .onSizeChanged { container = it }
                // 拖拽平移：整个预览区域生效（含图片外的画布），任意缩放级别都可拖
                .pointerInput(link) {
                    detectDragGestures { change, dragAmount ->
                        offset += dragAmount
                        change.consume()
                    }
                }
                // 双击复位到适应窗口；单击图片外的背景关闭
                .pointerInput(link) {
                    detectTapGestures(
                        onDoubleTap = {
                            absScale = initialScale
                            offset = Offset.Zero
                        },
                        onTap = { position ->
                            if (!insideImage(position)) onDismiss()
                        }
                    )
                }
                // 滚轮缩放：鼠标在图上以鼠标为锚点，在图片外以视图中心为锚点
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val change = event.changes.firstOrNull() ?: return@onPointerEvent
                    val delta = change.scrollDelta.y
                    if (delta != 0f) {
                        val anchor = if (insideImage(change.position)) change.position else viewCenter
                        zoomAt(anchor, WHEEL_STEP.pow(-delta.coerceIn(-5f, 5f)))
                        change.consume()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp == null) {
                // 缓存里一般已有（详情页刚展示过）；未命中时等加载完成
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            } else {
                Image(
                    bitmap = bmp,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // 相对倍率：容器大小元素内部已按 Fit 画好，再相对它缩放即为绝对倍率
                            val rel = if (fitRatio > 0f) absScale / fitRatio else 1f
                            scaleX = rel
                            scaleY = rel
                            translationX = offset.x
                            translationY = offset.y
                            transformOrigin = TransformOrigin.Center
                        }
                )
            }
        }

        // 覆盖在图片之上的控件：关闭按钮 + 操作提示
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = "关闭",
                tint = Color.White
            )
        }
        Text(
            text = "滚轮缩放 · 拖拽移动 · 双击复位 · Esc 关闭",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}
