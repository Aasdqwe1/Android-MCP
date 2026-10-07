package com.mcp.litert
    
    /**
     * LiteRT 本地后端的运行配置。
     *
     * @param modelPath    .litertlm 模型文件的绝对路径（用户导入后持久化）
     * @param backend      算力档位（CPU / GPU / NPU）
     * @param cacheDir     可写缓存目录（传 Android context.cacheDir.path 可加快二次加载）
     * @param temperature  采样温度
     * @param topK         top-K 采样
     * @param topP         nucleus 采样
     * @param nativeLibraryDir NPU 后端必需：context.applicationInfo.nativeLibraryDir
     */
    data class LiteRtConfig(
        val modelPath: String,
        val backend: LiteRtBackend = LiteRtBackend.CPU,
        val cacheDir: String? = null,
        val temperature: Double = 0.7,
        val topK: Int = 40,
        val topP: Double = 0.95,
        val nativeLibraryDir: String? = null,
        /**
         * 引擎上下文窗口（token）。
         *
         * LiteRT 未指定时**默认只有 4096**，会把稍长的 system 提示词直接拒掉
         * （报 "Input token ids are too long ... >= 4096"）。这里按模型实际能力放宽：
         * Gemma 3 系列原生支持 32K，留出输出空间后设 32K 上限更合理。
         */
        val maxNumTokens: Int = 32768,
            /**
             * 工具执行器（由 app 层注入 ToolRuntime 的 Toolbox 实例）。
             *
             * :litert 不依赖 :app，无法直接取 ToolRuntimeHolder，故用字段注入。
             * 为空表示不暴露任何工具（纯对话模式）。
             */
            val toolbox: com.mcp.toolbox.Toolbox? = null,
    ) {
        /** 引擎缓存键：路径或后端任一变化都要重建引擎。 */
        fun engineKey(): String = "$modelPath|$backend|$maxNumTokens"
    
        /** 模型路径是否有效（非空且文件存在）。 */
        fun isModelAvailable(): Boolean =
            modelPath.isNotBlank() && java.io.File(modelPath).let { it.exists() && it.isFile }
    }
    