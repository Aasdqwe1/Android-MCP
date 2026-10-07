# AGENTS.md

> 给在这个仓库里干活的 AI Agent（以及三个月后的你自己）的说明书。
> 这是**契约**，不是介绍。动手改行为前先读完，冲突时以本文件为准。

---

## 0. 一句话定位

**AgentToolbox** — 把完整的 AI Agent 执行环境塞进一台 Android 手机。
单 App 内同时具备：LLM 多后端客户端、PRoot Debian 执行环境、100+ 工具、多 Agent 编排、内嵌浏览器、技能系统。

约束条件决定了几乎所有设计取舍：**无 root（可选）、API 24+、arm64、内存受限、包体受限**。
任何「在服务器上很优雅」的方案，在这里先问一句：34MB 的 rootfs 放得下吗？低端机跑得动吗？

---

## 1. 核心原则

### 1.1 注释即设计文档
这个仓库的注释密度高于常规。很多注释在写「为什么这么改、不这么改会怎样、**别改回去**」。
看到这类注释，先理解再动手；确实要推翻，请在 commit message 里写明推翻的理由。

### 1.2 单一事实源（Single Source of Truth）
同一个判断**绝不允许写两处**。已有先例：
- `backendCredentialsSatisfied()` — 曾经 ChatBridge 与 WebApiServer 各写一份，修一处漏一处
- `usesTextToolProtocol()` — 曾经内联在 buildToolResult，UI 再写一份就分叉

新增类似判断时，抽成函数放 `core`，两个消费点都调它。

### 1.3 依赖方向靠注入反转，不靠打破分层
上层实现要注入下层时，用 `var xxxProvider: (() -> T)?` 这类 provider 字段，
由 `:app` 在启动时注册。已有先例：
- `LLMClientFactory.webAutomationDriverProvider` — :llm 不能依赖 :app 的 WebBrowser
- `LLMClientFactory.litertClientProvider` — :llm 不依赖 :litert（后者带 native 库、体积大）

**不要**为了图方便让下层 `import` 上层。

### 1.4 错误必须可读，不许静默失败
兜底实现返回**明确的可读错误**，不抛 NPE。见 `NoopWebAutomationDriver` / `NoopLiteRtClient`。
工具执行失败一律返回 `{"error":"..."}` 文本，不让一次失败打挂整个会话。

### 1.5 诚实高于好看
做不到就说做不到，失败就说失败。禁止把失败包装成成功、禁止用兜底分支掩盖同步问题。

---

## 2. 仓库结构与依赖方向（硬约束）

### 2.1 七个模块（README 只写了 5 个，以本节为准）

```
:app            Android 应用层：UI / 工具实现 / Agent 编排 / 浏览器 / 微信 / 浮窗
:data           本地持久化：LocalStore + JSONL 后端 + 会话事件日志
:core           会话模型、提示词组合、技能仓库（不依赖 Android UI）
:llm            LLM 后端与协议适配（DeepSeek 逆向 / OpenAI 兼容 / Web 自动化）
:tool-compiler  工具编译器（纯 Kotlin/JVM，无 Android 依赖，可桌面直接跑测试）
:mcp-bridge     MCP 双向：本地工具发射（Server）+ 远程工具接收（Client）
:litert         本地模型后端（LiteRT-LM，native 库，minSdk 单独抬到 26）
```

### 2.2 依赖方向

```
:app ──→ :data ──→ :core ──→ :tool-compiler
  │                   ↑
  ├──→ :llm ──────────┘
  ├──→ :mcp-bridge ──→ :tool-compiler
  ├──→ :litert ──→ :llm ──→ :core
  └──→ :mnn ──→ :llm ──→ :core
```

**禁止反向依赖**。特别地：
- `:core` 不得 import `:app` 或 `:llm` 的任何东西
- `:llm` 不得 import `:app` / `:litert` / `:mnn`（本地推理后端只由 app 层注入配置）
- `:tool-compiler` 不得 import Android SDK（这是它能桌面跑测试的前提）

### 2.3 已知的平台约束

- `:litert` 的 `minSdk = 26`，其余模块 `24`。改 `:app` 的 minSdk 或给 litert 加依赖前，先确认 manifest 合并不会出问题。
- 编译/目标 SDK：`compileSdk = 36`，`targetSdk = 36`（`docs/REPACK_TARGETSDK28.md` 记录了降级重打包的完整流程）
- 只打 `arm64-v8a`（`abiFilters`），不要引入需要其他 ABI 的 native 依赖
- 单次构建超时 30 分钟（CI `timeout-minutes: 30`）

---

## 3. 常用命令

```bash
# 构建 Debug APK
./gradlew assembleDebug

# 单元测试
./gradlew test

# 静态门（CI 第一步，秒级，先于编译跑）
python3 scripts/kotlin_sanity_check.py --all
python3 scripts/architecture_check.py        # 依赖方向硬门
```

CI（`.github/workflows/build.yml`）在 push 到 main 时：
1. Kotlin sanity check
2. `assembleDebug`
3. 发布到本仓库 `latest-build` 预发布
4. 发布到 `Aasdqwe1/agent-toolbox` 的 `kotlin-latest-build`

**本地改动提交前，至少跑一遍 sanity check。**

---

## 4. 不许动的东西（契约）

