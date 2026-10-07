package com.mcp.browser

import com.mcp.core.llm.BackendType
import com.mcp.core.llm.usesTextToolProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 浏览器工具的**契约回归测试**（读源码做断言）。
 *
 * 锁住三件容易被无意改回去的事：
 *  1. 两种快照脚本的元素编号顺序必须一致（否则 tree 快照后按 #N 点击会串元素）；
 *  2. 输出给模型的编号记号必须是 #N（曾输出 [N] 而 ref 只认 #N，模型每轮都要试错一次）；
 *  3. 拦截代发路径的关键加固（sanitizeHeaders / copyRequestHeader）不能被删。
 */
class BrowserContractTest {

    private fun src(rel: String): String {
        val candidates = listOf(File(rel), File("../" + rel), File("../app/" + rel))
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码 " + rel + "（尝试过: " + candidates.joinToString { it.path } + "）")
        return f.readText().replace("\r\n", "\n")
    }

    private val webBrowser by lazy { src("src/main/java/com/mcp/browser/WebBrowser.kt") }
    private val browserTools by lazy { src("src/main/java/com/mcp/BrowserTools.kt") }

    @Test
    fun `两种快照都用同一顺序钻 shadowRoot`() {
        val hits = Regex("if \\(el\\.shadowRoot\\) walk\\(el\\.shadowRoot").findAll(webBrowser).count()
        assertEquals("flat 与 tree 各应有一处「边遍历边钻 shadowRoot」：找到 $hits 处", 2, hits)
        assertFalse(
            "不允许再出现「遍历完再回头钻 shadowRoot」的旧写法（会让两种模式编号不一致）",
            webBrowser.contains("对每个元素，若它有 open shadowRoot")
        )
    }

    @Test
    fun `编号记号统一为井号N`() {
        val flatOut = "append(\"#${'$'}i <${'$'}tag> ${'$'}label"
        val treeOut = "append(\"#${'$'}idx <${'$'}tag> ${'$'}label"
        val oldNotation = "\"[${'$'}i] <${'$'}tag>"
        assertTrue("flat 输出应为 #N", webBrowser.contains(flatOut))
        assertTrue("tree 输出应为 #N", webBrowser.contains(treeOut))
        assertFalse("不应再输出方括号记号", webBrowser.contains(oldNotation))
        assertTrue("ref 解析应同时接受 #N 与方括号 N", webBrowser.contains("exec(ref) || /^\\[\\s*(\\d+)\\s*\\]$/"))
    }

    @Test
    fun `拦截代发路径的关键加固还在`() {
        assertTrue("应存在 sanitizeHeaders（逐条回写 Set-Cookie + 可丢 Content-Length）", webBrowser.contains("private fun sanitizeHeaders("))
        assertFalse("旧的 stripCspHeaders 用 firstOrNull() 丢 cookie，应已移除", webBrowser.contains("private fun stripCspHeaders("))
        assertTrue("应丢弃 Accept-Encoding（否则透明解压失效）", webBrowser.contains("private fun copyRequestHeader("))
        assertTrue("Set-Cookie 应写回 CookieManager", webBrowser.contains("CookieManager.getInstance().setCookie(url, raw)"))
    }

    @Test
    fun `工具清单唯一且包含新增能力`() {
        val names = Regex("tool\\(\"([a-z_]+)\"\\)").findAll(browserTools).map { it.groupValues[1] }.toList()
        assertTrue("至少应注册 25 个工具，实际 ${names.size}", names.size >= 25)
        assertEquals("工具名不得重复", names.size, names.toSet().size)
        listOf(
            "browser_list_tabs", "browser_navigate", "browser_click", "browser_type", "browser_snapshot",
            "browser_evaluate", "browser_extract", "browser_screenshot", "browser_status",
            "browser_tabs", "browser_press_key", "browser_hover", "browser_select_option",
            "browser_file_upload", "browser_handle_dialog", "browser_download",
        ).forEach { assertTrue("缺少工具 $it", it in names) }
    }

