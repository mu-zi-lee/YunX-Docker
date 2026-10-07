package com.yunx.macos

import com.yunx.app.AppContext
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.*
import com.yunx.app.data.network.HttpClients
import com.yunx.server.Credentials
import com.yunx.server.ServerService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class LocalDownloadTest {
    private val db get() = AppDatabase.get()
    private fun manager(directory: File, events: DownloadSystemEvents = DownloadSystemEvents.None) =
        DownloadManager(db.downloadTaskDao(), ChunkDownloader { HttpClients.downloadClient() },
            threadProvider = { 2 }, saveDirProvider = { directory.absolutePath }, systemEvents = events)

    private fun source(bytes: ByteArray, ranges: Boolean, slow: Boolean = false) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = if (ranges) request.getHeader("Range") else null
                val parts = range?.removePrefix("bytes=")?.split("-")
                val start = parts?.getOrNull(0)?.toIntOrNull() ?: 0
                val end = (parts?.getOrNull(1)?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
                val response = MockResponse().setResponseCode(if (range == null) 200 else 206)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(bytes.copyOfRange(start, end + 1)))
                if (range != null) response.setHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                if (slow) response.throttleBody(32768, 15, TimeUnit.MILLISECONDS)
                return response
            }
        }
        start()
    }

    private suspend fun finished(id: Long) = withTimeout(30000) {
        db.downloadTaskDao().observeAll().first { rows -> rows.any { it.id == id && it.status in listOf(3, 4) } }.first { it.id == id }
    }

    @Test fun rangeDownloadPreservesBytesAndSystemEvents() = runBlocking {
        AppContext.init()
        val data = ByteArray(768 * 1024) { (it % 251).toByte() }
        val output = File(AppContext.dataDir, "range-output").also { it.mkdirs() }
        var started = 0; var stopped = 0
        val events = object : DownloadSystemEvents {
            override fun started(keepAwake: Boolean) { started++ }
            override fun finished() { stopped++ }
        }
        val downloader = manager(output, events)
        source(data, true).use { server ->
            val id = downloader.enqueue(server.url("/file.bin").toString(), "中文 空格.bin")
            val task = finished(id)
            assertEquals(task.errorMsg, 3, task.status)
            assertArrayEquals(data, File(task.savePath).readBytes())
            downloader.shutdown()
            assertEquals(3, db.downloadTaskDao().get(id)!!.status)
            assertTrue(started > 0); assertTrue(stopped > 0)
        }
    }

    @Test fun pauseAndRestartResumesPersistedRangeParts() = runBlocking {
        AppContext.init()
        val data = ByteArray(4 * 1024 * 1024) { (it % 239).toByte() }
        val output = File(AppContext.dataDir, "resume-output").also { it.mkdirs() }
        var downloader = manager(output)
        source(data, true, true).use { server ->
            val id = downloader.enqueue(server.url("/resume.bin").toString(), "resume.bin", mapOf("X-Test" to "persisted"))
            withTimeout(10000) { db.downloadTaskDao().observeAll().first { it.any { row -> row.id == id && row.downloadedSize > 0 } } }
            downloader.pause(id)
            withTimeout(10000) { db.downloadTaskDao().observeAll().first { it.any { row -> row.id == id && row.status == 2 } } }
            val progress = db.downloadTaskDao().get(id)!!.downloadedSize
            assertTrue(progress > 0)
            downloader.shutdown()
            downloader = manager(output)
            downloader.start(id)
            val task = finished(id)
            assertEquals(task.errorMsg, 3, task.status)
            assertArrayEquals(data, File(task.savePath).readBytes())
            downloader.shutdown()
            var foundHeader = false
            repeat(server.requestCount) { if (server.takeRequest().getHeader("X-Test") == "persisted") foundHeader = true }
            assertTrue(foundHeader)
        }
    }

    @Test fun noRangeSourceFallsBackWithoutCorruptingFile() = runBlocking {
        AppContext.init()
        val data = ByteArray(256 * 1024) { (it % 223).toByte() }
        val output = File(AppContext.dataDir, "no-range-output").also { it.mkdirs() }
        val downloader = manager(output)
        source(data, false).use { server ->
            val id = downloader.enqueue(server.url("/plain.bin").toString(), "plain.bin")
            val task = finished(id)
            assertEquals(task.errorMsg, 3, task.status)
            assertArrayEquals(data, File(task.savePath).readBytes())
            downloader.remove(id, false)
            withTimeout(5000) { db.downloadTaskDao().observeAll().first { rows -> rows.none { it.id == id } } }
            assertTrue(File(task.savePath).exists())
            downloader.shutdown()
        }
    }

    @Test fun failureCanBeRetriedAndMagnetNeverStartsAnEngine() = runBlocking {
        AppContext.init()
        val output = File(AppContext.dataDir, "retry-output").also { it.mkdirs() }
        val downloader = manager(output)
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(403) }
            val id = downloader.enqueue(server.url("/retry.bin").toString(), "retry.bin")
            assertEquals(4, finished(id).status)
            server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setBody("retried") }
            downloader.start(id)
            withTimeout(10000) { db.downloadTaskDao().observeAll().first { rows -> rows.any { it.id == id && it.status != 4 } } }
            assertEquals(3, finished(id).status)
            val magnet = downloader.enqueue("magnet:?xt=urn:btih:test", "magnet")
            assertEquals(4, db.downloadTaskDao().get(magnet)!!.status)
            assertTrue(db.downloadTaskDao().get(magnet)!!.errorMsg.contains("不支持"))
            downloader.shutdown()
        }
    }

    @Test fun credentialsAreEncryptedAndCloudCapabilitiesAreShared() = runBlocking {
        AppContext.init()
        val file = File(AppContext.dataDir, "test-accounts.enc")
        val credentials = Credentials(file)
        val downloader = manager(File(AppContext.dataDir, "accounts-output"))
        val local = ServerService(db, downloader, credentials)
        val server = ServerService(db, downloader, credentials)
        local.saveAccount(JSONObject().put("platform", "QUARK").put("credentials", JSONObject().put("cookie", "test-private-cookie")))
        assertFalse(file.readText().contains("test-private-cookie"))
        assertEquals("test-private-cookie", Credentials(file).credential("QUARK"))
        assertTrue(local.accounts().objects().first { it.getString("platform") == "LANZOU" }.getBoolean("cloudSupported"))
        assertTrue(server.accounts().objects().first { it.getString("platform") == "LANZOU" }.getBoolean("cloudSupported"))
        local.saveAccount(JSONObject().put("platform", "QUARK").put("credentials", JSONObject()))
        assertEquals("", credentials.credential("QUARK"))
        downloader.shutdown()
    }
}
