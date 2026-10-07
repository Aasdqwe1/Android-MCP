package com.mcp

import android.content.Context
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * x86_64 二进制转译层（QEMU user-mode）。
 *
 * 让 ARM64 的 Debian 执行环境能**透明**运行 x86_64 ELF：把 arm64 静态链接的
 * `qemu-x86_64` 注入 rootfs，注入 Debian amd64 运行库并开启 multiarch，再用 su 注册
 * 内核 `binfmt_misc`（fix-binary 标志）。
 *
 * 透明执行的实际生效范围（实测确认，别想当然）：
 * - binfmt_misc 是**宿主内核全局表**，注册成功只对「能看到该挂载点」的进程生效。
 * - PRoot guest 内 `/proc` 是白名单逐项 bind，**不含 binfmt_misc**，因此 guest 内
 *   的 `execve` 命不中 binfmt，`./x86prog` 仍报 Exec format error。guest 里跑 x86_64
 *   程序必须显式 `/usr/bin/qemu-x86_64 ./x86prog`（不自动改写用户命令，避免命令语义被黑魔法污染）。
 * - 由真 root（su）启动的进程、或宿主原生 chroot 后端，则能命中 binfmt 透明执行。
 *
 * 不改动既有的固定 rootfs 资产（SHA256 强校验）：qemu / 库 / multiarch 配置全部在
 * rootfs 解压之后按需注入，且以版本标记做幂等，重复初始化几乎零成本。
 *
 * 能力边界：
 * - qemu + amd64 运行库注入**不需要 root**（只是往 rootfs 写文件）。
 * - 透明执行（binfmt 自动托管）需要 **root**：binfmt_misc 注册是设备全局的。多数
 *   Android 设备已由 init 挂载 binfmt_misc；未挂载时需 root 才能 `mount -t`。
 * - 注册的 interpreter 必须是**本 App rootfs 里的 qemu**（绝对路径 <127 字节）。
 *   设备上预置的 `/usr/local/bin/qemu-x86_64` 常常指向不存在的文件——这是「条目在、
 *   却透明执行失败」的常见根因，因此注册前必须**校验并覆盖**坏条目，不能只判存在。
 * - 未 root / 注册失败时，仍可显式 `qemu-x86_64 <x86程序>` 运行。
 * - 离线仅打包了基础 amd64 库（libc / libstdc++ / libgcc / zlib 等）。需要更多库时，
 *   因已开启 multiarch，可在环境内 `apt-get install <lib>:amd64` 按需补齐（需联网）。
 */
object X86Translation {

    private const val TAG = "X86Translation"
    private const val VERSION = "x86_64-qemu-10.0.13"

    private const val QEMU_ASSET = "proot-runtime/qemu-x86_64"
    private const val LIBS_ASSET = "proot-runtime/x86_64-libs.tar.xz"
    private const val SMOKE_ASSET = "proot-runtime/x86_64-smoketest"
    private const val MARKER = ".x86_translation_version"
    private const val QEMU_BIN = "usr/bin/qemu-x86_64"
    private const val SMOKE_BIN = "usr/local/bin/x86_64-smoketest"

    // x86_64 ELF 的 binfmt_misc 匹配串（取自 Debian qemu 包官方 binfmt.d 配置）。
    private const val MAGIC =
        "\\x7f\\x45\\x4c\\x46\\x02\\x01\\x01\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x00\\x02\\x00\\x3e\\x00"
    private const val MASK =
        "\\xff\\xff\\xff\\xff\\xff\\xfe\\xfe\\xfc\\xff\\xff\\xff\\xff\\xff\\xff\\xff\\xff\\xfe\\xff\\xff\\xff"
    private const val BINFMT_NAME = "qemu-x86_64"

    /** 最近一次自检输出（供 UI / 调试查看），非持久。 */
    @Volatile
    var lastVerifyOutput: String? = null
        private set

    // ── 幂等判定 ──

