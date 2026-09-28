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
| 数据目录 | 应用私有目录 | `%USERPROFILE%\.yunx-pc\`：`yunx.db`、`credential.key`、`cache/download_tmp`、`files/yunx-pc.log` |
| 认证备份 | 有 | 同思路：PBKDF2 派生密钥 + AES-GCM 加密 Cookie / JWT，导出 `.yunx` 备份文件，可跨设备恢复 |

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
