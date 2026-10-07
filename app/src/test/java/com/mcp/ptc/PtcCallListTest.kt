package com.mcp.ptc

import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * run_code 卡片「程序内部调用了哪些工具、每个成没成」的端到端契约测试。
 *
 * 链路：Toolbox 子调用 → [PtcCallSummary] 摘要 → run_code 结果 JSON 的 calls/tool_calls
 *      → 前端 `renderPtcCalls` 渲染进工具卡片（样式复用 .tc-* 状态色）。
 * 这里既跑真实 Rhino 验证后端产出的清单，也把前端渲染函数抽出来在 Rhino 里真跑一遍，
 * 避免「后端字段改名 / 前端读不到」这类只在真机上才暴露的漂移。
 */
class PtcCallListTest {

    private fun file(rel: String): File {
        val candidates = listOf(File(rel), File("../app/" + rel))
        return candidates.firstOrNull { it.exists() }
            ?: error("找不到文件 " + rel + "（尝试过: " + candidates.joinToString { it.path } + "）")
    }

    private fun text(rel: String): String = file(rel).readText().replace("\r\n", "\n")

    /** 抽出 `function name(...) { ... }` 的完整函数体（花括号配平）。 */
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

    private fun box(): Toolbox = Toolbox().apply {
        register(tool("echo") {
            description = "回显"
            string("msg") { description = "文本" }
            handler { args -> "{\"ok\":true,\"msg\":\"" + args["msg"] + "\"}" }
        })
        register(tool("boom") {
            description = "总是失败"
            handler { throw IllegalStateException("kaboom") }
        })
    }

