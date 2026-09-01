# -*- coding: utf-8 -*-
"""端到端演示：用样例题库跑通各类场景（选项打乱、多选、判断、模糊、未命中）。

运行：python3 -m prototype.demo
"""
import os

from .bank_parser import load_bank
from .matcher import answer, build_index

HERE = os.path.dirname(os.path.abspath(__file__))
SAMPLE = os.path.join(HERE, "..", "sample", "sample_bank.csv")


def main():
    bank = load_bank(SAMPLE)
    idx = build_index(bank)
    print(f"样例题库加载：{len(bank)} 题\n")

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
        print(f"{mark} [{name}] 模式={r['mode']} 状态={r['status']}")
        print(f"     期望: {expect} / 实际: {got}")
    print("\n全部场景通过 ✅" if all_ok else "\n存在失败用例 ❌")


if __name__ == "__main__":
    main()
