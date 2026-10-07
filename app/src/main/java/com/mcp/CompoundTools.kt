package com.mcp

import android.content.Context
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * 复合工具：将多个原子操作合并为一次调用，减少 LLM 编排步骤。
 *
 * 设计原则：
 * - 覆盖最高频的"搜索→读取"和"读取→编辑"链路
 * - 内部自动完成中间步骤，LLM 只需一次调用
 * - 返回结果包含完整上下文和哈希锚点，供后续原子工具使用
 */

/** SHA-256 前 6 位短哈希，用于行级锚定 */
private fun shaShort(line: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(line.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(6)
}

/** 行级前缀：LINE[HASH]: content */
private fun linePrefix(lineNum: Int, line: String): String = "$lineNum[${shaShort(line)}]: $line"

/** 判断文本是否疑似二进制文件（ripgrep 风格，逻辑与 FileTools.looksBinary 一致）。 */
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

/**
 * 单文件搜索上限：超过此尺寸的文件跳过整文件读入，避免 OOM。
 * 日志曾出现 128MB/256MB 分配失败（OutOfMemoryError），根因是 readText() 把大文件整块读入。
 */
private const val MAX_SEARCH_FILE_BYTES = 16 * 1024 * 1024 // 16MB

/** 二进制探测读取的字节数（仅读文件前缀，不整文件读入）。 */
private const val BINARY_PROBE_BYTES = 8 * 1024 // 8KB

/**
 * 只读取文件前 [probeBytes] 字节判断是否为二进制，避免像 looksBinary(text) 那样先整文件读入。
 * 语义与 looksBinary 一致：命中 NUL 即判二进制；否则按前缀内控制字符比例（默认 10%）判定。
 */
private fun looksBinaryPrefix(file: File, probeBytes: Int = BINARY_PROBE_BYTES, threshold: Double = 0.10): Boolean {
    val buf = ByteArray(probeBytes)
    val n = file.inputStream().use { it.read(buf) }
    if (n <= 0) return false
    if (buf.copyOf(n).toString(Charsets.UTF_8).contains('\u0000')) return true
    var ctrl = 0
    for (i in 0 until n) {
        val code = buf[i].toInt() and 0xFF
        val isControl = code in 0x00..0x08 || code in 0x0B..0x0C || code in 0x0E..0x1F || code == 0x7F
        if (isControl) ctrl++
    }
    return ctrl.toDouble() / n > threshold
}

/**
 * 智能块展开（与 FileTools.kt 中的实现一致）。
 */
private fun expandToBlock(lines: List<String>, lineIdx: Int): Pair<Int, Int> {
    if (lines.isEmpty()) return 0 to 0
    val total = lines.size
    val targetLine = lines[lineIdx]
    val targetIndent = targetLine.length - targetLine.trimStart().length

    // 花括号匹配
    var braceStart = lineIdx
    var braceEnd = lineIdx
    var openBraceFound = false
    for (i in lineIdx downTo 0) {
        if (lines[i].contains("{")) { braceStart = i; openBraceFound = true; break }
    }
    if (openBraceFound) {
        var depth = 0
        var started = false
        for (i in braceStart..<total) {
            for (c in lines[i]) { if (c == '{') { depth++; started = true }; if (c == '}') depth-- }
            if (started && depth <= 0) { braceEnd = i; break }
        }
    }

    // 缩进对齐
    val defKeywords = Regex("""^\s*(fun|def|function|class|struct|enum|interface|trait|object|type|val|var|const|let|import|package|namespace|module|public|private|protected|abstract|open|data|sealed|override|inline|suspend|tailrec|operator|infix)\b""")
    var indentStart = lineIdx
    var indentEnd = lineIdx
    for (i in lineIdx downTo 0) {
        val trimmed = lines[i].trim()
        if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#") || trimmed.startsWith("/*") || trimmed.startsWith("*")) continue
        val indent = lines[i].length - trimmed.length
        if (indent <= targetIndent) { indentStart = i; if (defKeywords.containsMatchIn(trimmed)) break }
    }
    for (i in lineIdx..<total) {
        val trimmed = lines[i].trim()
        if (trimmed.isEmpty() || trimmed == "}") break
        val indent = lines[i].length - trimmed.length
        if (indent < targetIndent && trimmed.isNotEmpty() && !trimmed.startsWith("//") && !trimmed.startsWith("#")) { indentEnd = i - 1; break }
        indentEnd = i
    }

    // 空行段落
    var paraStart = lineIdx
    var paraEnd = lineIdx
    for (i in lineIdx downTo maxOf(0, lineIdx - 20)) { paraStart = i; if (lines[i].trim().isEmpty()) { paraStart = i + 1; break } }
    for (i in lineIdx..<minOf(total, lineIdx + 20)) { paraEnd = i; if (lines[i].trim().isEmpty()) { paraEnd = i - 1; break } }

    val braceSpan = braceEnd - braceStart
    val indentSpan = indentEnd - indentStart
    return when {
        braceSpan > 0 && braceSpan <= 500 -> braceStart to braceEnd
        indentSpan > 0 && indentSpan <= 200 -> indentStart to indentEnd
        else -> paraStart to paraEnd
    }
}

// ─────────────────────────────────────────────────────────────
//  复合工具 1: search_and_read
// ─────────────────────────────────────────────────────────────

/**
 * 搜索内容关键词 → 自动读取匹配处的语义块。
 *
 * 将 search_files + read_with_context 合并为一次调用。
 * LLM 只需提供关键词，即可获得包含目标行的完整代码块（含哈希锚点）。
 */
fun searchAndReadTool(context: Context): ToolDef = tool("search_and_read") {
    description = "搜索内容关键词，并自动读取第一个匹配处所在的完整语义块（函数/类/段落）。" +
        "一次调用替代 search_files + read_with_context 两步。返回内容含行号哈希锚点，可直接用于 edit_file。" +
        "适合：查找函数定义、定位配置项、搜索日志关键词。"

    string("content_pattern") {
        description = "搜索关键词（大小写不敏感）。工具会定位到第一个匹配行，并展开到完整的函数/类/代码块。"
        required = true
    }
    string("directory") {
        description = "搜索根目录（必须是目录路径，不填则为 filesDir）。支持绝对路径和相对路径。"
        required = false
    }
    string("path") {
        description = "directory 的别名，兼容旧调用（必须是目录路径，不能是文件路径）。支持绝对路径和相对路径。"
        required = false
    }
    string("file_pattern") {
        description = "文件名过滤模式（大小写不敏感），如 .kt、build.gradle、README。限制只搜索特定文件。注意：不是 glob 通配符，是子串包含匹配。"
        required = false
    }
    string("context_lines") {
        description = "上下文行数（默认 auto）。设为 auto 自动展开到语义块边界；设为数字则返回固定行数上下文。"
        required = false
    }
    boolean("text") {
        description = "是否强制按文本搜索二进制文件（默认 false：自动跳过含 NUL 等二进制特征的文件，ripgrep 风格）。"
        required = false
    }

    handler { args ->
        withContext(Dispatchers.IO) {
            runCatching {
                val contentPattern = args.requireStr("content_pattern", "search_and_read")
                val dirPath = args["directory"]?.jsonPrimitive?.content ?: args["path"]?.jsonPrimitive?.content ?: ""
                val filePattern = args["file_pattern"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                val contextLines = args["context_lines"]?.jsonPrimitive?.content
                val textMode = args["text"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) == true

                val (target, err) = if (dirPath.isEmpty())
                    FileTarget.FileRef(context.filesDir) to null
                else
                    resolveTarget(context, dirPath)
                if (err != null) return@runCatching """{"error":"$err"}"""

                val matchingFiles = mutableListOf<Pair<File, Int>>() // file -> match line index (0-based)
                var firstLines: List<String>? = null // 第一个命中文件的行缓存，避免二次读盘

                when (target) {
                    is FileTarget.FileRef -> {
                        val dir = target.file
                        if (!dir.exists()) return@runCatching """{"error":"目录不存在: ${dir.absolutePath}"}"""
                        if (!dir.isDirectory) return@runCatching """{"error":"不是目录: ${dir.absolutePath}"}"""
                        // 命中第一个匹配即停止遍历：search_and_read 只关心首个匹配，
                        // 无需扫完整棵树累计 match_count，避免大目录下不必要的 IO/GC 开销。
                        // 过滤时捕获异常，避免 /dev/fd/* 等不可访问路径导致遍历中断（PRoot 环境常见）。
                        val walker = dir.walkTopDown().filter {
                            try { it.isFile } catch (_: Exception) { false }
                        }.iterator()
                        walk@ while (walker.hasNext()) {
                            val file = walker.next()
                            if (filePattern != null && !file.name.contains(filePattern, ignoreCase = true)) continue
                            // 大小守卫：跳过超大文件，避免整文件读入触发 OOM（日志曾出现 128MB/256MB 分配失败）。
                            // 捕获异常，避免 /dev/fd/* 等不可访问路径导致中断。
                            val fileSize = try { file.length() } catch (_: Exception) { Long.MAX_VALUE }
                            if (fileSize > MAX_SEARCH_FILE_BYTES) continue
                            // 先用文件前缀判断二进制，避免整文件读入（looksBinary 需全文本，代价高）。
                            // 捕获异常，避免 /dev/fd/* 等不可访问路径导致中断。
                            val isBinary = try { !textMode && looksBinaryPrefix(file) } catch (_: Exception) { false }
                            if (isBinary) continue
                            val hit = runCatching {
                                val lines = file.readLines(Charsets.UTF_8)
                                for ((idx, line) in lines.withIndex()) {
                                    if (line.contains(contentPattern, ignoreCase = true)) {
                                        firstLines = lines
                                        return@runCatching file to idx
                                    }
                                }
                                null
                            }.getOrNull()
                            if (hit != null) {
                                matchingFiles.add(hit)
                                break@walk
                            }
                        }
                    }
                    is FileTarget.SafRef -> {
                        return@runCatching """{"error":"SAF 路径暂不支持 search_and_read，请使用 search_files 替代"}"""
                    }
                    is FileTarget.None -> return@runCatching """{"error":"无权限访问该路径"}"""
                }

                if (matchingFiles.isEmpty()) {
                    return@runCatching """{"error":"未找到匹配的内容: ${JsonPrimitive(contentPattern)}"}"""
                }

                // 取第一个匹配文件（优先复用扫描时已缓存的行，避免二次读盘）
                val (firstFile, matchIdx) = matchingFiles.first()
                val lines = firstLines ?: firstFile.readLines(Charsets.UTF_8)
                val total = lines.size
                val fullText = if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n"
                val fileHash = try {
                    val d = MessageDigest.getInstance("SHA-256")
                    d.digest(fullText.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
                } catch (e: Exception) { "" }

                val (blockStart, blockEnd) = if (contextLines == "auto" || contextLines == null) {
                    expandToBlock(lines, matchIdx)
                } else {
                    val cl = contextLines.toIntOrNull() ?: 3
                    maxOf(0, matchIdx - cl) to minOf(total - 1, matchIdx + cl)
                }

                val blockContent = lines.subList(blockStart, blockEnd + 1)
                    .mapIndexed { i, l ->
                        val actualLine = blockStart + i + 1
                        if (actualLine == matchIdx + 1) {
                            "★ $l"
                        } else {
                            linePrefix(actualLine, l)
                        }
                    }.joinToString("\n")

                // 注：命中首个匹配即停止遍历，故 match_count 固定为 1（首个命中文件），不再统计全树命中数。
                """{"ok":true,"path":"${firstFile.absolutePath}","sha256":"$fileHash","total_lines":$total,"match_line":${matchIdx + 1},"match_count":${matchingFiles.size},"block_start":${blockStart + 1},"block_end":${blockEnd + 1},"content":${JsonPrimitive(blockContent)}}"""
            }.getOrElse { e -> """{"error":"search_and_read 失败: ${e.message?.take(200)?.replace("\"", "'")}"}""" }
        }
    }
}
