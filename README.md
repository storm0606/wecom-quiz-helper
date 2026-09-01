# 企微考试（企业微信答题助手）

目标：在企业微信答题时，悬浮窗**实时显示正确答案**（人工点选作答）。
方案：无障碍读屏（+ OCR 兜底）→ 题干匹配题库 → 悬浮窗显示答案。
本仓库先实现**电脑端匹配原型**：验证「题库解析 + 题干匹配 + 选项打乱定位」逻辑，
之后再移植为 **Kotlin Android 应用**（应用名：企微考试，首发版本 v1.0）。

## 文档

- 📄 [开发文档](docs/开发文档.md) —— 需求定义、系统架构、关键设计、验证结论、版本记录
- 📄 [开发计划](docs/开发计划.md) —— 版本路线图、里程碑任务分解、验收标准、测试计划

## 目录结构

```
wecom-quiz-helper/
├── docs/                  # 开发文档 + 开发计划 + AI 上下文摘要
├── prototype/             # Python3 匹配原型（纯标准库，零依赖）
│   ├── bank_parser.py     # 题库解析：CSV / xlsx / xls / docx（表格）+ 手动列映射
│   ├── xls_reader.py      # .xls 老格式（OLE2 + BIFF8）解析器
│   ├── normalize.py       # 文本归一化
│   ├── matcher.py         # 题干匹配 + 答案生成（v1 内容 / v2 ABCD）
│   ├── dump_reader.py     # 解析 uiautomator dump（window.xml）
│   ├── cli.py             # 命令行入口（--dump / --stem / --columns）
│   ├── demo.py            # 演示脚本
│   ├── inspect_bank.py    # 题库结构检查工具
│   └── selftest.py        # 自测（真实 dump + 真实题库回归）
├── android/               # Android 工程（Kotlin，应用名：企微考试）
│   ├── app/               # 应用模块（M1 骨架：MainActivity + 权限声明）
│   ├── gradle/            # Gradle Wrapper（8.9）+ 版本目录
│   └── ...
├── sample/                # 样例题库 + 模拟控件树
├── verify/                # 真机验证说明 + 真实 dump 归档（verify/dumps/）
├── 题库1.xlsx             # 真实题库：无表头 4 列、选项|分隔、答案 A|B|C|D|
└── 题库2.xls              # 真实题库：老版格式、题目类型/题目标题/题目答案 表头
```

## 快速开始（Mac / Linux 自带 Python3）

```bash
cd wecom-quiz-helper
python3 -m prototype.selftest   # 自测：解析 + 匹配 全部场景
python3 -m prototype.demo       # 演示：选项打乱也能答对
python3 -m prototype.cli sample/sample_bank.csv   # 交互试玩
```

## 关键设计（已确认的需求）

- 企业微信试题**随机出题且选项随机打乱**，匹配必须按**题干文字**（归一化后查库）。
- 题型：单选 / 多选 / 判断 / 简答；判断题题库答案存"正确/错误"文字。
- **v1（当前原型，Android 首发版）**：只显示正确答案内容——
  单选显示答案选项文字（如 `对向道岔`）、多选显示 `ABCD`（字母集合不受打乱影响）、
  判断显示 `正确/错误`、简答直接显示答案文字。**不匹配屏幕 ABCD 位置**。
- **v2（后续开发）**：按屏幕选项内容定位实际字母（`--match-options` 开关，选项打乱也准）。
- 题干归一化：去空格/换行/引号/标点，全半角统一 —— 题库和屏幕文本格式不同也能命中。
- 三份真实 dump（单选/判断/多选）已归档到 `verify/dumps/`，全部通过全链路测试，
  v1 答案与屏幕"正确答案"交叉校验一致。

## 下一步

1. 把真实 Excel / Word 题库文件发我 → 验证解析器兼容性（列名、跨行单元格等）；
2. 移植为 Kotlin Android 应用（无障碍服务读题 + 悬浮窗显示答案 + SQLite 题库），
   先做 v1（只显示答案内容），稳定后再加 v2 ABCD 定位。
