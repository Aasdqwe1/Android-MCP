# GitHub Actions Skill 索引

## 📂 文件列表
| 文件 | 说明 |
|------|------|
| `README.md` | 完整使用指南 |
| `WORKFLOW.md` | 工作流说明 |
| `quick_ref.md` | 快速参考卡片 |
| `skill.json` | 元数据与工具定义 |
| `scripts/status.py` | 绑定脚本：查看工作流状态（注册为 `skill_github_actions_status`） |
| `scripts/logs.py` | 绑定脚本：获取工作流日志（注册为 `skill_github_actions_logs`） |
| `requirements.txt` | 依赖声明：`requests`（仅辅助库 github_actions.py 需要；绑定的两个脚本只用标准库） |
| `apt-requirements.txt` | 系统依赖：`python3`（随包 rootfs 未预装） |

## 🚀 快速开始
1. 无需安装依赖：脚本只用标准库 `urllib`
2. 查看状态：`python3 scripts/status.py <owner> <repo> <token>`
3. 获取日志：`python3 scripts/logs.py <owner> <repo> <token> <run_id>`
4. 或直接调用 `skill_github_actions_status` / `skill_github_actions_logs`

## 🔗 相关路径
- Skill 目录（PRoot 内）: `/host/app/skills/github-actions-skill`（宿主 `filesDir/skills/github-actions-skill` 的映射；`/sdcard/Work` 不是默认加载位置）
- 脚本: `scripts/status.py`, `scripts/logs.py`
