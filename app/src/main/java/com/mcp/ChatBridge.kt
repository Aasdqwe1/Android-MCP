package com.mcp

import android.content.Context
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.mcp.LogStore
import com.mcp.core.prompt.modelDirectToolAllowed
import com.mcp.core.prompt.ptcDirectCallRejection
import com.mcp.core.prompt.ptcRequestContext
import com.mcp.compaction.CompactionTransaction
import com.mcp.compaction.ContextCompactor
import com.mcp.compaction.ToolResultSpill
import com.mcp.compaction.SurfaceChangedException
import com.mcp.chat.MessageIdSpace
import com.mcp.core.chat.ChatMessage
import com.mcp.core.chat.ToolCallData
import com.mcp.core.prompt.PresetPromptOverride
import com.mcp.core.prompt.SystemPromptComposer
import com.mcp.core.skill.SkillRepository
import com.mcp.deepseek.AuthPrefs

import com.mcp.deepseek.DeepSeekException
import com.mcp.llm.MessageEvent
import com.mcp.composition.ToolRuntimeHolder
import com.mcp.preset.PresetManager
import com.mcp.preset.PresetRuntime
import com.mcp.data.LocalStore
import com.mcp.core.llm.BackendType
import com.mcp.core.llm.backendCredentialsSatisfied
import com.mcp.llm.LLMClient
import com.mcp.llm.LLMClientFactory
import com.mcp.llm.LLMConfig
import com.mcp.llm.LLMRequest
import com.mcp.llm.RawCapture
import com.mcp.llm.OpenAIException
import com.mcp.toolbox.ToolDef
import com.mcp.core.prompt.modelFacingTools
import com.mcp.toolbox.errorResult
import com.mcp.toolbox.parseToolArguments
import com.mcp.toolbox.ToolArgsParseException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import java.util.Locale
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat

/**
 * WebView 与原 DeepSeek 能力之间的桥接。
 *
 * 前端 chat.html 不再访问网络，而是通过 `window.ChatBridge.*` 调用本类方法，
 * 由 App 内已登录的 token 和当前会话 id 去请求 DeepSeek，再把 SSE 事件流回传给页面。
 */
/** 附件图片预览上限（超出则该图仅在气泡显示占位，不生成 data URL）。 */
private const val MAX_IMAGE_PREVIEW_BYTES = 3L * 1024 * 1024

/** 附件文本内容读取上限（超出则只注入提示，不读完整内容）。 */
private const val MAX_TEXT_ATTACH_BYTES = 512L * 1024

