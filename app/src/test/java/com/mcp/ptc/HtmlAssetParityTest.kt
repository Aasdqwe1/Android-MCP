package com.mcp.ptc

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两个前端资源（chat.html = WebView，chat_desktop.html = 浏览器）共享链路的「防漂移」测试（B10）。
 *
 * 这两份文件历史上已因手工同步漂移过三次（token 进度条、clear 分支、send 附件策略），
 * 这里把「同源函数体必须逐字节一致」固化成单测，改一侧忘另一侧会立刻红。
 */
class HtmlAssetParityTest {

    /** 必须两侧一致的函数（编辑重发 / 重新生成 / 消息树 / 菜单 / 清理）。 */
    private val shared = listOf(
        "doRegenerate", "doEditResend", "showEditBar", "cancelEdit",
        "assignMessageIds", "addRowMenu", "showCtxMenu", "doFork",
        "purgeDeadMessages", "setBar", "stripLineProtocolCalls",
        // 围栏屏蔽（围栏内一律视为示例）的辅助函数：两侧必须逐字节一致，否则
        // 「后端不执行、前端却剥离」这类漂移会重新出现。
        "isFenceLine", "fenceRanges", "isFencedLine", "stripCallsInText",
        // 多端同步：别端发起的流在本端「中途加入」时建气泡的函数，两端必须一致，
        // 否则手机会话与桌面会话对同一条流的渲染方式会漂移。
        "attachForeignStreamBubble",
        // run_code 卡片：程序内子调用清单必须两端一致（含状态点与转义规则）
        "renderPtcCalls",
        "renderToolCallCard"
    )

    private fun asset(name: String): String {
        val candidates = listOf(File("src/main/assets/$name"), File("../app/src/main/assets/$name"))
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到资源 " + name + "（尝试过: " + candidates.joinToString { it.path } + "）")
        return f.readText().replace("\r\n", "\n")
    }

    private fun functionBody(src: String, name: String): String? {
        val m = Regex("function\\s+" + Regex.escape(name) + "\\s*\\(").find(src) ?: return null
        val open = src.indexOf('{', m.range.last)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { i++; break } }
            }
            i++
        }
        return src.substring(m.range.first, i)
    }

    @Test
    fun `两个 HTML 的共享函数体保持一致`() {
        val webview = asset("chat.html")
        val desktop = asset("chat_desktop.html")
        val diffs = shared.mapNotNull { name ->
            val a = functionBody(webview, name)
            val b = functionBody(desktop, name)
            when {
                a == null && b == null -> "两边都找不到函数: " + name
                a == null -> name + " 只存在于 chat_desktop.html"
                b == null -> name + " 只存在于 chat.html"
                a != b -> name + " 实现已漂移（chat=" + a.length + "B, desktop=" + b.length + "B）"
                else -> null
            }
        }
        assertTrue("前端共享链路出现漂移:\n" + diffs.joinToString("\n"), diffs.isEmpty())
        // stripToolCalls 的函数体里含字符串花括号（'{"tool_calls' 等），brace 匹配不适用；
        // 改为校验「行式调用剥离」这一关键调用点在两侧都存在且一致。
        val stripCall = "result = stripLineProtocolCalls(result);"
        assertTrue("chat.html 缺少行式调用剥离", webview.contains(stripCall))
        assertTrue("chat_desktop.html 缺少行式调用剥离", desktop.contains(stripCall))
    }

    /**
     * 前后端剥离算法一致性契约（B11）。
     *
     * 此前测试只锁「两个 HTML 互相同步」，但前端 JS 与后端 Kotlin 的剥离算法仍可能
     * 各自漂移（例如后端把整段 `.trim()` 换成只裁整行空白的 `trimBlankLines`，前端没跟）。
     * 一旦漂移，同一段文本在「执行路径（后端剥）」与「展示路径（前端剥）」会得到
     * 不同结果——用户看到的气泡与模型拿到的内容不一致。这里锁住两侧的关键标记。
     */
    @Test
    fun `前后端剥离算法使用同一套空白裁剪`() {
        val webview = asset("chat.html")
        val desktop = asset("chat_desktop.html")
        for ((name, src) in listOf("chat.html" to webview, "chat_desktop.html" to desktop)) {
            // 前端必须定义 trimBlankLines 辅助（与后端 com.mcp.toolbox.trimBlankLines 同语义）
            assertTrue("$name 应定义 trimBlankLines", src.contains("function trimBlankLines("))
            // XML 剥离与行式剥离都必须走 trimBlankLines，而不是整段 .trim()
            assertTrue(
                "$name 的 XML 剥离应走 trimBlankLines",
                Regex("return trimBlankLines\\(r\\)").containsMatchIn(src)
            )
            assertTrue(
                "$name 的行式剥离应走 trimBlankLines",
                Regex("out = trimBlankLines\\(out\\.slice").containsMatchIn(src)
            )
        }
        // 后端同源函数：Kotlin 端必须存在 trimBlankLines（internal，供两套协议复用）
        val kotlinXml = run {
            val f = listOf(
                File("src/main/kotlin/com/mcp/toolbox/xmlProtocol.kt"),
                File("../tool-compiler/src/main/kotlin/com/mcp/toolbox/xmlProtocol.kt")
            ).firstOrNull { it.exists() }
            f?.readText() ?: error("找不到 xmlProtocol.kt")
        }
        assertTrue(
            "Kotlin 端应定义 trimBlankLines（internal）",
            kotlinXml.contains("internal fun trimBlankLines(")
        )
    }

    /**
     * ask_user 多问题提交的空答案判定契约（B12）。
     *
     * 曾把「拼接后的问题\n答案」拿去做空判定：多问题时每项都被加上 "[序号] 问题" 前缀，
     * 于是即使用户一个都没答，字符串仍恒非空 —— 走不到「取消」分支，反而把
     * "[1] 问题\n\n[2] 问题\n" 这种空壳当成用户回答喂给模型。
     * 修法：空判定必须基于 collectAnswer 的**原始**结果（不含问题标题）。
     * 这里锁住两侧 HTML 的 doAskSubmit 都按此实现，防止回退。
     */
    @Test
    fun `ask_user 多问题空判定基于原始答案`() {
        val webview = asset("chat.html")
        val desktop = asset("chat_desktop.html")
        for ((name, src) in listOf("chat.html" to webview, "chat_desktop.html" to desktop)) {
            val body = functionBody(src, "doAskSubmit")
                ?: error("$name 找不到 doAskSubmit")
            // 必须先把原始答案收集到 raw，再基于 raw 判空
            assertTrue(
                "$name 的 doAskSubmit 应先用 collectAnswer 收集 raw 原始答案",
                Regex("const raw = askQuestions\\.map\\(\\(q, qi\\) => collectAnswer\\(qi, q\\)\\)").containsMatchIn(body)
            )
            assertTrue(
                "$name 的空判定应基于 raw（原始答案）",
                body.contains("const anyFilled = raw.some(")
            )
            // 禁止回退：空判定不得再基于拼接后的 ans
            assertTrue(
                "$name 的空判定不得基于拼接后的 ans（问题标题会让它恒非空）",
                !body.contains("const anyFilled = ans.some(")
            )
        }
    }
}
