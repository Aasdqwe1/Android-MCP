package com.mcp

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 物理机 root（su）探测与 PRoot root 模式支持。
 *
 * PRoot 的 --root-id 只在 guest 内伪造 uid 视图，真正执行系统调用的是 tracee
 * 进程的宿主 uid——普通 App 仍是 u0_aXXX，SELinux 照旧拦截 /data 等其他 App 数据。
 * 若物理机已 root，可用 su 以真 uid=0 启动 PRoot，guest 内进程即真 root。
 *
 * ## 为什么不能靠「文件是否存在」判定
 *
 * Android root 方案已迭代到内核级（KernelSU / APatch），它们的 su 通过挂载或
 * PATH 注入提供，File.exists() 在普通 App 的 SELinux 域下常常返回 false。
 * 即便文件存在，File.canExecute() 也会因策略拒绝而误报——它会拦截 access(X_OK)
 * 系统调用。同理，硬编码路径列表只能覆盖 Magisk 与厂商 root。
 *
 * 因此本对象的权威判定是 verifySu()：真正 spawn 一次 su -c id，看输出里有没有
 * uid=0。findSu() 只做廉价提示（给 UI 用），不参与任何安全决策。
 */
object HostSu {
    private const val TAG = "HostSu"
    private const val PROBE_TIMEOUT_SEC = 5L

