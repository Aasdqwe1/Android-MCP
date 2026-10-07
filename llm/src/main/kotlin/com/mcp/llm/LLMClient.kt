package com.mcp.llm

import com.mcp.core.llm.BackendType
import com.mcp.llm.MessageEvent
import com.mcp.toolbox.ToolDef
import kotlinx.coroutines.flow.Flow

/**
 * 统一 LLM 后端类型。
 *
 * - [DEEPSEEK_REVERSE]: DeepSeek Android 逆向协议（服务端会话态，需 token + sessionId）。
 * - [OPENAI]: OpenAI 兼容协议（`/v1/chat/completions`，baseUrl 可配置）。
 *   借助可配置的 [baseUrl] 与 [apiKey]，同一套实现即可覆盖
 *   OpenAI / Azure OpenAI / Ollama / vLLM / LM Studio / Groq / OpenRouter / 本地推理服务
 *   等"其他模型调用协议"。
 */
/**
 * 统一 LLM 客户端接口：屏蔽不同后端的协议差异，
 * 向上层（ChatBridge）提供一致的 [sendMessage] 流式事件 [MessageEvent]。
 */
interface LLMClient {
    /** 发送一次对话请求，返回流式事件。 */
    suspend fun sendMessage(request: LLMRequest): Flow<MessageEvent>

    /**
     * 重新生成某条已有的助手回复，返回流式事件。
     *
     * 两个后端语义不同：
     *  - DeepSeek 逆向协议：服务端有消息树，走专用端点 `POST /chat/regenerate`，
     *    以 [childMessageId]（被重新生成的那条助手消息 id）为锚点，在同一父节点下
     *    新建兄弟分支——这样客户端分支树才能与服务端严格对齐。
     *  - OpenAI 兼容协议：无服务端状态，也没有 regenerate 端点，"重新生成"等价于
     *    把截断到该助手消息之前的上下文再发一次；由上层构造好 [request] 后走
     *    这里的默认实现（即普通 completion）即可，故默认委托给 [sendMessage]。
     *
     * @param childMessageId 被重新生成的助手消息 id（OpenAI 侧忽略）
     */
    suspend fun regenerate(request: LLMRequest, childMessageId: String): Flow<MessageEvent> =
        sendMessage(request)

    /**
     * 编辑某条已发出的用户消息并重新提问，返回流式事件。
     *
     * 两个后端语义不同：
     *  - DeepSeek 逆向协议：走专用端点 `POST /chat/edit_message`，以 [messageId]
     *    （被编辑的用户消息 id）为锚点，在其父节点下新建「新提问 + 新回复」的兄弟分支，
     *    原提问及其子树保留为另一分支。
     *  - OpenAI 兼容协议：无服务端状态，等价于把上下文截断到被编辑消息之前、
     *    再发送新文案；由上层构造好 [request] 后走默认实现即可。
     *
     * @param messageId 被编辑的用户消息 id（OpenAI 侧忽略）
     */
    suspend fun editMessage(request: LLMRequest, messageId: String): Flow<MessageEvent> =
        sendMessage(request)

    /** 是否已具备调用该后端的认证条件。 */
    suspend fun isAuthenticated(): Boolean

    /** 后端类型（用于上层分支构造请求）。 */
    val backendType: BackendType

    /**
     * 流式通信原始报文抓取（Token 看板明细「请求/响应」用）。
     * 调用方在 [sendMessage] 前把回调设到 client 实例；底层在
     *  - [RawCapture.onRequest]：实际发出的完整 POST 请求体（JSON 字符串）
     *  - [RawCapture.onResponseChunk]：每个 SSE `data:` 帧（已剥离前缀的纯 JSON，[DONE] 除外）
     * 回调原始报文；结束后应置 null 避免泄漏。
     * 默认实现返回 null（不采集，零开销）；需采集的后端（OpenAI 兼容）自行 override。
     */
    var rawCapture: RawCapture?
        get() = null
        set(_) {}

