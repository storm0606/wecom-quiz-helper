package com.qweexam.app.data.parser

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 复现导入页流程（v1.2 用户反馈 .xls 导入 0 题）：
 * parseRows(取第一个 sheet 预览) → autoColumnRoles → 用映射解析全部 sheet。
 */
class XlsImportFlowTest {

    private val XLS = File("../../题库2.xls")

    @Test
    fun importFlow_mappingPath_yieldsQuestions() {
        assertTrue("题库2.xls 不存在", XLS.exists())
        val data = XLS.readBytes()

        // 1) 预览：parseRows = 第一个 sheet
        val previewRows = BankParser.parseRows("题库2.xls", data)
        assertTrue("预览行数应为 671，实际 ${previewRows.size}", previewRows.size == 671)

        // 2) 自动列角色
        val roles = BankParser.autoColumnRoles(previewRows)
        println("自动角色: $roles")
        assertTrue(roles.any { it.role == ColumnRoles.STEM })

        // 3) 用映射解析全部 sheet（等价于 importBank 的 xls 分支）
        val mapping = HashMap<String, Int>()
        for ((idx, cr) in roles.withIndex()) {
            if (cr.role != ColumnRoles.IGNORE) mapping[cr.role] = idx
        }
        val questions = BankParser.parseXls(data)
            .flatMap { BankParser.rowsToQuestions(it, mapping) }
        println("映射路径题数: ${questions.size}")
        assertEquals(1129, questions.size)
    }
}
