package com.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 原生 chroot 执行后端 —— PRoot 的零损耗替代。
 *
 * 设备已 root 且开启 root 模式时，用 su 以真 uid=0 启动 `chroot`，guest 直接跑在
 * Android Linux 内核上，彻底去掉 PRoot 的 ptrace 系统调用模拟 / seccomp / link2symlink
 * 开销。编译 / 构建 / 跑服务等 fork-exec 密集任务因此接近原生速度。
 *
 * rootfs 与 PRoot 完全相同（直接复用 [ProotEnvironment] 的安装与就绪判定）。
 *
 * 挂载策略逐项平移 PRoot 的 --bind 白名单（/dev、/proc 与 /sys 的只读白名单、
 * 应用私有目录、SandboxMounts 用户授权目录），差别在于这些是**真实 mount**，
 * 且全部在 su 子进程的**私有 mount namespace** 内完成——su 进程退出即自动回收，
 * 不会污染宿主全局挂载表（因此刻意**不**加 HostSu 的 -M 参数）。
 */
object NativeChrootEnvironment {

    private const val TAG = "NativeChroot"
    private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    private const val COMMAND_TIMEOUT_SECONDS = 300L
    private const val WAIT_SLICE_MS = 200L
    private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
    private val PACKAGE_MUTATION = Regex(
        """(?m)(^|[;&|]\s*)(?:sudo\s+)?(?:apt|apt-get)\s+(?:install|upgrade|full-upgrade|dist-upgrade|remove|autoremove|--fix-broken)|\bdpkg\s+(?:--configure|--unpack|--install|--remove|--purge)"""
    )

    // /proc 暴露项。
    //
    // 为什么比 ProotEnvironment 的白名单宽：原生 chroot 是**真 root、真 chroot**，
    // /proc 是内核接口，JDK / Gradle / aapt2 / dpkg 等大量工具启动时要读
    // /proc/self/*（exe、fd、maps、status）、/proc/mounts、/proc/thread-self ——
    // 白名单缺项时表现为「java: the java command requires a mounted proc fs」，
    // 或工具静默失败。原生 chroot 本就以真 uid=0 运行、无沙盒隔离承诺，
    // 因此这里改为**在 guest 内挂载完整 proc**（见 buildMountScript），白名单仅作兜底。
    private val PROC_WHITELIST = listOf(
        "meminfo", "cpuinfo", "version", "uptime", "stat", "loadavg",
        "filesystems", "misc", "devices", "sys", "vmstat", "memfd",
        "self", "thread-self", "mounts", "cmdline", "loadavg",
    )
    private val SYS_WHITELIST = listOf(
        "kernel", "fs", "devices/system/cpu", "devices/system/memory",
    )

    @Volatile
    var lastError: String? = null
        private set

    /** x86 转译是否已在本进程内确保过（见 execute 里的注册逻辑）。 */
    private val x86Ensured = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── 复用 PRoot 的 rootfs 安装与就绪判定（rootfs 完全相同）──
    fun rootfsDir(context: Context): File = ProotEnvironment.rootfsDir(context)
    fun isReady(context: Context): Boolean = ProotEnvironment.isReady(context)
    suspend fun ensureInitialized(context: Context, onProgress: (String) -> Unit = {}): Boolean {
        val ok = ProotEnvironment.ensureInitialized(context, onProgress)
        // x86_64 转译预置：PRoot 的 ensureInitialized 只在「实际安装 rootfs」时调一次
        // X86Translation.ensureReady()，环境已就绪时不再触发。而原生 chroot 是独立后端，
        // 必须自己保证 qemu + binfmt 已注册，否则 x86_64 程序（如 Termux aapt2）跑不起来。
        //
        // 为什么放在这里而不是 execute()：注册是幂等的重活（拷 qemu、写 binfmt 条目），
        // 每次执行都做会拖慢每条命令；ensureInitialized 只在后端首次使用时跑一次。
        //
        // 前置：buildMountScript 已在 guest 内挂 binfmt_misc，且以 su 真 root 运行，
        // 因此这里的注册（写 /proc/sys/fs/binfmt_misc/register）有权限、能被内核接受。
        runCatching { X86Translation.ensureReady(context) }
            .onFailure { Log.w(TAG, "x86 转译预置未完成（不影响 ARM64 环境）: ${it.message}") }
        return ok
    }

