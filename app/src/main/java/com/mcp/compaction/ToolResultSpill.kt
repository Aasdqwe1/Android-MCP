package com.mcp.compaction

import android.content.Context
import com.mcp.LogStore
import java.io.File
import java.nio.charset.Charset

/**
 * 工具结果 spill 落盘（对齐 deepseek-harness `spill-policy`）。
 *
 * 当一条**纯文本**工具结果（按 UTF-8 字节计）超过 [MAX_INLINE_BYTES] 时，把**完整文本**写入
 * 会话作用域的 spill 目录（`<filesDir>/spill/<sessionId>/`），并把喂给模型的正文替换为
 * 「头/尾预览 + 定位符 + 取回指引」，从而把长会话里的大工具输出（读文件 / 跑命令 / 抓网页）从
 * 上下文里挪出去，避免撑爆窗口、也避免 B4 硬剪掉仍有价值的细节。
 *
 * 设计取舍（与 dsh 一致）：
 *  - **best-effort**：无 session 归属、无存储后端、写盘失败时一律**保留原样**，绝不把成功的工具调用
 *    变成错误，也不隐藏原始内容（dsh：spill failure must NEVER turn a successful call into an isError）。
 *  - **跳过 read 类工具**（[SKIP_TOOLS]）以避免「read → spill → 再 read」死循环（dsh 同样跳过
 *    `read` 的模型面分支——它正是产生巨大日志的工具）。
 *  - **仅处理纯文本结果**；非文本（图片 / 二进制）不 spill。
 *  - 预览按 **UTF-8 字节**预算切分（头/尾各约一半），绝不切断增补平面字符的代理对。
 */
object ToolResultSpill {

    /** 模型面上下文上限（UTF-8 字节）。超过则 spill。默认 8 KiB，与 B4 剪枝阈值同量级。 */
    const val MAX_INLINE_BYTES = 8_192

    /** 预览在头/尾之间的字节分配（与 dsh TextRetainer headTail 一致：各占一半）。 */
    private const val HEAD_RATIO = 0.5

    /** 跳过 spill 的工具名（小写），避免读类工具触发 read→spill→read 循环。 */
    private val SKIP_TOOLS = setOf("read", "read_file", "readfile", "cat", "type", "read_text")

    /** spill 结果：替换后的模型面内容 + 是否真的发生了落盘。 */
    data class Result(val content: String, val spilled: Boolean)

    /**
     * 若 [rawContent] 超过上限则 spill，否则原样返回。
     *
     * @param context   用于定位会话作用域 spill 目录（[Context.getFilesDir]）。
     * @param sessionId 归属会话 id（spill 目录按会话隔离，便于整会话清理）。
     * @param callId    工具调用 id（用作落盘文件名，便于与 tool_call_id 对应）。
     * @param toolName  工具名（命中 [SKIP_TOOLS] 则跳过 spill）。
     * @param rawContent 工具返回的纯文本结果（UTF-8）。
     */
    fun maybeSpill(
        context: Context,
        sessionId: String,
        callId: String,
        toolName: String,
        rawContent: String
    ): Result {
        if (rawContent.isEmpty()) return Result(rawContent, false)
        if (SKIP_TOOLS.contains(toolName.lowercase())) return Result(rawContent, false)
        val size = rawContent.toByteArray(UTF8).size
        if (size <= MAX_INLINE_BYTES) return Result(rawContent, false)

        val dir = spillDir(context, sessionId)
        val file = File(dir, "${safeName(callId)}.txt")
        return try {
            dir.mkdirs()
            file.writeText(rawContent, UTF8)
            val preview = previewContent(rawContent, MAX_INLINE_BYTES)
            // 定位符用相对于 filesDir 的路径：本 App 的 read_file 工具支持「绝对路径 或 相对于 filesDir」，
            // 模型可直接 read_file(path="spill/<sessionId>/<callId>.txt") 取回完整内容，闭环 spill→read。
            val locator = "spill/${safeName(sessionId)}/${safeName(callId)}.txt"
            val notice = "(已省略约 ${size - preview.toByteArray(UTF8).size} 字节。完整结果已存盘（相对路径：$locator；" +
                "绝对路径：${file.absolutePath}）。如需查看，请用 read_file 打开该相对路径取回全文，或先摘要关键部分再继续。"
            Result("$preview\n$notice", true)
        } catch (e: Exception) {
            // best-effort：写盘失败保留原样（绝不丢失/报错工具结果）
            LogStore.w("SPILL", "工具结果落盘失败（${toolName} $callId），保留内联：${e.message}")
            Result(rawContent, false)
        }
    }

    /** 会话作用域 spill 根目录。 */
    fun spillDir(context: Context, sessionId: String): File =
        File(context.filesDir, "spill").resolve(safeName(sessionId))

    /** 整会话清理（切换/删除会话时调用，避免 spill 文件无限堆积）。 */
    fun clearSession(context: Context, sessionId: String) {
        runCatching { spillDir(context, sessionId).deleteRecursively() }
    }

    /**
     * 纯函数预览：把 [text] 压成 [budgetBytes] UTF-8 字节上限内的「头 + 省略标记 + 尾」。
     * 不依赖 Context，可单元测试。头/尾各占预算的一半，按 code point 累加字节，绝不切断代理对。
     */
    fun previewContent(text: String, budgetBytes: Int): String {
        if (budgetBytes <= 0) return ""
        if (text.toByteArray(UTF8).size <= budgetBytes) return text
        val headBudget = (budgetBytes * HEAD_RATIO).toInt().coerceAtLeast(1)
        val tailBudget = (budgetBytes - headBudget).coerceAtLeast(1)
        val head = headByBytes(text, headBudget)
        val tail = tailByBytes(text, tailBudget)
        val headBytes = head.toByteArray(UTF8).size
        val tailBytes = tail.toByteArray(UTF8).size
        val omitted = text.toByteArray(UTF8).size - headBytes - tailBytes
        return "$head\n…[已省略约 $omitted 字节]…\n$tail"
    }

    // ── UTF-8 字节感知的头/尾截取（绝不切断增补平面代理对） ──

    private fun headByBytes(text: String, maxBytes: Int): String {
        var bytes = 0
        var idx = 0
        while (idx < text.length) {
            val cp = text.codePointAt(idx)
            val len = utf8Size(cp)
            if (bytes + len > maxBytes) break
            bytes += len
            idx += Character.charCount(cp)
        }
        return text.substring(0, idx)
    }

    private fun tailByBytes(text: String, maxBytes: Int): String {
        var bytes = 0
        var idx = text.length
        while (idx > 0) {
            val cp = text.codePointBefore(idx)
            val len = utf8Size(cp)
            if (bytes + len > maxBytes) break
            bytes += len
            idx -= Character.charCount(cp)
        }
        return text.substring(idx)
    }

    /** 单 code point 的 UTF-8 字节数（1/2/3/4）。 */
    private fun utf8Size(cp: Int): Int = when {
        cp < 0x80 -> 1
        cp < 0x800 -> 2
        cp < 0x10000 -> 3
        else -> 4
    }

    /** 文件名安全化：非 [A-Za-z0-9._-] 一律替换为下划线。 */
    private fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private val UTF8: Charset = Charsets.UTF_8
}
