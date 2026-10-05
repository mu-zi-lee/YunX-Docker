package com.yunx.app.data.network

import com.yunx.app.AppContext
import com.yunx.app.data.security.FileCredentialCipher

/**
 * GitHub Token 加密存储。
 *
 * 安全说明：
 * - Token 仅用于提升 GitHub API 限额（未认证 60 次/小时/IP，认证后 5000 次/小时）；
 * - **严禁明文存储**：统一经 [FileCredentialCipher] AES-GCM 加密（密钥不可导出）后落盘；
 * - **严禁输出到日志**，**严禁在 UI 中回显完整 Token**（输入框用 PasswordVisualTransformation）。
 *
 * 桌面版以 [AppContext.miscPrefs]（java.util.prefs）替代 Android SharedPreferences，键名保持语义一致。
 */
object GitHubTokenStore {

    private const val KEY_TOKEN = "github_token_encrypted"
    private const val PURPOSE = "github_token"

    private val cipher = FileCredentialCipher()

    /** 读取已加密 Token 并解密；未配置或解密失败返回 null */
    fun getToken(): String? {
        val stored = AppContext.miscPrefs.get(KEY_TOKEN, null) ?: return null
        return runCatching { cipher.decrypt(stored, PURPOSE) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            // 解密失败自愈（对齐上游 #144 口径）：密钥文件丢失/轮换后，这段密文永远解不开，
            // 留着只会每次读取都失败；清掉它，UI 才会如实显示「未配置 Token」并允许重新填写。
            ?: run {
                AppContext.miscPrefs.remove(KEY_TOKEN)
                null
            }
    }

    /** 设置/更新 Token；传 null 或空串则清除 */
    fun setToken(token: String?) {
        val prefs = AppContext.miscPrefs
        val trimmed = token?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            prefs.remove(KEY_TOKEN)
            return
        }
        runCatching {
            val encrypted = cipher.encrypt(trimmed, PURPOSE)
            prefs.put(KEY_TOKEN, encrypted)
        }
    }

    /** 是否已配置 Token（仅判断是否存在，不做解密） */
    fun hasToken(): Boolean = !AppContext.miscPrefs.get(KEY_TOKEN, null).isNullOrBlank()
}
