package com.yunx.server

import com.yunx.app.data.backup.AuthCrypto
import com.yunx.app.data.network.SharePlatform
import org.json.JSONArray
import org.json.JSONObject

/** Windows-compatible credential archive. Plaintext never leaves this codec. */
object AccountBackup {
    private val fields = setOf("cookie", "accessToken", "refreshToken", "deviceId", "deviceSign", "captchaToken", "authType", "uuid", "label")
    fun export(credentials: Credentials, password: String): String {
        val accounts = JSONArray()
        SharePlatform.entries.filter { it != SharePlatform.GITHUB }.forEach { platform ->
            if (credentials.credential(platform.name).isNotBlank()) {
                val account = credentials.get(platform.name)
                account.put("platform", platform.name.lowercase())
                if (platform == SharePlatform.ILANZOU) account.put("appToken", account.remove("accessToken"))
                accounts.put(account)
            }
        }
        val root = JSONObject().put("app", "yunx_auth_backup").put("version", 1)
            .put("exportedAt", System.currentTimeMillis()).put("accounts", accounts)
        credentials.credential("GITHUB").takeIf { it.isNotBlank() }?.let { root.put("githubToken", it) }
        require(accounts.length() > 0 || root.has("githubToken")) { "没有可备份的账号凭证" }
        return AuthCrypto.encrypt(root.toString(), password)
    }
    fun decode(content: String, password: String): Map<String, JSONObject> {
        require(content.length <= 2 * 1024 * 1024 && password.length >= 8) { "备份文件过大或口令不足 8 位" }
        val plain = try { AuthCrypto.decrypt(content, password) }
        catch (_: Exception) { error("口令错误或备份文件已损坏") }
        val root = try { JSONObject(plain) } catch (_: Exception) { error("备份格式错误") }
        require(root.optString("app") == "yunx_auth_backup" && root.optInt("version") == 1) { "不支持此备份格式" }
        val input = root.optJSONArray("accounts") ?: error("备份缺少账号信息")
        require(input.length() <= SharePlatform.entries.size) { "备份账号数量异常" }
        val values = linkedMapOf<String, JSONObject>()
        for (i in 0 until input.length()) {
            val entry = input.optJSONObject(i) ?: error("备份账号格式错误")
            val platform = SharePlatform.entries.firstOrNull { it.name.equals(entry.optString("platform"), true) }
                ?: error("备份包含未知平台")
            require(platform.name !in values) { "备份包含重复平台" }
            val account = JSONObject()
            fields.forEach { key ->
                val source = if (platform == SharePlatform.ILANZOU && key == "accessToken") "appToken" else key
                if (entry.has(source)) {
                    require(entry.get(source) is String && entry.getString(source).length <= 65536) { "备份凭证格式错误" }
                    account.put(key, entry.getString(source))
                }
            }
            require(account.optString("cookie").ifBlank { account.optString("accessToken") }.isNotBlank()) { "备份包含空凭证" }
            require(platform != SharePlatform.ILANZOU || account.optString("uuid").isNotBlank()) { "蓝奏优享备份缺少 UUID" }
            values[platform.name] = account
        }
        if (root.has("githubToken")) {
            require(root.get("githubToken") is String && root.getString("githubToken").length <= 65536) { "GitHub 凭证格式错误" }
            root.getString("githubToken").takeIf { it.isNotBlank() }?.let {
                values["GITHUB"] = JSONObject().put("accessToken", it)
            }
        }
        require(values.isNotEmpty()) { "备份没有可恢复的账号" }
        return values
    }
}
