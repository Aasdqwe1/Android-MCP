package com.mcp

import android.content.Context
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import com.mcp.wechat.OpenClawWeChat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 微信 (OpenClaw Weixin / openclaw-weixin) 渠道连接工具集。
 *
 *  双路径：
 *  - 【路径 B 原生直连】：不用 Node.js，Kotlin 直接调腾讯 iLink HTTP JSON API。
 *    适用于想让 Agent 在 App 内直接收发微信消息、不想多跑一个 Gateway 的场景。
 *  - 【路径 A 官方插件 (Debian PRoot)】：Debian 里装 node + openclaw CLI +
 *    @tencent-weixin/openclaw-weixin 插件，扫码登录后跑 openclaw gateway。
 *    完全复用官方文档步骤，兼容性最强。
 *
 * 两个路径的账号凭证格式一致，可随时切换。
 */
fun wechatTools(context: Context): List<ToolDef> = listOf(
    // 路径 B：原生直连
    weixinStatusTool(context),
    weixinQrStartTool(context),
    weixinQrPollTool(context),
    weixinGetUpdatesTool(context),
    weixinSendTextTool(context),
    // 路径 A：Debian PRoot 官方插件（对应官方文档 CLI）
    weixinProotInstallTool(context),
    weixinProotLoginTool(context),
    weixinProotStatusTool(context),
    weixinProotPairingTool(context),
    weixinProotGatewayTool(context),
    weixinProotLogsTool(context),
    weixinTroubleshootTool(context)
)

// ═══════════════════════════════════════════════════════════════════
//  路径 B：iLink 原生直连（无 Node.js）
// ═══════════════════════════════════════════════════════════════════

private fun weixinStatusTool(context: Context): ToolDef =
    tool("openclaw_weixin_status") {
        description = "查询微信连接状态：已登录账号数、默认账号、iLink base_url。"
        handler {
            OpenClawWeChat.statusSnapshot(context).toString()
        }
    }

private fun weixinQrStartTool(context: Context): ToolDef =
    tool("openclaw_weixin_qr_start") {
        description = "【微信原生登录】启动二维码登录，返回 qr_url（扫码用 URL，需前端用 ZXing 渲染成二维码图片）和 qrcode（后续 openclaw_weixin_qr_poll 用的标识符）。"
        handler {
            withContext(Dispatchers.IO) {
                runCatching {
                    val (qrUrl, qrcode) = OpenClawWeChat.loginQrStart(context)
                    """{"qr_url":${imgJson(qrUrl)},"qrcode":${uuidJson(qrcode)}}"""
                }.getOrElse {
                    """{"error":"${it.message?.sanitize()}"}"""
                }
            }
        }
    }

