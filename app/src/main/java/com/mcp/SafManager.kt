package com.mcp

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/**
 * SAF（Storage Access Framework）管理器。
 *
 * 允许用户通过系统文件选择器授权访问任意目录，不依赖存储权限。
 * 授权后的 URI 会持久化，跨进程/重启后仍可用。
 *
 * 核心流程：
 * 1. 用户调用 saf_open_directory 工具 → 启动系统目录选择器
 * 2. 用户选择目录 → 回调返回 tree URI
 * 3. 持久化 URI + 路径映射 → 后续文件操作自动走 SAF
 */
object SafManager {

    private const val PREF_NAME = "saf_roots"
    private const val KEY_ROOT_COUNT = "root_count"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    /** 挂起的 SAF 选择器请求（用于 ChatBridge 工具调用链）。 */
    private var pendingDeferred: CompletableDeferred<Uri?>? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    // ─────────────────────────────────────────────
    //  URI 持久化
    // ─────────────────────────────────────────────

    /** 保存一个 SAF 根目录映射：displayPath → treeUri。 */
    fun saveRoot(displayPath: String, uri: Uri) {
        val count = prefs.getInt(KEY_ROOT_COUNT, 0)
        prefs.edit()
            .putString("root_path_$count", displayPath)
            .putString("root_uri_$count", uri.toString())
            .putInt(KEY_ROOT_COUNT, count + 1)
            .apply()

        // 持久化权限（重启后仍有效）
        appContext.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        LogStore.i("SAF", "已保存根目录: $displayPath → $uri")
    }

    /** 获取所有已保存的 SAF 根目录映射。 */
    fun getAllRoots(): List<Pair<String, Uri>> {
        val count = prefs.getInt(KEY_ROOT_COUNT, 0)
        return (0 until count).mapNotNull { i ->
            val path = prefs.getString("root_path_$i", null) ?: return@mapNotNull null
            val uriStr = prefs.getString("root_uri_$i", null) ?: return@mapNotNull null
            path to Uri.parse(uriStr)
        }
    }

