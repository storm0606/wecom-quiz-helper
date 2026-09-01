package com.qweexam.app.data.parser

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** .xlsx 解析（OOXML：sharedStrings + 第一个工作表），返回二维表（含表头行） */
object XlsxParser {

    fun parse(data: ByteArray): List<List<String>> {
        val shared = mutableListOf<String>()
        var sheetXml: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when {
                    entry.name == "xl/sharedStrings.xml" -> {
                        shared.addAll(parseSharedStrings(zip.readBytes()))
                    }
                    entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml") -> {
                        if (sheetXml == null) sheetXml = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val sheetBytes = sheetXml ?: return emptyList()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(sheetBytes))
        val rowsEl = doc.getElementsByTagName("row")
        val grid = HashMap<Pair<Int, Int>, String>()
        var maxRow = 0
        var maxCol = 0
        for (i in 0 until rowsEl.length) {
            val rowEl = rowsEl.item(i) as Element
            val r = rowEl.getAttribute("r").toIntOrNull() ?: (i + 1)
            val cells = rowEl.getElementsByTagName("c")
            for (j in 0 until cells.length) {
                val c = cells.item(j) as Element
                val ref = c.getAttribute("r")
                val col = colIndex(ref)
                val type = c.getAttribute("t")
                val vEl = childElement(c, "v")
                val isEl = childElement(c, "is")
                val value = when {
                    type == "s" && vEl != null ->
                        shared.getOrElse(vEl.textContent.toIntOrNull() ?: -1) { "" }
                    type == "inlineStr" && isEl != null -> {
                        val ts = isEl.getElementsByTagName("t")
                        buildString { for (m in 0 until ts.length) append(ts.item(m).textContent) }
                    }
                    vEl != null -> vEl.textContent
                    else -> ""
                }
                grid[r to col] = value
                maxRow = maxOf(maxRow, r)
                maxCol = maxOf(maxCol, col)
            }
        }
        return (1..maxRow).map { r ->
            (1..maxCol).map { c -> grid[r to c] ?: "" }
        }
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val shared = mutableListOf<String>()
        val sis = doc.getElementsByTagName("si")
        for (k in 0 until sis.length) {
            val si = sis.item(k) as Element
            val ts = si.getElementsByTagName("t")
            val sb = StringBuilder()
            for (m in 0 until ts.length) sb.append(ts.item(m).textContent)
            shared.add(sb.toString())
        }
        return shared
    }

    /** 列引用 "A".."ZZ" -> 1.. */
    private fun colIndex(ref: String): Int {
        var col = 0
        for (ch in ref) {
            if (ch.isLetter()) col = col * 26 + (ch.uppercaseChar() - 'A' + 1) else break
        }
        return col
    }

    private fun childElement(parent: Element, name: String): Element? {
        val children = parent.getElementsByTagName(name)
        return if (children.length > 0) children.item(0) as Element else null
    }
}
