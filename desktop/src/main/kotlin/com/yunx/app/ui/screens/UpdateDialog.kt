package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunx.app.data.update.UpdateChecker

/**
 * 发现新版本弹窗（Material3）：
 * 标题 + 当前/最新版本 + 更新说明（可滚动）+ 下载更新 / 稍后 / 忽略本次。
 *
 * 桌面版同时提供「安装版(.exe)」和「便携版(.zip)」两个下载入口，
 * 由用户按需选择；若某个类型在 Release 中不存在，则不显示对应按钮。
 */
@Composable
fun UpdateDialog(
    currentVersion: String,
    release: UpdateChecker.Release,
    onDownloadAsset: (url: String, name: String) -> Unit,
    onLater: () -> Unit,
    onIgnore: () -> Unit,
    downloading: Boolean = false,
    /** 使用镜像站下载（可选）；为 null 时不显示镜像站按钮 */
    onDownloadMirrorAsset: ((url: String, name: String) -> Unit)? = null
) {
    val exeAsset = release.assets.firstOrNull { it.name.endsWith(".exe", true) }
    val zipAsset = release.assets.firstOrNull { it.name.endsWith(".zip", true) }
    AlertDialog(
        onDismissRequest = onLater,
        icon = {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                androidx.compose.foundation.layout.Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SystemUpdate,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        },
        title = {
            Column {
                Text(
                    text = "发现新版本",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = release.tagName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "当前 $currentVersion",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column {
                Text(
                    text = "更新内容",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                // 更新说明（可滚动，防止长文本撑爆弹窗）
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow
                ) {
                    Text(
                        text = release.body.ifBlank { "暂无更新说明" },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp
                    )
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (exeAsset != null) {
                    Button(
                        onClick = { onDownloadAsset(exeAsset.downloadUrl, exeAsset.name) },
                        enabled = !downloading
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载中…")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载安装版 (.exe)")
                        }
                    }
                    if (onDownloadMirrorAsset != null) {
                        TextButton(onClick = { onDownloadMirrorAsset(exeAsset.downloadUrl, exeAsset.name) }) {
                            Text("镜像站下载安装版", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (zipAsset != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = { onDownloadAsset(zipAsset.downloadUrl, zipAsset.name) },
                        enabled = !downloading
                    ) {
                        if (downloading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载中…")
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("下载便携版 (.zip)")
                        }
                    }
                    if (onDownloadMirrorAsset != null) {
                        TextButton(onClick = { onDownloadMirrorAsset(zipAsset.downloadUrl, zipAsset.name) }) {
                            Text("镜像站下载便携版", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (exeAsset == null && zipAsset == null) {
                    Text(
                        text = "该版本未提供安装包",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onIgnore) {
                    Text("忽略本次", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onLater) {
                    Text("稍后")
                }
            }
        }
    )
}