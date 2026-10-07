#!/usr/bin/env bash
# skill:smb-windows-share / script:serve — 在 Android（Debian PRoot）上以 SMB 服务端共享本机目录
#
# 用法:
#   serve <share_path> [share_name] [port] [bind_ip] [user] [password] [persist]
#
# 两种模式：
#   persist=0（默认）验证模式：smbd 启动并自检后脚本立即退出，只用于快速验证配置与连通性。
#     注意：PRoot 命令带 --kill-on-exit，命令一结束 guest 内所有进程（含 smbd）都会被回收，
#     因此该模式下服务不会跨命令存活。
#   persist=1 常驻模式：脚本启动 smbd（前台）并原地等待，服务进程不退脚本不退。
#     必须通过 run_bash_bg（或本技能 serve_bg 入口）启动 —— 宿主侧常驻 PRoot 进程承载，
#     无 300 秒命令超时，服务可跨命令存活，用 bash_task_status / bash_task_logs / bash_task_kill 管理。
#
# 已内建本机验证过的两项修复（Android PRoot + FUSE 存储）：
#   * 独立运行时目录 /root/samba_run（pid/lock/state/cache/private/log），
#     避免直接使用 /tmp 时 msg.lock 等文件权限/归属问题导致 smbd 启动失败。
#   * 共享块加 force user = root，解决 Android FUSE 目录对访客（nobody）不可读的访问限制。
set -u

SPATH="${1:-}"
SNAME="${2:-share}"
BIND="${4:-0.0.0.0}"
SUSER="${5:-}"
SPASS="${6:-}"
PERSIST="${7:-0}"
RUNDIR="${SMB_RUNDIR:-/root/samba_run}"

# 端口：默认优先 445（Windows 原生客户端默认端口）；未显式指定且 445 绑不上时自动回退 8445
if [ -z "${3:-}" ]; then
  PORT=445
  AUTO_FALLBACK=1
else
  PORT="$3"
  AUTO_FALLBACK=0
fi

# 参数校验
[ -z "$SPATH" ] && { echo "用法: serve <share_path> [share_name] [port] [bind_ip] [user] [password] [persist]"; exit 1; }
[ -d "$SPATH" ] || { echo "错误: 共享目录不存在: $SPATH"; exit 1; }

# samba（提供 smbd 真实 SMB3 服务端）由本技能的 apt-requirements.txt 声明，宿主自动安装。
command -v smbd >/dev/null 2>&1 || {
  echo "错误: smbd 未安装。它已由 apt-requirements.txt（samba）声明，宿主会自动补装；若仍缺失请检查网络或 SKILL 日志。"
  exit 1
}

# 独立运行时目录：smbd 的 pid/lock/state/cache/private/log 全部放这里，
# 避免 /tmp 下 msg.lock 等文件因归属/权限问题导致启动失败。
mkdir -p "$RUNDIR" 2>/dev/null || true
chmod 777 "$RUNDIR" 2>/dev/null || true

# 共享目录权限（匿名访客需可读写）
chmod -R 777 "$SPATH" 2>/dev/null || true

# 认证配置：提供 user 则启用账户，否则匿名访客
if [ -n "$SUSER" ]; then
  AUTH_BLOCK="security = user
   guest ok = no"
  if command -v smbpasswd >/dev/null 2>&1; then
    if printf '%s\n%s\n' "$SPASS" "$SPASS" | smbpasswd -a -s "$SUSER" >/dev/null 2>&1; then
      echo "已创建 SMB 账户: $SUSER"
    else
      echo "（创建 SMB 账户失败，回退为匿名共享）"
      AUTH_BLOCK="security = user
   map to guest = bad user
   guest account = nobody
   guest ok = yes"
    fi
  else
    echo "（未找到 smbpasswd，回退为匿名共享）"
    AUTH_BLOCK="security = user
   map to guest = bad user
   guest account = nobody
   guest ok = yes"
  fi
else
  AUTH_BLOCK="security = user
   map to guest = bad user
   guest account = nobody
   guest ok = yes"
fi

# 绑定地址：仅本机 127.0.0.1 时显式限制接口；其他(0.0.0.0/具体IP)交 smbd 默认绑所有接口
# （PRoot 下 smbd 无法把 interfaces=0.0.0.0 解析为网络接口，会导致启动失败）
if [ "$BIND" = "127.0.0.1" ]; then
  BIND_CONF="   bind interfaces only = yes
   interfaces = 127.0.0.1"
else
  BIND_CONF=""
fi

# 生成配置（端口参数化，便于 445 失败回退 8445 时重新生成）
gen_conf() {
  local p="$1"
  cat > "$RUNDIR/smbserve_${p}.conf" <<EOF
[global]
   workgroup = WORKGROUP
   server string = Android SMB Server (smb-windows-share serve)
   $AUTH_BLOCK
   server min protocol = SMB2
   server max protocol = SMB3
   smb ports = $p
   $BIND_CONF
   pid directory = $RUNDIR
   lock directory = $RUNDIR
   state directory = $RUNDIR
   cache directory = $RUNDIR
   private dir = $RUNDIR
   log file = $RUNDIR/smbd_${p}.log
   log level = 1
[$SNAME]
   path = $SPATH
   read only = no
   browseable = yes
   # Android FUSE 存储对访客(nobody)不可读：强制以 root 身份访问文件，
   # 让匿名访客也能正常读写共享目录（PRoot --root-id 下 smbd 即以 root 运行）。
   force user = root
   create mask = 0644
   directory mask = 0755
EOF
}

