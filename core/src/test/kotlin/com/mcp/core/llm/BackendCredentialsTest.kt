package com.mcp.core.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「凭据是否就绪」判定的回归测试。
 *
 * 背景：这个判定原先在 ChatBridge.checkHealth 与 WebApiServer.health 各写了一份
 * 「token != null」。修好前者时漏了后者，于是选 OpenAI 后端的用户在网页端看到
 * 连接指示器恒为「未连接」、还弹「尚未登录」的误导横幅。
 * 抽成纯函数后这里锁定语义，两个调用点共用同一份实现。
 */
class BackendCredentialsTest {

    @Test
    fun `OpenAI 兼容后端不需要 DeepSeek token`() {
        assertTrue(
            "选了 OpenAI 后端就不该要求登录 DeepSeek（凭据只有 Base URL / API Key）",
            backendCredentialsSatisfied(BackendType.OPENAI, hasDeepSeekToken = false)
        )
        assertTrue(
            "有 token 时当然也满足",
            backendCredentialsSatisfied(BackendType.OPENAI, hasDeepSeekToken = true)
        )
    }

}
