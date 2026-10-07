package com.mcp.chat

/**
 * 消息 id 命名空间：**本地 id ↔ 服务端 id**。
 *
 * 背景：DeepSeek 逆向协议的服务端为每个会话从 1 开始编号消息。上下文压缩触发
 * **session rotation**（新建服务端会话）后，消息 id 会**重新从 1 开始**；而本地会话窗口里
 * 仍挂着上一代的气泡（id 1..N）。两代 id 混用会撞车——`ChatBridge.truncateHistoryAt` 与
 * `LocalStore.deleteMessage` 都是按 `indexOfFirst { it.id == messageId }` 定位，
 * 撞车时会命中**旧**的那条消息，表现就是「编辑一条新消息却删掉大半段历史」。
 *
 * 方案：本地维护一个**只增不减的偏移** offset，本地 id = 服务端 id + offset。
 * - offset = 0 时行为与改造前完全一致（未轮转过的会话、OpenAI 后端的本地合成 id）；
 * - 每次轮转把 offset 推进到「上一代的最大服务端 id」，新会话的 1/2/3… 映射为
 *   offset+1/offset+2/…，与已有本地 id 天然不重叠；
 * - **前端只看到本地 id**；发往服务端的 `parent_message_id`、停止用的 message_id
 *   始终是服务端 id（内部状态里保存的也是服务端 id）。
 *
 * 偏移随会话一起落盘（`LocalStore.SessionRotation`），否则重启后前端气泡 id 与
 * 本地转换基准不一致，锚点会被算错。
 */
object MessageIdSpace {

    /** 服务端 id → 本地 id。非数字（OpenAI 本地合成 id / 空串）原样返回。 */
    fun toLocal(raw: String?, offset: Long): String? {
        if (raw == null) return null
        if (offset == 0L) return raw
        val n = raw.toLongOrNull() ?: return raw
        return (n + offset).toString()
    }

    /**
     * 本地 id → 服务端 id。
     * 偏移为 0 时原样返回（含非数字）；偏移 > 0 时非数字或落在偏移内的值视为无效锚点 → null，
     * 避免把一个本地合成 id 当成服务端 id 发出去。
     */
    fun toRaw(local: String?, offset: Long): String? {
        if (local == null) return null
        if (offset == 0L) return local
        val n = local.toLongOrNull() ?: return null
        val raw = n - offset
        return if (raw > 0) raw.toString() else null
    }

    /**
     * 轮转后推进偏移：把「上一代最后一个服务端 id」加到当前偏移上。
     * 未知/非法时保持不变（宁可偏移不前进，也不回退——回退必然撞车）。
     */
    fun advance(offset: Long, lastRawId: String?): Long {
        val last = lastRawId?.toLongOrNull()?.takeIf { it > 0 } ?: return offset
        return offset + last
    }

    /**
     * 是否为合法的服务端消息 id：必须解析为 1..u32 的正整数。
     *
     * DeepSeek 逆向协议的 message_id 是 u32，而「带 id 的 id」不止消息——文件是
     * "file-<uuid>"。把脏 id 当成续聊锚点会同时炸三处：请求被归一化成 null（服务端开新分支、
     * 历史全丢）、前端 parseInt 得 NaN（消息树打散）、持久化后冷启动继续用错锚点。
     * 所有锚点写入点都应先过这一关。
     */
    fun isValidServerId(id: String?): Boolean {
        val n = id?.toLongOrNull() ?: return false
        return n in 1L..UInt.MAX_VALUE.toLong()
    }
}
