package com.mcp

import com.mcp.compaction.ContextCompactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Token 看板数据链路一致性测试桩。
 *
 * 覆盖三条数据源链路：
 *   A. 后端 buildTokenConfig → 前端 TOKEN_CONFIG（totalContext / model）
 *   B. 后端 flushTokenFlow → 前端 tokenFlow 事件（prompt / completion / cumulative）
 *   C. 前端本地计算（currentUsed / threshold / free）
 *
 * 验证三条链路在 maxInput 设置、profile 配置跟随、跨会话累计三个场景下的数据一致性。
 */
class TokenMetricsDataChainTest {

    private val compactor = ContextCompactor()

    // ── 场景 A：maxInput 导致后端/前端阈值不一致 ─────────────────────────────

    /**
     * 后端 compactThreshold = min(window * 0.8, maxInput)
     * 前端 threshold = total * 0.8（不含 maxInput）
     *
     * 当 maxInput < window * 0.8 时，两者不一致。
     */
    @Test
    fun thresholdMismatchWhenMaxInputSetBelowWindowRatio() {
        val model = "gpt-4o"
        val window = 128_000
        val maxInput = 32_000

        // 后端 compactThreshold 的等价计算
        val backendThreshold = compactor.thresholdTokens(
            model = model,
            windowOverride = window,
            maxInputOverride = maxInput
        )

        // 前端阈值：total * 0.8
        val total = effectiveContextWindow(model, window)
        val frontendThreshold = (total * 0.8).toInt()

        println("[A] window=$window maxInput=$maxInput")
        println("    backend threshold  = $backendThreshold")
        println("    frontend threshold = $frontendThreshold")
        println("    mismatch = ${backendThreshold != frontendThreshold}")

        // 预期：maxInput 压低了后端阈值，前端不知道
        assertEquals(32_000, backendThreshold)
        assertEquals(102_400, frontendThreshold)
        assertTrue(backendThreshold < frontendThreshold)
    }

    /** maxInput=0（未设置）时，前后端阈值一致。 */
    @Test
    fun thresholdMatchesWhenMaxInputUnset() {
        val model = "gpt-4o"
        val window = 128_000

        val backendThreshold = compactor.thresholdTokens(
            model = model,
            windowOverride = window,
            maxInputOverride = 0
        )
        val frontendThreshold = (window * 0.8).toInt()

        println("[A-2] window=$window maxInput=0")
        println("    backend threshold  = $backendThreshold")
        println("    frontend threshold = $frontendThreshold")

        assertEquals(frontendThreshold, backendThreshold)
    }

    // ── 场景 B：窗口跟随当前 active profile（不做会话创建快照） ─────────────

    @Test
    fun windowFollowsActiveProfileNoSessionSnapshot() {
        val model = "gpt-4o"

        // 1. 当前 profile 手填 256k → 用它（会话创建时的旧快照不再参与）
        assertEquals(256_000, effectiveContextWindow(model, 256_000))

        // 2. 切换档案后改填 64k → 立即跟随新 profile
        assertEquals(64_000, effectiveContextWindow(model, 64_000))

        // 3. 未配置 → 0（前端显示 “—”），绝不按模型名推断：
        //    避免模型表规则（gemini→1M、nemotron-3-ultra→1_048_576 等）冒出与设置页不一致的总窗口
        assertEquals(0, effectiveContextWindow(model, 0))
        assertEquals(0, effectiveContextWindow("nemotron-3-ultra-free", 0))
        println("[B] 窗口跟随当前 active profile；未配置 → 0（不再冒出模型表值如 1M）")
    }

    // ── 场景 C：used 精度混用 ──────────────────────────────────────────────

