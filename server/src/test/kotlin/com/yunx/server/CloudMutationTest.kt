package com.yunx.server

import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.model.ShareFile
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class CloudMutationTest {
    private val file = ShareFile("file", "文件.txt", 10, false, "0", "")
    private val folder = ShareFile("folder", "资料", 0, true, "0", "")
    private val child = ShareFile("child", "子目录", 0, true, "folder", "")
    private val files = listOf(file, folder, child).associateBy { it.fid }
    private val parents = mapOf("file" to "0", "folder" to "0", "child" to "folder")
    private val directories = setOf("0", "folder", "child", "other")
    private fun body(action: String, ids: List<String> = listOf("file")) =
        JSONObject().put("action", action).put("directory", "0").put("fids", JSONArray(ids)).put("name", "new.txt")
    private fun validate(request: JSONObject, listed: Set<String> = files.keys) =
        CloudMutationPolicy.validate(request, SharePlatform.QUARK, files, parents, directories, listed)

    @Test fun requiresListedFilesCurrentParentAndExplicitDeleteConfirmation() {
        assertFailsWith<IllegalArgumentException> { validate(body("rename", listOf("forged"))) }
        assertFailsWith<IllegalArgumentException> { validate(body("rename", listOf("child"))) }
        assertFailsWith<IllegalArgumentException> { validate(body("rename"), setOf("folder")) }
        assertFailsWith<IllegalArgumentException> { validate(body("delete")) }
        assertEquals(listOf(file), validate(body("delete").put("confirmed", true)).files)
        assertFailsWith<IllegalArgumentException> { validate(body("rename", listOf("file", "file"))) }
        assertFailsWith<IllegalArgumentException> { validate(body("rename", listOf("file", "folder"))) }
    }
    @Test fun preventsMovingFoldersIntoTheirOwnSubtrees() {
        assertFailsWith<IllegalArgumentException> { validate(body("move", listOf("folder")).put("target", "folder")) }
        assertFailsWith<IllegalArgumentException> { validate(body("move", listOf("folder")).put("target", "child")) }
        assertFailsWith<IllegalArgumentException> { validate(body("move").put("target", "unlisted")) }
        assertEquals("other", validate(body("move").put("target", "other")).target)
    }
    @Test fun namesAreValidatedWithoutRejectingChineseSpacesAndQuotes() {
        for (name in listOf("", ".", "..", "../file", "a\\b", "a\nb")) {
            assertFailsWith<IllegalArgumentException> { validate(body("create", emptyList()).put("name", name)) }
        }
        assertEquals("中文 \"资料\"", validate(body("create", emptyList()).put("name", "中文 \"资料\"")).name)
        assertEquals("15", CloudMutationPolicy.directoryId(SharePlatform.LANZOU, folder.copy(fid = "d:15")))
        assertEquals("/资料", CloudMutationPolicy.directoryId(SharePlatform.BAIDU, folder.copy(fidToken = "/资料")))
    }
    @Test fun quarkAsyncPartialFailuresRemainVisible() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"status":200,"data":{"task_id":"pending-task"}}"""))
        server.enqueue(MockResponse().setBody("""{"status":400,"message":"upstream failure"}"""))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        try {
            val executor = CloudMutationExecutor(quark = { QuarkApi(clientProvider = { client }) })
            val response = executor.execute(SharePlatform.QUARK, JSONObject().put("cookie", "test-cookie"),
                CloudMutation("delete", "0", listOf(file, folder), "", ""))
            assertTrue(response.getBoolean("pending"))
            assertEquals("pending-task", response.getJSONArray("taskIds").getString(0))
            assertEquals("file", response.getJSONArray("processedFids").getString(0))
            assertEquals("folder", response.getJSONArray("errors").getJSONObject(0).getString("fid"))
            val firstRequest = JSONObject(server.takeRequest().body.readUtf8())
            assertEquals("file", firstRequest.getJSONArray("filelist").getString(0))
            assertEquals(2, firstRequest.getInt("action_type"))
        } finally { server.shutdown() }
    }
}
