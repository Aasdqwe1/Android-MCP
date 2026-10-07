package com.mcp

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 待办任务管理页：列表 + 状态切换 + 新增 + 删除。
 *
 * 任务数据由 [TodoStore] 持久化；同时注册为 LLM 工具，
 * 可通过 [list_todos] / [add_todo] / [update_todo] 等工具调用。
 */
class TodoFragment : Fragment(R.layout.fragment_todo) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = TodoAdapter()
    private var lastVersion = -1L

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<RecyclerView>(R.id.rvTodos).adapter = adapter
        view.findViewById<FloatingActionButton>(R.id.fabAddTodo).setOnClickListener { showAddDialog() }

        // 启动时立即刷新，并每 2 秒检查版本号变化
        scope.launch {
            while (true) {
                val v = TodoStore.version
                if (v != lastVersion) {
                    lastVersion = v
                    reload(view)
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let { reload(it) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    private fun reload(root: View) {
        scope.launch {
            val ctx   = requireContext()
            val tasks = withContext(Dispatchers.IO) { TodoStore.load(ctx) }
            val stats = withContext(Dispatchers.IO) { TodoStore.stats(ctx) }

            val empty  = root.findViewById<TextView>(R.id.tvTodoEmpty)
            val rv     = root.findViewById<RecyclerView>(R.id.rvTodos)
            val tvSum  = root.findViewById<TextView>(R.id.tvTodoSummary)

            if (tasks.isEmpty()) {
                empty.visibility = View.VISIBLE
                rv.visibility    = View.GONE
                tvSum.visibility = View.GONE
            } else {
                empty.visibility = View.GONE
                rv.visibility    = View.VISIBLE
                adapter.submit(tasks)
            }

            // 摘要行（有内容才显示）
            val summaryText = TodoStatus.values()
                .filter { (stats[it] ?: 0) > 0 }
                .joinToString("  ") { "${it.label}: ${stats[it]}" }
            if (summaryText.isNotEmpty()) {
                tvSum.text = summaryText
                tvSum.visibility = View.VISIBLE
            } else {
                tvSum.visibility = View.GONE
            }
        }
    }

    private fun showAddDialog() {
        val ctx = requireContext()
        MaterialDialogs.prompt(
            context = ctx,
            title = "添加待办任务",
            hint = "任务标题",
            confirmText = "添加",
        ) { title ->
            scope.launch {
                withContext(Dispatchers.IO) { TodoStore.add(ctx, title) }
                reload(requireView())
            }
        }
    }

    // ─────────────────────── Adapter ───────────────────────

    private inner class TodoAdapter : RecyclerView.Adapter<TodoAdapter.VH>() {

        private var items: List<TodoTask> = emptyList()

        fun submit(list: List<TodoTask>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_todo, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val task = items[pos]
            h.tvStatus.text  = task.status.mdBox
            h.tvTitle.text   = task.title
            h.tvId.text      = "#${task.id}"

            if (!task.notes.isNullOrEmpty()) {
                h.tvNotes.visibility = View.VISIBLE
                h.tvNotes.text       = task.notes
            } else {
                h.tvNotes.visibility = View.GONE
            }

            // 标题颜色：已完成/跳过显示为次要色
            val alpha = if (task.status == TodoStatus.DONE || task.status == TodoStatus.SKIPPED) 0.45f else 1f
            h.tvTitle.alpha = alpha

            // 点击状态图标循环切换
            h.tvStatus.setOnClickListener {
                val next = task.status.next()
                scope.launch {
                    withContext(Dispatchers.IO) {
                        TodoStore.updateStatus(requireContext(), task.id, next)
                    }
                    reload(requireView())
                }
            }

            // 删除
            h.btnDelete.setOnClickListener {
                MaterialDialogs.confirmDestructive(
                    context = requireContext(),
                    title = "删除待办",
                    message = "删除「${task.title}」？此操作不可恢复。",
                    confirmText = "删除",
                ) {
                    scope.launch {
                        withContext(Dispatchers.IO) { TodoStore.delete(requireContext(), task.id) }
                        reload(requireView())
                    }
                }
            }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvStatus: TextView = v.findViewById(R.id.tvTodoStatus)
            val tvTitle : TextView = v.findViewById(R.id.tvTodoTitle)
            val tvNotes : TextView = v.findViewById(R.id.tvTodoNotes)
            val tvId    : TextView = v.findViewById(R.id.tvTodoId)
            val btnDelete: TextView = v.findViewById(R.id.btnTodoDelete)
        }
    }
}
