package com.mcp

import android.content.Context
import com.mcp.browser.WebBrowser
import com.mcp.llm.WebAutomationConfig
import com.mcp.llm.WebAutomationDriver
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Web 自动化驱动实现：用内嵌 [WebBrowser] 驱动网页版 AI 对话框。
 *
 * 工作流程（模板阶段）：
 *  1. 确保 WebView 就绪（[WebBrowser.ensureStarted]）；
 *  2. 若当前不在目标站点，navigate 过去；
 *  3. 往输入框写入 prompt 并触发发送；
 *  4. 轮询思考/正文容器，直到「生成中」标志消失（状态机见 [awaitReply]）；
 *  5. 分别返回思考与正文的最终全文。
 *
 * 已知限制（后续逐步完善）：
 *  - **非流式**：按 [POLL_MS] 轮询 DOM 只为判定「生成是否结束」，不把中间态推给前端；
 *    生成结束（或停止/超时兜底）后**一次性**读出思考 + 正文的最终全文，由上层单发一次 Done。
 *    早先的「模拟 SSE 打字机」（前缀增长发后缀 / 非前缀改发整段替换）已移除：DOM 是整段重渲染的，
 *    增量模型无法可靠表达「改写」，中间态与最终态不一致会直接造成气泡缺字；
 *  - **登录态**：站点需登录时，用户须先在浏览器页手动登录，cookie 才会生效；
 *  - **选择器**：各站点的 DOM 规则见 [WebAutomationConfig]，为占位模板需按真实页面校正；
 *  - **无 tools**：网页版不支持 function calling。
 */