    fun isProvisioned(context: Context): Boolean {
        val root = ProotEnvironment.rootfsDir(context)
        val marker = File(root, MARKER)
        if (!marker.isFile || marker.readText().trim() != VERSION) return false
        return File(root, QEMU_BIN).isFile
    }

    // ── 注入 qemu + amd64 运行库 + multiarch（不需要 root）──

    fun provision(context: Context): Boolean {
        val root = ProotEnvironment.rootfsDir(context)
        if (!root.isDirectory) {
            Log.w(TAG, "rootfs 未就绪，跳过 x86 转译预置")
            return false
        }
        if (isProvisioned(context)) {
            Log.i(TAG, "x86 转译已预置（版本匹配），跳过")
            return true
        }
        return runCatching {
            // 1. qemu 静态二进制
            val qemu = File(root, QEMU_BIN).apply { parentFile?.mkdirs() }
            context.assets.open(QEMU_ASSET).use { it.copyTo(qemu.outputStream()) }
            qemu.setExecutable(true, false)

            // 2. amd64 运行库
            context.assets.open(LIBS_ASSET).use { extractTarXz(it, root) }

            // 3. 端到端自检程序（x86_64 静态）
            val smoke = File(root, SMOKE_BIN).apply { parentFile?.mkdirs() }
            context.assets.open(SMOKE_ASSET).use { it.copyTo(smoke.outputStream()) }
            smoke.setExecutable(true, false)

            // 4. multiarch 配置（开启后可在环境内 apt 装更多 amd64 库）
            enableMultiarch(root)

            // 5. 版本标记（幂等）
            File(root, MARKER).writeText(VERSION)
            Log.i(TAG, "x86 转译预置完成")
            true
        }.getOrElse { e ->
            Log.e(TAG, "x86 转译预置失败", e)
            false
        }
    }

    private fun enableMultiarch(root: File) {
        runCatching {
            val archFile = File(root, "var/lib/dpkg/arch")
            val lines = if (archFile.isFile) archFile.readLines().toMutableList() else mutableListOf("arm64")
            if (!lines.contains("amd64")) {
                lines.add("amd64")
                archFile.writeText(lines.joinToString("\n") + "\n")
            }
            val confDir = File(root, "etc/dpkg/dpkg.conf.d").apply { mkdirs() }
            File(confDir, "multiarch").writeText("foreign-architecture amd64\n")
            val ldConf = File(root, "etc/ld.so.conf.d/x86_64-linux-gnu.conf").apply { parentFile?.mkdirs() }
            ldConf.writeText("/usr/lib/x86_64-linux-gnu\n/lib/x86_64-linux-gnu\n")
        }.onFailure { Log.w(TAG, "multiarch 配置写入失败（不影响基础转译）: ${it.message}") }
    }

    // ── 注册 binfmt_misc（需要 root，best-effort）──

