package com.yunx.app.data.announcement

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.prefs.Preferences

/**
 * 已读公告的本地记录。
 *
 * 服务端**没有**已读接口（公开接口只有列表 / 最新 / 详情，详情只累加浏览量），
 * 所以「未读」口径完全由客户端维护：**打开详情** 或 **关掉启动弹窗** 都算已读。
 *
 * 存储：与桌面既有设置一致，用 `java.util.prefs`（落注册表）存一个 JSON 数组字符串
 * （最新的排最前），不落 SQLite —— 它只是「n 个 id 的集合」，没有查询需求。
 * 超过 [MAX_KEEP] 条时从尾部丢弃：公告 ID 不复用，丢掉很久以前的已读记录最多让一条
 * 陈年公告重新算作未读（角标数字会修正，不会丢数据）。
 *
 * 并发：写入方只有 ViewModel 一处，但读写可能跨线程（UI + 协程），用 [lock] 串行化，
 * 并且**先更新内存再落盘**，[readIds] 的订阅者（角标）不会被 IO 卡住。
 *
 * 桌面差异：上游用 `SharedPreferences`（Context 注入）；桌面无 Context，直接持有
 * `Preferences.userRoot().node("yunx/announcement")`，读写语义与键名一致。
 */
class AnnouncementReadStore {

    private val prefs: Preferences = Preferences.userRoot().node("yunx/announcement")

    private val lock = Any()

    /** 有序（最新在前）的已读 id，内存里的唯一真源 */
    private var ordered: List<String> = loadOrdered()

    private val _readIds = MutableStateFlow(ordered.toSet())

    /** 已读 id 集合（供 Compose 观察；写入后立即更新） */
    val readIds: StateFlow<Set<String>> = _readIds.asStateFlow()

    fun isRead(id: String): Boolean = id in _readIds.value

    /** 标记一条公告已读；已读过则直接返回 */
    fun markRead(id: String) {
        val trimmed = id.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            if (trimmed in _readIds.value) return
            ordered = (listOf(trimmed) + ordered).take(MAX_KEEP)
            persistLocked()
            _readIds.value = ordered.toSet()
        }
    }

    /**
     * 批量标记已读（「全部标为已读」）。
     * @return 本次真正新增的条数（0 表示这些公告本来就都读过了，调用方可据此决定要不要提示）
     */
    fun markAllRead(ids: Collection<String>): Int {
        val fresh = ids.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .filter { it !in _readIds.value }
        if (fresh.isEmpty()) return 0
        synchronized(lock) {
            // 锁内再取一次差集：上面那轮过滤是为了不改锁外可见状态，这里保证真正落盘的没有重复
            val stillFresh = fresh.filter { it !in _readIds.value }
            if (stillFresh.isEmpty()) return 0
            ordered = (stillFresh + ordered).distinct().take(MAX_KEEP)
            persistLocked()
            _readIds.value = ordered.toSet()
            return stillFresh.size
        }
    }

    private fun loadOrdered(): List<String> {
        val text = prefs.get(KEY_READ_IDS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            val out = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                if (arr.isNull(i)) continue
                val id = arr.optString(i).trim()
                if (id.isNotEmpty()) out.add(id)
            }
            out
        } catch (e: Exception) {
            // 记录损坏（手改注册表 / 写一半崩了）：当作没有已读记录，不要因为一个偏好项让应用起不来
            emptyList()
        }
    }

    private fun persistLocked() {
        prefs.put(KEY_READ_IDS, JSONArray(ordered).toString())
    }

    companion object {
        private const val KEY_READ_IDS = "read_ids"

        /** 最多保留的已读记录条数（见类注释：多出来的从最旧的开始丢） */
        private const val MAX_KEEP = 500
    }
}
