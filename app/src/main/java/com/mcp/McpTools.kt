package com.mcp

import android.content.Context
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.serialization.json.jsonPrimitive

/** MCP 远程连接管理工具（Client 方向的用户入口）。 */
fun mcpTools(context: Context, registryProvider: () -> McpRemoteRegistry): List<ToolDef> = listOf(
    tool("mcp_list_servers") {
        description = "列出已配置的远程 MCP 服务器及其连接状态。"
        handler { _ ->
            val reg = registryProvider()
            val servers = reg.servers()
            if (servers.isEmpty()) return@handler "尚未配置任何远程 MCP 服务器。"
            servers.joinToString("\n") { s ->
                val mark = if (s.enabled) "启用" else "禁用"
                "- " + s.id + " (" + mark + "): " + s.endpoint
            }
        }
    },
    tool("mcp_add_server") {
        description = "添加一个远程 MCP 服务器并立即拉取其工具，注册为本地 remote_<id>_<tool>。"
        string("id") { description = "服务器标识（用于工具名前缀），如 github" }
        string("endpoint") { description = "MCP 端点 URL，如 http://192.168.1.10:3000/mcp" }
        string("token") { description = "可选的 Bearer token"; required = false }
        handler { args ->
            val id = args["id"]?.jsonPrimitive?.content ?: return@handler "缺少 id"
            val endpoint = args["endpoint"]?.jsonPrimitive?.content ?: return@handler "缺少 endpoint"
            val token = args["token"]?.jsonPrimitive?.content ?: ""
            val reg = registryProvider()
            val trust = args["trust_self_signed"]?.jsonPrimitive?.content?.toBoolean() ?: false
            reg.addServer(id, endpoint, token, trust)
            "已添加并拉取: " + id + "，当前注册 " + reg.registeredCount() + " 个远程工具。"
        }
    },
    tool("mcp_remove_server") {
        description = "移除一个远程 MCP 服务器并注销其全部远程工具。"
        string("id") { description = "服务器标识" }
        handler { args ->
            val id = args["id"]?.jsonPrimitive?.content ?: return@handler "缺少 id"
            registryProvider().removeServer(id)
            "已移除: " + id
        }
    },
    tool("mcp_refresh") {
        description = "重新拉取远程 MCP 服务器工具清单（不传 id 则刷新全部）。"
        string("id") { description = "服务器标识（可选）"; required = false }
        handler { args ->
            val reg = registryProvider()
            val id = args["id"]?.jsonPrimitive?.content
            if (id.isNullOrBlank()) reg.refreshAll() else reg.refresh(id)
            "刷新完成，当前注册 " + reg.registeredCount() + " 个远程工具。"
        }
    },
)
