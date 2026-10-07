package com.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.mcp.serialization.McpJson
import com.mcp.toolbox.BraceBalance
import com.mcp.toolbox.EditBatchResult
import com.mcp.toolbox.EditSpec
import com.mcp.toolbox.ParamType
import com.mcp.toolbox.ReceiptSpan
import com.mcp.toolbox.SingleEditResult
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.applySequentialEdits
import com.mcp.toolbox.applySingleEdit
import com.mcp.toolbox.renderPostWriteReceipts
import com.mcp.toolbox.replaceAllOccurrences
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import com.mcp.FileLockManager

// ─────────────────────────────────────────────────────────────
//  路径解析（能力接缝，见 CapabilitySeam.kt）
// ─────────────────────────────────────────────────────────────

/**
 * 智能获取文件访问目标的统一入口：收敛到 [CapabilityRegistry].fs 接缝。
 * 具体解析逻辑由当前 [FsProvider]（默认 [LocalFsProvider]）实现，Provider 可换。
 */
internal fun resolveTarget(ctx: Context, path: String): Pair<FileTarget, String?> =
    CapabilityRegistry.fs.resolveTarget(ctx, path)



// ─────────────────────────────────────────────────────────────
//  写入辅助（健壮性）
// ─────────────────────────────────────────────────────────────

/** 单次写入上限（10 MB），防止异常大内容撑爆存储或卡死。 */
private const val MAX_WRITE_BYTES = 10 * 1024 * 1024

/**
 * 缺少必填参数时抛出的自愈异常：message 面向模型，直接回灌即可引导其重发合法调用。
 *
 * 背景：此前工具 handler 用 `args["path"]!!` 取必填参数，一旦上游解析把 arguments
 * 退化成 `{}`（例如参数内容自带 ``` 围栏导致 JSON 被腰斩），`!!` 会抛出 message 为 null 的
 * NullPointerException，经 dispatch 的 `e.message ?: e::class.simpleName` 兜底后
 * 只剩字面量 "NullPointerException" —— 模型和用户都无从判断出了什么问题，也无法自愈。
 */
internal class MissingArgException(argName: String, toolName: String) : Exception(
    "调用 $toolName 缺少必填参数「$argName」。请重新输出该工具调用，确保包含 $argName 参数，然后重试。"
)

/** 取必填字符串参数；缺失时抛 [MissingArgException]（由 handler 的 runCatching 捕获成可读错误）。 */
internal fun JsonObject.requireStr(name: String, tool: String): String =
    (this[name] ?: throw MissingArgException(name, tool)).jsonPrimitive.content

/**
 * 原子写入文本：先写临时文件，再 rename 到目标，避免写入中途失败损坏原文件
 * （直接 writeText 会先清空再写，进程中断就会丢失原文）。借鉴编辑器/commons-io 的“安全保存”套路。
 */
private fun writeFileAtomic(file: File, text: String) {
    val parent = file.parentFile ?: file.absoluteFile.parentFile
    parent?.mkdirs()
    val tmp = File(parent ?: file.absoluteFile.parentFile, "${file.name}.tmp.${System.nanoTime()}")
    try {
        tmp.writeText(text, Charsets.UTF_8)
        if (file.exists() && !file.delete()) {
            // 原文件删不掉（被占用等），退化为流式覆盖
            file.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } }
        } else if (!tmp.renameTo(file)) {
            // 跨文件系统 renameTo 失败，退化为复制后删临时
            file.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } }
        }
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}

// ─────────────────────────────────────────────────────────────
//  编辑撤销历史
// ─────────────────────────────────────────────────────────────

/**
 * 进程内的文件编辑撤销历史（多级 undo）。
 *
 * 按「文件路径」维护一个 LIFO 栈，每个节点保存该文件某次写盘前的完整旧内容。
 * 写盘类工具（write_file / edit_file / multi_edit / delete_range）在真正修改文件前
 * 先调用 [EditHistory.record] 记录旧内容；`undo_edit` 工具再按 [EditHistory.pop]
 * 弹出最近一次（或多次）旧内容并写回，让子 Agent 改坏后能恢复。
 *
 * 说明：进程内存实现，历史随进程存续；主要用于「同一会话内」的即时回滚。
 * 路径 key 统一使用 `File.absolutePath`（FileRef）或 SAF 的 `displayPath`，
 * 与各写盘工具 `loadEditable`/`resolveTarget` 产出的展示路径保持一致，保证可命中。
 */
private object EditHistory {
    /** 每个文件最多保留的撤销层级，超出丢弃最老快照。 */
    private const val MAX_DEPTH = 20
    private val stacks = ConcurrentHashMap<String, ArrayDeque<String>>()

    /** 记录某文件写盘前的旧内容（key = 展示路径，见类注释）。 */
    fun record(key: String, before: String) {
        val stack = stacks.getOrPut(key) { ArrayDeque() }
        synchronized(stack) {
            stack.addLast(before)
            while (stack.size > MAX_DEPTH) stack.removeFirst()
        }
    }

    /**
     * 弹出 [levels] 级旧内容，返回应写回的内容。
     * @return 若无历史捕获到内容则返回 null。
     */
    fun pop(key: String, levels: Int): String? {
        val stack = stacks[key] ?: return null
        synchronized(stack) {
            if (stack.isEmpty()) return null
            var result: String? = null
            repeat(minOf(levels, stack.size)) { result = stack.removeLast() }
            return result
        }
    }

    /** 剩余可撤销层级数（用于回执展示，-1 表示无记录）。 */
    fun remaining(key: String): Int = stacks[key]?.size ?: -1
}

// ─────────────────────────────────────────────────────────────
//  哈希辅助
// ─────────────────────────────────────────────────────────────

/** 计算 UTF-8 文本的 SHA-256 十六进制小写字符串。 */
private fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** 返回 SHA-256 前 6 位短哈希，用于行级锚定。 */
private fun shaShort(line: String): String = sha256(line).take(6)

/** 行级前缀：`199[abc123]: content`。hash 是行内容的短哈希，作为不可变锚点。 */
private fun linePrefix(lineNum: Int, line: String): String = "$lineNum[${shaShort(line)}]: $line"

/**
 * 按 return_format 渲染行范围：annotated → LINE[HASH] 前缀（阅读与定位）；
 * raw → 纯文本（可直接作为 edit_file 的 old_string，无需剥前缀）。
 * matchLine 用于 annotated 模式给匹配行加 ★ 标记。
 */
private fun renderLines(lines: List<String>, range: IntRange, annotated: Boolean, matchLine: Int? = null): String {
    val start = range.first
    val end = range.last
    if (start < 1 || end > lines.size || start > end) return ""
    return if (annotated) {
        lines.subList(start - 1, end).mapIndexed { i, l ->
            val actualLine = start + i
            if (actualLine == matchLine) "★ $l" else linePrefix(actualLine, l)
        }.joinToString("\n")
    } else {
        lines.subList(start - 1, end).joinToString("\n")
    }
}

/** 结构化行输出，避免调用方解析 LINE[HASH] 文本前缀。 */
private fun renderStructuredLines(lines: List<String>, range: IntRange): JsonArray {
    val start = range.first
    val end = range.last
    if (start < 1 || end > lines.size || start > end) return JsonArray(emptyList())
    return JsonArray(lines.subList(start - 1, end).mapIndexed { index, line ->
        JsonObject(
            mapOf(
                "num" to JsonPrimitive(start + index),
                "content" to JsonPrimitive(line)
            )
        )
    })
}

/**
 * 智能块展开：尝试将 [lineIdx] 所在行展开到其所属的函数/类/块边界。
 *
 * 策略分层（通用启发式，不依赖语法）：
 * 1. 花括号匹配：找到前一个 `{` 和后一个匹配的 `}`
 * 2. 缩进对齐：向上找同缩进或更小缩进的「定义行」（fun/def/class/val等），向下找同缩进或更小缩进的「结束行」
 * 3. 空行段落：回退到空行分割的段落
 *
 * @return Pair(startIdx, endIdx) 块起始和结束的行索引（0-based，含两端）
 */
private fun expandToBlock(lines: List<String>, lineIdx: Int): Pair<Int, Int> {
    if (lines.isEmpty()) return 0 to 0
    val total = lines.size
    val targetLine = lines[lineIdx]
    val targetIndent = targetLine.length - targetLine.trimStart().length

    // 策略 1：花括号匹配 — 找到最近的 `{` 行前和 `}` 行后
    var braceStart = lineIdx
    var braceEnd = lineIdx

    // 向前找 `{`（从当前行往前，找到最近的左花括号所在行，它是块的开头）
    var openBraceFound = false
    for (i in lineIdx downTo 0) {
        if (lines[i].contains("{")) {
            braceStart = i
            openBraceFound = true
            break
        }
    }
    // 向后找匹配的 `}`
    if (openBraceFound) {
        var depth = 0
        var started = false
        for (i in braceStart..<total) {
            val l = lines[i]
            for (c in l) {
                if (c == '{') { depth++; started = true }
                if (c == '}') depth--
            }
            if (started && depth <= 0) {
                braceEnd = i
                break
            }
        }
    }

    // 策略 2：缩进对齐 — 找同缩进的定义行（向上）和空行/更小缩进（向下）
    var indentStart = lineIdx
    var indentEnd = lineIdx

    // 字段/方法/类定义关键词
    val defKeywords = Regex("""^\s*(fun|def|function|class|struct|enum|interface|trait|object|type|val|var|const|let|import|package|namespace|module|public|private|protected|abstract|open|data|sealed|override|inline|suspend|tailrec|operator|infix)\b""")

    // 向上：找到同缩进的关键词定义行，或更小缩进的行
    for (i in lineIdx downTo 0) {
        val trimmed = lines[i].trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue
        val indent = lines[i].length - trimmed.length
        if (indent <= targetIndent) {
            indentStart = i
            // 如果是定义行，停在这里
            if (defKeywords.containsMatchIn(trimmed)) break
            // 如果是空行或注释，继续向上
        }
    }
    // 向下：找到第一个空行或更小缩进的行
    for (i in lineIdx..<total) {
        val trimmed = lines[i].trim()
        if (trimmed.isEmpty() || trimmed == "}") break
        val indent = lines[i].length - trimmed.length
        if (indent < targetIndent && trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("#")) {
            indentEnd = i - 1
            break
        }
        indentEnd = i
    }

    // 策略 3：空行段落
    var paraStart = lineIdx
    var paraEnd = lineIdx
    for (i in lineIdx downTo maxOf(0, lineIdx - 20)) {
        paraStart = i
        if (lines[i].trim().isEmpty()) { paraStart = i + 1; break }
    }
    for (i in lineIdx..<minOf(total, lineIdx + 20)) {
        paraEnd = i
        if (lines[i].trim().isEmpty()) { paraEnd = i - 1; break }
    }

    // 选择最优结果：优先花括号（范围最大），其次缩进，最后段落
    val braceSpan = braceEnd - braceStart
    val indentSpan = indentEnd - indentStart
    val paraSpan = paraEnd - paraStart

    return when {
        braceSpan > 0 && braceSpan <= 500 -> braceStart to braceEnd
        indentSpan > 0 && indentSpan <= 200 -> indentStart to indentEnd
        else -> paraStart to paraEnd
    }
}

// ─────────────────────────────────────────────────────────────
//  文件操作工具
// ─────────────────────────────────────────────────────────────

/**
 * 读取文件，支持分层读取策略。
 *
 * 模式：
 * - `normal`（默认）：按行号范围读取，支持 `expand_blocks` 自动展开到语义块边界
 * - `head_tail`：读取头 N 行和尾 N 行，中间折叠，适合大文件快速预览
 *
 * 输出格式：每行带短哈希锚点 `LINE[HASH]: content`，hash 是行内容的不可变指纹。
 * 全文输出 `sha256` 用于 edit_file 乐观锁。
 */
