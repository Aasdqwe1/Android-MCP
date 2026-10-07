package com.mcp.toolbox

/**
 * 顺序多编辑：在同一文本上按顺序应用多个 `{old_string → new_string}` 替换。
 *
 * 语义与 Reasonix multi_edit 一致：每个编辑都基于前一个编辑的结果在内存中应用，
 * **全部成功才产出最终文本**；任一编辑失败则整体失败（[EditBatchResult.Failure]），
 * 由调用方决定不写盘，避免「改到一半」留下部分修改。
 *
 * 与 edit_file 同源：支持 CRLF/LF 归一化匹配（保存保持原换行风格）、
 * old_string 必须唯一或显式指定 occurrence（1-based）。
 */
data class EditSpec(
    val oldString: String,
    val newString: String,
    /** 1-based；缺省要求 old_string 在文本中唯一，否则报错。 */
    val occurrence: Int? = null
)

/** 单个编辑的应用结果。 */
sealed class SingleEditResult {
    /** 应用成功：newText 为替换后的完整文本，outcome 记录本次编辑信息。 */
    data class Ok(val newText: String, val outcome: EditOutcome) : SingleEditResult()

    /** 应用失败：reason 面向模型、可直接回灌。 */
    data class Error(val reason: String) : SingleEditResult()
}

/** 一次成功编辑的元信息。 */
data class EditOutcome(
    /** 编辑位置行号（1-based，基于应用前的文本）。 */
    val editLine: Int,
    /** 被替换的 old_string 长度（字符数）。 */
    val replacedLength: Int,
    /** 替换后的 new_string 长度（字符数）。 */
    val newLength: Int,
    /** 是否为模糊匹配命中（old_string 与文件内容存在行尾空白/缩进/读前缀差异时自动容错）。 */
    val fuzzy: Boolean = false
)

/** 整批顺序编辑的结果。 */
sealed class EditBatchResult {
    /** 全部成功：newText 为最终文本，outcomes 与 edits 一一对应。 */
    data class Success(val newText: String, val outcomes: List<EditOutcome>) : EditBatchResult()

    /** 第 step 个编辑（1-based）失败，reason 面向模型。 */
    data class Failure(val step: Int, val reason: String) : EditBatchResult()
}

/**
 * 按顺序应用一批编辑（[MultiEdit.kt] 的对外入口）。
 *
 * @return 任一失败返回 [EditBatchResult.Failure]（步骤号 + 原因），否则 [EditBatchResult.Success]。
 */
fun applySequentialEdits(fullText: String, edits: List<EditSpec>): EditBatchResult {
    if (edits.isEmpty()) return EditBatchResult.Failure(0, "edits 为空，没有可应用的编辑。")
    var current = fullText
    val outcomes = ArrayList<EditOutcome>(edits.size)
    for ((idx, spec) in edits.withIndex()) {
        val step = idx + 1
        when (val r = applySingleEdit(current, spec)) {
            is SingleEditResult.Ok -> {
                current = r.newText
                outcomes.add(r.outcome)
            }
            is SingleEditResult.Error -> return EditBatchResult.Failure(step, r.reason)
        }
    }
    return EditBatchResult.Success(current, outcomes)
}

/**
 * 在 [text] 上应用单次替换（与 edit_file 的定位/替换逻辑同源）。
 *
 * - 换行归一化：CRLF/LF 差异不导致匹配失败，但替换按原始字符位置进行，保持文件原换行风格；
 * - old_string 必须唯一，否则需显式 [EditSpec.occurrence]（1-based）；
 * - 空 old_string 拒绝（避免定位死循环）。
 */
