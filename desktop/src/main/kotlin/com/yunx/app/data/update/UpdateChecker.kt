package com.yunx.app.data.update

import com.yunx.app.APP_VERSION
import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * GitHub Release 更新检测。
 * 正式版通道（默认）：GET https://api.github.com/repos/tidain/YunX-Desktop/releases/latest
 * 预发布通道（设置页开启「接受预发布版更新」后）：GET .../releases
 */
object UpdateChecker {

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/tidain/YunX-Desktop/releases/latest"

    /** Release 列表（按发布时间倒序，含 Pre-release）：开启「接受预发布版更新」时用它取最新一条 */
    private const val RELEASES_LIST_URL =
        "https://api.github.com/repos/tidain/YunX-Desktop/releases"

    private const val TAG = "YunX-Update"

    /** GitHub 下载加速镜像站前缀（国内直连 GitHub 慢/失败时的兜底下载通道） */
    const val MIRROR_PREFIX = "https://cdn.gh-proxy.org/"

    /** 把 GitHub release 直链转成镜像站直链：<prefix><原直链>；默认使用内置镜像前缀 */
    fun mirrorUrl(url: String, prefix: String = MIRROR_PREFIX): String = prefix + url

    data class Asset(
        val name: String,
        val downloadUrl: String
    )

    data class Release(
        val tagName: String,
        val body: String,
        val assets: List<Asset>,
        val publishedAt: String,
        /** 是否为 GitHub Pre-release：正式版通道恒为 false，预发布通道可能为 true */
        val prerelease: Boolean = false
    )

    /**
     * 比较两个版本号：v1 > v2 返回正数，v1 < v2 返回负数，相等返回 0。
     * 兼容 fork 构建后缀（如 "1.2.6-gh1"）：每段取数字前缀比较，后缀不影响主版本比较。
     * 数字段完全相同时，只有两边都带后缀（预发布版，如 "1.3.0-beta1"）才继续比后缀：先比后缀里第一段数字
     * （beta2 > beta1），再按字符串比较；一边没有后缀则视为相等，免得把 fork 构建（1.2.6-gh1）判成比同号正式版旧。
     */
    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.trimStart('v').split(".")
        val parts2 = v2.trimStart('v').split(".")
        val maxLength = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLength) {
            val num1 = DIGITS.find(parts1.getOrNull(i).orEmpty())?.value?.toIntOrNull() ?: 0
            val num2 = DIGITS.find(parts2.getOrNull(i).orEmpty())?.value?.toIntOrNull() ?: 0
            if (num1 != num2) return num1 - num2
        }
        if (v1 == v2) return 0
        val suffix1 = v1.trimStart('v').substringAfter('-', "")
        val suffix2 = v2.trimStart('v').substringAfter('-', "")
        if (suffix1.isEmpty() || suffix2.isEmpty()) return 0
        val pre1 = DIGITS.find(suffix1)?.value?.toIntOrNull() ?: 0
        val pre2 = DIGITS.find(suffix2)?.value?.toIntOrNull() ?: 0
        if (pre1 != pre2) return pre1 - pre2
        return suffix1.compareTo(suffix2)
    }

    private val DIGITS = Regex("\\d+")

    /** 当前应用版本号，来源：仓库根 version.txt（构建时生成 APP_VERSION，勿在此硬编码） */
    const val PC_VERSION = APP_VERSION

    fun currentVersion(): String = PC_VERSION

    /**
     * 请求 GitHub 最新 Release。
     * [includePrerelease] = true 时改走预发布通道（Release 列表接口，按发布时间倒序取第一条非 Draft 版本），
     * 这样标了 Pre-release 的版本也会被当成可更新版本；false 时走 `/releases/latest`（GitHub 只给正式版）。
     * 网络失败 / 仓库无 Release（404）返回 null。
     */
    suspend fun fetchLatestRelease(includePrerelease: Boolean = false): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val json = (if (includePrerelease) requestReleaseList() else requestLatest())
                ?: return@runCatching null
            val tag = json.optString("tag_name")
            if (tag.isBlank()) return@runCatching null
            val assets = buildList {
                json.optJSONArray("assets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val a = arr.optJSONObject(i) ?: continue
                        add(Asset(a.optString("name"), a.optString("browser_download_url")))
                    }
                }
            }
            Release(
                tagName = tag,
                body = json.optString("body"),
                assets = assets,
                publishedAt = json.optString("published_at"),
                prerelease = json.optBoolean("prerelease")
            )
        }.onFailure { e ->
            Log.e(TAG, "获取最新 Release 异常：${e.javaClass.simpleName}: ${e.message}", e)
        }.getOrNull()
    }

    /** 正式版通道：GET /releases/latest，GitHub 保证返回最新的非 Pre-release、非 Draft 版本 */
    private fun requestLatest(): JSONObject? {
        val body = fetchBody(RELEASES_LATEST_URL) ?: return null
        return runCatching { JSONObject(body) }.getOrElse {
            Log.e(TAG, "获取最新 Release 失败：响应不是合法 JSON（前 200 字=${body.take(200)}）", it)
            null
        }
    }

    /** 预发布通道：GET /releases（数组、按发布时间倒序），取第一条非 Draft 且带 tag_name 的版本 */
    private fun requestReleaseList(): JSONObject? {
        val body = fetchBody(RELEASES_LIST_URL) ?: return null
        val array = runCatching { JSONArray(body) }.getOrElse {
            Log.e(TAG, "获取最新 Release 失败：响应不是合法 JSON 数组（前 200 字=${body.take(200)}）", it)
            return null
        }
        val json = (0 until array.length())
            .mapNotNull { array.optJSONObject(it) }
            .firstOrNull { !it.optBoolean("draft") && it.optString("tag_name").isNotBlank() }
        if (json == null) {
            Log.e(TAG, "获取最新 Release 失败：列表里没有可用版本（全为 Draft 或缺 tag_name）")
        }
        return json
    }

    /** 发 GitHub API GET 请求；非 2xx / 空响应返回 null 并打 E 级日志 */
    private fun fetchBody(url: String): String? {
        val client = HttpClients.apiClient()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YunX")
            .get()
            .build()
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.e(TAG, "获取最新 Release 失败：HTTP ${resp.code}（$url）")
                return@use null
            }
            val text = resp.body?.string()
            if (text.isNullOrBlank()) {
                Log.e(TAG, "获取最新 Release 失败：响应体为空")
                null
            } else {
                text
            }
        }
    }
}