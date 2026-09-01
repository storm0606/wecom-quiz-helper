package com.qweexam.app.service

import android.graphics.Rect

/** 屏幕节点：文本 + 屏幕坐标 */
data class ScreenNode(val text: String, val bounds: Rect?)

/** 提取出的题目块 */
data class QuestionBlock(
    val header: String,
    val stem: String,
    val options: Map<String, String>,
    val groundTruth: String?
)

/**
 * 屏幕题目提取器 —— 移植自原型 prototype/dump_reader.py（已用真实控件树验证）。
 * 规则：
 * - 以"第N题"节点为题目块起点；重复题号行（顶部悬浮+正文）去重；
 * - 选项为"字母节点 + 文字节点"分离（同一行，y 坐标中心配对 ≤40px），
 *   也兼容 "A.xxx" 合并形式；
 * - 拆分式题号行分值节点 "(1.67 分)" 跳过；题型标签/进度/及格线跳过；
 * - "正确答案：X" 仅开发期交叉校验用（正式作答时不存在）。
 */
object ScreenQuestionExtractor {

    private val HEADER_RE = Regex("第\\s*\\d+\\s*题")
    private val TYPE_LABELS = setOf("单选", "多选", "判断", "判断题", "单选题", "多选题")
    private val ANSWER_RE = Regex("正确答案\\s*[:：]?\\s*([ABCD]+)")
    private val SCORE_RE = Regex("\\(\\d+(\\.\\d+)?\\s*分\\)")
    private val SINGLE_LETTER_RE = Regex("[A-Da-d]")
    private val ROW_TOLERANCE = 40

    private fun isSkip(text: String): Boolean {
        if (text.isEmpty()) return true
        if (text in TYPE_LABELS) return true
        if (Regex("\\d+").matches(text) || Regex("/\\d+").matches(text) ||
            Regex("\\d+(\\.\\d+)?%").matches(text)
        ) return true
        if (SCORE_RE.matches(text)) return true
        if (text.contains("回答正确") || text.contains("回答错误") ||
            text.contains("正确答案") || text.contains("及格线")
        ) return true
        return false
    }

    private fun centerY(rect: Rect?): Float? =
        rect?.let { (it.top + it.bottom) / 2f }

    /** 字母节点之后找同一行的文字节点作为选项内容，返回 (字母, 文字, 下一处理下标) */
    private fun optionText(nodes: List<ScreenNode>, j: Int): Triple<String, String, Int>? {
        val letter = nodes[j].text
        val base = centerY(nodes[j].bounds)
        var k = j + 1
        while (k < nodes.size) {
            val tk = nodes[k].text
            if (isSkip(tk) || SINGLE_LETTER_RE.matches(tk)) {
                k++
                continue
            }
            val bk = centerY(nodes[k].bounds)
            if (base != null && bk != null && kotlin.math.abs(base - bk) > ROW_TOLERANCE) {
                return null
            }
            return Triple(letter, tk, k + 1)
        }
        return null
    }

    /** 从节点列表提取题目块 */
    fun findQuizBlocks(nodes: List<ScreenNode>): List<QuestionBlock> {
        val blocks = mutableListOf<QuestionBlock>()
        var i = 0
        while (i < nodes.size) {
            val t = nodes[i].text
            if (HEADER_RE.containsMatchIn(t)) {
                var j = i + 1
                val stemParts = mutableListOf<String>()
                val options = LinkedHashMap<String, String>()
                var groundTruth: String? = null
                while (j < nodes.size) {
                    val tj = nodes[j].text
                    if (HEADER_RE.containsMatchIn(tj)) {
                        // 重复题号行（中间无实质内容）直接跳过
                        if (tj == t && stemParts.isEmpty() && options.isEmpty() && groundTruth == null) {
                            j++
                            continue
                        }
                        break
                    }
                    if (tj.contains("上一题") || tj.contains("下一题")) break
                    val ansM = ANSWER_RE.find(tj)
                    if (ansM != null) {
                        groundTruth = ansM.groupValues[1]
                        j++
                        continue
                    }
                    if (isSkip(tj)) {
                        j++
                        continue
                    }
                    // 选项：独立字母节点 + 同一行文字节点
                    if (SINGLE_LETTER_RE.matches(tj)) {
                        val pair = optionText(nodes, j)
                        if (pair != null) {
                            options[pair.first.uppercase()] = pair.second
                            j = pair.third
                            continue
                        }
                        j++
                        continue
                    }
                    // 选项：字母+文字合并形式（A.xxx / B、xxx）
                    val m = Regex("^([A-Da-d])[\\.、．:：\\s]").find(tj)
                    if (m != null) {
                        options[m.groupValues[1].uppercase()] = tj.substring(m.range.last + 1).trim()
                        j++
                        continue
                    }
                    stemParts.add(tj)
                    j++
                }
                blocks.add(
                    QuestionBlock(
                        header = t,
                        stem = stemParts.joinToString(""),
                        options = options,
                        groundTruth = groundTruth
                    )
                )
                i = j
            } else {
                i++
            }
        }
        return blocks
    }
}
