package com.qweexam.app.ui

import com.qweexam.app.data.db.QuestionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 题库内容预览：抽样（每种题型 ≤5 题、组序固定）与表格列规则（纯函数）单测。
 */
class BankPreviewModelTest {

    private fun q(
        id: Long,
        type: String,
        stem: String,
        a: String? = null,
        b: String? = null,
        c: String? = null,
        d: String? = null,
        e: String? = null,
        f: String? = null,
        answer: String = "A"
    ) = QuestionEntity(
        id = id, bankId = 1, seq = id.toString(), qtype = type,
        stem = stem, stemNorm = stem,
        optionA = a, optionB = b, optionC = c, optionD = d, optionE = e, optionF = f,
        answer = answer
    )

    @Test
    fun sampleByType_perTypeCapAndOrder() {
        val rows = buildList {
            // 单选 8 道、多选 12 道、判断 2 道、未知 1 道（故意乱序传入，应按 id 升序取前 5）
            for (i in 1..8) add(q(i.toLong(), "单选", "单$i"))
            for (i in 1..12) add(q((100 + i).toLong(), "多选", "多$i", a = "甲", b = "乙", answer = "AB"))
            add(q(50, "判断", "判1", a = "正确", b = "错误"))
            add(q(200, "未知", "未1"))
            add(q(60, "判断", "判2", a = "对", b = "错"))
        }.shuffled()

        val groups = BankPreviewModel.sampleByType(rows)
        assertEquals(listOf("单选", "多选", "判断", "未知"), groups.map { it.first })
        assertEquals(listOf(5, 5, 2, 1), groups.map { it.second.size })
        // 单选取 id 最小前 5（1..5），顺序保持
        assertEquals((1..5).toList(), groups[0].second.map { it.id.toInt() })
        assertEquals(listOf(50L, 60L), groups[2].second.map { it.id })
    }

    @Test
    fun typeCounts_countsInOrder() {
        val rows = listOf(
            q(1, "单选", "s1"), q(2, "单选", "s2"),
            q(3, "多选", "m1", answer = "AB"),
            q(4, "判断", "j1", a = "正确", b = "错误"),
            q(5, "未知", "u1")
        )
        assertEquals(
            listOf("单选" to 2, "多选" to 1, "判断" to 1, "未知" to 1),
            BankPreviewModel.typeCounts(rows)
        )
    }

    @Test
    fun tableColumns_hidesEmptyOptionColumns() {
        // 所有行都没有 C/E/F 选项 → 表格列不含 选项C/E/F
        val rows = listOf(
            q(1, "单选", "题干1", a = "甲", b = "乙", d = "丁"),
            q(2, "多选", "题干2", a = "甲", b = "乙", d = "丁", answer = "ABD")
        )
        val cols = BankPreviewModel.tableColumns(rows)
        assertEquals(
            listOf("题型", "题目", "选项A", "选项B", "选项D", "答案"),
            cols.map { it.header }
        )
        // 取值与表头对应：题目列取题干、选项D列取丁、答案列取归一化答案
        val byHeader = cols.associateBy { it.header }
        assertEquals("题干2", byHeader.getValue("题目").value(rows[1]))
        assertEquals("丁", byHeader.getValue("选项D").value(rows[1]))
        assertEquals("ABD", byHeader.getValue("答案").value(rows[1]))
        assertFalse(cols.any { it.header == "选项E" })
    }
}
