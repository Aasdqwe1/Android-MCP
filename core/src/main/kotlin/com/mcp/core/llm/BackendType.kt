package com.mcp.core.llm

/** 应用可选择的 LLM 后端类型，由协议层和本地数据层共同使用。 */
enum class BackendType {
    OPENAI,

    /**
     * Web 自动化后端：驱动网页版 AI 对话框（采集 / 操作网页），把回复当作模型输出。
     *
     * 与另两个后端的本质差异：
     *  - **没有 SSE 流式**：只能靠轮询 DOM 判断「生成中 / 已完成」，伪流式靠增量 diff；
     *  - **没有 function calling**：网页版不支持工具调用，因此本后端下 Agent 工具能力不可用；
     *  - **依赖 WebView 存活**：不像 HTTP 后端可纯后台，页面必须保持加载；
     *  - **无服务端会话 id**：对话历史由本地维护（与 OPENAI 同为无状态路径）。
     */
    WEB_AUTOMATION,
    
        /**
         * LiteRT-LM 本地模型后端：模型完全离线运行在设备上。
         *
         * 与另三个后端的本质差异：
         *  - **无网络**：不联网、不上云，数据不出设备；
         *  - **无服务端会话态**：对话历史由本地维护（与 OPENAI 同为无状态路径）；
         *  - **无服务端 stop 端点**：停止生成靠取消协程；
         *  - **依赖模型文件**：需用户预先导入 .litertlm 模型。
         */
        LITERT,

        /**
         * MNN-LLM 本地模型后端：阿里的端侧推理引擎，模型为 .mnn 格式。
         *
         * 与 [LITERT] 同为离线本地推理，差异只在引擎与模型格式：
         *  - **无网络**：数据不出设备；
         *  - **无服务端会话态**：历史由上层维护；
         *  - **无服务端 stop 端点**：停止生成靠取消协程；
         *  - **依赖模型文件**：需用户预先准备含 config.json 的 .mnn 模型目录。
         */
        MNN
    }

/**
 * 当前后端是否已具备发出请求的凭据。
 *
 * DeepSeek 逆向后端依赖服务端会话态，必须有 token；OpenAI 兼容后端无状态，
 * 凭据只是 Base URL / API Key，**不需要也不应该要求 DeepSeek 登录**。
 *
 * 为什么单独抽出来：这个判定原先在 ChatBridge.checkHealth 与 WebApiServer.health
 * 各写了一份「token != null」，结果修好了一处、漏掉另一处——选 OpenAI 后端的用户
 * 在网页端会看到连接指示器恒为「未连接」，并弹出「尚未登录」的误导横幅。
 * 收敛成单一函数，就是为了不再出现「修一处漏一处」。
 */
fun backendCredentialsSatisfied(backend: BackendType, hasDeepSeekToken: Boolean): Boolean =
    // DeepSeek 后端已移除。现在所有后端都不需要 DeepSeek token：
    // OpenAI/LiteRT/MNN 靠各自配置，Web 自动化靠 WebView 页面就绪。
    // 保留 hasDeepSeekToken 形参只为签名兼容（调用方多处传参），恒不参与判定。
    true

/**
 * 该后端是否使用**文本工具调用协议**（模型在正文里写调用，宿主解析）。
 *
 * true：DeepSeek 逆向（行式 / DSML）、Web 自动化（网页版无原生 function calling）。
 * false：OpenAI 兼容（原生 tools 参数）、LiteRT 本地（由 LiteRT 适配层自行处理）。
 *
 * 消费点两处，必须同源：
 *  - `ChatBridge.buildToolResult`：决定工具结果封成行式块、DSML 还是 tool 角色 JSON；
 *  - `SettingsFragment`：决定「工具调用协议风格」这一项是否展示（对原生 FC 后端无意义）。
 * 原先判据在 buildToolResult 内联写死，UI 再写一份就会「修一处漏一处」——
 * 与 [backendCredentialsSatisfied] 同理，收敛为单一函数。
 */
fun usesTextToolProtocol(backend: BackendType): Boolean =
    // DeepSeek 后端已移除，文本工具调用协议现在只剩 Web 自动化在用。
    backend == BackendType.WEB_AUTOMATION
