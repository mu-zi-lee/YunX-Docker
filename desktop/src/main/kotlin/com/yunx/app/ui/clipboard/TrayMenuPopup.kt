package com.yunx.app.ui.clipboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExitToApp
import androidx.compose.material.icons.outlined.OpenInNew

/**
 * 托盘右键菜单（Compose 风格）：在鼠标右键位置弹出 Material3 风格菜单。
 *
 * 替代 AWT PopupMenu，统一应用视觉风格并避免中文乱码。
 * 使用独立透明 Window（POPUP 类型）渲染，点击菜单项或点击外部时关闭。
 */
@Composable
fun TrayMenuPopup(
    onShowMainWindow: () -> Unit,
    onExit: () -> Unit
) {
    var visible by remember { mutableStateOf(false) }
    var menuX by remember { mutableStateOf(0) }
    var menuY by remember { mutableStateOf(0) }

    // 监听 TrayMenuController 的右键请求
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(100)
            TrayMenuController.consume()?.let { (x, y) ->
                menuX = x
                menuY = y
                visible = true
            }
        }
    }

    if (visible) {
        // 菜单位置微调：向上偏移避免超出屏幕底部
        val adjustedY = (menuY - 120).coerceAtLeast(0)
        Window(
            onCloseRequest = { visible = false },
            undecorated = true,
            transparent = true,
            alwaysOnTop = true,
            focusable = false,
            resizable = false,
            state = rememberWindowState(
                position = WindowPosition.Absolute(menuX.dp, adjustedY.dp)
            )
        ) {
            // 全屏透明背景用于捕获点击外部关闭
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { visible = false; TrayMenuController.dismiss() }
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart),
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
                                TrayMenuController.dismiss()
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
                                TrayMenuController.dismiss()
                                onExit()
                            }
                        )
                    }
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
