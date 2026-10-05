package com.yunx.app.data.repository

import com.yunx.app.data.network.LanzouApi
import com.yunx.app.data.network.LanzouSharePage
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝奏云分享解析仓库：**匿名**，无需登录。
 * 分享页 HTML → 文件夹分页（filemoreajax）/ 单文件 → 下载参数（ajaxm/ajaxfile）→ HEAD 探测直链。
 * 解析出的分享页缓存在内存中（按分享 URL 作 key），供后续列目录 / 取链复用。
 *
 * 蓝奏分享暂不支持直接转存：转存相关方法返回 [UnsupportedOperationException]（与上游一致）。
 */
class LanzouResolveRepository(
    private val api: LanzouApi
) : ShareResolveRepository {

    /** 分享页内存缓存（HTTP 层可能并发调用，用并发容器避免竞态） */
    private val pageCache = ConcurrentHashMap<String, LanzouSharePage>()

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val page = api.resolveShare(link, pwd)
            if (page.needsPwd && pwd.isNullOrBlank()) {
                throw IllegalStateException("此蓝奏分享需要提取码，请填写后重新解析")
            }
            pageCache[page.shareUrl] = page
            ShareSession(shareId = page.shareUrl, stoken = pwd.orEmpty(), title = page.title)
        }

    override suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>> =
        runCatching {
            val page = pageCache[session.shareId]
                ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
            val pwd = session.stoken.takeIf { it.isNotBlank() }
            if (page.isFolder) {
                api.listShareFolder(page, pwd, dirFid)
            } else {
                listOfNotNull(page.singleFile)
            }
        }

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("蓝奏分享暂不支持直接转存，请下载后上传"))

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<String> = Result.failure(
        UnsupportedOperationException("蓝奏分享暂不支持直接转存，请下载后上传")
    )

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("蓝奏分享请使用 getShareDownloadLink"))

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<DownloadLink> = runCatching {
        val page = pageCache[session.shareId]
            ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
        api.getShareDownloadLink(page, file, session.stoken.takeIf { it.isNotBlank() })
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = runCatching {
        val page = pageCache[session.shareId]
            ?: throw IllegalStateException("蓝奏分享会话已失效，请重新解析")
        api.getShareDownloadLink(page, file, session.stoken.takeIf { it.isNotBlank() })
    }
}
