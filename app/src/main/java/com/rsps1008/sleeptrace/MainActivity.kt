package com.rsps1008.sleeptrace

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.withResumed
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
import com.rsps1008.sleeptrace.power.BackgroundAccess
import com.rsps1008.sleeptrace.sleep.ClassificationSample
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
    private lateinit var scroll: ScrollView
    private val preferences by lazy { SleepPreferences(this) }
    private val store by lazy { SleepStore(this) }
    private val healthSync by lazy { HealthConnectSync(this) }
    private val motionSettings by lazy { MotionSettings(this) }
    private val backgroundAccess by lazy { BackgroundAccess(this) }
    private var permissionFlowComplete = false
    private var backgroundSettingsOpen = false
    private val batterySettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        backgroundSettingsOpen = false
        ensureAutomaticRecording()
        refresh()
        guideBackgroundAccessIfNeeded()
    }
    private val xiaomiSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        backgroundSettingsOpen = false
        ensureAutomaticRecording()
        refresh()
    }
    private val usageSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionFlowComplete = true
        refresh()
        guideBackgroundAccessIfNeeded()
    }
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
            ensureAutomaticRecording()
        }
        if (continueStartupPermissionFlow) {
            continueStartupPermissionFlow = false
            requestHealthPermissionIfNeeded()
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startupPermissionCheckDone = savedInstanceState?.getBoolean("startup_checked") ?: false
        continueStartupPermissionFlow = savedInstanceState?.getBoolean("continue_permissions") ?: false
        permissionFlowComplete = savedInstanceState?.getBoolean("permissions_complete") ?: false
        backgroundSettingsOpen = savedInstanceState?.getBoolean("background_settings_open") ?: false
        enableEdgeToEdge()
        title = getString(R.string.app_name)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            addView(content)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(scroll)
        scroll.requestFocus()
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("sleeptrace_reconcile_now").observe(this) { refresh() }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("sleeptrace_reconcile").observe(this) { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        WorkScheduler.schedule(this)
        WorkScheduler.reconcileSoon(this)
        ensureAutomaticRecording()
        if (!startupPermissionCheckDone) {
            startupPermissionCheckDone = true
            requestMissingPermissionsAtStartup()
        }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("startup_checked", startupPermissionCheckDone)
        outState.putBoolean("continue_permissions", continueStartupPermissionFlow)
        outState.putBoolean("permissions_complete", permissionFlowComplete)
        outState.putBoolean("background_settings_open", backgroundSettingsOpen)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            // Fetch before changing the view tree: async gaps used to collapse the scroll content.
            val configured = preferences.configured()
            val schedule = if (configured) preferences.schedule() else null
            val (sessions, latestClassification) = withContext(Dispatchers.IO) {
                store.sessions() to store.samples().maxByOrNull { it.timeMillis }
            }
            val healthGranted = healthSync.hasWritePermission()
            val previousScroll = scroll.scrollY
            content.removeAllViews()
            renderHeader()
            if (!configured) {
                renderSetupGuide()
            } else {
                renderSleepSection(sessions, latestClassification)
                renderScheduleCard(schedule!!)
                renderPermissionsSection(healthGranted)
                renderBackgroundAccess()
            }
            scroll.post { scroll.scrollTo(0, previousScroll) }
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
            text = "安心睡覺，醒來查看紀錄"
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

    private fun renderSleepSection(sessions: List<SleepSession>, latestClassification: ClassificationSample?) {
        content.addView(createSectionTitle("最近睡眠紀錄"))
        content.addView(createSleepApiClassificationCard(latestClassification))

        if (sessions.isEmpty()) {
            val emptyCard = createCard()
            val emptyLayout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            val emptyText = TextView(this).apply {
                text = "準備好迎接第一晚。睡眠時間會在起床後自動整理並同步，不需要每天操作。"
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

    }

    /** Shows only the most recently stored Play services classification; it never requests a live update. */
    private fun createSleepApiClassificationCard(sample: ClassificationSample?): MaterialCardView {
        val card = createCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val score = TextView(this).apply {
            text = sample?.let { "最近一次 Sleep API 睡眠信心：${it.confidence}/100" } ?: "尚未收到 Sleep API 睡眠分類"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
        }
        val detail = TextView(this).apply {
            text = sample?.let {
                val time = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())
                "回報時間：${time.format(Instant.ofEpochMilli(it.timeMillis))} · 使用已保存資料，非即時查詢、非準確率"
            } ?: "會在 Google Play services 回報分類後自動更新。"
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        }
        layout.addView(score)
        layout.addView(detail)
        card.addView(layout)
        return card
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
            orientation = LinearLayout.VERTICAL
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
        layout.addView(TextView(this).apply {
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            text = getString(R.string.sleep_times, time.format(Instant.ofEpochMilli(session.startMillis)), time.format(Instant.ofEpochMilli(session.endMillis)))
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            setPadding(0, 0, 0, dp(8))
        })

        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        val awakeText = TextView(this).apply {
            text = getString(R.string.excluded_phone_time, formatDuration(session.awakeMillis))
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        metricsRow.addView(awakeText)
        // Technical confidence and source details remain available in the optional details dialog.
        layout.addView(metricsRow)

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
                text = getString(R.string.sleep_duration_summary, formatDuration(session.durationMillis), formatDuration(session.awakeMillis))
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
            SyncState.SYNCED -> Triple("已同步", color(R.color.status_success), color(R.color.status_success_bg))
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
            text = if (motionSettings.enabled) "自動記錄已開啟" else "自動記錄已暫停"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.purple_500))
            setPadding(0, dp(4), 0, dp(4))
        }
        layout.addView(modeLabel)

        val noteText = TextView(this).apply {
            setText(R.string.automatic_recording_description)
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            setLineSpacing(0f, 1.2f)
        }
        layout.addView(noteText)
        layout.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (motionSettings.enabled) "暫停自動記錄" else "恢復自動記錄"
            isAllCaps = false
            setOnClickListener {
                motionSettings.enabled = !motionSettings.enabled
                if (motionSettings.enabled) {
                    ensureAutomaticRecording()
                    guideBackgroundAccessIfNeeded()
                }
                else {
                    runCatching { SleepTracker.unsubscribe(this@MainActivity) }
                    if (MotionService.active != null) startService(Intent(this@MainActivity, MotionService::class.java).setAction(MotionService.ACTION_STOP))
                }
                refresh()
            }
        })

        card.addView(layout)
        content.addView(card)
    }

    private fun renderPermissionsSection(healthGranted: Boolean) {
        content.addView(createSectionTitle("系統連線與權限"))
        val card = createCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }

        val activityGranted = SleepTracker.hasActivityRecognition(this)
        val usageGranted = UsageMonitor.hasAccess(this)
        val allGranted = activityGranted && usageGranted && healthGranted

        if (allGranted) {
            layout.addView(createBadge(getString(R.string.basic_permissions_ready), color(R.color.status_success), color(R.color.status_success_bg)))
            card.addView(layout)
            content.addView(card)
            return
        }

        layout.addView(createPermissionItem("睡眠偵測", "允許 App 自動記錄", activityGranted))
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

    private fun renderBackgroundAccess() {
        val access = backgroundAccess
        if (access.batteryReady) return
        content.addView(createSectionTitle(getString(R.string.background_recording_title)))
        val card = createCard()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        if (!access.batteryReady) {
            layout.addView(TextView(this).apply {
                setText(if (access.restricted) R.string.battery_restricted_description else R.string.battery_optimized_description)
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
            })
            layout.addView(MaterialButton(this).apply {
                setText(R.string.allow_overnight_recording)
                isAllCaps = false
                setOnClickListener { openBatterySettings() }
            })
        }
        card.addView(layout)
        content.addView(card)
    }

    private fun guideBackgroundAccessIfNeeded() = lifecycleScope.launch {
        if (!permissionFlowComplete || !preferences.configured() || !motionSettings.enabled) return@launch
        lifecycle.withResumed {
            if (!backgroundSettingsOpen && motionSettings.enabled) {
                when {
                    !backgroundAccess.batteryReady && !backgroundAccess.batteryGuideShown -> openBatterySettings()
                    backgroundAccess.isXiaomi && !backgroundAccess.xiaomiGuideShown -> openXiaomiSettings()
                }
            }
        }
    }

    private fun openBatterySettings() {
        if (backgroundSettingsOpen) return
        backgroundAccess.batteryGuideShown = true
        launchBackgroundSettings(backgroundAccess.batteryIntents(), batterySettingsLauncher)
    }

    private fun openXiaomiSettings() {
        if (backgroundSettingsOpen) return
        backgroundAccess.xiaomiGuideShown = true
        android.widget.Toast.makeText(this, R.string.xiaomi_autostart_hint, android.widget.Toast.LENGTH_LONG).show()
        launchBackgroundSettings(backgroundAccess.xiaomiIntents(), xiaomiSettingsLauncher)
    }

    private fun launchBackgroundSettings(intents: List<Intent>, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
        for (intent in intents) {
            try {
                backgroundSettingsOpen = true
                launcher.launch(intent)
                return
            } catch (_: ActivityNotFoundException) {
                backgroundSettingsOpen = false
            } catch (_: SecurityException) {
                backgroundSettingsOpen = false
            }
        }
        showMessage(getString(R.string.background_settings_unavailable))
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

    private fun ensureAutomaticRecording() = lifecycleScope.launch {
        if (!preferences.configured() || !motionSettings.enabled || !SleepTracker.hasActivityRecognition(this@MainActivity)) return@launch
        lifecycle.withResumed {
            if (motionSettings.enabled) {
                SleepTracker.subscribe(this@MainActivity)
                if (MotionService.active == null) runCatching { MotionService.start(this@MainActivity) }.onFailure {
                    motionSettings.status = "系統暫時無法啟動記錄，重新開啟 App 後會自動再試"
                }
            }
        }
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
        ensureAutomaticRecording()
        guideBackgroundAccessIfNeeded()
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
        permissionFlowComplete = false
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
        if (!UsageMonitor.hasAccess(this)) usageSettingsLauncher.launch(UsageMonitor.accessIntent())
        else {
            permissionFlowComplete = true
            guideBackgroundAccessIfNeeded()
        }
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
