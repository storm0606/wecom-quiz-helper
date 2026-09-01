# -*- coding: utf-8 -*-
"""命令行入口（原型）。

用法：
  python3 -m prototype.cli <题库文件> --stem "题干" [--opt "A:文本"] [--opt "B:文本"] ...
  python3 -m prototype.cli <题库文件> --dump window.xml
  python3 -m prototype.cli <题库文件>                # 交互模式

默认 v1 模式：只显示正确答案内容（不做屏幕 ABCD 定位）；
加 --match-options 进入 v2 模式：按屏幕选项内容定位实际字母（选项打乱也准）。

题库文件支持 .csv / .xlsx / .docx。
"""
import argparse
import sys

from .bank_parser import load_bank
from .dump_reader import extract_text_nodes, find_quiz_blocks
from .matcher import answer, build_index, verify_ground_truth


def parse_opts(pairs):
    out = {}
    for p in pairs:
        if ":" in p:
            k, v = p.split(":", 1)
            out[k.strip().upper()] = v.strip()
        else:
            letters = [L for L in "ABCD" if L not in out]
            if letters:
                out[letters[0]] = p.strip()
    return out


def parse_columns(spec):
    """解析 --columns '题目:1,选项:3,答案:4' -> {'题目': 1, '选项': 3, '答案': 4}"""
    out = {}
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if ":" in part:
            role, val = part.split(":", 1)
            role = role.strip()
            val = val.strip()
            if val.isdigit():
                out[role] = int(val)
            else:
                out[role] = val  # 按表头列名
        else:
            raise ValueError(f"--columns 格式应为 '角色:列号'，如 '题目:1,选项:2,答案:3'，收到：{part}")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description="企业微信答题助手 - 匹配原型")
    ap.add_argument("bank", help="题库文件（.csv/.xlsx/.docx）")
    ap.add_argument("--stem", help="屏幕读取到的题干")
    ap.add_argument("--opt", action="append", default=[], help="屏幕选项，如 --opt 'A:房地产开发'，可多次")
    ap.add_argument("--dump", help="uiautomator dump 的 window.xml 路径")
    ap.add_argument("--columns", help="手动列映射，如 '题目:1,选项:3,答案:4'（1 起始列号，"
                                     "或用表头列名如 '题目:题目标题'），逗号分隔多个")
    ap.add_argument("--match-options", action="store_true",
                    help="v2 模式：按屏幕选项内容定位实际字母（默认 v1 只显示答案内容）")
    args = ap.parse_args(argv)

    mapping = parse_columns(args.columns) if args.columns else None
    bank = load_bank(args.bank, mapping=mapping)
    idx = build_index(bank)
    print(f"题库加载完成：{len(bank)} 题（{args.bank}）\n")

    if args.dump:
        nodes = extract_text_nodes(args.dump)
        print(f"页面共 {len(nodes)} 个文本节点：")
        for nd in nodes:
            print("  -", nd["text"])
        print()
        blocks = find_quiz_blocks(nodes)
        if not blocks:
            print("⚠️  未识别到题目块（没找到形如 '第N题' 的节点），请把上面的完整输出发给我调规则。")
            return
        for blk in blocks:
            print(f"【识别到题目】{blk['header']}")
            print(f"  题干: {blk['stem']}")
            print(f"  选项: {blk['options']}")
            r = answer(bank, idx, blk["stem"], blk["options"] or None,
                       use_screen_position=args.match_options)
            print(f"  → 答案: {r['display']}  [{r['status']}]")
            if r.get("bank_answer"):
                print(f"  题库答案: {r['bank_answer']}")
            gt = blk.get("ground_truth")
            if gt and r.get("question"):
                ok_c, note = verify_ground_truth(r["question"], blk["options"], gt)
                if ok_c is None:
                    mark = "请人工核对 ⚠️"
                elif ok_c:
                    mark = "一致 ✅"
                else:
                    mark = "不一致 ❌"
                print(f"  屏幕显示正确答案: {gt}（{mark}：{note}）")
        return

    if args.stem:
        opts = parse_opts(args.opt)
        r = answer(bank, idx, args.stem, opts or None,
                   use_screen_position=args.match_options)
        print(f"匹配模式: {r['mode']}")
        print(f"答案: {r['display']}  [{r['status']}]")
        if r.get("question"):
            q = r["question"]
            print(f"命中题库 {q.seq} 号（{q.qtype}），题库答案: {q.answer}")
        return

    # 交互模式
    print("交互模式：逐题输入（空题干退出）")
    while True:
        stem = input("题干: ").strip()
        if not stem:
            break
        opts = {}
        print("选项（格式 字母:文本，直接回车结束）：")
        while True:
            line = input("  ").strip()
            if not line:
                break
            if ":" in line:
                k, v = line.split(":", 1)
                opts[k.strip().upper()] = v.strip()
            else:
                letters = [L for L in "ABCD" if L not in opts]
                if letters:
                    opts[letters[0]] = line
        r = answer(bank, idx, stem, opts or None,
                   use_screen_position=args.match_options)
        print(f"  → 答案: {r['display']}  [{r['status']}]\n")


if __name__ == "__main__":
    main(sys.argv[1:])
