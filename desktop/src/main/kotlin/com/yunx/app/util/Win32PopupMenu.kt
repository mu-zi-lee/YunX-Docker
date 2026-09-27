package com.yunx.app.util

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.WinDef
import java.awt.EventQueue

/**
 * Win32 TrackPopupMenu 桥接：JNA 调 CreatePopupMenu + AppendMenuW + TrackPopupMenuEx。
 *
 * 中文用 AppendMenuW（UTF-16）所以正常；暗色由 DarkMode 打开 uxtheme 的 AllowDark 决定。
 * 不用 Swing、不自绘窗口。
 *
 * 关键坑（实测确认，2026-09-27）：
 * TrackPopupMenuEx 要求 owner 窗口必须属于**调用它的那个线程**，否则 Windows 直接返回 0
 * 并置 ERROR_INVALID_PARAMETER(87)，菜单完全不显示（既不报错也不闪一下）。
 * AWT 顶层窗口（主窗口）的 HWND 是 AWT 自己线程创建的，与托盘回调所在的 EDT 不是同一线程，
 * 所以拿主窗口 HWND 当 owner 必然失败（前面多轮「右键没反应」的真因）。
 * 解决：首次调用时在本线程建一个隐藏的 WS_POPUP 窗口专作菜单 owner，之后复用。
 */
object Win32PopupMenu {

    private interface User32Lib : Library {
        fun CreatePopupMenu(): WinDef.HMENU?
        fun AppendMenuW(hMenu: WinDef.HMENU, uFlags: Int, uIDNewItem: Int, lpNewItem: WString?): Boolean
        fun DestroyMenu(hMenu: WinDef.HMENU): Boolean
        fun TrackPopupMenuEx(
            hMenu: WinDef.HMENU,
            fuFlags: Int,
            x: Int,
            y: Int,
            hwnd: WinDef.HWND?,
            lptpm: Pointer?
        ): Int
        fun SetForegroundWindow(hwnd: WinDef.HWND?): Boolean
        fun PostMessageW(hwnd: WinDef.HWND?, msg: Int, wParam: Pointer?, lParam: Pointer?): Boolean
        fun CreateWindowExW(
            exStyle: Int,
            className: WString,
            windowName: WString,
            style: Int,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            parent: WinDef.HWND?,
            menu: Pointer?,
            instance: Pointer?,
            param: Pointer?
        ): WinDef.HWND?
    }

    private val lib: User32Lib? by lazy {
        if (!System.getProperty("os.name", "").lowercase().contains("win")) null
        else runCatching { Native.load("user32", User32Lib::class.java) }.getOrNull()
    }

    private const val MF_STRING = 0x00000000
    private const val MF_SEPARATOR = 0x00000800
    private const val TPM_RETURNCMD = 0x0100
    private const val TPM_BOTTOMALIGN = 0x0020
    private const val WS_POPUP = 0x80000000.toInt()
    private const val WM_NULL = 0x0000

    /** 菜单 owner（隐藏窗口），必须由首次调用它的线程创建并复用 */
    @Volatile
    private var ownerHwnd: WinDef.HWND? = null

    /**
     * 弹出原生菜单并返回选中项 ID，0 = 未选择/取消失败。
     * 可在任意线程调用；内部统一切到 AWT EDT 执行（owner 窗口与调用线程必须一致）。
     */
    fun show(items: List<Pair<String, Int>>, x: Int, y: Int): Int {
        if (!EventQueue.isDispatchThread()) {
            var result = 0
            return runCatching {
                EventQueue.invokeAndWait { result = showOnEdt(items, x, y) }
                result
            }.getOrDefault(0)
        }
        return showOnEdt(items, x, y)
    }

    private fun showOnEdt(items: List<Pair<String, Int>>, x: Int, y: Int): Int {
        val u32 = lib ?: return 0
        val owner = ownerWindow(u32) ?: return 0
        val hMenu = u32.CreatePopupMenu() ?: return 0
        try {
            for ((label, id) in items) {
                if (label == "-") {
                    u32.AppendMenuW(hMenu, MF_SEPARATOR, 0, null)
                } else {
                    u32.AppendMenuW(hMenu, MF_STRING, id, WString(label))
                }
            }
            // 按当前系统配色刷新菜单主题（用户可能在运行中切换了亮/暗）
            DarkMode.refresh()
            u32.SetForegroundWindow(owner)
            val cmd = u32.TrackPopupMenuEx(
                hMenu, TPM_RETURNCMD or TPM_BOTTOMALIGN, x, y, owner, null
            )
            // 标准收尾：给 owner 投一条空消息，确保菜单彻底关闭且不残留鼠标捕获
            u32.PostMessageW(owner, WM_NULL, null, null)
            return cmd
        } finally {
            u32.DestroyMenu(hMenu)
        }
    }

    /** 取（必要时创建）菜单 owner 隐藏窗口；必须在将调用 TrackPopupMenuEx 的线程上执行 */
    private fun ownerWindow(u32: User32Lib): WinDef.HWND? {
        ownerHwnd?.let { return it }
        val created = runCatching {
            u32.CreateWindowExW(
                0, WString("STATIC"), WString("YunXMenuOwner"), WS_POPUP,
                0, 0, 0, 0, null, null, null, null
            )
        }.getOrNull()
        if (created != null) ownerHwnd = created
        return created
    }
}
