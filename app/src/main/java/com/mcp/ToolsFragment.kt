@file:JvmName("ToolsFragmentKt")
package com.mcp

import android.content.Context
import android.os.Bundle
import android.widget.CompoundButton
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.mcp.composition.ToolRuntime
import com.mcp.composition.ToolRuntimeHolder
import com.mcp.deepseek.AuthPrefs
import com.mcp.preset.PresetManager
import com.mcp.preset.PresetRuntime
import com.mcp.toolbox.PrettyJson
import com.mcp.toolbox.ToolArgsParseException
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.errorResult
import com.mcp.toolbox.parseToolArguments
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 工具页面：按**分组**展示应用全部工具，每组带一个整组开关。
 *
 *  1. 工具列表来源：[ToolRuntime.catalog].all()（完整目录，含用户禁用的工具），
 *     这样用户即使关闭了某个工具，也能在 UI 上找回来重新打开。
 *  2. 分组规则：交给 [ToolGroups]（纯函数、按工具名判定），本页面只负责渲染。
 *     不在这里写 when(name) 分支，是为了让「新增工具该归哪组」有唯一改动点。
 *  3. 组开关语义 = **一键全开 / 全关**（勾选态表示「该组全部启用」）：
 *     - 全开 → 点击变全关；
 *     - 全关或部分启用 → 点击补齐为全开。
 *     选这个语义而不是「勾选 = 至少一个启用」，是因为「想把整组打开」比「想整组关掉」
 *     更常见（后者用部分状态的下一次点击也能达成），且默认全开时勾选态与直觉一致。
 *     受预设白名单限制的工具无法启用，组计数里会单独标出，不制造假开关。
 *  4. 折叠：组头的箭头只控制 UI 折叠，折叠状态持久化到 [ToolPrefs]（默认展开）。
 *  5. 「执行」按钮：走共享 [ToolRuntime.toolbox].dispatch，与主聊天/微信渠道用同一个 Toolbox。
 */
