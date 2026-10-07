# YunX Mac 本地精简版

独立运行的 Apple Silicon macOS 应用，复用 YunX 网盘协议和内置下载器。
界面以本仓库 Docker Web 版的黑白灰样式为基准，使用 Compose Desktop 实现。
应用附带 Java 运行环境，不需要 Docker、浏览器服务或另外安装 Java。

## 安装与使用

打开 `YunX-1.0.0.dmg`，将 YunX 拖入 Applications，再启动应用。
本地测试包未做 Developer ID 签名与公证；如果 macOS 阻止启动，可在系统
“隐私与安全性”中允许打开。不要使用来历不明的重新打包版本。

1. 在“账号”中选择网盘，填入 Cookie / Token 等凭证并保存。
2. 保存仅表示本地配置完成；点击“验证”查看有效、无效或暂时无法验证的结果。
3. 在“我的网盘”选择账号浏览文件，或在“分享解析”粘贴链接与提取码。
4. 选择文件加入下载，下载目录默认是用户的 Downloads。

凭证可从已经登录的浏览器开发者工具中获取，填写方式与对应平台接口要求一致。
夸克、UC、百度、139、115、蓝奏使用 Cookie；123、GitHub 使用 Token；
光鸭使用 Token 与设备信息；蓝奏优享使用 App Token 与 UUID；
迅雷使用 Token、设备信息及登录通道，个人目录需要 App 通道凭证。
应用不会显示已保存的凭证。更新时空白可选字段沿用原值；要完全清空请移除账号后重新添加。

## 下载行为

- 支持暂停、继续、失败重试、多选文件下载、打开文件和 Finder 定位。
- 关闭窗口后下载继续；点击 Dock 图标重新打开。
- 使用 Cmd+Q 退出时停止下载并保存进度，重新打开后手动继续。
- 任务删除会清理临时分片，保留已经完成的文件。
- 下载期间阻止空闲休眠，全部任务停止后释放；不保证合盖后继续下载。
- 下载地址或凭证过期时可能需要更新凭证并重新解析链接；重试不能保证刷新所有平台的临时链接。
- 支持云端新建目录、重命名、移动和确认后删除，具体能力以平台接口为准；真实账号操作尚未验收。
- 不支持磁力 / BT、整文件夹递归下载、云端转存、内嵌浏览器登录或 Gopeed。

数据目录为 `~/Library/Application Support/YunX`，含加密凭证、密钥、SQLite 数据库、
分片与日志。备份时退出应用并复制整个目录，不能只保留数据库。
设置使用独立的 `yunx-macos` Java Preferences 节点。
不会自动迁移 Docker 或 Windows 的数据。

“设置 → 账号备份”可导出或主动导入口令加密的 `.yunx` 文件，
沿用 Windows 的 PBKDF2 / AES-GCM 备份格式。口令至少 8 位；
导入会替换文件中对应平台的凭证，其他平台保留。导入后仍需重新验证账号。
账号备份不包含下载任务、分片、收藏或应用设置，不能代替完整数据目录备份。

## 开发与打包

需要 Apple Silicon Mac、JDK 21 和 Gradle 8.14.3：

```sh
gradle :macos:test :macos:createDistributable :macos:packageDmg
gradle -p server test
gradle :desktop:test
```

输出位置：

- `macos/build/compose/binaries/main/app/YunX.app`
- `macos/build/compose/binaries/main/dmg/YunX-1.0.0.dmg`

Mac 模块直接编译必要的共享源码，并在进程内复用服务逻辑。
它不启动 HTTP 服务，也不包含 Windows 系统集成、JCEF 或 Gopeed 实现。
Windows 和 Server 各自保留原有入口。

## 验证记录

验证细节和未完成的真实账号验收见 [Mac 验证记录](MACOS-VALIDATION.md)。
后续全功能适配的差距见 [移植进度](PORTING.md)。
GitHub Actions 的 macOS 工作流负责测试和生成安装包，不自动公开发布。
首版仅交付 arm64；Intel、Developer ID 签名、公证和自动更新未纳入本版。

沿用原作者 tidain 的署名和 AGPL-3.0 协议；其他项目来源见仓库 README。
