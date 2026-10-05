package com.yunx.app.data.gopeed

import com.yunx.app.AppContext
import com.yunx.app.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * 内置 Gopeed 下载引擎（**外部 exe 子进程** 版）。
 *
 * 与上游 Android 版的根本差异：上游把 gomobile 编译出的 `libgojni.so` 用 `System.load` 装进本进程、
 * 配 `apiEnable=false` 走进程内 `rest.Dispatch`；桌面没有 Windows 版内核库，改为把官方
 * `gopeed-web-<ver>-windows-amd64.zip` 里的 `gopeed.exe` 作为**子进程**拉起，走它的本地 HTTP API。
 * 因此 REST 契约（路径 / 请求体 / 响应信封 `{code,msg,data}` / 状态枚举）与上游完全一致。
 *
 * 进程约定：
 * - 只监听 `127.0.0.1`（随机空闲端口），**不设 `-p` 密码** ⇒ 服务端不启用 Web 鉴权、也无需 apiToken；
 * - 内核、存储（bolt）、临时目录全部落在应用数据目录 `<dataDir>/gopeed/` 下，不污染安装目录；
 * - JDK 17 在 Windows 上默认以 `CREATE_NO_WINDOW` 创建子进程（仅显式继承 stdio 时才清除），
 *   所以不会冒出控制台黑窗；stdout/stderr 重定向到日志文件，避免管道写满卡死子进程。
 *
 * 线程约定：[start] / [stop] / [createTask] / [taskStatus] 等全部**阻塞**，必须在 IO 线程调用。
 */
object GopeedEngine {

    /** 引擎状态：未导入内核 / 已导入未运行 / 运行中 */
    enum class State { NOT_INSTALLED, INSTALLED, RUNNING }

    /** 计划任务状态：与 Gopeed `TaskRuntimeStatus.status` 取值一一对应 */
    object TaskStatus {
        const val READY = "ready"
        const val RUNNING = "running"
        const val WAIT = "wait"
        const val PAUSE = "pause"
        const val ERROR = "error"
        const val DONE = "done"
    }

    /** 任务状态快照 */
    data class TaskView(
        val id: String,
        val status: String,
        val downloaded: Long,
        val total: Long,
        val speed: Long
    )

    private const val TAG = "GopeedEngine"
    private const val DIR_NAME = "gopeed"
    private const val EXE_NAME = "gopeed.exe"
    private const val BIN_DIR_NAME = "bin"

    /** 启动后等待 HTTP API 就绪的上限（毫秒）；冷启动解压/建库一般 1-3 秒 */
    private const val READY_TIMEOUT_MS = 20_000L

    /** 停止时等待进程自行退出的上限（毫秒），超时强杀 */
    private const val STOP_TIMEOUT_MS = 3_000L

    private val _state = MutableStateFlow(State.NOT_INSTALLED)
    val state: StateFlow<State> = _state.asStateFlow()

    /** 最近一次失败原因（引擎/子进程原文），供引擎页原样展示便于排查 */
    @Volatile
    var lastError: String? = null
        private set

    /** 引擎 HTTP API 端口；未运行时为 0 */
    @Volatile
    var port: Int = 0
        private set

    @Volatile
    private var process: Process? = null

    private var shutdownHook: Thread? = null

    /** 专用于 localhost 的客户端：**必须绕开应用代理**（否则用户配了系统/手动代理时本地请求会被代理走） */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    // ---------------------------------------------------------------------------------------------
    // 内核文件
    // ---------------------------------------------------------------------------------------------

    /** 引擎数据根目录 `<dataDir>/gopeed` */
    fun engineDir(): File = File(AppContext.dataDir, DIR_NAME)

    /** 内核可执行文件 */
    fun exeFile(): File = File(File(engineDir(), BIN_DIR_NAME), EXE_NAME)

    /** 引擎存储目录（bolt 数据库） */
    private fun storageDir(): File = File(engineDir(), "storage")

