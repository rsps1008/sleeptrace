package com.rsps1008.sleeptrace

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.viewModels
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepTracker
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageMonitor
import com.rsps1008.sleeptrace.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private val dependencies by lazy { sleepDependencies() }
    private val preferences get() = dependencies.preferences
    private val store get() = dependencies.store
    private val healthSync get() = dependencies.healthSync
    private val motionSettings get() = dependencies.motionSettings
    private val backgroundAccess get() = dependencies.backgroundAccess
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
    private val homeViewModel: HomeViewModel by viewModels()
    private var startupPermissionCheckDone = false
    private var continueStartupPermissionFlow = false
    private lateinit var homeViews: HomeViews
    private var renderedSchedule: SleepSchedule? = null
    private var renderedSessions: List<SleepSession> = emptyList()
    private var renderedLatestSession: SleepSession? = null
    private val renderedHistorySessions = arrayOfNulls<SleepSession>(4)
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
        homeViews = createHomeSkeleton()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                homeViewModel.state.filterNotNull().collect { renderHome(it) }
            }
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("sleeptrace_reconcile_now").observe(this) { infos ->
            if (infos.any { it.state.isFinished }) refresh()
        }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("sleeptrace_reconcile").observe(this) { infos ->
            if (infos.any { it.state.isFinished }) refresh()
        }
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

    private fun refresh() = homeViewModel.refresh()

    private data class PermissionViews(
        val row: LinearLayout,
        val description: TextView,
        val badge: TextView
    )

    private data class HistoryRowViews(
        val row: LinearLayout,
        val title: TextView,
        val summary: TextView,
        val badge: TextView,
        val divider: View?
    )

    private data class LatestViews(
        val card: MaterialCardView,
        val title: TextView,
        val status: TextView,
        val duration: TextView,
        val times: TextView,
        val awake: TextView
    )

    private data class HomeViews(
        val setupCard: MaterialCardView,
        val configuredRoot: LinearLayout,
        val classificationScore: TextView,
        val classificationDetail: TextView,
        val emptyCard: MaterialCardView,
        val latestCard: MaterialCardView,
        val latestTitle: TextView,
        val latestStatus: TextView,
        val latestDuration: TextView,
        val latestTimes: TextView,
        val latestAwake: TextView,
        val historyCard: MaterialCardView,
        val historyRows: List<HistoryRowViews>,
        val historyAllButton: MaterialButton,
        val scheduleTime: TextView,
        val scheduleMode: TextView,
        val scheduleToggle: MaterialButton,
        val permissionsReady: TextView,
        val permissionRows: List<PermissionViews>,
        val permissionSummary: TextView,
        val permissionGrant: MaterialButton,
        val backgroundSection: LinearLayout,
        val backgroundDescription: TextView
    )

    /** Builds the stable page hierarchy once; renderHome only changes data and visibility. */
    private fun createHomeSkeleton(): HomeViews {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
            addView(TextView(this@MainActivity).apply {
                text = getString(R.string.app_name)
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text_primary))
            })
            addView(TextView(this@MainActivity).apply {
                text = "安心睡覺，醒來查看紀錄"
                textSize = 13f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(2), 0, 0)
            })
        }

        val setupCard = createCard()
        setupCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(TextView(this@MainActivity).apply {
                text = "設定你的自動偵測時段"
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text_primary))
            })
            addView(TextView(this@MainActivity).apply {
                text = "眠迹只會在設定的時段分析睡眠，依可用資料自動選擇最佳推估並同步。授予使用情況存取權後，會排除手機使用時間；不需要逐筆確認。"
                textSize = 14f
                setTextColor(color(R.color.text_secondary))
                setPadding(0, dp(8), 0, dp(16))
                setLineSpacing(0f, 1.25f)
            })
            addView(MaterialButton(this@MainActivity).apply {
                text = "設定時段並開始"
                isAllCaps = false
                setOnClickListener { chooseSchedule() }
            })
        })

        val configuredRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        configuredRoot.addView(createSectionTitle("最近睡眠紀錄"))
        val classificationCard = createCard()
        val classificationScore = TextView(this).apply {
            text = "尚未收到 Sleep API 睡眠分類"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
        }
        val classificationDetail = TextView(this).apply {
            text = "會在 Google Play services 回報分類後自動更新。"
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(4), 0, 0)
        }
        classificationCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addView(classificationScore)
            addView(classificationDetail)
        })
        configuredRoot.addView(classificationCard)

        val latest = createLatestSessionSkeleton()
        configuredRoot.addView(latest.card)

        val emptyCard = createCard().apply {
            visibility = View.GONE
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
                addView(TextView(this@MainActivity).apply {
                    text = "準備好迎接第一晚。睡眠時間會在起床後自動整理並同步，不需要每天操作。"
                    textSize = 14f
                    setTextColor(color(R.color.text_secondary))
                })
            })
        }
        configuredRoot.addView(emptyCard)

        val historyRows = List(4) { index -> createHistoryRow(index) }
        val historyAllButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "查看全部紀錄"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { showAllSessions() }
        }
        val historyCard = createCard().apply { visibility = View.GONE }
        historyCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(TextView(this@MainActivity).apply {
                text = "更早的紀錄"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color(R.color.text_secondary))
                setPadding(0, 0, 0, dp(8))
            })
            historyRows.forEachIndexed { index, item ->
                item.divider?.let { addView(it) }
                addView(item.row)
            }
            addView(historyAllButton)
        })
        configuredRoot.addView(historyCard)

        configuredRoot.addView(createSectionTitle("自動偵測排程"))
        val scheduleTime = TextView(this).apply {
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val scheduleMode = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.purple_500))
            setPadding(0, dp(4), 0, dp(4))
        }
        val scheduleToggle = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            isAllCaps = false
            setOnClickListener {
                motionSettings.enabled = !motionSettings.enabled
                if (motionSettings.enabled) {
                    ensureAutomaticRecording()
                    guideBackgroundAccessIfNeeded()
                } else {
                    runCatching { SleepTracker.unsubscribe(this@MainActivity) }
                    if (MotionService.active != null) startService(Intent(this@MainActivity, MotionService::class.java).setAction(MotionService.ACTION_STOP))
                }
                refresh()
            }
        }
        val scheduleCard = createCard().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(scheduleTime)
                    addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        text = "修改時段"
                        isAllCaps = false
                        setOnClickListener { renderedSchedule?.let { chooseSchedule(it) } }
                    })
                })
                addView(scheduleMode)
                addView(TextView(this@MainActivity).apply {
                    setText(R.string.automatic_recording_description)
                    textSize = 13f
                    setTextColor(color(R.color.text_secondary))
                    setLineSpacing(0f, 1.2f)
                })
                addView(scheduleToggle)
            })
        }
        configuredRoot.addView(scheduleCard)

        configuredRoot.addView(createSectionTitle("系統連線與權限"))
        val permissionsReady = createBadge(getString(R.string.basic_permissions_ready), color(R.color.status_success), color(R.color.status_success_bg))
        val permissionRows = listOf(
            createPermissionRow("睡眠偵測", "允許 App 自動記錄"),
            createPermissionRow("使用情況存取", "排除夜間使用手機時間"),
            createPermissionRow("Health Connect", "自動寫入睡眠紀錄")
        )
        val permissionSummary = TextView(this).apply { textSize = 13f; setPadding(0, dp(12), 0, dp(8)) }
        val permissionGrant = MaterialButton(this).apply {
            text = "檢查並引導授權"
            isAllCaps = false
            setOnClickListener { requestMissingPermissionsAtStartup() }
        }
        val permissionRecheck = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "重新檢查權限"
            isAllCaps = false
            setOnClickListener { requestMissingPermissionsAtStartup() }
        }
        val permissionsCard = createCard().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(permissionsReady)
                permissionRows.forEachIndexed { index, item ->
                    if (index > 0) addView(createDivider())
                    addView(item.row)
                }
                addView(permissionSummary)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(permissionGrant, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
                    addView(permissionRecheck, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
            })
        }
        configuredRoot.addView(permissionsCard)

        val backgroundDescription = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
        }
        val backgroundSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(createSectionTitle(getString(R.string.background_recording_title)))
            addView(createCard().apply {
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(18), dp(16), dp(18), dp(16))
                    addView(backgroundDescription)
                    addView(MaterialButton(this@MainActivity).apply {
                        setText(R.string.allow_overnight_recording)
                        isAllCaps = false
                        setOnClickListener { openBatterySettings() }
                    })
                })
            })
        }
        configuredRoot.addView(backgroundSection)

        content.addView(header)
        content.addView(setupCard)
        content.addView(configuredRoot)
        return HomeViews(
            setupCard, configuredRoot, classificationScore, classificationDetail, emptyCard,
            latest.card, latest.title, latest.status, latest.duration, latest.times, latest.awake,
            historyCard, historyRows, historyAllButton, scheduleTime, scheduleMode, scheduleToggle, permissionsReady,
            permissionRows, permissionSummary, permissionGrant, backgroundSection, backgroundDescription
        )
    }

    private fun createLatestSessionSkeleton(): LatestViews {
        val title = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val status = createBadge("", color(R.color.status_info), color(R.color.status_info_bg))
        val duration = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.purple_500))
        }
        val times = TextView(this).apply {
            textSize = 16f
            setTextColor(color(R.color.text_primary))
            setPadding(0, 0, 0, dp(8))
        }
        val awake = TextView(this).apply {
            textSize = 13f
            setTextColor(color(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val card = createCard().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(18))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(title)
                    addView(status)
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.BOTTOM
                    setPadding(0, dp(10), 0, dp(8))
                    addView(duration)
                    addView(TextView(this@MainActivity).apply {
                        text = "  推估睡眠時長"
                        textSize = 13f
                        setTextColor(color(R.color.text_secondary))
                        setPadding(0, 0, 0, dp(3))
                    })
                })
                addView(times)
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 0, 0, dp(10))
                    addView(awake)
                })
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
                        text = "自選修正時間"
                        isAllCaps = false
                        setOnClickListener { renderedLatestSession?.let { editSession(it) } }
                    })
                    addView(MaterialButton(this@MainActivity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text = "查看完整詳情"
                        isAllCaps = false
                        setOnClickListener { renderedLatestSession?.let { showSession(it) } }
                    })
                })
            })
        }
        return LatestViews(card, title, status, duration, times, awake)
    }

    private fun createHistoryRow(index: Int): HistoryRowViews {
        val title = TextView(this).apply {
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(color(R.color.text_primary))
        }
        val summary = TextView(this).apply {
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        val badge = createBadge("", color(R.color.status_info), color(R.color.status_info_bg))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { renderedHistorySessions[index]?.let { showSession(it) } }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(title)
                addView(summary)
            })
            addView(badge)
        }
        return HistoryRowViews(row, title, summary, badge, if (index > 0) createDivider() else null)
    }

    private fun createPermissionRow(name: String, descriptionText: String): PermissionViews {
        val description = TextView(this).apply {
            text = descriptionText
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            setPadding(0, dp(2), 0, 0)
        }
        val badge = createBadge("", color(R.color.status_warning), color(R.color.status_warning_bg))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(this@MainActivity).apply {
                    text = name
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(color(R.color.text_primary))
                })
                addView(description)
            })
            addView(badge)
        }
        return PermissionViews(row, description, badge)
    }

    private fun renderHome(snapshot: HomeSnapshot) {
        val previousScroll = scroll.scrollY
        val configured = snapshot.configured && snapshot.schedule != null
        homeViews.setupCard.visibility = if (configured) View.GONE else View.VISIBLE
        homeViews.configuredRoot.visibility = if (configured) View.VISIBLE else View.GONE
        if (configured) {
            updateSleepSection(snapshot.sessions, snapshot.latestClassification)
            updateSchedule(requireNotNull(snapshot.schedule), snapshot.recordingEnabled)
            updatePermissions(snapshot.healthGranted)
            updateBackgroundAccess(snapshot.backgroundRestricted, snapshot.batteryExempt)
        }
        scroll.post { scroll.scrollTo(0, previousScroll) }
    }

    private fun updateSleepSection(sessions: List<SleepSession>, latestClassification: ClassificationSample?) {
        renderedSessions = sessions
        homeViews.classificationScore.text = latestClassification?.let { "最近一次 Sleep API 睡眠信心：${it.confidence}/100" } ?: "尚未收到 Sleep API 睡眠分類"
        homeViews.classificationDetail.text = latestClassification?.let {
            val time = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())
            "回報時間：${time.format(Instant.ofEpochMilli(it.timeMillis))} · 使用已保存資料，非即時查詢、非準確率"
        } ?: "會在 Google Play services 回報分類後自動更新。"
        renderedLatestSession = sessions.firstOrNull()
        homeViews.emptyCard.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        homeViews.latestCard.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
        homeViews.historyCard.visibility = if (sessions.size > 1) View.VISIBLE else View.GONE
        homeViews.historyAllButton.visibility = if (sessions.size > 1 + homeViews.historyRows.size) View.VISIBLE else View.GONE
        sessions.firstOrNull()?.let { session ->
            homeViews.latestTitle.text = session.title()
            updateBadge(homeViews.latestStatus, session.state)
            homeViews.latestDuration.text = formatDuration(session.durationMillis)
            val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
            homeViews.latestTimes.text = getString(R.string.sleep_times, time.format(Instant.ofEpochMilli(session.startMillis)), time.format(Instant.ofEpochMilli(session.endMillis)))
            homeViews.latestAwake.text = getString(R.string.excluded_phone_time, formatDuration(session.awakeMillis))
        }
        repeat(homeViews.historyRows.size) { index ->
            val session = sessions.drop(1).getOrNull(index)
            renderedHistorySessions[index] = session
            val row = homeViews.historyRows[index]
            row.row.visibility = if (session == null) View.GONE else View.VISIBLE
            row.divider?.visibility = if (session != null && sessions.drop(1).getOrNull(index - 1) != null) View.VISIBLE else View.GONE
            session?.let {
                row.title.text = it.title()
                row.summary.text = getString(R.string.sleep_duration_summary, formatDuration(it.durationMillis), formatDuration(it.awakeMillis))
                updateBadge(row.badge, it.state)
            }
        }
    }

    private fun updateSchedule(schedule: SleepSchedule, recordingEnabled: Boolean) {
        renderedSchedule = schedule
        homeViews.scheduleTime.text = schedule.label()
        homeViews.scheduleMode.text = if (recordingEnabled) "自動記錄已開啟" else "自動記錄已暫停"
        homeViews.scheduleToggle.text = if (recordingEnabled) "暫停自動記錄" else "恢復自動記錄"
    }

    private fun updatePermissions(healthGranted: Boolean) {
        val activityGranted = SleepTracker.hasActivityRecognition(this)
        val usageGranted = UsageMonitor.hasAccess(this)
        val allGranted = activityGranted && usageGranted && healthGranted
        val states = listOf(
            activityGranted to null,
            usageGranted to if (!usageGranted) "未授權時無法排除手機使用" else null,
            healthGranted to if (!healthGranted) "尚未連線或未授權寫入" else null
        )
        homeViews.permissionRows.forEachIndexed { index, row ->
            val (granted, note) = states[index]
            row.row.visibility = if (allGranted) View.GONE else View.VISIBLE
            row.description.text = note ?: listOf("允許 App 自動記錄", "排除夜間使用手機時間", "自動寫入睡眠紀錄")[index]
            row.description.setTextColor(if (note != null) color(R.color.status_warning) else color(R.color.text_secondary))
            row.badge.text = if (granted) "已允許 ✓" else "需要允許 ⚠"
            row.badge.setTextColor(if (granted) color(R.color.status_success) else color(R.color.status_warning))
            row.badge.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (granted) color(R.color.status_success_bg) else color(R.color.status_warning_bg))
            }
        }
        homeViews.permissionsReady.visibility = if (allGranted) View.VISIBLE else View.GONE
        homeViews.permissionSummary.visibility = if (allGranted) View.GONE else View.VISIBLE
        homeViews.permissionSummary.text = "⚠ 尚有未允許的項目。每次開啟 App 都會自動檢查；使用情況存取需在 Android 系統設定中開啟。"
        homeViews.permissionSummary.setTextColor(color(R.color.status_warning))
        homeViews.permissionGrant.visibility = if (allGranted) View.GONE else View.VISIBLE
    }

    private fun updateBackgroundAccess(backgroundRestricted: Boolean, batteryExempt: Boolean) {
        val visible = backgroundRestricted || !batteryExempt
        homeViews.backgroundSection.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) homeViews.backgroundDescription.setText(
            if (backgroundRestricted) R.string.battery_restricted_description else R.string.battery_optimized_description
        )
    }

    private fun updateBadge(view: TextView, state: SyncState) {
        val (text, textColor, backgroundColor) = syncBadgeStyle(state)
        view.text = text
        view.setTextColor(textColor)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(backgroundColor)
        }
    }

    private fun syncBadgeStyle(state: SyncState): Triple<String, Int, Int> {
        return when (state) {
            SyncState.SYNCED -> Triple("已同步", color(R.color.status_success), color(R.color.status_success_bg))
            SyncState.PENDING -> Triple("等待自動同步", color(R.color.status_info), color(R.color.status_info_bg))
            SyncState.SYNCING -> Triple("同步中…", color(R.color.status_info), color(R.color.status_info_bg))
            SyncState.FAILED -> Triple("同步失敗待重試", color(R.color.status_warning), color(R.color.status_warning_bg))
            SyncState.SKIPPED -> Triple("App 已自動略過", color(R.color.status_neutral), color(R.color.status_neutral_bg))
            SyncState.RETIRED -> Triple("正在整理舊資料", color(R.color.status_info), color(R.color.status_info_bg))
        }
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
        SleepDialogHelper.showSchedule(this, existing, ::setupSchedule)
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
        loadSessionDetails(session.id)
    }

    private fun loadSessionDetails(id: String) {
        lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { store.session(id) } ?: return@launch
            SleepDialogHelper.showSession(this@MainActivity, session, ::formatDuration) { editSession(session) }
        }
    }

    private fun editSession(session: SleepSession) {
        SleepDialogHelper.showTimeEditor(this, session, ::formatDuration,
            onSave = { newStart, newEnd ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { store.reviseTimes(session.id, newStart, newEnd) }
                    WorkScheduler.reconcileSoon(this@MainActivity)
                    refresh()
                }
            },
            onError = { showMessage(it) }
        )
    }

    private fun showAllSessions() {
        SleepDialogHelper.showAllSessions(
            context = this,
            loadPage = { offset, limit -> store.sessions(limit, offset, includeAwakeIntervals = false) },
            formatDuration = ::formatDuration,
            onSelected = ::loadSessionDetails
        )
    }

    private fun showMessage(text: String) = MaterialAlertDialogBuilder(this).setMessage(text).setPositiveButton("知道了", null).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(resId: Int): Int = ContextCompat.getColor(this, resId)
    private fun formatDuration(millis: Long): String = "%d 小時 %d 分鐘".format(millis / 3600000, (millis / 60000) % 60)

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
