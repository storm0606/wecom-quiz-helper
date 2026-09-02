package com.qweexam.app.ui

import com.qweexam.app.data.db.QuestionEntity

/**
 * 题库内容预览的抽样与列规则（v2.4，纯函数，JVM 可单测）。
 *
 * 目的：选择某题库后以表格抽查导入结果 —— 表头（题型/题目/选项/答案）与 DB 字段对应，
 * 每种题型最多展示前 perType（默认 5）题，用于核对列映射是否正确。
 */
object BankPreviewModel {

    /** 题型展示顺序（与 QuestionType.label 一致） */
    val TYPE_ORDER = listOf("单选", "多选", "判断", "简答", "未知")

    /** 表格一列：表头文字 + 从题目取值的函数 */
    data class Column(val header: String, val value: (QuestionEntity) -> String)

    /**
     * 按题型分组抽样：组序按 [TYPE_ORDER]，组内按 id 升序（≈导入文件顺序）取前 [perType] 题。
     * 未出现在 TYPE_ORDER 中的 qtype 视作「未知」。
     */
    fun sampleByType(rows: List<QuestionEntity>, perType: Int = 5): List<Pair<String, List<QuestionEntity>>> {
        val safe = if (perType < 1) 1 else perType
        val grouped = rows.groupBy { it.qtype.ifBlank { "未知" } }
        return TYPE_ORDER.mapNotNull { t ->
            val g = grouped[t].orEmpty().sortedBy { it.id }.take(safe)
            if (g.isEmpty()) null else t to g
        }
    }

    /** 各题型总数（顶部汇总用），顺序同 [TYPE_ORDER]，0 的不列出 */
    fun typeCounts(rows: List<QuestionEntity>): List<Pair<String, Int>> =
        TYPE_ORDER.mapNotNull { t ->
            val c = rows.count { it.qtype.ifBlank { "未知" } == t }
            if (c > 0) t to c else null
        }

    /**
     * 表格列：恒有 题型/题目/答案；选项列仅当库中存在非空内容才显示
     * （列头与数据库字段逐列对应：题目→题干、选项A–F→各选项、答案→归一化答案）。
     */
    fun tableColumns(rows: List<QuestionEntity>): List<Column> {
        val cols = mutableListOf<Column>()
        cols += Column("题型") { it.qtype.ifBlank { "未知" } }
        cols += Column("题目") { it.stem }
        for (l in 'A'..'F') {
            val get = letterValue(l)
            if (rows.any { !get(it).isNullOrBlank() }) {
                cols += Column("选项$l") { get(it).orEmpty() }
            }
        }
        cols += Column("答案") { it.answer }
        return cols
    }

    private fun letterValue(l: Char): (QuestionEntity) -> String? = when (l) {
        'A' -> { q: QuestionEntity -> q.optionA }
        'B' -> { q: QuestionEntity -> q.optionB }
        'C' -> { q: QuestionEntity -> q.optionC }
        'D' -> { q: QuestionEntity -> q.optionD }
        'E' -> { q: QuestionEntity -> q.optionE }
        else -> { q: QuestionEntity -> q.optionF }
    }
}
