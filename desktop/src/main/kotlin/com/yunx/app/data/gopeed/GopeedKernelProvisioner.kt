package com.yunx.app.data.gopeed

import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.update.UpdateChecker
import com.yunx.app.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Gopeed 内核（Windows exe）的云端获取：从 **Gopeed 官方 Release** 拉取
 * `gopeed-web-<版本>-windows-<架构>.zip`（无界面 web/服务端版），下载 → 校验 → 交给
 * [GopeedEngine.installFromArchive] 解包导入。
 *
 * 与上游 Android 版的差别：上游从私有内核仓库取 4 个 ABI 的 AAR；桌面直接取官方 Windows 包，
 * 架构按 `os.arch` 映射（amd64 / arm64），官方包名后缀与之一一对应。
 *
 * 下载走项目既有的 [HttpClients.downloadClient]（跟随代理设置）；镜像前缀沿用「GitHub 下载镜像」
 * 设置（未配置时用 [UpdateChecker.MIRROR_PREFIX]），镜像失败自动回退直连。
 */
object GopeedKernelProvisioner {

    private const val TAG = "GopeedKernel"
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/GopeedLab/gopeed/releases/latest"

    /** 官方包名前缀/后缀：`gopeed-web-v1.9.3-windows-amd64.zip` */
    private const val ASSET_PREFIX = "gopeed-web-"
    private const val ASSET_ARCH_SUFFIX = ".zip"

    /** 内核包落地的临时目录 `<dataDir>/gopeed/kernel` */
    private fun tempDir(): File = File(GopeedEngine.engineDir(), "kernel")

    /** 进度阶段（引擎页文案跟随它变化） */
    enum class Stage { RESOLVING, DOWNLOADING, VERIFYING, INSTALLING, DONE }

    /**
     * 一次获取过程的状态快照。
     * @param downloaded/[total] 仅 [Stage.DOWNLOADING] 有意义；[total] 为 -1 表示服务端未给长度
     * @param message 失败/提示文案（各阶段可空）
     */
    data class Progress(
        val stage: Stage,
        val downloaded: Long = 0L,
        val total: Long = -1L,
        val speed: Long = 0L,
        val message: String = ""
    )

    /** 解析出的下载计划 */
    data class Plan(
        val version: String,
        val assetName: String,
        /** 主 URL（配了镜像前缀时为镜像地址） */
        val url: String,
        /** 回退直连（与主 URL 相同时为空） */
        val fallbackUrl: String,
        val size: Long,
        /** 服务端给出的 sha256 摘要（形如 `sha256:xxxx`）；空串表示不校验 */
        val digest: String
    )

