package com.mcp.deepseek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `splitAccumulatedUsage` —— 累积 token 用量的本地拆分测试。
 *
 * 背景（真机实测）：DeepSeek 逆向只回 `accumulated_token_usage`（服务端累积上下文 + 本轮产出）。
 * 早期实现把「本轮输入」当 prompt、让 completion 吃下整个累积上下文，于是 /v1 出现
 * `completion_tokens: 24126` 配一条 3 个字的回复 —— 客户端据此做成本核算会全错。
 *
 * 契约：
 *  1. prompt + completion == total 恒成立（OpenAI 口径的自洽要求）；
 *  2. completion 由**本轮产出字符数**推导，与历史长度无关 —— 短回复就得短 completion；
 *  3. 历史越长（total 越大）时增量全部落在 prompt，而不是 completion；
 *  4. 非法输入（total <= 0）返回 (0, 0)，不抛异常、不产生负数。
 */
class DeepSeekUsageSplitTest {

    @Test
    fun `拆分自洽且completion由产出长度决定`() {
        val (prompt, completion) = splitAccumulatedUsage(totalTokens = 24172, completionChars = 6)
        assertEquals(24172, prompt + completion)
        // 6 字符 → 6/3 = 2，绝不能被历史长度撑大
        assertEquals(2, completion)
        assertEquals(24170, prompt)
    }

    @Test
    fun `历史越长增量只落在prompt而非completion`() {
        val short = splitAccumulatedUsage(totalTokens = 100, completionChars = 30)
        val long = splitAccumulatedUsage(totalTokens = 24000, completionChars = 30)
        assertEquals(short.second, long.second)
        assertEquals(10, short.second)
        assertTrue("prompt 必须随历史增长", long.first > short.first)
    }

    @Test
    fun `空产出时completion仍至少为1且不吞掉total`() {
        val (prompt, completion) = splitAccumulatedUsage(totalTokens = 5, completionChars = 0)
        assertEquals(1, completion)
        assertEquals(4, prompt)
    }

    @Test
    fun `产出远大于total时夹取到total且prompt为0`() {
        val (prompt, completion) = splitAccumulatedUsage(totalTokens = 10, completionChars = 9000)
        assertEquals(10, completion)
        assertEquals(0, prompt)
    }

    @Test
    fun `非法total返回零对而不是负数`() {
        assertEquals(0 to 0, splitAccumulatedUsage(totalTokens = 0, completionChars = 50))
        assertEquals(0 to 0, splitAccumulatedUsage(totalTokens = -1, completionChars = 50))
    }
}
