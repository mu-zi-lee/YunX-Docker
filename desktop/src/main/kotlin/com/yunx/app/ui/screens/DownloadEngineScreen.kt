package com.yunx.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.data.gopeed.GopeedEngine
import com.yunx.app.data.gopeed.GopeedKernelProvisioner
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.SnackbarController
import com.yunx.app.ui.components.FadeAlertDialog
import com.yunx.app.ui.rememberGlobalSnackbarHostState
import com.yunx.app.util.DesktopActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 下载引擎页：在内置分片下载器与外部 Gopeed 引擎之间选择，并管理 Gopeed 内核。
 *
 * 与上游 Android 版的能力对齐（去掉 Android 专属的存储权限、AAR 导入、前台服务）：
 * - 引擎状态 / 内核版本 / 内核体积 / 下载目录展示；
 * - 内核获取：**从 Gopeed 官方 Release 下载**（镜像站回退 + sha256 校验）与**导入本地 exe/zip**；
 * - 重启引擎、删除内核；
 * - 切换引擎（切换前要求内核已导入）。
 *
 * 只影响**新任务**由谁执行；已有任务保持原归属（已在下载中的引擎任务继续由引擎跑完）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadEngineScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val snackbarHostState = rememberGlobalSnackbarHostState()
    val scope = rememberCoroutineScope()
    val settingsRepo = remember { SettingsRepository() }

    val engineState by GopeedEngine.state.collectAsState()
    var currentEngine by remember { mutableStateOf(settingsRepo.downloadEngine) }
    // 内核信息（版本需要联网询问引擎，仅在运行中才有值）
    var kernelVersion by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 内核获取进度（非空时显示进度弹窗，弹窗内可「取消」）
    var progress by remember { mutableStateOf<GopeedKernelProvisioner.Progress?>(null) }
    // 正在跑的内核获取协程：取消按钮据此中断（下载循环里检查取消，随即清掉半截包）
    var provisionJob by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    // 「导入本地内核」与「删除内核」的确认
    var showImportMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    // 进入页面同步一次磁盘事实 + 询问内核版本（运行中才有）
    LaunchedEffect(Unit) {
        GopeedEngine.syncInstalledState()
        if (GopeedEngine.state.value == GopeedEngine.State.RUNNING) {
            kernelVersion = withContext(Dispatchers.IO) { GopeedEngine.version() }
        }
    }

    /** 读取内核版本（引擎没在跑就不读——绝不为了显示版本号把引擎进程拉起来） */
    fun refreshVersion() {
        scope.launch {
            kernelVersion = withContext(Dispatchers.IO) {
                runCatching {
                    if (GopeedEngine.state.value != GopeedEngine.State.RUNNING) return@runCatching null
                    GopeedEngine.version()
                }.getOrNull()
            }
        }
    }

    /** 启动引擎并读取核心版本（只在用户把下载引擎切到 Gopeed 时调用） */
    fun startEngineAndReadVersion() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    GopeedEngine.start(DesktopActions.defaultDownloadDir)
                    GopeedEngine.version()
                }
            }
            result.onSuccess { kernelVersion = it }
                .onFailure { failure = it.message ?: it.javaClass.simpleName }
        }
    }

    /** 从官方 Release 下载内核（可取消；取消后清掉半截包） */
    fun downloadKernel() {
        if (busy) return
        busy = true
        failure = null
        provisionJob = scope.launch {
            try {
                GopeedKernelProvisioner.provision(
                    mirrorPrefix = settingsRepo.githubMirrorPrefix
                ) { p -> progress = p }
                progress = null
                SnackbarController.show("Gopeed 内核已就绪")
                refreshVersion()
            } catch (e: CancellationException) {
                progress = null
                SnackbarController.show("已取消内核下载")
                throw e
            } catch (e: Exception) {
                progress = null
                failure = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
                provisionJob = null
            }
        }
    }

    /** 取消正在进行的内核下载（下载循环每轮都检查取消，能较快停下并删除半截包） */
    fun cancelKernelDownload() {
        provisionJob?.cancel()
    }

    /** 导入本地内核（官方 zip 或裸 gopeed.exe） */
    fun importKernel() {
        scope.launch {
            val path = withContext(Dispatchers.IO) {
                DesktopActions.pickFile(
                    filters = listOf(
                        "Gopeed 内核 (*.zip;*.exe)" to "*.zip;*.exe",
                        "所有文件 (*.*)" to "*.*"
                    )
                )
            } ?: return@launch
            busy = true
            failure = null
            val result = withContext(Dispatchers.IO) {
                runCatching { GopeedEngine.installFromArchive(java.io.File(path)) }
            }
            busy = false
            result.onSuccess {
                SnackbarController.show("内核已导入")
                refreshVersion()
            }.onFailure { failure = it.message ?: it.javaClass.simpleName }
        }
    }

    /** 停用引擎进程；若引擎里还有任务在跑则先不停（避免把正在下载的引擎任务打断） */
    fun stopEngineIfIdle() {
        scope.launch {
            val active = withContext(Dispatchers.IO) {
                runCatching { GopeedEngine.hasActiveTasks() }.getOrDefault(false)
            }
            if (active) {
                SnackbarController.show("引擎仍有任务在下载，任务结束后再切换即可停用")
                return@launch
            }
            withContext(Dispatchers.IO) { runCatching { GopeedEngine.stop() } }
            kernelVersion = null
            SnackbarController.show("已停用 Gopeed 引擎进程")
        }
    }

    /** 切换引擎（切到 Gopeed 前要求内核已导入） */
    fun switchEngine(target: String) {
        if (target == SettingsRepository.ENGINE_GOPEED && !GopeedEngine.isInstalled()) {
            SnackbarController.show("请先导入 Gopeed 内核")
            return
        }
        settingsRepo.downloadEngine = target
        currentEngine = target
        SnackbarController.show(
            if (target == SettingsRepository.ENGINE_GOPEED) "已切换到 Gopeed 引擎（仅影响新任务）"
            else "已切换到内置下载器（仅影响新任务）"
        )
        // ★ 只在「切到 Gopeed 引擎」这一时刻把引擎进程拉起来：下载/导入内核、进入本页都不提前启动。
        // 引擎没起来时读不到核心版本号，属正常（不是失败）。
        if (target == SettingsRepository.ENGINE_GOPEED) {
            startEngineAndReadVersion()
        } else {
            // 切回内置下载器：引擎进程没必要继续常驻，直接停掉（内核文件保留，随时可再切回来）
            stopEngineIfIdle()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("下载引擎", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                text = "下载引擎决定**新任务**由谁来执行，已存在的任务不受影响。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))

            // ===== 内置分片下载器 =====
            EngineCard(
                icon = { Icon(Icons.Outlined.Memory, contentDescription = null) },
                title = "内置分片下载器",
                description = "Range 并发分片、断点续传、自适应分片、慢连接抢占。支持镜像回退与全局限速。",
                active = currentEngine == SettingsRepository.ENGINE_BUILTIN,
                onSwitch = { switchEngine(SettingsRepository.ENGINE_BUILTIN) }
            )

            Spacer(Modifier.height(12.dp))

            // ===== Gopeed 引擎 =====
            EngineCard(
                icon = { Icon(Icons.Outlined.Speed, contentDescription = null) },
                title = "Gopeed 引擎",
                badge = "实验性",
                description = "以独立进程运行官方 Gopeed 内核，由它接管 HTTP 多连接下载。" +
                    "引擎不支持下载限速、失败重试与镜像回退，GitHub 下载仍走内置引擎。",
                active = currentEngine == SettingsRepository.ENGINE_GOPEED,
                onSwitch = { switchEngine(SettingsRepository.ENGINE_GOPEED) }
            ) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(12.dp))

                // 引擎状态
                AnimatedContent(
                    targetState = engineState,
                    transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
                    label = "engineState"
                ) { state ->
                    KeyValueRow("引擎状态", engineStateLabel(state))
                }
                Spacer(Modifier.height(6.dp))
                KeyValueRow(
                    "内核",
                    if (GopeedEngine.isInstalled()) {
                        buildString {
                            append(formatSize(GopeedEngine.kernelSize()))
                            kernelVersion?.let { append(" · 核心版本 $it") }
                        }
                    } else {
                        "未导入（需要官方 windows 版内核）"
                    }
                )
                Spacer(Modifier.height(6.dp))
                KeyValueRow("落盘目录", DesktopActions.defaultDownloadDir.absolutePath)

                Spacer(Modifier.height(14.dp))

                if (!GopeedEngine.isInstalled()) {
                    Button(
                        onClick = { downloadKernel() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("从官方下载内核")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { importKernel() },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("导入本地内核（.zip / .exe）")
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val r = withContext(Dispatchers.IO) {
                                        runCatching {
                                            GopeedEngine.restart(DesktopActions.defaultDownloadDir)
                                        }
                                    }
                                    busy = false
                                    r.onSuccess {
                                        SnackbarController.show("引擎已重启")
                                        refreshVersion()
                                    }.onFailure { failure = it.message }
                                }
                            },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重启引擎")
                        }
                        OutlinedButton(
                            onClick = { showImportMenu = true },
                            enabled = !busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("更换内核")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("删除内核", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            // 失败原因：原文可复制，便于直接排查
            failure?.let { msg ->
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "操作失败",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(6.dp))
                        SelectionContainer {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }

    // 内核获取进度：下载阶段可「取消」；下载期间可复制「加速链接 / 直链」。
    // 校验/解包阶段不给取消（那两步很快，且中途放弃会留下解包了一半的内核）。
    progress?.let { p ->
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { },
            title = {
                Text(
                    when (p.stage) {
                        GopeedKernelProvisioner.Stage.RESOLVING -> "正在获取内核信息"
                        GopeedKernelProvisioner.Stage.DOWNLOADING -> "正在下载内核"
                        GopeedKernelProvisioner.Stage.VERIFYING -> "正在校验内核"
                        GopeedKernelProvisioner.Stage.INSTALLING -> "正在导入内核"
                        GopeedKernelProvisioner.Stage.DONE -> "内核已就绪"
                    }
                )
            },
            text = {
                Column {
                    if (p.stage == GopeedKernelProvisioner.Stage.DOWNLOADING && p.total > 0) {
                        LinearProgressIndicator(
                            progress = { (p.downloaded.toFloat() / p.total.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${formatSize(p.downloaded)} / ${formatSize(p.total)}" +
                                if (p.speed > 0) " · ${formatSpeed(p.speed)}" else "",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(text = p.message.ifBlank { "请稍候…" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    // 下载地址（解析完成后才有）：镜像「加速链接」是实际使用的地址，直链可复制备用
                    if (p.stage == GopeedKernelProvisioner.Stage.DOWNLOADING &&
                        (p.url.isNotBlank() || p.directUrl.isNotBlank())
                    ) {
                        Spacer(Modifier.height(14.dp))
                        if (p.url.isNotBlank()) {
                            KernelLinkRow("加速链接", p.url) {
                                DesktopActions.copyToClipboard(it)
                                SnackbarController.show("已复制加速链接")
                            }
                        }
                        if (p.directUrl.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            KernelLinkRow("直链", p.directUrl) {
                                DesktopActions.copyToClipboard(it)
                                SnackbarController.show("已复制直链")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                // 只在下载阶段给「取消」
                if (p.stage == GopeedKernelProvisioner.Stage.DOWNLOADING) {
                    TextButton(onClick = { cancelKernelDownload() }) { Text("取消") }
                }
            }
        )
    }

    // 更换内核：下载 / 本地导入二选一
    if (showImportMenu) {
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { showImportMenu = false },
            title = { Text("更换内核") },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            showImportMenu = false
                            downloadKernel()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("从官方下载最新内核")
                    }
                    TextButton(
                        onClick = {
                            showImportMenu = false
                            importKernel()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("导入本地内核（.zip / .exe）")
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportMenu = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteConfirm) {
        FadeAlertDialog(
            visible = true,
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除 Gopeed 内核？") },
            text = {
                Text(
                    "将停止引擎并删除内核文件。引擎任务将无法继续；" +
                        "若当前正使用 Gopeed 引擎，会自动切回内置下载器。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    scope.launch {
                        withContext(Dispatchers.IO) { GopeedEngine.uninstall() }
                        kernelVersion = null
                        if (settingsRepo.downloadEngine == SettingsRepository.ENGINE_GOPEED) {
                            settingsRepo.downloadEngine = SettingsRepository.ENGINE_BUILTIN
                            currentEngine = SettingsRepository.ENGINE_BUILTIN
                        }
                        SnackbarController.show("内核已删除")
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

/** 弹窗里的一行下载地址：小字单行省略显示 + 一键复制 */
@Composable
private fun KernelLinkRow(label: String, url: String, onCopy: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onCopy(url) }, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "复制$label",
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** 引擎卡片：标题 + 说明 + 当前状态按钮 + 可选扩展内容 */
@Composable
private fun EngineCard(
    icon: @Composable () -> Unit,
    title: String,
    description: String,
    active: Boolean,
    onSwitch: () -> Unit,
    badge: String? = null,
    content: (@Composable () -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon()
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (active) {
                Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                    Text("使用中")
                }
            } else {
                OutlinedButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
                    Text("切换到此引擎")
                }
            }
            content?.invoke()
        }
    }
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun engineStateLabel(state: GopeedEngine.State): String = when (state) {
    GopeedEngine.State.NOT_INSTALLED -> "未导入内核"
    GopeedEngine.State.INSTALLED -> "已导入，未启动"
    GopeedEngine.State.RUNNING -> "运行中"
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024
        idx++
    }
    return if (idx == 0) "${bytes} B" else String.format("%.1f %s", value, units[idx])
}

private fun formatSpeed(bytesPerSec: Long): String =
    if (bytesPerSec <= 0) "" else "${formatSize(bytesPerSec)}/s"
