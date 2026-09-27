package com.yunx.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 关闭主窗口时的选择结果。
 */
enum class CloseChoice {
    /** 最小化到系统托盘 */
    TRAY,
    /** 直接退出应用 */
    EXIT,
    /** 取消关闭 */
    CANCEL
}

/**
 * 关闭确认弹窗（窗口内覆盖层，FadeAlertDialog）。
 *
 * 两种场景：
 * 1. 普通询问（默认行为=每次询问）：提供「退出」和「最小化到托盘」两个选项 + 取消
 * 2. 有下载任务时的询问（默认行为=直接退出）：提供「最小化到托盘」和「仍然退出」+ 取消
 *
 * 底部有「记住选择，下次不再询问」复选框；勾选后回调返回 [remember] = true，
 * 调用方据此把 closeBehavior 持久化。
 */
@Composable
fun CloseConfirmDialog(
    visible: Boolean,
    /** 是否为「有下载任务」场景（true=突出最小化到托盘，退出标为「仍然退出」） */
    hasActiveDownloads: Boolean,
    onDismiss: (choice: CloseChoice, remember: Boolean) -> Unit,
) {
    var rememberChoice by remember { mutableStateOf(false) }
    // 默认选中：有下载任务时默认选「最小化到托盘」，否则默认「退出」
    var selected by remember(hasActiveDownloads) {
        mutableStateOf(if (hasActiveDownloads) CloseChoice.TRAY else CloseChoice.EXIT)
    }

    FadeAlertDialog(
        visible = visible,
        onDismissRequest = { onDismiss(CloseChoice.CANCEL, false) },
        title = { Text("关闭云析") },
        text = {
            Column {
                Text(
                    text = if (hasActiveDownloads) {
                        "当前有下载任务正在进行，关闭窗口将停止下载。"
                    } else {
                        "选择关闭窗口时的操作："
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(16.dp))

                val options = if (hasActiveDownloads) {
                    listOf(CloseChoice.TRAY to "最小化到系统托盘（继续下载）", CloseChoice.EXIT to "仍然退出（停止下载）")
                } else {
                    listOf(CloseChoice.EXIT to "退出应用", CloseChoice.TRAY to "最小化到系统托盘")
                }

                options.forEach { (choice, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = selected == choice,
                                onClick = { selected = choice },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == choice,
                            onClick = { selected = choice }
                        )
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = rememberChoice,
                            onClick = { rememberChoice = !rememberChoice },
                            role = Role.Checkbox
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = rememberChoice,
                        onCheckedChange = { rememberChoice = it }
                    )
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text("记住选择，下次不再询问", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss(selected, rememberChoice) }) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(CloseChoice.CANCEL, false) }) {
                Text("取消")
            }
        }
    )
}
