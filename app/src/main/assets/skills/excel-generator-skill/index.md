# Excel Generator Skill 索引

## 📂 文件列表
| 文件 | 说明 |
|------|------|
| `README.md` | 完整使用指南 |
| `WORKFLOW.md` | 工作流说明 |
| `quick_ref.md` | 快速参考卡片 |
| `skill.json` | 元数据与工具定义 |
| `scripts/excel_generator.py` | 绑定脚本：从 JSON 生成 Excel（注册为 `skill_excel_generator_skill_generate`） |
| `requirements.txt` | Python 依赖声明（`openpyxl`），PRoot 就绪后自动安装 |

## 🚀 快速开始
1. 依赖 `openpyxl` 已由技能目录的 `requirements.txt` 声明并自动安装（无需手动 pip）
2. 生成 Excel：`python3 scripts/excel_generator.py '[{"col1":"val1"}]' /path/to/output.xlsx "标题"`
3. 或直接调用工具 `skill_excel_generator_skill_generate`

## 🔗 相关路径
- Skill 目录（PRoot 内）: `/host/app/skills/excel-generator-skill`（宿主 `filesDir/skills/excel-generator-skill` 的映射；`/sdcard/Work` 不是默认加载位置）
- 脚本: `scripts/excel_generator.py`
