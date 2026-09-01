package com.qweexam.app.data.parser

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** .docx 表格解析（word/document.xml 中的 w:tbl），返回 [表][行][列] */
object DocxParser {

    fun parseTables(data: ByteArray): List<List<List<String>>> {
        var documentXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "word/document.xml") {
                    documentXml = zip.readBytes()
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val bytes = documentXml ?: return emptyList()

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val tbls = doc.getElementsByTagName("w:tbl")
        val tables = mutableListOf<List<List<String>>>()
        for (i in 0 until tbls.length) {
            val tbl = tbls.item(i) as Element
            val trs = tbl.getElementsByTagName("w:tr")
            val rows = mutableListOf<List<String>>()
            for (j in 0 until trs.length) {
                val tr = trs.item(j) as Element
                val tcs = tr.getElementsByTagName("w:tc")
                val cells = mutableListOf<String>()
                for (k in 0 until tcs.length) {
                    val tc = tcs.item(k) as Element
                    val ps = tc.getElementsByTagName("w:p")
                    val lines = mutableListOf<String>()
                    for (m in 0 until ps.length) {
                        val p = ps.item(m) as Element
                        val ts = p.getElementsByTagName("w:t")
                        lines.add(buildString {
                            for (n in 0 until ts.length) append(ts.item(n).textContent)
                        })
                    }
                    cells.add(lines.joinToString("\n").trim())
                }
                rows.add(cells)
            }
            tables.add(rows)
        }
        return tables
    }
}
