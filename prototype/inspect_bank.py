# -*- coding: utf-8 -*-
"""检查真实题库文件的结构（表头、题型分布、答案格式样本）。"""
import csv
import io
import sys

from prototype.bank_parser import load_bank, parse_xlsx_bytes, rows_to_questions

def main(path):
    if path.endswith(".xlsx"):
        rows = parse_xlsx_bytes(open(path, "rb").read())
        print(f"=== {path} ===")
        print(f"总行数: {len(rows)}")
        if rows:
            print("表头行(第1行):", rows[0])
            if len(rows) > 1:
                print("第2行:", rows[1])
                print("第3行:", rows[2])
        bank = load_bank(path)
        types = {}
        ans_samples = set()
        for q in bank:
            types[q.qtype] = types.get(q.qtype, 0) + 1
            ans_samples.add(q.answer)
        print("题型分布:", types)
        print(f"答案样本({len(ans_samples)}种):", sorted(ans_samples)[:20])
        # 检查答案里带 | 的
        pipe = [q.answer for q in bank if "|" in q.answer]
        print(f"答案含'|'的题数: {len(pipe)}", "示例:", pipe[:5])
    else:
        print(f"{path}: 非 xlsx，跳过（等 .xls 解析器实现）")

if __name__ == "__main__":
    main(sys.argv[1])
