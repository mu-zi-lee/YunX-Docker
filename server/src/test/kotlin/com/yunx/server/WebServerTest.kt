package com.yunx.server

import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.HttpClients
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.model.DownloadLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class WebServerTest {
    private val client = OkHttpClient()
    private var defaultCookie: String? = null
    private lateinit var server: WebServer
    private lateinit var manager: DownloadManager
    private lateinit var destination: File
    private lateinit var store: Credentials
    private lateinit var accountFile: File
    private lateinit var login: LoginAuth

    @Before
    fun setup() {
        AppContext.init()
        destination = Files.createTempDirectory("yunx-download-test").toFile()
        accountFile = File(destination, "accounts.enc")
        store = Credentials(accountFile)
        val db = AppDatabase.get()
        manager = DownloadManager(db.downloadTaskDao(), ChunkDownloader { HttpClients.downloadClient() },
            threadProvider = { 2 }, saveDirProvider = { destination.absolutePath },
            keepAwakeProvider = { false }, retryCountProvider = { 0 })
        login = LoginAuth(File(destination, "login-auth.json"), "test-password-only")
        server = WebServer(ServerService(db, manager, store), "admin", "test-password-only", InetSocketAddress("127.0.0.1", 0), login)
        server.start()
    }

    @After
    fun teardown() {
        server.stop()
        runBlocking { manager.shutdown() }
        destination.deleteRecursively()
    }

    private fun request(path: String, body: String? = null, authorized: Boolean = true, csrf: Boolean = true,
                        contentType: String = "application/json", crossSite: Boolean = false, cookie: String? = null): Pair<Int, String> {
        val req = Request.Builder().url("http://127.0.0.1:${server.port}$path")
        if (authorized) req.header("Cookie", defaultCookie ?: loginCookie().also { defaultCookie = it })
        if (csrf) req.header("X-YunX-Request", "1")
        if (crossSite) req.header("Sec-Fetch-Site", "cross-site")
        if (cookie != null) req.header("Cookie", cookie)
        if (body != null) req.post(body.toRequestBody(contentType.toMediaType()))
        return client.newCall(req.build()).execute().use { it.code to it.body!!.string() }
    }

    @Test
    fun `login shell is public but data requires authentication`() {
        assertEquals(200, request("/health", authorized = false).first)
        assertEquals(200, request("/", authorized = false).first)
        assertEquals(401, request("/api/tasks", authorized = false).first)
        assertContains(request("/").second, "YunX")
        assertEquals(200, request("/lucide.min.js").first)
        assertEquals(200, request("/icon.png").first)
        assertEquals(200, request("/fonts/space-mono.ttf", authorized = false).first)
        assertEquals(404, request("/../../Dockerfile").first)
    }

    private fun loginCookie(): String {
        val body = JSONObject().put("username", "admin").put("password", "test-password-only")
        val req = Request.Builder().url("http://127.0.0.1:${server.port}/api/auth/login")
            .header("X-YunX-Request", "1").post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(req).execute().use {
            assertEquals(200, it.code)
            val header = it.header("Set-Cookie")!!
            assertContains(header, "HttpOnly")
            assertContains(header, "SameSite=Strict")
            header.substringBefore(';')
        }
    }

    @Test
    fun `cookie login change password and logout invalidate sessions and persist hashes`() {
        val cookie = loginCookie().also { defaultCookie = it }
        assertEquals(200, request("/api/tasks", authorized = false, cookie = cookie).first)
        assertEquals(400, request("/api/auth/password", """{"current":"wrong-password","password":"new-test-password"}""",
            authorized = false, cookie = cookie).first)
        assertEquals(200, request("/api/auth/password", """{"current":"test-password-only","password":"new-test-password"}""",
            authorized = false, cookie = cookie).first)
        assertEquals(401, request("/api/tasks", authorized = false, cookie = cookie).first)
        assertEquals(401, request("/api/tasks").first)
        val persisted = File(destination, "login-auth.json")
        assertFalse(persisted.readText().contains("new-test-password"))
        val restored = LoginAuth(persisted, "test-password-only")
        assertTrue(restored.verify("new-test-password"))
        assertFalse(restored.verify("test-password-only"))
    }

    @Test
    fun `login writes require csrf and logout actually revokes the session`() {
        val body = """{"username":"admin","password":"test-password-only"}"""
        assertEquals(403, request("/api/auth/login", body, authorized = false, csrf = false).first)
        assertEquals(403, request("/api/auth/login", body, authorized = false, crossSite = true).first)
        assertEquals(401, request("/api/auth/login", """{"username":"admin","password":"wrong-password"}""", authorized = false).first)
        val cookie = loginCookie()
        assertEquals(200, request("/api/auth/logout", "{}", authorized = false, cookie = cookie).first)
        assertEquals(401, request("/api/tasks", authorized = false, cookie = cookie).first)
    }

    @Test
    fun `settings persist and cloud sessions reject unconfigured accounts and forged ids`() {
        assertEquals(200, request("/api/settings", """{"threads":8,"concurrency":2,"speedLimit":1048576}""").first)
        assertEquals(8, JSONObject(request("/api/settings").second).getInt("threads"))
        assertEquals(8, Credentials(accountFile).get("_SETTINGS").getInt("threads"))
        assertEquals(400, request("/api/settings", """{"threads":129,"concurrency":2,"speedLimit":0}""").first)
        assertEquals(400, request("/api/account-info", """{"platform":"QUARK"}""").first)
        assertEquals(400, request("/api/cloud/files", """{"platform":"QUARK"}""").first)
        assertEquals(400, request("/api/cloud/download", """{"sessionId":"forged","fid":"1"}""").first)
    }
    @Test
    fun `saved links use persistent desktop storage and can be removed`() {
        val link = "https://pan.quark.cn/s/123456789abc"
        val body = JSONObject().put("kind", "bookmarks").put("link", link).put("title", "测试收藏").put("password", "1234")
        assertEquals(200, request("/api/library", body.toString()).first)
        val saved = JSONObject(request("/api/library").second).getJSONArray("bookmarks")
        val item = (0 until saved.length()).map { saved.getJSONObject(it) }.first { it.getString("link") == link }
        assertEquals("1234", item.getString("password"))
        assertEquals(200, request("/api/library", JSONObject().put("kind", "bookmarks").put("action", "remove")
            .put("id", item.getLong("id")).toString()).first)
        assertEquals(400, request("/api/library", """{"kind":"bookmarks","link":"file:///etc/passwd"}""").first)
    }

    @Test
    fun `writes reject cross site form requests and oversized bodies`() {
        val body = """{"platform":"QUARK","credentials":{"cookie":"secret"}}"""
        assertEquals(403, request("/api/accounts", body, csrf = false).first)
        assertEquals(403, request("/api/accounts", body, crossSite = true).first)
        assertEquals(415, request("/api/accounts", body, contentType = "text/plain").first)
        assertEquals(413, request("/api/accounts", "x".repeat(262145)).first)
    }

    @Test
    fun `credentials persist encrypted without echoing secrets`() {
        val secret = "BDUSS=test-secret-cookie"
        val body = JSONObject().put("platform", "BAIDU").put("credentials", JSONObject().put("cookie", secret))
        assertEquals(200, request("/api/accounts", body.toString()).first)
        assertFalse(accountFile.readText().contains(secret))
        assertEquals(secret, Credentials(accountFile).credential("BAIDU"))
        val accounts = request("/api/accounts").second
        assertFalse(accounts.contains(secret))
        assertTrue(JSONArray(accounts).let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.first { it.getString("platform") == "BAIDU" }.getBoolean("configured")
        })
        assertEquals(200, request("/api/accounts", """{"platform":"BAIDU","credentials":{}}""").first)
        assertEquals("", Credentials(accountFile).credential("BAIDU"))
    }

    @Test
    fun `invalid sessions and unsafe protocols cannot enqueue`() {
        assertEquals(400, request("/api/download", """{"sessionId":"missing","fid":"fake"}""").first)
        assertEquals(400, request("/api/direct", """{"url":"file:///etc/passwd"}""").first)
        assertEquals(400, request("/api/direct", """{"url":"http://user:test-password@example.com/file"}""").first)
    }

    @Test
    fun `115 requires account cookie plus CDN cookie`() {
        val headers = downloadHeaders(SharePlatform.PAN115, DownloadLink("fid", "file", "https://example.com", 1,
            guestCookie = "cdn=cookie"), "UID=account")
        assertContains(headers.getValue("Cookie"), "UID=account")
        assertContains(headers.getValue("Cookie"), "cdn=cookie")
    }

    @Test
    fun `real range download survives pause and resumes to identical bytes`() = runBlocking {
        val data = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        val source = MockWebServer()
        source.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                val match = Regex("""bytes=(\d+)-(\d*)""").matchEntire(range ?: "")
                val start = match?.groupValues?.get(1)?.toInt() ?: 0
                val end = match?.groupValues?.get(2)?.takeIf { it.isNotBlank() }?.toInt() ?: data.lastIndex
                return MockResponse().setResponseCode(if (match == null) 200 else 206)
                    .setHeader("Accept-Ranges", "bytes").setHeader("Content-Length", end - start + 1)
                    .apply { if (match != null) setHeader("Content-Range", "bytes $start-$end/${data.size}") }
                    .setBody(Buffer().write(data, start, end - start + 1))
                    .throttleBody(128 * 1024, 50, TimeUnit.MILLISECONDS)
            }
        }
        source.start()
        try {
            val created = request("/api/direct", JSONObject().put("url", source.url("/file").toString())
                .put("filename", "file.bin").toString())
            assertEquals(200, created.first)
            val id = JSONObject(created.second).getLong("id")
            delay(250)
            assertEquals(200, request("/api/tasks", """{"id":$id,"action":"pause"}""").first)
            var paused = false
            repeat(100) {
                if (!paused) {
                    paused = AppDatabase.get().downloadTaskDao().get(id)?.status == 2
                    if (!paused) delay(50)
                }
            }
            assertTrue(paused, "Task must pause")
            assertEquals(200, request("/api/tasks", """{"id":$id,"action":"resume"}""").first)
            var completed = false
            repeat(200) {
                if (!completed) {
                    val task = AppDatabase.get().downloadTaskDao().get(id)!!
                    if (task.status == 4) fail(task.errorMsg)
                    completed = task.status == 3
                    if (!completed) delay(50)
                }
            }
            assertTrue(completed, "Task must finish")
            assertContentEquals(data, File(destination, "file.bin").readBytes())
            assertEquals(400, request("/api/tasks", """{"id":$id,"action":"pause"}""").first)
            assertEquals(200, request("/api/tasks", """{"id":$id,"action":"remove"}""").first)
            assertTrue(File(destination, "file.bin").exists(), "Removing a record must preserve downloaded files")
        } finally { source.shutdown() }
    }
}
