package com.mcp.ptc

import com.mcp.core.chat.ChatMessage
import com.mcp.core.chat.ToolCallData
import com.mcp.data.persistence.SessionLogEvent
import com.mcp.toolbox.ptc.PtcDispatchEvent
import com.mcp.toolbox.ptc.PtcEventKind

/**
 * PTC 嵌套事件 → 会话日志事件的适配器。
 *
 * 为什么需要它：PTC 的语义是「**子调用不进模型主历史**」——只有外层 `run_code` 的
 * 精简结果回到模型上下文（省 token），但每个子调用仍要完整落到 append-only 日志里
 * （可审计、可回放、UI 可展开看调用树）。对齐 dsh 的 `tool/code-dispatch` 事件。
 *
 * 落盘形态复用 [SessionLogEvent]：`type` 取 [PtcEventKind.type]（`ptc/call`/`ptc/result`），
 * 工具名/参数/结果塞进 [ToolCallData]，[SessionLogEvent.parentId] 用外层 run_code 的
 * run_id、[SessionLogEvent.parentSeq] 用外层事件的 seq。
 *
 * 用法（日志写入处一行接上即可）：
 * ```kotlin
 * PtcEventBus.subscribe { e -> log.append(e.toLogEvent(seq = nextSeq(), parentSeq = runCodeSeq)) }
 * ```
 */
fun PtcDispatchEvent.toLogEvent(seq: Long, parentSeq: Long? = null): SessionLogEvent {
    val isResult = kind == PtcEventKind.PTC_RESULT.type
    val body = when {
        isResult && error != null -> "错误: $error"
        isResult -> result ?: ""
        else -> arguments
    }
    return SessionLogEvent(
        type = kind,
        seq = seq,
        isUser = false,
        content = body,
        toolCall = ToolCallData(
            id = callId,
            name = name,
            arguments = if (isResult) "" else arguments,
            result = if (isResult) (result ?: "") else ""
        ),
        id = callId,
        parentId = parentId,
        parentSeq = parentSeq,
        depth = depth
    )
}

/** 反向：把日志事件还原成领域消息（供历史回放时把 PTC 子调用拼回可视消息）。 */
fun SessionLogEvent.toChatMessageOrNull(): ChatMessage? =
    if (type.startsWith("ptc/")) toMessage() else null