    /**
     * 生成「su 子进程内执行」的宿主侧挂载 + chroot 启动脚本。
     * 脚本通过 stdin 把 guest bash 接到外部管道：外部写入的脚本会透传给 guest `bash -s`。
     * 所有 mount 都在 su 私有 namespace 内，su 进程退出即回收，无需手动卸载。
     */
    private fun buildMountScript(context: Context): String {
        prepareGuestRuntime(context)
        val root = rootfsDir(context).absolutePath
        val sb = StringBuilder()
        sb.appendLine("#!/system/bin/sh")
        sb.appendLine("# 原生 chroot：挂载（su 私有 namespace，退出即回收）→ exec chroot → bash -s")
        // /dev（整体 + 关键字符设备逐个，确保 urandom/null 等可用）
        bind(sb, "/dev", "$root/dev")
        for (dev in listOf("urandom", "random", "null", "zero", "tty", "pts", "ptmx", "shm", "full")) {
            bind(sb, "/dev/$dev", "$root/dev/$dev")
        }
        // /proc：**在 guest 内挂载一份新的 procfs**，而不是逐项 bind 宿主 /proc。
        //
        // 为什么必须挂完整 proc：JDK / Gradle / aapt2 / dpkg 启动时要读 /proc/self/*
        // （exe、fd、maps、status）、/proc/mounts、/proc/thread-self；缺项时 java 直接报
        // 「the java command requires a mounted proc fs」而无法运行。逐项 bind 无法覆盖
        // 这些动态入口（self 是魔术链接，bind 不了）。
        //
        // 安全性：`mount -t proc` 挂的是**新的 procfs 实例**，只反映本 mount namespace 内
        // 的进程视图；su 退出即随 namespace 回收，不泄漏宿主进程树给持久沙盒。
        // 白名单（PROC_WHITELIST）保留作兜底——若 proc 挂载失败，退回逐项 bind。
        sb.appendLine("mkdir -p '$root/proc' 2>/dev/null || true")
        sb.appendLine("if ! mount -t proc proc '$root/proc' 2>/dev/null; then")
        for (rel in PROC_WHITELIST) bind(sb, "/proc/$rel", "$root/proc/$rel")
        sb.appendLine("fi")
        // binfmt_misc：x86_64 透明执行依赖它（qemu-x86_64 注册后内核自动转译）。
        //
        // proc 是新挂的实例，不带宿主的 binfmt_misc 挂载，所以必须在这里单独挂一份；
        // 否则 X86Translation 在 guest 内注册/查询都会失败（目录存在但为空），
        // 表现为「x86 程序跑不起来」但看不出原因。
        sb.appendLine("mkdir -p '$root/proc/sys/fs/binfmt_misc' 2>/dev/null || true")
        sb.appendLine("mount -t binfmt_misc binfmt_misc '$root/proc/sys/fs/binfmt_misc' 2>/dev/null || true")
        // /sys 白名单
        for (rel in SYS_WHITELIST) bind(sb, "/sys/$rel", "$root/sys/$rel")
        // 应用私有目录（沙盒可见的唯一宿主区域）
        bind(sb, context.dataDir.absolutePath, "$root/data/user/0/com.mcp")
        bind(sb, "/data/data/com.mcp", "$root/data/data/com.mcp")
        bind(sb, context.filesDir.absolutePath, "$root/host/app")
        bind(sb, context.cacheDir.absolutePath, "$root/host/cache")
        // 用户显式授权的宿主目录
        SandboxMounts.all().forEach { host ->
            val real = resolveHostPath(File(host))
            if (real.exists()) bind(sb, real.absolutePath, "$root/${host.trimStart('/')}")
        }
        // 启动 guest：真 root，干净 env（-i），stdin 透传
        sb.appendLine(
            "exec chroot $root /usr/bin/env -i" +
                " HOME=/root USER=root LOGNAME=root" +
                " PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" +
                " LANG=C.UTF-8 LC_ALL=C.UTF-8 TERM=xterm-256color TMPDIR=/tmp" +
                " DEBIAN_FRONTEND=noninteractive ANDROID_ROOT=/system" +
                " ANDROID_DATA=/data ANDROID_STORAGE=/storage EXTERNAL_STORAGE=/sdcard" +
                " /usr/bin/bash --noprofile --norc -s"
        )
        return sb.toString()
    }

    /** 把挂载脚本落盘到应用私有目录，并返回 `su -c "sh '<path>'"` 启动参数。 */
    private fun launchCommand(context: Context): List<String> {
        val scriptFile = File(context.filesDir, "proot/native_chroot.sh").apply {
            parentFile?.mkdirs()
            writeText(buildMountScript(context))
        }
        val su = HostSu.findSu() ?: "su"
        // 刻意不用 HostSu.rootNamespaceArgs() 的 -M：原生 chroot 在 su 私有 namespace 内
        // 挂载，退出即回收；-M 会把挂载带入全局命名空间，反而需要手动清理。
        return listOf(su, "-c", "sh " + HostSu.shellQuote(scriptFile.absolutePath))
    }

