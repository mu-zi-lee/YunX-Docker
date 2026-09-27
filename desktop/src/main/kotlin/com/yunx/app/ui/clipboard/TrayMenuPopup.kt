package com.yunx.app.ui.clipboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.OpenInNew
import java.awt.Color as AwtColor
import java.awt.geom.RoundRectangle2D

/**
 * 托盘右键菜单（Compose 风格）：在鼠标右键位置弹出 Material3 风格菜单。
 *
 * 替代 AWT PopupMenu，统一应用视觉风格并避免中文乱码。
 *
 * 实现要点：
 * - 不使用 transparent=true（OPENGL 后端下透明窗口不渲染），改用 AWT setShape 裁剪圆角
 * - 窗口尺寸贴合菜单内容（非全屏），避免遮挡
 * - focusable=true，窗口失焦时自动关闭（点击外部关闭）
 * - 直接观察 TrayMenuController.showRequest 状态，而非轮询
 */
@Composable
fun TrayMenuPopup(
    onShowMainWindow: () -> Unit,
    onExit: () -> Unit
) {
    val showRequest by TrayMenuController.showRequestState
    var visible by remember { mutableStateOf(false) }
    var menuX by remember { mutableStateOf(0) }
    var menuY by remember { mutableStateOf(0) }

    // 观察右键请求：状态变化时立即显示菜单
    LaunchedEffect(showRequest) {
        showRequest?.let { (x, y) ->
            menuX = x
            menuY = y
            visible = true
            TrayMenuController.dismiss()
        }
    }

    if (visible) {
        // 菜单高度约 110dp，向上偏移避免超出屏幕底部
        val adjustedY = (menuY - 120).coerceAtLeast(0)
        Window(
            onCloseRequest = { visible = false },
            undecorated = true,
            alwaysOnTop = true,
            focusable = true,
            resizable = false,
            state = rememberWindowState(
                width = 180.dp,
                height = 120.dp,
                position = WindowPosition.Absolute(menuX.dp, adjustedY.dp)
            )
        ) {
            // AWT 背景透明 + setShape 圆角裁剪，消除直角白边
            androidx.compose.runtime.SideEffect {
                val w = window
                w.background = AwtColor(0, 0, 0, 0)
                w.shape = RoundRectangle2D.Double(0.0, 0.0, w.width.toDouble(), w.height.toDouble(), 24.0, 24.0)
            }
            // 窗口失焦（点击外部）时关闭
            LaunchedEffect(Unit) {
                window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                    override fun windowGainedFocus(e: java.awt.event.WindowEvent?) {}
                    override fun windowLostFocus(e: java.awt.event.WindowEvent?) {
                        visible = false
                    }
                })
            }

            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(4.dp)
                ) {
                    TrayMenuItem(
                        icon = Icons.Outlined.OpenInNew,
                        label = "显示主窗口",
                        onClick = {
                            visible = false
                            onShowMainWindow()
                        }
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    androidx.compose.material3.Divider(
                        modifier = Modifier.padding(horizontal = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    TrayMenuItem(
                        icon = Icons.Outlined.ExitToApp,
                        label = "退出",
                        onClick = {
                            visible = false
                            onExit()
                        }
                    )
                }
            }
        }
    }
}

/** 托盘菜单项：图标 + 文字，Material3 风格 */
@Composable
private fun TrayMenuItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .width(160.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
