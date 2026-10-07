package com.mcp.toolbox

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.*

/**
 * run_bash 写 /storage/emulated/0/Work/ 的「测试桩」。
 *
 * 目的：把"JSON 解析层"和"OS 权限层"都桩掉，用确定性场景验证
 *   run_bash 与 write_file 在写外部存储时的真实行为差异。
 *
 * 复刻依据（来自仓库）：
 *  - JSON 解析层：ChatBridge.executeToolAsync 中
 *        LenientJson.parseToJsonElement(arguments).jsonObject
 *    （LenientJson = Json { isLenient = true; ignoreUnknownKeys = true }，见 core.kt:91）
 *  - run_bash 执行层：AndroidTools.kt 的 runBash/executeBash/runShellScript。
 *        ⚠️ run_bash 不调用 checkStoragePerm / resolveTarget！
 *        脚本写到 cacheDir(应用私有,无需权限)，再用 ProcessBuilder 启动原生 bash。
 *        真正的 '> /Work/x' 写文件是 bash 子进程做的，权限由 OS 层
 *        MANAGE_EXTERNAL_STORAGE 对该 app 进程的授予情况决定(FUSE 守护进程按 UID 放行)。
 *  - write_file 执行层：FileTools.kt 的 checkStoragePerm。
 *        ⚠️ Android13+(TIRAMISU) 分支只看 READ_MEDIA_IMAGES，从不看 MANAGE_EXTERNAL_STORAGE。
 *
 * 运行：
 *   ./gradlew :tool-compiler:test --tests "com.mcp.toolbox.RunBashPermStub"
 * 或独立运行：kotlinc -cp kotlinx-serialization-json.jar RunBashPermStub.kt -include-runtime -d stub.jar && java -jar stub.jar
 */
class RunBashPermStub {

    /** 解析结果：ok=是否成功；value=成功时为 JsonObject，失败时为错误信息字符串。 */
    private data class ParseResult(val ok: Boolean, val value: Any?)

    // ───────────── 1) JSON 解析层：与 ChatBridge.executeToolAsync 完全一致 ─────────────
    private fun parseArguments(arguments: String): ParseResult {
        return try {
            val obj = LenientJson.parseToJsonElement(arguments).jsonObject
            ParseResult(true, obj)
        } catch (e: Exception) {
            ParseResult(false, e.message ?: e::class.simpleName)
        }
    }

    // ───────────── 2) run_bash 执行层：复刻 AndroidTools.runBash 的权限相关决策 ─────────────
    // 注意：run_bash 不查 checkStoragePerm。脚本放 cacheDir(永远可写)，bash 子进程按 OS MANAGE 写外部存储。
    private fun runBashSimulate(script: String, osManageGranted: Boolean): String {
        val target = script.split(" > ", limit = 2).getOrNull(1)
            ?.trim()?.split(" ")?.firstOrNull()?.trim('\'', '"')
        if (target == null) return "OK (无重定向)"
        if (target.startsWith("/storage/emulated/0/") &&
            !target.startsWith("/storage/emulated/0/Android/data/")
        ) {
            return if (osManageGranted)
                "OK: bash 子进程写入 $target 成功 (OS 已授予 MANAGE_EXTERNAL_STORAGE)"
            else
                "FAIL: bash 写 $target 被拒 (EACCES: 进程未获 MANAGE_EXTERNAL_STORAGE)"
        }
        return "OK: 写入 $target"
    }

    // ───────────── 3) write_file 执行层：复刻 FileTools.checkStoragePerm(Android13 分支) ─────────────
    // Android13+(TIRAMISU) 只看 READ_MEDIA_IMAGES，从不看 MANAGE_EXTERNAL_STORAGE（代码全工程零引用）。
    private fun checkStoragePermAndroid13(readMediaGranted: Boolean): String? =
        if (readMediaGranted) null else "无权限访问该路径"

    private fun writeFileSimulate(path: String, osManageGranted: Boolean, readMediaGranted: Boolean): String {
        val err = checkStoragePermAndroid13(readMediaGranted)
        if (err != null) return "BLOCKED by checkStoragePerm: $err"
        if (path.startsWith("/storage/emulated/0/") &&
            !path.startsWith("/storage/emulated/0/Android/data/")
        ) {
            return if (osManageGranted)
                "OK: java.io.File 写入 $path 成功 (OS 已授予 MANAGE_EXTERNAL_STORAGE)"
            else
                "FAIL: java.io.File 写 $path 被拒 (EACCES)"
        }
        return "OK: 写入 $path"
    }

