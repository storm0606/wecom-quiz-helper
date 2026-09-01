package com.qweexam.app.service

/**
 * 错题/回顾模式屏幕答案兜底（v1.1）：
 * 屏幕会显示「正确答案：X」（正式作答时不存在）。
 * - 题库命中 → 附加 screenAnswer 作交叉校验提示；
 * - 题库未命中/有歧义 → 直接用屏幕答案兜底显示（保证错题场景必有答案）。
 */
object ScreenAnswerFallback {

    fun merge(result: MatchResult, block: QuestionBlock): MatchResult {
        val gt = block.groundTruth ?: return result
        if (result.status == "ok") {
            return result.copy(screenAnswer = gt)
        }
        if (result.status == "miss" || result.status == "ambiguous") {
            val display = if (gt.length > 1 && block.options.isNotEmpty()) {
                // 多选：逐字母映射选项文字（如 ABCD → A.安全 B.可靠 C.便捷 D.舒适）
                val parts = gt.mapNotNull { letter ->
                    block.options[letter.toString()]?.let { "$letter.$it" }
                }
                if (parts.size == gt.length) "屏幕答案：${parts.joinToString(" ")}" else "屏幕答案：$gt"
            } else {
                block.options[gt]?.let { "屏幕答案：$gt.$it" } ?: "屏幕答案：$gt"
            }
            return MatchResult(
                display = display,
                status = "ok",
                mode = "screen",
                bankAnswer = null,
                question = null,
                screenAnswer = gt
            )
        }
        return result
    }
}
