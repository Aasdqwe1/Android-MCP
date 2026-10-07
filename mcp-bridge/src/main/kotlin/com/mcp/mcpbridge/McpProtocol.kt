package com.mcp.mcpbridge

/**
 * MCP 协议常量。
 *
 * 对齐 2026-07-28 版规范：无状态、每条请求自包含、取消强制握手。
 * 这里同时保留 initialize 与 server/discover 两条发现路径，以兼容旧客户端。
 */
object McpProtocol {
    /** 本实现支持的协议版本（最新规范）。 */
    const val PROTOCOL_VERSION = "2026-07-28"

    /** 兼容的旧版本列表（客户端协商时可回落）。 */
    val SUPPORTED_VERSIONS = listOf("2026-07-28", "2025-11-25", "2025-06-18")

    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_DISCOVER = "server/discover"
    const val METHOD_PING = "ping"
    const val METHOD_TOOLS_LIST = "tools/list"
    const val METHOD_TOOLS_CALL = "tools/call"
    const val NOTIFICATION_INITIALIZED = "notifications/initialized"
    const val NOTIFICATION_CANCELLED = "notifications/cancelled"
}
