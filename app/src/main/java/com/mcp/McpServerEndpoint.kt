package com.mcp

import android.content.Context
import com.mcp.composition.ToolRuntimeHolder
import com.mcp.deepseek.AuthPrefs
import com.mcp.mcpbridge.McpEndpoint
import com.mcp.mcpbridge.McpServer
import kotlinx.coroutines.runBlocking

/**
 * app 侧 MCP 服务端入口：把应用 Toolbox 通过 MCP 协议暴露出去。
 *
 * 单例惰性构建 [McpEndpoint]，复用 [ToolRuntimeHolder] 里已组装好的 Toolbox——
 * 即「当前 ToolPrefs 开关 ∩ 预设白名单」后的工具集合，与 LLM 看到的完全一致。
 * 这样用户改工具开关/切预设后，MCP 暴露面自动跟随，无需额外同步。
 */
object McpServerEndpoint {

    @Volatile
    private var cached: McpEndpoint? = null

    /** 取（或惰性构建）MCP 端点。 */
    fun get(context: Context, auth: AuthPrefs): McpEndpoint {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: run {
                val toolbox = ToolRuntimeHolder.get(context, auth).toolbox
                val server = McpServer(
                    toolbox = toolbox,
                    serverName = "agent-toolbox",
                    serverVersion = com.mcp.skill.AppVersion.CURRENT,
                )
                McpEndpoint(server).also { cached = it }
            }
        }
    }

    /**
     * 处理一段 MCP JSON-RPC 文本。
     * @return 响应文本；null 表示通知，无需回写。
     */
    fun handle(context: Context, auth: AuthPrefs, text: String): String? =
        runBlocking { get(context, auth).handleJson(text) }

    /** 工具集变更（重扫技能 / 切预设）后失效缓存，下次请求重建。 */
    fun invalidate() {
        synchronized(this) { cached = null }
    }
}
