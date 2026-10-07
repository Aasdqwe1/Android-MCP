package com.mcp

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import android.system.Os
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Result of one command executed inside the Debian PRoot guest. */
data class ProotExecutionResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false
)

/**
 * Complete rootless Linux runtime.
 *
 * The APK carries a fixed PRoot runtime and a Debian ARM64 rootfs. The guest is
 * extracted into app-private storage on first use and is never mixed with the
 * Android process environment. Every script is sent to `/usr/bin/bash -s` through
 * stdin, so no executable script is created in the app data directory.
 */
object ProotEnvironment {
    private const val TAG = "PRoot"
    private const val VERSION = "debian-trixie-pd-v4.29.0"
    private const val ROOTFS_ASSET = "proot-runtime/debian-trixie-aarch64-pd-v4.29.0.tar.xz"
    private const val ROOTFS_SHA256 = "3834a11cbc6496935760bdc20cca7e2c25724d0cd8f5e4926da8fd5ca1857918"
    private const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
    private const val COMMAND_TIMEOUT_SECONDS = 300L

    /** 分片等待时间片（毫秒）：等待期间周期性醒来检查协作式取消标志。 */
    private const val WAIT_SLICE_MS = 200L
    private const val KERNEL_RELEASE = "6.1.0-PRoot"

    /**
     * /proc 白名单：只暴露与「当前进程」无关的系统级信息文件。
     *
     * 刻意**不含**任何数字 PID 目录、self、thread-self、mounts、cwd、root——
     * 这些要么泄漏宿主进程树，要么成为逃逸入口（/proc/1/root 一类魔法链接）。
     * 保留的是 meminfo/cpuinfo/version 这类内核静态信息，够跑常规工具链。
     */
    private val PROC_WHITELIST = listOf(
        "meminfo", "cpuinfo", "version", "uptime", "stat", "loadavg",
        "filesystems", "misc", "devices", "sys", "vmstat", "memfd",
    )

    /**
     * /sys 白名单：只暴露只读的硬件/内核信息子树。
     *
     * 不含 devices、class、block 等会泄漏宿主设备拓扑全貌的目录；
     * 保留 kernel 与 fs 下最常见的只读信息，供工具链查询内核参数。
     */
    private val SYS_WHITELIST = listOf(
        "kernel", "fs", "devices/system/cpu", "devices/system/memory",
    )
    private val FALLBACK_DNS = listOf("1.1.1.1", "8.8.8.8")
    private val PACKAGE_MUTATION = Regex(
        """(?m)(^|[;&|]\s*)(?:sudo\s+)?(?:apt|apt-get)\s+(?:install|upgrade|full-upgrade|dist-upgrade|remove|autoremove|--fix-broken)|\bdpkg\s+(?:--configure|--unpack|--install|--remove|--purge)"""
    )

    private val installMutex = Mutex()

    @Volatile
    private var nativeHardLinksSupported: Boolean? = null

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    var lastInstallAt: Long = 0L
        private set

    // The version directory itself is the guest root. READY is an inert marker
    // inside it and is intentionally hidden from normal Linux tooling.
    fun rootfsDir(context: Context): File = versionDir(context)

    private fun versionDir(context: Context): File = File(context.filesDir, "proot/$VERSION")
    private fun stagingDir(context: Context): File = File(context.filesDir, "proot/.staging-${UUID.randomUUID()}")
    private fun runtimeDir(context: Context): File = File(context.filesDir, "proot/runtime")
    private fun l2sDir(context: Context): File = File(rootfsDir(context), ".l2s")
    // Keep PRoot's host-side glue files under filesDir. Some Android 10 builds
    // expose cacheDir through the legacy /data/data alias, which can disappear
    // during PRoot path translation even though filesDir remains accessible.
    private fun prootTmpDir(context: Context): File = File(context.filesDir, "proot/tmp")
    private fun readyMarker(context: Context): File = File(versionDir(context), "READY")

    private fun nativeFile(context: Context, name: String): File =
        File(context.applicationInfo.nativeLibraryDir, name)

    fun isReady(context: Context): Boolean {
        val root = rootfsDir(context)
        return readyMarker(context).isFile &&
            File(root, "bin/bash").isFile &&
            File(root, "usr/bin/env").isFile &&
            runtimeFilesExist(context) &&
            root.isDirectory
    }

    private fun runtimeFilesExist(context: Context): Boolean {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return false
        return File(nativeDir, "libproot.so").isFile &&
            File(nativeDir, "libproot_loader.so").isFile &&
            File(nativeDir, "libandroid-shmem.so").isFile &&
            File(nativeDir, "libtalloc.so").isFile
    }

