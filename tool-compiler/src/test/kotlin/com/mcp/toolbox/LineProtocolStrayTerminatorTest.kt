package com.mcp.toolbox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 尾部多余的 `>>>` 不该作废一条完整合法的调用。
 *
 * 真机事故（2026-09-21）：模型输出
 * ```
 * tool_call: run_code
 * id: call_25
 * description: ...
 * code <<<
 * ...
 * >>>
 * >>>          ← 多写了这一行
 * ```
 * 旧实现据此抛 ToolArgsParseException → 上层把整份结果换成 __parse_error__ → 端点 502。
 * 一个多余字符换来一次硬失败。现在按「本段到此结束」处理，调用照常送达。
 */
class LineProtocolStrayTerminatorTest {

    @Test
    fun `尾部多一个结束符仍然保住这条调用`() {
        val text = """
            |tool_call: run_code
            |id: call_25
            |description: Verify hash
            |code <<<
            |return 1;
            |>>>
            |>>>
        """.trimMargin()
        val calls = parseToolCallLineProtocol(text)
        assertEquals(1, calls.size)
        assertEquals("call_25", calls[0].callId)
        assertEquals("run_code", calls[0].name)
    }
}
