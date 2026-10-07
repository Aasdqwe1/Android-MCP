# ZeroTermux API 快速参考

## 常用命令

| 命令 | 说明 |
|------|------|
| `skill_zero_termux_api_start(port="8765")` | 启动 API 服务（**后台常驻**，返回 `bg_` 任务 ID；port 省略即 8765） |
| `skill_zero_termux_api_status(port="8765")` | 检查服务是否运行（curl `/health`；端口要与 start 一致） |

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/exec` | POST | 执行 Shell 命令 |
| `/api/file` | GET/POST | 读写文件 |
| `/api/device` | GET | 获取设备信息 |
| `/health` | GET | 健康检查 |

## 快速开始

```bash
# 启动服务（端口为位置参数，默认 8765；工具模式下由 start 工具后台承载）
python3 scripts/flask_api.py 8765

# 健康检查
curl http://localhost:8765/health

# 执行命令
curl -X POST http://localhost:8765/api/exec -H "Content-Type: application/json" -d '{"cmd":"echo hello"}'
```

## 环境要求

- 宿主自动安装：`python3`（apt-requirements.txt）+ `flask`（requirements.txt），**无需手动 pip/pkg**
- 文件读写走 PRoot 内路径（宿主 `filesDir` 映射为 `/host/app`）
