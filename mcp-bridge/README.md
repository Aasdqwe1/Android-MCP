# mcp-bridge — MCP 双向服务

把 AgentToolbox 的工具能力接入 **MCP（Model Context Protocol）**，双向打通：

- **Server 方向（发射）**：把本机 Toolbox 里的工具通过标准 MCP 协议暴露给外部客户端
  （Claude Desktop / Cursor / 自研 client）。
- **Client 方向（接收）**：连接远程 MCP 服务器，把对方的工具包装成本地 `ToolDef`
  注册进 Toolbox，与本地工具一同供 LLM 调用。

---

## 1. 模块定位

```
mcp-bridge/                      纯 Kotlin/JVM，不依赖 Android SDK，可独立 ./gradlew test
  src/main/kotlin/com/mcp/mcpbridge/
    jsonrpc/JsonRpc.kt           JSON-RPC 2.0 信封（request / response / error）
    McpProtocol.kt               协议常量与版本列表
    McpServer.kt                 协议核心：把 Toolbox 暴露成 MCP 服务
    McpEndpoint.kt               文本级端点：JSON 文本 <-> McpServer
    McpTransport.kt              传输接口 + 内存通道
    McpClient.kt                 MCP 客户端：拉取远程工具、包装为 ToolDef
    McpHttpSender.kt             HttpURLConnection 发送器（零依赖）
    JsonSchemaToParams.kt        JSON Schema -> 本地 ParamSpec
```

app 侧集成：

```
app/src/main/java/com/mcp/
  McpServerEndpoint.kt   Server 入口（复用 ToolRuntimeHolder 的 Toolbox）
  McpPrefs.kt            Server 开关 / Bearer token
  McpRemotePrefs.kt      远程服务器列表持久化
  McpRemoteRegistry.kt   远程连接管理 + 工具注册（双重过滤）
  McpTools.kt            mcp_list_servers / mcp_add_server / mcp_remove_server / mcp_refresh
  WebApiServer.kt        挂载 /mcp 路由
```

---

## 2. Server 方向：把本地工具发射出去

### 端点

`POST http://<ip>:<port>/mcp`，请求体是一段 JSON-RPC 2.0 文本。

支持的方法：

| 方法 | 说明 |
|---|---|
| `initialize` | 能力协商，返回 protocolVersion / capabilities / serverInfo |
| `server/discover` | 新版规范的可选发现端点，不握手也能拿到能力 |
| `tools/list` | 列出工具（复用 `ToolCompiler.toMcp`，产出 `{name, description, inputSchema}`） |
| `tools/call` | 调用工具（复用 `Toolbox.dispatchOrThrow`） |
| `ping` | 健康检查 |
| `notifications/*` | 通知，返回 202 无响应体 |

### 关键设计

- **零协议改动**：`McpServer` 只依赖 `Toolbox`，工具清单与执行全复用既有实现。
  因此 LLM 看到的工具集与 MCP 暴露的工具集**永远一致**（同一份 Toolbox）。
- **错误语义分层**：
  - 协议级错误（未知方法 / 参数缺失）→ JSON-RPC `error` 对象；
  - 工具级失败（handler 抛异常）→ `result.isError = true` + `content[]`，
    让客户端模型看到失败原因并可自行修正，而不是打断整条连接。
- **通知不响应**：无 `id` 的消息返回 202 Accepted，符合 JSON-RPC 2.0。

### 访问控制

`mcpRoute` 里三层 gate：

1. **总开关** `McpPrefs.isEnabled`（默认开；关闭后 /mcp 返回 404）；
2. **Origin 校验**（防 DNS 重绑定）：非浏览器客户端通常不带 Origin → 放行；
   浏览器带 Origin 时要求与 Host 同源或为本机，否则 403；
3. **Bearer token**（可配）：`McpPrefs.token` 非空时校验 `Authorization: Bearer <token>`。

> 传输绑定沿用 `WebApiServer` 的端口。如需限制只本机可访问，建议把服务绑定到
> `127.0.0.1`（规范建议），或配置 token 后仅在可信内网使用。

### 手动验证

