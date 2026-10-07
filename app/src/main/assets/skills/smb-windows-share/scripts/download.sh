#!/usr/bin/env bash
# skill:smb-windows-share / script:download — 从共享下载文件
# 用法: download <host> <share> [user] [password] <remote_file> [local_path] [workgroup] [port]
set -u

HOST="${1:-}"; SHARE="${2:-}"; USER="${3:-}"; PASS="${4:-}"; RFILE="${5:-}"; LPATH="${6:-}"; WG="${7:-WORKGROUP}"; PORT="${8:-445}"

# smbclient 由本技能的 apt-requirements.txt 声明，宿主在 PRoot 就绪后自动安装并跳过已装的。
# 这里只做可用性检查（不在脚本里临时 apt-get install，避免声明与实现两处漂移）。
command -v smbclient >/dev/null 2>&1 || {
  echo "错误: smbclient 未安装。它已由 apt-requirements.txt 声明，宿主会自动补装；若仍缺失请检查网络或 SKILL 日志。"
  exit 1
}
[ -z "$HOST" ] || [ -z "$SHARE" ] || [ -z "$RFILE" ] && { echo "用法: download <host> <share> [user] [password] <remote_file> [local_path] [workgroup] [port]"; exit 1; }

[ -z "$LPATH" ] && LPATH="$(basename "$RFILE")"

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

echo "===== 下载 //$HOST/$SHARE/$RFILE → $LPATH ====="
smbclient "//$HOST/$SHARE" -p "$PORT" -W "$WG" "${AUTH[@]}" -c "get \"$RFILE\" \"$LPATH\"" 2>&1
rc=$?
echo "===== 完成（退出码 $rc）====="
exit $rc