    @Test
    fun `会话列表输出的是编号而不是内部 id`() {
        assertTrue(browserTools.contains("append(\"[\${t.index}]"))
        assertFalse("不应再把会话 id 当编号输出", browserTools.contains("append(\"[\${t.id}]"))
    }

    @Test
    fun `开发者工具对用户可用且能关掉`() {
        val main = src("src/main/java/com/mcp/MainActivity.kt")
        assertTrue("用户入口必须能开启抓包（曾改成只提示，导致用户打不开面板）", main.contains("WebBrowser.setDevToolsEnabled(true)"))
        assertTrue("用户入口必须能关闭抓包", main.contains("WebBrowser.setDevToolsEnabled(false)"))
        assertTrue("面板接口在未注入时应自动开启，避免点了没反应", webBrowser.contains("if (!devToolsEnabled) setDevToolsEnabled(true)"))
    }

    @Test
    fun `自动化浮窗先拿容器再建会话`() {
        val main = src("src/main/java/com/mcp/MainActivity.kt")
        val showIdx = main.indexOf("BrowserFloatWindow.showAutomation(this@MainActivity)")
        val ensureIdx = main.indexOf("WebBrowser.ensureAutomationStarted(applicationContext)")
        assertTrue("两处调用都应存在", showIdx >= 0 && ensureIdx >= 0)
        assertTrue(
            "showAutomation 必须在 ensureAutomationStarted 之前（否则建出的 WebView 没有父容器）",
            showIdx < ensureIdx
        )
    }

    @Test
    fun `Web自动化的会话列表以网页为准`() {
        val tabs = src("src/main/java/com/mcp/Tabs.kt")
        assertTrue("会话页应对 Web 自动化走后端专用分支", tabs.contains("BackendType.WEB_AUTOMATION -> {") || Regex("backend == BackendType\\.WEB_AUTOMATION[\\s\\S]{0,200}loadWebAutomationSessions").containsMatchIn(tabs))
        assertTrue("应存在网页会话加载实现", tabs.contains("private fun loadWebAutomationSessions("))
        assertTrue("应存在网页会话切换实现", tabs.contains("private fun switchToWebSession("))
        // 本地 id 必须是稳定 UUID（wa-<uuid>），不得再用网页下标当 id
        assertTrue("本地会话 id 应用稳定前缀", tabs.contains("const val WA_SESSION_PREFIX"))
        assertFalse("不得再用网页下标拼本地 id", tabs.contains("id = WEB_SESSION_PREFIX + item.index"))
        // 刷新列表必须按绑定键合并（保留本地 id），不得整体重建
        assertTrue("应存在按 siteKey 合并的实现", tabs.contains("private fun mergeWebSessions("))
        assertTrue("合并实现应使用 siteKey", tabs.contains("siteKey"))
        // 点网页会话 = 进本地聊天窗口（气泡展示/发送/回复获取都在窗口里）+ 后台切站点
        val onClickBranch = tabs.substringAfter("BackendType.WEB_AUTOMATION) {").substringBefore("onLongPress")
        assertTrue("点网页会话必须进本地聊天窗口", onClickBranch.contains("openWindow(s, isNew = false)"))
        assertTrue("点网页会话必须同时把站点切过去", onClickBranch.contains("switchToWebSession(s)"))
        assertTrue("WebBrowser 应提供读取网页会话 API", webBrowser.contains("suspend fun readAutomationSessions("))
        assertTrue("WebBrowser 应提供切换网页会话 API", webBrowser.contains("suspend fun switchAutomationSession("))
        assertTrue("读取与点击必须用同一套收集顺序", webBrowser.contains("SESSION_LIST_JS") && webBrowser.contains("SESSION_CLICK_JS"))
        // 绑定键：读取要提取 siteKey，点击要优先按 siteKey 定位
        assertTrue("条目应提取稳定 siteKey", webBrowser.contains("siteKey: keyOf(node)"))
        assertTrue("点击应支持按 siteKey 定位", webBrowser.contains("wantKey"))
    }

