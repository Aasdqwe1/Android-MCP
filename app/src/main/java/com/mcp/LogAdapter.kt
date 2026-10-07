package com.mcp

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

class LogAdapter : ListAdapter<LogEntry, LogAdapter.LogViewHolder>(DiffCallback()) {

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private var colorDebug = 0
    private var colorInfo = 0
    private var colorWarn = 0
    private var colorError = 0
    private var colorsLoaded = false

    fun loadColors(context: android.content.Context) {
        if (!colorsLoaded) {
            colorDebug = ContextCompat.getColor(context, R.color.log_debug)
            colorInfo = ContextCompat.getColor(context, R.color.log_info)
            colorWarn = ContextCompat.getColor(context, R.color.log_warn)
            colorError = ContextCompat.getColor(context, R.color.log_error)
            colorsLoaded = true
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false) as TextView
        loadColors(parent.context)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val entry = getItem(position)
        holder.bind(entry, timeFmt, colorDebug, colorInfo, colorWarn, colorError)
    }

    class LogViewHolder(private val tv: TextView) : RecyclerView.ViewHolder(tv) {
        fun bind(
            entry: LogEntry,
            timeFmt: SimpleDateFormat,
            colorDebug: Int,
            colorInfo: Int,
            colorWarn: Int,
            colorError: Int
        ) {
            val sb = SpannableStringBuilder()
            val time = timeFmt.format(java.util.Date(entry.time))
            sb.append(time).append(" ")

            val tagStart = sb.length
            sb.append("[").append(entry.tag).append("] ")
            val tagColor = when (entry.level) {
                LogLevel.DEBUG -> colorDebug
                LogLevel.INFO -> colorInfo
                LogLevel.WARN -> colorWarn
                LogLevel.ERROR -> colorError
            }
            sb.setSpan(ForegroundColorSpan(tagColor), tagStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            val msgStart = sb.length
            sb.append(entry.message)
            if (entry.level == LogLevel.ERROR) {
                sb.setSpan(ForegroundColorSpan(colorError), msgStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            tv.text = sb
        }
    }

    private class DiffCallback : DiffUtil.ItemCallback<LogEntry>() {
        override fun areItemsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: LogEntry, newItem: LogEntry): Boolean {
            return oldItem == newItem
        }
    }

    companion object {
        const val VISIBLE_MAX_LINES = 1000
    }
}
