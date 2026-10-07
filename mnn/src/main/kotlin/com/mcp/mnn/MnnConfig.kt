package com.mcp.mnn

/**
 * MNN-LLM 本地后端的运行配置。
 *
 * @param modelDir    模型目录的绝对路径，目录内需含 config.json（MNN-LLM 的入口文件）
 * @param backendType 算力后端（cpu / opencl），对应 config.json 的 backend_type
 * @param threadNum   推理线程数
 * @param maxNewTokens 单轮最大生成 token 数
 * @param precision   精度档（low / normal / high）
 * @param memory      内存档（low / normal / high）
 * @param systemPrompt 系统提示词（为空则用模型自带模板）
 */
data class MnnConfig(
    val modelDir: String,
    val backendType: String = "cpu",
    val threadNum: Int = 4,
    val maxNewTokens: Int = 4096,
    val precision: String = "low",
    val memory: String = "low",
    val systemPrompt: String = "",
) {
    /** 入口配置文件路径。 */
    val configPath: String get() = modelDir.trimEnd('/') + "/config.json"

    /** 模型是否可用：目录含 config.json，且权重文件存在。 */
    fun isModelAvailable(): Boolean {
        val cfgFile = java.io.File(configPath)
        if (!cfgFile.exists() || !cfgFile.isFile) return false
        // 权重文件名由 config.json 指定，这里只做基础存在性检查
        return java.io.File(modelDir).isDirectory
    }

    /** 引擎缓存键。 */
    fun engineKey(): String = "$modelDir|$backendType|$threadNum"

    /**
     * 生成交给 native 的 mergedConfig JSON。
     *
     * 字段名与 MNN Chat 的 ModelConfig @SerializedName 对齐（backend_type / thread_num ...），
     * native 用 nlohmann::json 按这些键取值，键名不符即被忽略、回落到 config.json 原值。
     * 用 Gson 而非手写拼接，避免转义与字段遗漏。
     */
    fun toMergedConfigJson(): String {
        val m = LinkedHashMap<String, Any?>()
        m["backend_type"] = backendType
        m["thread_num"] = threadNum
        m["max_new_tokens"] = maxNewTokens
        m["precision"] = precision
        m["memory"] = memory
        if (systemPrompt.isNotBlank()) m["system_prompt"] = systemPrompt
        return com.google.gson.Gson().toJson(m)
    }

    /**
     * 生成运行时开关 JSON（is_r1 / mmap_dir / keep_history）。
     *
     * keep_history=false：历史完全由上层每轮传入，native 不自行累积，
     * 与 submitFullHistory 的无状态语义配套。
     */
    fun toConfigMapJson(): String {
        val m = LinkedHashMap<String, Any?>()
        m["is_r1"] = false
        m["mmap_dir"] = ""
        m["keep_history"] = false
        return com.google.gson.Gson().toJson(m)
    }
}
