# 与上游的差异（Windows 桌面版 vs Android 版）

- 上游（Android）：[CYQawa/YunX](https://github.com/CYQawa/YunX)
- 本仓库（Windows 桌面移植）：[tidain/YunX-Desktop](https://github.com/tidain/YunX-Desktop)

**沿用上游的部分**：网盘分享链接的解析协议、下载引擎（Range 分片并发、弹性区按字节顺序分配、
断点续传、失败重试、全局限速）、界面框架与主题体系（Compose + Material 3、深色模式 + 种子色）、
各网盘 API 的协议实现。

下面只列桌面版**新增、重做或移除**的内容。

## 1. 登录与账号

| 能力 | 上游（Android） | 桌面版 |
| --- | --- | --- |
| 夸克 / UC / 百度 / 139 登录 | 内嵌 WebView 提取 Cookie | **内嵌 Chromium（JCEF 132）** 打开真实网页登录，登录完成后自动检测并保存 Cookie |
| 已有浏览器会话 | 无 | **一键导入本机浏览器 Cookie**：扫描 Chrome / Edge / Firefox / 360 / QQ 等，用 Windows DPAPI 解密（Chrome / Edge 较新版本受「应用绑定加密」限制可能读不到，Firefox / 360 等始终可用） |
| 兜底方式 | 手动粘贴 Cookie| 与上游相同       |
| 123 云盘 | 账号密码换 JWT | 同上游（纯 HTTP 表单登录） |
| GitHub Token | Android Keystore 加密存储 | AES-GCM 加密存储，密钥为本地密钥文件 `credential.key`（非 Android Keystore）；仅用于提升 API 限额 |
| 登出 | 清除 WebView 的 Cookie | 通过 CEF 的 `delete` 标志清除该平台全部 Cookie，避免残留 |

## 2. 下载进度与保活

- **进度通知改为 Windows 通知中心**：下载进度显示为 toast 进度条（多任务自动聚合成一条、
  带实时速度与完成度）。
  - 同一条通知**原地刷新**，不会每次刷新都弹横幅或响铃；
  - **送达自检**：发送后对比 Windows 为应用维护的 `LastNotificationAddedTime`，若系统静默丢弃了通知，
    则自动创建带 AppUserModelID 的开始菜单快捷方式兜底（这是未打包桌面应用能发通知的前提条件），
    正常情况下不会改动用户的开始菜单。快捷方式位于开始菜单文件夹
    `%APPDATA%\Microsoft\Windows\Start Menu\Programs\`，且无法保证 100% 送达（取决于系统通知设置）。
- **保活语义变化**：「锁屏后保持下载」→ **「下载时阻止电脑休眠」**，通过
  `SetThreadExecutionState` 实现，任务结束自动恢复（偏好键 `keep_awake_downloading`，旧键
  `keep_download_when_locked` 自动迁移）。
- **完成提示**：全部任务结束且确实下载完成时，额外弹一条完成通知。

## 3. 系统托盘与窗口行为

桌面版新增了完整的托盘与窗口生命周期（上游为 Android 通知栏 / 前台服务概念）：

- **系统托盘**：AWT `TrayIcon` + 手写 Win32 原生右键菜单（JNA 调 `CreatePopupMenu` +
  `AppendMenuW` + `TrackPopupMenuEx`），中文显示正常、不再出现方块。
- **菜单跟随系统配色**：自带原生桥接层 `native/darkmode.cpp` → `darkmode.dll`，启动时通过
  `uxtheme.dll` 的 `SetPreferredAppMode` 打开暗色支持，每次弹菜单前按当前系统配色重新应用，
  因此系统在深色 / 浅色之间切换时菜单会跟着变。
- **窗口行为**：左键托盘图标恢复主窗口（含从任务栏最小化状态恢复）、最小化到托盘、
  关闭主窗口时按设置询问 / 直接退出 / 最小化到托盘。
- **启动与关闭过渡**：启动闪屏 + 主窗口内容就绪后渐入，关闭时先淡出再销毁，
  避免原生窗口销毁瞬间的白屏闪烁。

## 4. Windows 系统整合

- **原生对话框**：下载目录选择、认证备份的导入 / 导出均为资源管理器同款样式
  （Vista+ 的 COM `IFileDialog`，JNA 直接调 vtable，不依赖 Swing）。
- **下载目录解析**：通过 Known Folder API 解析系统「下载」位置，支持用户重定向过的路径。
- **文件定位**：下载项「文件夹」按钮等价于 `explorer /select,<file>`，直接选中目标文件。
- **剪贴板分享链接检测**：主窗口不在前台时轮询剪贴板，发现网盘分享链接在右下角弹出提示卡片，
  点击即可打开解析（可在设置中关闭）。

## 5. 桌面专属体验

- **弹窗统一为窗口内覆盖层**：所有对话框都在主窗口内以覆盖层渲染，不创建原生窗口，
  避免阻塞 UI 线程、动画卡顿与任务栏多出窗口。
- **解析链接历史**：自动记录解析过的分享链接（1 小时去重），支持搜索、复用提取码、删除与清空。
- **链接收藏**：收藏解析链接并分类管理。
- **右键菜单**：统一为窗口内弹窗材质，并新增「复制分享链接」。
- **渲染后端可选**：默认 `OPENGL`（GPU 渲染，动画流畅且文字清晰），
  可用环境变量 `YUNXPC_RENDER_API=SOFTWARE|DIRECT3D|OPENGL` 切换；
  字体 hinting 会按后端自动调整，保证文字清晰度。
- **百度网盘 API 修复**：域名迁移、`bdstoken` 自动刷新、限流重试、超时收紧等一串修复。

## 6. 数据与存储

| 项 | 上游（Android） | 桌面版 |
| --- | --- | --- |
| 数据库 | Room | 纯 JDBC（`sqlite-jdbc`）直连 SQLite |
| 设置存储 | SharedPreferences | `java.util.prefs`，落在注册表 `HKEY_CURRENT_USER\Software\JavaSoft\Prefs\yunx` |
| 文件存储 | SAF / tree Uri | 普通文件路径（下载目录等） |
| 数据目录 | 应用私有目录 | `%USERPROFILE%\.yunx-desktop\`：`yunx.db`、`credential.key`、`cache/download_tmp`、`files/yunx-desktop.log`（旧版 `.yunx-pc` 首次启动自动迁移） |
| 认证备份 | 有 | 同思路：PBKDF2 派生密钥 + AES-GCM 加密 Cookie / JWT，导出 `.yunx` 备份文件，可跨设备恢复 |
| README / 更新说明图片渲染 | Android `BitmapFactory` 解码（`inSampleSize` 降采样） | GFM Markdown 渲染，图片改用 Skia（skiko）解码，无降采样 |

## 7. 构建与分发

- **技术栈**：Kotlin + Compose Multiplatform（Desktop）+ Gradle，JDK 17。
- **打包链路**：`jlink` 裁出运行时镜像 → `jpackage` 生成 app-image → 自研启动器替换默认启动器；
  安装包由 Inno Setup 压缩成单文件（每用户安装、无需管理员权限）。
- **自研启动器**（`launcher/Launcher.cs`，C# 编译为无控制台窗口的 exe）：
  固定读取 `app\YunX-Desktop.cfg`，因此**改 exe 名字或放在中文路径下都不会失效**；
  同时负责启动闪屏与主窗口的交叉淡入。
- **版本号单一来源**：根目录 `version.txt` —— jpackage 版本、便携包 / 安装程序里的程序版本与
  文件名、应用内「关于页 / 更新检测」全部由它派生，**发版只需改这一个文件**。
- **原生桥接层**：`native/darkmode.cpp` 在构建期编译为 `darkmode.dll`（优先 `cl.exe`，回退 `g++`），
  随便携版 / 安装包一起分发。
- **零手动环境配置**：缺 JDK 自动下载、Gradle 由 wrapper 拉取、缺 Inno Setup 自动静默安装。

## 8. 已移除的上游功能

- 电池优化引导
- 壁纸动态取色（Material You / 壁纸取色）——桌面版改为**用户自选种子色**，界面其余主题体系不变
- 应用图标切换
- APK 更新检测（改为检测 GitHub Releases）
- 崩溃独立进程

## 9. 上游提交对齐记录

- 上一次对齐：上游 `2e8bbb2`（GitHub 解析平台 #112/#114），桌面版提交 `7c80a9c`。
- 第二轮对齐：上游 `d9d17a5`（含 `ba3bfb0`/`835b1cb`/`c4ef992`/`a84118a`/`286446c`/`dbccb09`/`d9d17a5`），桌面版提交 `902035f`。
- **本次对齐：上游 `5d614f6`（含 `b6a251b`/`f381914`/`2761069`/`5d614f6`），跳过 `880f7ca`/`2743dcd`。**

### 9.1 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `ba3bfb0`（#116） | 大文件下载 OOM 修复 | 全进程在飞上限 `MAX_INFLIGHT_CHUNKS = clamp(maxHeap/8/64KB, 8, 512)` + 跨任务共享 `inflightLimiter`（主池/弹性区/失败重试三条路径统一过闸，取代旧的「每任务一个信号量」）；专用分片线程池 `chunkIoDispatcher`（core=max=上限、30s 空闲回收、daemon，不再受 `Dispatchers.IO` 的 `max(64,核数)` 限制）；**慢连接抢占**（看门狗 5s 采样，阈值 `max(12KB/s, 任务均速/2)`，15s/收尾 3s、每片最多 3 次、零退避，断连续传不丢数据）；读缓冲 256KB→64KB；下载客户端排队上限 64、空闲连接池 8×1min。**HTTP/2 桌面已是可开关（默认仅 1.1）**，无需改。桌面堆更大（jpackage 默认 1/4 物理内存，实测约 4GB → 上限夹到 512），OOM 部分按需取舍。 |
| `835b1cb`（#123） | 更新下载应用自定义镜像前缀 + 失败回退直连 | `SettingsScreen` 的「镜像站下载更新包」改用 `SettingsRepository.githubMirrorPrefix`（未配置用内置默认），并把 GitHub 直连 URL 作为 `fallbackUrl` 传入 `DownloadManager.enqueue`；镜像主 URL 探测失败时整任务切直连（与 GitHub 浏览下载同机制）。 |
| `c4ef992`（#126） | 未登录也可查看解析文件列表 | `ResolveViewModel.isGuest` + 6 平台列表接口匿名：百度 `errno=-6` 文案区分、夸克/UC 非 JSON 响应带 HTTP 码、迅雷 token 为空时不写 `Authorization` 并走 `panCallAnonymous`、139 去掉账号前置校验；`startResolve`/`openFolder`/`goBack`/`navigateToLevel` 空凭据照常下传；下载/转存（`fetchDownloadLink`/`downloadFiles`/`startDownload`/`saveToCloud`/`requestSave`）仍要求登录，提示统一为「…需要先登录 X（未登录仅能浏览文件列表）」；`ShareDetailScreen` 顶部新增 `GuestBrowseNotice` 常驻提示条。 |
| `a84118a`（#127） | 修正创建分享有效期错位、统一中性码 | 新增 `model/ShareExpire`（1/2/3/4 中性码 + `daysOrNull`/`baiduPeriod`/`xunleiDays`，未知码 fail-loud）；百度 `BaiduShareResult.expiredType` 可空、ViewModel 用 `baiduPeriod` 转换并优先用服务端回填；139/123/迅雷 API 的中性码回填不再 fail-open 成「永久/30 天」。**桌面差异**：139 与 123 云盘页自带分享弹窗，有效期本就是「天数」语义（`null=永久 / 1 / 7 / 30`）且直接传给对应 API，故这两个 ViewModel 仍收天数、未改签名；只有百度走 `CloudFileSheets` 的中性码，需按上游修正。 |
| `286446c`（#118） | 合并大文件时显示合并进度 | `DownloadStats.mergePercent`（-1=不在合并）；`ChunkDownloader.mergeChunksToStream` 新增 `onProgress`，`finishDownload` 300ms 节流上报；下载页主任务卡/子任务行/文件夹徽标显示「合并中 · n%」。桌面落盘已改为「边合并边写目标文件」，故进度按「已合并字节 / total」计算。**未同步通知栏**：桌面进度走 Windows 通知中心，合并阶段仍在 100% 后短暂显示（未额外改造 toast 文案）。 |
| `d9d17a5`（#129） | 夸克/UC 免登录下载、设置项开关 | 夸克/UC 游客取链 `getGuestShareDownloadLink`（不转存，`__pugs` 随响应捕获进 `DownloadLink.guestCookie`）；`enqueueDownload` 识别游客直链并用游客头（UC：`GUEST_UA`+`Sec-Ch-Ua`；夸克：`API_USER_AGENT`）；`ResolveViewModel.supportsGuestDownload()` 仅夸克/UC 放行，其余平台下载仍要登录；`GuestBrowseNotice` 按平台给不同文案。设置项：新增「接受预发布版更新」开关（`SettingsRepository.acceptPrereleaseUpdate` + `UpdateChecker` 预发布通道与版本后缀比较 + 更新弹窗「预发布」标记）；「自动识别剪贴板」桌面版早有等价开关（设置页「剪贴板分享链接检测」）。 |

### 9.2 跳过（Android 专属或不适用）

| 上游提交/内容 | 原因 |
| --- | --- |
| `d99daaf`(#113)、`43537b2`(#125) | CI：Android 签名 APK 构建与发布，桌面分发走 jpackage/Inno Setup，无关。 |
| `560d3b6`(#117)、`52f939e`(#119)、`75deabd`(#120)、`06125a8`(#124)、`d85ac95`(#128) | Android 专属：图标/自适应图标、引导页、权限检查等，桌面无对应形态。 |
| `dbccb09`(#122) 目录选择器崩溃修复 | 桌面下载目录选择走原生 `WindowsFolderPicker`（非 Android SAF `ActivityResultLauncher`），不存在 `ActivityNotFoundException` 崩溃路径；且仪器测试依赖 AndroidX Compose test，桌面无该依赖。 |
| `d9d17a5`(#129)「网盘更新自动化」 | 桌面更新弹窗只有「安装版(.exe)/便携版(.zip)」资产下载，**没有** Android 版 Release 说明里的「网盘更新」入口，`startUpdateDownload` 在桌面无调用方，故不涉及。 |
| `d9d17a5`(#129)「自动识别剪贴板」开关 | 桌面版早已有等价开关与开关项（「剪贴板分享链接检测」，键 `clipboard_link_detection`）。 |
| `d9d17a5`(#129) 更新说明改纯文本 | 桌面更新弹窗用自研 Markdown 渲染（与 README 共用），保留既有行为。 |
| `ba3bfb0`(#116) `onTrimMemory` 释放空闲连接 / CrashHandler 内存快照 | Android 生命周期回调与崩溃上报形态，桌面无等价入口（内存高压日志已并入下载进度回调）。 |

### 9.3 本次对齐（上游 `5d614f6`）

本轮覆盖上游 `b6a251b`(#130) / `f381914`(#131·#132) / `2761069`(#135) / `5d614f6`(#136)；
`ShareLinkParser` 的 115 链接形态改动随 `f381914` 一并落地。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `f381914`(#131/#132) | 115 网盘支持（登录 / 云盘管理 / 分享 / 转存 / 下载） | **数据层**：新增 `Pan115Api` / `Pan115Constants` / `Pan115Crypto`（后者把上游 `android.util.Base64` 换成 `java.util.Base64`）；**模型**：`ShareInfo` 增 `warning`（非致命提示），`ShareExpire` 增 115 专属档位（101..105 + `PAN115_OPTIONS`/`pan115Duration`/`pan115CodeOf`/`pan115CodeOfText`）；**仓库**：`Pan115AccountRepository`（Cookie 落库，登出走 `CookieCleaner` 清 `115.com`）与 `Pan115ResolveRepository`（`share/snap` 列目录 → 分享直链；大文件退回「转存临时目录 + 电脑端加密取链」，接入桌面 `TransferSpaceGuard` 空间前置校验）；**DB**：`Pan115AccountEntity`/DAO/`SecureAccountDaos`/`AppDatabase`（新建 `pan115_account` 表，Cookie 加密落库）；**登录**：桌面走既有 JCEF 内嵌浏览器（`Pan115LoginScreen`）打开 115.com，检测 `UID`+`SEID` 后按 `Pan115Constants.filterLoginCookies` 只保留会话字段再用 `/user/info` 校验；**下载**：`DownloadPlatform.PAN115` + `enqueueDownload` 用「登录 Cookie + 取链响应 900s CDN Cookie」拼接、`User-Agent` 用客户端串 `CLIENT_UA`、`Referer` 用 115 域（三处缺失任一 CDN 均 403）；**云盘页**：`Pan115CloudViewModel` + `Pan115CloudScreen`/`Pan115SaveSheet`/`Pan115AccountSheet`（结构对齐 123 云盘页，非照搬 Android UI）；**配套**：网盘页 115 卡片 + 配额（`DriveQuotaViewModel` 并发拉 `getQuota`）、收藏/解析平台标签、认证备份导入导出、`BrowserCookieImporter` 域名后缀、关于/引导页平台列表。**未做端到端真机验证**（无 115 账号，登录/转存/直链需用户自测）。 |
| `2761069`(#135) | 网盘创建文件夹 | 桌面按上游覆盖面做全 7 个平台：`Pan115CloudViewModel`/`QuarkCloudViewModel`/`UCCoudViewModel`/`XunleiCloudViewModel`/`BaiduCloudViewModel`/`C139CloudViewModel`/`Pan123CloudViewModel` 各加 `createFolder`；`Pan123Api` 补 `createDir`（复用 `upload_request`，`type=1`）、`C139Api` 补 `createDir`（`hcy/file/create`，`type=folder`）+ 对应 Constants；7 个云盘页标题行加「新建文件夹」入口，共用 `CloudCreateFolderDialog`（**窗口内覆盖层 `FadeAlertDialog`，按项目约定不使用 Popup/material3 AlertDialog**），含名称本地校验。各平台父目录/根目录约定不同（百度绝对路径、迅雷根为空串、其余 `"0"`/`"/"`）。 |
| `5d614f6`(#136) | 夸克免转存下载（分享凭证直取直链）+ 设置项开关 | `QuarkApi.getShareDownloadLinkWithoutSave`（`fids`/`fids_token`/`pwd_id`/`stoken` 交给 `file/download`，Cookie 用账号态）+ `ShareResolveRepository.getShareDownloadLinkWithoutSave`（默认回退 `getShareDownloadLink`，仅夸克覆写）+ `QuarkResolveRepository` 覆写；`ResolveViewModel.resolveShareLink` 收敛「单文件 / 批量」两处登录态取链，开关关掉走老转存流程、夸克个别分享类型失败自动回退转存；设置页「下载」组新增「免转存下载」开关（`SettingsRepository.quarkNoSaveDownload`，默认开），经 `noSaveDownloadProvider` 注入即时生效。**放置判断**：该开关是功能性取链方式选择（默认即正常行为），不是「实验性功能」页那些高风险调参（HTTP/2、读缓冲、慢连接抢占），故放在主设置页「下载」分组，与上游一致。 |
| `b6a251b`(#130) | QQ 群链接 | 上游在引导页/设置页/关于页引入 `AppLinks`。桌面在**关于页**新增「QQ 交流群」卡片（群号 `635207650`）：Windows 无 `mqqapi://` scheme 的可靠保证，故点击**复制群号**并提示，供用户在 QQ 中搜索加群。 |
| `ShareLinkParser`（随 `f381914`） | 115 链接形态 | `SharePlatform` 增 `PAN115`；新增 `115(?:cdn|rc)?\.com/s/(sw…)`、口令形态 `115…com/(sw…)-(码)`、`?password=` 三个正则与解析分支；兼容既有 6 平台形态不被误伤（探针 69 项全过）。 |

#### 跳过

| 上游提交/内容 | 原因 |
| --- | --- |
| `880f7ca`(#133) 版本号 1.2.8 | 仅 Android 版本号变更，桌面版本号单一来源为根 `version.txt`。 |
| `2743dcd`(#131) F-Droid 元数据/截图 | Android 应用商店元数据与截图，桌面无对应分发渠道。 |

### 9.4 本次对齐（上游 `700ce12`）

本轮覆盖上游 `700ce12`(#137)：**迅雷中文口令解析（口令 → 分享链接 + 提取码）**。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `700ce12`(#137) | 迅雷中文口令解析 | **新增纯逻辑对象** `data/network/XunleiKouling.kt`（判定 / 去装饰符 / 拼 jump URL / 解 `location`，语义与上游逐行一致，去掉 AGPL 文件头）。**接入点**：`XunleiApi.parseKouling(keyword)`（用带 Thunder/TBC 标识的 UA 打 shoulei `jump` 接口，`ext.kouling_type=="share_page"` 时取 `location` 里的分享页地址，否则抛「口令无效」）；`XunleiResolveRepository.resolveKouling(keyword)` 包成 `Result`；`ResolveViewModel.startResolve` 在「`ShareLinkParser.parse(link)==null` 且 `XunleiKouling.looksLikeKouling(link)`」时先换链，之后用 `effectiveLink`（真实分享链接）替代原始口令走既有解析/收藏/复制流程。`ResolveScreen` 输入框占位符加「或 迅雷口令」。**判定规则**：`normalize` 去两侧空白与装饰符；`looksLikeKouling` 要求非空、≤64 字、正文只含汉字/字母/数字且至少一个汉字——带空格/标点的整段文案、纯英文/数字、已是分享链接者均不误触。**桌面差异**：无（纯 Kotlin/Regex，不含 Android API），`java.net.URLEncoder` 与上游同。 |

#### 验证

上游 `XunleiKoulingTest.kt` 的 7 个用例全部移植到桌面并实测（`desktop/build/` 下临时探针直接调用桌面版编译产物，
`:desktop:test` 源集历史损坏、未改动）：真实 `location` → `https://pan.xunlei.com/s/VOEs0DLEAfUV9o-JOAqrzgZmA1?pwd=nw45`
且能被 `ShareLinkParser` 二次解析出 `XUNLEI`/shareId/`nw45`；无 `pwd` 保留裸链接；非分享页/空 location 返回 `null`；
中文口令（含 `【】`/`「」`/两侧空白）判定为口令；分享链接、纯英文/数字、含空格、过长文本均判否；负例（夸克/百度/115 链接、
整段带链接文案、普通 URL）均不会误入口令分支（探针 35 项全过）。

#### 跳过

| 上游提交/内容 | 原因 |
| --- | --- |
| 无 | 本次提交内容全部适用，无跳过项。 |

### 9.5 本次对齐（上游 `989a6d7`）

本轮覆盖上游 `561dd10`(#138) / `6265668`(#141) / `989a6d7`(#142)。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `989a6d7`(#142) | 应用内公告系统（远程列表 / 启动弹窗 / 未读角标） | **数据层**：`data/announcement/AnnouncementApi`（列表 / 详情两接口；成功与否看响应体 `success`；不轮询；详情按 id 缓存）、`AnnouncementReadStore`（已读口径完全本地维护，打开详情 / 关弹窗都算已读）、`AnnouncementTime`（ISO 8601 → 本地时间，逐行一致）。**桌面适配**：日志 `android.util.Log` → 项目自带 `Log`；`SharedPreferences` → `java.util.prefs`（节点 `yunx/announcement`，仍存 JSON 数组、最多 500 条）；`User-Agent` 改 `YunX-Desktop`；按 `API-PC.md` **每个请求携带 `X-Client-Platform: desktop`**（服务端据此只返回「发电脑端」的公告；缺省 / 未知值一律回落手机端，会静默拿到手机端公告）；上游 `network_security_config.xml` 的明文放行在桌面 JVM 下不需要（去掉了对应注释与依赖）。**ViewModel**：`AnnouncementViewModel`（列表唯一真源 / 详情会话内缓存 / 未读数 / 启动弹窗候选口径与上游一致）；`Factory` 适配桌面 lifecycle 的 `create(KClass, CreationExtras)` 签名；`SnackbarController` 与上游同名同语义。**图片**：新增 `RemoteImageLoader`（Skia `Image.makeFromEncoded` 解码 + 128 张 LRU + `Semaphore(4)` + 进行中去重 + GitHub 镜像；SVG 直接跳过，与上游 BitmapFactory 取舍一致）与 `RemoteImage`（`ImageBitmap` 版，`autoHeight` 自算宽高比占位；底框宽度收窄到实际绘制宽度、缩小后靠左；弹窗封面与详情图片均设高度上限），以及全窗口看图组件 `ImageViewerOverlay`（详情页封面 / 正文图集单击放大；滚轮以鼠标为锚点缩放、拖拽平移、双击复位、Esc 或单击图片外关闭）。**UI**：`AnnouncementScreen`（列表 ↔ 详情，`AnimatedContent` 淡入淡出）、`AnnouncementListPage`（首屏 / 错误 / 空态；刷新走顶栏按钮）、`AnnouncementDetailPage`（正文复用 GFM 渲染器 + 自研 `GitHubMarkdownImageTransformer`）、`AnnouncementPopupDialog`（**窗口内 `FadeAlertDialog`**，非 material3 AlertDialog）、`AnnouncementUnreadBadge`。**MainScreen**：顶栏 `Campaign` 图标（所有 Tab 可见）+ 未读角标（画在 `IconButton` 外层 48dp Box，避免被 IconButton 的圆形裁剪切掉）+ 公告覆盖页 + 启动弹窗。 |
| `989a6d7`(#142) 桌面取舍 | 共享元素过渡 / 下拉刷新 | **未移植共享元素**：上游用 `SharedTransitionLayout` + `sharedBounds` 做「列表项长成整页 / 图标长成整页」；桌面 `MainScreen` 没有共享元素基础设施（全部叠加页统一 `AnimatedVisibility` 淡入淡出），本页随全站约定改用 `AnimatedContent` 淡入淡出，不额外引入共享元素作用域。**未移植下拉刷新**：桌面无该交互，刷新改为顶栏按钮（`PullToRefreshBox` 亦为 AndroidX 依赖）。**图片缓存不共用**：上游把 Markdown 内嵌图与普通图片合到同一个 `RemoteImageLoader`；桌面 `GitHubMarkdownImageTransformer` 另有 SVG 光栅化 / `<text>` 补绘 / HTML 尺寸标记等渲染器适配，拆分会牵动既有 README 渲染链路，故二者各自持 LRU（同图跨场景重复加载极少）。 |

#### 跳过

| 上游提交/内容 | 原因 |
| --- | --- |
| `561dd10`(#138) README contributors | 上游仓库 README 专属章节；桌面 README 独立维护。 |
| `6265668`(#141) 内置 Gopeed 下载引擎 | Android 专属：依赖 gomobile 编译的 `libgojni.so`（AAR 导入 + `System.load`）、SAF tree Uri 反解真实路径、前台服务保活、`DownloadEngineScreen` 引擎页、`StorageDirs`/`PermissionState`。桌面已有自研分片下载引擎（Range 并发 / 断点续传 / 自适应分片）与原生下载目录选择，无对应形态。 |
| `6265668`(#141) `UpdateChecker` 仓库参数化 / Asset size·digest | 为 Gopeed 内核仓库（`CYQawa/yunx_gopeed_build`）服务；桌面更新检测只针对本项目 Release，无第二仓库需求。 |


