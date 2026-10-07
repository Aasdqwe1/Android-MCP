package com.mcp.data.persistence

import java.io.File

/**
 * 会话日志的 JSONL 文件后端：每个会话一个 append-only 文件。
 *
 * 借鉴 deepseek-harness 的 `session-persistence-jsonl`：后端只实现
 * [PersistenceBackend] 的最小物理原语（读/覆写/追加/删除/时间戳），把缓冲、串行化、
 * 游标、物化、崩溃修复交由 [PersistenceCoordinator] 编排，二者之间只有文件字节往来。
 *
 * 文件路径演进为按会话分目录布局：`<filesDir>/Chat/<tag>/<sid>/<tag>_msg_<sid>.json`。
 * `tag` 是后端命名空间（ds/oa），`sid` 是会话 id；会话 id 带 `tag-` 前缀时目录名去掉该前缀。
 *
 * 注意：Android 单进程应用无 dsh 那种多进程并发写，因此无需 zstd 分帧、link/unlink 原子
 * 发布、目录 fsync 那套 POSIX 语义；「原子覆写」用「临时文件 + 同目录 rename」近似即可，
 * 足以覆盖应用崩溃时留半行（torn tail）的自愈场景。
 */
class JsonlPersistenceBackend(
    private val filesDir: File,
    private val tag: String,
) : PersistenceBackend {

    /** 会话目录：`<filesDir>/Chat/<tag>/<sid>`（会话 id 带 `tag-` 前缀时目录名去掉该前缀）。 */
    private fun sessionDir(sid: String): File =
        File(File(File(filesDir, "Chat"), tag), sid.removePrefix("$tag-"))

    private fun file(sid: String): File = File(sessionDir(sid), "${tag}_msg_$sid.json")

    override fun readRaw(sid: String): String? {
        val f = file(sid)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    override fun write(sid: String, text: String) {
        val f = file(sid)
        val dir = f.parentFile ?: return
        runCatching { dir.mkdirs() }
        val tmp = File(dir, "${f.name}.${System.nanoTime()}.tmp")
        runCatching {
            tmp.writeText(text)
            // 同目录 rename 覆盖既有文件（Android 内部存储保证同文件系统，rename 可靠）。
            if (!tmp.renameTo(f)) {
                f.writeText(text)
            }
        }.getOrElse { /* 写失败时尽力清理临时文件，不冒泡破坏上层游标判定 */ }
        if (tmp.exists()) runCatching { tmp.delete() }
    }

    override fun appendLine(sid: String, line: String) {
        runCatching {
            val f = file(sid)
            f.parentFile?.mkdirs()
            f.appendText(line + "\n")
        }
    }

    override fun delete(sid: String) {
        runCatching { file(sid).delete() }
    }

    override fun lastModified(sid: String): Double {
        val f = file(sid)
        return if (f.exists()) f.lastModified() / 1000.0 else 0.0
    }

    override fun length(sid: String): Long {
        val f = file(sid)
        return if (f.exists()) f.length() else 0L
    }
}