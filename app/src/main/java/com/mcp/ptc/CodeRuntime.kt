package com.mcp.ptc

import kotlinx.serialization.json.JsonPrimitive

/**
 * PTC 程序运行时：决定用哪种解释器、如何把用户代码包装成可运行程序。
 *
 * 一个 run_code 程序 = **prelude（注入 `tools` 桥）** + **用户代码（async 函数体）** + **runner**。
 * prelude 里的 `tools.name(args)` 会把调用序列化成 [com.mcp.toolbox.ptc.PtcCallRequest]
 * 写入请求文件，并阻塞等待宿主侧经 [com.mcp.toolbox.Toolbox.dispatch] 执行后写回结果文件。
 *
 * 用户代码即 `async` 函数体；prelude 定义 `_ptc_main()` 并把用户代码缩进为其函数体，最后由 runner 调用。
 */
interface CodeRuntime {
    /** 语言标识（python / node）。 */
    val language: String

    /** 解释器启动参数（命令 + 脚本 guest 路径）。 */
    fun interpreterArgs(scriptGuestPath: String): List<String>

    /** prelude（注入 tools 桥 + 定义空的 `_ptc_main()`）；[callTimeoutSeconds] 用于子调用轮询上限。 */
    fun prelude(reqPath: String, resDir: String, callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_SECONDS): String

    /**
     * 把用户代码拼成完整程序：prelude + 缩进后的用户代码 + runner。
     * runner 统一写 `{"__ptc_ok":true,"value":...}` / `{"__ptc_ok":false,"error":...}` 信封，
     * 宿主据此判定成败（不再靠 stdout 里是否出现 "error" 这种脆弱启发式）。
     */
    fun buildProgram(
        userCode: String,
        reqPath: String,
        resDir: String,
        callTimeoutSeconds: Long = DEFAULT_CALL_TIMEOUT_SECONDS
    ): String
}

/** 子调用轮询默认上限（秒）：与宿主 PtcBridge 的单次调用超时保持一致。 */
const val DEFAULT_CALL_TIMEOUT_SECONDS = 300L

/** Python 运行时（默认）。 */
object PythonRuntime : CodeRuntime {
    override val language = "python"

    override fun interpreterArgs(scriptGuestPath: String): List<String> = listOf("python3", scriptGuestPath)

    override fun prelude(reqPath: String, resDir: String, callTimeoutSeconds: Long): String = """
import json, os, time, uuid, threading

class ToolCallError(RuntimeError):
    def __init__(self, tool_name, message):
        super().__init__(message)
        self.tool_name = tool_name

class _Tools:
    def __init__(self, req_path, res_dir):
        self._req = req_path
        self._res = res_dir
        self._lock = threading.Lock()
    def _invoke(self, name, arguments):
        call_id = "c_" + uuid.uuid4().hex
        with self._lock:
            with open(self._req, "a", encoding="utf-8") as f:
                f.write(json.dumps({"callId": call_id, "name": name,
                                    "arguments": json.dumps(arguments, ensure_ascii=False)}) + "\\n")
                f.flush()
        res_path = os.path.join(self._res, call_id + ".json")
        deadline = time.time() + ${callTimeoutSeconds}  # 与宿主单次调用超时一致（避免 pump 挂死后静默等待）
        while time.time() < deadline:
            if os.path.exists(res_path):
                with open(res_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                if data.get("error") is not None:
                    raise ToolCallError(name, str(data["error"]))
                return json.loads(data.get("value") or "null")
            time.sleep(0.02)
        raise RuntimeError("PTC 子调用超时未收到结果: " + name)
    def __getattr__(self, name):
        def meth(*args, **kwargs):
            # 对齐 Node 版 Proxy 语义：允许 `**kwargs`（文档主用例）、单 dict 位置参、或单个位置参
            # （单值情形 guest 直接透传，宿主校验时会立即 fail-fast，不会长时间卡死再报）
            if args and kwargs:
                raise TypeError(
                    f"tools.{name}: 不能同时使用位置参数和关键字参数，请二选一"
                )
            if len(args) == 1:
                payload = args[0]
            elif args:
                raise TypeError(
                    f"tools.{name}: 不支持多位置参数；请改用关键字 tools.{name}(key=value) "
                    f"或单个 dict tools.{name}(dict_obj)"
                )
            else:
                payload = kwargs
            return self._invoke(name, payload)
        return meth

tools = _Tools(${jsonLiteral(reqPath)}, ${jsonLiteral(resDir)})

async def _ptc_main():
""".trimIndent()