    @Test
    fun `点网页会话后要刷新列表状态且切不过去时宁可报错`() {
        val tabs = src("src/main/java/com/mcp/Tabs.kt")
        val webBrowser = src("src/main/java/com/mcp/browser/WebBrowser.kt")
        // 初次打开只在「页面彻底加载完成后」自动刷新一次（onPageFinished 信号），
        // 不做固定间隔盲轮询、也不在每次切 Tab 时自动刷新
        assertTrue(
            "WebBrowser 应提供等页面彻底加载完成的信号",
            webBrowser.contains("suspend fun awaitAutomationPageLoaded(") && webBrowser.contains("automationPageFinishedAt")
        )
        val loadFn = tabs.substringAfter("fun loadWebAutomationSessions").substringBefore("private fun mergeWebSessions")
        assertTrue("初次打开应等页面彻底加载完再读一次", loadFn.contains("awaitAutomationPageLoaded("))
        assertFalse("初次打开不得固定间隔轮询", loadFn.contains("attempt < 4"))
        // 切回会话 Tab 必须自动刷新（读不到则等页面加载完补读一次）——不能让用户手动点刷新
        assertTrue(
            "切回会话 Tab 应自动刷新列表",
            Regex("fun onHiddenChanged[\\s\\S]{0,800}awaitAutomationPageLoaded").containsMatchIn(tabs)
        )
        // 点完只弹 Toast 不回读 → 列表永远不更新
        assertTrue("切换成功后应重读网页列表刷新 UI", Regex("fun switchToWebSession[\\s\\S]{0,1600}applySessionList").containsMatchIn(tabs))
        // 点击前乐观挪标记，别等回读才动
        val switchFn = tabs.substringAfter("fun switchToWebSession").substringBefore("\n    private fun")
        assertTrue("点击应先乐观更新「当前会话」标记", switchFn.contains("copy(pinned = (it.id == session.id))"))
        // 列表顺序会变：点击 JS 必须带标题兜底，标题对不上宁可报错也不能切进错误的会话
        assertTrue("切换 API 应支持标题兜底参数", webBrowser.contains("title: String = \"\""))
        assertTrue("点击 JS 应按标题兜底匹配", webBrowser.contains("wantTitle"))
        // 单发 el.click() 在只响应 mousedown/pointerdown 的站点切不过去
        val clickJs = webBrowser.substringAfter("private val SESSION_CLICK_JS").substringBefore("})()\"\"\"")
        assertTrue("点击 JS 应派发完整鼠标序列", clickJs.contains("mousedown") && clickJs.contains("mouseup") && clickJs.contains("el.click()"))
    }

