package com.yunx.app.data.repository

import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession

/**
 * 分享解析仓库公共接口：夸克 / UC 共用同一套流程（token → 列表 → 转存 → 直链）。
 */
interface ShareResolveRepository {
    suspend fun createSession(link: String, pwd: String?, cookie: String): Result<ShareSession>
    suspend fun listFiles(session: ShareSession, dirFid: String, cookie: String): Result<List<ShareFile>>
    suspend fun ensureTempDir(cookie: String): Result<String>
    /**
     * 转存分享文件到网盘指定目录。
     * @param skipSpaceCheck true = 调用方已在批量入口做过整批空间预算校验，跳过本次逐项校验（避免重复查配额）
     */
    suspend fun transferFile(
        session: ShareSession,
        file: ShareFile,
        toDirFid: String,
        cookie: String,
        skipSpaceCheck: Boolean = false
    ): Result<String>
    suspend fun getDownloadLink(fid: String, cookie: String): Result<DownloadLink>

    /**
     * 获取分享文件下载直链（平台差异在此收敛）：
     * - 夸克：转存到临时目录 → 用转存后新 fid 取直链；
     * - UC：直接用分享 fid + stoken + fid_token 取直链（无需转存）。
     * @param skipSpaceCheck true = 调用方已在批量入口做过整批空间预算校验，跳过本次逐项校验
     */
    suspend fun getShareDownloadLink(
        session: ShareSession,
        file: ShareFile,
        cookie: String,
        skipSpaceCheck: Boolean = false
    ): Result<DownloadLink>

    /**
     * 未登录（游客）取分享直链：目前只有夸克/UC 实现。
     * 返回的 [DownloadLink.guestCookie] 必须带进下载请求（服务端随取链响应下发的游客态 __pugs，
     * 夸克缺它 412、UC 缺它 403）；不需要也不应该传账号 Cookie。
     * 其余平台默认失败 —— 它们的取链/转存中转都依赖账号态。
     */
    suspend fun getGuestShareDownloadLink(
        session: ShareSession,
        file: ShareFile
    ): Result<DownloadLink> = Result.failure(
        IllegalStateException("该平台未登录时无法下载，请先登录后再试")
    )

    /**
     * 批量转存前的整批空间预算校验（只查一次配额，见 TransferSpaceGuard.ensureEnoughSpaceForBatch）。
     * 不足时抛出「剩余空间不足：需要 X，当前可用 Y」；默认不判定（返回 false，调用方保留逐项校验）。
     * @return true = 已完成整批判定（调用方循环内跳过逐项校验）；false = 未判定（保留逐项）
     */
    suspend fun ensureBatchSpace(sizes: List<Long>, credential: String): Boolean = false

    /**
     * 下载完成后清理临时转存目录（夸克实现删除 tr_* 子目录；其它平台默认空实现）。
     * @param dirFid DownloadLink.cleanupDirFid 带回的临时目录 fid
     */
    suspend fun cleanupTempDir(dirFid: String, cookie: String) {}
}