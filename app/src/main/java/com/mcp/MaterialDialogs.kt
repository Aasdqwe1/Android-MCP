package com.mcp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * 统一的对话框构件 —— 让「删除 / 添加 / 移除」这类操作在视觉与交互上一致。
 *
 * 设计要点：
 *  - 用 MaterialAlertDialogBuilder（Material3 圆角、留白、按钮排版）；
 *  - 破坏性操作用主题 colorError 色按钮，明确后果；
 *  - 输入表单用 TextInputLayout（浮动标签 + 错误提示），不再用裸 EditText + hint。
 *
 * 四种形态，覆盖全 App 的全部弹窗诉求 —— 不要在业务代码里直接 new
 * AlertDialog.Builder / MaterialAlertDialogBuilder，样式会飘：
 *
 *   confirmDestructive  破坏性确认（删除 / 移除 / 清空）
 *   confirm             普通确认（继续 / 保存），确认键用主色
 *   prompt              单个文本输入（重命名 / 添加待办 / 新建配置）
 *   choose              列表选择（会话操作 / 窗口切换）
 *   alert               纯提示（JS alert / 校验警告），只有「知道了」
 */
object MaterialDialogs {

    // ─────────────────────── 内部工具 ───────────────────────

    /**
     * 从主题解析颜色（优先 colorError / colorPrimary 这类语义色，不写死色值）。
     *
     * 之前破坏性按钮写的是 android.R.color.holo_red_dark，浅色主题下过暗、
     * 深色主题下对比度不足，和界面其余部分不搭。改走主题属性，深浅色自动适配。
     */
    private fun themeColor(context: Context, attrRes: Int, fallback: Int): Int {
        val tv = android.util.TypedValue()
        if (!context.theme.resolveAttribute(attrRes, tv, true)) return fallback
        return if (tv.resourceId != 0) androidx.core.content.ContextCompat.getColor(context, tv.resourceId)
        else tv.data
    }