    // ───────────── 4) 场景 ─────────────
    private val MALFORMED = """{"script": "echo 'hello' > /storage/emulated/0/Work/test.txt"}}""" // 用户实际发的(多一个 })
    private val VALID     = """{"script": "echo 'hello' > /storage/emulated/0/Work/test.txt"}"""

    data class Scenario(
        val name: String, val api: String,
        val osManage: Boolean, val readMedia: Boolean, val args: String
    )

    fun run() {
        val scenarios = listOf(
            Scenario("S1", "Android13", osManage = true,  readMedia = true,  args = MALFORMED), // 用户真实情况
            Scenario("S2", "Android13", osManage = true,  readMedia = true,  args = VALID),
            Scenario("S3", "Android13", osManage = false, readMedia = true,  args = VALID),
            Scenario("S5", "Android13", osManage = true,  readMedia = false, args = VALID), // 关键不对称
        )

        println("=".repeat(96))
        println("run_bash / write_file 写 /storage/emulated/0/Work/ 测试桩 — 确定性场景")
        println("=".repeat(96))
        println("%-4s %-10s %-11s %-11s %-12s | run_bash 结果".format("场景", "系统", "OS-MANAGE", "READ_MEDIA", "arguments"))
        println("-".repeat(96))
        for (s in scenarios) {
            val parsed = parseArguments(s.args)
            val rb = if (!parsed.ok) "[JSON解析失败] ${parsed.value}"
            else runBashSimulate((parsed.value as JsonObject)["script"]!!.jsonPrimitive.content, s.osManage)
            val argS = if (s.args === MALFORMED) "畸形(}})" else "合法"
            val mgS = if (s.osManage) "有" else "无"
            val rmS = if (s.readMedia) "有" else "无"
            println("%-4s %-10s %-11s %-11s %-12s | %s".format(s.name, s.api, mgS, rmS, argS, rb))
        }
        println("-".repeat(96))

        println("\n>>> 关键对比：同一套权限下，run_bash 与 write_file 的差异")
        println("-".repeat(96))
        for (s in scenarios.filter { it.name != "S1" }) {
            val rb = runBashSimulate("echo x > /storage/emulated/0/Work/test.txt", s.osManage)
            val wf = writeFileSimulate("/storage/emulated/0/Work/test.txt", s.osManage, s.readMedia)
            val mgS = if (s.osManage) "有" else "无"
            val rmS = if (s.readMedia) "有" else "无"
            println("[${s.name}] OS-MANAGE=$mgS READ_MEDIA=$rmS")
            println("    run_bash  : $rb")
            println("    write_file: $wf")
        }
    }

    /**
     * 正式单测：验证核心不对称性——仅有 MANAGE_EXTERNAL_STORAGE、未给 READ_MEDIA 时，
     * run_bash 能写 /Work/（bash 子进程按 OS 授权），而 write_file 被自己的
     * checkStoragePerm（从不认 MANAGE，只认 READ_MEDIA_IMAGES）拦截。
     * 同时验证：arguments 多一个 '}' 时会在 JSON 解析阶段失败，工具根本不执行。
     */
    @Test
    fun runBashShouldSucceedWhereWriteFileIsBlocked() {
        // 场景 S5：OS 已授予 MANAGE，但应用层未请求/未获 READ_MEDIA
        val rb = runBashSimulate("echo x > /storage/emulated/0/Work/test.txt", osManageGranted = true)
        val wf = writeFileSimulate("/storage/emulated/0/Work/test.txt", osManageGranted = true, readMediaGranted = false)
        assertTrue(rb.startsWith("OK"), "run_bash 应在 OS 已授予 MANAGE 时成功写入 /Work/")
        assertTrue(wf.startsWith("BLOCKED"), "write_file 应被 checkStoragePerm 拦截（其不识别 MANAGE）")

        // 场景 S1：arguments 畸形（多一个 '}'）-> JSON 解析失败，工具未执行
        val parsed = parseArguments("""{"script": "echo hi > /storage/emulated/0/Work/test.txt"}}""")
        assertTrue(!parsed.ok, "畸形 arguments(多一个 '}') 应在解析阶段失败")
    }
}

fun main() {
    RunBashPermStub().run()
}
