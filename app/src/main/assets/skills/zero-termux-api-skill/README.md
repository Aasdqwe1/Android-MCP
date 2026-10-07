# ZeroTermux API Skill

## 简介

这个 Skill 让你在 ZeroTermux 中启动一个 Flask API 服务，使 AI 助手可以通过 HTTP 远程执行命令、管理文件和控制你的 Android 设备。

## 运行环境（先看这条）

本技能由宿主在 **Debian PRoot** 里执行（不是 Termux 原生环境）：

- `python3` 与 `flask` **不需要手动安装**：`apt-requirements.txt`（python3）与
  `requirements.txt`（flask）声明后，宿主会在 PRoot 就绪时自动装，且装一次即跳过。
- 随包 rootfs 是 Debian minbase，**预装里没有 python3**，所以第一次调用会先装依赖，稍慢属正常。
- Termux 专属命令（`pkg`、`termux-*`）在 PRoot 内**不可用**；`/api/device` 返回的是本服务的环境信息。

## 快速开始

### 1. 启动 API 服务

推荐直接用绑定的工具（**后台常驻**，立即返回 `bg_` 任务 ID，服务跨命令存活）：

```
skill_zero_termux_api_start(port="8765")     # port 可省略，默认 8765
```

启动后用 `bash_task_status` / `bash_task_logs` 查看状态与日志，
`bash_task_kill` 停止服务；健康检查用 `skill_zero_termux_api_status(port="8765")`。

也可以在 PRoot 里手工前台运行（会占用当前命令直到超时）：

```bash
python3 /host/app/skills/zero-termux-api-skill/scripts/flask_api.py 8765
```

服务启动后，你会看到：

```
* Running on http://127.0.0.1:8765
* Running on http://192.168.x.x:8765
```

### 2. 测试 API

在 ZeroTermux 中测试：

```bash
# 测试 ping
curl http://127.0.0.1:8765/ping

# 执行命令
curl -X POST http://127.0.0.1:8765/api/exec \
  -H "Content-Type: application/json" \
  -d '{"cmd": "echo Hello"}'
```

### 3. 让 AI 助手连接

当服务运行后，AI 助手可以通过以下方式连接：

**方式一：局域网连接**

使用手机局域网 IP（如 `http://192.168.80.104:8765`）

**方式二：内网穿透（远程访问）**

安装 ngrok 并暴露端口：

```bash
# 安装 ngrok
pkg install ngrok

# 暴露 8765 端口
ngrok http 8765
```

### 4. 连接成功通知

当 AI 助手成功连接到 API 服务时，会自动发送通知到手机：

```bash
termux-notification -t "✅ AI 助手连接成功" -c "已成功连接到 ZeroTermux API 服务！" --priority high
```

AI 助手会通过 `/api/exec` 端点自动执行此命令，让你在手机上实时收到连接状态提醒。

## API 端点

### GET /ping
健康检查，返回 `{"status": "ok"}`

### POST /api/exec
执行命令，请求体：

```json
{
  "cmd": "your command here"
}
```

返回：

```json
{
  "stdout": "命令输出",
  "stderr": "错误输出",
  "exit_code": 0
}
```

## 使用示例

### 获取电池信息

```bash
curl -X POST http://127.0.0.1:8765/api/exec \
  -H "Content-Type: application/json" \
  -d '{"cmd": "termux-battery-status"}'
```

### 发送通知

```bash
curl -X POST http://127.0.0.1:8765/api/exec \
  -H "Content-Type: application/json" \
  -d '{"cmd": "termux-notification -t \"标题\" -c \"内容\""}'
```

### 连接成功通知（AI 助手自动发送）

```bash
curl -X POST http://127.0.0.1:8765/api/exec \
  -H "Content-Type: application/json" \
  -d '{"cmd": "termux-notification -t \"✅ AI 助手连接成功\" -c \"已成功连接到 ZeroTermux API 服务！\" --priority high"}'
```

### 列出文件

```bash
curl -X POST http://127.0.0.1:8765/api/exec \
  -H "Content-Type: application/json" \
  -d '{"cmd": "ls -la /sdcard"}'
```

