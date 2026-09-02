# -*- coding: utf-8 -*-
"""题库解析：CSV / Excel(.xlsx / .xls) / Word(.docx 表格)。

仅用 Python 标准库：
- .xlsx：zip 包内的 sharedStrings.xml + sheet1.xml
- .xls ：OLE2 + BIFF8 二进制（prototype/xls_reader.py）
- .docx：zip 包内的 word/document.xml 表格
- .csv ：标准 csv 模块（正确处理单元格内的引号、逗号、换行）

兼容各类真实题库：
- 首行有表头关键词（题目类型/题目标题/题目答案/选项A-D 等）→ 自动识别列；
- 首行无表头 → 自动按列数猜测常见布局，或由调用方手动指定列映射；
- 答案形如 "A|B|C|D|"（带 | 分隔）→ 归一化为 "ABCD"；
- 选项放在单列用 | 分隔（如 "A、正线 |B、停车场 |C、车辆段 |"）→ 自动拆成 A/B/C/D；
- 无题型列 → 根据选项/答案自动推断（判断/单选/多选/简答）。
"""
import csv
import io
import os
import re
import zipfile
from xml.etree import ElementTree as ET

from .xls_reader import parse_xls_bytes

NS_X = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
NS_W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"

TYPE_ALIASES = {
    "单选": "单选", "单选题": "单选", "单项选择": "单选", "单项": "单选",
    "多选": "多选", "多选题": "多选", "多项选择": "多选", "多项": "多选",
    "判断": "判断", "判断题": "判断", "对错": "判断",
    "简答": "简答", "简答题": "简答", "问答": "简答", "问答题": "简答", "主观题": "简答",
    "填空": "简答", "填空题": "简答",
}

_COL_ALIASES = {
    "序号": ("序号", "题号", "编号", "id", "ID"),
    "题型": ("题型", "类型", "题目类型"),
    "题目": ("题目", "题干", "问题", "试题", "题干内容", "题目标题"),
    "答案": ("答案", "正确答案", "参考答案", "标准答案", "题目答案"),
    "选项": ("选项",),  # v2.4：带表头文件若选项为单列且表头就叫"选项"（无 A–F 后缀）也自动识别
}

# 无表头时的常见布局猜测：列数 -> 角色顺序
_GUESS_LAYOUTS = {
    3: ["题目", "选项", "答案"],
    4: ["序号", "题目", "选项", "答案"],
    5: ["序号", "题型", "题目", "选项", "答案"],
    8: ["序号", "题型", "题目", "选项A", "选项B", "选项C", "选项D", "答案"],
}

_JUDGE_OPTION_WORDS = ("正确", "错误", "对", "错")


def normalize_answer(raw):
    """答案归一化：'A|B|C|D|' / 'A B C' / 'A | B' / 'A、B' → 'ABC'（纯字母 A–F，
    去空白/分隔/标点）；其它内容（数字填空 '50|100'、判断文字）→ 分隔符替换为 '、' 保可读。"""
    s = str(raw or "").strip()
    letters = re.sub(r"[\s|｜、,，;；.．:：/]+", "", s).upper()
    if letters and all(ch in "ABCDEF" for ch in letters):
        return letters
    return re.sub(r"[|｜,，;；/]+", "、", s).strip()


def split_options(raw):
    """把单列选项拆成 {'A': 文本, ...}（v2.4 增强）：
    1) 含 | 分隔：'A、正线 |B、停车场 |C、车辆段 |'（无字母前缀按出现顺序分配 A/B/C/D）；
    2) 无 | 的单格连写（'A 上行 B 下行 C 车辆段' / 'A.上行B.下行'，选项挤在一个单元格）
       → 按字母边界拆分；
    3) 无法按字母识别时整格作为唯一选项 A。"""
    s = str(raw or "").strip()
    if not s:
        return {}
    if "|" not in s and "｜" not in s:
        out = _split_letter_options(s)
        if out is not None:
            return out
    parts = re.split(r"[|｜]+", s)
    opts = {}
    next_letter = "A"
    for p in parts:
        p = p.strip()
        if not p:
            continue
        m = re.match(r"^([A-Fa-f])[\.、．:：\s]", p)
        if m:
            opts[m.group(1).upper()] = p[m.end():].strip()
        else:
            opts[next_letter] = p
            next_letter = chr(ord(next_letter) + 1)
    return opts


