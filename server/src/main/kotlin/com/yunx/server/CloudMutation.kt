package com.yunx.server

import com.yunx.app.data.network.*
import com.yunx.app.data.network.model.ShareFile
import org.json.JSONArray
import org.json.JSONObject
import com.yunx.app.util.LogRedactor
import kotlinx.coroutines.CancellationException

data class CloudMutation(val action: String, val directory: String, val files: List<ShareFile>, val name: String, val target: String)

object CloudMutationPolicy {
    fun directoryId(platform: SharePlatform, file: ShareFile): String = when (platform) {
        SharePlatform.BAIDU -> file.fidToken
        SharePlatform.ILANZOU, SharePlatform.LANZOU -> file.fid.removePrefix("d:")
        else -> file.fid
    }
    fun validate(body: JSONObject, platform: SharePlatform, files: Map<String, ShareFile>,
        parents: Map<String, String>, directories: Set<String>, listed: Set<String> = files.keys): CloudMutation {
        val action = body.getString("action")
        require(action in setOf("create", "rename", "move", "delete")) { "未知云端操作" }
        val directory = body.getString("directory")
        require(directory in directories) { "请从已列出的目录操作" }
        val ids = body.optJSONArray("fids") ?: JSONArray()
        require(ids.length() <= 100) { "每次最多操作 100 项" }
        val selected = (0 until ids.length()).map {
            val id = ids.getString(it)
            require(id in listed) { "文件未在当前目录列出，请刷新" }
            require(parents[id] == directory) { "请选择当前目录中的文件" }
            files[id] ?: error("文件信息已失效，请刷新")
        }
        require(selected.map { it.fid }.distinct().size == selected.size) { "文件选择重复" }
        require(if (action == "create") selected.isEmpty() else selected.isNotEmpty()) { "请选择文件" }
        require(action != "rename" || selected.size == 1) { "每次只能重命名一项" }
        val name = body.optString("name").trim()
        if (action in setOf("create", "rename"))
            require(name.isNotBlank() && name.length <= 255 && name !in setOf(".", "..") &&
                name.none { it == '/' || it == '\\' || it.isISOControl() }) { "名称无效，不能包含斜线或控制字符" }
        val target = body.optString("target")
        if (action == "move") {
            require(target in directories && target != directory) { "请选择其他已浏览过的目标目录" }
            val movingDirectories = selected.filter { it.isdir }.map { directoryId(platform, it) }.toSet()
            val directoryParents = files.values.filter { it.isdir }.associate { directoryId(platform, it) to parents[it.fid] }
            var ancestor: String? = target
            val visited = mutableSetOf<String>()
            while (ancestor != null && visited.add(ancestor)) {
                require(ancestor !in movingDirectories) { "不能将文件夹移入自身或子目录" }
                ancestor = directoryParents[ancestor]
            }
            require(platform != SharePlatform.LANZOU || selected.none { it.isdir }) { "蓝奏云协议仅支持移动文件" }
        }
        require(action != "delete" || body.optBoolean("confirmed")) { "删除云端文件需要确认" }
        return CloudMutation(action, directory, selected, name, target)
    }
}

