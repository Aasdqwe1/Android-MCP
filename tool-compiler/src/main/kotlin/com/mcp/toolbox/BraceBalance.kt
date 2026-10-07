package com.mcp.toolbox

/**
 * delete_range 的代码完整性校验（对齐官方 delete_range.go 的花括号配对检查）。
 *
 * 删除代码类文件中的区间前，校验被删区间内的花括号 `{}` 是否配对：
 * 若区间把代码块切开（左花括号没闭合、右花括号没开头），删除会产生残缺语法，
 * 应拒绝并引导改用 edit_file。校验跳过注释（行注释、块注释、井号注释）与字符串（单引号、双引号、反引号）。
 */
object BraceBalance {

    /** 判定区间 [start, end)（原始文本下标）是否为「花括号配对」的完整块。 */
    fun deleteIsBalanced(text: String, start: Int, end: Int): Boolean {
        if (start < 0 || end > text.length || start > end) return false
        var depth = 0
        var i = start
        var state = CharState.CODE
        while (i < end) {
            val c = text[i]
            when (state) {
                CharState.CODE -> when {
                    c == '/' && i + 1 < end && text[i + 1] == '/' -> { state = CharState.LINE_COMMENT; i++ }
                    c == '/' && i + 1 < end && text[i + 1] == '*' -> { state = CharState.BLOCK_COMMENT; i++ }
                    c == '#' -> state = CharState.LINE_COMMENT
                    c == '"' -> state = CharState.DQ_STRING
                    c == '\'' -> state = CharState.SQ_STRING
                    c == '`' -> state = CharState.BT_STRING
                    c == '{' -> depth++
                    c == '}' -> {
                        depth--
                        if (depth < 0) return false  // 多余的右花括号：区间起点在代码块内部
                    }
                }
                CharState.LINE_COMMENT -> if (c == '\n') state = CharState.CODE
                CharState.BLOCK_COMMENT -> if (c == '*' && i + 1 < end && text[i + 1] == '/') { state = CharState.CODE; i++ }
                CharState.DQ_STRING -> when (c) {
                    '\\' -> i++
                    '"' -> state = CharState.CODE
                }
                CharState.SQ_STRING -> when (c) {
                    '\\' -> i++
                    '\'' -> state = CharState.CODE
                }
                CharState.BT_STRING -> if (c == '`') state = CharState.CODE
            }
            i++
        }
        return depth == 0
    }

    private enum class CharState { CODE, LINE_COMMENT, BLOCK_COMMENT, DQ_STRING, SQ_STRING, BT_STRING }
}