    /** Install the fixed rootfs once. Concurrent callers share the same install. */
    suspend fun ensureInitialized(context: Context, onProgress: (String) -> Unit = {}): Boolean =
        installMutex.withLock {
            if (isReady(context)) {
                // 已就绪：顺手清掉版本升级后残留的旧 rootfs（每份解压后 1-2 GB）。
                pruneOldRootfs(context)
                return@withLock true
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    lastError = null
                    onProgress("准备 PRoot ARM64 运行时…")
                    prepareRuntime(context)
                    val staging = stagingDir(context)
                    try {
                        staging.mkdirs()
                        val archive = File(context.cacheDir, "proot-$VERSION.tar.xz")
                        copyAndVerifyAsset(context, archive, onProgress)
                        extractRootfs(archive, staging, onProgress)
                        archive.delete()
                        finalizeRootfs(context, staging)
                        val check = execute(context, "printf 'proot-ok\\n'", timeoutSeconds = 15)
                        check.exitCode == 0 && check.output.contains("proot-ok")
                            .also { ok ->
                                if (!ok) error("PRoot 健康检查失败: exit=${check.exitCode} output=${check.output.take(300)}")
                            }
                        lastInstallAt = System.currentTimeMillis()
                        pruneOldRootfs(context)
                        onProgress("PRoot Debian 环境就绪")
                        // 非阻塞：x86_64 转译预置（透明执行需 root，失败不影响 ARM64 主环境）
                        runCatching { X86Translation.ensureReady(context) }
                            .onFailure { e -> LogStore.w(TAG, "x86 转译预置未完成（不影响 ARM64 环境）: ${e.message}") }
                        true
                    } finally {
                        archiveFile(context).delete()
                        if (staging.exists()) staging.deleteRecursively()
                    }
                }.getOrElse { error ->
                    lastError = error.message ?: error::class.java.simpleName
                    Log.e(TAG, "PRoot 初始化失败", error)
                    LogStore.e(TAG, "PRoot 初始化失败: ${lastError}")
                    false
                }
            }
        }

    /**
     * 删除版本升级后残留的旧 rootfs 目录。
     *
     * rootfs 目录按 VERSION 命名（proot/debian-trixie-pd-vX.Y.Z），版本号一变，
     * 旧目录就不再被任何逻辑引用，却仍占着磁盘（每份解压后 1-2 GB），只会越积越多。
     * 只删「与本环境同族、且不是当前 VERSION」的目录；runtime / tmp / .staging-* /
     * 其他无关目录一律保留。
     */
    private fun pruneOldRootfs(context: Context) {
        val prootRoot = File(context.filesDir, "proot")
        if (!prootRoot.isDirectory) return
        val family = VERSION.substringBeforeLast('-')   // 如 debian-trixie-pd
        prootRoot.listFiles()?.forEach { f ->
            if (!f.isDirectory || f.name == VERSION || f.name.startsWith(".")) return@forEach
            if (!f.name.startsWith("$family-")) return@forEach
            runCatching { f.deleteRecursively() }
                .onSuccess { LogStore.i(TAG, "已清理旧版本 rootfs 目录: ${f.name}") }
        }
    }

    private fun archiveFile(context: Context): File = File(context.cacheDir, "proot-$VERSION.tar.xz")

    private fun prepareRuntime(context: Context) {
        require(Build.SUPPORTED_64_BIT_ABIS.any { it == "arm64-v8a" }) {
            "当前 APK 仅支持 arm64-v8a，设备不提供 ARM64 ABI"
        }
        require(runtimeFilesExist(context)) {
            "APK 缺少 PRoot native 运行时，请检查 libproot.so、loader 和依赖库"
        }
        val dir = runtimeDir(context).apply { mkdirs() }
        copyIfChanged(nativeFile(context, "libproot.so"), File(dir, "proot"))
        copyIfChanged(nativeFile(context, "libproot_loader.so"), File(dir, "loader"))
        copyIfChanged(nativeFile(context, "libproot_loader32.so"), File(dir, "loader32"))
        copyIfChanged(nativeFile(context, "libandroid-shmem.so"), File(dir, "libandroid-shmem.so"))
        copyIfChanged(nativeFile(context, "libtalloc.so"), File(dir, "libtalloc.so"))
        context.assets.open("proot-runtime/libtalloc.so.2").use { input ->
            File(dir, "libtalloc.so.2").outputStream().use { output -> input.copyTo(output) }
        }
        listOf("proot", "loader", "loader32").forEach { File(dir, it).setExecutable(true, false) }
    }

    private fun copyIfChanged(source: File, destination: File) {
        if (!destination.isFile || destination.length() != source.length()) {
            source.copyTo(destination, overwrite = true)
        }
        destination.setExecutable(source.name in setOf("proot", "loader", "loader32"), false)
    }

    private fun copyAndVerifyAsset(context: Context, destination: File, onProgress: (String) -> Unit) {
        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ROOTFS_ASSET).use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var read: Int
                while (input.read(buffer).also { read = it } >= 0) {
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    total += read
                    if (total % (4L * 1024 * 1024) < buffer.size) {
                        onProgress("准备 rootfs ${total / 1024 / 1024}MB…")
                    }
                }
            }
        }
        val actual = digest.digest().toHex()
        require(actual == ROOTFS_SHA256) { "rootfs SHA-256 不匹配: $actual" }
    }

    private fun extractRootfs(archive: File, staging: File, onProgress: (String) -> Unit) {
        onProgress("解压 Debian rootfs…")
        FileInputStream(archive).use { fileInput ->
            XZInputStream(BufferedInputStream(fileInput, 64 * 1024)).use { xz ->
                TarExtractor(xz, staging, onProgress).extract()
            }
        }
        listOf(
            "root", "tmp", "workspace", "proc", "sys", "dev", "run", "host", ".l2s",
            // 沙盒唯一可见的宿主区域：应用自身私有目录（bind 目标需预先存在）
            "data/user/0/com.mcp", "data/data/com.mcp"
        ).forEach {
            File(staging, it).mkdirs()
        }
        File(staging, "tmp").setWritable(true, false)
        File(staging, "tmp").setExecutable(true, false)
    }

    private fun finalizeRootfs(context: Context, staging: File) {
        val root = rootfsDir(context)
        require(File(staging, "bin/bash").isFile) { "rootfs 缺少 /bin/bash" }
        require(File(staging, "usr/bin/env").isFile) { "rootfs 缺少 /usr/bin/env" }
        val version = versionDir(context)
        version.parentFile?.mkdirs()
        if (version.exists()) version.deleteRecursively()
        require(staging.renameTo(version)) { "无法原子切换 PRoot rootfs" }
        val markerTmp = File(version, "READY.tmp")
        markerTmp.writeText("$VERSION\n$ROOTFS_SHA256\n")
        require(markerTmp.renameTo(readyMarker(context))) { "无法写入 PRoot READY 标记" }
        require(root.isDirectory && readyMarker(context).isFile) { "PRoot rootfs 提交失败" }
    }

    /** Build the complete host-side PRoot command and guest environment. */
    fun command(
        context: Context,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
        useNativeHardLinks: Boolean = false
    ): List<String> {
        require(isReady(context)) { lastError ?: "PRoot 环境未就绪" }
        val runtime = runtimeDir(context)
        val root = rootfsDir(context)
        prepareGuestRuntime(context)
        val args = mutableListOf<String>(
            "/system/bin/linker64",
            File(runtime, "proot").absolutePath,
            "--rootfs=${root.absolutePath}",
            "--cwd=/root",
            "--root-id",
            "--kernel-release=$KERNEL_RELEASE",
            "--sysvipc",
            "--kill-on-exit"
        )
        if (!useNativeHardLinks) args += "--link2symlink"
        // ── 挂载策略：沙盒只暴露「应用自身私有目录」+ 内核必需接口 ──
        // 边界原则：沙盒是沙盒、宿主是宿主。guest 内不 bind 任何宿主可写区域
        // （公共存储 /sdcard、/storage、/mnt，Android 系统分区 /system、/vendor…，
        // 以及宿主 /data、/cache），沙盒既看不到也改不了宿主文件。
        // 仅保留两类：
        //   1) 内核必需接口（/dev /proc /sys）—— 内核虚拟视图而非宿主文件，
        //      缺了 bash/apt/git 均无法运行；/proc 会暴露宿主进程列表，属已知取舍。
        //   2) 应用自身私有目录（filesDir/cacheDir/dataDir）—— 即「应用本身范围」。
        listOf("data/user/0/com.mcp", "data/data/com.mcp", "host").forEach { ensureGuestDir(context, it) }
        // /dev 绑定（Android 10 可能不暴露 pts 子树，保留 PTY 供 apt/dpkg/shell 用）
        addBind(args, File("/dev/"), "/dev")
        // 关键设备文件显式绑定：git/openssl/ssh 需要 /dev/urandom 生成随机字节
        // （git_mkstemp 创建临时 pack 文件时直接从 /dev/urandom 读取），
        // /dev 整体绑定在某些 ROM/SELinux 下可能不完整，逐个绑定更可靠。
        // PRoot 绑定宿主设备文件后，guest 里 open("/dev/urandom") 会被翻译到
        // 宿主的字符设备，App 进程读 /dev/urandom 是 SELinux 允许的。
        addBind(args, File("/dev/urandom"), "/dev/urandom")
        addBind(args, File("/dev/random"), "/dev/random")
        addBind(args, File("/dev/null"), "/dev/null")
        addBind(args, File("/dev/zero"), "/dev/zero")
        addBind(args, File("/dev/tty"), "/dev/tty")
        addBind(args, File("/dev/pts"), "/dev/pts")
        addBind(args, File("/dev/ptmx"), "/dev/ptmx")
        addBind(args, File("/dev/shm"), "/dev/shm")
        // 内核虚拟文件系统：**不整体 bind /proc**。
        //
        // 为什么收窄：/proc 是内核接口，PRoot 的路径翻译拦不住它内部的
        // 魔法链接与进程视图。整体 bind 宿主 /proc 会让 guest 直接看到
        // 全部宿主进程（/proc/<pid>/cmdline、/proc/mounts 分区结构），
        // 等于在沙盒墙上开了一个信息泄漏口。
        //
        // 也不能让 guest 自己挂：PRoot 下 mount 系统调用不可用（open_tree 失败），
        // 宿主侧 toybox unshare 又不支持 --mount-proc。
        //
        // 因此改为**白名单式逐项 bind**：只暴露与进程无关的系统信息文件。
        // 效果：ps 类命令不可用（可接受），但 bash/apt/git/dpkg 全部照常，
        // 且 guest 内看不到任何宿主进程。
        listOf("proc").forEach { ensureGuestDir(context, it) }
        PROC_WHITELIST.forEach { rel ->
            addBind(args, File("/proc/$rel"), "/proc/$rel")
        }
        // /sys 同样只暴露只读的硬件信息子树，避免泄漏宿主设备拓扑全貌。
        listOf("sys").forEach { ensureGuestDir(context, it) }
        SYS_WHITELIST.forEach { rel ->
            addBind(args, File("/sys/$rel"), "/sys/$rel")
        }
        // 应用私有目录：沙盒可见的唯一宿主区域（Android 原生路径 + 便捷别名）
        addBind(args, context.dataDir, "/data/user/0/com.mcp")
        addBind(args, File("/data/data/com.mcp"), "/data/data/com.mcp")
        addBind(args, File(context.filesDir.absolutePath), "/host/app")
        addBind(args, File(context.cacheDir.absolutePath), "/host/cache")
        // 沙盒宿主挂载授权：仅**用户显式授权**的宿主目录才会 bind 进 guest。
        // 默认名单为空 —— 沙盒开箱即与宿主完全隔离，授权是唯一的暴露入口。
        // guest 侧目标路径与宿主保持一致，脚本里可直接用同一绝对路径访问。
        SandboxMounts.all().forEach { host ->
            val real = resolveHostPath(File(host))
            if (real.exists()) {
                ensureGuestDir(context, host.trimStart('/'))
                addBind(args, real, host)
            }
        }
        // Use the guest env only to create a clean Linux environment. The PRoot
        // host-side variables are kept in ProcessBuilder and are not leaked into
        // Bash because env -i replaces the guest environment.
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root",
            "USER=root",
            "LOGNAME=root",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8",
            "LC_ALL=C.UTF-8",
            "TERM=xterm-256color",
            "TMPDIR=/tmp",
            "DEBIAN_FRONTEND=noninteractive",
            // 持久 shell / 长任务专用：guest bash 的父进程（PRoot 主体）异常死亡时，
            // 让 bash 收到 SIGTERM 自杀，避免父死子孤、后台 job 泄漏。
            // 一次性 execute 也无害（bash 父本就是 PRoot，PRoot 结束时 bash 一起结束）。
            "PDEATHSIG=SIGTERM",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "ANDROID_STORAGE=/storage",
            "EXTERNAL_STORAGE=/sdcard",
            "/usr/bin/bash", "--noprofile", "--norc", "-s"
        )
        Log.d(TAG, "PRoot command built timeout=${timeoutSeconds}s root=${root.absolutePath}")
        return args
    }

    private fun addBind(args: MutableList<String>, host: File, guest: String) {
        // 只要求存在即可绑定：是否可读由访问时的 SELinux/权限决定。
        // 注意不能用 canRead() 过滤——Android /data 权限 drwxrwx--x 对 App 无 R_OK，
        // 但绑定依然必要（guest 内访问到有权限的子路径如 /data/user/0/com.mcp 时正常）。
        val real = resolveHostPath(host)
        if (real.exists()) args += "--bind=${real.absolutePath}:$guest"
    }


    /**
     * 确保 guest rootfs 内存在挂载点目录。
     *
     * PRoot 的 --bind 目标必须先在 guest 侧存在；本版本根文件系统已预建
     * data/user/0/com.mcp 与 data/data/com.mcp，但由旧 rootfs 升级而来的
     * 安装可能缺目录，导致绑定静默失效。这里幂等补齐。
     */
    private fun ensureGuestDir(context: Context, rel: String) {
        runCatching { File(rootfsDir(context), rel).mkdirs() }
    }

    /**
     * 解析宿主路径真实位置（跟随符号链接）。
     * Android 的 /data/data -> /data/user/0、/sdcard -> /storage/emulated/0 均为链接，
     * 解析成真实路径再绑定，避免 guest 内绑到链接本身。解析失败回退原路径。
     */
    private fun resolveHostPath(host: File): File = runCatching {
        val c = host.canonicalFile
        if (c.exists()) c else host
    }.getOrDefault(host)

    /** Prepare state that cannot be shipped correctly in a static rootfs tar. */
    private fun prepareGuestRuntime(context: Context) {
        l2sDir(context).mkdirs()

        // Debian images commonly carry systemd-resolved's 127.0.0.53 stub,
        // but systemd is intentionally not started inside this PRoot guest.
        // Use Android's active network DNS servers and retain public fallbacks.
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
            // Remove a shipped symlink as well as a regular file. A guest
            // without systemd must not follow it to a missing stub resolver.
            resolver.delete()
            resolver.writeText(dnsServers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" })
        }.onFailure {
            Log.w(TAG, "Unable to refresh guest DNS configuration", it)
        }
    }

    /** Build and configure (not yet started) the PRoot process for a script. */
    private fun prepareProcess(context: Context, script: String): ProcessBuilder {
        // Android app-private files normally support hard links even when the
        // system-wide Termux layout does not. dpkg needs a real hard link for
        // archive entries such as /usr/bin/perlthanks, then immediately calls
        // chown on the resulting .dpkg-new path. PRoot's link2symlink layer can
        // lose that temporary path during this sequence, so use native links
        // only for package mutations when the private rootfs supports them.
        val packageMutation = isPackageMutation(script)
        val useNativeHardLinks = packageMutation && supportsNativeHardLinks(context)
        if (packageMutation) {
            ensureSafeGuestPath(context)
        }
        val command = command(context, COMMAND_TIMEOUT_SECONDS, useNativeHardLinks)
        if (packageMutation) {
            Log.d(TAG, "Package command hard-link mode: native=$useNativeHardLinks")
        }
        val runtime = runtimeDir(context)
        val prootTmp = prootTmpDir(context).apply {
            mkdirs()
            require(isDirectory && canWrite()) { "PRoot 临时目录不可写: $absolutePath" }
        // Do not call canonicalFile here. On Android it may resolve the app's
        // /data/user/0 path to the legacy /data/data alias, which is not always
        // traversable by PRoot on newer devices.
        }.absoluteFile
        // root 模式：若物理机 su 可用，用 su 启动整个 PRoot 进程，guest 内即真 root，
        // SELinux 放行 /data 等；否则维持普通 App 权限（--root-id 只伪装 uid 视图）。
        // 判定改用 verifySu（真跑一次 su -c id）。findSu 只看文件存在性，
        // KernelSU / APatch 的 su 常 stat 不到，会导致已 root 的设备被误判为不可用。
        // verifySu 有失败缓存，不会每次启动都触发授权弹窗。
        val launchCommand = if (HostSu.isRootModeEnabled(context) && HostSu.verifySu()) {
            HostSu.wrapCommand(command)
        } else command
        return ProcessBuilder(launchCommand)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.PIPE)
            .apply {
                val nativeLoader = nativeFile(context, "libproot_loader.so")
                val nativeLoader32 = nativeFile(context, "libproot_loader32.so")
                // These variables are consumed by the Android-side PRoot
                // executable and must not be replaced by guest values.
                environment().clear()
                environment()["LD_LIBRARY_PATH"] = runtime.absolutePath
                // Keep the loader in Android's extracted native-library directory.
                // It is executable there; app-private cache/files directories may
                // be mounted with restrictions on some Android 10 devices.
                environment()["PROOT_LOADER"] = nativeLoader.absolutePath
                environment()["PROOT_LOADER_32"] = nativeLoader32.absolutePath
                environment()["PROOT_L2S_DIR"] = File(rootfsDir(context), ".l2s").absolutePath
                // PRoot's package default may point to an unavailable path. Set it
                // explicitly so the Android app always uses its private cache.
                environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
                // Huawei/Android 10 vendor kernels commonly fail PRoot's seccomp
                // probe; ptrace mode remains supported and is more compatible.
                environment()["PROOT_NO_SECCOMP"] = "1"
                // When a package manager must fall back to link2symlink (the
                // device rejects native hard links), the bundled PRoot copies
                // the source file instead of fabricating an l2s symlink chain:
                // dpkg then owns and chowns a real .dpkg-new path it can find.
                if (packageMutation && !useNativeHardLinks) {
                    environment()["PROOT_LINK2COPY"] = "1"
                }
                // The guest sees /tmp; PRoot itself uses PROOT_TMP_DIR above.
                environment()["HOME"] = "/root"
                environment()["USER"] = "root"
                environment()["LOGNAME"] = "root"
                environment()["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
                environment()["LANG"] = "C.UTF-8"
                environment()["LC_ALL"] = "C.UTF-8"
                environment()["TERM"] = "xterm-256color"
                environment()["TMPDIR"] = "/tmp"
                environment()["DEBIAN_FRONTEND"] = "noninteractive"
                environment()["ANDROID_ROOT"] = "/system"
            }
    }

    /**
     * 在宿主侧直接修正 guest PATH 各目录的存在性与权限（0755）。
     *
     * Perl -T (taint) 的 secure_path 会逐个 stat PATH 目录，任意目录“组/其他可写”
     * 即报 "Insecure directory in $ENV{PATH}"（Debian 13 收得更紧，dbus/adduser 等
     * postinst 都会触发）。PRoot fakeroot（--root-id）下 guest 内的 chmod 可能被伪装成
     * 成功而宿主权限未真正变更，因此这里绕过 PRoot、直接在宿主 rootfs 上落盘权限。
     */
    private fun ensureSafeGuestPath(context: Context) {
        val root = rootfsDir(context)
        listOf(
            "usr/local/sbin", "usr/local/bin", "usr/sbin", "usr/bin", "sbin", "bin"
        ).forEach { rel ->
            runCatching {
                val dir = File(root, rel)
                dir.mkdirs()
                Os.chmod(dir.absolutePath, 0x1ED) // 0755（仅属主可写）
            }.onFailure {
                Log.w(TAG, "无法修正 PATH 目录权限: $rel (${it.message})")
            }
        }
    }

    /** Execute a Bash script by streaming it to the guest shell's stdin. */
    fun execute(
        context: Context,
        script: String,
        timeoutSeconds: Long = COMMAND_TIMEOUT_SECONDS,
        onLine: ((String) -> Unit)? = null
    ): ProotExecutionResult {
        val process = prepareProcess(context, script).start()
        val output = ByteArrayOutputStream()
        var truncated = false
        // 原始字节累积，行尾（遇到 \n）再按 UTF-8 解码，避免中文等多字节字符被逐字节拆散成乱码
        val lineBuffer = ByteArrayOutputStream()
        val reader = Thread {
            try {
                process.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } >= 0) {
                        if (read == 0) continue
                        // 按行回调
                        if (onLine != null) {
                            for (i in 0 until read) {
                                val b = buffer[i].toInt() and 0xFF
                                if (b == '\n'.code) {
                                    val line = lineBuffer.toByteArray().toString(Charsets.UTF_8).trimEnd()
                                    lineBuffer.reset()
                                    if (line.isNotEmpty()) {
                                        onLine(line)
                                    }
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
                    // 最后一行可能没有换行
                    if (onLine != null && lineBuffer.size() > 0) {
                        onLine(lineBuffer.toByteArray().toString(Charsets.UTF_8).trimEnd())
                        lineBuffer.reset()
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (e: Exception) {
                // 进程被强杀或管道断开时 input.read 会抛 IOException，不能击穿后台线程导致 App 崩溃。
                Log.w(TAG, "读取 PRoot 输出中断: ${e.message}")
            }
        }.apply { name = "proot-output-reader"; start() }
        try {
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(deviceInitScript() + withDpkgRecovery(script))
            }
            // 分片等待：每片至多 [WAIT_SLICE_MS] 毫秒，醒来先看协作式取消标志。
            //
            // 不能用一把 process.waitFor(timeoutSeconds)：那是宿主侧阻塞调用，
            // 既不响应协程取消，也读不到 PtcCancellation——用户点「停止」后 PRoot
            // 进程要等自然结束/超时才被 finally 回收，表现为「停止后命令还在跑」。
            // 分片轮询让停止请求能在 ~200ms 内被感知，立即 SIGKILL 并返回。
            var cancelled = false
            val waitStartMs = System.currentTimeMillis()
            while (true) {
                // 精确到「当前会话」：无参 isRequested() 只看 globalFlag，而 stopStream 调的是
                // request(sid)（置会话桶）——用无参版永远读不到停止信号，导致用户点停止后
                // PRoot 进程仍跑到命令结束（「停止后还要等前一个工具完成」的根因）。
                val cancelSid = com.mcp.ptc.PtcAudit.current()
                val cancelledNow =
                    if (cancelSid != null) com.mcp.ptc.PtcCancellation.isRequested(cancelSid)
                    else com.mcp.ptc.PtcCancellation.isRequested()
                if (cancelledNow) {
                    cancelled = true
                    break
                }
                val finished = if (timeoutSeconds > 0) {
                    process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
                } else {
                    // 0 表示无超时：仍分片轮询，保证取消可被感知。
                    process.waitFor(WAIT_SLICE_MS, TimeUnit.MILLISECONDS)
                }
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
            // 用 isAlive 判定而非 finished 布尔：分片循环的最后一拍是「未完成」不代表超时，
            // 需区分「进程已结束」与「等待预算耗尽仍在运行」。
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
     * 启动一个**长活** PRoot bash 进程（极简模式的 persistent-bash 后端）。
     *
     * 与 [execute] 的区别：不等待进程结束、脚本由调用方多次写入 stdin
     * （命令尾部已经是 `bash --noprofile --norc -s`），因此 cwd / 环境变量 /
     * shell 变量在多次工具调用之间持续存在。同时去掉 `--kill-on-exit`——
     * 该标志会在 shell 退出时连带回收 guest 进程，对长活会话反而是负担。
     *
     * 返回的 [Process] 归调用方所有：必须自行 `destroyForcibly()` 销毁
     * （见 [com.mcp.PersistentShellProvider.destroy]）。
     */
    fun startPersistent(context: Context): Process {
        val builder = prepareProcess(context, "")
        val command = ArrayList(builder.command())
        if (command.remove("--kill-on-exit")) {
            Log.d(TAG, "Persistent shell: --kill-on-exit removed")
        }
        builder.command(command)
        val process = builder.start()
        // 与 [execute] 一致，先跑一次设备节点兜底脚本（git/openssl 需要 /dev/urandom）。
        // 该脚本只有副作用、无输出，写入后不等待，命令间的 sentinel 机制不受影响。
        runCatching {
            val w = process.outputStream.bufferedWriter(Charsets.UTF_8)
            w.write(deviceInitScript())
            w.flush()
        }.onFailure { Log.w(TAG, "persistent shell 初始化脚本写入失败: ${it.message}") }
        return process
    }

    /**
     * Start a long-lived PRoot process for a background task (HTTP server, listener, etc.).
     *
     * Unlike [execute] this returns immediately without waiting. The PRoot process itself
     * becomes the background task and keeps running, so its syscall interception stays alive
     * for the guest process; a guest process cannot outlive its PRoot tracer. stdout/stderr are
     * merged and appended to the host-side [logFile]. The caller owns the returned [Process]
     * and must terminate it via `destroyForcibly()` (a companion `bash_task_kill`).
     */
    fun startDetached(context: Context, script: String, logFile: File): Process {
        logFile.parentFile?.mkdirs()
        val process = prepareProcess(context, script)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            .start()
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(deviceInitScript() + withDpkgRecovery(script))
        }
        return process
    }

    /**
     * Recover an interrupted dpkg transaction left by an older app build.
     *
     * The old build could leave `/var/lib/dpkg/status-new` after the failed
     * hard-link backup. Debian's apt intentionally refuses every install until
     * `dpkg --configure -a` completes. Do this only for package-management
     * scripts and only while the interrupted marker is present; ordinary Bash
     * commands retain their exact behavior.
     */
    /**
     * Guest 启动前置脚本：确保关键设备节点可用。
     *
     * Android 宿主 /dev/urandom 等字符设备通常通过 PRoot 绑定透传，但个别
     * ROM/SELinux 策略下整体 /dev 绑定不完整。此脚本在每次命令执行前兜底：
     * 若 /dev/urandom 缺失，尝试 mknod 创建设备节点（PRoot --root-id 下
     * 对非受限目录的 mknod 通常被允许）。git 的 git_mkstemp 依赖 /dev/urandom
     * 生成临时 pack 文件名，缺失会导致 "unable to get random bytes for
     * temporary file"。
     */
    private fun deviceInitScript(): String = """
        # PRoot device nodes fallback (git/openssl need /dev/urandom)
        if [ ! -e /dev/urandom ]; then
            mknod -m 666 /dev/urandom c 1 9 2>/dev/null || true
        fi
        if [ ! -e /dev/random ]; then
            mknod -m 666 /dev/random c 1 8 2>/dev/null || true
        fi
        if [ ! -e /dev/null ]; then
            mknod -m 666 /dev/null c 1 3 2>/dev/null || true
        fi
        if [ ! -e /dev/zero ]; then
            mknod -m 666 /dev/zero c 1 5 2>/dev/null || true
        fi
        if [ ! -e /dev/full ]; then
            mknod -m 666 /dev/full c 1 7 2>/dev/null || true
        fi
        if [ ! -e /dev/tty ]; then
            mknod -m 666 /dev/tty c 5 0 2>/dev/null || true
        fi
        :
    """.trimIndent() + "\n"

    private fun withDpkgRecovery(script: String): String {
        if (!isPackageMutation(script)) return script
        val recovery = """
            # 包管理环境修复：
            # 1) perl taint（dpkg/openssh/dbus 等 postinst 用 -T 调用 adduser）会逐一
            #    stat PATH 里的目录；目录缺失（精简 rootfs 打包可能丢弃空目录）或被
            #    看作 world-writable 时，报 "Insecure directory in PATH"。这里先补齐
            #    并收紧权限，再固化为纯 rootfs 目录。
            # 2) 修复中断的 dpkg 事务（status-new / updates 残留）。
            mkdir -p /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin
            chmod 0755 /usr/local/sbin /usr/local/bin /usr/sbin /usr/bin /sbin /bin 2>/dev/null || true
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            if [ -e /var/lib/dpkg/status-new ] || find /var/lib/dpkg/updates -type f -print -quit 2>/dev/null | grep -q .; then
                echo '[PRoot] recovering interrupted dpkg transaction'
                /usr/bin/dpkg --configure -a || exit ${'$'}?
            fi
        """.trimIndent()
        return recovery + "\n" + script
    }

    private fun isPackageMutation(script: String): Boolean = PACKAGE_MUTATION.containsMatchIn(script)

    /**
     * Check the exact filesystem used for the guest rootfs instead of making
     * assumptions about the Android vendor kernel. The result is cached for
     * this app process because the rootfs and its backing filesystem do not
     * change during a session.
     */
    private fun supportsNativeHardLinks(context: Context): Boolean {
        nativeHardLinksSupported?.let { return it }
        val probeDir = File(rootfsDir(context), ".native-hardlink-probe")
        val source = File(probeDir, "source")
        val target = File(probeDir, "target")
        val supported = runCatching {
            probeDir.mkdirs()
            source.delete()
            target.delete()
            source.writeText("proot-hardlink-probe\n")
            Os.link(source.absolutePath, target.absolutePath)
            source.isFile && target.isFile && target.length() == source.length()
        }.getOrDefault(false)
        runCatching { probeDir.deleteRecursively() }
        nativeHardLinksSupported = supported
        Log.d(TAG, "Native hard-link probe: supported=$supported")
        return supported
    }

    fun status(context: Context): String {
        val root = rootfsDir(context)
        val runtime = runtimeDir(context)
        val ready = isReady(context)
        return buildJsonObject {
            put("backend", "proot")
            put("distribution", "Debian Trixie ARM64")
            put("version", VERSION)
            put("ready", ready)
            put("rootfs", root.absolutePath)
            put("rootfs_exists", root.isDirectory)
            put("rootfs_bytes", if (root.exists()) rootSize(root) else 0L)
            put("proot", File(runtime, "proot").absolutePath)
            put("loader", nativeFile(context, "libproot_loader.so").absolutePath)
            put("loader32", nativeFile(context, "libproot_loader32.so").absolutePath)
            put("l2s_dir", l2sDir(context).absolutePath)
            put("proot_tmp_dir", prootTmpDir(context).absolutePath)
            put("native_library_dir", context.applicationInfo.nativeLibraryDir)
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
            put("last_install_at", lastInstallAt)
            put("last_error", lastError?.let(::JsonPrimitive) ?: JsonNull)
            put("x86_translation", kotlinx.serialization.json.Json.parseToJsonElement(X86Translation.status(context)))
        }.toString()
    }

    private fun rootSize(file: File): Long {
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf(::rootSize) ?: 0L
    }

    private class TarExtractor(
        private val input: InputStream,
        private val destination: File,
        private val onProgress: (String) -> Unit
    ) {
        private var entries = 0

        fun extract() {
            val header = ByteArray(512)
            while (readBlock(header)) {
                if (header.all { it.toInt() == 0 }) break
                val rawName = field(header, 0, 100)
                val prefix = field(header, 345, 155)
                val rawPath = if (prefix.isBlank()) rawName else "$prefix/$rawName"
                val path = safePath(rawPath)
                if (path == null) {
                    skipEntry(number(header, 124, 12))
                    continue
                }
                val size = number(header, 124, 12)
                val mode = number(header, 100, 8).toInt()
                val type = header[156].toInt().and(0xff).toChar()
                val target = File(destination, path)
                when (type) {
                    '5' -> {
                        target.mkdirs()
                        chmod(target, mode)
                        skipEntry(size)
                    }
                    '2' -> {
                        val linkTarget = field(header, 157, 100)
                        target.parentFile?.mkdirs()
                        deleteEntry(target)
                        runCatching { Os.symlink(linkTarget, target.absolutePath) }
                            .getOrElse { throw IllegalStateException("无法创建 rootfs 符号链接 $path -> $linkTarget", it) }
                        skipEntry(size)
                    }
                    '1' -> {
                        val linkTarget = safePath(field(header, 157, 100))
                        target.parentFile?.mkdirs()
                        deleteEntry(target)
                        val source = linkTarget?.let { File(destination, it) }
                        if (source?.isFile == true) source.copyTo(target, overwrite = true)
                        else throw IllegalStateException("rootfs hardlink 目标不存在: $path -> $linkTarget")
                        skipEntry(size)
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        deleteEntry(target)
                        FileOutputStream(target).use { output -> copyExactly(size, output) }
                        chmod(target, mode)
                        skipPadding(size)
                    }
                    else -> skipEntry(size)
                }
                entries++
                if (entries % 500 == 0) onProgress("解压 Debian rootfs：$entries 个文件…")
            }
        }

        private fun safePath(raw: String): String? {
            val cleaned = raw.replace('\\', '/').removePrefix("./")
            val parts = cleaned.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            val withoutArchiveRoot = if (parts.first() == "debian-trixie-aarch64") parts.drop(1) else parts
            if (withoutArchiveRoot.isEmpty()) return null
            if (withoutArchiveRoot.any { it == ".." }) throw IllegalStateException("rootfs 路径越界: $raw")
            return withoutArchiveRoot.joinToString("/")
        }

        private fun readBlock(buffer: ByteArray): Boolean {
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count < 0) return offset == 0
                if (count == 0) continue
                offset += count
            }
            return true
        }

        private fun copyExactly(size: Long, output: OutputStream) {
            var remaining = size
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                val count = input.read(buffer, 0, wanted)
                require(count > 0) { "rootfs tar 数据提前结束" }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }

        private fun skipEntry(size: Long) {
            var remaining = size
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val wanted = minOf(remaining, buffer.size.toLong()).toInt()
                val count = input.read(buffer, 0, wanted)
                require(count > 0) { "rootfs tar 数据提前结束" }
                remaining -= count
            }
            skipPadding(size)
        }

        private fun skipPadding(size: Long) {
            val padding = (512L - (size % 512L)) % 512L
            repeat(padding.toInt()) { input.read() }
        }

        private fun field(buffer: ByteArray, offset: Int, length: Int): String {
            var end = offset
            while (end < offset + length && buffer[end].toInt() != 0 && buffer[end].toInt() != 32) end++
            return buffer.copyOfRange(offset, end).toString(Charsets.UTF_8)
        }

        private fun number(buffer: ByteArray, offset: Int, length: Int): Long {
            val text = field(buffer, offset, length).trim()
            return if (text.isEmpty()) 0L else text.toLongOrNull(8) ?: 0L
        }

        private fun chmod(file: File, mode: Int) {
            runCatching { Os.chmod(file.absolutePath, mode and 0x1ff) }
            if (mode and 0x100 != 0) file.setReadable(true, false)
            if (mode and 0x40 != 0) file.setWritable(true, false)
            if (mode and 0x49 != 0) file.setExecutable(true, false)
        }

        private fun deleteEntry(file: File) {
            if (file.exists() || runCatching { Os.lstat(file.absolutePath); true }.getOrDefault(false)) {
                file.deleteRecursively()
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}