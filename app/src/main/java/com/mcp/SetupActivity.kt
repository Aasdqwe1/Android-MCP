package com.mcp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.mcp.core.llm.BackendType
import com.mcp.deepseek.AuthPrefs
import com.mcp.floatwin.OverlayPermission
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * 首次运行初始化向导（安装后第一次启动的落地页）。
 *
 * 为什么需要它：新装的 App 既没有后端选择、也没有任何凭据。此前直接落进会话页，
 * 而会话页只会显示一张 **DeepSeek 登录卡**——即使用户想用 OpenAI 兼容后端，
 * 也因为「未登录 DeepSeek」被挡在门外（旧判定只看 token，不看后端）。
 *
 * 四步：① 选后端 ② 配置凭据（OpenAI 三项 + 测试连接 / DeepSeek 登录）
 * ③ 授予权限（全部文件访问、录音）④ 确认并完成。完成后写 setup_completed 标记，
 * 之后启动直接进 [MainActivity]；设置页可经 [intent] 传 force=true 重新进入。
 *
 * 设计取向：**只警告、不锁死**。任意一步都能「仍然继续」——凭据后续可在设置页补，
 * 而把用户永久锁在一个可能失败的登录页上才是真正的坑。
 */
class SetupActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_FORCE = "force_setup"
        private const val STEP_COUNT = 5

        /** 「准备 Linux 环境」步骤的下标（0 基）。 */
        private const val STEP_PROOT = 3
        private const val TIMEOUT_MS = 15_000

        /** 构造进入本页的 Intent；force=true 时即使已完成初始化也重新走一遍（设置页入口）。 */
        fun intent(context: Context, force: Boolean = false): Intent =
            Intent(context, SetupActivity::class.java).putExtra(EXTRA_FORCE, force)
    }

    private lateinit var auth: AuthPrefs

    /** 录音授权返回后刷新状态文案。 */
    private val recordPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermissionStatus() }

    /** 照片/视频是一组细粒度权限，需要一次性申请（见 [mediaPermissions]）。 */
    private val mediaPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermissionStatus() }

    private var step = 0

    private lateinit var steps: List<View>
    private lateinit var tvTitle: TextView
    private lateinit var tvIndicator: TextView
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton

    // —— 第 1 步 ——
    private lateinit var backendGroup: RadioGroup

    // —— 第 2 步 ——
    private lateinit var openAiPanel: View
    private lateinit var etBaseUrl: TextInputEditText
    private lateinit var etApiKey: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var btnTest: MaterialButton
    private lateinit var tvTestStatus: TextView

    // —— 第 3 步 ——
    private lateinit var btnPermAllFiles: MaterialButton
    private lateinit var btnPermRecord: MaterialButton
    private lateinit var btnPermMedia: MaterialButton
    private lateinit var btnPermOverlay: MaterialButton

    // —— 第 4 步：PRoot 环境准备 ——
    private lateinit var progressProotSetup: com.google.android.material.progressindicator.LinearProgressIndicator
    private lateinit var tvProotSetupStatus: TextView
    /** 安装作业句柄。跑在进程级 [AppScope] 上，离开本页也会继续。 */
    private var prootJob: Job? = null

    // —— 第 5 步 ——
    private lateinit var tvSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CertBypass.apply(this)
        auth = AuthPrefs(this)

        // 已完成初始化且非「重新初始化」：直接放行到主界面，不闪一下向导
        if (auth.isSetupCompleted() && !intent.getBooleanExtra(EXTRA_FORCE, false)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        // 本应用 targetSdk=36，Android 15+ 强制边到边：不显式处理 inset 就会压在通知栏下面。
        // 与 MainActivity 共用同一套窗口状态，保证两个页面在不同 API 上表现一致。
        EdgeToEdge.apply(this)
        setContentView(R.layout.activity_setup)
        bindViews()
        applySafeAreaInsets()
        restoreExistingConfig()

        backendGroup.setOnCheckedChangeListener { _, _ -> applyBackendPanels() }
        btnBack.setOnClickListener { if (step > 0) showStep(step - 1) }
        btnNext.setOnClickListener { onNext() }
        btnTest.setOnClickListener { testConnection() }
        btnPermAllFiles.setOnClickListener { requestAllFilesAccess() }
        btnPermRecord.setOnClickListener { requestRecordAudio() }
        btnPermMedia.setOnClickListener { requestMediaAccess() }
        btnPermOverlay.setOnClickListener { requestOverlayAccess() }

        // 系统返回键：先逐步回退，回到第 1 步再退出（退出不丢已填内容，下次仍从第 1 步开始）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step > 0) showStep(step - 1) else finish()
            }
        })

        showStep(0)
        refreshPermissionStatus()
        // 尽早把 PRoot 解压丢到后台：用户填凭据、授权限的这段时间它就在跑，
        // 走到第 4 步时多半已经就绪，不必干等。
        startProotInstallIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // 「全部文件访问」是跳到系统设置页授权的，回来后必须重算状态
        refreshPermissionStatus()
        if (step == STEP_COUNT - 1) refreshSummary()
    }

    // ═══════════════════════════════════════════
    //  视图绑定与步骤切换
    // ═══════════════════════════════════════════

    private fun bindViews() {
        steps = listOf(
            findViewById(R.id.step1),
            findViewById(R.id.step2),
            findViewById(R.id.step3),
            findViewById(R.id.stepProot),
            findViewById(R.id.step4)
        )
        tvTitle = findViewById(R.id.tvSetupTitle)
        tvIndicator = findViewById(R.id.tvSetupIndicator)
        btnBack = findViewById(R.id.btnSetupBack)
        btnNext = findViewById(R.id.btnSetupNext)

        backendGroup = findViewById(R.id.backendGroup)

        openAiPanel = findViewById(R.id.openAiPanel)
        etBaseUrl = findViewById(R.id.etBaseUrl)
        etApiKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        btnTest = findViewById(R.id.btnTest)
        tvTestStatus = findViewById(R.id.tvTestStatus)

        btnPermAllFiles = findViewById(R.id.btnPermAllFiles)
        btnPermRecord = findViewById(R.id.btnPermRecord)
        btnPermMedia = findViewById(R.id.btnPermMedia)
        btnPermOverlay = findViewById(R.id.btnPermOverlay)

        progressProotSetup = findViewById(R.id.progressProotSetup)
        tvProotSetupStatus = findViewById(R.id.tvProotSetupStatus)

        tvSummary = findViewById(R.id.tvSummary)
    }

    /**
     * 在安全区内渲染：于布局既有的基础内边距之上再叠加系统栏 inset。
     *
     * 顶部让出状态栏 / 刘海，底部让出导航栏；键盘弹出时底部改让键盘高度，
     * 否则「上一步 / 下一步」会被输入法挡住（本页第 2 步有多个输入框）。
     * 基础内边距在监听器外先捕获，避免重复派发时把 inset 累加两次。
     *
     * API 30 以下不取 IME inset：那时窗口仍受 adjustResize 影响，
     * 再叠加一次会被重复缩进（与 MainActivity 的处理保持一致）。
     */
    private fun applySafeAreaInsets() {
        val root = findViewById<View>(R.id.setupRoot)
        val baseTop = root.paddingTop
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            } else 0
            v.setPadding(
                v.paddingLeft,
                baseTop + bars.top,
                v.paddingRight,
                baseBottom + maxOf(bars.bottom, imeBottom)
            )
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** 回填已有配置：重新初始化时不能把用户手上的凭据清空。 */
    private fun restoreExistingConfig() {
        backendGroup.check(
            when (auth.getBackend()) {
                BackendType.OPENAI -> R.id.rbBackendOpenAi
                BackendType.WEB_AUTOMATION -> R.id.rbBackendWebAuto
                BackendType.LITERT -> R.id.rbBackendLiteRt
                BackendType.MNN -> R.id.rbBackendMnn
                else -> R.id.rbBackendOpenAi
            }
        )
        etBaseUrl.setText(auth.getOpenAIBaseUrl())
        etApiKey.setText(auth.getOpenAIApiKey())
        etModel.setText(auth.getOpenAIModel())
        applyBackendPanels()
    }

    private fun showStep(index: Int) {
        step = index
        steps.forEachIndexed { i, v -> v.visibility = if (i == index) View.VISIBLE else View.GONE }
        tvTitle.text = getString(titleOf(index))
        tvIndicator.setText(getString(R.string.setup_step_indicator, index + 1, STEP_COUNT))
        btnBack.isEnabled = index > 0
        btnBack.alpha = if (index > 0) 1f else 0.4f
        btnNext.setText(if (index == STEP_COUNT - 1) R.string.setup_finish else R.string.setup_next)
        if (index == STEP_COUNT - 1) refreshSummary()
        if (index == STEP_PROOT) startProotInstallIfNeeded()
        applyBackendPanels()
    }

    private fun titleOf(index: Int): Int = when (index) {
        0 -> R.string.setup_step1_title
        1 -> R.string.setup_step2_title
        2 -> R.string.setup_step3_title
        3 -> R.string.setup_proot_title
        else -> R.string.setup_step4_title
    }

    private fun selectedBackend(): BackendType = when (backendGroup.checkedRadioButtonId) {
        R.id.rbBackendOpenAi -> BackendType.OPENAI
        R.id.rbBackendWebAuto -> BackendType.WEB_AUTOMATION
        R.id.rbBackendLiteRt -> BackendType.LITERT
        R.id.rbBackendMnn -> BackendType.MNN
        else -> BackendType.OPENAI
    }

    private fun applyBackendPanels() {
        if (!::openAiPanel.isInitialized) return
        val backend = selectedBackend()
        openAiPanel.visibility = if (backend == BackendType.OPENAI) View.VISIBLE else View.GONE
        // Web 自动化无需凭据（登录态在浏览器页里），两个面板都隐藏
    }

    // ═══════════════════════════════════════════
    //  导航 / 校验
    // ═══════════════════════════════════════════

    private fun onNext() {
        if (step < STEP_COUNT - 1) {
            proceedNext()
            return
        }
        completeSetup()
    }

    /** 免责声明（若需）通过后的「下一步」实际逻辑。 */
    private fun proceedNext() {
        val warn = validationWarning()
        if (warn != null) {
            // 只警告不拦截：把用户永久锁在一个可能失败的登录页上才是真正的坑
            MaterialDialogs.confirm(
                context = this,
                title = getString(R.string.setup_continue_title),
                message = warn,
                confirmText = getString(R.string.setup_continue_ok),
                cancelText = getString(R.string.setup_continue_cancel),
            ) { advance() }
            return
        }
        advance()
    }

    private fun advance() {
        if (step == 1) persistOpenAiConfigIfNeeded()
        showStep(step + 1)
    }

    /** 进入下一步前的警告文案；返回 null 表示配置完整。 */
    private fun validationWarning(): String? {
        if (step != 1) return null
        val b = selectedBackend()
        if (b == BackendType.LITERT) {
            // 本地模型：仅要求已导入模型文件，不要求任何登录/凭据
            return if (auth.getLiteRtModelPath().isBlank())
                "请先导入 .litertlm 模型文件（设置 → 本地模型）" else null
        }
        if (b == BackendType.MNN) {
            // MNN 同样只需模型目录已配置，无需登录/凭据
            return if (auth.getMnnModelDir().isBlank())
                "请先配置 MNN 模型目录（设置 → MNN 本地模型）" else null
        }
        return if (b == BackendType.OPENAI) {
            if (etBaseUrl.text.toString().isBlank()) getString(R.string.setup_warn_no_base_url) else null
        } else {
            null
        }
    }

    private fun persistOpenAiConfigIfNeeded() {
        if (selectedBackend() != BackendType.OPENAI) return
        auth.saveOpenAIConfig(
            etBaseUrl.text.toString(),
            etApiKey.text.toString(),
            etModel.text.toString()
        )
        // 回写规范化后的值：空 URL/模型会落到默认值，界面要与实际保存的一致
        etBaseUrl.setText(auth.getOpenAIBaseUrl())
        etModel.setText(auth.getOpenAIModel())
    }

    private fun completeSetup() {
        val backend = selectedBackend()
        if (backend == BackendType.OPENAI) persistOpenAiConfigIfNeeded()
        auth.setBackend(backend)
        auth.setSetupCompleted(true)
        LogStore.i("SETUP", "初始化完成 backend=$backend")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ═══════════════════════════════════════════
    //  OpenAI 兼容：测试连接
    // ═══════════════════════════════════════════

    private fun testConnection() {
        val base = etBaseUrl.text.toString().trim()
            .ifBlank { AuthPrefs.DEFAULT_OPENAI_BASE_URL }
        val model = etModel.text.toString().trim()
            .ifBlank { AuthPrefs.DEFAULT_OPENAI_MODEL }
        etBaseUrl.setText(base)
        etModel.setText(model)
        persistOpenAiConfigIfNeeded()

        btnTest.isEnabled = false
        tvTestStatus.setText(R.string.setup_testing)
        val key = etApiKey.text.toString().trim()
        thread {
            val result = runCatching { probe(base, key, model) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnTest.isEnabled = true
                tvTestStatus.text = result.getOrElse { "连接失败：${it.message ?: it}" }
            }
        }
    }

    /**
     * 探测端点可用性：先 `GET /models`（最轻、不消耗 token）；该方法未实现时
     * （404/405/400）退化为一次 `max_tokens=1` 的最小对话请求——很多自建/本地服务
     * 只实现了 `/chat/completions`。
     *
     * baseUrl 未带 `/v1` 时再试一次追加 `/v1` 的候选，避免用户少写一段路径就判失败。
     */
    private fun probe(baseUrl: String, apiKey: String, model: String): String {
        var base = baseUrl.trim().trimEnd('/').removeSuffix("/chat/completions")
        if (base.isEmpty()) base = AuthPrefs.DEFAULT_OPENAI_BASE_URL
        val candidates = if (base.endsWith("/v1")) listOf(base) else listOf(base, "$base/v1")

        var lastCode = 0
        var lastBody = ""
        for (b in candidates) {
            val (c1, b1) = http("$b/models", apiKey, "GET")
            if (c1 in 200..299) {
                val n = runCatching {
                    JSONObject(b1).optJSONArray("data")?.length() ?: 0
                }.getOrDefault(0)
                return if (n > 0) "连接成功：$b（/models 返回 $n 个模型）" else "连接成功：$b（/models 可用）"
            }
            lastCode = c1
            lastBody = b1
            if (c1 == 401 || c1 == 403) {
                return "连接失败：HTTP $c1 鉴权被拒，请检查 API Key。${snippet(b1)}"
            }
            if (c1 == 404 || c1 == 405 || c1 == 400) {
                val payload = JSONObject().apply {
                    put("model", model)
                    put("stream", false)
                    put("max_tokens", 1)
                    put("messages", JSONArray().put(JSONObject().apply {
                        put("role", "user")
                        put("content", "ping")
                    }))
                }.toString()
                val (c2, b2) = http("$b/chat/completions", apiKey, "POST", payload)
                if (c2 in 200..299) return "连接成功：$b（/chat/completions 可用，模型 $model）"
                lastCode = c2
                lastBody = b2
                if (c2 == 401 || c2 == 403) {
                    return "连接失败：HTTP $c2 鉴权被拒，请检查 API Key。${snippet(b2)}"
                }
            }
        }
        return "连接失败：HTTP $lastCode ${snippet(lastBody)}（已尝试 $base 与 /v1 前缀）"
    }

    /** 单次 HTTP 调用；走 [CertBypass] 扩展后的信任库（与 App 其余请求一致）。 */
    private fun http(
        url: String,
        apiKey: String,
        method: String,
        jsonBody: String? = null
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            if (jsonBody != null) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun snippet(body: String): String =
        if (body.isBlank()) "" else "body=" + body.take(200)

    // ═══════════════════════════════════════════
    //  第 4 步：PRoot Debian 环境（后台安装）
    // ═══════════════════════════════════════════

    /**
     * 在引导阶段就把 PRoot Debian 环境装上。
     *
     * 以前它是「首次进主界面」时被动触发的，而且那条链全在主线程：
     *   MainActivity.onCreate → ToolsFragment.onViewCreated → ToolRuntimeHolder.get
     *   → SkillManager.discoverAndLoad → installPythonDependencies → executeBash
     *   → runBlocking { 解压整个 Debian rootfs }
     * 结果就是首次启动整段黑屏卡死。现在改到这里主动、异步地做掉。
     *
     * 安装跑在进程级 [AppScope] 上：用户点「下一步」离开本页不会被取消；
     * 进度同时写入 [InstallTaskStore]，设置页能看到同一份状态。
     * 幂等：已就绪直接返回，进行中不重复启动。
     */
    private fun startProotInstallIfNeeded() {
        if (ProotEnvironment.isReady(this)) {
            renderProotState(getString(R.string.setup_proot_done), indeterminate = false, progress = 100)
            return
        }
        if (prootJob?.isActive == true) return

        val appCtx = applicationContext
        InstallTaskStore.startTask(appCtx, InstallTaskStore.TaskType.PROOT_INSTALL)
        renderProotState(getString(R.string.setup_proot_running), indeterminate = true, progress = 0)

        prootJob = AppScope.launch {
            val ok = ProotEnvironment.ensureInitialized(appCtx) { msg ->
                // 进度回调来自 IO 线程：先落盘（设置页据此恢复），再回主线程更新界面
                val pct = Regex("(\\d+)%").find(msg)?.groupValues?.get(1)?.toIntOrNull()
                if (pct != null) InstallTaskStore.updateProgress(appCtx, pct, msg.take(80))
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        renderProotState(msg.take(120), indeterminate = pct == null, progress = pct ?: 0)
                    }
                }
            }
            if (ok) {
                InstallTaskStore.finishTask(appCtx)
                LogStore.i("SETUP", "引导阶段 PRoot Debian 环境已就绪")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        renderProotState(getString(R.string.setup_proot_done), indeterminate = false, progress = 100)
                    }
                }
            } else {
                val err = ProotEnvironment.lastError ?: "未知原因"
                InstallTaskStore.failTask(appCtx, err.take(120))
                LogStore.e("SETUP", "引导阶段 PRoot 安装失败：$err")
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        renderProotState(getString(R.string.setup_proot_failed, err), indeterminate = false, progress = 0)
                    }
                }
            }
        }
    }

    /** 更新 PRoot 步骤的进度显示（非确定进度时只换文案）。 */
    private fun renderProotState(text: String, indeterminate: Boolean, progress: Int) {
        if (!::progressProotSetup.isInitialized) return
        progressProotSetup.isIndeterminate = indeterminate
        if (!indeterminate) progressProotSetup.setProgressCompat(progress, true)
        tvProotSetupStatus.text = text
    }

    // ═══════════════════════════════════════════
    //  权限
    // ═══════════════════════════════════════════

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true

    private fun hasRecordAudio(): Boolean = isGranted(Manifest.permission.RECORD_AUDIO)

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 照片/视频按系统版本取权限，不能只写一种：
     *  - 14+：细粒度媒体权限，外加 READ_MEDIA_VISUAL_USER_SELECTED —— 用户在系统对话框里选
     *    「仅选择部分照片」时**只授予后一项**，不申请它就永远显示「未授予」；
     *  - 13：READ_MEDIA_IMAGES / READ_MEDIA_VIDEO；
     *  - 12 及以下：退回 READ_EXTERNAL_STORAGE。
     */
    private fun mediaPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** 任一媒体权限已授予即算通过：用户可能只授权了「部分照片」。 */
    private fun hasMediaAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            isGranted(Manifest.permission.READ_MEDIA_IMAGES) ||
                isGranted(Manifest.permission.READ_MEDIA_VIDEO) ||
                isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            isGranted(Manifest.permission.READ_MEDIA_IMAGES) ||
                isGranted(Manifest.permission.READ_MEDIA_VIDEO)
        // 12 及以下：全面文件访问已覆盖读取媒体，无需再单独授予
        else -> isGranted(Manifest.permission.READ_EXTERNAL_STORAGE) || hasAllFilesAccess()
    }

    private fun refreshPermissionStatus() {
        if (!::btnPermAllFiles.isInitialized) return
        btnPermAllFiles.text = permLabel(R.string.setup_perm_all_files, hasAllFilesAccess())
        btnPermRecord.text = permLabel(R.string.setup_perm_record, hasRecordAudio())
        btnPermMedia.text = permLabel(R.string.setup_perm_media, hasMediaAccess())
        btnPermOverlay.text = permLabel(R.string.setup_perm_overlay, OverlayPermission.granted(this))
    }

    /** 申请悬浮窗权限（跳系统设置页）；已授权时只刷新状态。 */
    private fun requestOverlayAccess() {
        if (OverlayPermission.granted(this)) {
            refreshPermissionStatus()
            return
        }
        OverlayPermission.request(this)
    }

    private fun permLabel(titleRes: Int, granted: Boolean): String =
        getString(titleRes) + " — " +
            getString(if (granted) R.string.setup_granted else R.string.setup_not_granted)

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasAllFilesAccess()) {
            refreshPermissionStatus()
            return
        }
        // 优先跳到本应用的授权页；部分 ROM 没有该 Activity，退化到总列表页
        val appPage = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:$packageName"))
        runCatching { startActivity(appPage) }.onFailure {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }

    private fun requestRecordAudio() {
        if (hasRecordAudio()) {
            refreshPermissionStatus()
            return
        }
        recordPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestMediaAccess() {
        if (hasMediaAccess()) {
            refreshPermissionStatus()
            return
        }
        mediaPermLauncher.launch(mediaPermissions())
    }

    // ═══════════════════════════════════════════
    //  完成页摘要
    // ═══════════════════════════════════════════

    private fun refreshSummary() {
        if (!::tvSummary.isInitialized) return
        val backend = selectedBackend()
        val sb = StringBuilder()
        sb.append(
            getString(
                R.string.setup_summary_backend,
                getString(
                    when (backend) {
                        BackendType.OPENAI -> R.string.settings_backend_openai
                        BackendType.LITERT -> R.string.settings_backend_litert
                        else -> R.string.settings_backend_openai
                    }
                )
            )
        )
        if (backend == BackendType.OPENAI) {
            val model = etModel.text.toString().trim().ifBlank { auth.getOpenAIModel() }
            sb.append("\n").append(getString(R.string.setup_summary_model, model))
            sb.append("\n").append(etBaseUrl.text.toString().trim().ifBlank { auth.getOpenAIBaseUrl() })
        }

        val granted = buildList {
            if (hasAllFilesAccess()) add(getString(R.string.setup_perm_all_files))
            if (hasRecordAudio()) add(getString(R.string.setup_perm_record))
            if (hasMediaAccess()) add(getString(R.string.setup_perm_media))
        }
        sb.append("\n").append(
            getString(
                R.string.setup_summary_perm,
                if (granted.isEmpty()) getString(R.string.setup_summary_perm_none)
                else granted.joinToString("、")
            )
        )
        tvSummary.text = sb.toString()
    }
}