    @Test
    fun `选定的会话继续聊，新建会话在站点立即生效`() {
        val tabs = src("src/main/java/com/mcp/Tabs.kt")
        val impl = src("src/main/java/com/mcp/WebAutomationDriverImpl.kt")
        val webBrowser = src("src/main/java/com/mcp/browser/WebBrowser.kt")
        // 驱动只负责输入发送，绝不碰会话定位（曾把用户选定的会话切走/每条消息都新建）
        val askBody = impl.substringAfter("suspend fun ask").substringBefore("private suspend fun typeAndSend")
        assertFalse("驱动发消息不得点新建对话", askBody.contains("newChatSelector"))
        assertFalse("驱动发消息不得点最新会话", askBody.contains(":first-child"))
        // 导航判定必须按 scheme+host（不比路径）：站点选中会话后 URL 变成会话地址，
        // 按前缀比会把用户从选定的会话里导航回新会话页——一发消息就新建了会话
        assertFalse("驱动不得按 siteUrl 前缀判断是否导航", askBody.contains("startsWith(siteUrl"))
        assertTrue("驱动导航应按 host 判断", askBody.contains("automationOnSite(siteUrl)"))
        val main = src("src/main/java/com/mcp/MainActivity.kt")
        assertTrue("弹浮窗的导航也应按 host 判断", main.contains("WebBrowser.automationOnSite(siteUrl)"))
        // 新建会话 = 会话层立即在站点新建（点「新建对话」按钮；没配则回站点首页）
        assertTrue("FAB 新建应触发站点新建", tabs.contains("prepareSiteNewChat(ctx)"))
        val prepFn = tabs.substringAfter("private fun prepareSiteNewChat").substringBefore("/** 点网页会话")
        assertTrue("优先点新建对话按钮", prepFn.contains("clickAutomation(cfg.newChatSelector)"))
        assertTrue("没配按钮则回站点首页", prepFn.contains("navigateAutomation(cfg.siteUrl)"))
        // 站点会自动改名会话（首条消息后命名等），本地标题快照过期是常态：
        // 点击 JS 标题对不上时仍按位置点，不能拒绝——拒绝会让站点停在新对话，再发消息就新建了
        val clickJs = webBrowser.substringAfter("private val SESSION_CLICK_JS").substringBefore("})()\"\"\"")
        assertFalse("标题对不上不得拒绝点击", clickJs.contains("列表里找不到会话"))
    }

    @Test
    fun `Web自动化复用DeepSeek组合器与解析器且只首轮注入`() {
        val bridge = src("src/main/java/com/mcp/ChatBridge.kt")
        val client = src("llm/src/main/kotlin/com/mcp/llm/WebAutomationClient.kt")
        val api = src("llm/src/main/kotlin/com/mcp/deepseek/DeepSeekApi.kt")
        // 首轮注入复用 DeepSeek 组合器：系统提示词+技能+MCP+工具清单+行式调用说明一次到位；
        // 之后每轮只发消息原文（站点会话自身有上下文，重发历史等于重复灌上下文）
        assertTrue("应有站点请求收敛入口", bridge.contains("private fun webAutoSiteRequest"))
        // 注入判定必须从**本地历史**推导（历史里出现助手回复 = 首轮已送达）：
        // 内存标志会被 LRU 淘汰/进程重启丢掉，一丢就反复注入
        assertTrue("首轮应复用 composeDeepSeek 组合", Regex("history\\.none \\{ !it\\.isUser \\}[\\s\\S]{0,300}composeDeepSeek\\(").containsMatchIn(bridge))
        assertFalse("不得再用内存标志判定首轮", bridge.contains("siteInitialized"))
        // 压缩摘要会经 client() 被当成消息打进站点对话——Web 自动化必须跳过本地压缩
        assertTrue("Web 自动化不得走本地压缩", Regex("fun compactOpenAIIfNeeded[\\s\\S]{0,400}WEB_AUTOMATION\\) return").containsMatchIn(bridge))
        // 站点回复里的 tool_call 用 DeepSeek 同一套文本级解析器解析执行（只解析正文，思考区不执行）
        assertTrue("DeepSeekApi 解析器应可复用", api.contains("internal fun parseToolCallFromText"))
        assertTrue("站点回复应解析 tool_call", client.contains("parseToolCallFromText(reply.content)") && client.contains("MessageEvent.ToolCall(id, name, args)"))
        // 工具结果按行式协议回传站点（与 DeepSeek 后端同一段封装）
        assertTrue("工具续聊应复用 lineResults", bridge.contains("ToolMessages.lineResults(results)"))
        // 每个 tool_call id 只消费一次（跨轮台账）：站点复读上一次的调用不能再次执行/回传
        assertTrue("应有跨轮已消费 id 台账", bridge.contains("consumedToolCallIds = LinkedHashSet<String>()"))
        assertTrue("ToolCall 处理应查台账", Regex("is MessageEvent\\.ToolCall[\\s\\S]{0,500}consumedToolCallIds\\.add").containsMatchIn(bridge))
        assertTrue("重新生成/编辑重发应重置台账", Regex("fun truncateHistoryAt[\\s\\S]{0,400}consumedToolCallIds\\.clear").containsMatchIn(bridge))
    }

