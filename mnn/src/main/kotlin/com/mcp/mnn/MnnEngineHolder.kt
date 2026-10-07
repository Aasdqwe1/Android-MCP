package com.mcp.mnn

import android.util.Log
import com.alibaba.mnnllm.android.llm.LlmSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MNN 引擎与会话的生命周期管理器。
 *
 * 与 [com.mcp.litert.LiteRtEngineHolder] 对应，但语义更简单：
 * MNN 的 LlmSession 本身就持有 nativePtr 与 KV cache，一个会话一个实例即可，
 * 不需要 LiteRT 那套「按历史条数重建 Conversation」的补偿逻辑——
 * 因为我们走 submitFullHistory，每轮 native 都按传入的完整历史重算，
 * 天然与上层无状态接口一致。
 *
 * 线程安全：engine/session 的读写都在 synchronized 内；
 * 模型加载耗时数秒，放在 IO 上下文执行。
 */
object MnnEngineHolder {

    private const val TAG = "MNN"

    @Volatile private var session: LlmSession? = null
    @Volatile private var currentKey: String? = null

    /** 当前是否有已加载的会话。 */
    fun isReady(): Boolean = session != null

    /** 当前加载的模型目录（诊断用）。 */
    fun currentModelDir(): String? = currentKey?.substringBefore('|')

    /**
     * 取（或初始化）会话。配置变化时释放旧会话、重建。
     * 必须在 IO 上下文调用——模型加载会阻塞数秒。
     */
    suspend fun session(cfg: MnnConfig): LlmSession = withContext(Dispatchers.IO) {
        if (!cfg.isModelAvailable()) {
            throw MnnException("模型不可用：${cfg.configPath} 不存在（需包含 config.json 与权重文件）")
        }
        val key = cfg.engineKey()
        session?.takeIf { currentKey == key }?.let { return@withContext it }

        synchronized(this@MnnEngineHolder) {
            session?.release()
            session = null
            currentKey = null
        }

        val s = LlmSession(
            modelId = cfg.modelDir,
            sessionId = System.currentTimeMillis().toString(),
            configPath = cfg.configPath,
        )
        try {
            s.load(cfg.toMergedConfigJson(), cfg.toConfigMapJson())
        } catch (t: Throwable) {
            runCatching { s.release() }
            throw MnnException("MNN 模型加载失败：${t.message}", t)
        }

        synchronized(this@MnnEngineHolder) {
            session = s
            currentKey = key
        }
        Log.i(TAG, "引擎就绪：${cfg.modelDir} / ${cfg.backendType}")
        s
    }

    /** 释放当前会话（切换模型或退出时调用）。 */
    fun release() {
        synchronized(this@MnnEngineHolder) {
            runCatching { session?.release() }
            session = null
            currentKey = null
        }
    }
}
