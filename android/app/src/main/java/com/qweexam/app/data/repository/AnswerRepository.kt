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
        if (cacheKey != key) {
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

    // ---------- 悬浮窗自定义设置（v1.5） ----------

    /** 默认悬浮窗背景色（半透明蓝） */
    companion object {
        const val DEFAULT_OVERLAY_BG = "#E61A73E8"
    }

    /** 新题反馈方式：none / flash（高亮闪烁）/ color（背景色短暂变化） */
    suspend fun overlayFeedback(): String =
        db.appSettingsDao().get(AppSettingsKeys.OVERLAY_FEEDBACK) ?: "flash"

    suspend fun setOverlayFeedback(mode: String) =
        db.appSettingsDao().put(AppSettingsKeys.OVERLAY_FEEDBACK, mode)

    /** 悬浮窗背景颜色（#AARRGGBB），解析失败回退默认 */
    suspend fun overlayBgColor(): Int {
        val raw = db.appSettingsDao().get(AppSettingsKeys.OVERLAY_BG_COLOR) ?: return parseColor(DEFAULT_OVERLAY_BG)
        return parseColor(raw)
    }

    suspend fun setOverlayBgColor(colorHex: String) =
        db.appSettingsDao().put(AppSettingsKeys.OVERLAY_BG_COLOR, colorHex)

    private fun parseColor(hex: String): Int =
        runCatching { android.graphics.Color.parseColor(hex) }
            .getOrDefault(android.graphics.Color.parseColor(DEFAULT_OVERLAY_BG))

    /** 单元测试辅助：注入题目列表 */
    fun matchWith(questions: List<QuestionEntity>, stemText: String): MatchResult =
        AnswerMatcher.match(stemText, questions)
}
