#!/usr/bin/env bash
# skill:smb-windows-share / script:upload — 上传文件到共享
# 用法: upload <host> <share> [user] [password] <local_path> [remote_path] [workgroup] [port]
set -u

HOST="${1:-}"; SHARE="${2:-}"; USER="${3:-}"; PASS="${4:-}"; LPATH="${5:-}"; RPATH="${6:-}"; WG="${7:-WORKGROUP}"; PORT="${8:-445}"

# smbclient 由本技能的 apt-requirements.txt 声明，宿主在 PRoot 就绪后自动安装并跳过已装的。
# 这里只做可用性检查（不在脚本里临时 apt-get install，避免声明与实现两处漂移）。
command -v smbclient >/dev/null 2>&1 || {
  echo "错误: smbclient 未安装。它已由 apt-requirements.txt 声明，宿主会自动补装；若仍缺失请检查网络或 SKILL 日志。"
  exit 1
}
[ -z "$HOST" ] || [ -z "$SHARE" ] || [ -z "$LPATH" ] && { echo "用法: upload <host> <share> [user] [password] <local_path> [remote_path] [workgroup] [port]"; exit 1; }
[ -f "$LPATH" ] || { echo "错误: 本地文件不存在: $LPATH"; exit 1; }
[ -z "$RPATH" ] && RPATH="$(basename "$LPATH")"

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

echo "===== 上传 $LPATH → //$HOST/$SHARE/$RPATH ====="
smbclient "//$HOST/$SHARE" -p "$PORT" -W "$WG" "${AUTH[@]}" -c "put \"$LPATH\" \"$RPATH\"" 2>&1
rc=$?
echo "===== 完成（退出码 $rc）====="
exit $rc
