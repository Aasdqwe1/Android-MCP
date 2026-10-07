# Git Skill 索引

## 📂 文件列表

| 文件 | 说明 |
|------|------|
| `README.md` | 完整使用指南 |
| `quick_ref.md` | 快速参考卡片 |
| `scripts/repo_status.sh` | 绑定脚本：仓库状态查看（注册为工具 `skill_git_repo_status`） |

## 🚀 快速开始

1. 阅读 `README.md` 了解基本用法
2. 查看 `quick_ref.md` 快速查找命令
3. 通过 Debian PRoot 安装 Git：`apt update && apt install git`
4. 直接调用 `skill_git_repo_status` 查看任意仓库状态（无需手写命令）

## 🔗 相关路径

- Skill 目录: 内置 Skill (assets/skills/git)
- Debian PRoot rootfs: 应用私有目录中的固定 Debian rootfs，脚本内使用 `/usr/bin` 和 `/root`
- Git 安装后路径: `/usr/bin/git`（PRoot Debian；`$PREFIX` 是 Termux 概念，PRoot 里没有）

---
生成时间: 2026-07-26