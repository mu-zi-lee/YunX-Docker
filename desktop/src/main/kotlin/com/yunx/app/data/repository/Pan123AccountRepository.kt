package com.yunx.app.data.repository

import com.yunx.app.data.db.Pan123AccountDao
import com.yunx.app.data.db.Pan123AccountEntity
import com.yunx.app.data.network.Pan123Api
import com.yunx.app.data.network.Pan123LoginResult
import com.yunx.app.util.CookieCleaner
import kotlinx.coroutines.flow.Flow

/**
 * 123 云盘账号仓库：账号+密码登录 → JWT 落库（依据《123网盘API文档_面向Agent.md》§5.1）。
 * 凭证 = data.token（Bearer JWT，authorToken 同源同形，约 90 天过期）；token 失效时重新走登录
 * （无 refresh 接口，文档 §3.3）。
 */
class Pan123AccountRepository(
    private val dao: Pan123AccountDao,
    private val api: Pan123Api
) {

    fun observeAccount(): Flow<Pan123AccountEntity?> = dao.observeAccount()

    suspend fun getAccount(): Pan123AccountEntity? = dao.getAccount()

    /**
     * 账号密码登录（POST user.123pan.cn/api/user/sign_in，无需签名）→ token 落库。
     *
     * @return 成功返回 null；失败返回可直接展示给用户的原因（已做过错误映射，不回显服务端原文）
     */
    suspend fun loginWithPassword(account: String, password: String): String? =
        when (val result = api.passwordLogin(account, password)) {
            is Pan123LoginResult.Success -> {
                // sign_in 只回 token，昵称要另取一次；取不到（网络抖动）不算登录失败——
                // token 是刚签发的确实可用，昵称退化成账号名展示即可
                val nickname = api.fetchNickname(result.token)?.takeIf { it.isNotBlank() }
                dao.upsert(
                    Pan123AccountEntity(
                        id = "pan123",
                        accessToken = result.token,
                        account = account.trim(),
                        nickname = nickname ?: account.trim()
                    )
                )
                null
            }

            is Pan123LoginResult.Failure -> result.message
        }

    /** 校验当前 token 是否仍有效（失败自动清库，下次重新登录） */
    suspend fun validate(): Boolean {
        val acc = dao.getAccount() ?: return false
        val ok = api.fetchNickname(acc.accessToken) != null
        if (!ok) dao.clear()
        return ok
    }

    suspend fun logout() {
        CookieCleaner.clearCookiesForDomains(listOf("www.123pan.com", "123pan.com"))
        dao.clear()
    }
}