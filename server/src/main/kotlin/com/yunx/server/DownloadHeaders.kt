package com.yunx.server

import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.DownloadLink
import java.net.URI

internal fun downloadHeaders(platform: SharePlatform, link: DownloadLink, credential: String): Map<String, String> {
    val cookie = link.guestCookie.ifBlank { credential }
    return when (platform) {
        SharePlatform.XUNLEI -> mapOf("User-Agent" to XunleiConstants.APP_UA)
        SharePlatform.BAIDU -> mapOf("Cookie" to credential, "User-Agent" to BaiduConstants.UA_NETDISK)
        SharePlatform.C139 -> mapOf("User-Agent" to C139Constants.PC_UA)
        SharePlatform.PAN115 -> mapOf(
            "Cookie" to Pan115Constants.mergeCookies(credential, link.guestCookie),
            "User-Agent" to Pan115Constants.CLIENT_UA, "Referer" to Pan115Constants.DOWNLOAD_REFERER
        )
        SharePlatform.PAN123 -> mapOf("User-Agent" to Pan123Constants.WEB_UA, "Referer" to Pan123Constants.DOWNLOAD_REFERER)
        SharePlatform.GUANGYA -> mapOf("User-Agent" to GuangYaConstants.WEB_UA, "Referer" to GuangYaConstants.DOWNLOAD_REFERER)
        SharePlatform.ILANZOU -> mapOf(
            "User-Agent" to ILanzouConstants.WEB_UA, "Referer" to ILanzouConstants.DOWNLOAD_REFERER,
            "Origin" to ILanzouConstants.DOWNLOAD_ORIGIN
        )
        SharePlatform.LANZOU -> mapOf(
            "User-Agent" to LanzouConstants.WEB_UA,
            "Referer" to URI(link.downloadUrl).let { "${it.scheme}://${it.host}/" },
            "Cookie" to LanzouConstants.DOWN_IP_COOKIE
        )
        SharePlatform.UC -> buildMap {
            put("Cookie", cookie)
            put("User-Agent", if (link.guestCookie.isNotBlank()) UCConstants.GUEST_UA else UCConstants.USER_AGENT)
            put("Referer", UCConstants.DOWNLOAD_REFERER)
            put("Origin", UCConstants.WEB_ORIGIN)
            if (link.guestCookie.isNotBlank()) put("Sec-Ch-Ua", UCConstants.GUEST_SEC_CH_UA)
        }
        SharePlatform.QUARK -> mapOf(
            "Cookie" to cookie, "User-Agent" to QuarkConstants.API_USER_AGENT,
            "Referer" to QuarkConstants.DOWNLOAD_REFERER
        )
        SharePlatform.GITHUB -> emptyMap()
    }
}