private fun weixinQrPollTool(context: Context): ToolDef =
    tool("openclaw_weixin_qr_poll") {
        description = "【微信原生登录】轮询二维码状态（每 2s 调用一次）。status: wait待扫 / scaned已扫待确认 / confirmed已确认（成功自动保存账号）/ expired已过期。"
        string("qrcode") { description = "openclaw_weixin_qr_start 返回的 qrcode" }
        integer("max_wait_seconds") {
            description = "最多等待秒数（默认 120，最大 180）"
            required = false
        }
        handler { args ->
            val qrcode = requireStr(args, "qrcode", "openclaw_weixin_qr_poll")
            val wait = (args["max_wait_seconds"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: 120).coerceIn(5, 180)
            withContext(Dispatchers.IO) {
                runCatching {
                    val acc = OpenClawWeChat.loginQrAwait(context, qrcode, wait)
                    if (acc != null)
                        """{"status":"confirmed","account_id":${acc.accountId.j},"saved":true}"""
                    else
                        """{"status":"expired","result":"expired_or_timeout"}"""
                }.getOrElse {
                    """{"error":"${it.message?.sanitize()}"}"""
                }
            }
        }
    }

private fun weixinGetUpdatesTool(context: Context): ToolDef =
    tool("openclaw_weixin_get_updates") {
        description = "【微信原生】长轮询获取新消息（一次最多等 ~35s）。返回 msgs[] + get_updates_buf（已自动持久化，下次不用手动传）。每条消息含 from_user_id / to_user_id / context_token / item_list（text_item.text 是文本）。"
        string("account_id") {
            description = "账号 ID（留空用默认账号）"
            required = false
        }
        handler { args ->
            val acc = args["account_id"]?.jsonPrimitive?.content?.let { id ->
                OpenClawWeChat.listAccounts(context).firstOrNull { it.accountId == id }
            } ?: OpenClawWeChat.defaultAccount(context)
            if (acc == null) return@handler """{"error":"未登录微信账号，请先 openclaw_weixin_qr_start"}"""
            withContext(Dispatchers.IO) {
                runCatching {
                    val r = OpenClawWeChat.getUpdates(context, acc)
                    val msgs = r.msgs.orEmpty().map { m ->
                        buildJsonObject {
                            m.seq?.let { put("seq", it) }
                            m.message_id?.let { put("message_id", it) }
                            m.from_user_id?.let { put("from_user_id", it) }
                            m.to_user_id?.let { put("to_user_id", it) }
                            m.create_time_ms?.let { put("create_time_ms", it) }
                            m.session_id?.let { put("session_id", it) }
                            m.message_type?.let { put("message_type", it) }
                            m.message_state?.let { put("message_state", it) }
                            m.context_token?.let { put("context_token", it) }
                            m.plainText()?.let { put("text", it) }
                        }.toString()
                    }
                    """{"ret":${r.ret ?: -1},"errcode":${r.errcode ?: 0},"errmsg":${r.errmsg.j},"msgs":[${msgs.joinToString(",")}],"next_timeout_ms":${r.longpolling_timeout_ms ?: 0}}"""
                }.getOrElse { """{"error":"${it.message?.sanitize()}"}""" }
            }
        }
    }

private fun weixinSendTextTool(context: Context): ToolDef =
    tool("openclaw_weixin_send_text") {
        description = "【微信原生】给指定用户发一条纯文本消息。必须传入入站消息里的 to_user_id（注意方向反转：回复时是 from→to 交换）和 context_token。"
        string("to_user_id") { description = "目标用户 ID（来自 getUpdates 返回的 from_user_id）" }
        string("text") {
            description = "要发送的文本内容"
            multiLine = true
        }
        string("context_token") {
            description = "会话上下文令牌（必须回传 getUpdates 返回的 context_token）"
            required = false
        }
        string("account_id") {
            description = "发送所用的微信账号（留空=默认）"
            required = false
        }
        handler { args ->
            val acc = args["account_id"]?.jsonPrimitive?.content?.let { id ->
                OpenClawWeChat.listAccounts(context).firstOrNull { it.accountId == id }
            } ?: OpenClawWeChat.defaultAccount(context)
            if (acc == null) return@handler """{"error":"未登录微信账号"}"""
            val to = requireStr(args, "to_user_id", "openclaw_weixin_send_text")
            val text = requireStr(args, "text", "openclaw_weixin_send_text")
            val ctx = args["context_token"]?.jsonPrimitive?.content
            withContext(Dispatchers.IO) {
                runCatching {
                    val r = OpenClawWeChat.sendText(context, acc, to, text, ctx)
                    """{"ret":${r.ret ?: -1},"errmsg":${r.errmsg.j}}"""
                }.getOrElse { """{"error":"${it.message?.sanitize()}"}""" }
            }
        }
    }

// ═══════════════════════════════════════════════════════════════════
//  路径 A：Debian PRoot 官方插件（对应官方文档 CLI）
// ═══════════════════════════════════════════════════════════════════

private fun weixinProotInstallTool(context: Context): ToolDef =
    tool("openclaw_weixin_proot_install") {
        description =
            "【Debian PRoot 官方插件，对应文档第 1 步】在 Debian 中安装 Node.js、OpenClaw CLI 和 @tencent-weixin/openclaw-weixin 插件。" +
            "首次执行时间较长（下载 ~200MB）。force_legacy=true 安装 legacy 版本线（对应 OpenClaw <2026.3.22）。"
        boolean("force_legacy") {
            description = "强制安装 legacy 版本线（1.x，对应 OpenClaw <2026.3.22）"
            required = false
        }
        handler { args ->
            val legacy = args["force_legacy"]?.jsonPrimitive?.let { p ->
                if (p.isString) p.content == "true"
                else runCatching { p.content.toBooleanStrictOrNull() ?: false }.getOrDefault(false)
            } ?: false
            bash(context, OpenClawWeChat.prootInstallScript(forceLegacy = legacy), tag = "wx-install")
        }
    }

private fun weixinProotLoginTool(context: Context): ToolDef =
    tool("openclaw_weixin_proot_login") {
        description = "【Debian PRoot 官方插件，对应文档第 3 步】执行 `openclaw channels login --channel openclaw-weixin` 启动二维码登录。注意该命令交互式输出二维码，用 run_bash 直接执行同样可达。"
        handler { bash(context, OpenClawWeChat.prootLoginCmd(), tag = "wx-login", tty = true) }
    }

private fun weixinProotStatusTool(context: Context): ToolDef =
    tool("openclaw_weixin_proot_status") {
        description = "【Debian PRoot 官方插件】执行 openclaw plugins list + channels status --probe + --version 诊断官方插件与 Gateway 健康状态。"
        handler { bash(context, OpenClawWeChat.prootStatusCmd(), tag = "wx-status") }
    }

private fun weixinProotPairingTool(context: Context): ToolDef =
    tool("openclaw_weixin_proot_pairing") {
        description =
            "【Debian PRoot 官方插件，访问控制】查看配对列表或批准新发送者（对应文档 openclaw pairing list/approve）。"
        string("action") {
            description = "list 列出待批准/已批准；approve 批准指定 code"
            enumValues = listOf("list", "approve")
        }
        string("code") {
            description = "待批准的 code（action=approve 时必填）"
            required = false
        }
        handler { args ->
            when (args["action"]?.jsonPrimitive?.content) {
                "approve" -> {
                    val code = requireStr(args, "code", "openclaw_weixin_proot_pairing(approve)")
                    bash(context, OpenClawWeChat.prootPairingApproveCmd(code), tag = "wx-approve")
                }
                else -> bash(context, OpenClawWeChat.prootPairingListCmd(), tag = "wx-pairing")
            }
        }
    }

private fun weixinProotGatewayTool(context: Context): ToolDef =
    tool("openclaw_weixin_proot_gateway") {
        description = "【Debian PRoot 官方插件，对应文档第 4 步】Gateway 生命周期管理：restart 重启（先启用插件再以常驻 PRoot 进程前台重启，解决渠道已安装但无法连接）；start 前台常驻启动（日志 tee 到 ~/.openclaw/gateway.log）；disable 禁用插件并停止。"
        string("action") {
            enumValues = listOf("restart", "start", "disable")
        }
        handler { args ->
            withContext(Dispatchers.IO) {
                runCatching {
                    when (args["action"]?.jsonPrimitive?.content) {
                        "disable" -> {
                            OpenClawWeChat.disableGateway(context)
                            """{"action":"disable","result":"stopped"}"""
                        }
                        "start" -> {
                            val id = OpenClawWeChat.startGateway(context)
                            """{"action":"start","task_id":${id.j},"running":${OpenClawWeChat.gatewayRunning()},"result":"started"}"""
                        }
                        else -> {
                            val id = OpenClawWeChat.restartGateway(context)
                            """{"action":"restart","task_id":${id.j},"running":${OpenClawWeChat.gatewayRunning()},"result":"restarted"}"""
                        }
                    }
                }.getOrElse {
                    """{"error":"${it.message?.sanitize()}"}"""
                }
            }
        }
    }

private fun weixinProotLogsTool(context: Context): ToolDef =
    tool("openclaw_weixin_logs") {
        description = "【故障排查】查看 Gateway 日志 / openclaw-weixin 状态目录。tail=200 默认输出最近 200 行；传入 path 可以 cat 任意 ~/.openclaw/ 下的文件（如 openclaw.json、openclaw-weixin/accounts.json）。"
        integer("tail") { required = false; description = "输出最后多少行，默认 200" }
        string("path") { required = false; description = "相对 ~/.openclaw 的子路径；如 openclaw.json / openclaw-weixin/accounts.json / gateway.log（默认）" }
        handler { args ->
            val tail = args["tail"]?.jsonPrimitive?.content?.toIntOrNull() ?: 200
            val rel = args["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "gateway.log"
            val safe = rel.filter { !it.isWhitespace() && it != '/' && it != '\'' && it != '"' && it != ';' && it != '&' && it != '|' && it != '`' }
            if (safe.isEmpty() || safe.startsWith("..") || safe.startsWith("/"))
                return@handler """{"error":"非法的 path：$rel"}"""
            bash(
                context,
                """
STATE_DIR="${'$'}{HOME:-/root}/.openclaw"
TARGET="${'$'}STATE_DIR/$safe"
if [ ! -e "${'$'}TARGET" ]; then
  echo "文件不存在：${'$'}TARGET"
  echo "已存在的文件/目录（${'$'}STATE_DIR）："
  ls -la "${'$'}STATE_DIR" 2>/dev/null || echo "(STATE_DIR 不存在)"
  exit 0
fi
echo "==== ${'$'}TARGET ===="
if command -v tail >/dev/null 2>&1; then
  tail -n $tail "${'$'}TARGET" 2>&1 || cat "${'$'}TARGET"
else
  cat "${'$'}TARGET" | awk "NR>(NR>0?$tail:0)"
fi
                """.trimIndent(),
                tag = "wx-logs"
            )
        }
    }

private fun weixinTroubleshootTool(context: Context): ToolDef =
    tool("openclaw_weixin_troubleshoot") {
        description = "【故障排查，一键】按文档故障排查章节顺序执行：plugins list → channels status --probe → openclaw --version → npm view 插件版本 → 如版本过旧强制重装，最后重启 gateway。输出完整诊断报告。"
        handler {
            bash(context, """
set +e
echo "=== 1. openclaw plugins list ==="
openclaw plugins list 2>&1
echo ""
echo "=== 2. openclaw channels status --probe ==="
openclaw channels status --probe 2>&1
echo ""
echo "=== 3. openclaw --version ==="
openclaw --version 2>&1
echo ""
echo "=== 4. npm view @tencent-weixin/openclaw-weixin version ==="
command -v npm >/dev/null 2>&1 && npm view @tencent-weixin/openclaw-weixin version 2>&1 || echo "(npm 不可用，跳过)"
echo ""
echo "=== 5. ~/.openclaw/openclaw.json plugins 段落 ==="
CFG="${'$'}{HOME:-/root}/.openclaw/openclaw.json"
[ -f "${'$'}CFG" ] && (command -v grep >/dev/null 2>&1 && grep -A3 -B1 '"openclaw-weixin"' "${'$'}CFG" 2>&1 || cat "${'$'}CFG") || echo "(无 openclaw.json)"
echo ""
echo "=== 6. 账号/配置目录存在性 ==="
WXDIR="${'$'}{HOME:-/root}/.openclaw/openclaw-weixin"
if [ -d "${'$'}WXDIR" ]; then
  echo "目录 ${'$'}WXDIR："
  ls -la "${'$'}WXDIR" 2>&1
else
  echo "微信插件状态目录尚未创建（${'$'}WXDIR 不存在），登录后会自动生成。"
fi
echo ""
echo "=== 7. Gateway 日志最后 80 行（若有） ==="
LOG="${'$'}{HOME:-/root}/.openclaw/gateway.log"
[ -f "${'$'}LOG" ] && (command -v tail >/dev/null 2>&1 && tail -n 80 "${'$'}LOG" || cat "${'$'}LOG") || echo "(gateway.log 不存在，Gateway 尚未启动过)"
""", tag = "wx-diag")
        }
    }

// ═══════════════════════════════════════════════════════════════════
//  内部帮助：跑 bash / 字符串 JSON 化
// ═══════════════════════════════════════════════════════════════════

private suspend fun bash(
    context: Context,
    script: String,
    tag: String,
    tty: Boolean = false
): String {
    // 走 executeBash，复用 Debian PRoot 执行逻辑（与 run_bash 工具同路径）
    return runCatching {
        executeBash(context, script)
    }.getOrElse {
        "微信工具 bash 执行失败（${it.message}），请改用 run_bash 直接执行：\n---\n$script"
    }
}

private fun String.sanitize(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

private val String?.j: String
    get() = if (this == null) "null"
    else "\"${this.sanitize()}\""

private fun imgJson(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
private fun uuidJson(s: String) = "\"${s.sanitize()}\""

private fun requireStr(args: JsonObject, k: String, toolName: String): String {
    val raw = args[k]
    val s = raw?.jsonPrimitive?.content
    if (s.isNullOrBlank()) throw IllegalArgumentException("$toolName 缺少必填参数 $k")
    return s
}

// 兼容旧写法：Map<String, *>.requireStr 回退到从 Map 找 JsonPrimitive/String
private fun Map<String, *>.requireStr(k: String, toolName: String): String =
    this[k]?.let { v ->
        when (v) {
            is JsonPrimitive -> v.content
            is JsonObject -> v[k]?.jsonPrimitive?.content
            is String -> v
            else -> null
        }
    } ?: throw IllegalArgumentException("$toolName 缺少必填参数 $k")
