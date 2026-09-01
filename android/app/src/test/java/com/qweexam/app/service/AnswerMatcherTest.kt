package com.qweexam.app.service

import com.qweexam.app.data.db.QuestionEntity
import com.qweexam.app.data.parser.Normalizer
import org.junit.Assert.assertEquals
import org.junit.Test

/** 答案匹配器单元测试：v1 显示规则 + 三级匹配 + 歧义/未命中 */
class AnswerMatcherTest {

    private fun q(
        seq: String, qtype: String, stem: String,
        opts: Map<String, String>, answer: String
    ): QuestionEntity = QuestionEntity(
        bankId = 1, seq = seq, qtype = qtype, stem = stem,
        stemNorm = Normalizer.normalize(stem),
        optionA = opts["A"], optionB = opts["B"], optionC = opts["C"],
        optionD = opts["D"], optionE = opts["E"], optionF = opts["F"],
        answer = answer
    )

    private val bank = listOf(
        q("400", "单选", "根据列车运行方向、当列车迎着道岔尖轨运行时、该道岔就叫（）。",
            mapOf("A" to "顺向道岔", "B" to "对向道岔", "C" to "电动道岔", "D" to "侧向道岔"), "B"),
        q("500", "判断", "其他部门或单位人员需要登车作业时、作业负责人与信号楼联系、取得同意后、办理相关的手续后方可作业。作业完毕后负责人向信号楼办理注销手续后方可离开。",
            mapOf("A" to "正确", "B" to "错误"), "错误"),
        q("600", "多选", "在段场（含试车线）轨道行车区范围内开行电客车/工程车的施工（不含车辆检修作业动车）为B1类施工。",
            mapOf("A" to "洗车作业", "B" to "列车连挂", "C" to "司机救援动车演练", "D" to "占用轨道送车辆"), "ABCD"),
        q("2", "单选", "“三权移交”是指建设单位将调度指挥权、设施设备使用权和什么权交给运营单位的行为?",
            mapOf("A" to "经营管理权", "B" to "属地管理权", "C" to "票务收益权", "D" to "广告经营权"), "B"),
        q("3", "单选", "判断字母答案翻译测试",
            mapOf("A" to "正确", "B" to "错误"), "A")
    )

    @Test
    fun single_exactMatch() {
        val r = AnswerMatcher.match("根据列车运行方向、当列车迎着道岔尖轨运行时、该道岔就叫（）。", bank)
        assertEquals("对向道岔", r.display)
        assertEquals("exact", r.mode)
        assertEquals("ok", r.status)
        assertEquals("B", r.bankAnswer)
    }

    @Test
    fun judge_answerText() {
        val r = AnswerMatcher.match(
            "其他部门或单位人员需要登车作业时、作业负责人与信号楼联系、取得同意后、办理相关的手续后方可作业。作业完毕后负责人向信号楼办理注销手续后方可离开。",
            bank
        )
        assertEquals("错误", r.display)
        assertEquals("ok", r.status)
    }

    @Test
    fun judge_letterAnswer_translatedThroughOptions() {
        val r = AnswerMatcher.match("判断字母答案翻译测试", bank)
        assertEquals("正确", r.display)  // 答案 A -> 选项A文字"正确"
        assertEquals("ok", r.status)
    }

    @Test
    fun multi_numberedOptionTexts() {
        val r = AnswerMatcher.match(
            "在段场（含试车线）轨道行车区范围内开行电客车/工程车的施工（不含车辆检修作业动车）为B1类施工。",
            bank
        )
        assertEquals("1.洗车作业 2.列车连挂 3.司机救援动车演练 4.占用轨道送车辆", r.display)
        assertEquals("ABCD", r.bankAnswer)
        assertEquals("ok", r.status)
    }

    @Test
    fun fuzzy_slightStemChange() {
        // 题干轻微改动（哪种 vs 什么）→ 模糊命中
        val r = AnswerMatcher.match(
            "三权移交是指建设单位将调度指挥权、设施设备使用权和哪种权交给运营单位的行为?",
            bank
        )
        assertEquals("属地管理权", r.display)
        assertEquals("fuzzy", r.mode)
        assertEquals("ok", r.status)
    }

