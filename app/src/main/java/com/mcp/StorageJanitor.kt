package com.mcp

import android.content.Context
import java.io.File

/**
 * 应用私有目录的定期清扫。
 *
 * 背景：App 内有一批「按时间戳/随机 id 新建、从不回收」的落盘目录，
 * 长期使用后只增不减，是设备存储被无故占用（可达数十 GB）的主因：
 *  - browser/        ：截图、整页长图（每张可达数 MB）
 *  - browser/downloads：页面触发的下载
 *  - recordings/     ：语音输入录音（转写完即无用）
 *  - attachments/    ：聊天附件副本
 *  - ptc/            ：run_code 失败时保留的脚本目录
 *
 * 这些都不是用户的正式数据，属于会话/操作的过程产物，适合按「保留期」回收。
 * 保留期刻意给得宽松（默认 7 天），避免误删用户近期还在用的文件；
 * 删不掉（占用中/权限）一律忽略，不影响主流程。
 *
 * 注意：这里**不**碰 spill/（由 ToolResultSpill.clearSession 随会话删除清理）
 * 和 proot/（由 ProotEnvironment 管理），避免两处逻辑互相打架。
 */
object StorageJanitor {

    private const val TAG = "JANITOR"

    /** 默认保留期：7 天。 */
    private const val DEFAULT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

    /** 语音录音更短命：转写完成后即无价值，保留 3 天足够回看。 */
    private const val RECORDING_RETENTION_MS = 3L * 24 * 60 * 60 * 1000

    /**
     * 执行一次清扫。设计为幂等、可重复调用，建议 App 启动时在后台线程调用一次。
     *
     * @return 删除的文件数与释放的字节数（仅用于日志）。
     */
    fun sweep(context: Context): Pair<Int, Long> {
        var count = 0
        var bytes = 0L

        fun purge(dirName: String, retentionMs: Long) {
            val dir = File(context.filesDir, dirName)
            if (!dir.isDirectory) return
            val cutoff = System.currentTimeMillis() - retentionMs
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || f.lastModified() >= cutoff) return@forEach
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    count++
                    bytes += len
                }
            }
        }

        purge("browser", DEFAULT_RETENTION_MS)
        purge("browser/downloads", DEFAULT_RETENTION_MS)
        purge("recordings", RECORDING_RETENTION_MS)
        purge("attachments", DEFAULT_RETENTION_MS)

        // ptc/ 下每个 run 是子目录：整个目录够旧才删（失败保留的脚本便于排查）
        val ptcDir = File(context.filesDir, "ptc")
        if (ptcDir.isDirectory) {
            val cutoff = System.currentTimeMillis() - DEFAULT_RETENTION_MS
            ptcDir.listFiles()?.forEach { f ->
                if (!f.isDirectory || f.lastModified() >= cutoff) return@forEach
                val size = sizeOf(f)
                if (runCatching { f.deleteRecursively() }.getOrDefault(false)) {
                    count++
                    bytes += size
                }
            }
        }

        // 孤儿任务日志：App 刚启动时内存任务表必为空，此前进程留下的日志
        // 再也不会被任何逻辑认领（task/bg id 随机），只能靠这里按保留期回收。
        // 与「正在跑的任务」无冲突：进程刚起，尚无运行中任务。
        fun purgeLogs(dirName: String, retentionMs: Long) {
            val dir = File(context.filesDir, dirName)
            if (!dir.isDirectory) return
            val cutoff = System.currentTimeMillis() - retentionMs
            dir.listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(".log") || f.lastModified() >= cutoff) return@forEach
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    count++
                    bytes += len
                }
            }
        }

        purgeLogs("bash_tasks", DEFAULT_RETENTION_MS)
        purgeLogs("async_tasks", DEFAULT_RETENTION_MS)

        // 历史遗留：早期版本把 Gradle 构建日志直接散落在 filesDir 根目录
        // （gradle_build_<ts>.log）。新版本已改写到 gradle_logs/，这里回收旧的。
        val rootCutoff = System.currentTimeMillis() - 3_600_000L
        context.filesDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.startsWith("gradle_build_") &&
                f.name.endsWith(".log") && f.lastModified() < rootCutoff
            ) {
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) {
                    count++
                    bytes += len
                }
            }
        }
        // 新版构建日志目录同样按 1 小时回收
        purgeLogs("gradle_logs", 3_600_000L)

        if (count > 0) {
            LogStore.i(TAG, "启动清扫完成：删除 $count 个过期产物，释放 ${bytes / 1024} KB")
        }
        return count to bytes
    }

    private fun sizeOf(file: File): Long {
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::sizeOf) ?: 0L
    }
}