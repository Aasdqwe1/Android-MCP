package com.mcp.deepseek

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mcp.core.llm.BackendType
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 登录态持久化（EncryptedSharedPreferences + Android Keystore AES-256-GCM）。
 *
 * Token 和手机号加密存储，rooted 设备或 ADB backup 无法直接读取明文。
 * 自动迁移策略：首次初始化时检测旧版明文文件，若存在则自动迁移并清除旧明文。
 * 容错策略：Keystore 初始化失败（极少情况）时降级为明文存储并记录日志。
 */
class AuthPrefs(context: Context) {

    private val appContext = context.applicationContext

    private val sp: SharedPreferences = sharedPrefs(appContext)

    /**
     * 取进程内唯一的一份 SharedPreferences，首次调用时构建。
     *
     * 为什么必须复用：MasterKey.Builder().build() + EncryptedSharedPreferences.create()
     * 每次都要走 Android Keystore（AES-256-GCM 主密钥），单次开销几十到上百毫秒，且**全在
     * 主线程**。而启动路径上 AuthPrefs 会被构造多次——SessionFragment、SettingsFragment、
     * ToolsFragment 各一次，PresetPrefs 每个调用点再各一次——此前每次都重建一份，
     * 累计起来就是「进入主窗口卡一下」的主要来源。
     *
     * SharedPreferences 本身线程安全，复用无副作用；Keystore 降级为明文的结果同样缓存，
     * 避免每个实例重复尝试一次注定失败的初始化。
     */
    private fun sharedPrefs(ctx: Context): SharedPreferences =
        cachedPrefs ?: synchronized(PREFS_LOCK) {
            cachedPrefs ?: buildEncryptedPrefs(ctx).also { cachedPrefs = it }
        }

