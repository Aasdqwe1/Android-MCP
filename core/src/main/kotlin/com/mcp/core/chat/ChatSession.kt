package com.mcp.core.chat

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 可由不同 LLM 后端和本地缓存共享的会话摘要。
 * 后端适配层负责把远端 DTO 映射为该模型，数据层不依赖具体协议。
 */
@Serializable
data class ChatSession(
    val id: String,
    val title: String = "",
    val pinned: Boolean = false,
    @SerialName("updated_at")
    val updatedAt: Double = 0.0,
    @SerialName("current_message_id")
    val currentMessageId: String? = null,
    // 会话级上下文窗口快照（创建/打开时由 active OpenAI profile 锁定）。
    // 截断/压缩阈值读此而非全局 active profile，避免切换 OpenAI 配置档案改变阈值
    // 导致已加载历史被按新（更小）窗口重砍（表现为「切换档案后消息被吞」）。
    // 旧会话缺字段→默认空/0，ChatBridge 在 openWindow 时锁定为当时 active profile 的窗口（进程内，不落盘）。
    @SerialName("model")
    val model: String = "",
    @SerialName("max_input")
    val maxInput: Int = 0,
    @SerialName("context_window")
    val contextWindow: Int = 0,
    // 会话级预设 id（per-session agent 组合）。null 与 "full" 等价于全量模式。
    // 切换会话时由 Tabs.openWindow 同步到 PresetRuntime，保证极简/PTC 模式不跨会话泄漏。
    @SerialName("preset_id")
    val presetId: String? = null,
    /**
     * Web 自动化专用：本地会话与「网页站点会话」的稳定绑定键。
     *
     * 为什么需要：站点侧边栏按活跃度重排（当前会话上浮、新消息置顶），旧实现把网页列表**下标**
     * 直接当本地 id（web:0/1/2…），下标一漂移，消息就写进别的会话文件——历史错乱。
     * 现在本地 id 用稳定 UUID，绑定改靠此键：
     *  - 优先站点条目的稳定标识（data-id / href 里的 cid 等），见 WebBrowser 的 keyOf；
     *  - 取不到稳定 id 时回退用会话名（title）；
     *  - 新建会话站点还没命名 → 为空，首条消息后站点命名再回填。
     */
    @SerialName("site_key")
    val siteKey: String? = null
)
