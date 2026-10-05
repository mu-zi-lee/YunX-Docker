package com.yunx.app.data.announcement

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 公告时间工具。
 *
 * 服务端时间统一是 **UTC ISO 8601**（形如 `2026-10-04T10:00:00.000Z`），展示前必须转本地时区。
 * 与上游 Android 版逐行一致（SimpleDateFormat 无兼容风险，且桌面 JDK 17 同样适用）。
 */

/**
 * 支持的 ISO 8601 形态，按常见程度排序：
 * 1/2 是服务端标准输出（带毫秒 / 不带毫秒的 UTC "Z"）；3/4 兜底带 `±0800` 偏移的写法。
 * `'Z'` 是字面量（引号包住），配 `timeZone = UTC` 解析；不带引号的 `Z` 才是 RFC822 时区。
 */
private val ISO_PATTERNS = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
    "yyyy-MM-dd'T'HH:mm:ssZ"
)

/** 解析 ISO 8601 时间为毫秒时间戳；空串 / 无法识别返回 null（调用方自己决定兜底口径） */
fun parseIsoMillis(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    for (pattern in ISO_PATTERNS) {
        val parsed: Date? = try {
            SimpleDateFormat(pattern, Locale.US)
                .apply { if (pattern.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC") }
                .parse(text)
        } catch (e: Exception) {
            null
        }
        if (parsed != null) return parsed.time
    }
    return null
}

/**
 * 相对时间文案（列表 / 详情共用同一口径）：
 * 刚刚 / N 分钟前 / N 小时前 / N 天前，超过 7 天显示本地日期。
 * 客户端时钟比服务端慢时会算出负数，一律按「刚刚」处理（不显示「-3 分钟前」）。
 */
fun relativeTime(millis: Long): String {
    if (millis <= 0L) return ""
    val diff = System.currentTimeMillis() - millis
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> formatLocalDate(millis)
    }
}

/** 本地日期（yyyy-MM-dd） */
fun formatLocalDate(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

/** 本地日期时间（yyyy-MM-dd HH:mm），详情页底部「最后更新」用 */
fun formatLocalDateTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