    /** 用真实 Rhino 执行前端函数（与 run_code 的 JS 引擎同款），返回渲染结果字符串。 */
    private fun evalJs(jsSource: String, fn: String, arg: String): String {
        val cx = org.mozilla.javascript.Context.enter()
        try {
            // Rhino 默认语言版本是 ES5：不显式切到 ES6，前端代码里的 let/const/箭头函数
            // 会被判成语法错误（"missing ; before statement"），测试会误报为前端有问题。
            cx.languageVersion = org.mozilla.javascript.Context.VERSION_ES6
            cx.optimizationLevel = -1
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, jsSource, "renderPtcCalls", 1, null)
            val f = scope.get(fn, scope) as org.mozilla.javascript.Function
            val out = f.call(cx, scope, scope, arrayOf<Any>(arg))
            return org.mozilla.javascript.Context.toString(out)
        } finally {
            org.mozilla.javascript.Context.exit()
        }
    }

    // ── 后端：子调用清单真的被采集 ─────────────────────────────────────────

    @Test
    fun `JsEngine 回传子调用清单（工具名与成败）`() {
        val r = JsEngine.run(
            """
            try { tools.echo({ msg: "a" }); } catch (e) {}
            try { tools.boom({}); } catch (e) {}
            try { tools.nope({}); } catch (e) {}
            return "done";
            """.trimIndent(),
            box(), { true }, 200, 30, "runCode:test", "单测", 0
        )
        assertNull("err=" + r.err, r.err)
        // 未注册的工具在 JS 侧就是 undefined（TypeError），根本到不了 dispatch —— 不进清单。
        assertEquals("清单只含真正分发过的子调用", 2, r.calls.size)
        assertEquals("总次数", 2, r.callCount)
        assertEquals(listOf("echo", "boom"), r.calls.map { it.name })
        assertTrue("成功的子调用应为 ok", r.calls[0].ok)
        assertTrue("handler 抛错应为 !ok", !r.calls[1].ok)
        assertTrue("失败行必须带错误信息（前端要显示）", r.calls[1].error?.contains("kaboom") == true)
        assertTrue("耗时字段应被填充（可为 0）", r.calls.all { it.ms >= 0 })

        // 预设白名单拒绝：分发发生但被拦，同样要出现在清单里且标记为失败
        val denied = JsEngine.run(
            """try { tools.echo({ msg: "a" }); } catch (e) {} return 0;""",
            box(), { false }, 200, 30, "runCode:test", "单测", 0
        )
        assertEquals("白名单拒绝也要进清单", 1, denied.calls.size)
        assertTrue("白名单拒绝应标记失败", !denied.calls[0].ok)
        assertTrue("白名单拒绝应带原因", denied.calls[0].error?.contains("预设") == true)
    }

    @Test
    fun `子调用摘要序列化为前端可读字段`() {
        val json = listOf(
            PtcCallSummary("read_file", ok = true, ms = 12),
            PtcCallSummary("write_file", ok = false, ms = 34, error = "权限不足"),
        ).toJsonArray().toString()
        assertTrue(json, json.contains("\"name\":\"read_file\""))
        assertTrue(json, json.contains("\"ok\":true"))
        assertTrue(json, json.contains("\"ms\":12"))
        assertTrue(json, json.contains("\"error\":\"权限不足\""))
        assertTrue("成功的调用不该带 error（省体积）", !Regex("\"read_file\",\"ok\":true,\"ms\":12,\"error").containsMatchIn(json))
    }

    @Test
    fun `run_code 两个分支的结果都带 calls 与 tool_calls`() {
        val src = text("src/main/java/com/mcp/ptc/RunCodeTool.kt")
        assertEquals(
            "协议/JS 两个执行分支都要写入子调用清单",
            2, Regex("put\\(\"calls\", ").findAll(src).count()
        )
        assertEquals(
            "两个分支都要写入总次数（JS 分支原先缺 tool_calls）",
            2, Regex("put\\(\"tool_calls\", ").findAll(src).count()
        )
        assertTrue("超出清单上限要有截断标记", src.contains("put(\"calls_truncated\", true)"))
    }

    // ── 前端：两端渲染函数一致且真能渲染出来 ────────────────────────────────

    @Test
    fun `两端卡片渲染同一份子调用清单（含状态与转义）`() {
        val webview = text("src/main/assets/chat.html")
        val desktop = text("src/main/assets/chat_desktop.html")
        val a = functionBody(webview, "renderPtcCalls")
        val b = functionBody(desktop, "renderPtcCalls")
        assertTrue("chat.html 缺少 renderPtcCalls", a != null)
        assertTrue("chat_desktop.html 缺少 renderPtcCalls", b != null)
        assertEquals("两端 renderPtcCalls 实现已漂移", a, b)
        for ((name, html) in listOf("chat.html" to webview, "chat_desktop.html" to desktop)) {
            assertTrue("$name 的卡片未接入子调用清单", html.contains("html += renderPtcCalls(resultText);"))
            assertTrue("$name 缺少清单样式", html.contains(".ptc-dot.tc-ok") && html.contains(".ptc-dot.tc-error"))
            assertTrue("$name 缺少清单容器样式", html.contains(".ptc-call {"))
        }

        val js = functionBody(webview, "escapeHtml")!! + "\n" + a!!
        val result = """
            {"ok":true,"tool_calls":3,"calls_truncated":true,"calls":[
              {"name":"read_file","ok":true,"ms":12},
              {"name":"write_file","ok":false,"ms":34,"error":"权限不足"},
              {"name":"<script>bad</script>","ok":true,"ms":1}]}
        """.trimIndent()
        val out = evalJs(js, "renderPtcCalls", result)
        assertTrue(out, out.contains("程序内调用（3）"))
        assertTrue("清单被截断时要说明", out.contains("仅列前 3 条"))
        assertTrue("成功行要有 tc-ok 状态点", out.contains("ptc-dot tc-ok"))
        assertTrue("失败行要有 tc-error 状态点", out.contains("ptc-dot tc-error"))
        assertTrue("要列出工具名", out.contains("read_file") && out.contains("write_file"))
        assertTrue("失败行要显示错误", out.contains("权限不足"))
        assertTrue("成功行要显示耗时", out.contains("12 ms"))
        assertTrue("工具名必须转义（子调用名来自程序，不可信）", out.contains("&lt;script&gt;") && !out.contains("<script>"))

        // 非 run_code 结果（无 calls）/ 非法 JSON：不渲染任何东西，卡片保持原样
        assertEquals("", evalJs(js, "renderPtcCalls", """{"ok":true,"path":"/a.txt"}"""))
        assertEquals("", evalJs(js, "renderPtcCalls", "not-json"))
        assertEquals("", evalJs(js, "renderPtcCalls", """{"ok":true,"calls":[]}"""))
    }
}
