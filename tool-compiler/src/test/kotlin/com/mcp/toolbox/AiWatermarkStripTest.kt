package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 回归测试：DeepSeek 服务端追加的 AI 生成标识必须被正确剥离。
 *
 * 历史 bug（commit 6c2685b2 引入）：正则结尾误写为 \\$（字面美元符）而非 $（行尾锚定），
 * 导致 AI_WATERMARK_RE 永远不匹配，水印粘在最后一个参数值上（如
 * language="javascript本回答由 AI 生成，内容仅供参考，请仔细甄别"），
 * 触发 run_code 报 unsupported language。
 *
 * 这些用例锁定「水印必须被剥离」，防止再次被误改。
 */
class AiWatermarkStripTest {

    private val wm = "本回答由 AI 生成，内容仅供参考，请仔细甄别"

    /** 水印紧贴参数值末尾（日志中的真实形态）→ 只保留有效值。 */
    @Test
    fun watermark_gluedToValue_stripped() {
        assertEquals("javascript", stripAiWatermark("javascript" + wm))
    }

    /** 正常值不受影响（无副作用）。 */
    @Test
    fun plainValue_unchanged() {
        assertEquals("javascript", stripAiWatermark("javascript"))
        assertEquals("run_code", stripAiWatermark("run_code"))
        assertEquals("", stripAiWatermark(""))
    }

    /** 正文里提到「由 AI 生成」但未以标识短语结尾 → 不误伤。 */
    @Test
    fun bodyMention_notStripped() {
        val s = "本内容由 AI 生成的技术报告已完成"
        assertEquals(s, stripAiWatermark(s))
    }

    /** 水印前的空白被一并裁掉，不留尾随空格。 */
    @Test
    fun trailingWhitespace_trimmed() {
        assertEquals("javascript", stripAiWatermark("javascript   " + wm))
    }
}
