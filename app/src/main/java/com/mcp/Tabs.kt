package com.mcp

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.JsResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.mcp.deepseek.AuthPrefs
import com.mcp.core.llm.BackendType
import com.mcp.preset.PresetManager
import com.mcp.preset.PresetRuntime
import com.mcp.core.chat.ChatSession
import com.mcp.core.chat.ChatMessage
import com.mcp.core.chat.ToolCallData
import com.mcp.data.LocalStore
import com.mcp.serialization.McpJson
import com.mcp.toolbox.Toolbox
import kotlin.concurrent.thread
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Context
import androidx.lifecycle.lifecycleScope
import com.mcp.browser.WebBrowser
import kotlinx.coroutines.launch

/**
 * Web 自动化本地会话的 id 前缀：`wa-<uuid>`。
 *
 * 历史教训：曾用 `web:<网页列表序号>` 当本地 id，而站点侧边栏会按活跃度重排，
 * 下标一漂移就把历史写进别的会话文件。现在 id 用**稳定 UUID**，与网页会话的绑定
 * 改走 [com.mcp.core.chat.ChatSession.siteKey]（站点稳定标识，回退会话名）。
 */
const val WA_SESSION_PREFIX = "wa-"

/**
 * 会话页：基于 DeepSeek 逆向协议（deepseek-android-reverse）的客户端外壳。
 * 三态：登录 -> 会话列表 -> 会话窗口。
 * 聊天窗口由 WebView（chat.html）作为主力，通过 ChatBridge 与原生层通信。
 */
class SessionFragment : Fragment(R.layout.fragment_session) {

    private lateinit var auth: AuthPrefs
    private val sessions = mutableListOf<ChatSession>()
    private lateinit var adapter: SessionAdapter

    // 三态容器
    private lateinit var loginCard: View
    private lateinit var listLayout: View
    private lateinit var windowLayout: View

    // 聊天窗口：每会话一个 WebView 的池。打开过的会话常驻内存，切换只换入换出视图，
    // 不再整页重载 chat.html —— 销毁重建 JS 上下文正是切换出视图 bug 的根源。
    // 池有上限 [MAX_WEBVIEWS]，LRU 淘汰最久未用的闲置会话（有活跃流的不淘汰）。
    private lateinit var webContainer: ViewGroup
    private var chatBridge: ChatBridge? = null
    private val webViewPool = LinkedHashMap<String, WebView>(8, 0.75f, true)

    /** 待 onPageFinished 注入历史的挂起参数：sid -> (session, isNew)。 */
    private val pendingLoads = HashMap<String, Pair<ChatSession, Boolean>>()

    /** 页面已就绪（chat.html 加载完成、可接收 onHistory/onAnchor）的会话集合。 */
    private val loadedSids = HashSet<String>()

    /** 当前挂在容器里的前台 WebView。 */
    private var activeWebView: WebView? = null

