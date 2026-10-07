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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.util.DesktopActions
import org.json.JSONObject
import java.io.File

private val names = mapOf("QUARK" to "夸克", "UC" to "UC", "XUNLEI" to "迅雷", "BAIDU" to "百度",
    "C139" to "139", "PAN123" to "123", "PAN115" to "115", "GUANGYA" to "光鸭", "ILANZOU" to "蓝奏优享", "LANZOU" to "蓝奏", "GITHUB" to "GitHub")

@Composable
fun ResolvePage(browser: Browser?, history: List<JSONObject>, busy: Boolean,
    resolve: (String, String) -> Unit, direct: (String, String) -> Unit,
    navigate: (String, List<Directory>) -> Unit, download: (Set<String>) -> Unit, repository: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var directOpen by remember { mutableStateOf(false) }
    var directUrl by remember { mutableStateOf("") }
    var directName by remember { mutableStateOf("") }
    var historyOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("分享链接", link, { link = it }, multiline = true, enabled = !busy)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("提取码", password, { password = it }, Modifier.width(160.dp), enabled = !busy)
            Command("解析", Icons.Outlined.Link, primary = true, enabled = !busy && link.isNotBlank()) { resolve(link, password) }
            Tool("添加直链下载", Icons.Outlined.Add, !busy) { directOpen = !directOpen; historyOpen = false }
            Tool("最近解析", Icons.Outlined.History, !busy) { historyOpen = !historyOpen; directOpen = false }
        }
        if (directOpen) {
            Field("下载地址", directUrl, { directUrl = it }, enabled = !busy)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Field("文件名（可留空）", directName, { directName = it }, Modifier.weight(1f), enabled = !busy)
                Command("加入下载", Icons.Outlined.Download, enabled = !busy && directUrl.isNotBlank()) { direct(directUrl, directName) }
            }
        }
        if (historyOpen) {
            LazyColumn(Modifier.heightIn(max = 150.dp)) {
                items(history) { h ->
                    Label(h.optString("title").ifBlank { h.optString("link") }, Modifier.fillMaxWidth().clickable(enabled = !busy) {
                        link = h.getString("link"); password = h.optString("password"); resolve(link, password); historyOpen = false
                    }.padding(vertical = 8.dp), lines = 1)
                    Rule()
                }
            }
            if (history.isEmpty()) Label("暂无解析记录", muted = true)
        }
        if (browser != null) {
            if (browser.result.has("directUrl")) {
                Command("下载 ${browser.result.optString("filename")}", Icons.Outlined.Download, enabled = !busy) {
                    direct(browser.result.getString("directUrl"), browser.result.optString("filename"))
                }
            } else if (browser.result.has("assets")) {
                LazyColumn(Modifier.weight(1f)) {
                    items(browser.result.getJSONArray("assets").objects()) { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Label(a.getString("name"), Modifier.weight(1f), lines = 2)
                            Label(sizeText(a.optLong("size")), muted = true, mono = true, size = 12)
                            Tool("下载", Icons.Outlined.Download, !busy) { direct(a.getString("url"), a.getString("name")) }
                        }
                        Rule()
                    }
                }
            } else if (browser.result.has("repositories")) {
                LazyColumn(Modifier.weight(1f)) {
                    items(browser.result.getJSONArray("repositories").objects()) { r ->
                        Command(r.getString("name"), Icons.Outlined.Folder, enabled = !busy) { repository(r.getString("url")) }
                    }
                }
            } else FileBrowser(browser, busy, navigate, download, Modifier.weight(1f))
        }
    }
}

