# Git 使用 Skill (Debian PRoot)

## 📖 概述

本 Skill 提供在 Android 环境下使用 Git 版本控制的能力，基于 Debian PRoot Linux 环境安装的 Git 实现，配合 `run_bash` 工具调用。

## 📦 依赖环境

- Debian PRoot 环境（首次 run_bash 自动解压或到设置页手动安装）
- Git：由技能目录的 `apt-requirements.txt` 声明（内容就是 `git`），宿主在 PRoot 就绪后自动安装，
  装过即跳过；**不需要**手动 `apt install git`。若脚本报 `git: command not found`，
  检查网络或日志页的 `SKILL` 标签（随包 rootfs 是 Debian minbase，不含 git）。
- 网络连接（用于远程仓库 clone/pull/push）

## 🧰 绑定工具（推荐用法）

本技能已把 `scripts/repo_status.sh` 注册为工具，**不用手敲 git 命令**即可查看仓库状态：

| 参数 | 必填 | 说明 |
|------|------|------|
| `repo_path` | ✅ | 要检查的 Git 仓库绝对路径 |
| `detail` | | `short`（默认，状态 + 分支 + 最近 5 条提交）/ `full`（再加 diff 统计与远程同步状态） |

示例：`skill_git_repo_status(repo_path="/host/app/skills/git", detail="full")`

其余操作（clone / commit / push / 分支管理…）用 `run_bash` 直接执行下面的命令。

## 🔧 快速开始

### 1. 确认 Git 可用

```bash
git --version    # 依赖由声明文件自动安装；缺失时看日志页 SKILL 标签
```

### 2. 配置用户信息

```bash
git config --global user.name "Your Name"
git config --global user.email "email@example.com"
```

### 3. 初始化仓库

```bash
cd /path/to/your/project
git init
```

### 4. 添加文件

```bash
git add file.txt      # 单个文件
git add .             # 所有文件
```

### 5. 提交变更

```bash
git commit -m "提交信息"
```

### 6. 查看状态

```bash
git status
```

### 7. 查看提交历史

```bash
git log --oneline -n 20
```

---

## 📋 远程仓库操作

### 克隆远程仓库

```bash
cd /target/parent/dir
git clone https://github.com/user/repo.git
```

### HTTPS 认证（使用 Token）

```bash
# 推送到 GitHub（使用 Personal Access Token）
git remote set-url origin https://<token>@github.com/user/repo.git
git push origin main
```

### 推送

```bash
git push origin main
git push --force origin main   # 强制推送（谨慎使用）
```

### 拉取

```bash
git pull origin main
```

### 仅拉取不合并

```bash
git fetch origin main
git merge origin/main
```

> **注意**：`git pull` 遇到冲突时需手动解决冲突后再次提交。建议使用 `fetch` + `merge` 组合获得更细粒度的控制。

---

## 🌿 分支管理

### 列出分支

```bash
git branch            # 本地分支
git branch -a         # 所有分支（含远程）
```

### 创建并切换

```bash
git checkout -b feature-x    # 创建并切换
git branch feature-x         # 仅创建
git checkout feature-x       # 切换到已有
```

### 删除分支

```bash
git branch -d feature-x      # 已合并分支
git branch -D feature-x      # 强制删除（未合并）
```

---

## 🔍 差异对比

```bash
git diff                      # 工作区 vs 暂存区
git diff --cached             # 暂存区 vs HEAD
git diff HEAD~1 HEAD          # 最近两次提交差异
git diff main..feature-x      # 分支差异
```

---

## 🛠️ 常见场景

### 场景 1：推送本地新项目到 GitHub

```bash
cd /path/to/project
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://<token>@github.com/user/repo.git
git push -u origin main
```

### 场景 2：拉取最新并解决冲突

```bash
git fetch origin
git merge origin/main
# 如发生冲突：编辑冲突文件 → git add . → git commit
```

### 场景 3：查看提交差异（定位某次提交改了什么）

```bash
git show <commit_hash>
git diff <commit_hash>~1 <commit_hash>
```

### 场景 4：撤销本地未提交的修改

```bash
git checkout -- <file>     # 撤销单个文件
git checkout .             # 撤销所有文件修改
```

---

## 📌 注意事项

1. **执行路径**：`run_bash` 中务必 `cd /repo/dir` 后再执行 Git 命令，或使用 `-C` 参数：`git -C /repo/dir status`
2. **认证方式**：HTTPS + Token 是 Android 下最稳定的方式。SSH 需要额外配置密钥。
3. **Dubious ownership**：PRoot 使用 root 身份访问绑定目录。如遇报错可在命令中临时设置：`git config --global safe.directory '*'`
4. **性能**：原生 Git 二进制性能远优于脚本实现，大型仓库也能流畅运行。

---

## 📚 相关资源

- Git 官方文档: https://git-scm.com/docs
- Pro Git 中文版: https://git-scm.com/book/zh/v2
