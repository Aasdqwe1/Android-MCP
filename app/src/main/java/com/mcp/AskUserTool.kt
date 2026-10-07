package com.mcp

import com.mcp.toolbox.ParamType
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * ask_user 工具：向用户提问，等待用户输入回答。
 *
 * 支持：
 *  - 单问题：question / type / options / multi_select
 *  - 多问题：questions（数组，每项 {question, type?, options?, multi_select?}）
 *  - 选项支持「标题|描述」格式，前端以「标题 + 简要描述」列表呈现
 *  - multi_select=true 允许多选（checkbox），提交结果以逗号拼接
 *
 * 调用后返回带 action:"ask_user" 的 JSON，ChatBridge 拦截并弹窗等待用户输入，
 * 再把回答作为工具结果返回给 LLM。
 */
fun askUserTool(): ToolDef = tool("ask_user") {
    description = "向用户提问，等待用户输入回答。当需要用户决策、确认、提供额外信息或做出选择时使用。支持一次提出多个问题（questions 数组）；选项支持「标题|描述」形式以列表呈现；多选通过 multi_select=true 开启（用户可勾选多项，提交结果以逗号拼接）。"
    string("question") {
        description = "单个问题文本（与 questions 二选一；提供 questions 时此字段可省略）"
        required = false
    }
    // 刻意**不加** enumValues。
    //
    // 这里曾声明 enum = [text, confirm, choice]，而 JsonSchemaSubset 在 dispatch 入口先于 handler
    // 执行枚举校验——于是模型（尤其 PTC 下）写 multiple / single / radio 这类语义正确的同义词时，
    // 会直接被拒：「取值 "multiple" 不在枚举 [text, confirm, choice] 内」，工具根本进不到 handler，
    // 归一化逻辑永远没机会跑。这不是「模型写错该被纠正」，而是「自然写法被工具名挡在门外」。
    //
    // 改为：schema 不设枚举（放行任意字符串），由 handler 的 normalizeAskType 统一映射到
    // text / confirm / choice 三个合法值——文档承诺的「同义词自动归一化」才真正成立。
    // 代价是模型可能写更离谱的值，但归一化会把无法识别的降级为 text，不会崩。
    string("type") {
        description = "输入类型：text（文本输入，默认）；confirm（确认/取消，是/否）；choice（多项选择，从 options 中选择）。也接受同义词（multiple/checkbox→多选 choice，radio/single→单选 choice，yes_no→confirm），会被自动归一化"
        required = false
    }
    // 与下方 questions 同理：声明为 json，而不是 string。
    // 描述里承诺「也接受字符串数组形式（自动合并）」，handler 的 safeText() 也确实实现了合并，
    // 但 string 会带出 type:string 约束——校验层在 handler 之前就把数组拒了，于是
    // PTC/程序内调用里最自然的写法 ["A|说明","B"] 必然报
    // 「$.options: 期望类型 string, 实际 array」，文档与实现自相矛盾。
    // 改为 json（core.kt 对 JSON 类型不输出 type 约束）后数组放行，统一交给 handler 合并。
    json("options") {
        description = "选择项列表（type=choice 时使用）。可写逗号分隔的字符串（如 A|说明,B），也可写字符串数组（如 [\"A|说明\",\"B\"]），两种等价；每项为「标题」或「标题|描述」"
        required = false
    }
    boolean("multi_select") {
        description = "是否允许多选（type=choice 时有效）。true 时用户可勾选多个选项，提交结果以逗号拼接"
        required = false
    }
    // 注意：这里刻意用 json 而非 array。行式协议（DeepSeek 逆向）下没有「数组参数」的原生表达，
    // 模型输出的 questions 会被解析成 JSON 字符串；若声明为 array，校验层会以
    // 「期望类型 array, 实际 string」直接拒绝，工具根本进不到 handler。
    // 声明为 json（不输出 type 约束）+ handler 内兼容字符串/数组两种形态，是唯一能同时
    // 兼容两种后端的形状。元素结构写进 description 引导模型。
    json("questions") {
        description = "一次提出多个问题：传 JSON 数组，每项为 {question(必填), type?(text/confirm/choice), options?(逗号分隔的「标题」或「标题|描述」), multi_select?(bool)}。与单个 question 字段二选一；同时提供时优先使用 questions。示例：[{\"question\":\"选哪个库\",\"type\":\"choice\",\"options\":\"A|说明,B\"}]"
        required = false
    }
    handler { args ->
        // 参数形状容错：kotlinx 的 .jsonPrimitive/.jsonArray/.jsonObject 对错误形状是「抛异常」
        // 而非返回 null（如 options 传成 JSON 数组时会炸出「...is not a JsonPrimitive」内部错误，
        // 模型拿到无从自愈）。这里统一走安全提取：标量直取，数组拍平合并，其余形状降级为空。
        fun JsonElement?.safeText(): String? = when (this) {
            null -> null
            is JsonPrimitive -> content
            is JsonArray -> filterIsInstance<JsonPrimitive>().joinToString(",") { it.content }
                .takeIf { it.isNotBlank() }
            else -> null
        }
        fun safeQuestions(): List<JsonObject> = when (val q = args["questions"]) {
            is JsonArray -> q.filterIsInstance<JsonObject>()
            is JsonObject -> listOf(q)
            // 行式协议下数组参数会被序列化成 JSON 字符串（见 dsl 里 json 类型的说明），
            // 这里把它还原回对象列表，避免模型明明传对了结构却拿不到问题。
            is JsonPrimitive -> runCatching {
                when (val p = Json.parseToJsonElement(q.content)) {
                    is JsonArray -> p.filterIsInstance<JsonObject>()
                    is JsonObject -> listOf(p)
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            else -> emptyList()
        }

        // type 归一化。前端只实现 text / confirm / choice 三条渲染分支（见 chat.html 的
        // renderSingleQuestion / buildQuestionBlock），非法 type 会静默落到「只渲染一个文本框」
        // 的兜底分支——模型明明给了 options，用户却看不到任何选项，只能盲打。
        // 模型常写 multiple / single / radio / checkbox 这类语义相近的词，这里统一映射到合法枚举；
        // 另外做一次「有选项却写成 text」的纠正，否则选项同样会被前端忽略。
        fun normalizeAskType(raw: String?, hasOptions: Boolean, rawMulti: Boolean): Pair<String, Boolean> {
            val t = raw?.trim()?.lowercase() ?: ""
            val multiWord = t in setOf(
                "multiple", "multi", "multiselect", "multi_select",
                "checkbox", "multi-choice", "multi_choice"
            )
            val base = when (t) {
                "confirm", "yesno", "yes_no", "boolean", "bool" -> "confirm"
                "choice", "select", "single", "radio", "single_choice", "single-choice" -> "choice"
                else -> if (multiWord) "choice" else "text"
            }
            // 有选项就一定是选择题：模型把 type 写成 text（或留空）却给了 options 时按 choice 走。
            val finalType = if (hasOptions && base == "text") "choice" else base
            return finalType to ((rawMulti || multiWord) && finalType == "choice")
        }

        val questions = safeQuestions()
        buildJsonObject {
            put("action", "ask_user")
            if (questions.isNotEmpty()) {
                putJsonArray("questions") {
                    questions.forEach { o ->
                        addJsonObject {
                            val opts = o["options"].safeText()?.takeIf { it.isNotBlank() }
                            val (qType, qMulti) = normalizeAskType(
                                raw = o["type"].safeText(),
                                hasOptions = opts != null,
                                rawMulti = o["multi_select"].safeText()?.equals("true", ignoreCase = true) == true
                            )
                            put("question", o["question"].safeText() ?: "")
                            put("type", qType)
                            opts?.let { put("options", it) }
                            if (qMulti) put("multi_select", true)
                        }
                    }
                }
            } else {
                val opts = args["options"].safeText()?.takeIf { it.isNotBlank() }
                val (qType, qMulti) = normalizeAskType(
                    raw = args["type"].safeText(),
                    hasOptions = opts != null,
                    rawMulti = args["multi_select"].safeText()?.equals("true", ignoreCase = true) == true
                )
                put("question", args["question"].safeText() ?: "请回答：")
                put("type", qType)
                opts?.let { put("options", it) }
                if (qMulti) put("multi_select", true)
            }
            put("status", "awaiting_user_input")
        }.toString()
    }
}
