# -*- coding: utf-8 -*-
"""文本归一化：题干/选项匹配前的清洗。

规则：NFKC 统一兼容字符 → 全角标点转半角 → 去掉所有空白 → 去掉所有标点 → 小写。
匹配时只比较中文、字母、数字，忽略标点 / 空格 / 换行等差异。
"""
import re
import unicodedata

# 全角标点 → 半角（NFKC 不会转换中文全角标点，需手动映射）
_FULL_TO_HALF = {
    "，": ",", "。": ".", "、": ",", "；": ";", "：": ":",
    "？": "?", "！": "!", "（": "(", "）": ")", "【": "[", "】": "]",
    "“": '"', "”": '"', "‘": "'", "’": "'", "《": "<", "》": ">",
    "～": "~", "—": "-", "–": "-", "　": " ", "\u00a0": " ", "\u3000": " ",
}


def normalize(text):
    """返回用于匹配的归一化字符串（纯中文字母数字，小写，无空白无标点）。"""
    if text is None:
        return ""
    s = str(text)
    s = unicodedata.normalize("NFKC", s)
    for k, v in _FULL_TO_HALF.items():
        s = s.replace(k, v)
    s = re.sub(r"\s+", "", s)
    # 删除所有标点（保留字母、数字、汉字）
    s = "".join(ch for ch in s if not unicodedata.category(ch).startswith("P"))
    return s.lower()
