package com.yunx.app.data.network

import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI

/**
 * 迅雷网页登录凭据（pan.xunlei.com 的 localStorage 对象）。
 *
 * @param accessToken 云盘接口用的 access_token（网页版 token）
 * @param refreshToken 刷新令牌；网页 token 只能配**网页** client_id 刷新（见 [CLIENT_ID]）
 * @param deviceId 网页侧 device_id（取自 deviceid Cookie/脚本兜底；可能为空，调用方自行兜底）
 * @param captchaToken 网页侧 captcha_token（可能为空）
 * @param userId 用户 ID（JWT sub / user_id），用于后续换号检测
 * @param nickname 昵称（网页对象里通常没有，可能为空）
 */
data class XunleiWebTokens(
    val accessToken: String,
    val refreshToken: String,
    val deviceId: String,
    val captchaToken: String,
    val userId: String,
    val nickname: String
)

/**
 * 迅雷网页登录：站点私有格式的解析（纯逻辑，便于单测）+ JCEF 桥接脚本。
 *
 * ⚠️ 这依赖迅雷网页版的私有实现（键名、字段名）。改版失效时，用户仍可走「手动粘贴」兜底
 * （[parse] 同时支持整段 JSON、带引号的 JSON 字符串、裸 token 三种粘贴形态）。
 */
object XunleiWebCredential {

    /**
     * 网页版 OAuth client_id（抓包自 pan.xunlei.com，与 App 端 Xp6vsxz_7IYVw2BB 不同）。
     * 网页 token 只能用这个 client_id 刷新，且**不需要** client_secret。
     */
    const val CLIENT_ID = "Xqp0kJBXWhwaTpB6"

    /** 登录态在 localStorage 中的键名 */
    const val STORAGE_KEY = "credentials_$CLIENT_ID"

    /** 验证码 token 在 localStorage 中的键名（有效期由页面自己维护，过期就忽略） */
    const val CAPTCHA_KEY = "captcha_$CLIENT_ID"

    /** 网页登录页（未登录会自动进入登录流程；扫码 / 验证码都由官网页面自己处理） */
    const val LOGIN_URL = "https://pan.xunlei.com/"

    /** 落库时写入的认证方式：网页 token 的刷新路径与 App token 不同 */
    const val AUTH_TYPE = "webToken"

    /** 网页版桌面 UA（网页 token 的刷新请求必须带，否则被风控拒绝） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    /** 允许 WebView 停留 / 跳转的域名（登录过程会经 i.xunlei.com 等官方域，统一放行 *.xunlei.com） */
    private val TRUSTED_HOSTS = listOf("xunlei.com")

    /** JCEF 桥接：把网页凭据写进 Cookie 的分片键前缀 / 分片数键（单条 Cookie 有 4KB 上限） */
    const val CRED_COOKIE_PREFIX = "yunx_xl_cred_"
    const val CRED_COOKIE_COUNT = "yunx_xl_cred_n"

    /**
     * 桌面 JCEF 不支持取 executeJavaScript 的返回值，所以改为「把凭据写进 Cookie，再从 Cookie 读回」：
     * 该脚本从 localStorage 取 credentials_<clientId>（子账号 `...@<current_sub>`），补上
     * deviceid Cookie → device_id、captcha_<clientId> → captcha_token，再 URL 编码后分片
     * 写入 `yunx_xl_cred_*` Cookie；Kotlin 侧轮询 Cookie 拼回原文并解析。
     *
     * 分片是因为 access+refresh token 的 JSON 可能超过单条 Cookie 4KB 上限，直接写会被浏览器静默丢弃。
     */
    val COOKIE_BRIDGE_SCRIPT: String = """
        (function(){try{
          var prefix='$STORAGE_KEY';
          var sub=localStorage.getItem('current_sub');
          var raw=sub?localStorage.getItem(prefix+'@'+sub):localStorage.getItem(prefix);
          if(!raw)return;
          var data=JSON.parse(raw);
          if(!data||typeof data!=='object'||Array.isArray(data))return;
          if(sub&&data.sub&&String(data.sub)!==sub)return;
          try{
            var c=JSON.parse(localStorage.getItem('$CAPTCHA_KEY')||'{}');
            if(c&&c.token&&Date.parse(c.expires_at)>Date.now())data.captcha_token=c.token;
          }catch(e){}
          try{
            var all=document.cookie.split(';');
            for(var i=0;i<all.length;i++){
              var s=all[i].trim();
              if(s.indexOf('deviceid=')!==0)continue;
              var d=decodeURIComponent(s.slice(9));
              data.device_id=(d.indexOf('.')>-1&&d.length>32)?d.split('.')[1].slice(0,32):d;
              break;
            }
          }catch(e){}
          var payload=encodeURIComponent(JSON.stringify(data));
          var size=3000;
          var n=Math.ceil(payload.length/size); if(n<1)n=1;
          for(var j=0;j<n;j++){
            document.cookie='$CRED_COOKIE_PREFIX'+j+'='+payload.substr(j*size,size)+';path=/;max-age=600';
          }
          document.cookie='$CRED_COOKIE_COUNT='+n+';path=/;max-age=600';
        }catch(e){}})()
    """.trimIndent()

