package com.qweexam.app.ui

import com.qweexam.app.service.MatchResult

/**
 * 悬浮窗文本格式化（纯函数，可单测）。
 * 返回 (答案主文本, 状态/模式/题库答案 副文本)。
 */
object OverlayTextFormatter {

    fun format(result: MatchResult?): Pair<String, String> {
        if (result == null) return "等待题目…" to ""

        val meta = listOfNotNull(
            when (result.status) {
                "ok" -> "✓"
                "miss" -> "未命中"
                "ambiguous" -> "⚠ 有歧义"
                else -> result.status
            },
            when (result.mode) {
                "exact" -> "精确匹配"
                "contain" -> "包含匹配"
                "fuzzy" -> "模糊匹配"
                "screen" -> "屏幕正确答案"
                else -> null
            },
            result.bankAnswer?.let { "题库答案:$it" },
            result.screenAnswer?.let { "屏幕答案:$it" }
        ).joinToString(" ")
        return result.display to meta
    }
}
