package com.qweexam.app.data.parser

import java.io.File

/** 手动列映射界面用的列角色 */
object ColumnRoles {
    const val IGNORE = "忽略"
    const val SEQ = "序号"
    const val QTYPE = "题型"
    const val STEM = "题目"
    const val ANSWER = "答案"
    const val OPTIONS_SINGLE = "选项"          // 单列 | 分隔
    const val OPTION_A = "选项A"
    const val OPTION_B = "选项B"
    const val OPTION_C = "选项C"
    const val OPTION_D = "选项D"
    const val OPTION_E = "选项E"
    const val OPTION_F = "选项F"

    val ALL = listOf(
        IGNORE, SEQ, QTYPE, STEM, ANSWER, OPTIONS_SINGLE,
        OPTION_A, OPTION_B, OPTION_C, OPTION_D, OPTION_E, OPTION_F
    )
}

/** 一列的角色建议；selectedRole 记录用户在导入界面的实际选择（避免 RecyclerView 复用导致读取错位） */
data class ColumnRole(
    val columnName: String,
    val role: String,
    var selectedRole: String = role
)

/** 题库解析总入口 + 列映射逻辑。移植自原型 prototype/bank_parser.py。 */
object BankParser {

    // 无表头时的布局猜测：列数 -> 角色顺序
    private val GUESS_LAYOUTS = mapOf(
        3 to listOf(ColumnRoles.STEM, ColumnRoles.OPTIONS_SINGLE, ColumnRoles.ANSWER),
        4 to listOf(ColumnRoles.SEQ, ColumnRoles.STEM, ColumnRoles.OPTIONS_SINGLE, ColumnRoles.ANSWER),
        5 to listOf(ColumnRoles.SEQ, ColumnRoles.QTYPE, ColumnRoles.STEM, ColumnRoles.OPTIONS_SINGLE, ColumnRoles.ANSWER),
        8 to listOf(
            ColumnRoles.SEQ, ColumnRoles.QTYPE, ColumnRoles.STEM,
            ColumnRoles.OPTION_A, ColumnRoles.OPTION_B, ColumnRoles.OPTION_C, ColumnRoles.OPTION_D,
            ColumnRoles.ANSWER
        )
    )

    private val COL_ALIASES = mapOf(
        ColumnRoles.SEQ to listOf("序号", "题号", "编号", "id", "ID"),
        ColumnRoles.QTYPE to listOf("题型", "类型", "题目类型"),
        ColumnRoles.STEM to listOf("题目", "题干", "问题", "试题", "题干内容", "题目标题"),
        ColumnRoles.ANSWER to listOf("答案", "正确答案", "参考答案", "标准答案", "题目答案")
    )

    private val JUDGE_WORDS = setOf("正确", "错误", "对", "错")

    // ---------- 答案/选项处理 ----------

    /**
     * 答案归一化：
     * - 纯字母答案 'A|B|C|D|' / 'A、B' → 'ABCD'（去掉分隔符）
     * - 其它内容（如数字填空 '50|100'）→ 分隔符替换为 '、'（保留可读性，避免 '50100'）
     */
    fun normalizeAnswer(raw: String?): String {
        val s = (raw ?: "").trim()
        val stripped = s.replace(Regex("[|｜、,，;；/]+"), "").trim()
        return if (stripped.isNotEmpty() && stripped.all { it in "ABCD" }) {
            stripped
        } else {
            s.replace(Regex("[|｜,，;；/]+"), "、").trim()
        }
    }

    /** 单列选项 'A、正线 |B、停车场 |C、车辆段 |' -> {'A':'正线',...}；无前缀按顺序 A/B/C/D */
    fun splitOptions(raw: String?): Map<String, String> {
        val s = (raw ?: "").trim()
        if (s.isEmpty()) return emptyMap()
        val parts = s.split(Regex("[|｜]+"))
        val opts = LinkedHashMap<String, String>()
        var next = 'A'
        for (p in parts) {
            val pp = p.trim()
            if (pp.isEmpty()) continue
            val m = Regex("^([A-Da-d])[\\.、．:：\\s]").find(pp)
            if (m != null) {
                opts[m.groupValues[1].uppercase()] = pp.substring(m.range.last + 1).trim()
            } else {
                opts[next.toString()] = pp
                next++
            }
        }
        return opts
    }