    /**
     * WebView 是否允许停留在该 URL：仅 https 的 xunlei.com 及其子域、443 端口（about:blank 放行）。
     *
     * ⚠️ `java.net.URI` 与 `android.net.Uri` 一致：URL 未写端口时 port 返回 **-1**（不补默认端口），
     * 所以必须把 -1 当成 443 放行，否则 `https://pan.xunlei.com/` 会被判成不可信、拦掉所有跳转。
     */
    fun isTrustedUrl(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        if (value.isEmpty() || value == "about:blank") return true
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null) return false
        val port = uri.port
        if (port != -1 && port != 443) return false
        val host = uri.host?.lowercase().orEmpty()
        return TRUSTED_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * 解析网页登录凭据。支持三种输入：
     * 1. 完整 JSON（localStorage 原文 / 抓包复制）；
     * 2. 被引号包起来的 JSON 字符串（某些工具复制出来是这种）；
     * 3. 裸 access_token（手动粘贴兜底，此时没有 refresh_token，过期后只能重新登录）。
     *
     * 解析失败（含字段校验不过）一律返回 null，由调用方继续轮询或提示用户。
     */
    fun parse(raw: String): XunleiWebTokens? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        // 形态 2：外层是 JSON 字符串 → 还原成里面的内容
        unwrapQuoted(text)?.let { text = it.trim() }
        if (text.isEmpty()) return null
        // 形态 3：不是 JSON 对象 → 按裸 token 处理（纯字符串逻辑，不碰 JSON）
        if (!text.startsWith("{")) return parseRawToken(text)

        val root = parseObject(text) ?: return null
        return fieldsFrom(toFieldMap(unwrapNested(root)))
    }

    /**
     * 裸 token（手动粘贴 access_token 的场景）：
     * 去掉 `Bearer ` 前缀后过一遍 token 校验，通过就只填 access（没有 refresh，过期只能重登）。
     */
    internal fun parseRawToken(text: String): XunleiWebTokens? {
        val token = stripBearer(text)
        return if (isValidToken(token)) XunleiWebTokens(token, "", "", "", "", "") else null
    }

    /**
     * 从「键 → 值」映射里挑出凭据字段（**纯逻辑**）。
     *
     * 兼容点：snake_case 与 camelCase 两种键名；任一 token 非法（长度/字符集不对）即整体判失败——
     * 网页登录过程中会把中间态写进 localStorage，不能当成登录成功。
     */
    internal fun fieldsFrom(fields: Map<String, String>): XunleiWebTokens? {
        val access = pick(fields, "access_token", "accessToken")
        val refresh = pick(fields, "refresh_token", "refreshToken")
        if (access.isNotEmpty() && !isValidToken(access)) return null
        if (refresh.isNotEmpty() && !isValidToken(refresh)) return null
        if (access.isEmpty() && refresh.isEmpty()) return null
        val captcha = pick(fields, "captcha_token", "captchaToken")
        return XunleiWebTokens(
            accessToken = access,
            refreshToken = refresh,
            deviceId = pick(fields, "device_id", "deviceId"),
            captchaToken = if (isValidToken(captcha)) captcha else "",
            userId = pick(fields, "user_id", "userId", "sub"),
            nickname = pick(fields, "nick_name", "nickname", "name", "user_name")
        )
    }

    /** 去掉 `Bearer ` 前缀与首尾空白（**必须先 trim 再匹配**，否则 "  Bearer xxx  " 匹配不上前缀） */
    private fun stripBearer(value: String): String =
        value.trim().replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()

    /** 把 JSONObject 拍平成「键 → 字符串值」；非字符串/数字的值直接丢掉（token 一定是字符串） */
    private fun toFieldMap(data: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = data.opt(key)
            if (value is String || value is Number) map[key] = value.toString()
        }
        return map
    }

    /** 外层是 JSON 字符串字面量时还原内容；不是则返回 null */
    private fun unwrapQuoted(text: String): String? {
        if (!text.startsWith("\"")) return null
        return runCatching {
            val parsed = JSONTokener(text).nextValue()
            parsed as? String
        }.getOrNull()
    }

    /** 解析成 JSONObject；不是对象（裸 token / 数组 / 非法 JSON）返回 null */
    private fun parseObject(text: String): JSONObject? {
        if (!text.startsWith("{")) return null
        return runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull()
    }

    /**
     * 兼容嵌套包装：有些版本把 token 包在 `credentials` / `token` / `data` 里。
     * 最多向下找两层，找到含 token 字段的那层为止。
     */
    private fun unwrapNested(root: JSONObject): JSONObject {
        var current = root
        repeat(2) {
            if (current.has("access_token") || current.has("accessToken") ||
                current.has("refresh_token") || current.has("refreshToken")
            ) {
                return current
            }
            val nested = current.optJSONObject("credentials")
                ?: current.optJSONObject("token")
                ?: current.optJSONObject("data")
                ?: return current
            current = nested
        }
        return current
    }

    /** 取第一个非空字符串字段（数字也接受） */
    private fun pick(fields: Map<String, String>, vararg keys: String): String {
        for (key in keys) {
            val text = fields[key]?.trim().orEmpty()
            if (text.isEmpty()) continue
            if (CONTROL_CHARS.containsMatchIn(text)) continue
            return text
        }
        return ""
    }

    /**
     * token 合法性：长度 16~16384、仅 JWT/Base64 允许的字符集、且不是 "null"/"undefined" 这类占位串。
     * 中间态写进 localStorage 的垃圾值不会当成登录成功。
     */
    fun isValidToken(value: String): Boolean =
        value.length in 16..16384 &&
            TOKEN_CHARS.matches(value) &&
            value != "null" &&
            value != "undefined"

    private val TOKEN_CHARS = Regex("^[A-Za-z0-9._~+/=-]+$")
    private val CONTROL_CHARS = Regex("[\\x00-\\x1f\\x7f]")
}