class CloudMutationExecutor(
    private val quark: () -> QuarkApi = { QuarkApi() },
    private val uc: () -> UCApi = { UCApi() }
) {
    suspend fun execute(platform: SharePlatform, account: JSONObject, operation: CloudMutation,
        beforeRequest: () -> Unit = {}): JSONObject {
        beforeRequest()
        val credential = account.optString("cookie").ifBlank { account.optString("accessToken") }
        require(credential.isNotBlank()) { "请先添加账号" }
        val ids = operation.files.map { it.fid }
        val first = operation.files.firstOrNull()
        val name = operation.name
        val directory = operation.directory
        val target = operation.target
        var pending = false
        val taskIds = JSONArray()
        val processed = JSONArray()
        val errors = JSONArray()
        var individual = false
        suspend fun eachFile(block: suspend (ShareFile) -> Unit) {
            individual = true
            for (file in operation.files) {
                try {
                    beforeRequest()
                    block(file)
                    processed.put(file.fid)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    errors.put(JSONObject().put("fid", file.fid).put("name", file.fname)
                        .put("message", LogRedactor.line(e.message ?: "操作失败")))
                }
            }
        }
        fun accepted(task: String?) {
            check(!task.isNullOrBlank()) { "平台未接受操作，请刷新目录确认" }
            taskIds.put(task); pending = true
        }
        when (platform) {
            SharePlatform.QUARK -> quark().let { api ->
                when (operation.action) {
                    "create" -> check(!api.createFolder(name, directory, credential).isNullOrBlank()) { "创建文件夹失败" }
                    "rename" -> check(api.renameFile(first!!.fid, name, credential)) { "重命名失败" }
                    "move" -> eachFile { accepted(api.moveFile(it.fid, target, credential)) }
                    "delete" -> eachFile { accepted(api.deleteFile(it.fid, credential)) }
                }
            }
            SharePlatform.UC -> uc().let { api ->
                when (operation.action) {
                    "create" -> check(!api.createFolder(name, directory, credential).isNullOrBlank()) { "创建文件夹失败" }
                    "rename" -> check(api.renameFile(first!!.fid, name, credential)) { "重命名失败" }
                    "move" -> eachFile { accepted(api.moveFile(it.fid, target, credential)) }
                    "delete" -> eachFile { accepted(api.deleteFile(it.fid, credential)) }
                }
            }
            SharePlatform.BAIDU -> BaiduApi().let { api ->
                when (operation.action) {
                    "create" -> check(api.createDir("${directory.trimEnd('/')}/$name", credential)) { "创建文件夹失败" }
                    "rename" -> check(api.renameFile(first!!.fidToken, name, credential)) { "重命名失败" }
                    "move" -> { check(api.moveFiles(operation.files.map { it.fidToken }, target, credential)) { "移动失败" }; pending = true }
                    "delete" -> { check(api.deleteFiles(operation.files.map { it.fidToken }, credential)) { "删除失败" }; pending = true }
                }
            }
            SharePlatform.PAN115 -> Pan115Api().let { api ->
                when (operation.action) {
                    "create" -> check(!api.createDir(directory, name, credential).isNullOrBlank()) { "创建文件夹失败" }
                    "rename" -> api.rename(first!!.fid, name, credential)
                    "move" -> { api.move(ids, target, credential); pending = true }
                    "delete" -> api.delete(ids, directory, credential)
                }
            }
            SharePlatform.PAN123 -> Pan123Api().let { api ->
                when (operation.action) {
                    "create" -> api.createDir(directory, name, credential)
                    "rename" -> api.renameFile(first!!.fid, name, credential)
                    "move" -> api.moveFiles(ids, target, credential)
                    "delete" -> api.deleteFiles(operation.files, credential)
                }
            }
            SharePlatform.C139 -> C139Api().let { api ->
                when (operation.action) {
                    "create" -> api.createDir(directory.ifBlank { "/" }, name, credential)
                    "rename" -> check(api.renameFile(first!!.fid, name, credential)) { "重命名失败" }
                    "move" -> { api.moveFiles(ids, target.ifBlank { "/" }, credential)?.let { taskIds.put(it) }; pending = true }
                    "delete" -> { api.deleteFiles(ids, credential)?.let { taskIds.put(it) }; pending = true }
                }
            }
            SharePlatform.GUANGYA -> GuangYaApi().apply { deviceIdProvider = { account.optString("deviceId") } }.let { api ->
                when (operation.action) {
                    "create" -> api.createDir(credential, directory, name)
                    "rename" -> api.renameFile(credential, first!!.fid, name)
                    "move" -> api.moveFiles(credential, ids, target)
                    "delete" -> api.deleteFiles(credential, ids)
                }
            }
            SharePlatform.XUNLEI -> XunleiApi().let { api ->
                require(account.optString("authType") != "webToken") { "个人文件操作需要迅雷 App 通道凭证" }
                val device = account.optString("deviceId")
                val captcha = account.optString("captchaToken")
                when (operation.action) {
                    "create" -> check(!api.createFolder(name, directory, credential, device, captcha).isNullOrBlank()) { "创建文件夹失败" }
                    "rename" -> check(api.renameFile(first!!.fid, name, credential, device, captcha)) { "重命名失败" }
                    "move" -> accepted(api.moveFile(ids, target, credential, device, captcha))
                    "delete" -> check(api.deleteFiles(ids, credential, device, captcha)) { "删除失败" }
                }
            }
            SharePlatform.LANZOU -> LanzouApi().let { api ->
                val params = api.fetchLoginParams(credential) ?: error("凭证无效")
                fun id(file: ShareFile) = file.fid.removePrefix(if (file.isdir) "d:" else "f:")
                when (operation.action) {
                    "create" -> api.createFolder(credential, params.uid, params.vei, directory, name)
                    "rename" -> if (first!!.isdir) api.renameFolder(credential, params.uid, params.vei, id(first), name)
                        else api.renameFile(credential, params.uid, params.vei, id(first), name)
                    "move" -> eachFile { api.moveFile(credential, params.uid, params.vei, target, id(it)) }
                    "delete" -> eachFile { if (it.isdir) api.deleteFolder(credential, params.uid, params.vei, id(it))
                        else api.deleteFile(credential, params.uid, params.vei, id(it)) }
                }
            }
            SharePlatform.ILANZOU -> ILanzouApi().let { api ->
                val uuid = account.getString("uuid")
                val folders = operation.files.filter { it.isdir }.map { it.fid.removePrefix("d:") }
                val files = operation.files.filterNot { it.isdir }.map { it.fid.removePrefix("f:") }
                when (operation.action) {
                    "create" -> api.createDir(credential, uuid, directory, name)
                    "rename" -> if (first!!.isdir) api.renameFolder(credential, uuid, first.fid.removePrefix("d:"), name)
                        else api.renameFile(credential, uuid, first.fid.removePrefix("f:"), name)
                    "move" -> api.moveEntries(credential, uuid, folders, files, target)
                    "delete" -> api.deleteEntries(credential, uuid, folders, files)
                }
            }
            else -> error("此平台不支持云端文件操作")
        }
        if (!individual) ids.forEach { processed.put(it) }
        return JSONObject().put("pending", pending).put("taskIds", taskIds).put("processedFids", processed).put("errors", errors)
            .put("message", if (errors.length() > 0) "已提交 ${processed.length()} 项，${errors.length()} 项失败"
                else if (pending) "请求已提交，平台后台处理中，请刷新确认" else "操作已完成")
    }
}