class ChatBridge(
    private val auth: AuthPrefs,
    private val context: Context
) {
    /**
     * 当前前台 WebView（会话 WebView 池模式下由宿主在 attach 时更新）。
     * 所有 window.onChatEvent 事件只推给它；为 null 或未挂载时静默丢弃。
     */
    @Volatile internal var frontWebView: WebView? = null

    /**
     * 宿主注入：某「不在前台」的会话产生了流更新或流完成（其 WebView 页面已错过这些事件）。
     * 宿主据此把该会话标记为脏，下次换入前台时走确定性重建补齐视图。
     */
    @Volatile internal var onOffstageStreamEvent: ((sid: String) -> Unit)? = null
    /**
     * 宿主注入：任一会话的流开始（active=true）或流结束（active=false）时回调。
     * 会话列表据此显示/隐藏生成中动画（loading.gif）。回调可能来自 IO 线程，宿主需自行切主线程。
     */
    @Volatile internal var onStreamActivityChange: ((sid: String, active: Boolean) -> Unit)? = null

    /**
     * 宿主注入：把 emit/emitStream 产生的事件（sid, event, dataJson）转发给 HTTP 桌面端的 SSE 订阅者。
     * 为 null 时仅回传 WebView（原生模式）；桌面端 WebApiServer 启动时注入。
     */
    @Volatile internal var onWebEvent: ((sid: String?, event: String, data: String) -> Unit)? = null

    /**
     * 进程级流事件订阅者（多端同步）。
     *
     * 每个 ChatBridge 实例的 [emitStream] 原本只走自己的 onWebEvent（桌面 SSE 只订阅了
     * WebApiServer 那个实例）和自己的 frontWebView，于是「手机发起的流桌面看不到、桌面发起的
     * 流手机看不到」——运行状态（生成中/停止）与信息流（逐字正文、工具卡片）都不同步。
     * 这里把**流事件**提升为进程级广播：
     *  - WebApiServer 订阅它 → 所有实例的流事件都进 SSE；
     *  - 每个实例订阅它 → 别端发起的流落在自己的前台 WebView 上照样实时渲染。
     *
     * 只扇出流事件（必带 sid）。非流事件（tokenConfig / tokenFlow / tts / 附件回传）仍走实例
     * 自己的 onWebEvent，避免把手机侧会话的看板流水灌进桌面看板。
     */
    internal fun interface StreamListener {
        /**
         * @param origin 事件来源实例的身份标识，订阅方用**引用比较**判断「是不是自己发的」。
         *   类型取 Any? 而非 ChatBridge：单测据此无需构造 ChatBridge（其构造依赖 Android Context）
         *   即可验证扇出语义；生产代码里恒为发事件的 ChatBridge 实例。
         */
        fun onStreamEvent(origin: Any?, sid: String?, event: String, data: String)
    }

    /**
     * 进程级流事件订阅者（见 [StreamListener]）：本实例据此收到**别端**发起的流事件。
     * 用字段持有而不是每次传函数引用——函数引用每次都是新对象，destroy() 里的移除会匹配不上。
     */
    private val foreignStreamListener = StreamListener { origin, sid, event, data ->
        onForeignStreamEvent(origin, sid, event, data)
    }

    /** 别端发起的流事件：本实例的前台 WebView 正显示该会话时照样实时渲染，否则标脏待重建。 */
    private fun onForeignStreamEvent(origin: Any?, sid: String?, event: String, data: String) {
        if (origin === this) return
        val terminal = event == "done" || event == "error" || event == "stopped"
        // 会话列表的「生成中」动画/刷新：别端的流同样要反映在本端列表上
        if (sid != null) onStreamActivityChange?.invoke(sid, !terminal)
        if (sid == null || sid != currentSessionId) {
            // 本端 WebView 停留的会话不是它：页面已错过这些事件，标脏待回前台时确定性重建
            if (sid != null) onOffstageStreamEvent?.invoke(sid)
            return
        }
        val obj = runCatching { JSONObject(data) }.getOrNull() ?: return
        pushToWebView(event, obj)
    }

    /** SSE 流消息 + 续聊作用域（IO 线程，避免阻塞 UI 线程）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Application 级协程作用域，不随 ChatBridge 销毁取消，用于后台流任务。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 工具执行专用作用域（独立 IO，避免工具执行阻塞 SSE 流）。 */
    private val toolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * history 列表的同步锁（多线程访问：scope/IO、toolScope/IO、主线程 JavascriptInterface）。
     * **进程级共享**：手机桥与桌面桥读写的已是同一份 history（见 [SessionStateStore]），
     * 各持一把锁会让 ChatBridge.addToHistory 的「读上一条 id + 追加」跨实例交错。
     */
    private val historyLock = SessionStateStore.historyLock

    /**
     * 落盘互斥锁，串行化 appendMessage/rebuildMessages，防止并发写使日志交错或消息丢失。
     * **进程级共享**：两端写的是同一份会话日志，跨实例也必须串行（rebuild 是整文件重写）。
     */
    private val saveMutex = SessionStateStore.saveMutex

    /** 重复失败防护（对齐 DeepSeek-Reasonix repeat_failure_guard.go）：同一锚点连续失败 >=2 次阻止重试。 */
    private val repeatGuard = com.mcp.toolbox.RepeatFailureGuard()

    // ── 语音闭环：录音（输入）+ 播放（TTS 输出） ─────────────────────────
    /** 麦克风录音的 MediaRecorder（同一时刻仅一路录音）。 */
    @Volatile private var mediaRecorder: MediaRecorder? = null

    /** 当前录音落盘文件（filesDir/recordings 下）。 */
    @Volatile private var recordingFile: File? = null

    @Volatile private var recording = false

    /** 等待权限授予后自动开始录音的标记（懒申请 RECORD_AUDIO）。 */
    @Volatile private var pendingStartRecording = false

    /** 由宿主 Fragment 注入：申请录音权限的入口。 */
    var onRecordPermissionRequest: (() -> Unit)? = null

    /** 由宿主 Fragment 注入：拉起系统文件选择器（通用附件上传）的入口。 */
    var onPickAttachmentsRequest: (() -> Unit)? = null

    /** 当前正在播放的 TTS 音频（新播放前先释放旧的）。 */
    private var currentPlayer: MediaPlayer? = null

    /** 系统 TTS 引擎实例（气泡朗读走系统输出时使用，主线程初始化）。 */
    @Volatile private var systemTts: TextToSpeech? = null

    /** 系统 TTS 是否已就绪（onInit 成功）。 */
    @Volatile private var systemTtsReady = false

    /** 系统 TTS 最近一次 speak 的 utteranceId，用于过滤被 QUEUE_FLUSH 打断的旧 onStop 回传。 */
    @Volatile private var currentTtsUtteranceId: String? = null

    @Volatile
    var currentSessionId: String? = null

    /**
     * 每个会话独立的会话态（上下文 + 在途流状态）。
     *
     * 为支持「多会话并发请求」，原先这些单一字段（parentMessageId 锚点、线性历史 history、
     * OpenAI 无状态消息缓冲、系统提示词注入标记、操作历史、在途流 Job/消息 id、工具续聊状态、
     * isBusy/isStopping/isRegenerating/lastThinkingEnabled）全部下沉为按 sessionId 隔离。
     * 否则并行切换会话时上下文会互相覆盖，单一 isBusy 也会阻塞其它会话的发送。
     */
    internal class SessionState {
        var parentMessageId: String? = null
        /**
         * 本轮 **user 消息**的服务端 id（SSE 的 request_message_id）。
         *
         * 与 parentMessageId（本轮 assistant）成对，供**子 Agent fork** 定位兄弟分支。
         * 服务端本来就把两个 id 都发下来了，不要再用「assistant - 1」推算 ——
         * 该假设只在 id 连续的轮次成立，分叉后跳号（实测 …18 → 21/22），推算即指错消息。
         */
        @Volatile var lastUserMessageId: String? = null
        /** DeepSeek 逆向续聊锚点是否已尝试惰性补齐（每会话态一次，防止每次发送都拉远端）。 */
        @Volatile var deepseekAnchorSeeded: Boolean = false

        /** 会话级预设 id（由 openWindow 注入；PTC 折叠/SDK 段按此派生，不用进程级全局）。 */
        @Volatile var presetId: String? = null
        /**
         * 会话级协议风格：true=XML（DSML），false=行式。
         *
         * 必须**按会话**存，不能读全局 PresetPromptOverride.protocolStyle——
         * 同一进程内 A(PTC/XML) 与 B(行式) 会话可并存，用户在 A 流式输出期间切到 B
         * 会改写全局值，导致 A 的返回封装（buildToolResult）与下一轮提示词按 B 的风格走，
         * 出现「教 XML 却回 LINE 结果」的错位。与 presetId / ptcActive(sid) 同源派生。
         * 默认 false（行式）以兼容旧会话（未持久化该值时回退全局）。
         */
        @Volatile var protocolStyleXml: Boolean = false
        /** 会话级 protocolStyleXml 是否已解析过（每会话一次，之后不再回退全局）。 */
        @Volatile var protocolStyleResolved: Boolean = false
        /**
         * DeepSeek 逆向协议：当前会话实际使用的服务端 session ID。
         * 初始 null = 沿用本地 sid（向后兼容）；session rotation 后指向新建的服务端会话。
         * OpenAI 兼容后端不使用此字段（无状态，本地 sid 即足够）。
         */
        @Volatile var serverSessionId: String? = null
        /**
         * 本会话最近一次 provider 上报的 promptTokens（服务端上下文大小的权威口径）。
         *
         * 必须是**会话级**：压缩判定用它描述「这个服务端会话有多满」。原先放在 ChatBridge 上
         * 做全局变量，从大上下文会话切到新会话后，新会话第一条消息就会用上一个会话的数值
         * 误触发 session rotation（新建服务端会话 + 清空锚点 + 塞一段无关摘要）。
         */
        @Volatile var lastPromptTokens: Int = 0
        /**
         * 本地消息 id 偏移（本地 id = 服务端 id + idOffset）。见 [MessageIdSpace]。
         * 压缩轮转后服务端消息 id 从 1 重新开始，用偏移把两代 id 隔开，避免撞车。
         */
        @Volatile var idOffset: Long = 0L
        /** 轮转状态是否已从磁盘读取过（每个会话态只读一次）。 */
        @Volatile var rotationLoaded: Boolean = false
        /**
         * 本会话的重复失败防护。
         * 原先是 ChatBridge 上的单例——A 会话把某个 edit_file 失败两次后，B 会话里**同样的调用**
         * 会被直接熔断（返回「已连续失败 2 次」），而 B 根本没试过。按会话隔离后不再互相误伤。
         */
        val repeatGuard = com.mcp.toolbox.RepeatFailureGuard()
        val history = ArrayList<ChatMessage>()
        val openAIMessages = ArrayList<com.mcp.llm.ChatMessage>()
        var sessionHistoryLoaded: String? = null
        /**
         * 工具拓扑变更标记：远程 MCP 服务增删、或某服务工具集变化时置 true。
         *
         * 仅对 DeepSeek 逆向协议有意义——它的工具清单是「仅首条消息注入」，
         * 中途变更后模型看不到更新。下一轮发送时若此标记为真，强制重新注入一次
         * （并在注入后清除）。OpenAI 每轮重建 system，不受影响。
         */
        @Volatile var toolTopologyDirty: Boolean = false
        val operationHistory = ArrayDeque<String>()

        @Volatile var isBusy: Boolean = false
        @Volatile var lastThinkingEnabled: Boolean = false
        @Volatile var isRegenerating: Boolean = false
        @Volatile var isStopping: Boolean = false

        @Volatile var streamJob: Job? = null
        @Volatile var streamMessageId: String? = null

        /**
         * 本地消息 id 自增序号（**仅 OpenAI 兼容后端使用**）。
         * DeepSeek 逆向协议由服务端分配 id，前端从 `message_id` 事件回填。
         * 见 [nextLocalMessageId] 的说明。
         */
        @Volatile var msgIdSeq: Long = 0

        /** 停止流时，从 StreamTaskManager 快照的已送达前缀（content/thinking），供定稿落盘用。 */
        @Volatile var stoppedPrefixContent: String = ""
        @Volatile var stoppedPrefixThinking: String = ""

        /** 最近访问时间戳，供有界 LRU 淘汰最久未访问的闲置会话态。 */
        @Volatile var lastAccessAt: Long = 0L

        val pendingToolJobs = LinkedHashMap<String, CompletableDeferred<String>>()
        val pendingUserInput = LinkedHashMap<String, CompletableDeferred<String>>()
        val pendingToolMeta = LinkedHashMap<String, Pair<String, String>>()
        /** 本轮 assistant(tool_calls) 的思考内容，供 continueOpenAI 回放时作为 reasoning_content 发回。 */
        @Volatile var pendingToolReasoning: String = ""
        /**
         * 已消费的 tool_call id 台账（跨轮累积）：同一个 id 只消费一次、结果只回传一次。
         * 站点/模型常在后续回复里复读上一次的 tool_call——跨轮后 pendingToolJobs 已清空，
         * 没有这台账就会再次执行、把同一份 tool_result 重复发出去（甚至来回循环）。
         * 重新生成/编辑重发会重置（truncateHistoryAt），那是合法的重跑同一批调用。
         */
        val consumedToolCallIds = LinkedHashSet<String>()

        /**
         * 被拒工具调用的警告结果（id → 已封装的 tool_result 文本），在 handleDone 收集时
         * 追加进正常结果一并回传。用于「重复 id / 已消费过」等被拒绝执行的调用：
         * 不再静默丢弃，而是把一条**面向生成该调用的 LLM 的警告**附进结果，
         * 让它下一轮改用唯一 id（自愈），与 ToolCallGuard 的重复 id 分级策略一致。
         * 单独成表是为了不覆盖 pendingToolJobs 中仍挂起的首个正常调用 deferred。
         */
        val rejectedToolResults = mutableListOf<Pair<String, String>>()

        /**
         * 同轮内重复 id 时，第二个及之后的调用改用「唯一内部键」存入 pendingToolJobs，
         * 避免覆盖首个仍挂起的 deferred（否则前一次结果会被丢、只回传最后一次）。
         * toolCallAlias 把内部键映射回原始 id，供 handleDone 把结果按原始 id 回传。
         * 仅去重关闭、且模型发了重复 id（不规范输入）时才会用到；正常唯一 id 不会进这里。
         */
        var dupKeySeq: Int = 0
        val toolCallAlias = mutableMapOf<String, String>()

        /**
         * 本会话正在执行的工具协程（executeToolAsync 经 toolScope 启动的 Job）。
         * 用户主动停止时统一取消，确保 in-flight 的工具真正停掉——仅取消结果 deferred
         * （clearPendingTools 旧行为）拦不住跑在独立协程里的工具本体。
         */
        val activeToolJobs = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Job, Boolean>())

        /**
         * 停止时由 [clearPendingTools] 填充：本轮被中断（尚未执行完成）的工具调用
         * (id → (name, arguments))。停止收尾协程（stream.collect 的 catch / 完成分支）
         * 读取它，给前端气泡里对应的工具卡片回填「已停止」结果，使其从「执行中…」
         * 变为有明确终止态，而不是无限挂起——这正是「停止后前端没反馈」的根因。
         * 单独成表是因为 pendingToolMeta 在 clearPendingTools 里会被清空。
         */
        var stoppedUnfinishedTools = mutableListOf<Pair<String, Pair<String, String>>>()
    }

    /**
     * sid → 会话态。**进程级共享**（见 [SessionStateStore]）：手机桥与桌面桥各持一份会让
     * 同一个会话有两份 history/锚点/isBusy —— 表现为信息流（消息、msgCount）与运行状态
     * （生成中动画、停止按钮）在两端不同步，且模型上下文缺对方轮次。
     */
    private val sessionStates: ConcurrentHashMap<String, SessionState> get() = SessionStateStore.states

    /**
     * 从文件恢复某会话的请求流水并推送给前端。
     * 用于切回已有会话时重新填充 Token 看板流水列表。
     * 仅当 [sid] 仍是前台会话时才回推（调用方须保证在 attachWebView 之后调用，
     * 使 frontWebView 已指向本会话），避免把 A 的流水打到当时仍是前台的 B 看板。
     */
    internal fun restoreTokenFlow(sid: String) {
        if (sid != currentSessionId) return
        // 流水文件每行含原始 HTTP 报文（reqText/respText，可达 MB 级）：读盘 + 解析必须在
        // IO 线程做，否则「返回会话列表再进聊天窗口」会在主线程整文件读 + 大 JSON 解析，
        // 造成明显卡顿。读完后仍需是前台会话才回推（读盘期间用户可能已切走）。
        scope.launch {
            val tag = LocalStore.backendTag(auth.getBackend())
            val lines = LocalStore.loadTokenFlow(context, tag, sid)
            if (sid != currentSessionId) return@launch
            val total = lines.size
            // 分页：仅回推最近一页（默认 50 条），前端按 tokenFlowRestore 事件接收；
            // 更早的流水走前端拉取窗口滚动加载（ChatBridge.getTokenFlowPage）。
            // 事件载荷：{entries: [...], total, offset: 0, hasMore}
            val limit = TOKEN_FLOW_PAGE_SIZE
            val start = if (limit < total) total - limit else 0
            val arr = JSONArray()
            for (i in start until total) {
                runCatching {
                    val e = JSONObject(lines[i].trim())
                    // 剥离大字段：整页携带原始报文会把 evaluateJavascript 的 JS 字符串
                    // 撑到几十 MB，WebView 解析同样卡顿。行号 lineIndex 供前端
                    // 「查看」详情时经 getTokenFlowDetail 按需取回。
                    e.remove("reqText")
                    e.remove("respText")
                    e.put("lineIndex", i)
                    arr.put(e)
                }
            }
            val payload = JSONObject()
                .put("entries", arr)
                .put("total", total)
                .put("offset", 0)
                .put("limit", limit)
                .put("hasMore", start > 0)
            emit("tokenFlowRestore", payload)
            pushTokenConfig()
        }
    }

    /** 取会话态（不存在返回 null），并刷新最近访问时间戳。 */
    private fun state(sid: String?): SessionState? {
        val st = sid?.let { sessionStates[it] } ?: return null
        st.lastAccessAt = System.currentTimeMillis()
        return st
    }

    /**
     * 取或创建会话态（并发安全），并做有界管理：内存中的会话态不允许无界增长。
     * 超过 [MAX_SESSION_STATES] 时，淘汰「最久未访问且无活跃流」的会话态——
     * 有活跃 SSE 流的会话绝不淘汰（否则正在进行的流会丢失 streamJob/前缀定稿锚点）。
     * 消息历史已落盘（append-only 日志），被淘汰的闲置会话下次进入会重新加载历史。
     */
    private fun stateFor(sid: String): SessionState {
        val now = System.currentTimeMillis()
        var fresh = false
        val created = sessionStates.computeIfAbsent(sid) {
            fresh = true
            SessionState().also { it.lastAccessAt = now }
        }
        if (fresh) loadRotationIfNeeded(sid, created)
        created.lastAccessAt = now
        evictIdleStatesIfNeeded()
        return created
    }

    /**
     * 从磁盘恢复会话轮转状态（serverSessionId + idOffset），每个会话态只读一次。
     *
     * 进程被杀后 `serverSessionId` 若只存在内存里，请求会退回**轮转前的旧服务端会话**
     * （仍是满的），本地锚点又属于轮转后的会话——轮转被静默撤销且锚点错配。
     * 偏移同理：丢了它前端气泡 id 与本地换算基准不一致，锚点会被算错。
     */
    private fun loadRotationIfNeeded(sid: String, st: SessionState) {
        if (st.rotationLoaded) return
        st.rotationLoaded = true
        val rotation = runCatching {
            LocalStore.loadSessionRotation(context, LocalStore.backendTag(auth.getBackend()), sid)
        }.getOrNull() ?: return
        if (rotation.serverSessionId.isNotBlank()) st.serverSessionId = rotation.serverSessionId
        if (rotation.idOffset > 0L) st.idOffset = rotation.idOffset
        LogStore.i(
            "COMPACT",
            "恢复会话轮转状态 session=$sid serverSessionId=${rotation.serverSessionId.ifBlank { "-" }} idOffset=${rotation.idOffset}"
        )
    }

    // ── 消息 id 命名空间（本地 ↔ 服务端）────────────────────────────────────
    // 前端只看到「本地 id」；st.parentMessageId / 请求参数 / 停止用 id 一律是「服务端 id」。

    /**
     * 服务端消息 id 合法性（1..u32 正整数）；语义与理由见 [MessageIdSpace.isValidServerId]。
     *
     * 非法值落进 st.parentMessageId 后，下一轮请求会被 DeepSeekApi.normalizeMessageId
     * 归一化成 null → 服务端「开新分支」→ 历史全丢；发给前端则让 assignMessageIds 的
     * parseInt 得 NaN、消息树被打散。故每个锚点写入点都必须先过这一关。
     */
    private fun isValidServerMessageId(id: String?): Boolean = MessageIdSpace.isValidServerId(id)

    /** 某会话的 id 偏移（不创建会话态、不刷新访问时间）。 */
    internal fun idOffsetFor(sid: String?): Long = sid?.let { sessionStates[it]?.idOffset } ?: 0L

    /** 服务端 id → 本地 id（跨桥发给前端用）。 */
    internal fun toLocalId(sid: String?, raw: String?): String? =
        MessageIdSpace.toLocal(raw, idOffsetFor(sid))

    /** 本地 id → 服务端 id（前端回传的锚点用）；无效锚点返回 null。 */
    internal fun toRawId(sid: String?, local: String?): String? =
        MessageIdSpace.toRaw(local, idOffsetFor(sid))

    /**
     * 确保会话态已建立（并已从磁盘恢复轮转状态），返回其 id 偏移。
     * 历史装载方（Tabs）在把服务端消息写成 ChatMessage 之前必须调用它：
     * 日志统一存**本地 id**，换算基准就来自这里。
     */
    internal fun ensureAndGetIdOffset(sid: String): Long = stateFor(sid).idOffset

    /** 会话态超阈值时淘汰最久未访问的闲置会话（跳过有活跃流的会话）。 */
    private fun evictIdleStatesIfNeeded() {
        if (sessionStates.size <= MAX_SESSION_STATES) return
        var victim: Pair<String, SessionState>? = null
        for ((sid, st) in sessionStates) {
            if (st.isBusy || st.streamJob != null) continue  // 有活跃流，不可淘汰
            if (victim == null || st.lastAccessAt < victim!!.second.lastAccessAt) {
                victim = sid to st
            }
        }
        victim?.let { (sid, _) ->
            sessionStates.remove(sid)
            LogStore.d("SESS", "会话态有界淘汰闲置会话 session=$sid")
        }
    }

    /**
     * 设置/清空当前会话的多轮续聊锚点（由外部在加载历史或新建会话后调用）。
     * 同时暴露给 WebView JS，供「重新生成 / 编辑重发」在发送前重定向父消息。
     */
    @JavascriptInterface
    fun setParentMessageId(id: String?) {
        // 前端给的是**本地 id**：换算回服务端 id 再存（轮转后两者不再相等）。
        // 换算失败（无效锚点）等价于清空锚点，绝不能让一个本地 id 当服务端 id 发出去。
        state(currentSessionId)?.parentMessageId = toRawId(currentSessionId, id)
    }

    /**
     * 按 sessionId 精确设置续聊锚点（历史加载回填用，避免依赖 currentSessionId 的切换竞态）。
     *
     * 入参来自 [LocalStore.loadSessions] 的 currentMessageId，而该字段历史上被写入过文件 id
     * （"file-<uuid>"）。非法值在这里就地丢弃——继续用它会让下一轮 parent_message_id 被归一化
     * 成 null（服务端开新分支、历史丢失）；丢弃后 [ensureDeepSeekAnchor] 会重新向服务端取锚点。
     */
    internal fun setParentFor(sessionId: String, id: String?) {
        val st = state(sessionId) ?: return
        if (id == null) {
            st.parentMessageId = null
        } else if (isValidServerMessageId(id)) {
            st.parentMessageId = id
        } else {
            st.parentMessageId = null
            LogStore.w("SESS", "丢弃非法续聊锚点 session=$sessionId anchor=$id（非 1..u32，疑似文件 id）")
        }
    }

    /**
     * 从当前会话的本地消息日志中删除指定 id 的消息（及其之后的消息），
     * 保持 parentId 链完整。由 WebView JS 在「删除消息」时调用。
     */
    @JavascriptInterface
    fun deleteMessage(messageId: String) {
        deleteMessageFor(currentSessionId, messageId)
    }

    /** 指定会话删除消息（Web 桌面端按请求 sessionId 调用）。 */
    internal fun deleteMessageFor(sid0: String?, messageId: String) {
        // 本地历史/日志按**本地 id** 索引（前端给的也是本地 id），无需换算
        val sid = sid0 ?: return
        val tag = LocalStore.backendTag(auth.getBackend())
        LocalStore.deleteMessage(context, tag, sid, messageId)
        // 同步更新当前会话态的历史镜像，避免 UI 残留已删除的消息。
        synchronized(historyLock) {
            state(sid)?.history?.removeAll { it.id == messageId }
        }
    }

    /** 当前会话是否有消息正在流式返回；前端与 UI 层靠此禁用重复发送。读写均作用于当前会话态。 */
    var isBusy: Boolean
        get() = state(currentSessionId)?.isBusy ?: false
        internal set(value) { state(currentSessionId)?.isBusy = value }

    /**
     * PTC 子调用 ask_user 的处理器引用：必须用**字段**持有同一个函数对象，register / unregister
     * 才能按引用配对。若在 init 与 destroy 里各写一次 lambda 字面量，每次求值都是新对象，
     * destroy 时的移除会失配——处理器残留在注册表里，被销毁实例的 handlePtcAskUser 仍会被调用。
     * 声明必须在 init 之前：Kotlin 按类体出现顺序初始化，init 早于本属性会让注册到 null。
     */
    private val ptcAskUserHandler: suspend (String) -> String? = { payload -> handlePtcAskUser(payload) }

    init {
        instances.add(this)   // 登记到进程实例表（供 notifyProfileConfigChanged 推送 tokenConfig）
        // 多端同步：订阅进程级流事件（别端发起的流也要能在本端 WebView 实时渲染）
        addStreamListener(foreignStreamListener)
        // PTC 审计：进程内只订阅一次，把 ptc/call·result 写进会话日志（不进模型历史）
        com.mcp.ptc.PtcAuditSink.ensureSubscribed(context, auth)
        // PTC 子调用 ask_user 弹窗：程序内 tools.ask_user(...) 走 PtcBridge/JsDispatchBridge，
        // 不经过下面 sendMessage 的直调拦截。这里注册处理器，让子调用也能弹窗并挂起等用户回答。
        // 同一进程多个 ChatBridge 实例（手机/桌面）时，按「当前前台会话」路由到对应实例。
        com.mcp.ptc.PtcAskUserBridge.register(ptcAskUserHandler)
    }

    /**
     * 处理 PTC 程序内 ask_user 子调用：弹出与直调路径相同的对话框，挂起等待用户回答。
     *
     * 与 [sendMessage] 里直调 ask_user 的拦截块共用同一份前端合约（emitStream("ask_user", ...)）：
     * 用户提交后由 [submitUserInputFor] 完成 deferred，返回值即用户回答，写回 guest 程序。
     * 拿不到会话态（无前台会话/会话已销毁）时返回 null，让调用方按普通结果透传。
     */
    private suspend fun handlePtcAskUser(payloadJson: String): String? {
        // 会话归属优先取 run_code 绑定的**发起会话**（PtcAudit），而非本实例的 currentSessionId。
        // ask_user 是 run_code 的程序内子调用：run_code 一开始 ChatBridge 就 PtcAudit.bind(sid)、
        // 结束时 unbind，期间该 sid 恒定。处理器可能落在任意一个 ChatBridge 实例上（注册表遍历到谁
        // 就是谁），而 currentSessionId 是「本实例当前前台焦点」，多实例/多会话下与发起会话并不一致，
        // 用它定位会拿不到会话态而静默 return null——表现为提问不弹窗、程序却继续跑。
        // 会话态（SessionStateStore）本身是进程级共享，因此任一实例都能据该 sid 正确挂起/回填。
        val sid = com.mcp.ptc.PtcAudit.current() ?: currentSessionId ?: return null
        val st = state(sid) ?: return null
        val payload = runCatching { JSONObject(payloadJson) }.getOrElse { JSONObject() }
        // callId 需唯一，且要与前端提交时携带的一致；用随机 id 避免与直调 callId 冲突。
        val callId = "ptc-ask-" + java.util.UUID.randomUUID()
        val deferred = CompletableDeferred<String>()
        // 与 forgetSession 的清理加同一把锁，避免「注册 pending 的同时会话被销毁」的竞态。
        synchronized(st.pendingUserInput) { st.pendingUserInput[callId] = deferred }
        withContext(Dispatchers.Main) {
            emitStream(sid, "ask_user", JSONObject().apply {
                put("id", callId)
                if (payload.has("questions")) {
                    put("questions", payload.getJSONArray("questions"))
                } else {
                    put("question", payload.optString("question", "请回答："))
                    put("type", payload.optString("type", "text"))
                    if (payload.has("options")) put("options", payload.optString("options"))
                }
                if (payload.has("multi_select")) put("multi_select", payload.optBoolean("multi_select"))
            })
        }
        // 开始计时：这段「等用户回答」的时长要从 run_code 的超时预算里扣除，
        // 由 withUserWaitAwareTimeout 按 UserInputClock 的累计值折算。
        com.mcp.ptc.UserInputClock.beginWait()
        return try {
            // 按片轮询，而不是裸 deferred.await()。
            //
            // 为什么必须轮询：这个 deferred **不在** st.pendingToolJobs 台账里，stopStream 的
            // clearPendingTools 不会 cancel 它。裸 await 一旦挂起，JsDispatchBridge 里那层
            // `runBlocking { awaitUserInput(res) }` 就把 ptc-js 线程永久占死——Rhino 指令观察者
            // 再也跑不到检查点，PtcCancellation 形同虚设，整个 run_code 只能干等 JS 的 280s 超时。
            // 表现为「用户没回答、也没点停止，程序永远不返回，停止按钮也按不动」。
            var answer: String? = null
            while (answer == null) {
                answer = kotlinx.coroutines.withTimeoutOrNull(PTC_ASK_POLL_MS) { deferred.await() }
                if (answer == null && com.mcp.ptc.PtcCancellation.isRequested()) {
                    // 用户点了停止：清掉台账并抛取消，让异常穿透 awaitUserInput → runBlocking →
                    // JsDispatchBridge，程序随之终止，而不是把原始 ask_user JSON 当结果继续跑。
                    synchronized(st.pendingUserInput) { st.pendingUserInput.remove(callId) }
                    throw com.mcp.ptc.PtcCancelledException()
                }
            }
            // 用户点了「取消/关闭」：前端提交的是哨兵值 [ASK_USER_CANCEL_MARKER]，
            // submitUserInputFor 不区分它和真实答案，一律包成 {"user_response":"..."} 回填 deferred。
            // 若原样返回，程序会把这串 JSON 当成「用户的回答」继续往下跑（比如拿它去匹配选项），
            // 明明用户什么都没答。这里识别出来并抛 ToolCallException：tools.ask_user() 变成
            // ToolCallError，程序可以 catch 并据此中止，而不会把哨兵当成答案。
            // 只影响 PTC 子调用路径——直调路径的取消语义由 submitUserInputFor 自行处理。
            if (isAskUserCancel(answer)) {
                synchronized(st.pendingUserInput) { st.pendingUserInput.remove(callId) }
                throw com.mcp.toolbox.ToolCallException("ask_user", "用户取消了提问（未作答）")
            }
            answer
        } catch (e: com.mcp.ptc.PtcCancelledException) {
            LogStore.w("ASK_USER", "PTC ask_user 等待被用户停止: $callId")
            throw e
        } catch (e: Exception) {
            LogStore.w("ASK_USER", "PTC ask_user 等待被取消: $callId ${e.message}")
            null
        } finally {
            com.mcp.ptc.UserInputClock.endWait()
        }
    }

    companion object {
        private const val MAX_OP_HISTORY = 10
        private const val MAX_SESSION_STATES = 16

        /**
         * PTC ask_user 等待的轮询时间片（毫秒）。
         *
         * 等待期间必须周期性醒来检查 PtcCancellation，否则「停止」对卡在提问上的 run_code 无效。
         * 取值与 [com.mcp.ptc.USER_WAIT_POLL_MS] 同量级：足够及时，又不会把等待变成忙轮询。
         */
        private const val PTC_ASK_POLL_MS = 200L

        /**
         * 前端「取消/关闭提问」提交的哨兵值（见 chat.html 的 closeAskUserAsCancel）。
         * 它不是用户的答案，必须与真实回答区分开。
         */
        private const val ASK_USER_CANCEL_MARKER = "__cancel__"

        /**
         * 判断 submitUserInputFor 回填的内容是不是「用户取消」。
         *
         * 必须先把**协议包装**剥掉再解析：deferred 拿到的是 submitUserInputFor 写入的
         * `buildToolResult(...)` 产物，行式协议下形如
         *   tool_result: <id> <<< {"user_response":"__cancel__"} >>>
         * 直接 JSONObject 解析整串必然抛异常，会被下面的 getOrDefault(false) 吞成「不是取消」——
         * 取消识别静默失效。所以先按会话协议提取 content，再解析；提取失败时退回裸串解析
         * （PTC 路径在部分后端下确实直接给裸 JSON）。
         *
         * 两条兜底都失败时保守地当作「不是取消」：宁可把异常内容透传给程序，
         * 也不要误判成取消而把一次真实的作答丢掉。
         */
        private fun isAskUserCancel(answer: String): Boolean {
            val candidates = listOf(
                runCatching { com.mcp.toolbox.ToolMessages.extractContent(answer) }.getOrDefault(""),
                answer
            )
            return candidates.any { raw ->
                runCatching {
                    org.json.JSONObject(raw.trim()).optString("user_response") == ASK_USER_CANCEL_MARKER
                }.getOrDefault(false)
            }
        }

        /** 溢出恢复时激进保留的最近消息条数（不含 system）。 */
        private const val OVERFLOW_RETAIN_MESSAGES = 4

        /** 进程内所有存活 ChatBridge 实例（手机聊天页桥 + WebApiServer 桌面桥，最多 2 个）。 */
        private val instances = java.util.concurrent.CopyOnWriteArrayList<ChatBridge>()

        /** 进程级流事件订阅者（多端同步，见 [StreamListener]）。 */
        private val streamListeners = java.util.concurrent.CopyOnWriteArrayList<StreamListener>()

        internal fun addStreamListener(l: StreamListener) {
            streamListeners.add(l)
        }

        internal fun removeStreamListener(l: StreamListener) {
            streamListeners.remove(l)
        }

        /** 广播一条流事件给进程内所有订阅者；单个订阅者抛错不影响其它订阅者与调用方。 */
        internal fun publishStreamEvent(origin: Any?, sid: String?, event: String, data: String) {
            for (l in streamListeners) runCatching { l.onStreamEvent(origin, sid, event, data) }
        }

        /**
         * 设置页切换 / 编辑 / 删除 OpenAI 配置档案后调用：让所有存活实例立即重推 tokenConfig，
         * 手机页与桌面 Web 的 Token 看板（总窗口/模型/压缩阈值）随当前 active profile 即时刷新，
         * 无需等下一次会话打开或下一轮请求完成。
         */
        fun notifyProfileConfigChanged() {
            for (b in instances) {
                runCatching { if (b.currentSessionId != null) b.pushTokenConfig() }
            }
        }

        /**
         * 工具拓扑变更（远程 MCP 服务增删 / 工具集变化）时调用：
         * 把所有存活实例的**全部**会话标记为「提示词需重注入」。
         *
         * 不直接清 sessionHistoryLoaded 是有意为之：那个标记还管着历史加载状态，
         * 清掉会连带触发 OpenAI 路径重新 seed 历史，副作用过大。用独立脏标记
         * 只影响 DeepSeek 的工具清单注入。
         */
        fun notifyToolTopologyChanged() {
            for (b in instances) {
                runCatching { b.markAllSessionsToolTopologyDirty() }
            }
        }

        /**
         * 用户在设置页显式切换工具调用协议风格（LINE/XML）后调用。
         *
         * 已建立的会话在首次解析协议风格后会与全局值**解耦**（见 protocolStyleXmlFor），
         * 以免切会话时被别的会话的全局写入翻转。但用户主动改全局偏好时，语义上应当
         * 对所有会话生效（含正在跑的），故在此强制把新风格广播到各会话缓存。
         */
        fun notifyProtocolStyleChanged() {
            for (b in instances) {
                runCatching { b.refreshProtocolStyleForAllSessions() }
            }
        }
    }

    /** 记录一条操作到该会话的历史缓冲区（超出上限自动淘汰最旧的）。 */
    private fun recordOperation(sid: String?, summary: String) {
        val st = state(sid) ?: return
        synchronized(st.operationHistory) {
            if (st.operationHistory.size >= MAX_OP_HISTORY) {
                st.operationHistory.removeFirst()
            }
            st.operationHistory.addLast(summary)
        }
    }

    /** 格式化该会话操作历史为 Markdown 摘要，供注入 system prompt。 */
    private fun formatOperationHistory(sid: String?): String {
        val st = state(sid) ?: return ""
        synchronized(st.operationHistory) {
            if (st.operationHistory.isEmpty()) return ""
            return st.operationHistory.joinToString("\n") { "  - $it" }
        }
    }

    // ── 上下文压缩（Context Compaction）──────────────────────────────────────
    // 策略对齐 deepseek-harness dsh-compaction-basic：
    //   压力阈值 = contextWindow × thresholdRatio（模型感知）；
    //   保留 = contextWindow × retainRatio（token 预算）+ 条数地板；
    //   切点满足 tool-pairing 平衡（不切断 assistant(tool_calls)/tool 结果对）；
    //   摘要必须收缩（收敛验证），替换而非追加；溢出时走溢出恢复路径。

    /** 上下文压缩策略（纯策略，无 Android 依赖，可单元测试）。 */
    private val contextCompactor = ContextCompactor()

    /**
     * 压缩事务（锁 + 异步摘要期间的稳定性复检 + replace 提交）。
     *
     * 必须先有事务层再接线：摘要是异步 LLM 调用（秒级），期间 [SessionState.openAIMessages]
     * 可能增长（用户新消息 / 工具结果 / 子 agent 追加）。若沿用「摘要前快照」直接 clear+addAll，
     * 期间新增的消息会被整段抹掉。事务负责回到最新列表、校验范围未变、并取当前尾部提交。
     */
    private val compactionTransaction = CompactionTransaction(contextCompactor)

    /** 工具定义 token 估算（复用 ContextCompactor，计入压缩压力判断）。 */
    private fun estimateToolsTokens(tools: List<ToolDef>?): Int = contextCompactor.estimateToolsTokens(tools)

    /**
     * 会话的模型 / 上下文窗口 / 最大输入一律跟随【当前 active profile】，不保留创建会话时的快照。
     *
     * 早先实现把创建/打开会话时的 profile 值固化为会话级快照（进程锁），本意是避免切换档案后
     * 旧历史被按新（更小）窗口重砍；但会话并不属于某个档案——同一批 OpenAI 兼容档案共用同一份
     * 会话（见 OpenAIProfileAdapter：切换档案不重建会话），且实际发请求的模型/窗口都是每次实时读
     * active profile（LLMConfig model = auth.getOpenAIModel()）。快照固化后一旦切换档案/模型，
     * 看板总窗口与压缩阈值仍按旧模型旧窗口计算，出现「设置 262,144、看板 1,048,576」这类不一致。
     * 因此这里不再做会话级锁定：切换模型后，看板与压缩阈值立即跟随当前 profile。
     */
    internal fun setSessionProfile(sid: String, model: String, contextWindow: Int, maxInput: Int) {
        // 参数保留给 Tabs 调用兼容；窗口/阈值不再按会话快照取值——configured* / effectiveContextWindow
        // 均实时读 active profile。打开会话即把 Token 看板配置推给前端（页面未就绪时 emit 静默
        // 丢弃，首轮 Done 会再推一次）。
        pushTokenConfig()
    }

    // ── Token 看板（DeepSeek 风格请求流水 + 可视化）状态 ──
    private var tokenBoardSeq = 0
    private var tokenBoardCumulative = 0
    // 注：provider 上报的 promptTokens 基线已下沉到 SessionState.lastPromptTokens（会话级）。
    // 全局变量会让「切换会话」继承上一个会话的上下文压力，误触发 DeepSeek session rotation。
    // 本轮请求捕获（用于流水明细）
    private var reqPrompt = 0
    private var reqCompletion = 0
    private var reqReasoning = 0
    private var reqCache = 0
    private var reqOverflow = false
    private var reqRateLimited = false
    private var reqCategories: ContextCompactor.TokenCategoryBreakdown? = null
    private var reqRecorded = false
    // Token 看板：本轮「进行中」条目实时预览（流式未结束前，前端按 seq upsert；不落盘、不占正式 seq）
    private var reqLiveEmitted = false
    private var reqPromptLive = 0
    private var reqLiveChars = 0
    private var reqLiveAccChars = 0
    private val tokenLiveThresholdChars = 12   // 流式实时预览节流阈值（字符）：约 4 token 更新一次
    private var reqStartedAt = 0L
    /** 本轮原始请求报文（POST 请求体 JSON，来自 OpenAIClient rawCapture），用于流水明细弹窗。 */
    private var reqRawBuffer: String = ""
    /** 本轮原始响应报文累积（SSE 各 data 帧 JSON），由底层 rawCapture 按流填充。 */
    private var respRawBuilder: StringBuilder = StringBuilder()

    /**
     * 看板显示的总上下文窗口：实时读当前 active profile 手填的上下文窗口。
     * 未配置（0）时返回 0 → 前端占位 “—”。
     *
     * 不再按会话创建快照取值（见 [setSessionProfile]），也不按模型名猜窗口：模型表规则
     * （gemini→1M、nemotron-3-ultra→1_048_576、gpt-4o→128k…）只是压缩逻辑的内部兜底，与
     * 服务端真实窗口无关。压缩触发侧的模型表回退由 [compactThreshold]（ContextCompactor.
     * thresholdTokens）自行负责，不经过本函数。
     */
    private fun effectiveContextWindow(): Int = when (auth.getBackend()) {
        BackendType.LITERT ->
            // 本地模型的「总窗口」= 引擎 maxNumTokens（设置页可配）
            auth.getLiteRtMaxTokens().takeIf { it > 0 } ?: 0
        BackendType.MNN ->
            // MNN 单轮最大输出即其可用窗口上限（config.json 的 max_new_tokens）
            auth.getMnnMaxOutput().takeIf { it > 0 } ?: 0
        else ->
            auth.getOpenAIContextWindow().takeIf { it > 0 } ?: 0
    }
    /**
     * 落一轮请求的 Token 看板流水。
     * @param sid 本轮实际所属的会话（流式协程捕获的 streamSid），务必传调用方持有的真实
     *            会话 id，绝不能用可变的 currentSessionId——否则用户中途切走后，后台流的用量
     *            会被存进「当前前台会话」的文件、并打到其看板，造成会话间数据串扰。
     *            仅当 [sid] 仍是前台会话时才回推前端；后台流只落盘，待切回时由 restoreTokenFlow 恢复。
     */
    private fun flushTokenFlow(status: String = "ok", sid: String? = currentSessionId) {
        val cats = reqCategories ?: return
        if (reqPrompt <= 0 && reqCompletion <= 0) return
        if (reqRecorded) return
        reqRecorded = true
        tokenBoardSeq++
        tokenBoardCumulative += reqPrompt + reqCompletion
        // 会话级基线：压缩判定只看本会话的服务端上报值
        sid?.let { state(it)?.lastPromptTokens = reqPrompt }
        val entry = JSONObject().apply {
            put("seq", tokenBoardSeq)
            put("startedAt", reqStartedAt)
            put("completedAt", System.currentTimeMillis())
            put("ts", System.currentTimeMillis())
            put("sessionId", sid ?: "")
            put("route", auth.getBackend().name.lowercase() + "/" + backendModelLabel())
            put("prompt", reqPrompt)
            put("uncachedInput", (reqPrompt - reqCache).coerceAtLeast(0))
            put("completion", reqCompletion)
            put("reasoning", reqReasoning)
            put("cacheHit", reqCache)
            put("total", reqPrompt + reqCompletion)
            put("cumulative", tokenBoardCumulative)
            put("status", status)
            put("catSystem", cats.system)
            put("catUser", cats.user)
            put("catToolRequest", cats.toolRequest)
            put("catToolResponse", cats.toolResponse)
            put("catTools", cats.tools)
            put("catContent", cats.content)
            put("catThinking", cats.thinking)
            put("catOther", cats.other)
            // 请求/响应：原始 HTTP 报文（POST 请求体 JSON + SSE 各 data 帧 JSON），来自底层 rawCapture。
            // 响应优先用本轮原始 SSE 帧；未采集时回退到 StreamTaskManager 的纯文本回复（兼容兜底）。
            put("reqText", reqRawBuffer)
            put("respText", (if (respRawBuilder.isNotEmpty()) respRawBuilder.toString()
                else sid?.let { StreamTaskManager.get(it) }?.lastContent) ?: "")
        }
        val tag = LocalStore.backendTag(auth.getBackend())
        val saveSid = sid ?: ""
        LocalStore.saveTokenFlow(context, tag, saveSid, entry.toString())
        // 仅前台会话的流水回推前端：后台流（用户已切走）不打到当前看板，只落盘待恢复，
        // 否则 A 后台生成会把它本轮用量灌进 B 的看板（会话间串扰）。
        if (sid != null && sid == currentSessionId) emit("tokenFlow", entry)
    }

    /**
     * Token 看板：推送「进行中」条目的实时预览（不落盘、不累加 cumulative、不占正式 seq）。
     * prompt 用校准后的本地计量（请求报文的真实内容），completion 按已生成文本实时计数；
     * 流结束由 [flushTokenFlow] 以 provider 精确值按同一 seq 覆盖。仅前台会话回推
     * （与 flushTokenFlow 同守卫），后台流切回时由 restoreTokenFlow 恢复终态。
     */
    private fun emitTokenFlowLive() {
        val cats = reqCategories ?: return
        val prompt = reqPromptLive
        val completion = ((reqLiveChars / 3.0) * contextCompactor.tokenScaleValue).toInt()
        if (prompt <= 0 && completion <= 0) return
        reqLiveEmitted = true
        val sid = currentSessionId
        val entry = JSONObject().apply {
            put("seq", tokenBoardSeq + 1)   // 预占下一正式 seq；结算时 tokenBoardSeq++ 得到同一值，前端据此 upsert
            put("startedAt", reqStartedAt)
            put("ts", System.currentTimeMillis())
            put("sessionId", sid ?: "")
            put("route", auth.getBackend().name.lowercase() + "/" + backendModelLabel())
            put("prompt", prompt)
            put("uncachedInput", prompt)   // 实时阶段 cache 未知，按未缓存口径
            put("completion", completion)
            put("reasoning", 0)
            put("cacheHit", 0)
            put("total", prompt + completion)
            put("cumulative", tokenBoardCumulative)   // 不预加本轮，避免重复累计
            put("status", "ing")
            put("catSystem", cats.system)
            put("catUser", cats.user)
            put("catToolRequest", cats.toolRequest)
            put("catToolResponse", cats.toolResponse)
            put("catTools", cats.tools)
            put("catContent", cats.content)
            put("catThinking", cats.thinking)
            put("catOther", cats.other)
        }
        if (sid != null && sid == currentSessionId) emit("tokenFlow", entry)
    }

    /**
     * 当前后端的「模型显示名」，供 Token 看板与流水 route 复用。
     *
     * DeepSeek 逆向通道没有「模型名」概念：请求体只有 model_type=default，无可选模型。
     * 此前一律取 getOpenAIModel()，会显示 OpenAI profile 里的残留模型名（如 gpt-4o），
     * 与当前请求无关。故按后端分派：DeepSeek 显示固定标识，OpenAI 才取真实模型名。
     */
    private fun backendModelLabel(): String = when (auth.getBackend()) {
        BackendType.LITERT -> auth.getLiteRtModelLabel()
        BackendType.MNN -> auth.getMnnModelLabel()
        else -> auth.getOpenAIModel()
    }

    /** 构造 Token 看板配置（总上下文/模型），供推送与前端拉取共用。阈值由前端按 total × 0.8 自行计算。 */
    private fun buildTokenConfig(): JSONObject = JSONObject()
        .put("totalContext", effectiveContextWindow())
        .put("model", backendModelLabel())
        .put("backend", auth.getBackend().name)

    /**
     * 推送 Token 看板配置到前端（总上下文/模型）。配置只依赖当前 active profile，与具体会话无关。
     * [target] 非 null 时直接评估到该 WebView（用于 onPageFinished 页面就绪后的定向补发，
     * 绕过 frontWebView 字段就绪时序竞态，确保首进即达）；为 null 时退回全局 emit。
     */
    internal fun pushTokenConfig(target: WebView? = null) {
        try {
            val cfg = buildTokenConfig()
            if (target != null) {
                // 定向推给刚加载完成的页面：不依赖 frontWebView 字段时序，首进即可达。
                val js = "window.onChatEvent(\"tokenConfig\", ${cfg.toString()})"
                if (Looper.myLooper() == Looper.getMainLooper()) target.evaluateJavascript(js, null)
                else target.post { target.evaluateJavascript(js, null) }
                return
            }
            emit("tokenConfig", cfg)
        } catch (_: Exception) { /* 前端未就绪时静默 */ }
    }

    /**
     * 前端页面就绪后主动拉取当前 Token 看板配置（pull-on-ready）。
     * 由 chat.html 初始化末尾同步调用，返回 JSON 字符串；彻底规避「原生在页面就绪前
     * 推送、事件被吞」的时序竞态（即冷启动首进看板为空、返回列表再进才有的问题）。
     * 同步返回值直接交由调用方解析，不依赖 evaluateJavascript 的异步时序。
     */
    @JavascriptInterface
    fun requestTokenConfig(): String = try {
        buildTokenConfig().toString()
    } catch (_: Exception) { "{}" }

    // ── 历史分页 API（显示层分页，LLM 层不变）────────────────────────────────
    // 前端初始只加载最近 [HISTORY_PAGE_SIZE] 条消息，向上滚动再按 offset 加载更早的一批；
    // openAIMessages / LLM 上下文策略与本地完整历史不变，仅 WebView 视图层按需渲染。
    // 数据源：优先 st.history（内存缓存），未加载时回退磁盘（LocalStore.loadMessages）。
    //   内存路径避免每次分页都读 JSONL，磁盘路径保证冷启动 / 内存淘汰后仍能分页。

    /** 分页粒度：前端初次拉取与每页向上加载的条数。 */
    private val HISTORY_PAGE_SIZE: Int get() = 50

    /** Token 流水分页粒度（与消息分页保持一致的默认值）。 */
    private val TOKEN_FLOW_PAGE_SIZE: Int get() = 50

    /**
     * 前端拉取当前会话 id（供调用 [getMessagesPage] / [getTokenFlowPage] 时定位会话）。
     * ChatBridge 在单例下由 Tabs.openWindow 设置 currentSessionId；此处仅做只读返回。
     * 注意：不能命名为 getCurrentSessionId()，否则与属性 currentSessionId 的 getter 冲突。
     */
    @JavascriptInterface
    fun getCurrentSessionIdForJs(): String = currentSessionId ?: ""

    // ───────────────────────── 分支选择状态持久化（◀ 分支 k/N ▶ 箭头） ─────────────────────────

    /**
     * 前端保存当前会话的分支选择状态（分叉点 ◀/▶ 箭头选中的分支）。
     *
     * 前端 `activeTurn` / `userSwitched` 原本只活在 WebView 内存：切走或重启后回退到
     * 「最新一轮」，用户手动切到的旧分支丢失。这里按会话落盘，[loadBranchState] 回填。
     *
     * @param json 形如 `{"activeTurn":{"<pid>":1},"userSwitched":{"<pid>":true}}`
     */
    @JavascriptInterface
    fun saveBranchState(json: String) {
        val sid = currentSessionId ?: return
        val tag = LocalStore.backendTag(auth.getBackend())
        LocalStore.saveBranchState(context, tag, sid, json)
    }

    /** 读取当前会话的分支选择状态；无则返回空串（前端按默认「最新」处理）。 */
    @JavascriptInterface
    fun loadBranchState(): String {
        val sid = currentSessionId ?: return ""
        val tag = LocalStore.backendTag(auth.getBackend())
        return LocalStore.loadBranchState(context, tag, sid)
    }

    /** 按指定会话 id 保存分支状态（WebApiServer 浏览器模式用；不受 currentSessionId 影响）。 */
    fun saveBranchStateFor(sid: String, json: String) {
        if (sid.isBlank()) return
        val tag = LocalStore.backendTag(auth.getBackend())
        LocalStore.saveBranchState(context, tag, sid, json)
    }

    /** 按指定会话 id 读取分支状态（WebApiServer 浏览器模式用）。 */
    fun loadBranchStateFor(sid: String): String {
        if (sid.isBlank()) return ""
        val tag = LocalStore.backendTag(auth.getBackend())
        return LocalStore.loadBranchState(context, tag, sid)
    }

    /** 某会话的消息总数（含 system/工具消息）。用于前端判断是否还有更早历史可加载。 */
    @JavascriptInterface
    fun getMessageCount(sessionId: String): Int {
        val st = stateFor(sessionId)
        synchronized(historyLock) {
            if (st.history.isNotEmpty()) return st.history.size
        }
        val tag = LocalStore.backendTag(auth.getBackend())
        return runCatching { LocalStore.loadMessages(context, tag, sessionId).size }.getOrDefault(0)
    }

    /**
     * 分页拉取某会话的 [limit] 条历史（按 offset 从新到旧倒数第 offset 条开始）。
     *
     * 返回 JSON：{"messages": [...], "total": N, "offset": Int, "limit": Int, "hasMore": Boolean}。
     * 前端把 messages 追加到 DOM 顶部并平移现有消息索引（详见 chat.html 的 onHistory 分页路径）。
     * [offset] = 从最新一条往回数第几条（0 = 最新一批，50 = 更早的 50 条…）。
     */
    @JavascriptInterface
    fun getMessagesPage(sessionId: String, offset: Int, limit: Int): String {
        try {
            val st = stateFor(sessionId)
            val all: List<ChatMessage>
            synchronized(historyLock) {
                all = if (st.history.isNotEmpty()) st.history.toList()
                else {
                    val tag = LocalStore.backendTag(auth.getBackend())
                    LocalStore.loadMessages(context, tag, sessionId)
                }
            }
            return messagesPageJson(all, offset.coerceAtLeast(0), limit.coerceAtLeast(1))
        } catch (_: Exception) {
            return JSONObject().put("messages", JSONArray()).put("total", 0).toString()
        }
    }

    /**
     * 把全量历史列表按 [offset]（从新到旧倒数）+ [limit] 切片并序列化成前端 onHistory 可用的
     * JSON payload（格式与 Tabs.messagesToHistoryJson 完全一致，见 Tabs.kt）。
     */
    private fun messagesPageJson(all: List<ChatMessage>, offset: Int, limit: Int): String {
        val total = all.size
        // offset 语义：从最新一条往回数第 offset 条开始的 [limit] 条。
        // 关键：total < limit 时（例如只有 30 条历史），必须把 start 强制夹到 0，
        // 否则 total - offset - limit 会得到负数，subList 抛 IndexOutOfBoundsException，
        // 外层 catch 静默吞掉会返回空 payload，前端 hasMore 被误关。
        val start = if (offset >= total) total else (total - offset - limit).coerceAtLeast(0)
        val end = if (offset >= total) total else (total - offset).coerceAtMost(total)
        val slice = if (start < end) all.subList(start, end) else emptyList()
        val arr = JSONArray()
        for (m in slice) {
            val obj = JSONObject()
                .put("isUser", m.isUser)
                .put("content", m.content)
                .put("thinking", m.thinking)
                // 日志里存的就是**本地 id**（写入时按当时的偏移换算过），原样交给前端
                .put("id", m.id)
                .put("parentId", m.parentId)
            m.toolCall?.let { tc ->
                obj.put("toolCall", JSONObject()
                    .put("id", tc.id)
                    .put("name", tc.name)
                    .put("arguments", tc.arguments)
                    .put("result", tc.result))
            }
            arr.put(obj)
        }
        return JSONObject()
            .put("messages", arr)
            .put("total", total)
            .put("offset", offset)
            .put("limit", limit)
            // offset 越过最旧一条（>= total）时切片为空：必须报 hasMore=false，
            // 否则前端拿到「空列表 + hasMore=true」的矛盾元数据。
            .put("hasMore", start > 0 && start < end)
            .toString()
    }

    /** Token 流水总条数。文件不存在返回 0。 */
    @JavascriptInterface
    fun getTokenFlowCount(sessionId: String): Int {
        val tag = LocalStore.backendTag(auth.getBackend())
        return runCatching { LocalStore.loadTokenFlow(context, tag, sessionId).size }.getOrDefault(0)
    }

    /**
     * 分页拉取 Token 流水：从最新一条往回数第 [offset] 条开始的 [limit] 条。
     * 返回 JSON：{"entries": [...], "total": N, "offset": Int, "limit": Int, "hasMore": Boolean}。
     * 与 [getMessagesPage] 对称——restoreTokenFlow 只推最近一页，前端滚到顶部再拉更早。
     */
    @JavascriptInterface
    fun getTokenFlowPage(sessionId: String, offset: Int, limit: Int): String {
        try {
            val tag = LocalStore.backendTag(auth.getBackend())
            val lines = LocalStore.loadTokenFlow(context, tag, sessionId)
            val total = lines.size
            val start = if (offset >= total) total else (total - offset - limit).coerceAtLeast(0)
            val end = if (offset >= total) total else total - offset
            val arr = JSONArray()
            if (start < end) {
                for (i in start until end) {
                    runCatching {
                        val e = JSONObject(lines[i].trim())
                        // 同 restoreTokenFlow：剥离 reqText/respText 大字段，带行号供按需取回
                        e.remove("reqText")
                        e.remove("respText")
                        e.put("lineIndex", i)
                        arr.put(e)
                    }
                }
            }
            return JSONObject()
                .put("entries", arr)
                .put("total", total)
                .put("offset", offset)
                .put("limit", limit)
                .put("hasMore", start > 0)
                .toString()
        } catch (_: Exception) {
            return JSONObject().put("entries", JSONArray()).put("total", 0).toString()
        }
    }

    /**
     * 按磁盘行号取回某条 Token 流水的完整原文（含 reqText/respText 原始报文）。
     * 分页/恢复 payload 已剥离这两个大字段，前端「查看」详情弹窗按 lineIndex 在此按需拉取。
     * 运行在 WebView 的 JS 桥线程（非主线程），整文件读的耗时不会卡 UI。
     */
    @JavascriptInterface
    fun getTokenFlowDetail(sessionId: String, lineIndex: Int): String {
        return try {
            val tag = LocalStore.backendTag(auth.getBackend())
            val lines = LocalStore.loadTokenFlow(context, tag, sessionId)
            if (lineIndex < 0 || lineIndex >= lines.size) return "{}"
            lines[lineIndex].trim().ifEmpty { "{}" }
        } catch (_: Exception) { "{}" }
    }

    /**
     * 清空某会话的全部请求流水（删除磁盘 JSONL 文件 + 前端列表同步清空）。
     * 返回 {ok: true} 或 {ok: false, error: ...}。
     */
    @JavascriptInterface
    fun clearTokenFlow(sessionId: String): String {
        return try {
            val sid = sessionId.trim().ifEmpty { currentSessionId ?: "" }
            if (sid.isEmpty()) {
                JSONObject().put("ok", false).put("error", "sessionId 为空").toString()
            } else {
                val tag = LocalStore.backendTag(auth.getBackend())
                LocalStore.deleteTokenFlow(context, tag, sid)
                JSONObject().put("ok", true).put("sessionId", sid).toString()
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: "clear failed").toString()
        }
    }

    /** 当前后端手填的上下文窗口（tokens）；0 = 未配置（压缩内部再按模型表兜底）。 */
    private fun configuredContextWindow(): Int = when (auth.getBackend()) {
        BackendType.LITERT -> auth.getLiteRtMaxTokens()
        BackendType.MNN -> auth.getMnnMaxOutput()
        else -> auth.getOpenAIContextWindow()
    }

    /** 当前后端手填的最大输入（tokens）；0 = 不限制。 */
    private fun configuredMaxInput(): Int = when (auth.getBackend()) {
        BackendType.LITERT -> auth.getLiteRtMaxInput()
        BackendType.MNN -> auth.getMnnMaxOutput()
        else -> auth.getOpenAIMaxInput()
    }

    /** 当前后端的模型名（OpenAI = active profile 模型；DeepSeek = 固定 deepseek-chat 供窗口表匹配）。 */
    private fun configuredModel(): String = when (auth.getBackend()) {
        // 本地模型不参与云端窗口表匹配：窗口已由用户显式配置
        BackendType.LITERT -> auth.getLiteRtModelLabel()
        BackendType.MNN -> auth.getMnnModelLabel()
        else -> auth.getOpenAIModel()
    }

    /** 当前会话对应的压缩触发阈值（token）：min(窗口×0.8, 最大输入)，用户手填值优先。 */
    private fun compactThreshold(): Int =
        contextCompactor.thresholdTokens(configuredModel(), configuredContextWindow(), configuredMaxInput())

    /** 当前会话对应的保留预算（token），用户手填窗口优先。 */
    private fun compactRetainTokens(): Int =
        contextCompactor.retainTokensFor(configuredModel(), configuredContextWindow())

    /**
     * 摘要调用的生成预算（token）：跟随用户设置的最大输出 token，不低于 4096 保底。
     * 此前固定 4096：长会话的检查点必然写不完，finish_reason=length → 压缩失败，只能走降级截断。
     */
    private fun compactSummaryMaxTokens(): Int =
        ContextCompactor.summaryMaxTokens(
            if (auth.getBackend() == BackendType.LITERT) auth.getLiteRtMaxOutput()
            else auth.getOpenAIMaxTokens()
        )

    /**
     * Step 2: 调用 LLM 对旧消息做结构化摘要，返回摘要文本。
     * 将消息渲染为文本再追加压缩指令，避免工具调用对/reasoning 字段的结构约束；
     * 渲染由 ContextCompactor.renderForSummary 完成（工具结果先经过剪枝，内容完整保留）。
     */
    /**
     * 生成压缩摘要（对齐 deepseek-harness `summarizer.ts`）。
     *
     * 三个关键点：
     * 1. **prefix-cache 对齐**：把会话自己的 system + 被阴影化的消息**原样结构化重放**，
     *    摘要指令作为**最后一条 user message** 追加。这样辅助请求是上一次路由请求的
     *    真前缀，provider 的 KV cache 可直接复用（dsh summarizer.ts:24-30 明确的设计意图）。
     *    改造前把消息 `renderForSummary` 压平成纯文本塞进单条 user 消息，与上次请求
     *    零前缀重合，每次压缩都要从冷 cache 重新 prefill，白烧 token 且更慢。
     * 2. **截断即失败**：`finish_reason=length` 视为失败（对齐 dsh `finishError` 的
     *    max-tokens 分支："incomplete checkpoint"）。装一个被截断的检查点会让后续对话
     *    永久丢失后半段章节，且不报错——比压缩失败本身更糟。
     * 3. 关闭思考模式：thinking 计入 maxTokens，深度模型思考可达上万 tokens，会占满
     *    生成预算导致 content 为空（日志中「摘要结果为空」的根因）。
     *
     * @param messages 被阴影化（待摘要）的消息序列，已保证 tool-pairing 平衡。
     * @param systemForPrefix 会话的 system 消息，用于前缀对齐；无则传 null。
     */
    private suspend fun summarizeViaLLM(
        messages: List<com.mcp.llm.ChatMessage>,
        systemForPrefix: com.mcp.llm.ChatMessage? = null
    ): String {
        val prefix = if (systemForPrefix != null) listOf(systemForPrefix) else emptyList()
        val req = LLMRequest(
            // 结构化重放：system + 原文消息 + 末尾指令。不再压平成纯文本。
            messages = prefix + messages + com.mcp.llm.ChatMessage(
                role = "user",
                content = ContextCompactor.COMPACT_INSTRUCTION
            ),
            tools = null,
            config = LLMConfig(
                model = auth.getOpenAIModel(),
                maxTokens = compactSummaryMaxTokens(),
                stream = true,
                thinkingEnabled = false
            )
        )
        val sb = StringBuilder()
        var truncated = false
        client().sendMessage(req).collect { event ->
            when (event) {
                is MessageEvent.Content -> sb.append(event.delta)
                is MessageEvent.Done -> truncated = event.truncated
                else -> {}
            }
        }
        val result = sb.toString().trim()
        if (result.isEmpty()) throw IllegalStateException("LLM 摘要结果为空")
        if (truncated) throw IllegalStateException(
            "摘要被 max_tokens(${compactSummaryMaxTokens()}) 截断，检查点不完整，拒绝安装"
        )
        return result
    }



    /** 模型无关降级：保留选中尾部（摘要失败 / 摘要不收缩时使用），并记录原因。 */
    private fun fallbackTruncate(
        sid: String,
        st: SessionState,
        systemMsg: com.mcp.llm.ChatMessage?,
        selection: ContextCompactor.RetainedSelection,
        reason: String,
        beforeTokens: Int
    ) {
        LogStore.w("COMPACT", "$reason session=$sid")
        st.openAIMessages.clear()
        if (systemMsg != null) st.openAIMessages.add(systemMsg)
        st.openAIMessages.addAll(selection.retained)
        LogStore.i("COMPACT", "降级截断 session=$sid: $beforeTokens tokens → ${contextCompactor.estimateTokens(st.openAIMessages)} tokens")
    }

    /**
     * Step 2+3: 上下文压缩主流程（对齐 deepseek-harness compaction-basic）。
     * 1) 模型无关工具结果剪枝（prune）；2) 压力超过阈值（模型窗口 × 比例）时，
     *    按 token 预算 + 条数地板 + tool-pairing 平衡切点选出保留尾部；
     * 3) LLM 结构化摘要被替换范围；4) 收缩验证（摘要必须比被替换范围小）；
     * 5) 替换（replace）而非追加——system + <compacted-summary> 检查点 + 保留尾部；
     * 6) 收敛：替换后仍超阈值且有重试预算 → 对替换后的表面再压一轮（合并旧检查点）。
     */
    private suspend fun compactOpenAIIfNeeded(
        sid: String, st: SessionState, extraTokens: Int = 0,
        retriesLeft: Int = ContextCompactor.DEFAULT_COMPACTION_RETRIES,
        force: Boolean = false
    ) {
        // Web 自动化：站点会话自己维护上下文，本地 openAIMessages 缓冲只作簿记、从不发给站点。
        // 压缩摘要若走 client() 会被当成普通消息**打字进站点对话**（表现为站点里反复出现
        // 长段提示词/摘要文本）——该后端直接跳过本地压缩。
        if (auth.getBackend() == BackendType.WEB_AUTOMATION) return
        // 预设关闭常规压缩（`compaction:false`，如极简模式）：跳过按阈值触发的压缩，保住长会话状态。
        // 只对**非 force** 生效：force 路径（DeepSeek 前置压缩 / 溢出自救）是逃出窗口的最后手段，
        // 一起关掉会让长会话直接失败。
        if (!force && !compactionEnabled(sid)) {
            LogStore.i("COMPACT", "预设关闭常规压缩，跳过 session=$sid")
            return
        }
        // 压缩状态推送：仅最外层调用推送，避免递归调用导致闪烁中断
        val isOutermost = retriesLeft == ContextCompactor.DEFAULT_COMPACTION_RETRIES
        if (isOutermost && sid == currentSessionId) emit("compacting", JSONObject().put("active", true))
        try {
            // Step 1: 模型无关的工具结果剪枝（无需 LLM，先释放大头）
            contextCompactor.pruneToolResults(st.openAIMessages)
            val threshold = compactThreshold()
            // 压力 = 消息估算 + 工具定义估算（tools 参数每次请求都全量携带，可能数万 tokens）
            val tokens = contextCompactor.estimateTokens(st.openAIMessages) + extraTokens
            if (!force && tokens <= threshold) return

            // Step 2+3: 走压缩事务——锁、异步摘要、稳定性复检、收缩验证、replace 提交一体完成。
            // 绝不能在此处自己「取快照 → 摘要 → clear+addAll」：摘要是秒级异步调用，
            // 期间 openAIMessages 若增长，旧快照提交会把新增消息整段抹掉。
            val outcome = try {
                compactionTransaction.run(
                    snapshot = { st.openAIMessages.toList() },
                    apply = { replacement ->
                        st.openAIMessages.clear()
                        st.openAIMessages.addAll(replacement)
                    },
                    summarize = { shadowed, system -> summarizeViaLLM(shadowed, system) },
                    retainTokens = compactRetainTokens()
                )
            } catch (e: SurfaceChangedException) {
                // 正常竞态：摘要期间上下文变了。本轮放弃即可，下次请求会自然再压——这不是故障。
                LogStore.w("COMPACT", "压缩期间上下文已变化，放弃本轮 session=$sid: ${e.message?.take(80)}")
                return
            } catch (e: CompactionTransaction.BusyException) {
                LogStore.w("COMPACT", "压缩锁已被占用，跳过本轮 session=$sid")
                return
            } catch (e: IllegalArgumentException) {
                fallbackTruncateNow(sid, st, "摘要未收缩，降级截断: ${e.message?.take(80)}", tokens)
                return
            } catch (e: Exception) {
                fallbackTruncateNow(sid, st, "LLM 摘要失败，降级截断: ${e.message?.take(80)}", tokens)
                return
            }
            if (outcome == null) return

            // Step 4: 收敛——替换后（含工具定义）仍超阈值且有重试预算 → 再压一轮（旧检查点会被合并）
            val after = contextCompactor.estimateTokens(st.openAIMessages) + extraTokens
            if (after > threshold && retriesLeft > 0) {
                LogStore.i("COMPACT", "压缩后仍超阈值 ($after > $threshold)，递归再压 session=$sid")
                compactOpenAIIfNeeded(sid, st, extraTokens, retriesLeft - 1)
                return
            }
            LogStore.i("COMPACT", "上下文压缩完成 session=$sid: ${outcome.shadowedCount} 条→摘要, 检查点约 ${outcome.summaryTokens} tokens, $tokens tokens → $after tokens")
        } finally {
            // 压缩完成（或失败/提前返回）：仅最外层调用通知前端恢复正常样式
            if (isOutermost && sid == currentSessionId) emit("compacting", JSONObject().put("active", false))
        }
    }

    /**
     * 降级截断：用**当前最新**状态重新选择保留尾部（而非沿用摘要前的旧快照）。
     * 摘要失败 / 未收缩 / 参数异常时走此路径，保证上下文至少被缩减。
     */
    private fun fallbackTruncateNow(sid: String, st: SessionState, reason: String, beforeTokens: Int) {
        val current = st.openAIMessages.toList()
        val systemMsg = current.firstOrNull { it.role == "system" }
        val nonSystem = current.filter { it.role != "system" }
        // 保留预算直接用 profile 配置（contextWindow × 0.16），不另设独立上限：
        // 万一降级后请求仍被网关拒收（如 SenseNova 的 "inference request is invalid"），
        // 由 isContextWindowExceededError 命中后的溢出恢复兜底（只留最近 OVERFLOW_RETAIN_MESSAGES 条 + 重试）。
        val selection = contextCompactor.selectRetainedTail(nonSystem, compactRetainTokens())
        if (selection == null) {
            LogStore.w("COMPACT", "$reason，但已无可安全压缩范围 session=$sid")
            return
        }
        fallbackTruncate(sid, st, systemMsg, selection, reason, beforeTokens)
    }

    /**
     * 溢出恢复（对齐 deepseek-harness overflow-recovery）：provider 已确认上下文窗口溢出，
     * 绕过正常压力阈值，剪枝后做一次激进平衡头部缩减（保留最近 [OVERFLOW_RETAIN_MESSAGES] 条）。
     * 摘要失败时降级为模型无关截断；返回是否已产生可用缩减（供上层决定是否重试）。
     */
    private suspend fun forceCompactForOverflow(sid: String, st: SessionState, extraTokens: Int = 0): Boolean {
        contextCompactor.pruneToolResults(st.openAIMessages)
        val before = contextCompactor.estimateTokens(st.openAIMessages)
        val outcome = try {
            compactionTransaction.run(
                snapshot = { st.openAIMessages.toList() },
                apply = { replacement ->
                    st.openAIMessages.clear()
                    st.openAIMessages.addAll(replacement)
                },
                summarize = { shadowed, system -> summarizeViaLLM(shadowed, system) },
                // 激进保留：预算取 Int.MAX_VALUE（永不满足 → 纯条数地板驱动）
                retainTokens = Int.MAX_VALUE,
                minRetain = OVERFLOW_RETAIN_MESSAGES,
                // 溢出恢复的目标是「无论代价先逃出溢出」，摘要即使没变小也必须替换，
                // 因此跳过常规压缩的收缩验证（否则可能因"摘要不够小"而恢复失败）。
                requireShrink = false
            )
        } catch (e: SurfaceChangedException) {
            LogStore.w("COMPACT", "溢出恢复期间上下文已变化，放弃 session=$sid: ${e.message?.take(80)}")
            return false
        } catch (e: CompactionTransaction.BusyException) {
            LogStore.w("COMPACT", "压缩锁已被占用，溢出恢复跳过 session=$sid")
            return false
        } catch (e: Exception) {
            LogStore.w("COMPACT", "溢出恢复摘要失败，模型无关截断: ${e.message?.take(80)} session=$sid")
            fallbackTruncateNow(sid, st, "溢出恢复降级截断", before)
            return true
        }
        if (outcome == null) {
            LogStore.w("COMPACT", "溢出恢复：无可安全压缩范围，保留全部 session=$sid")
            return false
        }
        LogStore.i("COMPACT", "溢出恢复完成 session=$sid: ${outcome.shadowedCount} 条→摘要, $before → ${contextCompactor.estimateTokens(st.openAIMessages)} tokens")
        return true
    }

    /**
     * 轮转（或溢出恢复轮转）后把新的服务端会话状态**写回本地文件**。
     *
     * 三件事必须一起做：
     *  1. 记住新的 serverSessionId —— 否则进程被杀后退回旧的（已满的）服务端会话，轮转被静默撤销；
     *  2. 推进 idOffset —— 新会话的消息 id 从 1 重新开始，必须与本地已有气泡 id 隔开，
     *     否则 truncate/delete 按 id 定位会命中旧消息（编辑一条新消息却删掉大半段历史）；
     *  3. 清掉旧的续聊锚点 —— 旧锚点属于旧会话，留着会被当成新会话的父节点。
     *
     * 基线（lastPromptTokens）同时清零：服务端新会话的第一次上报到达前，不能再按旧数值轮转。
     */
    private fun persistRotation(sid: String, st: SessionState, newServerSessionId: String) {
        val lastRaw = maxOf(
            st.parentMessageId?.toLongOrNull() ?: 0L,
            (synchronized(historyLock) {
                st.history.mapNotNull { it.id.toLongOrNull() }.maxOrNull() ?: 0L
            }) - st.idOffset
        )
        st.serverSessionId = newServerSessionId
        st.parentMessageId = null
        st.lastPromptTokens = 0
        st.idOffset = MessageIdSpace.advance(st.idOffset, lastRaw.toString())
        val tag = LocalStore.backendTag(auth.getBackend())
        runCatching {
            LocalStore.saveSessionRotation(context, tag, sid, LocalStore.SessionRotation(newServerSessionId, st.idOffset))
            LocalStore.saveSessionCurrentId(context, tag, sid, null)
        }
        LogStore.i(
            "COMPACT",
            "轮转状态已落盘 session=$sid 服务端会话=$newServerSessionId idOffset=${st.idOffset}（旧锚点已作废）"
        )
    }

    /** Markdown 技能仓库（按需注入到会话上下文）。 */
    private val skillRepo = SkillRepository(context.applicationContext)

    /**
     * 由组合根统一提供工具运行时和提示词组合器，ChatBridge 只负责会话协议与 WebView 事件转发。
     */
    private val toolRuntime = ToolRuntimeHolder.get(context, auth)
    private val toolbox = toolRuntime.toolbox
    private val agentOrchestrator = toolRuntime.agentOrchestrator
    private val promptComposer = SystemPromptComposer(context.applicationContext, skillRepo).apply {
        // 远程 MCP 服务清单注入系统提示词：让模型知道有哪些远程工具、端点在哪、怎么调。
        // 每次组装时实时读取，因此新增/移除服务器、连接状态变化都能立即反映。
        mcpContextProvider = { toolRuntime.remoteRegistry.describeForPrompt() }
    }

    init {
        // 注册 Web 自动化驱动（app 层实现依赖 WebBrowser，llm 模块不能反向引用）。
        // 用 provider 而非实例：WebView 可能被销毁重建，每次取最新。
        LLMClientFactory.webAutomationDriverProvider = {
            WebAutomationDriverImpl(context.applicationContext, auth)
        }
    }

    /** 按当前设置（DeepSeek 逆向 / OpenAI 兼容 / Web 自动化）构造对应的 LLM 客户端。 */
    private fun client(): LLMClient = LLMClientFactory.create(auth)

    init {
        applyAgentSettings()
        skillRepo.discover()
        // 后台预热 system_prompt.txt，避免首次消息时同步 IO 阻塞 WebView 桥线程
        scope.launch { promptComposer.preload() }
    }

    /** 从 SharedPreferences 读取 Agent 设置并应用到编排器。 */
    fun applyAgentSettings() {
        agentOrchestrator.refreshSettings()
    }

    /**
     * 恢复当前会话是否有活跃的流任务（用于退出再进入时接上进度）。
     * 返回 StreamTaskManager.StreamTask?，调用方可据此恢复 UI 状态。
     */
    fun getActiveStream(): StreamTaskManager.StreamTask? {
        val sid = currentSessionId ?: return null
        return StreamTaskManager.get(sid)
    }

    /** 检查当前会话是否有活跃流（轻量查询）。 */
    fun hasActiveStream(): Boolean {
        val sid = currentSessionId ?: return false
        return StreamTaskManager.hasActiveTask(sid)
    }

    // ── 本地消息 id（OpenAI 兼容后端的消息树锚点）──────────────────────────────
    //
    // 背景：DeepSeek 逆向协议的服务端维护一棵消息树，每轮回传真实 message_id，
    // 前端 assignMessageIds() 据此给气泡编号并串起父子关系——「重新生成」与
    //「编辑重发」正是靠这套 id 定位锚点的。
    //
    // OpenAI 兼容后端没有服务端状态：SSE 永远不会下发 message_id，历史里的
    // ChatMessage.id 恒为空串。于是前端 doRegenerate() 在 `if (!cid)` 处直接
    // toast「这条回复缺少服务端消息 id」并放弃，doEditResend() 也退化成
    // 「改父锚点后当新消息发」的老路径——两个功能在该后端上等同缺失。
    //
    // 修法：在无服务端状态的后端上**本地合成一棵等价的线性消息树**，编号沿用
    // DeepSeek 逆向协议的约定（每轮 user = 2k-1、assistant = 2k，即 assistant = user + 1），
    // 这样前端 `uId = assistantId - 1` 的反推与 `parentId = 上一条消息 id` 的链式结构
    // 全部照旧成立，前端改动量降到最小（只需新增 truncate 事件 + 让 dead 节点不参与树）。

    /** 取下一个本地消息 id（单调递增；截断丢弃的 id 不再复用，避免分叉节点撞车）。 */
    private fun nextLocalMessageId(st: SessionState): String = (++st.msgIdSeq).toString()

    /**
     * 给待入库消息打上本地 id 与父子链。
     *
     * DeepSeek 后端（或已带 id 的历史消息）原样返回——它的 id 由服务端分配、
     * 由前端回填，本地不得越权改写，否则会把服务端消息树打散。
     */
    private fun tagLocalId(st: SessionState, msg: ChatMessage, prevId: String): ChatMessage =
        if (msg.id.isNotBlank()) msg
        else msg.copy(id = nextLocalMessageId(st), parentId = prevId)

    /**
     * 往本地线性历史追加一条消息，并（在 OpenAI 兼容后端下）分配本地 id 与父子链。
     *
     * 集中在这里是为了保证 id 分配只有一个入口：任何新增历史的代码路径
     * （用户提问 / 助手回复 / 工具卡片 / ask_user / 停止定稿）都不会漏掉锚点，
     * 也就不会再出现「能重新生成但锚点错位」这类半残状态。
     *
     * @return 真正入库的那条（可能已带 id/parentId）；调用方应拿它去落盘。
     */
    private fun addToHistory(st: SessionState, msg: ChatMessage): ChatMessage {
        // 取值 + 追加必须在同一把锁内完成：否则「读到的上一条 id」可能已被并发的
        // 追加改写，父子链会接错。用 synchronized 的返回值带出结果，
        // 避免在 lambda 里给外部 val 赋值（Kotlin 不允许捕获的 val 重新赋值）。
        return synchronized(historyLock) {
            val stored = tagLocalId(st, msg, st.history.lastOrNull()?.id.orEmpty())
            st.history.add(stored)
            stored
        }
    }

    /**
     * 把本地线性历史截断到锚点消息**之前**（锚点及其之后的全部丢弃）。
     *
     * 这是「重新生成 / 编辑重发」在无服务端状态的 OpenAI 后端上的等效实现：
     * 服务端实现是在锚点消息的父节点下开一条兄弟分支，本地线性日志没有分支概念，
     * 截断到锚点前即可保证「被丢弃的那条回复不会再进入后续上下文」。
     *
     * 新消息随后由 [addToHistory] 追加，其 parentId 自动取截断后的末条 id
     * —— 也就是被丢弃锚点原本的 parentId，树形因此保持连续。
     *
     * @return 是否找到锚点。找不到时调用方应报错退出，
     *   而不是退化成「追加到对话末尾」——那会把编辑/重生成静默变成一次普通追问。
     */
    private fun truncateHistoryAt(st: SessionState, messageId: String): Boolean {
        synchronized(historyLock) {
            val idx = st.history.indexOfFirst { it.id == messageId }
            if (idx < 0) return false
            while (st.history.size > idx) st.history.removeAt(st.history.size - 1)
            // 重新生成/编辑重发是合法重跑：同一批 id 允许再消费，故清台账
            st.consumedToolCallIds.clear()
            return true
        }
    }

    /**
     * 为缺失 id 的存量历史补齐本地 id 与父子链（**仅 OpenAI 兼容后端**）。
     *
     * 本地 id 机制上线前创建的会话，所有消息的 id 都是空串，前端同样拿不到锚点。
     * 历史加载后就地回填一次并落盘，老会话立刻可用，无需用户重开。
     *
     * @return 是否发生了回填（true 时调用方需回写磁盘，否则下次加载仍是无 id 的旧数据）。
     */
    private fun backfillLocalIds(st: SessionState): Boolean {
        var changed = false
        synchronized(historyLock) {
            var prevId = ""
            for (i in st.history.indices) {
                val m = st.history[i]
                // 已带 id 的历史片段（理论上不会有）视为权威，只把它接进链条
                val id = if (m.id.isNotBlank()) m.id else nextLocalMessageId(st)
                if (id != m.id || m.parentId != prevId) {
                    st.history[i] = m.copy(id = id, parentId = prevId)
                    changed = true
                }
                prevId = id
            }
        }
        return changed
    }

    /** 把本地 id 序号推进到历史中已有 id 之后，保证新发 id 不与历史撞车（会话态被 LRU 淘汰后重建时必需）。 */
    private fun syncLocalIdSeq(st: SessionState) {
        var max = 0L
        synchronized(historyLock) {
            for (m in st.history) max = maxOf(max, m.id.toLongOrNull() ?: 0L)
        }
        if (st.msgIdSeq < max) st.msgIdSeq = max
    }

    /**
     * 把新分配的本地消息 id 回传前端（**仅 OpenAI 兼容后端**）。
     *
     * 复用前端已有的 `message_id` 事件：DeepSeek 后端由服务端事件触发，
     * OpenAI 后端由这里合成触发，前端 assignMessageIds() 无需区分来源。
     * 工具续聊轮里工具卡片也会占用一个 id，事件随即把它推进到下一轮的助手 id——
     * 前端 assignMessageIds() 有「气泡已有 id 则只推进不重挂父」的分支，正好兼容。
     */
    private suspend fun emitLocalMessageId(sid: String, messageId: String) {
        if (messageId.isBlank()) return
        syncMessageIdState(sid, messageId)
        withContext(Dispatchers.Main) {
            emitStream(sid, "message_id", JSONObject().put("id", toLocalId(sid, messageId)))
        }
    }

    /**
     * 通知前端：本地历史已在锚点处截断，请把该锚点及其之后的气泡一并作废。
     *
     * 前端自己只隐藏了被重生成/被编辑的那一条（`oldRow.style.display = "none"`），
     * 它后面的气泡仍在 DOM 里。不通知的话，重新生成中间某条回复后会看到
     * 「新回复插在中间、旧尾巴还挂在下头」，直到切走再切回才恢复。
     * 这里发 truncate 事件让前端把这些节点标 dead（不参与消息树、恒隐藏），
     * 比整页重建 onHistory 轻得多，也不会打断正在进行的流。
     */
    private suspend fun emitTruncateFrom(sid: String, messageId: String) {
        withContext(Dispatchers.Main) {
            // 入参就是**本地 id**（本地历史与前端气泡同处一个 id 空间）
            emitStream(sid, "truncate", JSONObject().put("id", messageId))
        }
    }

    /** 同步 st.parentMessageId / streamMessageId（与 DeepSeek 后端的 MessageId 分支保持一致的状态副作用）。 */
    private fun syncMessageIdState(sid: String, messageId: String) {
        val st = state(sid) ?: return
        st.parentMessageId = messageId
        st.streamMessageId = messageId
    }

    /**
     * 灌入历史（加载成功后调用）。
     * @param persist 是否全量回写日志：远端拉取的历史（DeepSeek）需要落盘同步；
     *   从本地日志读出的内容与磁盘同源，回写是纯冗余，传 false 跳过
     *   （旧格式迁移已下沉到 PersistenceCoordinator.load 内完成，不再依赖这趟往返）。
     */
    fun setHistory(sessionId: String, list: List<ChatMessage>, persist: Boolean = true) {
        val sid = sessionId
        val st = stateFor(sid)
        val snapshot: List<ChatMessage>
        synchronized(historyLock) {
            st.history.clear()
            st.history.addAll(list)
            snapshot = st.history.toList()
        }
        if (true) {
            // 补齐存量会话缺失的本地 id（老数据全部为空串），让「重新生成 / 编辑重发」
            // 立刻有锚点可用；回填过就必须落盘，否则下次加载又退回无 id 状态。
            if (backfillLocalIds(st)) {
                val fixTag = LocalStore.backendTag(auth.getBackend())
                val fixed = synchronized(historyLock) { st.history.toList() }
                scope.launch {
                    saveMutex.withLock { LocalStore.rebuildMessages(context, fixTag, sid, fixed) }
                }
                LogStore.i("OPENAI", "存量会话补齐本地消息 id: ${fixed.size} 条, 会话=$sid")
            }
            syncLocalIdSeq(st)
            // OpenAI 兼容后端：用已加载的历史重新播撒无状态消息缓冲，恢复多轮上下文
            seedOpenAIMessages(sid)
        }
        if (!persist) return
        val tag = LocalStore.backendTag(auth.getBackend())
        scope.launch { saveMutex.withLock { LocalStore.rebuildMessages(context, tag, sid, snapshot) } }
    }

    /**
     * 惰性补齐 OpenAI 兼容会话的上下文缓冲（system + 完整历史）。
     *
     * 无状态缓冲只在本 ChatBridge 实例的内存态里累积；会话历史通常由手机端 Tabs 打开会话时
     * 经 setHistory 灌入。WebApiServer 使用独立的 ChatBridge（无 WebView/无 Tabs），冷启动后
     * 缓冲为空——此时 openAIRequest 只会补 system + 当前 user，整段历史丢失，表现为
     * 「Web 请求构造不带历史记录」。本方法在发送/重新生成/编辑前惰性补一次（幂等，仅一次）：
     * 磁盘有该会话消息日志就整体读回并播撒缓冲。
     */
    private fun ensureOpenAIContextSeeded(sid: String) {
        val st = stateFor(sid)
        if (st.sessionHistoryLoaded == sid) return   // 本实例已加载过（Tabs 打开或上次惰性补齐）
        val local = runCatching {
            // 当前后端的命名空间（Web 自动化是 wa）——写死 oa 会让 wa 会话每次请求都丢历史
            LocalStore.loadMessages(context, LocalStore.backendTag(auth.getBackend()), sid)
        }.getOrDefault(emptyList())
        if (local.isNotEmpty()) {
            // 与 Tabs 打开会话同一条路径：清空 + 回填 + seedOpenAIMessages；persist=false 只改内存，
            // 磁盘内容本就同源，避免冗余全量回写。
            setHistory(sid, local, persist = false)
        }
        st.sessionHistoryLoaded = sid
    }

    /** 用加载到该会话历史缓冲的本地历史重新构造 OpenAI 后端的无状态消息缓冲。 */
    private fun seedOpenAIMessages(sid: String) {
        val st = stateFor(sid)
        st.openAIMessages.clear()
        st.pendingToolMeta.clear()
        st.openAIMessages.add(com.mcp.llm.ChatMessage(role = "system", content = buildOpenAISystemContent(sid)))
        synchronized(historyLock) {
            for (h in st.history) {
                if (h.toolCall != null) {
                    // 工具结果还原为 function calling 序列：先补一条 assistant(tool_calls)，
                    // 再补 tool 消息，确保无状态缓冲里 tool 消息有合法前驱（否则 OpenAI 会 400）。
                    st.openAIMessages.add(
                        com.mcp.llm.ChatMessage(
                            role = "assistant",
                            reasoning = h.thinking.takeIf { it.isNotBlank() },
                            toolCalls = listOf(
                                com.mcp.llm.ToolCall(
                                    id = h.toolCall!!.id,
                                    function = com.mcp.llm.ToolCallFunction(h.toolCall!!.name, h.toolCall!!.arguments)
                                )
                            )
                        )
                    )
                    st.openAIMessages.add(
                        com.mcp.llm.ChatMessage(
                            role = "tool",
                            content = extractToolContent(h.toolCall!!.result),
                            toolCallId = h.toolCall!!.id
                        )
                    )
                } else {
                    st.openAIMessages.add(
                        com.mcp.llm.ChatMessage(
                            role = if (h.isUser) "user" else "assistant",
                            content = h.content,
                            reasoning = if (h.isUser) null else h.thinking.takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
        }
        LogStore.i("OPENAI", "从历史重新播撒无状态缓冲: ${st.openAIMessages.size} 条消息")
        // 历史加载后同步裁剪超长工具结果；token 仍超阈值则按平衡切点截断（无 LLM，不阻塞加载路径）
        contextCompactor.pruneToolResults(st.openAIMessages)
        val seedTokens = contextCompactor.estimateTokens(st.openAIMessages)
        if (seedTokens > compactThreshold()) {
            val sysMsg = st.openAIMessages.firstOrNull { it.role == "system" }
            val nonSys = st.openAIMessages.filter { it.role != "system" }
            val selection = contextCompactor.selectRetainedTail(nonSys, compactRetainTokens())
            if (selection != null && selection.retained.size < nonSys.size) {
                st.openAIMessages.clear()
                if (sysMsg != null) st.openAIMessages.add(sysMsg)
                st.openAIMessages.add(com.mcp.llm.ChatMessage(role = "user",
                    content = "注：历史对话较长，已截断保留最近 ${selection.retained.size} 条消息（完整历史已在本地存储）。"))
                st.openAIMessages.add(com.mcp.llm.ChatMessage(role = "assistant", content = "好的，请继续。"))
                st.openAIMessages.addAll(selection.retained)
                LogStore.i("COMPACT", "历史加载截断: $seedTokens tokens → ${contextCompactor.estimateTokens(st.openAIMessages)} tokens")
            }
        }
    }

    /** 切换会话时清空当前会话的累积列表（避免残留上一会话的消息），并重置历史加载标记。 */
    fun clearHistory() {
        val st = state(currentSessionId) ?: return
        synchronized(historyLock) { st.history.clear() }
        st.openAIMessages.clear()
        st.pendingToolMeta.clear()
        st.sessionHistoryLoaded = null
        st.isRegenerating = false
        synchronized(st.operationHistory) { st.operationHistory.clear() }
        LogStore.d("TOOL", "ChatBridge 清空历史 + 操作记录")
    }

    /**
     * 会话被**删除**时清空它在内存中的一切痕迹（磁盘文件由调用方删除）。
     *
     * 只删磁盘是不够的：内存里的 SessionState 仍在（isBusy / streamJob / history / 锚点），
     * 还在跑的流会继续把消息写进**已删除**会话的日志（deleteRecursively 之后目录又被重建），
     * 表现为「列表里没有这个会话，后台却在持续消耗 token」的幽灵会话。
     * 同时把该会话的待输入 deferred 取消，避免 ask_user 悬挂。
     */
    internal fun forgetSession(sid: String) {
        val st = sessionStates.remove(sid) ?: return
        runCatching {
            st.streamJob?.cancel()
            synchronized(st.pendingUserInput) {
                st.pendingUserInput.values.forEach { it.cancel() }
                st.pendingUserInput.clear()
            }
        }
        synchronized(historyLock) { st.history.clear() }
        st.openAIMessages.clear()
        st.pendingToolMeta.clear()
        StreamTaskManager.complete(sid)
        // 释放该会话在 LiteRT 侧的 Conversation（含 KV cache）。
        // 不加后端判断：被删的会话可能是在「切到别的后端之前」用 LiteRT 建的，
        // 此时 auth.getBackend() 已变，判断当前后端会漏掉它。
        // releaseSession 幂等（sid 不在表里就是无操作），非 LiteRT 后端下零副作用。
        runCatching { com.mcp.litert.LiteRtEngineHolder.releaseSession(sid) }
        LogStore.i("SESS", "已清理被删除会话的内存态 session=$sid")
    }

    /** 清空全部会话态（切换 LLM 后端时调用：旧后端 sessionId 全部失效，避免历史/openAI 缓冲残留占内存）。 */
    internal fun clearAllSessionStates() {
        sessionStates.clear()
        // 切后端时卸载 LiteRT 引擎与全部 Conversation。
        // 不这么做的话，从 LITERT 切走（或切到 LITERT 再切走）会留下：
        //   - Engine 持有的模型权重（设备端 3~4 GB 常驻）
        //   - 每个会话的 Conversation KV cache（maxNumTokens 越大越可观）
        // 直到进程被杀才释放。closeAll() 幂等，非 LiteRT 场景下是空操作。
        runCatching { com.mcp.litert.LiteRtEngineHolder.closeAll() }
        LogStore.d("TOOL", "ChatBridge 清空全部会话态")
    }

    /**
     * 标记会话已加载过历史消息，防止重复注入系统提示词。
     * 当从列表点开一个已有会话时，历史加载完成后调用此方法。
     */
    fun markSessionHistoryLoaded(sessionId: String) {
        stateFor(sessionId).sessionHistoryLoaded = sessionId
        LogStore.d("TOOL", "标记会话 $sessionId 已加载历史，后续发消息不再注入系统提示词")
    }

    /**
     * 取该会话的内存缓存历史快照（WebView 池冷重建时宿主优先用它，免磁盘重读）。
     * 仅当历史已加载过（sessionHistoryLoaded 标记在位）且非空时返回，否则 null。
     */
    internal fun cachedHistory(sessionId: String): List<ChatMessage>? {
        val st = state(sessionId) ?: return null
        synchronized(historyLock) {
            if (st.sessionHistoryLoaded == sessionId && st.history.isNotEmpty()) {
                return st.history.toList()
            }
        }
        return null
    }

    /**
     * 从指定消息锚点**分叉**出一个新会话（对齐 dsh core/session `fork(boundary)`）。
     *
     * 把源会话 [boundaryMessageId] 及其之前的历史复制到新会话，新会话因此带一份
     * 独立的 lineage；之后在新会话里续聊不会污染源会话，实现「同一前置下多条探索路径」。
     * 仅对本地管理历史的无状态后端（OpenAI 兼容）生效——DeepSeek 逆向协议的分叉由服务端
     * 按 message_id 完成，本端不做。
     *
     * @return 新会话 id；失败（锚点不存在 / 非本地后端 / 持久化错误）返回 null。
     * @see forkSession 前端的 JS 桥，内部调用本方法。
     */
    fun forkSessionAt(boundaryMessageId: String): String? {

        val srcSid = currentSessionId ?: run {
            LogStore.w("FORK", "分叉时无当前会话")
            return null
        }
        val src = stateFor(srcSid)
        val boundaryIndex = synchronized(historyLock) {
            src.history.indexOfFirst { it.id == boundaryMessageId }
        }
        if (boundaryIndex < 0) {
            LogStore.w("FORK", "分叉锚点不存在：messageId=$boundaryMessageId 会话=$srcSid")
            return null
        }
        val copied: List<ChatMessage> = synchronized(historyLock) {
            src.history.subList(0, boundaryIndex + 1).map { it.copy() }
        }
        val tag = LocalStore.backendTag(BackendType.OPENAI)
        val newSid = "oa-${java.util.UUID.randomUUID()}"
        // 派生命名：取锚点前最近的 user 消息前若干字作为分支标题
        val title = synchronized(historyLock) {
            copied.asReversed().firstOrNull { it.isUser && !it.content.isNullOrBlank() }
                ?.content?.take(20)?.replace("\n", " ")?.trim() ?: "分支 · ${boundaryMessageId.take(8)}"
        }
        val session = com.mcp.core.chat.ChatSession(
            newSid, "⑂ $title", false, System.currentTimeMillis() / 1000.0,
            model = auth.getOpenAIModel(), contextWindow = auth.getOpenAIContextWindow(), maxInput = auth.getOpenAIMaxInput()
        )
        return try {
            // 1)+2) 落盘新会话的消息历史并写入会话列表（historyLock 同 history 写保护），
            //    否则返回列表看不到该会话。
            synchronized(historyLock) {
                LocalStore.rebuildMessages(context, tag, newSid, copied)
                val list = LocalStore.loadSessions(context, tag).toMutableList()
                list.add(session)
                LocalStore.saveSessions(context, tag, list)
            }
            // 3) 在内存中播撒新会话的无状态缓冲（含 openAIMessages 重建 + id 回填），
            //    persist=false：消息已在上一步全量落盘，勿重复回写。
            setHistory(newSid, copied, persist = false)
            LogStore.i("FORK", "已分叉会话 $srcSid → $newSid（锚点=$boundaryMessageId，复制 ${copied.size} 条）")
            onForkedSession?.invoke(newSid)
            newSid
        } catch (e: Exception) {
            LogStore.e("FORK", "分叉会话失败：${e.message}")
            null
        }
    }

    /**
     * 前端「分支」按钮调用：从某条消息分叉出新会话（JS 桥，方法名即 JS 侧 `ChatBridge.forkSession`）。
     * @return 新会话 id；失败返回空串（前端据此提示）。
     */
    @JavascriptInterface
    fun forkSession(messageId: String): String = forkSessionAt(messageId) ?: ""

    /**
     * 分叉完成后由宿主（Tabs）接收新会话 id 并切换窗口的回调。
     * ChatBridge 不直接持有 UI，fork 成功后通知宿主打开新会话。
     */
    var onForkedSession: ((sid: String) -> Unit)? = null

    /**
     * 前端读取当前主题偏好：system / light / dark。
     * 与 MainActivity 共用 "tab_bar_mode" 偏好文件，键为 MainActivity.PREF_THEME。
     */
    @JavascriptInterface
    fun getTheme(): String {
        val prefs = context.getSharedPreferences("tab_bar_mode", Context.MODE_PRIVATE)
        return prefs.getString("pref_theme", "system") ?: "system"
    }

    /**
     * 前端页面加载后调用，返回 JSON：
     * { "ok": true, "hasToken": true/false, "sessionReady": true/false, "sessionId": "..." }
     */
    @JavascriptInterface
    fun checkHealth(): String {
        val sid = currentSessionId
        // OpenAI 兼容后端同样无需 DeepSeek token。若仍按 token 判定，前端会弹
        // 「尚未登录，请在会话页完成登录」的误导横幅，把一个完全可用的会话判成不可用。
        val authorized = hasCredentials()
        return JSONObject().apply {
            put("ok", authorized)
            put("hasToken", authorized)
            put("loggedIn", authorized)
            put("sessionReady", authorized && !sid.isNullOrEmpty())
            put("sessionId", sid ?: "")
        }.toString()
    }

    /** 返回可用技能列表（JSON 数组），前端用于 /skill <TAB> 补全或展示。 */
    @JavascriptInterface
    fun listSkills(): String {
        // 每次查询前刷新，确保新导入的技能立即可见
        skillRepo.discover()
        val arr = org.json.JSONArray()
        for (s in skillRepo.allSkills()) {
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("description", s.description)
                put("version", s.version)
                put("files", org.json.JSONArray(s.files))
            })
        }
        return arr.toString()
    }

    /**
     * 重置系统提示词注入标记。
     * 用户输入 /system 时调用，效果是让下一条用户消息重新注入 system_prompt + 工具清单。
     */
    @JavascriptInterface
    fun resendSystemPrompt() {
        state(currentSessionId)?.sessionHistoryLoaded = null
        LogStore.i("SYSTEM", "已重置 sessionHistoryLoaded，下一条消息将重新注入系统提示词")
    }

    /** 异步执行一个工具调用，完成后通过 deferred 通知调用方。超时默认 120 秒，超时后返回错误。 */
    private fun executeToolAsync(sid: String?, callId: String, name: String, arguments: String, deferred: CompletableDeferred<String>) {
        val st = state(sid)
        val job = toolScope.launch {
            // 立即登记到本会话的工具协程集合：run_code 等工具在 dispatch 期间就在跑，
            // 用户停止时 activeToolJobs 必须已含本协程，否则 PtcCancellation 不会被请求、工具停不下来。
            // 用协程自身 Job（coroutineContext[Job]）而非常量 job 变量——后者在 lambda 内尚为未赋值状态。
            st?.activeToolJobs?.add(coroutineContext[Job]!!)
            // ── 重复失败防护：同一锚点连续失败 >=2 次则阻止重试，引导重建锚点 ──
            val preArgs = runCatching { parseToolArguments(arguments) }.getOrNull()
            val blockMsg = if (st != null && preArgs != null) st.repeatGuard.shouldBlock(name, preArgs) else null
            if (blockMsg != null) {
                recordOperation(sid, "$name → ✗ 已阻止（重复失败）")
                deferred.complete(buildToolResult(callId, errorResult(blockMsg)))
                return@launch
            }

            // ── 工具执行带超时 ──
            // P0-C 修复：ChatBridge 曾只识别 `timeout_sec`（http_request 沿用），
            // 导致 run_code 声明的 `timeout_seconds`（上限 3600）永远落到 120s 兜底，
            // 用户以为能开 1 小时实际 2 分钟就切。现在两种参数名都认：
            //   `timeout_seconds`：run_code 使用（默认 300、上限 3600）
            //   `timeout_sec`：其余工具/http_request 使用
            // 未显式提供时的兜底仍是 120s（原行为）。
            val timeoutMs = runCatching {
                val raw = (preArgs?.get("timeout_seconds")
                    ?: preArgs?.get("timeout_sec")) as? kotlinx.serialization.json.JsonPrimitive
                raw?.content?.toLongOrNull()?.times(1000)
            }.getOrNull()
                // run_code 的工具级默认是 300s（timeout_seconds 上限 3600），外层兜底必须一致，
                // 否则模型不传参时会被 120s 提前切断（声明与行为不符）。
                ?: if (name == com.mcp.ptc.RUN_CODE_TOOL) 300_000L else 120_000L

            // 工具调用开始：立即通知前端创建「待返回」气泡（灰色），不等执行完毕；
            // 结果到达后由 tool_call 事件按 callId 一一对应填充并着色（绿=成功/红=失败）。
            // __parse_error__ 是**内部错误通道**（解析器合成的假调用，callId 为空），不是真工具调用：
            // 发 tool_call_start 会在前端建一个永远填不上的「待返回」气泡，名字还叫 __parse_error__。
            // 直聊路径可见。错误本身仍通过下方 deferred.complete(toolResult) 回给模型去改正，
            // 与 UI 事件无关 —— 所以这里只掐呈现，不掐通道。
            if (name != "__parse_error__") {
                withContext(Dispatchers.Main) {
                    emitStream(sid, "tool_call_start", JSONObject().apply {
                        put("id", callId)
                        put("name", name)
                        put("arguments", arguments)
                    })
                }
            }

            val rawResult = try {
                // 特殊解析错误通道：__parse_error__ 的 arguments 已是正确转义的错误 JSON，
                // 直接作为工具结果回传（模型可见定界块提示），不走真实工具 dispatch。
                if (name == "__parse_error__") {
                    parseToolArguments(arguments).let { args ->
                        (args["error"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: errorResult("工具参数解析失败")
                    }
                } else {
                    // 用用户等待感知的超时：ask_user 等用户回答的时长不计入预算，
                    // 否则用户在弹窗上多花点时间，run_code 就到点返回「工具执行超时」，
                    // 刚提交的回答被丢弃。语义与 withTimeoutOrNull 一致（超时返回 null）。
                    com.mcp.ptc.withUserWaitAwareTimeout(timeoutMs) {
                        val args = preArgs ?: parseToolArguments(arguments)
                        // PTC 折叠：dispatch 层兑现「只能直调 run_code」，模型无视提示词直调其它工具会在此被拒
                        if (!modelDirectToolAllowed(name, ptcActive(sid))) {
                            errorResult(ptcDirectCallRejection(name, protocolStyleXmlFor(sid)))
                        } else {
                            // 工具执行期间绑定会话（**保存/恢复**，嵌套安全）：
                            //  - PtcEventBus 的嵌套事件据此落进正确会话的审计日志；
                            //  - 子 Agent 委派（delegate_to_agent / agent_workflow）据此拿到主会话 id 做 fork。
                            // 必须保存/恢复而非 bind(null)：PTC 模式下 delegate 是 run_code 的**内部**子调用，
                            // 内层 unbind 置 null 会让外层 run_code 后续执行丢掉绑定。
                            val prevAudit = com.mcp.ptc.PtcAudit.current()
                            try {
                                com.mcp.ptc.PtcAudit.bind(sid)
                                // 仅 run_code 清取消标志：非 run_code 工具若也清，会在用户点「停止」后
                                // 被 run_code 内部的子调用误清，停止随之失效。
                                if (name == com.mcp.ptc.RUN_CODE_TOOL) com.mcp.ptc.PtcCancellation.clear(sid)
                                toolbox.dispatch(name, args)
                            } finally {
                                com.mcp.ptc.PtcAudit.bind(prevAudit)
                            }
                        }
                        // 带上工具名：原先只说「工具执行超时（超过 300 秒）」，模型看不出
                        // 超时的是整段 run_code 还是内部某次子调用，也就无从判断该缩短程序还是换命令。
                    } ?: errorResult("工具执行超时（${name} 超过 ${timeoutMs / 1000} 秒）")
                }
            } catch (e: Exception) {
                // 解析失败：返回正确转义的错误 JSON（含自愈提示），绝不让工具调用把会话打挂
                val msg = (e as? ToolArgsParseException)?.message
                    ?: "工具参数解析失败：${e.message ?: e::class.simpleName}"
                errorResult(msg)
            }

            // ── 失败防护记录 / 成功清除（写工具成功按路径清除非锚点类失败历史）──
            val executedArgs = runCatching { parseToolArguments(arguments) }.getOrNull()
            if (executedArgs != null) {
                val execPath = (executedArgs["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val guard = st?.repeatGuard
                if (guard != null && rawResult.contains("\"error\"")) {
                    guard.recordFailure(name, executedArgs, rawResult, execPath)
                } else if (guard != null && name != "read_file" && name != "get_block" && name != "search_files" && name != "search_and_read") {
                    guard.clearAfterMutation(name, executedArgs, execPath)
                }
            }

            // 状态注入：记录操作摘要（提取关键参数，截断长内容）
            val argsBrief = try {
                val args = parseToolArguments(arguments)
                // 提取关键字段：path / file / pattern / content_pattern / name_pattern
                val path = args["path"]?.toString()?.trim('"') ?: args["file"]?.toString()?.trim('"') ?: args["directory"]?.toString()?.trim('"') ?: ""
                val pattern = args["content_pattern"]?.toString()?.trim('"') ?: args["name_pattern"]?.toString()?.trim('"') ?: ""
                val extra = if (path.isNotEmpty() && pattern.isNotEmpty()) " ($path, 关键词: ${pattern.take(40)})"
                    else if (path.isNotEmpty()) " ($path)"
                    else if (pattern.isNotEmpty()) " (关键词: ${pattern.take(40)})"
                    else ""
                "$name$extra"
            } catch (e: Exception) { name }
            // 从结果中提取关键信息
            val resultBrief = if (rawResult.contains("\"ok\":true") || rawResult.contains("\"ok\": true")) "✓ 成功"
                else if (rawResult.contains("\"error\"")) "✗ 失败"
                else if (rawResult.contains("\"count\"")) "✓ 返回结果"
                else "✓ 完成"
            recordOperation(sid, "$argsBrief → $resultBrief")

            // ── 推进模式：工具结果追加待办摘要 ──
            val pushMode = context.getSharedPreferences(MainActivity.PREF_TAB_MODE, Context.MODE_PRIVATE)
                .getBoolean("push_mode", false)

            // 排除：Agent 工具 + 待办任务工具 + 只读工具（避免干扰纯读取场景）
            val excludedTools = setOf(
                // Agent 工具
                "delegate_to_agent", "list_agents", "agent_status",
                "agent_cancel", "agent_complete", "agent_progress",
                // 待办任务工具（自身已经返回待办信息，追加会重复）
                "list_todos", "add_todo", "update_todo", "import_todos", "delete_todo",
                // 只读工具（不产生副作用，无需追加待办）
                "read_file", "get_block", "search_files", "glob", "grep",
                "search_and_read", "list_files", "saf_list_roots"
            )

            val finalResult = if (pushMode && name !in excludedTools) {
                val todoCtx = withContext(Dispatchers.IO) {
                    TodoStore.toMarkdown(context, excludeDone = true)
                }
                if (todoCtx.isNotBlank() && todoCtx != "当前没有任何待办任务。") {
                    val stats = TodoStore.stats(context)
                    val openCount = (stats[TodoStatus.PENDING] ?: 0) + (stats[TodoStatus.IN_PROGRESS] ?: 0)
                    buildString {
                        append(rawResult)
                        append("\n\n<current-todos>\n")
                        append(todoCtx)
                        append("\n</current-todos>\n")
                        if (openCount > 0) {
                            append("提示：还有 $openCount 个待办步骤未完成。")
                        }
                    }
                } else {
                    rawResult
                }
            } else {
                rawResult
            }

            // ── ask_user 特殊处理：拦截结果，弹出对话框等待用户输入 ──
            // 透传工具产出的完整提问结构（单问题字段，或 questions 数组、multi_select 等），
            // 与 AskUserTool / 前端 renderAskUser 共用同一份合约；中间层不再逐字段抽取，
            // 避免 questions 数组、multi_select 布尔被正则抽取逻辑丢弃导致三方耦合错位。
            if (rawResult.trimStart().startsWith("""{"action":"ask_user"""")) {
                val payload = runCatching { JSONObject(rawResult) }.getOrElse { JSONObject() }
                st?.pendingUserInput?.set(callId, deferred)
                withContext(Dispatchers.Main) {
                    emitStream(sid, "ask_user", JSONObject().apply {
                        put("id", callId)
                        if (payload.has("questions")) {
                            put("questions", payload.getJSONArray("questions"))
                        } else {
                            put("question", payload.optString("question", "请回答："))
                            put("type", payload.optString("type", "text"))
                            if (payload.has("options")) put("options", payload.optString("options"))
                        }
                        if (payload.has("multi_select")) put("multi_select", payload.optBoolean("multi_select"))
                    })
                }
                // deferred 会在 submitUserInput() 中被 complete
                return@launch
            }

            val toolResult = buildToolResult(callId, finalResult)

            // 用 NonCancellable 包裹「落盘 + 回传前端 + complete deferred」：
            // 用户停止时 clearPendingTools 会取消本协程，但 run_code 经 PtcCancellation 中断后是「正常返回 err」
            // 而非崩溃，结果仍需回传前端，否则卡片卡在「执行中…」。NonCancellable 屏蔽取消，确保这段一定跑完。
            withContext(NonCancellable) {
            // IO 线程：更新历史 + 追加落盘（不阻塞主线程）
            //
            // __parse_error__ 例外：它不是工具调用，只是解析器合成的**内部错误通道**。
            // 落盘会让翻历史时又从 onHistory 的 m.toolCall 分支渲染出一张名为 __parse_error__
            // 的工具卡片（现场气泡已在上方掐掉，卡片会从历史里"复活"）。跳过落盘是安全的：
            // 模型靠 pendingToolJobs 拿结果续聊（见 handleDone 的 results），不读这张卡片；
            // 且 DEEPSEEK_REVERSE 路径本就不调用 emitLocalMessageId，卡片没有树记账作用。
            if (sid != null && st != null && name != "__parse_error__") {
                // 工具结果以「卡片」形态入库：content 留空，避免把 JSON 透出到气泡；
                // 渲染时由 onHistory 的 m.toolCall 分支还原成工具卡片。作为事件追加落盘。
                val toolMsg = addToHistory(
                    st,
                    ChatMessage(
                        isUser = false,
                        content = "",
                        toolCall = ToolCallData(id = callId, name = name, arguments = arguments, result = toolResult)
                    )
                )
                saveMutex.withLock { LocalStore.appendMessage(context, LocalStore.backendTag(auth.getBackend()), sid, toolMsg) }
                // 工具卡片在 OpenAI 后端也占一个树节点：回传 id 让前端把本轮气泡推进到它，
                // 后续正文到达时 assignMessageIds 才会走「只推进不重挂父」的分支。
                if (true) {
                    emitLocalMessageId(sid, toolMsg.id)
                }
            }

            // 从 emit 切到主线程（轻量操作，evaluateJavascript 必须在主线程）
            // 同上：__parse_error__ 不呈现为工具卡片（它的 callId 为空，前端也无法与任何调用对上）
            if (name != "__parse_error__") {
                withContext(Dispatchers.Main) {
                    emitStream(sid, "tool_call", JSONObject().apply {
                        put("id", callId)
                        put("name", name)
                        put("arguments", arguments)
                        put("result", toolResult)
                        // 用户主动停止导致 run_code 中断：把结果标红，否则 isToolError 把纯文本判为 ok（绿色），
                        // 与「被中断」语义相反。仅在 isStopping 期间完成的 run_code 标红，正常超时不受影响。
                        if (name == com.mcp.ptc.RUN_CODE_TOOL && st?.isStopping == true) put("error", true)
                    })
                }
            }
            runCatching { deferred.complete(toolResult) }
            }
        }
        job.invokeOnCompletion { st?.activeToolJobs?.remove(job) }
    }

    /**
     * 前端点击发送时调用。App 负责完成 PoW 并流式接收 SSE，
     * 通过 evaluateJavascript("window.onChatEvent(...)") 把事件推回页面。
     */
    @JavascriptInterface
    fun sendMessage(prompt: String, thinking: Boolean, search: Boolean) {
        // 会话 id 在**入口同步捕获一次**，之后整条异步链路只用它。
        // currentSessionId 是可变全局：Web 桌面端每个 HTTP 请求都会改写它，多会话并发时
        // 协程里再读会把 A 的提问/事件写进 B（“多会话链路不协调”的根因之一）。
        sendMessageFor(currentSessionId, prompt, thinking, search)
    }

    /**
     * OpenAI 兼容端点的对话入口：复用本桥的会话链，把「无状态全量 messages」翻译成
     * DeepSeek 有状态的「单条 prompt + parentMessageId」。
     *
     * 为什么必须走这里而不是直接调 LLMClient：
     *  - DeepSeek 逆向是**服务端会话态**协议，续聊靠 parent_message_id 串链；
     *    直接调 LLMClient 会绕过 ensureDeepSeekAnchor 与 st.parentMessageId 的维护，
     *    每轮 parent 都是 null → 服务端开新分支 → 模型看不到历史（「不延续」）。
     *  - 本方法在发送前惰性补齐锚点，发送后由 streamLLM 内部的 MessageId 分支
     *    自动更新 st.parentMessageId，链路与 App 内聊天完全一致。
     *
     * @param sid      归属会话（外部请求无会话概念，由 WebApiServer 解析后传入）
     * @param prompt   要发送的正文（取外部 messages 最后一条 user 的 content）
     * @param thinking 是否开启深度思考
     * @param onEvent  事件回调（在流式协程内被调用，用于把 MessageEvent 转成 OpenAI 帧）
     * @param onFinish 结束回调（成功或失败都调一次）
     */
    internal fun openAiCompatChat(
        sid: String,
        prompt: String,
        thinking: Boolean,
        onEvent: (com.mcp.llm.MessageEvent) -> Unit,
        onFinish: (error: Throwable?) -> Unit,
    ) {
        if (sid.isEmpty()) {
            onFinish(IllegalStateException("未指定会话"))
            return
        }
        scope.launch(Dispatchers.IO) {
            var err: Throwable? = null
            try {
                val st = stateFor(sid)
                ensureOpenAIContextSeeded(sid)

                val backend = auth.getBackend()
                val request = LLMRequest(
                    messages = listOf(com.mcp.llm.ChatMessage(role = "user", content = prompt)),
                    tools = null,
                    config = LLMConfig(
                        sessionId = sid,
                        parentMessageId = st.parentMessageId,
                        thinkingEnabled = thinking,
                        searchEnabled = false,
                    )
                )

                streamLLM(
                    request = request,
                    emitThinking = true,
                    onDone = { /* OpenAI 端点不触发工具续聊，仅回传事件 */ },
                    sid = sid,
                    onEventOverride = onEvent,
                )
            } catch (e: Throwable) {
                err = e
            } finally {
                onFinish(err)
            }
        }
    }

    /**
     * 指定会话发送消息（Web 桌面端按请求携带的 sessionId 调用，不依赖全局 currentSessionId）。
     */
    internal fun sendMessageFor(sid0: String?, prompt: String, thinking: Boolean, search: Boolean) {
        if (sid0.isNullOrEmpty()) {
            emit("error", JSONObject().put("message", "未选择会话"))
            return
        }
        // JS 桥线程只负责启动：整个发送流程（含上下文压缩的 LLM 摘要网络调用）放到 IO 协程执行。
        // 原实现在 JS 桥线程同步 runBlocking 等摘要（可达 30s+），会阻塞 WebView Java Bridge 线程
        // 导致整页卡顿；异步化后 JS 桥立即返回，压缩在网络 IO 线程完成。
        if (state(sid0)?.isBusy == true) {
            // 只在目标会话仍是前台时把错误推给 UI，避免后台会话的报错弹到别的会话页
            if (sid0 == currentSessionId) emit("error", JSONObject().put("message", "上一条消息尚未完成"))
            return
        }
        scope.launch {
            try {
                innerSendMessage(sid0, prompt, thinking, search)
            } catch (e: Exception) {
                LogStore.e("SEND", "sendMessage 异常: ${e.message ?: e::class.simpleName}")
                val st = state(sid0)
                st?.isBusy = false
                st?.isStopping = false
                st?.isRegenerating = false
                st?.streamJob = null
                st?.streamMessageId = null
                runCatching { emit("error", JSONObject().put("message", "发送失败: ${e.message ?: e::class.simpleName}")) }
            }
        }
    }

    /** 从用户消息生成会话标题（OpenAI 本地会话自动命名用）。
     * 取首行、压缩空白、截 24 字符；/skill 命令优先用其后的用户消息。
     */
    private fun suggestTitle(prompt: String): String {
        var t = prompt.trim()
        // /skill 命令：优先用命令后的用户消息，没有则退回技能名
        if (t.startsWith("/skill ")) {
            val rest = t.removePrefix("/skill ").trim()
            val firstSpace = rest.indexOf(' ')
            val skillName = if (firstSpace >= 0) rest.substring(0, firstSpace) else rest
            val userMsg = if (firstSpace >= 0) rest.substring(firstSpace + 1).trim() else ""
            t = userMsg.ifEmpty { "/skill " + skillName }
        }
        val firstLine = t.lineSequence().firstOrNull { it.isNotBlank() } ?: return "新会话"
        return firstLine.trim().replace(Regex("\\s+"), " ").take(24)
    }

    /**
     * 当前后端是否具备发出请求的凭据。
     *
     * DeepSeek 逆向后端依赖服务端会话态，必须有 token；OpenAI 兼容后端无状态、
     * 凭据只是 Base URL / API Key，**不需要也不应该要求 DeepSeek 登录**——否则选了
     * OpenAI 后端的用户即使已经把端点配好，也会被「未登录或未选择会话」挡在门外。
     */
    private fun hasCredentials(): Boolean =
        backendCredentialsSatisfied(auth.getBackend(), !auth.getToken().isNullOrEmpty())

    private suspend fun innerSendMessage(
        sid: String?,
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        imageUrls: List<String> = emptyList(),
        refFileIds: List<String>? = null
    ) {
        if (sid.isNullOrEmpty() || !hasCredentials()) {
            emit("error", JSONObject().put("message", "未登录或未选择会话"))
            return
        }
        val st = stateFor(sid)
        // OpenAI 无状态缓冲缺失历史时（如 Web 桌面端独立 ChatBridge 冷启动）先按磁盘历史补齐
        ensureOpenAIContextSeeded(sid)
        // 记录本轮思考开关，供工具结果续聊（sendToolResultBatch / continueOpenAI）复用
        st.lastThinkingEnabled = thinking
        if (st.isBusy) {
            emit("error", JSONObject().put("message", "上一条消息尚未完成"))
            return
        }
        // 消息不能为空——但「只有图片附件、无用户文字」是合法的多模态场景：
        // 此时 finalPrompt 会被下游渲染成 `[图片: xxx]` / `[语音: xxx]` 等占位或空串，
        // 而真正的视觉/音频内容通过 imageUrls / refFileIds 通道送达模型。
        if (prompt.isBlank() && imageUrls.isEmpty() && refFileIds.isNullOrEmpty()) {
            emit("error", JSONObject().put("message", "消息不能为空"))
            return
        }

        // ── /skill <name> 命令 ───────────────────────────────────
        // 格式: /skill <name> [用户消息]
        val skillInjected: String?
        var finalPromptBase = prompt
        if (prompt.startsWith("/skill ")) {
            val rest = prompt.substring("/skill ".length).trim()
            val firstSpace = rest.indexOf(' ')
            val skillName = if (firstSpace >= 0) rest.substring(0, firstSpace) else rest
            val userMsg = if (firstSpace >= 0) rest.substring(firstSpace + 1).trim() else ""

            if (skillName.isBlank()) {
                emit("error", JSONObject().put("message",
                    "用法: /skill <名称> [消息]。可用: ${skillRepo.skillNames().joinToString(", ")}"))
                st.isBusy = false
                return
            }
            val skill = skillRepo.getSkill(skillName)
            // 与 system prompt 注入同一开关口径：禁用的技能不允许 /skill 直注绕过
            if (skill != null && !com.mcp.core.skill.SkillPreferences
                    .isEnabled(context, skillName)) {
                LogStore.w("SKILL", "技能已禁用，拒绝注入: $skillName")
                emit("error", JSONObject().put("message",
                    "技能「$skillName」已在设置中禁用。可在 技能管理 页开启后再用。"))
                st.isBusy = false
                return
            }
            if (skill != null) {
                LogStore.i("SKILL", "注入技能: $skillName (${skill.content.length} chars)")
                finalPromptBase = skill.content + if (userMsg.isNotBlank()) "\n\n---\n\n$userMsg" else ""
                skillInjected = skillName
            } else {
                LogStore.w("SKILL", "技能不存在: $skillName, 可用: ${skillRepo.skillNames()}")
                emit("error", JSONObject().put("message",
                    "未知技能: $skillName。可用: ${skillRepo.skillNames().joinToString(", ")}"))
                st.isBusy = false
                return
            }
        } else if (prompt.trim() == "/skill") {
            emit("error", JSONObject().put("message",
                "用法: /skill <名称> [消息]。可用: ${skillRepo.skillNames().joinToString(", ")}"))
            st.isBusy = false
            return
        } else {
            skillInjected = null
        }

        st.isBusy = true
        // 先把用户这条消息计入本地累积列表，并作为事件即时追加落盘（append-only）。
        // 流式进行中若切走再切回，本地历史需已包含本轮提问，否则 chat.html 的 onHistory
        // 会因末条是 AI、reuseLastAi=true 而把正在生成的新回复误覆盖到上一条助手气泡上。
        // OpenAI 兼容后端由 addToHistory 分配本地 id（父 = 上一条消息），
        // 前端的「编辑重发」正是拿这个 id 当锚点。
        val userMsg = addToHistory(st, ChatMessage(isUser = true, content = prompt))
        scope.launch {
            saveMutex.withLock {
                LocalStore.appendMessage(context, LocalStore.backendTag(auth.getBackend()), sid, userMsg)
            }
        }

        // 会话列表元数据（仅 OpenAI 本地会话）：刷新活跃时间 + 首条消息自动命名。
        // 异步 IO 落盘，不阻塞发送路径；saveMutex 防止与消息落盘并发写坏 JSON。
        if (true) {
            val metaSid = sid
            val metaPrompt = prompt
            scope.launch {
                saveMutex.withLock {
                    // 会话元数据必须写进**当前后端**的列表：写死 oa 时，Web 自动化会话
                    // 既不会出现在会话列表里，也拿不到标题与更新时间。
                    val tag = LocalStore.backendTag(auth.getBackend())
                    val list = LocalStore.loadSessions(context, tag)
                    val idx = list.indexOfFirst { it.id == metaSid }
                    if (idx >= 0) {
                        val cur = list[idx]
                        val title = if (cur.title.isBlank()) suggestTitle(metaPrompt) else cur.title
                        val now = System.currentTimeMillis() / 1000.0
                        val updated = list.toMutableList().apply { set(idx, cur.copy(title = title, updatedAt = now)) }
                        LocalStore.saveSessions(context, tag, updated)
                    }
                }
            }
        }

        // 注入策略：仅首次消息注入 system_prompt。
        // - DeepSeek 逆向协议不支持原生 function calling，由组合器统一生成行式工具协议提示词（含 <<< >>> 定界块）。
        // - OpenAI 兼容协议走原生 function calling：工具经 tools 参数传递、系统提示词由 composeOpenAiSystemContent
        //   单独构造（不含行式定界块），故首条用户消息不注入 DeepSeek 协议指南，避免定界块越界污染 OpenAI 上下文。
        val isFirstMessage = st.sessionHistoryLoaded != sid
        // 工具拓扑脏标记：远程 MCP 服务增删 / 工具集变化后，即使不是首条消息也要重注入，
        // 否则模型看到的工具清单停留在变更前的旧版本（DeepSeek 无原生 tools 参数，
        // 清单只能靠提示词下发）。
        val topologyDirty = st.toolTopologyDirty
        val hasTools = toolbox.all().isNotEmpty()
        val isOpenAI = true
        val shouldInject = (isFirstMessage || topologyDirty) && hasTools && !isOpenAI
        val finalPrompt = if (shouldInject) {
            val injected = promptComposer.composeDeepSeek(
                toolbox, finalPromptBase, workspacePath(sid),
                ptcRequestContext(toolbox.all(), ptcActive(sid), style = currentPtcStyle(sid))
            )
            LogStore.i("TOOL", "ChatBridge 注入 DeepSeek 行式协议 system/tools 到 prompt, 会话=$sid" +
                "（首条=$isFirstMessage, 拓扑变更=$topologyDirty）")
            injected
        } else {
            if (hasTools && !isOpenAI) {
                LogStore.d("TOOL", "ChatBridge 非首次消息，不注入 system/tools, prompt=${prompt.take(100)}, 会话=$sid")
            }
            finalPromptBase
        }
        // 注入后清除拓扑脏标记（无论走哪条分支都清，避免标记永久悬空）。
        st.toolTopologyDirty = false
        // 首次注入后立即标记已加载，防止本会话第二条消息重复注入
        if (isFirstMessage) {
            st.sessionHistoryLoaded = sid
        }

        // ── 推进模式：每次消息都注入待办任务上下文 ──────────────────────────
        val prefs = context.getSharedPreferences(MainActivity.PREF_TAB_MODE, Context.MODE_PRIVATE)
        val pushMode = prefs.getBoolean("push_mode", false)
        val finalPromptWithTodo = if (pushMode) {
            val (todoCtx, openCount) = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                val markdown = TodoStore.toMarkdown(context, excludeDone = true)
                val stats = TodoStore.stats(context)
                markdown to ((stats[com.mcp.TodoStatus.PENDING] ?: 0) + (stats[com.mcp.TodoStatus.IN_PROGRESS] ?: 0))
            }
            if (todoCtx.isNotBlank() && todoCtx != "当前没有任何待办任务。") {
                LogStore.i("PUSH", "注入 <current-todos> (${todoCtx.length} chars, 未完成 $openCount)")
                buildString {
                    append(finalPrompt)
                    // 固定 XML 块注入当前待办（仿官方 user-turn 注入；显示层可剥离该块）
                    append("\n\n<current-todos>\n")
                    append(todoCtx)
                    append("\n</current-todos>\n")
                    if (openCount > 0) {
                        append("提示：还有 $openCount 个待办步骤未完成。每完成一步立即用 update_todo 标记 done；全部完成后再输出最终回复。\n")
                    }
                }
            } else finalPrompt
        } else finalPrompt

        // 按当前后端构造请求：DeepSeek 逆向协议只发最新 prompt（服务端维护上下文），
        // OpenAI 兼容协议发送完整消息列表（无状态，system + 历史 + 当前用户消息）。
        val backend = auth.getBackend()
        var requestRefresher: (() -> LLMRequest)? = null
        val request = run {
            val (req, refresh) = openAIRequest(sid, finalPromptBase, thinking, imageUrls)
            requestRefresher = refresh
            req
        }
        // DeepSeek 逆向协议：消息 id 连续递增，每一轮服务端会新建两条消息——用户消息(+1) 与
        // 助手消息(+1)，故"当前正在生成的助手消息"(即要停止的 message_id) = 上一条消息 id(parentMessageId) + 2。
        // 初始无上一条时 parentMessageId 为 null，视作 0 → message_id = 2（首条用户=1、首条助手=2）。
        // 该值在发送时即可由 parentMessageId 确定，无需等待 SSE 的 response_message_id；
        // SSE 若回传不同值（修正）会覆盖它。
        streamLLM(request, emitThinking = true, onDone = { ev -> handleDone(ev, sid) }, requestRefresher = requestRefresher, sid = sid)
    }

    /**
     * 前端点击「重新生成」时调用。
     *
     * 与 [sendMessage] 的本质区别：**不重发 prompt**。DeepSeek 服务端维护着一棵消息树，
     * 重新生成走专用端点 `POST /chat/regenerate`，只需给出被重生成的那条助手消息 id
     * （child_message_id），服务端便在其父节点下追加一个兄弟分支。
     *
     * 旧实现用 completion 重发一遍用户 prompt，会在服务端额外插入一条用户消息，
     * 导致「客户端以为是同级分支、服务端却是新一轮对话」的错位——这正是切换分支时
     * 总是显示最后一条回复的根因。
     *
     * @param childMessageId 被重新生成的助手消息 id（前端消息树里该气泡的 id）
     */
    @JavascriptInterface
    fun regenerate(childMessageId: String, thinking: Boolean, search: Boolean) {
        regenerateFor(currentSessionId, childMessageId, thinking, search)
    }

    /** 指定会话重新生成（Web 桌面端按请求 sessionId 调用，不依赖全局 currentSessionId）。 */
    internal fun regenerateFor(sid0: String?, childMessageId: String, thinking: Boolean, search: Boolean) {
        if (sid0.isNullOrEmpty()) return
        // 前端给的是**本地 id**：本地历史按它定位，服务端的 child_message_id 用换算后的服务端 id
        val rawId = toRawId(sid0, childMessageId)
        // JS 桥线程只负责启动（理由同 sendMessage）：请求构造含上下文压缩网络调用，放到 IO 协程执行
        scope.launch { innerRegenerate(sid0, childMessageId, rawId, thinking, search) }
    }

    private suspend fun innerRegenerate(
        sid: String?,
        childMessageId: String,
        childServerMessageId: String?,
        thinking: Boolean,
        search: Boolean
    ) {
        if (sid.isNullOrEmpty() || !hasCredentials()) {
            emit("error", JSONObject().put("message", "未登录或未选择会话"))
            return
        }
        val st = stateFor(sid)
        // OpenAI：重新生成/编辑要在本地历史里找锚点，先确保该实例已载入完整历史（Web 桌面端冷启动场景）
        ensureOpenAIContextSeeded(sid)
        // 记录思考开关，供工具结果续聊复用
        st.lastThinkingEnabled = thinking
        if (st.isBusy) {
            emit("error", JSONObject().put("message", "上一条消息尚未完成"))
            return
        }
        val backend = auth.getBackend()
        st.isBusy = true
        st.isRegenerating = true
        LogStore.i("SSE", "重新生成: child_message_id=$childMessageId 会话=$sid 后端=$backend")

        var requestRefresher: (() -> LLMRequest)? = null
        val request = if (true) {
            // OpenAI 无服务端状态：以被重新生成的那条助手消息 id 为锚点，把本地线性历史
            // 截断到它之前（等价服务端「在其父节点下开兄弟分支」），再用截断后的历史
            // 整体重建无状态缓冲后原样发一次。
            //
            // 旧实现按 role 从缓冲尾部弹到 user 为止，有两个硬伤：
            //  1. 工具续聊轮的尾部是 tool 消息，弹到 user 时会在中间留下
            //     assistant(tool_calls) 而丢掉它的 tool 结果 —— 未闭合的工具调用
            //     直接触发 OpenAI 400；
            //  2. 完全不碰 st.history，新旧两条回复都留在本地日志里，
            //     切走再切回就会看到两条助手消息叠在同一提问下。
            // 以 id 为锚点还可精确定位「重新生成中间某一条」的场景
            // （按 role 弹永远只能重生成最后一条）。
            if (!truncateHistoryAt(st, childMessageId)) {
                LogStore.w("SSE", "重新生成找不到锚点 id=$childMessageId 会话=$sid")
                emit("error", JSONObject().put("message", "无法重新生成：找不到该回复（可能已被上下文压缩合并）"))
                st.isBusy = false
                st.isRegenerating = false
                return
            }
            // 通知前端同步丢弃锚点及其之后的旧气泡，避免 DOM 残留已被截断的分支
            emitTruncateFrom(sid, childMessageId)
            seedOpenAIMessages(sid)
            val tools = modelTools()
            compactOpenAIIfNeeded(sid, st, extraTokens = estimateToolsTokens(tools))
            val cfg = LLMConfig(
                model = auth.getOpenAIModel(),
                temperature = auth.getOpenAITemperature(),
                maxTokens = auth.getOpenAIMaxTokens(),
                stream = true,
                thinkingEnabled = thinking
            )
            if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
                // 站点会话自身有上下文：重发这条提问原文即可（首轮注入语义见 webAutoSiteRequest）
                val req = webAutoSiteRequest(cfg, sid, st.openAIMessages)
                requestRefresher = { req }
                req
            } else {
                requestRefresher = { LLMRequest(st.openAIMessages.toList(), tools, cfg) }
                LLMRequest(st.openAIMessages.toList(), tools, cfg)
            }
        } else {
            LLMRequest(
                emptyList(),
                null,
                LLMConfig(
                    sessionId = st.serverSessionId ?: sid,
                    parentMessageId = st.parentMessageId,
                    thinkingEnabled = thinking,
                    searchEnabled = search
                )
            )
        }
        // 重新生成的新消息 id 由服务端分配（无法像 sendMessage 那样按 +2 推算），
        // 置空即可：SSE 结束时的 message_id 事件会补上；期间点「停止」仍能靠取消协程断流。
        st.streamMessageId = null
        streamLLM(
            request,
            emitThinking = true,
            onDone = { ev -> handleDone(ev, sid) },
            // 服务端按 child_message_id 开兄弟分支：传服务端 id（OpenAI 无服务端状态，换算恒等）
            channel = StreamChannel.Regenerate(childServerMessageId ?: childMessageId),
            requestRefresher = requestRefresher,
            sid = sid
        )
    }

    /**
     * 前端提交「编辑重发」时调用。
     *
     * 走 `POST /chat/edit_message`：以被编辑的用户消息 id 为锚点，在其父节点下原位
     * 分叉出「新提问 + 新回复」，原提问及其后续对话保留为另一分支。
     *
     * 旧实现只是把新文案填回输入框当普通消息发出，结果接在整段对话的末尾而非原位，
     * 树形因此错位。注意与 [regenerate] 的分工：编辑作用于**用户**卡片、
     * 重新生成作用于**助手**卡片。
     *
     * @param messageId 被编辑的用户消息 id
     * @param prompt    编辑后的新文案
     */
    @JavascriptInterface
    fun editMessage(messageId: String, prompt: String, thinking: Boolean, search: Boolean) {
        editMessageFor(currentSessionId, messageId, prompt, thinking, search)
    }

    /** 指定会话编辑重发（Web 桌面端按请求 sessionId 调用，不依赖全局 currentSessionId）。 */
    internal fun editMessageFor(
        sid0: String?,
        messageId: String,
        prompt: String,
        thinking: Boolean,
        search: Boolean
    ) {
        if (sid0.isNullOrEmpty()) return
        // 前端给的是**本地 id**：本地历史按它定位；服务端的 edit_message 用换算后的服务端 id
        val rawId = toRawId(sid0, messageId)
        // JS 桥线程只负责启动（理由同 sendMessage）：请求构造含上下文压缩网络调用，放到 IO 协程执行
        scope.launch { innerEditMessage(sid0, messageId, rawId, prompt, thinking, search) }
    }

    private suspend fun innerEditMessage(
        sid: String?,
        messageId: String,
        serverMessageId: String?,
        prompt: String,
        thinking: Boolean,
        search: Boolean
    ) {
        if (sid.isNullOrEmpty() || !hasCredentials()) {
            emit("error", JSONObject().put("message", "未登录或未选择会话"))
            return
        }
        val st = stateFor(sid)
        // OpenAI：重新生成/编辑要在本地历史里找锚点，先确保该实例已载入完整历史（Web 桌面端冷启动场景）
        ensureOpenAIContextSeeded(sid)
        // 记录思考开关，供工具结果续聊复用
        st.lastThinkingEnabled = thinking
        if (st.isBusy) {
            emit("error", JSONObject().put("message", "上一条消息尚未完成"))
            return
        }
        if (prompt.isBlank()) {
            emit("error", JSONObject().put("message", "消息不能为空"))
            return
        }
        val backend = auth.getBackend()
        st.isBusy = true
        LogStore.i("SSE", "编辑重发: message_id=$messageId 会话=$sid 后端=$backend")

        // 编辑重发同样只在本地线性历史里留「改后提问 + 新回复」这一版：
        // 先摘掉被编辑消息之后的尾巴，再把新文案作为用户消息追加。
        if (true) {
            // OpenAI 无服务端状态：以被编辑的**用户**消息 id 为锚点，截断到它之前，
            // 再把新文案作为一条新 user 消息追加。锚点定位而非「找最后一条 user」，
            // 才能正确支持编辑中间某一条提问（旧实现只会动最后一条）。
            if (!truncateHistoryAt(st, messageId)) {
                LogStore.w("SSE", "编辑重发找不到锚点 id=$messageId 会话=$sid")
                emit("error", JSONObject().put("message", "无法编辑：找不到该提问（可能已被上下文压缩合并）"))
                st.isBusy = false
                return
            }
            // 通知前端同步丢弃被编辑提问及其之后的旧气泡，避免 DOM 残留已被截断的分支
            emitTruncateFrom(sid, messageId)
        } else {
            synchronized(historyLock) {
                val lastUser = st.history.indexOfLast { it.isUser }
                if (lastUser >= 0) {
                    while (st.history.size > lastUser) st.history.removeAt(st.history.size - 1)
                }
                st.history.add(ChatMessage(isUser = true, content = prompt))
            }
        }
        // 追加改后的提问（OpenAI 后端由 addToHistory 分配本地 id，父 = 被编辑提问的父）
        val editedUserMsg = addToHistory(st, ChatMessage(isUser = true, content = prompt))
        // 编辑重发改写了历史尾部（截断 + 新提问），即时全量重建日志，
        // 避免切走再切回时本地日志仍残留被编辑消息之后的旧尾巴。
        val editSnap = synchronized(historyLock) { st.history.toList() }
        scope.launch {
            saveMutex.withLock { LocalStore.rebuildMessages(context, LocalStore.backendTag(auth.getBackend()), sid, editSnap) }
        }
        // 注意：这里**不**为新提问单独回传 message_id。前端 assignMessageIds() 的语义是
        // 「收到助手 id，再反推同一轮的用户 id（uId = aId - 1）」，提前发用户 id 会让它
        // 把气泡编号算错一位。改后提问的 id 由本轮助手回复的 message_id 一并带上去。

        var requestRefresher: (() -> LLMRequest)? = null
        val request = if (true) {
            // 用截断后的历史整体重建无状态缓冲：不再按 role 从尾部弹，
            // 避免工具续聊轮留下未闭合的 assistant(tool_calls)（OpenAI 400）。
            // 新提问最后追加，保证缓冲与本地历史严格同构。
            seedOpenAIMessages(sid)
            val tools = modelTools()
            compactOpenAIIfNeeded(sid, st, extraTokens = estimateToolsTokens(tools))
            val cfg = LLMConfig(
                model = auth.getOpenAIModel(),
                temperature = auth.getOpenAITemperature(),
                maxTokens = auth.getOpenAIMaxTokens(),
                stream = true,
                thinkingEnabled = thinking
            )
            if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
                // 站点会话自身有上下文：重发这条提问原文即可（首轮注入语义见 webAutoSiteRequest）
                val req = webAutoSiteRequest(cfg, sid, st.openAIMessages)
                requestRefresher = { req }
                req
            } else {
                requestRefresher = { LLMRequest(st.openAIMessages.toList(), tools, cfg) }
                LLMRequest(st.openAIMessages.toList(), tools, cfg)
            }
        } else {
            LLMRequest(
                listOf(com.mcp.llm.ChatMessage(role = "user", content = prompt)),
                null,
                LLMConfig(
                    sessionId = st.serverSessionId ?: sid,
                    parentMessageId = st.parentMessageId,
                    thinkingEnabled = thinking,
                    searchEnabled = search
                )
            )
        }
        // 新消息 id 由服务端分配，等 SSE 的 message_id 事件回填
        st.streamMessageId = null
        streamLLM(
            request,
            emitThinking = true,
            onDone = { ev -> handleDone(ev, sid) },
            // 服务端按被编辑消息 id 原位分叉：传服务端 id（OpenAI 无服务端状态，换算恒等）
            channel = StreamChannel.Edit(serverMessageId ?: messageId),
            requestRefresher = requestRefresher,
            sid = sid
        )
    }

    /**
     * 停止正在进行的流式生成（前端「停止」按钮调用）。
     * 转调 [stopStream]，作用于当前前台会话。
     */
    @JavascriptInterface
    fun stopGeneration() {
        stopGenerationFor(currentSessionId)
    }

    /** 指定会话停止生成（Web 桌面端按请求 sessionId 调用；后台会话也能停）。 */
    internal fun stopGenerationFor(sid: String?) {
        if (sid == null) {
            LogStore.w("STOP", "stopGeneration 无当前会话，忽略")
            return
        }
        stopStream(sid)
    }

    /**
     * 停止指定会话的流式生成（跨会话后台流停止入口）。
     * 1) 先快照已送达前缀到 SessionState，再取消该会话的 SSE 收集协程（断开 HTTP 流）；
     *    协程 catch 中检测到 [SessionState.isStopping] 会调 [finalizeStoppedPrefix] 定稿前缀并发 "stopped" 事件。
     * 2) best-effort 通知服务端停止（POST /chat/stop_stream，message_id 取当前正在生成的消息 id）；
     *    DeepSeek 逆向协议支持，OpenAI 兼容协议为空实现。
     */
    /** Web 自动化远程停止的等待上限：等采集循环点站点停止按钮并自然收尾的最长时间。 */
    private val REMOTE_STOP_WAIT_MS = 15_000L

    /**
     * 清空会话的「待执行工具」台账，并取消其 deferred。
     *
     * 用户点停止时必须调用。否则下一轮 handleDone 会看到残留的 pendingToolJobs，
     * await 陈旧 deferred 并用旧工具结果续聊——表现为「停止后工具结果回不来、
     * 会话却继续、还要等上一个工具排队」。
     *
     * 先 cancel deferred（唤醒挂起的 await，让它抛 CancellationException），
     * 再清空映射。顺序反了会让 await 方短暂看到空引用。
     */
    private fun clearPendingTools(st: SessionState) {
        // 收集本轮尚未执行完成的工具（deferred 仍挂起、未 complete），供停止收尾时回填「已停止」结果。
        // 必须在 cancel 之前快照：cancel 会立刻把 deferred 置为 completed(cancelled)，之后无法区分。
        val unfinished = st.pendingToolJobs
            .filterValues { !it.isCompleted }
            .mapNotNull { (key, _) ->
                // key 可能是 internalKey（同轮重复 id 时为 "id#dupN"），而 pendingToolMeta
                // 按原始 id 存；alias 命中时取原始 id，取不到再退回 key 本身。
                val rawId = st.toolCallAlias[key] ?: key
                st.pendingToolMeta[rawId]?.let { rawId to it }
            }
            .toMutableList()
        st.stoppedUnfinishedTools = unfinished
        val jobs = st.pendingToolJobs.values.toList()
        st.pendingToolMeta.clear()
        st.pendingToolReasoning = ""
        st.pendingToolJobs.clear()
        st.rejectedToolResults.clear()
        st.toolCallAlias.clear()
        jobs.forEach { runCatching { it.cancel() } }
        // 取消本会话仍在跑的工具协程：上面只取消了「结果 deferred」，工具本体（run_code / http_request /
        // 等）仍跑在独立协程里。不显式取消，用户停止后工具会继续执行到结束。run_code 还会被
        // PtcCancellation 在检查点中断，root 类由 RunRootProcessRegistry 强杀，这里兜底其余协程。
        st.activeToolJobs.forEach { runCatching { it.cancel() } }
        st.activeToolJobs.clear()
    }

    /**
     * 停止收尾：给被中断、尚未完成的工具回填一条「已停止（用户中断）」结果，让前端气泡里
     * 的工具卡片从「执行中…」变为有明确终止态。必须在发 `stopped` 事件之前调用——
     * 前端收到 stopped 后 currentAi 会被清空，再发 tool_call 回填就找不到卡片了。
     * emit 需在主线程（WebView 投递），用 withContext(Dispatchers.Main) 保证。
     */
    private suspend fun emitStoppedToolResults(st: SessionState, sid: String?) {
        val tools = st.stoppedUnfinishedTools
        if (tools.isEmpty()) return
        // 必须 NonCancellable：本方法在「用户停止」路径被调用，此时协程已被 cancel；
        // 裸 withContext(Dispatchers.Main) 会立刻抛 CancellationException，
        // 导致工具卡片永远停在「执行中…」，且后续的 finalizeStoppedPrefix / stopped 事件
        // 一并被跳过——这正是「停止后工具无结果、按钮不反转」的根因。
        withContext(NonCancellable + Dispatchers.Main) {
            for ((id, meta) in tools) {
                runCatching {
                    emitStream(sid, "tool_call", JSONObject().apply {
                        put("id", id)
                        put("name", meta.first)
                        put("arguments", meta.second)
                        put("result", "已停止（用户中断）")
                        put("error", true)
                    })
                }
            }
        }
        LogStore.i("STOP", "已回填 ${tools.size} 个被中断工具的「已停止」结果到前端")
    }

    internal fun stopStream(sid: String) {
        try {
            val st = state(sid)
            if (st == null) {
                LogStore.w("STOP", "stopStream 无会话态，忽略 sid=$sid")
                return
            }
            // 放宽守卫：只要还有在途工具（run_code / run_root 等长任务），即使流已进入收尾
            // 把 isBusy 置 false，也必须允许停止——否则用户点「停止」被静默忽略，长任务打不断。
            if (!st.isBusy && st.activeToolJobs.isEmpty() && st.pendingToolJobs.isEmpty()) {
                LogStore.w("STOP", "stopStream 无活跃流且无在途工具，忽略 sid=$sid")
                return
            }
            // Web 自动化：**先让远程停下，本地才能停**。置位远程停止标记，采集循环（500ms 周期）
            // 发现后点站点停止按钮 → 信号消失 → 做最后一次读取 → 经 Done 自然收尾
            // （handleDone 正常落历史，停止前最后一段内容不丢）。等不到自然收尾再回退取消。
            // 整个过程在 toolScope 里等待，不阻塞 JS 桥线程；期间不置 isStopping——
            // 让流以普通 Done 结束，避免与 finalizeStoppedPrefix 双重落历史。
            if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
                val job = st.streamJob
                // 与普通分支一致：先请求协作式取消，让 run_code 在检查点中断；
                // 并清台账 / 取消工具协程 / 杀 run_root，否则 Web 自动化下「停止」只停流不停工具。
                st.isStopping = true
                if (st.activeToolJobs.isNotEmpty() || st.pendingToolJobs.isNotEmpty()) {
                    com.mcp.ptc.PtcCancellation.request(sid)
                }
                clearPendingTools(st)
                runCatching { com.mcp.ptc.RunRootProcessRegistry.killAllForcibly() }
                toolScope.launch {
                    com.mcp.WebAutomationDriverImpl.requestRemoteStop()
                    val finished = withTimeoutOrNull(REMOTE_STOP_WAIT_MS) {
                        while (job?.isActive == true) delay(200)
                        true
                    }
                    if (finished != true) {
                        LogStore.w("STOP", "远程停止超时，回退取消本地流 sid=$sid")
                        st.stoppedPrefixContent = StreamTaskManager.get(sid)?.lastContent ?: ""
                        st.stoppedPrefixThinking = StreamTaskManager.get(sid)?.lastThinking ?: ""
                        job?.cancel()
                    }
                }
                return
            }
            val token = auth.getToken()
            val msgId = st.streamMessageId
            LogStore.i("STOP", "stopStream: isBusy=true sid=$sid msgId=$msgId job=${st.streamJob != null}")
            st.isStopping = true
            // 用户主动停止：请求 run_code 的协作式取消（Rhino 指令观察者在检查点抛出并中断死循环/长任务）。
            // 原判断 `PtcAudit.current() == sid` 不可靠：PtcAudit 是全局可变 var，停止动作由 JavaBridge 线程发起，
            // 读到的并非 run_code 执行线程绑定的 sid，导致 request() 从未被调用、纯 busy wait 的 run_code 停不下来。
            // 改为「本会话有在途工具」即请求：pendingToolJobs 在 dispatch 前就登记，故即便 activeToolJobs 尚未登记也能命中。
            if (st.activeToolJobs.isNotEmpty() || st.pendingToolJobs.isNotEmpty()) {
                com.mcp.ptc.PtcCancellation.request(sid)
                LogStore.i("STOP", "请求 run_code 协作式取消: sid=$sid activeToolJobs=${st.activeToolJobs.size} pendingToolJobs=${st.pendingToolJobs.size}")
            }

            // 先快照已送达前缀到 SessionState，再取消协程。否则 finalizeStoppedPrefix 在协程
            // 取消后从 StreamTaskManager 取值会遇到竞态（invokeOnCompletion 可能已把任务移除），
            // 导致前缀定稿静默丢失。快照使定稿来源确定，不依赖 Manager 是否仍持有任务。
            val task = StreamTaskManager.get(sid)
            st.stoppedPrefixContent = task?.lastContent ?: ""
            st.stoppedPrefixThinking = task?.lastThinking ?: ""

            // 优先从 streamJob 取消，如果为空则从 Manager 获取
            if (st.streamJob != null) {
                st.streamJob?.cancel()
            } else if (task != null) {
                LogStore.i("STOP", "从 StreamTaskManager 恢复并取消流任务 session=$sid")
                task.job.cancel()
                StreamTaskManager.complete(sid)
            }

            // 关键：清空「待执行工具」台账 + 取消其 deferred。
            // 只取消 streamJob 不够——工具在独立 toolScope 里跑，台账不清的话，
            // 下一轮 handleDone 看到 pendingToolJobs 非空，会 await 陈旧 deferred 并用
            // 旧结果续聊，表现为「停止后工具结果回不来、会话却继续、还要等上一个工具排队」。
            clearPendingTools(st)

            // 强制结束所有活跃的 run_root 宿主侧 su 进程。
            //
            // 上面的 clearPendingTools 只 cancel 了工具结果 deferred，工具本身跑在独立的
            // toolScope.launch 里、那个协程并未被取消；而 PtcCancellation 只在 run_code 内
            // 才置位。两条取消链都到不了 executeRoot 里阻塞的 su 进程——若不显式杀，
            // su/sleep 会在用户停止后继续跑（还拖慢后续 root 调用）。注册表与取消机制解耦，
            // 直调 / 嵌套 / run_code 内调用全覆盖。
            runCatching { com.mcp.ptc.RunRootProcessRegistry.killAllForcibly() }

            // 通知服务端停止（与取消解耦，失败不影响前端收尾）。
            // Web 自动化已在上方走「远程先停」分支（提前 return），不会到达这里。
            if (token != null && !msgId.isNullOrEmpty()) {
                val serverSid = st.serverSessionId ?: sid
                toolScope.launch {
                    runCatching { client().stopStream(token, serverSid, msgId) }
                }
            }
        } catch (e: Exception) {
            LogStore.e("STOP", "stopStream 异常: ${e.message ?: e::class.simpleName}")
            state(sid)?.isStopping = false
            runCatching { emitStream(sid, "error", JSONObject().put("message", "停止失败: ${e.message ?: e::class.simpleName}")) }
        }
    }

    /**
     * 停止流式生成时，把已送达用户的前缀内容（StreamTaskManager 累积的 content/thinking）
     * 定稿为一条 AI 消息并追加落盘（对齐 deepseek-harness 的 cancelled-stream-prefix-finalize）。
     * 否则停止后切走再切回，这段用户已看到的半截回复会从历史消失，后续追问也缺上下文。
     * 重新生成的停止覆盖尾部旧回复，其余场景追加一条新消息。
     */
    /**
     * 停止定稿前，把前缀里未完成的工具调用/结果信封（如 "tool_call: run_code id: ... code <<<...>>>"）
     * 截掉，只保留助手真实正文。停止常发生在模型刚吐出信封的途中，此时 StreamTaskManager 累积的
     * lastContent 混有协议文本，直接落盘会让历史里出现一段原始协议串。
     */
    private fun stripToolProtocolTail(content: String): String {
        val markers = listOf("tool_call:", "tool_result:")
        var cut = Int.MAX_VALUE
        for (m in markers) {
            val i = content.indexOf(m)
            if (i >= 0 && i < cut) cut = i
        }
        return if (cut == Int.MAX_VALUE) content else content.substring(0, cut).trimEnd()
    }

    private suspend fun finalizeStoppedPrefix(sid: String) {
        val st = stateFor(sid)
        // 从 SessionState 读取停止前快照的前缀（stopGeneration 在取消协程前落定），
        // 不读 StreamTaskManager——协程取消后其 invokeOnCompletion 可能已把任务移除。
        val rawContent = st.stoppedPrefixContent
        // 停止常发生在模型刚吐出工具调用信封（tool_call: run_code ... code <<<...>>>）的途中，
        // 此时 lastContent 里混着**协议文本而非助手正文**。直接落盘会变成历史里一段原始协议串，
        // 回看即见 "tool_call: run_code id: ... code <<<"。截到第一个信封标记之前，只保留真正的正文。
        val content = stripToolProtocolTail(rawContent)
        val thinking = st.stoppedPrefixThinking
        if (content.isBlank() && thinking.isBlank()) return
        val msg = ChatMessage(isUser = false, content = content, thinking = thinking)
        val (stored, appended) = synchronized(historyLock) {
            val lastIdx = st.history.indexOfLast { !it.isUser }
            if (st.isRegenerating && lastIdx >= 0 && lastIdx == st.history.size - 1) {
                // 覆盖旧回复时沿用它的 id / parentId：新前缀占据树上的同一个位置，
                // 若换成新 id，前端已渲染的分支切换器会指向一个不再存在的节点。
                val old = st.history[lastIdx]
                val m = tagLocalId(st, msg, old.parentId).let {
                    if (old.id.isNotBlank()) it.copy(id = old.id) else it
                }
                st.history[lastIdx] = m
                m to false
            } else {
                val m = tagLocalId(st, msg, st.history.lastOrNull()?.id.orEmpty())
                st.history.add(m)
                m to true
            }
        }
        // 协程已被 cancel（stopGeneration 触发），落盘需在 NonCancellable 域内避免直接抛 CancellationException
        withContext(NonCancellable) {
            saveMutex.withLock {
                if (appended) {
                    LocalStore.appendMessage(context, LocalStore.backendTag(auth.getBackend()), sid, stored)
                } else {
                    val snap = synchronized(historyLock) { st.history.toList() }
                    LocalStore.rebuildMessages(context, LocalStore.backendTag(auth.getBackend()), sid, snap)
                }
            }
            // 停止定稿同样要回传 id：否则这条半截回复在前端树上没有锚点，无法再被重新生成
            emitLocalMessageId(sid, stored.id)
        }
        LogStore.i("STOP", "finalize 已送达前缀 session=$sid content=${content.take(40)}")
    }

    /**
     * 处理 SSE Done 事件。
     * 如果有待处理的工具调用，等待所有异步工具执行完成后按后端协议续聊：
     *  - DeepSeek 逆向协议：把工具结果拼成一条新 prompt 续聊。
     *  - OpenAI 兼容协议：追加 assistant(tool_calls) + tool 结果消息后续聊。
     */
    private suspend fun handleDone(event: MessageEvent.Done, sid: String?) {
        if (sid == null) return
        val st = stateFor(sid)
        // 防御：用户已点停止时不再走工具续聊。正常路径下 stopStream 的 clearPendingTools
        // 已清空台账，这里不会命中；但 Done 事件可能在停止信号之前就已入队，补兜底。
        if (st.isStopping) {
            LogStore.i("STOP", "handleDone 检测到 isStopping，跳过工具续聊 session=$sid")
            st.pendingToolJobs.clear()
            st.pendingToolMeta.clear()
            st.pendingToolReasoning = ""
            st.rejectedToolResults.clear()
            st.toolCallAlias.clear()
            return
        }
        // 正常工具结果 + 被拒警告合并回传：被拒调用（重复 id 等）虽未执行，但必须将面向 LLM 的
        // 警告附进结果，否则模型收不到反馈、只会不停复读同一 id。正常结果在前、警告在后，保持调用顺序。
        val rejected = st.rejectedToolResults.toList()
        val results = buildList {
            if (st.pendingToolJobs.isNotEmpty()) {
                // 等待所有异步工具执行完成（挂起但不阻塞 SSE 消费——Done 是最后一个事件，无需继续消费）。
                // pendingToolJobs 是 LinkedHashMap，遍历顺序即本轮 tool_calls 顺序；用 List<Pair> 承载
                // 而非 Map，让「结果顺序 == 调用顺序」由类型保证，避免下游误传无序 Map 导致并行归属错乱。
                addAll(st.pendingToolJobs.map { (k, job) -> (st.toolCallAlias[k] ?: k) to job.await() })
            }
            addAll(rejected)
        }
        if (results.isNotEmpty()) {
            st.pendingToolJobs.clear()
            st.rejectedToolResults.clear()
            st.toolCallAlias.clear()
            LogStore.i("TOOL", "ChatBridge Done: ${results.size} 个工具结果回传（含 ${rejected.size} 条被拒警告），按后端续聊")
            // 关键：await 期间用户可能刚好点了停止——开头的 isStopping 检查已经越过，
            // 而 results 是 await 后构建的局部快照，clearPendingTools 清台账对它无效。
            // 若不在发请求前最后再查一次，就会出现「工具执行完毕准备续聊时点停止，请求仍然发出去」。
            if (st.isStopping) {
                LogStore.i("STOP", "handleDone 续聊前检测到 isStopping，丢弃 ${results.size} 个工具结果，不发起续聊 session=$sid")
                st.pendingToolMeta.clear()
                st.pendingToolReasoning = ""
                st.isStopping = false
                st.isBusy = false
                st.isRegenerating = false
                st.streamJob = null
                st.streamMessageId = null
                return
            }
            // 不设 isBusy=false，续聊方法会立即设为 true，避免窗口期
            if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
                continueWebAutomation(sid, results)
            } else {
                continueOpenAI(sid, results)
            }
        } else {
            // OpenAI 兼容后端：把助手回复追加进无状态缓冲，维持多轮上下文
            if (event.content.isNotEmpty()) {
                st.openAIMessages.add(com.mcp.llm.ChatMessage(role = "assistant", content = event.content, reasoning = event.thinking.takeIf { it.isNotBlank() }))
            }
            val doneContent = event.content.trim().let { c ->
                c.replace(Regex("^```[a-zA-Z]*\n?"), "").replace(Regex("\n?```$"), "").trim()
            }
            // IO 线程：更新历史 + 落盘（append-only 事件日志）
            //
            // 顺序很关键：必须先落历史、再（OpenAI 后端）合成 message_id、最后才发 done。
            // 前端的 done 处理器会执行 `pendingRegen = null; pendingEditIds = null; currentAi = null`，
            // 一旦 done 先到，本轮的 message_id 就赶不上 assignMessageIds 的
            // 「重新生成 / 编辑重发」分支，新回复会被当成普通消息挂到 lastId 下——
            // 表现就是「重新生成出来的回复变成了原回复的子节点」。
            // DeepSeek 后端天然满足这个顺序（服务端在流中途就下发 message_id）。
            if (event.content.isNotEmpty() || event.thinking.isNotEmpty()) {
                val newMsg = ChatMessage(isUser = false, content = event.content, thinking = event.thinking)
                // 普通回复 → 追加一条事件；重新生成（覆盖尾部）→ 历史被改写，需全量重建。
                //
                // 注意：OpenAI 后端的「重新生成」已在 innerRegenerate 里把历史截断到锚点之前，
                // 此刻历史末尾是一条 user 消息，下面的覆盖分支几何上不会命中（lastIdx != size-1），
                // 走 append 分支并由 addToHistory 分配新 id —— 正是「兄弟分支」该有的结果。
                // 覆盖分支只为 DeepSeek 后端保留（服务端保留旧分支，本地线性日志只留最新一版）。
                val (stored, appended) = synchronized(historyLock) {
                    val lastIdx = st.history.indexOfLast { !it.isUser }
                    if (st.isRegenerating && lastIdx >= 0 && lastIdx == st.history.size - 1) {
                        // 重新生成：覆盖同一提问的上一版回复，保持线性历史不出现连续两条助手消息
                        val old = st.history[lastIdx]
                        val m = tagLocalId(st, newMsg, old.parentId).let {
                            if (old.id.isNotBlank()) it.copy(id = old.id) else it
                        }
                        st.history[lastIdx] = m
                        m to false
                    } else {
                        val m = tagLocalId(st, newMsg, st.history.lastOrNull()?.id.orEmpty())
                        st.history.add(m)
                        m to true
                    }
                }
                saveMutex.withLock {
                    if (appended) {
                        LocalStore.appendMessage(context, LocalStore.backendTag(auth.getBackend()), sid, stored)
                    } else {
                        val snap = synchronized(historyLock) { st.history.toList() }
                        LocalStore.rebuildMessages(context, LocalStore.backendTag(auth.getBackend()), sid, snap)
                    }
                }
                // OpenAI 兼容后端：把本地合成的助手消息 id 回传前端，供「重新生成 / 编辑重发」定位
                emitLocalMessageId(sid, stored.id)
            }
            // 使用 NonCancellable 防止协程已取消时 withContext 抛出 CancellationException
            withContext(NonCancellable + Dispatchers.Main) {
                emitStream(sid, "done", JSONObject().apply {
                    put("thinking", event.thinking)
                    put("content", doneContent)
                })
            }
            st.isRegenerating = false
            st.isBusy = false
            st.isStopping = false
            // 本轮生成彻底结束（无待续聊工具），清理在途流引用
            st.streamJob = null
            st.streamMessageId = null
            // 从全局 Manager 移除
            StreamTaskManager.complete(sid)
            onStreamActivityChange?.invoke(sid, false)
            // 流在后台完成：其页面停在旧快照上，标记脏待回前台重建
            if (sid != currentSessionId) onOffstageStreamEvent?.invoke(sid)
        }
    }

    /** 把事件安全地回传给前台页面。自动确保 evaluateJavascript 在主线程执行。 */
    private fun emit(event: String, data: JSONObject) {
        onWebEvent?.invoke(null, event, data.toString())
        pushToWebView(event, data)
    }

    private fun pushToWebView(event: String, data: JSONObject) {
        val js = "window.onChatEvent(\"$event\", ${data.toString()})"
        val wv = frontWebView ?: return
        if (wv.parent != null) {
            // evaluateJavascript 必须在主线程调用（UI 线程）。
            // 大部分 emit 调用已通过 withContext(Dispatchers.Main) 确保在主线程，
            // 此时直接调用 evaluateJavascript 避免 webView.post 额外延迟一个消息队列周期。
            // 仅当从 @JavascriptInterface 后台线程调用时，才用 post 投递到主线程。
            if (Looper.myLooper() == Looper.getMainLooper()) {
                wv.evaluateJavascript(js, null)
            } else {
                wv.post { wv.evaluateJavascript(js, null) }
            }
        }
    }

    /**
     * 带会话过滤的事件回传：仅当 [sid] 仍是「前台显示」的会话时才推给前端。
     *
     * 后台流（用户已切到别的会话）仍会在 appScope 里继续跑：其内容已增量累积进
     * StreamTaskManager（lastContent / lastThinking），此处不再向前端派发，待用户切回
     * 该会话时由 onHistory 的 resume 第二参数确定性恢复。
     *
     * 若不在这一层按会话拦截，A 会话后台流的 content / done 会打到 B 会话的共享 WebView：
     *   - content / thinking 会被追加进 B 的 currentAi，污染 B 的气泡；
     *   - done / error / stopped 是终态事件，前端越过 currentAi 判空直接复位，会把 B
     *     的「停止→发送」按钮与顶部横幅提前复位。
     */
    private fun emitStream(sid: String?, event: String, data: JSONObject) {
        // 流事件统一走**进程级扇出**（[StreamListener]）：
        //  - Web 桌面端（SSE）要拿到所有会话、**所有实例**的事件（手机发起的流桌面也该看到）；
        //  - 每个实例据 origin 判断是不是自己发的，别端的事件补推给自己的前台 WebView。
        // 这里不再单独调 onWebEvent：那会让本源事件经「onWebEvent + 扇出」重复投递两次
        // （前端表现为正文重复追加）。
        publishStreamEvent(this, sid, event, data.toString())
        if (sid != null && sid != currentSessionId) {
            // WebView 只挂前台会话的页面：后台会话的页面错过了这次事件，标记脏，
            // 待其回到前台时确定性重建（Tabs 侧的 onOffstageStreamEvent）。
            onOffstageStreamEvent?.invoke(sid)
            return
        }
        pushToWebView(event, data)
    }

    /**
     * 把工具执行结果包装为 function calling 的「tool 角色消息」：
     *   {"role":"tool","tool_call_id":<callId>,"content":<原始工具输出>}
     * 这是 OpenAI function calling 协议里工具结果的标准载体；不再使用 MCP 的 JSON-RPC 2.0 信封。
     * [rawResult] 为工具返回的原文（可能是 JSON 文本或纯文本），原样作为 content 透传。
     */
    /**
     * Web 自动化的工具续聊：站点回复里的 tool_call 已由 [com.mcp.toolbox.parseToolCallFromText]
     * （与 DeepSeek 后端同一套解析器，XML 优先、行式回退）解析并执行，
     * 这里把工具结果按**与调用一致的协议**发回站点继续：
     *  - 极简模式（XML）：<｜｜DSML｜｜ result name="id">原文</...>
     *  - 其余（行式）：tool_result: <id> <<< ... >>>
     * 站点会话自身有上下文，不重发历史。
     */
    private fun continueWebAutomation(sid: String, results: List<Pair<String, String>>) {
        val st = stateFor(sid)
        // 发请求前最后一道闸：用户可能在 handleDone 构建 results 后才点停止。
        if (st.isStopping) {
            LogStore.i("STOP", "continueWebAutomation 检测到 isStopping，放弃续聊 session=$sid")
            st.pendingToolMeta.clear()
            st.pendingToolReasoning = ""
            st.isStopping = false
            st.isBusy = false
            return
        }
        st.isBusy = true
        val prompt = if (protocolStyleXmlFor(sid))
            com.mcp.toolbox.ToolMessages.xmlResults(results)
        else com.mcp.toolbox.ToolMessages.lineResults(results)
        st.pendingToolMeta.clear()
        LogStore.i("TOOL", "Web自动化续聊: 发送 ${results.size} 个工具结果")
        scope.launch {
            val req = LLMRequest(
                listOf(com.mcp.llm.ChatMessage(role = "user", content = prompt)),
                null,
                LLMConfig(
                    model = auth.getOpenAIModel(),
                    temperature = auth.getOpenAITemperature(),
                    maxTokens = auth.getOpenAIMaxTokens(),
                    stream = true,
                    thinkingEnabled = st.lastThinkingEnabled
                )
            )
            // emitThinking=true：续聊轮的思考也要推给前端（前端按工具卡片之后线性渲染）
            streamLLM(req, emitThinking = true, onDone = { ev -> handleDone(ev, sid) }, sid = sid)
        }
    }

    /**
     * 把工具执行结果包装为「后端各自协议」：
     *  - DeepSeek：行式块 tool_result: <id> <<< ... >>>（零转义）；
     *    极简模式（XML 协议）用 <｜｜DSML｜｜ result name="id">原文</...>，与调用格式对称
     *  - OpenAI：function calling 的 tool 消息 {"role":"tool","tool_call_id":...,"content":...}
     * [rawResult] 为工具返回的原文（可能是 JSON 文本或纯文本），原样透传。
     */
    private fun buildToolResult(callId: String, rawResult: String, sid: String? = null): String {
        // 判据是「文本协议后端」而非「是否 DeepSeek」：Web 自动化同样用文本协议
        // （与设置页「协议风格」项的显隐判据同源，见 usesTextToolProtocol）
        val textProtocol = com.mcp.core.llm.usesTextToolProtocol(auth.getBackend())
        return when {
            !textProtocol ->
            com.mcp.toolbox.ToolMessages.resultJson(callId, rawResult)
            // 按**会话**风格封结果：不能读全局，否则 A 会话的工具结果会被 B 会话的
            // 风格静默改写（见 SessionState.protocolStyleXml 注释）。
            protocolStyleXmlFor(sid) ->
            com.mcp.toolbox.ToolMessages.xmlResult(callId, rawResult)
            else ->
                com.mcp.toolbox.ToolMessages.lineResult(callId, rawResult)
        }
    }

    /**
     * 从工具结果封装中提取纯文本 content，兼容三种形状：
     *  - 行式（DeepSeek）：tool_result: <id> <<< ... >>>
     *  - function calling（OpenAI）：{"role":"tool","tool_call_id":...,"content":...}
     *  - 旧（JSON-RPC 2.0）：{"jsonrpc":"2.0","id":...,"result":{"content":[{"type":"text","text":...}]},"error":...}
     */
    private fun extractToolContent(wrapped: String): String =
        com.mcp.toolbox.ToolMessages.extractContent(wrapped)

    /** 从 JSON 字符串中提取指定字段的原始值（简单实现，不依赖完整解析）。 */
    /**
     * 前端提交用户输入给 ask_user 工具（由 JS 端在用户输入后调用）。
     * 该方法会完成对应的 deferred，使工具调用链继续执行。
     */
    @JavascriptInterface
    fun submitUserInput(callId: String, response: String) {
        submitUserInputFor(currentSessionId, callId, response)
    }

    /** 指定会话提交 ask_user 的输入（Web 桌面端按请求 sessionId 调用）。 */
    internal fun submitUserInputFor(sid0: String?, callId: String, response: String) {
        if (sid0.isNullOrEmpty()) {
            LogStore.w("ASK_USER", "submitUserInput 无会话，忽略 callId=$callId")
            return
        }
        val deferred = state(sid0)?.pendingUserInput?.remove(callId)
        if (deferred == null) {
            LogStore.w("ASK_USER", "未找到待处理的用户输入: $callId")
            return
        }
        LogStore.i("ASK_USER", "收到用户输入: callId=$callId response=${response.take(100)}")
        toolScope.launch {
            val toolResult = buildToolResult(callId, """{"user_response":${JsonPrimitive(response)}}""")
            val sid = sid0
            val st = state(sid)
            if (sid != null && st != null) {
                val askMsg = addToHistory(
                    st,
                    ChatMessage(
                        isUser = false,
                        content = "",
                        toolCall = ToolCallData(id = callId, name = "ask_user", arguments = "", result = toolResult)
                    )
                )
                saveMutex.withLock { LocalStore.appendMessage(context, LocalStore.backendTag(auth.getBackend()), sid, askMsg) }
                emitLocalMessageId(sid, askMsg.id)
            }
            withContext(Dispatchers.Main) {
                emit("tool_call", JSONObject().apply {
                    put("id", callId)
                    put("name", "ask_user")
                    put("result", toolResult)
                })
            }
            deferred.complete(toolResult)
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    //  LLM 客户端统一流式驱动（适配 DeepSeek 逆向 / OpenAI 兼容）
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 本次流式调用走哪个端点。三者响应同构，仅请求语义不同：
     *  - [Send] 追加新一轮对话
     *  - [Regenerate] 换一个回复（作用于助手卡片）
     *  - [Edit] 改问题重问（作用于用户卡片）
     */
    private sealed interface StreamChannel {
        data object Send : StreamChannel
        data class Regenerate(val childMessageId: String) : StreamChannel
        data class Edit(val messageId: String) : StreamChannel
    }

    /**
     * 统一驱动一次 LLM 流式调用：内置限流重试，并把 [MessageEvent] 派发回前端。
     * 同时累积待续聊工具调用的元信息（[pendingToolMeta]），供 OpenAI 后端构造 assistant(tool_calls)。
     *
     * @param emitThinking 是否向用户展示思考过程（首轮与工具续聊轮均为 true，前端线性渲染）。
     * @param onDone Done 事件回调（负责工具续聊或收尾）。
     * @param channel 走哪个流式端点：普通发送 / 重新生成 / 编辑重发（见 [StreamChannel]）。
     */
    private fun streamLLM(
        request: LLMRequest,
        emitThinking: Boolean,
        onDone: suspend (MessageEvent.Done) -> Unit,
        channel: StreamChannel = StreamChannel.Send,
        /** 溢出恢复用：用压缩后的当前缓冲重建请求（仅 OpenAI 无状态后端提供）。 */
        requestRefresher: (() -> LLMRequest)? = null,
        /**
         * 事件旁路：非空时，每个 [MessageEvent] 都额外喂给该回调。
         *
         * OpenAI 兼容端点用它把流事件转成 OpenAI SSE 帧——它是**旁路**，
         * 原有分发（前端 emitStream / Token 看板 / 锚点更新）一律照常执行，
         * 因此会话链（parentMessageId）与 App 内聊天完全一致。
         */
        onEventOverride: ((com.mcp.llm.MessageEvent) -> Unit)? = null,
        /**
         * 本流的会话 id。**必须由调用方显式传入**（在入口同步捕获）：
         * 流式协程会跨多个异步阶段运行，而 currentSessionId 是可变全局——Web 桌面端
         * 每个 HTTP 请求都会改写它，协程内再读会把本会话的内容/事件/落盘写到错误会话。
         */
        sid: String? = null
    ) {
        val streamSid = sid ?: currentSessionId ?: run {
            LogStore.e("SSE", "streamLLM 无当前会话，取消流式")
            return
        }
        val st = stateFor(streamSid)
        // Token 看板：本轮请求捕获清零（粘性，溢出/限流标志跨重试保留）
        reqPrompt = 0; reqCompletion = 0; reqReasoning = 0; reqCache = 0
        reqOverflow = false; reqRateLimited = false
        reqCategories = null; reqRecorded = false
        reqStartedAt = 0L
        // 本轮原始报文清零（与请求捕获同生命周期）
        reqRawBuffer = ""; respRawBuilder = StringBuilder()
        reqLiveEmitted = false; reqPromptLive = 0; reqLiveChars = 0; reqLiveAccChars = 0
        val prefs = context.getSharedPreferences(MainActivity.PREF_TAB_MODE, Context.MODE_PRIVATE)
        val retryMax = prefs.getInt("retry_max", 3)
        val retryIntervalMs = (prefs.getFloat("retry_interval_sec", 3f) * 1000).toLong()
        st.streamJob = appScope.launch streamLaunch@ {
            st.isStopping = false
            var attempt = 0
            /** 本轮已做过一次溢出恢复（每轮最多一次，避免循环压缩）。 */
            var overflowRecovered = false
            // 当前轮实际发送的请求：溢出恢复压缩后会被 requestRefresher 重建。
            // 声明在 while 循环**外**——若在循环内声明，每轮迭代会重新初始化为原始 request，
            // 把上一轮溢出恢复重建的请求丢弃，导致重试仍带满的旧会话 id 发出，
            // 服务端再次 context_length_exceeded（commit 2ce5c9c7 引入的 bug）。
            var currentRequest = request
            while (attempt < retryMax) {
                // 协程结束时自动从 Manager 移除（仅异常/取消时）
                if (attempt == 0) {
                    val jobRef = st.streamJob
                    jobRef?.invokeOnCompletion { cause ->
                        if (cause != null) {
                            val task = StreamTaskManager.get(streamSid)
                            if (task != null) {
                                StreamTaskManager.complete(streamSid)
                                onStreamActivityChange?.invoke(streamSid, false)
                                LogStore.i("STREAM_TASK", "流任务异常/取消清理 session=$streamSid cause=${cause.message ?: "未知"}")
                            }
                        }
                    }
                    // 提前注册流任务，确保页面重载时能通过 onHistory resume 参数恢复
                    if (jobRef != null) {
                        StreamTaskManager.register(
                            StreamTaskManager.StreamTask(
                                sessionId = streamSid,
                                messageId = "pending",  // 临时 ID，等 MessageId 来再更新
                                job = jobRef,
                                backend = auth.getBackend()
                            )
                        )
                        LogStore.i("STREAM_TASK", "流提前注册任务 session=$streamSid (pending)")
                        onStreamActivityChange?.invoke(streamSid, true)
                    }
                }
                attempt++
                var rateLimited = false
                /** 上下文窗口溢出待处理标记（Error 帧/异常置位，collect 结束后统一压缩重试）。 */
                var overflowPending = false
                var overflowMsg: String? = null
                var streamRegistered = true  // 已在 attempt==0 时注册，无需 Content 事件再注册
                reqStartedAt = System.currentTimeMillis()
                reqCategories = contextCompactor.rawEstimateTokensByCategory(currentRequest.messages, currentRequest.tools)
                // Token 看板：请求发出即推「进行中」条目——prompt 用校准后的本地计量（实时可得），
                // completion 随流式 delta 实时增长；流结束时由 flushTokenFlow 以 provider 精确值同 seq 覆盖。
                reqLiveEmitted = false
                reqLiveChars = 0
                reqLiveAccChars = 0
                reqPromptLive = contextCompactor.estimateTokens(currentRequest.messages) +
                    (contextCompactor.rawEstimateToolsTokens(currentRequest.tools) * contextCompactor.tokenScaleValue).toInt()
                emitTokenFlowLive()
                try {
                    // 抓取本轮原始 HTTP 报文（请求体 JSON + SSE 响应帧），供 Token 看板明细展示
                    val cl = client().apply {
                        rawCapture = object : RawCapture {
                            override fun onRequest(json: String) { reqRawBuffer = json }
                            override fun onResponseChunk(json: String) {
                                // 上限保护：单条响应原始报文超过 20000 字符后停止累积，避免撑爆流水记录与显示。
                                if (respRawBuilder.length >= 20000) return
                                if (respRawBuilder.isEmpty()) respRawBuilder.append(json)
                                else respRawBuilder.append("\n").append(json)
                            }
                        }
                    }
                    val stream = when (channel) {
                        is StreamChannel.Send -> cl.sendMessage(currentRequest)
                        is StreamChannel.Regenerate -> cl.regenerate(currentRequest, channel.childMessageId)
                        is StreamChannel.Edit -> cl.editMessage(currentRequest, channel.messageId)
                    }
                    stream.collect { event ->
                        when (event) {
                            is MessageEvent.PowProgress -> {
                                if (emitThinking) withContext(Dispatchers.Main) {
                                    emitStream(streamSid, "pow", JSONObject().apply {
                                        put("tried", event.tried)
                                        put("total", event.total)
                                    })
                                }
                            }
                            is MessageEvent.Thinking -> {
                                onEventOverride?.invoke(event)
                                // Token 看板：思考内容计入 completion 实时量（与 emitThinking 解耦，token 照消耗）
                                reqLiveChars += event.delta.codePointCount(0, event.delta.length)
                                reqLiveAccChars += event.delta.codePointCount(0, event.delta.length)
                                if (reqLiveAccChars >= tokenLiveThresholdChars) { emitTokenFlowLive(); reqLiveAccChars = 0 }
                                if (emitThinking) {
                                    // 累积思考内容到全局 Manager（用于恢复时追显）
                                    val sid = streamSid
                                    if (sid != null) {
                                        val existing = StreamTaskManager.get(sid)?.lastThinking ?: ""
                                        StreamTaskManager.updateThinking(sid, existing + event.delta)
                                    }
                                    withContext(Dispatchers.Main) {
                                        emitStream(streamSid, "thinking", JSONObject().put("delta", event.delta))
                                    }
                                }
                            }
                            is MessageEvent.ThinkingReplace -> {
                                // 整段替换（Web 自动化站点重渲染）：覆盖而非追加，
                                // 避免非前缀变化那一轮的新增内容丢失
                                if (emitThinking) {
                                    val sid = streamSid
                                    if (sid != null) {
                                        StreamTaskManager.updateThinking(sid, event.full)
                                    }
                                    withContext(Dispatchers.Main) {
                                        emitStream(streamSid, "thinking_replace", JSONObject().put("full", event.full))
                                    }
                                }
                            }
                            is MessageEvent.Content -> {
                                onEventOverride?.invoke(event)
                                val sid = streamSid
                                // 累积内容并更新到全局 Manager（用于恢复时追显）
                                // 流任务已由 attempt==0 块提前注册，此处不再重复注册
                                if (sid != null) {
                                    val existing = StreamTaskManager.get(sid)?.lastContent ?: ""
                                    val newContent = existing + event.delta
                                    StreamTaskManager.updateContent(sid, newContent)
                                }
                                // Token 看板：正文 delta 计入 completion 实时量（节流 emit）
                                reqLiveChars += event.delta.codePointCount(0, event.delta.length)
                                reqLiveAccChars += event.delta.codePointCount(0, event.delta.length)
                                if (reqLiveAccChars >= tokenLiveThresholdChars) { emitTokenFlowLive(); reqLiveAccChars = 0 }
                                withContext(Dispatchers.Main) {
                                    emitStream(streamSid, "content", JSONObject().put("delta", event.delta))
                                }
                            }
                            is MessageEvent.ContentReplace -> {
                                // 整段替换（Web 自动化站点重渲染 / Markdown 标记回改）：
                                // 覆盖当前段，避免增量模型无法表达的「改写」导致缺字
                                val sid = streamSid
                                if (sid != null) {
                                    StreamTaskManager.updateContent(sid, event.full)
                                }
                                withContext(Dispatchers.Main) {
                                    emitStream(streamSid, "content_replace", JSONObject().put("full", event.full))
                                }
                            }
                            is MessageEvent.MessageId -> {
                                if (!isValidServerMessageId(event.id)) {
                                    // DeepSeek 服务端消息 id 必须是 1..u32。file-<uuid> 之类脏值落进
                                    // st.parentMessageId 会让下一轮 parent_message_id 被 normalizeMessageId
                                    // 归一化成 null（服务端开新分支、历史全丢），发给前端则让
                                    // assignMessageIds 的 parseInt 得 NaN、消息树打散。
                                    // DeepSeekApi 已在源头校验，这里是最后一道闸门。
                                    LogStore.w("MSG_ID", "丢弃非法服务端消息 id session=$streamSid id=${event.id}")
                                } else {
                                // 服务端 id 存内部状态（请求/停止都用它）；发给前端的一律换算成本地 id
                                st.parentMessageId = event.id
                                st.streamMessageId = event.id
                                // 同时记下**本轮 user 消息 id**（真实值），供子 Agent fork 用
                                event.requestId?.takeIf { isValidServerMessageId(it) }?.let {
                                    st.lastUserMessageId = it
                                }
                                // B9：把续聊锚点持久化，冷启动/离线时无需再拉整棵历史
                                runCatching {
                                    LocalStore.saveSessionCurrentId(
                                        context, LocalStore.backendTag(auth.getBackend()),
                                        streamSid, event.id
                                    )
                                }
                                // 把真实助手消息 id 回传前端，用于维护消息树（重新生成 / 编辑重发）
                                withContext(Dispatchers.Main) {
                                    emitStream(streamSid, "message_id", JSONObject().put("id", toLocalId(streamSid, event.id)))
                                }
                                // 更新已注册任务的 messageId（任务已在 attempt==0 时注册）
                                val task = StreamTaskManager.get(streamSid)
                                if (task != null) {
                                    // 更新 messageId
                                    val updatedTask = task.copy(messageId = event.id)
                                    StreamTaskManager.register(updatedTask)
                                    LogStore.i("STREAM_TASK", "更新流任务 messageId session=$streamSid msgId=${event.id}")
                                } else {
                                    // 防御：如果任务不存在（理论上不会），用旧逻辑注册
                                    if (st.streamJob != null) {
                                        StreamTaskManager.register(
                                            StreamTaskManager.StreamTask(
                                                sessionId = streamSid,
                                                messageId = event.id,
                                                job = st.streamJob!!,
                                                backend = auth.getBackend()
                                            )
                                        )
                                        LogStore.i("STREAM_TASK", "fallback 注册流任务 session=$streamSid msgId=${event.id}")
                                    }
                                }
                                }
                            }
                            is MessageEvent.ToolCall -> {
                                onEventOverride?.invoke(event)
                                LogStore.i("TOOL", "ChatBridge 收到 ToolCall: id=${event.id} name=${event.name}")
                                var id = event.id
                                // 用户已主动停止：不再启动新工具执行，避免「停止后工具还在跑 / 还被触发」。
                                // in-flight 工具由 clearPendingTools 取消协程 + PtcCancellation / killAllForcibly 兜底。
                                if (st.isStopping) {
                                    LogStore.w("TOOL", "停止中，跳过工具调用: id=${event.id} name=${event.name}")
                                    return@collect
                                }
                                // id 缺失自愈：模型偶尔漏写 id。补一个本轮内唯一、且不撞历史台账的 call_N，
                                // 并记一条面向 LLM 的提示（随结果回传，让它下次显式给 id）。
                                // 不补位的话，空 id 会让 pendingToolJobs/别名映射全部落在同一个 key 上，
                                // 多个无 id 调用互相覆盖、结果错乱。
                                var idFixWarn: String? = null
                                if (id.isBlank()) {
                                    var n = 1
                                    while (("call_$n" in st.consumedToolCallIds) ||
                                        ("call_$n" in st.pendingToolJobs) || st.toolCallAlias.containsValue("call_$n")) n++
                                    id = "call_$n"
                                    idFixWarn = "工具调用 id 缺失，已自动补为 $id；下次请为每个 tool_call 显式给定唯一且非空的 id（例如 id: call_1）。"
                                    LogStore.w("TOOL", "工具调用 id 缺失，已补位: $id name=${event.name}")
                                }
                                // 重复判定：本轮已出现（pendingToolJobs 仍挂着首个调用）或跨轮已消费过。
                                //
                                // 顺序敏感：必须**先判定、后登记**。此前在判定之前就 `add(id)`，
                                // 导致 `consumedToolCallIds.contains(id)` 对刚写入的 id 恒为 true——
                                // 任何 id（含全新 id）第一次出现就被误判为「历史已出现重复」：
                                // 去重开时会拒绝执行全部工具，去重关时每个调用都附一条重复警告。
                                val reuseInTurn = id.isNotEmpty() && st.pendingToolJobs.containsKey(id)
                                val crossTurnDup = id.isNotEmpty() && st.consumedToolCallIds.contains(id)
                                val isDup = reuseInTurn || crossTurnDup
                                // 判定完成后才登记：跨轮台账只记录真正消费过的 id，供后续轮次识别复读。
                                if (id.isNotEmpty()) st.consumedToolCallIds.add(id)
                                // 去重开关的两档语义：
                                //  - 开：重复 id 直接**拒绝执行**，把拒绝理由回传 LLM（自愈）；
                                //  - 关：不拦截，**照常执行**，但把重复警告贴到工具输出后面（仍提示 LLM 改唯一 id）。
                                val where = if (reuseInTurn) "本轮内" else "历史已出现"
                                if (auth.isToolDedupEnabled() && isDup) {
                                    val dupMsg = "工具调用被拒绝：id '${id}' ${where}重复，重复 id 不会再次执行。" +
                                        "请为每个工具调用分配唯一 id 后重试。"
                                    st.rejectedToolResults.add(id to buildToolResult(id, errorResult(dupMsg), streamSid))
                                    LogStore.w("TOOL", "ToolCall id 重复，已拒绝执行: id=$id name=${event.name}")
                                } else {
                                    st.pendingToolMeta[id] = event.name to event.arguments
                                    // 捕获本轮思考，供 continueOpenAI 回放 assistant(tool_calls) 时原样发回
                                    // （DeepSeek 思考模式 + function calling 的硬性要求）。
                                    st.pendingToolReasoning = StreamTaskManager.get(streamSid)?.lastThinking ?: ""
                                    // 同轮重复时改用唯一内部键，避免覆盖首个仍挂起的 deferred（两个都会执行）；
                                    // 结果按原始 id 回传（toolCallAlias 映射回原始 id）。
                                    val internalKey = if (reuseInTurn) {
                                        val k = "${id}#dup${++st.dupKeySeq}"
                                        st.toolCallAlias[k] = id
                                        k
                                    } else id
                                    val deferred = CompletableDeferred<String>()
                                    st.pendingToolJobs[internalKey] = deferred
                                    // 异步执行工具，不阻塞 SSE 消费
                                    executeToolAsync(streamSid, id, event.name, event.arguments, deferred)
                                    // 去重关 + 重复：照常执行，但把警告贴在结果后（提示 LLM 改用唯一 id）。
                                    if (!auth.isToolDedupEnabled() && isDup) {
                                        val warn = "工具调用已照常执行，但 id '${id}' ${where}重复。请为每个工具调用分配唯一 id，" +
                                            "否则结果归属可能错乱。"
                                        st.rejectedToolResults.add(id to buildToolResult(id, errorResult(warn), streamSid))
                                        LogStore.w("TOOL", "重复 id 已照常执行并附警告: id=$id name=${event.name}")
                                    }
                                    // id 缺失补位的提示同样随结果回传（无论去重开关），让 LLM 下次显式给 id。
                                    idFixWarn?.let { w ->
                                        st.rejectedToolResults.add(id to buildToolResult(id, errorResult(w), streamSid))
                                        LogStore.i("TOOL", "已回传 id 缺失补位提示: id=$id")
                                    }
                                }
                            }
                            is MessageEvent.Usage -> {
                                // provider 上报的真实 prompt token（route-priced）→ 校准本地启发式估算。
                                // 分母必须用**未校准**口径（rawEstimate*），否则比值恒为 1，校准自我抵消。
                                // 校准放在这里而不是 Done 之后：下一轮发送前阈值就已收敛。
                                //
                                // 例外：DeepSeek 逆向 SSE 只回传总数（accumulated_token_usage），
                                // prompt/completion 拆分是本地估算（providerUsageOnly=true）。用推算值
                                // 校准会把噪声灌进 tokenScale（0.5~3.0、EMA 30%），污染整个压缩阈值体系，
                                // 危害大于看板数字不精确——故跳过校准，看板仍照常记录 total 与估算拆分。
                                val heuristic = contextCompactor.rawEstimateTokens(currentRequest.messages) +
                                    contextCompactor.rawEstimateToolsTokens(currentRequest.tools)
                                if (!event.providerUsageOnly) {
                                    contextCompactor.calibrate(event.promptTokens, heuristic)
                                }
                                // Token 看板：捕获本轮用量明细（Usage 事件即落流水，无需等待 Done）
                                reqPrompt = event.promptTokens
                                reqCompletion = event.completionTokens
                                reqReasoning = if (event.reasoningTokens >= 0) event.reasoningTokens else 0
                                reqCache = event.cacheReadTokens
                                flushTokenFlow(sid = streamSid)
                                // T2：reasoning_tokens 与 cache hit 可观测。
                                // cacheReadTokens>0 即证明 provider 暖缓存被复用——直接验证 A1
                                // 「前缀缓存友好化」是否真命中（字节前缀稳定 ≠ 一定命中，还要看计费口径/路由）。
                                val cacheHit = if (event.cacheReadTokens > 0) " cacheHit=${event.cacheReadTokens}" else ""
                                val reasoning = if (event.reasoningTokens >= 0) " reasoning=${event.reasoningTokens}" else ""
                                if (event.cacheReadTokens > 0) {
                                    LogStore.i("TOKEN", "前缀缓存命中：provider 复用暖缓存 cacheReadTokens=${event.cacheReadTokens}/${event.promptTokens}")
                                }
                                LogStore.d(
                                    "TOKEN",
                                    "usage: prompt=${event.promptTokens} completion=${event.completionTokens} " +
                                        // Locale.US 固定小数分隔符：默认 locale 为德语/法语区时
                                        // "%.3f" 会输出 "1,234"，日志里的校准系数会被误读成两个字段
                                        "启发式=$heuristic 校准系数=" +
                                        String.format(java.util.Locale.US, "%.3f", contextCompactor.tokenScaleValue) +
                                        reasoning + cacheHit
                                )
                            }
                            is MessageEvent.Done -> {
                                onEventOverride?.invoke(event)
                                onDone(event)
                                // Token 看板：本轮请求流水追加（Done 兜底：若 Usage 阶段未落流水则补写）
                                flushTokenFlow(
                                    when {
                                        event.truncated -> "trunc"      // 输出被 max_tokens 截断
                                        reqOverflow -> "over"          // 上下文窗口溢出（压缩后重试）
                                        reqRateLimited -> "rate"       // 429 限流
                                        else -> "ok"
                                    },
                                    sid = streamSid
                                )
                                pushTokenConfig() // 刷新模型/窗口（首轮前若页面未就绪则此时补齐）
                                return@collect
                            }
                            is MessageEvent.Error -> {
                                flushTokenFlow("error", sid = streamSid)
                                val msg = formatError(event.throwable)
                                // 上下文窗口溢出（对齐 deepseek-harness overflow-recovery）：
                                // 置位等待 collect 结束后压缩当前缓冲并重建请求重试，不立即复位 isBusy。
                                val isOverflow = requestRefresher != null &&
                                    ContextCompactor.isContextWindowExceededError(msg) &&
                                    !overflowRecovered
                                if (isOverflow) {
                                    overflowPending = true
                                    reqOverflow = true   // Token 看板：标记本轮为上下文溢出
                                    overflowMsg = msg
                                    LogStore.i("COMPACT", "检测到上下文窗口溢出，等待压缩后重试: ${msg.take(120)}")
                                } else {
                                    rateLimited = (event.throwable as? OpenAIException)?.httpCode == 429
                                        || msg.contains("过于频繁") || msg.contains("rate_limit")
                                    if (rateLimited) reqRateLimited = true   // Token 看板：标记本轮为 429 限流
                                    if (rateLimited && attempt < retryMax) {
                                        LogStore.i("TOOL", "限流重试 ($attempt/$retryMax), ${retryIntervalMs}ms 后重试")
                                        withContext(Dispatchers.Main) {
                                            emitStream(streamSid, "error", JSONObject().put("message", "限流：${retryIntervalMs / 1000}秒后重试 ($attempt/$retryMax)"))
                                        }
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            emitStream(streamSid, "error", JSONObject().put("message", msg))
                                        }
                                        st.isBusy = false
                                        st.isRegenerating = false
                                    }
                                }
                            }
                        
                        else -> {}}
                    }
                    // stream.collect 正常完成（可能 SSE 流已读完），但用户可能已点击停止
                    if (st.isStopping) {
                        LogStore.i("STOP", "stream.collect 正常完成后检测到 isStopping，发送 stopped 事件")
                        // 整段收尾必须 NonCancellable：本协程可能已被 stopStream cancel，
                        // 收尾步骤（回填工具结果 / 定稿 / 发 stopped）任一被取消都会让前端卡住。
                        withContext(NonCancellable) {
                        // 顺序：先定稿前缀，再发 stopped。
                        // 前端的 stopped 处理器会执行 `pendingRegen = null; pendingEditIds = null`，
                        // 而定稿过程（OpenAI 后端）要合成 message_id —— 必须先到，
                        // 否则 assignMessageIds 退回「用 lastId 反推 uId = aId - 1」的分支，
                        // 而重新生成/编辑重发后的 id 并不连续，会把新回复挂到错误的父节点下
                        // （表现为刚生成的半截回复直接不可见）。
                        // 先给被中断的工具回填「已停止」结果，再定稿、再发 stopped：
                        // 回填必须在 stopped 之前，否则前端 currentAi 已清空，卡片找不到。
                        emitStoppedToolResults(st, streamSid)
                        finalizeStoppedPrefix(streamSid)
                            runCatching { withContext(NonCancellable + Dispatchers.Main) { emitStream(streamSid, "stopped", JSONObject()) } }
                        }
                        st.isBusy = false
                        st.isStopping = false
                        st.isRegenerating = false
                        st.streamJob = null
                        st.streamMessageId = null
                        return@streamLaunch
                    }
                } catch (e: Exception) {
                    if (st.isStopping) {
                        // 用户主动停止：不报错误，通知前端收尾（气泡定格 + 按钮复位）
                        // 整段收尾必须包在 NonCancellable 里：本协程正是被 stopStream cancel 的，
                        // 裸 suspend（emitStoppedToolResults 内的 withContext(Dispatchers.Main) 已自行 NonCancellable）
                        // 之外的挂起点都会立刻抛 CancellationException，导致 stopped 事件发不出去、
                        // 前端按钮停在红色停止态不反转。
                        LogStore.i("STOP", "catch 到异常(${e::class.simpleName})，检测到 isStopping，发送 stopped 事件")
                        withContext(NonCancellable) {
                            // 先给被中断的工具回填「已停止」结果，再定稿、再发 stopped：
                            // 回填必须在 stopped 之前，否则前端 currentAi 已清空，卡片找不到。
                            emitStoppedToolResults(st, streamSid)
                            // 先定稿再发 stopped，理由同上：定稿内含 OpenAI 后端合成的 message_id，
                            // 必须赶在前端清掉 pendingRegen / pendingEditIds 之前送达。
                            finalizeStoppedPrefix(streamSid)
                            runCatching { withContext(NonCancellable + Dispatchers.Main) { emitStream(streamSid, "stopped", JSONObject()) } }
                        }
                        st.isBusy = false
                        st.isStopping = false
                        st.isRegenerating = false
                        st.streamJob = null
                        st.streamMessageId = null
                        return@streamLaunch
                    }
                    // 如果 handleDone 已处理完成（isBusy=false），忽略后续异常，防止覆盖 done 内容
                    if (!st.isBusy && st.streamJob == null) {
                        LogStore.w("STOP", "忽略异常(${e::class.simpleName})，handleDone 已完成处理: ${e.message?.take(100)}")
                        st.isStopping = false
                        st.isRegenerating = false
                        return@streamLaunch
                    }
                    val msg = formatError(e)
                    // 上下文窗口溢出异常 → 与 Error 帧同一路径：压缩后重试
                    val isOverflow = requestRefresher != null &&
                        ContextCompactor.isContextWindowExceededError(msg) &&
                        !overflowRecovered
                    if (isOverflow) {
                        overflowPending = true
                        reqOverflow = true   // Token 看板：标记本轮为上下文溢出
                        overflowMsg = msg
                        LogStore.i("COMPACT", "检测到上下文窗口溢出异常，等待压缩后重试: ${msg.take(120)}")
                    } else {
                        rateLimited = msg.contains("过于频繁") || msg.contains("rate_limit")
                        if (rateLimited) reqRateLimited = true   // Token 看板：标记本轮为 429 限流
                        if (!rateLimited || attempt >= retryMax) {
                            withContext(Dispatchers.Main) {
                                emitStream(streamSid, "error", JSONObject().put("message", msg))
                            }
                            st.isBusy = false
                            st.isRegenerating = false
                            st.streamJob = null
                            st.streamMessageId = null
                        }
                    }
                }
                // 上下文窗口溢出恢复：压缩当前缓冲并重建请求后重试（每轮最多一次）
                if (overflowPending && requestRefresher != null) {
                    if (attempt >= retryMax) {
                        // 已到重试上限：保留原始溢出错误，复位状态
                        overflowPending = false
                        withContext(Dispatchers.Main) {
                            emitStream(streamSid, "error", JSONObject().put("message", overflowMsg ?: "上下文窗口溢出"))
                        }
                        st.isBusy = false
                        st.isRegenerating = false
                        break
                    }
                    // DeepSeek：溢出恢复前从本地历史播撒 openAIMessages（正常流程未填充）
                    val compacted = withContext(Dispatchers.IO) { forceCompactForOverflow(streamSid, st, extraTokens = estimateToolsTokens(modelTools())) }
                    if (compacted) {
                        currentRequest = requestRefresher.invoke()
                        reqCategories = contextCompactor.rawEstimateTokensByCategory(currentRequest.messages, currentRequest.tools)
                        overflowRecovered = true
                        overflowPending = false
                        LogStore.i("COMPACT", "上下文溢出压缩完成，重试 ($attempt/$retryMax)")
                        withContext(Dispatchers.Main) {
                            emitStream(streamSid, "compacting", JSONObject().put("message", "上下文已满，已压缩，正在重试…"))
                        }
                    } else {
                        // 压缩失败：保留原始溢出错误
                        overflowPending = false
                        withContext(Dispatchers.Main) {
                            emitStream(streamSid, "error", JSONObject().put("message", overflowMsg ?: "上下文窗口溢出，且压缩失败"))
                        }
                        st.isBusy = false
                        st.isRegenerating = false
                        break
                    }
                } else if (rateLimited && attempt < retryMax) {
                    delay(retryIntervalMs)
                } else {
                    break
                }
            }
        }
    }

    /** 把异常格式化为可读错误信息（兼容 OpenAI / DeepSeek 异常）。 */
    private fun formatError(t: Throwable): String {
        return (t as? OpenAIException)?.let { "HTTP ${it.httpCode}: ${it.message}" }
            ?: (t as? DeepSeekException)?.let { "HTTP ${it.httpCode}: ${it.message}" }
            ?: (t.message ?: t.toString())
    }

    /**
     * 构造 OpenAI 兼容后端的请求：维护无状态消息缓冲（system + 历史 + 当前用户消息），
     * 并附带工具定义（原生 function calling）。每次调用会向缓冲追加一条 user 消息。
     */
    /**
     * 构造 OpenAI 兼容后端的请求：维护无状态消息缓冲（system + 历史 + 当前用户消息），
     * 并附带工具定义（原生 function calling）。每次调用会向缓冲追加一条 user 消息。
     * @return 请求本身 + 溢出恢复用重建函数（用压缩后的当前缓冲重建等价请求）。
     */
    private suspend fun openAIRequest(sid: String, userContent: String, thinking: Boolean, imageUrls: List<String> = emptyList()): Pair<LLMRequest, () -> LLMRequest> {
        val st = stateFor(sid)
        if (st.openAIMessages.isEmpty() || st.openAIMessages.firstOrNull()?.role != "system") {
            st.openAIMessages.clear()
            st.openAIMessages.add(com.mcp.llm.ChatMessage(role = "system", content = buildOpenAISystemContent(sid)))
        }
        st.openAIMessages.add(com.mcp.llm.ChatMessage(role = "user", content = userContent, imageUrls = imageUrls.ifEmpty { null }))
        val tools = modelTools()
        // 发送前压缩：工具定义估算计入压力（tools 每次请求全量携带），先剪枝，仍超阈值时 LLM 摘要并替换
        compactOpenAIIfNeeded(sid, st, extraTokens = estimateToolsTokens(tools))
        val cfg = LLMConfig(
            model = auth.getOpenAIModel(),
            temperature = auth.getOpenAITemperature(),
            maxTokens = auth.getOpenAIMaxTokens(),
            stream = true,
            thinkingEnabled = thinking
        )
        LogStore.i("OPENAI", "构造请求: messages=${st.openAIMessages.size} tools=${tools?.size ?: 0} model=${cfg.model}")
        val request = LLMRequest(st.openAIMessages.toList(), tools, cfg)
        val refresher: () -> LLMRequest = { LLMRequest(st.openAIMessages.toList(), tools, cfg) }
        // Web 自动化：站点会话自身有上下文，不能把整段本地历史拍平重发（会重复灌上下文）。
        // 与 DeepSeek 后端同一套语义：**只有本地首轮**注入系统提示词+技能+MCP+工具清单+行式
        // 调用说明（复用 composeDeepSeek 同一段组合），之后每轮只发新消息原文；
        // 站点回复里的 tool_call 行由 WebAutomationClient 用同一套解析器解析执行。
        if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
            val siteReq = webAutoSiteRequest(cfg, sid, st.openAIMessages)
            return siteReq to { siteReq }
        }
        return request to refresher
    }

    /**
     * Web 自动化的站点请求：收敛为**单条 user 消息**。
     * 首轮判定从本地历史推导（历史里还没有助手回复 = 首轮未送达）：注入 DeepSeek 组合器产出的
     * 完整提示词（系统提示词 + 技能 + MCP + 工具清单 + 行式调用说明）；之后只发新消息原文——
     * 站点侧上下文由站点会话自己维护，重发历史等于重复灌上下文。
     */
    private fun webAutoSiteRequest(cfg: LLMConfig, sid: String, messages: List<com.mcp.llm.ChatMessage>): LLMRequest {
        val st = stateFor(sid)
        val userText = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        // 是否已注入过首轮提示词**从本地历史推导**，不用内存标志：
        // 助手回复落进历史 = 首轮已成功送达站点。内存标志会被 LRU 淘汰/
        // 进程重启丢掉，一丢就重新注入——防不住反复注入。
        // 代价：首轮发送失败（助手回复没落历史）时，重试会再注入一次，可接受。
        val sitePrompt = if (st.history.none { !it.isUser }) {
            promptComposer.composeDeepSeek(
                toolbox, userText, workspacePath(sid),
                ptcRequestContext(toolbox.all(), ptcActive(sid), style = currentPtcStyle(sid))
            )
        } else userText
        return LLMRequest(listOf(com.mcp.llm.ChatMessage(role = "user", content = sitePrompt)), null, cfg)
    }

    /**
     * 传给模型的工具集：PTC 呈现折叠下只暴露 `run_code`（其余工具经程序内 `tools.xxx()` 调用），
     * 与系统提示词里的 collapseSection / SDK 段保持一致。无工具时返回 null。
     */
    private fun modelTools(): List<ToolDef>? {
        val all = toolbox.all()
        val ptc = ptcActive(currentSessionId)
        val facing = modelFacingTools(all, ptc)
        LogStore.i("OPENAI", "modelTools: toolbox=${all.size} facing=${facing?.size} ptc=$ptc")
        return facing
    }

    /** 构造 OpenAI 兼容后端的 system 消息内容（system_prompt + 已启用技能 + 推进模式待办 + 操作历史）。
     * 工具定义通过原生 function calling（tools 参数）传递，不在 system message 中重复注入。 */
    private fun buildOpenAISystemContent(sid: String): String {
        // PTC 折叠按会话预设派生（同一进程内手机桥/Web 桥可各持不同会话，不再读全局状态）
        val ptcCtx = ptcRequestContext(toolbox.all(), ptcActive(sid), style = currentPtcStyle(sid))
        val pushMode = context.getSharedPreferences(MainActivity.PREF_TAB_MODE, Context.MODE_PRIVATE)
            .getBoolean("push_mode", false)
        val todoContext = if (pushMode) {
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                TodoStore.toMarkdown(context, excludeDone = true)
            }
        } else {
            null
        }
        return promptComposer.composeOpenAiSystemContent(
            todoContext = todoContext,
            operationHistory = formatOperationHistory(sid),
            workspaceDir = workspacePath(sid),
            ptc = ptcCtx
        )
    }

    /** 当前会话的工作目录绝对路径（惰性创建会话目录与 Workspace，供系统提示词注入）。 */
    internal fun workspacePath(sid: String): String =
        LocalStore.ensureSessionWorkspace(context, LocalStore.backendTag(auth.getBackend()), sid).absolutePath

    /** 前端获取当前会话工作区路径（浏览器模式下显示与快捷注入）。 */
    @JavascriptInterface
    fun getSessionWorkspacePath(): String {
        val sid = currentSessionId ?: return ""
        return workspacePath(sid)
    }

    /**
     * 再重新请求（原生 function calling 回合）。
     * OpenAI 兼容后端工具续聊：把助手发起的 tool_calls 与工具结果作为标准消息追加到无状态缓冲，
     */
    private suspend fun continueOpenAI(sid: String, results: List<Pair<String, String>>) {
        val st = stateFor(sid)
        // 发请求前最后一道闸：用户可能在 handleDone 构建 results 后才点停止。
        if (st.isStopping) {
            LogStore.i("STOP", "continueOpenAI 检测到 isStopping，放弃续聊 session=$sid")
            st.pendingToolMeta.clear()
            st.pendingToolReasoning = ""
            st.isStopping = false
            st.isBusy = false
            return
        }
        // 第 2 步：先补回助手那条 assistant(tool_calls)（content 为空），保持 messages 合法
        val toolCalls = st.pendingToolMeta.map { (id, pair) ->
            com.mcp.llm.ToolCall(id, "function", com.mcp.llm.ToolCallFunction(pair.first, pair.second))
        }
        st.openAIMessages.add(com.mcp.llm.ChatMessage(role = "assistant", toolCalls = toolCalls, reasoning = st.pendingToolReasoning.takeIf { it.isNotBlank() }))
        // 第 4 步：为每个 tool_call 追加一条 tool 消息，顺序与 tool_calls 一致，靠 tool_call_id 归属。
        // 必须按 pendingToolMeta（即 toolCalls 的来源）遍历而非按 results：OpenAI 要求
        // assistant.tool_calls 与后续 tool 消息严格一一对应，缺一条即 400，故结果缺失时也补空串占位。
        val byId = results.toMap()
        for ((id, meta) in st.pendingToolMeta) {
            // 取出 function calling 工具结果的纯文本 content，作为 tool 消息正文
            val wrapped = byId[id] ?: ""
            val raw = extractToolContent(wrapped)
            // T4：超长结果 spill 落盘，正文替换为头/尾预览 + 定位符（best-effort，写盘失败保留原样）。
            // 完整文本仍留在 history 的 toolCall.result 中，重载会话时由 seedOpenAIMessages 还原，不丢数据。
            val spilled = ToolResultSpill.maybeSpill(context, sid, id, meta.first, raw)
            if (spilled.spilled) {
                LogStore.i("SPILL", "工具结果落盘: 工具=${meta.first} 调用=$id 会话=$sid")
            }
            st.openAIMessages.add(com.mcp.llm.ChatMessage(role = "tool", content = spilled.content, toolCallId = id))
        }
        st.pendingToolMeta.clear()
        // 工具续聊前压缩：工具结果往往是 token 大户，先裁剪再按需 LLM 摘要（工具定义估算计入压力）
        val tools = modelTools()
        compactOpenAIIfNeeded(sid, st, extraTokens = estimateToolsTokens(tools))
        val cfg = LLMConfig(
            model = auth.getOpenAIModel(),
            temperature = auth.getOpenAITemperature(),
            maxTokens = auth.getOpenAIMaxTokens(),
            stream = true,
            thinkingEnabled = st.lastThinkingEnabled
        )
        val req = LLMRequest(st.openAIMessages.toList(), tools, cfg)
        LogStore.i("OPENAI", "工具续聊: messages=${st.openAIMessages.size} toolCalls=${toolCalls.size}")
        // emitThinking=true：续聊轮的思考也要推给前端（前端按工具卡片之后线性渲染）
        streamLLM(req, emitThinking = true, onDone = { ev -> handleDone(ev, sid) }, requestRefresher = { LLMRequest(st.openAIMessages.toList(), tools, cfg) }, sid = sid)
    }

    /**
     * 把工具结果作为 function calling 的 tool 角色消息发给逆向 API 续聊。
     * prompt 即该 tool 消息本身（单条对象 / 多条数组），严格符合 function calling 协议，
     * 不再拼成带标注的裸文本。收到 LLM 回复后，若有新工具调用，继续异步执行 + 批量发送。
     */
    private suspend fun sendToolResultBatch(sid: String, results: List<Pair<String, String>>) {
        val token = auth.getToken()
        if (token.isNullOrEmpty()) {
            LogStore.e("TOOL", "sendToolResultBatch 失败: token=${token?.take(4) ?: "null"} sid=$sid")
            withContext(Dispatchers.Main) {
                emit("error", JSONObject().put("message", "自动续聊失败：未登录或无会话"))
            }
            state(sid)?.isBusy = false
            return
        }
        val st = stateFor(sid)
        // 发请求前最后一道闸：用户可能在 handleDone 构建 results 后才点停止。
        if (st.isStopping) {
            LogStore.i("STOP", "sendToolResultBatch 检测到 isStopping，放弃续聊 session=$sid")
            st.pendingToolMeta.clear()
            st.pendingToolReasoning = ""
            st.isStopping = false
            st.isBusy = false
            return
        }
        st.isBusy = true
        // 把工具结果作为 function calling 的 tool 角色消息发给逆向 API（prompt 即该消息本身）：
        //   单条 -> {"role":"tool","tool_call_id":id,"content":...}；多条 -> [{"role":"tool",...}, ...]
        val prompt = if (protocolStyleXmlFor(sid))
            com.mcp.toolbox.ToolMessages.xmlResults(results)
        else com.mcp.toolbox.ToolMessages.lineResults(results)
        st.pendingToolMeta.clear()
        LogStore.i("TOOL", "ChatBridge 批量续聊: 发送 ${results.size} 个工具结果(tool 消息), parent=${st.parentMessageId}")

        scope.launch {
            // 任务执行间隔（工具调用链之间停顿，避免触发限流）
            val taskIntervalMs = (context.getSharedPreferences(MainActivity.PREF_TAB_MODE, Context.MODE_PRIVATE)
                .getFloat("task_interval_sec", 3f) * 1000).toLong()
            if (taskIntervalMs > 0) {
                delay(taskIntervalMs)
            }
            val req = LLMRequest(
                listOf(com.mcp.llm.ChatMessage(role = "tool", content = prompt)),
                null,
                LLMConfig(
                    sessionId = st.serverSessionId ?: sid,
                    parentMessageId = st.parentMessageId,
                    thinkingEnabled = st.lastThinkingEnabled,
                    searchEnabled = false
                )
            )
            // emitThinking=true：续聊轮的思考也要推给前端（前端按工具卡片之后线性渲染）
            streamLLM(req, emitThinking = true, onDone = { ev -> handleDone(ev, sid) }, sid = sid)
        }
    }

    // ───────────────────────── 语音闭环（录音 / 播放 / 转写） ─────────────────────────

    /** 前端点麦克风开始录音。已授权则立即开始；未授权则请求权限并以 pending 状态返回。 */
    @JavascriptInterface
    fun startRecording(): String {
        if (recording) return JSONObject().put("status", "recording").toString()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return doStartRecording()
        pendingStartRecording = true
        onRecordPermissionRequest?.invoke()
        return JSONObject().put("status", "pending").toString()
    }

    /** 由宿主 Fragment 在权限结果回调注入：授予后若有挂起的录音请求则自动开始。 */
    fun onRecordPermissionResult(granted: Boolean) {
        val shouldStart = pendingStartRecording
        pendingStartRecording = false
        if (!granted) {
            emit("recording_error", JSONObject().put("message", "麦克风权限被拒绝，无法录音"))
            return
        }
        if (!shouldStart) return
        val res = doStartRecording()
        val obj = runCatching { JSONObject(res) }.getOrElse { JSONObject() }
        if (obj.optString("status") == "recording") {
            emit("recording_started", JSONObject())
        } else {
            emit("recording_error", JSONObject().put("message", obj.optString("message", "录音启动失败")))
        }
    }

    /** 停止录音，返回落盘路径（m4a），供前端拿去转写。 */
    @JavascriptInterface
    fun stopRecording(): String {
        val recFile = recordingFile
        val rec = mediaRecorder
        if (rec == null && recFile == null) return JSONObject().put("status", "idle").toString()
        runCatching { rec?.stop() }
        runCatching { rec?.release() }
        mediaRecorder = null
        recordingFile = null
        recording = false
        if (recFile == null || !recFile.exists() || recFile.length() <= 0L) {
            return JSONObject().put("status", "error").put("message", "录音为空或未生成文件").toString()
        }
        return JSONObject()
            .put("status", "stopped")
            .put("path", recFile.absolutePath)
            .put("size", recFile.length())
            .put("format", "m4a")
            .toString()
    }

    /** 取消录音并删除中间文件。 */
    @JavascriptInterface
    fun cancelRecording(): String {
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        val f = recordingFile
        recordingFile = null
        recording = false
        runCatching { f?.delete() }
        return JSONObject().put("status", "cancelled").toString()
    }

    /** 真正开始录音（已确认权限）。失败会回退到未录音态并返回错误。 */
    private fun doStartRecording(): String {
        return try {
            // 防御：清理可能残存的旧实例
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            recordingFile = null

            val dir = File(context.filesDir, "recordings").apply { mkdirs() }
            val f = File(dir, "voice_${System.currentTimeMillis()}.m4a")
            val mr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioSamplingRate(44100)
            mr.setAudioEncodingBitRate(128000)
            mr.setAudioChannels(1)
            mr.setOutputFile(f.absolutePath)
            mr.prepare()
            mr.start()
            mediaRecorder = mr
            recordingFile = f
            recording = true
            JSONObject().put("status", "recording").toString()
        } catch (e: Exception) {
            LogStore.e("VOICE", "开始录音失败: ${e.message}")
            runCatching { mediaRecorder?.release() }
            mediaRecorder = null
            recordingFile = null
            recording = false
            JSONObject().put("status", "error").put("message", e.message ?: e.toString()).toString()
        }
    }

    /** 把录制好的本地音频转写为文字（复用 transcribe_audio 工具，仅 OpenAI 后端）。
     *  异步执行（可能耗时数秒~数十秒），结果经 "transcribed" 事件回传前端，避免阻塞 JS 线程。 */
    @JavascriptInterface
    fun transcribeAudio(path: String): String {
        if (path.isBlank()) return JSONObject().put("error", "缺少音频路径").toString()
        val argsJson = JSONObject().put("path", path).toString()
        toolScope.launch {
            val raw = runCatching { toolbox.dispatch("transcribe_audio", argsJson) }
                .getOrElse { e -> """{"error":"${e.message ?: e::class.simpleName}"}""" }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            val text = obj?.optString("text", "") ?: ""
            if (text.isNotBlank()) {
                emit("transcribed", JSONObject().put("text", text))
            } else {
                emit("transcribed", JSONObject().put("error", obj?.optString("error") ?: "转写结果为空"))
            }
        }
        return JSONObject().put("ok", true).toString()
    }

    /** 气泡底部「朗读」：把文本合成语音并播放。
     *  设置「使用系统文字转语音」开启时走 Android 系统 TTS（离线）；否则复用 text_to_speech 工具（OpenAI 端点）。
     *  异步执行（合成 + 播放），失败经 "tts_error" 事件回传前端，避免阻塞 JS 线程。 */
    @JavascriptInterface
    fun speak(text: String): String {
        if (text.isBlank()) return JSONObject().put("error", "文本为空").toString()
        if (auth.isAudioUseSystemTts()) {
            toolScope.launch { speakWithSystemTts(text) }
        } else {
            speakViaOpenAi(text)
        }
        return JSONObject().put("ok", true).toString()
    }

    /** 复用 text_to_speech 工具（OpenAI 兼容端点）合成并播放，失败经 tts_error 事件回传前端。 */
    private fun speakViaOpenAi(text: String) {
        val argsJson = JSONObject().put("text", text).toString()
        toolScope.launch {
            val raw = runCatching { toolbox.dispatch("text_to_speech", argsJson) }
                .getOrElse { e -> """{"error":"${e.message ?: e::class.simpleName}"}""" }
            val obj = runCatching { JSONObject(raw) }.getOrNull()
            val audioPath = obj?.optString("audio_path", "") ?: ""
            if (audioPath.isNotBlank()) {
                val playObj = runCatching {
                    playAudioInternal(audioPath) { emit("tts_done", JSONObject()) }
                }.getOrNull()
                if (playObj != null && playObj.optString("error").isNotBlank()) {
                    emit("tts_error", JSONObject().put("error", playObj.optString("error")))
                }
            } else {
                emit("tts_error", JSONObject().put("error", obj?.optString("error") ?: "语音合成失败"))
            }
        }
    }

    /** 用 Android 系统 TTS 朗读文本（主线程初始化 + 播放，不依赖任何后端端点）。
     *  初始化成功后先设置语言（中文优先）；引擎不可用时回退到 OpenAI 在线合成，避免朗读直接失败。 */
    private suspend fun speakWithSystemTts(text: String) {
        withContext(Dispatchers.Main) {
            val tts = systemTts
            if (tts != null && systemTtsReady) {
                startSystemTtsUtterance(tts, text)
                return@withContext
            }
            // 首次：主线程创建 TTS，onInit 成功后再设置语言并立即朗读
            val t = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    systemTtsReady = true
                    configureSystemTtsLanguage()
                    systemTts?.let { startSystemTtsUtterance(it, text) }
                } else {
                    systemTtsReady = false
                    emit("tts_notice", JSONObject().put("message", "系统文字转语音不可用（status=$status），已改用在线语音合成"))
                    speakViaOpenAi(text)
                }
            }
            attachSystemTtsListener(t)
            systemTts = t
        }
    }

    /** 生成 utteranceId 并朗读；记录到 currentTtsUtteranceId 以便过滤被打断的旧回调。 */
    private fun startSystemTtsUtterance(tts: TextToSpeech, text: String) {
        val uid = "bubble_${System.currentTimeMillis()}"
        currentTtsUtteranceId = uid
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
    }

    /** 监听系统 TTS 播放进度：正常结束/出错/被停止时回传 tts_done，让前端复位「朗读中」状态。 */
    private fun attachSystemTtsListener(tts: TextToSpeech) {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { emitTtsDoneIfCurrent(utteranceId) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { emitTtsDoneIfCurrent(utteranceId) }
            override fun onError(utteranceId: String?, errorCode: Int) { emitTtsDoneIfCurrent(utteranceId) }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { emitTtsDoneIfCurrent(utteranceId) }
        })
    }

    private fun emitTtsDoneIfCurrent(utteranceId: String?) {
        if (utteranceId == currentTtsUtteranceId) emit("tts_done", JSONObject())
    }

    /** 为系统 TTS 选择可用语言：中文优先，其次跟随系统默认，最后回退英文。 */
    private fun configureSystemTtsLanguage() {
        val tts = systemTts ?: return
        val candidates = listOf(
            Locale.SIMPLIFIED_CHINESE,
            Locale.CHINA,
            Locale.TRADITIONAL_CHINESE,
            Locale.getDefault(),
            Locale.US
        )
        for (loc in candidates) {
            val r = tts.setLanguage(loc)
            if (r == TextToSpeech.LANG_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE) {
                return
            }
        }
    }

    /** 播放本地音频（TTS 输出闭环），仅限应用私有目录，避免越界读取。 */
    @JavascriptInterface
    fun playAudio(path: String): String {
        return playAudioInternal(path, null).toString()
    }

    /** 播放本地音频，可选在自然播放结束时回调（TTS 朗读用于回传 tts_done）。 */
    private fun playAudioInternal(path: String, onComplete: (() -> Unit)?): JSONObject {
        val err = validateAudioPath(path)
        if (err != null) return JSONObject().put("error", err)
        return runCatching {
            stopAudioInternal()
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            if (onComplete != null) {
                mp.setOnCompletionListener { onComplete() }
            }
            mp.start()
            currentPlayer = mp
            JSONObject().put("ok", true).put("duration", mp.duration)
        }.getOrElse { e ->
            JSONObject().put("error", e.message ?: e.toString())
        }
    }

    /** 停止正在播放的音频；同时释放系统 TTS 引擎（不占用）。 */
    @JavascriptInterface
    fun stopAudio(): String {
        stopAudioInternal()
        stopSystemTts()
        return JSONObject().put("ok", true).toString()
    }

    /** 停止并关闭系统 TTS，置空实例，使下次朗读重新初始化，避免长期占用引擎。 */
    private fun stopSystemTts() {
        runCatching { systemTts?.stop() }
        runCatching { systemTts?.shutdown() }
        systemTts = null
        systemTtsReady = false
        currentTtsUtteranceId = null
    }

    private fun validateAudioPath(path: String): String? {
        if (path.isBlank()) return "音频路径为空"
        val f = File(path)
        if (!f.exists() || !f.isFile) return "音频文件不存在"
        val root = context.filesDir.canonicalPath.trimEnd('/')
        val target = f.canonicalPath
        return if (target != root && !target.startsWith("$root/")) "路径越界" else null
    }

    private fun stopAudioInternal() {
        runCatching {
            currentPlayer?.let { p ->
                if (p.isPlaying) p.stop()
                p.release()
            }
        }
        currentPlayer = null
    }

    // ───────────────────────── 附件上传（通用附件：图片/音频/文本/其他） ─────────────────────────

    /** 附件元数据（前端持有，发送时回传；仅元数据，内容发送时由原生重读）。 */
    private data class AttachmentMeta(val path: String, val name: String, val mime: String, val kind: String)

    /** 前端点「附件」按钮：请求宿主 Fragment 拉起系统文件选择器（多选）。 */
    @JavascriptInterface
    fun pickAttachments(): String {
        onPickAttachmentsRequest?.invoke()
        return JSONObject().put("status", "pending").toString()
    }

    /** 由宿主 Fragment 在文件选择结果回调注入：逐个处理选中 uri，回传元数据给前端。 */
    fun onAttachmentsPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        appScope.launch {
            val arr = JSONArray()
            for (uri in uris) {
                val meta = processAttachment(uri) ?: continue
                arr.put(meta)
            }
            if (arr.length() > 0) emit("attachments_picked", JSONObject().put("attachments", arr))
            else emit("attachment_error", JSONObject().put("message", "没有可用的附件"))
        }
    }

    /** 把选中的 content uri 拷贝到应用私有目录并生成附件元数据；小图附带 data URL 预览。 */
    private fun processAttachment(uri: Uri): JSONObject? {
        return try {
            val mime = context.contentResolver.getType(uri) ?: guessMimeByUri(uri)
            val name = queryDisplayName(uri) ?: "附件"
            val kind = classifyAttachment(mime, name)
            val dir = File(context.filesDir, "attachments").apply { mkdirs() }
            val out = File(dir, "${System.currentTimeMillis()}_${sanitizeFileName(name)}")
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return null
            if (bytes.isEmpty()) return null
            out.writeBytes(bytes)

            val o = JSONObject()
                .put("path", out.absolutePath)
                .put("name", name)
                .put("mime", mime)
                .put("kind", kind)
                .put("size", bytes.size)
            if (kind == "image" && bytes.size <= MAX_IMAGE_PREVIEW_BYTES) {
                o.put("preview", "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            o
        } catch (e: Exception) {
            LogStore.w("ATTACH", "处理附件失败: ${e.message}")
            null
        }
    }

    /** 发送带附件的消息：图片作为多模态 image_url、文本文件读取内容注入、音频转写后注入；
     *  其余二进制仅附占位说明。DeepSeek 逆向协议走 file/upload_file → ref_file_ids 通道，
     *  OpenAI 兼容协议走 image_url 数组通道。 */
    @JavascriptInterface
    fun sendMessageWithAttachments(prompt: String, thinking: Boolean, search: Boolean, attachmentsJson: String): String =
        sendMessageWithAttachmentsFor(currentSessionId, prompt, thinking, search, attachmentsJson)

    /** 指定会话发送带附件的消息（同 [sendMessageFor]：会话 id 入口捕获一次）。 */
    internal fun sendMessageWithAttachmentsFor(
        sid0: String?,
        prompt: String,
        thinking: Boolean,
        search: Boolean,
        attachmentsJson: String
    ): String {
        if (sid0.isNullOrEmpty()) return JSONObject().put("ok", false).put("error", "未选择会话").toString()
        appScope.launch {
            val attachments = parseAttachments(attachmentsJson)
            val imageUrls = mutableListOf<String>()
            val parts = mutableListOf<String>()   // 附件内容注入块（文本/音频转写）
            val imagePaths = mutableListOf<Pair<String, String>>()   // (path, name) 仅 DEEPSEEK 分支用
            val isDeepSeek = false

            for (att in attachments) {
                when (att.kind) {
                    "image" -> {
                        // 图片本身已作为真正的多模态输入送达模型（OpenAI: image_url；DeepSeek: ref_file_ids），
                        // 不再往 prompt 文本里插 [图片: xxx] 占位——那样会让模型在只有图片、
                        // 无用户文字时把占位符当成唯一可见内容并复述给用户。
                        fileToDataUrl(att.path, att.mime)?.let { imageUrls.add(it) }
                        if (isDeepSeek) imagePaths.add(att.path to att.name)
                    }
                    "audio" -> {
                        val text = transcribeAttachment(att.path)
                        if (text != null) parts += "\n\n【语音转写：${att.name}】\n$text"
                    }
                    "text" -> {
                        readTextAttachment(att.path)?.let {
                            if (it.isNotBlank()) parts += "\n\n【附件文件：${att.name}】\n$it"
                        }
                    }
                    else -> {
                        val text = readTextAttachment(att.path)
                        if (text != null && text.isNotBlank()) {
                            parts += "\n\n【附件文件：${att.name}】\n$text"
                        }
                    }
                }
            }

            val finalPrompt = buildString {
                append(prompt)
                if (parts.isNotEmpty()) append("\n" + parts.joinToString("\n"))
                // 图片本身通过 imageUrls / refFileIds 通道送达，模型已"看到"图像；
                // 此处**不要**再插入 [图片: xxx] 占位——模型会把占位符当成唯一可见内容，
                // 直接复述给用户（DeepSeek 实测：prompt="[图片: xxx]" 时回复就是 "[图片: xxx]"）。
                // 若 prompt 为空且无其它注入，给一个默认视觉指令兜底，避免 completion 拿到空 prompt。
                if (isBlank() && imagePaths.isNotEmpty()) append("描述这张图片的内容。")
            }.ifEmpty { prompt }

            // 图片通过 imageUrls 通道送达模型（OpenAI 兼容格式）。
            runCatching { innerSendMessage(sid0, finalPrompt, thinking, search, imageUrls, null) }
                .onFailure { e ->
                    LogStore.e("ATTACH", "带附件发送失败: ${e.message}")
                    emit("error", JSONObject().put("message", "发送失败: ${e.message ?: e::class.simpleName}"))
                }
        }
        return JSONObject().put("ok", true).toString()
    }

    private fun parseAttachments(json: String): List<AttachmentMeta> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val path = o.optString("path")
                    if (path.isBlank()) continue
                    add(AttachmentMeta(
                        path = path,
                        name = o.optString("name").ifBlank { "附件" },
                        mime = o.optString("mime"),
                        kind = o.optString("kind").ifBlank { "other" }
                    ))
                }
            }
        }.getOrElse { emptyList() }
    }

    private fun fileToDataUrl(path: String, mime: String): String? = runCatching {
        val f = File(path)
        if (!f.exists()) return@runCatching null
        val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
        "data:${mime.ifBlank { "image/jpeg" }};base64,$b64"
    }.getOrNull()

    private fun readTextAttachment(path: String): String? = runCatching {
        val f = File(path)
        if (!f.exists()) return@runCatching null
        val bytes = f.readBytes()
        if (bytes.size > MAX_TEXT_ATTACH_BYTES) {
            "（文件过大，超出 ${MAX_TEXT_ATTACH_BYTES / 1024}KB，未读取内容）"
        } else {
            bytes.toString(Charsets.UTF_8)
        }
    }.getOrNull()

    private suspend fun transcribeAttachment(path: String): String? = try {
        val raw = toolbox.dispatch("transcribe_audio", JSONObject().put("path", path).toString())
        val obj = JSONObject(raw)
        obj.optString("text", "").takeIf { it.isNotBlank() } ?: obj.optString("error")
    } catch (e: Exception) {
        null
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME)) else null
        }
    } catch (e: Exception) { null }

    private fun guessMimeByUri(uri: Uri): String {
        val ext = queryDisplayName(uri)?.substringAfterLast('.', "")?.lowercase() ?: ""
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(120).ifBlank { "file" }

    private fun classifyAttachment(mime: String, name: String): String = when {
        mime.startsWith("image/") -> "image"
        mime.startsWith("audio/") -> "audio"
        mime.startsWith("text/") -> "text"
        else -> when (name.substringAfterLast('.', "").lowercase()) {
            "md", "markdown", "txt", "json", "xml", "yaml", "yml", "csv", "log",
            "kt", "java", "py", "js", "ts", "tsx", "jsx", "html", "css", "sh",
            "properties", "gradle", "ini", "toml", "rst", "sql" -> "text"
            else -> "other"
        }
    }

    // ────────────── 预设（运行模式）切换 ──────────────

    /**
     * 列出所有可用预设（含默认 FULL）。前端顶栏下拉初始化时调用一次。
     * 返回 JSON 数组：`[{ id, name, description }]`。
     */
    @JavascriptInterface
    fun listPresets(): String {
        val arr = JSONArray()
        PresetManager.list().forEach { p ->
            arr.put(JSONObject()
                .put("id", p.id)
                .put("name", p.name)
                .put("description", p.description))
        }
        return arr.toString()
    }

    /** 当前生效的预设 id（无预设返回空串）。 */
    @JavascriptInterface
    fun getPreset(): String = PresetRuntime.current?.id ?: ""

    /**
     * 切换预设并立即生效（工具集 / 提示词 / 能力接缝一起更新）。
     * 传入 `""` 恢复完整模式。切换成功后返回 `{ok, id, name}`，失败返回 `{ok:false, error}`。
     *
     * 切换会重新注册工具；模型下一轮就能看到新工具集。UI 侧应提示用户「切换后生效」，
     * 避免历史会话消息里出现的工具被误认为现在仍可用。
     *
     * 同时把选择**持久化到当前会话**的 `presetId` 字段：切换会话时会由
     * [Tabs.openWindow] 读回并同步，避免「会话 A 切极简、切回会话 B 仍是极简」的跨会话泄漏。
     */
    @JavascriptInterface
    fun setPreset(presetId: String): String {
        return try {
            val id = presetId.trim().ifEmpty { null }
            val p = toolRuntime.setPreset(id)
            persistSessionPreset(id)  // 落盘到当前会话（失败不影响预设切换本身）
            JSONObject().put("ok", true)
                .put("id", p?.id ?: "")
                .put("name", p?.name ?: "完整")
                .toString()
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", e.message ?: e::class.simpleName).toString()
        }
    }

    /**
     * 把当前会话的 `preset_id` 字段落盘。失败只记日志，不影响预设切换本身。
     *
     * 实现走 LocalStore.loadSessions / saveSessions 全量更新，O(N) 但会话数通常小、
     * 且预设切换低频（每次调用一次），可接受。若要 O(1) 可参照 [saveSessionCurrentId]
     * 走 per-session 小文件，但会增加两处合并读取路径。
     */
    private fun persistSessionPreset(presetId: String?) {
        val sid = currentSessionId ?: return
        runCatching {
            val tag = LocalStore.backendTag(auth.getBackend())
            val list = LocalStore.loadSessions(context, tag)
            val idx = list.indexOfFirst { it.id == sid }
            if (idx < 0) {
                // 本地列表没有该会话时无法落盘（历史 bug：DeepSeek 新建会话未写本地列表，
                // 导致预设切换静默失效、徽章停在「完整」）。至少留痕，便于定位。
                LogStore.w("PRESET", "会话预设落盘跳过：本地无此会话 sid=$sid presetId=$presetId")
                return
            }
            val updated = list[idx].copy(presetId = presetId)
            val newList = list.toMutableList()
            newList[idx] = updated
            LocalStore.saveSessions(context, tag, newList)
        }.onFailure {
            LogStore.w("PRESET", "会话预设持久化失败 sid=$sid presetId=$presetId: ${it.message}")
        }
    }

    /**
     * 把 [Tabs.openWindow] 传入的会话 `presetId` 同步到 [PresetRuntime]。
     *
     * **不改写全局用户偏好**：走 [ToolRuntime.activatePreset]，只切换运行时（current / 工具集 /
     * 提示词 / 能力接缝），不写 [PresetPrefs]。否则打开任意会话都会覆盖用户偏好，新建会话随之回落。
     *
     * 幂等：若目标 id 与 [PresetRuntime.current] 一致则直接返回，避免
     * 每次切换会话都重扫工具箱、重扫技能脚本、切换持久 shell 接缝（[PresetRuntime.apply]
     * 是"清工具箱 + 重注册"的完整操作，成本较高）。
     *
     * 特殊处理：null 与 "full" 语义等价（都是全量模式），但 [PresetRuntime.current?.id]
     * 可能是 null（未选）或 "full"（显式选 FULL 预设）。这里做短路判断，不做归一化——
     * 若两者语义等价但字面不等（如 current=FULL, target=null），仍会走一次 apply()
     * 把 current 归零。这是正确行为（下一轮 prompt 用同一语义），只是多一次开销。
     *
     * 失败静默：预设切换失败不应阻塞会话打开，用户会看到当前 preset 仍生效。
     */
    fun applySessionPreset(sid: String?, presetId: String?) {
        val target = presetId?.trim()?.takeIf { it.isNotEmpty() }
        // 会话级记录：PTC 折叠 / SDK 段 / 直调门控都按它派生，避免进程级全局状态跨会话串味
        if (sid != null) stateFor(sid).presetId = target
        if (PresetRuntime.current == null && target == null) return
        if (PresetRuntime.current?.id == target) return
        // 用 activatePreset 而非 setPreset：后者会把该会话的预设写进全局用户偏好，
        // 于是「打开一个完整模式旧会话」就会覆盖用户选的极简偏好，新建会话随之回落完整。
        runCatching { toolRuntime.activatePreset(target) }
            .onFailure { LogStore.w("PRESET", "会话预设同步失败 presetId=$target: ${it.message}") }
    }

    /**
     * 指定会话是否处于 PTC 折叠模式：按该会话的 presetId 判定（回退当前生效预设，兼容旧会话）。
     */
        /**
     * 当前会话应使用的 PTC 协议风格（PTC 下的 run_code 入口语法）。
     *
     * 来源：PresetPromptOverride.protocolStyle（已由 PresetRuntime 按
     * 「受限预设锁 XML / 用户偏好 / 预设声明」三级规则解析完毕）。
     * 非 PTC 会话下返回值无意义（不会被消费）。
     */
    private fun currentPtcStyle(sid: String?): com.mcp.core.prompt.PtcProtocolStyle =
        if (protocolStyleXmlFor(sid)) com.mcp.core.prompt.PtcProtocolStyle.XML
        else com.mcp.core.prompt.PtcProtocolStyle.LINE

    /**
     * 会话级协议风格解析（XML=true）。
     *
     * 首次访问该会话时从全局 [PresetPromptOverride.protocolStyle] 播种——
     * 全局值由 PresetRuntime.applyPromptOverride 按「受限预设锁 XML / 用户偏好 / 预设声明」
     * 三级规则算出，作为**该会话建立时**的初始风格是正确口径（此后与全局解耦）。
     * 之后不再回读全局：否则用户切到别的会话会改写全局值，本会话的风格被静默翻转。
     *
     * @param sid 会话 id；null 时直接回退全局（无会话上下文的一次性调用）。
     */
    private fun protocolStyleXmlFor(sid: String?): Boolean {
        if (sid == null) return PresetPromptOverride.protocolStyle == PresetPromptOverride.ProtocolStyle.XML
        val st = stateFor(sid)
        if (!st.protocolStyleResolved) {
            st.protocolStyleXml = PresetPromptOverride.protocolStyle == PresetPromptOverride.ProtocolStyle.XML
            st.protocolStyleResolved = true
        }
        return st.protocolStyleXml
    }

    /**
     * 用户**显式切换**协议风格时，把新风格广播给所有已建立的会话。
     *
     * 设置页切换后 PresetRuntime.apply 会重算全局值，但已建立的会话因「解析一次后解耦」
     * 不会自动跟随。用户改的是全局偏好，语义上应当对所有会话生效（含正在跑的），
     * 故在此强制刷新各会话的缓存值。
     */
    internal fun refreshProtocolStyleForAllSessions() {
        val xml = PresetPromptOverride.protocolStyle == PresetPromptOverride.ProtocolStyle.XML
        for (st in sessionStates.values) {
            st.protocolStyleXml = xml
            st.protocolStyleResolved = true
        }
    }

private fun ptcActive(sid: String?): Boolean =
        PresetRuntime.isPtc(state(sid)?.presetId ?: currentSessionId?.let { state(it)?.presetId })

    /**
     * 指定会话是否允许**常规**上下文压缩：按该会话的 presetId 判定（判据同 [ptcActive]）。
     *
     * `compaction:false` 的预设（极简）不压；溢出自救与 DeepSeek 前置压缩（force 路径）不经此判定。
     */
    private fun compactionEnabled(sid: String?): Boolean =
        PresetRuntime.isCompactionEnabled(state(sid)?.presetId ?: currentSessionId?.let { state(it)?.presetId })

    /** 把本实例所有会话标记为「工具拓扑已变」，下轮 DeepSeek 发送时重注入。 */
    internal fun markAllSessionsToolTopologyDirty() {
        // sessionStates 是 ConcurrentHashMap，弱一致遍历即可；每个 flag 是 @Volatile。
        for (st in sessionStates.values) st.toolTopologyDirty = true
        LogStore.d("MCP", "已标记全部会话工具拓扑变更，下轮消息将重注入工具清单")
    }

    /** 清理协程，在 Fragment onDestroyView 时调用。 */
    fun destroy() {
        instances.remove(this)
        removeStreamListener(foreignStreamListener)
        // 反注册 PTC ask_user 处理器：本实例销毁后不再接收子调用弹窗（其他实例会重新注册）。
        com.mcp.ptc.PtcAskUserBridge.unregister(ptcAskUserHandler)
        runCatching { mediaRecorder?.stop() }
        runCatching { mediaRecorder?.release() }
        mediaRecorder = null
        recording = false
        runCatching { recordingFile?.delete() }
        recordingFile = null
        stopAudioInternal()
        stopSystemTts()
        scope.cancel()
        toolScope.cancel()
        toolRuntime.destroy()
    }
}