@Composable
fun CloudPage(accounts: List<JSONObject>, selected: String, browser: Browser?, busy: Boolean,
    add: () -> Unit, choose: (String) -> Unit, navigate: (String, List<Directory>) -> Unit,
    more: () -> Unit, download: (Set<String>) -> Unit, manage: (String, Set<String>) -> Unit) {
    val available = accounts.filter { it.optBoolean("configured") && it.optBoolean("cloudSupported") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice(selected, available.map { it.getString("platform").let { p -> p to (names[p] ?: p) } }, !busy, choose)
            Tool("添加账号", Icons.Outlined.Add, !busy, add)
            if (selected.isNotEmpty()) Tool("刷新目录", Icons.Outlined.Refresh, !busy) {
                if (browser != null) navigate(browser.result.getString("directory"), browser.trail) else choose(selected)
            }
        }
        if (available.isEmpty()) {
            Spacer(Modifier.height(48.dp))
            Label("还没有可浏览的网盘", muted = true)
            Command("添加账号", Icons.Outlined.Add, action = add)
        } else if (browser == null) Label("选择一个网盘", muted = true)
        else {
            FileBrowser(browser, busy, navigate, download, Modifier.weight(1f), manage)
            if (browser.result.optBoolean("hasMore")) Command("加载更多", enabled = !busy, action = more)
            if (browser.result.optBoolean("limited")) Label("当前平台目录接口最多返回 500 项", muted = true, size = 12)
        }
    }
}

@Composable
fun FileBrowser(browser: Browser, busy: Boolean, navigate: (String, List<Directory>) -> Unit,
    download: (Set<String>) -> Unit, modifier: Modifier = Modifier, manage: ((String, Set<String>) -> Unit)? = null) {
    var search by remember(browser.result.optString("sessionId"), browser.result.optString("directory")) { mutableStateOf("") }
    var selection by remember(browser.result.optString("sessionId"), browser.result.optString("directory")) { mutableStateOf(setOf<String>()) }
    var sort by remember { mutableStateOf("name") }
    val files = browser.files.filter { it.getString("name").contains(search, ignoreCase = true) }
        .sortedWith(compareBy<JSONObject> { !it.getBoolean("directory") }.thenComparator { a, b ->
            when (sort) {
                "size" -> b.optLong("size").compareTo(a.optLong("size"))
                "date" -> b.optString("modified").compareTo(a.optString("modified"))
                else -> a.getString("name").compareTo(b.getString("name"), ignoreCase = true)
            }
        })
    LaunchedEffect(browser.result) { selection = selection.intersect(browser.files.map { it.getString("fid") }.toSet()) }
    val visibleIds = files.filter { manage != null || !it.getBoolean("directory") }.map { it.getString("fid") }.toSet()
    val selectedFiles = browser.files.filter { !it.getBoolean("directory") && it.getString("fid") in selection }.map { it.getString("fid") }.toSet()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            browser.trail.forEachIndexed { index, dir ->
                if (index > 0) Label("/", muted = true)
                Label(dir.name, Modifier.widthIn(max = 180.dp).clickable(enabled = !busy) { navigate(dir.id, browser.trail.take(index + 1)) }, lines = 1)
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("当前目录搜索", search, { search = it }, Modifier.weight(1f))
            Choice(sort, listOf("name" to "名称", "size" to "大小", "date" to "日期")) { sort = it }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool("全选文件", if (visibleIds.isNotEmpty() && selection.containsAll(visibleIds)) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, !busy) {
                selection = if (selection.containsAll(visibleIds)) selection - visibleIds else selection + visibleIds
            }
            Label("已选 ${selection.size} 项", Modifier.weight(1f), muted = true, size = 12, lines = 1)
            if (manage != null) {
                Tool("新建文件夹", Icons.Outlined.CreateNewFolder, !busy) { manage("create", emptySet()) }
                Tool("重命名", Icons.Outlined.DriveFileRenameOutline, !busy && selection.size == 1) { manage("rename", selection) }
                Tool("移动", Icons.Outlined.DriveFileMove, !busy && selection.isNotEmpty()) { manage("move", selection) }
                Tool("删除云端文件", Icons.Outlined.DeleteOutline, !busy && selection.isNotEmpty()) { manage("delete", selection) }
            }
            Command("下载", Icons.Outlined.Download, primary = true, enabled = !busy && selectedFiles.isNotEmpty()) { download(selectedFiles) }
        }
        Rule()
        if (files.isEmpty()) Label(if (search.isBlank()) "目录为空" else "没有匹配文件", muted = true)
        LazyColumn(Modifier.weight(1f)) {
            items(files, key = { it.getString("fid") }) { file ->
                val id = file.getString("fid")
                val directory = file.getBoolean("directory")
                Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!directory || manage != null) Tool("选择文件", if (id in selection) Icons.Outlined.CheckBox else Icons.Outlined.CheckBoxOutlineBlank, !busy) {
                        selection = if (id in selection) selection - id else selection + id
                    } else Spacer(Modifier.width(40.dp))
                    Icon(if (directory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile, null, Modifier.size(20.dp), tint = Colors.current.secondary)
                    Label(file.getString("name"), Modifier.weight(1f).clickable(enabled = directory && !busy) {
                        val dirId = file.optString("directoryId", id)
                        navigate(dirId, browser.trail + Directory(dirId, file.getString("name")))
                    }.padding(vertical = 10.dp), lines = 2)
                    Label(if (directory) "" else sizeText(file.optLong("size")), Modifier.width(82.dp), size = 11, muted = true, mono = true, lines = 1)
                    if (!directory) Tool("下载文件", Icons.Outlined.Download, !busy) { download(setOf(id)) }
                    else Tool("打开目录", Icons.Outlined.ChevronRight, !busy) {
                        val dirId = file.optString("directoryId", id)
                        navigate(dirId, browser.trail + Directory(dirId, file.getString("name")))
                    }
                }
                Rule()
            }
        }
    }
}

