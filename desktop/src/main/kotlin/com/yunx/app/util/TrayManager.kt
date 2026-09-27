package com.yunx.app.util

import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * 系统托盘管理器：应用最小化到托盘时显示图标，支持右键菜单（显示主窗口 / 退出）。
 *
 * 注意：TrayIcon 的所有操作必须在 AWT EDT 线程执行（SystemTray/TrayIcon 非线程安全）。
 * 托盘图标使用 16x16 的 BufferedImage（从 icon.png 缩放），确保在系统托盘清晰显示。
 */
object TrayManager {

    private var trayIcon: TrayIcon? = null
    private var onShowMainWindow: (() -> Unit)? = null
    private var onExit: (() -> Unit)? = null

    /** 是否已安装托盘图标 */
    val isInstalled: Boolean
        get() = trayIcon != null

    /**
     * 安装托盘图标。
     * @param image 托盘图标图片（建议 16x16）
     * @param onShowMainWindow 点击「显示主窗口」或双击托盘图标时回调
     * @param onExit 点击「退出」时回调
     */
    fun install(image: BufferedImage, onShowMainWindow: () -> Unit, onExit: () -> Unit) {
        if (!SystemTray.isSupported()) return
        if (trayIcon != null) return

        this.onShowMainWindow = onShowMainWindow
        this.onExit = onExit

        val popup = PopupMenu().apply {
            add(MenuItem("显示主窗口").apply {
                addActionListener { onShowMainWindow() }
            })
            addSeparator()
            add(MenuItem("退出").apply {
                addActionListener { onExit() }
            })
        }

        val icon = TrayIcon(image.getScaledInstance(16, 16, Image.SCALE_SMOOTH), "云析 YunX-Desktop", popup).apply {
            isImageAutoSize = true
            // 双击托盘图标显示主窗口
            addActionListener { onShowMainWindow() }
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
        onShowMainWindow = null
        onExit = null
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
