package com.yunx.app.ui.clipboard

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * 托盘右键菜单控制器：桥接 AWT 鼠标事件线程与 Compose UI 线程。
 *
 * TrayManager 在 AWT EDT 线程捕获右键事件，通过 [requestShow] 把屏幕坐标写入
 * Compose State；MainScreen 中 [TrayMenuPopup] 观察此 State，在对应位置渲染
 * Material3 风格菜单（替代 AWT PopupMenu，统一视觉风格并避免中文乱码）。
 */
object TrayMenuController {

    private val _showRequest = mutableStateOf<Pair<Int, Int>?>(null)

    /** 可观察的显示请求状态（非 null 时表示需要在 (x, y) 屏幕坐标显示托盘菜单） */
    val showRequestState: State<Pair<Int, Int>?> = _showRequest

    /** 请求在指定屏幕坐标显示托盘菜单（由 TrayManager 右键回调调用） */
    fun requestShow(x: Int, y: Int) {
        _showRequest.value = x to y
    }

    /** 消费显示请求（返回坐标并清空），由 TrayMenuPopup 调用 */
    fun consume(): Pair<Int, Int>? {
        val req = _showRequest.value
        _showRequest.value = null
        return req
    }

    /** 手动关闭菜单 */
    fun dismiss() {
        _showRequest.value = null
    }
}
