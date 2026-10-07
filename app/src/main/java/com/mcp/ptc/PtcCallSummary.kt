package com.mcp.ptc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * run_code 程序内一次子调用的摘要，随 run_code 结果 JSON 的 `calls` 字段回给前端。
 *
 * 子调用**不进模型主历史**（省上下文是 PTC 的初衷之一），但人要看得见：前端
 * （chat.html / chat_desktop.html 的 `renderPtcCalls`）据此在 run_code 卡片里列出
 * 「程序内部调用了哪些工具、每个成没成、耗时多少」。
 *
 * 字段契约（前后端各改一侧必须同步）：
 *  - name   工具名
 *  - ok     该子调用是否成功
 *  - ms     耗时（毫秒）
 *  - error  仅失败时带上（截断 200 字符，控制结果体积）
 */
data class PtcCallSummary(
    val name: String,
    val ok: Boolean,
    val ms: Long,
    val error: String? = null
)

/**
 * 摘要列表 → JSON 数组。
 *
 * 单条体积很小（name/ok/ms，失败才带 error），但仍按 [MAX_CALLS_IN_RESULT] 截断：
 * 200 次子调用的全量清单既撑大结果又没人看，超出部分用 truncated 标记说明。
 */
fun List<PtcCallSummary>.toJsonArray(): JsonArray = buildJsonArray {
    forEach { c ->
        add(
            buildJsonObject {
                put("name", c.name)
                put("ok", c.ok)
                put("ms", c.ms)
                if (!c.ok && !c.error.isNullOrBlank()) put("error", c.error.take(200))
            }
        )
    }
}

/** run_code 结果里最多列出多少条子调用（超出只计数、不再展开）。 */
const val MAX_CALLS_IN_RESULT = 50
