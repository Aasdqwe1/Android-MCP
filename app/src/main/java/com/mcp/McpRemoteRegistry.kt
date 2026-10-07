package com.mcp

import android.content.Context
import com.mcp.mcpbridge.McpClient
import com.mcp.mcpbridge.McpHttpSender
import com.mcp.preset.PresetRuntime
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.Toolbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程 MCP 连接管理器 —— Client 方向：接收远程工具并注册进本地 Toolbox。
 *
 * 设计要点：
 *  - 工具注册受「ToolPrefs 开关 ∩ 预设白名单」双重过滤（与本地工具一致）；
 *  - ToolDef 按服务器缓存，[reregisterFromCache] 供预设重扫后补回（不重复网络请求）；
 *  - 网络拉取是 suspend（[refresh]），绝不在主线程/组合根里同步连接，避免启动卡顿。
 */
class McpRemoteRegistry(
    private val context: Context,
    private val toolbox: Toolbox,
) {
    /** 服务器 id -> 缓存的远程 ToolDef（含被开关挡下的，便于开关变化后重注册）。 */
    private val defsByServer = LinkedHashMap<String, List<ToolDef>>()
    /** 服务器 id -> 当前已注册进 toolbox 的工具名。 */
    private val registeredByServer = LinkedHashMap<String, MutableList<String>>()

    fun servers(): List<McpRemoteServer> = McpRemotePrefs.list(context)

    /** 添加/更新服务器并立即拉取（后台线程，由调用方保证）。 */
    suspend fun addServer(id: String, endpoint: String, token: String = "", trustSelfSigned: Boolean = false) {
        McpRemotePrefs.add(context, McpRemoteServer(id, endpoint, token, true, trustSelfSigned))
        refresh(id)
        notifyTopologyChanged()
    }

    /** 移除服务器：注销其工具 + 清缓存 + 删配置。 */
    fun removeServer(id: String) {
        unregister(id)
        defsByServer.remove(id)
        McpRemotePrefs.remove(context, id)
        // 一并清掉轮询相关状态，避免残留（尤其 lastToolNames，否则重新添加同名服务器会漏注册）
        lastToolNames.remove(id)
        lastSeenAt.remove(id)
        lastErrors.remove(id)
        pollStats.remove(id)
        notifyTopologyChanged()
    }

    /** 刷新全部启用服务器。 */
    suspend fun refreshAll() {
        servers().filter { it.enabled }.forEach { refresh(it.id) }
    }

    /** 连接指定服务器，拉取工具并（按过滤）注册。 */
    suspend fun refresh(serverId: String) {
        val server = servers().firstOrNull { it.id == serverId } ?: return
        // 开关关闭 = 服务不存在：注销其工具，且不发起任何网络请求。
        if (!server.enabled) {
            unregister(serverId)
            defsByServer.remove(serverId)
            lastToolNames.remove(serverId)
            lastErrors.remove(serverId)
            lastSeenAt.remove(serverId)
            notifyTopologyChanged()
            return
        }
        unregister(serverId)
        withContext(Dispatchers.IO) {
            runCatching {
                val sender = McpHttpSender(server.endpoint, server.token, trustSelfSigned = server.trustSelfSigned)
                val client = McpClient(server.id, sender.asSender())
                val remote = client.listTools()
                val defs = client.wrapAsToolDefs(remote)
                defsByServer[serverId] = defs
                registerFiltered(serverId, defs)
                lastErrors.remove(server.id)
                // 同步轮询基线：否则轮询器首次探测会误判「工具集变化」而重复注册一遍。
                lastToolNames[serverId] = remote.map { it.name }.toSet()
                lastSeenAt[serverId] = System.currentTimeMillis()
                // refresh 是用户显式动作（添加/手动刷新），工具集大概率变了，
                // 统一通知一次；重复通知无害（重置标记是幂等的）。
                notifyTopologyChanged()
            }.onFailure { e ->
                lastErrors[server.id] = e.message ?: "连接失败"
                LogStore.w("MCP", "远程服务器 ${server.id} 拉取失败: ${e.message}")
            }
        }
    }

    /** 预设重扫后补回缓存中的远程工具（纯内存操作，不联网）。 */
    fun reregisterFromCache() {
        val enabledIds = servers().filter { it.enabled }.map { it.id }.toSet()
        defsByServer.forEach { (serverId, defs) ->
            registeredByServer[serverId] = mutableListOf()
            if (serverId !in enabledIds) return@forEach
            registerFiltered(serverId, defs)
        }
    }

    /** 已注册的远程工具总数（诊断用）。 */
    fun registeredCount(): Int = registeredByServer.values.sumOf { it.size }

    /** 某服务器当前已注册的工具数（UI 展示用）。 */
    fun toolCountFor(serverId: String): Int = registeredByServer[serverId]?.size ?: 0

    /** 某服务器拉取到的工具总数（含被开关挡下的）。 */
    fun fetchedCountFor(serverId: String): Int = defsByServer[serverId]?.size ?: 0

    /** 上次拉取是否失败（UI 展示连接状态用）。 */
    fun lastErrorFor(serverId: String): String? = lastErrors[serverId]

    private val lastErrors = LinkedHashMap<String, String>()

    // ───────── 周期探测 ─────────

    /** 探测作用域（惰性创建，stopPolling 时取消）。 */
    private var pollScope: CoroutineScope? = null
    private var pollJob: Job? = null

    /** 服务器 id -> 上次成功探测时间戳（毫秒）。 */
    private val lastSeenAt = LinkedHashMap<String, Long>()
    /** 服务器 id -> 上次探测到的工具名集合（用于「变了才重注册」）。 */
    private val lastToolNames = LinkedHashMap<String, Set<String>>()
    /** 服务器 id -> 累计探测次数 / 失败次数（诊断用）。 */
    private val pollStats = LinkedHashMap<String, Pair<Int, Int>>()

    /**
     * 拓扑变更回调：服务器增删、或某服务器的工具集发生变化时触发。
     *
     * 用途：DeepSeek 逆向协议只在**会话首条消息**注入工具清单，中途新增/移除远程服务
     * 时模型看不到更新。ChatBridge 在这里挂一个「重置 sessionHistoryLoaded」的回调，
     * 下一条消息就会重新注入最新清单。
     *
     * 注意：回调可能在 IO 线程触发，实现方需自行切主线程。
     */
    @Volatile
    var onTopologyChanged: (() -> Unit)? = null

    private fun notifyTopologyChanged() {
        runCatching { onTopologyChanged?.invoke() }
    }

    /** 上次成功探测时间；未探测过返回 null。 */
    fun lastSeenFor(serverId: String): Long? = lastSeenAt[serverId]

    /**
     * 生成给 LLM 的远程 MCP 服务说明（Markdown）。
     *
     * 目的：让模型知道「有哪些远程服务、各自暴露什么工具、端点在哪、当前是否可用」。
     * 工具名用 remote_<id>_<tool> 形式列出，模型可直接按行式协议调用——
     * 这些工具已注册进 Toolbox，与本地工具同等对待。
     *
     * 无远程服务时返回空串（调用方据此跳过注入）。
     */
    fun describeForPrompt(): String {
        // 极简模式（受限且非 PTC）：白名单不含 remote_*，远程工具根本没注册，
        // 不应注入 MCP 清单——否则模型会以为自己能调远程工具。
        // PTC 模式虽受限，但远程工具作为「程序内可见工具」经 run_code 调起，仍须注入服务+工具。
        val preset = PresetRuntime.current
        if (preset?.isRestricted == true && !preset.ptc) return ""
        // 只注入「开关打开 且 在线 且 有可用工具」的服务器：
        //   - 开关关闭 = 服务不存在（工具已注销）；
        //   - 未探测到 / 连接失败 = 服务不可用；
        //   - 可用工具为 0 = 即使注入模型也无工具可调。
        val servers = servers().filter { s ->
            s.enabled && lastSeenAt[s.id] != null && toolCountFor(s.id) > 0
        }
        if (servers.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## 远程 MCP 服务\n")
        sb.append("以下远程服务已接入，其工具已注册到你的工具清单（前缀 remote_<服务>_）。")
            .append("直接按普通工具调用即可，系统会转发到对应端点执行。\n")
        servers.forEach { s ->
            val available = toolCountFor(s.id)
            sb.append("\n### ").append(s.id).append("\n")
            sb.append("- 端点: ").append(s.endpoint).append("\n")
            sb.append("- 鉴权: ").append(if (s.token.isNotEmpty()) "Bearer token 已配置" else "无").append("\n")
            sb.append("- 状态: 在线，可用 ").append(available).append(" 个工具\n")
            // 工具名按「当前可用工具」（实际注册成功者）注入，最多 30 个，避免撑爆提示词。
            val names = registeredByServer[s.id].orEmpty()
            if (names.isNotEmpty()) {
                val shown = names.take(30)
                sb.append("- 工具: ").append(shown.joinToString(", "))
                if (names.size > shown.size) sb.append(" …（共 ").append(names.size).append(" 个）")
                sb.append("\n")
            }
        }
        return sb.toString()
    }

    /** 某服务器轮询统计：(总次数, 失败次数)。 */
    fun pollStatsFor(serverId: String): Pair<Int, Int> =
        pollStats[serverId] ?: (0 to 0)

    /** 是否正在轮询。 */
    val isPolling: Boolean get() = pollJob?.isActive == true

    /**
     * 启动周期探测：按 [McpPrefs.pollIntervalMs] 定时对每个启用的服务器发 `tools/list`。
     *
     * 关键：探测到工具名集合与上次**相同**时，不动 toolbox——每 5 秒无谓重注册
     * 会让本地工具列表反复抖动，也浪费 CPU。只有集合变化才重新注册。
     *
     * 幂等：重复调用会先停掉旧轮询器再起新的。
     */
    fun startPolling() {
        stopPolling()
        // 轮询是 App 的静默能力（默认开、无 UI 开关）：不再读 McpPrefs 的开关位，
        // 否则用户早先在 UI 上关过一次，会留下 poll_enabled=false 的持久化偏好，
        // 此后即使 UI 已移除、轮询也永远不启动。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pollScope = scope
        pollJob = scope.launch {
            while (isActive) {
                val interval = McpPrefs.pollIntervalMs(context)
                delay(interval)
                servers().filter { it.enabled }.forEach { server ->
                    if (!isActive) return@forEach
                    pollOnce(server)
                }
            }
        }
        LogStore.i("MCP", "远程轮询已启动，间隔 " + McpPrefs.pollIntervalMs(context) + "ms")
    }

    /** 停止轮询。 */
    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        pollScope?.cancel()
        pollScope = null
    }

    /**
     * 对单个服务器做一次探测。
     * 成功：更新 lastSeen / 统计；若工具集变化则重注册。
     * 失败：记录错误，保留上次已注册的工具（不因一次抖动就把工具撤掉）。
     */
    private suspend fun pollOnce(server: McpRemoteServer) {
        // 二次校验：服务器可能在轮询循环取完 filter 之后才被关掉（时序竞态），
        // 关掉的服务绝不发探测请求。
        if (!server.enabled) return
        val prev = pollStats[server.id] ?: (0 to 0)
        runCatching {
            val sender = McpHttpSender(server.endpoint, server.token, trustSelfSigned = server.trustSelfSigned)
            val client = McpClient(server.id, sender.asSender())
            val remote = client.listTools()
            val names = remote.map { it.name }.toSet()
            lastSeenAt[server.id] = System.currentTimeMillis()
            lastErrors.remove(server.id)
            pollStats[server.id] = (prev.first + 1) to prev.second

            if (lastToolNames[server.id] != names) {
                // 工具集变了：重注册（先注销旧的，再注册新的）
                val defs = client.wrapAsToolDefs(remote)
                unregister(server.id)
                defsByServer[server.id] = defs
                registerFiltered(server.id, defs)
                lastToolNames[server.id] = names
                LogStore.i("MCP", "远程 " + server.id + " 工具集变化 -> 重注册 " + names.size + " 个")
                // 工具集变了 -> 模型看到的工具清单过期了，通知上层重置提示词注入标记
                notifyTopologyChanged()
            }
        }.onFailure { e ->
            pollStats[server.id] = prev.first + 1 to prev.second + 1
            lastErrors[server.id] = e.message ?: "探测失败"
        }
    }

    // ───────── 内部 ─────────

    private fun registerFiltered(serverId: String, defs: List<ToolDef>) {
        val names = registeredByServer.getOrPut(serverId) { mutableListOf() }
        defs.forEach { def ->
            if (!isAllowed(def.name)) return@forEach
            toolbox.register(def)
            if (!names.contains(def.name)) names.add(def.name)
        }
    }

    /** 双重过滤：ToolPrefs 全局开关 ∩ 预设白名单。 */
    private fun isAllowed(toolName: String): Boolean =
        ToolPrefs.isEnabled(context, toolName) &&
            (PresetRuntime.current?.allows(toolName) ?: true)

    private fun unregister(serverId: String) {
        registeredByServer.remove(serverId)?.forEach { toolbox.unregister(it) }
    }
}