    /**
     * 统一的按钮着色 —— Material3 的 alert dialog 里有三种按钮槽位。
     *
     * 必须在 show() 之后调用，否则 getButton 返回 null（按钮尚未创建）。
     */
    private fun tintButtons(
        dialog: AlertDialog,
        context: Context,
        positiveIsDestructive: Boolean = false,
    ) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            if (positiveIsDestructive) {
                themeColor(context, androidx.appcompat.R.attr.colorError, 0xFFB3261E.toInt())
            } else {
                themeColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF6750A4.toInt())
            }
        )
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
            themeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF49454F.toInt())
        )
    }

    /** 标题 + 正文的共用内容视图（间距与字号统一在 dialog_confirm.xml 里）。 */
    private fun titleMessageView(context: Context, title: String, message: String): View {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
        view.findViewById<TextView>(R.id.tvConfirmTitle).text = title
        view.findViewById<TextView>(R.id.tvConfirmMessage).text = message
        return view
    }

    /**
     * 破坏性操作确认框。
     *
     * @param confirmText 确认按钮文案（如「删除」「移除」）
     * @param onConfirm   确认回调
     */
    fun confirmDestructive(
        context: Context,
        title: String,
        message: String,
        confirmText: String,
        // 这两个带默认值，想改文案或接取消回调时用命名参数传入即可；
        // onConfirm 放在最后，好让调用方用尾随 lambda 写确认逻辑。
        cancelText: String = "取消",
        onCancel: (() -> Unit)? = null,
        onConfirm: () -> Unit,
    ) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(titleMessageView(context, title, message))
            .setNegativeButton(cancelText) { _, _ -> onCancel?.invoke() }
            .setPositiveButton(confirmText) { _, _ -> onConfirm() }
            .setOnCancelListener { onCancel?.invoke() }
            .create()
        dialog.show()
        tintButtons(dialog, context, positiveIsDestructive = true)
    }

    /**
     * 普通确认框（非破坏性）。
     *
     * 与 [confirmDestructive] 的唯一差别是确认键用主色，适用于「继续 / 保存 / 确定」
     * 这类不造成数据丢失的动作（如引导页的「仍然继续」）。
     */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "确定",
        cancelText: String = "取消",
        onConfirm: () -> Unit,
    ) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(titleMessageView(context, title, message))
            .setNegativeButton(cancelText, null)
            .setPositiveButton(confirmText) { _, _ -> onConfirm() }
            .create()
        dialog.show()
        tintButtons(dialog, context)
    }

    /**
     * 单输入框弹窗（重命名 / 添加待办 / 新建配置共用）。
     *
     * 用 dialog_prompt.xml：一个 TextInputLayout + 一个 TextInputEditText，
     * 与表单弹窗同一套浮动标签样式，不再出现裸 EditText 撑在对话框里。
     *
     * @param hint        浮动标签文案
     * @param value       初始值
     * @param confirmText 确认按钮文案
     * @param required    为空时是否拦截并提示
     * @param onValidated 校验通过后回调（已 trim）
     */
    fun prompt(
        context: Context,
        title: String,
        hint: String,
        value: String = "",
        confirmText: String = "确定",
        required: Boolean = true,
        onValidated: (String) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_prompt, null)
        val til = view.findViewById<TextInputLayout>(R.id.tilPrompt)
        val et = view.findViewById<TextInputEditText>(R.id.etPrompt)
        til.hint = hint
        et.setText(value)
        et.setSelection(et.text?.length ?: 0)

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText, null)  // 先不设回调，show 后接管以支持校验拦截
            .create()
        dialog.show()
        tintButtons(dialog, context)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val text = et.text?.toString()?.trim().orEmpty()
            if (required && text.isEmpty()) {
                til.error = "不能为空"
                return@setOnClickListener
            }
            til.error = null
            dialog.dismiss()
            onValidated(text)
        }
    }

    /**
     * 列表选择弹窗（会话操作 / 窗口切换共用）。
     *
     * 用 dialog_list.xml 承载选项：标题固定在顶部，列表可滚动且带最大高度，
     * 选项行高与颜色统一 —— 不再用 AlertDialog 内置 setItems（那套排版由系统决定，
     * 深浅色与圆角容易和其他弹窗不一致）。
     *
     * @param items     选项文案
     * @param destructiveIndexes 需要标红的选项下标（如「删除」那一项）
     * @param onSelect  选中回调
     */
    fun choose(
        context: Context,
        title: String,
        items: List<String>,
        destructiveIndexes: Set<Int> = emptySet(),
        onSelect: (Int) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_list, null)
        view.findViewById<TextView>(R.id.tvListTitle).text = title
        val list = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDialogList)
        val errorColor = themeColor(context, androidx.appcompat.R.attr.colorError, 0xFFB3261E.toInt())
        val normalColor = themeColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(view)
            .setNegativeButton("取消", null)
            .create()
        dialog.show()
        tintButtons(dialog, context)

        list.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        list.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val row = LayoutInflater.from(parent.context).inflate(R.layout.item_dialog_choice, parent, false)
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(row) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val tv = holder.itemView as TextView
                tv.text = items[position]
                tv.setTextColor(if (position in destructiveIndexes) errorColor else normalColor)
                tv.setOnClickListener {
                    dialog.dismiss()
                    onSelect(position)
                }
            }

            override fun getItemCount() = items.size
        }
    }

    /**
     * 纯提示弹窗（JS alert / 校验警告共用）。
     *
     * 只有一个「知道了」按钮，且按钮用主色 —— 与确认类弹窗视觉一致，
     * 但语义上明确「这不是个要你二选一的框」。
     *
     * @param cancelable 是否允许返回键/点外部关闭（JS alert 需传 false 以同步结果）
     */
    fun alert(
        context: Context,
        title: String,
        message: String,
        confirmText: String = "知道了",
        cancelable: Boolean = true,
        onDismiss: (() -> Unit)? = null,
    ) {
        val dialog = MaterialAlertDialogBuilder(context)
            .setView(titleMessageView(context, title, message))
            .setPositiveButton(confirmText, null)
            .setCancelable(cancelable)
            .create()
        dialog.show()
        tintButtons(dialog, context)
        // 用统一入口接管点击：无论是点按钮还是返回键取消，都走同一个收尾回调
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            dialog.dismiss()
            onDismiss?.invoke()
        }
        dialog.setOnDismissListener { onDismiss?.invoke() }
    }

    /** 表单可选复选框定义。 */
    data class CheckboxSpec(val label: String, val checked: Boolean = false)

    /** 表单字段定义。 */
    data class Field(
        val hint: String,
        val value: String = "",
        val required: Boolean = false,
        val helper: String = "",
    )

    /**
     * 表单输入对话框（最多 3 个字段，对应 dialog_mcp_add.xml 的布局）。
     *
     * @param onValidated 校验通过后回调，参数为各字段值（按顺序）
     */
    fun form(
        context: Context,
        title: String,
        hint: String?,
        fields: List<Field>,
        confirmText: String,
        /**
         * 可选复选框：非 null 时在字段下方显示一个勾选项，
         * 其初始值即为 [CheckboxSpec.checked]，结果经 onValidated 第二参回传。
         *
         * 之所以给 form 加这个能力而非另起一个对话框：带勾选项的表单是常见形态
         * （如「信任自签名证书」），单独实现会导致样式与校验逻辑各写一份。
         */
        checkbox: CheckboxSpec? = null,
        onValidated: (List<String>, Boolean) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_mcp_add, null)
        val tvHint = view.findViewById<TextView>(R.id.tvFormHint)
        if (hint.isNullOrBlank()) tvHint.visibility = View.GONE else {
            tvHint.visibility = View.VISIBLE
            tvHint.text = hint
        }

        val tils = listOf<TextInputLayout>(
            view.findViewById(R.id.tilField1),
            view.findViewById(R.id.tilField2),
            view.findViewById(R.id.tilField3),
        )
        val ets = listOf<TextInputEditText>(
            view.findViewById(R.id.etField1),
            view.findViewById(R.id.etField2),
            view.findViewById(R.id.etField3),
        )

        // 按 fields 数量显示/隐藏，避免空框残留
        tils.forEachIndexed { i, til ->
            val f = fields.getOrNull(i)
            if (f == null) {
                til.visibility = View.GONE
            } else {
                til.visibility = View.VISIBLE
                til.hint = f.hint
                til.helperText = f.helper.takeIf { it.isNotBlank() }
                ets[i].setText(f.value)
            }
        }

        // 可选复选框：按 spec 决定显示与初值
        val cb = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.cbTrustSelfSigned)
        if (checkbox == null) {
            cb.visibility = View.GONE
        } else {
            cb.visibility = View.VISIBLE
            cb.text = checkbox.label
            cb.isChecked = checkbox.checked
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton(confirmText, null)  // 先不设回调，show 后接管以支持校验拦截
            .create()
        dialog.show()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            // 校验：必填项不能为空；错误直接显示在对应输入框下方（不弹 Toast）
            var ok = true
            fields.forEachIndexed { i, f ->
                if (!f.required) return@forEachIndexed
                if (ets[i].text.isNullOrBlank()) {
                    tils[i].error = "不能为空"
                    ok = false
                } else {
                    tils[i].error = null
                }
            }
            if (!ok) return@setOnClickListener
            val values = fields.indices.map { i -> ets[i].text?.toString().orEmpty().trim() }
            val checked = checkbox != null && cb.isChecked
            dialog.dismiss()
            onValidated(values, checked)
        }
    }
}