### 执行 Python 脚本

```bash
curl -X POST http://127.0.0.1:8765/api/exec \
  -H "Content-Type: application/json" \
  -d '{"cmd": "python3 -c \"print(1+1)\""}'
```

## 与 AI 助手配合使用

当 API 服务运行后，AI 助手可以通过 `http_request` 工具调用你的 API：

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "http_request",
    "arguments": {
      "url": "http://192.168.80.104:8765/api/exec",
      "method": "POST",
      "body": "{\"cmd\": \"termux-battery-status\"}",
      "content_type": "application/json"
    }
  }
}
```

### AI 助手连接流程

1. AI 助手通过 `http_request` 调用 `/ping` 端点验证服务可用性
2. 验证通过后，自动调用 `/api/exec` 发送连接成功通知
3. 之后所有操作都通过 `/api/exec` 执行命令

## 后台运行（Termux 原生环境，可选的附录）

> **在应用内不需要这一节**：工具 `skill_zero_termux_api_start` 本身就是后台常驻
> （`background: true`，立即返回 `bg_` 任务 ID），用 `bash_task_kill` 停止即可。
> 下面是在 **ZeroTermux 原生环境手工运行**服务时的做法，与 PRoot 工具路径互不影响
> （`pkg` / `nohup` / `tmux` 在 PRoot Debian 里不可用）。

```bash
# 使用 nohup
nohup python /storage/emulated/0/Work/flask_api.py > /dev/null 2>&1 &

# 或使用 tmux
pkg install tmux
tmux new -s api
python /storage/emulated/0/Work/flask_api.py
# Ctrl+B, D 脱离会话
# tmux attach -t api 重新连接
```

## 自动启动（同样仅适用于 Termux 原生手工运行）

在 ZeroTermux 的 `~/.bashrc` 中添加：

```bash
# 自动启动 API 服务
if ! pgrep -f flask_api.py > /dev/null; then
    cd /storage/emulated/0/Work
    nohup python flask_api.py > /tmp/flask_api.log 2>&1 &
fi
```

### 使用 termux-services（runit）自启动（推荐）

这种方式比 `~/.bashrc` 更可靠，服务由 Termux 的 runit 进程管理，Termux 启动时自动运行，退出 Termux 后服务仍然保持（只要系统不杀后台）。

**1. 安装 termux-services**
```bash
pkg install termux-services
```

**2. 创建服务目录和启动脚本**
```bash
mkdir -p ~/.termux/services/flask_api
cat > ~/.termux/services/flask_api/run << 'EOF'
#!/data/data/com.termux/files/usr/bin/bash
cd /storage/emulated/0/Work/zero-termux-api-skill/scripts
exec python flask_api.py 2>&1
EOF
chmod +x ~/.termux/services/flask_api/run
```

**3. 链接到系统服务目录**
```bash
ln -sf ~/.termux/services/flask_api $PREFIX/var/service/
```

**4. 启动服务**
```bash
sv up flask_api
```

**5. 检查状态**
```bash
sv status flask_api
```
应显示 `run: flask_api: (pid X) ...`

**服务管理命令：**
| 操作 | 命令 |
|------|------|
| 启动 | `sv up flask_api` |
| 停止 | `sv down flask_api` |
| 重启 | `sv restart flask_api` |
| 状态 | `sv status flask_api` |

**验证自启动：** 完全退出 Termux 后重新打开，运行 `sv status flask_api` 应显示 `run` 状态。


## 故障排除

### 端口被占用
```bash
# 查找占用进程
netstat -tulpn | grep 8765
# 杀掉进程
kill -9 PID
```

### termux-api 命令找不到
```bash
pkg install termux-api
```

### 权限被拒绝
```bash
# 给脚本执行权限
chmod +x /storage/emulated/0/Work/flask_api.py
```

## 安全提醒

⚠️ **注意**：此 API 允许远程执行任意命令，请勿在公共网络或不可信环境中使用。建议：

- 仅在局域网内使用
- 使用内网穿透时注意安全
- 可添加简单的 token 认证（修改 flask_api.py）


