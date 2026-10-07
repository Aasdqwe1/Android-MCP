package com.mcp.llm

import com.mcp.core.llm.BackendType
import com.mcp.llm.MessageEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * MNN 后端的兜底实现：app 层未注入 [LLMClientFactory.mnnClientProvider] 时使用。
 *
 * 遵循「错误必须可读，不许静默失败」原则：返回明确的配置指引，而不是抛 NPE。
 */
object NoopMnnClient : LLMClient {
    override val backendType: BackendType = BackendType.MNN

    override suspend fun sendMessage(request: LLMRequest): Flow<MessageEvent> = flow {
        emit(MessageEvent.Error(Exception(
            "MNN 后端未启用：请先在设置中配置 .mnn 模型目录（含 config.json），再切换到该后端。"
        )))
    }

    override suspend fun isAuthenticated(): Boolean = false
}
