package com.mcp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.mcp.deepseek.AuthPrefs
import com.mcp.deepseek.OpenAIProfileConfig

/**
 * OpenAI 配置「文件夹」列表适配器。
 *
 * - 单击文件夹行：设为后端当前配置（active）。
 * - 长按文件夹行：展开/收起该配置的内联编辑器（可修改）。
 * - 左右滑动删除由 SettingsFragment 的 ItemTouchHelper 处理。
 *
 * ## 切换 / 保存档案不重建会话
 *
 * 早先这里持有 `onChanged` 回调，切换或保存档案后一路触发
 * `SessionFragment.reloadSessionsForBackend()`——那是为**切换后端**
 * （DeepSeek 逆向 ↔ OpenAI 兼容，两套协议不兼容）设计的全量拆毁：
 * 清 currentSessionId、`clearAllSessionStates()` 抹掉所有会话的 history
 * 与 openAIMessages、`StreamTaskManager.clearAll()` 取消在跑的流、
 * 拆掉整个 WebView 池。
 *
 * 用在同协议的档案操作上既没必要也有害：
 * - 会话按**后端**隔离（`LocalStore.backendTag(OPENAI) == "oa"`），不按档案，
 *   所有档案共用同一份会话列表，重建后 `loadSessions()` 返回的还是同一批数据；
 * - 配置是**每请求实时读**的（[AuthPrefs.activeProfile] 每次读 SharedPreferences，
 *   [ChatBridge] 的 `compactThreshold()` / `LLMRequest` 也都是现算），
 *   切换档案在下一次请求即自动生效，不需要任何失效通知；
 * - `SessionState` 不缓存任何配置（只有消息缓冲与历史），无陈旧状态需要清理；
 * - 消息缓冲是标准 OpenAI JSON，跨档案（换模型 / base URL）协议兼容，
 *   换模型后压缩阈值变化也由 `compactOpenAIIfNeeded` 在下次请求自愈。
 *
 * 因此回调整个移除，档案操作只更新列表自身的 UI（激活徽标 / 内联编辑器）。
 */
class OpenAIProfileAdapter(
    private val context: android.content.Context,
    private val auth: AuthPrefs
) : RecyclerView.Adapter<OpenAIProfileAdapter.VH>() {

    var profiles: MutableList<OpenAIProfileConfig> = mutableListOf()
        private set
    var activeId: String? = null
    var expandedId: String? = null

    val currentList: List<OpenAIProfileConfig> get() = profiles

    fun submit(list: MutableList<OpenAIProfileConfig>, activeId: String?) {
        this.profiles = list
        this.activeId = activeId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = profiles.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_openai_profile, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = profiles[position]
        holder.bind(p, p.id == activeId, p.id == expandedId)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val header = itemView.findViewById<View>(R.id.op_header)
        private val tvModel = itemView.findViewById<android.widget.TextView>(R.id.op_model)
        private val tvSub = itemView.findViewById<android.widget.TextView>(R.id.op_subtitle)
        private val badge = itemView.findViewById<android.widget.TextView>(R.id.op_active_badge)
        private val editor = itemView.findViewById<View>(R.id.op_editor)
        private val etBase = itemView.findViewById<EditText>(R.id.op_et_base_url)
        private val etKey = itemView.findViewById<EditText>(R.id.op_et_api_key)
        private val etModel = itemView.findViewById<EditText>(R.id.op_et_model)
        private val etTemp = itemView.findViewById<EditText>(R.id.op_et_temperature)
        private val etMaxOut = itemView.findViewById<EditText>(R.id.op_et_max_output)
        private val etCtx = itemView.findViewById<EditText>(R.id.op_et_context)
        private val etMaxIn = itemView.findViewById<EditText>(R.id.op_et_max_input)
        private val swInsecure = itemView.findViewById<MaterialSwitch>(R.id.op_sw_insecure)
        private val rg = itemView.findViewById<MaterialButtonToggleGroup>(R.id.op_reasoning_group)
        private val btnSave = itemView.findViewById<View>(R.id.op_btn_save)

        fun bind(p: OpenAIProfileConfig, isActive: Boolean, isExpanded: Boolean) {
            tvModel.text = p.model.ifBlank { "(未命名)" }
            tvSub.text = p.baseUrl.ifBlank { "未设置 Base URL" }
            badge.visibility = if (isActive) View.VISIBLE else View.GONE
            editor.visibility = if (isExpanded) View.VISIBLE else View.GONE

            if (isExpanded) {
                etBase.setText(p.baseUrl)
                etKey.setText(p.apiKey)
                etModel.setText(p.model)
                etTemp.setText(p.temperature.toString())
                etMaxOut.setText(p.maxOutput.toString())
                etCtx.setText(p.contextWindow.toString())
                etMaxIn.setText(p.maxInput.toString())
                swInsecure.isChecked = p.insecureSkipVerify
                rg.check(
                    when (p.reasoningEffort) {
                        "low" -> R.id.op_reasoning_low
                        "medium" -> R.id.op_reasoning_medium
                        "xhigh" -> R.id.op_reasoning_xhigh
                        else -> R.id.op_reasoning_high
                    }
                )
            }
        }

        init {
            header.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                val p = profiles[pos]
                auth.setActiveOpenAIProfileId(p.id)
                activeId = p.id
                notifyItemRangeChanged(0, profiles.size)
                // 切换档案（换模型/窗口）后即时刷新聊天页 Token 看板与桌面 Web 徽标
                ChatBridge.notifyProfileConfigChanged()
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
                val temp = etTemp.text?.toString()?.toDoubleOrNull() ?: p.temperature
                val maxOut = etMaxOut.text?.toString()?.toIntOrNull() ?: p.maxOutput
                val ctx = (etCtx.text?.toString()?.toLongOrNull() ?: 0L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                val maxIn = (etMaxIn.text?.toString()?.toLongOrNull() ?: 0L)
                    .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                // 4 档：low / medium / high / xhigh（无 max，服务端枚举不认）
                val effort = when (rg.checkedButtonId) {
                    R.id.op_reasoning_low -> "low"
                    R.id.op_reasoning_medium -> "medium"
                    R.id.op_reasoning_xhigh -> "xhigh"
                    else -> "high"
                }
                val updated = p.copy(
                    baseUrl = etBase.text?.toString()?.trim().orEmpty(),
                    apiKey = etKey.text?.toString().orEmpty(),
                    model = (etModel.text?.toString()?.trim().orEmpty()).ifBlank { p.model },
                    temperature = temp.coerceIn(0.0, 2.0),
                    maxOutput = maxOut,
                    contextWindow = ctx,
                    maxInput = maxIn,
                    insecureSkipVerify = swInsecure.isChecked,
                    reasoningEffort = effort
                )
                auth.upsertOpenAIProfile(updated)
                profiles[pos] = updated
                notifyItemChanged(pos)
                // 编辑档案（可能改了激活档的模型/窗口）后即时重推 tokenConfig
                ChatBridge.notifyProfileConfigChanged()
            }
        }
    }
}
