package com.mcp

import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import com.mcp.browser.WebBrowser
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.materialswitch.MaterialSwitch
import com.mcp.deepseek.AuthPrefs
import com.mcp.core.llm.BackendType
import com.mcp.data.LocalStore
import com.mcp.serialization.McpJson
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mcp.wechat.OpenClawWeChat
import com.mcp.wechat.WeixinConversationGuide
import com.mcp.toolbox.Toolbox
import com.mcp.wechat.WeixinLlmResponder
import com.mcp.composition.ToolRuntimeHolder
import com.mcp.floatwin.BrowserFloatWindow
import android.widget.ImageView
import kotlin.concurrent.thread
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri

/**
 * 设置页：外观主题 + 重置。
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    /** 配置文件导入：单选一个 JSON 文件（SAF，无需存储权限）。 */
    private val configImportPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        importConfig(uri)
    }

    /**
         * LiteRT 模型导入：单选一个 .litertlm 文件（SAF，无需存储权限）。
         *
         * 必须作为 Fragment 字段注册——registerForActivityResult 只能在初始化阶段调用，
         * 放进点击回调会在 RESUMED 后注册而抛 IllegalStateException 崩溃。
         */
        private val litertModelPicker = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) importLiteRtModel(uri)
        }
    
        /** 导入完成后刷新 LiteRT 配置区字段（由 bindAppPage 注册）。 */
        private var refreshLiteRtFields: (() -> Unit)? = null
    
        /** 把 SAF 选中的 .litertlm 复制到应用私有目录，并持久化路径。 */
        private fun importLiteRtModel(uri: Uri) {
            val ctx = context ?: return
            runCatching {
                val dst = java.io.File(ctx.filesDir, "models").apply { mkdirs() }
                val out = java.io.File(dst, "model.litertlm")
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("无法读取所选文件")
                AuthPrefs(ctx).saveLiteRtModelPath(out.absolutePath)
                refreshLiteRtFields?.invoke()
                Toast.makeText(ctx, "模型已导入：${out.absolutePath}", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(ctx, "导入失败：${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindAppPage(view)
    }

    /** 读取 SAF 选中的配置文件并导入（覆盖写回）。 */
    private fun importConfig(uri: Uri) {
        val tvBackupStatus = view?.findViewById<TextView>(R.id.tvBackupStatus)
        tvBackupStatus?.text = "导入中…"
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val json = requireContext().contentResolver.openInputStream(uri)?.use {
                    it.readBytes().toString(Charsets.UTF_8)
                } ?: throw IllegalStateException("无法读取所选文件")
                ConfigBackup.importFromStream(requireContext(), json)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { summary ->
                    tvBackupStatus?.text = "$summary（部分设置需重启 App 生效）"
                    Toast.makeText(requireContext(), summary, Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    tvBackupStatus?.text = "导入失败：${e.message?.take(120)}"
                    Toast.makeText(requireContext(), "导入失败：${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** 软件设置页绑定（外观主题 + 重置）。 */
    /**
     * 新建一个会话并设为 API 转发默认归属会话。
     *
     * 与 Tabs.createNewSession 同源逻辑，但不受会话页 UI 约束：
     * 本地生成「tag-uuid」会话，写入对应命名空间。
     *
     * @param refresh 成功后的回调（刷新归属会话显示）
     */
    private fun createApiForwardSession(refresh: () -> Unit) {
        val ctx = requireContext().applicationContext
        val auth = AuthPrefs(requireContext())
        val backend = auth.getBackend()
        val tag = LocalStore.backendTag(backend)

        val id = tag + "-" + java.util.UUID.randomUUID()
        val session = com.mcp.core.chat.ChatSession(
            id = id, title = "", pinned = false, updatedAt = 0.0,
            model = auth.getOpenAIModel(),
            contextWindow = auth.getOpenAIContextWindow(),
            maxInput = auth.getOpenAIMaxInput(),
            presetId = com.mcp.preset.PresetRuntime.userPreferredId(ctx)
        )
        val list = LocalStore.loadSessions(ctx, tag).toMutableList()
        list.add(0, session)
        LocalStore.saveSessions(ctx, tag, list)
        ApiForwardPrefs.setSessionId(ctx, id)
        LogStore.i("WEB", "API 转发新建本地会话 id=" + id)
        Toast.makeText(requireContext(), "已新建并绑定会话", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun bindAppPage(page: View) {
        val activity = requireActivity() as MainActivity
        val prefs = activity.getSharedPreferences(MainActivity.PREF_TAB_MODE, android.content.Context.MODE_PRIVATE)
        // SettingsFragment 自身无 auth 字段（auth 属于 SessionFragment），此处按需构造；
        // AuthPrefs 基于同一份加密存储，写入对 ChatBridge / SessionFragment 可见。
        val auth = AuthPrefs(requireContext())

        // 工具调用去重开关：读当前值 + 切换时持久化
        val swToolDedup = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swToolDedup)
        swToolDedup.isChecked = auth.isToolDedupEnabled()
        swToolDedup.setOnCheckedChangeListener { _, checked -> auth.setToolDedupEnabled(checked) }

        // 工具调用协议风格：仅对**文本协议后端**（DeepSeek 逆向 / Web 自动化）有意义。
        // OpenAI 兼容走原生 tools 参数、LiteRT 由适配层自行处理——它们没有「正文里写
        // 调用」这回事，展示这个选项只会误导。判据与 ChatBridge.buildToolResult 同源
        // （com.mcp.core.llm.usesTextToolProtocol），不再各写一份。
        // 对全部预设生效（含极简与 PTC）——协议风格与预设正交，预设声明只作为
        // 「跟随预设」时的取值来源，不再有强制锁定的预设。
        // 控件引用在此声明（供 applyBackendVisibility 闭包捕获，统一控制显隐）；
        // 可见性不在本处直接设置，避免与后端切换时的刷新逻辑各写一份。
        val protocolStyleTitle = page.findViewById<android.widget.TextView>(R.id.tvProtocolStyleTitle)
        val protocolStyleHint = page.findViewById<android.widget.TextView>(R.id.tvProtocolStyleHint)
        val protocolStyleGroup = page.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.protocol_style_group)
        protocolStyleGroup.check(
            when (auth.getProtocolStylePref()) {
                "line" -> R.id.protocol_line
                "xml" -> R.id.protocol_xml
                else -> R.id.protocol_follow
            }
        )
        protocolStyleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val v = when (checkedId) {
                R.id.protocol_line -> "line"
                R.id.protocol_xml -> "xml"
                else -> ""
            }
            auth.setProtocolStylePref(v)
            // 立即生效：让预设重新应用（重注入提示词）
            com.mcp.preset.PresetRuntime.apply(
                requireContext(),
                com.mcp.composition.ToolRuntimeHolder.get(requireContext(), auth),
                com.mcp.preset.PresetRuntime.current,
            )
            // 已建立的会话首次解析后会与全局解耦，需显式广播，否则旧会话仍按老风格收发
            com.mcp.ChatBridge.notifyProtocolStyleChanged()
            Toast.makeText(requireContext(), if (v.isEmpty()) "协议风格：跟随预设" else "协议风格：" + v.uppercase(), Toast.LENGTH_SHORT).show()
        }

        // 微信会话引导：每页展示的会话数（1~10）。写入引导自身的 wx_conv_guide 偏好，
        // WeixinConversationGuide.pageSize() 直接读取，引导代码无需改动。
        val etWeixinGuidePageSize = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etWeixinGuidePageSize)
        val guidePrefs = requireContext().getSharedPreferences(WeixinConversationGuide.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        etWeixinGuidePageSize.setText(guidePrefs.getInt(WeixinConversationGuide.KEY_PAGE_SIZE, WeixinConversationGuide.DEFAULT_PAGE_SIZE).toString())
        etWeixinGuidePageSize.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val n = etWeixinGuidePageSize.text?.toString()?.toIntOrNull() ?: WeixinConversationGuide.DEFAULT_PAGE_SIZE
                guidePrefs.edit().putInt(WeixinConversationGuide.KEY_PAGE_SIZE, n.coerceIn(1, WeixinConversationGuide.MAX_PAGE_SIZE)).apply()
            }
        }

        // 外观主题（跟随系统 / 浅色 / 深色）
        val themeGroup = page.findViewById<MaterialButtonToggleGroup>(R.id.theme_group)
        val currentTheme = prefs.getString(MainActivity.PREF_THEME, MainActivity.THEME_SYSTEM) ?: MainActivity.THEME_SYSTEM
        themeGroup.check(
            when (currentTheme) {
                MainActivity.THEME_LIGHT -> R.id.theme_light
                MainActivity.THEME_DARK -> R.id.theme_dark
                else -> R.id.theme_system
            }
        )
        themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val theme = when (checkedId) {
                R.id.theme_light -> MainActivity.THEME_LIGHT
                R.id.theme_dark -> MainActivity.THEME_DARK
                else -> MainActivity.THEME_SYSTEM
            }
            // 保存并全局应用（会重建 Activity，当前会话由 SessionFragment 恢复）
            activity.setAppTheme(theme)
        }

        // ── LLM 后端选择（DeepSeek 逆向 / OpenAI 兼容）──
        val backendGroup = page.findViewById<MaterialButtonToggleGroup>(R.id.backend_group)

        // 重走首次运行初始化向导（后端 / 凭据 / 权限）。force=true：已完成初始化也照常进入。
        page.findViewById<MaterialButton>(R.id.btnReopenSetup).setOnClickListener {
            startActivity(SetupActivity.intent(requireContext(), force = true))
        }
        val openaiConfig = page.findViewById<View>(R.id.openai_config)
            // LiteRT 本地模型配置区（与 deepseek/openai 配置区同构）
            val litertConfig = page.findViewById<View>(R.id.litert_config)
            // 本地模型：引擎二选一（LiteRT / MNN），各对应一个子配置区
            val mnnSubConfig = page.findViewById<View>(R.id.mnn_sub_config)
            val litertSubConfig = page.findViewById<View>(R.id.litert_sub_config)
            val localEngineGroup = page.findViewById<android.widget.RadioGroup>(R.id.local_engine_group)
            val etLitertModel = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.litert_et_model)
            val etLitertTemp = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.litert_et_temp)
        val etLitertMaxTokens = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.litert_et_max_tokens)
            val groupLitertBackend = page.findViewById<android.widget.RadioGroup>(R.id.litert_backend_group)
            // MNN 本地模型字段
            val etMnnModelDir = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.mnn_et_model_dir)
            val etMnnThreads = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.mnn_et_threads)
            val etMnnMaxOutput = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.mnn_et_max_output)

            // MNN 字段初值 + 失焦即存（与 LiteRT 区同款交互）
            etMnnModelDir.setText(auth.getMnnModelDir())
            etMnnModelDir.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) auth.saveMnnModelDir(etMnnModelDir.text?.toString().orEmpty())
            }
            etMnnThreads.setText(auth.getMnnThreads().toString())
            etMnnThreads.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = etMnnThreads.text?.toString()?.trim()?.toIntOrNull() ?: 4
                    auth.saveMnnThreads(v.coerceIn(1, 16))
                }
            }
            etMnnMaxOutput.setText(auth.getMnnMaxOutput().toString())
            etMnnMaxOutput.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val v = etMnnMaxOutput.text?.toString()?.trim()?.toIntOrNull() ?: 4096
                    auth.saveMnnMaxOutput(v.coerceIn(64, 32768))
                }
            }

            // 引擎选择的回填与监听放在 applyBackendVisibility 定义之后（该闭包需先就位）。
        // ── OpenAI 兼容：多配置档案（文件夹样式列表）──
        val openaiProfileList = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.openai_profile_list)
        val btnAddOpenAIProfile = page.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_openai_profile)
        openaiProfileList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        // 切换 / 保存 / 新建 / 删除档案都不重建会话：会话按后端隔离而非档案，
        // 且配置是每请求实时读取的。reloadSessionsForBackend() 只留给「切换后端」
        // 与「清空本地会话」这两个真正让会话失效的场景（见下方各自调用点）。
        val openaiAdapter = com.mcp.OpenAIProfileAdapter(requireContext(), auth)
        openaiProfileList.adapter = openaiAdapter

        fun refreshOpenAIProfiles() {
            openaiAdapter.submit(auth.getOpenAIProfiles().toMutableList(), auth.getActiveOpenAIProfileId())
        }

        btnAddOpenAIProfile.setOnClickListener {
            MaterialDialogs.prompt(
                context = requireContext(),
                title = "新建 OpenAI 配置",
                hint = "模型名",
                confirmText = "确定",
            ) { name ->
                val pp = auth.createOpenAIProfile(name)
                auth.setActiveOpenAIProfileId(pp.id)
                ChatBridge.notifyProfileConfigChanged()   // 新档即激活：聊天页/桌面 Web 立即跟随新模型窗口
                refreshOpenAIProfiles()
            }
        }

        val openaiSwipe = androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                0,
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            ) {
                override fun onMove(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ) = false

                override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {
                    val pos = vh.bindingAdapterPosition
                    val list = openaiAdapter.currentList
                    if (pos !in list.indices) { openaiAdapter.notifyItemChanged(pos); return }
                    val pp = list[pos]
                    MaterialDialogs.confirmDestructive(
                        context = requireContext(),
                        title = "删除配置",
                        message = "确定删除「${pp.model}」这个 OpenAI 配置吗？",
                        confirmText = "删除",
                        onConfirm = {
                            // 删除的是当前激活档案时，AuthPrefs 已把 active 回退到首个剩余档案
                            // （全删完则为 null，配置读取回退到遗留单档键）。会话历史不受影响，
                            // 无需重建——换了个配置，下次请求自然生效。
                            auth.deleteOpenAIProfile(pp.id)
                            ChatBridge.notifyProfileConfigChanged()   // 删除激活档后 active 回退到首个：看板同步刷新
                            refreshOpenAIProfiles()
                        },
                        // 取消（含点外部/返回键）要把滑走的条目复位，否则卡片会停在半滑状态
                        onCancel = { openaiAdapter.notifyItemChanged(pos) },
                    )
                }
            }
        )
        openaiSwipe.attachToRecyclerView(openaiProfileList)

        refreshOpenAIProfiles()
        // 语音配置（转写 / TTS）
        val swAudioTranscribe = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAudioTranscribe)
        val etAudioTranscribeUrl = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAudioTranscribeUrl)
        val etAudioTranscribeApiKey = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAudioTranscribeApiKey)
        val etAudioTranscribeModel = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAudioTranscribeModel)
        val swAudioTts = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAudioTts)
        val swAudioUseSystemTts = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swAudioUseSystemTts)
        val etAudioTtsUrl = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAudioTtsUrl)
        val etAudioTtsApiKey = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAudioTtsApiKey)
        val etAudioTtsModel = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAudioTtsModel)

        val webautoConfig = page.findViewById<View>(R.id.webauto_config)

        // ── Web 自动化：多配置档案（与 OpenAI 档案同构）──
        // 改造前这里是一长条 9 个输入框 + 失焦即存：单站点时够用，多站点时无法并存，
        // 且「我现在用的是哪个站点」没有视觉答案。现在改为列表，单击切换、长按编辑。
        val waProfileList = page.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.webauto_profile_list)
        val btnAddWaProfile = page.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add_webauto_profile)
        waProfileList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        waProfileList.isNestedScrollingEnabled = false

        // 切换/保存档案后的统一收尾：桌面 UA 是全局开关，必须重新应用一次，
        // 否则用户切到一个 desktopMode=true 的档案后，当前页面仍是移动 UA。
        val onWaProfileChanged: () -> Unit = {
            viewLifecycleOwner.lifecycleScope.launch {
                runCatching { WebBrowser.setDesktopUaMode(auth.getWebAutomationConfig().desktopMode) }
            }
        }
        val waAdapter = com.mcp.WebAutomationProfileAdapter(requireContext(), auth, onWaProfileChanged)
        waProfileList.adapter = waAdapter

        fun refreshWaProfiles() {
            waAdapter.submit(
                auth.getWebAutomationProfiles().toMutableList(),
                auth.getActiveWebAutomationProfileId(),
            )
        }

        btnAddWaProfile.setOnClickListener {
            MaterialDialogs.prompt(
                context = requireContext(),
                title = getString(R.string.settings_webauto_new_profile_title),
                hint = getString(R.string.settings_webauto_new_profile_hint),
                confirmText = "确定",
            ) { name ->
                val p = auth.createWebAutomationProfile(name)
                auth.setActiveWebAutomationProfileId(p.id)
                refreshWaProfiles()
                onWaProfileChanged()
            }
        }

        // 左右滑动删除。OpenAI 档案那套用同一交互，用户学一次即可。
        androidx.recyclerview.widget.ItemTouchHelper(
            object : androidx.recyclerview.widget.ItemTouchHelper.SimpleCallback(
                0,
                androidx.recyclerview.widget.ItemTouchHelper.LEFT or androidx.recyclerview.widget.ItemTouchHelper.RIGHT
            ) {
                override fun onMove(
                    rv: androidx.recyclerview.widget.RecyclerView,
                    vh: androidx.recyclerview.widget.RecyclerView.ViewHolder,
                    target: androidx.recyclerview.widget.RecyclerView.ViewHolder
                ) = false

                override fun onSwiped(vh: androidx.recyclerview.widget.RecyclerView.ViewHolder, dir: Int) {
                    val pos = vh.bindingAdapterPosition
                    val list = waAdapter.currentList
                    if (pos !in list.indices) { waAdapter.notifyItemChanged(pos); return }
                    val p = list[pos]
                    MaterialDialogs.confirmDestructive(
                        context = requireContext(),
                        title = "删除配置",
                        message = getString(R.string.settings_webauto_delete_confirm, p.name),
                        confirmText = "删除",
                        onCancel = { waAdapter.notifyItemChanged(pos) },
                    ) {
                        auth.deleteWebAutomationProfile(p.id)
                        refreshWaProfiles()
                        onWaProfileChanged()
                    }
                }
            }
        ).attachToRecyclerView(waProfileList)

        // ── LiteRT 本地模型配置：字段回填 + 失焦保存 ──
            fun loadLiteRtFields() {
                etLitertModel.setText(auth.getLiteRtModelPath())
                etLitertTemp.setText(auth.getLiteRtTemperature().toString())
                etLitertMaxTokens.setText(auth.getLiteRtMaxTokens().toString())
                when (auth.getLiteRtBackend()) {
                    "GPU" -> groupLitertBackend.check(R.id.litert_rb_gpu)
                    "NPU" -> groupLitertBackend.check(R.id.litert_rb_npu)
                    else -> groupLitertBackend.check(R.id.litert_rb_cpu)
                }
            }
    
            fun saveLiteRtFields() {
                auth.saveLiteRtModelPath(etLitertModel.text?.toString()?.trim() ?: "")
                auth.saveLiteRtTemperature(
                    etLitertTemp.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.7
                )
                val backendName = when (groupLitertBackend.checkedRadioButtonId) {
                    R.id.litert_rb_gpu -> "GPU"
                    R.id.litert_rb_npu -> "NPU"
                    else -> "CPU"
                }
                // 上下文窗口：低于 512 直接不可用，做下限保护
                    auth.saveLiteRtMaxTokens(
                        (etLitertMaxTokens.text?.toString()?.trim()?.toIntOrNull() ?: 32768)
                            .coerceAtLeast(512)
                    )
                    auth.saveLiteRtBackend(backendName)
            }
    
            loadLiteRtFields()
            etLitertModel.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveLiteRtFields() }
            etLitertTemp.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveLiteRtFields() }
        etLitertMaxTokens.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveLiteRtFields() }
            groupLitertBackend.setOnCheckedChangeListener { _, _ -> saveLiteRtFields() }
    
            // 导入模型：走 SAF 选文件，落盘到应用私有目录后持久化路径
                        // 把「刷新 LiteRT 字段」的闭包注册给字段级 picker（导入完成后回填输入框）
                refreshLiteRtFields = { loadLiteRtFields() }
                page.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_litert_import)
                    .setOnClickListener { litertModelPicker.launch(arrayOf("*/*")) }
        
            fun applyBackendVisibility(backend: BackendType) {
            openaiConfig.visibility = if (backend == BackendType.OPENAI) View.VISIBLE else View.GONE
            webautoConfig.visibility = if (backend == BackendType.WEB_AUTOMATION) View.VISIBLE else View.GONE
            // 本地模型下再分引擎：MNN 与 LiteRT 都归入「本地模型」后端，
            // 由 local_engine_group 决定展示哪个子区（见下方监听器）。
            val isLocal = backend == BackendType.LITERT || backend == BackendType.MNN
            litertConfig.visibility = if (isLocal) View.VISIBLE else View.GONE
            localEngineGroup.visibility = if (isLocal) View.VISIBLE else View.GONE
            litertSubConfig.visibility = if (backend == BackendType.LITERT) View.VISIBLE else View.GONE
            mnnSubConfig.visibility = if (backend == BackendType.MNN) View.VISIBLE else View.GONE
            // 「工具调用协议风格」只对文本协议后端有意义：OpenAI 走原生 tools 参数、
            // LiteRT 由适配层自行处理，展示该选项只会误导。判据与 ChatBridge 同源。
            val protocolVis = if (com.mcp.core.llm.usesTextToolProtocol(backend)) View.VISIBLE else View.GONE
            protocolStyleTitle.visibility = protocolVis
            protocolStyleHint.visibility = protocolVis
            protocolStyleGroup.visibility = protocolVis
        }

        val currentBackend = auth.getBackend()
        backendGroup.check(
            when (currentBackend) {
                BackendType.OPENAI -> R.id.backend_openai
                BackendType.LITERT -> R.id.backend_litert
                BackendType.MNN -> R.id.backend_litert   // 本地模型共用同一个按钮
                BackendType.WEB_AUTOMATION -> R.id.backend_webauto
                else -> R.id.backend_openai
            }
        )
        // Web 自动化：填充档案列表（切换/编辑/删除的交互都在适配器里，见 WebAutomationProfileAdapter）。
        //
        // 这行是**必须**的，而且不能只在「新建/删除」后调用：适配器初始状态是空列表，
        // 不主动灌一次数据的话，冷启动进来永远是「没有配置」，只有点新建才刷出内容——
        // 杀掉进程再进来又变空，看起来就像配置没保存住（实则是从没读过）。
        refreshWaProfiles()



        applyBackendVisibility(currentBackend)

        // 引擎选择：回填当前后端，切换时同时切后端与子区显示。
        // 两个引擎共用 BackendType.LITERT / MNN 两个枚举值，切引擎即切后端。
        // 必须放在 applyBackendVisibility 定义之后——否则引用未声明的局部量。
        localEngineGroup.check(
            if (currentBackend == BackendType.MNN) R.id.local_rb_mnn else R.id.local_rb_litert
        )
        localEngineGroup.setOnCheckedChangeListener { _, checkedId ->
            val target = if (checkedId == R.id.local_rb_mnn) BackendType.MNN else BackendType.LITERT
            if (target == auth.getBackend()) return@setOnCheckedChangeListener
            auth.setBackend(target)
            applyBackendVisibility(target)
        }

        backendGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val backend = when (checkedId) {
                R.id.backend_openai -> BackendType.OPENAI
                R.id.backend_litert -> BackendType.LITERT

                R.id.backend_webauto -> BackendType.WEB_AUTOMATION
                else -> BackendType.OPENAI
            }
            // 切走前不需要再「保存字段」了：档案编辑即时落盘（适配器里的保存按钮 / 滑动删除），
            // 这里原本的 saveWebautoConfig() 已随单表单改造一并移除。
            if (backend == auth.getBackend()) return@addOnButtonCheckedListener
            auth.setBackend(backend)
            applyBackendVisibility(backend)
            // 切到 Web 自动化：自动弹出自动化浮窗，让用户看到页面并完成登录。
            // 这是该后端的必要前提——不登录站点，自动化无法工作。
            if (backend == BackendType.WEB_AUTOMATION && !BrowserFloatWindow.isAutomationShowing) {
                (activity as? MainActivity)?.popOutAutomationWindow()
            }
            if (false) {
                (activity as? androidx.fragment.app.FragmentActivity)?.let { fa ->
                    DisclaimerDialog.show(fa, auth) { }
                }
            }
            // 切换后端：通知会话列表 Fragment（TAB0）按新后端重新渲染，
            // 并清空其当前会话（避免旧后端的 sessionId 串到新后端）。
            val sessionFrag = activity?.supportFragmentManager
                ?.findFragmentByTag("TAB0") as? SessionFragment
            sessionFrag?.reloadSessionsForBackend()
        }

        // 再点一次「Web 自动化」也能呼出自动化浮窗。
        //
        // MaterialButtonToggleGroup 的监听只在"选中项发生变化"时触发，于是后端本来就已经是
        // Web 自动化时，再点这个按钮毫无反应——用户想重新看到站点页面/补登录，必须先切到别的
        // 后端再切回来（"点 web 不能呼起悬浮窗、非要切换"就是这么来的）。
        // 这里给按钮单独挂点击：只要当前后端是 Web 自动化且浮窗没在显示，就把它呼出来。
        page.findViewById<android.view.View>(R.id.backend_webauto).setOnClickListener {
            if (auth.getBackend() == BackendType.WEB_AUTOMATION && !BrowserFloatWindow.isAutomationShowing) {
                (activity as? MainActivity)?.popOutAutomationWindow()
            }
        }

        // 清空 OpenAI 本地会话（仅 oa_ 命名空间：oa_sessions.json / oa_msg_*.json / oa_sess_cur_*.txt / oa_token_flow_*.jsonl）
        val btnClearOpenAISessions = page.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnClearOpenAISessions)
        btnClearOpenAISessions.setOnClickListener {
            MaterialDialogs.confirmDestructive(
                context = requireContext(),
                title = "清空 OpenAI 本地会话",
                message = "将删除全部 OpenAI 本地会话、消息及请求流水（不影响 DeepSeek 逆向会话），此操作不可恢复。",
                confirmText = "清空",
            ) {
                val tag = LocalStore.backendTag(BackendType.OPENAI)
                LocalStore.clearAll(requireContext(), tag)
                val sf = activity?.supportFragmentManager
                    ?.findFragmentByTag("TAB0") as? SessionFragment
                sf?.reloadSessionsForBackend()
                Toast.makeText(requireContext(), "已清空 OpenAI 本地会话", Toast.LENGTH_SHORT).show()
                LogStore.i("NET", "清空 OpenAI 本地会话 (oa_)")
            }
        }




        // ── 语音配置（转写 / TTS）──
        fun saveAudioSettings() {
            auth.saveAudioTranscribeEnabled(swAudioTranscribe.isChecked)
            auth.saveAudioTranscribeUrl(etAudioTranscribeUrl.text?.toString()?.trim() ?: "")
            auth.saveAudioTranscribeApiKey(etAudioTranscribeApiKey.text?.toString() ?: "")
            auth.saveAudioTranscribeModel(etAudioTranscribeModel.text?.toString()?.trim() ?: "")
            auth.saveAudioTtsEnabled(swAudioTts.isChecked)
            auth.saveAudioUseSystemTts(swAudioUseSystemTts.isChecked)
            auth.saveAudioTtsUrl(etAudioTtsUrl.text?.toString()?.trim() ?: "")
            auth.saveAudioTtsApiKey(etAudioTtsApiKey.text?.toString() ?: "")
            auth.saveAudioTtsModel(etAudioTtsModel.text?.toString()?.trim() ?: "")
        }
        fun applyAudioFieldsEnabled() {
            etAudioTranscribeUrl.isEnabled = swAudioTranscribe.isChecked
            etAudioTranscribeApiKey.isEnabled = swAudioTranscribe.isChecked
            etAudioTranscribeModel.isEnabled = swAudioTranscribe.isChecked
            // TTS 端点字段仅在「TTS 开启 且 未使用系统 TTS」时可编辑（系统 TTS 不调端点）
            val ttsFieldsEnabled = swAudioTts.isChecked && !swAudioUseSystemTts.isChecked
            etAudioTtsUrl.isEnabled = ttsFieldsEnabled
            etAudioTtsApiKey.isEnabled = ttsFieldsEnabled
            etAudioTtsModel.isEnabled = ttsFieldsEnabled
            swAudioUseSystemTts.isEnabled = swAudioTts.isChecked
        }
        swAudioTranscribe.isChecked = auth.isAudioTranscribeEnabled()
        etAudioTranscribeUrl.setText(auth.getAudioTranscribeUrl())
        etAudioTranscribeApiKey.setText(auth.getAudioTranscribeApiKey())
        etAudioTranscribeModel.setText(auth.getAudioTranscribeModel())
        swAudioTts.isChecked = auth.isAudioTtsEnabled()
        swAudioUseSystemTts.isChecked = auth.isAudioUseSystemTts()
        etAudioTtsUrl.setText(auth.getAudioTtsUrl())
        etAudioTtsApiKey.setText(auth.getAudioTtsApiKey())
        etAudioTtsModel.setText(auth.getAudioTtsModel())
        applyAudioFieldsEnabled()

        swAudioTranscribe.setOnCheckedChangeListener { _, _ ->
            applyAudioFieldsEnabled()
            saveAudioSettings()
        }
        swAudioTts.setOnCheckedChangeListener { _, _ ->
            applyAudioFieldsEnabled()
            saveAudioSettings()
        }
        swAudioUseSystemTts.setOnCheckedChangeListener { _, _ ->
            applyAudioFieldsEnabled()
            saveAudioSettings()
        }
        val onFocusLostAudio = View.OnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveAudioSettings() }
        etAudioTranscribeUrl.onFocusChangeListener = onFocusLostAudio
        etAudioTranscribeApiKey.onFocusChangeListener = onFocusLostAudio
        etAudioTranscribeModel.onFocusChangeListener = onFocusLostAudio
        etAudioTtsUrl.onFocusChangeListener = onFocusLostAudio
        etAudioTtsApiKey.onFocusChangeListener = onFocusLostAudio
        etAudioTtsModel.onFocusChangeListener = onFocusLostAudio

        // 推进模式开关
        val switchPush = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchPushMode)
        switchPush.isChecked = prefs.getBoolean("push_mode", false)
        switchPush.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("push_mode", isChecked).apply()
        }

        // 限流重试
        val etRetryMax = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRetryMax)
        val etRetryInterval = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etRetryInterval)
        val etTaskInterval = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTaskInterval)
        etRetryMax.setText(prefs.getInt("retry_max", 3).toString())
        etRetryInterval.setText(prefs.getFloat("retry_interval_sec", 3f).toString())
        etTaskInterval.setText(prefs.getFloat("task_interval_sec", 3f).toString())

        // 子 Agent 设置
        val etAgentMaxRounds = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAgentMaxRounds)
        val etAgentTimeout = page.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAgentTimeout)
        etAgentMaxRounds.setText(prefs.getInt("agent_max_rounds", 8).toString())
        etAgentTimeout.setText(prefs.getInt("agent_timeout_sec", 180).toString())

        // Agent 并行开关（默认关闭 → 子 Agent 串行执行）
        val switchAgentParallel = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAgentParallel)
        switchAgentParallel.isChecked = prefs.getBoolean("agent_parallel", false)
        switchAgentParallel.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("agent_parallel", isChecked).apply()
        }

        // 重置设置（全部恢复默认）
        page.findViewById<View>(R.id.btn_reset).setOnClickListener {
            themeGroup.check(R.id.theme_system)
            etRetryMax.setText("3")
            etRetryInterval.setText("3")
            etTaskInterval.setText("3")
            switchPush.isChecked = false
            etAgentMaxRounds.setText("8")
            etAgentTimeout.setText("180")
            switchAgentParallel.isChecked = false
            etWeixinGuidePageSize.setText(WeixinConversationGuide.DEFAULT_PAGE_SIZE.toString())
            guidePrefs.edit().putInt(WeixinConversationGuide.KEY_PAGE_SIZE, WeixinConversationGuide.DEFAULT_PAGE_SIZE).apply()
            // LLM 后端恢复默认（OpenAI 兼容）+ OpenAI 配置恢复默认
            backendGroup.check(R.id.backend_openai)
            auth.resetOpenAIProfiles()
            refreshOpenAIProfiles()
            auth.setBackend(BackendType.OPENAI)
            applyBackendVisibility(BackendType.OPENAI)
            // 语音配置恢复默认（转写 / TTS 均开启，端点与模型留空走推导）
            swAudioTranscribe.isChecked = true
            etAudioTranscribeUrl.setText("")
            etAudioTranscribeApiKey.setText("")
            etAudioTranscribeModel.setText("")
            swAudioTts.isChecked = true
            swAudioUseSystemTts.isChecked = false
            etAudioTtsUrl.setText("")
            etAudioTtsApiKey.setText("")
            etAudioTtsModel.setText("")
            saveAudioSettings()
            applyAudioFieldsEnabled()
            prefs.edit()
                .putString(MainActivity.PREF_THEME, MainActivity.THEME_SYSTEM)
                .putInt("retry_max", 3)
                .putFloat("retry_interval_sec", 3f)
                .putFloat("task_interval_sec", 3f)
                .putBoolean("push_mode", false)
                .putInt("agent_max_rounds", 8)
                .putInt("agent_timeout_sec", 180)
                .putBoolean("agent_parallel", false)
                .apply()
        }

        // ── 配置导入导出 ──────────────────────────────────────────────
        val tvBackupStatus = page.findViewById<TextView>(R.id.tvBackupStatus)
        page.findViewById<MaterialButton>(R.id.btnConfigExport).setOnClickListener {
            tvBackupStatus.text = "导出中…"
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                val result = runCatching { ConfigBackup.exportToDownloads(requireContext()) }
                withContext(Dispatchers.Main) {
                    result.onSuccess { f ->
                        tvBackupStatus.text = "已导出：${f.absolutePath}"
                        Toast.makeText(requireContext(), "配置已导出到 Download", Toast.LENGTH_LONG).show()
                    }.onFailure { e ->
                        tvBackupStatus.text = "导出失败：${e.message?.take(120)}"
                        Toast.makeText(requireContext(), "导出失败：${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        page.findViewById<MaterialButton>(R.id.btnConfigImport).setOnClickListener {
            // MIME 放开：部分文件管理器给 .json 的 type 是 application/octet-stream。
            configImportPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
        }

        // ── Debian 执行环境（解压 / 安装 / 状态；PRoot 与原生 chroot 共用同一 rootfs）────────────────────────
        val tvProotStatus = page.findViewById<TextView>(R.id.tvProotStatus)
        val progressProot = page.findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressProot)
        val btnProotInstall = page.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnProotInstall)

        fun refreshProotStatus() {
            val ready = ProotEnvironment.isReady(requireContext())
            tvProotStatus.text = if (ready) "状态：Debian 执行环境已就绪" else "状态：Debian 执行环境未安装"
            btnProotInstall.isEnabled = !ready
            if (ready) progressProot.visibility = View.GONE
        }

        // 页面重建恢复：存在仍在时效内的【同类】安装任务（页面销毁/重建后回来）时恢复进度并保持按钮禁用，
        // 防止用户误以为未在安装而重复触发；否则按常规刷新状态。
        // 注意：InstallTaskStore 是单槽存储，两种安装共用一个状态位，必须按 type 过滤，
        // 否则装 A 时会把 B 的按钮也禁用。
        InstallTaskStore.getLiveTaskState(requireContext())
            ?.takeIf { it.type == InstallTaskStore.TaskType.PROOT_INSTALL }
            ?.let { st ->
                progressProot.visibility = View.VISIBLE
                progressProot.progress = st.progress
                tvProotStatus.text = "状态：${st.step}"
                btnProotInstall.isEnabled = false
            } ?: run { refreshProotStatus() }

        // 原生 chroot（root 模式）：物理机已 root 时用系统 su 以真 uid=0 启动原生 chroot，
        // guest 直接跑在 Android 内核上（零 proot 损耗）；否则维持普通 App 沙箱、run_bash 退回 PRoot。
        val switchProotRoot = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchProotRoot)
        switchProotRoot.isChecked = HostSu.isRootModeEnabled(requireContext())
        // 不再因 findSu() 为 null 就禁用开关：KernelSU / APatch 是内核级方案，
        // su 通过挂载或 PATH 注入，普通 App 的 File.exists() 常常看不到。
        // 开关保持可点，点开时用 verifySu() 真正执行一次 su -c id 判定。
        // 若已确认过 root，把文案更新为已就绪。
        if (HostSu.hasVerifiedRoot()) {
            switchProotRoot.text = "root 模式（已检测到可用 su，将使用原生 chroot）"
        }
        switchProotRoot.setOnCheckedChangeListener { _, checked ->
            if (checked && !HostSu.verifySu()) {
                switchProotRoot.isChecked = false
                Toast.makeText(requireContext(), "su 不可用或未授权（uid 非 0）", Toast.LENGTH_LONG).show()
                return@setOnCheckedChangeListener
            }
            HostSu.setRootModeEnabled(requireContext(), checked)
            // 切换后端：root 模式 + 可用 su → 原生 chroot（零损耗）；否则退回 PRoot。
            CapabilityRegistry.selectBackend(requireContext())
            Toast.makeText(
                requireContext(),
                if (checked) "root 模式已开启：run_bash 将以原生 chroot 真 root 运行（零 proot 损耗）"
                else "root 模式已关闭：run_bash 退回 PRoot 沙箱",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnProotInstall.setOnClickListener {
            btnProotInstall.isEnabled = false
            progressProot.visibility = View.VISIBLE
            progressProot.progress = 0
            tvProotStatus.text = "状态：正在安装 PRoot Debian…"
            // applicationContext 不随 Fragment 销毁失效，协程内持久化调用统一用它
            val appCtx = requireContext().applicationContext
            InstallTaskStore.startTask(appCtx, InstallTaskStore.TaskType.PROOT_INSTALL)
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                val ok = ProotEnvironment.ensureInitialized(requireContext()) { msg ->
                    activity.runOnUiThread {
                        val pct = Regex("(\\d+)%").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                        if (pct != null) {
                            progressProot.progress = pct
                            InstallTaskStore.updateProgress(appCtx, pct, msg.take(80))
                        }
                        tvProotStatus.text = msg
                    }
                }
                progressProot.visibility = View.GONE
                if (ok) {
                    LogStore.i("PROOT", "设置页手动安装 PRoot Debian 环境成功")
                    Toast.makeText(appCtx, "PRoot Debian 环境就绪", Toast.LENGTH_SHORT).show()
                } else {
                    LogStore.w("PROOT", "设置页手动安装 PRoot Debian 环境失败（详见日志页 PROOT 标签）")
                    Toast.makeText(appCtx, "PRoot 安装失败，详见日志", Toast.LENGTH_LONG).show()
                }
                if (ok) InstallTaskStore.finishTask(appCtx)
                else InstallTaskStore.failTask(appCtx, "详见日志页 PROOT 标签")
                refreshProotStatus()
            }
        }

        // 保存单个设置值到 SharedPreferences
        fun saveSettings() {
            val max = etRetryMax.text?.toString()?.toIntOrNull() ?: 3
            val sec = etRetryInterval.text?.toString()?.toFloatOrNull() ?: 3f
            val taskSec = etTaskInterval.text?.toString()?.toFloatOrNull() ?: 3f
            val agentRounds = etAgentMaxRounds.text?.toString()?.toIntOrNull() ?: 8
            val agentTimeout = etAgentTimeout.text?.toString()?.toIntOrNull() ?: 180
            prefs.edit().putInt("retry_max", max.coerceIn(1, 20))
                .putFloat("retry_interval_sec", sec.coerceIn(0.5f, 30f))
                .putFloat("task_interval_sec", taskSec.coerceIn(0f, 30f))
                .putInt("agent_max_rounds", agentRounds.coerceIn(1, 50))
                .putInt("agent_timeout_sec", agentTimeout.coerceIn(10, 3600))
                .apply()
        }
        // 输入框失焦时保存
        val onFocusLost = View.OnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveSettings() }
        etRetryMax.onFocusChangeListener = onFocusLost
        etRetryInterval.onFocusChangeListener = onFocusLost
        etTaskInterval.onFocusChangeListener = onFocusLost
        etAgentMaxRounds.onFocusChangeListener = onFocusLost
        etAgentTimeout.onFocusChangeListener = onFocusLost

        // ── 微信连接（原生直连 + Debian PRoot 官方插件）───────────────────
        val tvWeixinStatus = page.findViewById<TextView>(R.id.tvWeixinStatus)
        val btnWeixinQr = page.findViewById<MaterialButton>(R.id.btnWeixinQr)
        val ivWeixinQr = page.findViewById<ImageView>(R.id.ivWeixinQr)
        val tvWeixinQrHint = page.findViewById<TextView>(R.id.tvWeixinQrHint)
        val btnWeixinInstallProot = page.findViewById<MaterialButton>(R.id.btnWeixinInstallProot)
        val btnWeixinPluginLogin = page.findViewById<MaterialButton>(R.id.btnWeixinPluginLogin)
        val btnWeixinGatewayStart = page.findViewById<MaterialButton>(R.id.btnWeixinGatewayStart)
        val btnWeixinGatewayRestart = page.findViewById<MaterialButton>(R.id.btnWeixinGatewayRestart)
        val swWeixinDmScope = page.findViewById<MaterialSwitch>(R.id.swWeixinDmScope)
        val tvWeixinLoopStatus = page.findViewById<TextView>(R.id.tvWeixinLoopStatus)
        val btnWeixinLoopStart = page.findViewById<MaterialButton>(R.id.btnWeixinLoopStart)
        val btnWeixinLoopStop = page.findViewById<MaterialButton>(R.id.btnWeixinLoopStop)
        val btnWeixinClearAccounts = page.findViewById<MaterialButton>(R.id.btnWeixinClearAccounts)
        // ToggleGroup 仅作视觉容器（3 个操作按钮连成一体共享边框）：
        // 点击后立即清除选中态，避免按钮高亮成"被选中"，保持"操作按钮"语义。
        page.findViewById<MaterialButtonToggleGroup>(R.id.wxLoopGroup)
            .addOnButtonCheckedListener { group, checkedId, _ ->
                if (checkedId != 0) group.clearChecked()
            }
        page.findViewById<MaterialButtonToggleGroup>(R.id.webServerGroup)
            .addOnButtonCheckedListener { group, checkedId, _ ->
                if (checkedId != 0) group.clearChecked()
            }

        // ── Web 访问：启停原生 NanoHTTPD 服务（WebApiServer），提供 chat_desktop.html + REST API + SSE ──
        // 与 ChatBridge 同进程、同后端：浏览器打开 http://<本机IP>:<端口>/web/ 即可操作会话列表与聊天。
        val btnStartWebServer = page.findViewById<MaterialButton>(R.id.btnStartWebServer)
        val btnStopWebServer = page.findViewById<MaterialButton>(R.id.btnStopWebServer)
        val tvServerStatus = page.findViewById<TextView>(R.id.tvServerStatus)
        val etServerPort = page.findViewById<TextInputEditText>(R.id.etServerPort)
        val swServerHttps = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swServerHttps)
        val tvHttpsHint = page.findViewById<TextView>(R.id.tvHttpsHint)
        val btnRegenCert = page.findViewById<MaterialButton>(R.id.btnRegenCert)
        val webCtx = requireContext().applicationContext

        // 首次显示：读上次保存的端口，缺省 8693。
        etServerPort.setText(prefs.getInt("web_server_port", 8693).toString())

        // ── HTTPS 开关：持久化，重启服务生效 ──
        swServerHttps.isChecked = prefs.getBoolean("web_server_https", false)
        fun refreshCertHint() {
            val desc = HttpsCertManager.describe(webCtx)
            tvHttpsHint.text = if (desc == null)
                getString(R.string.settings_server_https_desc)
            else "$desc\n首次使用需在浏览器/客户端信任该自签名证书。"
        }
        refreshCertHint()
        swServerHttps.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("web_server_https", checked).apply()
            Toast.makeText(requireContext(),
                if (checked) "已开启 HTTPS，需重启服务生效" else "已关闭 HTTPS，需重启服务生效",
                Toast.LENGTH_SHORT).show()
        }
        btnRegenCert.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val ok = runCatching { HttpsCertManager.regenerate(webCtx); true }.getOrDefault(false)
                withContext(Dispatchers.Main) {
                    refreshCertHint()
                    Toast.makeText(requireContext(),
                        if (ok) "证书已重新生成，重启服务后生效" else "证书生成失败，请查看日志",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }

        /** 从 etServerPort 读当前端口，无效时兜底 8693；同时把值回写到 prefs。 */
        fun currentPort(): Int {
            val port = etServerPort.text?.toString()?.trim()?.toIntOrNull()?.coerceIn(1, 65535) ?: 8693
            if (prefs.getInt("web_server_port", 8693) != port) {
                prefs.edit().putInt("web_server_port", port).apply()
                etServerPort.setText(port.toString())
            }
            return port
        }

        /** 取本机局域网 IPv4 用于拼接访问地址；失败回退 loopback。 */
        fun localIp(): String = try {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }
                ?.hostAddress ?: "127.0.0.1"
        } catch (_: Exception) { "127.0.0.1" }

        fun updateServerUi(running: Boolean, message: String?) {
            val port = currentPort()
            btnStartWebServer.visibility = if (running) View.GONE else View.VISIBLE
            btnStopWebServer.visibility = if (running) View.VISIBLE else View.GONE
            tvServerStatus.visibility = View.VISIBLE
            tvServerStatus.text = buildString {
                append(if (running) "运行中" else "未运行")
                append("（端口 $port）")
                if (!message.isNullOrBlank()) append("\n").append(message)
            }
        }

        btnStartWebServer.setOnClickListener {
            val port = currentPort()
            LogStore.i("WEB", "点击启动 Web 服务器 port=$port")
            btnStartWebServer.isEnabled = false
            tvServerStatus.visibility = View.VISIBLE
            tvServerStatus.text = "正在启动（端口 $port）…"
            val appCtx = webCtx
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val actualPort = WebApiServer.start(appCtx, auth, port, swServerHttps.isChecked)
                    withContext(Dispatchers.Main) {
                        LogStore.i("WEB", "Web 服务器启动成功 port=$actualPort")
                        val scheme = if (swServerHttps.isChecked) "https" else "http"
                        updateServerUi(true, "地址: $scheme://${localIp()}:$actualPort${WebApiServer.ROUTE_PREFIX}/")
                        Toast.makeText(requireContext(), "Web 服务器已启动（端口 $actualPort）", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: java.io.IOException) {
                    withContext(Dispatchers.Main) {
                        LogStore.e("WEB", "Web 服务器启动失败：${e.message}")
                        updateServerUi(false, "启动失败：${e.message}")
                        Toast.makeText(requireContext(), "Web 服务器启动失败，看日志页", Toast.LENGTH_SHORT).show()
                    }
                } finally {
                    withContext(Dispatchers.Main) { btnStartWebServer.isEnabled = true }
                }
            }
        }

        btnStopWebServer.setOnClickListener {
            LogStore.i("WEB", "点击停止 Web 服务器")
            lifecycleScope.launch(Dispatchers.IO) {
                WebApiServer.stop()
                withContext(Dispatchers.Main) {
                    LogStore.i("WEB", "Web 服务器已停止")
                    updateServerUi(false, null)
                    Toast.makeText(requireContext(), "Web 服务器已停止", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // ── API 转发（外部 /v1 调用）独立开关：默认关闭，持久化 ──
        // 与 MCP 总开关解耦：关闭时 /v1/* 返回 404，不影响 /web 桌面页与 /mcp。
        val swApiForward = page.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.swApiForward)
        swApiForward.isChecked = ApiForwardPrefs.isEnabled(webCtx)
        swApiForward.setOnCheckedChangeListener { _, checked ->
            ApiForwardPrefs.setEnabled(webCtx, checked)
            LogStore.i("WEB", "API 转发开关 = $checked")
        }

        // ── 默认归属会话：点行弹列表选择，持久化到 ApiForwardPrefs ──
        val rowApiForwardSession = page.findViewById<android.widget.LinearLayout>(R.id.rowApiForwardSession)
        val tvApiForwardSession = page.findViewById<android.widget.TextView>(R.id.tvApiForwardSession)

        /** 刷新归属会话显示：空 = 未指定；否则显示「标题（短id）」。 */
        fun refreshApiForwardSession() {
            val sid = ApiForwardPrefs.sessionId(webCtx)
            if (sid.isBlank()) {
                tvApiForwardSession.text = getString(R.string.settings_api_forward_session_none)
                return
            }
            val tag = LocalStore.backendTag(auth.getBackend())
            val title = runCatching {
                LocalStore.loadSessions(webCtx, tag).firstOrNull { it.id == sid }?.title
            }.getOrNull()
            val label = if (!title.isNullOrBlank()) title else sid
            val shortId = if (sid.length > 8) sid.take(8) + "…" else sid
            tvApiForwardSession.text = "$label（$shortId）"
        }
        refreshApiForwardSession()

        rowApiForwardSession.setOnClickListener {
            val tag = LocalStore.backendTag(auth.getBackend())
            val sessions = runCatching { LocalStore.loadSessions(webCtx, tag) }.getOrDefault(emptyList())
            // 首项「未指定」+ 次项「＋ 新建会话」+ 既有会话列表
            val labels = ArrayList<String>()
            labels.add(getString(R.string.settings_api_forward_session_none))
            labels.add("＋ 新建会话")
            for (s in sessions) {
                val t = if (s.title.isNotBlank()) s.title else "(未命名)"
                val shortId = if (s.id.length > 8) s.id.take(8) + "…" else s.id
                labels.add("$t（$shortId）")
            }
            MaterialDialogs.choose(
                context = requireContext(),
                title = "选择默认归属会话",
                items = labels,
                onSelect = { idx ->
                    when (idx) {
                        0 -> {
                            ApiForwardPrefs.setSessionId(webCtx, "")
                            refreshApiForwardSession()
                        }
                        1 -> createApiForwardSession(refresh = { refreshApiForwardSession() })
                        else -> {
                            ApiForwardPrefs.setSessionId(webCtx, sessions[idx - 2].id)
                            refreshApiForwardSession()
                        }
                    }
                }
            )
        }

        // 页面进入时恢复状态：WebApiServer 为进程内单例，进程存续期间保持监听。
        if (WebApiServer.isRunning()) {
            updateServerUi(true, "地址: http://${localIp()}:${WebApiServer.currentPort()}${WebApiServer.ROUTE_PREFIX}/")
        }

        val wxLoop = OpenClawWeChat.WeixinMessageLoop.singleton(requireContext())

        fun refreshWxStatus() {
            val wxBackendLabel = when (auth.getBackend()) {
                BackendType.OPENAI -> "OpenAI Chat Completions"
                BackendType.LITERT -> "LiteRT 本地模型"
                BackendType.MNN -> "MNN 本地模型"
                BackendType.WEB_AUTOMATION -> "Web 自动化（网页版对话框）"
            }
            val native = OpenClawWeChat.listAccounts(requireContext())
            val nativeLine = "原生直连：${native.size} 个账号已登录" +
                (native.firstOrNull()?.let { "，默认=${it.accountId}" } ?: "")
            tvWeixinStatus.text = "$nativeLine\nDebian PRoot 官方插件：可通过下方按钮安装 / 查看状态"
            tvWeixinStatus.append("\n原生 iLink LLM：$wxBackendLabel")
            val running = wxLoop.runningCount()
            val savedRunning = prefs.getBoolean("wx_loop_running", true)
            tvWeixinLoopStatus.text = when {
                native.isEmpty() -> "轮询状态：未启动（没有登录的微信账号）"
                running > 0 -> "轮询状态：已启动 $running / ${native.size} 个账号（重启 App 后会自动恢复）"
                savedRunning -> "轮询状态：未启动（账号已登录，等待 App 重启或点启动按钮）"
                else -> "轮询状态：已停止（重启 App 后不会自动启动）"
            }
        }
        refreshWxStatus()

        btnWeixinLoopStart.setOnClickListener {
            prefs.edit().putBoolean("wx_loop_running", true).apply()
            wxLoop.startAll()
            refreshWxStatus()
            Toast.makeText(requireContext(), "已启动微信轮询，开始接收消息", Toast.LENGTH_SHORT).show()
        }
        btnWeixinLoopStop.setOnClickListener {
            prefs.edit().putBoolean("wx_loop_running", false).apply()
            wxLoop.stopAll()
            refreshWxStatus()
            Toast.makeText(requireContext(), "已停止微信轮询", Toast.LENGTH_SHORT).show()
        }

        // 清空所有已登录账号：先停轮询，再删账号文件，最后刷新 UI。
        btnWeixinClearAccounts.setOnClickListener {
            MaterialDialogs.confirmDestructive(
                context = requireContext(),
                title = "清空已登录账号",
                message = "将删除全部已登录的微信账号记录，且无法恢复。确认继续？",
                confirmText = "清空",
            ) {
                prefs.edit().putBoolean("wx_loop_running", false).apply()
                wxLoop.stopAll()
                OpenClawWeChat.clearAccounts(requireContext())
                refreshWxStatus()
                Toast.makeText(requireContext(), "已清空全部已登录账号", Toast.LENGTH_SHORT).show()
            }
        }

        // App 打开设置页时按上次状态恢复：默认 true（首次安装自动启动），
        // 用户点过「停止轮询」后 wx_loop_running=false，下次重启不再自动启动。
        if (prefs.getBoolean("wx_loop_running", true)) {
            wxLoop.startAll()
        }
        refreshWxStatus()

        // ── 微信入站消息 → Agent → 回推 ──────────────────────────────
        // 方案：消息循环只负责收发，会话与工具续聊统一交给 WeixinLlmResponder，
        // 由它按后端分支处理（DeepSeek 服务端 session / OpenAI function calling）。
        // 微信回复器复用主 Toolbox 的「通用能力」工具，但剔除 openclaw_weixin_* 渠道运维工具：
        // 收发消息已由 message loop 用代码完成（自己 sendText），LLM 再持有 send_text/get_updates
        // 既冗余又危险（方向易搞反）；登录/扫码/故障排查等运维走设置页 UI 或委派给 CHANNEL 子 Agent。
        val wxAuth = com.mcp.deepseek.AuthPrefs(requireContext())
        val fullToolbox = ToolRuntimeHolder.get(requireContext(), wxAuth).toolbox
        val wxToolbox = Toolbox().apply {
            fullToolbox.all().forEach { if (!it.name.startsWith("openclaw_weixin_")) register(it) }
        }
        // 原生 iLink 微信的两个 LLM 后端分别走自己的协议：DeepSeek 保留服务端会话，OpenAI 使用 function calling。
        val wxResponder = WeixinLlmResponder(requireContext(), wxAuth, wxToolbox)
        wxLoop.onIncomingText = { account, fromUserId, _, rawText, _ ->
            wxResponder.reply(account.accountId, fromUserId, rawText)
        }

        // 会话隔离开关：原生路径只是个开关（供工具读取）；PRoot 路径写 openclaw config
        swWeixinDmScope.isChecked = prefs.getBoolean("wx_dm_scope", true)
        swWeixinDmScope.setOnCheckedChangeListener { _, v ->
            prefs.edit().putBoolean("wx_dm_scope", v).apply()
            if (ProotEnvironment.isReady(requireContext())) {
                viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val cmd = "openclaw config set session.dmScope " +
                            if (v) "per-account-channel-peer" else "default"
                        executeBash(requireContext(), cmd)
                    }
                }
            }
        }

        // 扫码登录（原生）
        btnWeixinQr.setOnClickListener {
            btnWeixinQr.isEnabled = false
            ivWeixinQr.visibility = View.GONE
            tvWeixinQrHint.visibility = View.VISIBLE
            tvWeixinQrHint.text = "正在获取二维码…"
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val (qrcodeUrl, qrcode) = OpenClawWeChat.loginQrStart(requireContext())
                    // 用 ZXing 把 URL 渲染成二维码 bitmap
                    val size = 600
                    val bitMatrix = QRCodeWriter().encode(qrcodeUrl, BarcodeFormat.QR_CODE, size, size)
                    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
                    for (x in 0 until size) {
                        for (y in 0 until size) {
                            bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                        }
                    }
                    activity.runOnUiThread {
                        ivWeixinQr.setImageBitmap(bmp)
                        ivWeixinQr.visibility = View.VISIBLE
                        tvWeixinQrHint.setTextIsSelectable(true)
                        tvWeixinQrHint.text = "请用微信扫码，并在手机上确认授权（二维码 2 分钟内有效）\n链接：$qrcodeUrl"
                    }
                    val acc = OpenClawWeChat.loginQrAwait(requireContext(), qrcode, 180)
                    activity.runOnUiThread {
                        ivWeixinQr.visibility = View.GONE
                        if (acc != null) {
                            tvWeixinQrHint.text = "登录成功：${acc.accountId}。下次启动 App 会自动保存账号。"
                            wxLoop.start(acc)   // 登录成功立刻启动轮询，无需重启 App
                            Toast.makeText(requireContext(), "微信连接成功，已启动消息轮询", Toast.LENGTH_SHORT).show()
                        } else {
                            tvWeixinQrHint.text = "二维码已过期或超时，请重新点击「扫码登录」。"
                        }
                        btnWeixinQr.isEnabled = true
                        refreshWxStatus()
                    }
                }.onFailure {
                    activity.runOnUiThread {
                        tvWeixinQrHint.text = "获取二维码失败：${it.message?.take(80)}"
                        btnWeixinQr.isEnabled = true
                    }
                }
            }
        }

        // 安装 Debian PRoot 官方插件
        btnWeixinInstallProot.setOnClickListener {
            btnWeixinInstallProot.isEnabled = false
            btnWeixinInstallProot.text = "安装中…"
            tvWeixinStatus.text = "安装中，详细日志见日志页 (WX_INSTALL 标签)"
            // applicationContext 不随 Fragment 销毁失效，IO 协程内持久化调用统一用它
            val appCtx = requireContext().applicationContext
            InstallTaskStore.startTask(appCtx, InstallTaskStore.TaskType.WX_PLUGIN_INSTALL)

            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val script = OpenClawWeChat.prootInstallScript()

                // 逐行回调：实时写日志页 + 更新进度/步骤
                val onLine: (String) -> Unit = { line ->
                    LogStore.i("WX_INSTALL", line)
                    val pct = Regex("""(\d+)%""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                    if (pct != null && pct in 0..100) {
                        InstallTaskStore.updateProgress(appCtx, pct, line.take(80))
                        activity.runOnUiThread {
                            btnWeixinInstallProot.text = "安装中 $pct%"
                            tvWeixinStatus.text = "安装中 $pct%"
                        }
                    } else if (line.contains("apt-get") || line.contains("npm") || line.contains("openclaw")) {
                        val short = line.take(50)
                        activity.runOnUiThread {
                            btnWeixinInstallProot.text = short
                            tvWeixinStatus.text = short
                        }
                    }
                }

                val result = runCatching {
                    // 安装链路含 apt update / nodejs 22 / build-essential / npm install -g openclaw，
                    // 其中 npm 下载 + node-gyp 编译原生模块常远超默认 300 秒，此处放宽到 30 分钟。
                    executeBash(requireContext(), script, onLine, timeoutSeconds = 1800L)
                }.getOrElse { "安装异常：${it.message}" }

                val finalResult = result ?: "(结果为空)"
                LogStore.i("WX", "openclaw-weixin PRoot 安装结果尾段: ${finalResult.takeLast(400)}")
                activity.runOnUiThread {
                    btnWeixinInstallProot.isEnabled = true
                    if (finalResult.contains("\"error\"") || finalResult.contains("异常") || finalResult.contains("失败")) {
                        btnWeixinInstallProot.text = "安装失败，点此重试"
                        tvWeixinStatus.text = "安装失败，查看日志页详情"
                        InstallTaskStore.failTask(appCtx, finalResult.take(60))
                    } else if (finalResult.contains("安装完成") || finalResult.contains("openclaw plugins list")) {
                        btnWeixinInstallProot.text = "安装完成"
                        tvWeixinStatus.text = "安装完成，点击「② 启动 Gateway」"
                        InstallTaskStore.finishTask(appCtx)
                    } else {
                        btnWeixinInstallProot.text = "安装完成（查看状态）"
                        tvWeixinStatus.text = "安装完成，查看日志页详情"
                        InstallTaskStore.finishTask(appCtx)
                    }
                    refreshWxStatus()
                }
            }
        }

        // 页面重建恢复：【同类】插件安装任务进行中时保持按钮禁用并显示进度，防止重复触发安装
        // （单槽存储，按 type 过滤，避免误把 PRoot 安装当成插件安装在恢复）
        InstallTaskStore.getLiveTaskState(requireContext())
            ?.takeIf { it.type == InstallTaskStore.TaskType.WX_PLUGIN_INSTALL }
            ?.let { st ->
                btnWeixinInstallProot.isEnabled = false
                btnWeixinInstallProot.text = "安装中 ${st.progress}%"
                tvWeixinStatus.text = "${st.step}（任务跨页面恢复）"
            }

        // 扫码登录（Debian PRoot 官方插件）：复用原生 iLink 二维码流程渲染真实二维码，
        // 登录成功后把凭证按官方插件格式写入 ~/.openclaw/openclaw-weixin/accounts/，
        // 再重启 Gateway 使其加载新账号。这样「已安装但未登录」也能拿到二维码和链接。
        fun launchPluginLogin() {
            btnWeixinPluginLogin.isEnabled = false
            ivWeixinQr.visibility = View.GONE
            tvWeixinQrHint.visibility = View.VISIBLE
            tvWeixinQrHint.setTextIsSelectable(true)
            tvWeixinQrHint.text = "正在获取官方插件登录二维码…"
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val (qrcodeUrl, qrcode) = OpenClawWeChat.loginQrStart(requireContext())
                    val size = 600
                    val bitMatrix = QRCodeWriter().encode(qrcodeUrl, BarcodeFormat.QR_CODE, size, size)
                    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
                    for (x in 0 until size) {
                        for (y in 0 until size) {
                            bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                        }
                    }
                    activity.runOnUiThread {
                        ivWeixinQr.setImageBitmap(bmp)
                        ivWeixinQr.visibility = View.VISIBLE
                        tvWeixinQrHint.text = "请用微信扫码，并在手机上确认授权（二维码 2 分钟内有效）\n链接：$qrcodeUrl"
                    }
                    val outcome = OpenClawWeChat.loginQrAwaitDetailed(requireContext(), qrcode, 180)
                    // 先收起二维码，再在 IO 线程做收尾。
                    activity.runOnUiThread { ivWeixinQr.visibility = View.GONE }
                    val acc = outcome.account
                    // 注意：savePluginAccount / restartGateway 内部会走 PRoot 的
                    // executeBash → Process.waitFor（阻塞）。必须在当前 IO 线程执行，
                    // 绝不能放进 runOnUiThread，否则主线程被阻塞导致 ANR。
                    val resultText = when {
                        outcome.alreadyBound -> "该微信已绑定到本机 OpenClaw，无需重复登录。"
                        acc == null || outcome.rawBotId.isNullOrBlank() ->
                            "二维码已过期或超时，请重新点击「② 扫码登录」。"
                        else -> {
                            val normalized = OpenClawWeChat.savePluginAccount(
                                requireContext(), outcome.rawBotId!!, acc!!.token, outcome.baseUrl, outcome.userId
                            )
                            if (normalized == null) {
                                "登录成功，但写入官方插件账号目录失败，请查看日志页 WX 标签。"
                            } else {
                                runCatching { OpenClawWeChat.restartGateway(requireContext()) }.fold(
                                    onSuccess = { "官方插件登录成功：$normalized，Gateway 已重启。" },
                                    onFailure = { "账号已保存为 $normalized，但 Gateway 重启失败：${it.message?.take(60)}" }
                                )
                            }
                        }
                    }
                    activity.runOnUiThread {
                        tvWeixinQrHint.text = resultText
                        btnWeixinPluginLogin.isEnabled = true
                        refreshWxStatus()
                    }
                }.onFailure {
                    activity.runOnUiThread {
                        tvWeixinQrHint.text = "获取二维码失败：${it.message?.take(80)}"
                        btnWeixinPluginLogin.isEnabled = true
                    }
                }
            }
        }

        btnWeixinPluginLogin.setOnClickListener {
            if (!ProotEnvironment.isReady(requireContext())) {
                Toast.makeText(requireContext(), "请先点「① 安装 PRoot Debian 官方插件」", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            launchPluginLogin()
        }

        // 启动 Gateway（常驻后台进程）。
        // 关键：插件「已安装但未登录」时，直接点启动不会打印二维码/链接。
        // 这里先探测登录状态：未安装→提示先安装；已装未登录→自动弹出扫码登录二维码；
        // 已登录→正常启动 Gateway。
        btnWeixinGatewayStart.setOnClickListener {
            if (!ProotEnvironment.isReady(requireContext())) {
                Toast.makeText(requireContext(), "请先点「① 安装 PRoot Debian 官方插件」", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val state = OpenClawWeChat.pluginLoginState(requireContext())
                when (state) {
                    "not_installed" -> activity.runOnUiThread {
                        Toast.makeText(requireContext(), "官方插件尚未安装，请先点「① 安装 PRoot Debian 官方插件」", Toast.LENGTH_LONG).show()
                    }
                    "not_logged_in" -> activity.runOnUiThread {
                        Toast.makeText(requireContext(), "已安装但未登录，正在生成登录二维码…", Toast.LENGTH_LONG).show()
                        launchPluginLogin()
                    }
                    else -> {
                        val r = runCatching { OpenClawWeChat.startGateway(requireContext()) }
                        activity.runOnUiThread {
                            r.onSuccess { id ->
                                Toast.makeText(
                                    requireContext(),
                                    "Gateway 已后台启动（task=$id）。日志：~/.openclaw/gateway.log",
                                    Toast.LENGTH_LONG
                                ).show()
                            }.onFailure {
                                Toast.makeText(requireContext(), "Gateway 启动失败：${it.message?.take(80)}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            }
        }

        // 重启 Gateway（配置变更时用）
        btnWeixinGatewayRestart.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val r = runCatching { OpenClawWeChat.restartGateway(requireContext()) }
                activity.runOnUiThread {
                    r.onSuccess { id ->
                        Toast.makeText(requireContext(), "Gateway 已重启（task=$id）", Toast.LENGTH_SHORT).show()
                    }.onFailure {
                        Toast.makeText(requireContext(), "Gateway 重启失败：${it.message?.take(80)}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }


}