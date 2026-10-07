package com.mcp.wechat

import android.content.Context
import android.content.SharedPreferences
import com.mcp.core.chat.ChatSession
import com.mcp.core.llm.BackendType
import com.mcp.data.LocalStore
import java.util.UUID

/**
 * iLink 原生微信的「会话引导」：在用户正常聊天前，强制先选择要进入的会话。
 *
 * 交互形态（聊天内数字菜单，复用会话页 SessionFragment 的会话列表）：
 *   - 用户任意发言 → 若尚未选定会话，bot 回复编号菜单（并明示会话总数与展示条数）：
 *       请先选择要进入的会话（直接回复数字）：
 *       0. 新会话
 *       1. <会话页列表第 1 条标题>
 *       2. <会话页列表第 2 条标题>
 *       ...
 *       共 N 个会话；输入 0 开启新会话，输入 1~N 进入对应会话。
 *   - 用户直接在微信上回复数字：0=开新会话；1~N=进入对应已有会话。选定后记住，后续发言直接进入该会话聊天。
 *   - 发送「切换会话」可随时清掉选择、重新引导。
 *
 * 引导完全由代码完成（不经过 LLM）：在 WeixinLlmResponder.reply 调用 LLM 之前拦截，
 * 只有选定会话后（Outcome.Chat）才放行到对应会话线程去推理。
 * 会话列表直接来自会话页（LocalStore.loadSessions），与会话页展示保持一致。
 * 选择状态按 (accountId, fromUserId) 持久化，跨消息保留，且跨进程重启可恢复。
 */
class WeixinConversationGuide(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 引导拦截结果：Reply=bot 直接回复（菜单/确认，不进入聊天）；Chat=进入指定会话聊天。 */
    sealed interface Outcome {
        data class Reply(val text: String) : Outcome
        data class Chat(val sessionId: String, val isNew: Boolean) : Outcome
    }

    /**
     * 拦截一条入站消息，决定是展示引导菜单、确认选择，还是放行到正常聊天。
     * @param backend 当前 LLM 后端（决定取哪个命名空间的会话列表）
     */
    fun intercept(
        accountId: String,
        fromUserId: String,
        rawText: String,
        backend: BackendType
    ): Outcome {
        val key = keyOf(accountId, fromUserId)
        val selected = prefs.getString(selKey(key), null)
        val awaiting = prefs.getBoolean(awaitKey(key), false)

        // 切换命令：随时清掉选择并重新引导
        if (isSwitchCommand(rawText)) {
            prefs.edit().putBoolean(awaitKey(key), true).remove(selKey(key)).apply()
            return Outcome.Reply(buildMenu(backend))
        }

        // 已选定会话 → 直接放行聊天
        // isNew 由会话 id 决定：本地新会话用 "wx_" 前缀标记（尚未在 DeepSeek
        // 服务端创建），续聊时 responder 需先 createSession；已有会话 id 是
        // DeepSeek 返回的真实 UUID，直接续聊其远端上下文。
        if (selected != null && !awaiting) {
            return Outcome.Chat(sessionId = selected, isNew = isLocalNewSession(selected))
        }

        // 等待选择中 → 解析用户输入的编号
        if (awaiting) {
            val n = parseSelection(rawText)
            val sessions = loadSessions(backend)
            return when {
                n == null ->
                    Outcome.Reply("无法识别编号，请直接回复数字：\n${buildMenu(backend)}")
                n == 0 -> {
                    val newId = "wx_${UUID.randomUUID()}"
                    prefs.edit().putString(selKey(key), newId).putBoolean(awaitKey(key), false).apply()
                    Outcome.Reply("已开启新会话，现在可以直接聊天了。发送「切换会话」可换一个会话。")
                }
                n in 1..sessions.size -> {
                    val sid = sessions[n - 1].id
                    prefs.edit().putString(selKey(key), sid).putBoolean(awaitKey(key), false).apply()
                    val label = sessions[n - 1].title.takeIf { it.isNotBlank() } ?: sid
                    Outcome.Reply("已进入会话：$label。继续发送消息即可聊天；发送「切换会话」可换会话。")
                }
                else ->
                    Outcome.Reply("编号超出范围（仅 0~${sessions.size}），请重新选择：\n${buildMenu(backend)}")
            }
        }

        // 首次接触、尚未选择 → 展示菜单并进入等待
        prefs.edit().putBoolean(awaitKey(key), true).apply()
        return Outcome.Reply(buildMenu(backend))
    }

    /** 引导每页会话数（默认 10，固定常量；如需可后续调整为可配置）。 */
    fun pageSize(): Int = prefs.getInt(KEY_PAGE_SIZE, DEFAULT_PAGE_SIZE).coerceIn(1, MAX_PAGE_SIZE)

    /** 构建编号菜单：0. 新会话 + 会话页列表前 N 条，并明示会话总数与展示条数。 */
    fun buildMenu(backend: BackendType): String {
        val all = loadSessions(backend)
        val shown = all.take(pageSize())
        val sb = StringBuilder()
        sb.append("请先选择要进入的会话（直接回复数字）：\n")
        sb.append("0. 新会话\n")
        shown.forEachIndexed { i, s ->
            val label = s.title.takeIf { it.isNotBlank() } ?: s.id
            sb.append("${i + 1}. $label\n")
        }
        sb.append("\n共 ${all.size} 个会话")
        if (shown.size < all.size) sb.append("，已显示前 ${shown.size} 个")
        sb.append("；输入 0 开启新会话，输入 1~${shown.size} 进入对应会话。")
        sb.append("发送「切换会话」可重新选择。")
        return sb.toString()
    }

    /** 解析用户输入的编号：取开头连续数字；「1. xxx」「5」都识别为 1/5；非数字返回 null。 */
    private fun parseSelection(text: String): Int? {
        val digits = text.trim().takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    private fun loadSessions(backend: BackendType): List<ChatSession> {
        val tag = LocalStore.backendTag(backend)
        return LocalStore.loadSessions(context, tag)
            .sortedWith(compareByDescending<ChatSession> { it.pinned }.thenByDescending { it.updatedAt })
    }

    private fun isSwitchCommand(text: String): Boolean {
        val t = text.trim().lowercase()
        return SWITCH_COMMANDS.any { cmd -> t == cmd || t.startsWith("$cmd ") || t.endsWith(" $cmd") }
    }

    /** 本地新会话标记前缀：微信引导里「0. 新会话」生成的本地会话 id。 */
    private fun isLocalNewSession(sessionId: String): Boolean = sessionId.startsWith(NEW_SESSION_PREFIX)

    private fun keyOf(accountId: String, fromUserId: String) = "$accountId\u0000$fromUserId"
    private fun selKey(key: String) = "sel_$key"
    private fun awaitKey(key: String) = "await_$key"

    companion object {
        internal const val PREFS_NAME = "wx_conv_guide"
        /** 本地新会话 id 前缀（未在 DeepSeek 服务端创建，续聊前需 createSession）。 */
        const val NEW_SESSION_PREFIX = "wx_"
        const val KEY_PAGE_SIZE = "guide_page_size"
        const val DEFAULT_PAGE_SIZE = 10
        const val MAX_PAGE_SIZE = 10

        private val SWITCH_COMMANDS = setOf(
            "切换会话", "切换", "switch", "/切换", "会话列表", "/会话", "/switch"
        )
    }
}