    override fun buildProgram(
        userCode: String,
        reqPath: String,
        resDir: String,
        callTimeoutSeconds: Long
    ): String {
        val body = userCode.lines().joinToString("\n") { "    $it" }
        // 结构化信封：成功 {"__ptc_ok":true,"value":...}，失败 {"__ptc_ok":false,"error":...}。
        // stdout 仍是日志；宿主不再靠 stdout 文本判定成败。
        return "${prelude(reqPath, resDir, callTimeoutSeconds)}\n$body\n\n" +
            "import asyncio, json as _json, os as _os\n" +
            "_res_path = _os.path.join(_os.path.dirname(__file__), \"result.json\")\n" +
            "try:\n" +
            "    _ret = asyncio.run(_ptc_main())\n" +
            "    _payload = {\"__ptc_ok\": True, \"value\": _ret}\n" +
            "except BaseException as _e:\n" +
            "    _payload = {\"__ptc_ok\": False, \"error\": str(_e)}\n" +
            "with open(_res_path, \"w\", encoding=\"utf-8\") as _f:\n" +
            "    _f.write(_json.dumps(_payload, ensure_ascii=False, default=str))\n"
    }
}

/** Node 运行时。 */
object NodeRuntime : CodeRuntime {
    override val language = "node"

    override fun interpreterArgs(scriptGuestPath: String): List<String> = listOf("node", scriptGuestPath)

    override fun prelude(reqPath: String, resDir: String, callTimeoutSeconds: Long): String = """
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

class ToolCallError extends Error {
  constructor(toolName, message) { super(message); this.name = "ToolCallError"; this.tool_name = toolName; }
}

class _Tools {
  constructor(reqPath, resDir) { this.req = reqPath; this.res = resDir; }
  async _invoke(name, args) {
    const callId = 'c_' + crypto.randomBytes(8).toString('hex');
    fs.appendFileSync(this.req, JSON.stringify({callId, name, arguments: JSON.stringify(args)}) + '\\n');
    const resPath = path.join(this.res, callId + '.json');
    const deadline = Date.now() + ${callTimeoutSeconds}000; // 与宿主单次调用超时一致
    while (Date.now() < deadline) {
      if (fs.existsSync(resPath)) {
        const data = JSON.parse(fs.readFileSync(resPath, 'utf-8'));
        if (data.error != null) throw new ToolCallError(name, String(data.error));
        return JSON.parse(data.value != null ? data.value : 'null');
      }
      await new Promise(r => setTimeout(r, 20));
    }
    throw new Error('PTC 子调用超时未收到结果: ' + name);
  }
}
const tools = new Proxy(new _Tools(${jsonLiteral(reqPath)}, ${jsonLiteral(resDir)}), {
  get(t, prop) {
    if (typeof prop === 'string' && !(prop in t) && prop !== 'then') {
      return (...a) => t._invoke(prop, a.length === 1 ? a[0] : a);
    }
    return t[prop];
  }
});

async function _ptc_main() {
""".trimIndent()

    override fun buildProgram(
        userCode: String,
        reqPath: String,
        resDir: String,
        callTimeoutSeconds: Long
    ): String =
        "${prelude(reqPath, resDir, callTimeoutSeconds)}\n$userCode\n}\n" +
            "_ptc_main().then(ret => {\n" +
            "  const fs2 = require('fs');\n" +
            "  const safe = (ret === undefined) ? null : ret;\n" +
            "  fs2.writeFileSync(__dirname + '/result.json', JSON.stringify({\"__ptc_ok\": true, \"value\": safe}, (k, v) => typeof v === 'function' ? undefined : v));\n" +
            "}).catch(e => { const fs2 = require('fs'); fs2.writeFileSync(__dirname + '/result.json', JSON.stringify({\"__ptc_ok\": false, \"error\": String(e)})); console.error(e); });\n"
}

/** 把 Kotlin 字符串安全写成 JSON 字符串字面量（用于注入 prelude 中的 IPC 路径常量）。 */
private fun jsonLiteral(s: String): String = JsonPrimitive(s).toString()
