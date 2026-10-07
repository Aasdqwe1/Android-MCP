package com.mcp

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.mcp.composition.ToolRuntimeHolder
import com.mcp.deepseek.AuthPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MCP 双向服务管理页。
 *
 * 两块：
 *  1. 本机服务端（发射）——总开关、端点地址、Bearer token、已暴露工具数；
 *  2. 远程服务（接收）——服务器列表，逐个显示连接状态与拉取到的工具数。
 *
 * 所有网络动作都放 Dispatchers.IO，主线程只做 UI。
 */
class McpManagementFragment : Fragment(R.layout.fragment_mcp_management) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        val runtime = ToolRuntimeHolder.get(ctx, AuthPrefs(ctx))
        val registry = runtime.remoteRegistry

        view.findViewById<MaterialButton>(R.id.btnMcpBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // ── 本机服务端 ──
        val switchServer = view.findViewById<MaterialSwitch>(R.id.switchMcpServer)
        val tvServerStatus = view.findViewById<TextView>(R.id.tvServerStatus)
        val tvServerEndpoint = view.findViewById<TextView>(R.id.tvServerEndpoint)
        val tvServerTools = view.findViewById<TextView>(R.id.tvServerTools)
        val dotServer = view.findViewById<View>(R.id.dotServer)
        val etToken = view.findViewById<TextInputEditText>(R.id.etToken)

        switchServer.isChecked = McpPrefs.isEnabled(ctx)
        switchServer.setOnCheckedChangeListener { _, checked ->
            McpPrefs.setEnabled(ctx, checked)
            renderServerState(ctx, runtime, tvServerStatus, tvServerEndpoint, tvServerTools, dotServer)
        }
        etToken.setText(McpPrefs.token(ctx))
        view.findViewById<MaterialButton>(R.id.btnSaveToken).setOnClickListener {
            McpPrefs.setToken(ctx, etToken.text?.toString() ?: "")
            val has = McpPrefs.token(ctx).isNotEmpty()
            Toast.makeText(ctx, if (has) "Token 已保存，客户端需携带" else "Token 已清空（不校验）", Toast.LENGTH_SHORT).show()
        }
        renderServerState(ctx, runtime, tvServerStatus, tvServerEndpoint, tvServerTools, dotServer)

        // ── 远程服务 ──
        val rv = view.findViewById<RecyclerView>(R.id.rvMcpRemotes)
        val tvEmpty = view.findViewById<TextView>(R.id.tvMcpRemoteEmpty)

        // 周期探测是 App 自身能力，静默运行（默认 5s），不暴露 UI 控件。
        // 需要调间隔/停用时改 McpPrefs（见 McpPrefs.pollIntervalMs / setPollingEnabled）。

        val adapter = RemoteAdapter(
            onRefresh = { id ->
                scope.launch {
                    Toast.makeText(ctx, "正在刷新 " + id + "…", Toast.LENGTH_SHORT).show()
                    registry.refresh(id)
                    renderRemotes(ctx, registry, rv, tvEmpty, this)
                }
            },
            onDelete = { id -> confirmDelete(ctx, registry, id, rv, tvEmpty) },
            onToggleEnabled = { id, checked ->
                McpRemotePrefs.setEnabled(ctx, id, checked)
                scope.launch {
                    // 打开 -> 拉取并注册；关闭 -> 注销并从提示词移除。
                    registry.refresh(id)
                    renderRemotes(ctx, registry, rv, tvEmpty, this)
                    val msg = if (checked) "已启用 " + id + "，正在拉取工具…"
                              else "已停用 " + id + "，其工具已全部移除"
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                }
            },
        )
        rv.adapter = adapter

        view.findViewById<MaterialButton>(R.id.btnMcpAdd).setOnClickListener {
            showAddDialog(ctx, registry, rv, tvEmpty)
        }
        view.findViewById<MaterialButton>(R.id.btnMcpRefreshAll).setOnClickListener {
            scope.launch {
                Toast.makeText(ctx, "正在刷新全部…", Toast.LENGTH_SHORT).show()
                registry.refreshAll()
                renderRemotes(ctx, registry, rv, tvEmpty, scope)
            }
        }

        renderRemotes(ctx, registry, rv, tvEmpty, scope)
    }

    /** 渲染本机服务端状态。 */
    private fun renderServerState(
        ctx: android.content.Context,
        runtime: com.mcp.composition.ToolRuntime,
        tvStatus: TextView,
        tvEndpoint: TextView,
        tvTools: TextView,
        dot: View,
    ) {
        val enabled = McpPrefs.isEnabled(ctx)
        val running = WebApiServer.isRunning()
        val port = WebApiServer.currentPort()
        val tokenSet = McpPrefs.token(ctx).isNotEmpty()

        when {
            !enabled -> {
                tvStatus.text = "已关闭 · 不对外暴露工具"
                dot.backgroundTintList = ColorStateList.valueOf(0xFF9E9E9E.toInt())
            }
            !running -> {
                tvStatus.text = "已启用 · 等待 Web 服务器启动"
                dot.backgroundTintList = ColorStateList.valueOf(0xFFFF9800.toInt())
            }
            else -> {
                val t = if (tokenSet) "Bearer 校验已开启" else "无鉴权（建议内网使用）"
                tvStatus.text = "运行中 · " + t
                dot.backgroundTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
            }
        }

        // scheme 必须取实际值：HTTPS 模式下端点实际是 https://，写死 http:// 会让人照抄后连不上。
        val scheme = WebApiServer.currentScheme() ?: "http"
        if (running && port > 0) {
            val ip = localIpAddress()
            tvEndpoint.text = "POST $scheme://" + (ip ?: "<设备IP>") + ":" + port + "/mcp"
        } else {
            tvEndpoint.text = "POST $scheme://<设备IP>:<端口>/mcp"
        }

        val count = runtime.toolbox.all().size
        tvTools.text = "当前暴露 " + count + " 个工具（跟随工具开关与预设白名单）"
    }

    /** 取本机局域网 IP（展示用；失败返回 null）。 */
    private fun localIpAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
            ?.hostAddress
    }.getOrNull()

    /** 渲染远程服务器列表。 */
    private fun renderRemotes(
        ctx: android.content.Context,
        registry: McpRemoteRegistry,
        rv: RecyclerView,
        tvEmpty: TextView,
        scope: CoroutineScope,
    ) {
        val servers = registry.servers()
        tvEmpty.visibility = if (servers.isEmpty()) View.VISIBLE else View.GONE
        rv.visibility = if (servers.isEmpty()) View.GONE else View.VISIBLE
        (rv.adapter as? RemoteAdapter)?.submit(servers, registry)
    }

    /** 添加远程服务器对话框。 */
    private fun showAddDialog(
        ctx: android.content.Context,
        registry: McpRemoteRegistry,
        rv: RecyclerView,
        tvEmpty: TextView,
    ) {
        MaterialDialogs.form(
            context = ctx,
            title = "添加远程 MCP 服务器",
            hint = "接入后，对端暴露的工具会以 remote_<标识>_<工具> 注册到本机，可被 LLM 直接调用。",
            fields = listOf(
                MaterialDialogs.Field(hint = "标识", required = true, helper = "用于工具名前缀，如 github"),
                MaterialDialogs.Field(hint = "端点 URL", required = true, helper = "如 https://mcp.example.com/mcp（省略 https:// 会自动补全）"),
                MaterialDialogs.Field(hint = "Bearer Token", helper = "对端要求鉴权时填写，否则留空"),
            ),
            confirmText = "添加",
            // 自签名证书开关：自托管 / 内网 MCP 服务器常用自签名证书，
            // 默认校验会抛 Trust anchor for certification path not found。
            checkbox = MaterialDialogs.CheckboxSpec("信任自签名证书（自托管/内网服务器常见）"),
        ) { values, trustSelfSigned ->
            val id = values[0]
            val url = values[1]
            val token = values[2]
            scope.launch {
                Toast.makeText(ctx, "正在连接 " + id + "…", Toast.LENGTH_SHORT).show()
                registry.addServer(id, url, token, trustSelfSigned)
                withContext(Dispatchers.Main) {
                    val err = registry.lastErrorFor(id)
                    if (err != null) {
                        Toast.makeText(ctx, "拉取失败：" + err, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(ctx, "已接入 " + id + "，注册 " + registry.registeredCount() + " 个工具", Toast.LENGTH_SHORT).show()
                    }
                    renderRemotes(ctx, registry, rv, tvEmpty, this@launch)
                }
            }
        }
    }

    private fun confirmDelete(
        ctx: android.content.Context,
        registry: McpRemoteRegistry,
        id: String,
        rv: RecyclerView,
        tvEmpty: TextView,
    ) {
        MaterialDialogs.confirmDestructive(
            context = ctx,
            title = "移除远程服务器",
            message = "将断开与「" + id + "」的连接，并注销它注册的全部远程工具。",
            confirmText = "移除",
        ) {
            registry.removeServer(id)
            renderRemotes(ctx, registry, rv, tvEmpty, scope)
        }
    }

    override fun onDestroyView() { super.onDestroyView(); scope.cancel() }
}

