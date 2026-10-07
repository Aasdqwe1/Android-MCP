package com.mcp

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 文件锁管理器。
 *
 * 为每个文件维护一个读写锁，确保并发场景下文件操作的一致性。
 * - 读锁（共享）：多个工具可同时读取同一文件
 * - 写锁（独占）：修改文件时独占锁，防止并发写入导致数据损坏
 *
 * 使用方式：
 * ```
 * FileLockManager.withReadLock("/path/to/file.kt") {
 *     // 读取文件内容
 * }
 *
 * FileLockManager.withWriteLock("/path/to/file.kt") {
 *     // 修改文件内容
 * }
 * ```
 *
 * 注意：锁基于文件路径（规范路径）管理，同一路径的不同表示（如 /a/b 和 /a/../b）会归一化。
 */
object FileLockManager {
    private val locks = ConcurrentHashMap<String, ReentrantReadWriteLock>()

    /**
     * 获取文件的规范化路径（用于锁键）。
     */
    private fun normalizePath(path: String): String =
        runCatching { File(path).canonicalPath }.getOrElse { File(path).absolutePath }

    /**
     * 获取或创建指定文件的读写锁。
     */
    private fun getLock(path: String): ReentrantReadWriteLock =
        locks.computeIfAbsent(normalizePath(path)) { ReentrantReadWriteLock() }

    /**
     * 在读锁保护下执行操作（共享锁）。
     *
     * 多个协程/线程可同时读取同一文件。
     *
     * @param path 文件路径
     * @param block 读取操作
     * @return block 的返回值
     */
    fun <T> withReadLock(path: String, block: () -> T): T {
        val lock = getLock(path)
        return lock.readLock().lock().let {
            try {
                block()
            } finally {
                lock.readLock().unlock()
            }
        }
    }

    /**
     * 在写锁保护下执行操作（独占锁）。
     *
     * 同一时间只有一个协程/线程可修改指定文件。
     *
     * @param path 文件路径
     * @param block 写入操作
     * @return block 的返回值
     */
    fun <T> withWriteLock(path: String, block: () -> T): T {
        val lock = getLock(path)
        return lock.writeLock().lock().let {
            try {
                block()
            } finally {
                lock.writeLock().unlock()
            }
        }
    }

    /**
     * 尝试获取写锁，如果获取失败则返回 null。
     *
     * @param path 文件路径
     * @param timeoutMs 超时时间（毫秒），默认 0 表示不等待
     * @return 锁的持有者（需在 finally 中释放），若超时则返回 null
     */
    fun tryWriteLock(path: String, timeoutMs: Long = 0): AutoCloseable? {
        val lock = getLock(path)
        val acquired = if (timeoutMs > 0) {
            lock.writeLock().tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } else {
            lock.writeLock().tryLock()
        }
        return if (acquired) {
            AutoCloseable { lock.writeLock().unlock() }
        } else {
            null
        }
    }

    /**
     * 尝试获取读锁，如果获取失败则返回 null。
     */
    fun tryReadLock(path: String, timeoutMs: Long = 0): AutoCloseable? {
        val lock = getLock(path)
        val acquired = if (timeoutMs > 0) {
            lock.readLock().tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } else {
            lock.readLock().tryLock()
        }
        return if (acquired) {
            AutoCloseable { lock.readLock().unlock() }
        } else {
            null
        }
    }

    /**
     * 移除指定文件的锁（当不再需要时释放内存）。
     *
     * 注意：仅在确定没有持有者时调用，否则可能导致未定义行为。
     */
    fun removeLock(path: String) {
        locks.remove(normalizePath(path))
    }

    /**
     * 获取当前管理的文件锁数量（用于调试）。
     */
    fun lockCount(): Int = locks.size

    /**
     * 清理所有锁（用于测试或重置）。
     */
    fun clearAll() {
        locks.clear()
    }

    // ─────────────────────────────────────────────────────────────
    //  便捷方法：带锁的文件读写
    // ─────────────────────────────────────────────────────────────

    /**
     * 在读锁保护下读取文件内容。
     * @return 文件内容，如果文件不存在则返回 null
     */
    fun readFileSafe(path: String): String? {
        val file = File(path)
        if (!file.exists() || file.isDirectory) return null
        return withReadLock(path) {
            file.readText(Charsets.UTF_8)
        }
    }

    /**
     * 在读锁保护下读取文件行列表。
     */
    fun readLinesSafe(path: String): List<String>? {
        val file = File(path)
        if (!file.exists() || file.isDirectory) return null
        return withReadLock(path) {
            file.readLines(Charsets.UTF_8)
        }
    }

    /**
     * 在写锁保护下写入文件。
     * @param content 要写入的内容
     * @param append 是否追加模式
     * @return 是否成功
     */
    fun writeFileSafe(path: String, content: String, append: Boolean = false): Boolean {
        val file = File(path)
        return withWriteLock(path) {
            file.parentFile?.mkdirs()
            if (append) {
                file.appendText(content, Charsets.UTF_8)
            } else {
                file.writeText(content, Charsets.UTF_8)
            }
            true
        }
    }

    /**
     * 在写锁保护下删除文件。
     */
    fun deleteFileSafe(path: String): Boolean {
        val file = File(path)
        return withWriteLock(path) {
            file.delete()
        }
    }
}
