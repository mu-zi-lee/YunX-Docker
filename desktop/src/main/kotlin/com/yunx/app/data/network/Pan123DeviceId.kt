package com.yunx.app.data.network

import com.yunx.app.AppContext
import java.util.UUID

/**
 * 123 云盘的设备标识（`loginuuid` 请求头，32 位十六进制）。
 *
 * 为什么必须持久化：123 把这个值当设备指纹做风控，且账号密码登录也带它。若每次启动都换一个
 * （早先的写法是进程内随机），服务端就会一直把我们当成新设备，登录更容易被要求过验证。
 * 桌面版没有 Application.onCreate，改由 [value] 首次调用时懒加载：读 [AppContext.miscPrefs]，
 * 缺失才生成一次并落盘，此后跨启动返回同一个值（与迅雷设备指纹同一套思路）。
 */
object Pan123DeviceId {

    private const val KEY_UUID = "pan123_loginuuid"
    private val HEX32 = Regex("^[0-9a-fA-F]{32}$")

    @Volatile
    private var cached: String? = null

    /** 当前设备标识；首个调用负责从偏好读取或生成并持久化 */
    fun value(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val prefs = AppContext.miscPrefs
            val saved = prefs.get(KEY_UUID, null)?.takeIf { HEX32.matches(it) }
            val value = saved ?: UUID.randomUUID().toString().replace("-", "").also { prefs.put(KEY_UUID, it) }
            cached = value
            return value
        }
    }
}
