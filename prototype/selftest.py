# -*- coding: utf-8 -*-
"""自测脚本：
1) 生成合成 .xlsx / .docx（表格题库）→ 验证解析器与 CSV 解析结果一致；
2) 用模拟的 window.xml 验证 dump 解析 → 题目提取 → 匹配 全链路；
3) 跑匹配场景（选项打乱 / 多选 / 判断 / 模糊 / 未命中）。

运行：python3 -m prototype.selftest
"""
import csv as _csv
import io
import os
import shutil
import tempfile
import zipfile
from xml.sax.saxutils import escape

from .bank_parser import (load_bank, parse_docx_tables, parse_xlsx_bytes,
                          rows_to_questions)
from .dump_reader import extract_text_nodes, find_quiz_blocks
from .matcher import answer, build_index, verify_ground_truth

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
SAMPLE_CSV = os.path.join(ROOT, "sample", "sample_bank.csv")
SAMPLE_DUMP = os.path.join(ROOT, "sample", "sample_window.xml")


# ---------- 合成文件生成器（仅用于自测） ----------

def col_letter(i):
    s = ""
    i += 1
    while i:
        i, r = divmod(i - 1, 26)
        s = chr(65 + r) + s
    return s


def write_xlsx_bytes(rows):
    shared, seen = [], {}

    def sid(v):
        if v not in seen:
            seen[v] = len(shared)
            shared.append(v)
        return seen[v]

    sheet_rows = []
    for r_i, row in enumerate(rows, start=1):
        cells = []
        for c_i, val in enumerate(row):
            ref = f"{col_letter(c_i)}{r_i}"
            if val is None or str(val).strip() == "":
                cells.append(f'<c r="{ref}"/>')
            else:
                cells.append(f'<c r="{ref}" t="s"><v>{sid(str(val))}</v></c>')
        sheet_rows.append(f'<row r="{r_i}">{"".join(cells)}</row>')

    sst_items = "".join(f'<si><t xml:space="preserve">{escape(s)}</t></si>' for s in shared)
    sst = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           f'<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
           f'count="{len(shared)}" uniqueCount="{len(shared)}">{sst_items}</sst>')
    sheet = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
             f'<sheetData>{"".join(sheet_rows)}</sheetData></worksheet>')
    workbook = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                '<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                '<sheets><sheet name="题库" sheetId="1" r:id="rId1"/></sheets></workbook>')
    content_types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                     '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                     '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                     '<Default Extension="xml" ContentType="application/xml"/>'
                     '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
                     '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
                     '<Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
                     '</Types>')
    root_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                 '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
                 '</Relationships>')
    wb_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
               '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>'
               '</Relationships>')

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", root_rels)
        z.writestr("xl/workbook.xml", workbook)
        z.writestr("xl/_rels/workbook.xml.rels", wb_rels)
        z.writestr("xl/sharedStrings.xml", sst)
        z.writestr("xl/worksheets/sheet1.xml", sheet)
    return buf.getvalue()


def write_docx_bytes(rows):
    def cell(t):
        return (f'<w:tc><w:p><w:r><w:t xml:space="preserve">{escape(str(t))}</w:t></w:r></w:p></w:tc>')

    trs = "".join(f"<w:tr>{''.join(cell(c) for c in row)}</w:tr>" for row in rows)
    body = f"<w:tbl><w:tblPr/>{trs}</w:tbl>"
    doc = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
           f'<w:body>{body}</w:body></w:document>')
    content_types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                     '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
                     '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
                     '<Default Extension="xml" ContentType="application/xml"/>'
                     '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
                     '</Types>')
    root_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
                 '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                 '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
                 '</Relationships>')

    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", content_types)
        z.writestr("_rels/.rels", root_rels)
        z.writestr("word/document.xml", doc)
    return buf.getvalue()


# ---------- 匹配场景 ----------