def _split_letter_options(s):
    """字母边界拆分（与 Kotlin BankParser.splitLetterOptions 语义一致），失败返回 None。
    候选标记 = A–F 后紧跟标点（.、．:：)））或前后成词边界；
    门槛：标记 ≥2、首标记为串首 A、字母严格递增、每段内容非空、标记数 ≤6。"""
    n = len(s)
    marks = []  # (字母下标, 大写字母)
    i = 0
    while i < n:
        c = s[i]
        if ("A" <= c <= "F") or ("a" <= c <= "f"):
            punct = i + 1 < n and s[i + 1] in ".、．:：)）"
            boundary = (i == 0 or s[i - 1].isspace()) and \
                (i + 1 == n or s[i + 1].isspace())
            if punct or boundary:
                marks.append((i, c.upper()))
                i += 2 if punct else 1
                continue
        i += 1
    if len(marks) < 2:
        return None
    if marks[0][0] != 0 or marks[0][1] != "A":
        return None
    for k in range(1, len(marks)):
        if marks[k][1] <= marks[k - 1][1]:
            return None
    if len(marks) > 6:
        return None
    out = {}
    for k, (idx, letter) in enumerate(marks):
        cs = idx + 1
        while cs < n and (s[cs].isspace() or s[cs] in ".、．:：)）"):
            cs += 1
        ce = marks[k + 1][0] if k + 1 < len(marks) else n
        content = s[cs:ce].strip()
        while content and content[-1] in "、,，;；":
            content = content[:-1].rstrip()
        if not content:
            return None
        out[letter] = content
    return out


def infer_qtype(qtype_raw, answer, options):
    """无题型列时，根据答案/选项推断题型。"""
    qtype = str(qtype_raw or "").strip()
    if qtype:
        return TYPE_ALIASES.get(qtype, qtype)
    if not options:
        a = normalize_answer(answer)
        if a in _JUDGE_OPTION_WORDS:
            return "判断"
        return "简答"
    if len(options) == 2 and all(normalize_answer(v) in _JUDGE_OPTION_WORDS
                                 for v in options.values()):
        return "判断"
    letters = [ch for ch in normalize_answer(answer).upper() if ch in "ABCDEF"]
    if len(letters) > 1:
        return "多选"
    return "单选"


class Question:
    """一道题。options 为 {'A': 文本, ...}（判断题通常为空）。"""

    def __init__(self, seq, qtype, stem, options, answer):
        self.seq = str(seq).strip()
        self.qtype = TYPE_ALIASES.get(str(qtype).strip(), str(qtype).strip())
        self.stem = str(stem).strip()
        self.options = {}
        if isinstance(options, dict):
            for k, v in options.items():
                if v is not None and str(v).strip():
                    self.options[k] = str(v).strip()
        self.answer = normalize_answer(answer)

    def __repr__(self):
        return f"<Q{self.seq} {self.qtype} 题干={self.stem[:20]}... 答案={self.answer}>"


# ---------- 底层解析 ----------

def parse_xlsx_bytes(data):
    """xlsx bytes -> list[list[str]]（含表头行）。"""
    zf = zipfile.ZipFile(io.BytesIO(data))
    shared = []
    if "xl/sharedStrings.xml" in zf.namelist():
        root = ET.fromstring(zf.read("xl/sharedStrings.xml"))
        for si in root.iter(f"{NS_X}si"):
            shared.append("".join(t.text or "" for t in si.iter(f"{NS_X}t")))
    sheet_names = [n for n in zf.namelist()
                   if n.startswith("xl/worksheets/") and n.endswith(".xml")]
    if not sheet_names:
        return []
    root = ET.fromstring(zf.read(sheet_names[0]))
    grid = {}
    for row in root.iter(f"{NS_X}row"):
        r = int(row.attrib.get("r", "1"))
        for c in row.iter(f"{NS_X}c"):
            ref = c.attrib.get("r", "")
            col = 0
            for ch in ref:
                if ch.isalpha():
                    col = col * 26 + (ord(ch.upper()) - 64)
                else:
                    break
            typ = c.attrib.get("t", "")
            v = c.find(f"{NS_X}v")
            is_el = c.find(f"{NS_X}is")
            if typ == "s" and v is not None:
                val = shared[int(v.text)] if v.text else ""
            elif typ == "inlineStr" and is_el is not None:
                val = "".join(t.text or "" for t in is_el.iter(f"{NS_X}t"))
            elif v is not None:
                val = v.text or ""
            else:
                val = ""
            grid[(r, col)] = val
    max_r = max((r for r, _ in grid), default=0)
    max_c = max((c for _, c in grid), default=0)
    return [[grid.get((r, c), "") for c in range(1, max_c + 1)]
            for r in range(1, max_r + 1)]


def parse_docx_tables(data):
    """docx bytes -> [list[list[str]]]（每个表格是一份二维数据，含表头行）。"""
    zf = zipfile.ZipFile(io.BytesIO(data))
    root = ET.fromstring(zf.read("word/document.xml"))
    tables = []
    for tbl in root.iter(f"{NS_W}tbl"):
        rows = []
        for tr in tbl.iter(f"{NS_W}tr"):
            cells = []
            for tc in tr.iter(f"{NS_W}tc"):
                paras = []
                for p in tc.iter(f"{NS_W}p"):
                    paras.append("".join(t.text or "" for t in p.iter(f"{NS_W}t")))
                cells.append("\n".join(paras).strip())
            rows.append(cells)
        tables.append(rows)
    return tables


