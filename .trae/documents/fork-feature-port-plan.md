# Fork 功能移植到主仓库实施计划

## Context

主仓库 `e:/桌面/YunXPC`（HEAD `ce1ac8c`）从 fork 分叉点 `472527c` 之后只加了 2 条 README 文档提交。Fork 在 `472527c..4fce8bf` 之间累积了 15 条提交（v1.1.3 → v1.1.4），用户要求把其中 7 类功能「优化并更新到主仓库」：

1. 百度网盘连环修复（接口域名 / UA / bdstoken / errno=-6 重试 / 8888 限流重试 / 超时收紧）
2. 139 登录 anyOfKeys 兜底
3. 剪贴板分享链接检测弹窗（ClipboardLinkPopup）
4. 解析链接历史对话框（LinkHistoryDialog + link_history 表）
5. 登出彻底清除 Cookie（CookieCleaner + CEF delete 标志）
6. 下载完成项新增「文件夹」按钮（explorer /select）
7. 右键菜单统一 FadeAlertDialog 材质 + 复制分享链接

「优化」指：保持 fork 的功能行为，但遵循主仓库已有规范（FadeAlertDialog 弹窗材质、UTF-8 BOM 脚本、子模块化、不污染其它 VM）。

## 范围与不在范围内

**在内**：
- 百度专属改动（BaiduApi / BaiduCloudViewModel / BaiduAccountRepository / HttpClients 超时收紧 / DownloadManager.currentShareUrl）
- 139 JcefLoginPane anyOfKeys 参数
- 新文件 ClipboardLinkPopup.kt / CookieCleaner.kt / LinkHistoryDialog.kt
- DB 层新增 link_history 表 + download_task.shareUrl 字段
- 六个 AccountRepository.logout* 接入 CookieCleaner
- DownloadScreen 右键菜单与「文件夹」按钮
- ShareDetailScreen + ResolveScreen 入口按钮

**不在内**（fork 有但用户未列）：
- WebDAV 备份（WebDavBackupManager / 定时备份协程 / SettingsRepository.webdav*）
- 自动更新检查（UpdateChecker.Release.htmlUrl / MainScreen 6s 自动检查 / UpdateDialog）
- WindowsAppUserModelId（任务栏 AUMID）
- 各 CloudViewModel（除百度外）的自动重试 refresh()/load() 改动
- DownloadManager 分片大小 4→8MB / chunkCountFor 重写
- 项目改名 fork（标题、AUMID、图标）
- 各网盘页加刷新按钮（BaiduCloudScreen 等）
- OnboardingScreen / SupportScreen / AboutScreen / SettingsScreen 重排

## 实施步骤（按依赖顺序）

### Step 1 — DB 层基础（link_history 表 + download_task.shareUrl 列）

文件：
- [desktop/src/main/kotlin/com/yunx/app/data/db/Entities.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/db/Entities.kt)
  - `DownloadTaskEntity` 加 `shareUrl: String = ""` 字段（在 `platform` 与 `avgSpeed` 之间）
  - 新增 `LinkHistoryEntity(id, url, title, platform, pwd, createTime)` data class
- [desktop/src/main/kotlin/com/yunx/app/data/db/Daos.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/db/Daos.kt)
  - 新增 `LinkHistoryDao` 接口：`observeAll / search / insert / delete / clear`
- [desktop/src/main/kotlin/com/yunx/app/data/db/AppDatabase.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/db/AppDatabase.kt)
  - DDL：`download_task` CREATE 语句补 `shareUrl TEXT NOT NULL DEFAULT ''` 列
  - DDL：新增 `link_history` 表 CREATE 语句
  - `open()` 中 DDL 执行后追加迁移：`ALTER TABLE download_task ADD COLUMN shareUrl TEXT NOT NULL DEFAULT ''`（包 `runCatching`，旧库已存在则忽略）
  - `JdbcDownloadTaskDao`：`insert` SQL 加 `shareUrl` 列与参数；`toEntity` 读取 `shareUrl`（用 `runCatching` 兜底旧库缺列）
  - 新增 `JdbcLinkHistoryDao` 类（仿 `JdbcBookmarkDao` 用 `MutableStateFlow` 缓存 + `synchronized(conn)`）

### Step 2 — CookieCleaner 工具（CEF delete 标志）

新文件：[desktop/src/main/kotlin/com/yunx/app/util/CookieCleaner.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/util/CookieCleaner.kt)
- `object CookieCleaner.clearCookiesForDomains(domains: List<String>)`
- 方案 1：`CefCookieManager.visitAllCookies` + `BoolRef delete.set(true)` 删除命中域名
- 方案 2（兜底）：`manager.deleteCookies("https://$d", null)` + http 版本
- JCEF 未初始化时 `runCatching` 静默忽略
- 不超过 3 秒等待（`Object#wait/notifyAll`）

