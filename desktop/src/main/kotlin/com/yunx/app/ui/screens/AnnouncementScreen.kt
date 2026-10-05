package com.yunx.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.announcement.AnnouncementApi
import com.yunx.app.data.announcement.relativeTime
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.components.RemoteImage
import com.yunx.app.ui.viewmodel.AnnouncementViewModel

/**
 * 公告页宿主（全屏叠加页）：列表 ↔ 详情在这里切换。
 *
 * 桌面差异：上游用 SharedTransitionLayout / sharedBounds 做「列表项长成整页」的共享元素过渡；
 * 桌面 MainScreen 没有共享元素基础设施（各叠加页统一用淡入淡出），本页随全站约定用
 * [AnimatedContent] 做淡入淡出切换，不额外引入共享元素作用域。
 *
 * 详情打开时列表会被移出组合，返回时重新进入 —— 列表数据在 ViewModel 里，状态不丢。
 */
@Composable
fun AnnouncementScreen(
    viewModel: AnnouncementViewModel,
    onBack: () -> Unit,
    /** 从启动弹窗「查看详情」直接进详情页时带进来的公告 id（null = 先看列表） */
    initialDetailId: String? = null,
    modifier: Modifier = Modifier
) {
    // 当前是否在详情页（null = 列表页）
    var detailId by rememberSaveable { mutableStateOf(initialDetailId) }

    // 打开页面就有数据：启动检查成功过就直接用那份，不会再请求一次
    LaunchedEffect(Unit) { viewModel.ensureLoaded() }
    // 进入详情页才请求详情（详情接口会让浏览量 +1，ViewModel 里按 id 缓存，同一会话只请求一次）
    LaunchedEffect(detailId) { detailId?.let { viewModel.openDetail(it) } }

    // 返回键：详情页 → 列表页；列表页 → 关闭公告页（交回主界面）
    BackHandler {
        if (detailId != null) detailId = null else onBack()
    }

    val listState by viewModel.list.collectAsState()
    val detailState by viewModel.detail.collectAsState()
    val readIds by viewModel.readIds.collectAsState()

    AnimatedContent(
        targetState = detailId,
        transitionSpec = {
            fadeIn(tween(200)).togetherWith(fadeOut(tween(150)))
        },
        label = "announcementPage",
        modifier = modifier.fillMaxSize()
    ) { id ->
        if (id == null) {
            AnnouncementListPage(
                state = listState,
                readIds = readIds,
                onBack = onBack,
                onRefresh = { viewModel.refresh() },
                onLoadMore = { viewModel.loadMore() },
                onMarkAllRead = { viewModel.markAllRead() },
                onOpen = { detailId = it.id },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            AnnouncementDetailPage(
                state = detailState,
                onBack = { detailId = null },
                onRetry = { viewModel.retryDetail() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 顶栏公告入口的未读红点角标（>99 显示 `99+`）。
 *
 * 不用 material3 的 `BadgedBox`：那颗角标要叠在 24dp 图标上、自己控制偏移与最小尺寸，
 * 这里用 Surface + Text 手搓，样式完全可控。
 */
@Composable
internal fun AnnouncementUnreadBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.error,
        contentColor = MaterialTheme.colorScheme.onError
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                lineHeight = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/**
 * 启动弹窗：展示「未读的置顶公告」，没有置顶则展示「最新的一条未读」（候选逻辑见
 * `AnnouncementViewModel.pickPopupCandidate`）。
 *
 * 桌面差异：按项目约定不使用 material3 AlertDialog（桌面端会创建独立原生窗口、阻塞 UI 线程），
 * 改用窗口内覆盖层 [FadeAlertDialog]。
 * 两个出口都算已读：点「查看详情」进详情页、点「知道了」/ 点空白关掉。
 */
@Composable
fun AnnouncementPopupDialog(
    announcement: AnnouncementApi.Announcement,
    onDetail: () -> Unit,
    onDismiss: () -> Unit
) {
    val publisher = announcement.publisher.name.ifBlank { announcement.author }
    val meta = listOfNotNull(
        publisher.takeIf { it.isNotBlank() },
        relativeTime(announcement.effectiveMillis).takeIf { it.isNotBlank() }
    ).joinToString(" · ")

    FadeAlertDialog(
        visible = true,
        onDismissRequest = onDismiss,
        icon = {
            AnnouncementChip(
                text = if (announcement.isPinned) "置顶公告" else "最新公告",
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        title = {
            Text(
                text = announcement.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column {
                val cover = announcement.coverImage
                if (!cover.isNullOrBlank()) {
                    // 封面按图片自身比例自适应（ContentScale.Fit，不裁切），高度上限 [PopupCoverMaxHeight]：
                    // 不写 fillMaxWidth —— 底框宽度由 RemoteImage 收窄到实际绘制宽度（不超出图片本身），
                    // 缩小后的图因此靠左显示，弹窗高度也可预期。
                    RemoteImage(
                        url = cover,
                        contentDescription = null,
                        shape = MaterialTheme.shapes.medium,
                        contentScale = ContentScale.Fit,
                        autoHeight = true,
                        modifier = Modifier.heightIn(max = PopupCoverMaxHeight)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                if (announcement.summary.isNotBlank()) {
                    Text(
                        text = announcement.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDetail) { Text("查看详情") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        }
    )
}

/** 启动弹窗封面高度上限：图片按比例自适应展示，超过此高度才截断（配合 ContentScale.Fit，不裁切内容） */
private val PopupCoverMaxHeight = 180.dp
