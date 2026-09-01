package com.qweexam.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 轮换色板解析测试（v1.6） */
class ColorPaletteTest {

    @Test
    fun parse_normalList() {
        val colors = AnswerRepository.parseColorList("#E61A73E8,#E6174EA6,#E60FA958")
        assertEquals(3, colors.size)
        assertEquals(AnswerRepository.parseColorInt("#E61A73E8"), colors[0])
        assertEquals(AnswerRepository.parseColorInt("#E60FA958"), colors[2])
    }

    @Test
    fun parse_ignoreEmptyAndInvalid() {
        val colors = AnswerRepository.parseColorList("  , #E61A73E8 ,, 非法颜色 , #E60FA958")
        assertEquals(2, colors.size)
    }

    @Test
    fun parse_empty_fallsBackToDefaultPalette() {
        val colors = AnswerRepository.parseColorList(null)
        assertTrue(colors.size >= 2)  // 默认色板至少 2 个，保证轮换可用
        assertEquals(AnswerRepository.parseColorInt("#E61A73E8"), colors[0])
    }

    @Test
    fun parse_allInvalid_fallsBackToDefaultPalette() {
        val colors = AnswerRepository.parseColorList("x,y,z")
        assertTrue(colors.size >= 2)
    }

    @Test
    fun parseColorInt_forms() {
        // #RRGGBB 自动补全 alpha
        assertEquals(0xFF1A73E8.toInt(), AnswerRepository.parseColorInt("#1A73E8"))
        assertEquals(0xE61A73E8.toInt(), AnswerRepository.parseColorInt("#E61A73E8"))
        assertNull(AnswerRepository.parseColorInt("notacolor"))
        assertNull(AnswerRepository.parseColorInt("#12345"))
    }
}
