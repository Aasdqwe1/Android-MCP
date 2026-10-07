# Excel Generator Skill

> 把结构化数据（JSON 数组）生成带样式的 Excel 表格。

## 适用场景

- 需求文档 / 问题清单 → 格式化追踪表
- 任务排期 → 单表进度表
- 日志分析 → 统计报告

> 当前实现是**单工作表**（表名 = 传入的 title）。多 Sheet 需要自己扩展脚本，见 WORKFLOW.md「扩展点」。

---

## 用法（作为工具调用）

绑定脚本已注册为 MCP 工具 `skill_excel_generator_skill_generate`，参数**按声明顺序作为位置参数**传给脚本：

| 参数 | 必填 | 说明 |
|------|------|------|
| `data_json` | ✅ | JSON 数组字符串，如 `[{"序号":1,"任务":"需求分析"}]` |
| `output_path` | ✅ | 输出 .xlsx 的**绝对路径**（PRoot 内路径，宿主对应 `/host/...`） |
| `title` | | 工作表名（超过 31 字符会被截断） |

## 用法（在 PRoot 里直接跑）

```bash
python3 scripts/excel_generator.py '[{"序号":1,"任务":"需求分析","状态":"已完成"}]' /root/out.xlsx "项目进度"
```

---

## 脚本实际提供的能力

| 函数 | 说明 |
|------|------|
| `generate_excel(data, output_path, title="")` | 唯一入口：写表头样式 + 数据行，成功返回 `True` |
| `detect_type(value)` | 内部工具：推断单元格类型（数值/日期/文本/空） |
| `format_value(value, data_type)` | 内部工具：按类型格式化取值 |

生成时自动做的样式：深色表头（微软雅黑加粗白字）、数据行隔行底色、细边框、
按内容推算列宽、冻结表头；若存在数值列还会追加柱状图（多个数值列再加一张折线对比图）。

> 说明：早期文档里出现过的 `generate_multi_sheet` / `parse_input` / `clean_data` /
> `validate_output` / `add_sheet` / `set_column_width` / `apply_style` **在当前脚本里并不存在**，
> 那些章节已按实现改写。

---

## 目录结构

```
excel-generator-skill/
├── skill.json                 # 元数据 + 脚本声明
├── requirements.txt           # pip 依赖（openpyxl），PRoot 就绪后自动安装
├── apt-requirements.txt       # 系统依赖（python3；随包 rootfs 是 Debian minbase，不含 python3）
├── README.md                  # 本文件
├── WORKFLOW.md                # 流程说明与扩展点
├── index.md / quick_ref.md    # 索引入口与速查
└── scripts/
    └── excel_generator.py     # 绑定脚本（入口即 generate_excel）
```

## 依赖

- pip：`openpyxl`（见 `requirements.txt`，由宿主自动安装，**不需要手动 pip**）
- 系统：`python3`（见 `apt-requirements.txt`）

## 相关文档

- **[WORKFLOW.md](WORKFLOW.md)** — 处理流程、样式细节、扩展点（多 Sheet / 自定义样式）
