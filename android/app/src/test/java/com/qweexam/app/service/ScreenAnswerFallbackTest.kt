package com.qweexam.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 错题/回顾场景屏幕答案兜底逻辑测试（v1.1） */
class ScreenAnswerFallbackTest {

    private fun block(groundTruth: String?, options: Map<String, String> = emptyMap()) =
        QuestionBlock(header = "第10题", stem = "测试题干", options = options, groundTruth = groundTruth)

    private fun okResult() = MatchResult("车站接车人员", "ok", "exact", "B", null)
    private fun missResult() = MatchResult("未命中题库", "miss", "none", null, null)
    private fun ambiguousResult() = MatchResult("有歧义", "ambiguous", "exact", null, null)

    @Test
    fun noGroundTruth_unchanged() {
        val r = ScreenAnswerFallback.merge(okResult(), block(null))
        assertEquals("车站接车人员", r.display)
        assertNull(r.screenAnswer)
    }

    @Test
    fun bankHit_attachesScreenAnswerForCrossCheck() {
        val r = ScreenAnswerFallback.merge(okResult(), block("B"))
        assertEquals("车站接车人员", r.display)
        assertEquals("B", r.screenAnswer)
        assertEquals("exact", r.mode)
    }

    @Test
    fun bankMiss_usesScreenAnswerFallback() {
        val r = ScreenAnswerFallback.merge(
            missResult(),
            block("B", mapOf("A" to "行车调度员", "B" to "车站接车人员", "C" to "车辆基地调度员", "D" to "信号楼值班员"))
        )
        assertEquals("屏幕答案：B.车站接车人员", r.display)
        assertEquals("ok", r.status)
        assertEquals("screen", r.mode)
        assertEquals("B", r.screenAnswer)
    }

    @Test
    fun bankMiss_screenAnswerLetterOnly_whenNoOptionText() {
        val r = ScreenAnswerFallback.merge(missResult(), block("A"))
        assertEquals("屏幕答案：A", r.display)
        assertEquals("ok", r.status)
    }

    @Test
    fun bankAmbiguous_usesScreenAnswerFallback() {
        val r = ScreenAnswerFallback.merge(
            ambiguousResult(),
            block("ABCD", mapOf("A" to "安全", "B" to "可靠", "C" to "便捷", "D" to "舒适"))
        )
        // 多选：逐字母映射选项文字
        assertEquals("屏幕答案：A.安全 B.可靠 C.便捷 D.舒适", r.display)
        assertEquals("ok", r.status)
        assertEquals("screen", r.mode)
    }

    @Test
    fun multiLetter_gt_missingOptions_fallsBackToLetters() {
        val r = ScreenAnswerFallback.merge(
            missResult(),
            block("ABCD", mapOf("A" to "安全"))  // 选项不全
        )
        assertEquals("屏幕答案：ABCD", r.display)
        assertEquals("ok", r.status)
    }
}
