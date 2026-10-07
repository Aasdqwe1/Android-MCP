package com.mcp

import android.content.Context
import com.mcp.toolbox.ToolDef
import com.mcp.toolbox.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive

// ─────────────────────────────────────────────────────────────
//  沙盒宿主挂载授权工具
//
//  沙盒默认与宿主完全隔离，下列工具是「把宿主目录暴露给沙盒」的唯一入口。
//  授权必须由用户经系统目录选择器显式完成，模型无法自行扩大暴露面。
// ─────────────────────────────────────────────────────────────

private fun mountsJson(): String {
    val roots = SandboxMounts.all()
    val arr = roots.joinToString(",") { "\"$it\"" }
    return "[$arr]"
}

/**
 * 授权一个宿主目录暴露给 PRoot 沙盒（系统目录选择器）。
 * 授权后 guest 内可用与宿主一致的绝对路径访问该目录。
 */
fun sandboxMountAllow(context: Context): ToolDef = tool("sandbox_mount_allow") {
    description = "授权一个宿主目录暴露给 PRoot 沙盒。沙盒默认与宿主完全隔离，只有经此工具显式授权的目录才能在沙盒内访问（guest 内路径与宿主一致）。需用户在弹出的系统目录选择器中确认。"
    string("path") {
        description = "期望暴露的宿主目录绝对路径（如 /storage/emulated/0/Work）。作为选择器结果的校验/回退，不填则完全以选择结果为准。"
        required = false
    }
    handler { args ->
        withContext(Dispatchers.Main) {
            try {
                val uri = SafManager.requestDirectoryAccess()
                if (uri == null) {
                    "{\"ok\":false,\"message\":\"用户取消了目录选择\"}"
                } else {
                    val resolved = SandboxMounts.resolveTreeUri(uri)
                    val suggested = args["path"]?.jsonPrimitive?.content
                    val path = resolved ?: suggested
                    if (path.isNullOrBlank()) {
                        "{\"error\":\"无法从选择结果解析出文件系统路径，请改用 path 参数显式指定目录\"}"
                    } else {
                        SandboxMounts.allow(path)
                        "{\"ok\":true,\"path\":\"$path\",\"sandbox_mounts\":" + mountsJson() + "}"
                    }
                }
            } catch (e: Exception) {
                "{\"error\":\"启动目录选择器失败: ${e.message}\"}"
            }
        }
    }
}

/** 列出已授权暴露给沙盒的宿主目录。 */
fun sandboxMountList(context: Context): ToolDef = tool("sandbox_mount_list") {
    description = "列出已授权暴露给 PRoot 沙盒的宿主目录。空列表表示沙盒与宿主完全隔离（默认状态）。"
    handler {
        withContext(Dispatchers.IO) {
            val roots = SandboxMounts.all()
            if (roots.isEmpty()) {
                "{\"mounts\":[],\"message\":\"沙盒当前未暴露任何宿主目录（默认完全隔离）。使用 sandbox_mount_allow 授权。\"}"
            } else {
                "{\"mounts\":" + mountsJson() + "}"
            }
        }
    }
}

/** 撤销一个沙盒宿主挂载授权。 */
fun sandboxMountRevoke(context: Context): ToolDef = tool("sandbox_mount_revoke") {
    description = "撤销一个已授权暴露给沙盒的宿主目录，恢复隔离。"
    string("path") { description = "要撤销的宿主目录路径（与 sandbox_mount_list 中显示的一致）" }
    handler { args ->
        withContext(Dispatchers.IO) {
            try {
                val path = args["path"]?.jsonPrimitive?.content
                if (path.isNullOrBlank()) {
                    "{\"error\":\"缺少 path 参数\"}"
                } else if (SandboxMounts.revoke(path)) {
                    "{\"ok\":true,\"message\":\"已撤销: $path\",\"sandbox_mounts\":" + mountsJson() + "}"
                } else {
                    "{\"error\":\"未找到该沙盒挂载授权: $path\"}"
                }
            } catch (e: Exception) {
                "{\"error\":\"撤销失败: ${e.message}\"}"
            }
        }
    }
}