    @Test
    fun usedPrecisionMixingChineseInput() {
        val inputText = "你好世界".repeat(100)

        val frontendEstimate = estimateInputTokens(inputText)
        val providerCount = inputText.length

        println("[C] input: ${inputText.length} chars (中文密集)")
        println("    frontend estimate  = $frontendEstimate")
        println("    provider count     ≈ $providerCount")
        println("    deviation          = ${Math.abs(frontendEstimate - providerCount)}")

        // 前端估算约为 provider 的一半（中文 /2 vs 实际 /1）
        assertTrue(frontendEstimate < providerCount)
        assertTrue(frontendEstimate > providerCount * 0.4)

        // 混用场景：输入框有 inputText.length 个中文字符
        val lastPromptTokens = 5000
        val actualInputTokens = inputText.length  // provider 约 1 token/中文
        val usedLow = lastPromptTokens + frontendEstimate  // 偏低（/2 估算）
        val usedAccurate = lastPromptTokens + actualInputTokens  // 准确
        println("    used (frontend)  = $usedLow")
        println("    used (accurate)  = $usedAccurate")
        println("    deviation        = ${usedAccurate - usedLow}")
        assertTrue(usedAccurate > usedLow)
    }

    @Test
    fun usedPrecisionMixingCodeInput() {
        val codeText = "var x = 1; function foo() { return x + 2; }".repeat(50)

        val frontendEstimate = estimateInputTokens(codeText)
        val estimatedProviderCount = codeText.length

        println("[C-2] input: ${codeText.length} chars (代码密集)")
        println("    frontend estimate  = $frontendEstimate")
        println("    estimated provider ≈ $estimatedProviderCount")
        println("    deviation          ≈ ${estimatedProviderCount - frontendEstimate}")

        assertTrue(frontendEstimate < estimatedProviderCount * 0.3)
    }

    // ── 场景 D：跨会话累计污染 ──────────────────────────────────────────────

    @Test
    fun crossSessionCumulativeContamination() {
        var tokenBoardCumulative = 0

        // 会话 A：3 轮请求
        val sessionA = listOf(
            Pair(1000, 500),
            Pair(2000, 800),
            Pair(1500, 700)
        )

        for ((prompt, completion) in sessionA) {
            tokenBoardCumulative += prompt + completion
        }
        println("[D] session A cumulative = $tokenBoardCumulative")

        // 切换到会话 B：文件累计 2000
        val sessionBFileCumulative = 2000
        println("[D] session B file cumulative = $sessionBFileCumulative")
        println("[D] frontend shows = $sessionBFileCumulative")
        println("[D] backend still = $tokenBoardCumulative")

        // 会话 B 发一轮请求
        val sessionBRequest = 500
        val sessionBCompletion = 300
        tokenBoardCumulative += sessionBRequest + sessionBCompletion
        val pollutedCumulative = tokenBoardCumulative

        println("[D] after session B request: backend = $pollutedCumulative")
        println("[D] expected (reset to B):     = ${sessionBFileCumulative + sessionBRequest + sessionBCompletion}")

        val expected = sessionBFileCumulative + sessionBRequest + sessionBCompletion
        // A 累计 = 1500+2800+2200 = 6500；B 增量 = 800
        assertEquals(7300, pollutedCumulative)
        assertEquals(2800, expected)
        assertTrue(pollutedCumulative > expected)
        println("[D] contamination delta = ${pollutedCumulative - expected}")
    }

    // ── 辅助 ───────────────────────────────────────────────────────────────

    /**
     * 模拟 ChatBridge.effectiveContextWindow：看板总窗口 = 当前 active profile 手填值；
     * 不做会话创建快照、不做模型表推断；未配置 = 0（前端显示 “—”）。
     */
    private fun effectiveContextWindow(@Suppress("UNUSED_PARAMETER") model: String, profileWindow: Int): Int =
        if (profileWindow > 0) profileWindow else 0

    /**
     * 模拟前端 estimateInputTokens：中文/2，其它/4，+12 基线。
     */
    private fun estimateInputTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (ch in '㐀'..'鿿' || ch in '　'..'〿' || ch in '＀'..'￯') cjk++
            else other++
        }
        return Math.round(cjk / 2.0 + other / 4.0 + 12).toInt()
    }
}
