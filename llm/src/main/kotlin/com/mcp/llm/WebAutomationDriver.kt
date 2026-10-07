package com.mcp.llm

/**
 * Web 自动化驱动接口。
 *
 * 为什么定义在 llm 模块而不是直接用 app 的 WebBrowser：
 *   app ──依赖──> llm ──依赖──> core
 * 若 llm 反向引用 WebBrowser 会形成循环依赖。因此这里只声明契约，
 * 由 app 层实现（WebAutomationDriverImpl，内部用 WebBrowser）并在构造 [WebAutomationClient] 时注入。
 *
 * 与 HTTP 后端的本质差异（实现方需注意）：
 *  - 无 SSE：只能轮询 DOM 判断生成状态；[ask] 为**非流式**——中间态不推送，
 *    生成结束后一次读出思考 + 正文全文（[Event] 保留仅为历史兼容，实现方无需发出）；
 *  - 无 function calling：网页版不支持工具调用；
 *  - 依赖 WebView 存活：实现方需自行保证页面可用（必要时 navigate 到站点）；
 *  - 需要登录态：用户在 WebView 里手动登录后 cookie 生效，本接口不管登录。
 */
interface WebAutomationDriver {

    /** 一次站点回复：思考区与正文分开取（站点单独渲染思考时两者都可能有值）。 */
    data class Reply(val thinking: String, val content: String)

    /**
     * 流式增量（打字机）：思考 / 正文各自独立推送，delta 为相对上一次的**新增后缀**。
     *
     * 已不再使用：DOM 是整段重渲染的，按前缀差分无法可靠表达「改写」，
     * 中间态会与最终态不一致并造成气泡缺字。现约定为**非流式**——实现方不推送任何
     * [Event]，只在 [ask] 返回时给出思考 + 正文全文，由客户端单发一次 Done。
     */
    @Deprecated("非流式：不再推送中间态增量，收工时一次返回全文")
    sealed class Event {
        data class Thinking(val delta: String) : Event()
        data class Content(val delta: String) : Event()

        /**
         * 思考区**整段替换**：站点整段重渲染，新文本不再是旧基准的前缀时发出。
         *
         * 增量模型（[Thinking]）无法表达「改写」，若静默丢弃这一轮，站点重排
         * （Markdown 标记回改、折叠展开）造成的新增内容会永久缺失——表现为
         * 「写盘完整、气泡缺字」。故改用整段覆盖。
         */
        data class ThinkingReplace(val full: String) : Event()

        /** 正文**整段替换**：理由同 [ThinkingReplace]。 */
        data class ContentReplace(val full: String) : Event()
    }

    /**
     * 把 [prompt] 投递给指定站点并等待回复完成。
     *
     * 实现方职责：
     *  1. 确保 [siteUrl] 已加载（未加载则 navigate）；
     *  2. 定位输入框，填入 [prompt]，触发发送；
     *  3. 轮询判断生成是否结束；**非流式**——中间态不推送（[onEvent] 仅为历史兼容，
     *     实现方可不调用），生成结束后一次读出思考 + 正文，以返回的 [Reply] 全文为准；
     *  4. 轮询回复容器，直到「生成中」标志消失或超时；
     *  5. 分别读取思考容器与正文容器的**最后一个**匹配项返回。
     *
     * @param siteUrl  网页版 AI 站点地址（来自 [WebAutomationConfig.siteUrl]）
     * @param prompt   本轮要发送的提示（首轮含系统提示词+工具清单+行式调用说明，见 [WebAutomationClient]）
     * @param config   站点适配配置（选择器等）
     * @param onEvent  历史兼容参数（旧流式回调）。非流式实现不调用；可为 null
     * @return 思考区 + 正文（均为最终态全文）
     */
    suspend fun ask(
        siteUrl: String,
        prompt: String,
        config: WebAutomationConfig,
        onEvent: (suspend (Event) -> Unit)? = null,
    ): Reply

    /**
     * 停止当前生成（best-effort）。
     *
     * 网页版一般有「停止生成」按钮，实现方点击之；找不到按钮时静默返回。
     */
    suspend fun stop() {}

    /** 驱动是否可用（如 WebView 未就绪 / 未实现时返回 false）。 */
    suspend fun isReady(): Boolean = true
}