@Composable
fun CloudMutationDialog(action: String, ids: Set<String>, browser: Browser, busy: Boolean, error: String,
    dismiss: () -> Unit, submit: (JSONObject) -> Unit) {
    val selected = browser.files.filter { it.getString("fid") in ids }
    var name by remember { mutableStateOf(if (action == "rename") selected.firstOrNull()?.optString("name").orEmpty() else "") }
    val options = browser.result.optJSONArray("directories")?.objects().orEmpty()
        .filter { it.getString("id") != browser.result.getString("directory") }.map { it.getString("id") to it.getString("name") }
    var target by remember { mutableStateOf(options.firstOrNull()?.first.orEmpty()) }
    val title = when (action) { "create" -> "新建文件夹"; "rename" -> "重命名"; "move" -> "移动 ${ids.size} 项"; else -> "删除 ${ids.size} 项云端文件？" }
    val c = Colors.current
    DialogWindow(onCloseRequest = { if (!busy) dismiss() }, title = title, resizable = false,
        state = androidx.compose.ui.window.rememberDialogState(width = 500.dp, height = 360.dp)) {
        CompositionLocalProvider(Colors provides c) {
            Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Label(title, size = 22)
                if (action in setOf("create", "rename")) Field("名称", name, { name = it }, enabled = !busy)
                if (action == "move") {
                    Label("目标文件夹", size = 12, muted = true)
                    Choice(target, options, !busy) { target = it }
                    if (options.isEmpty()) Label("尚无其他可选目录", muted = true)
                }
                if (action == "delete") {
                    Label(selected.take(3).joinToString("\n") { it.getString("name") }, lines = 3)
                    Label("由网盘处理删除或回收；本地下载文件保留。", muted = true, size = 12)
                }
                if (error.isNotBlank()) Label(error, size = 12, lines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Command(if (action == "delete") "删除云端文件" else "确认", primary = true,
                        enabled = !busy && (action !in setOf("create", "rename") || name.isNotBlank()) &&
                            (action != "move" || options.any { it.first == target })) {
                        submit(JSONObject().put("action", action).put("sessionId", browser.result.getString("sessionId"))
                            .put("directory", browser.result.getString("directory")).put("fids", org.json.JSONArray(ids.toList()))
                            .put("name", name).put("target", target).put("confirmed", action == "delete"))
                    }
                    Command("取消", enabled = !busy, action = dismiss)
                }
            }
        }
    }
}

