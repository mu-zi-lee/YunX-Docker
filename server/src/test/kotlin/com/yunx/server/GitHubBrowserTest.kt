package com.yunx.server

import com.yunx.app.data.network.GitHubApi
import com.yunx.app.data.network.GitHubLinkType
import com.yunx.app.data.network.GitHubResponseCache
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class GitHubBrowserTest {
    private fun fixture(block: suspend (GitHubBrowser) -> Unit) = runBlocking {
        GitHubResponseCache.clear()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val body = when (request.path) {
                    "/repos/example/project" -> """{"name":"project","full_name":"example/project","default_branch":"main"}"""
                    "/repos/example/project/git/trees/main" -> """{"tree":[{"path":"中文 docs","type":"tree","sha":"abc"},{"path":"read me.txt","type":"blob","sha":"file","size":9}]}"""
                    "/repos/example/project/git/trees/abc" -> """{"tree":[{"path":"文档.txt","type":"blob","sha":"file2","size":12}]}"""
                    "/repos/example/project/releases?per_page=100&page=1" -> """[{"tag_name":"preview","prerelease":true,"assets":[{"name":"preview.zip","size":5,"browser_download_url":"https://github.com/example/project/releases/download/preview/preview.zip"}]},{"tag_name":"v1","assets":[{"name":"app.zip","size":8,"browser_download_url":"https://github.com/example/project/releases/download/v1/app.zip"}]}]"""
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(body)
            }
        }
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.newBuilder().scheme("http").host(server.hostName).port(server.port).build()
            chain.proceed(chain.request().newBuilder().url(url).build())
        }.build()
        try { block(GitHubBrowser(GitHubApi(clientProvider = { client }))) }
        finally { server.shutdown(); GitHubResponseCache.clear() }
    }
    @Test fun browseAndDownloadOnlyKnownEntries() = fixture { browser ->
        val root = browser.resolve(GitHubLinkType.Repository("example", "project", null))
        val id = root.getString("sessionId")
        val nested = browser.list(id, "tree:中文 docs")
        assertEquals("文档.txt", nested.getJSONArray("files").getJSONObject(0).getString("name"))
        assertEquals("https://raw.githubusercontent.com/example/project/main/%E4%B8%AD%E6%96%87%20docs/%E6%96%87%E6%A1%A3.txt",
            browser.download(id, "blob:中文 docs/文档.txt").getString("url"))
        assertFailsWith<IllegalStateException> { browser.list(id, "../forged") }
        assertFailsWith<IllegalStateException> { browser.download(id, "unknown") }
        browser.clear()
        assertFalse(browser.contains(id))
    }
    @Test fun blobLinksAndReleaseSelection() = fixture { browser ->
        val blob = browser.resolve(GitHubLinkType.Repository("example", "project", "blob/main/read me.txt"))
        assertEquals("read me.txt", blob.getString("filename"))
        assertTrue(blob.getString("directUrl").endsWith("read%20me.txt"))
        val latest = browser.resolve(GitHubLinkType.Repository("example", "project", "releases/latest"))
        assertEquals("app.zip", latest.getJSONArray("files").getJSONObject(0).getString("name"))
        assertEquals(1, latest.getJSONArray("files").length())
        val tagged = browser.resolve(GitHubLinkType.Repository("example", "project", "releases/tag/preview"))
        assertEquals("preview.zip", tagged.getJSONArray("files").getJSONObject(0).getString("name"))
    }
}
