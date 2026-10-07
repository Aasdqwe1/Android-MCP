package com.mcp

import com.mcp.toolbox.ToolDef

/**
 * 工具页分组。
 *
 * 背景：工具目录已近百个（浏览器 30+、微信 13，构建/依赖/日志/差异各若干），扁平列表既难找、
 * 也难管理——想批量关掉一整类能力（比如浏览器自动化）只能逐个点开关。
 *
 * 这里把「哪些工具属于哪一组」从 UI 里抽出来，做成一张**声明式**规则表：
 *  - 分组结果只依赖工具名，新增工具时补一条规则即可，不用改 UI；
 *  - 未命中任何规则的工具自动落入 [ToolGroups.OTHER_ID]（「其它」）——这是刻意的兜底：
 *    漏配规则的代价是「归到其它组」，而不是「用户在页面上找不到这个工具」。
 *
 * 分组**不参与任何权限判定**：组开关最终仍逐项写 [ToolPrefs]，预设白名单仍由
 * [com.mcp.composition.ToolRuntime.setToolsEnabled] 把守。分组只是视图与批量管理入口。
 */
data class ToolGroup(
    /** 稳定标识，用于持久化折叠状态（不要用中文名，改名不该丢状态）。 */
    val id: String,
    /** 组标题。 */
    val name: String,
    /** 一句话说明这组工具是干什么的，显示在组标题下方。 */
    val description: String,
    /** 组内工具（保持 ToolCatalog 的原始顺序）。 */
    val tools: List<ToolDef>,
) {
    val size: Int get() = tools.size
}

object ToolGroups {

    /** 兜底组：没命中任何规则的工具都进这里。 */
    const val OTHER_ID = "other"

    // ── 精确名集合（前缀规则覆盖不到的零散工具）─────────────────────────
    private val BASIC = setOf(
        "calculator", "get_current_time", "string_length", "http_request",
        "web_search", "ask_user",
    )
    private val FILE = setOf(
        "read_file", "write_file", "edit_file", "multi_edit", "get_block",
        "delete_file", "delete_range", "list_files", "copy_file", "move_file", "undo_edit",
    )
    private val SEARCH = setOf("search_files", "search_replace", "glob", "grep", "search_and_read")
    private val MULTIMODAL = setOf(
        "read_image", "describe_image", "generate_image", "transcribe_audio", "text_to_speech",
    )
    private val TODO = setOf("list_todos", "add_todo", "update_todo", "import_todos", "delete_todo")
    private val BUILD_EXTRA = setOf("lint_check", "format_code")
    private val DEP = setOf("list_dependencies", "update_dependency", "suggest_updates")
    private val LOGDIFF = setOf("tail_log", "analyze_log", "diff_files", "merge_files")
    private val AGENT_EXTRA = setOf("delegate_to_agent", "list_agents")

    /** 组元数据，列表顺序即页面展示顺序。 */
    private val META: List<Triple<String, String, String>> = listOf(
        Triple("basic", "基础工具", "计算、时间、字符串长度、HTTP 请求、联网搜索、向用户提问"),
        Triple("terminal", "终端与 Shell", "PRoot Debian 里执行命令，含常驻会话与后台任务管理"),
        Triple("async", "异步长任务", "提交不受超时限制的长任务，轮询状态、取日志、取消"),
        Triple("root", "Root 权限", "以 root 直接在 Android 宿主机执行命令（高危）"),
        Triple("todo", "待办事项", "让模型自己维护任务清单，跨轮追踪进度"),
        Triple("file", "文件操作", "读写编辑、按块定位、删除、复制移动、撤销"),
        Triple("search", "搜索与替换", "按文件名/内容检索、正则匹配、跨文件批量替换"),
        Triple("multimodal", "多模态", "图像读取与生成、语音转写、文本转语音"),
        Triple("saf", "SAF 存储", "通过系统文件选择器授权访问任意目录，无需存储权限"),
        Triple("sandbox", "沙盒隔离", "把沙盒与宿主隔开；仅经显式授权的宿主目录可暴露进沙盒"),
        Triple("agent", "Agent 编排", "派发子 Agent、声明式多步工作流、查看与取消任务"),
        Triple("build", "构建与质量", "Gradle 构建/测试/依赖树，以及 ktlint/detekt 风格检查"),
        Triple("dependency", "依赖管理", "查看依赖、检查可升级版本、更新版本号"),
        Triple("logdiff", "日志与差异", "看日志尾部、归类错误、文件对比与三方合并"),
        Triple("browser", "浏览器自动化", "内嵌浏览器导航、点击输入、抓包、截图、下载"),
        Triple("wechat", "微信", "OpenClaw 微信登录、收发消息、Gateway 运维"),
        Triple("skill", "技能市场", "安装/卸载技能，查看技能元数据与依赖"),
        Triple("batch", "批量执行", "把多个工具调用编排成一次确定性执行"),
        Triple("ptc", "PTC 编程", "run_code：写一段程序在内部调用其它工具"),
        Triple("mcp", "MCP 远程", "连接远程 MCP 服务器并注册其工具"),
        Triple(OTHER_ID, "其它", "尚未归类的工具"),
    )

