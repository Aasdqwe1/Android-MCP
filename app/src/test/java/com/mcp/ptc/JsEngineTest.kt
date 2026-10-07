package com.mcp.ptc

import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.tool
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JsEngine 集成测试桩（真实 Rhino + 真实 Toolbox）：证明 P1-D 整改后的契约可用。
 * 覆盖：正常调用 / 返回值 JSON / 日志捕获 / ToolCallError（未知工具、参数违规、handler 抛错、
 * 白名单、次数上限）/ ClassShutter 沙箱 / 未捕获异常的可读错误。
 */
class JsEngineTest {

    private fun box(): Toolbox = Toolbox().apply {
        register(tool("echo") {
            description = "回显"
            string("msg") { description = "文本" }
            handler { args -> "{\"ok\":true,\"msg\":\"" + args["msg"]!!.jsonPrimitive.content + "\"}" }
        })
        register(tool("boom") {
            description = "总是失败"
            handler { throw IllegalStateException("kaboom") }
        })
    }

    private fun run(
        code: String,
        allows: (String) -> Boolean = { true },
        maxCalls: Int = 200
    ): JsEngine.JsResult =
        JsEngine.run(code, box(), allows, maxCalls, 30, "runCode:test", "单测", 0)

    @Test
    fun `正常调用返回结果并捕获日志`() {
        val r = run("""const r = tools.echo({ msg: "hi" }); log(r); return JSON.parse(r).msg;""")
        assertNull(r.err)
        assertTrue("stdout=" + r.stdout, r.stdout.contains("hi"))
        assertEquals("hi", r.ret)
    }

    @Test
    fun `return 对象被 JSON 序列化`() {
        val r = run("""return { a: 1, b: "x" };""")
        assertNull(r.err)
        assertEquals("{\"a\":1,\"b\":\"x\"}", r.ret)
    }

    @Test
    fun `print 与 console log 都被捕获`() {
        val r = run("""print("p1"); console.log("p2"); return 0;""")
        assertNull(r.err)
        assertTrue("stdout=" + r.stdout, r.stdout.contains("p1") && r.stdout.contains("p2"))
    }

    @Test
    fun `未注册工具不在命名空间内（调用即 TypeError）`() {
        // tools 只暴露已注册工具名（_TOOL_NAMES），未注册名是 undefined → TypeError；
        // 「未知工具」的 ToolCallException 语义由 Toolbox.dispatchOrThrow 覆盖（见 tool-compiler 单测）。
        val r = run("""try { tools.nope({}); } catch (e) { log(e.name + "|" + e.message); } return "done";""")
        assertNull(r.err)
        assertTrue("stdout=" + r.stdout, r.stdout.contains("TypeError"))
        assertEquals("done", r.ret)
    }

    @Test
    fun `参数缺必填被 schema 校验拦下`() {
        val r = run("""try { tools.echo({}); } catch (e) { log(e.name + "|" + e.message); } return 1;""")
        assertTrue("stdout=" + r.stdout, r.stdout.contains("ToolCallError|"))
        assertTrue("应汇总缺必填信息: " + r.stdout, r.stdout.contains("缺少必填参数"))
    }

    @Test
    fun `handler 抛错被包装为 ToolCallError`() {
        val r = run("""try { tools.boom({}); } catch (e) { log(e.name + "|" + e.toolName + "|" + e.message); } return 1;""")
        assertTrue("stdout=" + r.stdout, r.stdout.contains("ToolCallError|boom|"))
        assertTrue("stdout=" + r.stdout, r.stdout.contains("kaboom"))
    }

    @Test
    fun `白名单拒绝抛 ToolCallError`() {
        val r = run(
            """try { tools.boom({}); } catch (e) { log(e.name + "|" + e.message); } return 1;""",
            allows = { it == "echo" }
        )
        assertTrue("stdout=" + r.stdout, r.stdout.contains("当前预设不允许"))
    }

    @Test
    fun `子调用次数上限生效`() {
        val r = run("""tools.echo({ msg: "a" }); tools.echo({ msg: "b" }); return 1;""", maxCalls = 1)
        assertTrue("未捕获异常应含上限提示: " + r.err, (r.err ?: "").contains("次数达上限"))
    }

    @Test
    fun `未捕获的 ToolCallError 错误信息可读`() {
        val r = run("""tools.echo({}); return 1;""")
        assertTrue("err=" + r.err, (r.err ?: "").contains("ToolCallError"))
        assertTrue("err=" + r.err, (r.err ?: "").contains("echo"))
        assertTrue("err=" + r.err, (r.err ?: "").contains("缺少必填参数"))
    }

    @Test
    fun `取消请求能中断死循环`() {
        PtcCancellation.clear()
        var result: JsEngine.JsResult? = null
        val runner = Thread {
            result = JsEngine.run(
                """while (true) {}""", box(), { true }, 200, 30, "t", "单测", 0,
                isMainThread = { false }
            )
        }
        runner.isDaemon = true
        runner.start()
        Thread.sleep(120)
        PtcCancellation.request()
        runner.join(5_000)
        PtcCancellation.clear()
        assertTrue("死循环应在取消后 5s 内返回", !runner.isAlive)
        assertTrue("err=" + result?.err, (result?.err ?: "").contains("取消"))
    }

    @Test
    fun `主线程调用被拒绝（防 UI 阻塞与死锁）`() {
        val r = JsEngine.run(
            """return 1;""", box(), { true }, 200, 30, "t", "单测", 0,
            isMainThread = { true }
        )
        assertTrue("err=" + r.err, (r.err ?: "").contains("主线程"))
    }