    /** 删除指定 SAF 根目录。 */
    fun removeRoot(displayPath: String): Boolean {
        val count = prefs.getInt(KEY_ROOT_COUNT, 0)
        var removed = false
        val edit = prefs.edit()
        var newIdx = 0
        for (i in 0 until count) {
            val path = prefs.getString("root_path_$i", null) ?: continue
            if (path == displayPath) {
                val uriStr = prefs.getString("root_uri_$i", null)
                if (uriStr != null) {
                    appContext.contentResolver.releasePersistableUriPermission(
                        Uri.parse(uriStr),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                edit.remove("root_path_$i").remove("root_uri_$i")
                removed = true
            } else {
                edit.putString("root_path_$newIdx", path)
                    .putString("root_uri_$newIdx", prefs.getString("root_uri_$i", null))
                newIdx++
            }
        }
        edit.putInt(KEY_ROOT_COUNT, newIdx).apply()
        return removed
    }

    // ─────────────────────────────────────────────
    //  路径 → SAF URI 解析
    // ─────────────────────────────────────────────

    /**
     * 尝试将文件系统路径解析为 SAF DocumentFile。
     * 遍历所有已注册的 SAF 根，找到最长前缀匹配。
     *
     * @return null 表示该路径未被 SAF 覆盖，应走直接文件访问。
     */
    fun resolveToDocumentFile(filePath: String): DocumentFile? {
        val canonical = runCatching { File(filePath).canonicalPath }.getOrElse { filePath }
        val roots = getAllRoots()
        // 按路径长度降序，优先匹配最具体的根
        val best = roots
            .filter { canonical.startsWith(it.first) }
            .maxByOrNull { it.first.length } ?: return null

        val rootFile = DocumentFile.fromTreeUri(appContext, best.second) ?: return null
        val relativePath = canonical.removePrefix(best.first).trimStart('/')

        if (relativePath.isEmpty()) return rootFile
        return navigateTo(rootFile, relativePath)
    }

    /** 从父 DocumentFile 按相对路径逐级导航到目标文件/目录。 */
    private fun navigateTo(parent: DocumentFile, relativePath: String): DocumentFile? {
        val parts = relativePath.split("/").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return parent

        var current = parent
        for (i in parts.indices) {
            val name = parts[i]
            val child = current.findFile(name)
            if (child != null) {
                current = child
            } else if (i == parts.lastIndex) {
                // 目标文件尚不存在，返回父目录 + 文件名信息
                return null // 由调用方处理
            } else {
                return null // 中间目录不存在
            }
        }
        return current
    }

    // ─────────────────────────────────────────────
    //  DocumentFile 操作（替代 java.io.File）
    // ─────────────────────────────────────────────

    /** 列出目录内容。 */
    fun listFiles(doc: DocumentFile): List<DocumentFile> =
        doc.listFiles().toList()

    /** 读取文件全文。 */
    fun readText(doc: DocumentFile): String {
        appContext.contentResolver.openInputStream(doc.uri)?.use { input ->
            return input.bufferedReader(Charsets.UTF_8).readText()
        } ?: throw FileNotFoundException("无法打开文件: ${doc.uri}")
    }

    /** 读取文件指定行。 */
    fun readLines(doc: DocumentFile, startLine: Int? = null, endLine: Int? = null): String {
        appContext.contentResolver.openInputStream(doc.uri)?.use { input ->
            val lines = input.bufferedReader(Charsets.UTF_8).readLines()
            val total = lines.size
            val s = (startLine ?: 1).coerceIn(1, maxOf(total, 1))
            val e = (endLine ?: total).coerceIn(s, total)
            return if (total == 0) "" else
                lines.subList(s - 1, e).mapIndexed { i, l -> "${s + i}: $l" }.joinToString("\n")
        } ?: throw FileNotFoundException("无法打开文件: ${doc.uri}")
    }

    /** 获取文件总行数。 */
    fun countLines(doc: DocumentFile): Int {
        appContext.contentResolver.openInputStream(doc.uri)?.use { input ->
            return input.bufferedReader(Charsets.UTF_8).readLines().size
        } ?: throw FileNotFoundException("无法打开文件: ${doc.uri}")
    }

    /** 覆盖写入文件（创建或覆盖）。 */
    fun writeText(doc: DocumentFile, content: String) {
        appContext.contentResolver.openOutputStream(doc.uri, "wt")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw FileNotFoundException("无法写入文件: ${doc.uri}")
    }

    /** 追加写入。 */
    fun appendText(doc: DocumentFile, content: String) {
        appContext.contentResolver.openOutputStream(doc.uri, "wa")?.use { output ->
            output.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw FileNotFoundException("无法写入文件: ${doc.uri}")
    }

    /** 获取文件大小（字节）。 */
    fun fileSize(doc: DocumentFile): Long = doc.length()

    /** 检查文件是否存在。 */
    fun fileExists(doc: DocumentFile): Boolean = doc.exists()

    /** 创建文件。 */
    fun createFile(parent: DocumentFile, mimeType: String, name: String): DocumentFile? =
        parent.createFile(mimeType, name)

    /** 创建目录。 */
    fun createDirectory(parent: DocumentFile, name: String): DocumentFile? =
        parent.createDirectory(name)

    /** 删除文件。 */
    fun deleteFile(doc: DocumentFile): Boolean = doc.delete()

    // ─────────────────────────────────────────────
    //  目录选择器
    // ─────────────────────────────────────────────

    /**
     * 启动 SAF 目录选择器，挂起等待用户选择结果。
     * 仅在 ChatBridge 工具调用链中使用。
     */
    suspend fun requestDirectoryAccess(): Uri? = withContext(Dispatchers.Main) {
        val deferred = CompletableDeferred<Uri?>()
        pendingDeferred = deferred
        // 通过回调通知 MainActivity 启动选择器
        onRequestPicker?.invoke()
        deferred.await().also { pendingDeferred = null }
    }

    /** 处理选择器返回结果。由 MainActivity 调用。 */
    fun handlePickerResult(uri: Uri?) {
        pendingDeferred?.complete(uri)
    }

    /** 由 MainActivity 注册的回调，用于启动 SAF 选择器。 */
    var onRequestPicker: (() -> Unit)? = null
}
