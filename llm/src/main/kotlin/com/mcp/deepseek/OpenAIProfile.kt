package com.mcp.deepseek

/**
 * 一个 OpenAI 兼容配置档案（设置页中以「文件夹」形式呈现，按模型名命名）。
 *
 * 包含：url / key / 模型名 / 温度 / 最大输入(tokens) / 最大输出(tokens) /
 * 上下文窗口(tokens) / 跳过证书校验 / 思考强度(reasoning_effort)。
 * 语音（转写/TTS）为全局独立设置，不并入档案。
 */
data class OpenAIProfileConfig(
    val id: String,
    val model: String,
    val baseUrl: String,
    val apiKey: String,
    val temperature: Double,
    val maxOutput: Int,
    val contextWindow: Int,
    val maxInput: Int,
    val insecureSkipVerify: Boolean,
    val reasoningEffort: String
)
