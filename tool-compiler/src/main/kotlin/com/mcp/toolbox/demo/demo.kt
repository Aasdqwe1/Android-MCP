package com.mcp.toolbox.demo

import com.mcp.toolbox.PrettyJson
import com.mcp.toolbox.ToolCompiler
import com.mcp.toolbox.Toolbox
import com.mcp.toolbox.examples.SampleAnnotatedTools
import com.mcp.toolbox.examples.calculator
import com.mcp.toolbox.examples.currentTime
import com.mcp.toolbox.examples.httpRequest
import com.mcp.toolbox.examples.sampleAnnotatedTools
import com.mcp.toolbox.examples.stringLength
import com.mcp.toolbox.ToolScanner
import kotlinx.coroutines.runBlocking

fun main() {
    // 1) 声明并注册工具：DSL 风格 + 注解风格混用，对用户都极简
    val box = Toolbox().apply {
        registerAll(
            calculator(),
            currentTime(),
            stringLength(),
            httpRequest()
        )
        sampleAnnotatedTools().forEach { register(it) }
        // 也可以直接扫描任意对象的 @Tool 方法
        ToolScanner.scan(SampleAnnotatedTools()).forEach { register(it) }
    }

    println("已注册工具：${box.names().joinToString()}\n")

    // 2) 「编译」成喂给 LLM 的工具定义
    println("════ 给 OpenAI / DeepSeek 的 tools 定义 ════")
    println(PrettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ToolCompiler.toOpenAi(box.all())))
    println("════ 给 MCP 的 tools/list 定义 ════")
    println(PrettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ToolCompiler.toMcpToolsList(box.all())))

    // 3) 模拟 LLM 发起一次工具调用，框架负责解析参数、分发执行、格式化返回
    println("════ 模拟 LLM 调用 ════")
    runBlocking {
        println("calculator(\"(2+3)*4-1\") -> " + box.dispatch("calculator", """{"expression":"(2+3)*4-1"}"""))
        println("greet({\"name\":\"MCP\",\"loud\":true}) -> " + box.dispatch("greet", """{"name":"MCP","loud":true}"""))
        println("unknown_tool(...) -> " + box.dispatch("nope", "{}"))
    }
}
