package com.mcp.toolbox

/**
 * 按 1-based 行号替换文本区间。
 *
 * 输入文本的换行风格会被保留；newContent 可以是单行或多行内容，末尾换行不会额外制造空行。
 */
data class LineRangeEditResult(
    val text: String,
    val oldText: String,
    val newText: String,
    val startLine: Int,
    val endLine: Int
) {
    val replacedLength: Int get() = oldText.length
    val newLength: Int get() = newText.length
}

fun applyLineRangeEdit(
    text: String,
    startLine: Int,
    endLine: Int,
    newContent: String
): LineRangeEditResult {
    require(startLine >= 1) { "start_line 必须从 1 开始。" }
    require(endLine >= startLine) { "end_line 必须大于或等于 start_line。" }

    val newline = if (text.contains("\r\n")) "\r\n" else "\n"
    val hadFinalNewline = text.endsWith("\n") || text.endsWith("\r")
    val normalizedLines = text.replace("\r\n", "\n").replace('\r', '\n')
    val contentLines = normalizedLines.split('\n').let { lines ->
        if (hadFinalNewline && lines.lastOrNull() == "") lines.dropLast(1) else lines
    }
    require(endLine <= contentLines.size) {
        "行号超出范围（共 ${contentLines.size} 行）。请重新 read_file 获取最新行号。"
    }

    val replacementLines = if (newContent.isEmpty()) {
        emptyList()
    } else {
        newContent
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .let { lines ->
                if (newContent.endsWith("\n") || newContent.endsWith("\r")) lines.dropLast(1) else lines
            }
    }
    val oldLines = contentLines.subList(startLine - 1, endLine)
    val resultLines = buildList(contentLines.size - oldLines.size + replacementLines.size) {
        addAll(contentLines.subList(0, startLine - 1))
        addAll(replacementLines)
        addAll(contentLines.subList(endLine, contentLines.size))
    }
    val result = if (resultLines.isEmpty()) {
        ""
    } else {
        resultLines.joinToString(newline) + if (hadFinalNewline) newline else ""
    }

    return LineRangeEditResult(
        text = result,
        oldText = oldLines.joinToString(newline),
        newText = replacementLines.joinToString(newline),
        startLine = startLine,
        endLine = endLine
    )
}
