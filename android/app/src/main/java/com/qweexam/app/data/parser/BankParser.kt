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
        ColumnRoles.ANSWER to listOf("答案", "正确答案", "参考答案", "标准答案", "题目答案"),
        // v2.4：带表头文件若选项是单列且表头就叫"选项"（无 A–F 后缀），也要自动识别为选项列
        ColumnRoles.OPTIONS_SINGLE to listOf("选项")
    )

    private val JUDGE_WORDS = setOf("正确", "错误", "对", "错")

    // ---------- 答案/选项处理 ----------

    /**
     * 答案归一化（v2.4 增强）：
     * - 纯字母答案：'A|B|C|D|' / 'A B C' / 'A | B' / 'A、B' / 'A.B.C.' / 小写 → 'ABC'
     *   （去掉空白与常见分隔/标点，域 A–F；答案集中在一个单元格用空格等分隔的格式也能识别）
     * - 其它内容（如数字填空 '50|100'、判断文字 '正确'）→ 分隔符替换为 '、'（保留可读性，避免 '50100'）
     */
    fun normalizeAnswer(raw: String?): String {
        val s = (raw ?: "").trim()
        val letters = s.filterNot { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it in "|｜、,，;；/．.:：" }
            .uppercase()
        return if (letters.isNotEmpty() && letters.all { it in "ABCDEF" }) {
            letters
        } else {
            s.replace(Regex("[|｜,，;；/]+"), "、").trim()
        }
    }

    /**
     * 单列选项拆分（v2.4 增强）：
     * 1) 含 | 分隔：'A、正线 |B、停车场 |C、车辆段 |' -> {'A':'正线',...}；无前缀按顺序 A/B/C/D
     * 2) 无 | 的单格连写（'A 上行 B 下行 C 车辆段' / 'A.上行B.下行'，选项挤在一个单元格）-> 按字母边界拆分
     * 3) 无法按字母识别时，整格作为唯一选项 A
     */
    fun splitOptions(raw: String?): Map<String, String> {
        val s = (raw ?: "").trim()
        if (s.isEmpty()) return emptyMap()
        if (!s.contains('|') && !s.contains('｜')) {
            splitLetterOptions(s)?.let { return it }
        }
        val parts = s.split(Regex("[|｜]+"))
        val opts = LinkedHashMap<String, String>()
        var next = 'A'
        for (p in parts) {
            val pp = p.trim()
            if (pp.isEmpty()) continue
            val m = Regex("^([A-Fa-f])[\\.、．:：\\s]").find(pp)
            if (m != null) {
                opts[m.groupValues[1].uppercase()] = pp.substring(m.range.last + 1).trim()
            } else {
                opts[next.toString()] = pp
                next++
            }
        }
        return opts
    }

    /**
     * 字母边界拆分（仅用于"选项"单列、无 | 分隔的单元格）。
     * 候选标记 = A–F 字母，且满足二者之一：
     * - 后紧跟标点 .、．:：)） （支持紧凑连写 'A.上行B.下行'、'A、正线B、停车场'）
     * - 前后成词边界（前一字符是空白/串首，后一字符是空白/串尾，支持 'A 上行 B 下行'）
     * 门槛（全部满足才拆，否则返回 null 由调用方兜底）：
     * 标记 ≥2；首标记必须是串首的 A；字母严格递增；每段内容 trim 后非空；标记数 ≤6。
     */
    private fun splitLetterOptions(s: String): Map<String, String>? {
        val n = s.length
        val marks = ArrayList<Pair<Int, Char>>()   // (字母下标, 大写字母)
        var i = 0
        while (i < n) {
            val c = s[i]
            if (c in 'A'..'F' || c in 'a'..'f') {
                val punct = i + 1 < n && s[i + 1] in ".、．:：)）"
                val boundary = (i == 0 || s[i - 1].isWhitespace()) &&
                    (i + 1 == n || s[i + 1].isWhitespace())
                if (punct || boundary) {
                    marks.add(i to c.uppercaseChar())
                    i += if (punct) 2 else 1
                    continue
                }
            }
            i++
        }
        if (marks.size < 2) return null
        if (marks[0].first != 0 || marks[0].second != 'A') return null
        for (k in 1 until marks.size) {
            if (marks[k].second <= marks[k - 1].second) return null
        }
        if (marks.size > 6) return null

        val out = LinkedHashMap<String, String>()
        for (k in marks.indices) {
            var cs = marks[k].first + 1
            while (cs < n && (s[cs].isWhitespace() || s[cs] in ".、．:：)）")) cs++
            val ce = if (k + 1 < marks.size) marks[k + 1].first else n
            var content = s.substring(cs, ce).trim()
            while (content.isNotEmpty() && content.last() in "、,，;；") {
                content = content.dropLast(1).trim()
            }
            if (content.isEmpty()) return null
            out[marks[k].second.toString()] = content
        }
        return out
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
        val letters = normalizeAnswer(answer).uppercase().filter { it in "ABCDEF" }
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
