# GitHub Actions 快速参考

## 常用命令

| 命令 | 说明 |
|------|------|
| `skill_github_actions_status owner repo token` | 查看最新工作流状态 |
| `skill_github_actions_logs owner repo token run_id` | 获取指定运行ID的日志 |

## 快速开始

```bash
# 查看状态
skill_github_actions_status Aasdqwe1 agent-toolbox-kotlin $GITHUB_TOKEN

# 获取日志
skill_github_actions_logs Aasdqwe1 agent-toolbox-kotlin $GITHUB_TOKEN 123456789
```

## 环境要求

- Python 3（仅标准库 urllib，无第三方依赖）
- GitHub Personal Access Token（需要有 repo 权限）