    /**
     * 判定单个工具属于哪一组。
     *
     * 顺序敏感：前缀规则在前，精确名集合在后。例如 run_bash_persistent 必须先被
     * startsWith("run_bash") 命中，否则会掉进兜底组。
     */
    internal fun groupIdOf(name: String): String = when {
        name.startsWith("browser_") -> "browser"
        name.startsWith("openclaw_") -> "wechat"
        name.startsWith("skill_") -> "skill"
        name.startsWith("mcp_") -> "mcp"
        name.startsWith("async_task_") -> "async"
        name.startsWith("saf_") -> "saf"
        name.startsWith("sandbox_mount_") -> "sandbox"
        name.startsWith("gradle_") -> "build"
        name.startsWith("bash_task_") -> "terminal"
        name.startsWith("persistent_bash") -> "terminal"
        name.startsWith("run_bash") -> "terminal"
        name.startsWith("agent_") -> "agent"

        name in AGENT_EXTRA -> "agent"
        name in BASIC -> "basic"
        name in FILE -> "file"
        name in SEARCH -> "search"
        name in MULTIMODAL -> "multimodal"
        name in TODO -> "todo"
        name in BUILD_EXTRA -> "build"
        name in DEP -> "dependency"
        name in LOGDIFF -> "logdiff"

        name == "run_root" -> "root"
        name == "batch_execute" -> "batch"
        name == "run_code" -> "ptc"
        // 终端环境自检，语义上属于终端而非搜索（catalog 里历史上放在搜索段）
        name == "proot_status" -> "terminal"

        else -> OTHER_ID
    }

    /**
     * 把 [tools] 切分成有序分组。
     *
     * @param includeEmpty 是否保留空组。默认 false（空组不显示，避免页面上一堆空标题）；
     *   传 true 便于单测断言「规则表本身没有写错的组 id」。
     */
    fun group(tools: List<ToolDef>, includeEmpty: Boolean = false): List<ToolGroup> {
        // 一次遍历分桶，保持组内原始顺序
        val buckets = LinkedHashMap<String, MutableList<ToolDef>>()
        for (t in tools) {
            buckets.getOrPut(groupIdOf(t.name)) { mutableListOf() }.add(t)
        }
        val out = ArrayList<ToolGroup>(META.size)
        for ((id, name, desc) in META) {
            val items = buckets[id] ?: continue
            if (items.isEmpty() && !includeEmpty) continue
            out += ToolGroup(id = id, name = name, description = desc, tools = items)
        }
        // 理论上不会走到这里（META 已含 OTHER_ID），但规则表被改动时留个安全网：
        // 宁可多显示一个组，也不能让工具凭空消失。
        buckets[OTHER_ID]?.let { items ->
            if (out.none { it.id == OTHER_ID } && (items.isNotEmpty() || includeEmpty)) {
                out += ToolGroup(OTHER_ID, "其它", "尚未归类的工具", items)
            }
        }
        return out
    }

    /**
     * 按工具名分组（[group] 的无依赖版本），返回 (组 id, 组内工具名) 列表。
     *
     * 存在的唯一理由是可测性：[group] 需要真实 ToolDef（进而需要 Context 构造 Toolbox），
     * 而分组规则本身只依赖名字。把规则与 ToolDef 解耦后，单测可以直接喂一串名字断言
     * 「每个工具都落到了预期的组、且没有工具被漏掉」。
     *
     * 与 [group] 共用同一份 [groupIdOf] 与 [META]，不存在两套规则漂移的可能。
     */
    internal fun groupNames(
        names: List<String>,
        includeEmpty: Boolean = false,
    ): List<Pair<String, List<String>>> {
        val buckets = LinkedHashMap<String, MutableList<String>>()
        for (n in names) buckets.getOrPut(groupIdOf(n)) { mutableListOf() }.add(n)
        val out = ArrayList<Pair<String, List<String>>>(META.size)
        for ((id, _, _) in META) {
            val items = buckets[id] ?: continue
            if (items.isEmpty() && !includeEmpty) continue
            out += id to items
        }
        return out
    }

    /** 全部组 id（供单测校验规则表完整性）。 */
    fun allGroupIds(): List<String> = META.map { it.first }

    /** 组标题（供 UI/单测展示；找不到返回 id 本身）。 */
    fun displayName(id: String): String =
        META.firstOrNull { it.first == id }?.second ?: id
}