fun applySingleEdit(text: String, spec: EditSpec): SingleEditResult {
    val oldString = spec.oldString
    val newString = spec.newString
    if (oldString.isEmpty()) {
        return SingleEditResult.Error("old_string 不能为空（空串会导致定位死循环）。")
    }

    // CRLF/LF 归一化匹配：构建 归一化文本 → 原始文本 的字符偏移映射（\r\n → \n 后长度缩短）
    val normToOrig = ArrayList<Int>()
    val normFullSb = StringBuilder()
    var fi = 0
    while (fi < text.length) {
        if (fi + 1 < text.length && text[fi] == '\r' && text[fi + 1] == '\n') {
            normFullSb.append('\n'); normToOrig.add(fi); fi += 2
        } else {
            normFullSb.append(text[fi]); normToOrig.add(fi); fi++
        }
    }
    val normFull = normFullSb.toString()
    val normOld = oldString.replace("\r\n", "\n")

    // 找出全部出现位置
    val indices = ArrayList<Int>().apply {
        var from = 0
        while (true) {
            val i = normFull.indexOf(normOld, from)
            if (i == -1) break
            add(i)
            from = i + normOld.length
        }
    }
    if (indices.isEmpty()) {
        // 模糊匹配容错（对齐 DeepSeek-Reasonix 官方实现）：old_string 与文件存在
        // 行尾空白 / tab 与空格 / read_file 的 LINE[HASH] 前缀 差异时自动归一化匹配。
        // 命中必须唯一；仍找不到才报错（含最接近行诊断）。
        val fuzzyRanges = fuzzyEditRanges(text, oldString)
        if (fuzzyRanges.size == 1) {
            val r = fuzzyRanges[0]
            // 注意：r 是 `start until end` 排他区间，替换终点用 r.last + 1
            val newText = text.replaceRange(r.first, r.last + 1, adaptNewLineEndings(text, newString))
            val editLine = text.substring(0, r.first).count { it == '\n' } + 1
            return SingleEditResult.Ok(
                newText,
                EditOutcome(editLine = editLine, replacedLength = oldString.length, newLength = newString.length, fuzzy = true)
            )
        }
        if (fuzzyRanges.size > 1) {
            return SingleEditResult.Error(
                "old_string 精确未找到，但模糊匹配到 ${fuzzyRanges.size} 处（行尾空白/缩进/前缀差异）。" +
                    "请补充上下文，或先用 read_file(return_format=raw)/get_block 获取精确原文作为 old_string 后重试。"
            )
        }
        // 近似诊断：用 old_string 的首个非空行锚定文件中最接近的位置，再逐行对比找第一处不一致，
        // 直接告诉模型差在哪一行、哪段内容（缩进/空白/内容差异），减少盲目重试。
        val diag = run {
            val textLines = text.lines()
            val oldLines = oldString.split("\n")
            val probeIdx = oldLines.indexOfFirst { it.isNotBlank() }
            if (probeIdx < 0) "" else {
                val probe = oldLines[probeIdx].trim()
                val hitIdx = textLines.indexOfFirst { it.contains(probe) }
                if (hitIdx < 0) "" else {
                    val startLine = hitIdx - probeIdx  // 对齐后 old 第 0 行对应文件的 startLine 行
                    var mismatch: String? = null
                    for (k in oldLines.indices) {
                        val fileLine = textLines.getOrNull(startLine + k)
                        if (fileLine == null) {
                            mismatch = "old_string 第 ${k + 1} 行超出文件末尾"; break
                        }
                        if (fileLine.trim() != oldLines[k].trim()) {
                            mismatch = "old_string 第 ${k + 1} 行「${oldLines[k].trim().take(50)}」与文件第 ${startLine + k + 1} 行「${fileLine.trim().take(50)}」不一致"
                            break
                        }
                    }
                    "; 最接近的匹配在第 ${hitIdx + 1} 行附近：" + (mismatch
                        ?: "各行 trim 后一致（疑似行尾空白或换行符差异），可用 read_file(return_format=raw) 核对")
                }
            }
        }
        return SingleEditResult.Error("未在文件中找到匹配的 old_string。请确保 old_string 与文件内容完全一致（包括缩进、换行）$diag。")
    }
    if (indices.size > 1 && spec.occurrence == null) {
        // inferScalar 把 "foo" 解析为 foo，可能导致 foo 同时作为子串出现在 "foo" 行和其他行。
        // 尝试加回外层引号，若带引号的版本唯一匹配，则用它（fuzzy 回退）。
        val quotedResult = tryQuotedMatch(normFull, normToOrig, text, oldString, newString)
        if (quotedResult != null) return quotedResult

        val preview = indices.take(5).joinToString("、") { ln ->
            val origPos = normToOrig.getOrElse(ln) { text.length }
            "第 ${text.substring(0, origPos).count { it == '\n' } + 1} 行"
        }
        val more = if (indices.size > 5) " 等" else ""
        return SingleEditResult.Error(
            "old_string 在文件中出现 ${indices.size} 次（$preview$more），为避免改错位置请二选一：" +
                "① 在 old_string 中补充前后上下文使其成为唯一串；② 传入 occurrence 参数指定第几次匹配（1-based，1..${indices.size}）。"
        )
    }
    // 单次匹配但匹配位置在引号内部（inferScalar 剥掉了文件内容的外层引号）：
    // 文件有 "foo"，old_string=foo（剥引号后），匹配到 "foo" 内部的 foo。
    // 尝试加回引号 → "foo" 精确匹配整行 → 替换整个引号包裹的内容。
    if (indices.size == 1 && spec.occurrence == null && oldString.lines().size == 1) {
        val matchStart = indices[0]
        val matchEnd = matchStart + normOld.length
        // 检查匹配位置前后是否都被引号包裹
        val hasLeftQuote = matchStart > 0 && normFull[matchStart - 1] == '"'
        val hasRightQuote = matchEnd < normFull.length && normFull[matchEnd] == '"'
        if (hasLeftQuote && hasRightQuote) {
            val quotedResult = tryQuotedMatch(normFull, normToOrig, text, oldString, newString)
            if (quotedResult != null) return quotedResult
        }
    }
    val which = if (spec.occurrence != null) {
        if (spec.occurrence < 1 || spec.occurrence > indices.size) {
            return SingleEditResult.Error(
                "occurrence=${spec.occurrence} 超出范围，old_string 当前共出现 ${indices.size} 次（有效 1..${indices.size}）。" +
                    "注意：multi_edit 的 edits 按顺序应用，occurrence 应基于前序编辑后的文本计数——前序编辑可能已替换掉部分匹配，剩余次数会少于全文件总量。"
            )
        }
        spec.occurrence - 1
    } else 0

    // 归一化位置 → 原始文本位置（还原 \r，保持原换行风格）
    val normIdx = indices[which]
    val origStart = normToOrig.getOrElse(normIdx) { normIdx }
    val origEnd = normToOrig.getOrElse(normIdx + normOld.length) { normIdx + normOld.length }

    val newText = text.replaceRange(origStart, origEnd, newString)
    val editLine = text.substring(0, origStart).count { it == '\n' } + 1
    return SingleEditResult.Ok(
        newText,
        EditOutcome(editLine = editLine, replacedLength = oldString.length, newLength = newString.length)
    )
}