    /** 隐藏期间发生过流更新/完成的会话（页面已错过事件），回前台时需确定性重建。 */
    private val staleSids: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // 录音权限（语音输入闭环）：懒申请，授权后回调 ChatBridge 继续开始录音
    private val recordPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> chatBridge?.onRecordPermissionResult(granted) }

    // 附件选择（通用附件上传）：多选任意文件，结果交给 ChatBridge 处理并回传前端
    private val attachmentPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> chatBridge?.onAttachmentsPicked(uris) }
    private var currentSessionId: String? = null

    /** 当前会话页面是否已就绪（池中存在且 chat.html 已加载完成）。 */
    private fun webLoaded(sid: String?): Boolean = sid != null && loadedSids.contains(sid)

    /** 打开/新建会话后待发送的提示词：页面未就绪时排队，锚点就绪后随对应会话 flush。 */
    private var pendingPrompt: String? = null
    private var pendingPromptSessionId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        android.util.Log.i("MCP_LIFE", "SessionFragment.onViewCreated pid=${android.os.Process.myPid()} savedInstanceState=${savedInstanceState != null}", Throwable("MCP_LIFE"))
        auth = AuthPrefs(requireContext())

        loginCard = view.findViewById(R.id.loginCard)
        listLayout = view.findViewById(R.id.listLayout)
        windowLayout = view.findViewById(R.id.windowLayout)

        setupList(view)
        setupWindow(view)

        // 主题切换会触发 Activity 重建：若此前正处于「会话窗口」，重建后自动恢复该会话
        val restore = savedInstanceState?.let { b ->
            if (b.getBoolean("inWindow", false) && !b.getString("sessionId").isNullOrEmpty()) {
                b.getString("sessionId")!! to (b.getString("sessionTitle") ?: "")
            } else null
        }
        if (restore != null) {
            openWindow(ChatSession(restore.first, restore.second, false, 0.0), isNew = false)
        } else if (canEnterSessionList()) {
            showList()
        } else {
            showLogin()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("inWindow", windowLayout.visibility == View.VISIBLE)
        outState.putString("sessionId", currentSessionId)
        outState.putString(
            "sessionTitle",
            windowLayout.findViewById<TextView>(R.id.tvWindowTitle).text?.toString() ?: ""
        )
    }

    /**
     * Tab 切换是 show/hide（不重建、不触发 onResume），切回会话页在这里自动刷新网页会话列表：
     * 先读一次；读不到（浮窗没开/页面还没加载完）→ 弹出浮窗、等页面**彻底加载完成**再补读一次。
     * 成功才覆盖列表（失败静默只记日志），避免每次切页都打扰。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden || !isAdded || !this::auth.isInitialized) return
        if (auth.getBackend() != BackendType.WEB_AUTOMATION) return
        if (listLayout.visibility != View.VISIBLE) return
        val selector = auth.getWebAutomationConfig().sessionListSelector
        if (selector.isBlank()) return
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            var res = readWebSessions(selector)
            if (res.error != null || res.items.isEmpty()) {
                (activity as? MainActivity)?.showAutomationWindow()
                WebBrowser.awaitAutomationPageLoaded(timeoutMs = 15_000)
                kotlinx.coroutines.delay(1000)   // SPA 侧边栏渲染晚于 onPageFinished，留个稳定窗口
                res = readWebSessions(selector)
            }
            if (!isAdded || res.error != null || res.items.isEmpty()) return@launch
            val web = mergeWebSessions(res.items)
            LocalStore.saveSessions(ctx, LocalStore.backendTag(auth.getBackend()), web)
            applySessionList(web)
        }
    }

    // ───────────────────────── 列表态 ─────────────────────────
    private fun setupList(root: View) {
        val rv = root.findViewById<RecyclerView>(R.id.rvSessions)
        val fab = root.findViewById<FloatingActionButton>(R.id.fabNew)
        val btnRefresh = root.findViewById<MaterialButton>(R.id.btnRefresh)
        val toolbar = root.findViewById<MaterialToolbar>(R.id.listToolbar)
        toolbar.setTitle("会话")

        adapter = SessionAdapter(sessions, onClick = { s ->
            // Web 自动化的会话来自网页：点它 = 进本地聊天窗口（气泡展示/发送/回复获取都在窗口里），
            // 同时在后台把站点切到那条会话——先开窗口不等人，切站点结果用 Toast 回执
            if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
                openWindow(s, isNew = false)
                switchToWebSession(s)
            } else {
                openWindow(s, isNew = false)
            }
        }, onLongPress = { showSessionActions(it) })
        rv.adapter = adapter

        fab.setOnClickListener { createNewSession() }
        btnRefresh.setOnClickListener { loadSessions() }
    }

    /** 会话长按菜单：重命名 / 置顶/取消置顶 / 删除（仅 OpenAI 本地会话可管理；DeepSeek 远端会话不支持本地侧操作）。 */
    private fun showSessionActions(session: ChatSession) {
        // Web 自动化：会话在网页那边，本地只是记录 → 改名/置顶/删除都不该在本地做
        if (auth.getBackend() == BackendType.WEB_AUTOMATION) {
            Toast.makeText(
                requireContext(),
                "网页会话请到站点里管理（本地只是记录）",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        // 仅 OpenAI 后端支持本地会话管理，DeepSeek 后端长按无操作

        val ctx = requireContext()
        val tag = LocalStore.backendTag(auth.getBackend())
        val pinText = if (session.pinned) "取消置顶" else "置顶"
        // 统一走 MaterialDialogs.choose：样式与其余弹窗一致，
        // 且「删除」项标红提示后果（下标 2）。
        MaterialDialogs.choose(
            context = ctx,
            title = if (session.title.isNotEmpty()) session.title else "会话操作",
            items = listOf("重命名", pinText, "删除"),
            destructiveIndexes = setOf(2),
        ) { which ->
            when (which) {
                0 -> showRenameDialog(session, tag)
                1 -> {
                    LocalStore.toggleSessionPinned(ctx, tag, session.id)
                    loadSessions()
                }
                2 -> confirmDeleteSession(session, tag)
            }
        }
    }

    private fun showRenameDialog(session: ChatSession, tag: String) {
        val ctx = requireContext()
        MaterialDialogs.prompt(
            context = ctx,
            title = "重命名会话",
            hint = "会话标题",
            value = session.title,
            confirmText = "保存",
        ) { title ->
            LocalStore.renameSession(ctx, tag, session.id, title)
            loadSessions()
        }
    }

    private fun confirmDeleteSession(session: ChatSession, tag: String) {
        val ctx = requireContext()
        MaterialDialogs.confirmDestructive(
            context = ctx,
            title = "删除会话",
            message = "确定删除「${if (session.title.isNotEmpty()) session.title else session.id}」？此操作不可恢复。",
            confirmText = "删除",
        ) {
                LocalStore.deleteSession(ctx, tag, session.id)
                // T4：同步清理该会话的 spill 落盘目录，避免大文件无限堆积。
                com.mcp.compaction.ToolResultSpill.clearSession(ctx, session.id)
                // 内存态同样要清：否则仍在该会话上跑的流会把消息写回已删除的日志（幽灵会话），
                // 池化的 WebView 也会一直占着内存。
                chatBridge?.forgetSession(session.id)
                staleSids.remove(session.id)
                if (webViewPool.containsKey(session.id)) teardownWebView(session.id)
                // 若删除的是当前活跃会话，关闭窗口回到列表
                if (session.id == currentSessionId) {
                    currentSessionId = null
                    chatBridge?.currentSessionId = null
                    showList()
                }
            loadSessions()
        }
    }

    /**
     * Web 自动化：从**网页**读会话列表（源头是站点自己的侧边栏），本地只落一份记录供离线展示。
     *
     * 只在初次打开会话页时读一次（刷新按钮可手动重读）：先秒出本地记录，
     * 若网页读不到则把自动化浮窗弹出来，**等页面彻底加载完成**（onPageFinished 信号）
     * 再自动刷新一次——不再固定间隔盲轮询，读早了列表不完整，读晚了干等。
     * 仍失败则保留本地记录并把原因明确说出来（没开浮窗 / 未登录 / 选择器失配）。
     */
    private fun loadWebAutomationSessions(ctx: Context, tag: String) {
        val selector = auth.getWebAutomationConfig().sessionListSelector
        // 先秒出本地记录（本地只是记录），再去网页取最新列表覆盖它——
        // 这样"网页还没加载好"时列表也不是空的。
        applySessionList(LocalStore.loadSessions(ctx, tag))
        viewLifecycleOwner.lifecycleScope.launch {
            var res = readWebSessions(selector)
            if (res.error != null || res.items.isEmpty()) {
                // 初次打开：确保浮窗弹出让站点开始加载，等它**彻底加载完**再读一次（仅此一次）
                (activity as? MainActivity)?.showAutomationWindow()
                WebBrowser.awaitAutomationPageLoaded(timeoutMs = 15_000)
                kotlinx.coroutines.delay(1000)   // SPA 侧边栏渲染通常晚于 onPageFinished，留个稳定窗口
                res = readWebSessions(selector)
            }
            if (!isAdded) return@launch
            if (res.error != null || res.items.isEmpty()) {
                val why = res.error ?: "网页里没有读到会话（可能未登录，或选择器指向的容器里没有条目）"
                if (sessions.isEmpty()) {
                    listLayout.findViewById<TextView>(R.id.tvListEmpty).text = "读不到网页会话：$why"
                } else {
                    Toast.makeText(ctx, "网页会话读取失败（当前显示本地记录）：$why", Toast.LENGTH_LONG).show()
                }
                LogStore.w("NET", "网页会话列表读取失败：$why")
                return@launch
            }
            val web = mergeWebSessions(res.items)
            LocalStore.saveSessions(ctx, tag, web)   // 本地只作记录
            applySessionList(web)
            LogStore.i("NET", "网页会话列表：${web.size} 条，当前=${res.items.firstOrNull { it.active }?.title.orEmpty()}")
        }
    }

    /**
     * 网页条目 → 本地会话列表：**按稳定绑定键合并**，不再用网页下标当本地 id。
     *
     * 旧实现把网页列表下标直接当本地 id（web:0/1/2…），而站点侧边栏会按活跃度重排——
     * 下标一漂移，历史就写进别的会话文件，记录全乱。现在：
     *  - 本地 id 用稳定 UUID（`wa-<uuid>`），只在首次见到该站点会话时分配，之后永不改；
     *  - 绑定键 siteKey 优先站点稳定标识（data-id / href 里的 cid），无则回退会话名；
     *  - 已绑定但网页里消失的会话 → 视为站点已删除，不再保留；
     *  - 本地新建、站点尚未命名的会话（siteKey 为空）→ 与未匹配网页条目对号入座（优先当前会话），
     *    站点出名字后即完成回填。
     */
    private fun mergeWebSessions(items: List<WebBrowser.WebSessionItem>): List<ChatSession> {
        val existing = sessions.toList()
        val consumedLocal = HashSet<String>()
        val result = mutableListOf<ChatSession>()
        val unmatched = mutableListOf<WebBrowser.WebSessionItem>()

        // 第一轮：按 siteKey 匹配已有本地会话，保留其本地 id
        for (item in items) {
            val key = item.siteKey.ifEmpty { item.title }
            val match = existing.firstOrNull {
                it.id !in consumedLocal && !it.siteKey.isNullOrEmpty() && key.isNotEmpty() && it.siteKey == key
            }
            if (match != null) {
                consumedLocal.add(match.id)
                result.add(match.copy(title = item.title.ifEmpty { match.title }, pinned = item.active, siteKey = key))
            } else {
                unmatched.add(item)
            }
        }

        // 第二轮：本地新建（siteKey 为空）的会话与未匹配网页条目对号入座
        existing.filter { it.siteKey.isNullOrEmpty() && it.id !in consumedLocal }.forEach { local ->
            val item = unmatched.firstOrNull { it.active } ?: unmatched.firstOrNull()
            consumedLocal.add(local.id)
            if (item != null) {
                unmatched.remove(item)
                val key = item.siteKey.ifEmpty { item.title }
                result.add(local.copy(title = item.title.ifEmpty { local.title }, pinned = item.active, siteKey = key))
            } else {
                // 站点还没出现这条（刚新建、首条消息未发出）：保留，等待回填
                result.add(local)
            }
        }

        // 第三轮：仍未匹配的网页条目 → 分配新的稳定本地 id
        for (item in unmatched) {
            result.add(ChatSession(
                id = WA_SESSION_PREFIX + java.util.UUID.randomUUID(),
                title = item.title,
                pinned = item.active,
                siteKey = item.siteKey.ifEmpty { item.title },
            ))
        }
        return result
    }

    /** 读一次网页会话列表（异常也转成带原因的结果，调用方只关心 error/items）。 */
    private suspend fun readWebSessions(selector: String): WebBrowser.WebSessionList =
        runCatching { WebBrowser.readAutomationSessions(selector) }
            .getOrElse { WebBrowser.WebSessionList(emptyList(), it.message ?: it.javaClass.simpleName) }

    /** 把一份会话列表灌进会话页（网页来源与本地来源共用）。 */
    private fun applySessionList(list: List<ChatSession>) {
        sessions.clear()
        sessions.addAll(list.sortedWith(
            compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt }
        ))
        adapter.notifyDataSetChanged()
        val emptyView = listLayout.findViewById<TextView>(R.id.tvListEmpty)
        emptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        if (sessions.isEmpty()) emptyView.text = getString(R.string.session_list_empty)
    }

    /**
     * Web 自动化：把站点切到全新会话（点「新建对话」按钮；没配按钮则回站点首页）。
     * 由「新建会话」触发并立即生效——驱动发消息时不再做任何会话准备。
     */
    private fun prepareSiteNewChat(ctx: Context) {
        val cfg = auth.getWebAutomationConfig()
        viewLifecycleOwner.lifecycleScope.launch {
            (activity as? MainActivity)?.showAutomationWindow()
            WebBrowser.awaitAutomationPageLoaded(timeoutMs = 10_000)
            val msg = when {
                cfg.newChatSelector.isNotBlank() -> {
                    val clicked = runCatching { WebBrowser.clickAutomation(cfg.newChatSelector) }
                        .getOrElse { "点击新建失败：${it.message ?: it.javaClass.simpleName}" }
                    if (clicked == null || clicked.contains("\"error\"")) {
                        "新建按钮点击失败（选择器：${cfg.newChatSelector}），可在设置里校正"
                    } else "已在站点打开新会话"
                }
                cfg.siteUrl.isNotBlank() ->
                    runCatching { WebBrowser.navigateAutomation(cfg.siteUrl) }
                        .getOrElse { "导航失败：${it.message ?: it.javaClass.simpleName}" }
                else -> "未配置「新建对话选择器」和站点地址，无法在站点新建会话"
            }
            if (isAdded) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            LogStore.i("NET", "新建会话 → 站点新建：$msg")
        }
    }

    /** 点网页会话：弹出自动化浮窗（不 toggle）并把站点切过去，成功后把最新的「当前会话」刷回列表。 */
    private fun switchToWebSession(session: ChatSession) {
        val ctx = context ?: return
        val selector = auth.getWebAutomationConfig().sessionListSelector
        if (selector.isBlank()) {
            Toast.makeText(ctx, "未配置「会话列表（选择器）」，无法切换网页会话", Toast.LENGTH_LONG).show()
            return
        }
        val siteKey = session.siteKey.orEmpty()
        // 站点尚未给这条会话命名（新建后首条消息还没发出）：它本来就是站点当前会话，无需切换。
        if (siteKey.isBlank()) {
            LogStore.i("NET", "网页会话尚未命名（新建待回填），不切换站点：${session.title}")
            return
        }
        // 乐观更新：先把「当前会话」标记挪到点中的条目，不等网页回读（ChatSession 不可变，整表重建）
        applySessionList(sessions.map { it.copy(pinned = (it.id == session.id)) })
        (activity as? MainActivity)?.showAutomationWindow()
        viewLifecycleOwner.lifecycleScope.launch {
            // 刚弹出浮窗时站点可能还在加载（选择器没渲染出来），读不到就重试几次
            var msg = ""
            for (attempt in 0 until 3) {
                if (attempt > 0) kotlinx.coroutines.delay(1500)
                // 按稳定 siteKey 定位；命中不了就报错不点（宁可不切也不切错会话）
                msg = runCatching { WebBrowser.switchAutomationSession(selector, siteKey = siteKey, title = session.title) }
                    .getOrElse { "切换失败：${it.message ?: it.javaClass.simpleName}" }
                val transient = msg.contains("超时") || msg.contains("找不到该选择器") || msg.startsWith("切换失败")
                if (!transient) break
            }
            if (isAdded) Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            LogStore.i("NET", "切换网页会话 key=$siteKey → $msg")
            if (!msg.startsWith("已切换") || !isAdded) return@launch
            // 切换成功：等站点渲染出新的选中态后重读网页列表，刷新「当前会话」/顺序（本地只作记录）
            kotlinx.coroutines.delay(1200)
            val fresh = readWebSessions(selector)
            if (!isAdded || fresh.error != null || fresh.items.isEmpty()) return@launch
            val web = mergeWebSessions(fresh.items)
            LocalStore.saveSessions(ctx, LocalStore.backendTag(auth.getBackend()), web)
            applySessionList(web)
        }
    }

    private fun loadSessions() {
        val ctx = requireContext()   // 主线程、已 attached 时捕获，供后台/UI 复用
        val backend = auth.getBackend()
        val tag = LocalStore.backendTag(backend)

        // Web 自动化：会话列表以**网页**为准（本地 wa_sessions.json 退化为记录/离线缓存）。
        if (backend == BackendType.WEB_AUTOMATION) {
            loadWebAutomationSessions(ctx, tag)
            return
        }

        // OpenAI 兼容协议：无远端会话列表概念，纯本地管理，不调用任何远端会话 API。
        run {
            val local = LocalStore.loadSessions(ctx, tag)
            // 一次性认领：历史上非 DeepSeek 后端的本地会话一律被写进 OpenAI 命名空间
            // （oa_sessions.json），而列表按当前后端的命名空间读 → Web 自动化会话"列表里看不见"。
            // 判据很硬：该会话在**当前**命名空间里确实有消息文件才认领，不会把 OpenAI 的会话误收进来。
            val openAiTag = LocalStore.backendTag(BackendType.OPENAI)
            val adopted = if (tag != openAiTag) {
                val known = local.map { it.id }.toSet()
                LocalStore.loadSessions(ctx, openAiTag)
                    .filter { it.id !in known && LocalStore.messageMtime(ctx, tag, it.id) > 0.0 }
            } else emptyList()
            if (adopted.isNotEmpty()) {
                LogStore.i("NET", "认领 ${adopted.size} 个误存在 OpenAI 命名空间的本后端会话 → $tag")
            }
            // 再兜一层：命名空间里有消息日志、但列表条目缺失的会话（历史 bug 会漏写/覆盖列表文件）。
            // 标题取该会话第一条用户消息，与"新建会话后自动命名"的口径一致。
            val recovered = LocalStore.sessionIdsWithMessages(ctx, tag)
                .filter { id -> (local + adopted).none { it.id == id } }
                .take(8)   // 兜底逻辑，限制单次找回条数，避免在列表加载路径上读太多历史
                .map { id ->
                    val firstUser = LocalStore.loadMessages(ctx, tag, id)
                        .firstOrNull { it.isUser && !it.content.isNullOrBlank() }
                        ?.content?.take(20)?.replace("\n", " ")?.trim()
                        .orEmpty()
                    ChatSession(id, firstUser, false, LocalStore.messageMtime(ctx, tag, id))
                }
            if (recovered.isNotEmpty()) {
                LogStore.i("NET", "从消息日志找回 ${recovered.size} 个缺失列表条目的会话 → $tag")
            }
            // 历史遗留会话 updatedAt 可能为 0（列表显示「未知」）：回退到本地消息文件 mtime，
            // 并回写一次持久化，后续加载直接走 updatedAt。
            val merged = (local + adopted + recovered).map { s ->
                if (s.updatedAt <= 0.0) {
                    val mtime = LocalStore.messageMtime(ctx, tag, s.id)
                    if (mtime > 0.0) s.copy(updatedAt = mtime) else s
                } else s
            }
            LocalStore.saveSessions(ctx, tag, merged)
            sessions.clear()
            sessions.addAll(merged.sortedWith(
                compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt }
            ))
            adapter.notifyDataSetChanged()
            val emptyView = listLayout.findViewById<TextView>(R.id.tvListEmpty)
            emptyView.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
            if (sessions.isEmpty()) emptyView.text = getString(R.string.session_list_empty)
            LogStore.i("NET", "本地会话列表加载 backend=$tag 共 ${sessions.size} 条")
            return
        }

    }

    /**
     * 由设置页切换 LLM 后端时调用：清空当前会话（避免旧后端 sessionId 串到新后端），
     * 并按新后端重新渲染会话列表（DeepSeek 逆向拉远端、OpenAI 读本地 oa_ 列表）。
     */
    internal fun reloadSessionsForBackend() {
        if (!isAdded) return
        currentSessionId = null
        chatBridge?.currentSessionId = null
        chatBridge?.clearAllSessionStates()
        // 旧后端的活跃 SSE 流若仍在跑，其 Job 与累积内容会常驻全局 StreamTaskManager：
        // 切后端等同放弃旧会话，一并取消并清空，避免资源与内容泄漏。
        StreamTaskManager.clearAll()
        // 旧后端的会话页面全部作废：清空 WebView 池，避免池命中复用旧上下文
        for (sid in webViewPool.keys.toList()) teardownWebView(sid)
        loadSessions()
    }

    private fun createNewSession() {
        val ctx = requireContext()   // 主线程捕获，与 loadSessions 保持一致风格

        // 非 DeepSeek 后端（OpenAI 兼容 / Web 自动化）都是"本地会话"：id 前缀与命名空间
        // 都跟随**当前后端**。
        // 旧实现一律写进 OpenAI 命名空间（oa_sessions.json），而会话列表按当前后端命名空间读，
        // 于是 Web 自动化下"新建会话后列表还是空的"——会话被存到了 oa 里，wa 里什么都没有。
        val tag = LocalStore.backendTag(auth.getBackend())
        val id = "$tag-${java.util.UUID.randomUUID()}"
        val session = ChatSession(id, "", false, 0.0, model = auth.getOpenAIModel(), contextWindow = auth.getOpenAIContextWindow(), maxInput = auth.getOpenAIMaxInput(), presetId = PresetRuntime.userPreferredId(ctx))
        LogStore.i("NET", "新建本地会话 backend=$tag id=$id")
        sessions.add(session)
        LocalStore.saveSessions(requireContext(), tag, sessions)
        openWindow(session, isNew = true)
        // Web 自动化：新建会话要**立即**在站点上新开一段对话（点「新建对话」按钮；
        // 没配按钮则回站点首页——多数站点根地址即新会话态）。会话定位归会话层，
        // 驱动发消息不再碰会话，否则第一条消息会发进站点当前停着的旧会话里。
        if (auth.getBackend() == BackendType.WEB_AUTOMATION) prepareSiteNewChat(ctx)
    }

    // ───────────────────────── 窗口态（聊天） ─────────────────────────
    private fun setupWindow(root: View) {
        val toolbar = root.findViewById<MaterialToolbar>(R.id.windowToolbar)
        toolbar.setNavigationOnClickListener { showList() }
        // 内嵌浏览器入口（会话名称右侧）：点击进入浏览器视图（底栏切回）；长按弹成可拖拽浮窗
        root.findViewById<MaterialButton>(R.id.btnBrowser).apply {
            setOnClickListener {
                (activity as? MainActivity)?.switchToTab(MainActivity.BROWSER_TAB)
            }
            setOnLongClickListener {
                (activity as? MainActivity)?.popOutBrowser()
                true
            }
        }
        webContainer = root.findViewById(R.id.webContainer)
        setupWebView()
    }

    private fun setupWebView() {
        // 视图重建（旋转 / 主题切换 / 切页签导致的视图销毁）时**复用已有 ChatBridge**：
        // 池里的会话 WebView 是在它身上通过 addJavascriptInterface 注入的 JS 桥，
        // 换新实例会让老页面调不到原生能力（发送/停止/附件全部失效）。
        if (chatBridge == null) chatBridge = ChatBridge(auth, requireContext().applicationContext)

        // 录音权限懒申请：ChatBridge 在无权时回调此入口，交还结果后自动继续录音
        chatBridge?.onRecordPermissionRequest = {
            val ok = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (ok) {
                chatBridge?.onRecordPermissionResult(true)
            } else {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }

        // 附件选择入口：拉起系统文件选择器（通用附件上传）
        chatBridge?.onPickAttachmentsRequest = {
            attachmentPickerLauncher.launch(arrayOf("*/*"))
        }
        // 后台会话有流更新/完成 → 标记脏（其 WebView 页面已错过事件，回前台时需重建）
        chatBridge?.onOffstageStreamEvent = { sid -> staleSids.add(sid) }
        // 流开始/结束 → 刷新会话列表，让「生成中」动画随状态显隐
        chatBridge?.onStreamActivityChange = { _, _ -> runOnUi { refreshSessionList() } }

        // T5：fork 完成后切换到新分支会话（fork 仅 OpenAI 后端，落盘 tag 恒为 OPENAI）
        chatBridge?.onForkedSession = { sid ->
            val tag = LocalStore.backendTag(BackendType.OPENAI)
            val session = LocalStore.loadSessions(requireContext(), tag).firstOrNull { it.id == sid }
            if (session != null) {
                if (sessions.none { it.id == sid }) sessions.add(session)
                runOnUi { openWindow(session, isNew = false) }
            } else {
                LogStore.w("FORK", "fork 回调找不到会话 $sid")
            }
        }

        // 各会话 WebView 由 openWindow 惰性创建（见 createChatWebView）：
        // 设置/客户端/JS 桥都在创建时配置，URL 也由 openWindow 统一加载，避免 onPageFinished 竞态。
    }

    /** 新建一个配置好的聊天 WebView（设置 / 客户端 / JS 桥），不加载 URL。 */
    private fun createChatWebView(): WebView {
        val wv = WebView(requireContext())
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            // 允许 file:// 页面加载同目录下的本地 JS 依赖（marked / highlight.js）
            allowFileAccessFromFileURLs = true
            // 不开启跨域访问：file:// 页面不需要访问任意 URL（http/https/content://）
            // 开启此项会允许潜在 XSS 通过 file:// 读取 filesDir 下的所有文件（含 token）
            allowUniversalAccessFromFileURLs = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        // 禁止 WebView 整体 overscroll（边缘橡皮筋）：配合 chat.html 内 #messages 的
        // min-height:0 + overscroll-behavior:contain，避免消息滚到底后继续滑动拖动整个页面。
        wv.overScrollMode = WebView.OVER_SCROLL_NEVER
        // 恢复 WebView 原生文本选区：长按消息即弹出系统自带的拖动手柄（选词 / 复制）。
        // 页面外壳仍保持 user-select:none（防误选整页），但消息气泡已在 chat.html 内
        // 放开 user-select:text，故原生选区可正常作用于气泡正文。
        // 「重新生成 / 编辑重发 / 复制」改由气泡右上角「⋯」按钮触发（见 chat.html addRowMenu）。
        wv.isLongClickable = true
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.webViewClient = PoolWebViewClient()
        wv.addJavascriptInterface(chatBridge!!, "ChatBridge")
        // 给聊天 WebView 补 WebChromeClient：JS 的 confirm()/alert() 在无此客户端时会被直接吞掉
        //（confirm 恒返回 false），导致删除功能的确认弹窗永远无法触发。
        wv.webChromeClient = object : WebChromeClient() {
            private var handled = false
            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                if (handled || result == null) return false
                handled = true
                // 网页 require 的确认框：必须不可取消，否则返回键会绕过结果回调
                MaterialDialogs.confirm(
                    context = requireContext(),
                    title = "网页提示",
                    message = message.orEmpty(),
                    confirmText = "确定",
                    cancelText = "取消",
                ) {
                    handled = false; result.confirm()
                }
                return true
            }
            override fun onJsAlert(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                if (handled || result == null) return false
                handled = true
                // 网页 alert：只有一个「知道了」，但同样不可取消（要保证 result 被 consume）
                MaterialDialogs.alert(
                    context = requireContext(),
                    title = "网页提示",
                    message = message.orEmpty(),
                    cancelable = false,
                ) {
                    handled = false; result.confirm()
                }
                return true
            }
        }
        return wv
    }

    /**
     * 池内所有 WebView 共用的客户端：onPageFinished 按 view.tag（sessionId）区分归属，
     * 各自注入锚点/历史，互不串扰。
     */
    private inner class PoolWebViewClient : WebViewClient() {
        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            val sid = view?.tag as? String ?: return
            android.util.Log.i("MCP_LIFE", "onPageFinished sid=$sid hasPending=${pendingLoads.containsKey(sid)} url=$url", Throwable("MCP_LIFE"))
            val pending = pendingLoads.remove(sid)
            if (pending == null) {
                // 没有 pendingLoads 记录 = 这次加载不是 openWindow 发起的：系统回收渲染进程后
                // WebView 自我重载、或页面被 reload。此时 DOM 是空的，若不重灌历史，
                // 表现就是「切回来整窗消息都没了」。仅在页面确实为空时才补，避免重复追加。
                view?.let { rescueBlankPage(it, sid) }
                return
            }
            val (session, isNew) = pending
            loadedSids.add(sid)
            if (isNew) {
                chatBridge?.setParentFor(session.id, null)
                // 新会话：锚点归零，前端消息 id 从 1 重新计数
                view.evaluateJavascript("window.onAnchor(null)", null)
                // 页面就绪：发送排队中的提示词（外部触发新建会话的场景）
                flushPendingPrompt(session.id)
            } else {
                // 有活跃后台流且本会话仍在前台：恢复 Kotlin 侧 isBusy（让前端显示停止按钮）。
                // 流内容快照由 loadHistoryIntoWeb 在调用 onHistory 时以第二参数确定性下发。
                StreamTaskManager.get(sid)?.let {
                    LogStore.i("STREAM_TASK", "待恢复活跃流 session=${it.sessionId} msgId=${it.messageId} contentLen=${it.lastContent.length}")
                    if (sid == currentSessionId) chatBridge?.isBusy = true
                }
                // 加载历史（onHistory 第二参数携带活跃流快照，完成确定性恢复）
                loadHistoryIntoWeb(session)
            }
            // 修复冷启动圆环丢失：openWindow 在 loadUrl 之前调用了 setSessionProfile，
            // pushTokenConfig 的 evaluateJavascript 早于 chat.html 加载，事件被吞。
            // 这里在页面就绪后补发一次，确保看板入口可见。
            chatBridge?.setSessionProfile(
                session.id,
                auth.getOpenAIModel(),
                auth.getOpenAIContextWindow(),
                auth.getOpenAIMaxInput()
            )
            // 页面已就绪：定向把 tokenConfig 推给刚加载完的这个 WebView，绕过 frontWebView
            // 字段的就绪时序竞态，修复「首次进入看板无数据、返回会话列表再进才有」的问题。
            view?.let { chatBridge?.pushTokenConfig(it) }
            // 冷加载完成、页面可交互后恢复该会话的 token 流水（池命中分支在 openWindow 已处理）。
            // 此处补发确保冷启动进入的会话也能正确回填看板流水，不串到其它会话。
            if (!isNew) chatBridge?.restoreTokenFlow(session.id)
        }

        override fun onReceivedSslError(
            view: WebView?,
            handler: android.webkit.SslErrorHandler?,
            error: android.net.http.SslError?
        ) {
            CertBypass.onWebViewSslError(view, handler, requireContext())
        }
    }

    // ───────────────────── 会话 WebView 池 ─────────────────────

    private companion object {
        /** 同时常驻的会话 WebView 数上限；超出按 LRU 淘汰闲置会话（有活跃流的不淘汰）。 */
        const val MAX_WEBVIEWS = 3
    }

    /** 把 [sid] 的 WebView 换入前台容器，并告知 ChatBridge 后续事件只推给它。 */
    private fun attachWebView(sid: String) {
        val wv = webViewPool[sid] ?: return
        if (activeWebView === wv && wv.parent != null) return
        webContainer.removeAllViews()
        webContainer.addView(wv, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        activeWebView = wv
        chatBridge?.frontWebView = wv
    }

    /** 池满时淘汰最久未用的闲置会话（LinkedHashMap accessOrder=true 首项即 LRU）。 */
    private fun evictPoolIfNeeded(keepSid: String?) {
        while (webViewPool.size >= MAX_WEBVIEWS) {
            val victim = webViewPool.entries.firstOrNull { (sid, _) ->
                sid != currentSessionId && sid != keepSid && !StreamTaskManager.hasActiveTask(sid)
            }?.key ?: break
            teardownWebView(victim)
        }
    }

    /**
     * 彻底销毁某会话的 WebView。ChatBridge 侧的会话态（历史/缓冲）保留——
     * 重开该会话时 loadHistoryIntoWeb 会优先用内存缓存，免磁盘重读。
     */
    private fun teardownWebView(sid: String) {
        loadedSids.remove(sid)
        pendingLoads.remove(sid)
        staleSids.remove(sid)
        val wv = webViewPool.remove(sid) ?: return
        LogStore.d("SESS", "淘汰会话 WebView session=$sid")
        wv.stopLoading()
        wv.clearCache(true)
        wv.removeJavascriptInterface("ChatBridge")
        (wv.parent as? ViewGroup)?.removeView(wv)
        if (activeWebView === wv) activeWebView = null
        wv.destroy()
    }

    /** 向指定会话自己的 WebView 下发 JS（池中不存在则忽略）。 */
    private fun evalFor(sid: String, js: String) {
        webViewPool[sid]?.evaluateJavascript(js, null)
    }

    /**
     * 在网页模式下加载某个已有会话的历史消息，并通过 window.onHistory 注入 WebView。
     * 同时把会话锚点设置为下一轮 parent_message_id，保证 WebView 内多轮续聊。
     * 加载成功会把历史灌入 ChatBridge 累积列表并落盘；失败则回退本地缓存（离线可用）。
     *
     * 注意：用户消息可能包含注入的 system_prompt + tools.md 前缀（由 ChatBridge
     * 在首次发消息时自动拼接），此处自动检测并剥离所有包含该前缀的消息，
     * 确保历史显示只包含用户原始输入。同时尝试从 AI 回复中提取工具调用卡片。
     */
    /** JS 字符串转义：把 Kotlin 字符串安全放进单引号 JS 字符串字面量。 */
    private fun escJs(s: String): String =
        s.replace("\\", "\\\\\\\\").replace("'", "\\\\'").replace("\n", "\\\\n")

    /**
     * 构造 onHistory 第二参数：指定会话活跃流的最新快照（content/thinking/msgId）。
     * 无活跃流时返回 "null"（前端据此复位为「发送」状态）。
     *
     * 不再通过 window._pendingStreamResume 全局变量接力——恢复数据在调用 onHistory 的
     * 同一时刻构造并作为参数确定性地下发，避免异步 onHistory 与 SSE 持续产生新内容之间
     * 存在竞态（旧实现靠再次 pushPendingStreamResume 打补丁）。
     */
    private fun activeStreamResumeJson(sid: String?): String {
        val t = sid?.let { StreamTaskManager.get(it) } ?: return "null"
        // msgId 交给前端当气泡 id：必须是本地 id（与 message_id 事件同一口径）
        val localMsgId = chatBridge?.toLocalId(sid, t.messageId) ?: t.messageId
        return "{ content: '${escJs(t.lastContent)}', thinking: '${escJs(t.lastThinking)}', msgId: '${escJs(localMsgId)}' }"
    }

    /** 当前 WebView 是否仍停留在 [sessionId]（防异步历史回包注入到已切走的会话）。 */
    private fun isCurrentSession(sessionId: String): Boolean =
        chatBridge?.currentSessionId == sessionId

    /** 页面/历史/锚点就绪后，发送排队中的提示词（仅当仍属于 [sessionId]，防止串到其它会话）。 */
    private fun flushPendingPrompt(sessionId: String) {
        if (pendingPromptSessionId != sessionId) return
        // 会话已切走：保留排队，待其再次成为前台时（openWindow 池命中路径）补发，
        // 否则 sendMessage 会把提示词发进当前前台会话，造成串会话。
        if (currentSessionId != sessionId) return
        val p = pendingPrompt ?: return
        pendingPrompt = null
        pendingPromptSessionId = null
        // 显式带上会话 id：不再依赖全局 currentSessionId（多会话并发时它可能已被改写）
        chatBridge?.sendMessageFor(sessionId, p, false, false)
    }

    /**
     * 空页面兜底：页面被系统重建/重载后 DOM 里一条消息都没有时，按池中 sid 重新灌一次历史。
     *
     * 触发场景：WebView 渲染进程被系统回收后自我重载（onPageFinished 再次回调，但这次
     * 不是 openWindow 发起的，pendingLoads 里没有记录）；旧逻辑在此直接 return，
     * 于是页面永远停在空白 —— 用户看到的就是「消息都不见了」。
     */
    private fun rescueBlankPage(view: WebView, sid: String) {
        if (!isAdded) return
        view.evaluateJavascript(
            "(function(){var e=document.getElementById('messages');return e?e.childElementCount:-1;})()"
        ) { r ->
            val count = r?.trim()?.trim('"')?.toIntOrNull() ?: -1
            if (count != 0) return@evaluateJavascript   // 非空页面不动，避免重复追加气泡
            val ctx = context ?: return@evaluateJavascript
            val session = sessions.firstOrNull { it.id == sid }
                ?: LocalStore.loadSessions(ctx, LocalStore.backendTag(auth.getBackend()))
                    .firstOrNull { it.id == sid }
                ?: return@evaluateJavascript
            LogStore.i("SESS", "页面被重载且为空，重灌历史 session=$sid")
            loadHistoryIntoWeb(session)
        }
    }

    private fun loadHistoryIntoWeb(session: ChatSession) {
        val ctx = requireContext()   // 主线程捕获，供后台/UI 复用
        // OpenAI 兼容协议：无远端会话历史概念，读本地消息缓存注入 WebView。
        // 长会话的消息日志可达数 MB：磁盘读 + JSON 构造放后台线程，
            // 否则冷进会话时 onPageFinished 里同步读盘会卡主线程（进入聊天窗口卡顿）。
            thread {
                // 读**当前后端**的命名空间（旧实现写死 OpenAI，Web 自动化下永远读不到自己的历史）
                val tag = LocalStore.backendTag(auth.getBackend())
                // 内存优先：池冷重建的会话若 ChatBridge 里还有缓存历史，直接用，免磁盘重读；
                // 也免 setHistory 的全量回写（内容与磁盘同源；旧格式迁移已在 Coordinator.load 内做）。
                val cached = chatBridge?.cachedHistory(session.id)
                val local = cached ?: LocalStore.loadMessages(ctx, tag, session.id).also {
                    if (it.isNotEmpty()) chatBridge?.setHistory(session.id, it, persist = false)
                }
                if (local.isNotEmpty()) {
                    if (cached == null) chatBridge?.markSessionHistoryLoaded(session.id)
                    val histJson = messagesHistoryJsonPaginated(local)
                    runOnUi {
                        if (isAdded) evalFor(session.id, "window.onHistory($histJson, ${activeStreamResumeJson(session.id)})")
                    }
                }
                chatBridge?.setParentFor(session.id, session.currentMessageId)
                // OpenAI 本地历史注入：把会话当前锚点告知前端（OpenAI 实际忽略 parent，但保持前端计数一致）。
                // 锚点在本地是服务端 id，发给前端要换算成本地 id。
                val localAnchor1 = chatBridge?.toLocalId(session.id, session.currentMessageId)
                val a1 = if (localAnchor1 != null) "\"$localAnchor1\"" else "null"
                runOnUi {
                    evalFor(session.id, "window.onAnchor($a1)")
                    flushPendingPrompt(session.id)
                }
                LogStore.i("NET", "OpenAI 历史注入 ${local.size} 条${if (cached != null) "（内存缓存）" else ""}")
            }
    }

    /**
     * 剥离用户消息中注入的 system 提示词（{"role":"system","content":...} JSON）+ tools（OpenAI tools 数组）前缀。
     * 注入格式：{system_json}\n\n---\n\n{tools_json}\n\n---\n\n{user_text}
     * 若 system 提示词为空，则为：{tools_json}\n\n---\n\n{user_text}
     *
     * 健壮的实现：
     * 1. 找第一个分隔符 --- 后面的内容
     * 2. 如果后面还有 --- 分隔符，说明是完整注入（system + tools），继续找第二个
     * 3. 如果只有一个 --- 分隔符，说明只注入了 tools（system 为空）
     */
    private fun stripInjectionPrefix(text: String): String {
        val delim = "\n\n---\n\n"
        val firstDelimIndex = text.indexOf(delim)
        
        // 没有分隔符，说明没有注入前缀，直接返回
        if (firstDelimIndex < 0) return text
        
        // 找到第一个分隔符后的内容
        val afterFirst = firstDelimIndex + delim.length
        
        // 检查是否存在第二个分隔符（完整注入的标志）
        val secondDelimIndex = text.indexOf(delim, afterFirst)
        
        return if (secondDelimIndex < 0) {
            // 仅注入 tools.md（system_prompt 为空）：格式是 {tools}\n\n---\n\n{user_text}
            // 所以第一个 --- 之后就是用户文本
            text.substring(afterFirst)
        } else {
            // 完整注入（system_prompt + tools.md）：格式是 {system_prompt}\n\n---\n\n{tools}\n\n---\n\n{user_text}
            // 第二个 --- 之后才是用户文本
            text.substring(secondDelimIndex + delim.length)
        }
    }

    /**
     * 尝试从 AI 回复（RESPONSE 片段）中提取工具调用卡片信息，兼容两种形状：
     *  - function calling 的 tool 消息：{"role":"tool","tool_call_id":...,"content":...}
     *  - 旧 JSON-RPC 2.0 信封：{"jsonrpc":"2.0","id":...,"result":{...},"error":{...}}
     *
     * 注意：工具结果是以 prompt 形式回传的，正常情况下只会出现在 **USER** 侧
     * （见上方历史映射中的 ToolMessages.parseResults 分支），不会出现在 RESPONSE 里。
     * 此处仅作为兜底，兼容早期把结果写进 RESPONSE 的历史数据，别误以为这是主路径。
     */
    private fun tryExtractToolCall(responseText: String): ToolCallData? {
        return try {
            // 用 McpJson（ignoreUnknownKeys + coerceInputValues + isLenient）宽松解析
            // 工具调用结果；相比 org.json 严格解析，对稍不规范的 JSON 更鲁棒。
            val obj = McpJson.parseToJsonElement(responseText.trim()).jsonObject
            val id = runCatching {
                (obj["id"] ?: obj["tool_call_id"])?.jsonPrimitive?.content
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null

            // function calling 形状：role 为 tool
            if (obj["role"]?.jsonPrimitive?.content == "tool") {
                return ToolCallData(
                    id = id,
                    name = "tool_call",  // 从历史回复中无法得知确切的工具名，此处为占位
                    arguments = "",      // 从历史回复中无法回溯确切参数
                    result = responseText
                )
            }

            // 检查是否是 JSON-RPC 工具调用响应（包含 result 或 error）
            if (!obj.containsKey("result") && !obj.containsKey("error")) return null

            ToolCallData(
                id = id,
                name = "tool_call",  // 从历史回复中无法得知确切的工具名，此处为占位
                arguments = "",      // 从历史回复中无法回溯确切参数
                result = responseText
            )
        } catch (e: Exception) {
            // 不是 JSON 格式，说明不是工具调用响应
            null
        }
    }

    /** 历史消息初始分页大小（与 ChatBridge.HISTORY_PAGE_SIZE 保持一致）。 */
    private val HISTORY_INITIAL_PAGE_SIZE: Int = 50

    /**
     * 构造 window.onHistory 用的分页 JSON：只包含「最新 [HISTORY_INITIAL_PAGE_SIZE] 条」，
     * 附 total/hasMore 供前端识别是否还有更早历史可加载（前端向上滚动再拉取）。
     *
     * 与 [messagesToHistoryJson] 的 messages 结构完全一致，仅多 3 个元数据字段。
     */
    private fun messagesHistoryJsonPaginated(list: List<ChatMessage>): String {
        val total = list.size
        val limit = HISTORY_INITIAL_PAGE_SIZE
        val slice = if (total <= limit) list else list.subList(total - limit, total)
        val arr = org.json.JSONArray()
        for (m in slice) {
            arr.put(org.json.JSONObject().apply {
                put("isUser", m.isUser)
                put("content", m.content)
                put("thinking", m.thinking)
                // 本地日志统一存**本地 id**（写入时已按当时偏移换算），原样交给前端
                put("id", m.id)
                put("parentId", m.parentId)
                m.toolCall?.let { tc ->
                    put("toolCall", org.json.JSONObject().apply {
                        put("id", tc.id)
                        put("name", tc.name)
                        put("arguments", tc.arguments)
                        put("result", tc.result)
                    })
                }
            })
        }
        return org.json.JSONObject()
            .put("messages", arr)
            .put("total", total)
            .put("offset", 0)
            .put("limit", limit)
            .put("hasMore", total > limit)
            .toString()
    }

    /** 把 ChatMessage 列表序列化为 window.onHistory 所需的 JSON 字符串。 */
    private fun messagesToHistoryJson(list: List<ChatMessage>): String {
        val arr = org.json.JSONArray()
        for (m in list) {
            arr.put(org.json.JSONObject().apply {
                put("isUser", m.isUser)
                put("content", m.content)
                put("thinking", m.thinking)
                put("id", m.id)
                put("parentId", m.parentId)
                m.toolCall?.let { tc ->
                    put("toolCall", org.json.JSONObject().apply {
                        put("id", tc.id)
                        put("name", tc.name)
                        put("arguments", tc.arguments)
                        put("result", tc.result)
                    })
                }
            })
        }
        return org.json.JSONObject().put("messages", arr).toString()
    }

    /**
     * 打开会话窗口。打开过的会话 WebView 常驻池中：命中直接换入前台（零重建），
     * 未命中才新建页面冷加载（历史优先取 ChatBridge 内存缓存，其次磁盘日志）。
     * @param isNew true=刚新建的会话（首条 parent=null）；false=从列表点开的已有会话，
     *              需先拉 history_messages 取 current_message_id 作为续聊锚点并还原历史气泡。
     */
    private fun openWindow(session: ChatSession, isNew: Boolean = false) {
        currentSessionId = session.id
        chatBridge?.currentSessionId = session.id
        // 会话窗口/阈值跟随当前 active profile（实际请求也用它，见 LLMConfig model = auth.getOpenAIModel()），
        // 不再保留会话创建时的快照——避免切换档案/模型后，看板与压缩阈值仍按旧模型旧窗口显示/计算。
        chatBridge?.setSessionProfile(
            session.id,
            auth.getOpenAIModel(),
            auth.getOpenAIContextWindow(),
            auth.getOpenAIMaxInput()
        )
        // 同步会话级预设：会话 A 的极简/PTC 模式不会泄漏到会话 B。
        // applySessionPreset 幂等——目标 id 与当前一致时短路，不做工具箱重扫。
        chatBridge?.applySessionPreset(session.id, session.presetId)

        // 隐藏期间有流更新/完成的会话，页面已错过事件：先拆掉旧页，落到下方冷加载分支
        // 确定性重建（历史优先取 ChatBridge 内存缓存 + resume 快照，无磁盘重读）。
        if (webViewPool.containsKey(session.id) && staleSids.remove(session.id)) {
            teardownWebView(session.id)
        }
        val pooled = webViewPool[session.id]
        if (pooled != null) {
            // 池命中：JS 上下文、DOM 会话树与流式状态（currentAi/busy）都仍在，直接换入前台。
            // 不能整页重载或重复 onHistory：会在非空 DOM 上重复追加气泡，并把 busy 复位为
            // 「发送」，后台仍在跑的 SSE 因 currentAi 错位而不再显示（「流式没有恢复和继续生成」）。
            attachWebView(session.id)
            // 页面已就绪：若此前有排队中的提示词（页面加载期间切走又切回），此时补发
            flushPendingPrompt(session.id)
            // 恢复该会话的请求流水（从本地文件）：须在 attachWebView 之后调用，
            // 此时 frontWebView 已指向本会话，避免把 A 的流水打到当时仍是前台的 B 看板。
            if (!isNew) chatBridge?.restoreTokenFlow(session.id)
        } else {
            evictPoolIfNeeded(keepSid = session.id)
            val wv = createChatWebView()
            wv.tag = session.id
            webViewPool[session.id] = wv
            pendingLoads[session.id] = session to isNew
            attachWebView(session.id)
            wv.loadUrl("file:///android_asset/chat.html")
            // 冷加载：页面尚未就绪，流水恢复推迟到 onPageFinished（页面就绪后）统一处理。
        }

        val titleView = windowLayout.findViewById<TextView>(R.id.tvWindowTitle)
        titleView.text = if (session.title.isNotEmpty()) session.title else getString(R.string.session_window_title_default)
        showWindow()
    }

    /**
     * 由外部（如 TodoFragment 推进模式）调用：打开或复用当前会话并发送消息。
     */
    fun sendPrompt(prompt: String) {
        if (currentSessionId == null) {
            // 无活跃会话，新建一个
            // 非 DeepSeek 后端：本地生成会话 id（前缀与命名空间都跟随当前后端），不调远端 createSession。
            val tag = LocalStore.backendTag(auth.getBackend())
            val id = "$tag-${java.util.UUID.randomUUID()}"
            val session = ChatSession(id, "", false, 0.0, model = auth.getOpenAIModel(), contextWindow = auth.getOpenAIContextWindow(), maxInput = auth.getOpenAIMaxInput(), presetId = PresetRuntime.userPreferredId(requireContext()))
            sessions.add(session)
            LocalStore.saveSessions(requireContext(), tag, sessions)
            openWindow(session, isNew = true)
            pendingPrompt = prompt
            pendingPromptSessionId = session.id
        } else if (webLoaded(currentSessionId)) {
            // 已有活跃会话且页面已就绪，直接发送（显式带会话 id，不依赖全局）
            chatBridge?.sendMessageFor(currentSessionId, prompt, false, false)
        } else {
            // 页面仍在加载：排队，待锚点就绪后 flush（替代固定 500ms 延迟）
            pendingPrompt = prompt
            pendingPromptSessionId = currentSessionId
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        android.util.Log.w("MCP_LIFE", "SessionFragment.onDestroyView pid=${android.os.Process.myPid()}", Throwable("MCP_LIFE"))
        // ⚠️ 这里**不销毁 WebView 池、也不销毁 ChatBridge**。
        //
        // 视图销毁（切底部页签 / 旋转 / 主题重建）只意味着「暂时不在屏幕上」：池里的会话页面
        // 连同它们的 DOM、输入框内容、正在生成的流都还在。原先在此处 teardown 整个池 + destroy
        // 桥，回来只能冷加载，多会话时表现为「来回切换后消息都不见了 / 白屏」。
        //
        // 只摘掉**会触碰视图**的钩子（否则视图没了还会被回调去 refreshSessionList / openWindow）：
        chatBridge?.frontWebView = null
        chatBridge?.onStreamActivityChange = null
        chatBridge?.onRecordPermissionRequest = null
        chatBridge?.onPickAttachmentsRequest = null
        chatBridge?.onForkedSession = null
        // onOffstageStreamEvent 仅往 fragment 级 staleSids 塞 id（不碰视图），必须保留：
        // 视图销毁期间错过的流事件要标记为脏，回本页重开该会话时才会确定性重建。
        webContainer.removeAllViews()
        activeWebView = null
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.w("MCP_LIFE", "SessionFragment.onDestroy pid=${android.os.Process.myPid()} finishing=${activity?.isFinishing} removing=$isRemoving added=$isAdded", Throwable("MCP_LIFE"))
        // 真正结束（Activity 关闭 / Fragment 被移除）才释放池与桥，避免 WebView 泄漏。
        // 切页签/旋转只走 onDestroyView，不会到这里。
        val finishing = activity?.isFinishing == true
        if (finishing || isRemoving || !isAdded) {
            for (sid in webViewPool.keys.toList()) teardownWebView(sid)
            webViewPool.clear()
            chatBridge?.destroy()
            chatBridge = null
            LogStore.d("SESS", "释放会话 WebView 池与桥（finishing=" + finishing + " removing=" + isRemoving + "）")
        }
    }

    // ───────────────────────── 状态切换 ─────────────────────────

    /**
     * 是否可直接进入会话列表（而不是被迫停在登录卡）。
     *
     * OpenAI 兼容后端不需要 DeepSeek 账号——会话与历史都在本地（见 [loadSessions] 的
     * OPENAI 分支），凭据只有 Base URL / API Key。若仍按 token 判定，选了这个后端的用户
     * 会被挡在一张永远登不上的 DeepSeek 登录卡前，App 直接不可用。
     * DeepSeek 逆向后端仍然必须登录（服务端会话态依赖 token）。
     */
    private fun canEnterSessionList(): Boolean = true

    private fun showLogin() {
        loginCard.visibility = View.VISIBLE
        listLayout.visibility = View.GONE
        windowLayout.visibility = View.GONE
    }

    private fun showList() {
        loginCard.visibility = View.GONE
        listLayout.visibility = View.VISIBLE
        windowLayout.visibility = View.GONE
        loadSessions()
    }

    private fun showWindow() {
        loginCard.visibility = View.GONE
        listLayout.visibility = View.GONE
        windowLayout.visibility = View.VISIBLE
    }

    /** 安全切回主线程（fragment 已 detach 时自动忽略）。 */
    private fun runOnUi(block: () -> Unit) {
        val act = activity ?: return
        act.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            block()
        }
    }

    /** 刷新会话列表（流开始/结束或返回列表时调用，让「生成中」动画同步）。 */
    private fun refreshSessionList() {
        if (isAdded && ::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    // ───────────────────────── 列表适配器 ─────────────────────────
    private class SessionAdapter(
        private val items: List<ChatSession>,
        private val onClick: (ChatSession) -> Unit,
        private val onLongPress: (ChatSession) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvTitle: TextView = itemView.findViewById(R.id.tvTitle)
            val tvTime: TextView = itemView.findViewById(R.id.tvTime)
            val tvMode: TextView = itemView.findViewById(R.id.tvMode)
            val pinDot: View = itemView.findViewById(R.id.pinDot)
            val ivLoading: RoundDotLoadingView = itemView.findViewById(R.id.ivLoading)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_session, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val s = items[position]
            holder.tvTitle.text = if (s.title.isNotEmpty()) s.title else "(未命名会话)"
            // 网页会话没有本地时间（updatedAt 恒为 0），显示来源/当前态，避免一律「时间未知」
            holder.tvTime.text = if (s.id.startsWith(WA_SESSION_PREFIX)) {
                if (s.pinned) "当前会话" else "网页会话"
            } else {
                formatTimeSafe(s.updatedAt, holder.tvTime)
            }
            holder.pinDot.visibility = if (s.pinned) View.VISIBLE else View.GONE
            // 会话运行模式小标：按会话 presetId 显示（完整模式 / 极简模式 / PTC 编程模式）
            holder.tvMode.text = presetNameOf(s.presetId)
            // 会话生成中：条目右侧显示 8 圆点环形动画；否则隐藏
            if (StreamTaskManager.hasActiveTask(s.id)) {
                holder.ivLoading.visibility = View.VISIBLE
                holder.ivLoading.start()
            } else {
                holder.ivLoading.stop()
                holder.ivLoading.visibility = View.GONE
            }
            holder.itemView.setOnClickListener { onClick(s) }
            holder.itemView.setOnLongClickListener { onLongPress(s); true }
        }

        override fun onViewRecycled(holder: VH) {
            holder.ivLoading.stop()
            super.onViewRecycled(holder)
        }

        private fun presetNameOf(id: String?): String {
            val key = id?.trim().orEmpty()
            if (key.isNotEmpty()) PresetManager.get(key)?.let { return it.name }
            // 未带 presetId 的历史会话 = 完整模式（与 PresetRuntime 的 null/full 等价语义一致）
            return PresetRuntime.FULL.name
        }

        private fun formatTimeSafe(ts: Double, tv: TextView): String {
            if (ts <= 0.0) return tv.context.getString(R.string.session_time_unknown)
            return try {
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                sdf.format(Date((ts * 1000).toLong()))
            } catch (e: Exception) {
                tv.context.getString(R.string.session_time_unknown)
            }
        }
    }
}