package com.rsps1008.sleeptrace

import android.content.Context
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepStage
import com.rsps1008.sleeptrace.sleep.SleepSessionTimelineView
import com.rsps1008.sleeptrace.sleep.sleepParts
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps sizeable, stateful dialogs out of MainActivity while preserving the existing callbacks. */
object SleepDialogHelper {
    fun showSchedule(context: Context, existing: SleepSchedule?, onSave: (SleepSchedule) -> Unit) {
        val start = existing?.startMinute ?: 0
        val end = existing?.endMinute ?: 540
        val startPicker = timePicker(context, start / 60, start % 60)
        val endPicker = timePicker(context, end / 60, end % 60)
        val weekendEnabled = CheckBox(context).apply {
            text = "週末（週六、週日）使用不同時段"
            isChecked = existing?.weekendStartMinute != null && existing.weekendEndMinute != null
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 4))
        }
        val weekendStart = existing?.weekendStartMinute ?: start
        val weekendEnd = existing?.weekendEndMinute ?: end
        val weekendStartPicker = timePicker(context, weekendStart / 60, weekendStart % 60)
        val weekendEndPicker = timePicker(context, weekendEnd / 60, weekendEnd % 60)
        val range = TextView(context).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 4))
        }
        fun updateRange() {
            val weekdayStart = startPicker.hour * 60 + startPicker.minute
            val weekdayEnd = endPicker.hour * 60 + endPicker.minute
            val weekendStartMinute = weekendStartPicker.hour * 60 + weekendStartPicker.minute
            val weekendEndMinute = weekendEndPicker.hour * 60 + weekendEndPicker.minute
            val schedule = SleepSchedule(
                weekdayStart, weekdayEnd,
                weekendStartMinute.takeIf { weekendEnabled.isChecked },
                weekendEndMinute.takeIf { weekendEnabled.isChecked }
            )
            val crossings = buildList {
                if (weekdayEnd <= weekdayStart) add("平日跨午夜")
                if (weekendEnabled.isChecked && weekendEndMinute <= weekendStartMinute) add("週末跨午夜")
            }
            range.text = schedule.label() + if (crossings.isEmpty()) "" else "（${crossings.joinToString("、")}）"
        }
        startPicker.setOnTimeChangedListener { _, _, _ -> updateRange() }
        endPicker.setOnTimeChangedListener { _, _, _ -> updateRange() }
        weekendStartPicker.setOnTimeChangedListener { _, _, _ -> updateRange() }
        weekendEndPicker.setOnTimeChangedListener { _, _, _ -> updateRange() }
        val weekendControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (weekendEnabled.isChecked) View.VISIBLE else View.GONE
            addView(label(context, "週末開始"))
            addView(weekendStartPicker)
            addView(label(context, "週末結束"))
            addView(weekendEndPicker)
        }
        weekendEnabled.setOnCheckedChangeListener { _, checked ->
            weekendControls.visibility = if (checked) View.VISIBLE else View.GONE
            updateRange()
        }
        updateRange()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(range)
            addView(label(context, "平日開始"))
            addView(startPicker)
            addView(label(context, "平日結束"))
            addView(endPicker)
            addView(weekendEnabled)
            addView(weekendControls)
        }
        val scroll = android.widget.ScrollView(context).apply { addView(content) }
        MaterialAlertDialogBuilder(context).setTitle("睡眠偵測時段").setView(scroll)
            .setPositiveButton("儲存") { _, _ ->
                onSave(SleepSchedule(
                    startPicker.hour * 60 + startPicker.minute,
                    endPicker.hour * 60 + endPicker.minute,
                    (weekendStartPicker.hour * 60 + weekendStartPicker.minute).takeIf { weekendEnabled.isChecked },
                    (weekendEndPicker.hour * 60 + weekendEndPicker.minute).takeIf { weekendEnabled.isChecked }
                ))
            }
            .setNegativeButton("取消", null).show()
    }

    fun showSession(
        context: Context,
        session: SleepSession,
        formatDuration: (Long) -> String,
        onEdit: () -> Unit,
        onRetry: (() -> Unit)? = null
    ) {
        val detail = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), 0, dp(context, 24), 0)
            addView(TextView(context).apply {
                val parts = sleepParts(session)
                val staged = session.stageIntervals.isNotEmpty()
                val lightMillis = parts.filter { it.stage == SleepStage.LIGHT }.sumOf { it.end - it.start }
                val deepMillis = parts.filter { it.stage == SleepStage.DEEP }.sumOf { it.end - it.start }
                val awakeMillis = parts.filter { it.stage == SleepStage.AWAKE }.sumOf { it.end - it.start }
                text = if (staged) {
                    "總睡眠：${formatDuration(lightMillis + deepMillis)}\n" +
                        "淺眠：約 ${formatDuration(lightMillis)}　深眠：約 ${formatDuration(deepMillis)}\n" +
                        "清醒：約 ${formatDuration(awakeMillis)}\n" +
                        "依手機活動與 Google Sleep API 推估，非醫療睡眠分期\n" +
                        "參考分數：${session.confidence}/100（非準確率）\n\n${session.reason}"
                } else {
                    "推估睡眠：${formatDuration(session.durationMillis)}\n" +
                        "夜間手機使用：${formatDuration(session.awakeMillis)}\n" +
                        "參考分數：${session.confidence}/100（非準確率）\n\n${session.reason}"
                } +
                    (session.syncError?.let { "\n\n同步錯誤：$it" } ?: "")
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                textSize = 14f
            })
            addView(SleepSessionTimelineView(context, session), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 70)
            ).apply { topMargin = dp(context, 16) })
        }
        val dialog = MaterialAlertDialogBuilder(context).setTitle(session.title()).setView(detail)
            .setNegativeButton("關閉", null)
            .setNeutralButton("修正時間") { _, _ -> onEdit() }
        if (onRetry != null) dialog.setPositiveButton("重新同步") { _, _ -> onRetry() }
        dialog.show()
    }

    fun showTimeEditor(
        context: Context,
        session: SleepSession,
        formatDuration: (Long) -> String,
        onSave: (startMillis: Long, endMillis: Long) -> Unit,
        onError: (String) -> Unit
    ) {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(session.startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(session.endMillis).atZone(zone)
        val startPicker = timePicker(context, start.hour, start.minute)
        val endPicker = timePicker(context, end.hour, end.minute)
        val duration = TextView(context).apply {
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 8))
        }

        fun editedTimes(): Pair<Long, Long> {
            val newStart = start.withHour(startPicker.hour).withMinute(startPicker.minute)
                .withSecond(0).withNano(0)
            var newEnd = end.withHour(endPicker.hour).withMinute(endPicker.minute)
                .withSecond(0).withNano(0)
            if (!newEnd.isAfter(newStart)) newEnd = newEnd.plusDays(1)
            return newStart.toInstant().toEpochMilli() to newEnd.toInstant().toEpochMilli()
        }
        fun updateDuration() {
            val (newStart, newEnd) = editedTimes()
            val millis = newEnd - newStart
            duration.text = "預計睡眠時間：${formatDuration(millis)}" +
                if (millis < MINIMUM_EDIT_MILLIS) "（至少需 30 分鐘）" else ""
            duration.setTextColor(ContextCompat.getColor(
                context,
                if (millis < MINIMUM_EDIT_MILLIS) R.color.status_warning else R.color.text_secondary
            ))
        }
        startPicker.setOnTimeChangedListener { _, _, _ -> updateDuration() }
        endPicker.setOnTimeChangedListener { _, _, _ -> updateDuration() }
        updateDuration()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(duration)
            addView(label(context, "入睡時間"))
            addView(startPicker)
            addView(label(context, "醒來時間"))
            addView(endPicker)
        }
        MaterialAlertDialogBuilder(context).setTitle("修正睡眠時間（保留日期）").setView(content)
            .setPositiveButton("儲存") { _, _ ->
                val (newStart, newEnd) = editedTimes()
                if (newEnd - newStart < MINIMUM_EDIT_MILLIS) onError("睡眠時間至少需 30 分鐘")
                else onSave(newStart, newEnd)
            }
            .setNegativeButton("取消", null).show()
    }

    fun showAllSessions(
        context: Context,
        loadPage: (offset: Int, limit: Int) -> List<SleepSession>,
        formatDuration: (Long) -> String,
        onSelected: (String) -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("全部睡眠紀錄")
            .setPositiveButton("關閉", null)
            .create()

        lateinit var adapter: SessionHistoryAdapter
        fun loadNextPage() {
            if (adapter.loading || !adapter.hasMore) return
            adapter.setLoading()
            val offset = adapter.sessionCount
            scope.launch {
                try {
                    val page = withContext(Dispatchers.IO) { loadPage(offset, HISTORY_PAGE_SIZE) }
                    adapter.append(page)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    adapter.setLoadFailed()
                }
            }
        }
        adapter = SessionHistoryAdapter(context, formatDuration, ::loadNextPage) { id ->
            dialog.dismiss()
            onSelected(id)
        }
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
            setHasFixedSize(true)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 440)
            )
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    if (manager.findLastVisibleItemPosition() >= adapter.itemCount - PREFETCH_DISTANCE) loadNextPage()
                }
            })
        }
        dialog.setView(list)
        dialog.setOnDismissListener { scope.cancel() }
        dialog.show()
        loadNextPage()
    }

    private class SessionHistoryAdapter(
        private val context: Context,
        private val formatDuration: (Long) -> String,
        private val loadMore: () -> Unit,
        private val onSelected: (String) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val sessions = mutableListOf<SleepSession>()
        var hasMore = true
            private set
        var loading = false
            private set
        private var loadFailed = false
        val sessionCount: Int get() = sessions.size

        override fun getItemCount() = sessions.size + if (hasMore || loadFailed) 1 else 0
        override fun getItemViewType(position: Int) = if (position < sessions.size) TYPE_SESSION else TYPE_FOOTER

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_FOOTER) {
                return FooterHolder(TextView(context).apply {
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                }, loadMore)
            }
            val title = TextView(context).apply {
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            }
            val summary = TextView(context).apply {
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(context, 3), 0, 0)
            }
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                isClickable = true
                isFocusable = true
                setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
                addView(title)
                addView(summary)
            }
            return SessionHolder(row, title, summary, onSelected)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is FooterHolder) {
                holder.bind(if (loading) "載入中…" else if (loadFailed) "讀取失敗，點此重試" else "載入更多紀錄")
            } else if (holder is SessionHolder) {
                holder.bind(sessions[position], formatDuration)
            }
        }

        fun setLoading() {
            loading = true
            loadFailed = false
            notifyItemChanged(sessions.size)
        }

        fun append(page: List<SleepSession>) {
            val oldSessionCount = sessions.size
            sessions.addAll(page)
            hasMore = page.size >= HISTORY_PAGE_SIZE
            loading = false
            loadFailed = false
            if (page.isNotEmpty()) notifyItemRangeInserted(oldSessionCount, page.size)
            val footerPosition = sessions.size
            if (hasMore) notifyItemChanged(footerPosition)
            else notifyItemRemoved(footerPosition)
        }

        fun setLoadFailed() {
            loading = false
            loadFailed = true
            hasMore = true
            notifyItemChanged(sessions.size)
        }

        private class SessionHolder(
            view: View,
            private val title: TextView,
            private val summary: TextView,
            private val onSelected: (String) -> Unit
        ) : RecyclerView.ViewHolder(view) {
            fun bind(session: SleepSession, formatDuration: (Long) -> String) {
                title.text = session.title()
                summary.text = "睡眠 ${formatDuration(session.durationMillis)} · 手機使用 ${formatDuration(session.awakeMillis)}"
                itemView.setOnClickListener { onSelected(session.id) }
            }
        }

        private class FooterHolder(view: View, private val onRetry: () -> Unit) : RecyclerView.ViewHolder(view) {
            fun bind(text: String) {
                (itemView as TextView).text = text
                itemView.setOnClickListener { onRetry() }
                itemView.isClickable = text == "讀取失敗，點此重試" || text == "載入更多紀錄"
                itemView.isFocusable = itemView.isClickable
            }
        }

        companion object {
            private const val TYPE_SESSION = 0
            private const val TYPE_FOOTER = 1
        }
    }

    private fun label(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0)
    }

    private fun timePicker(context: Context, hour: Int, minute: Int) = TimePicker(
        context, null, 0, R.style.SleepTraceSpinnerTimePicker
    ).apply {
        setIs24HourView(true)
        this.hour = hour
        this.minute = minute
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private const val MINIMUM_EDIT_MILLIS = 30 * 60 * 1000L
    private const val HISTORY_PAGE_SIZE = 40
    private const val PREFETCH_DISTANCE = 6
}
