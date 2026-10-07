package com.mcp

import android.content.Context
import com.mcp.serialization.McpJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** 一个远程 MCP 服务器配置。 */
@Serializable
data class McpRemoteServer(
    val id: String,
    val endpoint: String,
    val token: String = "",
    val enabled: Boolean = true,
    /**
     * 是否信任该服务器的自签名证书。
     *
     * 自托管 / 内网 / 本机 MCP 服务器常用自签名证书，其根不在系统信任库里，
     * 默认校验会抛 Trust anchor for certification path not found。
     * 按服务器粒度放开，避免全局关闭校验带来的中间人风险。
     */
    val trustSelfSigned: Boolean = false,
)

/** 远程 MCP 服务器列表持久化（SharedPreferences + JSON）。 */
object McpRemotePrefs {
    private const val PREF_FILE = "mcp_remote"
    private const val KEY_SERVERS = "servers"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun list(context: Context): List<McpRemoteServer> {
        val raw = prefs(context).getString(KEY_SERVERS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return runCatching { McpJson.decodeFromString<List<McpRemoteServer>>(raw) }
            .getOrDefault(emptyList())
    }

    fun save(context: Context, servers: List<McpRemoteServer>) {
        prefs(context).edit().putString(KEY_SERVERS, McpJson.encodeToString(servers)).apply()
    }

    fun add(context: Context, server: McpRemoteServer): List<McpRemoteServer> {
        val next = list(context).filter { it.id != server.id } + server
        save(context, next)
        return next
    }

    fun remove(context: Context, id: String): List<McpRemoteServer> {
        val next = list(context).filter { it.id != id }
        save(context, next)
        return next
    }

    /** 切换某个服务器的启用开关（关掉等于该服务不存在：不注册工具、不进提示词）。 */
    fun setEnabled(context: Context, id: String, enabled: Boolean): List<McpRemoteServer> {
        val next = list(context).map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(context, next)
        return next
    }
}