    /** 无题型列时，根据答案/选项推断题型 */
    fun inferQtype(qtypeRaw: String?, answer: String?, options: Map<String, String>): QuestionType {
        val qt = qtypeRaw?.trim() ?: ""
        if (qt.isNotEmpty()) return QuestionType.from(qt) ?: QuestionType.UNKNOWN
        if (options.isEmpty()) {
            return if (normalizeAnswer(answer) in JUDGE_WORDS) QuestionType.JUDGE else QuestionType.SHORT
        }
        if (options.size == 2 && options.values.all { normalizeAnswer(it) in JUDGE_WORDS }) {
            return QuestionType.JUDGE
        }
        val letters = normalizeAnswer(answer).uppercase().filter { it in "ABCD" }
        return if (letters.length > 1) QuestionType.MULTI else QuestionType.SINGLE
    }

    // ---------- 表头/列映射 ----------

    fun findHeaderRow(rows: List<List<String>>): Int? {
        for ((i, row) in rows.withIndex()) {
            val joined = row.joinToString("")
            if (listOf("题型", "题目", "答案").any { it in joined }) return i
        }
        return null
    }

    /** 自动识别每列角色（手动映射界面的初始值）；无表头时按列数猜测 */
    fun autoColumnRoles(rows: List<List<String>>): List<ColumnRole> {
        val ncols = rows.maxOfOrNull { it.size } ?: 0
        val headerIdx = findHeaderRow(rows)
        if (headerIdx != null) {
            val names = rows[headerIdx].map { it.trim() }
            return (0 until ncols).map { i ->
                val name = names.getOrElse(i) { "" }
                val role = COL_ALIASES.entries.firstOrNull { (_, aliases) -> name in aliases }?.key
                    ?: ('A'..'F').firstOrNull { name.replace(" ", "") == "选项$it" }
                        ?.let { "选项$it" }
                    ?: ColumnRoles.IGNORE
                ColumnRole(name.ifEmpty { "列${i + 1}" }, role)
            }
        }
        val layout = GUESS_LAYOUTS[ncols] ?: (0 until ncols).map { ColumnRoles.IGNORE }
        return (0 until ncols).map { i -> ColumnRole("列${i + 1}", layout.getOrElse(i) { ColumnRoles.IGNORE }) }
    }