### Step 3 — AccountRepository.logout* 接入 CookieCleaner

文件（每家 `logout*` 方法首行加 `CookieCleaner.clearCookiesForDomains(...)`）：
- [QuarkAccountRepository.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/repository/QuarkAccountRepository.kt) → `listOf("pan.quark.cn", "quark.cn")`
- [UCAccountRepository.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/repository/UCAccountRepository.kt) → `listOf("drive.uc.cn", "uc.cn")`
- [XunleiAccountRepository.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/repository/XunleiAccountRepository.kt) → `listOf("pan.xunlei.com", "xunlei.com")`
- [BaiduAccountRepository.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/repository/BaiduAccountRepository.kt) → `listOf("pan.baidu.com", "yun.baidu.com", "baidu.com")`
- [C139AccountRepository.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/repository/C139AccountRepository.kt) → `listOf("yun.139.com", "139.com")`
- [Pan123AccountRepository.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/repository/Pan123AccountRepository.kt) → `listOf("www.123pan.com", "123pan.com")`

BaiduAccountRepository 额外：`logoutBaidu` 调 `api.clearSessionCache()`，`saveBaiduAccount` 在校验 Cookie 通过后调 `api.clearSessionCache()`（避免旧 bdstoken 跨账号复用导致 errno=-6）。

### Step 4 — 百度网盘 API 连环修复

文件：[desktop/src/main/kotlin/com/yunx/app/data/network/BaiduApi.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/network/BaiduApi.kt)
- 新增 `suspend fun refreshBdstoken(cookie): String?`（清缓存 + 重新拉取）
- 新增 `fun clearSessionCache()`（清缓存 bdstoken）
- `listCloudFiles(dir, cookie)` 重写：首次用缓存 bdstoken → errno=-6 强制刷新重试一次 → 其它 errno 抛 `IllegalStateException("百度网盘接口错误 (errno=$errno)")`（不静默吞掉，避免误判为空目录）
- 抽出私有 `requestListCloudFiles(dir, cookie, bdstoken): Pair<JSONArray, Int>`，URL 改 `pan.baidu.com/api/list`，补 `bdstoken` / `channel=chunlei` / `showempty=0` 参数，UA 改 `UA_WEB`，Referer 改 `pan.baidu.com/disk/main`，补 `Accept` / `Accept-Language`
- 所有 `yun.baidu.com` → `pan.baidu.com`；所有 `UA_NETDISK` → `UA_WEB`；所有 `Referer: yun.baidu.com/disk/main` → `pan.baidu.com/disk/main`
- `executeJson(request)` 改 `suspend`：errno=8888（限流）自动重试 3 次（间隔 2 秒），重试时用 `Request.Builder` 重建请求（不复用已消费实例），重试时补 `Accept` / `Accept-Language` 头；最终仍 8888 抛 `BaiduApiException("百度接口限流，请稍后再试（errno=8888）")`

### Step 5 — HttpClients 超时收紧

文件：[desktop/src/main/kotlin/com/yunx/app/data/network/HttpClients.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/network/HttpClients.kt)
- `buildApi()`：`readTimeout` 60→30 秒，新增 `callTimeout(45, SECONDS)`
- `buildDownload()`：`maxIdleConnections` 64→128

### Step 6 — 百度 CloudViewModel 加固

文件：[desktop/src/main/kotlin/com/yunx/app/ui/viewmodel/BaiduCloudViewModel.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/viewmodel/BaiduCloudViewModel.kt)
- `downloadHeaders(cookie)`：`UA_NETDISK` → `UA_WEB`（两处：downloadHeaders 与另一个 download 链接构造处）
- `refresh()`：不再 `if (current !is Loaded) loadRoot(); return`，改为 `val dirPath = (current as? Loaded)?.dirPath ?: "/"` + `val pathNames = (current as? Loaded)?.pathNames ?: emptyList()`，让 Error 态也能下拉刷新当前路径
- `load(dirPath, pathNames)`：try-catch 改 `repeat(2) { attempt -> try { ... return@launch } catch (e) { lastError = e; if (attempt < 1) delay(500) } }`，最后置 `Error(lastError?.message ?: "加载失败")`

### Step 7 — 139 JcefLoginPane anyOfKeys 兜底