@Composable
fun TasksPage(tasks: List<DownloadTaskEntity>, speeds: Map<Long, Long>, busy: Boolean,
    action: (Long, String) -> Unit, open: (String, Boolean) -> Unit, bulk: (String) -> Unit) {
    var filter by remember { mutableStateOf("全部") }
    var removal by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label("${sizeText(speeds.values.sum())}/s", size = 28, mono = true)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("全部", "进行中", "已完成", "失败").forEach { f -> Command(f, primary = filter == f) { filter = f } }
            Spacer(Modifier.weight(1f))
            Tool("暂停全部", Icons.Outlined.Pause, !busy) { bulk("pause") }
            Tool("继续全部", Icons.Outlined.PlayArrow, !busy) { bulk("resume") }
        }
        val visible = tasks.filter { when (filter) { "进行中" -> it.status in listOf(0, 1); "已完成" -> it.status == 3; "失败" -> it.status == 4; else -> true } }
        if (visible.isEmpty()) Label("暂无下载任务", muted = true)
        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.id }) { task ->
                Column(Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Label(task.fileName, Modifier.weight(1f), lines = 2)
                        Label(DownloadTaskEntity.statusText(task.status), size = 12, muted = true)
                        if (task.status in listOf(0, 1)) Tool("暂停", Icons.Outlined.Pause, !busy) { action(task.id, "pause") }
                        if (task.status in listOf(2, 4)) Tool(if (task.status == 4) "重试" else "继续", Icons.Outlined.PlayArrow, !busy) { action(task.id, "resume") }
                        if (task.status == 3) {
                            Tool("打开文件", Icons.Outlined.OpenInNew, !busy) { open(task.savePath, false) }
                            Tool("在 Finder 中显示", Icons.Outlined.FolderOpen, !busy) { open(task.savePath, true) }
                        }
                        Tool(if (task.status in listOf(0, 1)) "取消任务" else "删除记录", Icons.Outlined.Close, !busy) { removal = task.id }
                    }
                    val fraction = if (task.totalSize > 0) (task.downloadedSize.toDouble() / task.totalSize).coerceIn(0.0, 1.0).toFloat() else 0f
                    Box(Modifier.fillMaxWidth().height(4.dp).background(Colors.current.line)) {
                        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Colors.current.text))
                    }
                    Label("${sizeText(task.downloadedSize)} / ${if (task.totalSize > 0) sizeText(task.totalSize) else "未知"}   ${sizeText(speeds[task.id] ?: 0)}/s",
                        size = 11, muted = true, mono = true)
                    if (task.status == DownloadTaskEntity.STATUS_FAILED && task.errorMsg.isNotBlank())
                        Label(com.yunx.app.util.LogRedactor.line(task.errorMsg), size = 12, lines = 2)
                }
                Rule()
            }
        }
    }
    if (removal != null) ConfirmDialog("移除任务？已完成文件将保留。", busy, { removal = null }) {
        action(removal!!, "remove"); removal = null
    }
}