// ── 模糊匹配（对齐 DeepSeek-Reasonix 官方 fuzzyEditRanges）──────────────────

/** read_file 的行前缀：`N[HASH]: `（如 `12[abc123]: `）。 */
private val READ_FILE_PREFIX = Regex("^\\d+\\[[0-9a-fA-F]{6}\\]: ")

/**
 * 引号回退：inferScalar 剥掉了文件内容的外层引号后，old_string 匹配到的是引号内部子串。
 * 尝试加回引号（"oldString"），若带引号的版本唯一匹配，则替换整个引号包裹的内容。
 * 仅对单行 old_string 有效（多行内容不会整体被引号包裹）。
 *
 * @return 唯一匹配时返回 Ok，否则 null（调用方继续其它逻辑）
 */
private fun tryQuotedMatch(
    normFull: String,
    normToOrig: List<Int>,
    text: String,
    oldString: String,
    newString: String
): SingleEditResult? {
    if (oldString.lines().size != 1) return null
    val quotedOld = "\"$oldString\""
    val qNorm = quotedOld.replace("\r\n", "\n")
    val quotedIndices = ArrayList<Int>()
    var qFrom = 0
    while (true) {
        val qi = normFull.indexOf(qNorm, qFrom); if (qi == -1) break
        quotedIndices.add(qi); qFrom = qi + qNorm.length
    }
    if (quotedIndices.size == 1) {
        val qi = quotedIndices[0]
        val origStart = normToOrig.getOrElse(qi) { qi }
        val origEnd = normToOrig.getOrElse(qi + qNorm.length) { qi + qNorm.length }
        val newText = text.replaceRange(origStart, origEnd, adaptNewLineEndings(text, newString))
        val editLine = text.substring(0, origStart).count { it == '\n' } + 1
        return SingleEditResult.Ok(newText, EditOutcome(editLine, quotedOld.length, newString.length, fuzzy = true))
    }
    return null
}

