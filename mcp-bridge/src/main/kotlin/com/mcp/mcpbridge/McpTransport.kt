package com.mcp.mcpbridge

/**
 * MCP 传输层接口 —— 与 [McpEndpoint] 对称的「接缝」设计（对齐 CapabilitySeam）。
 *
 * 协议核心（McpServer / McpEndpoint）不感知具体怎么收发，只依赖本接口；
 * 换 stdio / HTTP / SSE / 内存管道都只是替换实现，不动协议层。
 */
interface McpTransport {
    /** 传输标识（日志 / 诊断用）。 */
    val id: String

    /** 启动传输（绑定端口 / 拉起子进程 / 打开读写流）。 */
    fun start()

    /** 停止传输并释放资源。 */
    fun stop()
}

/**
 * 双向文本管道：MCP 的一条「连接」抽象。
 *
 * 对于 stdio 是一条子进程管道；对于 Streamable HTTP 是若干次请求-响应；
 * 对于内存测试是一对队列。收发都是 UTF-8 JSON 文本。
 */
interface McpChannel {
    /** 读取下一条消息；返回 null 表示对端已关闭。 */
    suspend fun receive(): String?

    /** 发送一条消息（通知场景）。 */
    suspend fun send(text: String)
}

/**
 * 记忆体通道：把一对请求-响应直接接起来，用于单元测试与进程内直连。
 *
 * [handle] 由调用方注入（通常是 McpEndpoint::handleJson），
 * 这样通道完全不依赖传输细节，只做「文本进 -> 文本出」。
 */
class InMemoryChannel(
    private val handle: suspend (String) -> String?
) : McpChannel {
    private val incoming = ArrayDeque<String>()
    private val outgoing = ArrayDeque<String>()

    /** 投递一条入站消息（模拟对端发来的请求）。 */
    fun push(text: String) { incoming.addLast(text) }

    /** 取走一条出站消息（模拟本端发回的响应）。 */
    fun pop(): String? = outgoing.removeFirstOrNull()

    override suspend fun receive(): String? = incoming.removeFirstOrNull()

    override suspend fun send(text: String) { outgoing.addLast(text) }

    /** 便捷：投递一条请求并立即处理，返回响应文本。 */
    suspend fun roundTrip(request: String): String? = handle(request)
}
