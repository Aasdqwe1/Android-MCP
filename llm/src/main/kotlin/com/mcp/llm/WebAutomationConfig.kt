package com.mcp.llm

/**
 * Web 自动化站点适配配置：一套 DOM 规则，**全部由用户填写**。
 *
 * 设计取向：不再硬编码任何站点的选择器——网页版 AI 的 DOM 变动频繁，
 * 且各家站点差异大，写死在代码里只会频繁失效且无法覆盖长尾站点。
 * 因此这里只提供字段与说明，实际取值来自设置页用户输入。
 *
 * 字段与用途的对应（驱动实现见 WebAutomationDriverImpl）：
 *  - [newChatSelector]      新建对话按钮：每次新会话时点击（可空 = 不点，用当前会话）
 *  - [inputSelector]        输入框：写入 prompt（textarea / contenteditable 均可）
 *  - [sendSelector]         发送按钮：点击发送（可空 = 用回车发送）
 *  - [contentSelector]      正文容器：读取模型最终回答（取最后一个匹配项为最新）
 *  - [thinkingSelector]     思考容器：站点把思考过程单独渲染时配置（可空）
 *  - [stopSelector]         停止按钮：出现即表示仍在生成中，用于判断完成
 *  - [sessionListSelector]  会话列表容器：会话页按它读取**网页上的会话列表**并支持点击切换；
 *                           未配置「新建对话」按钮时，驱动也会点它的第一条（最新会话）
 */
data class WebAutomationConfig(
    /** 站点标识（用于持久化与 UI 展示）。 */
    val id: String = "custom",
    /** 展示名。 */
    val name: String = "自定义站点",
    /** 站点地址。 */
    val siteUrl: String = "",

    /** 新建对话按钮选择器；空表示不新建，直接用当前会话。 */
    val newChatSelector: String = "",
    /** 输入框选择器（必填）。 */
    val inputSelector: String = "",
    /** 发送按钮选择器；空表示用回车发送。 */
    val sendSelector: String = "",
    /** 正文（回答）容器选择器（必填）：取最后一个匹配项为最新回答。 */
    val contentSelector: String = "",
    /** 思考容器选择器（可空）：站点把思考过程单独渲染时配置；空表示不单独取思考。 */
    val thinkingSelector: String = "",
    /** 停止生成按钮选择器；出现即视为「仍在生成」。 */
    val stopSelector: String = "",
    /**
     * 会话列表容器选择器（**会话页的会话来源**）。
     *
     * 两个用途：
     *  1. 会话页按它读取网页上的会话列表（Web 自动化以网页为准，本地 JSON 只作记录），
     *     点某条会话时也按它点击网页里对应的条目；
     *  2. 未配置 [newChatSelector] 时，驱动点击该容器下的第一条（最新会话），
     *     避免停在站点落地页/空会话上导致输入框找不到。
     *
     * 填容器本身（其直接子节点即各条会话），如 `#conv-list`、`nav[aria-label=会话]`。
     */
    val sessionListSelector: String = "",

    /** 是否用回车发送（部分站点按钮不稳定或压根没有发送按钮）。 */
    val useEnterToSend: Boolean = false,

    /**
     * 是否使用桌面 UA（**全局**，不只是自动化会话）。
     *
     * 部分站点（如智谱清言）会按 UA 把移动端重定向到功能受限的 mini 版，
     * 开启后内嵌浏览器与自动化会话都伪装成桌面 Chrome，即可拿到完整版界面。
     * 切换即时生效：活会话就地改 UA 并重载已加载的页面（不再靠"销毁旧会话等重建"）。
     * 代价：页面按桌面宽度渲染，手机屏上可能偏小、需横向滚动。
     */
    val desktopMode: Boolean = false,
) {
    /** 必填项是否齐全（input + content 是驱动工作的最低要求）。 */
    val isUsable: Boolean get() = siteUrl.isNotBlank() && inputSelector.isNotBlank() && contentSelector.isNotBlank()
}

/**
 * 默认配置：**只有站点地址，选择器全空**，等用户在设置页填写。
 *
 * 为什么不再内置各站点模板：
 *  - 网页版 DOM 随版本频繁变化，内置值很快失效，反而误导用户；
 *  - 各家站点差异大，内置只能覆盖少数，长尾仍要手填；
 *  - 让用户明确「这些是我配的」，失效时知道去哪里改。
 */
object WebAutomationSites {

    /**
     * 默认站点地址：App 内置起始页。
     *
     * 为什么不用某个具体站点：
     *  - 各站点 DOM 差异大，默认填哪个都会让另外的用户以为「坏了」；
     *  - 起始页里可直接输入网址/搜索，用户第一眼就知道该做什么；
     *  - 且它一定加载得出来（本地 asset），不会因网络/登录态白屏。
     */
    const val DEFAULT_SITE_URL = "file:///android_asset/browser_home.html"

    /** 出厂默认配置：仅站点地址，选择器待填。 */
    val DEFAULT: WebAutomationConfig = WebAutomationConfig(
        id = "custom",
        name = "自定义站点",
        siteUrl = DEFAULT_SITE_URL,
    )
}
