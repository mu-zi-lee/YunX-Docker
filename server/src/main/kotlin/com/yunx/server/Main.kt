package com.yunx.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.HttpClients
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class WebServer(
    private val service: ServerService,
    private val username: String,
    private val password: String,
    address: InetSocketAddress,
    private val login: LoginAuth = LoginAuth(null, password)
) {
    init {
        require(username.isNotBlank() && ':' !in username) { "YUNX_USERNAME must be nonempty and cannot contain ':'" }
        require(password.length >= 12) { "Set YUNX_PASSWORD to at least 12 characters" }
    }
    private val server = HttpServer.create(address, 64)
    private val executor = ThreadPoolExecutor(
        8, 8, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64), ThreadPoolExecutor.CallerRunsPolicy()
    )
    val port: Int get() = server.address.port

    init {
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                try { handle(exchange) }
                catch (_: BodyTooLarge) { json(exchange, 413, JSONObject().put("error", "请求内容过大")) }
                catch (e: Exception) {
                    json(exchange, 400, JSONObject().put("error", LogRedactor.line(e.message ?: "请求失败").take(1000)))
                }
            } finally { exchange.close() }
        }
    }

    fun start() = server.start()
    fun stop() {
        server.stop(1)
        executor.shutdownNow()
    }

    private fun authenticated(exchange: HttpExchange): Boolean {
        return login.valid(sessionToken(exchange))
    }
    private fun sessionToken(e: HttpExchange): String? = e.requestHeaders.getFirst("Cookie")?.split(';')
        ?.map { it.trim() }?.firstOrNull { it.startsWith("yunx_session=") }?.substringAfter('=')
    private fun sessionCookie(e: HttpExchange, token: String, lifetime: Int = 43200) {
        val secure = if (e.requestHeaders.getFirst("X-Forwarded-Proto") == "https") "; Secure" else ""
        e.responseHeaders.add("Set-Cookie", "yunx_session=$token; Path=/; HttpOnly; SameSite=Strict; Max-Age=$lifetime$secure")
    }

    private fun handle(e: HttpExchange) {
        e.responseHeaders.set("X-Content-Type-Options", "nosniff")
        e.responseHeaders.set("Referrer-Policy", "no-referrer")
        e.responseHeaders.set("X-Frame-Options", "DENY")
        e.responseHeaders.set("Cache-Control", "no-store")
        e.responseHeaders.set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        val path = e.requestURI.path
        if (path == "/health" && e.requestMethod == "GET") {
            json(e, 200, JSONObject().put("status", "ok"))
            return
        }
        if (e.requestMethod == "GET" && !path.startsWith("/api/")) {
            when (path) {
                "/", "/login" -> resource(e, "index.html", "text/html; charset=utf-8")
                "/app.js", "/lucide.min.js" -> resource(e, path.drop(1), "text/javascript; charset=utf-8")
                "/style.css" -> resource(e, "style.css", "text/css; charset=utf-8")
                "/icon.png" -> resource(e, "icon.png", "image/png")
                "/fonts/space-grotesk.ttf", "/fonts/space-mono.ttf" -> resource(e, path.drop(1), "font/ttf")
                else -> json(e, 404, JSONObject().put("error", "页面不存在"))
            }
            return
        }
        if (path == "/api/auth/session" && e.requestMethod == "GET") {
            val valid = authenticated(e)
            json(e, 200, JSONObject().put("authenticated", valid).put("username", if (valid) username else "")
                .put("passwordManaged", login.managed))
            return
        }
        if (path != "/api/auth/login" && !authenticated(e)) {
            json(e, 401, JSONObject().put("error", "请登录"))
            return
        }
        if (e.requestMethod == "GET") {
            when (path) {
                "/" -> resource(e, "index.html", "text/html; charset=utf-8")
                "/app.js" -> resource(e, "app.js", "text/javascript; charset=utf-8")
                "/lucide.min.js" -> resource(e, "lucide.min.js", "text/javascript; charset=utf-8")
                "/style.css" -> resource(e, "style.css", "text/css; charset=utf-8")
                "/icon.png" -> resource(e, "icon.png", "image/png")
                "/api/accounts" -> json(e, 200, service.accounts())
                "/api/tasks" -> json(e, 200, runBlocking { service.tasks() })
                "/api/settings" -> json(e, 200, service.settings())
                "/api/library" -> json(e, 200, runBlocking { service.library() })
                else -> json(e, 404, JSONObject().put("error", "页面不存在"))
            }
            return
        }
        if (e.requestMethod != "POST") {
            json(e, 405, JSONObject().put("error", "不支持此请求方式"))
            return
        }
        // Cross-origin forms cannot supply this header; CORS preflight is never allowed.
        if (e.requestHeaders.getFirst("X-YunX-Request") != "1" ||
            e.requestHeaders.getFirst("Sec-Fetch-Site") == "cross-site") {
            json(e, 403, JSONObject().put("error", "请求来源不允许"))
            return
        }
        if (e.requestHeaders.getFirst("Content-Type")?.substringBefore(';') != "application/json") {
            json(e, 415, JSONObject().put("error", "需要 JSON 请求"))
            return
        }
        val bytes = e.requestBody.readNBytes(262145)
        if (bytes.size > 262144) throw BodyTooLarge()
        val body = JSONObject(String(bytes, Charsets.UTF_8))
        if (path == "/api/auth/login") {
            val token = if (body.optString("username") == username)
                login.login(body.optString("password"), e.remoteAddress.address.hostAddress) else {
                login.login("", e.remoteAddress.address.hostAddress); null
            }
            if (token == null) { json(e, 401, JSONObject().put("error", "用户名或密码不正确，连续失败后请稍后重试")); return }
            sessionCookie(e, token)
            json(e, 200, JSONObject().put("ok", true)); return
        }
        if (path == "/api/auth/logout") {
            login.logout(sessionToken(e)); sessionCookie(e, "", 0)
            json(e, 200, JSONObject().put("ok", true)); return
        }
        if (path == "/api/auth/password") {
            login.change(body.getString("current"), body.getString("password"))
            sessionCookie(e, "", 0)
            json(e, 200, JSONObject().put("ok", true)); return
        }
        val result: Any = runBlocking {
            when (path) {
                "/api/accounts" -> { service.saveAccount(body); JSONObject().put("ok", true) }
                "/api/resolve" -> service.resolve(body)
                "/api/files" -> service.list(body)
                "/api/download" -> service.enqueue(body)
                "/api/direct" -> service.direct(body)
                "/api/tasks" -> { service.taskAction(body); JSONObject().put("ok", true) }
                "/api/account-info" -> service.accountInfo(body)
                "/api/cloud/files" -> service.cloudFiles(body)
                "/api/cloud/download" -> service.cloudDownload(body)
                "/api/settings" -> service.saveSettings(body)
                "/api/library" -> service.libraryAction(body)
                else -> { json(e, 404, JSONObject().put("error", "接口不存在")); return@runBlocking null }
            }
        } ?: return
        json(e, 200, result)
    }

    private fun resource(e: HttpExchange, name: String, type: String) {
        val bytes = javaClass.getResourceAsStream("/web/$name")?.use { it.readBytes() }
            ?: error("Missing web resource")
        respond(e, 200, type, bytes)
    }

    private fun json(e: HttpExchange, status: Int, value: Any) =
        respond(e, status, "application/json; charset=utf-8", value.toString().toByteArray(Charsets.UTF_8))

    private fun respond(e: HttpExchange, status: Int, type: String, bytes: ByteArray) {
        e.responseHeaders.set("Content-Type", type)
        e.sendResponseHeaders(status, bytes.size.toLong())
        e.responseBody.write(bytes)
    }

    private class BodyTooLarge : RuntimeException()
}