    /** 引擎临时目录（分片临时文件） */
    private fun tempDir(): File = File(engineDir(), "tmp")

    /** 引擎日志文件（子进程 stdout/stderr 重定向到这里） */
    private fun logFile(): File = File(engineDir(), "gopeed.log")

    private fun pidFile(): File = File(engineDir(), "engine.pid")

    fun isInstalled(): Boolean = exeFile().isFile

    /** 内核体积（引擎页展示用） */
    fun kernelSize(): Long = exeFile().takeIf { it.isFile }?.length() ?: 0L

    /**
     * 同步「是否已导入内核」这一事实到 [state]（只做一次 stat）。
     * 运行中不动；未运行时按 exe 是否存在写回 INSTALLED / NOT_INSTALLED。
     * 应用启动与引擎页进入时各调一次即可（否则进程内状态可能滞后于磁盘）。
     */
    fun syncInstalledState() {
        if (_state.value == State.RUNNING) return
        _state.value = if (isInstalled()) State.INSTALLED else State.NOT_INSTALLED
    }

    /**
     * 从内核压缩包 / 可执行文件导入内核，支持：
     * - `.zip`（官方 `gopeed-web-<ver>-windows-amd64.zip`）：在包内按名找 `gopeed.exe`（任意层级）；
     * - `.exe`：直接作为内核拷入。
     * 导入前会先停掉正在运行的引擎（否则 exe 被占用、写入失败）。
     * @return 安装后的内核文件
     */
    fun installFromArchive(archive: File): File {
        require(archive.isFile) { "内核文件不存在：${archive.absolutePath}" }
        // 运行中的老内核必须先停：Windows 上正在执行的 exe 无法被覆盖
        if (_state.value == State.RUNNING) stop()
        val target = exeFile()
        target.parentFile?.mkdirs()

        val name = archive.name.lowercase()
        if (name.endsWith(".zip")) {
            var found = false
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    if (!entry.isDirectory && entryName.substringAfterLast('/').equals(EXE_NAME, ignoreCase = true)) {
                        target.outputStream().buffered().use { out -> zip.copyTo(out) }
                        found = true
                        break
                    }
                    entry = zip.nextEntry
                }
            }
            if (!found) {
                throw IllegalStateException("压缩包里没有找到 $EXE_NAME（请使用 Gopeed 官方 windows-amd64 包）")
            }
        } else {
            archive.copyTo(target, overwrite = true)
        }
        lastError = null
        _state.value = State.INSTALLED
        Log.i(TAG, "内核已导入：${target.absolutePath}（${kernelSize()} 字节）")
        return target
    }

    /** 删除内核（先停引擎）；存储目录保留，避免误删用户未完成任务的元数据 */
    fun uninstall() {
        if (_state.value == State.RUNNING) stop()
        runCatching { exeFile().delete() }
        _state.value = State.NOT_INSTALLED
        Log.i(TAG, "内核已删除")
    }

    // ---------------------------------------------------------------------------------------------
    // 进程生命周期
    // ---------------------------------------------------------------------------------------------

    /**
     * 启动引擎（幂等：已在运行直接返回端口）。
     * @param downloadDir 引擎默认下载目录（实际落盘位置由每个任务的 `opts.path` 决定）
     * @return HTTP API 端口
     */
    fun start(downloadDir: File): Int {
        if (_state.value == State.RUNNING) return port
        val exe = exeFile()
        if (!exe.isFile) throw IllegalStateException("还没有导入 Gopeed 内核（缺少 ${exe.absolutePath}）")
        lastError = null
        // 上一次应用被强杀可能留下孤儿进程：它仍占着 bolt 存储锁，必须先清掉
        killStaleProcess()
        runCatching { downloadDir.mkdirs() }
        val localPort = pickFreePort()
        val args = mutableListOf(
            exe.absolutePath,
            "-A", "127.0.0.1",
            "-P", localPort.toString(),
            "-d", storageDir().absolutePath,
            "--temp-dir", tempDir().absolutePath
        )
        return try {
            val log = logFile().also { it.parentFile?.mkdirs() }
            val pb = ProcessBuilder(args)
                .directory(engineDir())
                // stdout/stderr 落地到文件：既不阻塞子进程，也留一份可排查的运行日志
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectError(ProcessBuilder.Redirect.appendTo(log))
            val p = pb.start()
            process = p
            port = localPort
            registerShutdownHook()
            if (!awaitReady()) {
                runCatching { p.destroyForcibly() }
                process = null
                port = 0
                val detail = "启动超时（${READY_TIMEOUT_MS / 1000}s 内未响应 /api/v1/info），详见 ${log.absolutePath}"
                lastError = detail
                throw IllegalStateException(detail)
            }
            runCatching { pidFile().writeText(p.pid().toString()) }
            _state.value = State.RUNNING
            Log.i(TAG, "引擎已启动：pid=${p.pid()} port=$port 内核版本=${version() ?: "未知"}")
            port
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "引擎启动失败：${e.message}", e)
            throw e
        }
    }

    /** 停止引擎（幂等）：先正常结束，超时强杀；非 Windows 平台行为一致 */
    fun stop() {
        val p = process
        process = null
        pidFile().takeIf { it.isFile }?.delete()
        port = 0
        if (p != null) {
            runCatching {
                p.destroy()
                if (!p.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "引擎未在 ${STOP_TIMEOUT_MS}ms 内退出，强制结束")
                    p.destroyForcibly()
                    p.waitFor(2, TimeUnit.SECONDS)
                }
            }.onFailure { Log.w(TAG, "停止引擎异常：${it.message}") }
        }
        if (_state.value == State.RUNNING) _state.value = State.INSTALLED
        Log.i(TAG, "引擎已停止")
    }

    /** 重启（引擎页「重启引擎」）：先停再起 */
    fun restart(downloadDir: File): Int {
        stop()
        return start(downloadDir)
    }

    /**
     * 清掉上一次应用被强杀后残留的引擎进程（按 pid 文件精确匹配，不误伤用户自己装的 Gopeed）。
     * 匹配条件：pid 存活 且 该进程可执行文件路径就是本应用的内核 exe。
     */
    private fun killStaleProcess() {
        val pid = pidFile().takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull() ?: return
        pidFile().delete()
        if (process?.pid() == pid) return
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        if (!handle.isAlive) return
        val cmd = runCatching { handle.info().command().orElse("") }.getOrDefault("")
        if (!cmd.equals(exeFile().absolutePath, ignoreCase = true)) {
            Log.w(TAG, "pid $pid 存活但不是本应用的内核（$cmd），跳过清理")
            return
        }
        Log.w(TAG, "清理残留引擎进程 pid=$pid")
        runCatching { handle.destroyForcibly() }
    }

    private fun registerShutdownHook() {
        if (shutdownHook != null) return
        val hook = Thread {
            runCatching { stop() }
        }
        shutdownHook = hook
        runCatching { Runtime.getRuntime().addShutdownHook(hook) }
    }

    /** 取一个本机空闲端口（先探测再释放；与引擎实际绑定之间存在极小竞态，失败会重启重试） */
    private fun pickFreePort(): Int = ServerSocket(0).use { it.localPort }

    /** 轮询 `/api/v1/info` 直到引擎就绪 */
    private fun awaitReady(): Boolean {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            process?.let { if (!it.isAlive) return false }
            if (runCatching { infoJson() }.isSuccess) return true
            Thread.sleep(200L)
        }
        return false
    }

    // ---------------------------------------------------------------------------------------------
    // HTTP API
    // ---------------------------------------------------------------------------------------------

    /** 引擎核心版本（`GET /api/v1/info` 的 `data.version`） */
    fun version(): String? =
        runCatching { infoJson().optString("version").takeIf { it.isNotBlank() } }.getOrNull()

    private fun infoJson(): JSONObject = get("/api/v1/info")

    /**
     * 新建下载任务。
     * @param saveDir 落盘目录（**真实文件系统绝对路径**，引擎写不了虚拟路径）
     * @param fileName 落盘文件名（引擎自己拼 `saveDir/fileName`）
     * @param headers 请求头（网盘直链需要 UA/Referer/Cookie）
     * @param connections 分片并发连接数（<=0 时不写，用引擎默认）
     * @param label 业务标签（这里放本地任务 id，便于引擎侧排查）
     * @return 引擎任务 ID
     */
    fun createTask(
        url: String,
        saveDir: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        connections: Int = 0,
        label: String = ""
    ): String {
        val req = JSONObject().apply {
            put("url", url)
            if (headers.isNotEmpty()) {
                put("extra", JSONObject().apply {
                    put("header", JSONObject().apply { headers.forEach { (k, v) -> put(k, v) } })
                })
            }
            if (label.isNotBlank()) {
                put("labels", JSONObject().apply { put("yunxTaskId", label) })
            }
        }
        val opts = JSONObject().apply {
            put("path", saveDir)
            if (fileName.isNotBlank()) put("name", fileName)
            if (connections > 0) {
                put("extra", JSONObject().apply { put("connections", connections) })
            }
        }
        val body = JSONObject().apply {
            put("req", req)
            put("opts", opts)
        }
        // 注意：新建任务的 `data` 是**任务 ID 字符串**，不是对象（与查询类接口不同）
        val id = requestEnvelope("POST", "/api/v1/tasks", body.toString()).optString("data").trim()
        if (id.isBlank()) throw IllegalStateException("引擎未返回任务 ID")
        return id
    }

    /** 查询任务状态 */
    fun taskStatus(engineTaskId: String): TaskView {
        val data = get("/api/v1/tasks/$engineTaskId/status")
        return TaskView(
            id = engineTaskId,
            status = data.optString("status"),
            downloaded = data.optLong("downloaded"),
            total = data.optLong("total"),
            speed = data.optLong("speed")
        )
    }

    fun pauseTask(engineTaskId: String) {
        requestEnvelope("PUT", "/api/v1/tasks/$engineTaskId/pause", null)
    }

    fun continueTask(engineTaskId: String) {
        requestEnvelope("PUT", "/api/v1/tasks/$engineTaskId/continue", null)
    }

    fun deleteTask(engineTaskId: String) {
        requestEnvelope("DELETE", "/api/v1/tasks/$engineTaskId", null)
    }

    /** `GET`：返回信封里的 `data` 对象（查询类接口的 `data` 都是对象） */
    private fun get(path: String): JSONObject =
        requestEnvelope("GET", path, null).optJSONObject("data") ?: JSONObject()

    /**
     * 发一次请求并解析统一信封 `{code,msg,data}`，返回整个信封。
     * `code != 0` 或 HTTP 非 2xx 一律抛 [IllegalStateException]（消息取服务端 `msg`，便于直接展示）。
     * 调用方按需从信封里取 `data`（对象或字符串）。
     */
    private fun requestEnvelope(method: String, path: String, body: String?): JSONObject {
        val currentPort = port
        if (currentPort <= 0) throw IllegalStateException("引擎未运行")
        val url = "http://127.0.0.1:$currentPort$path"
        val builder = Request.Builder().url(url)
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> {
                val payload = (body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
                if (method == "POST") builder.post(payload) else builder.put(payload)
            }
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) {
                if (!resp.isSuccessful) throw IllegalStateException("引擎返回 HTTP ${resp.code}")
                return JSONObject()
            }
            val json = runCatching { JSONObject(text) }.getOrElse {
                throw IllegalStateException("引擎响应不是合法 JSON（HTTP ${resp.code}）：${text.take(200)}")
            }
            val code = json.optInt("code")
            if (code != 0) {
                val msg = json.optString("msg").takeIf { it.isNotBlank() } ?: "引擎调用失败（code=$code）"
                throw IllegalStateException(msg)
            }
            return json
        }
    }
}
