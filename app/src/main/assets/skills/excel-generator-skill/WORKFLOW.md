# Excel Generator Skill - 工作流程

## 概述

技能只做一件事：**把 JSON 数组变成带样式的 .xlsx**。数据准备（抓取、清洗、汇总）
由调用方（LLM 用 `run_bash` / `run_code` 或其它工具）完成；本技能不解析 Markdown、
不清洗数据、不做多表分页——早期文档把这些设想当成了既有能力，容易误导。

## 实际流程

```
data_json（JSON 数组字符串）
        │
        ▼
python3 scripts/excel_generator.py <data_json> <output_path> [title]
        │
        ├─ 1. 取 data[0] 的键作为表头（键顺序 = 列顺序）
        ├─ 2. 写表头：微软雅黑加粗白字 / 深蓝底 / 居中 / 中粗外框
        ├─ 3. 逐列 detect_type 推断类型（取该列出现最多的非空类型）
        ├─ 4. 写数据行：format_value 按类型格式化 + 隔行底色 + 细边框
        ├─ 5. 按内容长度推算列宽、冻结首行
        └─ 6. 数值列追加柱状图；≥2 个数值列再追加折线对比图
        │
        ▼
output_path（.xlsx）+ stdout 报告（行数 / 列数 / 文件大小）
```

## 输入输出契约

| 项 | 约定 |
|---|---|
| `data_json` | JSON **数组**；每项是对象；第一项的键顺序即表头顺序 |
| `output_path` | `.xlsx` 绝对路径（PRoot 内路径；父目录必须已存在） |
| `title` | 可选，工作表名（>31 字符自动截断）；省略则用默认表名 |
| 成功 | 返回 `True`，stdout 打印 `✅ Excel 已生成: <path>` 与行列统计 |
| 失败 | 数据为空时打印「错误: 数据为空」并返回 `False` |
| 参数不足 | 打印用法并以 exit code 1 退出（工具会返回该错误输出） |

## 样式细节（与脚本一致）

- 表头：`Font(微软雅黑, bold, 白字)` + `PatternFill(1A3C5E)` + 居中 + 中粗边框
- 数据行：偶数行 `F5F7FA` / 奇数行白色，薄边框 `D0D7E5`
- 类型推断：`detect_type` 识别数值/日期/文本/空；取列内众数类型
- 图表：数值列生成 `BarChart`；数值列 ≥2 时再生成 `LineChart` 趋势对比
- 列宽：按单元格内容长度估算（非固定值）

## 扩展点

想做脚本当前**没有**的事，正确做法是改脚本或另写脚本，而不是照抄旧文档里的函数名：

| 想做的事 | 建议做法 |
|---|---|
| 多 Sheet | 在 `generate_excel` 里改用 `wb.create_sheet(title)`，或新增脚本入口并在 `skill.json` 的 `scripts` 里声明 |
| Markdown/CSV 解析 | 交给 LLM 或 `run_code` 先转成 JSON 数组，再调本工具（保持技能单一职责） |
| 自定义样式 | 改 `generate_excel` 里的 Font/Fill/Border 常量 |
| 追加到已有文件 | 用 `openpyxl.load_workbook(path)` 打开后再写；注意 PRoot 内路径映射 |

## 典型用法

**需求/问题清单**

```bash
python3 scripts/excel_generator.py '[{"序号":1,"问题":"防呆机制","优先级":"P0"},{"序号":2,"问题":"启停卡顿","优先级":"P1"}]' /root/问题清单.xlsx "需求追踪表"
```

**日志统计（先自己聚合好）**

```bash
python3 scripts/excel_generator.py "$(cat /root/agg.json)" /root/报告.xlsx "日志统计"
```

## 版本历史

- v1.0.0：绑定 `scripts/excel_generator.py`（`generate_excel`），单表 + 样式 + 数值列图表；
  依赖改为声明式：`openpyxl` 在 `requirements.txt`、`python3` 在 `apt-requirements.txt`。
