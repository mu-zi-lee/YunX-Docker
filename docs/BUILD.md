# 构建与打包

> 概览见 [README](../README.md)

## 环境要求

要求：Windows 10/11 x64。

| 依赖 | 说明 |
| --- | --- |
| JDK 17 | 缺失时 `jdk-setup.ps1` 自动下载便携版 |
| Gradle | 由 wrapper 自动拉取，无需手动安装 |
| **C++ 编译器** | 托盘菜单配色桥接层 `native/darkmode.cpp` 需编译为 `darkmode.dll`：优先 `cl.exe`（Visual Studio 2019/2022，勾选「使用 C++ 的桌面开发」工作负载），否则回退 `g++`（MinGW） |
| Inno Setup 6 | 仅打安装包需要；缺失时脚本自动下载并静默安装 |

**缺 C++ 编译器时**：`run` / `package` / `installer` 会在编译 `darkmode.dll` 这一步失败；
`run.ps1 build` 只编译 Kotlin，不受影响。

## 命令

```powershell
git clone https://github.com/tidain/YunX-Desktop.git
cd YunX-Desktop

.\run.ps1 run        # 编译并启动（开发，含 darkmode.dll）
.\run.ps1 build      # 仅编译 Kotlin（不需要 C++ 编译器）
.\run.ps1 package    # 免安装便携版 → release\YunX-Desktop\
.\run.ps1 installer  # 单文件安装程序 → release\YunX-Desktop-setup-*.exe
```

也可双击 `run.bat`（走同一入口）。中文向导语言包随仓库分发（`installer\ChineseSimplified.isl`）。

## 版本号

根目录 `version.txt` 是**唯一版本来源**：jpackage 版本、便携包 / 安装包的文件名与程序版本、
应用内「关于页 / 更新检测」全部由它派生。**发版只需改这一个文件。**

启动器 exe 的版本资源（公司 `tidain`、版权 `Copyright (C) 2026 tidain`）也在打包时由该版本号生成。

## 打包产物

| 产物 | 位置 | 说明 |
| --- | --- | --- |
| 便携版 | `release\YunX-Desktop\` | 整个文件夹拷走即用，双击 `YunX-Desktop.exe` 运行，无需安装 |
| 安装程序 | `release\YunX-Desktop-setup-<版本>.exe` | 每用户安装到 `%LOCALAPPDATA%\Programs\YunX-Desktop`，无需管理员权限；创建开始菜单 / 桌面快捷方式「云析」，可卸载 |

打包脚本会自动结束正在运行的程序实例，并在输出目录被占用时重试删除。

## 目录结构

| 目录 | 说明 |
| --- | --- |
| `desktop/` | 应用本体（Kotlin + Compose Multiplatform） |
| `desktop/native/` | 原生桥接层源码（`darkmode.cpp` → `darkmode.dll`） |
| `launcher/` | 自研启动器（C# 编译为无控制台窗口的 exe；固定读取 `app\YunX-Desktop.cfg`，改 exe 名或放在中文路径下都不会失效，同时负责启动闪屏与主窗口交叉淡入） |
| `installer/` | Inno Setup 脚本与中文语言包 |
| `docs/` | 文档（见 [README](../README.md#文档)） |
