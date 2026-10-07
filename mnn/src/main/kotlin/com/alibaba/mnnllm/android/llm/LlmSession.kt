package com.alibaba.mnnllm.android.llm

import android.util.Log

/**
 * MNN-LLM 的 JNI 桥接类。
 *
 * 【这个类为什么长这样】
 * 它是对 MNN Chat 官方 LlmSession.kt 的**最小复刻**：只保留 native 方法声明
 * 和驱动推理所必需的方法，剥掉原版里模型下载、QNN、mmap、split-file、benchmark
 * 等与本项目无关的部分。
 *
 * 包名与类名必须逐字对齐 `com.alibaba.mnnllm.android.llm.LlmSession`——
 * JNI 符号形如 Java_com_alibaba_mnnllm_android_llm_LlmSession_initNative，
 * 改名即找不到实现。因此本类虽然由本项目维护，仍放在原包名下。
 *
 * native 库加载：libmnnllmapp.so（其 DT_NEEDED 已依赖 libMNN.so，
 * System.loadLibrary 会自动一并加载，无需手动 load libMNN）。
 */
class LlmSession(
    private val modelId: String,
    var sessionId: String,
    private val configPath: String,
    private var historyStrings: List<String>? = null,
) {

    /** native 侧实例指针；0 表示未加载/已释放。 */
    private var nativePtr: Long = 0L

    @Volatile private var generating = false
    @Volatile private var releaseRequested = false
    private var keepHistory = true

    /** 模型是否已成功加载。 */
    fun isModelLoaded(): Boolean = nativePtr != 0L

    /**
     * 加载模型。
     *
     * @param mergedConfigJson 覆盖/补充 config.json 的字段（JSON 字符串）
     * @param configMapJson     运行时开关（is_r1 / mmap_dir / keep_history）
     */
    fun load(mergedConfigJson: String, configMapJson: String) {
        Log.d(TAG, "load begin modelId=$modelId")
        nativePtr = initNative(configPath, historyStrings, mergedConfigJson, configMapJson)
        if (nativePtr == 0L) {
            throw IllegalStateException("MNN 模型加载失败：initNative 返回空指针（检查 config.json 与权重文件是否齐全）")
        }
        if (releaseRequested) release()
        Log.d(TAG, "load ok ptr=$nativePtr")
    }

    /**
     * 提交一次对话（native 阻塞直到生成结束，增量文本经 listener 回调）。
     *
     * @return native 返回的统计 map（success / prompt_len / decode_len / prefill_time / decode_time）
     */
    @Synchronized
    fun generate(prompt: String, listener: GenerateProgressListener): HashMap<String, Any> {
        generating = true
        try {
            return submitNative(nativePtr, prompt, keepHistory, listener)
        } finally {
            generating = false
            if (releaseRequested) release()
        }
    }

    /** 清空 KV cache / 历史，开启新会话。 */
    @Synchronized
    fun reset() {
        if (nativePtr != 0L) resetNative(nativePtr)
    }

    /** 释放 native 实例。 */
    @Synchronized
    fun release() {
        if (generating) {
            releaseRequested = true
            return
        }
        if (nativePtr != 0L) {
            releaseNative(nativePtr)
            nativePtr = 0L
        }
    }

    fun setKeepHistory(keep: Boolean) { keepHistory = keep }

    fun updateMaxNewTokens(maxNewTokens: Int) {
        if (nativePtr != 0L) updateMaxNewTokensNative(nativePtr, maxNewTokens)
    }

    fun updateSystemPrompt(systemPrompt: String) {
        if (nativePtr != 0L) updateSystemPromptNative(nativePtr, systemPrompt)
    }

    fun updateConfig(configJson: String) {
        if (nativePtr != 0L) updateConfigNative(nativePtr, configJson)
    }

    /**
     * 提交完整对话历史（无状态路径）。
     *
     * 与 [generate] 的区别：generate 只传本轮输入、靠 native 内部累积历史，
     * 而上层（ChatBridge）每次都给完整 messages。用本方法把完整历史映射成
     * (role, content) 对交 native 套 chat template，语义与上层严格对齐，
     * 避免「native 里历史 + 上层历史」双重累积导致上下文错乱。
     */
    @Synchronized
    fun submitFullHistory(
        history: List<Pair<String, String>>,
        listener: GenerateProgressListener,
    ): HashMap<String, Any> {
        generating = true
        try {
            val androidHistory = history.map { android.util.Pair(it.first, it.second) }
            return submitFullHistoryNative(nativePtr, androidHistory, listener)
        } finally {
            generating = false
        }
    }

    fun modelId(): String = modelId

    companion object {
        private const val TAG = "MnnLlmSession"

        init {
            System.loadLibrary("mnnllmapp")
        }
    }

    // ───────── native 声明（签名必须与 libmnnllmapp.so 的导出符号一致）─────────

    private external fun initNative(
        configPath: String?,
        history: List<String>?,
        mergedConfigStr: String?,
        configJsonStr: String?,
    ): Long

    private external fun submitNative(
        instanceId: Long,
        input: String,
        keepHistory: Boolean,
        listener: GenerateProgressListener,
    ): HashMap<String, Any>

    private external fun submitFullHistoryNative(
        nativePtr: Long,
        history: List<android.util.Pair<String, String>>,
        progressListener: GenerateProgressListener,
    ): HashMap<String, Any>

    private external fun resetNative(instanceId: Long)

    private external fun releaseNative(instanceId: Long)

    private external fun updateMaxNewTokensNative(llmPtr: Long, maxNewTokens: Int)

    private external fun updateSystemPromptNative(llmPtr: Long, systemPrompt: String)

    private external fun updateConfigNative(llmPtr: Long, configJson: String)

    private external fun getSystemPromptNative(llmPtr: Long): String?

    private external fun dumpConfigNative(llmPtr: Long): String

    private external fun getDebugInfoNative(instanceId: Long): String
}
