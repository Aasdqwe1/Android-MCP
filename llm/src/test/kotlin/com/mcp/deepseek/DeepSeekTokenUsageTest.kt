package com.mcp.deepseek

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * DeepSeek SSE token 用量解析测试。
 *
 * 数据来源：/storage/emulated/0/Work/ds_sse_raw.txt（真机抓包，total=48）。
 * 覆盖三条契约：
 *  1. accumulated_token_usage 能从根 SET + BATCH 补丁中还原出最终值；
 *  2. 未携带该字段时返回 -1（调用方据此跳过 Usage 发射）；
 *  3. BATCH 补丁确实更新了还原树中的值（不是读到流开头的 0）。
 *
 * 注意：llm/build.gradle.kts 引入真实 org.json（testImplementation）——
 * Android android.jar 里的 org.json 是 stub，本地单测会得到空结果。
 */
class DeepSeekTokenUsageTest {

    /** 真机抓包第 7 行：流开头的根 SET，accumulated_token_usage 初始为 0。 */
    private val rootSetFrame: String = buildJson(
        "\"v\":{\"response\":{\"message_id\":2,\"parent_id\":1,\"role\":\"ASSISTANT\"," +
            "\"status\":\"WIP\",\"accumulated_token_usage\":0," +
            "\"fragments\":[{\"id\":2,\"type\":\"RESPONSE\",\"content\":\"你好\"}]}}"
    )

    /** 真机抓包第 33 行：流结尾 BATCH 补丁，回填真实 total=48。 */
    private val batchFrame: String = buildJson(
        "\"p\":\"response\",\"o\":\"BATCH\",\"v\":[" +
            "{\"p\":\"accumulated_token_usage\",\"v\":48}," +
            "{\"p\":\"quasi_status\",\"v\":\"FINISHED\"}]"
    )

    private fun buildJson(body: String) = "{$body}"

    @Test
    fun accumulatedTokenUsage_readsFinalValueFromBatchPatch() {
        val patcher = SSEPatcher()
        patcher.feedEvent(null, JSONObject(rootSetFrame), rootSetFrame)
        // 流开头：根 SET 里是 0
        assertEquals(0, patcher.accumulatedTokenUsage())
        // 流结尾：BATCH 补丁回填真实值
        patcher.feedEvent(null, JSONObject(batchFrame), batchFrame)
        assertEquals(48, patcher.accumulatedTokenUsage())
    }

    @Test
    fun accumulatedTokenUsage_returnsMinusOneWhenAbsent() {
        val patcher = SSEPatcher()
        // 只喂文本增量帧，不带任何 token 字段
        val textFrame = buildJson(
            "\"p\":\"response/fragments/-1/content\",\"o\":\"APPEND\",\"v\":\"！\""
        )
        patcher.feedEvent(null, JSONObject(textFrame), textFrame)
        assertEquals(-1, patcher.accumulatedTokenUsage())
    }

    @Test
    fun accumulatedTokenUsage_handlesStringValue() {
        val patcher = SSEPatcher()
        val rootSet = buildJson("\"v\":{\"response\":{\"accumulated_token_usage\":\"120\"}}")
        patcher.feedEvent(null, JSONObject(rootSet), rootSet)
        assertEquals(120, patcher.accumulatedTokenUsage())
    }

    @Test
    fun render_stillWorksAlongsideTokenParsing() {
        val patcher = SSEPatcher()
        patcher.feedEvent(null, JSONObject(rootSetFrame), rootSetFrame)
        val appendFrame = buildJson(
            "\"p\":\"response/fragments/-1/content\",\"o\":\"APPEND\",\"v\":\"！\""
        )
        patcher.feedEvent(null, JSONObject(appendFrame), appendFrame)
        val (think, content) = patcher.render()
        assertEquals("", think)
        assertEquals("你好！", content)
        // token 字段独立于文本渲染，互不干扰
        patcher.feedEvent(null, JSONObject(batchFrame), batchFrame)
        assertEquals(48, patcher.accumulatedTokenUsage())
        assertEquals("你好！", patcher.render().second)
    }
}
