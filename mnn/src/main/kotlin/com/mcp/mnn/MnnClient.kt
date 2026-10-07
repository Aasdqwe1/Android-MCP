package com.mcp.mnn

import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.mcp.core.llm.BackendType
import com.mcp.llm.MessageEvent
import com.mcp.llm.LLMClient
import com.mcp.llm.LLMRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * MNN 本地模型后端：[LLMClient] 的第五实现。
 *
 * 与 [com.mcp.litert.LiteRtClient] 同为「本地离线推理」，差异在于推理引擎：
 *  - LiteRT 用 Google 的 LiteRT-LM（.litertlm 模型）；
 *  - 本类用阿里的 MNN-LLM（.mnn 模型），复用 MNN Chat 的预编译 native 库。
 *
 * 协议特征：
 *  - 无服务端会话态：历史由上层维护，每轮经 submitFullHistory 全量下发；
 *  - 无服务端 stop 端点：停止生成 = 取消协程，listener 返回 true 即中止 native 循环；
 *  - **暂不支持工具调用**：MNN 的 submitNative 只回文本增量，没有结构化的 tool_call
 *    通道。要在 MNN 上做 function calling，需在文本层解析模型输出的 <tool_call> 标签
 *    （该模型 chat template 里确实带这个协议），属后续增强，当前先提供纯对话能力。
 */
class MnnClient(
    private val config: MnnConfig,
) : LLMClient {

    override val backendType: BackendType = BackendType.MNN

    override suspend fun sendMessage(request: LLMRequest): Flow<MessageEvent> = channelFlow {
        // 组装完整历史：system 在首、其余按序。
        val history = mutableListOf<Pair<String, String>>()
        for (m in request.messages) {
            val text = m.content ?: continue
            // MNN 的 chat template 认识 system / user / assistant 三种角色
            val role = when (m.role) {
                "system", "user", "assistant" -> m.role
                "tool" -> "user"   // 工具结果降级为用户消息（当前无工具通道）
                else -> "user"
            }
            history.add(role to text)
        }
        if (history.none { it.first == "user" }) {
            send(MessageEvent.Error(MnnException("本轮没有用户输入")))
            return@channelFlow
        }

        val session = try {
            MnnEngineHolder.session(config)
        } catch (t: Throwable) {
            send(MessageEvent.Error(if (t is MnnException) t else MnnException("会话初始化失败：${t.message}", t)))
            return@channelFlow
        }

        // 运行期参数覆盖（温度等由 config.json 决定；这里只同步 max_new_tokens）
        runCatching { session.updateMaxNewTokens(config.maxNewTokens) }

        val contentBuf = StringBuilder()
        try {
            // native 的 submitFullHistory 是阻塞调用，增量文本经 listener 回调。
            // 用 withContext(IO) 包住，避免占用 flow 的默认调度器。
            withContext(Dispatchers.IO) {
                val listener = object : GenerateProgressListener {
                    override fun onProgress(progress: String?): Boolean {
                        if (progress == null) return false   // null = 生成结束
                        contentBuf.append(progress)
                        // trySend 不阻塞；channelFlow 的 send 是 suspend，回调线程里不能用
                        trySend(MessageEvent.Content(progress))
                        // 取消检查：协程被取消时返回 true 让 native 尽快退出
                        return !isActive
                    }
                }
                session.submitFullHistory(history, listener)
            }
            send(MessageEvent.Done("", contentBuf.toString()))
        } catch (e: CancellationException) {
            throw e   // 停止生成：正常取消
        } catch (t: Throwable) {
            send(MessageEvent.Error(MnnException("推理失败：${t.message}", t)))
        }
    }

    override suspend fun isAuthenticated(): Boolean = config.isModelAvailable()

    override suspend fun stopStream(token: String, sessionId: String, messageId: String) {
        // MNN 无服务端 stop：取消 collect 协程即可，listener 会返回 true 中止 native 循环。
    }
}