    @Test
    fun `Web自动化首轮注入随会话预设支持PTC与XML`() {
        // P2：Web 后端首轮复用 composeDeepSeek 并传 ptcRequestContext(style=currentPtcStyle())。
        // 站点侧虽然不支持 function calling，但极简/PTC 预设下引导词与结果回传必须与
        // DeepSeek 后端一致，否则「教 XML 却回 JSON」的不对称会复现。
        val bridge = src("src/main/java/com/mcp/ChatBridge.kt")
        val webSiteIdx = bridge.indexOf("private fun webAutoSiteRequest")
        assertTrue("应有 webAutoSiteRequest", webSiteIdx >= 0)
        val siteBody = bridge.substring(webSiteIdx, minOf(bridge.length, webSiteIdx + 900))
        // Web 首轮注入应传 ptcRequestContext + 当前 PTC 风格
        assertTrue("Web 首轮应传 PTC 上下文", siteBody.contains("ptcRequestContext"))
        // 协议风格按会话解析（style = currentPtcStyle(sid)），不再读全局 PresetPromptOverride
        assertTrue("Web 首轮应传当前 PTC 风格", siteBody.contains("style = currentPtcStyle(sid)"))
        // 结果回传判据必须是「文本协议后端」而非「是否 DeepSeek」——Web 也走 xml/line
        val buildIdx = bridge.indexOf("private fun buildToolResult")
        assertTrue("应有 buildToolResult", buildIdx >= 0)
        val buildBody = bridge.substring(buildIdx, minOf(bridge.length, buildIdx + 900))
        assertTrue("结果回传应按文本协议后端判定", buildBody.contains("textProtocol"))
        // 判据已收敛为共享函数 usesTextToolProtocol（core/llm/BackendType.kt），
        // 消费点是 ChatBridge.buildToolResult 与 SettingsFragment —— 正是为了不再「修一处漏一处」。
        // 所以这里断言两件事，且**都不依赖源码字面量**：
        //   1) 结构性：buildToolResult 确实委托给了共享判定（防止有人在函数里再内联写死一份）；
        //   2) 行为性：Web 自动化确实被算作文本协议后端（这才是本用例真正要守的东西）。
        // 旧写法断言 buildBody 里出现字面量 "BackendType.WEB_AUTOMATION"，
        // 判据一抽走就假红 —— 那是对实现细节的断言，不是对行为的断言。
        assertTrue("结果回传应委托共享判定", buildBody.contains("usesTextToolProtocol"))
        assertTrue("Web 后端应计入文本协议", usesTextToolProtocol(BackendType.WEB_AUTOMATION))
        assertFalse("OpenAI 兼容有原生 function calling，不该走文本协议", usesTextToolProtocol(BackendType.OPENAI))
        // 风格判据改为会话级函数 protocolStyleXmlFor(sid)，不再是全局枚举字面量
        assertTrue("结果回传应有会话级风格分支", buildBody.contains("protocolStyleXmlFor"))
        assertTrue("XML 分支应回传 xmlResult", buildBody.contains("xmlResult"))
    }

