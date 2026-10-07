# GitHub Actions Skill

> 通过 GitHub API 获取工作流运行状态、执行日志和触发记录的标准化 Skill

## 适用场景

- 查看仓库 Actions 运行状态（成功/失败/进行中）
- 获取最新工作流触发记录与 commit 信息
- 拉取工作流执行日志用于调试
- 监控 CI/CD 流水线健康度
- 排查构建失败原因

---

## 先分清两套用法

| 用法 | 入口 | 依赖 |
|------|------|------|
| **工具调用（推荐）** | `skill_github_actions_status(owner, repo, token, limit)` / `skill_github_actions_logs(owner, repo, token, run_id)` | 只需标准库；`python3` 由宿主自动安装 |
| **手工调用辅助库** | 在 PRoot 里 `cd /host/app/skills/github-actions-skill` 后用下面的 python 示例 | 需要 `requests`（已在 `requirements.txt` 声明，宿主会自动装） |

> 注意：`from github_actions import ...` 这种写法**只在技能目录下手工执行时**有效
> （工具运行脚本是把脚本体经 stdin 交给解释器，工作目录不是技能目录）。

## 辅助库用法（手工）

```python
from github_actions import (
    list_workflow_runs,
    get_latest_status,
    get_failure_logs,
    download_latest_artifact,
    parse_logs,
    format_status_report
)

# 1. 列出最近 10 次运行记录
runs = list_workflow_runs(
    owner="Aasdqwe1",
    repo="agent-toolbox-kotlin",
    token="YOUR_GITHUB_TOKEN",
    per_page=10
)

# 2. 获取最新运行状态
status = get_latest_status(
    owner="Aasdqwe1",
    repo="agent-toolbox-kotlin",
    token="YOUR_GITHUB_TOKEN",
    branch="main"          # 可选
)
print(format_status_report(status))

# 3. 获取最近一次失败运行的日志
result = get_failure_logs(
    owner="Aasdqwe1",
    repo="agent-toolbox-kotlin",
    token="YOUR_GITHUB_TOKEN",
    branch="main"          # 可选
)
print(f"错误数: {len(result['parsed']['errors'])}")
for err in result['parsed']['errors']:
    print(err)

# 4. 下载最新成功运行的产物
files = download_latest_artifact(
    owner="Aasdqwe1",
    repo="agent-toolbox-kotlin",
    token="YOUR_GITHUB_TOKEN",
    output_dir="./artifacts",
    artifact_name="app"    # 可选，过滤产物名称
)
print(f"已下载: {files}")
```

---

## 核心函数

| 函数 | 说明 |
|------|------|
| `list_workflow_runs(owner, repo, token, per_page, status, conclusion, branch)` | 列出工作流运行记录 |
| `get_latest_status(owner, repo, token, branch)` | 获取最新一次运行状态 |
| `get_failure_logs(owner, repo, token, branch)` | 获取最近一次失败运行的日志（含解析结果） |
| `download_latest_artifact(owner, repo, token, output_dir, artifact_name)` | 下载最新成功运行的产物 |
| `parse_logs(logs_dict)` | 解析日志提取错误/警告 |
| `format_status_report(run_dict)` | 格式化为 Markdown 报告 |

---

## 目录结构

```
github-actions-skill/          # 注意：目录名带 -skill，而 skill.json 的 name 是 github-actions
├── skill.json                 # 元数据 + 脚本声明（status / logs）
├── README.md                  # 快速开始与用法分界（本文件）
├── WORKFLOW.md                # 流程、安全、函数一览
├── index.md / quick_ref.md    # 索引入口与速查
├── requirements.txt           # pip 依赖：requests（仅辅助库需要）
├── apt-requirements.txt       # 系统依赖：python3
├── github_actions.py          # 辅助库（需 requests；供 run_bash 手工调用，非注册工具）
└── scripts/
    ├── status.py              # 注册工具 skill_github_actions_status
    └── logs.py                # 注册工具 skill_github_actions_logs
```

---

## 详细文档

完整工作流程请参阅 **[WORKFLOW.md](WORKFLOW.md)**，包含：
- 工作流程图
- API 调用规范与错误处理
- 日志解析策略
- 典型场景复用指南
- 工具函数完整一览