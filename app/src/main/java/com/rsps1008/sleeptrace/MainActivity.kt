package com.rsps1008.sleeptrace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.health.HealthConnectSync
import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.SleepReconciler
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepTracker
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageMonitor
import com.rsps1008.sleeptrace.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private val preferences by lazy { SleepPreferences(this) }
    private val store by lazy { SleepStore(this) }
    private val healthSync by lazy { HealthConnectSync(this) }
    private val motionSettings by lazy { MotionSettings(this) }
    private var refreshJob: Job? = null
    private var startupPermissionCheckDone = false
    private var continueStartupPermissionFlow = false
    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) {
        WorkScheduler.reconcileSoon(this)
        if (continueStartupPermissionFlow) {
            continueStartupPermissionFlow = false
            openUsageAccessIfNeeded()
        }
        refresh()
    }
    private val requestAndroidPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (SleepTracker.hasActivityRecognition(this)) {
            subscribe()
            if (motionSettings.enabled) startMotion()
        }
        if (continueStartupPermissionFlow) {
            continueStartupPermissionFlow = false
            requestHealthPermissionIfNeeded()
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
        content.requestFocus()
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("sleeptrace_reconcile_now").observe(this) { refresh() }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("sleeptrace_reconcile").observe(this) { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        WorkScheduler.schedule(this)
        WorkScheduler.reconcileSoon(this)
        if (!startupPermissionCheckDone) {
            startupPermissionCheckDone = true
            requestMissingPermissionsAtStartup()
        }
        refresh()
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            content.removeAllViews()
            renderHeader()

            val configured = preferences.configured()
            if (!configured) {
                renderSetupGuide()
                return@launch
            }

            val sessions = store.sessions()
            renderSleepSection(sessions)

            val schedule = preferences.schedule()
            renderScheduleCard(schedule)

            renderMotionSection()
            renderPermissionsSection()
        }
    }

    private fun renderHeader() {
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
        }
        val subtitleView = TextView(this).apply {
            text = "省電睡眠自動推估 · 自動同步 Health Connect"
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        headerLayout.addView(titleView)
        headerLayout.addView(subtitleView)
        content.addView(headerLayout)
    }

    private fun renderSetupGuide() {
        val card = createCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val title = TextView(this).apply {
            text = "設定你的自動偵測時段"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
        }
        val desc = TextView(this).apply {
            text = "眠迹只會在設定的時段分析睡眠，依可用資料自動選擇最佳推估並同步。授予使用情況存取權後，會排除手機使用時間；不需要逐筆確認。"
            textSize = 14f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(8), 0, dp(16))
            setLineSpacing(0f, 1.25f)
        }
        val btn = MaterialButton(this).apply {
            text = "設定時段並開始"
            isAllCaps = false
            setOnClickListener { chooseSchedule() }
        }
        layout.addView(title)
        layout.addView(desc)
        layout.addView(btn)
        card.addView(layout)
        content.addView(card)
    }

    private fun renderSleepSection(sessions: List<SleepSession>) {
        content.addView(createSectionTitle("最近睡眠紀錄"))

        if (sessions.isEmpty()) {
            val emptyCard = createCard()
            val emptyLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            val emptyText = TextView(this).apply {
                text = "尚無紀錄。Sleep API 的資料通常在夜間結束或起床後送達並推估。"
                textSize = 14f
                setTextColor(color(R.color.text_secondary))
            }
            emptyLayout.addView(emptyText)
            emptyCard.addView(emptyLayout)
            content.addView(emptyCard)
        } else {
            val latest = sessions.first()
            content.addView(createLatestSessionCard(latest))

            if (sessions.size > 1) {
                val historyCard = createCard()
                val historyLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                }
                val historyHeader = TextView(this).apply {
                    text = "更早的紀錄"
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(color(R.color.text_secondary))
                    setPadding(0, 0, 0, dp(8))
                }
                historyLayout.addView(historyHeader)

                sessions.drop(1).take(4).forEachIndexed { index, session ->
                    if (index > 0) historyLayout.addView(createDivider())
                    historyLayout.addView(createCompactSessionRow(session))
                }
                historyCard.addView(historyLayout)
                content.addView(historyCard)
            }
        }

        content.addView(TextView(this).apply {
            text = "睡眠階段（深眠、淺眠與 REM）未在此版本估計，因手機放置位置與低頻感測資料不足以可靠區分。"
            textSize = 12f
            setTextColor(color(R.color.text_tertiary))
            setPadding(dp(4), 0, dp(4), dp(12))
        })
    }

    private fun createLatestSessionCard(session: SleepSession): MaterialCardView {
        val card = createCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleText = TextView(this).apply {
            text = session.title()
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val (statusText, statusTextColor, statusBgColor) = syncBadgeStyle(session.state)
        val statusBadge = createBadge(statusText, statusTextColor, statusBgColor)
        topRow.addView(titleText)
        topRow.addView(statusBadge)
        layout.addView(topRow)

        val durationLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(0, dp(10), 0, dp(8))
        }
        val durationValue = TextView(this).apply {
            text = formatDuration(session.durationMillis)
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.purple_500))
        }
        val durationLabel = TextView(this).apply {
            text = "  推估睡眠時長"
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, 0, 0, dp(3))
        }
        durationLayout.addView(durationValue)
        durationLayout.addView(durationLabel)
        layout.addView(durationLayout)

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val awakeText = TextView(this).apply {
            text = "排除手機使用：${formatDuration(session.awakeMillis)}"
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val scoreText = TextView(this).apply {
            text = "參考分數：${session.confidence}/100"
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
        }
        metricsRow.addView(awakeText)
        metricsRow.addView(scoreText)
        layout.addView(metricsRow)

        val reasonText = TextView(this).apply {
            text = "${session.reason}（工程規則參考分數，非臨床準確率）"
            textSize = 12f
            setTextColor(color(R.color.text_tertiary))
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, dp(14))
        }
        layout.addView(reasonText)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val editBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            text = "自選修正時間"
            isAllCaps = false
            setOnClickListener { editSession(session) }
        }
        val detailBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "查看完整詳情"
            isAllCaps = false
            setOnClickListener { showSession(session) }
        }
        actionRow.addView(editBtn)
        actionRow.addView(detailBtn)
        layout.addView(actionRow)

        card.addView(layout)
        return card
    }

    private fun createCompactSessionRow(session: SleepSession): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { showSession(session) }

            val infoCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleView = TextView(this@MainActivity).apply {
                text = session.title()
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text_primary))
            }
            val subView = TextView(this@MainActivity).apply {
                text = "${formatDuration(session.durationMillis)} · 排除手機 ${formatDuration(session.awakeMillis)} · 分數 ${session.confidence}"
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            }
            infoCol.addView(titleView)
            infoCol.addView(subView)
            addView(infoCol)

            val (statusText, statusTextColor, statusBgColor) = syncBadgeStyle(session.state)
            val badge = createBadge(statusText, statusTextColor, statusBgColor)
            addView(badge)
        }
    }

    private fun syncBadgeStyle(state: SyncState): Triple<String, Int, Int> {
        return when (state) {
            SyncState.SYNCED -> Triple("已寫入 Health Connect", color(R.color.status_success), color(R.color.status_success_bg))
            SyncState.PENDING -> Triple("等待自動同步", color(R.color.status_info), color(R.color.status_info_bg))
            SyncState.SYNCING -> Triple("同步中…", color(R.color.status_info), color(R.color.status_info_bg))
            SyncState.FAILED -> Triple("同步失敗待重試", color(R.color.status_warning), color(R.color.status_warning_bg))
            SyncState.SKIPPED -> Triple("App 已自動略過", color(R.color.status_neutral), color(R.color.status_neutral_bg))
        }
    }

    private fun renderScheduleCard(schedule: SleepSchedule) {
        content.addView(createSectionTitle("自動偵測排程"))
        val card = createCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val timeLabel = TextView(this).apply {
            text = schedule.label()
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "修改時段"
            isAllCaps = false
            setOnClickListener { chooseSchedule(schedule) }
        }
        topRow.addView(timeLabel)
        topRow.addView(editBtn)
        layout.addView(topRow)

        val modeLabel = TextView(this).apply {
            text = "模式：省電自動偵測（Google Sleep API）"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.purple_500))
            setPadding(0, dp(4), 0, dp(4))
        }
        layout.addView(modeLabel)

        val noteText = TextView(this).apply {
            text = "只在設定的時段內分析，結合已知手機使用與動作資料自動選擇最佳推估並同步至 Health Connect，不需要逐筆確認。"
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            setLineSpacing(0f, 1.2f)
        }
        layout.addView(noteText)

        card.addView(layout)
        content.addView(card)
    }

    private suspend fun renderMotionSection() {
        content.addView(createSectionTitle("動作感測（加速度計＋批次）"))
        val card = createCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val placementLabel = if (motionSettings.placement == Placement.BED) "手機放在床上" else "手機放在床邊"
        val isRunning = MotionService.active != null
        val statusText = if (isRunning) "偵測服務執行中" else if (motionSettings.enabled) "已中斷／待啟動" else "尚未開啟"
        val (statusTextColor, statusBgColor) = when {
            isRunning -> color(R.color.status_success) to color(R.color.status_success_bg)
            motionSettings.enabled -> color(R.color.status_warning) to color(R.color.status_warning_bg)
            else -> color(R.color.status_neutral) to color(R.color.status_neutral_bg)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val placementTitle = TextView(this).apply {
            text = placementLabel
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val statusBadge = createBadge(statusText, statusTextColor, statusBgColor)
        topRow.addView(placementTitle)
        topRow.addView(statusBadge)
        layout.addView(topRow)

        val modeDesc = TextView(this).apply {
            text = if (motionSettings.placement == Placement.BED)
                "床上模式：未供電 5 Hz、供電 10 Hz，硬體批次最長 60 秒（無 FIFO 降為 1 Hz）。未供電且電量 ≤ 15% 暫停。"
            else
                "床邊模式：只記錄手機動作，不從靜止推論使用者睡眠。"
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(4), 0, dp(8))
            setLineSpacing(0f, 1.15f)
        }
        layout.addView(modeDesc)

        val changePlacementBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "變更放置位置（$placementLabel）"
            isAllCaps = false
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("今晚手機放在哪裡？")
                    .setSingleChoiceItems(arrayOf("床上：可輔助推估睡眠", "床邊：只記錄手機動作"), motionSettings.placement.ordinal) { dialog, which ->
                        motionSettings.placement = Placement.entries[which]
                        MotionService.active?.refreshConfiguration()
                        dialog.dismiss()
                        refresh()
                    }.setNegativeButton("取消", null).show()
            }
        }
        layout.addView(changePlacementBtn)

        val now = System.currentTimeMillis()
        val (minutes, usage) = withContext(Dispatchers.IO) {
            val rows = MotionStore(this@MainActivity).use { it.read(now - 24 * 60 * MINUTE_MS, now) }
            rows to if (rows.isEmpty()) emptyList() else UsageMonitor.interactionIntervals(this@MainActivity, rows.first().startMillis, now)
        }

        val timelineSectionTitle = TextView(this).apply {
            text = "最近 24 小時動作時間軸"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
            setPadding(0, dp(14), 0, dp(4))
        }
        layout.addView(timelineSectionTitle)

        if (minutes.isEmpty()) {
            val emptyTimeline = TextView(this).apply {
                text = "最近 24 小時尚無動作摘要。啟用後，摘要約每 5～6 分鐘更新一次。"
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(2), 0, dp(10))
            }
            layout.addView(emptyTimeline)
        } else {
            val formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())
            val start = minutes.first().startMillis
            val end = minutes.last().startMillis + MINUTE_MS
            val quiet = minutes.count { it.placement == Placement.BED && it.level == MotionLevel.QUIET }
            val active = minutes.count { it.placement == Placement.BED && it.level == MotionLevel.ACTIVE }
            val unknown = ((end - start) / MINUTE_MS - quiet - active).coerceAtLeast(0)

            val timeRangeText = TextView(this).apply {
                text = "${formatter.format(Instant.ofEpochMilli(start))} ～ ${formatter.format(Instant.ofEpochMilli(end))}"
                textSize = 12f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, 0, 0, dp(6))
            }
            layout.addView(timeRangeText)

            val timelineWrapper = MaterialCardView(this).apply {
                radius = dp(8).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                setStrokeColor(color(R.color.card_stroke))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40)).apply {
                    setMargins(0, 0, 0, dp(8))
                }
                addView(MotionTimelineView(this@MainActivity, minutes, usage).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT)
                    contentDescription = "動作時間軸：安靜 $quiet 分鐘，活動 $active 分鐘，未知或床邊 $unknown 分鐘；紅色為手機使用"
                })
            }
            layout.addView(timelineWrapper)

            val legendRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(6))
            }
            legendRow.addView(createLegendDot(Color.rgb(71, 113, 193), "安靜 ${quiet}m"))
            legendRow.addView(createLegendDot(Color.rgb(225, 139, 38), "活動 ${active}m"))
            legendRow.addView(createLegendDot(Color.GRAY, "未知/床邊 ${unknown}m"))
            legendRow.addView(createLegendDot(Color.rgb(210, 67, 67), "手機使用"))
            layout.addView(legendRow)

            val timelineExplain = TextView(this).apply {
                text = "（摘要分鐘數不是睡眠總時數）每日時段結束後，連續安靜至少 20 分鐘且總區段至少 30 分鐘，App 會自動採用最佳推估並同步。已知手機使用、中斷或持續活動會切斷區段。"
                textSize = 12f
                setTextColor(color(R.color.text_tertiary))
                setLineSpacing(0f, 1.15f)
                setPadding(0, 0, 0, dp(8))
            }
            layout.addView(timelineExplain)
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        val toggleBtn = MaterialButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            isAllCaps = false
            if (MotionService.active == null) {
                text = if (motionSettings.enabled) "重新啟動動作偵測" else "開啟動作偵測"
                setOnClickListener {
                    motionSettings.enabled = true
                    if (!SleepTracker.hasActivityRecognition(this@MainActivity)) requestCorePermissions() else startMotion()
                }
            } else {
                text = "關閉動作偵測"
                setOnClickListener {
                    motionSettings.enabled = false
                    startService(Intent(this@MainActivity, MotionService::class.java).setAction(MotionService.ACTION_STOP))
                    refresh()
                }
            }
        }
        val updateBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "手動更新試算"
            isAllCaps = false
            setOnClickListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SleepReconciler(this@MainActivity).reconcile() }
                    WorkScheduler.reconcileSoon(this@MainActivity)
                    refresh()
                }
            }
        }
        buttonRow.addView(toggleBtn)
        buttonRow.addView(updateBtn)
        layout.addView(buttonRow)

        val motionNote = TextView(this).apply {
            text = "啟用後會有常駐通知，僅在設定時段取樣。重開機或遭系統終止後，請開啟 App 重新啟動。安靜不代表深眠；這版不推估深眠、淺眠或 REM。"
            textSize = 12f
            setTextColor(color(R.color.text_tertiary))
            setPadding(0, dp(8), 0, 0)
        }
        layout.addView(motionNote)

        card.addView(layout)
        content.addView(card)
    }

    private suspend fun renderPermissionsSection() {
        content.addView(createSectionTitle("系統連線與權限"))
        val card = createCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val activityGranted = SleepTracker.hasActivityRecognition(this)
        val usageGranted = UsageMonitor.hasAccess(this)
        val healthGranted = healthSync.hasWritePermission()
        val allGranted = activityGranted && usageGranted && healthGranted

        layout.addView(createPermissionItem("活動辨識", "Sleep API 與動作感測必要", activityGranted))
        layout.addView(createDivider())
        layout.addView(createPermissionItem("使用情況存取", "排除夜間使用手機時間", usageGranted, if (!usageGranted) "未授權時無法排除手機使用" else null))
        layout.addView(createDivider())
        layout.addView(createPermissionItem("Health Connect", "自動寫入睡眠紀錄", healthGranted, if (!healthGranted) "尚未連線或未授權寫入" else null))

        val summaryText = TextView(this).apply {
            text = if (allGranted) "✓ 所有必要權限與 Health Connect 授權皆已就緒"
            else "⚠ 尚有未允許的項目。每次開啟 App 都會自動檢查；使用情況存取需在 Android 系統設定中開啟。"
            textSize = 13f
            setTextColor(if (allGranted) color(R.color.status_success) else color(R.color.status_warning))
            setPadding(0, dp(12), 0, dp(8))
        }
        layout.addView(summaryText)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        if (!allGranted) {
            val grantBtn = MaterialButton(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
                text = "檢查並引導授權"
                isAllCaps = false
                setOnClickListener { requestMissingPermissionsAtStartup() }
            }
            btnRow.addView(grantBtn)
        }
        val recheckBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            layoutParams = if (!allGranted) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            text = "重新檢查權限"
            isAllCaps = false
            setOnClickListener { requestMissingPermissionsAtStartup() }
        }
        btnRow.addView(recheckBtn)
        layout.addView(btnRow)

        card.addView(layout)
        content.addView(card)
    }

    private fun createPermissionItem(name: String, desc: String, isGranted: Boolean, note: String? = null): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))

            val infoCol = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleView = TextView(this@MainActivity).apply {
                text = name
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text_primary))
            }
            val descView = TextView(this@MainActivity).apply {
                text = note ?: desc
                textSize = 12f
                setTextColor(if (note != null && !isGranted) color(R.color.status_warning) else color(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            }
            infoCol.addView(titleView)
            infoCol.addView(descView)
            addView(infoCol)

            val badge = if (isGranted) {
                createBadge("已允許 ✓", color(R.color.status_success), color(R.color.status_success_bg))
            } else {
                createBadge("需要允許 ⚠", color(R.color.status_warning), color(R.color.status_warning_bg))
            }
            addView(badge)
        }
    }

    private fun createLegendDot(dotColor: Int, label: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(10), 0)

            val dot = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                    marginEnd = dp(4)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(dotColor)
                }
            }
            val text = TextView(this@MainActivity).apply {
                this.text = label
                textSize = 11f
                setTextColor(color(R.color.text_secondary))
            }
            addView(dot)
            addView(text)
        }
    }

    private fun createSectionTitle(title: String): TextView = TextView(this).apply {
        text = title
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color(R.color.text_secondary))
        setPadding(dp(4), dp(8), dp(4), dp(6))
    }

    private fun createDivider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(color(R.color.card_stroke))
    }

    private fun createCard(): MaterialCardView = MaterialCardView(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, dp(14))
        }
        radius = dp(16).toFloat()
        cardElevation = dp(0).toFloat()
        strokeWidth = dp(1)
        setStrokeColor(color(R.color.card_stroke))
        val typedValue = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorSurface, typedValue, true)
        setCardBackgroundColor(typedValue.data)
    }

    private fun createBadge(text: String, textColor: Int, bgColor: Int): TextView = TextView(this).apply {
        this.text = text
        this.setTextColor(textColor)
        textSize = 11f
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(bgColor)
        }
        setPadding(dp(8), dp(3), dp(8), dp(3))
    }

    private fun startMotion() {
        runCatching { MotionService.start(this) }.onFailure {
            motionSettings.enabled = false
            showMessage("無法啟動動作偵測：${it.message}")
        }
        content.postDelayed({ if (!isFinishing && !isDestroyed) refresh() }, 500)
    }

    private fun chooseSchedule(existing: SleepSchedule? = null) {
        val start = existing?.startMinute ?: 0
        spinnerTimePicker().apply {
            setIs24HourView(true); hour = start / 60; minute = start % 60
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("開始偵測時間").setView(this)
                .setPositiveButton("下一步") { _, _ -> chooseEndSchedule(hour * 60 + minute, existing?.endMinute ?: 540) }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun chooseEndSchedule(startMinute: Int, currentEnd: Int) {
        spinnerTimePicker().apply {
            setIs24HourView(true); hour = currentEnd / 60; minute = currentEnd % 60
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("結束偵測時間").setView(this)
                .setPositiveButton("儲存") { _, _ -> setupSchedule(SleepSchedule(startMinute, hour * 60 + minute)) }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun setupSchedule(schedule: SleepSchedule) = lifecycleScope.launch {
        preferences.saveSchedule(schedule)
        MotionService.active?.refreshConfiguration()
        WorkScheduler.schedule(this@MainActivity)
        subscribe()
        refresh()
    }

    private fun requestCorePermissions() {
        val required = buildList {
            if (!SleepTracker.hasActivityRecognition(this@MainActivity)) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) addNotificationPermissionIfNeeded(this@MainActivity)
        }
        if (required.isNotEmpty()) requestAndroidPermissions.launch(required.toTypedArray())
    }

    private fun requestMissingPermissionsAtStartup() {
        val required = buildList {
            if (!SleepTracker.hasActivityRecognition(this@MainActivity)) add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) addNotificationPermissionIfNeeded(this@MainActivity)
        }
        if (required.isNotEmpty()) {
            continueStartupPermissionFlow = true
            requestAndroidPermissions.launch(required.toTypedArray())
            return
        }
        requestHealthPermissionIfNeeded()
    }

    private fun requestHealthPermissionIfNeeded() {
        lifecycleScope.launch {
            if (!healthSync.available() || healthSync.hasWritePermission()) {
                openUsageAccessIfNeeded()
                return@launch
            }
            continueStartupPermissionFlow = true
            requestHealthPermissions.launch(healthSync.writePermissions)
        }
    }

    private fun openUsageAccessIfNeeded() {
        if (!UsageMonitor.hasAccess(this)) startActivity(UsageMonitor.accessIntent())
    }

    private fun subscribe() = SleepTracker.subscribe(this) { result ->
        if (result.isFailure) runOnUiThread { showMessage("無法啟用偵測：${result.exceptionOrNull()?.message}") }
        else refresh()
    }

    @Suppress("unused")
    private fun connectHealth() {
        if (!healthSync.available()) { showMessage("Health Connect 尚未安裝、已停用或需要更新。請先在系統健康設定完成處理。"); return }
        requestHealthPermissions.launch(healthSync.writePermissions)
    }

    private fun showSession(session: SleepSession) {
        MaterialAlertDialogBuilder(this).setTitle(session.title())
            .setMessage("推估睡眠：${formatDuration(session.durationMillis)}\n夜間手機使用：${formatDuration(session.awakeMillis)}\n參考分數：${session.confidence}/100（非準確率）\n\n${session.reason}")
            .setPositiveButton("關閉", null)
            .setNeutralButton("修正時間") { _, _ -> editSession(session) }
            .show()
    }

    private fun editSession(session: SleepSession) {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(session.startMillis).atZone(zone)
        spinnerTimePicker().apply {
            setIs24HourView(true); hour = start.hour; minute = start.minute
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("修正入睡時間（保留日期）").setView(this)
                .setPositiveButton("下一步") { _, _ -> editEnd(session, hour, minute) }.setNegativeButton("取消", null).show()
        }
    }

    private fun editEnd(session: SleepSession, startHour: Int, startMinute: Int) {
        val zone = ZoneId.systemDefault()
        val end = Instant.ofEpochMilli(session.endMillis).atZone(zone)
        spinnerTimePicker().apply {
            setIs24HourView(true); hour = end.hour; minute = end.minute
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("修正醒來時間（保留日期）").setView(this)
                .setPositiveButton("儲存") { _, _ ->
                    val newStart = Instant.ofEpochMilli(session.startMillis).atZone(zone).withHour(startHour).withMinute(startMinute).withSecond(0).withNano(0).toInstant().toEpochMilli()
                    var newEnd = Instant.ofEpochMilli(session.endMillis).atZone(zone).withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant().toEpochMilli()
                    if (newEnd <= newStart) newEnd += 24 * 60 * 60 * 1000L
                    if (newEnd - newStart < 30 * 60 * 1000L) { showMessage("睡眠時間至少需 30 分鐘"); return@setPositiveButton }
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            val usage = UsageMonitor.interactionIntervals(this@MainActivity, newStart, newEnd)
                            store.reviseTimes(session.id, newStart, newEnd, usage)
                        }
                        WorkScheduler.reconcileSoon(this@MainActivity)
                        refresh()
                    }
                }.setNegativeButton("取消", null).show()
        }
    }

    private fun showMessage(text: String) = MaterialAlertDialogBuilder(this).setMessage(text).setPositiveButton("知道了", null).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
    private fun formatDuration(millis: Long): String = "%d 小時 %d 分鐘".format(millis / 3600000, (millis / 60000) % 60)

    private fun spinnerTimePicker() = TimePicker(this, null, 0, R.style.SleepTraceSpinnerTimePicker)

    private fun notificationPermissionNeeded(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && notificationPermissionNeededOnTiramisu()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun notificationPermissionNeededOnTiramisu() =
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun MutableList<String>.addNotificationPermissionIfNeeded(activity: MainActivity) {
        if (activity.notificationPermissionNeededOnTiramisu()) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
