package com.mcp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * multi_edit 参数契约的防漂移测试。
 *
 * 背景：edits 曾声明为 array 且未标 required=false，导致两条路都被校验层堵死：
 *  - 传 edits 数组（行式协议下被解析成 JSON 字符串）→ 期望类型 array, 实际 string；
 *  - 只传 edit_N_old/edit_N_new 编号键 → 缺少必填参数 "edits"。
 * handler 里的 parseMultiEditSpecs 虽已兼容三种形态，却因校验层先拒而永远跑不到。
 *
 * 修法：edits 改 json 类型（不输出 type 约束）+ required = false。本测试锁定该契约。
 */
class MultiEditToolSchemaTest {

    private fun src(rel: String): String {
        val candidates = listOf(File(rel), File("../app/" + rel))
        val f = candidates.firstOrNull { it.exists() }
            ?: error("找不到源码 " + rel + "（尝试过: " + candidates.joinToString { it.path } + "）")
        return f.readText().replace("\r\n", "\n")
    }

    private val fileTools: String by lazy { src("src/main/java/com/mcp/FileTools.kt") }

    // edits 必须是 json 声明：array 在行式协议下会被解析成 string 而遭校验层拒绝
    @Test
    fun `edits 声明为 json 而非 array`() {
        assertTrue("edits 应为 json 声明（array 会被行式协议卡死）", fileTools.contains("json(\"edits\")"))
        assertFalse("edits 不应再声明为 array", fileTools.contains("array(\"edits\", ParamType.OBJECT)"))
    }

    // edits 必须可选：否则只传编号键的路径会被「缺少必填参数」拒绝
    @Test
    fun `edits 是可选参数`() {
        val at = fileTools.indexOf("json(\"edits\")")
        assertTrue("找不到 edits 声明", at > 0)
        val block = fileTools.substring(at, minOf(fileTools.length, at + 700))
        assertTrue("edits 必须标 required = false（编号键路径依赖它）", block.contains("required = false"))
    }

    // handler 的三形态兼容必须保留（编号键是行式协议下的推荐路径）
    @Test
    fun `handler 保留编号键兼容`() {
        assertTrue("parseMultiEditSpecs 丢失编号键正则", fileTools.contains(""""^edit_(\\d+)_(old|new|occurrence)$""""))
    }
}