    /**
     * su 候选路径（顺序即探测优先级）。
     *
     * 覆盖主流方案：
     *  - Magisk：/debug_ramdisk/su（新版）、/sbin/su、/system/bin/su
     *  - KernelSU：/system/bin/su（挂载）、/data/adb/ksu/bin/su
     *  - APatch：/system/bin/su（挂载）、/data/adb/ap/bin/su
     *  - 厂商 root：/system/xbin/su、/su/bin/su
     *
     * 这些路径的 exists() 在部分方案下也为 false（SELinux 拦截 stat，或 /data/adb
     * 不可读），所以它们只是提示，最终以能否执行拿到 uid=0 为准。
     */
    private val SU_CANDIDATES = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/su/bin/su",
        "/debug_ramdisk/su",
        "/data/adb/ksu/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/magisk/su",
    )

    /** 已确认可执行的 su（拿到过 uid=0）。进程内缓存。 */
    @Volatile private var verifiedSu: String? = null

    /**
     * 已验证不可用的标记。缓存失败结果，避免每次调用都重新 spawn su
     * （未授权时每次都会弹授权窗，体验灾难）。用户装了/卸了 root 方案后
     * 可调 resetProbe() 重扫。
     */
    @Volatile private var verifiedFailed: Boolean = false

    /** 上次失败的时间戳，用于给失败缓存加过期（见 [FAIL_RETRY_BACKOFF_MS]）。 */
    @Volatile private var verifiedFailedAt: Long = 0L

    /** 失败后的重试退避：30 秒内不重复探测（避免刷屏授权窗），之后自动重试。 */
    private const val FAIL_RETRY_BACKOFF_MS = 30_000L

    // ───────── root 模式开关 ─────────

    /** 是否开启 root 模式（持久化；默认关，避免未授权 su 弹窗/卡死）。 */
    fun isRootModeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ROOT_MODE, false)

    fun setRootModeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ROOT_MODE, enabled).apply()
        Log.i(TAG, "root 模式: " + (if (enabled) "开启" else "关闭"))
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    // ───────── 探测 ─────────

    /**
     * 返回候选 su 路径，仅供 UI 提示，不代表一定可用。
     *
     * 优先返回已验证过的（进程缓存）；否则做一次廉价扫描（PATH 查询 + 候选路径
     * exists）。都不中时返回 null——但那也不代表设备未 root（KernelSU 的 su 可能
     * 既不在这些路径、又 stat 不到）。真正的判定请用 verifySu()。
     */
    fun findSu(): String? {
        verifiedSu?.let { return it }
        whichSu()?.let { return it }
        return SU_CANDIDATES.firstOrNull { File(it).exists() }
    }

    /**
     * 验证能否真正拿到 uid=0。这是唯一可靠的 root 判定。
     *
     * 依次尝试 PATH 的 su、各候选路径、裸 su，用 su -c id 执行，超时 5 秒。
     *
     * 关键优化：一旦某个候选「能跑起来」（无论输出什么），就停止尝试其它路径。
     * 因为不同路径最终指向同一个 su 实现，继续试只会反复触发授权弹窗。
     * 能跑但没拿到 uid=0 = 未授权/被拒，直接判定失败。
     */
    fun verifySu(): Boolean {
        verifiedSu?.let { return true }
        // 失败缓存带过期时间：此前一旦失败就进程内永久降级，用户点了「允许」授权、
        // 或 su 弹窗超时后再调用也永远返回 false，原生 chroot 再也起不来。
        // 现在失败只压制 30 秒，之后自动重试（成功即写入 verifiedSu 长期缓存）。
        if (verifiedFailed && (System.currentTimeMillis() - verifiedFailedAt) < FAIL_RETRY_BACKOFF_MS) {
            return false
        }

        val candidates = buildList {
            whichSu()?.let { add(it) }
            addAll(SU_CANDIDATES)
            add("su")
        }.distinct()

        for (su in candidates) {
            val r = probe(su)
            if (r == null) {
                Log.d(TAG, "候选不可执行，跳过: " + su)
                continue
            }
            if (r.contains("uid=0")) {
                verifiedSu = su
                Log.i(TAG, "su 验证成功: " + su)
                return true
            }
            Log.w(TAG, "su 可执行但未拿到 uid=0: " + su + ", 输出=" + r.take(120))
            markFailed()
            return false
        }
        Log.w(TAG, "su 验证失败：" + candidates.size + " 个候选均不可执行")
        markFailed()
        return false
    }

    private fun markFailed() {
        verifiedFailed = true
        verifiedFailedAt = System.currentTimeMillis()
    }

    /** 尝试执行 su，返回输出；进程起不来返回 null。 */
    private fun probe(su: String): String? = runCatching {
        val p = ProcessBuilder(su, "-c", "id")
            .redirectErrorStream(true)
            .start()
        // 顺序关键：先 waitFor 等超时，再读输出。
        // 旧实现先 readText() 再 waitFor——readText 要读到 EOF 才返回，
        // 若 su 正在弹授权框、进程不退出，这里会**死等**，waitFor 的超时形同虚设，
        // 整个调用线程被卡住（表现为设置页/首次 run_bash 无响应）。
        val finished = p.waitFor(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
        val out = runCatching {
            p.inputStream.bufferedReader().let { br ->
                val sb = StringBuilder()
                val buf = CharArray(512)
                // 进程已退出或超时被杀，这里读到的都是当前可用字节，不会无限阻塞。
                while (true) {
                    val n = br.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                    if (sb.length > 4096) break
                }
                sb.toString()
            }
        }.getOrDefault("")
        if (!finished) {
            p.destroyForcibly()
        }
        out
    }.getOrNull()

    // ───────── 挂载命名空间（-M / --mount-master） ─────────

    /**
     * su 是否支持 -M / --mount-master（切入 init 的全局挂载命名空间）。
     * null 表示尚未探测。
     *
     * 为什么必须探测而不能无脑加 -M：`-M` 是 KernelSU / Magisk 的扩展参数，
     * AOSP 与部分厂商 su 不认识它——加了会直接打印 usage 退出，命令根本不执行。
     *
     * 为什么又不能不加：Android 10+ 每个 App 进程持有独立 mount namespace，
     * 裸 su 只把 uid 换成 0，namespace 仍是本 App 的，于是
     * /data/user/0/<其它 App> 在这些进程里**根本不存在**，File.exists() 返回
     * false，工具只能报「文件不存在」——与「权限拒绝」是完全不同的故障，
     * 排查时极易被误导。MT 管理器之类能看见全部 App 目录，靠的正是 -M。
     *
     * 判定方式不是「-M 是否被接受」（部分实现会静默吞掉未知参数），而是直接
     * 比对进程自身与 PID 1 的挂载命名空间，一致才说明真的切过去了。
     */
    @Volatile private var mountMasterSupported: Boolean? = null

    /**
     * 返回执行 root 命令时该给 su 附加的参数前缀。
     * 支持全局命名空间返回 listOf("-M")，否则空列表（行为退回改动前）。
     */
    fun rootNamespaceArgs(): List<String> {
        val su = verifiedSu ?: findSu() ?: "su"
        val ok = mountMasterSupported ?: probeMountMaster(su).also { mountMasterSupported = it }
        return if (ok) listOf("-M") else emptyList()
    }

    /** 真跑一次 `su -M`，比对自身与 PID 1 的挂载命名空间是否一致。 */
    private fun probeMountMaster(su: String): Boolean = runCatching {
        val p = ProcessBuilder(su, "-M", "-c", "readlink /proc/self/ns/mnt; readlink /proc/1/ns/mnt")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching false
        }
        val ns = out.split('\n').map { it.trim() }.filter { it.startsWith("mnt:[") }
        val ok = ns.size >= 2 && ns[0] == ns[1]
        Log.i(TAG, "su -M 全局命名空间: " + (if (ok) "可用" else "不可用") + ", 输出=" + out.take(120))
        ok
    }.getOrDefault(false)

    /**
     * 若 root 模式开启且 su 可用，返回用 su 包装后的命令；否则原样返回。
     */
    fun wrapCommand(command: List<String>): List<String> {
        val su = verifiedSu ?: findSu() ?: return command
        val joined = command.joinToString(" ") { shellQuote(it) }
        return listOf(su, "-c", joined)
    }

    /** 清空探测缓存。用户装了/卸了 root 方案后可调用。 */
    fun resetProbe() {
        verifiedSu = null
        verifiedFailed = false
        verifiedFailedAt = 0L
        cachedWhichSu = null
        whichSuQueried = false
        mountMasterSupported = null
        Log.i(TAG, "su 探测缓存已清空")
    }

    /** 是否已确认拿到过 root（只读缓存，不触发新探测）。 */
    fun hasVerifiedRoot(): Boolean = verifiedSu != null

    @Volatile private var cachedWhichSu: String? = null
    @Volatile private var whichSuQueried: Boolean = false

    /** 用 /system/bin/sh 查询 PATH 中的 su。廉价子进程，不触发授权。 */
    private fun whichSu(): String? {
        if (whichSuQueried) return cachedWhichSu
        val r = queryWhichSu()
        cachedWhichSu = r
        whichSuQueried = true
        return r
    }

    private fun queryWhichSu(): String? = runCatching {
        val p = ProcessBuilder("/system/bin/sh", "-c", "command -v su")
            .redirectErrorStream(true)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor(2, TimeUnit.SECONDS)
        if (out.isNotEmpty() && File(out).exists()) out else null
    }.getOrNull()

    /** shell 单引号转义（安全拼接参数/命令片段）。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\\\''") + "'"

    private const val PREF_FILE = "host_su"
    private const val KEY_ROOT_MODE = "root_mode_enabled"
}