    @Test
    fun contain_screenBlob() {
        // 屏幕文本 = 题干 + 选项混在一起（v1 只取题干匹配）
        val r = AnswerMatcher.match(
            "根据列车运行方向、当列车迎着道岔尖轨运行时、该道岔就叫（）。顺向道岔对向道岔电动道岔侧向道岔",
            bank
        )
        assertEquals("对向道岔", r.display)
        assertEquals("contain", r.mode)
    }

    @Test
    fun miss_noMatch() {
        val r = AnswerMatcher.match("地球上最高的山峰是什么?", bank)
        assertEquals("miss", r.status)
        assertEquals("none", r.mode)
    }

    @Test
    fun ambiguous_sameStemDifferentAnswer() {
        val dup = bank + q("99", "单选", "根据列车运行方向、当列车迎着道岔尖轨运行时、该道岔就叫（）。",
            mapOf("A" to "顺向道岔", "B" to "对向道岔", "C" to "电动道岔", "D" to "侧向道岔"), "C")
        val r = AnswerMatcher.match("根据列车运行方向、当列车迎着道岔尖轨运行时、该道岔就叫（）。", dup)
        assertEquals("ambiguous", r.status)
        assert(r.display.contains("歧义"))
    }

    @Test
    fun shortAnswer_text() {
        val bank2 = listOf(
            q("200", "简答", "简述城市轨道交通的运营特点。", emptyMap(), "大运量、准点、安全、便捷")
        )
        val r = AnswerMatcher.match("简述城市轨道交通的运营特点。", bank2)
        assertEquals("大运量、准点、安全、便捷", r.display)
        assertEquals("ok", r.status)
    }

    // ---------- v1.2：合并所有题库匹配 ----------

    @Test
    fun mergedBanks_distinctStems_found() {
        val bankA = listOf(
            q("1", "单选", "题库A专属题目一", mapOf("A" to "甲", "B" to "乙", "C" to "丙", "D" to "丁"), "A"),
            q("2", "单选", "共有的题目", mapOf("A" to "公共甲", "B" to "公共乙", "C" to "公共丙", "D" to "公共丁"), "B")
        )
        val bankB = listOf(
            q("101", "单选", "题库B专属题目一", mapOf("A" to "甲", "B" to "乙", "C" to "丙", "D" to "丁"), "C"),
            q("102", "单选", "共有的题目", mapOf("A" to "公共甲", "B" to "公共乙", "C" to "公共丙", "D" to "公共丁"), "C")
        )
        val merged = bankA + bankB

        // 专属题：合并后可命中
        val r1 = AnswerMatcher.match("题库A专属题目一", merged)
        assertEquals("甲", r1.display)
        assertEquals("ok", r1.status)

        val r2 = AnswerMatcher.match("题库B专属题目一", merged)
        assertEquals("丙", r2.display)
        assertEquals("ok", r2.status)
    }

    @Test
    fun mergedBanks_sameStemDifferentAnswer_ambiguous() {
        val bankA = listOf(
            q("1", "单选", "跨库同题", mapOf("A" to "甲", "B" to "乙", "C" to "丙", "D" to "丁"), "A")
        )
        val bankB = listOf(
            q("101", "单选", "跨库同题", mapOf("A" to "甲", "B" to "乙", "C" to "丙", "D" to "丁"), "C")
        )
        val r = AnswerMatcher.match("跨库同题", bankA + bankB)
        assertEquals("ambiguous", r.status)
        assert(r.display.contains("歧义"))
    }

    @Test
    fun mergedBanks_sameStemSameAnswer_ok() {
        val bankA = listOf(
            q("1", "单选", "跨库同题同答案", mapOf("A" to "甲", "B" to "乙", "C" to "丙", "D" to "丁"), "B")
        )
        val bankB = listOf(
            q("101", "单选", "跨库同题同答案", mapOf("A" to "甲", "B" to "乙", "C" to "丙", "D" to "丁"), "B")
        )
        val r = AnswerMatcher.match("跨库同题同答案", bankA + bankB)
        assertEquals("ok", r.status)
        assertEquals("乙", r.display)
    }
}