文件：[desktop/src/main/kotlin/com/yunx/app/ui/jcef/JcefLoginPane.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/jcef/JcefLoginPane.kt)
- `JcefLoginPane(...)` 签名末尾加 `anyOfKeys: List<String> = emptyList()` 参数，补 KDoc 说明
- 轮询协程调用改为 `collectCookies(domains, requiredKeys, anyOfKeys)`
- `collectCookies(domains, requiredKeys, anyOfKeys)` 签名加默认参数；判定改为 `val requiredOk = requiredKeys.all { names.contains(it) }; val anyOk = anyOfKeys.isNotEmpty() && anyOfKeys.any { names.contains(it) }; if (!requiredOk && !anyOk) return null`

文件：[desktop/src/main/kotlin/com/yunx/app/ui/login/C139LoginScreen.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/login/C139LoginScreen.kt)
- `JcefLoginPane(...)` 调用处加 `anyOfKeys = listOf("authorization")`，补 KDoc 解释网页版只下发 authorization

### Step 8 — DownloadManager.currentShareUrl + enqueue shareUrl

文件：[desktop/src/main/kotlin/com/yunx/app/data/download/DownloadManager.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/data/download/DownloadManager.kt)
- 加 `@Volatile var currentShareUrl: String = ""`（任务入队时自动填充用）
- `enqueue(...)` 加 `shareUrl: String = ""` 参数；`val effectiveShareUrl = shareUrl.ifBlank { currentShareUrl }`；`DownloadTaskEntity(..., shareUrl = effectiveShareUrl)`；Log 加 `shareUrl=$effectiveShareUrl`

### Step 9 — ResolveViewModel 链接历史记录

文件：[desktop/src/main/kotlin/com/yunx/app/ui/viewmodel/ResolveViewModel.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/viewmodel/ResolveViewModel.kt)
- `startResolve(link, pwd)` 首行加 `downloadManager.currentShareUrl = link`（供入队时填充 task.shareUrl）
- 成功解析分支 `onSuccess { s -> ... }` 中加 `recordLinkHistory(s.title)`
- 新增 `private suspend fun recordLinkHistory(title)`：同 url 最近 1 小时去重，失败不影响解析

### Step 10 — LinkHistoryDialog 组件

新文件：[desktop/src/main/kotlin/com/yunx/app/ui/resolve/LinkHistoryDialog.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/resolve/LinkHistoryDialog.kt)
- `@Composable fun LinkHistoryDialog(visible, onDismiss, onSelect: (url, pwd) -> Unit)`
- 内部 `OutlinedTextField` 搜索 + `LazyColumn` 列表 + 单条删除 + 清空
- 用 `FadeAlertDialog`（不用 AlertDialog）—— 遵循主仓库弹窗规范
- 入口接入：
  - [ResolveScreen.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/screens/ResolveScreen.kt) 解析输入页加 History IconButton + 弹窗挂载（`onSelect = { url, pwd -> viewModel.startResolve(url, pwd.ifBlank { null }) }`）
  - [ShareDetailScreen.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/resolve/ShareDetailScreen.kt) 顶栏加 History IconButton + 弹窗挂载

### Step 11 — ClipboardLinkPopup + Controller + Detector

新文件：[desktop/src/main/kotlin/com/yunx/app/ui/clipboard/ClipboardLinkPopup.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/clipboard/ClipboardLinkPopup.kt)
- `data class ClipboardDetection(text, parsed)`
- `object ClipboardLinkController`：`pending` / `openRequest` / `mainWindow` 状态，`show / dismiss / open / consumeOpen / platformName` 方法
- `@Composable fun ClipboardLinkDetector()`：`LaunchedEffect(Unit)` 每秒轮询剪贴板；条件「文本变化 + ShareLinkParser.parse 命中 + 主窗口失焦 + 无弹窗在展示」时 `controller.show(...)`
- `@Composable fun ClipboardLinkPopup()`：独立 `Window(undecorated, alwaysOnTop, focusable=false)` 右下角卡片，10s 自动消失
  - **设计偏离 FadeAlertDialog 规范的说明**：FadeAlertDialog 是窗口内覆盖层，主窗口失焦时不可见；本弹窗的核心场景就是主窗口失焦时也能看到，因此必须用独立顶层 Window。Window 设 `focusable=false` + `isAutoRequestFocus=false` + `type=POPUP`，不抢焦点不阻塞 UI 线程，不触发 ripple 动画卡顿（与 Popup/AlertDialog 不同）

接入点：
- [Main.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/Main.kt) `SideEffect { mainWindow = window as? java.awt.Frame }` 行加 `ClipboardLinkController.mainWindow = mainWindow`
- [MainScreen.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/MainScreen.kt) 全屏覆盖层 `return` 之前加 `ClipboardLinkDetector()` + `ClipboardLinkPopup()`；之后加 `LaunchedEffect(clipboardOpenRequest)` 消费 open 请求 → `currentTab = MainTab.Resolve` + `resolveViewModel.startResolve(d.text, d.parsed.pwd)` + 主窗口 `toFront()`

