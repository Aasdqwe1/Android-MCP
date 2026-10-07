package com.mcp.toolbox

/**
 * 程序内工具调用失败（对齐 deepseek-harness 的 ToolCallError）。
 *
 * 与 [Toolbox.dispatch] 的「返回 {"error":...} 文本」不同，PTC 程序内调用走
 * [Toolbox.dispatchOrThrow]，失败**抛本异常**，只暴露 [toolName] 与 message，
 * 让程序里的 try/catch 成为可靠契约（此前提示词承诺抛异常、实现却返回字符串）。
 */
class ToolCallException(
    val toolName: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)