def run_match_cases():
    bank = load_bank(SAMPLE_CSV)
    idx = build_index(bank)
    cases = [
        ("单选-选项打乱(v1只显示答案内容)",
         "城市轨道交通的“运营”是指为实现安全有效运送乘客而有组织开展的活动总称,不包括以下哪项?",
         {"A": "房地产开发", "B": "行车组织", "C": "票务组织", "D": "客运组织"},
         "房地产开发", "ok"),
        ("单选-题库原序",
         "“三权移交”是指建设单位将调度指挥权、设施设备使用权和什么权交给运营单位的行为?",
         {"A": "经营管理权", "B": "属地管理权", "C": "票务收益权", "D": "广告经营权"},
         "属地管理权", "ok"),
        ("多选-选项打乱",
         "根据《城市轨道交通客运服务规范》,服务承诺内容包括?",
         {"A": "投诉处理", "B": "运营指标", "C": "改进措施", "D": "设施可靠"},
         "1.运营指标 2.设施可靠 3.投诉处理 4.改进措施", "ok"),
        ("判断-题库答案:正确",
         "运营单位应以安全、准时、便捷、舒适、文明为目标,为乘客提供持续改进的服务。",
         {"A": "错误", "B": "正确"},
         "正确", "ok"),
        ("单选-题干轻微改动(模糊匹配)",
         "三权移交是指建设单位将调度指挥权、设施设备使用权和哪种权交给运营单位的行为?",
         {"A": "经营管理权", "B": "属地管理权", "C": "票务收益权", "D": "广告经营权"},
         "属地管理权", "ok"),
        ("题库未命中",
         "地球上最高的山峰是什么?",
         {"A": "珠穆朗玛峰", "B": "乞力马扎罗", "C": "富士山", "D": "泰山"},
         None, "miss"),
    ]
    all_ok = True
    for name, stem, opts, expect, exp_status in cases:
        r = answer(bank, idx, stem, opts)
        if expect is not None:
            passed = (r["display"] == expect) and (r["status"] == exp_status)
            got = r["display"]
        else:
            passed = (r["status"] == exp_status)
            got = r["status"]
        all_ok = all_ok and passed
        mark = "✅" if passed else "❌"
        print(f"  {mark} [{name}] 模式={r['mode']} 状态={r['status']}")
        print(f"     期望: {expect} / 实际: {got}")
    return all_ok


# ---------- 主流程 ----------