    /** 本机对应的官方包架构后缀：x86_64 → amd64，aarch64 → arm64 */
    private fun archSuffix(): String {
        val arch = System.getProperty("os.arch").orEmpty().lowercase()
        return when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "amd64"
        }
    }

    /**
     * 查询官方最新 Release，拼出本机可用的内核计划。
     * @param mirrorPrefix 用户配置的镜像前缀；null/空用内置默认镜像
     */
    suspend fun resolvePlan(mirrorPrefix: String?): Plan = withContext(Dispatchers.IO) {
        val mirror = mirrorPrefix?.takeIf { it.isNotBlank() } ?: UpdateChecker.MIRROR_PREFIX
        val json = fetchLatestJson()
        val tag = json.optString("tag_name")
        if (tag.isBlank()) throw IllegalStateException("官方 Release 缺少版本号")
        val wanted = "$ASSET_PREFIX$tag-windows-${archSuffix()}$ASSET_ARCH_SUFFIX"
        val asset = json.optJSONArray("assets")?.let { arr ->
            (0 until arr.length())
                .mapNotNull { arr.optJSONObject(it) }
                .firstOrNull { it.optString("name").equals(wanted, ignoreCase = true) }
        } ?: throw IllegalStateException("官方 Release（$tag）里没有 Windows 内核包 $wanted")
        val direct = asset.optString("browser_download_url")
        if (direct.isBlank()) throw IllegalStateException("内核包缺少下载地址")
        Plan(
            version = tag,
            assetName = wanted,
            url = UpdateChecker.mirrorUrl(direct, mirror),
            fallbackUrl = direct,
            size = asset.optLong("size"),
            digest = asset.optString("digest")
        )
    }

    private fun fetchLatestJson(): JSONObject {
        val req = Request.Builder()
            .url(RELEASES_LATEST_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "YunX-Desktop")
            .build()
        return HttpClients.apiClient().newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                Log.e(TAG, "获取官方 Release 失败：HTTP ${resp.code}")
                throw IllegalStateException("获取官方 Release 失败（HTTP ${resp.code}）")
            }
            runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("官方 Release 响应格式异常")
            }
        }
    }

    /**
     * 完整流程：解析 → 下载（镜像失败回退直连）→ 校验 → 导入。
     * @return 安装后的内核文件
     */
    suspend fun provision(
        mirrorPrefix: String?,
        onProgress: (Progress) -> Unit
    ): File {
        onProgress(Progress(Stage.RESOLVING, message = "正在获取官方内核版本…"))
        val plan = resolvePlan(mirrorPrefix)
        onProgress(
            Progress(Stage.DOWNLOADING, 0L, plan.size, 0L, "开始下载 ${plan.assetName}")
        )
        val archive = download(plan, onProgress)

        if (plan.digest.isNotBlank()) {
            onProgress(Progress(Stage.VERIFYING, message = "正在校验内核完整性…"))
            verifyDigest(archive, plan.digest)
        }

        onProgress(Progress(Stage.INSTALLING, message = "正在解包导入内核…"))
        val installed = GopeedEngine.installFromArchive(archive)
        // 导入成功后删除临时包（保留会白占 ~40MB）
        runCatching { archive.delete() }
        onProgress(Progress(Stage.DONE, message = "内核已就绪（${plan.version}）"))
        return installed
    }

    /** 下载内核包到临时目录；主 URL（镜像）失败自动回退直连 */
    private suspend fun download(plan: Plan, onProgress: (Progress) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = tempDir().also { it.mkdirs() }
        val target = File(dir, plan.assetName)
        // 每次都重新下：内核包不大，避免半截文件的续传语义纠缠
        if (target.exists()) runCatching { target.delete() }

        val candidates = buildList {
            add(plan.url)
            if (plan.fallbackUrl.isNotBlank() && plan.fallbackUrl != plan.url) add(plan.fallbackUrl)
        }
        var lastError: Exception? = null
        for (candidate in candidates) {
            val result = runCatching { downloadFrom(candidate, target, plan.size, onProgress) }
            if (result.isSuccess) return@withContext target
            lastError = result.exceptionOrNull() as? Exception
            Log.w(TAG, "内核下载失败，尝试下一个地址：${lastError?.message}")
            runCatching { target.delete() }
        }
        throw IllegalStateException("内核下载失败：${lastError?.message ?: "未知错误"}")
    }

    /** 单地址流式下载（含速度统计），失败抛异常由调用方切换候选地址 */
    private fun downloadFrom(
        url: String,
        target: File,
        declaredSize: Long,
        onProgress: (Progress) -> Unit
    ) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "YunX-Desktop")
            .build()
        HttpClients.downloadClient().newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("响应体为空")
            val total = body.contentLength().takeIf { it > 0 } ?: declaredSize
            val buffer = ByteArray(256 * 1024)
            var downloaded = 0L
            val startedAt = System.currentTimeMillis()
            var lastReportAt = 0L
            var speed = 0L
            target.outputStream().buffered().use { out ->
                body.byteStream().use { input ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        // 进度节流 300ms：避免每 256KB 都回调一次（UI 侧也会重组）
                        if (now - lastReportAt >= 300L || (total > 0 && downloaded >= total)) {
                            // 全程均速，比瞬时速度稳定，进度条不跳
                            val elapsed = (now - startedAt).coerceAtLeast(1L)
                            speed = downloaded * 1000L / elapsed
                            lastReportAt = now
                            onProgress(Progress(Stage.DOWNLOADING, downloaded, total, speed, "正在下载内核…"))
                        }
                    }
                }
            }
            if (total > 0 && downloaded != total) {
                throw IllegalStateException("下载不完整（$downloaded/$total 字节）")
            }
            onProgress(Progress(Stage.DOWNLOADING, downloaded, total, speed, "内核下载完成"))
        }
    }

    /**
     * 校验 sha256：`digest` 形如 `sha256:xxxx`。
     * 长度不是 64（算法非 sha256 / 服务端未给）时跳过，不做无谓拦截。
     */
    private fun verifyDigest(file: File, digest: String) {
        val expected = digest.substringAfter(':', digest).trim().lowercase()
        if (expected.length != 64) return
        val actual = sha256(file)
        if (actual != expected) {
            runCatching { file.delete() }
            throw IllegalStateException("内核包校验失败（sha256 不匹配），已丢弃损坏的包")
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