/** 远程服务器列表适配器。 */
private class RemoteAdapter(
    private val onRefresh: (String) -> Unit,
    private val onDelete: (String) -> Unit,
    private val onToggleEnabled: (String, Boolean) -> Unit,
) : RecyclerView.Adapter<RemoteAdapter.VH>() {

    private var items: List<McpRemoteServer> = emptyList()
    private var registry: McpRemoteRegistry? = null

    fun submit(servers: List<McpRemoteServer>, reg: McpRemoteRegistry) {
        items = servers
        registry = reg
        notifyDataSetChanged()
    }

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val dot: View = itemView.findViewById(R.id.dotRemote)
        val tvId: TextView = itemView.findViewById(R.id.tvRemoteId)
        val tvEndpoint: TextView = itemView.findViewById(R.id.tvRemoteEndpoint)
        val tvStatus: TextView = itemView.findViewById(R.id.tvRemoteStatus)
        val switch: MaterialSwitch = itemView.findViewById(R.id.switchRemote)
        val btnRefresh: MaterialButton = itemView.findViewById(R.id.btnRemoteRefresh)
        val btnDelete: MaterialButton = itemView.findViewById(R.id.btnRemoteDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_mcp_remote, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = items[position]
        val reg = registry
        holder.tvId.text = s.id
        holder.tvEndpoint.text = s.endpoint

        val err = reg?.lastErrorFor(s.id)
        val registered = reg?.toolCountFor(s.id) ?: 0
        val fetched = reg?.fetchedCountFor(s.id) ?: 0

        // 开关：清除旧监听避免复用错位，再回填当前状态。
        holder.switch.setOnCheckedChangeListener(null)
        holder.switch.isChecked = s.enabled
        holder.switch.setOnCheckedChangeListener { _, checked ->
            if (checked != s.enabled) onToggleEnabled(s.id, checked)
        }

        when {
            !s.enabled -> {
                holder.dot.backgroundTintList = ColorStateList.valueOf(0xFF9E9E9E.toInt())
                holder.tvStatus.text = "已停用 · 不注册工具、不进入提示词"
                holder.tvStatus.setTextColor(0xFF9E9E9E.toInt())
            }
            err != null -> {
                holder.dot.backgroundTintList = ColorStateList.valueOf(0xFFF44336.toInt())
                holder.tvStatus.text = "连接失败：" + err.take(80)
                holder.tvStatus.setTextColor(0xFFF44336.toInt())
            }
            fetched == 0 -> {
                holder.dot.backgroundTintList = ColorStateList.valueOf(0xFFFF9800.toInt())
                holder.tvStatus.text = "尚未拉取 · 点「刷新」连接"
                holder.tvStatus.setTextColor(0xFF9E9E9E.toInt())
            }
            else -> {
                holder.dot.backgroundTintList = ColorStateList.valueOf(0xFF4CAF50.toInt())
                holder.tvStatus.text = "已接入 · 拉取 " + fetched + " 个工具，当前可用 " + registered + " 个"
                holder.tvStatus.setTextColor(0xFF4CAF50.toInt())
            }
        }

        holder.btnRefresh.setOnClickListener { onRefresh(s.id) }
        holder.btnDelete.setOnClickListener { onDelete(s.id) }
    }
}