```bash
# 能力协商
curl -s -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize"}'

# 列出工具
curl -s -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'

# 调用工具
curl -s -X POST http://127.0.0.1:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"calculator","arguments":{"expression":"(2+3)*4"}}}'

# 带 token（若配置了）
curl -s -X POST http://127.0.0.1:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":4,"method":"tools/list"}'
```

---

## 3. Client 方向：接收远程服务

### 流程

```
McpClient.initialize()   -> 握手
McpClient.listTools()    -> 拉取远程工具（自动翻页 nextCursor）
McpClient.wrapAsToolDefs -> 每个远程工具包成本地 ToolDef
                          命名：remote_<serverId>_<toolName>
McpRemoteRegistry        -> 按过滤注册进 Toolbox
```

### 可见性双重过滤

远程工具与本地工具一样，必须同时通过：

1. **`ToolPrefs` 全局开关**——用户可在「工具」页面逐项开关；
2. **预设白名单**——极简 / PTC 模式下，不在白名单的远程工具默认不可见。

预设切换时 `PresetRuntime.applyToolFilter` 会清空 toolbox 重扫，
随后调用 `remoteRegistry.reregisterFromCache()` 从缓存**纯内存**补回远程工具，
不触发网络请求。

### Agent 可用的管理工具

| 工具 | 说明 |
|---|---|
| `mcp_list_servers` | 列出已配置的远程服务器 |
| `mcp_add_server` | 添加服务器并立即拉取工具 |
| `mcp_remove_server` | 移除服务器并注销其工具 |
| `mcp_refresh` | 重新拉取（不传 id 则刷新全部） |

---

## 4. 测试

```bash
./gradlew :mcp-bridge:test
```

覆盖：

- `McpServerTest`：版本协商、tools/list 形状、tools/call 成功/失败、
  ping、未知方法、通知语义、`McpEndpoint` JSON 文本往返、`explicitNulls` 行为；
- `McpClientTest`：握手、工具拉取、调用、错误包装、ToolDef 包装与 schema 转换；
- `McpHttpRoundTripTest`：**HTTP 端到端**——JDK 内建 HttpServer 承载 /mcp，
  真实 HTTP 往返跑通 `McpServer -> McpEndpoint -> HTTP -> McpHttpSender -> McpClient -> ToolDef`
  全链路，含 Bearer token 拒绝路径。

---

## 5. 后续扩展路径

当前只实现 **Tools 原语**。MCP 还有若干原语与扩展，按需推进：

| 能力 | 落点 | 说明 |
|---|---|---|
| `Resources` | `McpServer.handle` 加 `resources/list` / `resources/read` | 把文件、会话、日志暴露为只读上下文 |
| `Prompts` | 加 `prompts/list` / `prompts/get` | 把预设 / 技能文档暴露为模板 |
| `Elicitation` | 需反向通道 | 服务器主动向用户索取补充信息，可接现有 `ask_user` |
| `Tasks` 扩展 | 接 `async_task_*` | 长任务异步化，避免阻塞 tools/call |
| `listChanged` 通知 | SSE 推送 | 工具集变化时主动通知已连接客户端 |
| stdio 传输 | 实现 `McpTransport` | 被外部进程拉起（Termux / 桌面）时使用 |
| OAuth 2.1 | 替换 Bearer | 规范对远程 MCP 认证的要求；内网场景可暂缓 |

传输层是接口化的（`McpTransport` / sender 函数），换实现不动协议层。

---

## 6. 设计对齐

- **协议无状态化**：对齐新版 MCP 规范（取消强制握手、移除会话 ID），
  每条请求自包含，无需维护连接状态机；同时保留 `initialize` 兼容旧客户端。
- **接缝模式**：传输层照搬 `CapabilitySeam` 的「接口 + Provider」写法，
  与项目既有的 fs / shell / subprocess 接缝风格一致。
- **单一事实来源**：Server 端直接复用 `ToolRuntimeHolder` 的 Toolbox，
  Client 端注册进同一个 Toolbox，因此 LLM 视角、MCP 暴露视角、
  用户开关视角三者天然一致，不会出现「开关关了但 MCP 还能调」这类割裂。
