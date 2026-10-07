package com.mcp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 能力接缝（Capability Seam）—— 借鉴 DeepSeek Harness「接口 / Provider / Consumer」三位一体。
 *
 * 每个能力拆成三个角色：
 * - Service Definition（接口）：[FsProvider] / [ShellProvider] / [SubprocessProvider]。
 * - Service Provider（实现）：本地 SAF / PRoot（当前），未来可加远程沙箱（e2b）等。
 * - Consumer（使用方）：`FileTools` / `runBash` / `run_bash_bg` 等工具只依赖接口，不感知具体后端。
 *
 * 消费方统一经 [CapabilityRegistry] 获取当前 Provider，切换执行世界（本地 / PRoot / 远程沙箱）
 * 只需替换注册表中的实现，一套 file/bash 工具即全换。
 */

// ─────────────────────────────────────────────
//  文件系统接缝（FsProvider）
// ─────────────────────────────────────────────

/** 文件访问目标：直接 File 或 SAF DocumentFile；由 [FsProvider.resolveTarget] 产出。 */
sealed class FileTarget {
    class FileRef(val file: File) : FileTarget()
    class SafRef(val doc: DocumentFile, val displayPath: String) : FileTarget()
    class None(val file: File) : FileTarget()
}

/** 文件系统能力接口。file 工具族只依赖本接口，Provider 可换。 */
interface FsProvider {
    val id: String

    /** 将路径解析为访问目标（含敏感文件拒绝、私有路径、权限与 SAF 判定）。返回 (目标, 错误)。 */
    fun resolveTarget(ctx: Context, path: String): Pair<FileTarget, String?>
}

/** 敏感凭据文件名单（对齐官方 confine.go deny 名单，防凭据泄露/误改）。 */
private val SENSITIVE_FILE_NAMES = listOf(
    ".env", ".git-credentials", ".netrc", ".npmrc", ".pypirc",
    "id_rsa", "id_ed25519", "id_dsa", "id_ecdsa",
    "local.properties", "keystore", "debug.keystore", "secrets.properties", "signing.properties"
)
private val SENSITIVE_EXTENSIONS = listOf(".pem", ".key", ".jks", ".bks", ".p12", ".pfx", ".keystore")

/**
 * 敏感路径检查：命中凭据文件名单返回面向模型的错误 JSON，否则 null。
 * 读与写统一拒绝（防模型把凭据文件内容回给用户，或被误改），错误文案指明原因。
 */
private fun sensitiveDeny(path: String): String? {
    val norm = path.replace('\\', '/').trimEnd('/')
    val fileName = norm.substringAfterLast('/')
    val matched = SENSITIVE_FILE_NAMES.any { fileName == it } ||
        SENSITIVE_EXTENSIONS.any { fileName.endsWith(it) }
    if (!matched) return null
    return """{"error":"路径指向敏感凭据文件（$fileName），为避免凭据泄露或误改已被拒绝。如需处理请用户手动操作，或改用 SAF 授权目录。"}"""
}

/** 绝对路径直接使用；相对路径解析为 filesDir 下的路径。 */
private fun resolvePath(ctx: Context, path: String): File =
    if (path.startsWith("/")) File(path) else File(ctx.filesDir, path)

/** 检查是否为应用私有路径（不需要存储权限）。 */
private fun isAppPrivate(ctx: Context, file: File): Boolean {
    val canonical = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
    val roots = listOfNotNull(ctx.dataDir, ctx.filesDir, ctx.cacheDir, ctx.getExternalFilesDir(null))
        .map { dir -> runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath } }
        .distinct()
    return roots.any { prefix -> canonical.startsWith(prefix) }
}

/**
 * 检查外部存储权限。
 * @return null 表示有权限；非 null 返回错误提示字符串。
 */
