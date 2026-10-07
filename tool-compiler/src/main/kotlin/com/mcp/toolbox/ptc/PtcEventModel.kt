@file:JvmName("PtcEventModel")
package com.mcp.toolbox.ptc

import com.mcp.serialization.McpJson
import kotlinx.serialization.Serializable

/**
 * PTC（Programmatic Tool Call）模式下的 guest↔host 工具调用桥协议模型。
 *
 * 借鉴 deepseek-harness 的 `run_code` 模式：模型写一段程序体，程序内通过
 * `tools.name(args)` 调用注册表工具；每个子调用被序列化后经 IPC 交给宿主侧，
 * 由宿主侧经 [com.mcp.toolbox.Toolbox.dispatch] 执行（继承白名单 / guard / 超时 / 审计），
 * 再把结果写回程序。本文件只定义**协议载荷与编解码**，不依赖 Android，便于单元测试。
 */

/** 程序内一次工具调用的请求（guest → host）。[arguments] 为工具参数的 JSON 字符串。 */
@Serializable
data class PtcCallRequest(
    val callId: String,
    val name: String,
    val arguments: String
)

/** 宿主侧执行结果（host → guest）。`value` 与 `error` 互斥：成功带 [value]，失败带 [error]。 */
@Serializable
data class PtcCallResult(
    val callId: String,
    val value: String? = null,
    val error: String? = null
)

/** 嵌套事件类型：扩展 [com.mcp.data.persistence.SessionLogEvent.type] 的取值（默认仍为 `"message"`）。 */
enum class PtcEventKind(val type: String) {
    /** 一次 PTC 子调用发起（对应 `tool/code-dispatch-start`）。 */
    PTC_CALL("ptc/call"),
    /** 一次 PTC 子调用结果（对应 `tool/code-dispatch`）。 */
    PTC_RESULT("ptc/result")
}

/**
 * 一次 PTC 子调用的**嵌套事件**，供 UI/会话日志渲染「程序内部调用了哪些工具」。
 *
 * 借鉴 dsh 的 `tool/code-dispatch` 事件：子调用不进入模型主历史（只有外层 run_code 的
 * 精简结果入历史），但完整写入 append-only 会话日志，保证可审计、可回放。
 *
 * @param kind        [PtcEventKind.type]：`ptc/call`（发起）/ `ptc/result`（返回）。
 * @param parentId    外层 run_code 的 tool call id，用于在日志里把子调用挂到父调用下。
 * @param depth       嵌套深度（run_code 内再嵌 run_code 时递增；当前实现禁止递归调用）。
 * @param description 外层 run_code 的 `description` 参数（UI 上的常驻标签），用于把整段 PTC
 *                   子调用树归到同一段程序名下。
 */
@Serializable
data class PtcDispatchEvent(
    val kind: String,
    val callId: String,
    val name: String,
    val parentId: String = "",
    val depth: Int = 0,
    val description: String = "",
    val arguments: String = "",
    val result: String? = null,
    val error: String? = null
)

/** IPC 行协议编解码：每行一个 JSON 对象（JSONL）。失败返回 null，调用方据此跳过脏行。 */
object PtcIpc {
    fun encodeRequest(r: PtcCallRequest): String = McpJson.encodeToString(PtcCallRequest.serializer(), r)
    fun decodeRequest(line: String): PtcCallRequest? =
        runCatching { McpJson.decodeFromString<PtcCallRequest>(line) }.getOrNull()

    fun encodeResult(r: PtcCallResult): String = McpJson.encodeToString(PtcCallResult.serializer(), r)
    fun decodeResult(line: String): PtcCallResult? =
        runCatching { McpJson.decodeFromString<PtcCallResult>(line) }.getOrNull()
}