# ---------- 二维表 -> 题目列表 ----------

def _find_header_row(rows):
    """找包含 题型/题目/答案 关键词的表头行；找不到返回 None。"""
    for i, row in enumerate(rows):
        joined = "".join(str(c) for c in row)
        if any(k in joined for k in ("题型", "题目", "答案")):
            return i
    return None


def rows_to_questions(rows, mapping=None):
    """二维表 -> list[Question]。

    mapping: 手动列映射（可选），如 {'题目': 2, '选项': 3, '答案': 4}（1 起始列号），
             或 {'题目': '题目标题', '答案': '题目答案'}（按表头列名）。
    无 mapping 时：先自动识别表头；失败则按列数猜测常见布局。
    """
    if not rows:
        return []

    # ---- 1) 确定列角色 -> 列下标(0 起始) ----
    role_to_idx = {}
    data_start = 0

    if mapping:
        header_idx = _find_header_row(rows)
        header_names = None
        if header_idx is not None:
            data_start = header_idx + 1
            header_names = [str(c).strip() for c in rows[header_idx]]
        for role, spec in mapping.items():
            if isinstance(spec, int):
                role_to_idx[role] = spec - 1
            elif header_names is not None and spec in header_names:
                role_to_idx[role] = header_names.index(spec)
        if not role_to_idx:
            raise RuntimeError(f"手动列映射未匹配到任何列：{mapping}")
    else:
        header_idx = _find_header_row(rows)
        if header_idx is not None:
            header_names = [str(c).strip() for c in rows[header_idx]]
            data_start = header_idx + 1
            for role, aliases in _COL_ALIASES.items():
                for i, name in enumerate(header_names):
                    if name in aliases:
                        role_to_idx[role] = i
                        break
            for L in "ABCDEF":
                for i, name in enumerate(header_names):
                    if name.replace(" ", "") == f"选项{L}":
                        role_to_idx[f"选项{L}"] = i
                        break
            if "题目" not in role_to_idx:
                raise RuntimeError("未在表头中找到'题目'列，请用 --columns 手动指定")
        else:
            # 无表头：按列数猜测常见布局（数据从第 1 行开始）
            ncols = max((len(r) for r in rows), default=0)
            layout = _GUESS_LAYOUTS.get(ncols)
            if layout is None:
                raise RuntimeError(
                    f"未识别表头且列数({ncols})不在常见布局中，请用 --columns 手动指定列映射，"
                    f"例如：--columns '题目:1,选项:2,答案:3'")
            for i, role in enumerate(layout):
                role_to_idx[role] = i
            data_start = 0

    def get(row, key):
        i = role_to_idx.get(key)
        if i is None or i >= len(row):
            return ""
        return row[i]

    # ---- 2) 逐行构造题目 ----
    out = []
    for row in rows[data_start:]:
        if not any(str(c).strip() for c in row):
            continue
        stem = str(get(row, "题目")).strip()
        if not stem:
            continue
        # 选项：优先 选项A-D 列；否则用单列"选项"
        options = {}
        letter_cols = {L: role_to_idx.get(f"选项{L}") for L in "ABCDEF"}
        if any(i is not None for i in letter_cols.values()):
            for L, i in letter_cols.items():
                if i is not None and i < len(row) and str(row[i]).strip():
                    options[L] = str(row[i]).strip()
        else:
            single = get(row, "选项")
            if single:
                options = split_options(single)
        qtype_raw = get(row, "题型")
        qtype = infer_qtype(qtype_raw, get(row, "答案"), options)
        out.append(Question(get(row, "序号"), qtype, stem, options, get(row, "答案")))
    return out


def load_bank(path, mapping=None):
    """加载题库文件（.csv / .xlsx / .xls / .docx）-> list[Question]

    mapping: 可选手动列映射，透传给 rows_to_questions。
    """
    ext = os.path.splitext(path)[1].lower()
    with open(path, "rb") as f:
        data = f.read()
    if ext == ".xlsx":
        return rows_to_questions(parse_xlsx_bytes(data), mapping)
    if ext == ".xls":
        out = []
        for sheet_rows in parse_xls_bytes(data):
            out.extend(rows_to_questions(sheet_rows, mapping))
        return out
    if ext == ".docx":
        out = []
        for tbl in parse_docx_tables(data):
            out.extend(rows_to_questions(tbl, mapping))
        return out
    if ext == ".csv":
        text = data.decode("utf-8-sig", errors="replace")
        return rows_to_questions(list(csv.reader(io.StringIO(text))), mapping)
    raise RuntimeError(f"不支持的题库格式：{ext}（支持 .csv/.xlsx/.xls/.docx）")