private fun checkStoragePerm(ctx: Context, file: File): String? {
    if (isAppPrivate(ctx, file)) return null

    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.MANAGE_EXTERNAL_STORAGE) ==
        PackageManager.PERMISSION_GRANTED) {
        return null
    }

    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_IMAGES) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) null else
                "需要存储权限（Android 13+）。请进入 系统设置 > 应用 > MCP Agent > 权限 > 文件与媒体 授权，或使用安全访问（saf_open_directory）或应用内部路径（${ctx.filesDir}）"
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
            val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) null else
                "需要 READ_EXTERNAL_STORAGE 权限。请进入 系统设置 > 应用 > MCP Agent > 权限 > 存储 授权，或使用安全访问（saf_open_directory）或应用内部路径（${ctx.filesDir}）"
        }
        else -> null
    }
}

/**
 * 本地文件系统 Provider：复用现状的 File / SAF 双重后端。
 *
 * 智能获取文件访问目标，优先级：
 * 1. 应用私有目录 → 直接 File 访问（无需权限）
 * 2. 公共路径 + 有权限 → File（优先于 SAF）
 * 3. 无权限但 SAF 已注册 → DocumentFile（安全访问，无需权限）
 */
object LocalFsProvider : FsProvider {
    override val id = "local"

    override fun resolveTarget(ctx: Context, path: String): Pair<FileTarget, String?> {
        val sensitiveErr = sensitiveDeny(path)
        if (sensitiveErr != null) return FileTarget.None(File(path)) to sensitiveErr

        val file = resolvePath(ctx, path)

        // 1. 应用私有目录 → 直接 File
        if (isAppPrivate(ctx, file)) {
            val canon = runCatching { file.canonicalPath }.getOrNull()
            if (canon != null && canon != file.absolutePath) {
                val canonErr = sensitiveDeny(canon)
                if (canonErr != null) return FileTarget.None(file) to canonErr
            }
            return FileTarget.FileRef(file) to null
        }

        // 2. 公共路径 → 先检查权限
        val permErr = checkStoragePerm(ctx, file)
        if (permErr == null) {
            val canonPub = runCatching { file.canonicalPath }.getOrNull()
            if (canonPub != null && canonPub != file.absolutePath) {
                val canonErr = sensitiveDeny(canonPub)
                if (canonErr != null) return FileTarget.None(file) to canonErr
            }
            return FileTarget.FileRef(file) to null
        }

        // 3. 无权限，尝试 SAF 作为备选方案
        val safDoc = SafManager.resolveToDocumentFile(file.absolutePath)
        if (safDoc != null) return FileTarget.SafRef(safDoc, file.absolutePath) to null

        return FileTarget.None(file) to permErr
    }
}

// ─────────────────────────────────────────────
//  Shell 接缝（ShellProvider）
// ─────────────────────────────────────────────

/** 一次 shell 执行的结果。 */
data class ShellResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false
)

/** Shell 执行能力接口（对应 run_bash / proot_status）。 */
interface ShellProvider {
    val id: String
    val lastError: String?
    fun isReady(ctx: Context): Boolean
    suspend fun ensureInitialized(ctx: Context, onProgress: (String) -> Unit = {}): Boolean
    fun execute(
        ctx: Context,
        script: String,
        timeoutSeconds: Long = 300L,
        onLine: ((String) -> Unit)? = null
    ): ShellResult
    fun status(ctx: Context): String
}

/** PRoot Debian Linux Provider（当前唯一实现）。 */
object ProotShellProvider : ShellProvider {
    override val id = "proot"
    override val lastError: String? get() = ProotEnvironment.lastError
    override fun isReady(ctx: Context): Boolean = ProotEnvironment.isReady(ctx)
    override suspend fun ensureInitialized(ctx: Context, onProgress: (String) -> Unit): Boolean =
        ProotEnvironment.ensureInitialized(ctx, onProgress)
    override fun execute(
        ctx: Context,
        script: String,
        timeoutSeconds: Long,
        onLine: ((String) -> Unit)?
    ): ShellResult {
        val result = ProotEnvironment.execute(ctx, script, timeoutSeconds, onLine)
        return ShellResult(result.exitCode, result.output, result.timedOut, result.truncated)
    }
    override fun status(ctx: Context): String = ProotEnvironment.status(ctx)
}

