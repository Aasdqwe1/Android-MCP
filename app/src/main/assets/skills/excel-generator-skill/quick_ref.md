# Excel Generator 快速参考

## 工具调用

| 工具/命令 | 说明 |
|----------|------|
| `skill_excel_generator_skill_generate(data_json, output_path, title)` | 生成 Excel（参数按声明顺序位置传入） |
| `python3 scripts/excel_generator.py <data_json> <output_path> [title]` | 同上，PRoot 内直接跑 |

## 最小示例

```bash
python3 scripts/excel_generator.py '[{"序号":1,"任务":"需求分析","状态":"已完成"}]' /root/任务清单.xlsx "项目进度"
```

## 输入约定

- `data_json`：JSON **数组**，每项是一个对象；**第一项的键顺序即表头顺序**。
- 输出：`.xlsx` 绝对路径；父目录需存在。
- 空数组 → 打印「错误: 数据为空」并返回失败。

## 依赖

```
requirements.txt     -> openpyxl>=3.1.0     （pip，由宿主自动安装）
apt-requirements.txt -> python3             （系统依赖，由宿主自动安装）
```

不要在文档或提示词里让 LLM 临场 `pip install`（例如 `pip install openpyxl pandas`——
`pandas` 本技能根本不使用）；依赖写进上面的声明文件，装一次之后都会跳过。
