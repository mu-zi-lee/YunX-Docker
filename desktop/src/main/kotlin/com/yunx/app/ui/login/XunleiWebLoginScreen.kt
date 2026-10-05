package com.yunx.app.ui.login

import com.yunx.app.ui.BackHandler
import com.yunx.app.ui.SnackbarController
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.XunleiWebCredential
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.ui.viewmodel.XunleiAccountViewModel
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cef.CefClient
import org.cef.browser.CefBrowser
import org.cef.callback.CefCookieVisitor
import org.cef.misc.BoolRef
import org.cef.network.CefCookie
import org.cef.network.CefCookieManager
import java.awt.BorderLayout
import javax.swing.JPanel
import javax.swing.SwingUtilities

private const val TAG = "YunX-XunleiWebLogin"

/** 自动检测轮询间隔 */
private const val POLL_INTERVAL_MS = 2_000L

/** 注入脚本后给页面写 Cookie 一点时间，再读回 */
private const val COOKIE_SETTLE_MS = 400L

/** 同一份凭据校验失败后，间隔多久才允许用相同凭据重试（网络闪断恢复后能自动补登） */
private const val SAME_CREDENTIAL_RETRY_MS = 10_000L

/**
 * 迅雷网页登录页（桌面版）：
 * 在内嵌 JCEF 里打开 pan.xunlei.com，由官网页面自己完成登录（扫码 / 账号密码 / 验证码都行）。
 *
 * 桌面 JCEF **拿不到 executeJavaScript 的返回值**，所以采用「Cookie 桥」：轮询注入一段 JS，
 * 把 localStorage 里的网页凭据（credentials_<clientId>，含 deviceid / captcha 补全）分片写进
 * `yunx_xl_cred_*` Cookie，再从全局 Cookie 管理器读回、解析、打一次真实云盘接口校验后落库。
 * 页面改版或桥接失败时，底部「手动粘贴」可贴 localStorage 原文 / 裸 access_token（[XunleiWebCredential.parse]）。
 */
