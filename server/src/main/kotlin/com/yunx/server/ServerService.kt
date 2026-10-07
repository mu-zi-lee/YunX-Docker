package com.yunx.server

import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.db.BookmarkEntity
import com.yunx.app.data.db.LinkHistoryEntity
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.*
import com.yunx.app.data.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
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
    private data class CloudSession(val platform: SharePlatform, val revision: Long,
        val files: MutableMap<String, ShareFile> = mutableMapOf(), val dirs: MutableSet<String> = mutableSetOf(),
        val mutex: Mutex = Mutex(), val created: Long = System.currentTimeMillis())
    private val cloudSessions = ConcurrentHashMap<String, CloudSession>()
    private val profiles = ConcurrentHashMap<String, JSONObject>()
    private val revisions = ConcurrentHashMap<String, Long>()
    private fun quark(): QuarkApi {
        var expected = credentials.credential("QUARK")
        return QuarkApi().apply { cookieSink = { updated -> credentials.updateCookie("QUARK", expected, updated); expected = updated } }
    }
    private fun uc(): UCApi {
        var expected = credentials.credential("UC")
        return UCApi().apply { cookieSink = { updated -> credentials.updateCookie("UC", expected, updated); expected = updated } }
    }
    private val github = GitHubApi(tokenProvider = { credentials.get("GITHUB").optString("accessToken") })

    fun accounts(): JSONArray = JSONArray(SharePlatform.entries.map { p ->
        JSONObject().put("platform", p.name).put("configured", credentials.credential(p.name).isNotBlank())
            .put("cloudSupported", p !in setOf(SharePlatform.GITHUB, SharePlatform.LANZOU, SharePlatform.ILANZOU))
            .put("authType", if (p == SharePlatform.XUNLEI) credentials.get(p.name).optString("authType") else "")
            .put("profile", profiles[p.name] ?: JSONObject.NULL)
    })

    fun saveAccount(body: JSONObject) {
        val platform = SharePlatform.valueOf(body.getString("platform"))
        val input = body.getJSONObject("credentials")
        val allowed = setOf("cookie", "accessToken", "refreshToken", "deviceId", "deviceSign", "captchaToken", "authType", "label")
        require(input.keySet().all { it in allowed }) { "未知凭证字段" }
        require(input.keySet().all { input.get(it) is String && input.getString(it).length <= 65536 }) { "凭证格式错误" }
        val next = if (input.length() == 0) JSONObject() else credentials.get(platform.name).apply {
            input.keySet().forEach { key ->
                val value = input.getString(key)
                if (value.isNotBlank() || key in setOf("authType", "label")) put(key, value)
            }
        }
        credentials.save(platform.name, next)
        revisions.merge(platform.name, 1L, Long::plus)
        sessions.clear()
        cloudSessions.clear()
        profiles.remove(platform.name)
    }

    fun settings(): JSONObject {
        val saved = credentials.get("_SETTINGS")
        return JSONObject().put("threads", saved.optInt("threads", System.getenv("YUNX_THREADS")?.toIntOrNull() ?: 16))
            .put("concurrency", saved.optInt("concurrency", System.getenv("YUNX_CONCURRENCY")?.toIntOrNull() ?: 3))
            .put("speedLimit", saved.optLong("speedLimit", System.getenv("YUNX_SPEED_LIMIT")?.toLongOrNull() ?: 0))
    }
    fun saveSettings(body: JSONObject): JSONObject {
        val threads = body.getInt("threads")
        val concurrency = body.getInt("concurrency")
        val speed = body.getLong("speedLimit")
        require(threads in 1..128 && concurrency in 1..10 && speed in 0..1_000_000_000_000L) { "下载设置超出范围" }
        val saved = JSONObject().put("threads", threads).put("concurrency", concurrency).put("speedLimit", speed)
        credentials.save("_SETTINGS", saved)
        return saved
    }
    suspend fun library(): JSONObject = JSONObject()
        .put("bookmarks", JSONArray(db.bookmarkDao().observeAll().first().map {
            JSONObject().put("id", it.id).put("link", it.link).put("title", it.title).put("password", it.pwd).put("platform", it.platform)
        }))
        .put("history", JSONArray(db.linkHistoryDao().observeAll().first().take(100).map {
            JSONObject().put("id", it.id).put("link", it.url).put("title", it.title).put("password", it.pwd).put("platform", it.platform)
        }))
    suspend fun libraryAction(body: JSONObject): JSONObject {
        val kind = body.getString("kind")
        require(kind in setOf("bookmarks", "history")) { "未知链接类型" }
        if (body.optString("action") == "remove") {
            if (kind == "bookmarks") db.bookmarkDao().delete(body.getLong("id"))
            else db.linkHistoryDao().delete(body.getLong("id"))
        } else {
            require(kind == "bookmarks") { "历史记录由解析生成" }
            val link = body.getString("link").trim()
            require(link.length in 1..10000 && (ShareLinkParser.parse(link) != null || GitHubLinkParser.parse(link) != null)) { "请输入有效分享链接" }
            require(body.optString("password").length <= 256 && body.optString("title").length <= 1000) { "链接信息过长" }
            val existing = db.bookmarkDao().observeAll().first()
            require(existing.size < 200) { "收藏数量已达到 200" }
            if (existing.none { it.link == link }) db.bookmarkDao().insert(BookmarkEntity(
                link = link, title = body.optString("title"), pwd = body.optString("password"),
                platform = ShareLinkParser.parse(link)?.platform?.name ?: "GITHUB"
            ))
        }
        return library()
    }
    suspend fun accountInfo(body: JSONObject): JSONObject = withTimeout(25000) {
        val p = SharePlatform.valueOf(body.getString("platform"))
        val c = credentials.credential(p.name)
        val revision = revisions[p.name] ?: 0L
        require(c.isNotBlank()) { "请先添加网盘账号" }
        val account = credentials.get(p.name)
        var name: String? = null
        val quota: QuotaInfo? = when (p) {
            SharePlatform.QUARK -> quark().let { name = it.fetchNickname(c); it.getQuota(credentials.credential(p.name)) }
            SharePlatform.UC -> uc().let { name = it.fetchNickname(c); it.getQuota(credentials.credential(p.name)) }
            SharePlatform.BAIDU -> BaiduApi().let { name = it.fetchNickname(c); it.getQuota(c) }
            SharePlatform.PAN115 -> Pan115Api().let { name = it.fetchNickname(c); it.getQuota(c) }
            SharePlatform.PAN123 -> Pan123Api().let { name = it.fetchNickname(c); it.getQuota(c) }
            SharePlatform.C139 -> C139Api().getQuota(c)
            SharePlatform.XUNLEI -> XunleiApi().getQuota(c, account.optString("deviceId"), account.optString("captchaToken"))
            SharePlatform.GUANGYA -> GuangYaApi().getQuota(c, GuangYaDevice(account.optString("deviceId"), account.optString("deviceSign")))
            else -> null
        }
        val result = JSONObject().put("nickname", name ?: account.optString("label"))
            .put("checkedAt", System.currentTimeMillis()).put("available", quota != null || name != null)
            .put("quota", quota?.let { JSONObject().put("used", it.used).put("total", it.total).put("trash", it.usedInTrash) } ?: JSONObject.NULL)
        require((revisions[p.name] ?: 0L) == revision) { "账号配置已更新，请刷新" }
        profiles[p.name] = result
        result
    }
    suspend fun cloudFiles(body: JSONObject): JSONObject {
        cloudSessions.entries.removeIf { System.currentTimeMillis() - it.value.created > 3600000 }
        val existing = body.optString("sessionId")
        val p = if (existing.isNotBlank()) cloudSessions[existing]?.platform ?: error("目录会话已失效，请重新打开网盘")
            else SharePlatform.valueOf(body.getString("platform"))
        val c = credentials.credential(p.name)
        require(c.isNotBlank()) { "请先添加网盘账号" }
        require(p !in setOf(SharePlatform.GITHUB, SharePlatform.LANZOU, SharePlatform.ILANZOU)) { "此平台暂不支持个人网盘浏览" }
        val root = when (p) { SharePlatform.BAIDU -> "/"; SharePlatform.C139, SharePlatform.XUNLEI -> ""; else -> "0" }
        val session = if (existing.isBlank()) CloudSession(p, revisions[p.name] ?: 0L).apply { dirs.add(root) }
            else cloudSessions[existing] ?: error("目录会话已失效")
        return session.mutex.withLock {
            require(session.revision == (revisions[p.name] ?: 0L)) { "账号配置已更新，请重新打开网盘" }
            val dir = if (existing.isBlank()) root else body.getString("directory")
            require(dir in session.dirs) { "请从已列出的文件夹进入" }
            val page = body.optInt("page", 1)
            require(page in 1..10000) { "页码无效" }
            val a = credentials.get(p.name)
            var more = false
            var cursor = ""
            val files: List<ShareFile> = when (p) {
                SharePlatform.QUARK -> quark().listCloudFiles(dir, c, page, 100)?.also { more = it.size == 100 } ?: error("无法获取文件，请检查凭证")
                SharePlatform.UC -> uc().listCloudFiles(dir, c, page, 100)?.also { more = it.size == 100 } ?: error("无法获取文件，请检查凭证")
                SharePlatform.BAIDU -> BaiduApi().listCloudFiles(dir, c)
                SharePlatform.PAN115 -> Pan115Api().listFiles(dir, c, (page - 1) * 100, 100).let { more = page * 100 < it.total; it.files }
                SharePlatform.PAN123 -> Pan123Api().listCloudFiles(dir, c, body.optString("cursor").ifBlank { "0" }).let {
                    cursor = it.second.orEmpty(); more = cursor.isNotBlank(); it.first
                }
                SharePlatform.C139 -> C139Api().listCloudFiles(dir, c, body.optString("cursor").ifBlank { null }).let {
                    more = it.second != null; cursor = it.second.orEmpty(); it.first
                }
                SharePlatform.GUANGYA -> GuangYaApi().apply { deviceIdProvider = { a.optString("deviceId") } }.listCloudFiles(c, dir)
                SharePlatform.XUNLEI -> {
                    require(a.optString("authType") != "webToken") { "个人网盘浏览需要迅雷 App 通道凭证" }
                    XunleiApi().getFiles(dir, c, a.optString("deviceId"), a.optString("captchaToken")) ?: error("无法获取迅雷目录")
                }
                else -> error("暂不支持")
            }
            require(session.revision == (revisions[p.name] ?: 0L)) { "账号配置已更新，请重试" }
            files.forEach { session.files[it.fid] = it; if (it.isdir) session.dirs.add(if (p == SharePlatform.BAIDU) it.fidToken else it.fid) }
            val id = existing.ifBlank { require(cloudSessions.size < 100) { "目录会话过多" }; UUID.randomUUID().toString() }
            cloudSessions[id] = session
            JSONObject().put("sessionId", id).put("platform", p.name).put("directory", dir).put("title", "我的网盘")
                .put("files", JSONArray(files.map { fileJson(it).put("directoryId", if (p == SharePlatform.BAIDU) it.fidToken else it.fid) }))
                .put("hasMore", more).put("cursor", cursor)
                .put("limited", p == SharePlatform.XUNLEI && files.size >= 500)
        }
    }
    suspend fun cloudDownload(body: JSONObject): JSONObject {
        val s = cloudSessions[body.getString("sessionId")] ?: error("目录会话已失效")
        return s.mutex.withLock {
            val c = credentials.credential(s.platform.name)
            require(s.revision == (revisions[s.platform.name] ?: 0L)) { "账号配置已更新，请重新打开网盘" }
            val f = s.files[body.getString("fid")] ?: error("请从已列出的文件中选择")
            require(!f.isdir) { "请进入文件夹选择文件" }
            val a = credentials.get(s.platform.name)
            val link = when (s.platform) {
                SharePlatform.QUARK -> quark().getDownloadLink(f.fid, c)
                SharePlatform.UC -> uc().cloudGetDownloadLink(f.fid, c)
                SharePlatform.BAIDU -> DownloadLink(f.fid, f.fname, BaiduApi().locateDownload(f.fidToken, c), f.fsize)
                SharePlatform.PAN115 -> Pan115Api().getDownloadLink(f, c)
                SharePlatform.PAN123 -> Pan123Api().getDownloadLink(f, c)
                SharePlatform.C139 -> C139Api().getDownloadUrl(f.fid, c)
                SharePlatform.GUANGYA -> GuangYaApi().apply { deviceIdProvider = { a.optString("deviceId") } }.getDownloadLink(c, f)
                SharePlatform.XUNLEI -> XunleiApi().getFileDetail(f.fid, c, a.optString("deviceId"), a.optString("captchaToken"))
                else -> null
            } ?: error("未取得下载地址，请检查账号权限")
            require(s.revision == (revisions[s.platform.name] ?: 0L)) { "账号配置已更新，请重新打开网盘" }
            JSONObject().put("id", downloads.enqueue(link.downloadUrl, f.fname, downloadHeaders(s.platform, link, credentials.credential(s.platform.name)),
                link.size, s.platform.name.lowercase()))
        }
    }

    private fun repo(p: SharePlatform): ShareResolveRepository {
        fun credential() = credentials.credential(p.name).takeIf { it.isNotBlank() }
        return when (p) {
            SharePlatform.QUARK -> QuarkResolveRepository(quark())
            SharePlatform.UC -> UCResolveRepository(uc())
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
        val history = db.linkHistoryDao().observeAll().first()
        history.filter { it.url == text }.forEach { db.linkHistoryDao().delete(it.id) }
        db.linkHistoryDao().insert(LinkHistoryEntity(url = text, title = share.title, platform = parsed.platform.name, pwd = pwd))
        db.linkHistoryDao().observeAll().first().drop(100).forEach { db.linkHistoryDao().delete(it.id) }
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
        .put("fid", f.fid).put("name", f.fname).put("size", f.fsize).put("directory", f.isdir).put("modified", f.modifyTime)
}
