# ZeroTermux API Skill 索引

## 📂 文件列表
| 文件 | 说明 |
|------|------|
| `README.md` | 完整使用指南 |
| `quick_ref.md` | 快速参考卡片 |
| `skill.json` | 元数据与工具定义 |
| `scripts/flask_api.py` | Flask API 服务主程序 |
| `scripts/status.sh` | 绑定脚本：检查服务状态（注册为 `skill_zero_termux_api_status`） |
| `requirements.txt` | Python 依赖声明（`flask`），PRoot 就绪后自动安装 |

## 🚀 快速开始
1. 依赖 `flask` 已由技能目录的 `requirements.txt` 声明并自动安装（无需手动 pip）
2. 启动服务（后台常驻）：工具 `skill_zero_termux_api_start(port="8765")`，或 PRoot 内
   `python3 scripts/flask_api.py 8765`（端口是**位置参数**）
3. 检查状态：工具 `skill_zero_termux_api_status(port="8765")`，或 `bash scripts/status.sh 8765`
4. 停服务：`bash_task_kill`（任务 ID 由 start 返回）

## 🔗 相关路径
- Skill 目录（PRoot 内）: `/host/app/skills/zero-termux-api-skill`（宿主 `filesDir/skills/zero-termux-api-skill` 的映射；`/sdcard/Work` 不是默认加载位置）
- 服务脚本: `scripts/flask_api.py`
- 状态脚本: `scripts/status.sh`
