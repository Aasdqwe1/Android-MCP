package com.mcp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.mcp.deepseek.AuthPrefs
import com.mcp.llm.WebAutomationConfig

/**
 * Web 自动化配置「文件夹」列表适配器 —— 与 [OpenAIProfileAdapter] 同构。
 *
 * - 单击文件夹行：设为当前生效配置（active），下一次请求即用新选择器。
 * - 长按文件夹行：展开/收起该配置的内联编辑器。
 * - 左右滑动删除由 SettingsFragment 的 ItemTouchHelper 处理。
 *
 * ## 切换档案为什么不需要重建会话
 *
 * Web 自动化下「会话」的真相在**网页**上（Tabs 按 sessionListSelector 去站点读列表），
 * 本地 JSON 只是记录。选择器是每请求实时读的（[AuthPrefs.getWebAutomationConfig]），
 * 因此切换档案后下一次发消息就用新选择器，无需拆毁会话列表。
 * 这一点与 OpenAI 档案一致，理由也一致：配置不进入会话状态。
 *
 * 唯一的例外是 [WebAutomationConfig.desktopMode]：它是**全局** UA 开关，切档后
 * 需要让 MainActivity 重新应用 UA。故这里只在切换时通知一次（见 [onActivated]）。
 */
class WebAutomationProfileAdapter(
    private val context: Context,
    private val auth: AuthPrefs,
    /** 切换激活档案后的回调（用于重应用桌面 UA、刷新网页会话列表）。 */
    private val onActivated: () -> Unit,
) : RecyclerView.Adapter<WebAutomationProfileAdapter.VH>() {

    var profiles: MutableList<WebAutomationConfig> = mutableListOf()
        private set
    var activeId: String? = null
        private set
    var expandedId: String? = null
        private set

    val currentList: List<WebAutomationConfig> get() = profiles

    fun submit(list: MutableList<WebAutomationConfig>, activeId: String?) {
        this.profiles = list
        this.activeId = activeId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = profiles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wa_profile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = profiles[position]
        holder.bind(p, p.id == activeId, p.id == expandedId)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val header = itemView.findViewById<View>(R.id.wa_header)
        private val tvName = itemView.findViewById<TextView>(R.id.wa_name)
        private val tvSub = itemView.findViewById<TextView>(R.id.wa_subtitle)
        private val badge = itemView.findViewById<TextView>(R.id.wa_active_badge)
        private val editor = itemView.findViewById<View>(R.id.wa_editor)
        private val etName = itemView.findViewById<EditText>(R.id.wa_et_name)
        private val etUrl = itemView.findViewById<EditText>(R.id.wa_et_url)
        private val etNewChat = itemView.findViewById<EditText>(R.id.wa_et_new_chat)
        private val etInput = itemView.findViewById<EditText>(R.id.wa_et_input)
        private val etSend = itemView.findViewById<EditText>(R.id.wa_et_send)
        private val etContent = itemView.findViewById<EditText>(R.id.wa_et_content)
        private val etThinking = itemView.findViewById<EditText>(R.id.wa_et_thinking)
        private val etStop = itemView.findViewById<EditText>(R.id.wa_et_stop)
        private val etSession = itemView.findViewById<EditText>(R.id.wa_et_session)
        private val swEnter = itemView.findViewById<CompoundButton>(R.id.wa_sw_enter)
        private val swDesktop = itemView.findViewById<CompoundButton>(R.id.wa_sw_desktop)
        private val btnSave = itemView.findViewById<View>(R.id.wa_btn_save)

        fun bind(p: WebAutomationConfig, isActive: Boolean, isExpanded: Boolean) {
            tvName.text = p.name.ifBlank { "未命名站点" }
            // 副标题给「地址 + 可用性」：地址让用户分辨站点，可用性提示还缺哪个必填项。
            // 只显示地址时，一个选择器没填全的档案看起来和可用的一模一样，
            // 用户切过去才发现发不出消息，却不知道该补什么。
            tvSub.text = buildString {
                append(p.siteUrl.ifBlank { "未设置站点地址" });
                if (!p.isUsable) append("（缺输入框或回答容器）")
            }
            badge.visibility = if (isActive) View.VISIBLE else View.GONE
            editor.visibility = if (isExpanded) View.VISIBLE else View.GONE

            if (isExpanded) {
                etName.setText(p.name)
                etUrl.setText(p.siteUrl)
                etNewChat.setText(p.newChatSelector)
                etInput.setText(p.inputSelector)
                etSend.setText(p.sendSelector)
                etContent.setText(p.contentSelector)
                etThinking.setText(p.thinkingSelector)
                etStop.setText(p.stopSelector)
                etSession.setText(p.sessionListSelector)
                swEnter.isChecked = p.useEnterToSend
                swDesktop.isChecked = p.desktopMode
            }
        }

        init {
            header.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val p = profiles[pos]
                auth.setActiveWebAutomationProfileId(p.id)
                activeId = p.id
                notifyItemRangeChanged(0, profiles.size)
                onActivated()
            }
            header.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                val p = profiles[pos]
                expandedId = if (expandedId == p.id) null else p.id
                notifyItemChanged(pos)
                true
            }
            btnSave.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val p = profiles[pos]
                val updated = p.copy(
                    name = etName.text?.toString()?.trim().orEmpty().ifBlank { p.name },
                    siteUrl = etUrl.text?.toString()?.trim().orEmpty(),
                    newChatSelector = etNewChat.text?.toString()?.trim().orEmpty(),
                    inputSelector = etInput.text?.toString()?.trim().orEmpty(),
                    sendSelector = etSend.text?.toString()?.trim().orEmpty(),
                    contentSelector = etContent.text?.toString()?.trim().orEmpty(),
                    thinkingSelector = etThinking.text?.toString()?.trim().orEmpty(),
                    stopSelector = etStop.text?.toString()?.trim().orEmpty(),
                    sessionListSelector = etSession.text?.toString()?.trim().orEmpty(),
                    useEnterToSend = swEnter.isChecked,
                    desktopMode = swDesktop.isChecked,
                )
                auth.upsertWebAutomationProfile(updated)
                profiles[pos] = updated
                notifyItemChanged(pos)
                // 改的可能正是激活档（地址/选择器/UA），让外部重应用一次
                onActivated()
            }
        }
    }
}