fun main() {
    val username = System.getenv("YUNX_USERNAME") ?: "admin"
    AppContext.init()
    val authFile = File(AppContext.dataDir, "login-auth.json")
    val configured = System.getenv("YUNX_PASSWORD")?.takeIf { it.isNotBlank() }
    val password = if (authFile.exists() && configured == null) "persisted-login-placeholder"
        else DeploymentPassword.load(AppContext.dataDir, configured)
    val login = LoginAuth(authFile, password, configured != null)
    val downloadDir = File(System.getenv("YUNX_DOWNLOAD_DIR") ?: "/downloads").canonicalFile
    check(downloadDir.isDirectory || downloadDir.mkdirs()) { "Cannot create download directory" }
    check(downloadDir.canWrite()) { "Download directory is not writable by the container user; check the NAS directory owner or ACL" }
    val db = AppDatabase.get()
    val store = Credentials(File(AppContext.dataDir, "server-credentials.enc"))
    runBlocking { db.downloadTaskDao().markInterruptedAsPaused() }
    val downloads = DownloadManager(
        db.downloadTaskDao(), ChunkDownloader { HttpClients.downloadClient() },
        threadProvider = { store.get("_SETTINGS").optInt("threads", System.getenv("YUNX_THREADS")?.toIntOrNull() ?: 16).coerceIn(1, 128) },
        saveDirProvider = { downloadDir.absolutePath },
        concurrencyProvider = { store.get("_SETTINGS").optInt("concurrency", System.getenv("YUNX_CONCURRENCY")?.toIntOrNull() ?: 3).coerceIn(1, 10) },
        speedLimitProvider = { store.get("_SETTINGS").optLong("speedLimit", System.getenv("YUNX_SPEED_LIMIT")?.toLongOrNull() ?: 0L).coerceAtLeast(0) },
        keepAwakeProvider = { false }, showSpeedProvider = { false },
        externalEngine = com.yunx.app.data.gopeed.GopeedDownloadEngine
    )
    val service = ServerService(db, downloads, store)
    val server = WebServer(
        service, username, password,
        InetSocketAddress(System.getenv("YUNX_HOST") ?: "0.0.0.0", System.getenv("YUNX_PORT")?.toInt() ?: 8080), login
    )
    Runtime.getRuntime().addShutdownHook(Thread {
        server.stop()
        runBlocking { downloads.shutdown() }
    })
    server.start()
    println("YunX server listening on port ${server.port}; downloads: $downloadDir")
}
