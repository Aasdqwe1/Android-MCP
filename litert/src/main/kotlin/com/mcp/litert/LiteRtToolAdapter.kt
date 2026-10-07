package com.mcp.litert
    
    import com.google.ai.edge.litertlm.OpenApiTool
    import com.mcp.toolbox.ToolCompiler
    import com.mcp.toolbox.ToolDef
    import com.mcp.toolbox.Toolbox
    import kotlinx.coroutines.runBlocking
    import kotlinx.serialization.json.jsonObject
    
    /**
     * 把项目的 [ToolDef] 桥接为 LiteRT 的 [OpenApiTool]。
     *
     * 设计目标：**工具定义与执行完全复用项目现有实现**，与 OpenAI 后端行为一致——
     *  - schema：复用 [ToolCompiler.toOpenAi]（同一份 JSON Schema 产物）；
     *  - 执行：复用 [Toolbox.dispatch]（同一套 schema 校验、错误自愈、重复失败防护）。
     *
     * LiteRT 的 OpenApiTool 接口要求的 JSON 形如：
     * { "name": "...", "description": "...", "parameters": { ... } }
     * 而 ToolCompiler.toOpenAi 产出的是 OpenAI 包裹形态：
     * { "type": "function", "function": { "name": ..., "description": ..., "parameters": ... } }
     * 因此这里剥掉 function 外壳再交给 LiteRT。
     */
    class LiteRtToolAdapter(
        private val tool: ToolDef,
        private val toolbox: Toolbox,
    ) : OpenApiTool {
    
        /** 缓存 schema JSON：构造一次，避免每次 provideTools 都重新序列化。 */
        private val schemaJson: String by lazy {
            val openAi = ToolCompiler.toOpenAi(listOf(tool))
            runCatching {
                openAi.first().jsonObject["function"]!!.toString()
            }.getOrElse {
                "{\"name\":\"" + tool.name + "\",\"description\":" + escapeJson(tool.description) +
                    ",\"parameters\":" + tool.inputSchema + "}"
            }
        }
    
        override fun getToolDescriptionJsonString(): String = schemaJson
    
        /**
         * 执行工具调用。
         *
         * LiteRT 以同步方式调用本方法，而 Toolbox.dispatch 是 suspend，故用 runBlocking 桥接。
         * 工具内部若有长耗时操作（bash、网络），会阻塞 LiteRT 的推理线程——
         * 这是 OpenApiTool 接口的固有限制。
         */
        override fun execute(paramsJsonString: String): String = runBlocking {
            toolbox.dispatch(tool.name, paramsJsonString)
        }
    
        private fun escapeJson(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
    
    /**
     * 把一批 [ToolDef] 批量包装为 LiteRT 工具。
     *
     * @param tools   要暴露的工具集（已由 ChatBridge 按 ToolPrefs 与预设白名单过滤）
     * @param toolbox 执行器（与 OpenAI 分支共用同一个实例）
     */
    fun toLiteRtTools(
        tools: List<ToolDef>,
        toolbox: Toolbox,
    ): List<com.google.ai.edge.litertlm.ToolProvider> =
        tools.map { com.google.ai.edge.litertlm.tool(LiteRtToolAdapter(it, toolbox)) }
    