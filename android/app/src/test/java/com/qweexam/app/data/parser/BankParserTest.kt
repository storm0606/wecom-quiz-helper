package com.qweexam.app.data.parser

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 题库解析器单元测试（JVM，无需设备）。
 * 关键：用真实题库文件（../../ 即 wecom-quiz-helper/ 根目录）与 Python 原型结果比对。
 */
class BankParserTest {

    // ---------- 归一化 ----------

    @Test
    fun normalize_fullWidthAndPunctuation() {
        assertEquals("三权移交是指建设单位将调度指挥权设施设备使用权和什么权交给运营单位的行为",
            Normalizer.normalize("“三权移交”是指建设单位将调度指挥权、设施设备使用权和什么权交给运营单位的行为?"))
        assertEquals("abc123", Normalizer.normalize("ＡＢＣ１２３"))
        assertEquals("跨行文本拼接", Normalizer.normalize("跨行\n文 本\t拼 接"))
    }

    // ---------- 答案/选项 ----------

    @Test
    fun normalizeAnswer_pipeFormat() {
        assertEquals("ABCD", BankParser.normalizeAnswer("A|B|C|D|"))
        assertEquals("AB", BankParser.normalizeAnswer("A、B"))
        assertEquals("D", BankParser.normalizeAnswer(" D "))
    }

    @Test
    fun normalizeAnswer_numericFillIn_keepsReadable() {
        // 数字填空答案 '50|100' 不应变成 '50100'，应保留分隔可读
        assertEquals("50、100", BankParser.normalizeAnswer("50|100"))
        assertEquals("正确", BankParser.normalizeAnswer("正确"))
    }

    // ---------- v2.4：答案/选项挤在一个单元格（答案空格/标点分隔、选项字母连写） ----------

    @Test
    fun normalizeAnswer_spaceDelimitedLetters() {
        // 多选答案在一个单元格：A B C / A | B / A.B.C. / 小写 -> 归一 ABC
        assertEquals("ABC", BankParser.normalizeAnswer("A B C"))
        assertEquals("AB", BankParser.normalizeAnswer("A | B"))
        assertEquals("AB", BankParser.normalizeAnswer("A、B"))
        assertEquals("ABC", BankParser.normalizeAnswer("A.B.C."))
        assertEquals("AB", BankParser.normalizeAnswer("a b"))
        assertEquals("ABC", BankParser.normalizeAnswer(" A B C | "))
        assertEquals("EF", BankParser.normalizeAnswer("E|F"))
        assertEquals("D", BankParser.normalizeAnswer("D"))
    }

    @Test
    fun splitOptions_lettersBoundary_inOneCell() {
        // 选项挤在一个单元格、字母后跟空格（选项文字可含数字）
        assertEquals(
            mapOf("A" to "上行", "B" to "下行", "C" to "车辆段"),
            BankParser.splitOptions("A 上行 B 下行 C 车辆段")
        )
        assertEquals(
            mapOf("A" to "12号线", "B" to "3号线", "C" to "1号线"),
            BankParser.splitOptions("A 12号线 B 3号线 C 1号线")
        )
        // 紧凑连写：点号 / 顿号紧跟字母
        assertEquals(
            mapOf("A" to "上行", "B" to "下行", "C" to "车辆段"),
            BankParser.splitOptions("A.上行B.下行C.车辆段")
        )
        assertEquals(
            mapOf("A" to "正线", "B" to "停车场", "C" to "车辆段"),
            BankParser.splitOptions("A、正线B、停车场C、车辆段")
        )
        // 小写字母
        assertEquals(mapOf("A" to "对", "B" to "错"), BankParser.splitOptions("a 对 b 错"))
        // A–F 六选项
        val six = BankParser.splitOptions("A 甲 B 乙 C 丙 D 丁 E 戊 F 己")
        assertEquals(6, six.size)
        assertEquals("己", six["F"])
    }

    @Test
    fun splitOptions_lettersBoundary_noFalseSplit() {
        // 非选项格（内容开头不是 A 标记 / 字母不成串）不误拆，整格作为唯一选项 A
        val mid = "下列对A、B两站之间联络线的表述"
        assertEquals(mapOf("A" to mid), BankParser.splitOptions(mid))
        // 相邻字母 AB 不是标记
        assertEquals(mapOf("A" to "AB"), BankParser.splitOptions("AB"))
        // 单个选项仍按整格分配 A（原有行为）
        assertEquals(mapOf("A" to "正确"), BankParser.splitOptions("正确"))
    }

    @Test
    fun rowsToQuestions_combinedCell_endToEnd() {
        // 表头 题目/选项/答案，选项格字母连写、答案格空格分隔 —— 应拆分并推断多选/判断
        val rows = listOf(
            listOf("题目", "选项", "答案"),
            listOf("问题一", "A 上行 B 下行 C 车辆段", "A B C"),
            listOf("问题二", "A 对 B 错", "A")
        )
        val qs = BankParser.rowsToQuestions(rows)
        assertEquals(2, qs.size)
        val multi = qs[0]
        assertEquals(QuestionType.MULTI, multi.type)
        assertEquals("ABC", multi.answer)
        assertEquals(mapOf("A" to "上行", "B" to "下行", "C" to "车辆段"), multi.options)
        val judge = qs[1]
        assertEquals(QuestionType.JUDGE, judge.type)
        assertEquals("A", judge.answer)
        assertEquals(mapOf("A" to "对", "B" to "错"), judge.options)
    }

