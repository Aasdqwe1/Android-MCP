#!/usr/bin/env bash
# skill:smb-windows-share / script:list_shares — 列出 Windows 主机的所有网络共享
# 用法: list_shares <host> [user] [password] [workgroup] [port]
set -u

HOST="${1:-}"; USER="${2:-}"; PASS="${3:-}"; WG="${4:-WORKGROUP}"; PORT="${5:-445}"

# smbclient 由本技能的 apt-requirements.txt 声明，宿主在 PRoot 就绪后自动安装并跳过已装的。
# 这里只做可用性检查（不在脚本里临时 apt-get install，避免声明与实现两处漂移）。
command -v smbclient >/dev/null 2>&1 || {
  echo "错误: smbclient 未安装。它已由 apt-requirements.txt 声明，宿主会自动补装；若仍缺失请检查网络或 SKILL 日志。"
  exit 1
}

[ -z "$HOST" ] && { echo "用法: list_shares <host> [user] [password] [workgroup] [port]"; exit 1; }

# 构造认证参数（优先用凭据文件，避免密码出现在进程列表）
CRED="$(mktemp)"; chmod 600 "$CRED"
trap 'rm -f "$CRED"' EXIT
if [ -n "$PASS" ]; then
  { [ -n "$USER" ] && printf 'username = %s\n' "$USER"; printf 'password = %s\n' "$PASS"; } > "$CRED"
  AUTH=(-A "$CRED")
elif [ -n "$USER" ]; then
  AUTH=(-U "$USER")
else
  AUTH=(-N)
fi

echo "===== 主机 $HOST 的网络共享 ====="
smbclient -L "//$HOST" -p "$PORT" -W "$WG" "${AUTH[@]}" 2>&1
rc=$?
echo "===== 完成（退出码 $rc）====="
exit $rc