def main():
    # 基准：CSV 样例
    with open(SAMPLE_CSV, "r", encoding="utf-8-sig") as f:
        rows = list(_csv.reader(f))
    print(f"CSV 基准：{len(rows) - 1} 条数据行")

    # 1) xlsx 解析一致性
    xlsx_bytes = write_xlsx_bytes(rows)
    rows_x = parse_xlsx_bytes(xlsx_bytes)
    assert rows_x == rows, f"xlsx 解析与 CSV 不一致:\n{rows_x}"
    print("✅ xlsx（合成）解析与 CSV 完全一致")

    # 2) docx 表格解析一致性
    docx_bytes = write_docx_bytes(rows)
    tables = parse_docx_tables(docx_bytes)
    assert len(tables) == 1 and tables[0] == rows, f"docx 解析与 CSV 不一致:\n{tables}"
    print("✅ docx（合成表格）解析与 CSV 完全一致")

    # 3) load_bank 三种格式加载同一批题
    tmp = tempfile.mkdtemp(dir=ROOT)
    try:
        p_x = os.path.join(tmp, "bank.xlsx")
        p_d = os.path.join(tmp, "bank.docx")
        with open(p_x, "wb") as f:
            f.write(xlsx_bytes)
        with open(p_d, "wb") as f:
            f.write(docx_bytes)
        n_csv = len(load_bank(SAMPLE_CSV))
        n_x = len(load_bank(p_x))
        n_d = len(load_bank(p_d))
        assert n_csv == n_x == n_d, (n_csv, n_x, n_d)
        print(f"✅ 三种格式加载题数一致：{n_csv} 题")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    # 4) 模拟 dump：解析 → 提取 → 匹配 全链路
    nodes = extract_text_nodes(SAMPLE_DUMP)
    blocks = find_quiz_blocks(nodes)
    assert len(blocks) == 1, blocks
    blk = blocks[0]
    assert "第10题" in blk["header"], blk
    assert "城市轨道交通" in blk["stem"], blk
    assert blk["options"] == {"A": "房地产开发", "B": "行车组织", "C": "票务组织", "D": "客运组织"}, blk
    bank = load_bank(SAMPLE_CSV)
    idx = build_index(bank)
    r = answer(bank, idx, blk["stem"], blk["options"])
    assert r["display"] == "房地产开发" and r["status"] == "ok", r
    print("✅ 模拟 dump 解析 → 题目提取 → 匹配答案 全链路通过")

    # 4b) 三份真实 dump（单选/判断/多选）：解析 → 提取 → v1 答案 → 与屏幕正确答案交叉校验
    real_cases = [
        # (文件名, 题号, 题干关键字, 提取出的选项, 屏幕正确答案, v1 期望显示)
        ("window.xml",  "第18题", "道岔",
         {"A": "顺向道岔", "B": "对向道岔", "C": "电动道岔", "D": "侧向道岔"},
         "B", "对向道岔"),
        ("window2.xml", "第26题", "登车作业",
         {"A": "正确", "B": "错误"},
         "B", "错误"),
        ("window3.xml", "第55题", "B1类施工",
         {"A": "洗车作业"},  # 多选题 B/C/D 字母未出现在控件树（v1 不需要，v2 再做）
         "ABCD", "1.洗车作业 2.列车连挂 3.司机救援动车演练 4.占用轨道送车辆"),
    ]
    for fname, hdr, stem_kw, opts, gt, expect in real_cases:
        dump_path = os.path.join(ROOT, "verify", "dumps", fname)
        nodes = extract_text_nodes(dump_path)
        blocks = find_quiz_blocks(nodes)
        assert len(blocks) == 1, f"{fname}: 应识别出 1 题，实际 {len(blocks)}：{blocks}"
        blk = blocks[0]
        assert hdr in blk["header"], blk
        assert stem_kw in blk["stem"], blk
        assert blk["options"] == opts, f"{fname} 选项: {blk}"
        assert blk["ground_truth"] == gt, blk
        r = answer(bank, idx, blk["stem"], blk["options"] or None)  # v1：只显示答案内容
        assert r["display"] == expect and r["status"] == "ok", (fname, r)
        ok_c, note = verify_ground_truth(r["question"], blk["options"], gt)
        assert ok_c is True, (fname, note)
        print(f"✅ 真实 {fname}（{blk['header']}）全链路通过：答案={r['display']}，"
              f"与屏幕正确答案 {gt} 一致")

    # 5) 匹配场景
    print("\n匹配场景：")
    ok = run_match_cases()
    print("\n全部通过 ✅" if ok else "\n存在失败用例 ❌")
    if not ok:
        raise SystemExit(1)

    # 6) 真实题库文件：无表头 + 选项|分隔 + A|B|C|D| 答案；老版 .xls + 新表头别名
    from .matcher import resolve_answer
    b1 = load_bank(os.path.join(ROOT, "题库1.xlsx"))
    assert len(b1) == 500, len(b1)
    t1 = {}
    for q in b1:
        t1[q.qtype] = t1.get(q.qtype, 0) + 1
    assert t1 == {"单选": 250, "多选": 100, "判断": 150}, t1
    multi1 = [q for q in b1 if q.qtype == "多选"][0]
    assert multi1.answer == "ABCD" and len(multi1.options) == 4, multi1
    judge1 = [q for q in b1 if q.qtype == "判断"][0]
    disp, st = resolve_answer(judge1)
    assert st == "ok" and disp == "正确", (judge1, disp, st)
    print("✅ 题库1.xlsx（无表头/选项单列|分隔/答案A|B|C|D|）解析正确：500 题，"
          "判断题字母答案正确翻译")

    b2 = load_bank(os.path.join(ROOT, "题库2.xls"))
    assert len(b2) == 1129, len(b2)
    t2 = {}
    for q in b2:
        t2[q.qtype] = t2.get(q.qtype, 0) + 1
    assert t2 == {"单选": 594, "多选": 142, "判断": 393}, t2
    print("✅ 题库2.xls（老版二进制格式/题目类型-题目标题-题目答案表头）解析正确：1129 题")

    # 手动列映射方式
    b1m = load_bank(os.path.join(ROOT, "题库1.xlsx"),
                    mapping={"题目": 2, "选项": 3, "答案": 4})
    assert len(b1m) == 500, len(b1m)
    print("✅ 手动列映射（--columns 题目:2,选项:3,答案:4）解析一致：500 题")

    # 7) v2.4：单格连写选项 + 单格空格分隔答案（多选）
    qs = rows_to_questions([
        ["题目", "选项", "答案"],
        ["连写题", "A 上行 B 下行 C 车辆段", "A B C"],
    ])
    assert len(qs) == 1, qs
    q = qs[0]
    assert q.qtype == "多选", q
    assert q.answer == "ABC", q.answer
    assert q.options == {"A": "上行", "B": "下行", "C": "车辆段"}, q.options
    print("✅ 单格选项（A 上行 B 下行 C 车辆段）拆分 + 空格答案（A B C）归一正确")


if __name__ == "__main__":
    main()