    @Test
    fun splitOptions_pipeWithLetterPrefix() {
        val opts = BankParser.splitOptions("A、正线 |B、停车场 |C、车辆段 |")
        assertEquals(mapOf("A" to "正线", "B" to "停车场", "C" to "车辆段"), opts)
    }

    @Test
    fun splitOptions_noPrefixAssignInOrder() {
        val opts = BankParser.splitOptions("正确|错误")
        assertEquals(mapOf("A" to "正确", "B" to "错误"), opts)
    }

    @Test
    fun inferQtype_byAnswerAndOptions() {
        val four = mapOf("A" to "a", "B" to "b", "C" to "c", "D" to "d")
        assertEquals(QuestionType.SINGLE, BankParser.inferQtype(null, "B", four))
        assertEquals(QuestionType.MULTI, BankParser.inferQtype(null, "AB", four))
        assertEquals(
            QuestionType.JUDGE,
            BankParser.inferQtype(null, "A", mapOf("A" to "正确", "B" to "错误"))
        )
        assertEquals(QuestionType.SHORT, BankParser.inferQtype(null, "大运量、准点", emptyMap()))
        assertEquals(QuestionType.SINGLE, BankParser.inferQtype("单选", "A", emptyMap()))
    }

    // ---------- CSV ----------

    @Test
    fun csv_quotedFieldsWithCommaAndNewline() {
        val text = "序号,题型,题目,答案\n1,单选,\"含,逗号\n换行的题干\",A\n"
        val rows = CsvParser.parse(text)
        assertEquals(2, rows.size)
        assertEquals("含,逗号\n换行的题干", rows[1][2])
        assertEquals("A", rows[1][3])
    }

    // ---------- 真实题库：题库1.xlsx（无表头 4 列、选项|分隔、答案 A|B|C|D|） ----------

    @Test
    fun xlsx_realBank1_layoutGuess() {
        val file = File("../../题库1.xlsx")
        assertTrue("题库1.xlsx 不存在", file.exists())
        val bank = BankParser.loadBankFile(file)
        assertEquals(500, bank.size)

        val types = bank.groupingBy { it.type }.eachCount()
        assertEquals(mapOf(
            QuestionType.SINGLE to 250,
            QuestionType.MULTI to 100,
            QuestionType.JUDGE to 150
        ), types)

        // 多选答案归一化 A|B|C|D| -> ABCD
        val multi = bank.first { it.type == QuestionType.MULTI }
        assertEquals("ABCD", multi.answer)
        assertEquals(4, multi.options.size)

        // 判断题选项为 正确/错误，答案存字母
        val judge = bank.first { it.type == QuestionType.JUDGE }
        assertEquals("正确", judge.options["A"])
        assertEquals("错误", judge.options["B"])
    }

    @Test
    fun xlsx_realBank1_manualMapping() {
        val file = File("../../题库1.xlsx")
        val bank = BankParser.loadBankFile(file, mapping = mapOf("题目" to 1, "选项" to 2, "答案" to 3))
        assertEquals(500, bank.size)
        assertEquals("判断", bank.first().type.label)
    }

    // ---------- 真实题库：题库2.xls（老格式、题目类型/题目标题/题目答案 表头） ----------

    @Test
    fun xls_realBank2_headerAliases() {
        val file = File("../../题库2.xls")
        assertTrue("题库2.xls 不存在", file.exists())
        val bank = BankParser.loadBankFile(file)
        assertEquals(1129, bank.size)

        val types = bank.groupingBy { it.type }.eachCount()
        assertEquals(mapOf(
            QuestionType.SINGLE to 594,
            QuestionType.MULTI to 142,
            QuestionType.JUDGE to 393
        ), types)
    }

    // ---------- 合成 docx 表格 ----------

    @Test
    fun docx_syntheticTable() {
        val rows = listOf(
            listOf("序号", "题型", "题目", "答案"),
            listOf("1", "单选", "测试题干", "A")
        )
        val bytes = buildDocx(rows)
        val tables = DocxParser.parseTables(bytes)
        assertEquals(1, tables.size)
        assertEquals(rows, tables[0])
    }

    private fun buildDocx(rows: List<List<String>>): ByteArray {
        fun cell(t: String) =
            "<w:tc><w:p><w:r><w:t xml:space=\"preserve\">$t</w:t></w:r></w:p></w:tc>"
        val trs = rows.joinToString("") { row ->
            "<w:tr>${row.joinToString("") { cell(it) }}</w:tr>"
        }
        val doc = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:body><w:tbl><w:tblPr/>$trs</w:tbl></w:body></w:document>"
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { z ->
            z.putNextEntry(ZipEntry("word/document.xml"))
            z.write(doc.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return buf.toByteArray()
    }
}
