#!/usr/bin/env bash
# skill:git / script:repo_status — 查看指定 git 仓库的状态、分支与最近提交
# 用法: repo_status <仓库路径> [详情级别]
#   详情级别: short（默认，状态+分支+最近 5 条提交）/ full（再加 diff 统计与远程同步状态）
set -u

REPO_PATH="${1:-}"
DETAIL="${2:-short}"

if [ -z "$REPO_PATH" ]; then
  echo "用法: repo_status <仓库路径> [short|full]"
  exit 1
fi

if [ ! -d "$REPO_PATH/.git" ]; then
  echo "错误: $REPO_PATH 不是 git 仓库（缺少 .git 目录）"
  exit 1
fi

# PRoot root 身份访问宿主目录时忽略 dubious ownership
git config --global safe.directory '*' 2>/dev/null || true

echo "===== Git 仓库状态: $REPO_PATH ====="
echo
echo "--- 当前分支 ---"
git -C "$REPO_PATH" branch --show-current 2>/dev/null || git -C "$REPO_PATH" rev-parse --abbrev-ref HEAD

echo
echo "--- 工作区状态 ---"
if [ -z "$(git -C "$REPO_PATH" status --porcelain)" ]; then
  echo "（工作区干净，无未提交变更）"
else
  git -C "$REPO_PATH" status --short
fi

echo
echo "--- 最近提交 ---"
git -C "$REPO_PATH" log --oneline -n 5 2>/dev/null || echo "（暂无提交）"

if [ "$DETAIL" = "full" ]; then
  echo
  echo "--- 变更统计（工作区 vs HEAD）---"
  git -C "$REPO_PATH" diff --stat 2>/dev/null | tail -n 20 || true

  echo
  echo "--- 与远程同步状态 ---"
  git -C "$REPO_PATH" fetch --quiet 2>/dev/null || echo "（无法访问远程，可能是离线或未配置 remote）"
  local_commit=$(git -C "$REPO_PATH" rev-parse HEAD 2>/dev/null || echo "")
  remote_commit=$(git -C "$REPO_PATH" rev-parse @{u} 2>/dev/null || echo "")
  if [ -n "$remote_commit" ] && [ -n "$local_commit" ]; then
    if [ "$local_commit" = "$remote_commit" ]; then
      echo "已与远程同步"
    else
      echo "本地落后/领先远程（本地=${local_commit:0:8} 远程=${remote_commit:0:8}）"
      git -C "$REPO_PATH" log --oneline HEAD..@{u} 2>/dev/null | head -n 10
    fi
  else
    echo "（未配置上游分支或无远程）"
  fi
fi
echo
echo "===== 完成 ====="