    @Test
    fun `ClassShutter 拒绝 Java 类访问且 host 函数不暴露反射面`() {
        val r = run(
            """var blocked; try { new java.io.File("/"); blocked = "LEAK"; } catch (e) { blocked = "BLOCKED"; } log(blocked + "|" + typeof _dispatch.getClass); return "ok";"""
        )
        assertNull(r.err)
        assertTrue("沙箱应拒绝 java.io.File: " + r.stdout, r.stdout.contains("BLOCKED"))
        assertTrue("host 函数不应暴露 getClass: " + r.stdout, r.stdout.contains("undefined"))
    }

    // ── Java 访问陷阱：把「JavaPackage 怪错」换成可自愈的边界提示 ─────────────
    // 真实现场：模型写 java.util.Base64.getEncoder()，拿到的是
    // 「Cannot call property getEncoder in object [JavaPackage java.util.Base64]」——
    // 读起来像「这个类没这个方法」，它意识不到是沙箱边界，只会换写法继续撞。

    @Test
    fun `访问 java 类给出可自愈错误而非 JavaPackage 怪错`() {
        val r = run(
            """try { java.util.Base64.getEncoder(); } catch (e) { log("CAUGHT:" + String(e.message || e)); } return "done";"""
        )
        assertNull(r.err)
        assertTrue("应明确提示禁止访问 Java 类，实际 stdout=" + r.stdout, r.stdout.contains("禁止访问 Java 类"))
        assertTrue("不应再出现 JavaPackage 字样，实际 stdout=" + r.stdout, !r.stdout.contains("JavaPackage"))
        assertEquals("done", r.ret)
    }

    @Test
    fun `Packages 与 importClass 入口同样给出明确提示`() {
        val r = run(
            """try { Packages.java.util.Base64; } catch (e) { log("A:" + String(e.message || e)); }
try { importClass(java.io.File); } catch (e) { log("B:" + String(e.message || e)); }
try { Java.type("java.lang.String"); } catch (e) { log("C:" + String(e.message || e)); }
return "done";"""
        )
        assertNull(r.err)
        assertTrue("Packages 应被拦下，实际 stdout=" + r.stdout, r.stdout.contains("A:") && r.stdout.contains("禁止访问 Java 类"))
        assertTrue("importClass 应被拦下，实际 stdout=" + r.stdout, r.stdout.contains("B:") && r.stdout.contains("禁止访问 Java 类"))
        assertTrue("Java.type 应被拦下，实际 stdout=" + r.stdout, r.stdout.contains("C:") && r.stdout.contains("禁止访问 Java 类"))
    }

    // ── 语法错误链路：行号必须是「程序第 N 行」，且不能裸异常逃出 run() ──────────
    // 真实现场：模型写 for (const f of files)（现代 JS 最自然的写法），Rhino 解析失败，
    // 报的是「syntax error (…#53)」——#53 是**包装后**源码的行号（前置 51 行脚手架），
    // 对应它自己程序的第 2 行。模型看不出这层关系，只能原样再写一遍。

    @Test
    fun `语法错误报用户坐标的行号并回显出错行`() {
        // 第 2 行出错
        val r2 = run("const a = 1;\nfor (const f of ['x']) { print(f); }")
        assertNotNull("应返回 JsResult.err，而不是抛异常", r2.err)
        assertTrue("应报用户坐标的行号，err=" + r2.err, (r2.err ?: "").contains("程序第 2 行"))
        assertTrue("应回显出错行原文，err=" + r2.err, (r2.err ?: "").contains("for (const f of ['x'])"))

        // 第 5 行出错：确认行号映射不是「恰好蒙对第 2 行」
        val r5 = run("var a = 1;\nvar b = 2;\nvar c = 3;\nvar d = 4;\nfor (const f of [1]) {}")
        assertTrue("应报程序第 5 行，err=" + r5.err, (r5.err ?: "").contains("程序第 5 行"))
        assertTrue("应回显第 5 行原文，err=" + r5.err, (r5.err ?: "").contains("for (const f of [1])"))
    }

    @Test
    fun `for of 的 const 形式给出可照抄的改法`() {
        val bad = run("for (const f of ['a','b']) { print(f); }")
        assertNotNull("const 形式应报语法错误", bad.err)
        assertTrue("应提示换成 let/var，err=" + bad.err, (bad.err ?: "").contains("换成 let 或 var"))

        // 实测确认 let / var 形式在同一个 Rhino 上确实可用——提示的改法是能跑通的，不是瞎猜
        val good = run("for (let f of ['a','b']) { print(f); } return 'ok';")
        assertNull("let 形式应可用，err=" + good.err, good.err)
        assertEquals("ok", good.ret)
    }

    @Test
    fun `解析期错误不再以裸异常逃出 run`() {
        val r = run("class A {}")
        assertNotNull("应在 JsResult.err 里返回，而不是穿透 run()", r.err)
        assertTrue("class 应给出改法，err=" + r.err, (r.err ?: "").contains("function 构造器"))
    }

    @Test
    fun `加了陷阱后正常工具调用与日志不受影响`() {
        val r = run("""log("still-ok"); const x = tools.echo({ msg: "hi" }); return JSON.parse(x).msg;""")
        assertNull(r.err)
        assertTrue("stdout=" + r.stdout, r.stdout.contains("still-ok"))
        assertEquals("hi", r.ret)
    }
}