    private fun bind(sb: StringBuilder, host: String, guest: String) {
        val parent = File(guest).parent ?: guest
        sb.appendLine("mkdir -p '$parent' 2>/dev/null || true")
        // 宿主是目录则建目录目标，否则建同名文件目标，保证 mount --bind 目标存在
        sb.appendLine("[ -d '$host' ] && mkdir -p '$guest' 2>/dev/null || (touch '$guest' 2>/dev/null || true)")
        sb.appendLine("mount --bind '$host' '$guest' 2>/dev/null || true")
    }

    private fun resolveHostPath(host: File): File = runCatching {
        val c = host.canonicalFile
        if (c.exists()) c else host
    }.getOrDefault(host)

    /** 刷新 guest resolv.conf（复用 PRoot 的 DNS 逻辑，避免依赖 systemd-resolved 桩）。 */
    private fun prepareGuestRuntime(context: Context) {
        val dnsServers = runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivity?.activeNetwork
            connectivity?.getLinkProperties(network)?.dnsServers.orEmpty()
                .mapNotNull { it.hostAddress?.substringBefore('%') }
        }.getOrDefault(emptyList())
            .plus(FALLBACK_DNS)
            .distinct()
            .take(4)
        val resolver = File(rootfsDir(context), "etc/resolv.conf")
        runCatching {
            resolver.parentFile?.mkdirs()
            resolver.delete()
            resolver.writeText(
                dnsServers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" }
            )
        }.onFailure { Log.w(TAG, "Unable to refresh guest DNS configuration", it) }
    }

    /** 执行脚本：写入 stdin → guest bash 运行 → 流式读回输出。 */
    fun execute(
        context: Context,
        script: String,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
        onLine: ((String) -> Unit)? = null
    ): ProotExecutionResult {
        lastError = null
        // x86 转译注册：进程内一次性。
        //
        // 为什么需要：CapabilitySeam/AndroidTools 只在 `!shell.isReady()` 时调
        // ensureInitialized——rootfs 已装好的设备 isReady 恒为 true，那条路永不执行，
        // 于是 binfmt 从未注册（表现为 binfmt_misc 已挂载但 qemu-x86_64 条目缺失）。
        //
        // 为什么放 execute 而不是 buildMountScript：buildMountScript 在 su 私有
        // namespace 的挂载脚本里，而注册要走 X86Translation.runAsRootScript（它自带 su -M，
        // 在全局 namespace 写 /proc/sys/fs/binfmt_misc，那是宿主真实挂载点）。
        // 两处 namespace 不同，必须分开做。
        //
        // 成本：仅进程内首次执行多一次 su（~百毫秒），之后走 AtomicBoolean 快速路径。
        if (x86Ensured.compareAndSet(false, true)) {
            runCatching { X86Translation.ensureReady(context) }
                .onFailure { Log.w(TAG, "x86 转译预置未完成（不影响 ARM64 环境）: ${it.message}") }
        }
        val process = try {
            ProcessBuilder(launchCommand(context))
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
        } catch (e: Exception) {
            lastError = e.message ?: e::class.java.simpleName
            Log.e(TAG, "原生 chroot 启动失败", e)
            return ProotExecutionResult(-1, "原生 chroot 启动失败: ${e.message?.take(500)}")
        }
        val output = ByteArrayOutputStream()
        var truncated = false
        val lineBuffer = ByteArrayOutputStream()
        val reader = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        if (onLine != null) {
                            for (i in 0 until read) {
                                val b = buffer[i].toInt() and 0xFF
                                if (b == '\n'.code) {
                                    val line = lineBuffer.toByteArray().toString(Charsets.UTF_8).trimEnd()
                                    lineBuffer.reset()
                                    if (line.isNotEmpty()) onLine(line)
                                } else {
                                    lineBuffer.write(b)
                                }
                            }
                        }
                        if (output.size() < MAX_OUTPUT_BYTES) {
                            val remaining = MAX_OUTPUT_BYTES - output.size()
                            output.write(buffer, 0, minOf(read, remaining))
                            if (read > remaining) truncated = true
                        } else {
                            truncated = true
                        }
                    }
                    if (onLine != null && lineBuffer.size() > 0) {
                        onLine(lineBuffer.toByteArray().toString(Charsets.UTF_8).trimEnd())
                        lineBuffer.reset()
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                Log.w(TAG, "读取原生 chroot 输出中断: ${e.message}")
            }
        }.apply { name = "native-chroot-output-reader"; start() }
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(deviceInitScript() + withDpkgRecovery(script))
            }
            var cancelled = false
            val waitStartMs = System.currentTimeMillis()
            while (true) {
                // 精确到「当前会话」（同 ProotEnvironment）：无参 isRequested() 只看 globalFlag，
                // 而 stopStream 置的是 request(sid) 的会话桶——用无参版读不到停止信号，
                // 原生 chroot 里的命令会跑到结束（「停止后还要等前一个工具完成」）。
                val cancelSid = com.mcp.ptc.PtcAudit.current()
                val cancelledNow =
                    if (cancelSid != null) com.mcp.ptc.PtcCancellation.isRequested(cancelSid)
                    else com.mcp.ptc.PtcCancellation.isRequested()
                if (cancelledNow) {
                    cancelled = true
                    break
                }
                val finished = process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
                if (finished) break
                if (timeoutSeconds > 0 &&
                    (System.currentTimeMillis() - waitStartMs) >= timeoutSeconds * 1000L) {
                    break
                }
            }
            if (cancelled) {
                process.destroyForcibly()
                reader.join(2_000)
                return ProotExecutionResult(-1, output.toString(Charsets.UTF_8.name()), timedOut = true, truncated)
            }
            if (process.isAlive) {
                process.destroyForcibly()
                reader.join(2_000)
                return ProotExecutionResult(-1, output.toString(Charsets.UTF_8.name()), timedOut = true, truncated)
            }
            reader.join(2_000)
            return ProotExecutionResult(process.exitValue(), output.toString(Charsets.UTF_8.name()), truncated = truncated)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { reader.join(2_000) }
        }
    }

    /**
     * 启动长活原生 chroot bash 进程（极简模式 persistent-bash 后端）。
     * 不等待结束、脚本由调用方多次写入 stdin；su 进程存活期间挂载持续存在，进程销毁即回收。
     */
    fun startPersistent(context: Context): Process {
        val process = ProcessBuilder(launchCommand(context))
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
        runCatching {
            val w = process.outputStream.bufferedWriter(Charsets.UTF_8)
            w.write(deviceInitScript())
            w.flush()
        }.onFailure { Log.w(TAG, "persistent shell 初始化脚本写入失败: ${it.message}") }
        return process
    }

    /** 启动长活原生 chroot 进程承载后台任务（HTTP server / listener 等），输出追加到 [logFile]。 */
    fun startDetached(context: Context, script: String, logFile: File): Process {
        logFile.parentFile?.mkdirs()
        val process = ProcessBuilder(launchCommand(context))
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(deviceInitScript() + withDpkgRecovery(script))
        }
        return process
    }

    /** Guest 启动前置脚本：确保关键设备节点可用（git/openssl 需要 /dev/urandom）。 */
    private fun deviceInitScript(): String = """
        if [ ! -e /dev/urandom ]; then mknod -m 666 /dev/urandom c 1 9 2>/dev/null || true; fi
        if [ ! -e /dev/random ]; then mknod -m 666 /dev/random c 1 8 2>/dev/null || true; fi
        if [ ! -e /dev/null ]; then mknod -m 666 /dev/null c 1 3 2>/dev/null || true; fi
        if [ ! -e /dev/zero ]; then mknod -m 666 /dev/zero c 1 5 2>/dev/null || true; fi
        if [ ! -e /dev/full ]; then mknod -m 666 /dev/full c 1 7 2>/dev/null || true; fi
        if [ ! -e /dev/tty ]; then mknod -m 666 /dev/tty c 5 0 2>/dev/null || true; fi
        :
    """.trimIndent() + "\n"

    private fun withDpkgRecovery(script: String): String {
        if (!isPackageMutation(script)) return script
        val recovery = """
            mkdir -p /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin
            chmod 0755 /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin 2>/dev/null || true
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            if [ -e /var/lib/dpkg/status-new ] || find /var/lib/dpkg/updates -type f -print -quit 2>/dev/null | grep -q .; then
                echo '[native-chroot] recovering interrupted dpkg transaction'
                /usr/bin/dpkg --configure -a || exit ${'$'}?
            fi
        """.trimIndent()
        return recovery + "\n" + script
    }

    private fun isPackageMutation(script: String): Boolean = PACKAGE_MUTATION.containsMatchIn(script)

    fun status(context: Context): String {
        val root = rootfsDir(context)
        val ready = isReady(context)
        return buildJsonObject {
            put("backend", "native-chroot")
            put("distribution", "Debian Trixie ARM64")
            put("ready", ready)
            put("rootfs", root.absolutePath)
            put("rootfs_exists", root.isDirectory)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("last_error", lastError?.let(::JsonPrimitive) ?: JsonNull)
            put("x86_translation", kotlinx.serialization.json.Json.parseToJsonElement(X86Translation.status(context)))
        }.toString()
    }
}
