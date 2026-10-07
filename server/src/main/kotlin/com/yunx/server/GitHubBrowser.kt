package com.yunx.server

import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubLinkType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A bounded, read-only browsing session. Only entries returned by GitHub can be opened or downloaded. */
class GitHubBrowser(private val api: GitHubApi) {
    private data class Directory(val sha: String, val path: String)
    private class Session(val owner: String, val repo: String, val ref: String) {
        var releaseFilter: String? = null
        val created = System.currentTimeMillis()
        val mutex = Mutex()
        val directories = mutableMapOf<String, Directory>()
        val downloads = mutableMapOf<String, JSONObject>()
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    fun clear() = sessions.clear()

    suspend fun resolve(target: GitHubLinkType.Repository): JSONObject {
        sessions.entries.removeIf { System.currentTimeMillis() - it.value.created > 3_600_000 }
        require(sessions.size < 100) { "GitHub 会话过多，请稍后再试" }
        val repo = api.getRepo(target.owner, target.repo) ?: error("无法读取 GitHub 仓库")
        val parts = target.subPath.orEmpty().split('/').filter { it.isNotEmpty() }
            .map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        var ref = repo.defaultBranch
        var path = ""
        if (parts.firstOrNull() in setOf("tree", "blob")) {
            require(parts.size > 1) { "GitHub 链接缺少版本" }
            // Refs may contain slashes. Resolve the longest valid ref before traversing paths.
            var found = false
            for (count in (parts.size - 1).coerceAtMost(32) downTo 1) {
                val candidate = parts.drop(1).take(count).joinToString("/")
                if (api.getTree(target.owner, target.repo, encode(candidate)) != null) {
                    ref = candidate
                    path = parts.drop(1 + count).joinToString("/")
                    found = true
                    break
                }
            }
            require(found) { "无法读取 GitHub 分支或版本" }
        }
        val session = Session(target.owner, target.repo, ref)
        if (parts.firstOrNull() == "releases") {
            session.releaseFilter = when (parts.getOrNull(1)) {
                "latest" -> "@latest"
                "tag" -> parts.drop(2).joinToString("/").also { require(it.isNotBlank()) { "缺少 Release 版本" } }
                else -> null
            }
        }
        session.directories[""] = Directory(encode(ref), "")
        var current = ""
        for (component in path.split('/').filter { it.isNotEmpty() }) {
            val directory = session.directories.getValue(if (current.isEmpty()) "" else "tree:$current")
            val entry = api.getTree(target.owner, target.repo, directory.sha)
                ?.firstOrNull { it.path == component } ?: error("GitHub 文件路径不存在")
            current = if (current.isEmpty()) component else "$current/$component"
            if (entry.type == "tree") session.directories["tree:$current"] = Directory(entry.sha, current)
            else {
                require(current == path && entry.type == "blob") { "GitHub 路径不是目录或文件" }
                return JSONObject().put("directUrl", raw(session, current)).put("filename", component)
            }
        }
        val id = UUID.randomUUID().toString()
        sessions[id] = session
        return try {
            list(id, if (parts.firstOrNull() == "releases") "@releases" else if (current.isEmpty()) "" else "tree:$current")
        } catch (e: Exception) {
            sessions.remove(id)
            throw e
        }
    }

    fun contains(id: String) = sessions.containsKey(id)
    suspend fun list(id: String, directoryId: String): JSONObject {
        val session = sessions[id] ?: error("GitHub 会话已失效，请重新解析")
        return session.mutex.withLock {
            val files = mutableListOf<JSONObject>()
            if (directoryId == "@releases") {
                for (page in 1..100) {
                    val releases = api.getReleases(session.owner, session.repo, page)
                        ?: error("无法读取 GitHub Releases")
                    val matching = when (session.releaseFilter) {
                        null -> releases
                        "@latest" -> releases.filter { !it.prerelease && !it.draft }.take(1)
                        else -> releases.filter { it.tagName == session.releaseFilter }
                    }
                    matching.flatMap { it.assets }.forEach { asset ->
                        val key = "asset:${asset.downloadUrl}"
                        val file = file(key, asset.name, false, asset.size).put("url", asset.downloadUrl)
                        session.downloads[key] = file
                        files += file
                    }
                    if (matching.isNotEmpty() && session.releaseFilter != null || releases.size < 100) break
                }
            } else {
                val dir = session.directories[directoryId] ?: error("请从已列出的 GitHub 目录进入")
                val entries = api.getTree(session.owner, session.repo, dir.sha) ?: error("无法读取 GitHub 目录")
                entries.filter { it.type == "tree" || it.type == "blob" }.forEach { entry ->
                    val path = if (dir.path.isEmpty()) entry.path else "${dir.path}/${entry.path}"
                    val key = "${entry.type}:$path"
                    val item = file(key, entry.path, entry.type == "tree", entry.size ?: 0)
                    if (entry.type == "tree") session.directories[key] = Directory(entry.sha, path)
                    else session.downloads[key] = item.put("url", raw(session, path))
                    files += item
                }
                if (directoryId.isEmpty()) {
                    files += file("@releases", "Releases", true, 0)
                    val archive = file("@archive", "${session.repo}-${session.ref.replace('/', '-')}.zip", false, 0)
                        .put("url", "https://github.com/${session.owner}/${session.repo}/archive/${encode(session.ref)}.zip")
                    session.downloads["@archive"] = archive
                    files += archive
                }
            }
            JSONObject().put("sessionId", id).put("platform", "GITHUB").put("title", "${session.owner}/${session.repo}")
                .put("directory", directoryId).put("files", JSONArray(files))
        }
    }
    suspend fun download(id: String, fid: String): JSONObject {
        val session = sessions[id] ?: error("GitHub 会话已失效，请重新解析")
        return session.mutex.withLock {
            JSONObject((session.downloads[fid] ?: error("请从已列出的 GitHub 文件中选择")).toString())
        }
    }
    private fun raw(s: Session, path: String) =
        "https://raw.githubusercontent.com/${s.owner}/${s.repo}/${encode(s.ref)}/${path.split('/').joinToString("/") { encode(it) }}"
    private fun file(id: String, name: String, directory: Boolean, size: Long) =
        JSONObject().put("fid", id).put("name", name).put("directory", directory).put("size", size).put("modified", "")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
