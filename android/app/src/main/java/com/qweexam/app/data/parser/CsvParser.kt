package com.qweexam.app.data.parser

/** 标准 CSV 解析（RFC4180：支持引号、转义双引号、逗号、换行） */
object CsvParser {

    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        sb.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    sb.append(c)
                }
            } else {
                when (c) {
                    '"' -> inQuotes = true
                    ',' -> { row.add(sb.toString()); sb.setLength(0) }
                    '\n' -> {
                        row.add(sb.toString()); sb.setLength(0)
                        rows.add(row.toList()); row.clear()
                    }
                    '\r' -> { /* 忽略，配合 \n 处理 */ }
                    else -> sb.append(c)
                }
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) {
            row.add(sb.toString())
            rows.add(row.toList())
        }
        return rows
    }
}
