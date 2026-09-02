package com.qweexam.app.service

import com.qweexam.app.data.db.QuestionEntity
import com.qweexam.app.data.parser.Normalizer

/** 匹配结果 */
data class MatchResult(
    val display: String,
    val status: String,        // ok / warn / miss / ambiguous
    val mode: String,          // exact / contain / fuzzy / screen / none
    val bankAnswer: String?,
    val question: QuestionEntity?,
    val screenAnswer: String? = null   // 屏幕显示的正确答案（错题/回顾模式），兜底与交叉校验用
)

/**
 * 答案匹配器 —— 移植自原型 prototype/matcher.py。
 * 三级匹配：精确 → 包含（屏幕文本混入选项）→ 模糊（LCS 相似度 ≥0.78）。
 * v1 显示：只显示答案内容，不做屏幕 ABCD 定位。
 */
object AnswerMatcher {

    private const val RATIO_THRESHOLD = 0.78
    private val JUDGE_TRUE = setOf("正确", "对", "是", "√", "T", "TRUE")
    private val JUDGE_FALSE = setOf("错误", "错", "否", "×", "X", "F", "FALSE")

    fun optionsOf(q: QuestionEntity): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        listOf(
            "A" to q.optionA, "B" to q.optionB, "C" to q.optionC,
            "D" to q.optionD, "E" to q.optionE, "F" to q.optionF
        ).forEach { (k, v) ->
            if (!v.isNullOrBlank()) m[k] = v.trim()
        }
        return m
    }

    private fun judgeText(ans: String?): String {
        val a = (ans ?: "").trim().uppercase()
        return when {
            a in JUDGE_TRUE -> "正确"
            a in JUDGE_FALSE -> "错误"
            else -> (ans ?: "").trim()
        }
    }

    /** 判断题答案归一化：文字，或字母（如 A）→ 通过题库选项翻译成文字 */
    private fun judgeAnswerText(q: QuestionEntity): String {
        var t = judgeText(q.answer)
        if (t.length == 1 && t[0] in "ABCD") {
            val opt = optionsOf(q)[t]
            if (opt != null) t = judgeText(opt)
        }
        return t
    }

    /** 最长公共子序列相似度 2*LCS/(len1+len2)（近似 difflib ratio） */
    private fun lcsRatio(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val n = a.length
        val m = b.length
        var prev = IntArray(m + 1)
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            for (j in 1..m) {
                cur[j] = if (a[i - 1] == b[j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val tmp = prev
            prev = cur
            cur = tmp
            cur[0] = 0
        }
        return 2.0 * prev[m] / (n + m)
    }

    /** 匹配并生成 v1 显示答案 */
    fun match(stemText: String, questions: List<QuestionEntity>): MatchResult {
        val ns = Normalizer.normalize(stemText)
        if (ns.isEmpty()) return MatchResult("未命中题库", "miss", "none", null, null)

        // 1) 精确
        val exact = questions.filter { it.stemNorm == ns }
        if (exact.isNotEmpty()) return resolve(exact, "exact")

        // 2) 包含：屏幕文本可能是题干+选项混在一起的大文本块
        val contain = questions
            .filter { it.stemNorm.length >= 6 && ns.contains(it.stemNorm) }
            .sortedByDescending { it.stemNorm.length }
        if (contain.isNotEmpty()) return resolve(contain, "contain")

        // 3) 模糊：长度粗筛 + LCS 相似度
        val fuzzy = questions
            .filter {
                it.stemNorm.isNotEmpty() &&
                    it.stemNorm.length * 0.6 <= ns.length &&
                    ns.length <= it.stemNorm.length * 1.8
            }
            .map { it to lcsRatio(ns, it.stemNorm) }
            .filter { it.second >= RATIO_THRESHOLD }
            .sortedByDescending { it.second }
            .map { it.first }
        if (fuzzy.isNotEmpty()) return resolve(fuzzy, "fuzzy")

        return MatchResult("未命中题库（可尝试补题库或 OCR 兜底）", "miss", "none", null, null)
    }

    private fun resolve(cands: List<QuestionEntity>, mode: String): MatchResult {
        if (cands.map { it.answer }.distinct().size > 1) {
            val detail = cands.take(5).joinToString("；") { "${it.seq}号 → ${it.answer}" }
            return MatchResult("有歧义，请人工判断：$detail", "ambiguous", mode, null, cands.first())
        }
        val q = cands.first()
        return MatchResult(v1Display(q), "ok", mode, q.answer, q)
    }

    /** v1：只显示答案内容（单选选项文字 / 多选编号文字 / 判断 / 简答） */
    fun v1Display(q: QuestionEntity): String {
        val opts = optionsOf(q)
        return when (q.qtype) {
            "单选" -> opts[q.answer] ?: q.answer
            "多选" -> {
                val letters = q.answer.filter { it in "ABCDEF" }
                val texts = letters.mapNotNull { opts[it.toString()]?.trim() }.filter { it.isNotEmpty() }
                if (texts.isNotEmpty()) {
                    val shown = mutableListOf<String>()
                    for (t in texts) {
                        shown.add(
                            if (t.firstOrNull()?.isDigit() == true) t else "${shown.size + 1}.$t"
                        )
                    }
                    shown.joinToString(" ")
                } else {
                    q.answer
                }
            }
            "判断" -> judgeAnswerText(q)
            else -> q.answer.ifEmpty { "（题库无答案）" }
        }
    }
}
