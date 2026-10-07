package com.yunx.app.data.network

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.junit.Test
import java.net.URLDecoder
import kotlin.test.*

class BaiduFileManagementTest {
    @Test fun managementRequestsEncodeQuotedChinesePathsAsValidJson() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"errno":0,"result":{"bdstoken":"test-token"}}"""))
        repeat(3) { server.enqueue(MockResponse().setBody("""{"errno":0}""")) }
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val original = chain.request().url
            val replacement = server.url(original.encodedPath).newBuilder().encodedQuery(original.encodedQuery).build()
            chain.proceed(chain.request().newBuilder().url(replacement).build())
        }.build()
        try {
            val api = BaiduApi(clientProvider = { client })
            val path = "/资料/引号\"与 空格.txt"
            assertTrue(api.renameFile(path, "新\"名.txt", "fake-cookie"))
            assertTrue(api.moveFiles(listOf(path), "/目标 \"目录", "fake-cookie"))
            assertTrue(api.deleteFiles(listOf(path), "fake-cookie"))
            server.takeRequest()
            fun payload() = JSONArray(URLDecoder.decode(server.takeRequest().body.readUtf8().substringAfter("filelist="), "UTF-8"))
            val rename = payload().getJSONObject(0)
            assertEquals(path, rename.getString("path"))
            assertEquals("新\"名.txt", rename.getString("newname"))
            val move = payload().getJSONObject(0)
            assertEquals(path, move.getString("path"))
            assertEquals("/目标 \"目录", move.getString("dest"))
            assertEquals(path, payload().getString(0))
        } finally { server.shutdown() }
    }
}