    /**
     * 停止正在进行的流式生成（Stop 按钮）。best-effort。
     *
     * 两个后端的"停止"语义不同：
     *  - DeepSeek 逆向协议：服务端有会话态，需调用 `POST /chat/stop_stream`
     *    （携带 chat_session_id + response_message_id）让服务端真正中断生成，
     *    见 [com.mcp.deepseek.DeepSeekApi.stopStream] 与 DeepSeekReverseClient 的重写。
     *  - OpenAI 兼容协议：无服务端会话态、也没有 stop 端点；"停止"纯靠客户端断开
     *    SSE 连接实现——由 ChatBridge.stopGeneration() 取消 collect 协程触发，
     *    底层 channelFlow 生产者被取消后其 finally 会 conn.disconnect() 关闭 HTTP 流。
     *    因此该默认空实现对 OpenAI 是正确的（请勿补一个不存在的 stop_stream 调用）。
     *
     * @param token 认证 token
     * @param sessionId 会话 id（DeepSeek 逆向协议的 chat_session_id）
     * @param messageId 当前正在生成消息的 id（response_message_id；OpenAI 侧恒为空）
     */
    suspend fun stopStream(token: String, sessionId: String, messageId: String) {}
}

/** 一次 LLM 调用的请求。 */
data class LLMRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDef>? = null,
    val config: LLMConfig
)

/** 单条对话消息（OpenAI 风格）。 */
data class ChatMessage(
    val role: String,                       // "system" / "user" / "assistant" / "tool"
    val content: String? = null,
    /**
     * 上一轮 assistant 的思考内容（DeepSeek thinking 模式）。回放 tool-call 轮时作为
     * `reasoning_content` 原样发回上游——这是 DeepSeek 思考模式 + function calling 的硬性要求
     * （guides/thinking_mode.md § Tool Calls），缺失会被上游拒绝或丢失思考签名。
     */
    val reasoning: String? = null,
    val name: String? = null,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    /**
     * 多模态图片内容（OpenAI 多模态消息格式）。
     * 每项为 `data:image/...;base64,...` 或 http(s) 图片 URL；非空时该消息的
     * `content` 会被渲染成 `[{type:"text",text:...}, {type:"image_url",image_url:{url:...}}, ...]`
     * 数组（text 与 image 并存）。仅 [BackendType.OPENAI] 分支会序列化；DEEPSEEK_REVERSE 忽略。
     */
    val imageUrls: List<String>? = null
)

/** OpenAI function calling 工具调用（流式增量累积后的完整调用）。 */
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolCallFunction
)

data class ToolCallFunction(
    val name: String,
    val arguments: String
)

/**
 * 调用配置：同时兼顾 DeepSeek 逆向协议与 OpenAI 兼容协议。
 *
 * - DeepSeek 逆向协议使用 [sessionId] + [parentMessageId]（服务端会话态），
 *   并依赖 [thinkingEnabled] / [searchEnabled]。
 * - OpenAI 兼容协议使用 [model] / [temperature] / [maxTokens] / [stream]。
 */
data class LLMConfig(
    val model: String = "gpt-4o",
    val temperature: Double = 0.7,
    val maxTokens: Int = 4096,
    val stream: Boolean = true,
    // —— DeepSeek 逆向协议专用 ——
    val sessionId: String? = null,
    val parentMessageId: String? = null,
    val thinkingEnabled: Boolean = true,
    val searchEnabled: Boolean = false,
    /** DeepSeek 逆向协议的 `ref_file_ids`：本轮引用的已上传文件 id（图片 / 文档等）。 */
    val refFileIds: List<String>? = null,
    /** 标准 OpenAI response_format 的 type 值（如 "json_object"）；null 表示不指定，向后兼容。 */
    val responseFormat: String? = null
)

/**
 * 流式通信原始报文抓取回调（见 [LLMClient.rawCapture]）。
 * 由调用方实现并挂到 client 实例，用于把底层真实 HTTP 报文回传给 Token 看板明细。
 */
interface RawCapture {
    /** 实际发出的完整 POST 请求体（JSON 字符串）。 */
    fun onRequest(json: String)
    /** 每个 SSE `data:` 帧（已剥离 `data:` 前缀的纯 JSON 字符串）。 */
    fun onResponseChunk(json: String)
}
