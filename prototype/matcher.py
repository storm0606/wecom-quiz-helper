# -*- coding: utf-8 -*-
"""题干匹配 + 屏幕选项定位（兼容选项随机打乱）。

流程：
1) 题干归一化后查题库（精确索引优先，子串包含 / 模糊兜底）；
2) 单选/多选：题库答案字母 → 题库该选项的文字 → 在屏幕选项里按"内容"定位实际字母；
   判断题：题库答案"正确/错误"文字 → 屏幕定位对应字母；
   简答题：直接显示题库答案文字。

最终显示的字母一定是当前屏幕上选项的实际位置 —— 选项被打乱也不会错。
"""
import difflib
import re

from .normalize import normalize

_OPT_PREFIX_RE = re.compile(r"^[A-Da-d][\.、．:：\s]*")

_JUDGE_TRUE = ("正确", "对", "是", "√", "T", "TRUE")
_JUDGE_FALSE = ("错误", "错", "否", "×", "X", "F", "FALSE")


def clean_option_text(raw):
    """去掉选项文字开头的字母编号（如 'A.'、'B、'、'C '），其余原样返回。"""
    s = str(raw or "").strip()
    m = _OPT_PREFIX_RE.match(s)
    if m and len(s) > len(m.group(0)):
        return s[m.end():].strip()
    return s


def _norm_opt(raw):
    return normalize(clean_option_text(raw))


def build_index(bank):
    """bank: list[Question] -> {归一化题干: [Question, ...]}"""
    idx = {}
    for q in bank:
        idx.setdefault(normalize(q.stem), []).append(q)
    return idx


def find_candidates(bank, idx, stem_text, ratio_threshold=0.78):
    """返回 (候选题目列表, 匹配模式: exact / contain / fuzzy / none)。"""
    ns = normalize(stem_text)
    if not ns:
        return [], "none"
    # 1) 精确匹配
    if ns in idx:
        return idx[ns], "exact"
    # 2) 子串包含：屏幕读到的可能是一个大文本块（题干+选项混在一起），
    #    此时归一化的题库题干应整段包含在屏幕文本里
    contains = []
    for q in bank:
        nq = normalize(q.stem)
        if nq and len(nq) >= 6 and nq in ns:
            contains.append((len(nq), q))
    if contains:
        contains.sort(key=lambda x: -x[0])
        return [q for _, q in contains], "contain"
    # 3) 模糊匹配（编辑距离相似度）
    best = []
    for q in bank:
        nq = normalize(q.stem)
        if not nq:
            continue
        # 长度粗筛，避免对明显不同的题做昂贵比较
        if not (0.6 * len(nq) <= len(ns) <= 1.8 * len(nq)):
            continue
        r = difflib.SequenceMatcher(None, ns, nq).ratio()
        if r >= ratio_threshold:
            best.append((r, q))
    best.sort(key=lambda x: -x[0])
    if not best:
        return [], "none"
    return [q for _, q in best], "fuzzy"


def find_screen_option(screen_options, want_raw):
    """在屏幕选项 {'A': text,...} 中按内容查找，返回实际字母；找不到返回 None。"""
    if not screen_options or not want_raw:
        return None
    wn = _norm_opt(want_raw)
    if not wn:
        return None
    for L in sorted(screen_options):
        if _norm_opt(screen_options[L]) == wn:
            return L
    # 兜底：包含关系（屏幕/题库文字多几个字或少几个字）
    for L in sorted(screen_options):
        tn = _norm_opt(screen_options[L])
        if tn and min(len(wn), len(tn)) >= 3 and (wn in tn or tn in wn):
            return L
    return None


def _judge_text(ans):
    a = (ans or "").strip().upper()
    if a in _JUDGE_TRUE:
        return "正确"
    if a in _JUDGE_FALSE:
        return "错误"
    return (ans or "").strip()


def _judge_answer_text(q):
    """判断题答案归一化：'正确'/'错误' 文字，或字母（如 A）→ 通过题库选项翻译成文字。"""
    ans_text = _judge_text(q.answer)
    if ans_text in "ABCD" and q.options:
        opt_text = q.options.get(ans_text)
        if opt_text:
            ans_text = _judge_text(opt_text)
    return ans_text