### 4.1 工具调用协议
两套协议**解析层都认**，提示词层只决定「教模型用哪套」：
- **行式**：`tool_call: 名字` + `id:` + `参数名: 值`，多行参数用 `<<<` / `>>>` 定界块
- **XML**：`DSML invoke` / `parameter` 标签（竖线为全角 U+FF5C）

改动点：`core/prompt/SystemPromptComposer.kt` 的 `lineProtocolGuide()` / `xmlProtocolGuide()`。
测试：`core/src/test/.../PtcProtocolGuideTest.kt`。

**注意**：PTC 模式下**不能**注入通用行式指南 —— 它的示例是直调工具，而 PTC_ONLY_RULE 禁止直调，
会造成「示例教 A、约束禁 A」的自相矛盾。这是踩过的坑。

### 4.2 PTC 的段序契约
`ptcProtocolSections()` 给出的顺序是契约：**硬约束 → 协议指南 → 程序内工具 SDK**。
两个后端（DeepSeek 路径 / OpenAI 路径）必须走同一个函数，保证同一预设下段序一致。

### 4.3 预设语义
`app/src/main/assets/presets/*.json` 的字段含义：
- `ptc: true` — 只暴露 `run_code`，其余工具经 SDK 调用
- `complete: false` — 屏蔽 Agent.md / 待办 / 操作历史的注入
- `restricted` + `allowedTools` — 白名单模式（技能清单与 MCP 清单的注入条件依赖它）
- `protocolStyle` — `line` 或 `xml`

`systemPromptOverride` 非空时**整体替换**默认模板（不是追加）。极简模式靠这个把长提示词砍掉。

### 4.4 会话 ID 空间
`ChatBridge` 里有本地 ID ↔ 服务端 ID 的映射（`MessageIdSpace`、`idOffsetFor`、`toLocalId` / `toRawId`）。
DeepSeek 逆向有服务端消息树，OpenAI 无状态 —— **两套 ID 体系必须共存**。
动这块前先想清楚：哪个后端会走这条路径？另一个后端会不会因此错位？

### 4.5 工具定义方式
用 `tool("name") { ... }` DSL（`tool-compiler` 模块），不要手写 JSON Schema。
编译产物有两份：`toOpenAi()` 与 `toMcp()`，都要保持可用。

### 4.6 签名配置
`app/build.gradle.kts` 里 `signingConfigs.debug` 指向**提交进仓库的** `ci-debug.keystore`。
这是为了让 CI 每次用同一个 key，用户能覆盖安装。**不要**改成 runner 自动生成的 debug key。

---

## 5. 安全提示

### 5.1 git remote 里的凭据
如果 `.git/config` 里的 remote URL 带着 `github_pat_...` 明文，那是泄露风险。
正确做法：remote URL 不含凭据，凭据交给 credential helper。

### 5.2 不要把真实密钥写进仓库
`skill.json` 的 params 里如果有 token 类参数（如 github-actions 技能的 `token`），
**不要**在文档、示例、提交里填真实值。

---

## 6. 已知技术债 / 待决问题

| 项 | 说明 |
|---|---|
| `ChatBridge.kt` ≈ 270KB | 单文件承载会话调度、ID 管理、压缩、流式、工具分发。拆分需谨慎，先确认没有跨函数的隐式状态耦合 |
| ~~README 模块数不符~~ | 已于 2026-09-21 解决：README 补齐 7 个模块、结构树与文档入口表 |
| `app/` 顶层 65 个文件 | 部分可下沉到子包 |
| ~~无架构强制校验~~ | 已于 2026-09-21 解决：`scripts/architecture_check.py` 把 §2.2 变成 CI 硬门 |

---

## 7. 改代码时的固定动作

1. **先定位、再读原文**：不要凭印象改。读文件时记下校验值。
2. **最小修改**：只动该动的地方，不顺手重构、不加无关注释。
3. **同文件多处改动一次提交**（`multi_edit` 原子性：全部成功才写盘）。
4. **改完回读验证**。
5. **行为改动补测试**；测试放对应模块的 `src/test/`。
6. **提交信息**：`type(scope): 中文描述`，type 用 `feat` / `fix` / `chore` / `revert` / `refactor`。
   推翻既有设计时，在正文写清「为什么推翻」。

---

## 8. 文档地图

| 文档 | 内容 |
|---|---|
| `README.md` | 项目概览（模块数已过时，以本文件 §2.1 为准） |
| `docs/api-forward-design.md` | OpenAI 兼容转发的全链路审核与重设计（66KB，当前最大设计文档） |
| `docs/BROWSER_TOOL_REVIEW.md` | 浏览器工具审查报告（P0/P1 问题清单） |
| `docs/BROWSER_TOOL_ISSUES.md` | 浏览器工具问题跟踪表 |
| `docs/RESEARCH_MCP_BROWSER_DEVTOOLS.md` | 浏览器 + DevTools MCP 生态调研 |
| `docs/litert-backend-design.md` | LiteRT 本地模型后端方案 |
| `docs/oauth-connector-design.md` | OAuth Connector 框架（未跟踪，待提交） |
| `docs/REPACK_TARGETSDK28.md` | 降 targetSdk 以支持无 root 跑 PRoot 的完整流程 |
| `_research/` | 浏览器 CDP 方案的原始调研材料与许可证存档 |
| `tool-compiler/README.md` | 工具编译器 API 详解 |
| `scripts/README.md` | 构建辅助脚本说明 |