class WebAutomationDriverImpl(
    private val context: Context,
    private val auth: com.mcp.deepseek.AuthPrefs,
) : WebAutomationDriver {

    override suspend fun isReady(): Boolean {
        return runCatching { WebBrowser.ensureAutomationStarted(context) == null }.getOrDefault(false)
    }

    override suspend fun ask(
        siteUrl: String,
        prompt: String,
        config: WebAutomationConfig,
        onEvent: (suspend (WebAutomationDriver.Event) -> Unit)?,
    ): WebAutomationDriver.Reply {
        // onEvent 保留仅为兼容接口签名：本实现为**非流式**，不再推送中间态增量
        // （思考与正文在生成结束后一次收数，由上层单发一次 Done）。
        waLogReset()
        waLog("=== ask start site=$siteUrl promptLen=${prompt.length} content=${config.contentSelector} stop=${config.stopSelector} send=${config.sendSelector}")
        // 1) 确保**自动化专用** WebView 就绪（独立会话 + 独立容器）
        WebBrowser.ensureAutomationStarted(context)?.let { err ->
            throw IllegalStateException(err)
        }
        // 2) 只在**不在该站点上**时才导航（按 scheme+host 判断，不比路径）：
        //    站点选中会话后 URL 会变成会话地址，按前缀比会把用户从选定的会话里
        //    导航回 siteUrl（新会话页），一发消息就新建了会话。
        if (!WebBrowser.automationOnSite(siteUrl)) {
            WebBrowser.navigateAutomation(siteUrl)
            delay(1500) // 等首屏 JS 渲染出输入框
        }
        // 2.5) **不做任何会话准备**：会话定位属于会话层——
        //   - 从会话页点网页会话：SessionFragment 已把站点切到那条会话（继续会话）；
        //   - 新建会话：createNewSession 已立即在站点新建（点新建按钮/回站点首页）。
        //   在这里点「新建对话」/「最新一条」会把用户选定的会话切走，回复进错会话。
        // 3) 写入并发送（返回发送前一刻发送按钮的「空闲态签名」，供生成结束判定）
        val idleButtonSig = typeAndSend(prompt, config)
        waLog("sent idleButtonSig.len=${idleButtonSig.length} sig=${idleButtonSig.take(60)}")
        // 4) 持续轮询只用于判定生成结束；结束后一次读出思考 + 正文全文
        return awaitReply(config, idleButtonSig)
    }

    override suspend fun stop() {
        clickStop(auth.getWebAutomationConfig())
    }

    /** 点击站点「停止生成」：优先 stopSelector，未配置时回退发送按钮（生成中即停止按钮）。 */
    private suspend fun clickStop(config: WebAutomationConfig) {
        val sel = when {
            config.stopSelector.isNotBlank() -> config.stopSelector
            config.sendSelector.isNotBlank() -> config.sendSelector
            else -> return
        }
        val quoted = JSONObject.quote(sel)
        runCatching {
            WebBrowser.evalRawAutomation(
                "(function(){var b=document.querySelector(" + quoted + ");" +
                    "if(b){b.click();return 'clicked';}return 'none';})()"
            )
        }
    }

    // ───────── 内部 ─────────

    /**
     * 往输入框写入文本并触发发送。
     *
     * 实现复用 WebBrowser 的 [WebBrowser.typeAutomation] / [WebBrowser.pressEnterAutomation]
     * （与 browser_type / browser_press_key 同一套 JS），不再在这里维护第二份选择器解析逻辑。
     */
    private suspend fun typeAndSend(prompt: String, config: WebAutomationConfig): String {
        if (config.inputSelector.isBlank()) {
            throw IllegalStateException("未配置输入框选择器（设置 → LLM 后端 → Web 自动化 → 输入框选择器）。")
        }
        val typed = WebBrowser.typeAutomation(config.inputSelector, prompt)
        val failed = typed == null || typed.contains("\"error\"")
        if (failed) {
            throw IllegalStateException(
                "未找到输入框（选择器：${config.inputSelector}）。该站点 DOM 可能已变化，请在设置里校正选择器" +
                    "（可用 browser_snapshot / browser_evaluate 确认元素是否还在）。"
            )
        }
        delay(300)
        // 发送：优先点按钮，其次回车（两者都复用同一套 JS）
        val sendSel = config.sendSelector
        if (config.useEnterToSend || sendSel.isBlank()) {
            WebBrowser.pressEnterAutomation()
            return ""
        }
        // 点击前一刻记录按钮「空闲态（发送）签名」：很多站点点击后同一个按钮会变成停止按钮，
        // 之后变回发送即生成结束——没配 stopSelector 时就用签名回归判定（见 [awaitReply]）。
        // 取 innerHTML（不含按钮自身属性）：生成结束按钮常回到禁用态，disabled 差异不算状态变化。
        val idleSig = buttonSignature(sendSel)
        val clicked = WebBrowser.clickAutomation(sendSel)
        if (clicked == null || clicked.contains("\"error\"")) {
            LogStore.w(TAG, "发送按钮选择器失配，退回回车发送：$sendSel")
            WebBrowser.pressEnterAutomation()
        }
        return idleSig
    }

    /** 发送按钮的当前签名（innerHTML 归一化；按钮不存在返回空串，也能与空闲态区分开）。 */
    private suspend fun buttonSignature(selector: String): String {
        if (selector.isBlank()) return ""
        val js = PAGE_ALIVE_JS + "(function(){var b=document.querySelector(" + JSONObject.quote(selector) + ");" +
            "if(!b)return '';" +
            "return (b.innerHTML||'').replace(/\\s+/g,' ').trim().slice(0,800);})()"
        return runCatching { decodeJsString(WebBrowser.evalRawAutomation(js)) }.getOrDefault("")
    }

    /**
     * 轮询等待回复完成，然后**一次**读出思考 + 正文全文返回。
     *
     * 本实现为非流式：轮询只用于判定「生成是否结束」，中间态一律不推给前端。
     * DOM 是整段重渲染的（Markdown 标记回改、列表符补全、代码高亮重排、思考区折叠），
     * 按前缀差分模拟流式必然在「改写」处丢字——故收敛为结束后一次收数。
     *
     * 生成中信号（任一即可，与站点行为对齐的状态机）：
     *  - 停止按钮出现（配置了 stopSelector）；
     *  - 发送按钮签名偏离空闲态（[idleButtonSig]，很多站点点击后同一按钮变成停止按钮）；
     *  - 正文开始变化（退化路径）。
     *
     * 完成判定：
     *  1. 没见过生成态绝不收工——否则会把上一条旧气泡/刚发出的提问当成回复；
     *  2. 配了按钮信号时，必须**真实见过「生成中」按钮**（sawBusy）才能收工：站点点击发送后会先把
     *     用户消息（工具续聊轮即 tool_result 文本）插进 DOM，此刻按钮尚未翻转——只看「正文变了」就
     *     收工会把刚发出的用户消息当成回复（真机表现：工具结果发出去了，站点的 LLM 回复没抓回来）；
     *  3. 信号消失（停止按钮变回发送 / 签名回归空闲态）且**见到了新内容**（正文或思考偏离基线）
     *     → 等 [SETTLE_MS]（500ms）让最后一段渲染完，再读最终思考/正文；
     *  4. 没有按钮信号（或配了却一直没观察到）时，退化为正文连续稳定若干轮（宽限窗口见
     *     [ENTER_GRACE_MS]，避开「发送后用户消息先入 DOM」的窗口）。
     */
    private suspend fun awaitReply(
        config: WebAutomationConfig,
        idleButtonSig: String,
    ): WebAutomationDriver.Reply {
        val contentSel = JSONObject.quote(config.contentSelector)
        val thinkingSel = JSONObject.quote(config.thinkingSelector)
        // 悬浮球状态下 WebView 摘出了窗口，页面 visibilityState=hidden，很多站点的流式渲染
        // （rAF / 可见性驱动）会停转——回复文本进不了 DOM，采集到的永远是旧基线。
        // 每次读取前注入「保活补丁」：伪造 visible + 把 rAF 换成 setTimeout 泵 + 补发事件。
        // 读取「同一条消息」容器内的所有匹配段并按文档顺序拼接。
        //
        // 为什么不能只取最后一个匹配项：一条回答常被站点拆成多段（正文段 → 代码块 → 尾段，
        // 每段各是一个 .markdown-body）。旧实现 `return els[els.length-1]` 只拿到最后一段，
        // 前面的正文全丢——真机表现就是「抓取不全」。这里从最后一个匹配项向上找它所属的
        // 消息容器（class 含 answer/message/thinking/content 等语义名），把容器内的匹配段全量拼接。
        // 容器内只有它自己时，等价于旧行为（只取最后一段），不会误伤单段站点。
        fun readContainerJs(selLiteral: String): String =
            PAGE_ALIVE_JS +
                "(function(){" +
                "var els=document.querySelectorAll(" + selLiteral + ");" +
                "if(!els.length)return '';" +
                "var last=els[els.length-1];" +
                "var RE=/(answer|message|bubble|thinking|reply|response|content)/i;" +
                "var box=null,node=last.parentElement;" +
                "for(var d=0;node&&node!==document.body&&d<12;d++,node=node.parentElement){" +
                "var cn=(typeof node.className==='string')?node.className:'';" +
                "if(RE.test(cn)){box=node;break;}" +
                "}" +
                "var out=[];" +
                "if(box){for(var i=0;i<els.length;i++){if(box.contains(els[i]))out.push(els[i].innerText||els[i].textContent||'');}}" +
                "if(!out.length)out.push(last.innerText||last.textContent||'');" +
                "return out.join('\\n');" +
                "})()"
        val readContentJs = readContainerJs(contentSel)
        val readThinkingJs = readContainerJs(thinkingSel)
        val hasStopSel = config.stopSelector.isNotBlank()
        val stopSel = JSONObject.quote(config.stopSelector)
        val busyJs = if (hasStopSel) PAGE_ALIVE_JS + "(function(){return !!document.querySelector(" + stopSel + ");})()" else null
        val hasThinkingSel = config.thinkingSelector.isNotBlank()
        // 发送按钮签名可用 = 空闲态签名非空（按钮存在且被记录）。生成中签名会偏离，结束即回归
        val hasButtonSig = idleButtonSig.isNotEmpty() && config.sendSelector.isNotBlank()
        // 是否配置了任何「生成中」按钮信号（停止按钮 / 发送按钮签名）
        val signalsConfigured = hasStopSel || hasButtonSig

        suspend fun readThinking(): String =
            if (hasThinkingSel) decodeJsString(WebBrowser.evalRawAutomation(readThinkingJs)) else ""

        // 发送时刻的基线（正文+思考）：收工必须见到偏离基线的新内容，
        // 否则会把上一条旧气泡/刚发出的提问当成回复
        var baseline = decodeJsString(WebBrowser.evalRawAutomation(readContentJs))
        val baselineThinking = readThinking()
        waLog("baseline contentLen=${baseline.length} thinkingLen=${baselineThinking.length} signalsConfigured=$signalsConfigured hasStop=$hasStopSel hasBtn=$hasButtonSig")
        var last = baseline
        var lastThinking = baselineThinking
        var stable = 0
        var sawGenerating = false
        // 是否**真实**观察到过「生成中」按钮信号（busy==true）。与 sawGenerating 的区别很关键：
        // sawGenerating 也会被「正文内容变化」置位，而站点点击发送后会先把用户消息（工具续聊轮
        // 即 tool_result 文本）插进 DOM，此刻按钮尚未翻转——若只看 sawGenerating 就收工，会把刚
        // 发出的用户消息当成回复（真机表现：工具结果发出去了，站点的 LLM 回复却没被抓回来）。
        var sawBusy = false
        remoteStopRequested = false   // 清掉上一次可能残留的停止标记，避免新发送被误停
        val startAt = System.currentTimeMillis()
        val deadline = startAt + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_MS)
            val text = decodeJsString(WebBrowser.evalRawAutomation(readContentJs))
            val thinking = readThinking()
            // 非流式：这里只更新「上一轮基线」用于变化检测（判定生成是否开始 / 是否稳定），
            // 不再把中间态差分推给前端。最终全文统一在收工处一次读出。
            val changedContent = text.isNotEmpty() && text != last
            lastThinking = thinking
            last = text
            // 本地请求的远程停止：点站点停止按钮（消费标记一次），随后信号消失 → 自然收工
            if (remoteStopRequested) {
                remoteStopRequested = false
                clickStop(config)
            }
            val stopBusy = if (busyJs != null) decodeJsString(WebBrowser.evalRawAutomation(busyJs)).contains("true") else false
            // 发送按钮签名偏离空闲态 = 按钮已变成停止（生成中）；回归空闲 = 生成结束
            val buttonBusy = hasButtonSig && buttonSignature(config.sendSelector) != idleButtonSig
            val busy = stopBusy || buttonBusy
            val sawNewContent = last != baseline || lastThinking != baselineThinking
            // 发送后的「进入生成态」宽限窗口：站点插入用户消息与按钮翻转之间存在时延，
            // 此窗口内一律不收工，避免把刚发出的用户消息当成回复。
            val gracePassed = System.currentTimeMillis() - startAt >= ENTER_GRACE_MS
            waLog("t=${System.currentTimeMillis() - startAt} textLen=${text.length} thinkLen=${thinking.length} changed=$changedContent stopBusy=$stopBusy btnBusy=$buttonBusy busy=$busy sawNew=$sawNewContent stable=$stable sawBusy=$sawBusy sawGen=$sawGenerating grace=$gracePassed lastLen=${last.length}")
            when {
                // ① 生成中：任一信号在
                busy -> {
                    sawBusy = true
                    sawGenerating = true
                    stable = 0
                }
                // ② 信号消失且已见到新内容 = 生成结束：等 SETTLE_MS 让最后一段渲染完，再读最终思考/正文。
                //    配了按钮信号时必须先真实见过生成中（sawBusy）；未配信号时须过宽限窗口——二者都为了
                //    避开「发送后用户消息先入 DOM、按钮还没翻转」那一轮被误判成结束。
                sawGenerating && sawNewContent && (sawBusy || (!signalsConfigured && gracePassed)) -> {
                    delay(SETTLE_MS)
                    waLog("DONE(branch2) contentLen=${last.length} thinkLen=${lastThinking.length} sawBusy=$sawBusy")
                    return WebAutomationDriver.Reply(
                        thinking = readThinking().ifEmpty { lastThinking },
                        content = decodeJsString(WebBrowser.evalRawAutomation(readContentJs)).ifEmpty { text },
                    )
                }
                // ③ 无按钮信号（或信号还没来）：正文开始变化即视为进入生成态
                changedContent -> {
                    sawGenerating = true
                    stable = 0
                }
                text.isNotEmpty() -> stable++
            }
            // 退化收工：正文连续稳定若干轮不变。适用两种情形：
            //  a) 站点本就没有可配置的按钮信号（!signalsConfigured）；
            //  b) 配了按钮信号却一直没观察到「生成中」——过宽限窗口后按正文稳定兜底，
            //     避免站点不翻按钮时永久挂到超时。
            val stabilityFallback =
                (!signalsConfigured && sawGenerating && stable >= STABLE_ROUNDS) ||
                    (signalsConfigured && !sawBusy && sawGenerating && gracePassed && stable >= SIGNAL_LESS_STABLE_ROUNDS)
            if (stabilityFallback) {
                delay(SETTLE_MS)
                waLog("DONE(fallback) contentLen=${last.length} thinkLen=${lastThinking.length} stable=$stable sawBusy=$sawBusy")
                return WebAutomationDriver.Reply(
                    thinking = readThinking().ifEmpty { lastThinking },
                    content = decodeJsString(WebBrowser.evalRawAutomation(readContentJs)).ifEmpty { text },
                )
            }
        }
        // 超时兜底：只有真的见过**新内容**才返回，绝不把基线旧气泡当回复
        waLog("TIMEOUT lastLen=${last.length} baselineLen=${baseline.length} sawBusy=$sawBusy sawGen=$sawGenerating")
        if (last.isNotEmpty() && last != baseline) return WebAutomationDriver.Reply(lastThinking, last)
        val ballHint = if (!com.mcp.floatwin.BrowserFloatWindow.isAutomationShowing)
            "（当前是悬浮球状态，部分站点会暂停页面渲染；若一直收不到回复，点开悬浮球展开浮窗后重试）" else ""
        throw IllegalStateException("等待回复超时（${TIMEOUT_MS / 1000} 秒）。可能站点未登录、选择器失配或页面未就绪。$ballHint")
    }

    /**
     * Web 自动化诊断日志：同时进 LogStore（App 日志页）与 files/logs/webauto.log（落盘便于导出）。
     * 每次 ask 开头重置文件，只保留本次调用的轨迹。
     */
    private fun waLog(msg: String) {
        LogStore.i(TAG, msg)
        runCatching {
            val f = java.io.File(context.filesDir, "logs/webauto.log")
            f.parentFile?.mkdirs()
            f.appendText(System.currentTimeMillis().toString() + " " + msg + "\n")
        }
    }

    /** 重置诊断日志（每次 ask 开头调用，只保留本次调用轨迹）。 */
    private fun waLogReset() {
        runCatching {
            val f = java.io.File(context.filesDir, "logs/webauto.log")
            f.parentFile?.mkdirs()
            f.writeText("")
        }
    }

    /** evaluateJavascript 回调值是 JSON 编码的字符串，解码成原始文本。 */
    private fun decodeJsString(raw: String?): String {
        if (raw == null || raw == "null" || raw == "undefined") return ""
        return runCatching {
            // raw 形如 "\"文本\""（带引号且已转义）
            org.json.JSONTokener(raw).nextValue()?.toString() ?: raw
        }.getOrDefault(raw)
    }

    companion object {
        private const val TAG = "WebAutomation"

        /** 远程停止请求标记（进程级）：本地停止时置位，采集循环下一轮（≤500ms）消费并点站点停止按钮。 */
        @Volatile
        private var remoteStopRequested = false

        /** 本地停止 → 远程停止：由 ChatBridge.stopStream 调用；采集循环消费后点站点停止按钮并自然收尾。 */
        fun requestRemoteStop() {
            remoteStopRequested = true
        }

        /**
         * 悬浮球/后台页保活补丁：WebView 摘出窗口后页面 visibilityState=hidden，
         * 站点的 rAF 驱动渲染与可见性相关更新会停转（回复文本进不了 DOM）。
         * 首次注入即生效（__mcpAlive 标记防重复）：伪造 visible、把 rAF 换成
         * setTimeout 泵、补发 visibilitychange/focus。随后每次求值空跑一次，开销可忽略。
         */
        private val PAGE_ALIVE_JS = """(function(){
  try {
    var d = document;
    if (!d.__mcpAlive) {
      d.__mcpAlive = true;
      try { Object.defineProperty(d, 'visibilityState', { configurable: true, get: function(){ return 'visible'; } }); } catch (e) {}
      try { Object.defineProperty(d, 'hidden', { configurable: true, get: function(){ return false; } }); } catch (e) {}
      try { window.requestAnimationFrame = function(cb){ return setTimeout(function(){ try { cb(Date.now()); } catch (e2) {} }, 16); }; } catch (e) {}
      try { d.dispatchEvent(new Event('visibilitychange')); } catch (e) {}
      try { window.dispatchEvent(new Event('focus')); } catch (e) {}
    }
  } catch (e) {}
})();"""

        /** 轮询间隔（打字机采集周期）。 */
        private const val POLL_MS = 500L
        /** 等待回复的总超时。 */
        private const val TIMEOUT_MS = 180_000L
        /** 生成结束（停止按钮变回发送 / 正文稳定）后再等这段时间，让最后一段渲染完才取气泡。 */
        private const val SETTLE_MS = 500L
        /** 未配置停止按钮时，正文连续稳定多少轮才算生成完成。 */
        private const val STABLE_ROUNDS = 2
        /**
         * 发送后「进入生成态」的最短宽限：站点先把用户消息（工具续聊轮即 tool_result 文本）插进 DOM，
         * 之后按钮才翻转为「停止」。此窗口内不收工，避免把刚发出的用户消息当成站点回复抓回来。
         */
        private const val ENTER_GRACE_MS = 1500L
        /** 配了按钮信号却一直没观察到「生成中」时，正文连续稳定多少轮才兜底收工（比 [STABLE_ROUNDS] 更保守）。 */
        private const val SIGNAL_LESS_STABLE_ROUNDS = 4
    }
}