# 端口就绪探测：进程存活 + 端口可连（优先 smbclient 协商，缺失时退化为 bash /dev/tcp）
port_ready() {
  local p="$1"
  if command -v smbclient >/dev/null 2>&1; then
    smbclient -L 127.0.0.1 -p "$p" -N -m SMB3 >/dev/null 2>&1 && return 0
  else
    (exec 3<>"/dev/tcp/127.0.0.1/$p") 2>/dev/null && { exec 3>&- 3<&-; return 0; }
  fi
  return 1
}

# 用指定端口启动 smbd（前台 -F，便于常驻等待），就绪后返回 0
start_with_port() {
  local p="$1"
  local conf="$RUNDIR/smbserve_${p}.conf"
  local log="$RUNDIR/smbd_${p}.log"
  gen_conf "$p"
  # 清理可能残留的同端口 smbd 实例
  pkill -f "smbd.*smbserve_${p}\.conf" 2>/dev/null || true
  nohup smbd -F -s "$conf" --no-process-group >"$log" 2>&1 &
  SMB_PID=$!
  local i
  for i in $(seq 1 20); do
    if ! kill -0 "$SMB_PID" 2>/dev/null; then
      echo "错误: smbd 在端口 $p 提前退出"; echo "--- 日志 ($log) ---"; cat "$log" 2>/dev/null
      return 1
    fi
    if port_ready "$p"; then
      return 0
    fi
    sleep 1
  done
  echo "错误: smbd 在端口 $p 就绪超时（20s）"; echo "--- 日志 ($log) ---"; cat "$log" 2>/dev/null
  kill "$SMB_PID" 2>/dev/null || true
  wait "$SMB_PID" 2>/dev/null || true
  return 1
}

# 优先 445（Windows 原生客户端默认端口），失败（无 root 绑不了特权端口）回退 8445
FINAL_PORT=""
if start_with_port "$PORT"; then
  FINAL_PORT="$PORT"
elif [ "$AUTO_FALLBACK" = "1" ] && [ "$PORT" = "445" ]; then
  echo "（445 绑定失败，可能无 root 权限，回退 8445）"
  PORT=8445
  if start_with_port "$PORT"; then
    FINAL_PORT="$PORT"
  fi
else
  echo "错误: 端口 $PORT 启动失败"
fi

if [ -z "$FINAL_PORT" ]; then
  echo "错误: 445 与 8445 均启动失败"; exit 1
fi

echo "===== SMB 服务端已启动 ====="
echo "共享地址: //$BIND:$FINAL_PORT/$SNAME"
echo "本地目录: $SPATH"
echo "smbd PID: $SMB_PID"
echo "配置文件: $RUNDIR/smbserve_${FINAL_PORT}.conf   日志: $RUNDIR/smbd_${FINAL_PORT}.log"
if [ "$BIND" = "0.0.0.0" ]; then
  echo
  echo "⚠️ 已绑定 0.0.0.0（暴露到所有网络接口）。匿名共享会被同网络任意客户端读写。"
  echo "   建议: 传 user/password 启用账户认证；确保 Android 防火墙放行 $FINAL_PORT；"
  if [ "$FINAL_PORT" = "445" ]; then
    echo "   当前为 445：外部 Windows 原生客户端可直接连 //$BIND/$SNAME。"
  else
    echo "   当前为 8445（非标准端口）：Windows 原生资源管理器连不上，需用支持端口的客户端（smbclient/Total Commander）。"
  fi
fi
echo
echo "客户端连接示例:"
echo "  本机匿名:  smbclient //127.0.0.1:$FINAL_PORT/$SNAME -N -p $FINAL_PORT"
echo "  外部 Windows: \\\\$BIND:$FINAL_PORT\\$SNAME  （需客户端支持端口 / 防火墙放行）"

if [ "$PERSIST" = "1" ]; then
  echo
  echo "（常驻模式：脚本保持运行直到服务停止；任务由 run_bash_bg 承载，可跨命令存活，）"
  echo "  用 bash_task_status / bash_task_logs / bash_task_kill 管理）"
  wait "$SMB_PID"
  local_rc=$?
  echo "===== SMB 服务端已退出 (code=$local_rc) ====="
  exit "$local_rc"
else
  echo
  echo "⚠️ 验证模式（persist=0）：PRoot 命令结束后服务进程会被 --kill-on-exit 回收，不会常驻。"
  echo "   如需服务跨命令常驻，请通过 run_bash_bg / serve_bg 启动并设置 persist=1。"
fi
echo "===== 完成 ====="
exit 0
