; YunX-Desktop installer script (Inno Setup 6)
; Compiled by installer-package.ps1 with /DAppVersion=x.y.z
; Source: the portable output folder (release\YunX-Desktop\) which already contains
; the custom launcher and skiko native libs.

#define AppName "云析"
#define AppNameEn "YunX-Desktop"
#define AppCompany "tidain"
#define AppExe "YunX-Desktop.exe"
; 版本号单一来源：仓库根 version.txt —— installer-package.ps1 读取后经 /DAppVersion 传入。
; 这里刻意不留默认值，避免与 version.txt 漂移；直接调 ISCC 而不传参会在编译期报错。
#ifndef AppVersion
  #error AppVersion not defined - build via installer-package.ps1 (it passes /DAppVersion from version.txt)
#endif

[Setup]
AppId={{7C1E5F9A-3B2D-4E68-9C4F-A1D2B3C4E5F6}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppNameEn}
AppPublisherURL=https://github.com/tidain/YunX-Desktop
AppSupportURL=https://github.com/tidain/YunX-Desktop/issues
AppUpdatesURL=https://github.com/tidain/YunX-Desktop/releases
; 安装程序自身的文件版本信息（资源管理器 → setup.exe 属性 → 详细信息）
VersionInfoVersion={#AppVersion}
VersionInfoProductName={#AppName}
VersionInfoCompany={#AppCompany}
VersionInfoDescription={#AppName} - 网盘分享链接解析与高速下载器
; lowest = no UAC prompt; {autopf} then maps to %LOCALAPPDATA%\Programs (ASCII-safe path
; for the bundled launcher). Remove PrivilegesRequired line to install per-machine instead.
PrivilegesRequired=lowest
DefaultDirName={autopf}\{#AppNameEn}
DisableProgramGroupPage=yes
WizardStyle=modern
SetupIconFile=..\YunX-Desktop.ico
UninstallDisplayIcon={app}\{#AppExe}
Compression=lzma2/max
SolidCompression=yes
; 输出到 release\（相对本 .iss 所在目录）
OutputDir=..\release
OutputBaseFilename=YunX-Desktop-setup-{#AppVersion}
ShowLanguageDialog=no

[Languages]
; 中文语言包随仓库分发（installer\ChineseSimplified.isl），构建不依赖外网
Name: "chs"; MessagesFile: "ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; GroupDescription: "{cm:AdditionalIcons}"

[Files]
Source: "..\release\YunX-Desktop\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Icons]
; MessageBox 之外，Windows 通知（toast）也依赖开始菜单里存在带 AppUserModelID 的快捷方式；
; 未打包的桌面应用缺了它就发不出通知（Show 不报错但什么都不会出现）
Name: "{autoprograms}\{#AppName}"; Filename: "{app}\{#AppExe}"; AppUserModelID: "YunX.Desktop"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon; AppUserModelID: "YunX.Desktop"

[Run]
Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent
