package com.mcp.mnn

/** MNN 本地后端的可读异常，避免把 native 层的裸错误直接抛给上层。 */
class MnnException(message: String, cause: Throwable? = null) : Exception(message, cause)
