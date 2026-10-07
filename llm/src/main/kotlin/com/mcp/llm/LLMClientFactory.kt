package com.mcp.llm

import com.mcp.core.llm.BackendType
import com.mcp.llm.MessageEvent
import kotlinx.coroutines.flow.Flow
import com.mcp.deepseek.AuthPrefs

/**
 * LLM 客户端工厂：按当前后端选择（[com.mcp.deepseek.AuthPrefs.getBackend]）构造对应的 [LLMClient]。
 *
 * - [BackendType.DEEPSEEK_REVERSE] → [DeepSeekReverseClient]（包装现有零依赖 DeepSeekApi）。
 * - [BackendType.OPENAI]          → [OpenAIClient]（OpenAI 兼容协议）。
 * - [BackendType.WEB_AUTOMATION]  → [WebAutomationClient]（驱动网页版 AI 对话框）。
 *
 * Web 自动化的驱动实现位于 app 层（依赖 WebBrowser），llm 模块不能反向引用，
 * 因此这里通过 [webAutomationDriverProvider] 由 app 在启动时注入。未注入时该后端不可用。
 */
object LLMClientFactory {

    /**
     * Web 自动化驱动提供者：app 启动时注册（见 ToolRuntime / AppScope 初始化处）。
     *
     * 用 provider 而非直接持有实例：驱动依赖 Activity/Context 生命周期，
     * 且 WebView 可能被销毁重建，每次 create 时取最新实例更稳。
     */
    /**
         * LiteRT 客户端提供者：app 启动时注册（见 ToolRuntime 初始化处）。
         *
         * 用 provider 而非直接 new：:llm 不依赖 :litert（后者携带 native 库、体积大），
         * 由 app 层把构造好的 [LLMClient] 注入进来，保持依赖方向 :app → :litert → :llm → :core。
         */
        @Volatile
        var litertClientProvider: (() -> LLMClient?)? = null
    
        @Volatile
        var webAutomationDriverProvider: (() -> WebAutomationDriver?)? = null

    /**
     * MNN 客户端提供者：app 启动时注册（见 ToolRuntime 初始化处）。
     *
     * 与 [litertClientProvider] 同理：:llm 不依赖 :mnn（后者携带 native 库、体积大），
     * 由 app 层注入，保持依赖方向 :app → :mnn → :llm → :core。
     */
    @Volatile
    var mnnClientProvider: (() -> LLMClient?)? = null

    fun create(auth: AuthPrefs): LLMClient {
        return when (auth.getBackend()) {
            BackendType.OPENAI -> OpenAIClient(
                OpenAIClientConfig(
                    apiKey = auth.getOpenAIApiKey(),
                    baseUrl = auth.getOpenAIBaseUrl(),
                    model = auth.getOpenAIModel(),
                    temperature = auth.getOpenAITemperature(),
                    maxTokens = auth.getOpenAIMaxTokens(),
                    insecureSkipVerify = auth.getOpenAIInsecureSkipVerify(),
                    reasoningEffort = auth.getOpenAIReasoningEffort()
                )
            )
            BackendType.LITERT -> litertClientProvider?.invoke()
                    ?: NoopLiteRtClient
            BackendType.MNN -> mnnClientProvider?.invoke()
                    ?: NoopMnnClient
                BackendType.WEB_AUTOMATION -> WebAutomationClient(
                auth = auth,
                driver = webAutomationDriverProvider?.invoke() ?: NoopWebAutomationDriver,
            )
        }
    }
}

/**
 * 兜底驱动：app 未注册真实驱动时使用。
 *
 * 不抛异常、不崩溃，只返回明确的可读错误，让用户在会话里看到原因，
 * 而不是遇到一个无法定位的 NPE。
 */
object NoopWebAutomationDriver : WebAutomationDriver {
    override suspend fun ask(
        siteUrl: String,
        prompt: String,
        config: WebAutomationConfig,
        onEvent: (suspend (WebAutomationDriver.Event) -> Unit)?,
    ): WebAutomationDriver.Reply = throw IllegalStateException(
        "Web 自动化驱动尚未就绪（WebView 未初始化）。请确认已打开浏览器页面后重试。"
    )

    override suspend fun isReady(): Boolean = false
}

    
    /**
     * 兜底 LiteRT 客户端：app 未注册 provider 时使用。
     * 返回明确的可读错误，而不是让用户遇到一个无法定位的 NPE。
     */
    object NoopLiteRtClient : LLMClient {
        override val backendType = BackendType.LITERT
        override suspend fun sendMessage(request: LLMRequest): Flow<MessageEvent> =
            kotlinx.coroutines.flow.flowOf(
                MessageEvent.Error(IllegalStateException(
                    "本地模型后端尚未就绪：请在设置中导入 .litertlm 模型后重试。"
                ))
            )
        override suspend fun isAuthenticated(): Boolean = false
    }
    