@Composable
fun XunleiWebLoginScreen(
    viewModel: XunleiAccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val ready = JcefHolder.browserReady
    var saving by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    var pasteInput by remember { mutableStateOf("") }
    var savingManual by remember { mutableStateOf(false) }

    // JCEF 未就绪（上次失败）时再触发一次后台初始化；成功后 ready 翻转 → 重建浏览器
    LaunchedEffect(Unit) { JcefHolder.initInBackground() }

    val app = if (ready) JcefHolder.app() else null
    val browserHolder = remember(ready) {
        if (app == null) null
        else runCatching {
            val client: CefClient = app.createClient()
            val browser: CefBrowser = client.createBrowser(XunleiWebCredential.LOGIN_URL, false, false)
            BrowserHolder(client, browser)
        }.onFailure { Log.e(TAG, "create browser failed", it) }.getOrNull()
    }

    DisposableEffect(browserHolder) {
        onDispose {
            runCatching { browserHolder?.browser?.close(true) }
            runCatching { browserHolder?.client?.dispose() }
        }
    }

    // 自动检测：注入 Cookie 桥 → 读回凭据 → 校验落库（同一份凭据失败后短暂冷却再试）
    LaunchedEffect(browserHolder) {
        val holder = browserHolder ?: return@LaunchedEffect
        var lastFailed = ""
        var lastFailedTs = 0L
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (saving) continue
            runCatching {
                SwingUtilities.invokeLater {
                    holder.browser.executeJavaScript(
                        XunleiWebCredential.COOKIE_BRIDGE_SCRIPT,
                        XunleiWebCredential.LOGIN_URL,
                        0
                    )
                }
            }
            delay(COOKIE_SETTLE_MS)
            // 只从可信域名（*.xunlei.com / https）读取登录态，避免页面被跳到别的站点时误读
            val currentUrl = runCatching { holder.browser.url }.getOrNull()
            if (!XunleiWebCredential.isTrustedUrl(currentUrl)) continue
            val raw = withContext(Dispatchers.IO) { collectWebCredential() }
            if (raw.isNullOrBlank() || XunleiWebCredential.parse(raw) == null) continue
            val now = System.currentTimeMillis()
            if (raw == lastFailed && now - lastFailedTs < SAME_CREDENTIAL_RETRY_MS) continue
            saving = true
            val saved = try {
                viewModel.saveWebCredential(raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            } finally {
                saving = false
            }
            if (saved) {
                SnackbarController.show("登录成功")
                onSaved()
                break
            } else {
                lastFailed = raw
                lastFailedTs = System.currentTimeMillis()
            }
        }
    }

    BackHandler(enabled = !saving && !savingManual) { onBack() }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部导航栏（显式提供 onSurface，深色主题下返回按钮悬停高亮才正常）
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { if (!saving && !savingManual && !pasteDialog) onBack() },
                        enabled = !saving && !savingManual && !pasteDialog
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                    Text(text = "迅雷网页登录", style = MaterialTheme.typography.titleMedium)
                }
            }

            // 状态卡
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在校验登录态…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    } else {
                        Icon(Icons.Outlined.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (browserHolder == null) "内嵌浏览器初始化中…" else "请在下方网页中登录，登录成功后自动识别",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 内嵌浏览器区域
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (browserHolder != null) {
                    SwingPanel(
                        factory = {
                            object : JPanel(BorderLayout()) {
                                override fun doLayout() {
                                    super.doLayout()
                                    val canvas = browserHolder.browser.uiComponent
                                    if (canvas.width != width || canvas.height != height ||
                                        canvas.x != 0 || canvas.y != 0
                                    ) {
                                        canvas.setBounds(0, 0, width, height)
                                    }
                                }
                            }.apply {
                                background = java.awt.Color.WHITE
                                add(browserHolder.browser.uiComponent, BorderLayout.CENTER)
                                addComponentListener(object : java.awt.event.ComponentAdapter() {
                                    override fun componentResized(e: java.awt.event.ComponentEvent) {
                                        browserHolder.browser.uiComponent.setBounds(0, 0, width, height)
                                    }
                                })
                                SwingUtilities.invokeLater { validate() }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        background = Color.White
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("内嵌浏览器不可用，请使用下方「手动粘贴登录凭据」", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            // 底部兜底入口
            OutlinedButton(
                onClick = { pasteDialog = true },
                enabled = !saving && !savingManual,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                Spacer(modifier = Modifier.size(6.dp))
                Text("手动粘贴登录凭据")
            }
        }

        // 手动粘贴兜底：窗口内覆盖层（与 FadeAlertDialog 同一套「零原生窗口」画法）。
        // 注意：本页是全屏覆盖层、不在 MainScreen 的 OverlayDialogHost 之下，注册式 FadeAlertDialog
        // 在此不会渲染，所以这里直接就地绘制遮罩 + 卡片。
        AnimatedVisibility(
            visible = pasteDialog,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { if (!savingManual) pasteDialog = false },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .widthIn(min = 280.dp, max = 560.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) {}
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("手动粘贴登录凭据", style = MaterialTheme.typography.titleLarge)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "在电脑浏览器登录 pan.xunlei.com 后，从开发者工具复制 localStorage 中 " +
                                "${XunleiWebCredential.STORAGE_KEY} 的值（整段 JSON 即可）；也可以直接粘贴 access_token",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = pasteInput,
                            onValueChange = { pasteInput = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("粘贴凭据 JSON 或 access_token…") },
                            minLines = 4,
                            maxLines = 8
                        )
                        TextButton(onClick = {
                            val text = DesktopActions.readClipboard().orEmpty()
                            if (text.isNotBlank()) pasteInput = text else SnackbarController.show("剪贴板为空")
                        }) { Text("从剪贴板粘贴") }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { if (!savingManual) pasteDialog = false },
                                enabled = !savingManual
                            ) { Text("取消") }
                            Spacer(modifier = Modifier.size(8.dp))
                            Button(
                                enabled = pasteInput.isNotBlank() && !savingManual,
                                onClick = {
                                    scope.launch {
                                        savingManual = true
                                        try {
                                            val saved = viewModel.saveWebCredential(pasteInput.trim())
                                            if (saved) {
                                                SnackbarController.show("登录成功")
                                                pasteDialog = false
                                                onSaved()
                                            } else {
                                                SnackbarController.show("凭据无效或已过期，请确认复制的是完整内容")
                                            }
                                        } finally {
                                            // 必须 finally：抛异常时「保存」按钮要能恢复可点
                                            savingManual = false
                                        }
                                    }
                                }
                            ) {
                                if (savingManual) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text("保存")
                            }
                        }
                    }
                }
            }
        }
    }
}

private class BrowserHolder(val client: CefClient, val browser: CefBrowser)

/**
 * 从全局 Cookie 管理器读回 `yunx_xl_cred_*` 分片并拼成完整凭据（URL 解码后即 localStorage 原文 JSON）。
 * 未登录 / 页面尚未写入 / 分片不完整时返回 null，由轮询继续等待。
 */
private fun collectWebCredential(): String? {
    return runCatching {
        val manager = CefCookieManager.getGlobalManager()
        val map = java.util.concurrent.ConcurrentHashMap<String, String>()
        manager.visitAllCookies(object : CefCookieVisitor {
            override fun visit(cookie: CefCookie, count: Int, total: Int, delete: BoolRef): Boolean {
                val domain = cookie.domain ?: ""
                val name = cookie.name ?: ""
                if (domain.contains("xunlei.com") &&
                    (name.startsWith(XunleiWebCredential.CRED_COOKIE_PREFIX) ||
                        name == XunleiWebCredential.CRED_COOKIE_COUNT)
                ) {
                    map[name] = cookie.value ?: ""
                }
                return true
            }
        })
        // visitAllCookies 回调在 CEF IO 线程异步执行，等它写完容器
        Thread.sleep(300)
        val count = map[XunleiWebCredential.CRED_COOKIE_COUNT]?.trim()?.toIntOrNull()
        if (count == null || count <= 0 || count > 64) {
            null
        } else {
            val parts = (0 until count).map { map["${XunleiWebCredential.CRED_COOKIE_PREFIX}$it"] }
            if (parts.any { it == null }) null
            else java.net.URLDecoder.decode(parts.joinToString(""), "UTF-8")
        }
    }.getOrNull()
}
