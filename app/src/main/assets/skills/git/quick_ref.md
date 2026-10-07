# Git 快速参考 (Debian PRoot)

## 常用命令

| Git 命令 | 说明 |
|----------|------|
| `git init` | 初始化仓库 |
| `git add <file>` | 添加文件到暂存区 |
| `git add .` | 添加所有文件 |
| `git commit -m "msg"` | 提交变更 |
| `git status` | 查看工作区状态 |
| `git log --oneline` | 查看提交历史（简洁） |
| `git clone <url> <dest>` | 克隆远程仓库 |
| `git push origin <branch>` | 推送到远程 |
| `git push --force origin <branch>` | 强制推送 |
| `git pull origin <branch>` | 拉取并合并 |
| `git fetch origin <branch>` | 仅拉取不合并 |
| `git branch` | 列出本地分支 |
| `git branch -d <branch>` | 删除分支 |
| `git checkout <branch>` | 切换分支 |
| `git diff` | 查看未暂存差异 |
| `git diff --cached` | 查看已暂存差异 |
| `git remote -v` | 查看远程仓库地址 |

## 配置用户信息

```bash
git config --global user.name "Your Name"
git config --global user.email "email@example.com"
```

## 常用 run_bash 调用模板

```bash
# 克隆仓库
cd /target/dir && git clone https://github.com/user/repo.git

# 提交变更
cd /repo/dir && git add . && git commit -m "message" && git push origin main
```

## 首次安装

```bash
apt update && apt install git
```
