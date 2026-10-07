package com.mcp

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.mcp.deepseek.AuthPrefs

/**
 * 免责声明（研究用途许可证）弹窗入口。
 *
 * 触发时机：首次选择 DeepSeek 逆向后端时（引导页点「下一步」，或设置页切到 DeepSeek）。
 * 确认一次后写入 [AuthPrefs.setDisclaimerAcknowledged]，之后不再弹出。
 *
 * 样式复用 [MarkdownViewerDialog]（Markdown 渲染的底部弹窗），并开启
 * requireScrollToBottom：关闭按钮在正文滚动到底之前不显示，期间无法关闭。
 */
object DisclaimerDialog {

    private const val TAG = "DISCLAIMER"
    private const val ASSET = "LICENSE.md"

    /** 是否还需要展示（未确认过即需要）。 */
    fun needed(auth: AuthPrefs): Boolean = !auth.isDisclaimerAcknowledged()

    /**
     * 展示免责声明。
     *
     * @param onAcknowledged 用户读到底并关闭后的回调（写标记、继续后续流程）。
     *   仅当弹窗被真正关闭（读到底）时触发；进程被杀等异常退出不触发。
     */
    fun show(
        activity: FragmentActivity,
        auth: AuthPrefs,
        title: String = "免责声明与许可",
        onAcknowledged: () -> Unit,
    ) {
        val text = readLicense(activity)
        val dialog = MarkdownViewerDialog.newInstance(
            title = title,
            subtitle = "研究用途许可证 · Research-Only License",
            content = text,
            requireScrollToBottom = true,
        )
        // BottomSheetDialogFragment 没有 Dialog.setOnDismissListener；
        // 用查看器自身的 onClosed 回调（在 onDismiss 里触发）收尾。
        dialog.onClosed = {
            // 走到这里说明已读到底（否则关闭按钮不可见、也无法取消）
            auth.setDisclaimerAcknowledged(true)
            onAcknowledged()
        }
        dialog.show(activity.supportFragmentManager, TAG)
    }

    /** 从 assets 读取许可证文本；失败时返回简短兜底文案，不阻塞流程。 */
    private fun readLicense(context: Context): String = runCatching {
        context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse { e ->
        LogStore.e("MD", "读取 $ASSET 失败: " + e.message)
        "# 免责声明\n\n本项目仅供学习研究使用，禁止商业用途与滥用。使用风险自负。"
    }
}
