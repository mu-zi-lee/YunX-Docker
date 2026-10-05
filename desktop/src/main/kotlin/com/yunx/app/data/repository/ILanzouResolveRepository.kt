package com.yunx.app.data.repository

import com.yunx.app.data.network.ILanzouApi
import com.yunx.app.data.network.ILanzouConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import kotlinx.coroutines.delay

/**
 * 蓝奏云优享版分享解析仓库。
 *
 * 平台差异：
 * - 目录：`apix.ilanzou.com/unproved/share/list`（匿名可用）——根目录不传 `folderId`，子目录传数字 id；
 * - 取链：`apix.ilanzou.com/unproved/file/redirect`（匿名可用，302 Location）；
 * - 转存：`apis.ilanzou.com/proved/file/transfer`（需登录 appToken）。
 *
 * 说明：该平台没有独立的「分享兑换 token」步骤，`stoken` 直接复用 `shareId`；
 * 免转存取链（[getGuestShareDownloadLink]）匿名可用，登录态转存走 [transferFile]。
 */
class ILanzouResolveRepository(
    private val api: ILanzouApi,
    private val accountRepository: ILanzouAccountRepository
) : ShareResolveRepository {

    @Volatile
    private var cachedUuid: String? = null

    override suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession> =
        runCatching {
            val shareId = ShareLinkParser.parse(link)?.shareId?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("无法识别蓝奏云优享版分享链接")
            ShareSession(shareId = shareId, stoken = shareId, title = "蓝奏云优享版分享")
        }

    override suspend fun listFiles(
        session: ShareSession,
        dirFid: String,
        cookie: String
    ): Result<List<ShareFile>> = runCatching {
        api.shareList(session.stoken, folderIdOf(dirFid), uuid())
    }

    override suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<DownloadLink> = runCatching {
        if (file.isdir) throw IllegalStateException("文件夹无法直接下载")
        val fileId = file.fid.removePrefix(ILanzouConstants.FILE_PREFIX)
        // downloadId = AES('<fileId>|<userId>')：未登录传空串，登录则用当前账号 userId
        val userId = accountRepository.getAccount()?.userId.orEmpty()
        api.shareDownload(session.stoken, fileId, userId, uuid())
    }

    override suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = runCatching {
        if (file.isdir) throw IllegalStateException("文件夹无法直接下载")
        val fileId = file.fid.removePrefix(ILanzouConstants.FILE_PREFIX)
        api.shareDownload(session.stoken, fileId, "", uuid())
    }

    override suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
        skipSpaceCheck: Boolean
    ): Result<String> = runCatching {
        val appToken = cookie.takeIf { it.isNotBlank() }
            ?: accountRepository.getAccount()?.appToken?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("请先登录蓝奏云优享版")
        val target = toDirFid.ifBlank { ILanzouConstants.ROOT_FOLDER_ID }
        val fileIds = if (file.isdir) emptyList() else listOf(file.fid.removePrefix(ILanzouConstants.FILE_PREFIX))
        val folderIds = if (file.isdir) listOf(file.fid.removePrefix(ILanzouConstants.FOLDER_PREFIX)) else emptyList()
        val key = api.transferShare(appToken, uuid(), session.stoken, fileIds, folderIds, target)
        // 返回非空 transferKey 表示异步转存，轮询到 map.num == 1 视为完成（最多 15 秒）
        if (key.isNotBlank()) {
            var done = false
            repeat(30) {
                if (done) return@repeat
                delay(500)
                if (runCatching { api.transferCount(appToken, uuid(), key) }.getOrDefault(0) == 1) done = true
            }
            if (!done) throw IllegalStateException("蓝奏优享仍在处理转存，请稍后刷新列表")
        }
        target
    }

    override suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink> =
        Result.failure(UnsupportedOperationException("蓝奏优享分享请使用 getShareDownloadLink"))

    override suspend fun ensureTempDir(cookie: String): Result<String> =
        Result.failure(UnsupportedOperationException("蓝奏优享分享无需临时目录"))

    /** 目录 id 归一化：空 / "0" / 带 d: 前缀都转成分享接口需要的数字 id（根目录返回 null）。 */
    private fun folderIdOf(dirFid: String): String? = when {
        dirFid.isBlank() -> null
        dirFid.startsWith(ILanzouConstants.FOLDER_PREFIX) ->
            dirFid.removePrefix(ILanzouConstants.FOLDER_PREFIX).takeIf { it.isNotBlank() }
        dirFid == ILanzouConstants.ROOT_FOLDER_ID -> null
        else -> dirFid
    }

    /** 设备标识：优先复用账号里已保存的，其次请求服务端，最后进程内缓存（匿名解析也可用）。 */
    private suspend fun uuid(): String {
        cachedUuid?.let { return it }
        val existing = accountRepository.getAccount()?.uuid?.takeIf { isValidUuid(it) }
        val value = existing ?: api.getUuid()
        cachedUuid = value
        return value
    }

    private fun isValidUuid(uuid: String): Boolean = Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)
}