@Composable
fun AccountsPage(accounts: List<JSONObject>, statuses: Map<String, String>, busy: Boolean,
    edit: (String) -> Unit, verify: (String) -> Unit, remove: (String) -> Unit, browse: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(accounts, key = { it.getString("platform") }) { a ->
            val p = a.getString("platform")
            Column(Modifier.fillMaxWidth().border(1.dp, Colors.current.line, RoundedCornerShape(8.dp)).background(Colors.current.surface, RoundedCornerShape(8.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Label(names[p] ?: p, Modifier.weight(1f), size = 18)
                    Label(if (a.optBoolean("configured")) statuses[p] ?: "已配置 · 尚未验证" else "未配置", size = 12, muted = true)
                }
                a.optJSONObject("profile")?.let { profile ->
                    if (profile.optString("nickname").isNotBlank()) Label(profile.getString("nickname"), muted = true)
                    profile.optJSONObject("quota")?.let { quota -> Label("${sizeText(quota.optLong("used"))} / ${sizeText(quota.optLong("total"))}", mono = true, muted = true) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Command(if (a.optBoolean("configured")) "更新凭证" else "添加账号", Icons.Outlined.Add, enabled = !busy) { edit(p) }
                    if (a.optBoolean("configured")) {
                        Command("验证", Icons.Outlined.Refresh, enabled = !busy) { verify(p) }
                        if (a.optBoolean("cloudSupported")) Tool("浏览文件", Icons.Outlined.FolderOpen, !busy) { browse(p) }
                        Tool("移除账号", Icons.Outlined.Close, !busy) { remove(p) }
                    }
                }
            }
        }
    }
}

@Composable
fun AccountDialog(initial: String, busy: Boolean, error: String, dismiss: () -> Unit, save: (String, JSONObject) -> Unit) {
    var platform by remember { mutableStateOf(initial) }
    var values by remember(platform) { mutableStateOf(mapOf<String, String>()) }
    val colors = Colors.current
    DialogWindow(onCloseRequest = { if (!busy) dismiss() }, title = "网盘凭证", resizable = false,
        state = androidx.compose.ui.window.rememberDialogState(width = 520.dp, height = 640.dp)) {
        CompositionLocalProvider(Colors provides colors) {
            Column(Modifier.fillMaxSize().background(colors.bg).padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Label("网盘凭证", size = 24)
                Choice(platform, SharePlatform.entries.map { it.name to (names[it.name] ?: it.name) }, !busy) { platform = it }
                val fields = when (platform) {
                    "XUNLEI" -> listOf("accessToken" to "Access Token", "refreshToken" to "Refresh Token", "deviceId" to "Device ID", "captchaToken" to "Captcha Token")
                    "GUANGYA" -> listOf("accessToken" to "Access Token", "refreshToken" to "Refresh Token", "deviceId" to "Device ID", "deviceSign" to "Device Sign")
                    "ILANZOU" -> listOf("accessToken" to "App Token", "uuid" to "UUID")
                    "PAN123", "GITHUB" -> listOf("accessToken" to "Access Token")
                    else -> listOf("cookie" to "Cookie")
                }
                fields.forEach { (key, title) -> Field(title, values[key].orEmpty(), { values = values + (key to it) }, secret = true, enabled = !busy) }
                if (platform == "XUNLEI") {
                    Label("登录通道", muted = true, size = 12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Command("App", primary = values["authType"].orEmpty().isEmpty(), enabled = !busy) { values = values + ("authType" to "") }
                        Command("网页", primary = values["authType"] == "webToken", enabled = !busy) { values = values + ("authType" to "webToken") }
                    }
                }
                Label("凭证仅保存在本机。更新时不显示已保存的凭证；空白的可选字段保留原值。", size = 12, muted = true)
                if (error.isNotBlank()) Label(error, size = 12)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Command(if (busy) "正在保存…" else "保存凭证", Icons.Outlined.Save, primary = true,
                        enabled = !busy && values[fields.first().first].orEmpty().isNotBlank() && (platform != "ILANZOU" || values["uuid"].orEmpty().isNotBlank())) {
                        save(platform, JSONObject(values + if (platform == "XUNLEI") mapOf("authType" to values["authType"].orEmpty()) else emptyMap()))
                    }
                    Command("取消", enabled = !busy, action = dismiss)
                }
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, busy: Boolean, dismiss: () -> Unit, confirm: () -> Unit) {
    val c = Colors.current
    DialogWindow(onCloseRequest = { if (!busy) dismiss() }, title = "确认", resizable = false,
        state = androidx.compose.ui.window.rememberDialogState(width = 420.dp, height = 200.dp)) {
        CompositionLocalProvider(Colors provides c) {
            Column(Modifier.fillMaxSize().background(c.bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Label(title)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Command("确认移除", enabled = !busy, action = confirm)
                    Command("取消", enabled = !busy, action = dismiss)
                }
            }
        }
    }
}

@Composable
fun SettingsPage(runtime: MacRuntime, theme: String, busy: Boolean, setTheme: (String) -> Unit, save: (() -> Unit) -> Unit,
    backup: (Boolean) -> Unit) {
    var directory by remember { mutableStateOf(runtime.prefs.get("directory", DesktopActions.defaultDownloadDir.absolutePath)) }
    var threads by remember { mutableStateOf(runtime.prefs.getInt("threads", 32).toString()) }
    var concurrency by remember { mutableStateOf(runtime.prefs.getInt("concurrency", 3).toString()) }
    var speed by remember { mutableStateOf((runtime.prefs.getLong("speedLimit", 0) / 1048576.0).toString()) }
    var host by remember { mutableStateOf(runtime.prefs.get("proxyHost", "")) }
    var port by remember { mutableStateOf(runtime.prefs.getInt("proxyPort", 8080).toString()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Label("下载设置", size = 18)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("下载目录", directory, { directory = it }, Modifier.weight(1f), enabled = !busy)
            Tool("选择目录", Icons.Outlined.FolderOpen, !busy) { DesktopActions.pickDirectory()?.let { directory = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("每任务连接数", threads, { threads = it }, Modifier.weight(1f), enabled = !busy)
            Field("同时下载任务数", concurrency, { concurrency = it }, Modifier.weight(1f), enabled = !busy)
        }
        Field("总限速 · MiB/s，0 为不限速", speed, { speed = it }, enabled = !busy)
        Rule()
        Label("代理", size = 18)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("HTTP 代理主机（留空为直连）", host, { host = it }, Modifier.weight(1f), enabled = !busy)
            Field("端口", port, { port = it }, Modifier.width(120.dp), enabled = !busy)
        }
        Command("保存设置", Icons.Outlined.Save, enabled = !busy) {
            save {
                val n = threads.toIntOrNull(); val concurrent = concurrency.toIntOrNull(); val rate = speed.toDoubleOrNull()
                require(n != null && n in 1..512 && concurrent != null && concurrent in 1..10 && rate != null && rate.isFinite() && rate in 0.0..953674.0) { "连接数 1–512，并发数 1–10，限速需为非负数" }
                val proxyPort = port.toIntOrNull()
                require(host.isBlank() || (proxyPort != null && proxyPort in 1..65535)) { "代理端口需为 1–65535" }
                val dir = File(directory)
                require(dir.isAbsolute && (dir.isDirectory || dir.mkdirs()) && dir.canWrite()) { "下载目录不可写，请重新选择" }
                runtime.prefs.put("directory", dir.canonicalPath)
                runtime.prefs.putInt("threads", n)
                runtime.prefs.putInt("concurrency", concurrent)
                runtime.prefs.putLong("speedLimit", (rate * 1048576).toLong())
                runtime.prefs.put("proxyHost", host.trim())
                runtime.prefs.putInt("proxyPort", proxyPort ?: 8080)
                runtime.applyProxy()
                runtime.prefs.flush()
            }
        }
        Rule()
        Label("外观", size = 18)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (key, title) ->
                Command(title, primary = theme == key) { setTheme(key) }
            }
        }
        Spacer(Modifier.height(16.dp))
        Rule()
        Label("账号备份", size = 18)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Command("导出加密备份", Icons.Outlined.Upload, enabled = !busy) { backup(true) }
            Command("导入加密备份", Icons.Outlined.Download, enabled = !busy) { backup(false) }
        }
    }
}

@Composable
fun BackupDialog(export: Boolean, busy: Boolean, error: String, dismiss: () -> Unit, submit: (File, String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val colors = Colors.current
    val title = if (export) "导出加密账号备份" else "导入加密账号备份"
    DialogWindow(onCloseRequest = { if (!busy) dismiss() }, title = title, resizable = false,
        state = androidx.compose.ui.window.rememberDialogState(width = 500.dp, height = 360.dp)) {
        CompositionLocalProvider(Colors provides colors) {
            Column(Modifier.fillMaxSize().background(colors.bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Label(title, size = 22)
                Field("备份口令", password, { password = it }, secret = true, enabled = !busy)
                if (export) Field("确认口令", confirmation, { confirmation = it }, secret = true, enabled = !busy)
                else Label("导入会替换备份中对应平台的账号。", size = 12, muted = true)
                if (error.isNotBlank()) Label(error, size = 12, lines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Command(if (export) "选择保存位置" else "选择备份文件", Icons.Outlined.FolderOpen,
                        enabled = !busy && password.length >= 8 && (!export || password == confirmation)) {
                        DesktopActions.pickFile(export)?.let { submit(it, password) }
                    }
                    Command("取消", enabled = !busy, action = dismiss)
                }
            }
        }
    }
}