    fun registerBinfmt(context: Context): Boolean {
        if (!HostSu.verifySu()) {
            Log.w(TAG, "未获取 root，跳过 binfmt 注册（透明执行不可用，仍可手动 qemu-x86_64 运行）")
            return false
        }
        val interp = File(ProotEnvironment.rootfsDir(context), QEMU_BIN).absolutePath
        if (!File(interp).isFile) {
            Log.e(TAG, "binfmt interpreter 不存在: $interp")
            return false
        }
        if (interp.length > 127) {
            Log.e(TAG, "binfmt interpreter 路径超长(${interp.length})，注册中止")
            return false
        }
        // 已有条目是否**已经指向本 App 的 qemu**？是则无需动它。
        //
        // 旧实现只判 `[ ! -e ... ]`，于是设备上残留的坏条目（interpreter 指向
        // `/usr/local/bin/qemu-x86_64` 之类不存在的路径）会让它直接走 else 分支
        // 打印 already，永远不修——透明执行表面 enabled、实际每次 execve 都失败。
        // 这里改为读回条目校验 interp，不匹配就注销后重注册。
        if (binfmtEntryUsable(context)) {
            Log.i(TAG, "binfmt 条目已指向本 App qemu，跳过")
            return true
        }
        // entry 用 base64 传输：raw 形态含大量 `\xNN` 字节串，经 ProcessBuilder →
        // su → sh → printf 多层转义极易错位（实测 mask 会被二次解释成 ffffffffff fefefc …）。
        // base64 只含 [A-Za-z0-9+/=]，任何一层都不会改写，落地后再解码写 register。
        val entry = ":$BINFMT_NAME:M::$MAGIC:$MASK:$interp:OCF"
        val entryB64 = base64(entry)
        val script = """
            mount -t binfmt_misc binfmt_misc /proc/sys/fs/binfmt_misc 2>/dev/null
            REG=/proc/sys/fs/binfmt_misc/register
            ENT=/proc/sys/fs/binfmt_misc/$BINFMT_NAME
            if [ ! -w "${'$'}REG" ]; then echo "no-register:${'$'}REG"; exit 2; fi
            # 注销已存在的条目（无论好坏），避免 register 报 File exists
            if [ -e "${'$'}ENT" ]; then echo -1 > "${'$'}ENT" 2>/dev/null; fi
            echo '$entryB64' | base64 -d > ${'$'}REG
            echo "rc=${'$'}?"
            cat ${'$'}ENT 2>/dev/null
        """.trimIndent()
        val (rc, out) = runAsRootScript(context, script)
        // 判定标准：注册命令返回 0，且条目里 interpreter 确为本 App 的 qemu
        val registered = out.contains("interpreter $interp")
        val ok = rc == 0 && registered
        Log.i(TAG, "binfmt 注册: ok=$ok rc=$rc out=${out.take(160)}")
        return ok
    }

    /** 标准 base64 编码（无换行）。用于把含二进制转义串的 binfmt entry 安全穿过 shell。 */
    private fun base64(s: String): String {
        val b64 = java.util.Base64.getEncoder().encode(s.toByteArray(Charsets.UTF_8))
        return String(b64, Charsets.US_ASCII)
    }

    /** provision + （已 root 时）registerBinfmt。返回 provision 是否成功。 */
    fun ensureReady(context: Context): Boolean {
        val ok = provision(context)
        if (HostSu.verifySu()) {
            runCatching { registerBinfmt(context) }
                .onFailure { Log.w(TAG, "binfmt 注册失败: ${it.message}") }
        }
        return ok
    }

    // ── 自检：用 qemu 跑 x86_64 程序，验证转译链路 ──

    /**
     * 在 Debian 环境内执行内置的 x86_64 自检程序。
     * - 已注册 binfmt：直接以程序路径执行（走透明转译）。
     * - 未注册：显式用 qemu-x86_64 运行（验证 qemu + amd64 库）。
     * 返回是否成功，并缓存输出到 [lastVerifyOutput]。
     */
    fun verify(context: Context): Boolean {
        if (!isProvisioned(context)) return false
        val transparent = binfmtEntryUsable(context)
        val cmd = if (transparent) "/usr/local/bin/x86_64-smoketest" else "/usr/bin/qemu-x86_64 /usr/local/bin/x86_64-smoketest"
        return runCatching {
            val r = ProotEnvironment.execute(context, cmd, timeoutSeconds = 30)
            lastVerifyOutput = r.output
            val ok = r.exitCode == 0 && r.output.contains("x86_64-translation-ok")
            Log.i(TAG, "x86 转译自检: ok=$ok exit=${r.exitCode} out=${r.output.take(80)}")
            ok
        }.getOrElse { e ->
            lastVerifyOutput = "verify error: ${e.message}"
            Log.w(TAG, "x86 转译自检异常: ${e.message}")
            false
        }
    }

    // ── 状态 ──

    fun binfmtEntryExists(): Boolean =
        runCatching { File("/proc/sys/fs/binfmt_misc/$BINFMT_NAME").exists() }.getOrDefault(false)

