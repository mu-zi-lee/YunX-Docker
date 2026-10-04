package com.yunx.app.ui.login

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yunx.app.data.network.Pan115Constants
import com.yunx.app.ui.jcef.JcefHolder
import com.yunx.app.ui.jcef.JcefLoginPane
import com.yunx.app.ui.viewmodel.Pan115AccountViewModel

/**
 * 115 网盘登录页：
 * - 首选内嵌 Chromium 浏览器（JCEF）打开 115.com 登录，检测到 UID+SEID 后一键保存；
 * - 不可用时回退「粘贴 Cookie / 自动导入浏览器 Cookie」。
 * 保存前只保留 UID/CID/SEID/KID 等会话字段（见 [Pan115Constants.filterLoginCookies]），再用 `/user/info` 校验。
 */
@Composable
fun Pan115LoginScreen(
    viewModel: Pan115AccountViewModel,
    onBack: () -> Unit,
    onSaved: () -> Unit
) {
    // JCEF 可用时用内嵌浏览器；用户可手动切到粘贴模式
    var pasteMode by remember { mutableStateOf(false) }
    val useBrowser = JcefHolder.isAvailable() && !pasteMode

    // 统一保存入口：过滤出 115 需要的会话 Cookie 字段再落库
    val save: suspend (String) -> Boolean = { raw ->
        viewModel.saveCookie(Pan115Constants.filterLoginCookies(raw))
    }

    if (useBrowser) {
        JcefLoginPane(
            loginUrl = Pan115Constants.WEB_LOGIN_URL,
            domains = listOf("115.com"),
            requiredKeys = listOf("UID", "SEID"),
            platform = "PAN115",
            onSave = save,
            onBack = onBack,
            onSaved = onSaved,
            onSwitchToPaste = { pasteMode = true }
        )
    } else {
        CookieLoginContent(
            title = "115网盘登录",
            loginUrl = Pan115Constants.WEB_LOGIN_URL,
            platform = "PAN115",
            cookieRequirement = "Cookie 需包含 UID 与 SEID（可含 CID / KID）",
            validityHint = "Cookie 会随会话轮换，失效后需重新登录",
            onSave = save,
            onBack = onBack,
            onSaved = onSaved
        )
    }
}
