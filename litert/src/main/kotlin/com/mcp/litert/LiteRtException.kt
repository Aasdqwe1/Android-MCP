package com.mcp.litert
    
    /** LiteRT 本地后端的统一异常：模型未就绪 / 加载失败 / 推理错误。 */
    class LiteRtException(
        message: String,
        cause: Throwable? = null,
    ) : Exception(message, cause)
    