    @Test
    fun `气泡选择器拆为思考与正文且等生成结束再取`() {
        val cfg = src("llm/src/main/kotlin/com/mcp/llm/WebAutomationConfig.kt")
        val impl = src("src/main/java/com/mcp/WebAutomationDriverImpl.kt")
        val client = src("llm/src/main/kotlin/com/mcp/llm/WebAutomationClient.kt")
        // 单一「回复气泡选择器」已拆为 思考选择器 + 正文选择器（站点把思考单独渲染时两者都取）
        assertFalse("旧回复气泡选择器应删除", cfg.contains("replySelector"))
        assertTrue("应有思考选择器", cfg.contains("thinkingSelector"))
        assertTrue("应有正文选择器且必填", cfg.contains("contentSelector") && Regex("isUsable[\\s\\S]{0,120}contentSelector\\.isNotBlank").containsMatchIn(cfg))
        // 设置页两个输入框都要绑定
        val settings = src("src/main/java/com/mcp/SettingsFragment.kt")
        assertTrue("设置页应绑定思考/正文输入框", settings.contains("etWebautoThinking") && settings.contains("etWebautoContent"))
        // 驱动分别读取两个容器的最后一个匹配项，返回 Reply(thinking, content)
        val awaitFn = impl.substringAfter("private suspend fun awaitReply").substringBefore("/** evaluateJavascript 回调值")
        assertTrue("驱动应返回 Reply", impl.contains("WebAutomationDriver.Reply("))
        assertTrue("应分别读思考与正文", awaitFn.contains("readContentJs") && awaitFn.contains("readThinkingJs"))
        // 非流式：DOM 整段重渲染，按前缀差分模拟 SSE 会在「改写」处丢字——
        // 驱动只在收工时一次读出思考+正文，中间态不推送
        assertFalse("不得再模拟 SSE 推增量", awaitFn.contains("Event.Thinking(") || awaitFn.contains("Event.Content("))
        assertFalse("awaitReply 不再接收流式回调", awaitFn.contains("onEvent"))
        assertTrue("非流式应保留一次收数注释", impl.contains("不再把中间态差分推给前端"))
        // 生成态状态机：没见过生成态不收工；结束等 500ms；超时不把基线旧气泡当回复
        assertTrue("应跟踪生成态标记", awaitFn.contains("sawGenerating"))
        assertTrue("生成结束应等稳定窗口再取气泡", awaitFn.contains("SETTLE_MS"))
        assertTrue("超时兜底要求见到新内容", awaitFn.contains("last != baseline"))
        // 客户端把思考随 Done 交给前端渲染；非流式：不再传 onEvent、不转发增量事件
        assertTrue("思考应随 Done 上屏", client.contains("thinking = reply.thinking"))
        assertFalse("客户端不得再传流式回调", client.contains("driver.ask(config.siteUrl, prompt, config) {"))
        assertFalse("客户端不得再转发增量事件", client.contains("MessageEvent.Thinking(ev.delta)") || client.contains("MessageEvent.Content(ev.delta)"))
        // 悬浮球（WebView 摘出窗口）→ 页面 hidden → 站点 rAF/可见性渲染停转，采集不到气泡：
        // 读取前必须注入保活补丁（伪造 visible + rAF 泵），超时提示要给展开浮窗的指引
        assertTrue("应有页面保活补丁", impl.contains("PAGE_ALIVE_JS"))
        assertTrue("读取路径应挂保活补丁", Regex("PAGE_ALIVE_JS \\+[\\s\\S]{0,80}querySelectorAll").containsMatchIn(impl))
        assertTrue("超时应提示展开悬浮球", impl.contains("点开悬浮球展开浮窗后重试"))
        // 本地暂停 → **远程先停，本地才能停**：置位远程停止标记，采集循环点站点停止按钮、
        // 做最后一次读取经 Done 自然收尾（最后一段内容不丢）；超时才回退取消本地流。
        // 驱动 stop() 未配停止按钮时回退点发送按钮（此刻它就是停止按钮）。
        val bridge = src("src/main/java/com/mcp/ChatBridge.kt")
        assertTrue(
            "本地停止应先请求远程停止并等自然收尾",
            Regex("requestRemoteStop\\(\\)[\\s\\S]{0,300}REMOTE_STOP_WAIT_MS").containsMatchIn(bridge)
        )
        assertTrue("远程停止超时应回退取消本地流", bridge.contains("回退取消本地流"))
        assertTrue("应有驱动停止标记 API", impl.contains("fun requestRemoteStop()"))
        val stopFn = impl.substringAfter("private suspend fun clickStop").substringBefore("/** evaluateJavascript 回调值")
        assertTrue("未配停止按钮应回退点发送按钮", stopFn.contains("config.sendSelector.isNotBlank() -> config.sendSelector"))
    }

