package com.yunx.app.data.repository

import com.yunx.app.data.db.ILanzouAccountDao
import com.yunx.app.data.db.ILanzouAccountEntity
import com.yunx.app.data.network.ILanzouApi
import kotlinx.coroutines.flow.Flow

/**
 * 蓝奏云优享版账号仓库（对照上游 Android 版逐项对齐）：
 * 设备标识 uuid 首次获取后持久化；账号密码登录换取 appToken；
 * 同时保存 password，供 appToken 失效后自动重新登录。
 */
class ILanzouAccountRepository(
    private val dao: ILanzouAccountDao,
    private val api: ILanzouApi
) {

    fun observeAccount(): Flow<ILanzouAccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): ILanzouAccountEntity? = dao.getAccount()

    /** 账号密码登录：取/复用设备标识 → 换 appToken → 拉账号信息 → 落库。 */
    suspend fun login(account: String, password: String): Result<ILanzouAccountEntity> = runCatching {
        val existing = dao.getAccount()
        val uuid = existing?.uuid?.takeIf { isValidUuid(it) } ?: api.getUuid()
        val token = api.login(account, password, uuid)
        val info = api.fetchAccountInfo(token, uuid)
        val entity = ILanzouAccountEntity(
            id = "ilanzou",
            appToken = token,
            uuid = uuid,
            account = account.trim(),
            password = password,
            userId = info?.userId.orEmpty(),
            nickname = info?.nickname.orEmpty().ifBlank { account.trim() }
        )
        dao.upsert(entity)
        entity
    }

    /** 取有效 appToken：缺失且保存了密码时自动重新登录。 */
    suspend fun ensureAppToken(): String? {
        val acc = dao.getAccount() ?: return null
        if (acc.appToken.isNotBlank()) return acc.appToken
        if (acc.password.isBlank()) return null
        val uuid = acc.uuid.takeIf { isValidUuid(it) } ?: return null
        val token = api.login(acc.account, acc.password, uuid)
        dao.upsert(acc.copy(appToken = token, updatedAt = System.currentTimeMillis()))
        return token
    }

    /** 重新登录（appToken 失效时由云盘 VM 调用）；成功返回新 token。 */
    suspend fun relogin(): String? {
        val acc = dao.getAccount() ?: return null
        if (acc.password.isBlank()) return null
        val uuid = acc.uuid.takeIf { isValidUuid(it) } ?: api.getUuid()
        val token = api.login(acc.account, acc.password, uuid)
        dao.upsert(acc.copy(appToken = token, uuid = uuid, updatedAt = System.currentTimeMillis()))
        return token
    }

    /** 取 userId（下载接口的 downloadId 需要它）：缺失时先请求账号信息并补库。 */
    suspend fun ensureUserId(): String? {
        val acc = dao.getAccount() ?: return null
        if (acc.userId.isNotBlank()) return acc.userId
        val info = api.fetchAccountInfo(acc.appToken, acc.uuid) ?: return null
        if (info.userId.isBlank()) return null
        dao.upsert(
            acc.copy(
                userId = info.userId,
                nickname = acc.nickname.ifBlank { info.nickname },
                updatedAt = System.currentTimeMillis()
            )
        )
        return info.userId
    }

    suspend fun logout() {
        dao.clear()
    }

    private fun isValidUuid(uuid: String): Boolean = Regex("^[A-Za-z0-9_-]{8,128}$").matches(uuid)
}
