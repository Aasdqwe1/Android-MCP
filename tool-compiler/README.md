# tool-compiler — 给 LLM 的自定义 MCP 工具编译器

把「普通 Kotlin 函数或几行 DSL」编译成 **喂给 LLM 的工具定义（JSON Schema）**，并负责解析 LLM 下发的调用、分发执行、把结果格式化回给 LLM。

- 纯 Kotlin/JVM（Kotlin 2.1.20 + kotlinx.serialization），**不依赖 Android SDK**，可在桌面直接 `run` / `test`。
- 两种「编译产物」格式：`toOpenAi()`（OpenAI / DeepSeek 兼容的 `tools`）与 `toMcp()`（Model Context Protocol 的 `tools`）。
- 两种定义方式，都对用户极简：
  - **DSL**：`tool("name") { ... }`，类型安全、支持 suspend。
  - **注解**：`@Tool` + `@Param` 标在普通函数上，零样板（用 `java.lang.reflect` 扫描，Android 兼容）。

---

## 1. 定义工具

### DSL 风格（推荐，支持 suspend / 复杂逻辑）

```kotlin
val calculator = tool("calculator") {
    description = "计算数学表达式，支持 + - * / %、括号与一元负号"
    string("expression") { description = "要计算的表达式，如 (2+3)*4" }
    handler { args ->
        val expr = args["expression"]!!.jsonPrimitive.content
        evalMath(expr).toString()
    }
}
```

支持的参数类型：`string / integer / number / boolean / array(element) / obj { ... }`，
每个参数可配 `description`、`required`（默认 true）、`enumValues`、`default(...)`。

### 注解风格（给已有函数加标签即可）

```kotlin
class MyTools {
    @Tool("greet", "向某人问好")
    fun greet(@Param(name = "name", description = "对方名字") name: String): String = "Hello, $name!"
}
// 注册
ToolScanner.scan(MyTools()).forEach { box.register(it) }
```

> 注解方法为**普通（非 suspend）函数**；suspend 函数请用 DSL。
> 参数名优先取 `@Param(name=)`，省略时取编译期参数名（本模块已开启 `-java-parameters`）。

---

## 2. 注册 + 编译 + 分发

```kotlin
val box = Toolbox().apply {
    registerAll(calculator(), currentTime())
    ToolScanner.scan(MyTools()).forEach { register(it) }
}

// 编译成喂给 LLM 的定义
val toolsJson = ToolCompiler.toOpenAi(box.all())   // OpenAI / DeepSeek function calling
val mcpJson   = ToolCompiler.toMcpToolsList(box.all()) // MCP tools/list

// LLM 决定调用时，框架负责解析参数、执行、格式化返回
runBlocking {
    val result = box.dispatch("calculator", """{"expression":"(2+3)*4-1"}""")
}
```

`dispatch` 对「未知工具 / 执行异常」都会捕获并返回 `{"error":"..."}` 文本，**不会让一次工具调用把整个会话打挂**。

---

## 3. 接入现有 DeepSeek 聊天

现有 `DeepSeekApi.sendMessage` 走的是逆向版私有端点 `/api/v0/chat/completion`，其请求体用
`prompt / thinking_enabled / search_enabled`，**原生并不支持 OpenAI 式 `tools`**。要让 LLM 真正「调用工具」，有两条路：

1. **官方 OpenAI 兼容端点**（推荐先做）
   把 `ToolCompiler.toOpenAi(box.all())` 作为 `tools` 字段塞进 DeepSeek 官方
   `POST /v1/chat/completions` 的 body，解析响应里的 `tool_calls`，用 `box.dispatch(...)`
   执行，再把结果作为 `role: "tool"` 消息回传，循环直到 LLM 不再调用工具。

2. **复用逆向端点**
   在 `sendMessage` 的 `body` 里附加自定义 `tools` 字段（需服务端接受，逆向协议目前未确认支持），
   并从 SSE 流里解析工具调用片段——属于适配层工作，建议等官方端点跑通后再做。

无论哪条路，本模块产出的 `tools` JSON 与 `dispatch` 都不用改，只是「把定义送进去、把调用接出来」的管道不同。

---

## 4. 构建 / 运行 / 测试

```bash
# 桌面独立构建（无需 Android SDK）：
./gradlew -c tool-compiler/settings.gradle.kts run     # 看编译出的工具定义 + 模拟调用
./gradlew -c tool-compiler/settings.gradle.kts test    # 跑单元测试

# 在 Android 工程内（需 Android SDK）：
./gradlew :tool-compiler:test
./gradlew :app:assembleDebug
```

`app` 已通过 `implementation(project(":tool-compiler"))` 依赖本模块。
