package com.yunx.app.data.repository

import com.yunx.app.data.network.model.QuotaInfo
import com.yunx.app.util.Log

/**
 * 转存 / 转存后下载前的空间前置校验：把「待转存内容大小」与目标账号剩余空间比较。
 *
 * 目的：空间不足时直接给出面向用户的明确提示，避免平台先接受任务、随后任务卡住，
 * 最终被误报成「转存超时」（见各平台 ResolveRepository 的轮询逻辑）。
 *
 * 容错原则：大小未知（如目录算不出）或空间查询失败（未登录 / 接口异常）时一律放行并记日志，
 * 不阻塞既有流程，只是少了这次前置校验。
 *
 * 单位为字节：各平台 [QuotaInfo] 均由各自 getQuota 归一化到「字节」后再比较，
 * 因此这里无需按平台换算（各平台原始单位差异已在 Api.getQuota 内处理）。
 */
object TransferSpaceGuard {

    private const val TAG = "TransferSpaceGuard"

    /**
     * 校验 requiredBytes 是否超过目标账号剩余空间，不足则抛 [IllegalStateException]。
     *
     * @param requiredBytes 待转存内容大小（字节）；null 或 <=0 表示未知（如目录算不出）→ 跳过校验
     * @param platform 平台名（仅用于日志排查），如 "夸克"
     * @param quotaProvider 查询空间详情的挂起函数；返回 null 表示查询失败 / 未登录 → 跳过校验
     */
    suspend fun ensureEnoughSpace(
        requiredBytes: Long?,
        platform: String,
        quotaProvider: suspend () -> QuotaInfo?
    ) {
        if (requiredBytes == null || requiredBytes <= 0) {
            Log.d(TAG, "[$platform] 待转存内容大小未知，跳过空间校验")
            return
        }
        val quota = runCatching { quotaProvider() }.getOrNull()
        if (quota == null || quota.total <= 0) {
            Log.d(TAG, "[$platform] 未获取到剩余空间，跳过空间校验")
            return
        }
        val free = quota.total - quota.used
        if (requiredBytes > free) {
            throw IllegalStateException(
                "剩余空间不足：需要 ${formatBytes(requiredBytes)}，当前可用 ${formatBytes(free)}"
            )
        }
    }

    /**
     * 批量转存 / 批量下载前的「整批一次性」空间预算校验：对选中项大小求和后**只查一次配额**。
     *
     * 与 [ensureEnoughSpace] 的区别：
     * - 一次请求即可判定整批是否放得下，避免批量循环内 N 次配额查询；
     * - 抛出的错误发生在批量循环**之外**，不会被循环内的 runCatching 容错吞掉（能沿 UI 提示链路显示）。
     *
     * 未知大小策略：只要存在大小未知项（目录 fsize<=0），就无法确定整批总需求，
     * 按约定**不做整批拦截**（记日志说明并返回 false），由调用方保留逐项校验兜底已知项。
     *
     * @param sizes 选中项大小（字节）；<=0 表示大小未知
     * @return true = 已完成整批判定（放行或抛错），调用方应跳过逐项校验以免重复查配额；
     *         false = 存在未知大小项或内容为空，未做整批判定，调用方保留逐项校验
     */
    suspend fun ensureEnoughSpaceForBatch(
        sizes: List<Long>,
        platform: String,
        quotaProvider: suspend () -> QuotaInfo?
    ): Boolean {
        if (sizes.any { it <= 0 }) {
            // 存在未知项：整批总需求不可知，不做拦截；仅对已知项求和记日志便于排查
            val known = sizes.filter { it > 0 }.sum()
            Log.d(TAG, "[$platform] 部分项大小未知，跳过批量空间校验（已知项合计 ${formatBytes(known)}）")
            return false
        }
        val required = sizes.sum()
        if (required <= 0) {
            Log.d(TAG, "[$platform] 批量内容为空或大小为 0，跳过批量空间校验")
            return false
        }
        val quota = runCatching { quotaProvider() }.getOrNull()
        if (quota == null || quota.total <= 0) {
            // 取配额失败：整批与逐项都无法判定，直接放行并返回 true（省掉循环内 N 次无效查询）
            Log.d(TAG, "[$platform] 未获取到剩余空间，跳过批量空间校验")
            return true
        }
        val free = quota.total - quota.used
        if (required > free) {
            throw IllegalStateException(
                "剩余空间不足：需要 ${formatBytes(required)}，当前可用 ${formatBytes(free)}"
            )
        }
        return true
    }

    /** 字节数格式化为人类可读单位（B / KB / MB / GB / TB），与项目既有展示风格一致 */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        return if (unit == 0) "$bytes B" else String.format("%.1f %s", value, units[unit])
    }
}
