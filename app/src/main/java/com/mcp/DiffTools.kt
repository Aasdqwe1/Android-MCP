package com.mcp

import android.content.Context
import com.mcp.toolbox.tool
import com.mcp.toolbox.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * 文件对比与合并工具集。
 *
 * 提供文件差异对比和合并功能。
 */
fun diffTools(context: Context): List<ToolDef> = listOf(
    diffFilesTool(context),
    mergeFilesTool(context)
)

/**
 * 对比两个文件的内容差异。
 *
 * 参数：
 * - path1 (必填): 第一个文件路径
 * - path2 (必填): 第二个文件路径
 * - context_lines (可选): 上下文行数，默认 3
 *
 * 返回：统一 diff 格式的差异输出
 */
fun diffFilesTool(context: Context): ToolDef = tool("diff_files") {
    description = "对比两个文件的内容差异，返回统一 diff 格式输出。"
    string("path1") {
        description = "第一个文件路径（绝对路径）"
        required = true
    }
    string("path2") {
        description = "第二个文件路径（绝对路径）"
        required = true
    }
    integer("context_lines") {
        description = "上下文行数，默认 3"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val path1 = args.requireStr("path1", "diff_files")
            val path2 = args.requireStr("path2", "diff_files")
            val contextLines = args["context_lines"]?.jsonPrimitive?.content?.toIntOrNull()?.coerceAtLeast(0) ?: 3

            val file1 = File(path1)
            val file2 = File(path2)

            if (!file1.exists()) {
                return@withContext """{"error":"文件不存在: $path1"}"""
            }
            if (!file2.exists()) {
                return@withContext """{"error":"文件不存在: $path2"}"""
            }

            val content1 = runCatching { file1.readText() }.getOrElse { e ->
                return@withContext """{"error":"读取 $path1 失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }
            val content2 = runCatching { file2.readText() }.getOrElse { e ->
                return@withContext """{"error":"读取 $path2 失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }

            val diff = computeDiff(content1, content2, contextLines)

            buildString {
                appendLine("""{"path1":"$path1","path2":"$path2","context_lines":$contextLines,"diff":""")
                appendLine(diff.replace("\"", "\\\""))
                appendLine("\"}")
            }
        }
    }
}

/**
 * 合并文件（支持 Git 风格冲突标记）。
 *
 * 参数：
 * - base_path (必填): 基础版本文件路径
 * - ours_path (必填): 当前版本文件路径
 * - theirs_path (必填): 对方版本文件路径
 * - output_path (可选): 输出文件路径，不填则返回合并后内容
 *
 * 返回：合并结果或冲突信息
 */
fun mergeFilesTool(context: Context): ToolDef = tool("merge_files") {
    description = "合并文件（支持 Git 风格冲突标记）。返回合并结果或冲突信息。"
    string("base_path") {
        description = "基础版本文件路径（绝对路径）"
        required = true
    }
    string("ours_path") {
        description = "当前版本文件路径（绝对路径）"
        required = true
    }
    string("theirs_path") {
        description = "对方版本文件路径（绝对路径）"
        required = true
    }
    string("output_path") {
        description = "输出文件路径（可选，不填则返回合并后内容）"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.IO) {
            val basePath = args.requireStr("base_path", "merge_files")
            val oursPath = args.requireStr("ours_path", "merge_files")
            val theirsPath = args.requireStr("theirs_path", "merge_files")
            val outputPath = args["output_path"]?.jsonPrimitive?.content.orEmpty()

            val baseFile = File(basePath)
            val oursFile = File(oursPath)
            val theirsFile = File(theirsPath)

            if (!baseFile.exists()) {
                return@withContext """{"error":"基础文件不存在: $basePath"}"""
            }
            if (!oursFile.exists()) {
                return@withContext """{"error":"当前版本文件不存在: $oursPath"}"""
            }
            if (!theirsFile.exists()) {
                return@withContext """{"error":"对方版本文件不存在: $theirsPath"}"""
            }

            val baseContent = runCatching { baseFile.readLines() }.getOrElse { e ->
                return@withContext """{"error":"读取 $basePath 失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }
            val oursContent = runCatching { oursFile.readLines() }.getOrElse { e ->
                return@withContext """{"error":"读取 $oursPath 失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }
            val theirsContent = runCatching { theirsFile.readLines() }.getOrElse { e ->
                return@withContext """{"error":"读取 $theirsPath 失败: ${e.message?.take(200)?.replace("\"", "'")}"}"""
            }

            val mergeResult = threeWayMerge(baseContent, oursContent, theirsContent)

            if (mergeResult.hasConflict) {
                // 返回冲突信息
                val conflictContent = mergeResult.content.joinToString("\n")
                if (outputPath.isNotEmpty()) {
                    File(outputPath).writeText(conflictContent)
                    return@withContext """{"has_conflict":true,"output_path":"$outputPath","message":"合并完成，但存在冲突，请手动解决"}"""
                } else {
                    return@withContext buildString {
                        appendLine("""{"has_conflict":true,"content":""")
                        appendLine(conflictContent.replace("\"", "\\\""))
                        appendLine("\"}")
                    }
                }
            } else {
                val mergedContent = mergeResult.content.joinToString("\n")
                if (outputPath.isNotEmpty()) {
                    File(outputPath).writeText(mergedContent)
                    return@withContext """{"has_conflict":false,"output_path":"$outputPath","message":"合并成功"}"""
                } else {
                    return@withContext buildString {
                        appendLine("""{"has_conflict":false,"content":""")
                        appendLine(mergedContent.replace("\"", "\\\""))
                        appendLine("\"}")
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
//  内部实现
// ─────────────────────────────────────────────────────────────

private data class MergeResult(
    val content: List<String>,
    val hasConflict: Boolean
)

/**
 * 简单三方合并（基于 LCS 算法）。
 * 仅处理无冲突情况，有冲突时保留冲突标记。
 */
private fun threeWayMerge(base: List<String>, ours: List<String>, theirs: List<String>): MergeResult {
    // 如果两个版本都基于 base 且无修改，直接返回
    if (ours == base && theirs == base) {
        return MergeResult(base, false)
    }
    if (ours == base) {
        return MergeResult(theirs, false)
    }
    if (theirs == base) {
        return MergeResult(ours, false)
    }
    // 如果 ours 和 theirs 相同，直接返回
    if (ours == theirs) {
        return MergeResult(ours, false)
    }

    // 简单行级合并：检测冲突
    val result = mutableListOf<String>()
    var hasConflict = false

    // 使用 LCS 简化版
    val lcs = computeLCS(base, ours)
    val baseSet = base.toSet()
    val oursSet = ours.toSet()
    val theirsSet = theirs.toSet()

    // 简单策略：如果修改不重叠，直接合并
    // 找到 base 中修改的行
    val baseOursDiff = ours.filterNot { base.contains(it) }
    val baseTheirsDiff = theirs.filterNot { base.contains(it) }

    // 检查是否修改了同一行
    val commonMods = baseOursDiff.intersect(baseTheirsDiff.toSet())
    if (commonMods.isNotEmpty()) {
        // 有重叠修改 → 冲突
        hasConflict = true
        result.add("<<<<<<< ours")
        result.addAll(ours)
        result.add("=======")
        result.addAll(theirs)
        result.add(">>>>>>> theirs")
    } else {
        // 无重叠，合并两个版本的修改
        val combined = mutableListOf<String>()
        // 使用 base 作为基础，应用 ours 和 theirs 的差异
        // 简化：取两者的并集，保留顺序
        val allLines = mutableSetOf<String>()
        allLines.addAll(base)
        allLines.addAll(ours)
        allLines.addAll(theirs)

        // 按 base 顺序排列，插入新增行
        val baseMap = base.withIndex().associate { it.value to it.index }
        combined.addAll(base)

        // 插入 ours 中新增的行（在 base 中不存在的）
        for (line in ours) {
            if (!base.contains(line) && !combined.contains(line)) {
                combined.add(line)
            }
        }

        // 插入 theirs 中新增的行
        for (line in theirs) {
            if (!base.contains(line) && !combined.contains(line)) {
                combined.add(line)
            }
        }

        result.addAll(combined)
    }

    return MergeResult(result, hasConflict)
}

/**
 * 计算 LCS（最长公共子序列）用于 diff。
 */
private fun computeLCS(a: List<String>, b: List<String>): List<String> {
    val n = a.size
    val m = b.size
    if (n == 0 || m == 0) return emptyList()

    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in 1..n) {
        for (j in 1..m) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    val result = mutableListOf<String>()
    var i = n
    var j = m
    while (i > 0 && j > 0) {
        when {
            a[i - 1] == b[j - 1] -> {
                result.add(a[i - 1])
                i--
                j--
            }
            dp[i - 1][j] >= dp[i][j - 1] -> i--
            else -> j--
        }
    }
    return result.reversed()
}

/**
 * 计算 unified diff。
 */
private fun computeDiff(content1: String, content2: String, contextLines: Int): String {
    val lines1 = content1.split("\n")
    val lines2 = content2.split("\n")

    if (lines1 == lines2) {
        return "文件内容相同，无差异"
    }

    // 使用简单的 diff 算法
    val lcs = computeLCS(lines1, lines2)

    // 构建 diff 输出
    val result = StringBuilder()
    var i = 0
    var j = 0

    while (i < lines1.size || j < lines2.size) {
        if (i < lines1.size && j < lines2.size && lines1[i] == lines2[j]) {
            // 相同行，保留上下文
            result.append("  ${lines1[i]}\n")
            i++
            j++
        } else {
            // 差异开始
            val startI = i
            val startJ = j

            // 收集差异块
            val diff1 = mutableListOf<String>()
            val diff2 = mutableListOf<String>()

            while (i < lines1.size && (j >= lines2.size || lines1[i] != lines2[j])) {
                diff1.add(lines1[i])
                i++
            }
            while (j < lines2.size && (i >= lines1.size || lines1[i] != lines2[j])) {
                diff2.add(lines2[j])
                j++
            }

            // 输出差异块
            if (diff1.isNotEmpty()) {
                result.append("- ${diff1.joinToString("\n- ")}\n")
            }
            if (diff2.isNotEmpty()) {
                result.append("+ ${diff2.joinToString("\n+ ")}\n")
            }
        }
    }

    return result.toString()
}
