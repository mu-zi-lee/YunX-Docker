package com.yunx.app.util

import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinReg

/**
 * 原生菜单配色桥接层。
 *
 * 目标配色由**系统「应用」颜色模式**决定（读注册表 AppsUseLightTheme），不写死：
 * 深色 → uxtheme 的 ForceDark，浅色 → ForceLight。启动时应用一次，
 * 每次弹菜单前再 [refresh] 一次，这样运行中切换系统配色也能跟着变。
 *
 * native/darkmode.dll 负责实际调用（uxtheme.dll 序号 132/135/136）。
 * 注意：JNA 的 Native.load 不读取 java.library.path，所以这里必须用 System.loadLibrary。
 */
object DarkMode {

    private const val TAG = "YunX-DarkMode"
    private const val PERSONALIZE_KEY =
        "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"
    private const val APPS_USE_LIGHT_THEME = "AppsUseLightTheme"

    private interface Kernel32Lib : Library {
        fun GetProcAddress(hModule: Pointer, lpProcName: String): Pointer?
    }

    private val k32: Kernel32Lib? by lazy {
        runCatching { Native.load("kernel32", Kernel32Lib::class.java) }.getOrNull()
    }

    /** darkmode.dll 导出的 EnableDarkMode(int dark)；null 表示该层不可用（菜单保持默认配色） */
    private val enableFn: Function? by lazy {
        runCatching {
            System.loadLibrary("darkmode")
            val module = Kernel32.INSTANCE.GetModuleHandle("darkmode.dll")
                ?: run {
                    Log.w(TAG, "GetModuleHandle(darkmode.dll) 返回 null")
                    return@runCatching null
                }
            val addr = k32?.GetProcAddress(module.pointer, "EnableDarkMode")
                ?: run {
                    Log.w(TAG, "GetProcAddress(EnableDarkMode) 返回 null")
                    return@runCatching null
                }
            Function.getFunction(addr, Function.ALT_CONVENTION)
        }.onFailure {
            Log.w(TAG, "加载 darkmode.dll 失败: ${it.message}")
        }.getOrNull()
    }

    /** 系统「应用」是否为深色；读不到按浅色（等同系统默认） */
    private fun systemUsesDark(): Boolean = runCatching {
        Advapi32Util.registryGetIntValue(
            WinReg.HKEY_CURRENT_USER, PERSONALIZE_KEY, APPS_USE_LIGHT_THEME
        ) == 0
    }.getOrDefault(false)

    /** 启动时调用一次（需在任何窗口创建之前） */
    fun enable() {
        if (!isWindows()) return
        val dark = systemUsesDark()
        Log.i(TAG, "system app theme = " + if (dark) "dark" else "light")
        apply(dark)
    }

    /** 按当前系统配色重新应用；菜单弹出前调用，跟随运行中的配色变化 */
    fun refresh() {
        if (!isWindows()) return
        apply(systemUsesDark())
    }

    private fun apply(dark: Boolean) {
        val fn = enableFn ?: return
        runCatching { fn.invoke(Void.TYPE, arrayOf<Any>(if (dark) 1 else 0)) }
            .onFailure { Log.w(TAG, "apply dark=$dark failed: ${it.message}") }
    }

    private fun isWindows() = System.getProperty("os.name", "").lowercase().contains("win")
}
