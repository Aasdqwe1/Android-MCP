package com.mcp.toolbox

import com.mcp.toolbox.examples.calculator
import com.mcp.toolbox.examples.currentTime
import com.mcp.toolbox.examples.httpRequest
import com.mcp.toolbox.examples.stringLength
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 契约字节稳定测试（对齐官方 contract.go + registry_canon_test.go）。 */
class ContractStabilityTest {

    private fun sampleBox(): Toolbox = Toolbox().apply {
        // 故意乱序注册：契约应按名称排序
        registerAll(currentTime(), httpRequest(), calculator(), stringLength())
        register(editDef())
    }

    private fun editDef(): ToolDef = ToolDef(
        "edit_file",
        "编辑文件",
        mapOf(
            "path" to ParamSpec("path", ParamType.STRING, "路径"),
            "old_string" to ParamSpec("old_string", ParamType.STRING, "旧文本"),
            "new_string" to ParamSpec("new_string", ParamType.STRING, "新文本")
        )
    ) { _ -> "{}" }

    // 契约快照：按名称排序、两次调用字节一致（缓存生效）
    @Test
    fun snapshotIsStableAndSorted() {
        val box = sampleBox()
        val a = box.contractEntries()
        val b = box.contractEntries()
        assertEquals(a, b)
        assertEquals(a.map { it.name }, a.map { it.name }.sorted(), "契约应按工具名排序")
        assertEquals(
            listOf("calculator", "edit_file", "get_current_time", "http_request", "string_length"),
            a.map { it.name }
        )
    }

    // schema 键排序稳定（properties 内部键序稳定）
    @Test
    fun schemaKeysAreCanonical() {
        val box = sampleBox()
        val edit = box.contractEntries().first { it.name == "edit_file" }
        val props = edit.schema["properties"]!!.jsonObject
        assertEquals(listOf("new_string", "old_string", "path"), props.keys.toList(), "schema 键应排序稳定")
    }

    // 注册顺序不影响契约字节
    @Test
    fun registrationOrderDoesNotMatter() {
        val box1 = sampleBox()
        val box2 = Toolbox().apply {
            registerAll(calculator(), stringLength(), httpRequest(), currentTime())
            register(editDef())
        }
        assertEquals(box1.contractEntries(), box2.contractEntries())
    }

    // 注册变化后缓存失效
    @Test
    fun cacheInvalidatesOnRegister() {
        val box = Toolbox().apply { register(calculator()) }
        val before = box.contractEntries().size
        box.register(stringLength())
        assertEquals(before + 1, box.contractEntries().size)
    }

    // 行式清单参数与契约 schema 一致（文档防漂移）
    @Test
    fun lineProtocolMatchesContractParams() {
        val box = sampleBox()
        val entries = box.contractEntries()
        val lineList = ToolCompiler.toProtocolTools(box.all())
        for (e in entries) {
            val schemaParams = e.schema["properties"]!!.jsonObject.keys.toSet()
            val toolLine = lineList.lines().first { it.startsWith("- ${e.name}:") }
            assertTrue(toolLine.isNotEmpty(), "清单应包含 ${e.name}")
            val paramText = lineList.lines().dropWhile { it != toolLine }.drop(1)
                .takeWhile { it.startsWith("    参数: ") }.joinToString(" ")
            if (schemaParams.isEmpty()) {
                assertTrue(paramText.isEmpty(), "${e.name} 无参数时清单不应有参数行")
            } else {
                assertTrue(paramText.isNotEmpty(), "${e.name} 清单应有参数行")
                for (p in schemaParams) assertTrue(paramText.contains("$p("), "${e.name} 参数 $p 应在清单中")
            }
        }
    }

    // canonicalizeSchema：嵌套 properties 也排序
    @Test
    fun canonicalizeNestedObject() {
        val nested = com.mcp.serialization.McpJson.parseToJsonElement(
            """{"type":"object","properties":{"b":{},"a":{}},"required":["b","a"]}"""
        )
        val canon = canonicalizeSchema(nested).jsonObject
        assertEquals(listOf("properties", "required", "type"), canon.keys.toList())
    }
}
