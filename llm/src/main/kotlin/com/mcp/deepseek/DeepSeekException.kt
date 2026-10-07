package com.mcp.deepseek

/**
 * 统一 HTTP/协议异常：httpCode 为 HTTP 状态码（非 2xx 时），-1 表示本地/解析错误。
 *
 * 说明：本类原定义在 `DeepSeekApi.kt` 中，DeepSeek 逆向后端移除后仍被上层
 * 通用异常处理复用（会话加载、Web API 错误回传等），故提取为独立文件保留。
 */
class DeepSeekException(val httpCode: Int, message: String) : Exception(message)