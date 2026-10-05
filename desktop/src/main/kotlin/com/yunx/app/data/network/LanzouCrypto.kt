package com.yunx.app.data.network

/**
 * 蓝奏云人机校验 Cookie `acw_sc__v2` 的纯 Kotlin 计算。
 *
 * 蓝奏云（与 aliyun WAF 同款）在分享页 / 下载节点 / 登录接口可能先返回一段 JS 校验页：
 * 页面里给出 40 位十六进制的 `arg1`，需要按固定位置重排后与固定掩码逐字节异或，得到
 * `acw_sc__v2` 的值并写入 Cookie 后重放请求。
 *
 * 该算法是**固定公开的字符串变换**（不执行页面脚本、不依赖 JS 引擎），上游 Android 版即用纯
 * Kotlin 实现；桌面版逐字节对齐，无需引入任何脚本引擎或第三方依赖。
 */
internal object LanzouCrypto {

    /** 重排位置表：把 arg1 的第 pos[z] 个字符放到第 z 位（1-based） */
    private val POS = intArrayOf(
        15, 35, 29, 24, 33, 16, 1, 38, 10, 9, 19, 31, 40, 27, 22, 23, 25, 13, 6, 11,
        39, 18, 20, 8, 14, 21, 32, 26, 2, 30, 7, 4, 17, 5, 3, 28, 34, 37, 12, 36
    )

    /** 异或掩码（40 位十六进制，即 20 字节） */
    private const val MASK = "3000176000856006061501533003690027800375"

    /**
     * 计算 `acw_sc__v2`：arg1 按 [POS] 重排 → 与 [MASK] 逐字节异或 → 20 位小写十六进制串。
     */
    fun acwScV2(arg1: String): String {
        val q = CharArray(POS.size)
        for (x in arg1.indices) {
            for (z in POS.indices) {
                if (POS[z] == x + 1) q[z] = arg1[x]
            }
        }
        val u = String(q)
        return buildString {
            var i = 0
            while (i + 2 <= u.length && i + 2 <= MASK.length) {
                val a = u.substring(i, i + 2).toInt(16)
                val b = MASK.substring(i, i + 2).toInt(16)
                append("%02x".format(a xor b))
                i += 2
            }
        }
    }

    /** 页面若为 acw_sc__v2 校验页则返回应设置的 Cookie 值（无 `arg1` 时返回 null）。 */
    fun challengeCookie(source: String): String? {
        val arg1 = Regex("""\barg1\s*=\s*'([0-9A-Fa-f]{40})'""").find(source)
            ?.groupValues?.getOrNull(1)
            ?: return null
        return acwScV2(arg1)
    }
}