### Step 12 — DownloadScreen 右键菜单 + 「文件夹」按钮

文件：[desktop/src/main/kotlin/com/yunx/app/ui/screens/DownloadScreen.kt](file:///e:/桌面/YunXPC/desktop/src/main/kotlin/com/yunx/app/ui/screens/DownloadScreen.kt)
- 删除 `combinedClickable` import，删除 `AlertDialog` import
- 加 imports：`pointerInput` / `PointerEventType` / `isSecondaryPressed` / `FadeAlertDialog` / `Folder` icon
- `DownloadSubTaskRow`（line ~542）：
  - `combinedClickable(onClick={}, onLongClick={showMenu=true})` → `clickable(onClick={}).onRightClick { showMenu = true }`
  - `STATUS_COMPLETED -> IconButton(...)` → `STATUS_COMPLETED -> Row { 文件夹 IconButton(revealFile) + 打开 IconButton(openSavedFile) }`
  - `if (showMenu) { AlertDialog(...) }` → `TaskContextMenu(visible, title=displayName, shareUrl=task.shareUrl, directUrl=task.url, onDismiss, onRedownload, onRemove)`
- `DownloadTaskCard`（line ~723）：同样改造
- 新增私有 `@Composable fun TaskContextMenu(visible, title, shareUrl, directUrl, onDismiss, onRedownload, onRemove)`：`FadeAlertDialog` + 4 个 `ContextMenuRow`（复制分享链接 / 复制直链 / 重新下载 / 删除任务）
- 新增私有 `@Composable fun ContextMenuRow(icon, label, tint, onClick)`
- 新增私有 `Modifier.onRightClick(onRightClick)`：`pointerInput(Unit) { awaitPointerEventScope { while (true) { val event = awaitPointerEvent(); if (event.type == Press && event.buttons.isSecondaryPressed) onRightClick() } } }`

## 关键优化点（相比 fork 原版）

1. **BaiduApi.executeJson 重试上限**：fork 写 `repeat(4)`（1+3 重试）但注释说「1 次首试 + 最多 3 次重试」与「最多重试 5 次」前后矛盾。统一为「1 次首试 + 最多 3 次重试 = 4 次总尝试」，对齐注释。
2. **ClipboardLinkPopup KDoc**：明确写明偏离 FadeAlertDialog 规范的理由（主窗口失焦时需可见），方便后人维护。
3. **LinkHistoryDialog 用 FadeAlertDialog**：fork 已用，移植时保持。
4. **不污染其它 VM**：fork 给六个 CloudViewModel 都加了 refresh/load 自动重试，但用户只列「百度网盘」，仅改 BaiduCloudViewModel。
5. **BaiduAccountRepository.saveBaiduAccount 的 clearSessionCache 时机**：放在 `isValidCookie` 通过之后，避免无效 Cookie 也清缓存。
6. **不引入 GlobalScope**：fork 在 Main.kt 用 `GlobalScope.launch` 启动定时备份；本计划不引入定时备份，故不需要 GlobalScope。

## 验证

构建：
```
.\run.ps1 build
```
若编译通过，启动：
```
.\run.ps1 run
```

端到端验证清单：
1. **百度网盘**：登录百度 → 浏览个人网盘根目录 → 应能加载（无 errno=-6 / errno=8888 卡死）；故意删除 BDUSS Cookie 后刷新 → 应进入 Error 态可重试（不是空目录）
2. **139 登录**：JCEF 打开 yun.139.com 登录 → 仅下发 authorization 时「保存登录」按钮应出现
3. **登出清 Cookie**：登录夸克 → 登出 → 再次打开登录页应需要重新登录（不被 JCEF 残留 Cookie 自动登录）
4. **链接历史**：解析任意分享 → 打开解析页 History IconButton → 应看到刚解析的记录；搜索 / 删除 / 清空均生效；选择历史项应重新解析
5. **剪贴板弹窗**：主窗口最小化 / 切到其它窗口 → 复制一条网盘分享链接 → 右下角应弹出卡片 10s 自动消失；点「打开」应切回解析页自动解析
6. **下载完成文件夹按钮**：完成一项下载 → 完成态应有「文件夹」+「打开」两个按钮；点文件夹应打开资源管理器并选中文件
7. **右键菜单**：在下载任务上右键 → 应弹 FadeAlertDialog 风格菜单（非 AlertDialog 原生窗口）；点「复制分享链接」应复制 task.shareUrl；点「复制直链」应复制 task.url
8. **DB 迁移**：用旧 yunx.db 启动 → 应自动 ALTER TABLE 加 shareUrl 列；旧任务读出来 shareUrl 应为空串