/** 模糊归一化配置：按宽松程度逐步尝试。 */
private data class FuzzyMode(val stripReadPrefix: Boolean, val trimTrailing: Boolean, val expandTabs: Boolean)

/** 单行模糊归一化：剥离 read 前缀 / 去行尾空白与 \r / tab 展开为 4 空格。 */
private fun normalizeFuzzyLine(line: String, mode: FuzzyMode): String {
    var body = line.trimEnd('\n')
    if (mode.stripReadPrefix) body = READ_FILE_PREFIX.replaceFirst(body, "")
    if (mode.trimTrailing) body = body.trimEnd(' ', '\t', '\r')
    if (mode.expandTabs) body = body.replace("\t", "    ")
    return body
}

/**
 * 多模式模糊定位 old_string 在 text 中的全部命中区间（原始文本 [start, end)）。
 * 依次尝试：① 行尾空白；② 行尾空白+tab展开；若 old_string 全部非空行带 read 前缀，再试 ③④ 剥离前缀版。
 * 行尾语义对齐官方 fuzzyWindowEnd：old 末行无换行时保留 content 末行的换行（替换区间排除它）。
 */
private fun fuzzyEditRanges(text: String, oldString: String): List<IntRange> {
    if (oldString.isEmpty() || text.isEmpty()) return emptyList()
    val textLines = text.split('\n')
    var oldLines = oldString.split('\n')
    val oldEndsWithNewline = oldString.endsWith("\n")
    if (oldEndsWithNewline) oldLines = oldLines.dropLast(1)  // 尾部空串仅表示行尾换行
    if (oldLines.isEmpty() || oldLines.size > textLines.size) return emptyList()
    // 行起点偏移表（行 i 的起点 = offsets[i]）
    val offsets = ArrayList<Int>(textLines.size)
    var off = 0
    for (line in textLines) { offsets.add(off); off += line.length + 1 }
    if (offsets.size > textLines.size) offsets.removeAt(offsets.lastIndex)

    val oldHasReadPrefix = oldLines.filter { it.isNotEmpty() }.all { READ_FILE_PREFIX.containsMatchIn(it) }
    val modes = mutableListOf(FuzzyMode(false, true, false), FuzzyMode(false, true, true))
    if (oldHasReadPrefix) {
        modes += FuzzyMode(true, true, false)
        modes += FuzzyMode(true, true, true)
    }
    for (mode in modes) {
        val normOld = oldLines.map { normalizeFuzzyLine(it, mode) }
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i + oldLines.size <= textLines.size) {
            var ok = true
            for (k in oldLines.indices) {
                val contentLine = textLines[i + k]
                // 换行语义：old 非末行必然要求换行；末行仅在 old_string 以换行结尾时要求 content 对应行也在文件中间
                val oldNeedsNewline = k < oldLines.size - 1 || oldEndsWithNewline
                if (oldNeedsNewline && i + k + 1 >= offsets.size) { ok = false; break }
                if (normalizeFuzzyLine(contentLine, mode) != normOld[k]) { ok = false; break }
            }
            if (ok) {
                val start = offsets[i]
                var end = if (i + oldLines.size < offsets.size) offsets[i + oldLines.size] else text.length
                // 对齐官方 fuzzyWindowEnd：old 末行无换行而 content 窗口末行有换行时，
                // 把该换行（及 CRLF 的 \r）排除在替换区间外，保留原换行风格
                if (!oldEndsWithNewline && i + oldLines.size < offsets.size && end > offsets[i + oldLines.size - 1]) {
                    end -= 1
                    if (end > offsets[i + oldLines.size - 1] && text[end - 1] == '\r') end--
                }
                ranges.add(start until end)
                i += oldLines.size
                continue
            }
            i++
        }
        if (ranges.isNotEmpty()) return ranges
    }
    return emptyList()
}