    /** 读取当前 binfmt 条目声明的 interpreter 路径；无条目/读不到返回 null。 */
    fun binfmtEntryInterp(): String? = runCatching {
        val f = File("/proc/sys/fs/binfmt_misc/$BINFMT_NAME")
        if (!f.isFile) return@runCatching null
        f.readLines().firstOrNull { it.startsWith("interpreter ") }?.removePrefix("interpreter ")?.trim()
    }.getOrNull()

    /**
     * 条目是否**真正可用**：不仅存在，而且 interpreter 指向本 App rootfs 里的 qemu
     * 且该文件存在。
     *
     * 为什么不能只判 exists()：设备上常有残留条目指向 `/usr/local/bin/qemu-x86_64`
     * 之类并不存在的路径，内核照表 exec 会失败，表现为「条目 enabled 却透明执行报错」。
     */
    fun binfmtEntryUsable(context: Context): Boolean {
        if (!binfmtEntryExists()) return false
        val interp = binfmtEntryInterp() ?: return false
        val expected = File(ProotEnvironment.rootfsDir(context), QEMU_BIN).absolutePath
        return interp == expected && File(interp).isFile
    }

    fun status(context: Context): String {
        val root = ProotEnvironment.rootfsDir(context)
        val provisioned = isProvisioned(context)
        val entryExists = binfmtEntryExists()
        val interp = binfmtEntryInterp()
        val usable = binfmtEntryUsable(context)
        val rootAvailable = HostSu.verifySu()
        // 诚实汇报：把「条目在不在」「interpreter 对不对」「是否可用」「为何不可用」
        // 分开表达。旧实现只有一个 binfmt 布尔，UI 只能显示一个叉，用户无法区分
        // 「没 root」「条目指向坏路径」「挂载点缺失」三种完全不同的故障。
        val reason: String? = when {
            usable -> null
            !entryExists && !rootAvailable -> "no-root"
            !entryExists && rootAvailable -> "not-registered"
            entryExists && interp == null -> "entry-unreadable"
            entryExists && !File(interp ?: "").isFile -> "interp-missing:$interp"
            entryExists -> "interp-mismatch:$interp"
            else -> "unknown"
        }
        return buildJsonObject {
            put("provisioned", provisioned)
            put("qemu_present", File(root, QEMU_BIN).isFile)
            put("binfmt_registered", entryExists)
            put("binfmt_usable", usable)
            put("binfmt_interp", interp?.let(::JsonPrimitive) ?: JsonNull)
            // 透明执行在本机是否真的可用（条目指向本 App qemu 且文件存在）
            put("transparent_execution", usable)
            put("transparent_unavailable_reason", reason?.let(::JsonPrimitive) ?: JsonNull)
            put("needs_root_for_transparent", !usable)
            // guest（PRoot 内）永远看不到 binfmt_misc：/proc 白名单不含它，
            // 因此 guest 内透明执行恒为 false，需显式 qemu-x86_64 前缀。
            put("transparent_inside_pr_root_guest", false)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("version", VERSION)
            put("last_verify", lastVerifyOutput?.let(::JsonPrimitive) ?: JsonNull)
        }.toString()
    }

    // ── 内部：以 root 身份执行脚本（stdin 模式，复用 HostSu 探测）──

