package com.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 新建会话的预设来源 + 偏好不被会话切换污染的防漂移测试。
 *
 * 背景（两层缺陷）：
 *  1) 新建会话曾用 `PresetRuntime.current?.id` 取 presetId。但 current 是进程级「当前会话
 *     生效的预设」，打开任意会话都会经 ChatBridge.applySessionPreset 把它改写。于是
 *     「选极简 → 中途打开过完整模式旧会话 → 新建」会回落完整模式。
 *  2) 更隐蔽的是：applySessionPreset 当初走的是 ToolRuntime.setPreset → PresetRuntime.select，
 *     而 select 会把 preset 写入**全局用户偏好** PresetPrefs。于是即便新建会话改读
 *     userPreferredId（持久化偏好），偏好本身也已被「打开旧会话」这一步覆盖——bug 仍在。
 *
 * 彻底修法：把「偏好写入」从「打开会话时的运行时同步」剥离——
 *  - PresetRuntime.select 增加 persistPreference（默认 true，仅用户主动选择写偏好）；
 *  - ToolRuntime 新增 activatePreset（persistPreference=false），供会话同步使用；
 *  - ChatBridge.applySessionPreset 改走 activatePreset。
 *
 * 本测试锁死上述契约，防止回退。
 */
class PresetPreferenceTest {

    private fun src(rel: String): String {
        val candidates = listOf(File(rel), File("../app/" + rel))
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码 " + rel + "（尝试过: " + candidates.joinToString { it.path } + "）")
        return f.readText().replace("\r\n", "\n")
    }

    private val tabs: String by lazy { src("src/main/java/com/mcp/Tabs.kt") }
    private val runtime: String by lazy { src("src/main/java/com/mcp/preset/PresetRuntime.kt") }
    private val toolRuntime: String by lazy { src("src/main/java/com/mcp/composition/ToolRuntime.kt") }
    private val bridge: String by lazy { src("src/main/java/com/mcp/ChatBridge.kt") }

    private fun body(src: String, signature: String, window: Int): String {
        val at = src.indexOf(signature)
        assertTrue("源码缺少 " + signature, at > 0)
        return src.substring(at, minOf(src.length, at + window))
    }

    // ── 缺陷 1：新建会话取偏好，不取被会话切换污染的 current ──

    @Test
    fun `新建会话用 userPreferredId 而非 current`() {
        assertFalse("Tabs.kt 不应再用 PresetRuntime.current?.id 取新建会话的 presetId",
            tabs.contains("presetId = PresetRuntime.current?.id"))
        assertTrue("新建会话应读 userPreferredId", tabs.contains("PresetRuntime.userPreferredId("))
    }

    @Test
    fun `userPreferredId 读持久化偏好`() {
        val b = body(runtime, "fun userPreferredId(", 200)
        assertTrue("userPreferredId 必须委托 PresetPrefs.selected", b.contains("PresetPrefs.selected("))
        assertFalse("userPreferredId 不得读 current", b.contains("current"))
    }

    // ── 缺陷 2：打开会话不得改写全局用户偏好 ──

    @Test
    fun `applySessionPreset 走 activatePreset 而非 setPreset`() {
        // 整个 ChatBridge 中，applySessionPreset 内不得出现写偏好的 setPreset 调用；
        // 必须调用 activatePreset。
        assertFalse("applySessionPreset 不得调用 toolRuntime.setPreset（会写全局偏好）",
            bridge.contains("runCatching { toolRuntime.setPreset("))
        assertTrue("applySessionPreset 应调用 toolRuntime.activatePreset",
            bridge.contains("toolRuntime.activatePreset("))
    }

    @Test
    fun `activatePreset 不写偏好`() {
        val b = body(toolRuntime, "fun activatePreset(", 200)
        assertTrue("activatePreset 必须传 persistPreference = false",
            b.contains("persistPreference = false"))
    }

    @Test
    fun `setPreset 保留写偏好（用户主动选择）`() {
        // setPreset 走默认参数，即 persistPreference=true，用户主动切换仍要落盘偏好。
        val b = body(toolRuntime, "fun setPreset(", 160)
        assertTrue("setPreset 应委托 PresetRuntime.select", b.contains("PresetRuntime.select("))
        assertFalse("setPreset 不得显式关闭偏好持久化", b.contains("persistPreference = false"))
    }

    // ── select 的开关契约 ──

    @Test
    fun `select 默认持久化偏好`() {
        val b = body(runtime, "fun select(context: Context, runtime: ToolRuntime", 400)
        assertTrue("select 的 persistPreference 默认值应为 true",
            b.contains("persistPreference: Boolean = true"))
        assertTrue("select 必须按开关写入 PresetPrefs",
            b.contains("if (persistPreference) PresetPrefs.select("))
    }
}