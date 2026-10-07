#!/usr/bin/env bash
# skill:smb-windows-share / script:list_files — 列出共享目录下的文件
# 用法: list_files <host> <share> [user] [password] [remote_path] [workgroup] [port]
set -u

HOST="${1:-}"; SHARE="${2:-}"; USER="${3:-}"; PASS="${4:-}"; RPATH="${5:-}"; WG="${6:-WORKGROUP}"; PORT="${7:-445}"

# smbclient 由本技能的 apt-requirements.txt 声明，宿主在 PRoot 就绪后自动安装并跳过已装的。
# 这里只做可用性检查（不在脚本里临时 apt-get install，避免声明与实现两处漂移）。
command -v smbclient >/dev/null 2>&1 || {
  echo "错误: smbclient 未安装。它已由 apt-requirements.txt 声明，宿主会自动补装；若仍缺失请检查网络或 SKILL 日志。"
  exit 1
}
[ -z "$HOST" ] || [ -z "$SHARE" ] && { echo "用法: list_files <host> <share> [user] [password] [remote_path] [workgroup] [port]"; exit 1; }

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

CMD="ls"
[ -n "$RPATH" ] && CMD="ls \"$RPATH\""
echo "===== //$HOST/$SHARE/${RPATH} ====="
smbclient "//$HOST/$SHARE" -p "$PORT" -W "$WG" "${AUTH[@]}" -c "$CMD" 2>&1
rc=$?
echo "===== 完成（退出码 $rc）====="
exit $rc
