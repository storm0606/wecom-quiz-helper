package com.qweexam.app.data.parser

/** 题型（与原型 TYPE_ALIASES 对齐） */
enum class QuestionType(val label: String) {
    SINGLE("单选"),
    MULTI("多选"),
    JUDGE("判断"),
    SHORT("简答"),
    UNKNOWN("未知");

    companion object {
        private val ALIASES = mapOf(
            "单选" to SINGLE, "单选题" to SINGLE, "单项选择" to SINGLE, "单项" to SINGLE,
            "多选" to MULTI, "多选题" to MULTI, "多项选择" to MULTI, "多项" to MULTI,
            "判断" to JUDGE, "判断题" to JUDGE, "对错" to JUDGE,
            "简答" to SHORT, "简答题" to SHORT, "问答" to SHORT, "问答题" to SHORT,
            "主观题" to SHORT, "填空" to SHORT, "填空题" to SHORT
        )

        fun from(raw: String?): QuestionType? =
            raw?.trim()?.let { ALIASES[it] }
    }
}

/** 一道题（解析结果，未入库形态） */
data class Question(
    val seq: String,
    val type: QuestionType,
    val stem: String,
    val options: Map<String, String>,   // A/B/C/D... -> 选项文字
    val answer: String,                 // 归一化答案（A|B|C|D| -> ABCD）
    val answerRaw: String               // 原始答案（调试用）
)

/** 解析出的题库 */
data class ParsedBank(
    val name: String,
    val questions: List<Question>,
    val sheets: Int = 1
)
