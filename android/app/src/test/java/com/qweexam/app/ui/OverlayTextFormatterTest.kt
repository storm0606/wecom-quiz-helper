package com.qweexam.app.ui

import com.qweexam.app.service.MatchResult
import org.junit.Assert.assertEquals
import org.junit.Test

/** 悬浮窗文本格式化测试（v1 显示规则） */
class OverlayTextFormatterTest {

    @Test
    fun null_result_showsWaiting() {
        val (answer, meta) = OverlayTextFormatter.format(null)
        assertEquals("等待题目…", answer)
        assertEquals("", meta)
    }

    @Test
    fun ok_singleChoice() {
        val r = MatchResult("对向道岔", "ok", "exact", "B", null)
        val (answer, meta) = OverlayTextFormatter.format(r)
        assertEquals("对向道岔", answer)
        assertEquals("✓ 精确匹配 题库答案:B", meta)
    }

    @Test
    fun ok_multiChoice_numbered() {
        val r = MatchResult("1.洗车作业 2.列车连挂 3.司机救援动车演练 4.占用轨道送车辆", "ok", "exact", "ABCD", null)
        val (answer, meta) = OverlayTextFormatter.format(r)
        assertEquals("1.洗车作业 2.列车连挂 3.司机救援动车演练 4.占用轨道送车辆", answer)
        assertEquals("✓ 精确匹配 题库答案:ABCD", meta)
    }

    @Test
    fun ok_judge() {
        val r = MatchResult("正确", "ok", "exact", "A", null)
        val (answer, meta) = OverlayTextFormatter.format(r)
        assertEquals("正确", answer)
    }

    @Test
    fun miss_showsHint() {
        val r = MatchResult("未命中题库（可尝试补题库或 OCR 兜底）", "miss", "none", null, null)
        val (answer, meta) = OverlayTextFormatter.format(r)
        assertEquals("未命中题库（可尝试补题库或 OCR 兜底）", answer)
        assertEquals("未命中", meta)
    }

    @Test
    fun ambiguous_showsWarning() {
        val r = MatchResult("有歧义，请人工判断：1号 → B；2号 → C", "ambiguous", "exact", null, null)
        val (answer, meta) = OverlayTextFormatter.format(r)
        assertEquals("有歧义，请人工判断：1号 → B；2号 → C", answer)
        assertEquals("⚠ 有歧义 精确匹配", meta)
    }

    @Test
    fun noBank_hint() {
        val r = MatchResult("请先导入题库", "miss", "none", null, null)
        val (answer, _) = OverlayTextFormatter.format(r)
        assertEquals("请先导入题库", answer)
    }

    @Test
    fun fuzzy_meta() {
        val r = MatchResult("属地管理权", "ok", "fuzzy", "B", null)
        val (_, meta) = OverlayTextFormatter.format(r)
        assertEquals("✓ 模糊匹配 题库答案:B", meta)
    }

    @Test
    fun ok_withScreenAnswer_crossCheck() {
        val r = MatchResult("车站接车人员", "ok", "exact", "B", null, screenAnswer = "B")
        val (_, meta) = OverlayTextFormatter.format(r)
        assertEquals("✓ 精确匹配 题库答案:B 屏幕答案:B", meta)
    }

    @Test
    fun screenFallback_showsScreenAnswer() {
        // 错题/回顾模式：题库未命中但屏幕显示正确答案 → 用屏幕答案兜底
        val r = MatchResult("屏幕答案：B.车站接车人员", "ok", "screen", null, null, screenAnswer = "B")
        val (answer, meta) = OverlayTextFormatter.format(r)
        assertEquals("屏幕答案：B.车站接车人员", answer)
        assertEquals("✓ 屏幕正确答案 屏幕答案:B", meta)
    }
}