fun readFileTool(context: Context): ToolDef = tool("read_file") {
    description = "读取文件内容，支持分层读取策略。默认返回带短哈希锚点的行前缀（LINE[HASH]: content）及全文 SHA-256；return_format=raw 时返回纯文本内容，可直接作为 edit_file 的 old_string。路径为绝对路径或相对于应用内部存储（filesDir）。"
    string("path") { description = "文件路径（绝对路径 或 相对于 filesDir 的路径）" }
    string("mode") {
        description = "读取模式：normal（默认）按行号范围读取；head_tail 读取头尾、中间折叠"
        required = false
        enumValues = listOf("normal", "head_tail")
    }
    integer("start_line") {
        description = "起始行号（1-based，包含）。normal 模式使用"
        required = false
    }
    integer("end_line") {
        description = "结束行号（1-based，包含）。normal 模式使用。不填则读到末尾"
        required = false
    }
    integer("head_lines") {
        description = "head_tail 模式：从头部读取的行数，默认 50"
        required = false
    }
    integer("tail_lines") {
        description = "head_tail 模式：从尾部读取的行数，默认 50"
        required = false
    }
    string("expand_blocks") {
        description = "是否自动展开到函数/类/块边界（true/false，默认 false）。启用后，读取范围会扩展到完整的语义块，避免截断在函数中间。"
        required = false
        enumValues = listOf("true", "false")
    }
    string("content_pattern") {
        description = "内容锚定读取（取代行号模式）：搜索关键词（大小写不敏感），自动展开到所属语义块（函数/类/段落），匹配行以 ★ 前缀标记（非 emoji，是输出锚点符号）。与 expand_blocks 配合使用效果最佳。提供此参数时忽略 start_line/end_line。"
        required = false
    }
    string("return_format") {
        description = "返回格式：annotated（默认）带 LINE[HASH] 行号前缀；raw 返回纯文本内容；lines 返回结构化行数组（num/content）"
        required = false
        enumValues = listOf("annotated", "raw", "lines")
    }
    handler { args ->
        withContext(Dispatchers.IO) {
          runCatching {
            val path        = args.requireStr("path", "read_file")
            val mode        = args["mode"]?.jsonPrimitive?.content ?: "normal"
            val startLine   = args["start_line"]?.jsonPrimitive?.content?.toIntOrNull()
            val endLine     = args["end_line"]?.jsonPrimitive?.content?.toIntOrNull()
            val headLines   = (args["head_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceIn(1, 500)
            val tailLines   = (args["tail_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceIn(1, 500)
            val expandBlocks = args["expand_blocks"]?.jsonPrimitive?.content == "true"
            val contentPattern = args["content_pattern"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            val returnFormat = args["return_format"]?.jsonPrimitive?.content ?: "annotated"
            val annotated = returnFormat == "annotated"
            val structuredLines = returnFormat == "lines"

            if (structuredLines && mode == "head_tail") {
                return@runCatching """{"error":"return_format=lines 暂不支持 head_tail，请使用 normal 模式并传 start_line/end_line。"}"""
            }

            val (target, err) = resolveTarget(context, path)
            if (err != null) return@runCatching """{"error":"$err"}"""

            when (target) {
                is FileTarget.FileRef -> {
                    val file = target.file
                    if (!file.exists()) return@runCatching """{"error":"文件不存在: ${file.absolutePath}"}"""
                    if (!file.isFile)   return@runCatching """{"error":"不是文件: ${file.absolutePath}"}"""
                    // 直接读取原始内容计算哈希，避免 readLines() 丢失尾随换行后
                    // 再 joinToString("\n") + "\n" 强制补回换行导致哈希失真。
                    val fullText = file.readText(Charsets.UTF_8)
                    val lines = if (fullText.isEmpty()) emptyList() else fullText.lines()
                    val total = lines.size
                    val fileHash = sha256(fullText)

                    if (!contentPattern.isNullOrBlank()) {
                        // 内容锚定读取：搜索关键词并展开语义块
                        val matchIndices = mutableListOf<Int>()
                        for ((idx, line) in lines.withIndex()) {
                            if (line.contains(contentPattern, ignoreCase = true)) matchIndices.add(idx)
                        }
                        if (matchIndices.isEmpty()) {
                            return@runCatching """{"error":"未找到匹配的内容: ${JsonPrimitive(contentPattern)}"}"""
                        }
                        val firstMatch = matchIndices.first()
                        val ctx = (args["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 500)
                        val (bs, be) = if (ctx > 0) {
                            maxOf(0, firstMatch - ctx) to minOf(total - 1, firstMatch + ctx)
                        } else {
                            expandToBlock(lines, firstMatch)
                        }
                        if (structuredLines) {
                            val blockLines = renderStructuredLines(lines, (bs + 1)..(be + 1))
                            return@runCatching """{"path":"${file.absolutePath}","sha256":"$fileHash","total_lines":$total,"match_line":${firstMatch + 1},"match_count":${matchIndices.size},"block_start":${bs + 1},"block_end":${be + 1},"lines":$blockLines}"""
                        }
                        val blockContent = renderLines(lines, (bs + 1)..(be + 1), annotated, matchLine = firstMatch + 1)
                        return@runCatching """{"path":"${file.absolutePath}","sha256":"$fileHash","total_lines":$total,"match_line":${firstMatch + 1},"match_count":${matchIndices.size},"block_start":${bs + 1},"block_end":${be + 1},"content":${JsonPrimitive(blockContent)}}"""
                    }

                    if (mode == "head_tail") {
                        val headCount = minOf(headLines, total)
                        val tailCount = minOf(tailLines, total - headCount)
                        val sb = StringBuilder()
                        var s = 1
                        var e = headCount
                        for (i in s..e) {
                            if (i <= total) sb.appendLine(if (annotated) linePrefix(i, lines[i - 1]) else lines[i - 1])
                        }
                        val folded = total - headCount - tailCount
                        if (folded > 0) {
                            sb.appendLine("... ($folded lines skipped)")
                        }
                        s = total - tailCount + 1
                        e = total
                        for (i in s..e) {
                            if (i >= 1) sb.appendLine(if (annotated) linePrefix(i, lines[i - 1]) else lines[i - 1])
                        }
                        val content = sb.toString().trimEnd()
                        """{"path":"${file.absolutePath}","sha256":"$fileHash","total_lines":$total,"mode":"head_tail","head_lines":$headCount,"tail_lines":$tailCount,"folded":$folded,"content":${JsonPrimitive(content)}}"""
                    } else {
                        var s = (startLine ?: 1).coerceIn(1, maxOf(total, 1))
                        var e = (endLine ?: total).coerceIn(s, total)
                        // expand_blocks
                        if (expandBlocks && lines.isNotEmpty()) {
                            val centerIdx = (s - 1 + e - 1) / 2
                            val (blockStart, blockEnd) = expandToBlock(lines, centerIdx)
                            s = blockStart + 1
                            e = blockEnd + 1
                        }
                        if (structuredLines) {
                            val lineJson = renderStructuredLines(lines, s..e)
                            """{"path":"${file.absolutePath}","sha256":"$fileHash","total_lines":$total,"start":$s,"end":$e,"lines":$lineJson}"""
                        } else {
                            val content = renderLines(lines, s..e, annotated)
                            if (!annotated) {
                            // raw 格式：只返回原始文本，直接可用于 edit_file old_string。
                            content
                            } else {
                                """{"path":"${file.absolutePath}","sha256":"$fileHash","total_lines":$total,"start":$s,"end":$e,"content":${JsonPrimitive(content)}}"""
                            }
                        }
                    }
                }
                is FileTarget.SafRef -> {
                    val doc = target.doc
                    if (!doc.exists()) return@runCatching """{"error":"文件不存在: ${target.displayPath}"}"""
                    if (!doc.isFile)   return@runCatching """{"error":"不是文件: ${target.displayPath}"}"""
                    val fullText = SafManager.readText(doc)
                    val lines = fullText.lines()
                    val total = lines.size
                    val fileHash = sha256(fullText)

                    if (!contentPattern.isNullOrBlank()) {
                        // 内容锚定读取：搜索关键词并展开语义块
                        val matchIndices = mutableListOf<Int>()
                        for ((idx, line) in lines.withIndex()) {
                            if (line.contains(contentPattern, ignoreCase = true)) matchIndices.add(idx)
                        }
                        if (matchIndices.isEmpty()) {
                            return@runCatching """{"error":"未找到匹配的内容: ${JsonPrimitive(contentPattern)}"}"""
                        }
                        val firstMatch = matchIndices.first()
                        val ctx = (args["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceIn(0, 500)
                        val (bs, be) = if (ctx > 0) {
                            maxOf(0, firstMatch - ctx) to minOf(total - 1, firstMatch + ctx)
                        } else {
                            expandToBlock(lines, firstMatch)
                        }
                        if (structuredLines) {
                            val blockLines = renderStructuredLines(lines, (bs + 1)..(be + 1))
                            return@runCatching """{"path":"${target.displayPath}","sha256":"$fileHash","total_lines":$total,"match_line":${firstMatch + 1},"match_count":${matchIndices.size},"block_start":${bs + 1},"block_end":${be + 1},"lines":$blockLines}"""
                        }
                        val blockContent = renderLines(lines, (bs + 1)..(be + 1), annotated, matchLine = firstMatch + 1)
                        return@runCatching """{"path":"${target.displayPath}","sha256":"$fileHash","total_lines":$total,"match_line":${firstMatch + 1},"match_count":${matchIndices.size},"block_start":${bs + 1},"block_end":${be + 1},"content":${JsonPrimitive(blockContent)}}"""
                    }

                    if (mode == "head_tail") {
                        val headCount = minOf(headLines, total)
                        val tailCount = minOf(tailLines, total - headCount)
                        val sb = StringBuilder()
                        var s = 1
                        var e = headCount
                        for (i in s..e) {
                            if (i <= total) sb.appendLine(if (annotated) linePrefix(i, lines[i - 1]) else lines[i - 1])
                        }
                        val folded = total - headCount - tailCount
                        if (folded > 0) sb.appendLine("... ($folded lines skipped)")
                        s = total - tailCount + 1
                        e = total
                        for (i in s..e) {
                            if (i >= 1) sb.appendLine(if (annotated) linePrefix(i, lines[i - 1]) else lines[i - 1])
                        }
                        val content = sb.toString().trimEnd()
                        """{"path":"${target.displayPath}","sha256":"$fileHash","total_lines":$total,"mode":"head_tail","head_lines":$headCount,"tail_lines":$tailCount,"folded":$folded,"content":${JsonPrimitive(content)}}"""
                    } else {
                        var s = (startLine ?: 1).coerceIn(1, maxOf(total, 1))
                        var e = (endLine ?: total).coerceIn(s, total)
                        if (expandBlocks && lines.isNotEmpty()) {
                            val centerIdx = (s - 1 + e - 1) / 2
                            val (blockStart, blockEnd) = expandToBlock(lines, centerIdx)
                            s = blockStart + 1
                            e = blockEnd + 1
                        }
                        if (structuredLines) {
                            val lineJson = renderStructuredLines(lines, s..e)
                            """{"path":"${target.displayPath}","sha256":"$fileHash","total_lines":$total,"start":$s,"end":$e,"lines":$lineJson}"""
                        } else {
                            val content = renderLines(lines, s..e, annotated)
                            if (!annotated) {
                            // raw 格式：只返回原始文本，直接可用于 edit_file old_string。
                            content
                            } else {
                                """{"path":"${target.displayPath}","sha256":"$fileHash","total_lines":$total,"start":$s,"end":$e,"content":${JsonPrimitive(content)}}"""
                            }
                        }
                    }
                }
                is FileTarget.None -> """{"error":"无权限访问该路径"}"""
            }
          }.getOrElse { e -> """{"error":"读取失败: ${e.message?.take(300)?.replace("\"", "'")}"}""" }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  文件差异计算（行级 LCS + 字符级 LCS，供 write_file / edit_file 结果展示）
// ─────────────────────────────────────────────────────────────

private const val MAX_DIFF_LINES = 600        // 单侧最多比对行数（超出则开窗/截断）
private const val MAX_DIFF_LINE_CHARS = 4000  // 单行文本在 diff 中的最大长度（超出截断）
private const val MAX_CHAR_DIFF_CHARS = 240   // 仅对不超过此长度的配对行做字符级高亮
private const val DIFF_CTX_LINES = 3           // hunk 上下文行数（GitHub -U3 风格）
private const val MAX_DIFF_BYTES = 4096        // diff 数组总预算（变更行优先，ctx 按剩余）

/** 单行 diff 操作：' ' 上下文、'-' 删除、'+' 增加。 */
private data class LineOp(val type: Char, val text: String)

/** 行级 LCS（DP 回溯），返回从 old→new 的编辑脚本。 */
private fun computeLineDiff(oldLines: List<String>, newLines: List<String>): List<LineOp> {
    val m = oldLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            dp[i][j] = if (oldLines[i] == newLines[j]) dp[i + 1][j + 1] + 1
                       else if (dp[i + 1][j] >= dp[i][j + 1]) dp[i + 1][j] else dp[i][j + 1]
        }
    }
    val ops = ArrayList<LineOp>(m + n)
    var i = 0; var j = 0
    while (i < m && j < n) {
        when {
            oldLines[i] == newLines[j] -> { ops.add(LineOp(' ', oldLines[i])); i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { ops.add(LineOp('-', oldLines[i])); i++ }
            else -> { ops.add(LineOp('+', newLines[j])); j++ }
        }
    }
    while (i < m) { ops.add(LineOp('-', oldLines[i])); i++ }
    while (j < n) { ops.add(LineOp('+', newLines[j])); j++ }
    return ops
}

/** 字符级 LCS：返回 (a 中被删字符区间, b 中新增字符区间)，区间半开 [start,end)。 */
private fun charChangeRanges(a: String, b: String): Pair<List<IntArray>, List<IntArray>> {
    val m = a.length; val n = b.length
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in m - 1 downTo 0) {
        for (j in n - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1
                       else if (dp[i + 1][j] >= dp[i][j + 1]) dp[i + 1][j] else dp[i][j + 1]
        }
    }
    val changedA = BooleanArray(m)
    val changedB = BooleanArray(n)
    var i = 0; var j = 0
    while (i < m && j < n) {
        when {
            a[i] == b[j] -> { i++; j++ }
            dp[i + 1][j] >= dp[i][j + 1] -> { changedA[i] = true; i++ }
            else -> { changedB[j] = true; j++ }
        }
    }
    while (i < m) { changedA[i] = true; i++ }
    while (j < n) { changedB[j] = true; j++ }
    return rangesFromMask(changedA) to rangesFromMask(changedB)
}

private fun rangesFromMask(mask: BooleanArray): List<IntArray> {
    val out = ArrayList<IntArray>()
    var i = 0
    while (i < mask.size) {
        if (mask[i]) {
            var j = i
            while (j < mask.size && mask[j]) j++
            out.add(intArrayOf(i, j))
            i = j
        } else i++
    }
    return out
}

/** 字符串 JSON 转义（把行文本安全嵌入结果 JSON）。 */
private fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    sb.append('"')
    for (ch in s) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> {
                if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code))
                else sb.append(ch)
            }
        }
    }
    sb.append('"')
    return sb.toString()
}

/** 单行渲染为 diff 数组元素 JSON。c 为可选的字符级变更区间 [start,end)。 */
private fun rowJson(t: Char, o: Int, n: Int, raw: String, c: List<IntArray>): String {
    val text = if (raw.length > MAX_DIFF_LINE_CHARS) raw.take(MAX_DIFF_LINE_CHARS) + "…" else raw
    val cJson = if (c.isEmpty()) "" else {
        ",\"c\":[" + c.joinToString(",") { "[${it[0]},${it[1]}]" } + "]"
    }
    return "{\"t\":\"$t\",\"o\":$o,\"n\":$n,\"s\":${jsonEscape(text)}$cJson}"
}

/**
 * 计算 oldText→newText 的差异，输出供前端渲染的 JSON 数组字符串。
 * 行级 LCS 定位增删；对相近的「删/增」配对行再做字符级 LCS 高亮行内变更。
 * 大文件仅比对以 oldOffset 起始的最多 maxLines 行（edit_file 围绕编辑位置开窗）。
 */
private fun computeDiff(oldText: String, newText: String, maxLines: Int = MAX_DIFF_LINES, oldOffset: Int = 0, newOffset: Int = 0): String {
    val oldLines = if (oldText.isEmpty()) emptyList() else oldText.lines()
    val newLines = if (newText.isEmpty()) emptyList() else newText.lines()
    val a = if (oldOffset == 0 && oldLines.size <= maxLines) oldLines else oldLines.drop(oldOffset).take(maxLines)
    val b = if (newOffset == 0 && newLines.size <= maxLines) newLines else newLines.drop(newOffset).take(maxLines)
    val ops = computeLineDiff(a, b)

    // 行号标注（窗口内行号 + 偏移）
    data class Row(val type: Char, val text: String, val oldNo: Int, val newNo: Int)
    val rows = ArrayList<Row>()
    var oldNo = 0; var newNo = 0
    for (op in ops) {
        when (op.type) {
            '-' -> { oldNo++; rows.add(Row('-', op.text, oldOffset + oldNo, 0)) }
            '+' -> { newNo++; rows.add(Row('+', op.text, 0, newOffset + newNo)) }
            else -> { oldNo++; newNo++; rows.add(Row(' ', op.text, oldOffset + oldNo, newOffset + newNo)) }
        }
    }

    // 变更块（-/+ 连续段）
    val blocks = ArrayList<IntArray>()
    var i = 0
    while (i < rows.size) {
        if (rows[i].type != ' ') {
            val s = i
            while (i < rows.size && rows[i].type != ' ') i++
            blocks.add(intArrayOf(s, i - 1))
        } else i++
    }

    // 预计算变更行的字符级高亮（块内 del/add 配对）
    val charC = HashMap<Int, List<IntArray>>()
    for (b in blocks) {
        val dels = ArrayList<Int>(); val adds = ArrayList<Int>()
        for (x in b[0]..b[1]) if (rows[x].type == '-') dels.add(x) else adds.add(x)
        val pairs = minOf(dels.size, adds.size)
        for (k in 0 until pairs) {
            val di = dels[k]; val ai = adds[k]
            if (rows[di].text.length <= MAX_CHAR_DIFF_CHARS && rows[ai].text.length <= MAX_CHAR_DIFF_CHARS) {
                val (dc, ac) = charChangeRanges(rows[di].text, rows[ai].text)
                charC[di] = dc; charC[ai] = ac
            }
        }
    }

    // 显示区：变更行必显示；ctx 仅显示块前后 DIFF_CTX_LINES 行（GitHub hunk 风格）
    val show = BooleanArray(rows.size)
    for (b in blocks) {
        for (x in b[0]..b[1]) show[x] = true
        for (x in (b[0] - DIFF_CTX_LINES).coerceAtLeast(0) until b[0]) if (rows[x].type == ' ') show[x] = true
        for (x in (b[1] + 1) until (b[1] + 1 + DIFF_CTX_LINES).coerceAtMost(rows.size)) if (rows[x].type == ' ') show[x] = true
    }

    // 单遍输出：变更行必留（预算优先），ctx 行按剩余预算，未显示/超预算段折叠为省略标记
    val out = ArrayList<String>()
    var used = 0
    var omittedRun = 0
    fun flushOmitted() {
        if (omittedRun > 0) {
            out.add("{\"t\":\"…\",\"o\":0,\"n\":0,\"omitted\":$omittedRun}")
            omittedRun = 0
            used += 20
        }
    }
    for (idx in rows.indices) {
        val r = rows[idx]
        if (r.type != ' ') {
            flushOmitted()
            val s = rowJson(r.type, r.oldNo, r.newNo, r.text, charC[idx] ?: emptyList())
            out.add(s); used += s.toByteArray(Charsets.UTF_8).size
        } else if (show[idx]) {
            val est = 24 + r.text.length * 2
            if (used + est <= MAX_DIFF_BYTES) {
                flushOmitted()
                val s = rowJson(' ', r.oldNo, r.newNo, r.text, emptyList())
                out.add(s); used += s.toByteArray(Charsets.UTF_8).size
            } else {
                omittedRun++
            }
        } else {
            omittedRun++
        }
    }
    flushOmitted()

    val truncated = (oldLines.size > maxLines && oldOffset == 0) || (newLines.size > maxLines && newOffset == 0)
    val head = if (truncated) rowJson(' ', 0, 0, "… 文件超过 $maxLines 行，仅展示前 $maxLines 行差异", emptyList()) else null
    val all = if (head != null) listOf(head) + out else out
    return "[" + all.joinToString(",") + "]"
}

/**
 * 写入文件（创建/覆盖 或 追加）。
 */
fun writeFileTool(context: Context): ToolDef = tool("write_file") {
    description = "写入文件。mode=overwrite（默认）创建或覆盖整个文件；mode=append 追加到末尾。单次写入上限 ${MAX_WRITE_BYTES / 1024 / 1024} MB。"
    string("path") { description = "文件路径" }
    string("content") {
        description = "要写入的内容（完整文件内容）"
        multiLine = true
    }
    string("mode") {
        description = "写入模式：overwrite（覆盖，默认）或 append（追加）"
        required = false
        enumValues = listOf("overwrite", "append")
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                // 必填参数在保护网内取值，缺失→可读自愈错误（详见 MissingArgException）
                val path    = args.requireStr("path", "write_file")
                val content = args.requireStr("content", "write_file")
                val append  = args["mode"]?.jsonPrimitive?.content == "append"

                val (target, err) = resolveTarget(context, path)
                if (err != null) return@runCatching """{"error":"$err"}"""

                val bytes = content.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_WRITE_BYTES)
                    return@runCatching """{"error":"内容过大（${bytes.size} 字节），上限 ${MAX_WRITE_BYTES} 字节"}"""

                // 读取写入前内容（不存在则视为空），用于计算写入前后差异供前端红绿对比
                val oldText = when (target) {
                    is FileTarget.FileRef -> if (target.file.exists() && !target.file.isDirectory)
                        runCatching { target.file.readText(Charsets.UTF_8) }.getOrDefault("") else ""
                    is FileTarget.SafRef -> if (target.doc.exists() && !target.doc.isDirectory)
                        runCatching { SafManager.readText(target.doc) }.getOrDefault("") else ""
                    is FileTarget.None -> ""
                }
                // append 换行感知：原文件非空且不以换行结尾时先补 \n，避免两段内容粘连
                val sep = if (append && oldText.isNotEmpty() && !oldText.endsWith("\n") && !oldText.endsWith("\r")) "\n" else ""
                val newFull = if (append) oldText + sep + content else content
                // 大文件 diff 截断：以首个差异行为中心开窗，避免超大文件全量 diff 撑爆结果
                val oldLn = oldText.lines(); val newLn = newFull.lines()
                var firstDiff = 0
                while (firstDiff < oldLn.size && firstDiff < newLn.size && oldLn[firstDiff] == newLn[firstDiff]) firstDiff++
                val diffRadius = 200
                val diffOffset = maxOf(0, firstDiff - diffRadius)
                val diffJson = computeDiff(oldText, newFull, oldOffset = diffOffset, newOffset = diffOffset)

                when (target) {
                    is FileTarget.FileRef -> {
                        val file = target.file
                        if (file.isDirectory) return@runCatching """{"error":"是目录，无法写入: ${file.absolutePath}"}"""
                        EditHistory.record(file.absolutePath, oldText)
                        if (append) {
                            file.parentFile?.mkdirs()
                            file.appendText(sep + content, Charsets.UTF_8)
                        } else {
                            writeFileAtomic(file, content)
                        }
                        """{"ok":true,"path":"${file.absolutePath}","bytes":${file.length()},"diff":$diffJson}"""
                    }
                    is FileTarget.SafRef -> {
                        val doc = target.doc
                        if (doc.isDirectory) return@runCatching """{"error":"是目录，无法写入: ${target.displayPath}"}"""
                        EditHistory.record(target.displayPath, oldText)
                        if (append) SafManager.appendText(doc, sep + content)
                        else        SafManager.writeText(doc, content)
                        """{"ok":true,"path":"${target.displayPath}","bytes":${SafManager.fileSize(doc)},"diff":$diffJson}"""
                    }
                    is FileTarget.None -> """{"error":"无权限访问该路径"}"""
                }
            }.getOrElse { e -> """{"error":"写入失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

/**
 * 哈希锚定 + 整块替换编辑（如 Aider 方案）。
 *
 * 不依赖行号，不依赖语法（Markdown / JSON / TXT 通吃）。
 * 通过 SHA-256 做乐观锁：文件若被外部修改，哈希不匹配则直接报错终止，
 * 从根源上杜绝"改错行"的幻觉。
 *
 * 最佳用法：Agent 读取文件后，将整个函数或代码块作为 old_string，
 * 用修改后的完整版本作为 new_string 一次性替换。
 */
fun editFile(context: Context): ToolDef = tool("edit_file") {
    description = """文件编辑（hash 模式）：通过 SHA-256 乐观锁和 old_string 精确替换代码块。

不依赖行号，Markdown/JSON/TXT/代码通吃。old_string 必须与文件中当前内容完全一致（出现多次时唯一或指定 occurrence），否则工具报错。"""
    string("path") { description = "文件路径" }
    string("old_hash") {
        description = "read_file 返回的 sha256 值，用于乐观锁校验：若文件已被外部修改（哈希不匹配）工具报错终止。省略则由工具自动按当前文件计算哈希（跳过外部修改校验），方便直接编辑。"
        required = false
    }
    string("old_string") {
        description = "要替换的精确原文（整个函数/代码块）。必须与文件中当前内容完全一致，否则会报错。"
        required = false
        multiLine = true
    }
    string("new_string") {
        description = "替换后的新内容（整个函数/代码块）。文件中的 old_string 将被此内容完整替换。"
        required = false
        multiLine = true
    }
    integer("occurrence") {
        description = "当 old_string 在文件中出现多次时，指定替换「第几次」匹配（1-based，从 1 开始）。省略则要求 old_string 唯一，否则工具报错并提示补充上下文或显式传入 occurrence。"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path      = args.requireStr("path", "edit_file")
                val oldHash   = args["old_hash"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }

                val oldString = args.requireStr("old_string", "edit_file")
                val newString = args.requireStr("new_string", "edit_file")
                if (oldString.isEmpty()) {
                    return@runCatching """{"error":"old_string 不能为空（空串会导致定位死循环）。"}"""
                }

                val (loaded, loadErr) = loadEditable(context, path, oldHash)
                if (loadErr != null) return@runCatching loadErr
                val editable = loaded ?: return@runCatching """{"error":"无法读取文件"}"""

                val occurrence = args["occurrence"]?.jsonPrimitive?.content?.toIntOrNull()

                when (val r = applySingleEdit(editable.fullText, EditSpec(oldString, newString, occurrence))) {
                    is SingleEditResult.Error -> """{"error":"${r.reason}"}"""
                    is SingleEditResult.Ok -> {
                        // 空操作防护：替换后文本未变（old_string 与 new_string 相同或替换结果不变）时，
                        // 不返回 ok:true 空 diff，而是明确报错引导重试，避免模型/调用方误以为已修改。
                        if (r.newText == editable.fullText) {
                            return@runCatching """{"error":"本次编辑未产生任何变化：old_string 与 new_string 相同或替换结果不变。请检查 old_string/new_string 参数后重新调用。","path":"${editable.displayPath}"}"""
                        }
                        EditHistory.record(editable.displayPath, editable.fullText)
                        editable.save(r.newText)
                        val newHash = sha256(r.newText)
                        val editLineNum = r.outcome.editLine
                        val newLines = r.newText.lines()
                        val ctxStart = maxOf(0, editLineNum - 4)  // 前 3 行 + 编辑行本身的前置
                        val ctxEnd = minOf(newLines.size, editLineNum + 3)  // 后 3 行
                        val contextLines = newLines.subList(ctxStart, ctxEnd)
                        val contextText = contextLines.mapIndexed { i, l ->
                            val actualLine = ctxStart + i + 1
                            val marker = if (actualLine == editLineNum) "★" else " "
                            "$marker $actualLine: $l"
                        }.joinToString("\n")
                        // 围绕编辑位置开窗计算差异，供前端红绿对比（大文件也只展示编辑附近）
                        val diffRadius = 80
                        val diffOffset = maxOf(0, editLineNum - diffRadius)
                        val diffJson = computeDiff(editable.fullText, r.newText, oldOffset = diffOffset, newOffset = diffOffset)
                        val receiptJson = JsonPrimitive(renderPostWriteReceipts(listOf(ReceiptSpan(oldString, newString, fuzzy = r.outcome.fuzzy))))
                        val editResultJson = """{"ok":true,"path":"${editable.displayPath}","sha256":"$newHash","replaced_length":${r.outcome.replacedLength},"new_length":${r.outcome.newLength},"edit_line":$editLineNum,"fuzzy_match":${r.outcome.fuzzy},"receipt":$receiptJson,"context_start":${ctxStart + 1},"context_end":$ctxEnd,"diff":$diffJson,"context":${JsonPrimitive(contextText)}}"""
                        return@runCatching editResultJson
                    }
                }
            }.getOrElse { e -> """{"error":"编辑失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

/**
 * 已加载的可编辑目标：全文 + 展示路径 + 保存回调（File 或 SAF）。
 */
private class LoadedEditable(
    val fullText: String,
    val displayPath: String,
    val save: (String) -> Unit
)

/**
 * 解析路径并读取全文 + 乐观锁校验（edit_file / multi_edit 共用的加载入口）。
 *
 * @return Pair(loaded, errorJson)——成功时 errorJson 为 null；失败时 loaded 为 null、
 *         errorJson 是可直接回灌给模型的 `{"error":...}` 字符串。
 */
private fun loadEditable(context: Context, path: String, oldHash: String?): Pair<LoadedEditable?, String?> {
    val (target, err) = resolveTarget(context, path)
    if (err != null) return null to """{"error":"$err"}"""
    return when (target) {
        is FileTarget.FileRef -> {
            val file = target.file
            if (!file.exists()) return null to """{"error":"文件不存在: ${file.absolutePath}"}"""
            if (file.isDirectory) return null to """{"error":"是目录，不是文件: ${file.absolutePath}"}"""
            val fullText = file.readText(Charsets.UTF_8)
            val hashErr = checkHash(fullText, oldHash, file.absolutePath)
            if (hashErr != null) return null to hashErr
            LoadedEditable(fullText, file.absolutePath, { text ->
                if (text.toByteArray(Charsets.UTF_8).size > MAX_WRITE_BYTES)
                    throw java.io.IOException("编辑后文件过大，上限 $MAX_WRITE_BYTES 字节")
                writeFileAtomic(file, text)
            }) to null
        }
        is FileTarget.SafRef -> {
            val doc = target.doc
            if (!doc.exists()) return null to """{"error":"文件不存在: ${target.displayPath}"}"""
            if (doc.isDirectory) return null to """{"error":"是目录，不是文件: ${target.displayPath}"}"""
            val fullText = SafManager.readText(doc)
            val hashErr = checkHash(fullText, oldHash, target.displayPath)
            if (hashErr != null) return null to hashErr
            LoadedEditable(fullText, target.displayPath, { text -> SafManager.writeText(doc, text) }) to null
        }
        is FileTarget.None -> null to """{"error":"无权限访问该路径"}"""
    }
}

/** SHA-256 乐观锁校验；通过返回 null，失败返回 `{"error":...}` 字符串。 */
private fun checkHash(fullText: String, oldHash: String?, displayPath: String): String? {
    if (oldHash == null) return null
    val currentHash = sha256(fullText)
    if (currentHash == oldHash) return null
    return """{"error":"哈希校验失败（当前: $currentHash, 期望: $oldHash）。文件可能已被外部修改，或 old_hash 取自旧版本。请重新 read_file 获取最新 sha256 后再编辑；若确认 old_hash 无误、只是文件被外部改动，请基于最新内容重新构造 old_string。","sha256":"$currentHash","path":"$displayPath"}"""
}

/**
 * 从工具参数中解析编辑列表，兼容两种形态：
 *  1) `edits` 数组（OpenAI function calling 原生 JSON schema，或行式协议下 `edits <<< [JSON] >>>` 定界块）；
 *  2) 行式编号键 `edit_{N}_old` / `edit_{N}_new`（可选 `edit_{N}_occurrence`）——零 JSON、多行 old/new 用定界块，
 *     与行式调用协议完全一致。
 */
private fun parseMultiEditSpecs(args: JsonObject): List<EditSpec> {
    args["edits"]?.let { el ->
        val list = when (el) {
            is JsonArray -> el
            is JsonPrimitive -> runCatching { McpJson.parseToJsonElement(el.content) as? JsonArray }.getOrNull()
            else -> null
        }
        if (list != null) {
            val specs = list.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val oldS = (o["old_string"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val newS = (o["new_string"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val occ = (o["occurrence"] as? JsonPrimitive)?.content?.toIntOrNull()
                EditSpec(oldS, newS, occ)
            }
            if (specs.isNotEmpty()) return specs
        }
    }
    // 行式编号键：edit_{N}_old / edit_{N}_new / edit_{N}_occurrence
    val re = Regex("^edit_(\\d+)_(old|new|occurrence)$")
    val byIndex = LinkedHashMap<Int, LinkedHashMap<String, String>>()
    for ((k, v) in args) {
        val m = re.find(k) ?: continue
        val idx = m.groupValues[1].toInt()
        val kind = m.groupValues[2]
        val value = (v as? JsonPrimitive)?.content ?: continue
        byIndex.getOrPut(idx) { LinkedHashMap() }[kind] = value
    }
    if (byIndex.isEmpty()) return emptyList()
    val specs = ArrayList<EditSpec>()
    for (idx in byIndex.keys.sorted()) {
        val e = byIndex[idx] ?: continue
        val oldS = e["old"] ?: return emptyList()
        val newS = e["new"] ?: return emptyList()
        specs.add(EditSpec(oldS, newS, e["occurrence"]?.toIntOrNull()))
    }
    return specs
}

/**
 * 同一文件批量顺序编辑（原子）：一次调用按顺序应用多个「old_string → new_string」替换。
 *
 * 语义与 edit_file 相同（SHA-256 乐观锁、old_string 唯一或 occurrence 定位、CRLF 归一化匹配），
 * 但整体原子：每个编辑基于前一个编辑的结果在内存中应用，**全部成功才写盘**；
 * 任一编辑失败则整个文件保持原样，并指出是第几个编辑、失败原因。
 *
 * 参数 `edits` 为数组，每项 {old_string, new_string, occurrence?}，按顺序应用。
 * 行式协议路径可用编号键 `edit_{N}_old` / `edit_{N}_new`（多行内容用 `<<< ... >>>` 定界块，零 JSON），
 * 后一个编辑可以命中前一个编辑刚写入的内容（连续替换多处）。
 */
fun multiEditFile(context: Context): ToolDef = tool("multi_edit") {
    description = """同一文件批量原子编辑：一次调用按顺序应用多个「old_string → new_string」替换，等价于连续多次 edit_file 但保证原子性——所有编辑都匹配成功才会写盘，任一失败则整个文件不变，并指出是第几个编辑失败、失败原因。

参数 edits 为数组，每项 {old_string, new_string, occurrence?}：
- old_string 必须与文件当前内容完全一致；出现多次时必须唯一（补充上下文）或指定 occurrence；
- 每个编辑都基于前一个编辑的结果定位，因此可以连续替换多处，也可以让后一个编辑命中前一个编辑刚写入的内容；
- 适合一次修改一个函数里的多处、或同一文件多个独立片段，减少调用往返。"""
    string("path") { description = "文件路径" }
    string("old_hash") {
        description = "read_file 返回的 sha256 值，用于乐观锁校验：文件已被外部修改则整体报错终止。省略则由工具自动按当前文件计算。"
        required = false
    }
    // 用 json 而非 array：行式协议下数组参数会被解析成 JSON 字符串，array 声明会被校验层
    // 以「期望类型 array, 实际 string」拒绝，工具根本进不到 handler。json 不输出 type 约束，
    // handler（parseMultiEditSpecs）已兼容「JSON 数组 / 字符串编码数组 / edit_N 编号键」三形态。
    json("edits") {
        description = "编辑列表，按顺序应用。传 JSON 数组，每项：old_string（必填，要替换的精确原文）、new_string（必填，替换后的新内容）、occurrence（可选，当 old_string 出现多次时指定第几次，1-based）。示例：[{\"old_string\":\"a\",\"new_string\":\"b\"}]。与行式编号键 edit_N_old/edit_N_new 二选一。"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "multi_edit")
                val oldHash = args["old_hash"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }

                val edits = parseMultiEditSpecs(args)
                if (edits.isEmpty()) {
                    return@runCatching """{"error":"edits 为空：请提供 edits 数组（每项含 old_string 和 new_string），或行式编号键 edit_1_old / edit_1_new。"}"""
                }

                val (loaded, loadErr) = loadEditable(context, path, oldHash)
                if (loadErr != null) return@runCatching loadErr
                val editable = loaded ?: return@runCatching """{"error":"无法读取文件"}"""

                when (val r = applySequentialEdits(editable.fullText, edits)) {
                    is EditBatchResult.Failure ->
                        """{"error":"第 ${r.step} 个编辑失败：${r.reason}（整体未写入，文件保持原样）。请修正后重试。"}"""
                    is EditBatchResult.Success -> {
                        // 空操作防护：所有编辑替换后文本未变时，明确报错而非返回 ok:true 空 diff
                        if (r.newText == editable.fullText) {
                            return@runCatching """{"error":"本次 multi_edit 未产生任何变化：所有编辑的 old_string 与 new_string 相同或替换结果不变。请检查 edits 参数后重新调用。","path":"${editable.displayPath}"}"""
                        }
                        EditHistory.record(editable.displayPath, editable.fullText)
                        editable.save(r.newText)
                        val newHash = sha256(r.newText)
                        // 以第一处编辑行为中心开窗计算差异，供前端红绿对比（大文件也只展示编辑附近）
                        val firstLine = r.outcomes.firstOrNull()?.editLine ?: 1
                        val diffRadius = 80
                        val diffOffset = maxOf(0, firstLine - diffRadius)
                        val diffJson = computeDiff(editable.fullText, r.newText, oldOffset = diffOffset, newOffset = diffOffset)
                        val editsJson = r.outcomes.mapIndexed { i, o ->
                            """{"index":${i + 1},"edit_line":${o.editLine},"replaced_length":${o.replacedLength},"new_length":${o.newLength}}"""
                        }.joinToString(",")
                        val receiptJson = JsonPrimitive(
                            renderPostWriteReceipts(edits.mapIndexed { i, e ->
                                ReceiptSpan(e.oldString, e.newString, fuzzy = r.outcomes.getOrNull(i)?.fuzzy ?: false)
                            })
                        )
                        """{"ok":true,"path":"${editable.displayPath}","sha256":"$newHash","edits_count":${r.outcomes.size},"receipt":$receiptJson,"edits":[$editsJson],"diff":$diffJson}"""
                    }
                }
            }.getOrElse { e -> """{"error":"编辑失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}


/**
 * 按关键词定位文件中的语义块（函数/类/代码块），返回该块的【纯文本内容】（不带行号前缀），
 * 可直接作为 edit_file 的 old_string 使用——实现「工具输出 = 下一工具输入」的数据流，
 * 免去 LLM 从 read_file 的 LINE[HASH] 前缀中手工剥取代码段。
 */
fun getBlockTool(context: Context): ToolDef = tool("get_block") {
    description = "按关键词定位文件中的语义块（函数/类/代码块），返回该块的纯文本内容（无行号前缀），" +
        "可直接作为 edit_file 的 old_string 使用。适合：取函数定义、类体、配置段做整块查看或替换。"
    string("path") { description = "文件路径" }
    string("block") {
        description = "定位关键词（大小写不敏感）。工具找到第一个包含它的行，并展开到完整语义块。"
        required = true
    }
    integer("occurrence") {
        description = "当关键词匹配多行时指定第几个（1-based，默认 1）"
        required = false
    }
    string("context_lines") {
        description = "auto（默认）自动展开到语义块边界；填数字则返回匹配行前后固定行数"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "get_block")
                val blockKey = args.requireStr("block", "get_block")
                val occurrence = (args["occurrence"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceAtLeast(1)

                val (loaded, loadErr) = loadEditable(context, path, oldHash = null)
                if (loadErr != null) return@runCatching loadErr
                val editable = loaded ?: return@runCatching """{"error":"无法读取文件"}"""
                val lines = if (editable.fullText.isEmpty()) emptyList() else editable.fullText.lines()
                val total = lines.size

                val matchIndices = mutableListOf<Int>()
                for ((idx, line) in lines.withIndex()) {
                    if (line.contains(blockKey, ignoreCase = true)) matchIndices.add(idx)
                }
                if (matchIndices.isEmpty()) {
                    return@runCatching """{"error":"未找到包含「${JsonPrimitive(blockKey)}」的行。请换一个关键词后重试。"}"""
                }
                if (occurrence > matchIndices.size) {
                    return@runCatching """{"error":"occurrence=$occurrence 超出范围，共匹配 ${matchIndices.size} 处（有效 1..${matchIndices.size}）。"}"""
                }
                val matchIdx = matchIndices[occurrence - 1]
                val ctx = args["context_lines"]?.jsonPrimitive?.content
                val (bs, be) = if (ctx == "auto" || ctx == null) {
                    expandToBlock(lines, matchIdx)
                } else {
                    val cl = (ctx.toIntOrNull() ?: 3).coerceIn(0, 500)
                    maxOf(0, matchIdx - cl) to minOf(total - 1, matchIdx + cl)
                }
                // 纯文本内容（无前缀），可直接作为 edit_file 的 old_string
                val content = lines.subList(bs, be + 1).joinToString("\n")
                val fileHash = sha256(editable.fullText)
                """{"ok":true,"path":"${editable.displayPath}","sha256":"$fileHash","total_lines":$total,"match_line":${matchIdx + 1},"match_count":${matchIndices.size},"block_start":${bs + 1},"block_end":${be + 1},"content":${JsonPrimitive(content)}}"""
            }.getOrElse { e -> """{"error":"get_block 失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}


/** 代码类文件扩展名（delete_range 删除前做花括号配对校验）。 */
private val BRACE_CHECK_EXTENSIONS = setOf(
    "kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "c", "h", "cpp", "hpp",
    "cs", "go", "rs", "swift", "scala", "groovy", "sh", "sql", "json", "xml", "html", "css"
)

/**
 * 按首尾行锚点删除一个完整区间（含首尾行），删除前对代码类文件做花括号配对校验，
 * 防止把代码块切半产生残缺语法（对齐官方 delete_range.go）。
 * 锚点行必须唯一（出现多次时报出全部行号，引导补上下文）。
 */
fun deleteRangeTool(context: Context): ToolDef = tool("delete_range") {
    description = "删除文件中从 start_anchor 行到 end_anchor 行（含首尾）的完整区间。" +
        "两个锚点必须是文件中唯一的行内容；对代码类文件删除前自动校验区间内花括号配对，" +
        "若区间切开代码块则拒绝并提示改用 edit_file / multi_edit。适合删除整个函数、配置段、注释块。"
    string("path") { description = "文件路径" }
    string("start_anchor") {
        description = "起始行内容（可含缩进；需在文件中唯一）。"
        required = true
    }
    string("end_anchor") {
        description = "结束行内容（可含缩进；需在文件中唯一，且位于 start_anchor 之后）。"
        required = true
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "delete_range")
                val startAnchor = args.requireStr("start_anchor", "delete_range")
                val endAnchor = args.requireStr("end_anchor", "delete_range")

                val (loaded, loadErr) = loadEditable(context, path, oldHash = null)
                if (loadErr != null) return@runCatching loadErr
                val editable = loaded ?: return@runCatching """{"error":"无法读取文件"}"""
                val lines = if (editable.fullText.isEmpty()) emptyList() else editable.fullText.lines()

                fun findLines(anchor: String): List<Int> =
                    lines.withIndex()
                        .filter { (_, l) -> l.trim() == anchor.trim() }
                        .map { it.index + 1 }

                val startLines = findLines(startAnchor)
                if (startLines.isEmpty()) return@runCatching """{"error":"未找到起始行锚点「${JsonPrimitive(startAnchor.take(40))}」。请从 read_file(return_format=raw) 输出中复制精确行内容。"}"""
                if (startLines.size > 1) return@runCatching """{"error":"起始行锚点出现 ${startLines.size} 次（第 ${startLines.take(5).joinToString("、")} 行）。请补充上下文使锚点唯一。"}"""
                val endLines = findLines(endAnchor)
                if (endLines.isEmpty()) return@runCatching """{"error":"未找到结束行锚点「${JsonPrimitive(endAnchor.take(40))}」。请从 read_file(return_format=raw) 输出中复制精确行内容。"}"""
                if (endLines.size > 1) return@runCatching """{"error":"结束行锚点出现 ${endLines.size} 次（第 ${endLines.take(5).joinToString("、")} 行）。请补充上下文使锚点唯一。"}"""

                val startLine = startLines[0]
                val endLine = endLines[0]
                if (endLine < startLine) return@runCatching """{"error":"end_anchor（第 $endLine 行）在 start_anchor（第 $startLine 行）之前，请检查锚点顺序。"}"""

                // 删除区间：[start 行起点, end 行末尾（含换行））
                var startOff = 0
                for (i in 0 until startLine - 1) startOff += lines[i].length + 1
                var endOff = startOff
                for (i in startLine - 1..endLine - 1) endOff += lines[i].length + 1
                if (endOff > editable.fullText.length) endOff = editable.fullText.length

                // 代码类文件：花括号配对校验，防切半代码块
                val ext = editable.displayPath.substringAfterLast('.', "").lowercase()
                if (ext in BRACE_CHECK_EXTENSIONS && !BraceBalance.deleteIsBalanced(editable.fullText, startOff, endOff)) {
                    return@runCatching """{"error":"删除区间内花括号不配对，会把代码块切半产生残缺语法（第 $startLine-$endLine 行）。请改用 edit_file / multi_edit 做精确修改。"}"""
                }

                val deletedLines = endLine - startLine + 1
                val newText = editable.fullText.removeRange(startOff, endOff)
                if (newText == editable.fullText) return@runCatching """{"error":"删除未产生变化（区间为空）。"}"""
                EditHistory.record(editable.displayPath, editable.fullText)
                editable.save(newText)
                val newHash = sha256(newText)
                val diffRadius = 80
                val diffOffset = maxOf(0, startLine - diffRadius)
                val diffJson = computeDiff(editable.fullText, newText, oldOffset = diffOffset, newOffset = diffOffset)
                """{"ok":true,"path":"${editable.displayPath}","sha256":"$newHash","deleted_lines":$deletedLines,"start_line":$startLine,"end_line":$endLine,"diff":$diffJson}"""
            }.getOrElse { e -> """{"error":"delete_range 失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}


/**
 * 删除文件或空目录。
 */
fun deleteFileTool(context: Context): ToolDef = tool("delete_file") {
    description = "删除文件或目录。默认只删文件或空目录；recursive=true 可递归删除非空目录。删除不可恢复，请谨慎。"
    string("path") { description = "文件或目录路径" }
    string("recursive") {
        description = "是否递归删除非空目录，true 或 false（默认）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "delete_file")
                val recursive = args["recursive"]?.jsonPrimitive?.content == "true"
                val (target, err) = resolveTarget(context, path)
                if (err != null) return@runCatching """{"error":"$err"}"""

                when (target) {
                    is FileTarget.FileRef -> {
                        val file = target.file
                        if (!file.exists()) return@runCatching """{"error":"不存在: ${file.absolutePath}"}"""
                        val ok = if (file.isFile) {
                            file.delete()
                        } else if (recursive) {
                            file.deleteRecursively()
                        } else {
                            val empty = file.listFiles()?.isEmpty() ?: true
                            if (empty) file.delete()
                            else return@runCatching """{"error":"目录非空，若需删除请设置 recursive=true: ${file.absolutePath}"}"""
                        }
                        if (ok) """{"ok":true,"path":"${file.absolutePath}"}"""
                        else """{"error":"删除失败（权限不足或目录被占用）: ${file.absolutePath}"}"""
                    }
                    is FileTarget.SafRef -> {
                        val doc = target.doc
                        if (!doc.exists()) return@runCatching """{"error":"不存在: ${target.displayPath}"}"""
                        val ok = SafManager.deleteFile(doc)
                        if (ok) """{"ok":true,"path":"${target.displayPath}"}"""
                        else """{"error":"删除失败（目录非空或权限不足）: ${target.displayPath}"}"""
                    }
                    is FileTarget.None -> """{"error":"无权限访问该路径"}"""
                }
            }.getOrElse { e -> """{"error":"删除失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

/** 复制/移动的源与目标归一化判断（canonical 路径一致即视为同一文件）。 */
private fun sameFile(a: File, b: File): Boolean =
    runCatching { a.canonicalPath }.getOrElse { a.absolutePath } ==
        runCatching { b.canonicalPath }.getOrElse { b.absolutePath }

/** 复制文件或目录（FileRef；dest 为副本完整新路径）。返回错误或 null。 */
private fun doCopyFile(src: File, dest: File, overwrite: Boolean): String? {
    if (!src.exists()) return "源不存在: ${src.absolutePath}"
    if (dest.exists() && !overwrite) return "目标已存在，若需覆盖请设置 overwrite=true: ${dest.absolutePath}"
    if (dest.exists() && sameFile(src, dest)) return "源与目标为同一路径，无需操作"
    dest.parentFile?.mkdirs()
    return try {
        if (src.isFile) {
            src.copyTo(dest, overwrite = overwrite)
        } else {
            if (overwrite && dest.exists()) dest.deleteRecursively()
            src.copyRecursively(dest, overwrite = overwrite)
        }
        null
    } catch (e: Exception) {
        "复制失败: ${e.message?.take(160)}"
    }
}

/** 移动或重命名文件/目录（FileRef；dest 为移动后完整新路径）。返回错误或 null。 */
private fun doMoveFile(src: File, dest: File, overwrite: Boolean): String? {
    if (!src.exists()) return "源不存在: ${src.absolutePath}"
    if (dest.exists() && !overwrite) return "目标已存在，若需覆盖请设置 overwrite=true: ${dest.absolutePath}"
    if (dest.exists() && sameFile(src, dest)) return "源与目标为同一路径，无需操作"
    dest.parentFile?.mkdirs()
    return try {
        if (overwrite && dest.exists()) dest.deleteRecursively()
        if (src.renameTo(dest)) {
            null
        } else {
            // 跨文件系统 renameTo 失败 → 复制后删除
            if (src.isDirectory) src.copyRecursively(dest, overwrite = true)
            else src.copyTo(dest, overwrite = true)
            src.deleteRecursively()
            null
        }
    } catch (e: Exception) {
        "移动失败: ${e.message?.take(160)}"
    }
}

/**
 * 复制文件或目录到新路径（dest 为副本完整路径）。
 * 支持目录递归复制。仅支持应用内部路径或已授权存储权限的普通路径；SAF 授权目录暂不支持。
 */
fun copyFileTool(context: Context): ToolDef = tool("copy_file") {
    description = "复制文件或目录到新路径。dest 是副本的完整路径；源为目录时递归复制整个目录。仅支持应用内部路径或已授权存储权限的普通路径，SAF 授权目录暂不支持。"
    string("path") { description = "源文件或目录路径" }
    string("dest") { description = "目标路径（副本的完整路径）" }
    string("overwrite") {
        description = "目标已存在时是否覆盖，true 或 false（默认）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "copy_file")
                val dest = args.requireStr("dest", "copy_file")
                val overwrite = args["overwrite"]?.jsonPrimitive?.content == "true"

                val (srcT, srcErr) = resolveTarget(context, path)
                if (srcErr != null) return@runCatching """{"error":"$srcErr"}"""
                val (destT, destErr) = resolveTarget(context, dest)
                if (destErr != null) return@runCatching """{"error":"$destErr"}"""

                val srcFile = (srcT as? FileTarget.FileRef)?.file
                    ?: return@runCatching """{"error":"SAF 授权目录暂不支持复制，请使用应用内部路径或已授权存储权限的普通路径"}"""
                val destFile = (destT as? FileTarget.FileRef)?.file
                    ?: return@runCatching """{"error":"SAF 授权目录暂不支持复制，请使用应用内部路径或已授权存储权限的普通路径"}"""

                val err = doCopyFile(srcFile, destFile, overwrite)
                if (err != null) return@runCatching """{"error":"$err"}"""
                """{"ok":true,"src":"${srcFile.absolutePath}","dest":"${destFile.absolutePath}"}"""
            }.getOrElse { e -> """{"error":"复制失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

/**
 * 移动或重命名文件/目录（path → dest）。同目录移动即重命名。
 * 支持目录。仅支持应用内部路径或已授权存储权限的普通路径；SAF 授权目录暂不支持。
 */
fun moveFileTool(context: Context): ToolDef = tool("move_file") {
    description = "移动或重命名文件/目录：将 path 移动到 dest。同目录移动即重命名。支持目录。仅支持应用内部路径或已授权存储权限的普通路径，SAF 授权目录暂不支持。"
    string("path") { description = "源文件或目录路径" }
    string("dest") { description = "目标路径（移动后的完整路径）" }
    string("overwrite") {
        description = "目标已存在时是否覆盖，true 或 false（默认）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "move_file")
                val dest = args.requireStr("dest", "move_file")
                val overwrite = args["overwrite"]?.jsonPrimitive?.content == "true"

                val (srcT, srcErr) = resolveTarget(context, path)
                if (srcErr != null) return@runCatching """{"error":"$srcErr"}"""
                val (destT, destErr) = resolveTarget(context, dest)
                if (destErr != null) return@runCatching """{"error":"$destErr"}"""

                val srcFile = (srcT as? FileTarget.FileRef)?.file
                    ?: return@runCatching """{"error":"SAF 授权目录暂不支持移动，请使用应用内部路径或已授权存储权限的普通路径"}"""
                val destFile = (destT as? FileTarget.FileRef)?.file
                    ?: return@runCatching """{"error":"SAF 授权目录暂不支持移动，请使用应用内部路径或已授权存储权限的普通路径"}"""

                val err = doMoveFile(srcFile, destFile, overwrite)
                if (err != null) return@runCatching """{"error":"$err"}"""
                """{"ok":true,"src":"${srcFile.absolutePath}","dest":"${destFile.absolutePath}"}"""
            }.getOrElse { e -> """{"error":"移动失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

/**
 * 撤销对某个文件的最近一次（或多级）修改，恢复为写盘前的旧内容。
 *
 * 依赖 [EditHistory] 在 write_file / edit_file / multi_edit / delete_range
 * 写盘前记录的快照。levels 控制回退层数：1 恢复最近一次修改前的内容，
 * N 恢复 N 次修改前的内容；快照耗尽则恢复到最早记录。
 */
fun undoEditTool(context: Context): ToolDef = tool("undo_edit") {
    description = "撤销对指定文件的最近一次或多级修改：恢复 write_file / edit_file / multi_edit / delete_range 写盘前的旧内容。levels（可选，默认 1）控制回退层数；没有可撤销历史时返回 error。"
    string("path") { description = "要撤销的文件路径" }
    integer("levels") {
        description = "回退层数（默认 1；填 N 恢复 N 次修改前的内容）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "undo_edit")
                val levels = (args["levels"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1).coerceAtLeast(1)

                val (target, err) = resolveTarget(context, path)
                if (err != null) return@runCatching """{"error":"$err"}"""

                val undoPlan: Pair<String, (String) -> Unit> = when (target) {
                    is FileTarget.FileRef -> {
                        val file = target.file
                        file.absolutePath to { text: String -> writeFileAtomic(file, text) }
                    }
                    is FileTarget.SafRef -> {
                        val doc = target.doc
                        target.displayPath to { text: String -> SafManager.writeText(doc, text) }
                    }
                    is FileTarget.None -> return@runCatching """{"error":"无权限访问该路径"}"""
                }
                val key = undoPlan.first
                val writeBack = undoPlan.second

                val before = EditHistory.pop(key, levels)
                    ?: return@runCatching """{"error":"该文件没有可撤销的历史（可能尚无修改，或历史已随进程重启清空）。","path":"$key"}"""
                writeBack(before)
                val remaining = EditHistory.remaining(key)
                """{"ok":true,"path":"$key","sha256":"${sha256(before)}","levels":$levels,"remaining_undo":$remaining}"""
            }.getOrElse { e -> """{"error":"撤销失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

/**
 * 列出目录内容。
 */
fun listFilesTool(context: Context): ToolDef = tool("list_files") {
    description = "列出目录内容（文件名、大小、类型）。不填路径则列出应用内部存储根目录（filesDir）。"
    handler { args ->
        val path = args["path"]?.jsonPrimitive?.content ?: ""
        withContext(Dispatchers.IO) {
            val (target, err) = if (path.isEmpty())
                FileTarget.FileRef(context.filesDir) to null
            else
                resolveTarget(context, path)

            if (err != null) return@withContext """{"error":"$err"}"""

            when (target) {
                is FileTarget.FileRef -> {
                    val dir = target.file
                    if (!dir.exists())     return@withContext """{"error":"目录不存在: ${dir.absolutePath}"}"""
                    if (!dir.isDirectory)  return@withContext """{"error":"不是目录: ${dir.absolutePath}"}"""
                    val files = dir.listFiles() ?: emptyArray()
                    val entries = files
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                        .take(300)
                        .joinToString(",") { f ->
                            val type = if (f.isDirectory) "dir" else "file"
                            val size = if (f.isFile) ",\"size\":${f.length()}" else ""
                            """{"name":"${f.name}","type":"$type"$size}"""
                        }
                    """{"path":"${dir.absolutePath}","count":${files.size},"entries":[$entries]}"""
                }
                is FileTarget.SafRef -> {
                    val doc = target.doc
                    if (!doc.exists())    return@withContext """{"error":"目录不存在: ${target.displayPath}"}"""
                    if (!doc.isDirectory) return@withContext """{"error":"不是目录: ${target.displayPath}"}"""
                    val files = SafManager.listFiles(doc)
                    val entries = files
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name ?: "" }))
                        .take(300)
                        .joinToString(",") { f ->
                            val type = if (f.isDirectory) "dir" else "file"
                            val size = if (f.isFile) ",\"size\":${f.length()}" else ""
                            """{"name":"${f.name ?: "?"}","type":"$type"$size}"""
                        }
                    """{"path":"${target.displayPath}","count":${files.size},"entries":[$entries]}"""
                }
                is FileTarget.None -> """{"error":"无权限访问该路径"}"""
            }
        }
    }
}

/** 搜索时默认跳过的目录名（大小写不敏感）。与 .x 隐藏规则叠加生效。
 * 这些都是扫描代价大但搜索命中率低的目录：构建产物、依赖缓存、生成代码。 */
private val SEARCH_SKIP_DIRS: Set<String> = setOf(
    "build", "node_modules", "generated", "__pycache__"
)

/** 判断文件名是否应在搜索时跳过：隐藏文件（.开头）或常见产物目录。 */
private fun isSkippedSearchName(name: String): Boolean =
    name.startsWith(".") || name.lowercase() in SEARCH_SKIP_DIRS

/**
 * 搜索文件：按文件名模式 和/或 文件内容关键词。
 *
 * 支持 context_lines 参数，返回匹配行及其上下文的代码片段。
 * 这个"内容锚定"片段可直接作为 edit_file 的 old_string 使用，
 * 不依赖行号，通过内容指纹定位。
 */
fun searchFiles(context: Context): ToolDef = tool("search_files") {
    description = "在目录中搜索文件：按文件名包含匹配（name_pattern）和/或文件内容关键词搜索（content_pattern），支持递归。默认跳过隐藏文件与目录（名字以 . 开头）以及 build/node_modules/generated/__pycache__ 等常见产物目录。注意：name_pattern 是子串包含匹配，不是 glob 通配符。可设置 context_lines 返回匹配行上下文的代码片段，作为 edit_file 的内容锚定。"
    string("directory") {
        description = "搜索根目录（必须是目录路径，不填则为 filesDir）"
        required = false
    }
    string("path") {
        description = "directory 的别名，兼容旧调用（必须是目录路径，不能是文件路径）"
        required = false
    }
    string("name_pattern") {
        description = "文件名包含匹配（大小写不敏感），如 .kt、report、README。注意：不是 glob 通配符，'*.html' 会字面查找文件名含 '*.html' 的文件而不会匹配 .html 结尾的文件，应直接写 'html'"
        required = false
    }
    string("content_pattern") {
        description = "文件内容关键词（大小写不敏感），返回含该词的行号 + 行内容"
        required = false
    }
    string("recursive") {
        description = "是否递归子目录，true（默认）或 false"
        required = false
    }
    integer("max_results") {
        description = "最多返回条目数，默认 50"
        required = false
    }
    integer("context_lines") {
        description = "匹配行前后各返回多少行上下文（默认 0，推荐 3-5）。返回的 context 字段可直接作为 edit_file 的 old_string 使用，实现内容锚定定位。"
        required = false
    }
    boolean("text") {
        description = "是否强制按文本搜索二进制文件（默认 false：自动跳过含 NUL 等二进制特征的文件，ripgrep 风格）。"
        required = false
    }
    string("return_format") {
        description = "返回格式：legacy（默认，保持现有 results 结构）或 json（返回结构化 matches）"
        required = false
        enumValues = listOf("legacy", "json")
    }
    handler { args ->
        val dirPath        = args["directory"]?.jsonPrimitive?.content ?: args["path"]?.jsonPrimitive?.content ?: ""
        val namePattern    = args["name_pattern"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val contentPattern = args["content_pattern"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val recursive      = args["recursive"]?.jsonPrimitive?.content != "false"
        val maxResults     = args["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50
        val contextLines   = args["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val textMode       = args["text"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true
        val returnFormat   = args["return_format"]?.jsonPrimitive?.content ?: "legacy"
        val structured     = returnFormat == "json"

        withContext(Dispatchers.IO) {
            if (namePattern == null && contentPattern == null)
                return@withContext """{"error":"至少提供 name_pattern 或 content_pattern 之一"}"""
            if (structured && contentPattern == null)
                return@withContext """{"error":"return_format=json 需要提供 content_pattern。"}"""

            val (target, err) = if (dirPath.isEmpty())
                FileTarget.FileRef(context.filesDir) to null
            else
                resolveTarget(context, dirPath)

            if (err != null) return@withContext """{"error":"$err"}"""

            val results = mutableListOf<String>()

            when (target) {
                is FileTarget.FileRef -> {
                    val dir = target.file
                    if (!dir.exists())    return@withContext """{"error":"目录不存在: ${dir.absolutePath}"}"""
                    if (!dir.isDirectory) return@withContext """{"error":"不是目录: ${dir.absolutePath}"}"""
                    val files: Sequence<File> =
                        if (recursive) dir.walkTopDown()
                            .onEnter { !isSkippedSearchName(it.name) }
                            .filter {
                                !isSkippedSearchName(it.name) &&
                                    try { it.isFile } catch (_: Exception) { false }
                            }
                        else (dir.listFiles()
                            ?.filter {
                                !isSkippedSearchName(it.name) &&
                                    try { it.isFile } catch (_: Exception) { false }
                            }
                            ?: emptyList()).asSequence()
                    for (file in files) {
                        if (results.size >= maxResults) break
                        if (namePattern != null && !file.name.contains(namePattern, ignoreCase = true)) continue
                        if (contentPattern != null) {
                            runCatching {
                                val allLines = file.readLines(Charsets.UTF_8)
                                if (!textMode && looksBinary(allLines.joinToString("\n"))) return@runCatching
                                for ((idx, line) in allLines.withIndex()) {
                                    if (results.size >= maxResults) break
                                    if (line.contains(contentPattern, ignoreCase = true)) {
                                        val lineNum = idx + 1
                                        if (structured) {
                                            val start = maxOf(0, idx - contextLines)
                                            val end = minOf(allLines.size - 1, idx + contextLines)
                                            val content = allLines.subList(start, end + 1).joinToString("\n")
                                            results.add("""{"file":${JsonPrimitive(file.absolutePath)},"start_line":${start + 1},"end_line":${end + 1},"content":${JsonPrimitive(content)}}""")
                                        } else if (contextLines > 0) {
                                            val start = maxOf(0, idx - contextLines)
                                            val end = minOf(allLines.size - 1, idx + contextLines)
                                            val ctxLines = allLines.subList(start, end + 1)
                                            val ctxText = ctxLines.mapIndexed { i, l ->
                                                val actualLine = start + i + 1
                                                if (actualLine == lineNum) "★ $l" else "  $l"
                                            }.joinToString("\n")
                                            results.add("""{"path":"${file.absolutePath}","line":$lineNum,"text":${JsonPrimitive(line.trim().take(300))},"context":${JsonPrimitive(ctxText)},"context_start":${start + 1},"context_end":${end + 1}}""")
                                        } else {
                                            results.add("""{"path":"${file.absolutePath}","line":$lineNum,"text":${JsonPrimitive(line.trim().take(300))}}""")
                                        }
                                    }
                                }
                            }
                        } else {
                            val fileSize = try { file.length() } catch (_: Exception) { 0L }
                            results.add("""{"path":"${file.absolutePath}","size":$fileSize}""")
                        }
                    }
                }
                is FileTarget.SafRef -> {
                    val doc = target.doc
                    if (!doc.exists())    return@withContext """{"error":"目录不存在: ${target.displayPath}"}"""
                    if (!doc.isDirectory) return@withContext """{"error":"不是目录: ${target.displayPath}"}"""
                    searchSafRecursive(doc, namePattern, contentPattern, recursive, maxResults, contextLines, textMode, structured, results)
                }
                is FileTarget.None -> return@withContext """{"error":"无权限访问该路径"}"""
            }

            if (structured) {
                """{"count":${results.size},"matches":[${results.joinToString(",")}] }""".replace("] }", "]}")
            } else {
                """{"count":${results.size},"results":[${results.joinToString(",")}]}"""
            }
        }
    }
}

/** 递归搜索 SAF DocumentFile 目录。 */
private fun searchSafRecursive(
    dir: DocumentFile,
    namePattern: String?,
    contentPattern: String?,
    recursive: Boolean,
    maxResults: Int,
    contextLines: Int,
    textMode: Boolean,
    structured: Boolean,
    results: MutableList<String>
) {
    for (child in dir.listFiles()) {
        if (results.size >= maxResults) break
        if (isSkippedSearchName(child.name ?: "")) continue
        if (child.isDirectory && recursive) {
            searchSafRecursive(child, namePattern, contentPattern, true, maxResults, contextLines, textMode, structured, results)
        } else if (child.isFile) {
            val name = child.name ?: continue
            if (namePattern != null && !name.contains(namePattern, ignoreCase = true)) continue
            if (contentPattern != null) {
                runCatching {
                    val allText = SafManager.readText(child)
                    if (!textMode && looksBinary(allText)) return@runCatching
                    val allLines = allText.lines()
                    for ((idx, line) in allLines.withIndex()) {
                        if (results.size >= maxResults) break
                        if (line.contains(contentPattern, ignoreCase = true)) {
                            val lineNum = idx + 1
                            if (structured) {
                                val start = maxOf(0, idx - contextLines)
                                val end = minOf(allLines.size - 1, idx + contextLines)
                                val content = allLines.subList(start, end + 1).joinToString("\n")
                                results.add("""{"file":${JsonPrimitive(child.uri.toString())},"start_line":${start + 1},"end_line":${end + 1},"content":${JsonPrimitive(content)}}""")
                            } else if (contextLines > 0) {
                                val start = maxOf(0, idx - contextLines)
                                val end = minOf(allLines.size - 1, idx + contextLines)
                                val ctxLines = allLines.subList(start, end + 1)
                                val ctxText = ctxLines.mapIndexed { i, l ->
                                    val actualLine = start + i + 1
                                    if (actualLine == lineNum) "★ $l" else "  $l"
                                }.joinToString("\n")
                                results.add("""{"path":"${child.uri}","line":$lineNum,"text":${JsonPrimitive(line.trim().take(300))},"context":${JsonPrimitive(ctxText)},"context_start":${start + 1},"context_end":${end + 1}}""")
                            } else {
                                results.add("""{"path":"${child.uri}","line":$lineNum,"text":${JsonPrimitive(line.trim().take(300))}}""")
                            }
                        }
                    }
                }
            } else {
                results.add("""{"path":"${child.uri}","size":${child.length()}}""")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  跨文件搜索替换（search_replace）
// ─────────────────────────────────────────────────────────────

/** search_replace 的候选文件句柄：展示路径 + 读 + 写，统一 File 与 SAF 两种底层。 */
private class ReplaceableFile(
    val displayPath: String,
    val read: () -> String,
    val save: (String) -> Unit
)

/** 递归收集 SAF DocumentFile 目录下的候选文件（按 namePattern 过滤）。 */
private fun collectSafCandidates(
    dir: DocumentFile,
    namePattern: String?,
    recursive: Boolean,
    maxFiles: Int,
    out: MutableList<ReplaceableFile>
) {
    for (child in dir.listFiles()) {
        if (out.size >= maxFiles) break
        if (isSkippedSearchName(child.name ?: "")) continue
        if (child.isDirectory && recursive) {
            collectSafCandidates(child, namePattern, true, maxFiles, out)
        } else if (child.isFile) {
            val name = child.name ?: continue
            if (namePattern != null && !name.contains(namePattern, ignoreCase = true)) continue
            out.add(
                ReplaceableFile(
                    child.uri.toString(),
                    { SafManager.readText(child) },
                    { SafManager.writeText(child, it) }
                )
            )
        }
    }
}

/**
 * 跨文件批量搜索替换：在目录下递归搜索所有包含 old_string 的文件（可按 name_pattern 过滤），
 * 对每个命中文件把 old_string 替换为 new_string。等价于「search_files + 多次 multi_edit」一次完成，
 * 显著减少来回轮数。dry_run=true 时仅预览命中文件与匹配数、不写盘。
 */
fun searchReplaceTool(context: Context): ToolDef = tool("search_replace") {
    description = "跨文件批量搜索替换：在目录下递归搜索所有包含 old_string 的文件（可按 name_pattern 过滤），对每个命中文件把 old_string 替换为 new_string。等价于 search_files + 多次 multi_edit 一次完成，显著减少往返。dry_run=true 仅预览命中文件与匹配数、不写盘（默认 false，会写盘）。"
    string("directory") {
        description = "搜索根目录（必须是目录路径，不填则默认为 filesDir）"
        required = false
    }
    string("path") {
        description = "directory 的别名，兼容旧调用（必须是目录路径，不能是文件路径）"
        required = false
    }
    string("name_pattern") {
        description = "文件名包含过滤（大小写不敏感），如 .kt、README"
        required = false
    }
    string("old_string") {
        description = "要被替换的精确原文（跨文件统一的搜索目标）。必须是文件中的精确内容（忽略 CRLF/LF 差异），不做模糊匹配。"
        required = true
        multiLine = true
    }
    string("new_string") {
        description = "替换后的新内容（跨文件统一）"
        required = true
        multiLine = true
    }
    boolean("recursive") {
        description = "是否递归子目录（默认 true）"
        required = false
    }
    boolean("replace_all") {
        description = "是否替换每文件中的所有匹配（默认 true）；false 只替换每文件第一处"
        required = false
    }
    boolean("dry_run") {
        description = "仅预览命中文件与匹配数、不写盘（默认 false）"
        required = false
    }
    integer("max_files") {
        description = "最多处理的文件数上限（默认 20，防止误伤大量文件）"
        required = false
    }

    handler { args ->
        val directory   = args["directory"]?.jsonPrimitive?.content ?: args["path"]?.jsonPrimitive?.content ?: ""
        val namePattern = args["name_pattern"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val oldString   = args.requireStr("old_string", "search_replace")
        val newString   = args.requireStr("new_string", "search_replace")
        val recursive   = args["recursive"]?.jsonPrimitive?.content != "false"
        val replaceAll  = args["replace_all"]?.jsonPrimitive?.content != "false"
        val dryRun      = args["dry_run"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true
        val maxFiles    = args["max_files"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20

        withContext(Dispatchers.IO) {
            runCatching {
                if (oldString.isEmpty())
                    return@runCatching """{"error":"old_string 不能为空。"}"""
                if (newString == oldString)
                    return@runCatching """{"error":"old_string 与 new_string 相同，无需替换。"}"""

                val (target, err) = if (directory.isEmpty())
                    FileTarget.FileRef(context.filesDir) to null
                else
                    resolveTarget(context, directory)
                if (err != null) return@runCatching """{"error":"$err"}"""

                // 收集候选文件（FileRef / SafRef 统一为 ReplaceableFile）
                val candidates = ArrayList<ReplaceableFile>()
                when (target) {
                    is FileTarget.FileRef -> {
                        val dir = target.file
                        if (!dir.exists()) return@runCatching """{"error":"目录不存在: ${dir.absolutePath}"}"""
                        if (!dir.isDirectory) return@runCatching """{"error":"不是目录: ${dir.absolutePath}"}"""
                        val files: Sequence<File> =
                            if (recursive) dir.walkTopDown().filter {
                                try { it.isFile } catch (_: Exception) { false }
                            }
                            else (dir.listFiles()?.filter {
                                try { it.isFile } catch (_: Exception) { false }
                            } ?: emptyList()).asSequence()
                        for (file in files) {
                            if (candidates.size >= maxFiles) break
                            if (namePattern != null && !file.name.contains(namePattern, ignoreCase = true)) continue
                            candidates.add(
                                ReplaceableFile(
                                    file.absolutePath,
                                    { file.readText(Charsets.UTF_8) },
                                    { writeFileAtomic(file, it) }
                                )
                            )
                        }
                    }
                    is FileTarget.SafRef -> {
                        val doc = target.doc
                        if (!doc.exists()) return@runCatching """{"error":"目录不存在: ${target.displayPath}"}"""
                        if (!doc.isDirectory) return@runCatching """{"error":"不是目录: ${target.displayPath}"}"""
                        collectSafCandidates(doc, namePattern, recursive, maxFiles, candidates)
                    }
                    is FileTarget.None -> return@runCatching """{"error":"无权限访问该路径"}"""
                }

                val modified = ArrayList<String>()
                var totalReplacements = 0
                for (c in candidates) {
                    if (modified.size >= maxFiles) break
                    val text = runCatching { c.read() }.getOrNull() ?: continue
                    if (looksBinary(text)) continue

                    val result = replaceAllOccurrences(text, oldString, newString)
                    if (result.replacedCount == 0) continue

                    val finalText: String
                    val count: Int
                    if (replaceAll || result.replacedCount == 1) {
                        finalText = result.newText
                        count = result.replacedCount
                    } else {
                        // 只替换第一处：已确认存在精确匹配，applySingleEdit 走精确路径（不会触发模糊回退）
                        val single = applySingleEdit(text, EditSpec(oldString, newString, occurrence = 1))
                        if (single is SingleEditResult.Ok) {
                            finalText = single.newText
                            count = 1
                        } else continue
                    }

                    totalReplacements += count
                    if (dryRun) {
                        modified.add("""{"path":${JsonPrimitive(c.displayPath)},"replacements":$count}""")
                    } else {
                        EditHistory.record(c.displayPath, text)
                        c.save(finalText)
                        modified.add("""{"path":${JsonPrimitive(c.displayPath)},"replacements":$count,"sha256":"${sha256(finalText)}"}""")
                    }
                }

                """{"ok":true,"dry_run":$dryRun,"files_modified":${modified.size},"total_replacements":$totalReplacements,"files":[${modified.joinToString(",")}]}"""
            }.getOrElse { e -> """{"error":"search_replace 失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  Glob 模式匹配文件搜索
// ─────────────────────────────────────────────────────────────

// 使用通配符模式搜索文件，如 **/*.kt、app/src/**/*.json。
// 底层使用 java.nio.file.FileSystem#getPathMatcher 进行 glob 匹配，支持递归搜索。
fun globTool(context: Context): ToolDef = tool("glob") {
    description = "使用通配符模式搜索文件，如 **/*.kt、src/**/*.json。支持递归搜索，返回匹配文件路径列表。"
    string("pattern") {
        description = "通配符模式，如 **/*.kt（匹配所有 .kt 文件）、**/build/**（匹配 build 目录下所有文件）"
        required = true
    }
    string("directory") {
        description = "搜索根目录（不填则为 filesDir）。支持绝对路径和相对路径。"
        required = false
    }
    integer("max_results") {
        description = "最多返回条目数，默认 50，最大 500"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val pattern   = args.requireStr("pattern", "glob")
                val dirPath   = args["directory"]?.jsonPrimitive?.content ?: ""
                val maxResult = (args["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceIn(1, 500)

                val (target, err) = if (dirPath.isEmpty())
                    FileTarget.FileRef(context.filesDir) to null
                else
                    resolveTarget(context, dirPath)
                if (err != null) return@runCatching """{"error":"$err"}"""

                val matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:$pattern")
                val results = mutableListOf<String>()

                when (target) {
                    is FileTarget.FileRef -> {
                        val dir = target.file
                        if (!dir.exists())    return@runCatching """{"error":"目录不存在: ${dir.absolutePath}"}"""
                        if (!dir.isDirectory) return@runCatching """{"error":"不是目录: ${dir.absolutePath}"}"""
                        dir.walkTopDown().forEach { f ->
                            if (results.size >= maxResult) return@forEach
                            if (matcher.matches(f.toPath())) {
                                val type = if (f.isDirectory) "dir" else "file"
                                val size = if (f.isFile) ",\"size\":${f.length()}" else ""
                                results.add("""{"path":"${f.absolutePath}","type":"$type"$size}""")
                            }
                        }
                    }
                    is FileTarget.SafRef -> {
                        val doc = target.doc
                        if (!doc.exists())    return@runCatching """{"error":"目录不存在: ${target.displayPath}"}"""
                        if (!doc.isDirectory) return@runCatching """{"error":"不是目录: ${target.displayPath}"}"""
                        globSafRecursive(doc, matcher, maxResult, results)
                    }
                    is FileTarget.None -> return@runCatching """{"error":"无权限访问该路径"}"""
                }

                val msg = if (results.size >= maxResult) "（结果过多，仅显示前 $maxResult 条）" else ""
                """{"count":${results.size},"pattern":"$pattern","results":[${results.joinToString(",")}]${if (msg.isNotEmpty()) ",\"message\":\"$msg\"" else ""}}"""
            }.getOrElse { e -> """{"error":"glob 搜索失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

/**
 * Grep 工具：正则内容搜索（ripgrep 风格）。区别于 search_files 的子串匹配，本工具用正则表达式
 * 匹配文件内容，并支持 glob 文件名过滤、大小写不敏感、三种输出模式（content / files_with_matches / count）。
 * context 字段返回原始文本（无 ★/空格前缀），可直接用于 edit_file 的 old_string；match_line_in_context 标记匹配行位置。
 */

/**
 * 判断文本是否疑似二进制文件。
 *
 * 信号（ripgrep 风格，以 NUL 字节为最可靠标志）：
 *  - 文本中含 U+0000（来自二进制里的 0x00 字节，UTF-8 解码后原样保留）→ 直接判二进制；
 *  - 兜底：不可打印控制字符（排除常见的 \t \n \r \f）占比超过阈值（默认 10%）→ 判二进制。
 *
 * 说明：U+FFFD（替换符，来自非法 UTF-8 字节）是可打印字符，不会被上述规则误伤；
 * 含 NUL 的真实二进制文件已被主信号覆盖。纯 U+FFFD、不含 NUL 的极端二进制可能漏判，与 ripgrep 行为一致。
 */
private fun looksBinary(text: String, threshold: Double = 0.10): Boolean {
    if (text.isEmpty()) return false
    if ('\u0000' in text) return true
    var ctrl = 0
    for (c in text) {
        val code = c.code
        val isControl = code in 0x00..0x08 || code in 0x0B..0x0C || code in 0x0E..0x1F || code == 0x7F
        if (isControl) ctrl++
    }
    return ctrl.toDouble() / text.length > threshold
}

fun grepTool(context: Context): ToolDef = tool("grep") {
    description = """正则内容搜索（ripgrep 风格）。与 search_files 的子串匹配不同，本工具用正则表达式匹配文件内容，并支持：
- glob 文件名过滤（如 *.kt、**/*.md）
- ignore_case 大小写不敏感
- output_mode：content（默认，逐行返回 路径/行号/内容/可选上下文）、files_with_matches（仅文件路径）、count（每文件匹配数）
- context_lines > 0 时返回 context 字段（原始文本，无额外前缀），可直接作为 edit_file 的 old_string；match_line_in_context 指示匹配行在 context 中的位置（1-based）
单行编辑：用 text 字段作 old_string；多行编辑：用 context 字段作 old_string（context 保留原始缩进和换行，无需修改）。"""
    string("pattern") {
        description = "正则表达式（Kotlin/Java 正则语法）。例：\"fun \\\\w+\"、\"TODO|FIXME\"、\"\\\\d{4}-\\\\d{2}-\\\\d{2}\"。"
        required = true
    }
    string("path") {
        description = "搜索的文件或目录路径；省略则搜索应用私有目录 filesDir。传文件则只搜该文件。"
        required = false
    }
    boolean("text") {
        description = "是否强制按文本搜索二进制文件（默认 false：自动跳过含 NUL 等二进制特征的文件，ripgrep 风格）。"
        required = false
    }
    string("glob") {
        description = "仅搜索文件名匹配该 glob 的文件，如 *.kt、**/*.md、src/**/*.kt。省略则不过滤。"
        required = false
    }
    boolean("ignore_case") {
        description = "是否大小写不敏感，默认 false"
        required = false
    }
    string("output_mode") {
        description = "输出模式：content（默认，逐行）/ files_with_matches（仅文件路径）/ count（每文件匹配数）"
        required = false
        enumValues = listOf("content", "files_with_matches", "count")
    }
    integer("context_lines") {
        description = "匹配行前后各返回多少行上下文（默认 0，推荐 2-3）。仅 output_mode=content 生效。"
        required = false
    }
    integer("head_limit") {
        description = "最多返回的匹配/文件数，默认 50；设为 0 表示不限制。"
        required = false
    }
    handler { args ->
        val pattern      = args.requireStr("pattern", "grep")
        val pathArg      = args["path"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val glob         = args["glob"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val ignoreCase   = args["ignore_case"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true
        val outputMode   = args["output_mode"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() } ?: "content"
        val contextLines = (args["context_lines"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0).coerceAtLeast(0)
        val headLimit    = (args["head_limit"]?.jsonPrimitive?.content?.toIntOrNull() ?: 50).coerceAtLeast(0)
        val textMode     = args["text"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true

        withContext(Dispatchers.IO) {
            runCatching {
                if (pattern.isEmpty()) return@runCatching """{"error":"pattern 不能为空"}"""
                // 先编译正则，非法直接报错（早于任何文件 IO）
                val regex = runCatching {
                    Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
                }.getOrElse { e -> return@runCatching """{"error":"正则表达式无效: ${e.message?.take(120)?.replace("\"", "'")}"}""" }
                val globRegex = glob?.let { globToRegex(it) }

                val (target, err) = if (pathArg.isNullOrEmpty())
                    FileTarget.FileRef(context.filesDir) to null
                else resolveTarget(context, pathArg)
                if (err != null) return@runCatching """{"error":"$err"}"""

                val results = mutableListOf<String>()
                var hitLimit = false

                fun searchFileDisplay(displayPath: String, read: () -> String?) {
                    if (hitLimit) return
                    val text = read() ?: return
                    if (!textMode && looksBinary(text)) return   // 默认跳过二进制文件
                    val lines = text.lines()
                    val matched = mutableListOf<Int>()
                    for ((idx, line) in lines.withIndex()) {
                        if (regex.containsMatchIn(line)) matched.add(idx)
                    }
                    if (matched.isEmpty()) return
                    when (outputMode) {
                        "files_with_matches" -> {
                            results.add("""{"path":${JsonPrimitive(displayPath)}}""")
                            if (headLimit > 0 && results.size >= headLimit) hitLimit = true
                        }
                        "count" -> {
                            results.add("""{"path":${JsonPrimitive(displayPath)},"count":${matched.size}}""")
                            if (headLimit > 0 && results.size >= headLimit) hitLimit = true
                        }
                        else -> {
                            for (mi in matched) {
                                if (hitLimit) break
                                val lineNum = mi + 1
                                val frag = if (contextLines > 0) {
                                    val start = maxOf(0, mi - contextLines)
                                    val end = minOf(lines.size - 1, mi + contextLines)
                                    // 不加 ★ / 空格前缀，保留原始内容，context 可直接用作 edit_file 的 old_string
                                    val ctx = lines.subList(start, end + 1).joinToString("\n")
                                    val matchRelLine = mi - start + 1
                                    ""","context":${JsonPrimitive(ctx)},"context_start":${start + 1},"context_end":${end + 1},"match_line_in_context":$matchRelLine"""
                                } else ""
                                results.add("""{"path":${JsonPrimitive(displayPath)},"line":$lineNum,"text":${JsonPrimitive(lines[mi].take(500))}$frag}""")
                                if (headLimit > 0 && results.size >= headLimit) hitLimit = true
                            }
                        }
                    }
                }

                when (target) {
                    is FileTarget.FileRef -> {
                        val f = target.file
                        if (!f.exists()) return@runCatching """{"error":"路径不存在: ${f.absolutePath}"}"""
                        if (f.isFile) {
                            searchFileDisplay(f.absolutePath) { f.readText(Charsets.UTF_8) }
                        } else if (f.isDirectory) {
                            val globHasPath = glob != null && '/' in glob
                            f.walkTopDown().filter {
                                try { it.isFile } catch (_: Exception) { false }
                            }.forEach { file ->
                                if (hitLimit) return@forEach
                                if (globRegex != null) {
                                    val matchTarget = if (globHasPath)
                                        runCatching { file.relativeTo(f).path.replace('\\', '/') }.getOrDefault(file.name)
                                    else
                                        file.name
                                    if (!globRegex.matches(matchTarget)) return@forEach
                                }
                                searchFileDisplay(file.absolutePath) { runCatching { file.readText(Charsets.UTF_8) }.getOrNull() }
                            }
                        } else return@runCatching """{"error":"既不是文件也不是目录: ${f.absolutePath}"}"""
                    }
                    is FileTarget.SafRef -> {
                        if (target.doc.isFile) {
                            // 单个 SAF 文件：直接搜索
                            val text = runCatching { SafManager.readText(target.doc) }.getOrNull()
                            if (text != null && (textMode || !looksBinary(text))) {
                                searchFileDisplay(target.displayPath) { text }
                            }
                        } else if (target.doc.isDirectory) {
                            // SAF 目录：递归搜索
                            hitLimit = grepSafRecursive(target.doc, regex, globRegex, glob, outputMode, contextLines, headLimit, results, textMode)
                        }
                    }
                    is FileTarget.None -> return@runCatching """{"error":"无权限访问该路径"}"""
                }

                val modeNote = if (outputMode == "content") "" else ""","output_mode":${JsonPrimitive(outputMode)}"""
                """{"count":${results.size},"pattern":${JsonPrimitive(pattern)},"truncated":$hitLimit$modeNote,"results":[${results.joinToString(",")}]}"""
            }.getOrElse { e -> """{"error":"grep 失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}

/** SAF 目录递归正则搜索（grep 工具用）。返回是否因 head_limit 而截断。 */
private fun grepSafRecursive(
    dir: DocumentFile,
    regex: Regex,
    globRegex: Regex?,
    glob: String?,
    outputMode: String,
    contextLines: Int,
    headLimit: Int,
    results: MutableList<String>,
    textMode: Boolean
): Boolean {
    var limited = false
    val globHasPath = glob != null && '/' in glob
    fun recurse(doc: DocumentFile, relDir: String) {
        if (limited) return
        for (child in doc.listFiles()) {
            if (limited) return
            val name = child.name ?: continue
            val childRel = if (relDir.isEmpty()) name else "$relDir/$name"
            if (child.isDirectory) {
                recurse(child, childRel)
            } else if (child.isFile) {
                if (globRegex != null) {
                    val matchTarget = if (globHasPath) childRel else name
                    if (!globRegex.matches(matchTarget)) continue
                }
                val text = runCatching { SafManager.readText(child) }.getOrNull() ?: continue
                if (!textMode && looksBinary(text)) continue   // 默认跳过二进制文件
                val lines = text.lines()
                val matched = mutableListOf<Int>()
                for ((idx, line) in lines.withIndex()) {
                    if (regex.containsMatchIn(line)) matched.add(idx)
                }
                if (matched.isEmpty()) continue
                val displayPath = child.uri.toString()
                when (outputMode) {
                    "files_with_matches" -> {
                        results.add("""{"path":${JsonPrimitive(displayPath)}}""")
                        if (headLimit > 0 && results.size >= headLimit) limited = true
                    }
                    "count" -> {
                        results.add("""{"path":${JsonPrimitive(displayPath)},"count":${matched.size}}""")
                        if (headLimit > 0 && results.size >= headLimit) limited = true
                    }
                    else -> {
                        for (mi in matched) {
                            if (limited) break
                            val lineNum = mi + 1
                            val frag = if (contextLines > 0) {
                                val start = maxOf(0, mi - contextLines)
                                val end = minOf(lines.size - 1, mi + contextLines)
                                val ctx = lines.subList(start, end + 1).mapIndexed { i, l ->
                                    val ln = start + i + 1
                                    if (ln == lineNum) "★ $l" else "  $l"
                                }.joinToString("\n")
                                ""","context":${JsonPrimitive(ctx)},"context_start":${start + 1},"context_end":${end + 1}"""
                            } else ""
                            results.add("""{"path":${JsonPrimitive(displayPath)},"line":$lineNum,"text":${JsonPrimitive(lines[mi].take(500))}$frag}""")
                            if (headLimit > 0 && results.size >= headLimit) limited = true
                        }
                    }
                }
            }
        }
    }
    recurse(dir, "")
    return limited
}

/** 把 glob 转为正则（支持 *、?、以及 ** 形式的目录通配）。用于 grep 工具文件名过滤。 */
private fun globToRegex(glob: String): Regex {
    val sb = StringBuilder()
    sb.append("^")
    var i = 0
    while (i < glob.length) {
        when (val c = glob[i]) {
            '*' -> {
                if (i + 1 < glob.length && glob[i + 1] == '*') {
                    sb.append(".*")
                    i += 2
                    if (i < glob.length && glob[i] == '/') i++
                    continue
                } else sb.append("[^/]*")
            }
            '?' -> sb.append(".")
            else -> sb.append(java.util.regex.Pattern.quote(c.toString()))
        }
        i++
    }
    sb.append("\$")
    return Regex(sb.toString())
}

/** 递归在 SAF DocumentFile 中执行 glob 匹配。 */
private fun globSafRecursive(
    dir: DocumentFile,
    matcher: java.nio.file.PathMatcher,
    maxResults: Int,
    results: MutableList<String>,
    parentPath: String = ""
) {
    for (child in dir.listFiles()) {
        if (results.size >= maxResults) break
        val name = child.name ?: continue
        val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
        if (child.isDirectory) {
            // 目录也匹配 glob
            if (matcher.matches(java.nio.file.Paths.get(childPath))) {
                results.add("""{"path":"${child.uri}","type":"dir"}""")
            }
            globSafRecursive(child, matcher, maxResults, results, childPath)
        } else if (child.isFile) {
            if (matcher.matches(java.nio.file.Paths.get(childPath))) {
                results.add("""{"path":"${child.uri}","type":"file","size":${child.length()}}""")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  SAF 安全访问工具
// ─────────────────────────────────────────────────────────────

/**
 * 打开系统目录选择器，授权访问指定目录（SAF 安全访问，无需存储权限）。
 * 授权后会持久化，后续文件操作自动走 SAF。
 */
fun safOpenDirectory(context: Context): ToolDef = tool("saf_open_directory") {
    description = "通过系统文件选择器安全授权访问一个目录（SAF），无需存储权限。授权后该目录下的文件操作（read_file/write_file/list_files 等）自动走安全访问通道。"
    string("path") {
        description = "建议的目录路径（如 /sdcard/gk），用于后续文件操作时匹配。不填则使用选择器返回的显示名称。"
        required = false
    }
    handler { args ->
        val suggestedPath = args["path"]?.jsonPrimitive?.content
        withContext(Dispatchers.Main) {
            try {
                val uri = SafManager.requestDirectoryAccess()
                if (uri != null) {
                    // 用建议路径或 URI 的最后一段作为显示名
                    val displayPath = suggestedPath ?: uri.lastPathSegment ?: uri.toString()
                    SafManager.saveRoot(displayPath, uri)
                    val roots = SafManager.getAllRoots()
                    val rootsJson = roots.joinToString(",") { "\"${it.first}\"" }
                    """{"ok":true,"path":"$displayPath","uri":"$uri","saf_roots":[$rootsJson]}"""
                } else {
                    """{"ok":false,"message":"用户取消了目录选择"}"""
                }
            } catch (e: Exception) {
                """{"error":"启动目录选择器失败: ${e.message}"}"""
            }
        }
    }
}

/**
 * 列出已授权的 SAF 根目录。
 */
fun safListRoots(context: Context): ToolDef = tool("saf_list_roots") {
    description = "列出所有已通过 SAF 授权的根目录。"
    handler {
        withContext(Dispatchers.IO) {
            val roots = SafManager.getAllRoots()
            if (roots.isEmpty()) return@withContext """{"roots":[],"message":"没有已授权的 SAF 根目录。使用 saf_open_directory 授权一个目录。"}"""
            val entries = roots.joinToString(",") { (path, uri) ->
                """{"path":"$path","uri":"$uri"}"""
            }
            """{"roots":[$entries]}"""
        }
    }
}

/**
 * 移除已授权的 SAF 根目录。
 */
fun safRemoveRoot(context: Context): ToolDef = tool("saf_remove_root") {
    description = "移除一个已授权的 SAF 根目录。"
    string("path") { description = "要移除的根目录路径（与 saf_open_directory 或 saf_list_roots 中显示的路径一致）" }
    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val path = args.requireStr("path", "saf_remove_root")
                val ok = SafManager.removeRoot(path)
                if (ok) """{"ok":true,"message":"已移除: $path"}"""
                else """{"error":"未找到该 SAF 根目录: $path"}"""
            }.getOrElse { e -> """{"error":"移除失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}
