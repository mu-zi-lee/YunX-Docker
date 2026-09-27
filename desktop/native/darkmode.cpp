// darkmode.cpp — 让 Win32 原生菜单（托盘右键）的亮/暗跟随系统「应用」颜色模式
//
// 只做一件事：把调用方算好的目标模式应用到进程。
//   EnableDarkMode(1) → ForceDark   （系统「应用」为深色）
//   EnableDarkMode(0) → ForceLight  （系统「应用」为浅色）
// 目标模式由 Kotlin 侧读注册表 HKCU\...\Themes\Personalize\AppsUseLightTheme 得出，
// 每次弹菜单前重新调用一次，因此运行中切换系统配色也能跟着变。
//
// 编译（需 MSVC 环境，即先 vcvars64.bat）：
//   cl /LD /utf-8 darkmode.cpp /Fe:darkmode.dll /link /NOENTRY kernel32.lib user32.lib
// 或（MinGW）：
//   g++ -shared -o darkmode.dll darkmode.cpp -lkernel32 -luser32

#include <windows.h>

// uxtheme.dll 未公开导出（按序号取）：
//   132 = RefreshImmersiveColorPolicyState()     ; 重新求值沉浸色策略（系统配色变化后必须刷新）
//   135 = SetPreferredAppMode(PreferredAppMode)  ; PreferredAppMode { Default=0, AllowDark=1, ForceDark=2, ForceLight=3 }
//   136 = FlushMenuThemes()                      ; 让菜单主题立即重绘
typedef int(WINAPI *SetPreferredAppMode_t)(int);
typedef void(WINAPI *RefreshImmersiveColorPolicyState_t)(void);
typedef void(WINAPI *FlushMenuThemes_t)(void);

extern "C" __declspec(dllexport) void EnableDarkMode(int dark) {
    // 必须 LoadLibrary 借一次句柄来取序号地址；但**绝对不能 FreeLibrary**：
    // 启动早期 uxtheme 通常还没被加载，引用计数为 1，一旦 FreeLibrary 就会把它卸载，
    // 进程级的 PreferredAppMode 状态随之丢失；之后应用创建菜单时 uxtheme 重新加载，
    // 模式退回 Default，菜单仍为亮色（实测复现过）。
    // uxtheme 是系统 DLL，进程生命周期内常驻没有代价，所以这里刻意不释放。
    HMODULE hUxTheme = LoadLibraryW(L"uxtheme.dll");
    if (!hUxTheme) return;

    SetPreferredAppMode_t setPreferredAppMode =
        (SetPreferredAppMode_t)GetProcAddress(hUxTheme, (LPCSTR)135);
    if (setPreferredAppMode) {
        setPreferredAppMode(dark ? 2 : 3);
    }

    // 先刷新策略再重绘菜单：否则运行中改系统配色时菜单会停留在旧配色
    RefreshImmersiveColorPolicyState_t refreshPolicy =
        (RefreshImmersiveColorPolicyState_t)GetProcAddress(hUxTheme, (LPCSTR)132);
    if (refreshPolicy) {
        refreshPolicy();
    }

    FlushMenuThemes_t flushMenuThemes =
        (FlushMenuThemes_t)GetProcAddress(hUxTheme, (LPCSTR)136);
    if (flushMenuThemes) {
        flushMenuThemes();
    }
}