    private fun runAsRootScript(context: Context, script: String): Pair<Int, String> {
        if (!HostSu.verifySu()) return -1 to "no-root"
        val su = HostSu.findSu() ?: "su"
        val ns = HostSu.rootNamespaceArgs()
        val pb = ProcessBuilder(listOf(su) + ns)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
        pb.environment().apply {
            remove("LD_LIBRARY_PATH"); remove("PREFIX"); remove("TERMUX_VERSION")
            remove("LD_PRELOAD"); remove("PROOT_LOADER"); remove("PROOT_TMP_DIR")
        }
        val process = pb.start()
        runCatching {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { w ->
                w.write(script); w.newLine(); w.flush()
            }
        }.onFailure { Log.w(TAG, "写入 root 命令失败: ${it.message}") }
        val out = StringBuilder()
        val buf = ByteArray(16 * 1024)
        var read: Int
        val deadline = System.currentTimeMillis() + 30_000L
        while (process.isAlive && System.currentTimeMillis() < deadline) {
            try {
                if (process.inputStream.available() > 0) {
                    read = process.inputStream.read(buf)
                    if (read > 0) out.append(String(buf, 0, read, Charsets.UTF_8))
                } else {
                    Thread.sleep(50)
                }
            } catch (_: Exception) {
                break
            }
        }
        if (process.isAlive) process.destroyForcibly()
        val rc = if (process.isAlive) -1 else runCatching { process.exitValue() }.getOrDefault(-1)
        return rc to out.toString()
    }

    // ── 内部：极简 USTAR tar.xz 解包（仅文件/目录/符号链接，无 PAX 扩展头）──

    private fun extractTarXz(input: InputStream, dest: File) {
        XZInputStream(BufferedInputStream(input, 64 * 1024)).use { xz ->
            val block = ByteArray(512)
            while (readBlock(xz, block)) {
                if (block.all { it.toInt() == 0 }) break
                val name = field(block, 0, 100)
                val prefix = field(block, 345, 155)
                val path = if (prefix.isBlank()) name else "$prefix/$name"
                if (path.isEmpty()) { skip(xz, octal(block, 124, 12)); continue }
                val size = octal(block, 124, 12)
                val mode = octal(block, 100, 8).toInt()
                val type = block[156].toInt().and(0xff).toChar()
                val link = field(block, 157, 100)
                val target = File(dest, path)
                when (type) {
                    '5' -> { target.mkdirs(); chmod(target, mode); skip(xz, size) }
                    '2' -> {
                        target.parentFile?.mkdirs(); target.deleteRecursively()
                        runCatching { Os.symlink(link, target.absolutePath) }
                            .onFailure { Log.w(TAG, "symlink 失败: $path -> $link") }
                        skip(xz, size)
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs(); target.deleteRecursively()
                        FileOutputStream(target).use { copyN(xz, it, size) }
                        chmod(target, mode); skipPadding(xz, size)
                    }
                    else -> skip(xz, size)
                }
            }
        }
    }

    private fun readBlock(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return off == 0
            if (n == 0) continue
            off += n
        }
        return true
    }

    private fun field(buf: ByteArray, off: Int, len: Int): String {
        val end = (off + len).coerceAtMost(buf.size)
        var stop = off
        while (stop < end && buf[stop].toInt() != 0) stop++
        return String(buf, off, stop - off, Charsets.US_ASCII).trim()
    }

    private fun octal(buf: ByteArray, off: Int, len: Int): Long {
        val s = field(buf, off, len)
        if (s.isEmpty()) return 0L
        return s.toLongOrNull(8) ?: 0L
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        val b = ByteArray(8192)
        while (left > 0) {
            val r = input.read(b, 0, minOf(b.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    private fun copyN(input: InputStream, out: FileOutputStream, n: Long) {
        var left = n
        val b = ByteArray(8192)
        while (left > 0) {
            val r = input.read(b, 0, minOf(b.size.toLong(), left).toInt())
            if (r < 0) break
            out.write(b, 0, r)
            left -= r
        }
    }

    private fun skipPadding(input: InputStream, n: Long) {
        val pad = (512 - (n % 512).toInt()) % 512
        if (pad > 0) skip(input, pad.toLong())
    }

    private fun chmod(file: File, mode: Int) {
        runCatching { Os.chmod(file.absolutePath, mode) }.onFailure {
            // 回退到 Java 层权限位，至少保证可读可执行
            file.setReadable(true, false)
            file.setExecutable((mode and 0b1001001) != 0, false)
        }
    }
}
