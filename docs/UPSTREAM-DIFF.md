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

本轮覆盖上游 `561dd10`(#138) / `989a6d7`(#142)；`6265668`(#141) 的 Gopeed 引擎另见 §9.6（桌面以 exe 子进程形态重做）。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `989a6d7`(#142) | 应用内公告系统（远程列表 / 启动弹窗 / 未读角标） | **数据层**：`data/announcement/AnnouncementApi`（列表 / 详情两接口；成功与否看响应体 `success`；不轮询；详情按 id 缓存）、`AnnouncementReadStore`（已读口径完全本地维护，打开详情 / 关弹窗都算已读）、`AnnouncementTime`（ISO 8601 → 本地时间，逐行一致）。**桌面适配**：日志 `android.util.Log` → 项目自带 `Log`；`SharedPreferences` → `java.util.prefs`（节点 `yunx/announcement`，仍存 JSON 数组、最多 500 条）；`User-Agent` 改 `YunX-Desktop`；按 `API-PC.md` **每个请求携带 `X-Client-Platform: desktop`**（服务端据此只返回「发电脑端」的公告；缺省 / 未知值一律回落手机端，会静默拿到手机端公告）；上游 `network_security_config.xml` 的明文放行在桌面 JVM 下不需要（去掉了对应注释与依赖）。**ViewModel**：`AnnouncementViewModel`（列表唯一真源 / 详情会话内缓存 / 未读数 / 启动弹窗候选口径与上游一致）；`Factory` 适配桌面 lifecycle 的 `create(KClass, CreationExtras)` 签名；`SnackbarController` 与上游同名同语义。**图片**：新增 `RemoteImageLoader`（Skia `Image.makeFromEncoded` 解码 + 128 张 LRU + `Semaphore(4)` + 进行中去重 + GitHub 镜像；SVG 直接跳过，与上游 BitmapFactory 取舍一致）与 `RemoteImage`（`ImageBitmap` 版，`autoHeight` 自算宽高比占位；底框宽度收窄到实际绘制宽度、缩小后靠左；弹窗封面与详情图片均设高度上限），以及全窗口看图组件 `ImageViewerOverlay`（详情页封面 / 正文图集单击放大；滚轮以鼠标为锚点缩放、拖拽平移、双击复位、Esc 或单击图片外关闭）。正文图集多图时按统一行高等高横向排布、放不下自动换行（`FlowRow` + `RemoteImage.fixedHeight`），单图沿用自适应高度。**UI**：`AnnouncementScreen`（列表 ↔ 详情，`AnimatedContent` 淡入淡出）、`AnnouncementListPage`（首屏 / 错误 / 空态；刷新走顶栏按钮）、`AnnouncementDetailPage`（正文复用 GFM 渲染器 + 自研 `GitHubMarkdownImageTransformer`）、`AnnouncementPopupDialog`（**窗口内 `FadeAlertDialog`**，非 material3 AlertDialog）、`AnnouncementUnreadBadge`。**MainScreen**：顶栏 `Campaign` 图标（所有 Tab 可见）+ 未读角标（画在 `IconButton` 外层 48dp Box，避免被 IconButton 的圆形裁剪切掉）+ 公告覆盖页 + 启动弹窗。 |
| `989a6d7`(#142) 桌面取舍 | 共享元素过渡 / 下拉刷新 | **未移植共享元素**：上游用 `SharedTransitionLayout` + `sharedBounds` 做「列表项长成整页 / 图标长成整页」；桌面 `MainScreen` 没有共享元素基础设施（全部叠加页统一 `AnimatedVisibility` 淡入淡出），本页随全站约定改用 `AnimatedContent` 淡入淡出，不额外引入共享元素作用域。**未移植下拉刷新**：桌面无该交互，刷新改为顶栏按钮（`PullToRefreshBox` 亦为 AndroidX 依赖）。**图片缓存不共用**：上游把 Markdown 内嵌图与普通图片合到同一个 `RemoteImageLoader`；桌面 `GitHubMarkdownImageTransformer` 另有 SVG 光栅化 / `<text>` 补绘 / HTML 尺寸标记等渲染器适配，拆分会牵动既有 README 渲染链路，故二者各自持 LRU（同图跨场景重复加载极少）。 |

#### 跳过

| 上游提交/内容 | 原因 |
| --- | --- |
| `561dd10`(#138) README contributors | 上游仓库 README 专属章节；桌面 README 独立维护。 |
| `6265668`(#141) `UpdateChecker` 仓库参数化 / Asset size·digest | 为 Gopeed 内核仓库（`CYQawa/yunx_gopeed_build`）服务；桌面内核取自 Gopeed 官方 Release，改为在 `GopeedKernelProvisioner` 内部自行解析（含 `digest` 校验），不动既有的应用更新检查链路。 |

### 9.6 本次对齐（上游 `6265668` 的 Gopeed 引擎，桌面重做）

上游 `6265668`(#141)「内置 Gopeed 下载引擎」在桌面**以完全不同的形态落地**：上游把 gomobile 编译的
`libgojni.so` 用 `System.load` 装进本进程、配 `apiEnable=false` 走进程内 `rest.Dispatch`；
桌面没有 Windows 内核库（内核仓库只有 4 个 Android ABI 的 AAR，无 exe/dll），改为把 Gopeed 官方
**无界面 web/服务端版 `gopeed.exe` 作为子进程**拉起，走它的本地 HTTP API。

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `6265668`(#141) | 内置 Gopeed 下载引擎（双下载器并存 + 内核获取） | **引擎进程** `data/gopeed/GopeedEngine.kt`：内核为 `gopeed.exe`，落 `<dataDir>/gopeed/{bin,storage,tmp}`；以 `-A 127.0.0.1 -P <随机空闲端口> -d <storage> --temp-dir <tmp>` 启动，**不设 `-p` 密码 ⇒ 服务端不启用 Web 鉴权、无需 apiToken**；JDK 17 在 Windows 默认以 `CREATE_NO_WINDOW` 创建子进程（仅显式继承 stdio 时才清除），**不会冒出控制台黑窗**，stdout/stderr 重定向到 `gopeed.log`；启动后轮询 `/api/v1/info` 确认就绪（上限 20s）；pid 落文件，启动时按「pid 存活且可执行路径就是本应用内核」精确清理上次强杀留下的孤儿进程（避免它占着 bolt 存储锁）；注册 JVM 关闭钩子，退出应用时停引擎。**API 客户端**：专用 OkHttp 客户端 **`Proxy.NO_PROXY`**（否则用户配了代理时本地请求会被代理走），信封 `{code,msg,data}`，`code != 0` 取 `msg` 抛错；接口与上游一致：`GET /api/v1/info`、`POST /api/v1/tasks`、`GET /api/v1/tasks/{id}/status`、`PUT .../pause`、`PUT .../continue`、`DELETE /api/v1/tasks/{id}`；建任务体 `{"req":{"url","extra":{"header":{…}},"labels":{"yunxTaskId":…}},"opts":{"path","name","extra":{"connections":N}}}`，**`data` 是任务 ID 字符串**（与查询类接口的对象不同）。**内核获取** `GopeedKernelProvisioner`：从 **Gopeed 官方 Release** 取 `gopeed-web-<tag>-windows-{amd64,arm64}.zip`（按 `os.arch` 映射），下载走 `HttpClients.downloadClient`（跟随代理）、镜像前缀复用「GitHub 下载镜像」设置且失败自动回退直连、按 Release 的 `digest` 校验 sha256（长度非 64 则跳过）后解包导入，结束删除临时包；也支持导入本地 `.zip` / `.exe`。**DB/设置**：`download_task` 增 `engineTaskId`（DDL + ALTER 迁移 + `readTask` 容错 + `insert` 16 占位符）、`DownloadTaskDao` 增 `updateEngineTaskId` / `listSyncableEngineTasks`；设置增 `download_engine`（`builtin` 默认 / `gopeed`）。**下载管理** `DownloadManager`：`enqueue` 末尾统一分流（**平台非 GitHub** + 选了 Gopeed + 内核已导入才走引擎，GitHub 因引擎无法镜像回退仍走内置）；`engineTaskId` 非空的任务其 `start`/`pause`/`remove` 分别转发 `continue`/`pause`/`delete`（绝不落到内置下载器重复下载）；1s 轮询 `/status` 回写进度与 `_stats`（`ready/running/wait` 视为下载中、`done` 完成并写平均速度与清理回调、`error` 置失败、`pause` 置暂停），保活与 Windows 通知沿用既有 `onTaskStarted/onTaskFinished`/`notifyProgress`（引用计数保证恰好配对）；落盘目录沿用设置里的自定义下载目录或系统「下载」目录，路径经 `DownloadPathPolicy.sanitize` 净化（防穿越/非法字符）。**UI** `ui/screens/DownloadEngineScreen.kt`：设置 → 「下载引擎」二级页，展示引擎状态 / 内核体积与核心版本 / 落盘目录，提供「从官方下载内核」「导入本地内核」「重启引擎」「更换内核」「删除内核」与引擎切换（切到 Gopeed 前要求内核已导入）；内核下载进度用**窗口内 `FadeAlertDialog`**（按项目约定，且刻意不可取消）；失败原因用 `SelectionContainer` 原文可复制。 |
| `6265668`(#141) 桌面差异 | Android 专属能力 | **去掉**：AAR 导入与 `System.load`、SAF tree Uri 反解、存储权限三态与「所有文件访问」引导、前台服务保活（桌面由 `WindowsKeepAwake` + 通知中心承担）、`DownloadEngineScreen` 里的「更新内核」入口（需先删后导）。**差异**：引擎不支持的「下载限速 / 失败重试 / 最大同时下载任务数 / GitHub 镜像回退」在桌面仍保留设置项（内置下载器继续使用），已在引擎页与设置项描述里注明「引擎不支持」；引擎内核版本号在官方包名里（`tag`），运行中经 `/api/v1/info` 读取展示。 |

#### 验证

- `gradle :desktop:compileKotlin` 通过。
- REST 契约（路由表 / 建任务请求体 / 状态字段 / `data` 为字符串）逐条核对 Gopeed 官方源码
  （`pkg/api/service.go`、`pkg/rest/server.go`、`cmd/web/flags.go`）确认。
- **未做端到端真机验证**：内核下载与引擎进程运行需联网拉取 ~40MB 官方包，尚未实跑；
  首次使用建议按「从官方下载内核」→ 切换引擎 → 下载一个小文件 → 暂停/继续/删除 全流程自测。

### 9.7 本次对齐（上游 `4cfb850`/`8dd441e`/`a4d5a7e`/`040584a`）

#### 已移植

| 上游提交 | 内容 | 桌面实现与取舍 |
| --- | --- | --- |
| `a4d5a7e`(#145) | 新增三个网盘（光鸭云盘 / 蓝奏云优享版 / 蓝奏云） | **网络层**：`GuangYaApi`/`ILanzouApi`/`LanzouApi` + 各自 Constants；蓝奏的 `acw_sc__v2` 人机校验上游本就是纯 Kotlin，桌面逐字节移植到 `LanzouCrypto`（未引入 JS 引擎等新依赖）。**数据层**：三张账号表 + 实体 + DAO + `SecureAccountDaos` AES-GCM 包装（光鸭 4 个凭证字段、优享 appToken/password、蓝奏 cookie）。**认证备份**：三平台纳入导出/导入（**桌面补上游遗漏** —— 上游 #145 未改 `AuthBackupManager`，不补会让「备份→恢复」丢这三个账号）。**解析识别**：`SharePlatform` 增 3 个平台；光鸭 `guangyapan.com/s/{id}`；蓝奏云优享版 `ilanzou.*`；蓝奏云域名族 `lanzou*`/`lan[zs]o[ux]`，**前置边界 `(?:^|[/.])` 不可省**，否则 `www.ilanzou.com` 里的 `lanzou.com` 会被误判成蓝奏云（优享版判断必须先于蓝奏云）。**接入**：`ResolveViewModel`（凭证/仓库/默认目录 `""`/平台名/游客下载/下载请求头/转存分支）、`DriveQuotaViewModel`（光鸭需 accessToken+设备标识、优享需 appToken+uuid；**蓝奏官方无配额接口**）、`DriveScreen` 卡片与路由 8/9/10、`MainScreen` 装配与登录页、关于页/收藏标签/设置页线程项/引导页。**登录**：三平台均为纯 HTTP 账号密码登录（光鸭另含短信验证码），**无需内嵌浏览器**。**UI 形态**：云盘页/账号弹窗/登录页按桌面既有 Pan115 形态重写，不照搬 Android UI。 |
| `4cfb850`(#143) | 公告图集（封面并入图集 / 全屏看图 / 刷新同步详情缓存） | **已移植**：`refresh()` 作废 `detailCache` 并 `reloadCurrentDetail()`（保留旧内容重拉、失败只弹 Snackbar 不闪加载态）、详情页右上角刷新按钮、封面与 `images` 去重（同一张图不再渲染两次）。**未移植**：把封面挪到正文下方组成横向缩略图条 —— 桌面此前已按用户要求实现「正文图集等高横向排布、放不下换行」+ 全窗口看图组件（`ImageViewerOverlay`，滚轮以鼠标为锚点缩放/拖拽/双击复位），上游方案依赖 `SharedTransitionLayout` 共享元素（桌面无此基础设施），改动会退回用户已确认的交互，故保持现状。 |
| `8dd441e`(#144) | 凭证失钥自愈（Android Keystore keyblob 作废） | **已移植**：`GitHubTokenStore.getToken()` 解密失败时清掉解不开的密文（否则每次读取都失败、UI 无法如实显示「未配置」）。**桌面已有**：下载请求头 `loadPersistedHeaders` 失败即清空该条并自愈；各账号 DAO 走 `SecureAccountDaos.decryptOrClear`（失败清该条 + 退回未登录）。**未移植**：Keystore 专项逻辑（`CredentialKeyException{PermanentlyInvalid,Unavailable}`、删坏条目重建密钥、失钥标记弹窗、`shared` 单例）——桌面用本地密钥文件 `FileCredentialCipher`，不存在「设备凭证变更导致 keyblob 永久失效」这一失效形态，失配时退回重登已足够。 |
| `040584a`(#146) | 迅雷/123 登录方式重构（三入口 / 两通道） | **已移植**：① 123 新增 `Pan123DeviceId`（桌面用 `AppContext.miscPrefs` 持久化稳定 UUID，**跨启动不变**，否则被服务端当新设备）与 `passwordLogin`（`user.123pan.cn/api/user/sign_in`，**成功判定 `code==200`**，账号 trim / **密码不 trim**，带 `platform: web`/`app-version: 132`/`loginuuid`/`Origin`/`Referer`）+ `Pan123LoginSupport`（频率/风控/冻结三类文案，**绝不复述服务端原文** —— 响应可能回显账号密码）。② 迅雷短信提为**一等入口**（不再只在密码登录触发风控后出现），两条流程共用 `sendSms`/`loginWithSms`，重发冷却 60s 记在 ViewModel 墙上时钟；进登录页 `resetLoginStep()`。③ 迅雷**网页登录**（`XunleiWebLoginScreen`，内嵌 JCEF 打开 `pan.xunlei.com` + 手动粘贴兜底）：新增 `XunleiWebCredential`（`parse`/`parseRawToken`/`fieldsFrom`/`isValidToken`/`isTrustedUrl` 纯函数 + Cookie 桥脚本）。**桌面关键差异**：上游从 WebView 读 localStorage，而桌面 JCEF 无 `executeJavaScript` 返回值 —— 改为**定期注入 JS 把 localStorage 凭据分片写进 Cookie**（`yunx_xl_cred_*`，规避 4KB 上限），再从 `CefCookieManager` 读回解析；读到的文本必须再过 `GET /drive/v1/about`（`verifyAccessToken`）确认可用才落库。④ **网页 token 与 App token 是两套 OAuth 客户端**（App `Xp6vsxz_7IYVw2BB` + secret + 表单刷新；网页 `Xqp0kJBXWhwaTpB6` 无 secret + JSON 刷新 + `X-Client-Id`），落库记 `authType='webToken'`，`XunleiApi.refreshToken(authType)` 分支，认证备份带上该字段（旧备份缺省=App 通道）。⑤ 上游两个 bug：端口白名单（`Uri.getPort()` 未写端口时返回 -1，`!= 443` 的写法会拦掉所有跳转）桌面无同类代码；剥 `Bearer ` 前缀「先 trim 再匹配」已按正确写法实现。**DB**：`xunlei_account` 增 `authType`（DDL + `ALTER TABLE` 迁移 + 单行表 columns/read/bind 三处同步；**该字段不加密**，它只是判别标记）。**未移植**：123 的网页登录（桌面原本只有账号密码表单；123 的 authorToken 只在 localStorage、不在 Cookie，JCEF 无法低成本读取）。 |
| `4cfb850`(#143) 桌面差异 | 图集交互 | 见上：桌面保留自己的图集与看图方案（用户已确认）。 |
| `040584a`(#146) 桌面差异 | 登录页弹窗 | 登录页是 MainScreen 的**早返回全屏覆盖层**，此时根部 `OverlayDialogHost()` 不参与组合，注册式 `FadeAlertDialog` 不会渲染；网页登录页的「手动粘贴」弹窗因此就地用「零原生窗口」画法（遮罩 + Surface 卡片 + 淡入淡出），仍然不用 material3 `AlertDialog`/`Popup`。 |

#### 验证

- `gradle :desktop:compileKotlin` 通过。
- **未做端到端真机验证**：三个新网盘需真实账号；迅雷网页登录的 JCEF 注入时序、`authType=webToken` 刷新分支、凭据 Cookie 分片拼回均需真机自测。


