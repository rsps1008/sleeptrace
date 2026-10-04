package com.rsps1008.sleeptrace

import android.Manifest
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.app.DatePickerDialog
import android.net.Uri
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import android.widget.Toast
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
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepTracker
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.SleepStageEstimator
import com.rsps1008.sleeptrace.sleep.normalizedAwake
import com.rsps1008.sleeptrace.sleep.stageUsageFor
import com.rsps1008.sleeptrace.sleep.reconciliationEvidenceStart
import com.rsps1008.sleeptrace.work.WorkScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.LocalDate

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
    private var sessionDetailJob: Job? = null
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
        guideBackgroundAccessIfNeeded()
    }
    private val windowAlarmSettingsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        ensureAutomaticRecording()
        refresh()
    }
    private val homeViewModel: HomeViewModel by viewModels()
    private var exportDate: LocalDate? = null
    private val createMotionCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val date = exportDate ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val zone = ZoneId.systemDefault()
                    val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val rows = dependencies.motionStore.read(start, end)
                    val sessions = store.sessionsInRange(start, end).filter {
                        it.state !in setOf(SyncState.SKIPPED, SyncState.RETIRED, SyncState.RETIRED_FAILED_PERMANENT)
                    }
                    val schedule = preferences.schedule()
                    val contextEnd = maxOf(end, sessions.maxOfOrNull { it.endMillis } ?: end) + 30 * MINUTE_MS
                    val contextStart = reconciliationEvidenceStart(
                        analysisStart = start,
                        sessionStarts = sessions.map { it.startMillis },
                        schedule = schedule,
                        now = contextEnd,
                        zone = zone
                    )
                    val awakeEvidence = normalizedAwake(
                        contextStart, contextEnd, sessions.flatMap { it.awakeIntervals }
                    )
                    val resolved = AutomaticPlacement.resolve(
                        dependencies.motionStore.read(contextStart, contextEnd), awakeEvidence, schedule
                    )
                    val placementByMinute = resolved.associateBy { it.startMillis }
                    val samples = store.recentSamples(contextStart)
                    val segments = store.segments(contextStart, contextEnd)
                    val diagnostics = sessions.associateWith { session ->
                        SleepStageEstimator.analyze(
                            session, resolved, samples,
                            stageUsageFor(session, awakeEvidence + session.awakeIntervals),
                            segments, schedule
                        )
                    }
                    val diagnosticByMinute = diagnostics.flatMap { (session, result) ->
                        result.minutes.map { minute ->
                            (Math.floorDiv(minute.startMillis, MINUTE_MS) * MINUTE_MS) to Triple(session, result, minute)
                        }
                    }.groupBy({ it.first }, { it.second })
                    data class SessionExportStats(
                        val legacyFeatureMinutes: Int,
                        val currentFeatureMinutes: Int,
                        val cadenceIncompatibleMinutes: Int,
                        val bedMinutes: Int,
                        val unknownMinutes: Int,
                        val deepEnterEvents: Int,
                        val deepExitEvents: Int
                    )
                    val captures = dependencies.motionStore.captures(contextStart, contextEnd)
                    val rawByMinute = rows.associateBy { it.startMillis }
                    val exportRows = (rows.map { it.startMillis } + diagnosticByMinute.keys.filter { it >= start && it < end })
                        .distinct().sorted().flatMap { key ->
                            val motion = rawByMinute[key] ?: com.rsps1008.sleeptrace.motion.MotionMinute(key, 0, 0, 0.0, 0, Placement.UNKNOWN, 0)
                            val matches = diagnosticByMinute[key].orEmpty()
                            if (matches.isEmpty()) listOf(motion to null) else matches.map { motion to it }
                        }
                    val exportStats = sessions.associateWith { session ->
                        val sessionRows = resolved.filter {
                            it.startMillis >= session.startMillis && it.startMillis < session.endMillis
                        }
                        val stageResult = diagnostics[session]
                        SessionExportStats(
                            legacyFeatureMinutes = sessionRows.count {
                                it.featureVersion == MotionAccumulator.LEGACY_CALLBACK_FEATURE_VERSION ||
                                    it.featureVersion == MotionAccumulator.LEGACY_FIXED_FEATURE_VERSION
                            },
                            currentFeatureMinutes = sessionRows.count { it.supportsCurrentStaging },
                            cadenceIncompatibleMinutes = sessionRows.count {
                                it.featureVersion == MotionAccumulator.CADENCE_INCOMPATIBLE_FEATURE_VERSION
                            },
                            bedMinutes = sessionRows.count { it.placement == Placement.BED },
                            unknownMinutes = sessionRows.count { it.placement == Placement.UNKNOWN },
                            deepEnterEvents = stageResult?.minutes?.count { it.event?.startsWith("enter_") == true } ?: 0,
                            deepExitEvents = stageResult?.minutes?.count { it.event?.startsWith("exit_") == true } ?: 0
                        )
                    }
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                        writer.write(com.rsps1008.sleeptrace.sleep.DIAGNOSTIC_CSV_HEADER + "\r\n")
                        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
                        exportRows.forEach { (minute, diagnostic) ->
                            val measured = minute.featureVersion != 0
                            val session = diagnostic?.first
                            val result = diagnostic?.second
                            val feature = diagnostic?.third
                            val stats = session?.let(exportStats::get)
                            val baseline = result?.baseline
                            val storedParts = session?.let { com.rsps1008.sleeptrace.sleep.sleepParts(it) }
                            val storedStage = storedParts?.filter { it.start < minute.startMillis + MINUTE_MS && it.end > minute.startMillis }
                                ?.map { it.stage }?.distinct()?.singleOrNull()
                            val capture = captures.firstOrNull { it.id == minute.recordingId }
                            // Placement is resolved for every measured row, including nights
                            // where no session diagnostic exists. Prefer that source so coupling
                            // failure evidence is still exported for exactly those failed nights.
                            val coupling = placementByMinute[minute.startMillis]?.coupling
                                ?: feature?.motion?.coupling
                            val totals = result?.durations
                            fun preciseParts(parts: List<com.rsps1008.sleeptrace.sleep.SleepPart>?) = parts.orEmpty()
                                .filter { it.start < minute.startMillis + MINUTE_MS && it.end > minute.startMillis }
                                .joinToString(";") { "${maxOf(it.start, minute.startMillis)}:${minOf(it.end, minute.startMillis + MINUTE_MS)}:${it.stage}" }
                            val extra = listOf(
                                if (measured) minute.featureVersion.toString() else "",
                                if (minute.featureVersion >= 2) minute.sampleCount.toString() else "",
                                placementByMinute[minute.startMillis]?.placement?.name.orEmpty(), minute.level.name,
                                session?.id.orEmpty(), baseline?.p25?.csvNumber().orEmpty(), baseline?.p35?.csvNumber().orEmpty(),
                                baseline?.p50?.csvNumber().orEmpty(), baseline?.p65?.csvNumber().orEmpty(),
                                baseline?.p70?.csvNumber().orEmpty(), baseline?.p75?.csvNumber().orEmpty(),
                                feature?.rollingMedianRms?.csvNumber().orEmpty(), feature?.stage?.name.orEmpty(),
                                storedStage?.name.orEmpty(), feature?.event.orEmpty(),
                                result?.motionCoverageRatio?.times(100)?.csvNumber().orEmpty(),
                                result?.sensorCoverageRatio?.times(100)?.csvNumber().orEmpty(),
                                result?.firstMotionDelayMillis?.div(60_000.0)?.csvNumber().orEmpty(),
                                feature?.stagingMotionUsable?.toString().orEmpty(),
                                feature?.stagingMotionRole.orEmpty(),
                                feature?.stagingMotionExclusionReason.orEmpty(),
                                result?.baselineFeatureVersion?.toString().orEmpty(),
                                result?.baselineSampleCount?.toString().orEmpty(),
                                result?.baselineEligibleMinutes?.toString().orEmpty(),
                                result?.currentFeatureValidMinutes?.toString().orEmpty(),
                                result?.baselineReason.orEmpty(),
                                stats?.legacyFeatureMinutes?.toString().orEmpty(),
                                stats?.currentFeatureMinutes?.toString().orEmpty(),
                                stats?.cadenceIncompatibleMinutes?.toString().orEmpty(),
                                stats?.bedMinutes?.toString().orEmpty(),
                                stats?.unknownMinutes?.toString().orEmpty(),
                                stats?.deepEnterEvents?.toString().orEmpty(),
                                stats?.deepExitEvents?.toString().orEmpty(),
                                feature?.stageAlgorithmVersion?.toString().orEmpty(), session?.stageAlgorithmVersion?.toString().orEmpty(),
                                session?.stageFeatureVersion?.toString().orEmpty(), feature?.endMillis?.toString().orEmpty(),
                                if (measured) (MINUTE_MS - minute.coveredMillis).toString() else "",
                                minute.longestGapMillis?.toString().orEmpty(), minute.maxDelta?.csvNumber().orEmpty(),
                                minute.movementEvents?.toString().orEmpty(), minute.longestActiveMillis?.toString().orEmpty(),
                                minute.quietTailMillis?.toString().orEmpty(), minute.postureDelta?.csvNumber().orEmpty(), minute.recordingId?.toString().orEmpty(),
                                (feature?.couplingState ?: coupling?.state)?.name.orEmpty(),
                                (feature?.couplingAgeMillis ?: coupling?.ageMillis)?.toString().orEmpty(),
                                (feature?.couplingInvalidation ?: coupling?.reason).orEmpty(),
                                feature?.canStage?.toString().orEmpty(), feature?.canEnterDeep?.toString().orEmpty(), feature?.canMaintainDeep?.toString().orEmpty(),
                                 feature?.priorState?.toString().orEmpty(), feature?.currentEligibility?.toString().orEmpty(),
                                 feature?.entryDecision?.applicable?.toString().orEmpty(), feature?.entryDecision?.allowed?.toString().orEmpty(),
                                 feature?.entryDecision?.reasons?.joinToString(";") { it.name }.orEmpty(),
                                 feature?.maintenanceDecision?.applicable?.toString().orEmpty(), feature?.maintenanceDecision?.allowed?.toString().orEmpty(),
                                 feature?.maintenanceDecision?.reasons?.joinToString(";") { it.name }.orEmpty(),
                                 feature?.action?.name.orEmpty(), feature?.transitionReason.orEmpty(), feature?.formalStage?.name.orEmpty(),
                                  feature?.wasBackfilled?.toString().orEmpty(), feature?.safetyCapAdjusted?.toString().orEmpty(),
                                  feature?.retroactivelyAdjusted?.toString().orEmpty(), feature?.retroactiveAdjustmentReason?.name.orEmpty(),
                                  feature?.retroactiveAdjustmentSourceMillis?.toString().orEmpty(), feature?.finalStage?.name.orEmpty(),
                                 feature?.highMotionWindowsBefore?.toString().orEmpty(), feature?.highMotionWindowsCandidate?.toString().orEmpty(),
                                 feature?.highMotionWindowsAfter?.toString().orEmpty(),
                                 feature?.currentEligibilityReasons?.joinToString(";") { it.name }.orEmpty(),
                                 feature?.windowBlockingReasons?.joinToString(";") { it.name }.orEmpty(),
                                 feature?.windowBlockingIntervals?.joinToString(";") { "${it.first}:${it.last}" }.orEmpty(),
                                 feature?.nonBlockingReasons?.joinToString(";") { it.name }.orEmpty(),
                                feature?.reasons?.joinToString(";") { it.name }.orEmpty(), feature?.primaryReason?.name.orEmpty(),
                                feature?.phoneUseMillis?.toString().orEmpty(), feature?.inOnsetGuard?.toString().orEmpty(), feature?.hasSleepEvidence?.toString().orEmpty(),
                                result?.firstValidMotionDelayMillis?.toString().orEmpty(), result?.sensorCoverageRatio?.csvNumber().orEmpty(),
                                result?.stageableCoverageRatio?.csvNumber().orEmpty(), totals?.span?.toString().orEmpty(), totals?.sleep?.toString().orEmpty(),
                                totals?.deep?.toString().orEmpty(), totals?.light?.toString().orEmpty(), totals?.sleeping?.toString().orEmpty(), totals?.awake?.toString().orEmpty(),
                                result?.undeterminedReasonsMillis?.entries?.joinToString(";") { "${it.key}:${it.value}" }.orEmpty(),
                                result?.longestGapMillis?.toString().orEmpty(),
                                preciseParts(session?.let { com.rsps1008.sleeptrace.sleep.sleepParts(it.copy(stageIntervals = result?.intervals.orEmpty())) }),
                                preciseParts(storedParts), capture?.trigger.orEmpty(), capture?.windowStart?.toString().orEmpty(),
                                capture?.registeredAt?.toString().orEmpty(), capture?.firstEvent?.toString().orEmpty(), capture?.periodUs?.toString().orEmpty(),
                                capture?.latencyUs?.toString().orEmpty(), capture?.fifoMaxEventCount?.toString().orEmpty(), capture?.wakeUp?.toString().orEmpty(),
                                capture?.rawEvents?.toString().orEmpty(), capture?.rejectedEvents?.toString().orEmpty(),
                                capture?.meanIntervalMillis?.csvNumber().orEmpty(), capture?.maxIntervalMillis?.toString().orEmpty(),
                                // Schema v2 is append-only: keep every v1 field name
                                // and position stable for existing CSV consumers.
                                coupling?.currentBlocker.orEmpty(), coupling?.lastResetReason.orEmpty(), coupling?.basis?.name.orEmpty(),
                                coupling?.fastStats?.historyMinutes?.toString().orEmpty(), coupling?.fastStats?.qualifyingMovements?.toString().orEmpty(),
                                coupling?.fastStats?.movementSpanMillis?.toString().orEmpty(), coupling?.sparseStats?.historyMinutes?.toString().orEmpty(),
                                coupling?.sparseStats?.qualifyingMovements?.toString().orEmpty(), coupling?.sparseStats?.movementSpanMillis?.toString().orEmpty(),
                                capture?.targetPeriodUs?.toString().orEmpty(), capture?.sensorMinDelayUs?.toString().orEmpty(),
                                capture?.sensorMaxDelayUs?.toString().orEmpty(), capture?.fifoReservedEventCount?.toString().orEmpty(),
                                capture?.targetHertz?.csvNumber().orEmpty(), capture?.registeredHertz?.csvNumber().orEmpty(),
                                capture?.observedRawHertz?.csvNumber().orEmpty(), capture?.featureHertz?.csvNumber().orEmpty(),
                                 com.rsps1008.sleeptrace.sleep.DIAGNOSTIC_SCHEMA_VERSION.toString(),
                                 feature?.isFallbackLight?.toString().orEmpty(),
                                 feature?.takeIf { it.isFallbackLight }?.primaryReason?.name.orEmpty(),
                                 result?.fallbackLightReasonsMillis?.entries?.joinToString(";") { "${it.key}:${it.value}" }.orEmpty(),
                                 baseline?.relativeQuietThreshold?.csvNumber().orEmpty(),
                                 baseline?.relativeQuietMethod.orEmpty()
                            )
                            val values = listOf(
                                formatter.format(Instant.ofEpochMilli(minute.startMillis)),
                                if (measured) (minute.coveredMillis / 1000.0).csvNumber() else "",
                                if (measured) (minute.activeMillis / 1000.0).csvNumber() else "",
                                if (measured) minute.rms.csvNumber() else "",
                                if (measured) minute.sampleCount.toString() else "",
                                if (measured) minute.placement.name else ""
                            ) + extra
                            writer.append(com.rsps1008.sleeptrace.sleep.diagnosticCsvRow(values)).append("\r\n")
                        }
                    } ?: error("無法建立匯出檔案")
                    exportRows.size
                }
            }
            result.onSuccess { count -> Toast.makeText(this@MainActivity, "已匯出 $count 分鐘資料", Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this@MainActivity, "匯出失敗：${it.message ?: "寫入檔案失敗"}", Toast.LENGTH_LONG).show() }
        }
    }
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
        lifecycleScope.launch {
            if (healthSync.hasWritePermission()) withContext(Dispatchers.IO) {
                AutomaticWorkSignals.markDirty(this@MainActivity)
                store.retryPermanentFailures()
            }
            WorkScheduler.reconcileSoon(this@MainActivity)
            if (continueStartupPermissionFlow) {
                continueStartupPermissionFlow = false
                permissionFlowComplete = true
                guideBackgroundAccessIfNeeded()
            }
            refresh()
        }
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
            setBackgroundColor(color(R.color.home_canvas))
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
        homeViews.captureView.detailsExpanded = savedInstanceState?.getBoolean("capture_details_expanded") ?: false
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
        if (store.hasPendingAutomaticWork()) WorkScheduler.reconcileSoon(this)
        ensureAutomaticRecording()
        if (!startupPermissionCheckDone) {
            startupPermissionCheckDone = true
            requestMissingPermissionsAtStartup()
        }
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("capture_details_expanded", homeViews.captureView.detailsExpanded)
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
        val captureView: HomeCaptureView,
        val scheduleTime: TextView,
        val scheduleMode: TextView,
        val scheduleDescription: TextView,
        val windowAlarmAccess: MaterialButton,
        val recordingModeButton: MaterialButton,
        val scheduleToggle: MaterialButton,
        val permissionsReady: TextView,
        val permissionRows: List<PermissionViews>,
        val permissionDividers: List<View>,
        val permissionSummary: TextView,
        val permissionGrant: MaterialButton,
        val permissionRecheck: MaterialButton,
        val backgroundSection: LinearLayout,
        val backgroundDescription: TextView
    )

    /** Builds the stable page hierarchy once; renderHome only changes data and visibility. */
    private fun createHomeSkeleton(): HomeViews {
        val header = homeColumn(4).apply {
            addView(homeText(getString(R.string.app_name), 26f, bold = true))
            addView(homeText("安心睡覺，醒來查看紀錄", 14f, secondary = true).apply {
                setPadding(0, dp(6), 0, dp(16))
            })
        }
        val setupCard = createCard().apply {
            addView(homeColumn().apply {
                addView(homeText("從你的作息開始", 21f, bold = true))
                addView(homeText("設定平日與週末的偵測時段。觀測完成後，App 會自動整理睡眠並同步至 Health Connect。", secondary = true).apply {
                    setPadding(0, dp(12), 0, dp(8))
                })
                addView(homeText("Sleep API 會判斷入睡與清醒，紀錄不需要逐筆確認。", secondary = true))
                addView(homeButton("設定時段並開始", primary = true) { chooseSchedule() })
            })
        }
        val configuredRoot = homeColumn(0).apply { visibility = View.GONE }
        configuredRoot.addView(createSectionTitle("最近睡眠紀錄"))
        val latest = createLatestSessionSkeleton()
        configuredRoot.addView(latest.card)
        val emptyCard = createCard().apply {
            visibility = View.GONE
            setCardBackgroundColor(color(R.color.home_hero))
            addView(homeColumn().apply {
                addView(homeText("等待第一晚的紀錄", 21f, bold = true))
                addView(homeText("完成時段與權限設定後，就可以安心休息。", secondary = true).apply {
                    setPadding(0, dp(10), 0, dp(8))
                })
                addView(homeText("觀測完成且資料足夠時，睡眠紀錄會自動出現在這裡。", secondary = true))
            })
        }
        configuredRoot.addView(emptyCard)
        val historyRows = List(4) { index -> createHistoryRow(index) }
        val historyAllButton = homeButton("查看全部紀錄") { showAllSessions() }.apply { visibility = View.GONE }
        val historyCard = createCard().apply {
            visibility = View.GONE
            addView(homeColumn().apply {
                addView(homeText("先前的睡眠", 15f, secondary = true, bold = true))
                historyRows.forEach { item ->
                    item.divider?.let { addView(it) }
                    addView(item.row)
                }
                addView(historyAllButton)
            })
        }
        configuredRoot.addView(createSectionTitle("記錄與排程"))
        val scheduleTime = homeText("", 22f, bold = true).apply {
            setPadding(0, dp(14), 0, dp(4))
        }
        val scheduleMode = createBadge("", color(R.color.status_info), color(R.color.status_info_bg))
        val scheduleDescription = homeText("", secondary = true).apply {
            setPadding(0, dp(8), 0, dp(12))
        }
        val windowAlarmAccess = homeButton("允許鬧鐘與提醒") { openWindowAlarmSettings() }
        val scheduleToggle = homeButton("") {
            motionSettings.enabled = !motionSettings.enabled
            if (motionSettings.enabled) {
                ensureAutomaticRecording()
                guideBackgroundAccessIfNeeded()
            } else {
                SleepWindowScheduler.cancel(this@MainActivity)
                runCatching { SleepTracker.unsubscribe(this@MainActivity) }
                if (MotionService.active != null) startService(Intent(this@MainActivity, MotionService::class.java).setAction(MotionService.ACTION_STOP))
            }
            refresh()
        }
        val recordingModeButton = homeButton("") { chooseRecordingMode() }
        val classificationScore = homeText("", 19f, bold = true)
        val classificationDetail = homeText("", 13f, secondary = true).apply { setPadding(0, dp(6), 0, 0) }
        configuredRoot.addView(createCard().apply {
            addView(homeColumn().apply {
                addView(scheduleMode)
                addView(scheduleTime)
                addView(scheduleDescription)
                addView(windowAlarmAccess)
                addView(homeActions(
                    homeButton("修改時段") { renderedSchedule?.let { chooseSchedule(it) } },
                    scheduleToggle
                ))
                addView(recordingModeButton)
                addView(homeText(getString(R.string.automatic_recording_description), 13f, secondary = true).apply {
                    setPadding(0, dp(8), 0, dp(16))
                })
                addView(createDivider())
                addView(homeText("Google 睡眠訊號", 14f, secondary = true, bold = true).apply {
                    setPadding(0, dp(16), 0, dp(8))
                })
                addView(classificationScore)
                addView(classificationDetail)
                addView(homeText("Sleep API 睡眠信心，非準確率。顯示已保存的回報，不會即時查詢 Google。", 13f, secondary = true).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            })
        })

        configuredRoot.addView(historyCard)
        configuredRoot.addView(createSectionTitle("連線與權限"))
        val permissionsReady = createBadge(getString(R.string.basic_permissions_ready), color(R.color.status_success), color(R.color.status_success_bg))
        val permissionRows = listOf(
            createPermissionRow("睡眠偵測", "取得 Google 睡眠訊號"),
            createPermissionRow("Health Connect", "自動寫入睡眠紀錄")
        )
        val permissionDividers = List(1) { createDivider() }
        val permissionSummary = homeText("", 13f, secondary = true).apply { setPadding(0, dp(12), 0, dp(8)) }
        val permissionGrant = homeButton("檢查並引導授權", primary = true) { requestMissingPermissionsAtStartup() }
        val permissionRecheck = homeButton("重新檢查權限") { requestMissingPermissionsAtStartup() }
        configuredRoot.addView(createCard().apply {
            addView(homeColumn().apply {
                addView(permissionsReady)
                permissionRows.forEachIndexed { index, item ->
                    if (index > 0) addView(permissionDividers[index - 1])
                    addView(item.row)
                }
                addView(permissionSummary)
                addView(permissionGrant)
                addView(permissionRecheck)
            })
        })
        val backgroundDescription = homeText("", secondary = true)
        val backgroundSection = homeColumn(0).apply {
            addView(createCard().apply {
                addView(homeColumn().apply {
                    addView(homeText(getString(R.string.background_recording_title), 16f, bold = true).apply {
                        setPadding(0, 0, 0, dp(8))
                    })
                    addView(backgroundDescription)
                    addView(homeButton(getString(R.string.allow_overnight_recording)) { openBatterySettings() })
                })
            })
        }
        configuredRoot.addView(backgroundSection)

        configuredRoot.addView(createSectionTitle("資料與匯出"))
        val captureView = HomeCaptureView(this)
        configuredRoot.addView(createCard().apply {
            addView(homeColumn().apply {
                addView(homeText("每分鐘動作摘要", 17f, bold = true))
                addView(homeText("保留最近 14 天，可選擇日期匯出 CSV。\n只保存分鐘摘要，不保存原始感測波形。", 13f, secondary = true).apply {
                    setPadding(0, dp(8), 0, dp(4))
                })
                addView(homeButton("匯出每分鐘動作資料") { chooseMotionExportDate() })
                addView(createDivider().apply {
                    layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(16) }
                })
                addView(captureView)
            })
        })
        configuredRoot.addView(homeText("睡眠時間來自 Sleep API；睡眠階段僅在睡眠階段模式推估。", 12f, secondary = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(8), dp(8), dp(12))
        })
        content.addView(header)
        content.addView(setupCard)
        content.addView(configuredRoot)
        return HomeViews(
            setupCard, configuredRoot, classificationScore, classificationDetail, emptyCard,
            latest.card, latest.title, latest.status, latest.duration, latest.times, latest.awake,
            historyCard, historyRows, historyAllButton, captureView, scheduleTime, scheduleMode, scheduleDescription,
            windowAlarmAccess, recordingModeButton, scheduleToggle, permissionsReady, permissionRows, permissionDividers, permissionSummary,
            permissionGrant, permissionRecheck, backgroundSection, backgroundDescription
        )
    }
    private fun createLatestSessionSkeleton(): LatestViews {
        val title = homeText("", 16f, secondary = true, bold = true).apply { setPadding(0, dp(14), 0, 0) }
        val status = createBadge("", color(R.color.status_info), color(R.color.status_info_bg))
        val duration = homeText("", 32f, bold = true).apply {
            setTextColor(color(R.color.home_accent))
            setPadding(0, dp(6), 0, dp(2))
        }
        val times = homeText("", 17f).apply { setPadding(0, dp(16), 0, dp(10)) }
        val awake = homeText("", 13f, secondary = true).apply { setPadding(0, 0, 0, dp(12)) }
        val card = createCard().apply {
            setCardBackgroundColor(color(R.color.home_hero))
            addView(homeColumn().apply {
                addView(status)
                addView(title)
                addView(duration)
                addView(homeText("推估睡眠時長", 13f, secondary = true))
                addView(times)
                addView(awake)
                addView(homeActions(
                    homeButton("查看詳情", primary = true) { renderedLatestSession?.let { showSession(it) } },
                    homeButton("修正時間") { renderedLatestSession?.let { editSession(it) } }
                ))
            })
        }
        return LatestViews(card, title, status, duration, times, awake)
    }

    private fun createHistoryRow(index: Int): HistoryRowViews {
        val title = homeText("", 16f, bold = true)
        val summary = homeText("", 13f, secondary = true).apply { setPadding(0, dp(6), 0, dp(10)) }
        val badge = createBadge("", color(R.color.status_info), color(R.color.status_info_bg))
        val row = homeColumn(0).apply {
            setPadding(dp(4), dp(16), dp(4), dp(16))
            isClickable = true
            isFocusable = true
            val selectable = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, selectable, true)
            foreground = ContextCompat.getDrawable(this@MainActivity, selectable.resourceId)
            setOnClickListener { renderedHistorySessions[index]?.let { showSession(it) } }
            addView(title)
            addView(summary)
            addView(badge)
        }
        return HistoryRowViews(row, title, summary, badge, if (index > 0) createDivider() else null)
    }

    private fun createPermissionRow(name: String, descriptionText: String): PermissionViews {
        val description = homeText(descriptionText, 13f, secondary = true).apply { setPadding(0, dp(6), 0, dp(8)) }
        val badge = createBadge("", color(R.color.status_warning), color(R.color.status_warning_bg))
        val row = homeColumn(0).apply {
            setPadding(0, dp(12), 0, dp(12))
            addView(homeText(name, 16f, bold = true))
            addView(description)
            addView(badge)
        }
        return PermissionViews(row, description, badge)
    }
    private fun renderHome(snapshot: HomeSnapshot) {
        val configured = snapshot.configured && snapshot.schedule != null
        homeViews.setupCard.visibility = if (configured) View.GONE else View.VISIBLE
        homeViews.configuredRoot.visibility = if (configured) View.VISIBLE else View.GONE
        if (configured) {
            updateSleepSection(snapshot.sessions, snapshot.latestClassification)
            homeViews.captureView.bind(snapshot.latestCapture)
            updateSchedule(requireNotNull(snapshot.schedule), snapshot.recordingEnabled,
                snapshot.recordingMode, snapshot.exactAlarmAllowed)
            updatePermissions(snapshot.healthGranted)
            updateBackgroundAccess(snapshot.backgroundRestricted, snapshot.batteryExempt, snapshot.recordingMode)
        }
        // The stable hierarchy preserves ScrollView's position naturally. Posting an old scrollY
        // here can undo a user scroll that happens between data binding and the next frame.
    }

    private fun updateSleepSection(sessions: List<SleepSession>, latestClassification: ClassificationSample?) {
        renderedSessions = sessions
        homeViews.classificationScore.text = latestClassification?.let { "${it.confidence} / 100" } ?: "尚未收到回報"
        homeViews.classificationDetail.text = latestClassification?.let {
            val time = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())
            "最近回報　${time.format(Instant.ofEpochMilli(it.timeMillis))}"
        } ?: "會在 Google Play services 回報分類後自動更新。"
        renderedLatestSession = sessions.firstOrNull()
        homeViews.emptyCard.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        homeViews.latestCard.visibility = if (sessions.isEmpty()) View.GONE else View.VISIBLE
        homeViews.historyCard.visibility = if (sessions.size > 1) View.VISIBLE else View.GONE
        homeViews.historyAllButton.visibility = if (sessions.size > 1) View.VISIBLE else View.GONE
        sessions.firstOrNull()?.let { session ->
            homeViews.latestTitle.text = session.title()
            updateBadge(homeViews.latestStatus, session.state)
            val durationLabel = formatDuration(session.durationMillis)
            homeViews.latestDuration.text = if (resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 360)
                durationLabel.replace(" 小時 ", " 小時\n") else durationLabel
            homeViews.latestDuration.contentDescription = durationLabel
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

    private fun updateSchedule(
        schedule: SleepSchedule,
        recordingEnabled: Boolean,
        recordingMode: RecordingMode,
        exactAlarmAllowed: Boolean
    ) {
        renderedSchedule = schedule
        fun range(start: Int, end: Int): String {
            fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
            return if (start == end) "全天（${clock(start)} 起）"
            else "${clock(start)} – ${clock(end)}${if (end < start) "（隔日）" else ""}"
        }
        homeViews.scheduleTime.text = if (schedule.weekendStartMinute != null && schedule.weekendEndMinute != null) {
            "平日\n${range(schedule.startMinute, schedule.endMinute)}\n\n週末\n${range(schedule.weekendStartMinute, schedule.weekendEndMinute)}"
        } else "每日\n${range(schedule.startMinute, schedule.endMinute)}"
        homeViews.scheduleMode.text = if (recordingEnabled) "自動記錄已開啟" else "自動記錄已暫停"
        homeViews.scheduleMode.setTextColor(color(if (recordingEnabled) R.color.status_success else R.color.status_neutral))
        homeViews.scheduleMode.backgroundTintList = android.content.res.ColorStateList.valueOf(
            color(if (recordingEnabled) R.color.status_success_bg else R.color.status_neutral_bg))
        homeViews.scheduleDescription.text = when {
            !recordingEnabled -> "目前不會自動偵測。恢復後會依設定時段記錄。"
            recordingMode == RecordingMode.BATTERY_SAVER -> "省電模式只使用 Google 睡眠訊號，不啟動加速度計，也不推估淺眠或深眠。"
            !schedule.requiresWindowBoundary() -> "目前設定為全天觀測，App 會依可用資料整理睡眠。"
            exactAlarmAllowed -> "觀測完成後自動整理；有持續睡眠訊號時，可能延長觀測。"
            else -> "尚未允許「鬧鐘與提醒」。時段開始時可能無法準時啟動，造成漏記。"
        }
        homeViews.windowAlarmAccess.visibility = if (recordingEnabled && schedule.requiresWindowBoundary() && !exactAlarmAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) View.VISIBLE else View.GONE
        homeViews.recordingModeButton.text = if (recordingMode == RecordingMode.STAGES) {
            "記錄模式：睡眠階段"
        } else "記錄模式：省電"
        homeViews.scheduleToggle.text = if (recordingEnabled) "暫停自動記錄" else "恢復自動記錄"
    }

    private fun chooseRecordingMode() {
        val choices = arrayOf(
            "睡眠階段\n使用 Sleep API 與加速度計推估淺眠／深眠",
            "省電\n只使用 Sleep API 記錄睡眠與清醒時間"
        )
        val current = if (motionSettings.recordingMode == RecordingMode.STAGES) 0 else 1
        MaterialAlertDialogBuilder(this)
            .setTitle("記錄模式")
            .setSingleChoiceItems(choices, current) { dialog, which ->
                val selected = if (which == 0) RecordingMode.STAGES else RecordingMode.BATTERY_SAVER
                if (selected != motionSettings.recordingMode) {
                    motionSettings.recordingMode = selected
                    if (selected == RecordingMode.BATTERY_SAVER && MotionService.active != null) {
                        MotionService.active?.refreshConfiguration()
                    }
                    AutomaticWorkSignals.markDirty(this)
                    WorkScheduler.reconcileSoon(this)
                    ensureAutomaticRecording()
                    refresh()
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updatePermissions(healthGranted: Boolean) {
        val activityGranted = SleepTracker.hasActivityRecognition(this)
        val allGranted = activityGranted && healthGranted
        val states = listOf(
            activityGranted to null,
            healthGranted to if (!healthGranted) "尚未連線或未授權寫入" else null
        )
        homeViews.permissionRows.forEachIndexed { index, row ->
            val (granted, note) = states[index]
            row.row.visibility = if (allGranted) View.GONE else View.VISIBLE
            row.description.text = note ?: listOf("取得 Google 睡眠訊號", "自動寫入睡眠紀錄")[index]
            row.description.setTextColor(if (note != null) color(R.color.status_warning) else color(R.color.text_secondary))
            row.badge.text = if (granted) "已允許" else "尚未允許"
            row.badge.setTextColor(if (granted) color(R.color.status_success) else color(R.color.status_warning))
            row.badge.background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(if (granted) color(R.color.status_success_bg) else color(R.color.status_warning_bg))
            }
        }
        homeViews.permissionsReady.visibility = if (allGranted) View.VISIBLE else View.GONE
        homeViews.permissionDividers.forEach { it.visibility = if (allGranted) View.GONE else View.VISIBLE }
        homeViews.permissionSummary.visibility = if (allGranted) View.GONE else View.VISIBLE
        homeViews.permissionSummary.text = "尚有權限需要設定，點選下方按鈕即可依序完成。每次開啟 App 也會自動檢查。"
        homeViews.permissionSummary.setTextColor(color(R.color.status_warning))
        homeViews.permissionGrant.visibility = if (allGranted) View.GONE else View.VISIBLE
        homeViews.permissionRecheck.visibility = if (allGranted) View.VISIBLE else View.GONE
    }

    private fun updateBackgroundAccess(
        backgroundRestricted: Boolean,
        batteryExempt: Boolean,
        recordingMode: RecordingMode
    ) {
        val visible = recordingMode == RecordingMode.STAGES && (backgroundRestricted || !batteryExempt)
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
            SyncState.FAILED_RETRYABLE -> Triple("同步失敗待重試", color(R.color.status_warning), color(R.color.status_warning_bg))
            SyncState.FAILED_PERMANENT -> Triple("同步失敗需處理", color(R.color.status_warning), color(R.color.status_warning_bg))
            SyncState.SKIPPED -> Triple("App 已自動略過", color(R.color.status_neutral), color(R.color.status_neutral_bg))
            SyncState.RETIRED -> Triple("正在整理舊資料", color(R.color.status_info), color(R.color.status_info_bg))
            SyncState.RETIRED_FAILED_PERMANENT -> Triple("舊資料移除失敗", color(R.color.status_warning), color(R.color.status_warning_bg))
        }
    }

    private fun guideBackgroundAccessIfNeeded() = lifecycleScope.launch {
        if (!permissionFlowComplete || !preferences.configured() || !motionSettings.enabled) return@launch
        val schedule = preferences.schedule()
        lifecycle.withResumed {
            if (!backgroundSettingsOpen && motionSettings.enabled) {
                when {
                    motionSettings.recordingMode == RecordingMode.STAGES &&
                        !backgroundAccess.batteryReady && !backgroundAccess.batteryGuideShown -> openBatterySettings()
                    motionSettings.recordingMode == RecordingMode.STAGES &&
                        backgroundAccess.isXiaomi && !backgroundAccess.xiaomiGuideShown -> openXiaomiSettings()
                    schedule.requiresWindowBoundary() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        !SleepWindowScheduler.hasExactAlarmAccess(this@MainActivity) && !motionSettings.windowAlarmGuideShown -> guideWindowAlarm()
                }
            }
        }
    }

    private fun guideWindowAlarm() {
        motionSettings.windowAlarmGuideShown = true
        val saver = motionSettings.recordingMode == RecordingMode.BATTERY_SAVER
        MaterialAlertDialogBuilder(this)
            .setTitle(if (saver) "準時完成睡眠整理" else "睡眠窗外關閉背景服務")
            .setMessage(if (saver) {
                "允許「鬧鐘與提醒」後，眠迹可在排程邊界準時切換 Sleep API 回報種類，並在窗口完成時整理昨晚睡眠。若略過，Android 可能延遲背景鬧鐘；SleepSegmentEvent 仍會在 Google 回報後觸發整理，開啟 App 時也會補排程。"
            } else {
                "允許「鬧鐘與提醒」後，眠迹可準時啟動觀測，並在排程結束時評估是否仍在睡眠。有足夠起床證據會提早整理，仍有近期睡眠證據則延長觀測，實際觀測結束後關閉前景服務。若略過，Android 可能限制鬧鐘或 Sleep API 回呼從背景啟動服務，造成動作資料缺口；Sleep API 睡眠區段仍會接收，開啟 App 時也會補啟動。"
            })
            .setPositiveButton("開啟系統設定") { _, _ -> openWindowAlarmSettings() }
            .setNegativeButton("稍後", null)
            .show()
    }

    private fun openWindowAlarmSettings() {
        motionSettings.windowAlarmGuideShown = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || SleepWindowScheduler.hasExactAlarmAccess(this)) {
            ensureAutomaticRecording()
            refresh()
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.parse("package:$packageName"))
        try {
            windowAlarmSettingsLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage("請在系統設定的「特殊應用程式存取權」中開啟「鬧鐘與提醒」。")
        } catch (_: SecurityException) {
            showMessage("系統未提供鬧鐘與提醒設定入口，請從 App 資訊手動開啟。")
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

    private fun chooseMotionExportDate() {
        val yesterday = LocalDate.now().minusDays(1)
        DatePickerDialog(this, { _, year, month, day ->
            val date = LocalDate.of(year, month + 1, day)
            exportDate = date
            createMotionCsv.launch("sleeptrace_motion_${date}.csv")
        }, yesterday.year, yesterday.monthValue - 1, yesterday.dayOfMonth).show()
    }

    private fun Double.csvNumber(): String = if (isFinite()) String.format(java.util.Locale.US, "%.6f", this) else ""

    private fun homeColumn(padding: Int = 20) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }

    private fun homeText(value: String, size: Float = 14f, secondary: Boolean = false, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        breakStrategy = android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
        setTextColor(color(if (secondary) R.color.text_secondary else R.color.text_primary))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(dp(2).toFloat(), 1.15f)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun homeButton(label: String, primary: Boolean = false, action: () -> Unit) = MaterialButton(
        this, null, if (primary) com.google.android.material.R.attr.materialButtonStyle
        else com.google.android.material.R.attr.materialButtonOutlinedStyle
    ).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        minHeight = dp(52)
        cornerRadius = dp(14)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
        setOnClickListener { action() }
    }

    /** Stack actions when the usable width or font size would squeeze their labels. */
    private fun homeActions(vararg buttons: MaterialButton) = LinearLayout(this).apply {
        val stacked = resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 360
        orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        buttons.forEachIndexed { index, button ->
            addView(button, LinearLayout.LayoutParams(if (stacked) -1 else 0, -2, if (stacked) 0f else 1f).apply {
                topMargin = dp(8)
                if (!stacked && index < buttons.lastIndex) marginEnd = dp(8)
            })
        }
    }

    private fun createSectionTitle(title: String): TextView = homeText(title, 16f, bold = true).apply {
        isAccessibilityHeading = true
        setPadding(dp(4), dp(18), dp(4), dp(12))
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
        radius = dp(22).toFloat()
        cardElevation = dp(0).toFloat()
        strokeWidth = dp(1)
        setStrokeColor(color(R.color.card_stroke))
        setCardBackgroundColor(color(R.color.home_surface))
    }

    private fun createBadge(text: String, textColor: Int, bgColor: Int): TextView = TextView(this).apply {
        this.text = text
        this.setTextColor(textColor)
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(-2, -2)
        setLineSpacing(dp(2).toFloat(), 1.1f)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(bgColor)
        }
        setPadding(dp(10), dp(6), dp(10), dp(6))
    }

    private fun ensureAutomaticRecording() = lifecycleScope.launch {
        val configured = preferences.configured()
        val schedule = if (configured) preferences.schedule() else null
        if (schedule == null || !motionSettings.enabled || !SleepTracker.hasActivityRecognition(this@MainActivity)) {
            SleepWindowScheduler.cancel(this@MainActivity)
            runCatching { SleepTracker.unsubscribe(this@MainActivity) }
            MotionService.active?.refreshConfiguration()
            return@launch
        }
        SleepWindowScheduler.schedule(this@MainActivity, schedule)
        SleepTracker.syncSubscription(this@MainActivity, schedule, motionSettings.enabled, System.currentTimeMillis())
        val now = System.currentTimeMillis()
        val stagesEnabled = motionSettings.recordingMode == RecordingMode.STAGES
        val shouldKeepService = stagesEnabled && SleepWindowScheduler.shouldRunForegroundService(schedule, now)
        if (!stagesEnabled && MotionService.active != null) {
            MotionService.active?.refreshConfiguration()
        } else if (MotionService.active != null) MotionService.active?.refreshConfiguration()
        if (shouldKeepService && MotionService.active == null) {
            lifecycle.withResumed {
                if (motionSettings.enabled) runCatching { MotionService.start(this@MainActivity) }.onFailure {
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
        SleepTracker.syncSubscription(this@MainActivity, schedule, motionSettings.enabled, System.currentTimeMillis(), force = true)
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
                permissionFlowComplete = true
                guideBackgroundAccessIfNeeded()
                return@launch
            }
            continueStartupPermissionFlow = true
            requestHealthPermissions.launch(healthSync.writePermissions)
        }
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
        sessionDetailJob?.cancel()
        sessionDetailJob = lifecycleScope.launch {
            val session = withContext(Dispatchers.IO) { store.session(id) } ?: return@launch
            SleepDialogHelper.showSession(
                this@MainActivity, session, ::formatDuration,
                onEdit = { editSession(session) },
                onRetry = if (session.state in setOf(SyncState.FAILED_PERMANENT, SyncState.RETIRED_FAILED_PERMANENT)) {
                    ({ retrySession(session) })
                } else null
            )
        }
    }

    private fun retrySession(session: SleepSession) = lifecycleScope.launch {
        val restored = withContext(Dispatchers.IO) { store.retryPermanentFailure(session.id) }
        if (restored) {
            WorkScheduler.reconcileSoon(this@MainActivity)
            refresh()
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