class ToolsFragment : Fragment(R.layout.fragment_tools) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 程序化切换 chip 时置 true，避免 OnCheckedChangeListener 递归触发。 */
    private var __suppressChipChange = false
    private lateinit var rvTools: RecyclerView
    private lateinit var tvToolsEmpty: TextView
    private lateinit var toolAdapter: ToolAdapter
    private var allTools: List<ToolDef> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 从组合根拿共享 ToolRuntime（主聊天/微信渠道复用同一个 Toolbox）
        val context = requireContext()
        val runtime = ToolRuntimeHolder.get(context, AuthPrefs(context))

        // 技能 Python 依赖改到后台补装。原先它在 discoverAndLoad() 里同步执行，而那条调用链
        // 全程在主线程（MainActivity.onCreate → 本方法），PRoot 未就绪时 executeBash 会
        // runBlocking 解压整个 Debian rootfs —— 首次启动黑屏卡死就是这么来的。
        // installPythonDependencies() 内部已加「环境未就绪直接返回」的防御，不会在此触发安装。
        scope.launch(Dispatchers.IO) {
            runCatching { runtime.skillManager.installSkillDependencies() }
        }

        rvTools = view.findViewById(R.id.rvTools)
        tvToolsEmpty = view.findViewById(R.id.tvToolsEmpty)

        allTools = runtime.catalog.all()
        toolAdapter = ToolAdapter(runtime, scope)
        rvTools.adapter = toolAdapter
        refreshToolList(runtime)

        // MCP 双向服务管理 — 打开 MCP 管理页面
        view.findViewById<MaterialButton>(R.id.btnMcpMgr).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .add(R.id.nav_host, McpManagementFragment(), "MCP_MGMT")
                .addToBackStack(null)
                .commit()
        }

        // Skill 管理按钮 — 打开 Skill 管理页面
        view.findViewById<MaterialButton>(R.id.btnSkillMgr).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .add(R.id.nav_host, SkillManagementFragment(), "SKILL_MGMT")
                .addToBackStack(null)
                .commit()
        }

        // 运行模式选择：水平 chip 组，点击即切换（无弹窗）
        val chipGroup = view.findViewById<ChipGroup>(R.id.chipPreset)
        val presets = PresetManager.list()

        val allPresets = if (presets.any { it.id == "full" }) presets
            else listOf(PresetRuntime.FULL) + presets

        val currentId = PresetRuntime.current?.id ?: "full"
        val chipIdMap = LinkedHashMap<String, Chip>()

        allPresets.forEach { preset ->
            val chip = Chip(context).apply {
                id = View.generateViewId()
                text = preset.name
                isCheckable = true
                isChecked = (preset.id == currentId)
                chipIdMap[preset.id] = this
            }
            chipGroup.addView(chip)
            chip.setOnCheckedChangeListener { _, _ ->
                if (__suppressChipChange) return@setOnCheckedChangeListener
                val selectedId = chipIdMap.keys.firstOrNull { chipIdMap[it]?.isChecked == true }
                    ?: return@setOnCheckedChangeListener
                applyPreset(selectedId, runtime, chipIdMap)
            }
        }
    }

    /** 切换预设并刷新工具列表（分组视图会按新白名单重算锁定态）。 */
    private fun applyPreset(
        presetId: String?,
        runtime: ToolRuntime,
        chipIdMap: LinkedHashMap<String, Chip>
    ) {
        try {
            runtime.setPreset(presetId)
            __suppressChipChange = true
            chipIdMap.values.forEach { it.isChecked = false }
            chipIdMap[presetId]?.isChecked = true
            __suppressChipChange = false
            refreshToolList(runtime)
            Toast.makeText(requireContext(), "已切换：" + (PresetRuntime.current ?: PresetRuntime.FULL).name, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "切换失败：" + (e.message ?: e::class.simpleName),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * 刷新工具列表（分组）。
     *
     * **不再按预设白名单过滤**：受预设限制的工具改为「留在列表里 + 开关置灰 + 说明原因」，
     * 兑现 [com.mcp.composition.ToolCatalog] 的承诺（UI 始终能看到完整目录）。
     * 之前被过滤掉的工具在这里既看不到、也查不到，而且 ToolPrefs 里的开关仍是打开态（假开关）。
     */
    private fun refreshToolList(runtime: ToolRuntime) {
        // 目录可能已变（技能重扫 / 远程 MCP 注册），每次刷新都重取，避免列表停在旧快照上。
        allTools = runtime.catalog.all()
        val groups = ToolGroups.group(allTools)
        toolAdapter.submit(groups)
        tvToolsEmpty.visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    /**
     * 工具列表适配器：组头 + 工具卡片两种行类型。
     *
     * 关键设计：
     *  - 行模型是「展平后的 Row 列表」（组头 + 该组未折叠时的成员），交给 RecyclerView 消费；
     *    折叠只影响 rows 的构造，不引入嵌套 RecyclerView（省掉滚动冲突与嵌套回收的复杂度）。
     *  - toolExpanded（参数/执行区展开）用 tool.name 做 key，避免列表重排后展开状态错位。
     *  - paramsLayout.tag 记录已创建参数的 tool name，RecyclerView 复用时避免重复创建 TextView。
     *  - 切换开关时同步更新 btnRun 可点击状态：禁用时不可执行。
     *  - btnRun 点击后检查 holder 有效性（holder.isAttachedToWindow），避免复用时写错卡片。
     */
    private class ToolAdapter(
        private val runtime: ToolRuntime,
        private val scope: CoroutineScope
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private companion object {
            const val TYPE_HEADER = 0
            const val TYPE_TOOL = 1
        }

        private sealed interface Row {
            data class Header(val group: ToolGroup) : Row
            data class Item(val tool: ToolDef) : Row
        }

        private var groups: List<ToolGroup> = emptyList()
        private var rows: List<Row> = emptyList()

        /** 工具卡片内「参数 + 执行区」是否展开，key = tool.name。 */
        private val toolExpanded = mutableSetOf<String>()

        /** 组是否折叠，key = group.id（与 [ToolPrefs] 同步）。 */
        private val collapsed = mutableSetOf<String>()

        private val ctx: Context get() = runtime.context

        /** 提交新分组：同步折叠状态并重建行列表。 */
        fun submit(newGroups: List<ToolGroup>) {
            groups = newGroups
            collapsed.clear()
            newGroups.forEach { if (ToolPrefs.isGroupCollapsed(ctx, it.id)) collapsed.add(it.id) }
            rebuildRows()
        }

        /** 折叠状态或启用状态变化后重建行列表并整体刷新（行数可能变，局部刷新不适用）。 */
        private fun rebuildRows() {
            val out = ArrayList<Row>(groups.size * 2)
            for (g in groups) {
                out += Row.Header(g)
                if (!collapsed.contains(g.id)) g.tools.forEach { out += Row.Item(it) }
            }
            rows = out
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Header -> TYPE_HEADER
            is Row.Item -> TYPE_TOOL
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderVH(inflater.inflate(R.layout.item_tool_group, parent, false))
            } else {
                ToolVH(inflater.inflate(R.layout.item_tool, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Header -> bindHeader(holder as HeaderVH, row.group)
                is Row.Item -> bindTool(holder as ToolVH, row.tool)
            }
        }

        class HeaderVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val btnFold: MaterialButton = itemView.findViewById(R.id.btnGroupFold)
            val tvName: TextView = itemView.findViewById(R.id.tvGroupName)
            val tvCount: TextView = itemView.findViewById(R.id.tvGroupCount)
            val tvDesc: TextView = itemView.findViewById(R.id.tvGroupDesc)
            // 声明成 CompoundButton 而不是具体实现：本页只用 isChecked / isEnabled /
            // setOnCheckedChangeListener 这三个通用 API，绑定到具体类只会让
            // 「SwitchMaterial 换成 MaterialSwitch」这类改动牵动 Kotlin 代码。
            val switch: CompoundButton = itemView.findViewById(R.id.switchGroup)
            /** 除开关外的整行：点它也能折叠，避免只能点中 22dp 的箭头。 */
            val foldableArea: View = itemView.findViewById(R.id.groupFoldArea)
        }

        class ToolVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvName: TextView = itemView.findViewById(R.id.tvToolName)
            val tvDesc: TextView = itemView.findViewById(R.id.tvToolDesc)
            val switch: CompoundButton = itemView.findViewById(R.id.switchEnabled)
            val paramsLayout: ViewGroup = itemView.findViewById(R.id.paramsLayout) as ViewGroup
            val runLayout: View = itemView.findViewById(R.id.runLayout)
            val etArgs: TextInputEditText = itemView.findViewById(R.id.etArgs)
            val btnRun: MaterialButton = itemView.findViewById(R.id.btnRun)
            val tvResult: TextView = itemView.findViewById(R.id.tvResult)
            val btnExpand: MaterialButton = itemView.findViewById(R.id.btnExpand)
        }

        private fun bindHeader(holder: HeaderVH, group: ToolGroup) {
            val isCollapsed = collapsed.contains(group.id)
            // 计数实时读 ToolPrefs，避免把启用状态缓存在 ToolGroup 里造成双份真相
            val enabledCount = group.tools.count { ToolPrefs.isEnabled(ctx, it.name) }
            val lockedCount = group.tools.count { !(runtime.preset?.allows(it.name) ?: true) }
            val allEnabled = group.size > 0 && enabledCount == group.size

            holder.tvName.text = group.name
            holder.tvCount.text = buildString {
                append("已启用 ").append(enabledCount).append('/').append(group.size)
                if (lockedCount > 0) {
                    append("（").append(lockedCount).append(" 个受当前预设限制）")
                }
            }
            holder.tvDesc.text = group.description
            // 折叠时收起说明行，让组头更紧凑；计数行保留（折叠后仍想知道开了几个）
            holder.tvDesc.visibility = if (isCollapsed) View.GONE else View.VISIBLE
            // 箭头方向：展开态显示「向上收」的提示（expand_less 语义由 drawable 决定），
            // 与列表展开方向一致；不要用文字箭头，字体差异会让粗细/基线在各设备上飘。
            holder.btnFold.setIconResource(
                if (isCollapsed) R.drawable.ic_group_collapse else R.drawable.ic_group_expand
            )

            val toggleFold = View.OnClickListener {
                val collapseNow = !collapsed.contains(group.id)
                if (collapseNow) collapsed.add(group.id) else collapsed.remove(group.id)
                ToolPrefs.setGroupCollapsed(ctx, group.id, collapseNow)
                rebuildRows()
            }
            holder.btnFold.setOnClickListener(toggleFold)
            // 整行（除开关）也可点：组头是「标题行」而非按钮，用户会自然地去点标题。
            holder.foldableArea.setOnClickListener(toggleFold)

            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = allEnabled
            // 整组都被预设锁住时开关无意义（点了也不会生效），置灰
            holder.switch.isEnabled = lockedCount < group.size
            holder.switch.setOnCheckedChangeListener { _, checked ->
                val names = group.tools.map { it.name }
                val applied = runtime.setToolsEnabled(names, checked)
                val blocked = names.size - applied
                val msg = when {
                    checked && blocked > 0 -> "已启用 " + applied + " 个；" + blocked + " 个受当前预设限制，未能启用"
                    checked -> "已启用「" + group.name + "」全部 " + applied + " 个工具"
                    else -> "已关闭「" + group.name + "」" + applied + " 个工具"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                // 组内每个工具的开关状态都变了，整表重建刷新（计数行也依赖它）
                rebuildRows()
            }
        }

        private fun bindTool(holder: ToolVH, tool: ToolDef) {
            val isExpanded = toolExpanded.contains(tool.name)
            val isEnabled = ToolPrefs.isEnabled(ctx, tool.name)
            // 当前预设是否允许这个工具（不允许时开关锁定，避免出现「打开了却注册不上」的假开关）
            val allowed = runtime.preset?.allows(tool.name) ?: true

            holder.tvName.text = tool.name
            holder.tvDesc.text = if (allowed) tool.description
            else tool.description + "\n（当前预设未包含此工具：开关被预设白名单锁定，切到全量模式后才能启用）"

            // 开关：先清监听器再设状态（避免复用时的连锁触发）
            holder.switch.setOnCheckedChangeListener(null)
            holder.switch.isChecked = isEnabled && allowed
            holder.switch.isEnabled = allowed
            holder.switch.setOnCheckedChangeListener { _, checked ->
                runtime.setToolEnabled(tool.name, checked)
                holder.runLayout.visibility =
                    if (checked && toolExpanded.contains(tool.name)) View.VISIBLE else View.GONE
                holder.btnRun.isEnabled = checked
            }

            // 参数区：tag 记录已创建的 tool name，避免 RecyclerView 复用时重复创建
            if (holder.paramsLayout.tag != tool.name) {
                holder.paramsLayout.removeAllViews()
                if (tool.parameters.isNotEmpty()) {
                    for ((name, spec) in tool.parameters) {
                        val tv = TextView(ctx).apply {
                            text = "  " + spec.type.jsonName + " " + name + (if (spec.required) " *" else "") + " — " + spec.description
                            textSize = 12f
                            setTextColor(ctx.getColor(android.R.color.darker_gray))
                            setPadding(16, 2, 0, 2)
                        }
                        holder.paramsLayout.addView(tv)
                    }
                } else {
                    val tv = TextView(ctx).apply {
                        text = "  (无参数)"
                        textSize = 12f
                        setTextColor(ctx.getColor(android.R.color.darker_gray))
                        setPadding(16, 2, 0, 2)
                    }
                    holder.paramsLayout.addView(tv)
                }
                holder.paramsLayout.tag = tool.name
            }

            holder.paramsLayout.visibility = if (isExpanded) View.VISIBLE else View.GONE
            // runLayout 仅在「展开 && 启用」时可见
            holder.runLayout.visibility = if (isExpanded && isEnabled) View.VISIBLE else View.GONE
            holder.btnExpand.text = if (isExpanded) "收起" else "展开"
            holder.btnRun.isEnabled = isEnabled

            holder.btnExpand.setOnClickListener {
                if (toolExpanded.contains(tool.name)) toolExpanded.remove(tool.name)
                else toolExpanded.add(tool.name)
                notifyItemChanged(holder.adapterPosition)
            }

            holder.btnRun.setOnClickListener {
                if (!ToolPrefs.isEnabled(ctx, tool.name)) return@setOnClickListener
                val argsJson = holder.etArgs.text.toString().trim()
                holder.tvResult.visibility = View.VISIBLE
                holder.tvResult.text = "执行中..."
                holder.btnRun.isEnabled = false

                scope.launch {
                    val result = try {
                        val args = if (argsJson.isNotEmpty())
                            parseToolArguments(argsJson)
                        else JsonObject(emptyMap())
                        // 走共享 Toolbox（主聊天/微信渠道用同一个），保证用户手动试的工具与 LLM 用的同源
                        runtime.toolbox.dispatch(tool.name, args)
                    } catch (e: Exception) {
                        val msg = (e as? ToolArgsParseException)?.message
                            ?: ("工具参数解析失败：" + (e.message ?: e::class.simpleName))
                        errorResult(msg)
                    }
                    // holder 有效性检查：避免 RecyclerView 复用后写错卡片
                    if (!holder.itemView.isAttachedToWindow) return@launch
                    holder.tvResult.text = formatResult(result)
                    holder.btnRun.isEnabled = ToolPrefs.isEnabled(ctx, tool.name)
                }
            }
        }

        private fun formatResult(json: String): String = try {
            PrettyJson.encodeToString(JsonElement.serializer(), PrettyJson.parseToJsonElement(json))
        } catch (_: Exception) { json }
    }
}