/** 按文件换行风格适配 new_string：文件为 CRLF 而 new_string 为 LF 时展开为 CRLF（保持整文件风格一致）。 */
private fun adaptNewLineEndings(text: String, newString: String): String =
    if (text.contains("\r\n") && !newString.contains("\r\n")) newString.replace("\n", "\r\n") else newString

/**
 * 跨文件搜索替换（单文件层面）的结果。
 *
 * @property replacedCount 实际替换次数（非重叠匹配数），0 表示该文件不含 [oldString]。
 */
data class ReplaceAllResult(
    /** 替换后的完整文本（未命中时与 [text] 相同）。 */
    val newText: String,
    /** 实际替换的次数（非重叠匹配数）。 */
    val replacedCount: Int
)

/**
 * 将 [text] 中所有「非重叠」出现的 [oldString] 替换为 [newString]（跨文件 search_replace 单文件内核）。
 *
 * 定位语义对齐 [applySingleEdit]：CRLF/LF 归一化匹配、保持原换行风格、空 old_string 直接返回原样。
 * 有意不做模糊匹配（批量替换宁缺毋滥）；且所有匹配区间先基于原始文本一次性算出再从右往左拼接，
 * 天然非重叠、偏移稳定，也规避 new_string 含 old_string 时的无限循环。
 */
fun replaceAllOccurrences(text: String, oldString: String, newString: String): ReplaceAllResult {
    if (oldString.isEmpty() || text.isEmpty()) return ReplaceAllResult(text, 0)

    // CRLF/LF 归一化匹配：构建 归一化文本 → 原始文本 的字符偏移映射（与 applySingleEdit 同源）
    val normToOrig = ArrayList<Int>()
    val normSb = StringBuilder()
    var fi = 0
    while (fi < text.length) {
        if (fi + 1 < text.length && text[fi] == '\r' && text[fi + 1] == '\n') {
            normSb.append('\n'); normToOrig.add(fi); fi += 2
        } else {
            normSb.append(text[fi]); normToOrig.add(fi); fi++
        }
    }
    val normFull = normSb.toString()
    val normOld = oldString.replace("\r\n", "\n")

    // 找出全部非重叠匹配（归一化坐标）
    val normIndices = ArrayList<Int>()
    var from = 0
    while (true) {
        val i = normFull.indexOf(normOld, from)
        if (i == -1) break
        normIndices.add(i)
        from = i + normOld.length
    }
    if (normIndices.isEmpty()) return ReplaceAllResult(text, 0)

    // 归一化坐标 → 原始文本区间 [start, end)，全部算好后从右往左拼接（偏移稳定）
    val ranges = ArrayList<IntRange>(normIndices.size)
    for (normIdx in normIndices) {
        val origStart = normToOrig[normIdx]
        val endNorm = normIdx + normOld.length
        val origEnd = if (endNorm < normToOrig.size) normToOrig[endNorm] else text.length
        ranges.add(origStart until origEnd)
    }
    val adaptedNew = adaptNewLineEndings(text, newString)
    var result = text
    for (r in ranges.asReversed()) {
        result = result.replaceRange(r.first, r.last + 1, adaptedNew)
    }
    return ReplaceAllResult(result, ranges.size)
}