def resolve_answer(bank_q, screen_options=None, use_screen_position=False):
    """根据题库生成答案显示。

    v1（默认 use_screen_position=False）：只显示正确答案内容，不做屏幕 ABCD 定位
      - 单选：显示题库答案选项的文字（如"对向道岔"）
      - 多选：显示题库答案字母组合（如"ABCD"，字母集合不受选项打乱影响）
      - 判断：显示"正确"/"错误"
      - 简答：显示题库答案文字
    v2（use_screen_position=True）：按屏幕选项内容定位实际字母（选项打乱也准）
    返回 (显示文本, 状态)；状态: ok / warn。
    """
    qtype = bank_q.qtype
    if qtype == "简答":
        return (bank_q.answer or "（题库无答案）"), "ok"

    if qtype == "判断":
        ans_text = _judge_answer_text(bank_q)
        if use_screen_position and screen_options:
            L = find_screen_option(screen_options, ans_text)
            if L:
                return f"{L}.{screen_options[L].strip()}", "ok"
        return ans_text, "ok"

    # 单选 / 多选
    letters = [ch for ch in (bank_q.answer or "").upper() if ch in "ABCD"]
    if not letters:
        return (bank_q.answer or "（题库答案为空）"), "warn"

    if not use_screen_position:
        # v1：直接给答案内容
        if qtype == "单选":
            txt = bank_q.options.get(letters[0], "")
            if txt:
                return txt, "ok"
            return letters[0], "warn"
        # 多选：显示答案选项的文字（自动编号 1. 2. …，选项本身带数字则不再加）
        texts = [(bank_q.options.get(L) or "").strip() for L in letters]
        texts = [t for t in texts if t]
        if texts:
            shown = []
            for t in texts:
                if re.match(r"^\d", t):
                    shown.append(t)
                else:
                    shown.append(f"{len(shown) + 1}.{t}")
            return " ".join(shown), "ok"
        return "".join(letters), "warn"

    # v2：按屏幕选项内容定位实际字母
    located, missing = [], []
    for L in letters:
        want = bank_q.options.get(L)
        hit = find_screen_option(screen_options, want) if (screen_options and want) else None
        if hit:
            located.append(hit)
        else:
            missing.append(L)
    if located and not missing:
        if qtype == "单选":
            return " ".join(f"{L}.{screen_options[L].strip()}" for L in sorted(located)), "ok"
        return "".join(sorted(located)), "ok"
    if missing and located:
        return (f"题库答案 {''.join(letters)}（已定位 {''.join(sorted(located))}，"
                f"其余选项在屏幕上找不到，可能被改写）请人工核对"), "warn"
    return f"题库答案 {''.join(letters)}（屏幕选项未读到/未匹配）请人工核对", "warn"


def verify_ground_truth(q, screen_options, gt):
    """用屏幕显示的正确答案（字母串，如 'B' / 'ABCD'）校验题库答案是否一致。
    返回 (bool | None, 说明)；None 表示无法自动对照。"""
    if not gt:
        return None, ""
    gt = gt.upper()
    if q.qtype in ("单选", "多选"):
        letters = "".join(ch for ch in (q.answer or "").upper() if ch in "ABCD")
        if letters:
            return letters == gt, f"题库答案 {letters} vs 屏幕正确答案 {gt}"
        return None, f"题库答案 '{q.answer}' 无法对照"
    if q.qtype == "判断":
        ans_text = _judge_answer_text(q)
        for L in sorted(screen_options or {}):
            if _norm_opt(screen_options[L]) == normalize(ans_text):
                return L == gt, f"屏幕选项 {L} 内容与题库答案 '{ans_text}' 一致 vs 屏幕正确答案 {gt}"
        return None, f"题库答案 '{ans_text}' 无法映射到屏幕字母"
    return None, ""


def answer(bank, idx, stem_text, screen_options=None, use_screen_position=False):
    """对外主入口。
    返回 dict: {hit, mode, question?, display, bank_answer, status}
    status: ok / warn / miss / ambiguous
    """
    cands, mode = find_candidates(bank, idx, stem_text)
    if not cands:
        return {"hit": False, "mode": mode,
                "display": "未命中题库（可尝试补题库或 OCR 兜底）", "status": "miss"}
    if len({q.answer for q in cands}) > 1:
        detail = "；".join(f"{q.seq}号 → {q.answer}" for q in cands[:5])
        return {"hit": True, "mode": mode, "question": cands[0],
                "display": f"有歧义，请人工判断：{detail}", "status": "ambiguous"}
    q = cands[0]
    display, status = resolve_answer(q, screen_options, use_screen_position)
    return {"hit": True, "mode": mode, "question": q,
            "display": display, "bank_answer": q.answer, "status": status}
