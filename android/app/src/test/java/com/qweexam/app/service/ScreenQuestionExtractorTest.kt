package com.qweexam.app.service

import android.graphics.Rect
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 屏幕题目提取器单元测试：用三份真实企业微信控件树 dump 验证
 * （与原型 dump_reader 结果一致）。
 */
class ScreenQuestionExtractorTest {

    private fun parseDump(path: String): List<ScreenNode> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(path))
        val nodes = doc.getElementsByTagName("node")
        val out = mutableListOf<ScreenNode>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            val text = el.getAttribute("text").trim()
            if (text.isEmpty()) continue
            out.add(ScreenNode(text, parseBounds(el.getAttribute("bounds"))))
        }
        return out
    }

    private fun parseBounds(s: String): Rect? {
        val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(s) ?: return null
        val v = m.groupValues.drop(1).map { it.toInt() }
        return Rect(v[0], v[1], v[2], v[3])
    }

    @Test
    fun windowXml_singleChoice() {
        val blocks = ScreenQuestionExtractor.findQuizBlocks(
            parseDump("../../verify/dumps/window.xml")
        )
        assertEquals(1, blocks.size)
        val b = blocks[0]
        assertTrue(b.header.contains("第18题"))
        assertTrue(b.stem.contains("道岔"))
        assertEquals(
            mapOf("A" to "顺向道岔", "B" to "对向道岔", "C" to "电动道岔", "D" to "侧向道岔"),
            b.options
        )
        assertEquals("B", b.groundTruth)
    }

    @Test
    fun window2Xml_judge_splitHeader() {
        val blocks = ScreenQuestionExtractor.findQuizBlocks(
            parseDump("../../verify/dumps/window2.xml")
        )
        assertEquals(1, blocks.size)
        val b = blocks[0]
        assertTrue(b.header.contains("第26题"))
        assertTrue(b.stem.contains("登车作业"))
        assertEquals(mapOf("A" to "正确", "B" to "错误"), b.options)
        assertEquals("B", b.groundTruth)
    }

    @Test
    fun window3Xml_multiChoice_missingLetters() {
        val blocks = ScreenQuestionExtractor.findQuizBlocks(
            parseDump("../../verify/dumps/window3.xml")
        )
        assertEquals(1, blocks.size)
        val b = blocks[0]
        assertTrue(b.header.contains("第55题"))
        assertTrue(b.stem.contains("B1类施工"))
        // 多选题 B/C/D 字母未出现在控件树，只有 A 配对成功
        assertEquals(mapOf("A" to "洗车作业"), b.options)
        assertEquals("ABCD", b.groundTruth)
    }
}
