## Apple Silicon macOS 测试版

本地运行的 YunX 网盘下载器，附带 Java 运行环境，无需 Docker 或另外安装 Java。
界面沿用本仓库 Web 版的黑白灰样式。

### 已提供

- 手动配置及验证网盘凭证、个人目录浏览、分享链接和 GitHub 仓库解析。
- 多选文件下载、暂停续传、失败重试、Finder 定位。
- 关闭窗口继续下载、Dock 恢复窗口、退出保存进度和下载期间防空闲休眠。
- 加密账号备份，以及平台支持的新建目录、重命名、移动和删除。

### 安装

下载 `YunX-<版本>-macos-arm64.dmg`，将 YunX 拖入 Applications。
安装包仅支持 Apple Silicon，未做 Developer ID 签名与 Apple 公证。
如 macOS 阻止打开，可在系统“隐私与安全性”中允许打开。

同时提供 `SHA256SUMS`，可将它与 DMG 放在同一目录后执行：

```sh
shasum -a 256 -c SHA256SUMS
```

### 验证边界

自动化检查覆盖 Mac 下载与凭证测试、Windows 和 Server 回归、
Docker 双架构容器下载与持久化，以及安装包架构和完整性。
本地另有窗口生命周期、跨进程续传和公开 GitHub 文件下载验证。
真实网盘账号及云端管理操作尚未完成端到端验收。

尚未提供磁力 / BT、整文件夹递归下载、云端转存、浏览器登录、
Gopeed 内核操作、托盘、自动更新及完整 Windows 功能移植。
更新不会自动迁移 Windows 或 Docker 数据。

使用说明见仓库 `docs/MACOS.md`；详细验证和移植进度见
`docs/MACOS-VALIDATION.md` 与 `docs/PORTING.md`。

保留原作者 tidain 的署名及 AGPL-3.0 协议。
