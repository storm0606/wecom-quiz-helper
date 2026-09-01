# -*- coding: utf-8 -*-
"""解析 uiautomator dump 的 window.xml（adb shell uiautomator dump）。

真实结构（企业微信 com.tencent.wework，已用真机数据验证）：
- 选项是"字母节点 + 文字节点"分离的（"A" 和 "顺向道岔" 是两个 TextView，同一行，
  用 y 坐标中心对齐配对）；
- 题号行"第N题(x.xx 分)"会出现两次（顶部悬浮题号 + 正文题号），需去重；
- 已答完的题目会显示"回答错误/回答正确"和"正确答案：X"（正式作答时不存在，
  仅用于与题库匹配结果交叉校验）；
- 页面上方还有考试标题 / 及格线 / 进度数字，都在题号行之前，直接忽略。
"""
import re
from xml.etree import ElementTree as ET

from .matcher import clean_option_text

_HEADER_RE = re.compile(r"第\s*\d+\s*题")
_TYPE_LABELS = ("单选", "多选", "判断", "判断题", "单选题", "多选题")
_ANSWER_RE = re.compile(r"正确答案\s*[:：]?\s*([ABCD]+)")
_BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")

# 选项字母与文字节点的 y 中心允许误差（px）
_ROW_TOLERANCE = 40


def _parse_bounds(bounds_str):
    m = _BOUNDS_RE.search(bounds_str or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return {"cy": (y1 + y2) / 2}


def _is_skip(text):
    """该文本节点是否应跳过（非题干、非选项内容）。"""
    if not text:
        return True
    if text in _TYPE_LABELS:
        return True
    if re.fullmatch(r"\d+", text) or re.fullmatch(r"/\d+", text) \
            or re.fullmatch(r"\d+(\.\d+)?%", text):
        return True
    # 拆分式题号行的分值节点，如 "(1.67 分)" / "(2.00 分)"
    if re.fullmatch(r"\(\d+(\.\d+)?\s*分\)", text):
        return True
    if "回答正确" in text or "回答错误" in text or "正确答案" in text or "及格线" in text:
        return True
    return False


def extract_text_nodes(xml_path):
    """返回 [{text, class, rid, bounds}, ...]，按文档顺序。"""
    tree = ET.parse(xml_path)
    out = []
    for el in tree.iter():
        tag = el.tag.rsplit("}", 1)[-1]
        if tag != "node":
            continue
        t = (el.attrib.get("text") or "").strip()
        if not t:
            continue
        out.append({
            "text": t,
            "class": el.attrib.get("class", ""),
            "rid": el.attrib.get("resource-id", ""),
            "bounds": el.attrib.get("bounds", ""),
        })
    return out


def _find_option_text(nodes, j):
    """字母节点（如 'A'）之后，找同一行的文字节点作为选项内容。
    返回 (字母, 选项文字, 下一个待处理下标)；找不到返回 None。"""
    letter = nodes[j]["text"]
    base = _parse_bounds(nodes[j].get("bounds"))
    k = j + 1
    while k < len(nodes):
        tk = nodes[k]["text"]
        if _is_skip(tk) or re.fullmatch(r"[A-Da-d]", tk):
            k += 1
            continue
        bk = _parse_bounds(nodes[k].get("bounds"))
        if base and bk and abs(base["cy"] - bk["cy"]) > _ROW_TOLERANCE:
            return None  # 同一行没有文字节点（异常布局），放弃配对
        return letter, tk, k + 1
    return None


def find_quiz_blocks(nodes):
    """启发式提取题目块：返回 [{header, stem, options, ground_truth}]。"""
    blocks = []
    i = 0
    n = len(nodes)
    while i < n:
        t = nodes[i]["text"]
        if _HEADER_RE.search(t):
            j = i + 1
            stem_parts = []
            options = {}
            ground_truth = None
            while j < n:
                tj = nodes[j]["text"]
                if _HEADER_RE.search(tj):
                    # 重复的题号行（顶部悬浮题号）：中间无实质内容则直接跳过
                    if tj == t and not stem_parts and not options and not ground_truth:
                        j += 1
                        continue
                    break
                if "上一题" in tj or "下一题" in tj:
                    break
                m = _ANSWER_RE.search(tj)
                if m:
                    ground_truth = m.group(1)
                    j += 1
                    continue
                if _is_skip(tj):
                    j += 1
                    continue
                # 选项：独立的字母节点 + 同一行文字节点
                if re.fullmatch(r"[A-Da-d]", tj):
                    pair = _find_option_text(nodes, j)
                    if pair:
                        options[pair[0].upper()] = pair[1]
                        j = pair[2]
                        continue
                    j += 1
                    continue
                # 选项：字母+文字合并形式（A.xxx / B、xxx），兼容其他版本
                mo = re.match(r"^([A-Da-d])[\.、．:：\s]", tj)
                if mo:
                    options[mo.group(1).upper()] = clean_option_text(tj)
                    j += 1
                    continue
                # 其余文本拼成题干
                stem_parts.append(tj)
                j += 1
            blocks.append({
                "header": t,
                "stem": "".join(stem_parts),
                "options": options,
                "ground_truth": ground_truth,
            })
            i = j
        else:
            i += 1
    return blocks
