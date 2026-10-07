package com.yunx.macos

import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.network.SharePlatform
import org.json.JSONArray
import org.json.JSONObject

/** Only used by --visual-smoke; no demo data is persisted into a user's accounts or tasks. */
internal object VisualFixtures {
    val accounts get() = SharePlatform.entries.map { platform ->
        JSONObject().put("platform", platform.name).put("configured", platform.name in setOf("QUARK", "PAN123"))
            .put("cloudSupported", platform.name != "GITHUB")
            .put("profile", if (platform.name == "QUARK") JSONObject().put("nickname", "演示账号")
                .put("quota", JSONObject().put("used", 12884901888L).put("total", 1099511627776L)) else JSONObject.NULL)
    }
    fun browser(cloud: Boolean) = Browser(JSONObject().put("sessionId", "visual-only").put("directory", "0").put("title", "演示目录")
        .put("files", JSONArray(listOf(
            JSONObject().put("fid", "folder").put("name", "项目资料").put("directory", true).put("size", 0),
            JSONObject().put("fid", "file1").put("name", "设计参考与说明文档.pdf").put("directory", false).put("size", 12582912),
            JSONObject().put("fid", "file2").put("name", "YunX-macOS-arm64.dmg").put("directory", false).put("size", 167772160),
            JSONObject().put("fid", "file3").put("name", "包含中文与空格的较长文件名 用于窗口布局验证.zip").put("directory", false).put("size", 4294967296L)
        ))), cloud, listOf(Directory("0", if (cloud) "夸克" else "演示分享")))
    val tasks get() = listOf(
        DownloadTaskEntity(id = 1, url = "", fileName = "项目资料与参考文件.zip", totalSize = 1073741824, downloadedSize = 402653184, status = 1),
        DownloadTaskEntity(id = 2, url = "", fileName = "已完成的设计说明.pdf", totalSize = 12582912, downloadedSize = 12582912, status = 3),
        DownloadTaskEntity(id = 3, url = "", fileName = "等待继续的文件.bin", totalSize = 268435456, downloadedSize = 67108864, status = 2),
        DownloadTaskEntity(id = 4, url = "", fileName = "需要重试的文件.zip", status = 4, errorMsg = "下载地址已失效，请重新解析分享链接后下载")
    )
}