    private fun buildEncryptedPrefs(ctx: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val enc = EncryptedSharedPreferences.create(
                ctx,
                ENCRYPTED_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            migratePlaintext(enc)
            enc
        } catch (e: Exception) {
            // Keystore 异常（设备重置后密钥失效等极少情况）→ 降级为明文存储，保证登录流程不崩溃
            Log.e("AuthPrefs", "Keystore 初始化失败，降级为明文存储（${e.message}\uff09", e)
            ctx.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * 将旧版明文 SharedPreferences 中的 token/mobile 迁移到加密存储，然后清除旧明文字段。
     * 异幂操作：已存在加密存储的字段不覆盖。
     */
    private fun migratePlaintext(enc: SharedPreferences) {
        val old = appContext.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val oldToken  = old.getString(KEY_TOKEN,  null)
        val oldMobile = old.getString(KEY_MOBILE, null)
        if (oldToken.isNullOrEmpty() && oldMobile.isNullOrEmpty()) return

        enc.edit().apply {
            if (!oldToken.isNullOrEmpty()  && enc.getString(KEY_TOKEN,  null).isNullOrEmpty()) putString(KEY_TOKEN,  oldToken)
            if (!oldMobile.isNullOrEmpty() && enc.getString(KEY_MOBILE, null).isNullOrEmpty()) putString(KEY_MOBILE, oldMobile)
        }.apply()

        // 仅清除敏感字段（保留文件以免系统对象异常）
        old.edit().remove(KEY_TOKEN).remove(KEY_MOBILE).apply()
        Log.i("AuthPrefs", "token/mobile 已从明文迁移到 Keystore 加密存储")
    }

    fun isLoggedIn(): Boolean = !getToken().isNullOrEmpty()

    fun getToken(): String? {
        val t = sp.getString(KEY_TOKEN, null)
        return if (t.isNullOrEmpty()) null else t
    }

    fun saveToken(token: String) {
        sp.edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearToken() {
        sp.edit().remove(KEY_TOKEN).apply()
    }

    // ═══════════════════════════════════════════
    //  首次运行初始化向导
    // ═══════════════════════════════════════════

    /**
     * 初始化向导是否已完成。
     *
     * 未完成时 SetupActivity 作为启动页挡住主界面：新装的 App 还没有后端选择、
     * 也没有任何凭据，直接进主界面只会看到一个空会话列表，无从下手。
     */
    fun isSetupCompleted(): Boolean = sp.getBoolean(KEY_SETUP_COMPLETED, false)

    fun setSetupCompleted(done: Boolean) {
        sp.edit().putBoolean(KEY_SETUP_COMPLETED, done).apply()
    }

    /**
     * 是否已确认免责声明（研究用途许可证）。
     *
     * 首次选择 DeepSeek 逆向后端时强制阅读到底并确认；确认一次后永久生效，
     * 之后在引导页或设置页再切到 DeepSeek 都不再弹出。
     */
    fun isDisclaimerAcknowledged(): Boolean = sp.getBoolean(KEY_DISCLAIMER_ACK, false)

    fun setDisclaimerAcknowledged(ack: Boolean) {
        sp.edit().putBoolean(KEY_DISCLAIMER_ACK, ack).apply()
    }

    fun getMobile(): String? = sp.getString(KEY_MOBILE, null)

    fun saveMobile(mobile: String) {
        sp.edit().putString(KEY_MOBILE, mobile).apply()
    }

    // ═══════════════════════════════════════════
    //  手动 device_id（登录风控兜底）
    // ═══════════════════════════════════════════

    /**
     * 用户手动填写的 device_id（登录 body 的 device_id 字段）。
     *
     * 为什么需要它：DeepSeek 登录风控要求 device_id 来自数美 SDK（SmAntiFraud.getDeviceId()），
     * 本地 Lex2 算法生成的 44 字符 device_id 会被服务端判为 RISK_DEVICE_DETECTED（biz_code=11）。
     * 在无法集成数美 SDK 时，允许用户把抓包到的真实 device_id 粘进来作为兜底。
     *
     * 非空时登录优先使用它，不再走本地生成。
     */
    fun getManualDeviceId(): String = sp.getString(KEY_MANUAL_DEVICE_ID, "") ?: ""

    fun saveManualDeviceId(id: String) {
        sp.edit().putString(KEY_MANUAL_DEVICE_ID, id.trim()).apply()
    }

    fun clearManualDeviceId() {
        sp.edit().remove(KEY_MANUAL_DEVICE_ID).apply()
    }

    /**
     * 解析要提交的设备标识。
     *
     * 原实现会依次尝试「手动值 > 官方 App 数美指纹（root 读取）> 本地 Lds2 算法」，
     * 后者均属 DeepSeek 登录风控专用（已随 DeepSeek 后端一并移除）。
     * 现在只返回手动填入值（空串表示未填）。
     */
    fun resolveDeviceId(manual: String): String {
        val m = manual.trim()
        if (m.isNotEmpty()) saveManualDeviceId(m)
        return m
    }

    // ═══════════════════════════════════════════
    //  LLM 后端选择与 OpenAI 兼容协议配置
    // ═══════════════════════════════════════════

    /** 当前选中的 LLM 后端（默认 DeepSeek 逆向协议）。 */
    // ═══════════════════════════════════════════
    //  LiteRT 本地模型后端配置
    // ═══════════════════════════════════════════
    
    /** .litertlm 模型文件绝对路径；空串表示未导入。 */
    /**
     * 工具调用协议风格偏好。
     *
     * 取值："line"（行式）/ "xml"（DSML 标签）/ ""（未设置，跟随预设）。
     * 仅对**文本协议**后端有意义（DeepSeek 逆向 / Web 自动化）。
     * 极简模式（受限 + 非 PTC）会强制 XML，本偏好对它无效。
     */
    fun getProtocolStylePref(): String =
        sp.getString(KEY_PROTOCOL_STYLE, "") ?: ""

    fun setProtocolStylePref(v: String) {
        sp.edit().putString(KEY_PROTOCOL_STYLE, v.trim().lowercase()).apply()
    }

    fun getLiteRtModelPath(): String =
        sp.getString(KEY_LITERT_MODEL_PATH, "") ?: ""
    
    fun saveLiteRtModelPath(path: String) {
        sp.edit().putString(KEY_LITERT_MODEL_PATH, path.trim()).apply()
    }
    
    /** 算力档位名（CPU / GPU / NPU），存字符串便于向前兼容。 */
    fun getLiteRtBackend(): String =
        sp.getString(KEY_LITERT_BACKEND, "CPU") ?: "CPU"
    
    fun saveLiteRtBackend(name: String) {
        sp.edit().putString(KEY_LITERT_BACKEND, name.trim().uppercase()).apply()
    }
    
    fun getLiteRtTemperature(): Double =
        sp.getString(KEY_LITERT_TEMPERATURE, "0.7")?.toDoubleOrNull() ?: 0.7
    
    fun saveLiteRtTemperature(t: Double) {
        sp.edit().putString(KEY_LITERT_TEMPERATURE, t.toString()).apply()
    }
    
    fun getLiteRtTopK(): Int =
        sp.getString(KEY_LITERT_TOP_K, "40")?.toIntOrNull() ?: 40

    fun saveLiteRtTopK(v: Int) {
        sp.edit().putString(KEY_LITERT_TOP_K, v.toString()).apply()
    }
    
    /**
         * 工具调用去重开关（默认开）。
         *
         * 开启时，同一 tool_call id 只消费一次，防止站点/模型复读上一次的调用
         * 导致重复执行甚至死循环。但若后端生成的 id 不唯一（曾出现于 LiteRT），
         * 去重会误杀合法的新调用——此时可关掉。
         */
        fun isToolDedupEnabled(): Boolean =
            sp.getBoolean(KEY_TOOL_DEDUP_ENABLED, true)
    
        fun setToolDedupEnabled(v: Boolean) {
            sp.edit().putBoolean(KEY_TOOL_DEDUP_ENABLED, v).apply()
        }
    
        /**
         * 引擎上下文窗口（token）。
     *
     * LiteRT 未指定时默认仅 4096，稍长的 system 提示词会被拒。
     * Gemma 3n 原生支持 32K，默认给足；用户可在设置页调整。
     */
    fun getLiteRtMaxTokens(): Int =
        sp.getString(KEY_LITERT_MAX_TOKENS, "32768")?.toIntOrNull() ?: 32768
    
    /**
         * 最大输出（token）。LiteRT 的 maxNumTokens 是**总窗口**，
         * 输入 = 总窗口 − 输出。默认 4096，与项目其他后端的默认最大输出一致。
         */
        fun getLiteRtMaxOutput(): Int =
            sp.getString(KEY_LITERT_MAX_OUTPUT, "4096")?.toIntOrNull() ?: 4096
    
        fun saveLiteRtMaxOutput(v: Int) {
            sp.edit().putString(KEY_LITERT_MAX_OUTPUT, v.toString()).apply()
        }
    
        /** LiteRT 输入上限 = 总窗口 − 输出预留（下限 512，防止算成 0）。 */
        fun getLiteRtMaxInput(): Int =
            (getLiteRtMaxTokens() - getLiteRtMaxOutput()).coerceAtLeast(512)
    
        /** LiteRT 模型显示名：从路径取文件名，空则给通用名。 */
        fun getLiteRtModelLabel(): String {
            val path = getLiteRtModelPath()
            if (path.isBlank()) return "本地模型（未导入）"
            val name = path.substringAfterLast('/')
            return if (name.isBlank()) "本地模型" else "本地 · $name"
        }
    
        fun saveLiteRtMaxTokens(v: Int) {
        sp.edit().putString(KEY_LITERT_MAX_TOKENS, v.toString()).apply()
    }
    
    fun getLiteRtTopP(): Double =
        sp.getString(KEY_LITERT_TOP_P, "0.95")?.toDoubleOrNull() ?: 0.95

    fun saveLiteRtTopP(v: Double) {
        sp.edit().putString(KEY_LITERT_TOP_P, v.toString()).apply()
    }

    // ───────── MNN 本地模型后端 ─────────

    /** MNN 模型目录（内含 config.json 与权重文件）。 */
    /**
     * MNN 模型目录（内含 config.json 与权重文件）。
     *
     * 默认指向共享存储下的约定位置：MNN Chat 的模型在它自己的私有目录（0700），
     * 本 App 无权读取；把模型放到 /storage/emulated/0/Models 下两个 App 都能访问，
     * 既不占双份空间，也不依赖 root 改权限。
     */
    fun getMnnModelDir(): String =
        sp.getString(KEY_MNN_MODEL_DIR, DEFAULT_MNN_MODEL_DIR) ?: DEFAULT_MNN_MODEL_DIR

    fun saveMnnModelDir(dir: String) {
        sp.edit().putString(KEY_MNN_MODEL_DIR, dir.trim()).apply()
    }

    /** MNN 算力后端名（cpu / opencl），对应 config.json 的 backend_type。 */
    fun getMnnBackend(): String =
        sp.getString(KEY_MNN_BACKEND, "cpu") ?: "cpu"

    fun saveMnnBackend(name: String) {
        sp.edit().putString(KEY_MNN_BACKEND, name.trim().lowercase()).apply()
    }

    /** MNN 推理线程数。 */
    fun getMnnThreads(): Int =
        sp.getString(KEY_MNN_THREADS, "4")?.toIntOrNull() ?: 4

    fun saveMnnThreads(v: Int) {
        sp.edit().putString(KEY_MNN_THREADS, v.toString()).apply()
    }

    /** MNN 单轮最大生成 token 数。 */
    fun getMnnMaxOutput(): Int =
        sp.getString(KEY_MNN_MAX_OUTPUT, "4096")?.toIntOrNull() ?: 4096

    fun saveMnnMaxOutput(v: Int) {
        sp.edit().putString(KEY_MNN_MAX_OUTPUT, v.toString()).apply()
    }

    /** MNN 模型显示名：从目录取末级名，空则给通用名。 */
    fun getMnnModelLabel(): String {
        val dir = getMnnModelDir()
        if (dir.isBlank()) return "MNN 模型（未配置）"
        val name = dir.trimEnd('/').substringAfterLast('/')
        return if (name.isBlank()) "MNN 模型" else "MNN · $name"
    }
    
        fun getBackend(): BackendType {
        val s = sp.getString(KEY_BACKEND, null)
        return runCatching { BackendType.valueOf(s ?: "OPENAI") }
            .getOrDefault(BackendType.OPENAI)
    }

    fun setBackend(type: BackendType) {
        sp.edit().putString(KEY_BACKEND, type.name).apply()
    }

    /** OpenAI 兼容服务的 API Key（Ollama 等本地服务可留空）。 */
    fun getOpenAIApiKey(): String = activeProfile()?.apiKey ?: (sp.getString(KEY_OPENAI_API_KEY, "") ?: "")

    fun saveOpenAIApiKey(key: String) {
        sp.edit().putString(KEY_OPENAI_API_KEY, key).apply()
    }

    /** OpenAI 兼容服务基址，例如 "https://api.openai.com/v1" 或 "http://localhost:11434/v1"。 */
    fun getOpenAIBaseUrl(): String =
        activeProfile()?.baseUrl ?: (sp.getString(KEY_OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL) ?: DEFAULT_OPENAI_BASE_URL)

    fun saveOpenAIBaseUrl(url: String) {
        sp.edit().putString(KEY_OPENAI_BASE_URL, url).apply()
    }

    fun getOpenAIModel(): String =
        activeProfile()?.model ?: (sp.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL)

    fun saveOpenAIModel(model: String) {
        sp.edit().putString(KEY_OPENAI_MODEL, model).apply()
    }

    /**
     * 一次性写入 OpenAI 兼容三项配置（初始化向导用）。
     *
     * 必须**同时**写 legacy 键与 active profile：getOpenAIBaseUrl()/getOpenAIModel()
     * 优先读 profile，只写 legacy 会让「向导里填的」与「实际发请求用的」不一致；
     * 而 profile 尚不存在时要靠 legacy 键兜底（profiles() 会按它们建首份档案）。
     */
    fun saveOpenAIConfig(baseUrl: String, apiKey: String, model: String) {
        val b = baseUrl.trim().ifBlank { DEFAULT_OPENAI_BASE_URL }
        val k = apiKey.trim()
        val m = model.trim().ifBlank { DEFAULT_OPENAI_MODEL }
        sp.edit()
            .putString(KEY_OPENAI_BASE_URL, b)
            .putString(KEY_OPENAI_API_KEY, k)
            .putString(KEY_OPENAI_MODEL, m)
            .apply()
        profiles()   // 确保至少存在一份档案（不存在时按上面的 legacy 值创建）
        updateActive { it.copy(baseUrl = b, apiKey = k, model = m) }
    }

    fun getOpenAITemperature(): Double =
        activeProfile()?.temperature
            ?: (sp.getString(KEY_OPENAI_TEMPERATURE, DEFAULT_OPENAI_TEMPERATURE.toString())?.toDoubleOrNull() ?: DEFAULT_OPENAI_TEMPERATURE)

    fun saveOpenAITemperature(temp: Double) {
        sp.edit().putString(KEY_OPENAI_TEMPERATURE, temp.toString()).apply()
    }

    // ═══════════════════════════════════════════
    //  预设（运行模式）选择
    // ═══════════════════════════════════════════

    fun getPreset(): String? = sp.getString(KEY_PRESET, null)

    fun savePreset(id: String) {
        sp.edit().putString(KEY_PRESET, id).apply()
    }

    fun clearPreset() {
        sp.edit().remove(KEY_PRESET).apply()
    }

    fun getOpenAIMaxTokens(): Int =
        activeProfile()?.maxOutput ?: (sp.getString(KEY_OPENAI_MAX_TOKENS, DEFAULT_OPENAI_MAX_TOKENS.toString())?.toIntOrNull() ?: DEFAULT_OPENAI_MAX_TOKENS)

    fun saveOpenAIMaxTokens(tokens: Int) {
        sp.edit().putString(KEY_OPENAI_MAX_TOKENS, tokens.toString()).apply()
    }

    /**
     * 跳过 TLS 证书校验（信任任意服务端证书）。用于自签/内网 CA 的 OpenAI 兼容服务，
     * 此时服务端证书不在系统/内置 CA 信任链中，会触发 CERTIFICATE_UNKNOWN。
     * 默认关闭；仅在可信内网环境开启。
     */
    fun getOpenAIInsecureSkipVerify(): Boolean = activeProfile()?.insecureSkipVerify ?: sp.getBoolean(KEY_OPENAI_INSECURE_SKIP_VERIFY, false)

    fun saveOpenAIInsecureSkipVerify(skip: Boolean) {
        sp.edit().putBoolean(KEY_OPENAI_INSECURE_SKIP_VERIFY, skip).apply()
    }

    /**
     * 思考强度（reasoning_effort）：开启深度思考时附加到请求体。
     *
     * 只设 4 个档位：low / medium / high / xhigh（见 [REASONING_EFFORT_LEVELS]）。
     * "none"（关闭思考）**不属于档位**——开不开思考由聊天页两个 HTML（chat.html /
     * chat_desktop.html）里的「深度思考」开关决定，OpenAIClient 按开关状态补发 none。
     *
     * 历史版本曾允许 "max"，但 SenseNova 等网关的枚举是 low/medium/high/xhigh/none，
     * 发 max 会直接报错：
     * `field ReasoningEffort invalid, should be one of: low, medium, high, xhigh, none`
     * 故读到时用 [normalizeReasoningEffort] 归一化（max→xhigh），避免旧配置继续触发 400。
     */
    fun getOpenAIReasoningEffort(): String {
        val v = activeProfile()?.reasoningEffort
            ?: (sp.getString(KEY_OPENAI_REASONING_EFFORT, DEFAULT_OPENAI_REASONING_EFFORT) ?: DEFAULT_OPENAI_REASONING_EFFORT)
        return normalizeReasoningEffort(v)
    }

    fun saveOpenAIReasoningEffort(effort: String) {
        updateActive { it.copy(reasoningEffort = normalizeReasoningEffort(effort)) }
    }

    /** 归一化思考强度档位：只保留 4 个合法档位，历史脏值降级到最近的合法档。 */
    fun normalizeReasoningEffort(v: String): String = when (v) {
        "low", "medium", "high", "xhigh" -> v
        "max" -> "xhigh"                              // 旧版最高档，多数网关不认
        "minimal" -> "low"
        "none" -> DEFAULT_OPENAI_REASONING_EFFORT     // none 是「关思考」，不是档位
        else -> DEFAULT_OPENAI_REASONING_EFFORT
    }

    // ──────────────────────────────────────────────────────
    //  OpenAI 多配置档案（文件夹样式）
    // ──────────────────────────────────────────────────────

    fun getOpenAIProfiles(): List<OpenAIProfileConfig> = profiles()

    fun getActiveOpenAIProfileId(): String? = sp.getString(KEY_OPENAI_ACTIVE_ID, null)

    fun setActiveOpenAIProfileId(id: String?) {
        sp.edit().putString(KEY_OPENAI_ACTIVE_ID, id).apply()
    }

    fun upsertOpenAIProfile(p: OpenAIProfileConfig) {
        val list = profiles().toMutableList()
        val idx = list.indexOfFirst { it.id == p.id }
        if (idx >= 0) list[idx] = p else list.add(p)
        saveProfiles(list)
        if (sp.getString(KEY_OPENAI_ACTIVE_ID, null) == null) {
            sp.edit().putString(KEY_OPENAI_ACTIVE_ID, p.id).apply()
        }
    }

    fun deleteOpenAIProfile(id: String) {
        val list = profiles().filter { it.id != id }.toMutableList()
        saveProfiles(list)
        if (sp.getString(KEY_OPENAI_ACTIVE_ID, null) == id) {
            sp.edit().putString(KEY_OPENAI_ACTIVE_ID, list.firstOrNull()?.id).apply()
        }
    }

    fun createOpenAIProfile(model: String): OpenAIProfileConfig {
        val p = OpenAIProfileConfig(
            id = java.util.UUID.randomUUID().toString(),
            model = model.ifBlank { DEFAULT_OPENAI_MODEL },
            baseUrl = DEFAULT_OPENAI_BASE_URL,
            apiKey = "",
            temperature = DEFAULT_OPENAI_TEMPERATURE,
            maxOutput = DEFAULT_OPENAI_MAX_TOKENS,
            contextWindow = 0,
            maxInput = 0,
            insecureSkipVerify = false,
            reasoningEffort = DEFAULT_OPENAI_REASONING_EFFORT
        )
        upsertOpenAIProfile(p)
        return p
    }

    fun resetOpenAIProfiles(): OpenAIProfileConfig {
        val p = OpenAIProfileConfig(
            id = java.util.UUID.randomUUID().toString(),
            model = DEFAULT_OPENAI_MODEL,
            baseUrl = DEFAULT_OPENAI_BASE_URL,
            apiKey = "",
            temperature = DEFAULT_OPENAI_TEMPERATURE,
            maxOutput = DEFAULT_OPENAI_MAX_TOKENS,
            contextWindow = 0,
            maxInput = 0,
            insecureSkipVerify = false,
            reasoningEffort = DEFAULT_OPENAI_REASONING_EFFORT
        )
        saveProfiles(listOf(p))
        sp.edit().putString(KEY_OPENAI_ACTIVE_ID, p.id).apply()
        return p
    }

    // ═══════════════════════════════════════════
    //  Web 自动化后端配置
    // ═══════════════════════════════════════════

    /**
     * 取当前生效的 Web 自动化配置（= 激活档案）。
     *
     * 多档案改造后这个「单份」入口仍然保留，且是全 App 的唯一读取点：
     * WebAutomationClient / Driver 只关心「现在用哪套选择器」，不该知道档案有几个。
     * 档案的增删改与切换属于设置页的事，只动 getWebAutomationProfiles 系列。
     */
    fun getWebAutomationConfig(): com.mcp.llm.WebAutomationConfig =
        activeWebAutomationProfile() ?: com.mcp.llm.WebAutomationSites.DEFAULT

    /**
     * 保存整份 Web 自动化配置（写回**当前激活档案**）。
     *
     * 兼容语义：改造前这是「保存那份唯一配置」。现在若还没有任何档案，
     * 会以传入配置为准建一份并激活——旧调用方（ConfigBackup 恢复、兼容 setter）
     * 因此无需改动即可继续工作。
     */
    fun setWebAutomationConfig(config: com.mcp.llm.WebAutomationConfig) {
        val list = waProfiles().toMutableList()
        val id = sp.getString(KEY_WA_ACTIVE_ID, null)
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = config else list.add(config)
        saveWaProfiles(list)
        if (id == null) sp.edit().putString(KEY_WA_ACTIVE_ID, config.id).apply()
    }

    // ── Web 自动化：多档案（与 OpenAI 档案同构）─────────────────────────

    /** 全部 Web 自动化档案。首次调用会把旧的单份配置迁移成一份档案。 */
    fun getWebAutomationProfiles(): List<com.mcp.llm.WebAutomationConfig> = waProfiles()

    fun getActiveWebAutomationProfileId(): String? = sp.getString(KEY_WA_ACTIVE_ID, null)

    /**
     * 切换激活档案。传 null 等于「用列表第一份」。
     *
     * 同步落盘：切档是用户明确的一次选择，异步写在被杀进程时会退回旧档，
     * 用户下次打开发现「切的档没生效」。
     */
    fun setActiveWebAutomationProfileId(id: String?) {
        sp.edit().putString(KEY_WA_ACTIVE_ID, id).commit()
    }

    /** 新增或更新一份档案（按 id 匹配）。 */
    fun upsertWebAutomationProfile(config: com.mcp.llm.WebAutomationConfig) {
        val list = waProfiles().toMutableList()
        val idx = list.indexOfFirst { it.id == config.id }
        if (idx >= 0) list[idx] = config else list.add(config)
        // 同步落盘：这是**用户主动操作**（新建/保存配置）的结果，
        // 用 apply() 时用户点完保存立刻杀进程就会丢——「新建完再启动又没了」。
        saveWaProfilesSync(list)
        if (sp.getString(KEY_WA_ACTIVE_ID, null) == null) {
            sp.edit().putString(KEY_WA_ACTIVE_ID, config.id).commit()
        }
    }

    /** 删除一份档案；删的是激活档案时自动切到列表第一份。 */
    fun deleteWebAutomationProfile(id: String) {
        val list = waProfiles().filter { it.id != id }.toMutableList()
        saveWaProfiles(list)
        // 同步修正激活 id：异步 apply 的间隙里如果进程被杀，会留下指向已删档案的悬空 id。
        // 悬空本身有兜底（activeWebAutomationProfile 会回退到列表第一份），但同步写更省心。
        if (sp.getString(KEY_WA_ACTIVE_ID, null) == id) {
            sp.edit().putString(KEY_WA_ACTIVE_ID, list.firstOrNull()?.id).commit()
        }
    }

    /**
     * 新建一份档案。
     *
     * 默认值沿用当前激活档案的**选择器**（站点大概率还是同一个，用户只需改地址/名字），
     * 但 id 与 name 必须独立，否则列表里两行看起来一模一样、无法区分。
     */
    fun createWebAutomationProfile(name: String): com.mcp.llm.WebAutomationConfig {
        val base = activeWebAutomationProfile() ?: com.mcp.llm.WebAutomationSites.DEFAULT
        val p = base.copy(
            id = java.util.UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "新站点" },
        )
        upsertWebAutomationProfile(p)
        return p
    }

    /** 激活档案；列表为空时返回 null（调用方各自兜底到 DEFAULT）。 */
    private fun activeWebAutomationProfile(): com.mcp.llm.WebAutomationConfig? {
        val list = waProfiles()
        val id = sp.getString(KEY_WA_ACTIVE_ID, null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    /**
     * 读档案列表，并在**首次**访问时完成 v1 → v2 迁移。
     *
     * 迁移策略：把旧的单份 JSON 原样变成列表里的第一份（id/name 保持）。
     * 用 KEY_WA_PROFILES_JSON 是否存在做一次性判据——迁移完就写入了，
     * 后续不会再走这条分支，也不会覆盖用户新建的档案。
     */
    private fun waProfiles(): List<com.mcp.llm.WebAutomationConfig> {
        // 判据是「解析结果为空」而不是 `sp.contains(KEY_WA_PROFILES_JSON)`。
        //
        // 为什么不能用 contains：apply() 是**异步落盘**，进程在写盘前被杀就丢数据；
        // 更糟的是旧版迁移路径每次都先 save 再返回，一旦这次写盘失败，下次冷启动
        // 又走一遍迁移分支 —— 用户看到的就是「新建完，杀掉再启动又没了」的循环。
        // 以「能不能解析出非空列表」为准，任何一次成功落盘都会让迁移永久停止。
        val raw = sp.getString(KEY_WA_PROFILES_JSON, null)
        val parsed = if (raw == null) emptyList()
        else runCatching { parseWaProfiles(raw) }.getOrDefault(emptyList())
        if (raw != null) return parsed

        // 走到这里说明**从未写过**档案列表（raw == null）。
        //
        // 判据必须是「键不存在」，不能是「解析结果为空」——空列表是合法状态：
        // 用户可以把配置全删光。若以空列表为迁移触发条件，删光之后下一次读取
        // 又会凭空冒出 DEFAULT 档案，用户永远删不干净，且每次冷启动都「有配置了」。
        //
        // 迁移用 commit()：只在首次发生，必须落盘后再返回，否则同一进程内紧接着的
        // 读取仍看到 null，会再迁移一次并把用户刚建的档案覆盖掉。
        val legacy = sp.getString(KEY_WA_CONFIG_JSON, null)
        val first = if (legacy == null) com.mcp.llm.WebAutomationSites.DEFAULT
        else parseWaConfig(legacy) ?: com.mcp.llm.WebAutomationSites.DEFAULT
        saveWaProfilesSync(listOf(first))
        if (sp.getString(KEY_WA_ACTIVE_ID, null) == null) {
            sp.edit().putString(KEY_WA_ACTIVE_ID, first.id).commit()
        }
        return listOf(first)
    }

    private fun saveWaProfiles(list: List<com.mcp.llm.WebAutomationConfig>) {
        val arr = JSONArray()
        for (p in list) arr.put(waConfigToJson(p))
        sp.edit().putString(KEY_WA_PROFILES_JSON, arr.toString()).apply()
    }

    /**
     * 同步落盘的版本，只用于一次性迁移。
     *
     * commit() 会阻塞到写盘完成（毫秒级），代价可接受；换来的是「迁移后立刻读到」的确定性。
     * 用 apply() 时若紧接着再调 waProfiles()（同一次冷启动内会发生多次），
     * 读到的仍是空列表 → 再迁移一次 → 列表被反复重置。
     */
    private fun saveWaProfilesSync(list: List<com.mcp.llm.WebAutomationConfig>) {
        val arr = JSONArray()
        for (p in list) arr.put(waConfigToJson(p))
        sp.edit().putString(KEY_WA_PROFILES_JSON, arr.toString()).commit()
    }

    private fun parseWaProfiles(raw: String): List<com.mcp.llm.WebAutomationConfig> {
        val arr = JSONArray(raw)
        val out = ArrayList<com.mcp.llm.WebAutomationConfig>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(waConfigFromJson(o))
        }
        return out
    }

    private fun parseWaConfig(raw: String): com.mcp.llm.WebAutomationConfig? =
        runCatching { waConfigFromJson(JSONObject(raw)) }.getOrNull()

    /**
     * 把一份 Web 自动化配置序列化为 JSON。
     *
     * public 而非 internal：ConfigBackup 在 **:app 模块**，与 :llm 是编译单元级的两个模块，
     * Kotlin 的 internal 只在同一模块内可见——internal 会让 app 侧备份/恢复编译不过。
     * 同理见 [waConfigFromJson]。
     */
    fun waConfigToJson(p: com.mcp.llm.WebAutomationConfig): JSONObject = JSONObject()
        .put("id", p.id)
        .put("name", p.name)
        .put("siteUrl", p.siteUrl)
        .put("newChatSelector", p.newChatSelector)
        .put("inputSelector", p.inputSelector)
        .put("sendSelector", p.sendSelector)
        .put("contentSelector", p.contentSelector)
        .put("thinkingSelector", p.thinkingSelector)
        .put("stopSelector", p.stopSelector)
        .put("sessionListSelector", p.sessionListSelector)
        .put("useEnterToSend", p.useEnterToSend)
        .put("desktopMode", p.desktopMode)

    /**
     * 从 JSON 还原一份配置。
     *
     * contentSelector 兼容旧键 replySelector：v1 时期曾用过后者，
     * 直接丢弃会让老用户的正文选择器凭空消失（表现为「升级后读不到回复」）。
     */
    /** 从 JSON 还原一份配置（与 [waConfigToJson] 互逆，同样因跨模块而 public）。 */
    fun waConfigFromJson(o: JSONObject): com.mcp.llm.WebAutomationConfig {
        val d = com.mcp.llm.WebAutomationSites.DEFAULT
        return com.mcp.llm.WebAutomationConfig(
            id = o.optString("id", d.id),
            name = o.optString("name", d.name),
            siteUrl = o.optString("siteUrl", d.siteUrl),
            newChatSelector = o.optString("newChatSelector", ""),
            inputSelector = o.optString("inputSelector", ""),
            sendSelector = o.optString("sendSelector", ""),
            contentSelector = o.optString("contentSelector", o.optString("replySelector", "")),
            thinkingSelector = o.optString("thinkingSelector", ""),
            stopSelector = o.optString("stopSelector", ""),
            sessionListSelector = o.optString("sessionListSelector", ""),
            useEnterToSend = o.optBoolean("useEnterToSend", false),
            desktopMode = o.optBoolean("desktopMode", false),
        )
    }

    /** 兼容旧接口：读站点地址。 */
    fun getWebAutomationSiteUrl(): String = getWebAutomationConfig().siteUrl

    /** 兼容旧接口：改站点地址（其余字段保留）。 */
    fun setWebAutomationSiteUrl(url: String) {
        setWebAutomationConfig(getWebAutomationConfig().copy(siteUrl = url.trim()))
    }

    fun getOpenAIContextWindow(): Int = activeProfile()?.contextWindow ?: 0

    fun getOpenAIMaxInput(): Int = activeProfile()?.maxInput ?: 0

    // ═══════════════════════════════════════════
    //  DeepSeek 逆向协议：上下文窗口配置
    // ═══════════════════════════════════════════
    // DeepSeek 服务端维护会话上下文，本地无法直接裁剪；但用户可设置上下文窗口
    // 大小，使 Token 看板可视化用量、并在接近阈值时触发「摘要→新建服务端会话」的
    // 自动压缩（session rotation），避免会话满后卡死。

    /** DeepSeek 逆向协议的上下文窗口（tokens）；0 = 未配置（不监控、不自动压缩）。 */
    fun getDeepSeekContextWindow(): Int = sp.getString(KEY_DEEPSEEK_CONTEXT_WINDOW, "0")?.toIntOrNull() ?: 0

    fun saveDeepSeekContextWindow(v: Int) { sp.edit().putString(KEY_DEEPSEEK_CONTEXT_WINDOW, v.toString()).apply() }

    /** DeepSeek 逆向协议的最大输入（tokens）；0 = 不限制。 */
    fun getDeepSeekMaxInput(): Int = sp.getString(KEY_DEEPSEEK_MAX_INPUT, "0")?.toIntOrNull() ?: 0

    fun saveDeepSeekMaxInput(v: Int) { sp.edit().putString(KEY_DEEPSEEK_MAX_INPUT, v.toString()).apply() }

    private fun activeProfile(): OpenAIProfileConfig? {
        val list = profiles()
        val id = sp.getString(KEY_OPENAI_ACTIVE_ID, null)
        return list.firstOrNull { it.id == id } ?: list.firstOrNull()
    }

    private fun profiles(): List<OpenAIProfileConfig> {
        if (!sp.contains(KEY_OPENAI_PROFILES_JSON)) buildFirstProfileFromLegacy()
        val raw = sp.getString(KEY_OPENAI_PROFILES_JSON, null) ?: return emptyList()
        return try { parseProfiles(raw) } catch (_: Exception) { emptyList() }
    }

    private fun saveProfiles(list: List<OpenAIProfileConfig>) {
        val arr = JSONArray()
        for (pr in list) {
            arr.put(JSONObject().apply {
                put("id", pr.id)
                put("model", pr.model)
                put("baseUrl", pr.baseUrl)
                put("apiKey", pr.apiKey)
                put("temperature", pr.temperature)
                put("maxOutput", pr.maxOutput)
                put("contextWindow", pr.contextWindow)
                put("maxInput", pr.maxInput)
                put("insecureSkipVerify", pr.insecureSkipVerify)
                put("reasoningEffort", pr.reasoningEffort)
            })
        }
        sp.edit().putString(KEY_OPENAI_PROFILES_JSON, arr.toString()).apply()
    }

    private fun parseProfiles(raw: String): List<OpenAIProfileConfig> {
        val arr = JSONArray(raw)
        val out = mutableListOf<OpenAIProfileConfig>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                OpenAIProfileConfig(
                    id = o.optString("id", java.util.UUID.randomUUID().toString()),
                    model = o.optString("model", DEFAULT_OPENAI_MODEL),
                    baseUrl = o.optString("baseUrl", DEFAULT_OPENAI_BASE_URL),
                    apiKey = o.optString("apiKey", ""),
                    temperature = o.optDouble("temperature", DEFAULT_OPENAI_TEMPERATURE),
                    maxOutput = o.optInt("maxOutput", DEFAULT_OPENAI_MAX_TOKENS),
                    contextWindow = o.optInt("contextWindow", 0),
                    maxInput = o.optInt("maxInput", 0),
                    insecureSkipVerify = o.optBoolean("insecureSkipVerify", false),
                    reasoningEffort = o.optString("reasoningEffort", DEFAULT_OPENAI_REASONING_EFFORT)
                )
            )
        }
        return out
    }

    private fun buildFirstProfileFromLegacy() {
        val tab = appContext.getSharedPreferences("tab_bar_mode", android.content.Context.MODE_PRIVATE)
        val p = OpenAIProfileConfig(
            id = java.util.UUID.randomUUID().toString(),
            model = sp.getString(KEY_OPENAI_MODEL, DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL,
            baseUrl = sp.getString(KEY_OPENAI_BASE_URL, DEFAULT_OPENAI_BASE_URL) ?: DEFAULT_OPENAI_BASE_URL,
            apiKey = sp.getString(KEY_OPENAI_API_KEY, "") ?: "",
            temperature = sp.getString(KEY_OPENAI_TEMPERATURE, DEFAULT_OPENAI_TEMPERATURE.toString())?.toDoubleOrNull() ?: DEFAULT_OPENAI_TEMPERATURE,
            maxOutput = sp.getString(KEY_OPENAI_MAX_TOKENS, DEFAULT_OPENAI_MAX_TOKENS.toString())?.toIntOrNull() ?: DEFAULT_OPENAI_MAX_TOKENS,
            contextWindow = tab.getInt("context_window_tokens", 0),
            maxInput = tab.getInt("max_input_tokens", 0),
            insecureSkipVerify = sp.getBoolean(KEY_OPENAI_INSECURE_SKIP_VERIFY, false),
            reasoningEffort = sp.getString(KEY_OPENAI_REASONING_EFFORT, DEFAULT_OPENAI_REASONING_EFFORT) ?: DEFAULT_OPENAI_REASONING_EFFORT
        )
        saveProfiles(listOf(p))
        sp.edit().putString(KEY_OPENAI_ACTIVE_ID, p.id).apply()
    }

    private fun updateActive(mut: (OpenAIProfileConfig) -> OpenAIProfileConfig) {
        val list = profiles().toMutableList()
        val id = sp.getString(KEY_OPENAI_ACTIVE_ID, null) ?: return
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        list[idx] = mut(list[idx])
        saveProfiles(list)
    }

    // ═══════════════════════════════════════════
    //  语音配置（转写 / TTS，仅 OpenAI 兼容后端）
    //  每个方向一套：开关、端点 URL（留空则从 Base URL 推导）、
    //  独立 API Key（留空则回退 OpenAI API Key）、模型（留空则用默认）。
    // ═══════════════════════════════════════════

    fun isAudioTranscribeEnabled(): Boolean = sp.getBoolean(KEY_AUDIO_TRANSCRIBE_ENABLED, true)
    fun saveAudioTranscribeEnabled(v: Boolean) { sp.edit().putBoolean(KEY_AUDIO_TRANSCRIBE_ENABLED, v).apply() }

    fun getAudioTranscribeUrl(): String = sp.getString(KEY_AUDIO_TRANSCRIBE_URL, "") ?: ""
    fun saveAudioTranscribeUrl(v: String) { sp.edit().putString(KEY_AUDIO_TRANSCRIBE_URL, v).apply() }

    fun getAudioTranscribeApiKey(): String = sp.getString(KEY_AUDIO_TRANSCRIBE_API_KEY, "") ?: ""
    fun saveAudioTranscribeApiKey(v: String) { sp.edit().putString(KEY_AUDIO_TRANSCRIBE_API_KEY, v).apply() }

    fun getAudioTranscribeModel(): String = sp.getString(KEY_AUDIO_TRANSCRIBE_MODEL, "") ?: ""
    fun saveAudioTranscribeModel(v: String) { sp.edit().putString(KEY_AUDIO_TRANSCRIBE_MODEL, v).apply() }

    fun isAudioTtsEnabled(): Boolean = sp.getBoolean(KEY_AUDIO_TTS_ENABLED, true)
    fun saveAudioTtsEnabled(v: Boolean) { sp.edit().putBoolean(KEY_AUDIO_TTS_ENABLED, v).apply() }

    fun getAudioTtsUrl(): String = sp.getString(KEY_AUDIO_TTS_URL, "") ?: ""
    fun saveAudioTtsUrl(v: String) { sp.edit().putString(KEY_AUDIO_TTS_URL, v).apply() }

    fun getAudioTtsApiKey(): String = sp.getString(KEY_AUDIO_TTS_API_KEY, "") ?: ""
    fun saveAudioTtsApiKey(v: String) { sp.edit().putString(KEY_AUDIO_TTS_API_KEY, v).apply() }

    fun getAudioTtsModel(): String = sp.getString(KEY_AUDIO_TTS_MODEL, "") ?: ""
    fun saveAudioTtsModel(v: String) { sp.edit().putString(KEY_AUDIO_TTS_MODEL, v).apply() }

    /** 气泡朗读是否改用 Android 系统 TTS（不调 OpenAI /audio/speech），默认关闭走 OpenAI TTS。 */
    fun isAudioUseSystemTts(): Boolean = sp.getBoolean(KEY_AUDIO_USE_SYSTEM_TTS, false)
    fun saveAudioUseSystemTts(v: Boolean) { sp.edit().putBoolean(KEY_AUDIO_USE_SYSTEM_TTS, v).apply() }

    // ═══════════════════════════════════════════
    //  全量导出 / 导入（供 ConfigBackup 使用）
    // ═══════════════════════════════════════════

    /**
     * 导出全部「配置类」字段为 JSON。
     *
     * 为什么放在 AuthPrefs 内部而不是 ConfigBackup：加密存储的物理文件
     * （deepseek_auth_enc）密文不可跨机迁移，只能逐项经公开 API 读写。把「哪些字段要
     * 备份」的清单留在本文件，新增配置项时改这里的人顺手补上 export/import，就不必再去
     * :app 模块同步第二份键清单——历史上 LiteRT / MNN / 协议风格等字段正是这样漏掉的。
     *
     * 不含设备标识（manual_device_id / rangers_id）：它们与设备绑定，跨机复制可能触发
     * 登录风控。确有迁移需求时再单独处理。
     */
    fun exportAll(): JSONObject {
        val o = JSONObject()
        // —— 登录态 / 后端选择 ——
        o.put("token", getToken() ?: "")
        o.put("mobile", getMobile() ?: "")
        o.put("backend", getBackend().name)
        o.put("preset", getPreset() ?: "")
        o.put("setupCompleted", isSetupCompleted())
        o.put("disclaimerAck", isDisclaimerAcknowledged())
        o.put("deepseekContextWindow", getDeepSeekContextWindow())
        o.put("deepseekMaxInput", getDeepSeekMaxInput())

        // —— LiteRT 本地模型 ——
        o.put("litertModelPath", getLiteRtModelPath())
        o.put("litertBackend", getLiteRtBackend())
        o.put("litertTemperature", getLiteRtTemperature())
        o.put("litertTopK", getLiteRtTopK())
        o.put("litertTopP", getLiteRtTopP())
        o.put("litertMaxTokens", getLiteRtMaxTokens())
        o.put("litertMaxOutput", getLiteRtMaxOutput())

        // —— MNN 本地模型 ——
        o.put("mnnModelDir", getMnnModelDir())
        o.put("mnnBackend", getMnnBackend())
        o.put("mnnThreads", getMnnThreads())
        o.put("mnnMaxOutput", getMnnMaxOutput())

        // —— 工具 / 协议偏好 ——
        o.put("toolDedupEnabled", isToolDedupEnabled())
        o.put("protocolStyle", getProtocolStylePref())

        // —— OpenAI 兼容：多档案 ——
        o.put("openaiActiveId", getActiveOpenAIProfileId() ?: "")
        val oaArr = JSONArray()
        for (p in getOpenAIProfiles()) {
            oaArr.put(JSONObject().apply {
                put("id", p.id)
                put("model", p.model)
                put("baseUrl", p.baseUrl)
                put("apiKey", p.apiKey)
                put("temperature", p.temperature)
                put("maxOutput", p.maxOutput)
                put("contextWindow", p.contextWindow)
                put("maxInput", p.maxInput)
                put("insecureSkipVerify", p.insecureSkipVerify)
                put("reasoningEffort", p.reasoningEffort)
            })
        }
        o.put("openaiProfiles", oaArr)

        // —— Web 自动化：多档案 + 旧格式兼容副本 ——
        // 同时写两份：webAutomationProfiles 是新格式（全部档案）；webAutomation 是旧格式
        // （只含当前激活的一份），仅为让旧版本 App 导入新备份时不至于读不出站点地址。
        val waProfiles = getWebAutomationProfiles()
        o.put("webAutomationProfiles", JSONArray().apply {
            waProfiles.forEach { p -> put(waConfigToJson(p)) }
        })
        o.put("webAutomationActiveId", getActiveWebAutomationProfileId() ?: "")
        o.put("webAutomation", waConfigToJson(getWebAutomationConfig()))

        // —— 语音（转写 / TTS）——
        o.put("audioTranscribeEnabled", isAudioTranscribeEnabled())
        o.put("audioTranscribeUrl", getAudioTranscribeUrl())
        o.put("audioTranscribeApiKey", getAudioTranscribeApiKey())
        o.put("audioTranscribeModel", getAudioTranscribeModel())
        o.put("audioTtsEnabled", isAudioTtsEnabled())
        o.put("audioTtsUrl", getAudioTtsUrl())
        o.put("audioTtsApiKey", getAudioTtsApiKey())
        o.put("audioTtsModel", getAudioTtsModel())
        o.put("audioUseSystemTts", isAudioUseSystemTts())
        return o
    }

    /**
     * 从 [exportAll] 产出的 JSON 写回配置（与 [exportAll] 互逆）。
     *
     * 未出现的键一律跳过，不改动本机现值——既兼容旧备份（缺新键），也避免把默认值
     * 硬写回去覆盖用户当前设置。token / mobile 为空视为「不覆盖」，避免导入一份未登录
     * 的配置把已有登录态清掉。
     */
    fun importAll(o: JSONObject) {
        // —— 登录态 / 后端选择 ——
        o.optString("token", "").takeIf { it.isNotEmpty() }?.let { saveToken(it) }
        o.optString("mobile", "").takeIf { it.isNotEmpty() }?.let { saveMobile(it) }
        o.optString("backend", "").takeIf { it.isNotEmpty() }?.let { name ->
            runCatching { BackendType.valueOf(name) }.getOrNull()?.let { setBackend(it) }
        }
        if (o.has("preset")) {
            val preset = o.optString("preset", "")
            if (preset.isEmpty()) clearPreset() else savePreset(preset)
        }
        if (o.has("setupCompleted")) setSetupCompleted(o.optBoolean("setupCompleted", false))
        if (o.has("disclaimerAck")) setDisclaimerAcknowledged(o.optBoolean("disclaimerAck", false))
        if (o.has("deepseekContextWindow")) saveDeepSeekContextWindow(o.optInt("deepseekContextWindow", 0))
        if (o.has("deepseekMaxInput")) saveDeepSeekMaxInput(o.optInt("deepseekMaxInput", 0))

        // —— LiteRT 本地模型 ——
        if (o.has("litertModelPath")) saveLiteRtModelPath(o.optString("litertModelPath", ""))
        if (o.has("litertBackend")) saveLiteRtBackend(o.optString("litertBackend", "CPU"))
        if (o.has("litertTemperature")) saveLiteRtTemperature(o.optDouble("litertTemperature", 0.7))
        if (o.has("litertTopK")) saveLiteRtTopK(o.optInt("litertTopK", 40))
        if (o.has("litertTopP")) saveLiteRtTopP(o.optDouble("litertTopP", 0.95))
        if (o.has("litertMaxTokens")) saveLiteRtMaxTokens(o.optInt("litertMaxTokens", 32768))
        if (o.has("litertMaxOutput")) saveLiteRtMaxOutput(o.optInt("litertMaxOutput", 4096))

        // —— MNN 本地模型 ——
        if (o.has("mnnModelDir")) saveMnnModelDir(o.optString("mnnModelDir", DEFAULT_MNN_MODEL_DIR))
        if (o.has("mnnBackend")) saveMnnBackend(o.optString("mnnBackend", "cpu"))
        if (o.has("mnnThreads")) saveMnnThreads(o.optInt("mnnThreads", 4))
        if (o.has("mnnMaxOutput")) saveMnnMaxOutput(o.optInt("mnnMaxOutput", 4096))

        // —— 工具 / 协议偏好 ——
        if (o.has("toolDedupEnabled")) setToolDedupEnabled(o.optBoolean("toolDedupEnabled", true))
        if (o.has("protocolStyle")) setProtocolStylePref(o.optString("protocolStyle", ""))

        // —— OpenAI 兼容：多档案。整体替换后恢复 active id（活动 id 若指向不存在档案，
        // activeProfile() 会自动回退到首份，不会崩）。 ——
        o.optJSONArray("openaiProfiles")?.let { arr ->
            val profiles = mutableListOf<OpenAIProfileConfig>()
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                profiles.add(OpenAIProfileConfig(
                    id = p.optString("id", UUID.randomUUID().toString()),
                    model = p.optString("model", DEFAULT_OPENAI_MODEL),
                    baseUrl = p.optString("baseUrl", DEFAULT_OPENAI_BASE_URL),
                    apiKey = p.optString("apiKey", ""),
                    temperature = p.optDouble("temperature", DEFAULT_OPENAI_TEMPERATURE),
                    maxOutput = p.optInt("maxOutput", DEFAULT_OPENAI_MAX_TOKENS),
                    contextWindow = p.optInt("contextWindow", 0),
                    maxInput = p.optInt("maxInput", 0),
                    insecureSkipVerify = p.optBoolean("insecureSkipVerify", false),
                    reasoningEffort = p.optString("reasoningEffort", DEFAULT_OPENAI_REASONING_EFFORT),
                ))
            }
            if (profiles.isNotEmpty()) {
                setActiveOpenAIProfileId(null)
                profiles.forEach { upsertOpenAIProfile(it) }
                val activeId = o.optString("openaiActiveId", "")
                setActiveOpenAIProfileId(activeId.ifEmpty { profiles.first().id })
            }
        }

        // —— Web 自动化：优先新格式（数组 + 激活 id），否则回退旧格式（单份）。 ——
        val waArr = o.optJSONArray("webAutomationProfiles")
        if (waArr != null && waArr.length() > 0) {
            val list = ArrayList<com.mcp.llm.WebAutomationConfig>(waArr.length())
            for (i in 0 until waArr.length()) {
                waArr.optJSONObject(i)?.let { list.add(waConfigFromJson(it)) }
            }
            // 整体替换而非逐份 upsert：导入语义是「还原到备份时的状态」。
            list.forEach { upsertWebAutomationProfile(it) }
            val ids = list.map { it.id }.toSet()
            getWebAutomationProfiles().filter { it.id !in ids }.forEach { deleteWebAutomationProfile(it.id) }
            val activeId = o.optString("webAutomationActiveId", "")
            setActiveWebAutomationProfileId(activeId.ifEmpty { list.firstOrNull()?.id })
        } else {
            o.optJSONObject("webAutomation")?.let { wa ->
                setWebAutomationConfig(waConfigFromJson(wa))
            }
        }

        // —— 语音 ——
        if (o.has("audioTranscribeEnabled")) saveAudioTranscribeEnabled(o.optBoolean("audioTranscribeEnabled", true))
        if (o.has("audioTranscribeUrl")) saveAudioTranscribeUrl(o.optString("audioTranscribeUrl", ""))
        if (o.has("audioTranscribeApiKey")) saveAudioTranscribeApiKey(o.optString("audioTranscribeApiKey", ""))
        if (o.has("audioTranscribeModel")) saveAudioTranscribeModel(o.optString("audioTranscribeModel", ""))
        if (o.has("audioTtsEnabled")) saveAudioTtsEnabled(o.optBoolean("audioTtsEnabled", true))
        if (o.has("audioTtsUrl")) saveAudioTtsUrl(o.optString("audioTtsUrl", ""))
        if (o.has("audioTtsApiKey")) saveAudioTtsApiKey(o.optString("audioTtsApiKey", ""))
        if (o.has("audioTtsModel")) saveAudioTtsModel(o.optString("audioTtsModel", ""))
        if (o.has("audioUseSystemTts")) saveAudioUseSystemTts(o.optBoolean("audioUseSystemTts", false))
    }

    companion object {
        private const val LEGACY_FILE    = "deepseek_auth"      // 旧版明文文件（仅用于迁移检测）
        private const val ENCRYPTED_FILE = "deepseek_auth_enc" // 新加密文件
        private const val KEY_OPENAI_PROFILES_JSON = "openai_profiles_json_v1"
        private const val KEY_OPENAI_ACTIVE_ID = "openai_active_profile_id"
        private const val KEY_TOKEN  = "token"
        private const val KEY_MOBILE = "mobile"
        private const val KEY_MANUAL_DEVICE_ID = "manual_device_id"
        private const val KEY_PRESET = "preset_selected"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
        private const val KEY_DISCLAIMER_ACK = "disclaimer_ack"

        /** 进程内唯一实例（见 sharedPrefs）。 */
        @Volatile private var cachedPrefs: SharedPreferences? = null
        private val PREFS_LOCK = Any()

        // —— LLM 后端 / OpenAI 兼容配置 ——
        private const val KEY_BACKEND            = "llm_backend"
    // LiteRT 本地模型后端
        private const val KEY_LITERT_MODEL_PATH = "litert_model_path"
        private const val KEY_LITERT_BACKEND = "litert_backend"
        private const val KEY_LITERT_TEMPERATURE = "litert_temperature"
        private const val KEY_LITERT_TOP_K = "litert_top_k"
        private const val KEY_LITERT_TOP_P = "litert_top_p"
    private const val KEY_LITERT_MAX_TOKENS = "litert_max_tokens"
    private const val KEY_LITERT_MAX_OUTPUT = "litert_max_output"
    // MNN 本地模型后端
    private const val KEY_MNN_MODEL_DIR = "mnn_model_dir"
    private const val KEY_MNN_BACKEND = "mnn_backend"
    private const val KEY_MNN_THREADS = "mnn_threads"
    private const val KEY_MNN_MAX_OUTPUT = "mnn_max_output"
    /** MNN 模型默认目录（共享存储，MNN Chat 与 AgentToolbox 都能读）。 */
    private const val DEFAULT_MNN_MODEL_DIR = "/storage/emulated/0/Models/Qwen3.5-4B-MNN"
    private const val KEY_TOOL_DEDUP_ENABLED = "tool_call_dedup_enabled"
    private const val KEY_PROTOCOL_STYLE = "protocol_style"
            private const val KEY_DEEPSEEK_CONTEXT_WINDOW = "deepseek_context_window"
        private const val KEY_DEEPSEEK_MAX_INPUT      = "deepseek_max_input"
        private const val KEY_OPENAI_API_KEY     = "openai_api_key"
        private const val KEY_OPENAI_BASE_URL    = "openai_base_url"
        private const val KEY_OPENAI_MODEL       = "openai_model"
        private const val KEY_OPENAI_TEMPERATURE = "openai_temperature"
        private const val KEY_OPENAI_MAX_TOKENS  = "openai_max_tokens"
        private const val KEY_OPENAI_INSECURE_SKIP_VERIFY = "openai_insecure_skip_verify"
        private const val KEY_OPENAI_REASONING_EFFORT = "openai_reasoning_effort"

        // —— Web 自动化后端配置（整份 JSON 持久化）——
        // 旧键：单份配置（v1）。保留常量只为**迁移读取**，新代码一律走下面的多档案。
        private const val KEY_WA_CONFIG_JSON = "wa_config_json_v1"
        // 多档案：数组 + 当前激活 id，与 OpenAI 档案（KEY_OPENAI_PROFILES_JSON / _ACTIVE_ID）同构
        private const val KEY_WA_PROFILES_JSON = "wa_profiles_json_v1"
        private const val KEY_WA_ACTIVE_ID = "wa_active_profile_id"

        // —— 语音配置（转写 / TTS）——
        private const val KEY_AUDIO_TRANSCRIBE_ENABLED = "audio_transcribe_enabled"
        private const val KEY_AUDIO_TRANSCRIBE_URL     = "audio_transcribe_url"
        private const val KEY_AUDIO_TRANSCRIBE_API_KEY = "audio_transcribe_api_key"
        private const val KEY_AUDIO_TRANSCRIBE_MODEL   = "audio_transcribe_model"
        private const val KEY_AUDIO_TTS_ENABLED        = "audio_tts_enabled"
        private const val KEY_AUDIO_TTS_URL            = "audio_tts_url"
        private const val KEY_AUDIO_TTS_API_KEY        = "audio_tts_api_key"
        private const val KEY_AUDIO_TTS_MODEL          = "audio_tts_model"
        private const val KEY_AUDIO_USE_SYSTEM_TTS     = "audio_use_system_tts"

        const val DEFAULT_OPENAI_BASE_URL    = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL       = "gpt-4o"
        const val DEFAULT_OPENAI_TEMPERATURE = 0.7
        // 思考型模型（DeepSeek/hy3）的 max_tokens 限制「思考+正文」合计输出 token；
        // 4096 在长思考时会把正文挤成 0，默认提到 16384 留足正文额度（用户可在设置中改）。
        const val DEFAULT_OPENAI_MAX_TOKENS  = 16384
        const val DEFAULT_OPENAI_REASONING_EFFORT = "high"

        /** 服务端枚举中表示「不开思考」的值；由聊天页「深度思考」开关决定，不进入设置页档位。 */
        const val REASONING_EFFORT_NONE = "none"

        /** 用户可选的思考强度档位（4 档）。 */
        val REASONING_EFFORT_LEVELS = setOf("low", "medium", "high", "xhigh")
    }
}
