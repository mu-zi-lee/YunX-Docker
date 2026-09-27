package com.yunx.app.util

import java.awt.Image
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 系统托盘管理器：应用最小化到托盘时显示图标。
 *
 * 设计说明：
 * - 不使用 AWT PopupMenu（原生 Windows 风格，与主程序 Material3 风格不一致，且中文可能乱码）。
 * - 改为通过 MouseAdapter 捕获左键/右键事件，回调给上层由 Compose 渲染统一样式的弹出菜单。
 * - 托盘图标使用 16x16 的 BufferedImage（从 icon.png 缩放），确保在系统托盘清晰显示。
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null
    /** 左键单击 / 双击托盘图标时回调（显示主窗口） */
    private var onLeftClick: (() -> Unit)? = null
    /** 右键单击托盘图标时回调（显示 Compose 风格菜单），参数为鼠标屏幕坐标 */
    private var onRightClick: ((x: Int, y: Int) -> Unit)? = null

    /** 是否已安装托盘图标 */
    val isInstalled: Boolean
        get() = trayIcon != null

    /**
     * 安装托盘图标。
     * @param image 托盘图标图片（建议 16x16）
     * @param onLeftClick 左键单击 / 双击托盘图标时回调（显示主窗口）
     * @param onRightClick 右键单击托盘图标时回调，参数为鼠标屏幕坐标 (x, y)
     */
    fun install(
        image: BufferedImage,
        onLeftClick: () -> Unit,
        onRightClick: (x: Int, y: Int) -> Unit
    ) {
        if (!SystemTray.isSupported()) return
        if (trayIcon != null) return

        this.onLeftClick = onLeftClick
        this.onRightClick = onRightClick

        val icon = TrayIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH), "云析 YunX-Desktop").apply {
            isImageAutoSize = true
            // 捕获鼠标事件：左键显示主窗口，右键弹出 Compose 菜单
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.button == MouseEvent.BUTTON1) {
                        onLeftClick()
                    } else if (e.button == MouseEvent.BUTTON3) {
                        onRightClick(e.xOnScreen, e.yOnScreen)
                    }
                }
            })
        }

        runCatching {
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
        }
    }

    /** 移除托盘图标 */
    fun uninstall() {
        trayIcon?.let { icon ->
            runCatching { SystemTray.getSystemTray().remove(icon) }
            trayIcon = null
        }
        onLeftClick = null
        onRightClick = null
    }

    /** 显示系统托盘气泡通知 */
    fun showNotification(title: String, message: String) {
        trayIcon?.displayMessage(title, message, TrayIcon.MessageType.INFO)
    }

    /** 从 classpath 加载 icon.png 并缩放为 16x16 BufferedImage（用于托盘图标） */
    fun loadTrayIcon(): BufferedImage? = runCatching {
        val stream = Thread.currentThread().contextClassLoader
            ?.getResourceAsStream("icon.png") ?: return@runCatching null
        val original = ImageIO.read(stream) ?: return@runCatching null
        val scaled = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        scaled.createGraphics().apply {
            drawImage(original.getScaledInstance(16, 16, Image.SCALE_SMOOTH), 0, 0, null)
            dispose()
        }
        scaled
    }.getOrNull()
}
