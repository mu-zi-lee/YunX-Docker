package com.yunx.macos

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.util.DesktopActions
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private val platformNames = mapOf("QUARK" to "夸克", "UC" to "UC", "XUNLEI" to "迅雷", "BAIDU" to "百度",
    "C139" to "139", "PAN123" to "123", "PAN115" to "115", "GUANGYA" to "光鸭", "ILANZOU" to "蓝奏优享", "LANZOU" to "蓝奏", "GITHUB" to "GitHub")
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
data class Directory(val id: String, val name: String)
data class Browser(val result: JSONObject, val cloud: Boolean, val trail: List<Directory>) {
    val files get() = result.optJSONArray("files")?.objects().orEmpty()
}

@Composable
fun Workspace(runtime: MacRuntime, previewPage: String?, previewTheme: String?, quitting: Boolean) {
    var page by remember { mutableStateOf("resolve") }
    var theme by remember { mutableStateOf(runtime.prefs.get("theme", "system")) }
    val dark = if (previewTheme != null) previewTheme == "dark" else theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val c = if (dark) WebColors.Dark else WebColors.Light
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val tasks by runtime.downloads.tasks.collectAsState(emptyList())
    val stats by runtime.downloads.stats.collectAsState()
    var accounts by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var history by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var share by remember { mutableStateOf<Browser?>(null) }
    var cloud by remember { mutableStateOf<Browser?>(null) }
    var selectedPlatform by remember { mutableStateOf("") }
    var accountEditor by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<String?>(null) }
    var backupExport by remember { mutableStateOf<Boolean?>(null) }
    var cloudEdit by remember { mutableStateOf<Pair<String, Set<String>>?>(null) }
    var accountStatus by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val visual = previewPage != null
    fun action(block: suspend () -> Unit) {
        if (busy || quitting) return
        busy = true; failed = false; notice = ""
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { failed = true; notice = LogRedactor.line(e.message ?: "操作失败，请重试") }
            finally { busy = false }
        }
    }
    suspend fun refresh() {
        val (a, h) = withContext(Dispatchers.IO) { runtime.service.accounts().objects() to runtime.service.library().getJSONArray("history").objects() }
        accounts = a; history = h
    }
    suspend fun loadCloud(platform: String) {
        val result = withContext(Dispatchers.IO) { runtime.service.cloudFiles(JSONObject().put("platform", platform)) }
        cloud = Browser(result, true, listOf(Directory(result.getString("directory"), platformNames[platform] ?: platform)))
        selectedPlatform = platform
    }
    LaunchedEffect(revision) { refresh() }
    LaunchedEffect(previewPage) { if (previewPage != null) page = previewPage }
    CompositionLocalProvider(Colors provides c) {
        Row(Modifier.fillMaxSize().background(c.bg)) {
            Column(Modifier.width(184.dp).fillMaxHeight().border(width = 1.dp, color = c.line).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.padding(top = 12.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource("icon.png"), "云析", Modifier.size(32.dp),
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }))
                    Column { Label("云析", size = 18); Label("YUNX / LOCAL", size = 10, muted = true, mono = true) }
                }
                val nav = listOf(Triple("cloud", "我的网盘", Icons.Outlined.Cloud), Triple("resolve", "分享解析", Icons.Outlined.Link),
                    Triple("tasks", "下载", Icons.Outlined.Download), Triple("accounts", "账号", Icons.Outlined.AccountCircle), Triple("settings", "设置", Icons.Outlined.Tune))
                nav.forEach { (id, title, icon) ->
                    Row(Modifier.fillMaxWidth().height(48.dp)
                        .background(if (page == id) c.surface else Color.Transparent, RoundedCornerShape(6.dp))
                        .clickable { page = id; notice = "" }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(icon, title, Modifier.size(20.dp), tint = if (page == id) c.text else c.secondary)
                        Label(title, muted = page != id)
                    }
                }
                Spacer(Modifier.weight(1f))
                Label("AGPL-3.0", size = 10, muted = true, mono = true)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Label("WORKSPACE / ${page.uppercase()}", size = 11, muted = true, mono = true, modifier = Modifier.weight(1f))
                    if (busy || quitting) Label(if (quitting) "正在保存任务…" else "正在处理…", size = 12, muted = true)
                }
                Label(when (page) { "cloud" -> "我的网盘"; "tasks" -> "下载任务"; "accounts" -> "网盘账号"; "settings" -> "设置"; else -> "分享解析" }, size = 28)
                if (notice.isNotEmpty()) CompositionLocalProvider(Colors provides c.copy(text = if (failed) c.red else c.secondary)) { Label(notice, size = 12, lines = 3) }
                when (page) {
                    "resolve" -> ResolvePage(if (visual) VisualFixtures.browser(false) else share, history, busy, { link, pwd ->
                        action {
                            require(!link.trim().startsWith("magnet:", true)) { "此版本不支持磁力 / BT 下载" }
                            val result = withContext(Dispatchers.IO) { runtime.service.resolve(JSONObject().put("link", link).put("password", pwd)) }
                            share = Browser(result, false, listOf(Directory(result.optString("directory"), result.optString("title", "分享文件"))))
                            refresh()
                        }
                    }, { url, name -> action { withContext(Dispatchers.IO) { runtime.service.direct(JSONObject().put("url", url).put("filename", name)) }; notice = "已加入下载" } },
                        { dir, trail -> action {
                            val current = share ?: return@action
                            val result = withContext(Dispatchers.IO) { runtime.service.list(JSONObject().put("sessionId", current.result.getString("sessionId")).put("directory", dir)) }
                            result.put("sessionId", current.result.getString("sessionId"))
                            share = Browser(result, false, trail)
                        } },
                        { ids -> action {
                            val current = share ?: return@action
                            val errors = mutableListOf<String>()
                            for (id in ids) {
                                try { withContext(Dispatchers.IO) { runtime.service.enqueue(JSONObject().put("sessionId", current.result.getString("sessionId")).put("fid", id)) } }
                                catch (e: Exception) { errors += LogRedactor.line(e.message ?: "添加失败") }
                            }
                            failed = errors.isNotEmpty(); notice = if (errors.isEmpty()) "已添加 ${ids.size} 个下载任务" else "已添加 ${ids.size - errors.size} 个，${errors.size} 个失败：${errors.first()}"
                        } },
                        { url -> action {
                            val result = withContext(Dispatchers.IO) { runtime.service.resolve(JSONObject().put("link", url)) }
                            share = Browser(result, false, listOf(Directory(result.optString("directory"), result.optString("title", "GitHub"))))
                        } })
                    "cloud" -> CloudPage(if (visual) VisualFixtures.accounts else accounts, if (visual) "QUARK" else selectedPlatform,
                        if (visual) VisualFixtures.browser(true) else cloud, busy,
                        { accountEditor = SharePlatform.QUARK.name },
                        { p -> action { loadCloud(p) } },
                        { dir, trail -> action {
                            val current = cloud ?: return@action
                            val result = withContext(Dispatchers.IO) { runtime.service.cloudFiles(JSONObject().put("sessionId", current.result.getString("sessionId")).put("directory", dir)) }
                            cloud = Browser(result, true, trail)
                        } },
                        { action {
                            val current = cloud ?: return@action
                            val result = withContext(Dispatchers.IO) { runtime.service.cloudFiles(JSONObject()
                                .put("sessionId", current.result.getString("sessionId")).put("directory", current.result.getString("directory"))
                                .put("page", current.result.optInt("_page", 1) + 1).put("cursor", current.result.optString("cursor"))) }
                            val all = JSONArray(current.files + result.getJSONArray("files").objects())
                            result.put("files", all).put("_page", current.result.optInt("_page", 1) + 1)
                            cloud = Browser(result, true, current.trail)
                        } },
                        { ids -> action {
                            val current = cloud ?: return@action
                            val errors = mutableListOf<String>()
                            for (id in ids) {
                                try { withContext(Dispatchers.IO) { runtime.service.cloudDownload(JSONObject().put("sessionId", current.result.getString("sessionId")).put("fid", id)) } }
                                catch (e: Exception) { errors += LogRedactor.line(e.message ?: "添加失败") }
                            }
                            failed = errors.isNotEmpty(); notice = if (errors.isEmpty()) "已添加 ${ids.size} 个下载任务" else "已添加 ${ids.size - errors.size} 个，${errors.size} 个失败：${errors.first()}"
                        } }, { command, ids -> cloudEdit = command to ids; notice = ""; failed = false })
                    "tasks" -> TasksPage(if (visual) VisualFixtures.tasks else tasks, if (visual) mapOf(1L to 8388608L) else stats.mapValues { it.value.speed }, busy, { id, cmd -> action {
                        withContext(Dispatchers.IO) { runtime.service.taskAction(JSONObject().put("id", id).put("action", cmd)) }
                    } }, { path, reveal -> action {
                        check(if (reveal) DesktopActions.revealFile(path) else DesktopActions.openFile(path)) { "文件不存在或无法打开" }
                    } }, { cmd -> action {
                        for (task in tasks.filter { if (cmd == "pause") it.status in listOf(0, 1) else it.status in listOf(2, 4) })
                            withContext(Dispatchers.IO) { runtime.service.taskAction(JSONObject().put("id", task.id).put("action", cmd)) }
                    } })
                    "accounts" -> AccountsPage(if (visual) VisualFixtures.accounts else accounts, accountStatus, busy, { accountEditor = it },
                        { p -> action {
                            try {
                                val profile = withContext(Dispatchers.IO) { runtime.service.accountInfo(JSONObject().put("platform", p)) }
                                accountStatus = accountStatus + (p to if (profile.optBoolean("available")) "有效" else "暂时无法验证")
                            } catch (e: Exception) {
                                val msg = e.message.orEmpty()
                                accountStatus = accountStatus + (p to if (Regex("(?i)未登录|登录失效|凭证无效|token.*expired|unauthorized").containsMatchIn(msg)) "无效" else "暂时无法验证")
                                throw e
                            }
                            refresh()
                        } }, { removal = it }, { p -> page = "cloud"; action { loadCloud(p) } })
                    "settings" -> SettingsPage(runtime, theme, busy, { next -> theme = next; runtime.prefs.put("theme", next) },
                        { action { it(); notice = "设置已保存" } }, { backupExport = it; failed = false; notice = "" })
                }
            }
        }
        if (accountEditor != null) AccountDialog(accountEditor!!, busy, if (failed) notice else "",
            { accountEditor = null }, { p, fields -> action {
                withContext(Dispatchers.IO) { runtime.service.saveAccount(JSONObject().put("platform", p).put("credentials", fields)) }
                accountStatus = accountStatus - p; share = null; cloud = null
                accountEditor = null; revision++; notice = "凭证已保存，尚未验证"
            } })
        if (removal != null) ConfirmDialog("移除 ${platformNames[removal]} 账号？", busy, { removal = null }) {
            action {
                val p = removal!!
                withContext(Dispatchers.IO) { runtime.service.saveAccount(JSONObject().put("platform", p).put("credentials", JSONObject())) }
                accountStatus = accountStatus - p; cloud = null; share = null; removal = null; revision++
            }
        }
        backupExport?.let { export ->
            BackupDialog(export, busy, if (failed) notice else "", { backupExport = null }) { file, password ->
                action {
                    if (export) {
                        withContext(Dispatchers.IO) { runtime.exportAccounts(file, password) }
                        notice = "加密账号备份已导出"
                    } else {
                        val count = withContext(Dispatchers.IO) { runtime.importAccounts(file, password) }
                        cloud = null; share = null; accountStatus = emptyMap(); revision++
                        notice = "已恢复 $count 个平台的凭证，尚未验证"
                    }
                    backupExport = null
                }
            }
        }
        cloudEdit?.let { (command, ids) ->
            cloud?.let { current ->
                CloudMutationDialog(command, ids, current, busy, if (failed) notice else "", { cloudEdit = null }) { request ->
                    action {
                        val response = withContext(Dispatchers.IO) { runtime.service.cloudAction(request) }
                        cloudEdit = null
                        failed = response.getJSONArray("errors").length() > 0
                        notice = response.getString("message")
                        if (failed) notice += "：" + response.getJSONArray("errors").getJSONObject(0).getString("message")
                        try {
                            val refreshed = withContext(Dispatchers.IO) {
                                runtime.service.cloudFiles(JSONObject().put("sessionId", current.result.getString("sessionId"))
                                    .put("directory", current.result.getString("directory")))
                            }
                            cloud = Browser(refreshed, true, current.trail)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            failed = true
                            notice += "；目录刷新失败，请手动刷新：" + LogRedactor.line(e.message ?: "网络错误")
                        }
                    }
                }
            }
        }
    }
}
