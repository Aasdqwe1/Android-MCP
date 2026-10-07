package com.mcp

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract

/**
 * 沙盒宿主挂载白名单。
 *
 * 边界设计：PRoot 沙盒默认与宿主**完全隔离**——guest 内看不到 /sdcard、/storage、
 * /system、/data 等任何宿主区域，只能访问自身 rootfs 与应用私有目录。
 * 若确有把某个宿主目录暴露给沙盒的需求，必须由用户经系统目录选择器**显式授权**，
 * 授权结果落在本白名单里，[com.mcp.ProotEnvironment] 启动时按名单逐条 bind。
 *
 * 与 [SafManager] 的区别：SafManager 面向 file 工具族（DocumentFile 通道，无需真实路径）；
 * 本对象面向 PRoot bind（必须是可 stat 的真实文件系统路径），所以用 tree URI 反解路径而非
 * 存 URI。两者互不影响，授权也各自独立。
 */
object SandboxMounts {

    private const val PREF_NAME = "sandbox_mounts"
    private const val KEY_COUNT = "count"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
        prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** 当前已授权的宿主目录（原始路径）。 */
    fun all(): List<String> {
        if (!::prefs.isInitialized) return emptyList()
        val count = prefs.getInt(KEY_COUNT, 0)
        return (0 until count).mapNotNull { prefs.getString("mount_$it", null) }
    }

    /** 授权一个宿主目录暴露给沙盒。重复授权幂等。 */
    fun allow(path: String) {
        val norm = normalize(path) ?: return
        val current = all().toMutableList()
        if (current.any { normalize(it) == norm }) return
        current.add(norm)
        write(current)
    }

    /** 撤销授权。返回是否确实移除。 */
    fun revoke(path: String): Boolean {
        val norm = normalize(path) ?: return false
        val current = all().toMutableList()
        val removed = current.removeAll { normalize(it) == norm }
        if (removed) write(current)
        return removed
    }

    private fun write(paths: List<String>) {
        val edit = prefs.edit().clear()
        paths.forEachIndexed { i, p -> edit.putString("mount_$i", p) }
        edit.putInt(KEY_COUNT, paths.size).apply()
    }

    private fun normalize(path: String): String? {
        val p = path.trim()
        if (p.isEmpty()) return null
        val trimmed = p.trimEnd('/')
        return if (trimmed.isEmpty()) "/" else trimmed
    }

    /**
     * 从 SAF tree URI 反解真实文件系统路径。
     *
     * tree URI 的 documentId 形如 `primary:Work`（主存储）或 `1A2B-3C4D:foo/bar`（SD 卡），
     * 冒号前是卷标识、后是卷内相对路径。primary 对应 /storage/emulated/0，
     * 其余卷对应 /storage/<卷ID>。反解失败（非标准 provider）返回 null。
     */
    fun resolveTreeUri(uri: Uri): String? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val idx = docId.indexOf(':')
        if (idx < 0) return@runCatching null
        val volume = docId.substring(0, idx)
        val rel = docId.substring(idx + 1).trimStart('/')
        val base = if (volume.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$volume"
        if (rel.isEmpty()) base else "$base/$rel"
    }.getOrNull()
}