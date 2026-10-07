package com.yunx.server

import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.*
import com.yunx.app.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ServerService(
    private val db: AppDatabase,
    val downloads: DownloadManager,
    private val credentials: Credentials
) {
    private data class BrowserSession(
        val platform: SharePlatform,
        val source: String,
        val repo: ShareResolveRepository,
        val share: ShareSession,
        val files: MutableMap<String, ShareFile> = mutableMapOf(),
        val dirs: MutableSet<String> = mutableSetOf(),
        val mutex: Mutex = Mutex(),
        val created: Long = System.currentTimeMillis()
    )
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private val sessionLock = Mutex()
    private val github = GitHubApi(tokenProvider = { credentials.get("GITHUB").optString("accessToken") })

    fun accounts(): JSONArray = JSONArray(SharePlatform.entries.map { p ->
        JSONObject().put("platform", p.name).put("configured", credentials.get(p.name).length() > 0)
    })

    fun saveAccount(body: JSONObject) {
        val platform = SharePlatform.valueOf(body.getString("platform"))
        val input = body.getJSONObject("credentials")
        val allowed = setOf("cookie", "accessToken", "refreshToken", "deviceId", "captchaToken", "authType")
        require(input.keySet().all { it in allowed }) { "未知凭证字段" }
        require(input.keySet().all { input.get(it) is String && input.getString(it).length <= 65536 }) { "凭证格式错误" }
        credentials.save(platform.name, input)
        sessions.clear()
    }

    private fun repo(p: SharePlatform): ShareResolveRepository {
        fun credential() = credentials.credential(p.name).takeIf { it.isNotBlank() }
        fun saveCookie(cookie: String) {
            if (credential() != null) credentials.save(p.name, credentials.get(p.name).put("cookie", cookie))
        }
        return when (p) {
            SharePlatform.QUARK -> QuarkResolveRepository(QuarkApi().apply { cookieSink = ::saveCookie })
            SharePlatform.UC -> UCResolveRepository(UCApi().apply { cookieSink = ::saveCookie })
            SharePlatform.BAIDU -> BaiduResolveRepository(BaiduApi())
            SharePlatform.C139 -> C139ResolveRepository(C139Api())
            SharePlatform.PAN123 -> Pan123ResolveRepository(Pan123Api()) { credential() }
            SharePlatform.PAN115 -> Pan115ResolveRepository(Pan115Api())
            SharePlatform.GUANGYA -> GuangYaResolveRepository(GuangYaApi()) { credential() }
            SharePlatform.ILANZOU -> {
                val api = ILanzouApi()
                ILanzouResolveRepository(api, ILanzouAccountRepository(db.ilanzouAccountDao(), api))
            }
            SharePlatform.LANZOU -> LanzouResolveRepository(LanzouApi())
            SharePlatform.XUNLEI -> {
                val api = XunleiApi()
                XunleiResolveRepository(
                    api, { credential() },
                    { credentials.get(p.name).optString("deviceId").takeIf { it.isNotBlank() } },
                    { credentials.get(p.name).optString("captchaToken") },
                    {
                        val account = credentials.get(p.name)
                        val fresh = api.refreshToken(account.optString("refreshToken"), account.optString("deviceId"), account.optString("authType"))
                        if (fresh != null) credentials.save(p.name, account.put("accessToken", fresh.first).put("refreshToken", fresh.second))
                        fresh
                    }
                )
            }
            SharePlatform.GITHUB -> error("请使用 GitHub 仓库入口")
        }
    }

    suspend fun resolve(body: JSONObject): JSONObject = sessionLock.withLock {
        val text = body.getString("link").trim()
        val parsed = ShareLinkParser.parse(text)
        if (parsed == null) return@withLock githubResolve(text)
        sessions.entries.removeIf { System.currentTimeMillis() - it.value.created > 3_600_000 }
        require(sessions.size < 100) { "分享会话过多，请稍后再试" }
        val repository = repo(parsed.platform)
        val pwd = body.optString("password").ifBlank { parsed.pwd.orEmpty() }
        val share = repository.createSession(text, pwd, credentials.credential(parsed.platform.name)).getOrThrow()
        val root = when (parsed.platform) {
            SharePlatform.BAIDU, SharePlatform.GUANGYA, SharePlatform.ILANZOU, SharePlatform.LANZOU -> ""
            else -> "0"
        }
        val session = BrowserSession(parsed.platform, text, repository, share)
        session.dirs.add(root)
        val id = UUID.randomUUID().toString()
        val response = list(session, root).put("sessionId", id)
        sessions[id] = session
        response
    }

    private suspend fun list(s: BrowserSession, directory: String): JSONObject {
        require(directory in s.dirs) { "请从已列出的文件夹进入" }
        val files = s.repo.listFiles(s.share, directory, credentials.credential(s.platform.name)).getOrThrow()
        files.forEach { s.files[it.fid] = it; if (it.isdir) s.dirs.add(it.fid) }
        return JSONObject().put("title", s.share.title).put("platform", s.platform.name)
            .put("directory", directory).put("files", JSONArray(files.map(::fileJson)))
    }

    suspend fun list(body: JSONObject): JSONObject {
        val s = sessions[body.getString("sessionId")] ?: error("分享会话已失效，请重新解析")
        return s.mutex.withLock { list(s, body.getString("directory")) }
    }

    suspend fun enqueue(body: JSONObject): JSONObject {
        val s = sessions[body.getString("sessionId")] ?: error("分享会话已失效，请重新解析")
        return s.mutex.withLock {
            val file = s.files[body.getString("fid")] ?: error("文件未列出，请重新解析")
            require(!file.isdir) { "请进入文件夹选择文件" }
            val credential = credentials.credential(s.platform.name)
            val link = if (credential.isBlank() && s.platform in setOf(
                    SharePlatform.QUARK, SharePlatform.UC, SharePlatform.GUANGYA,
                    SharePlatform.ILANZOU, SharePlatform.LANZOU
                )) {
                s.repo.getGuestShareDownloadLink(s.share, file).getOrThrow()
            } else {
                val withoutSave = s.repo.getShareDownloadLinkWithoutSave(s.share, file, credential)
                if (withoutSave.isFailure && s.platform == SharePlatform.QUARK)
                    s.repo.getShareDownloadLink(s.share, file, credential).getOrThrow()
                else withoutSave.getOrThrow()
            }
            val latestCredential = credentials.credential(s.platform.name)
            val id = downloads.enqueue(
                link.downloadUrl, file.fname, downloadHeaders(s.platform, link, latestCredential),
                link.size, s.platform.name.lowercase(), s.source
            ) {
                link.cleanupDirFid?.let { s.repo.cleanupTempDir(it, latestCredential) }
            }
            JSONObject().put("id", id)
        }
    }

    suspend fun direct(body: JSONObject): JSONObject {
        val url = body.getString("url")
        val uri = URI(url)
        require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null) { "请输入 HTTP/HTTPS 下载地址" }
        val name = body.optString("filename").ifBlank { uri.path.substringAfterLast('/').ifBlank { "download" } }
        return JSONObject().put("id", downloads.enqueue(url, name))
    }

    suspend fun tasks(): JSONArray = JSONArray(downloads.tasks.first().map { t ->
        val stats = downloads.stats.value[t.id]
        JSONObject().put("id", t.id).put("filename", t.fileName).put("status", t.status)
            .put("downloaded", t.downloadedSize).put("total", t.totalSize)
            .put("speed", stats?.speed ?: 0).put("mergePercent", stats?.mergePercent ?: -1)
            .put("error", t.errorMsg).put("savePath", t.savePath)
    })

    suspend fun taskAction(body: JSONObject) {
        val id = body.getLong("id")
        val task = db.downloadTaskDao().get(id) ?: error("任务不存在")
        when (body.getString("action")) {
            "pause" -> {
                require(task.status in listOf(0, 1)) { "只有运行中任务可以暂停" }
                downloads.pause(id)
            }
            "resume" -> {
                require(task.status in listOf(2, 4)) { "只有暂停或失败任务可以继续" }
                downloads.start(id)
            }
            "remove" -> downloads.remove(id, false)
            else -> error("未知任务操作")
        }
    }

    private suspend fun githubResolve(text: String): JSONObject {
        return when (val target = GitHubLinkParser.parse(text)) {
            is GitHubLinkType.DirectFile -> JSONObject().put("directUrl", target.url).put("filename", target.fileName)
            is GitHubLinkType.Repository -> {
                val repo = github.getRepo(target.owner, target.repo) ?: error("无法读取 GitHub 仓库")
                val releases = github.getReleases(target.owner, target.repo) ?: error("无法读取 GitHub Releases")
                val files = releases.flatMap { it.assets }.map {
                    JSONObject().put("name", it.name).put("size", it.size).put("url", it.downloadUrl)
                }.toMutableList()
                val branch = java.net.URLEncoder.encode(repo.defaultBranch, "UTF-8").replace("+", "%20")
                files.add(JSONObject().put("name", "${repo.name}-${repo.defaultBranch}.zip").put("size", 0)
                    .put("url", "https://github.com/${repo.fullName}/archive/refs/heads/$branch.zip"))
                JSONObject().put("title", repo.fullName).put("platform", "GITHUB").put("assets", JSONArray(files))
            }
            is GitHubLinkType.Account -> {
                val repos = mutableListOf<GitHubRepo>()
                for (page in 1..100) {
                    val batch = github.getUserRepos(target.owner, page) ?: error("无法读取 GitHub 账号仓库")
                    repos.addAll(batch)
                    if (batch.size < 100) break
                }
                JSONObject().put("title", target.owner).put("repositories", JSONArray(repos.map {
                    JSONObject().put("name", it.fullName).put("url", "https://github.com/${it.fullName}")
                }))
            }
            null -> error("未识别到分享链接")
        }
    }

    private fun fileJson(f: ShareFile) = JSONObject()
        .put("fid", f.fid).put("name", f.fname).put("size", f.fsize).put("directory", f.isdir)
}