    /**
     * 二维表 -> 题目列表。
     * @param mapping 手动列映射：{角色 -> 列下标(0 起始)}；null 则自动识别/猜测
     */
    fun rowsToQuestions(rows: List<List<String>>, mapping: Map<String, Int>? = null): List<Question> {
        if (rows.isEmpty()) return emptyList()

        val roleToIdx = HashMap<String, Int>()
        var dataStart = 0

        if (mapping != null) {
            val headerIdx = findHeaderRow(rows)
            if (headerIdx != null) dataStart = headerIdx + 1
            for ((role, idx) in mapping) roleToIdx[role] = idx
            if (roleToIdx.isEmpty()) throw IllegalArgumentException("手动列映射未匹配到任何列：$mapping")
        } else {
            val headerIdx = findHeaderRow(rows)
            if (headerIdx != null) {
                val headerNames = rows[headerIdx].map { c -> c.trim() }
                dataStart = headerIdx + 1
                for ((role, aliases) in COL_ALIASES) {
                    for ((i, name) in headerNames.withIndex()) {
                        if (name in aliases) {
                            roleToIdx[role] = i
                            break
                        }
                    }
                }
                for (l in 'A'..'F') {
                    for ((i, name) in headerNames.withIndex()) {
                        if (name.replace(" ", "") == "选项$l") {
                            roleToIdx["选项$l"] = i
                            break
                        }
                    }
                }
                if (!roleToIdx.containsKey(ColumnRoles.STEM)) {
                    throw IllegalArgumentException("未在表头中找到'题目'列，请手动指定列映射")
                }
            } else {
                val ncols = rows.maxOfOrNull { it.size } ?: 0
                val layout = GUESS_LAYOUTS[ncols]
                    ?: throw IllegalArgumentException(
                        "未识别表头且列数($ncols)不在常见布局中，请手动指定列映射，例如：题目:1,选项:2,答案:3"
                    )
                for ((i, role) in layout.withIndex()) roleToIdx[role] = i
                dataStart = 0
            }
        }

        fun get(row: List<String>, key: String): String {
            val i = roleToIdx[key] ?: return ""
            return if (i in row.indices) row[i] else ""
        }

        val out = mutableListOf<Question>()
        for (row in rows.drop(dataStart)) {
            if (row.none { it.isNotBlank() }) continue
            val stem = get(row, ColumnRoles.STEM).trim()
            if (stem.isEmpty()) continue

            // 选项：优先 选项A-F 列；否则单列"选项"
            val options = LinkedHashMap<String, String>()
            val letterCols = ('A'..'F').associateWith { roleToIdx["选项$it"] }
            if (letterCols.values.any { it != null }) {
                for ((l, i) in letterCols) {
                    if (i != null && i in row.indices && row[i].isNotBlank()) {
                        options[l.toString()] = row[i].trim()
                    }
                }
            } else {
                val single = get(row, ColumnRoles.OPTIONS_SINGLE)
                if (single.isNotEmpty()) options.putAll(splitOptions(single))
            }

            val qtype = inferQtype(get(row, ColumnRoles.QTYPE), get(row, ColumnRoles.ANSWER), options)
            val answerRaw = get(row, ColumnRoles.ANSWER)
            out.add(
                Question(
                    seq = get(row, ColumnRoles.SEQ),
                    type = qtype,
                    stem = stem,
                    options = options,
                    answer = normalizeAnswer(answerRaw),
                    answerRaw = answerRaw
                )
            )
        }
        return out
    }

    // ---------- 文件级入口 ----------

    fun parseCsv(text: String): List<List<String>> = CsvParser.parse(text)

    fun parseXlsx(data: ByteArray): List<List<String>> = XlsxParser.parse(data)

    fun parseXls(data: ByteArray): List<List<List<String>>> = XlsParser.parse(data)

    fun parseDocxTables(data: ByteArray): List<List<List<String>>> = DocxParser.parseTables(data)

    /** 解析出原始二维表（用于预览/列映射）；.xls 多 sheet 取行数最多的工作表 */
    fun parseRows(fileName: String, data: ByteArray): List<List<String>> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "xlsx" -> parseXlsx(data)
            "xls" -> parseXls(data).maxByOrNull { it.size } ?: emptyList()
            "docx" -> parseDocxTables(data).maxByOrNull { it.size } ?: emptyList()
            "csv" -> parseCsv(String(data, Charsets.UTF_8).removePrefix("\uFEFF"))
            else -> throw IllegalArgumentException("不支持的题库格式：$ext")
        }
    }

    /** 按扩展名加载题库文件 -> 题目列表（xlsx/xls/docx/csv） */
    fun loadBank(fileName: String, data: ByteArray, mapping: Map<String, Int>? = null): List<Question> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "xlsx" -> rowsToQuestions(parseXlsx(data), mapping)
            "xls" -> parseXls(data).flatMap { rowsToQuestions(it, mapping) }
            "docx" -> parseDocxTables(data).flatMap { rowsToQuestions(it, mapping) }
            "csv" -> rowsToQuestions(parseCsv(String(data, Charsets.UTF_8).removePrefix("\uFEFF")), mapping)
            else -> throw IllegalArgumentException("不支持的题库格式：$ext（支持 .csv/.xlsx/.xls/.docx）")
        }
    }

    /** 单元测试辅助：直接从文件读取 */
    fun loadBankFile(file: File, mapping: Map<String, Int>? = null): List<Question> =
        loadBank(file.name, file.readBytes(), mapping)
}