    @Test
    fun `本地会话按当前后端命名空间落盘`() {
        val tabs = src("src/main/java/com/mcp/Tabs.kt")
        val bridge = src("src/main/java/com/mcp/ChatBridge.kt")
        // 新建会话 / 发消息两条创建路径必须跟随当前后端（写死 oa 会让 Web 自动化列表空白）
        assertTrue(Regex("fun createNewSession[\\s\\S]{0,900}backendTag\\(auth\\.getBackend\\(\\)\\)").containsMatchIn(tabs))
        assertTrue(Regex("fun sendPrompt[\\s\\S]{0,1200}backendTag\\(auth\\.getBackend\\(\\)\\)").containsMatchIn(tabs))
        // 历史回灌 / 上下文补齐也要读当前后端命名空间
        assertTrue(Regex("fun loadHistoryIntoWeb[\\s\\S]{0,1600}backendTag\\(auth\\.getBackend\\(\\)\\)").containsMatchIn(tabs))
        assertTrue(Regex("fun ensureOpenAIContextSeeded[\\s\\S]{0,1200}backendTag\\(auth\\.getBackend\\(\\)\\)").containsMatchIn(bridge))
        // 会话列表要能认领"误存在 oa 命名空间"的存量会话
        assertTrue("会话列表缺少存量会话认领逻辑", tabs.contains("误存在 OpenAI 命名空间"))
    }

    @Test
    fun `UA 模式是全局且即时生效`() {
        assertTrue("应存在全局 UA 模式入口", webBrowser.contains("suspend fun setDesktopUaMode("))
        assertFalse(
            "不应再保留「销毁自动化会话等重建」的旧实现（它让开关看起来没生效）",
            webBrowser.contains("fun setAutomationDesktopMode(")
        )
        assertTrue(
            "UA 切换必须作用于所有会话",
            Regex("setDesktopUaMode[\\s\\S]{0,1200}sessions\\.forEach").containsMatchIn(webBrowser)
        )
        assertTrue("新建会话也要按模式设 UA", webBrowser.contains("if (desktopUaMode) UA_DESKTOP else UA_OVERRIDE"))
        assertTrue(
            "设置页切换开关后应立即生效",
            src("src/main/java/com/mcp/SettingsFragment.kt").contains("setDesktopUaMode(")
        )
    }

    @Test
    fun `再点Web自动化也能呼出浮窗`() {
        val settings = src("src/main/java/com/mcp/SettingsFragment.kt")
        assertTrue(
            "「Web 自动化」按钮必须单独挂点击（切换监听在选中项没变时不触发，用户会以为点了没反应）",
            settings.contains("R.id.backend_webauto).setOnClickListener")
        )
        assertTrue("该入口应能呼出自动化浮窗", settings.contains("popOutAutomationWindow()"))
    }

    @Test
    fun `有浮窗时不销毁浏览器会话`() {
        val main = src("src/main/java/com/mcp/MainActivity.kt")
        val idx = main.indexOf("WebBrowser.destroy()")
        assertTrue("MainActivity 应负责退出时释放浏览器", idx >= 0)
        val before = main.substring(maxOf(0, idx - 500), idx)
        assertTrue(
            "销毁前必须确认没有浮窗在显示（系统级浮窗不随 Activity 消失，销毁会让它永久空白）",
            before.contains("isNormalShowing") && before.contains("isAutomationShowing")
        )
    }

    @Test
    fun `容器搬运按用途隔离`() {
        assertTrue(
            "reattachContainer 必须只搬普通会话",
            Regex("reattachContainer[\\s\\S]{0,800}purpose == SessionPurpose.NORMAL").containsMatchIn(webBrowser)
        )
    }
}
