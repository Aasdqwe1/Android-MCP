package com.mcp.core.skill

/**
 * 基于 Markdown 的知识技能 — 通过 /skill <name> 注入到会话上下文。
 *
 * MarkdownSkill 只提供文档/知识，不包含可执行代码。
 * LLM 收到注入后能利用技能文档中的知识回答用户问题。
 *
 * @param name        技能唯一标识（如 "git"），对应 /skill <name>
 * @param description 简短说明，供列表展示
 * @param content     完整 Markdown 内容（所有文件合并）
 * @param version     版本号
 * @param files       技能包含的文件名列表
 */
data class MarkdownSkill(
    val name: String,
    val description: String,
    val content: String,
    val version: String = "1.0.0",
    val files: List<String> = emptyList()
)
