package com.qweexam.app.data.parser

/**
 * 文本归一化：题干/选项匹配前的清洗。
 * 与原型 prototype/normalize.py 逻辑一致：
 * NFKC 统一兼容字符 → 全角标点转半角 → 去掉所有空白 → 去掉所有标点 → 小写。
 */
object Normalizer {

    private val FULL_TO_HALF = mapOf(
        '，' to ',', '。' to '.', '、' to ',', '；' to ';', '：' to ':',
        '？' to '?', '！' to '!', '（' to '(', '）' to ')', '【' to '[', '】' to ']',
        '“' to '"', '”' to '"', '‘' to '\'', '’' to '\'', '《' to '<', '》' to '>',
        '～' to '~', '—' to '-', '–' to '-', '　' to ' ', '\u00a0' to ' ', '\u3000' to ' '
    )

    /** 该 Unicode 类型是否为标点（对应 Python unicodedata.category 以 P 开头） */
    private fun isPunctuation(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.CONNECTOR_PUNCTUATION.toInt() ||
            type == Character.DASH_PUNCTUATION.toInt() ||
            type == Character.END_PUNCTUATION.toInt() ||
            type == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.INITIAL_QUOTE_PUNCTUATION.toInt() ||
            type == Character.OTHER_PUNCTUATION.toInt() ||
            type == Character.START_PUNCTUATION.toInt()
    }

    /** 返回用于匹配的归一化字符串（纯中文字母数字，小写，无空白无标点）。 */
    fun normalize(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        for ((k, v) in FULL_TO_HALF) s = s.replace(k, v)
        s = s.replace(Regex("\\s+"), "")
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (!isPunctuation(ch.code)) sb.append(ch)
        }
        return sb.toString().lowercase()
    }
}