// ─────────────────────────────────────────────
//  子进程接缝（SubprocessProvider）
// ─────────────────────────────────────────────

/** 后台子进程能力接口（对应 run_bash_bg / async_task / bash_task_*）。 */
interface SubprocessProvider {
    val id: String

    /** 后台启动常驻进程，stdout/stderr 追加到 [logFile]；调用方持有返回的 Process 并负责销毁。 */
    fun start(ctx: Context, script: String, logFile: File): Process
}

/** PRoot 常驻进程 Provider（root 模式关闭 / 无 root 时的实现）。 */
object ProotSubprocessProvider : SubprocessProvider {
    override val id = "proot"
    override fun start(ctx: Context, script: String, logFile: File): Process =
        ProotEnvironment.startDetached(ctx, script, logFile)
}

/** 原生 chroot Debian Linux Provider（root 模式开启时的零损耗后端）。 */
object NativeChrootShellProvider : ShellProvider {
    override val id = "native-chroot"
    override val lastError: String? get() = NativeChrootEnvironment.lastError
    override fun isReady(ctx: Context): Boolean = NativeChrootEnvironment.isReady(ctx)
    override suspend fun ensureInitialized(ctx: Context, onProgress: (String) -> Unit): Boolean =
        NativeChrootEnvironment.ensureInitialized(ctx, onProgress)
    override fun execute(
        ctx: Context,
        script: String,
        timeoutSeconds: Long,
        onLine: ((String) -> Unit)?
    ): ShellResult {
        val result = NativeChrootEnvironment.execute(ctx, script, timeoutSeconds, onLine)
        return ShellResult(result.exitCode, result.output, result.timedOut, result.truncated)
    }
    override fun status(ctx: Context): String = NativeChrootEnvironment.status(ctx)
}

/** 原生 chroot 常驻进程 Provider（root 模式开启时的零损耗后端）。 */
object NativeChrootSubprocessProvider : SubprocessProvider {
    override val id = "native-chroot"
    override fun start(ctx: Context, script: String, logFile: File): Process =
        NativeChrootEnvironment.startDetached(ctx, script, logFile)
}

// ─────────────────────────────────────────────
//  能力注册表（组合根）
// ─────────────────────────────────────────────

/**
 * 能力接缝的运行时注册表：消费方从这获取当前 Provider。
 * 切换执行世界只需替换这些字段（本地 SAF / PRoot / 远程沙箱 e2b）。
 */
object CapabilityRegistry {
    @Volatile
    var fs: FsProvider = LocalFsProvider

    @Volatile
    var shell: ShellProvider = ProotShellProvider

    @Volatile
    var subprocess: SubprocessProvider = ProotSubprocessProvider

    // 按 root 状态选定的「默认」后端，供退出持久模式时切回（无需 Context）。
    private var chosenShell: ShellProvider = ProotShellProvider
    private var chosenSubprocess: SubprocessProvider = ProotSubprocessProvider

    /**
     * 按 root 模式 + su 可用性挑选 shell/subprocess 后端。
     * root 模式且 su 可拿到 uid=0 → 原生 chroot（零损耗）；否则退回 PRoot。
     * 幂等且廉价（[HostSu.verifySu] 有失败缓存，不会反复弹授权窗）。
     */
    fun selectBackend(ctx: Context) {
        val native = HostSu.isRootModeEnabled(ctx) && HostSu.verifySu()
        chosenShell = if (native) NativeChrootShellProvider else ProotShellProvider
        chosenSubprocess = if (native) NativeChrootSubprocessProvider else ProotSubprocessProvider
        shell = chosenShell
        subprocess = chosenSubprocess
    }

    /** 退出持久模式时，把 shell 接缝切回当前选定的（proot / 原生）后端。 */
    fun resetShellToSelected() {
        shell = chosenShell
    }
}