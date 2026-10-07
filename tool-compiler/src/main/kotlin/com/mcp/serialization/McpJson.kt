package com.mcp.serialization

import kotlinx.serialization.json.Json

/**
 * 全局 JSON 配置（替代 Android 自带的 org.json.JSONObject/JSONArray）。
 *
 * 相比 org.json 的优势（对应迁移背景中的痛点）：
 *  - [Json.configuration.ignoreUnknownKeys] = true
 *        → 后端新增/调整字段不会让旧客户端解析崩溃（向前兼容）。
 *  - [Json.configuration.coerceInputValues] = true
 *        → 非法或缺失的枚举/基础类型值强制回落到默认值，而非抛异常。
 *  - [Json.configuration.isLenient] = true
 *        → 宽松解析。实测（kotlinx.serialization 1.8.0）支持的宽限：
 *            · 未加引号的 key（如 { a: "x" }）
 *            · 数字前导零（如 007 → 7）
 *            · 浮点前导正负号（如 +1.5 → 1.5，注意：整数前导 + 如 +1 不支持）
 *            · 单引号字符串（注意：解析后值内仍保留字面引号，如 'x' → "'x'"）
 *          明确【不支持】的宽限（与常见误解相反，勿依赖）：
 *            · 尾随逗号、// 或 /* */ 注释、NaN、Infinity。
 *          这正是 org.json 严格模式会崩的“非标准数字/未转义”场景的主要补偿点。
 *
 * 此外编译期生成序列化器（无运行时反射）、类型安全、字段映射编译期检查。
 */
val McpJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    // 不写 encodeDefaults，保持输出精简（缺失字段反序列化时回落默认值，行为一致）。
}
