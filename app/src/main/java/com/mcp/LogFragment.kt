package com.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 日志页：订阅全局日志总线 [LogStore]，用 RecyclerView 增量更新，避免全量重绘卡顿。
 */
class LogFragment : Fragment(R.layout.fragment_log) {

    private val items = ArrayList<LogEntry>()
    private val adapter = LogAdapter()
    private lateinit var rvLog: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var switchAuto: androidx.appcompat.widget.SwitchCompat

    private var autoScrollEnabled = true
    private var isAtBottom = true

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    /** 保护 items 的并发访问 */
    private val logMutex = Mutex()

    /** 重绘合并信号通道 */
    private val flushChannel = Channel<Unit>(Channel.CONFLATED)

    companion object {
        /** 重绘合并窗口（毫秒）：高频日志最多每这么久重绘一次 */
        private const val FLUSH_INTERVAL_MS = 120L
        /** 日志显示行数上限：保留最近这么多行 */
        private const val VISIBLE_MAX_LINES = 1000
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvLog = view.findViewById(R.id.rvLog)
        tvEmpty = view.findViewById(R.id.tvLogEmpty)
        switchAuto = view.findViewById(R.id.switchAutoScroll)

        // 初始化 RecyclerView
        val layoutManager = LinearLayoutManager(requireContext())
        layoutManager.stackFromEnd = true  // 新条目从底部插入
        rvLog.layoutManager = layoutManager
        rvLog.adapter = adapter

        // 检测用户是否滚动到底部（用于自动滚动判断）
        rvLog.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    isAtBottom = !recyclerView.canScrollVertically(1)
                }
            }
        })

        // 回灌历史快照
        items.addAll(LogStore.snapshot())
        rebuildAll()
        updateEmpty()

        // 自动滚动开关
        switchAuto.setOnCheckedChangeListener { _, checked ->
            autoScrollEnabled = checked
            if (checked && items.isNotEmpty()) {
                rvLog.post { rvLog.scrollToPosition(adapter.itemCount - 1) }
            }
        }

        // 清空日志
        view.findViewById<View>(R.id.btnClearLog).setOnClickListener {
            lifecycleScope.launch(Dispatchers.Default) {
                LogStore.clear()
                logMutex.withLock {
                    items.clear()
                    rebuildAll()
                }
                flushChannel.trySend(Unit)
            }
        }

        // 复制全部
        view.findViewById<View>(R.id.btnCopyLog).setOnClickListener {
            if (items.isEmpty()) {
                Toast.makeText(requireContext(), R.string.log_copy_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch(Dispatchers.Default) {
                val sb = StringBuilder()
                val n = logMutex.withLock {
                    for (e in items) {
                        sb.append(timeFmt.format(java.util.Date(e.time)))
                            .append(" [").append(e.tag).append("] ").append(e.message)
                            .append("\n")
                    }
                    items.size
                }
                withContext(Dispatchers.Main.immediate) {
                    val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("agent-toolbox-kotlin logs", sb.toString()))
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.log_copied, n),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        // 实时订阅新增日志
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            LogStore.newFlow.collect { e ->
                logMutex.withLock {
                    items.add(e)
                    // 限制总数，防止内存膨胀
                    if (items.size > VISIBLE_MAX_LINES) {
                        val removeCount = items.size - VISIBLE_MAX_LINES
                        items.subList(0, removeCount).clear()
                    }
                    // 增量提交：只提交新条目，但 diff 会处理
                    // 由于 ListAdapter 的 submitList 需要完整列表，我们用全量提交
                    // 但 DiffUtil 会优化，只更新变化的部分
                    rebuildAll()
                }
                flushChannel.trySend(Unit)
            }
        }

        // 缓冲裁剪同步
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            LogStore.trimFlow.collect { n ->
                if (n > 0) {
                    logMutex.withLock {
                        if (items.isNotEmpty()) {
                            val remove = n.coerceAtMost(items.size)
                            items.subList(0, remove).clear()
                            rebuildAll()
                        }
                    }
                    flushChannel.trySend(Unit)
                }
            }
        }

        // 合并消费者：固定间隔重绘
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                flushChannel.receive()
                delay(FLUSH_INTERVAL_MS)
                // 排空窗口内堆积的信号
                while (flushChannel.tryReceive().isSuccess) { /* no-op */ }
                logMutex.withLock {
                    // submitList 会触发 DiffUtil 计算，只更新变化的部分
                    adapter.submitList(items.toList())
                    updateEmpty()
                    if (autoScrollEnabled && isAtBottom) {
                        rvLog.post { rvLog.scrollToPosition(adapter.itemCount - 1) }
                    }
                }
            }
        }
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && autoScrollEnabled && items.isNotEmpty()) {
            rvLog.post { rvLog.scrollToPosition(adapter.itemCount - 1) }
        }
    }

    private fun updateEmpty() {
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 重建适配器数据 */
    private fun rebuildAll() {
        // 只保留最近 VISIBLE_MAX_LINES 条
        val start = (items.size - VISIBLE_MAX_LINES).coerceAtLeast(0)
        if (start > 0) {
            items.subList(0, start).clear()
        }
        // submitList 在消费者中执行，这里只准备数据
    }
}
