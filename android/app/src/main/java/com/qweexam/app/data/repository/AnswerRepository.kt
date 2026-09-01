package com.qweexam.app.data.repository

import com.qweexam.app.data.db.AppDatabase
import com.qweexam.app.data.db.AppSettingsKeys
import com.qweexam.app.data.db.QuestionEntity
import com.qweexam.app.service.AnswerMatcher
import com.qweexam.app.service.MatchResult

/**
 * 答题匹配仓库（v1.2：支持匹配范围设置）。
 * - all：合并所有题库查找（最新优先语义由匹配顺序保证，跨库同题不同答案 → 歧义提示）；
 * - specific：仅从指定题库查找。
 */
class AnswerRepository(private val db: AppDatabase) {

    private var cacheQuestions: List<QuestionEntity>? = null
    private var cacheKey: String? = null

    /** 匹配范围：all / specific */
    suspend fun matchMode(): String =
        db.appSettingsDao().get(AppSettingsKeys.MATCH_MODE) ?: "all"

    suspend fun setMatchMode(mode: String) {
        db.appSettingsDao().put(AppSettingsKeys.MATCH_MODE, mode)
        cacheKey = null
    }

    suspend fun currentBankId(): Long? =
        db.appSettingsDao().get(AppSettingsKeys.CURRENT_BANK_ID)?.toLongOrNull()

    suspend fun setCurrentBank(bankId: Long) {
        db.appSettingsDao().put(AppSettingsKeys.CURRENT_BANK_ID, bankId.toString())
        cacheKey = null
    }

    /** 按当前设置加载题目缓存（题库规模千级，全量内存缓存毫秒级） */
    private suspend fun ensureLoaded() {
        val mode = matchMode()
        val currentBank = currentBankId()
        val key = if (mode == "all") "all" else "bank:$currentBank"
        if (cacheInvalid || cacheKey != key) {
            cacheInvalid = false
            cacheKey = key
            cacheQuestions = if (mode == "all") {
                db.questionDao().getAll()
            } else {
                currentBank?.let { db.questionDao().getByBank(it) } ?: emptyList()
            }
        }
    }

    /** 题干 -> v1 答案 */
    suspend fun match(stemText: String): MatchResult {
        ensureLoaded()
        val qs = cacheQuestions ?: emptyList()
        if (qs.isEmpty()) return MatchResult("请先导入题库", "miss", "none", null, null)
        return AnswerMatcher.match(stemText, qs)
    }

    // ---------- 悬浮窗自定义设置（v1.5/v1.6） ----------

    /** 默认悬浮窗背景色（半透明蓝） */
    companion object {
        const val DEFAULT_OVERLAY_BG = "#E61A73E8"

        /** 题库数据失效标记：导入新题库后置位，下次匹配强制刷新缓存（v1.7 修复） */
        @Volatile
        var cacheInvalid = false

        private val DEFAULT_PALETTE = listOf("#E61A73E8", "#E6174EA6", "#E60FA958")

        /** 纯 Kotlin 十六进制颜色解析：#RRGGBB / #AARRGGBB → Int（无 Android 依赖，可单测） */
        fun parseColorInt(hex: String): Int? {
            var h = hex.trim().removePrefix("#")
            if (h.length == 6) h = "FF$h"
            if (h.length != 8) return null
            return h.toLongOrNull(16)?.toInt()
        }

        /** 解析逗号分隔的 #AARRGGBB 颜色列表（空/非法时回退默认色板）——纯函数可单测 */
        fun parseColorList(raw: String?): List<Int> {
            val parsed = raw
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.mapNotNull { parseColorInt(it) }
                ?: emptyList()
            return if (parsed.isEmpty()) DEFAULT_PALETTE.mapNotNull { parseColorInt(it) } else parsed
        }
    }

    /** 新题反馈方式：none / flash（高亮闪烁）/ color（背景色轮换） */
    suspend fun overlayFeedback(): String =
        db.appSettingsDao().get(AppSettingsKeys.OVERLAY_FEEDBACK) ?: "flash"

    suspend fun setOverlayFeedback(mode: String) =
        db.appSettingsDao().put(AppSettingsKeys.OVERLAY_FEEDBACK, mode)

    /** 轮换色板（解析后），兼容旧版单色设置 */
    suspend fun overlayColors(): List<Int> {
        val raw = db.appSettingsDao().get(AppSettingsKeys.OVERLAY_COLORS)
            ?: db.appSettingsDao().get(AppSettingsKeys.OVERLAY_BG_COLOR)
        return parseColorList(raw)
    }

    suspend fun setOverlayColors(hexList: List<String>) =
        db.appSettingsDao().put(AppSettingsKeys.OVERLAY_COLORS, hexList.joinToString(","))

    /** 单元测试辅助：注入题目列表 */
    fun matchWith(questions: List<QuestionEntity>, stemText: String): MatchResult =
        AnswerMatcher.match(stemText, questions)
}
