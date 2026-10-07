package com.mcp.skill

/**
 * 技能脚本源码的文本归一化（纯逻辑，可 JVM 单测）。
 *
 * 为什么需要：脚本是经 stdin 交给解释器执行的——
 *  - bash/sh：`bash -s 'arg' <<'SCRIPT_EOF' <脚本体> SCRIPT_EOF`（见 [SkillManager.buildInvocation]）；
 *  - python3：`python3 -c '<脚本体>' 'arg'`。
 * Windows 上编辑/导入的技能脚本普遍是 CRLF，heredoc 交给 bash 后每一行末尾都多一个 \r：
 * 实测 `if [ -z "$1" ]; then\r` 会直接报 `syntax error near unexpected token`（exit=2），
 * 整条技能脚本工具全废；python3 因源码按行长解析不受影响，但同样一并归一化。
 *
 * 归一化放在**读取处**而不是要求作者改文件：内置技能（assets 里 33 个文件都是 CRLF）
 * 与用户用 SAF 导入/从市场 git clone 的技能都必须能跑。
 */
internal object SkillScriptSource {

    /** 把 CRLF / 孤立 CR 统一成 LF。 */
    fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')
}
