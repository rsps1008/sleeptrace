package com.rsps1008.sleeptrace

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.TimePicker
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkManager
import androidx.health.connect.client.PermissionController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.health.HealthConnectSync
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepTracker
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageMonitor
import com.rsps1008.sleeptrace.work.WorkScheduler
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.SleepReconciler
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
    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { WorkScheduler.reconcileSoon(this); refresh() }
    private val requestAndroidPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (SleepTracker.hasActivityRecognition(this)) {
            subscribe()
            if (motionSettings.enabled) startMotion()
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.app_name)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusableInTouchMode = true
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        content.requestFocus()
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("sleeptrace_reconcile_now").observe(this) { refresh() }
        WorkManager.getInstance(this).getWorkInfosForUniqueWorkLiveData("sleeptrace_reconcile").observe(this) { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        WorkScheduler.schedule(this)
        WorkScheduler.reconcileSoon(this)
        refresh()
    }

    private fun refresh() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
        content.removeAllViews()
        val configured = preferences.configured()
        if (!configured) {
            addTitle("設定你的自動偵測時段")
            addText("眠迹只會在設定的時段分析睡眠，依可用資料自動選擇最佳推估並同步。授予使用情況存取權後，會排除手機使用時間；不需要逐筆確認。")
            addButton("設定時段並開始") { chooseSchedule() }
            return@launch
        }
        val schedule = preferences.schedule()
        addTitle("今晚的自動偵測")
        addText("偵測時段：${schedule.label()}\n模式：省電自動偵測（Google Sleep API）")
        addButton("修改偵測時段") { chooseSchedule(schedule) }
        motionPanel()
        permissionPanel()
        addTitle("最近睡眠紀錄")
        val sessions = store.sessions()
        if (sessions.isEmpty()) addText("尚無紀錄。Sleep API 的資料可能在夜間結束後才送達。")
        sessions.forEach { addSession(it) }
        addText("睡眠階段（深眠、淺眠與 REM）未在此版本估計，因手機放置位置與低頻感測資料不足以可靠區分。")
        }
    }

    private suspend fun motionPanel() {
        addTitle("加速度計＋批次處理（試驗版）")
        val placementLabel = if (motionSettings.placement == Placement.BED) "手機放在床上" else "手機放在床邊"
        val status = if (MotionService.active != null) motionSettings.status else if (motionSettings.enabled) "未執行／已中斷，請按重新啟動" else "尚未開啟"
        addText("$placementLabel\n$status\n床上模式：未供電 5 Hz、供電時 10 Hz，硬體批次最長 60 秒；無 FIFO 時降為 1 Hz。未供電且電量 ≤ 15% 時暫停。")
        addButton("放置位置：$placementLabel") {
            MaterialAlertDialogBuilder(this).setTitle("今晚手機放在哪裡？")
                .setSingleChoiceItems(arrayOf("床上：可輔助推估睡眠", "床邊：只記錄手機動作"), motionSettings.placement.ordinal) { dialog, which ->
                    motionSettings.placement = Placement.entries[which]
                    MotionService.active?.refreshConfiguration()
                    dialog.dismiss(); refresh()
                }.setNegativeButton("取消", null).show()
        }
        if (MotionService.active == null) addButton(if (motionSettings.enabled) "重新啟動動作偵測" else "開啟動作偵測") {
            motionSettings.enabled = true
            if (!SleepTracker.hasActivityRecognition(this)) requestCorePermissions() else startMotion()
        }
        if (motionSettings.enabled || MotionService.active != null) addButton("關閉動作偵測") {
            motionSettings.enabled = false
            if (MotionService.active != null) startService(Intent(this, MotionService::class.java).setAction(MotionService.ACTION_STOP))
            refresh()
        }
        addText("啟用後會有常駐通知，僅在設定時段取樣。重開機或被系統終止後，請開啟 App 重新啟動。省電排程可能延後開始；休眠漏收資料會保留為未知。")
        addButton("更新動作紀錄與睡眠試算") {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { SleepReconciler(this@MainActivity).reconcile() }
                WorkScheduler.reconcileSoon(this@MainActivity)
                refresh()
            }
        }
        val now = System.currentTimeMillis()
        val (minutes, usage) = withContext(Dispatchers.IO) {
            val rows = MotionStore(this@MainActivity).use { it.read(now - 24 * 60 * MINUTE_MS, now) }
            rows to if (rows.isEmpty()) emptyList() else UsageMonitor.interactionIntervals(this@MainActivity, rows.first().startMillis, now)
        }
        if (minutes.isEmpty()) addText("最近 24 小時尚無動作摘要。啟用後，摘要最多約 6 分鐘更新一次。")
        else {
            val formatter = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.systemDefault())
            val start = minutes.first().startMillis
            val end = minutes.last().startMillis + MINUTE_MS
            val quiet = minutes.count { it.placement == Placement.BED && it.level == MotionLevel.QUIET }
            val active = minutes.count { it.placement == Placement.BED && it.level == MotionLevel.ACTIVE }
            val unknown = ((end - start) / MINUTE_MS - quiet - active).coerceAtLeast(0)
            addText("${formatter.format(Instant.ofEpochMilli(start))} ～ ${formatter.format(Instant.ofEpochMilli(end))}\n安靜 $quiet 分鐘 · 活動 $active 分鐘 · 未知／床邊 $unknown 分鐘（不是睡眠總時數）")
            content.addView(MotionTimelineView(this, minutes, usage).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(40))
                contentDescription = "動作時間軸：安靜 $quiet 分鐘，活動 $active 分鐘，未知或床邊 $unknown 分鐘；紅色為手機使用"
            })
            addText("藍：安靜　橙：活動　灰：未知／床邊　紅：手機使用\n每日時段結束後，連續安靜至少 20 分鐘且總區段至少 30 分鐘，App 會自動採用最佳推估並同步。動作模式取最長區段；使用手機、資料中斷或持續活動會切斷區段。")
        }
        addText("安靜不代表深眠；翻身也不一定清醒。這版不推估深眠、淺眠或 REM。完成 Health Connect 授權後，App 會自動寫入睡眠紀錄，不需要逐筆操作。")
    }

    private fun startMotion() {
        runCatching { MotionService.start(this) }.onFailure {
            motionSettings.enabled = false
            showMessage("無法啟動動作偵測：${it.message}")
        }
        content.postDelayed({ if (!isFinishing && !isDestroyed) refresh() }, 500)
    }

    private suspend fun permissionPanel() {
        addTitle("連線狀態")
        val activityGranted = SleepTracker.hasActivityRecognition(this)
        val usageGranted = UsageMonitor.hasAccess(this)
        val healthGranted = healthSync.hasWritePermission()
        addText("活動辨識：${if (activityGranted) "已允許" else "需要允許"}\n" +
            "使用情況存取：${if (usageGranted) "已允許" else "需要允許（用來排除手機使用）"}\n" +
            "Health Connect：${if (healthGranted) "可寫入睡眠" else "尚未連線"}")
        if (!activityGranted || notificationPermissionNeeded()) {
            addButton("允許睡眠偵測通知與活動辨識") { requestCorePermissions() }
        }
        if (!usageGranted) addButton("開啟使用情況存取設定") { startActivity(UsageMonitor.accessIntent()) }
        if (!healthGranted) addButton("連線 Health Connect") { connectHealth() }
        if (activityGranted) addButton("重新啟用 Sleep API 偵測") { subscribe() }
    }

    private fun chooseSchedule(existing: SleepSchedule? = null) {
        val start = existing?.startMinute ?: 0
        TimePicker(this).apply {
            setIs24HourView(true); hour = start / 60; minute = start % 60
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("開始偵測時間").setView(this)
                .setPositiveButton("下一步") { _, _ -> chooseEndSchedule(hour * 60 + minute, existing?.endMinute ?: 540) }
                .setNegativeButton("取消", null).show()
        }
    }

    private fun chooseEndSchedule(startMinute: Int, currentEnd: Int) {
        TimePicker(this).apply {
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

    private fun subscribe() = SleepTracker.subscribe(this) { result ->
        if (result.isFailure) runOnUiThread { showMessage("無法啟用偵測：${result.exceptionOrNull()?.message}") }
        else refresh()
    }

    private fun connectHealth() {
        if (!healthSync.available()) { showMessage("Health Connect 尚未安裝、已停用或需要更新。請先在系統健康設定完成處理。"); return }
        requestHealthPermissions.launch(healthSync.writePermissions)
    }

    private fun addSession(session: SleepSession) {
        val duration = formatDuration(session.durationMillis)
        val status = when (session.state) {
            SyncState.SYNCED -> "已寫入 Health Connect"
            SyncState.PENDING -> "等待自動同步"
            SyncState.SYNCING -> "同步中"
            SyncState.FAILED -> "同步暫時失敗，將自動重試"
            SyncState.SKIPPED -> "App 已自動略過"
        }
        addButton("${session.title()}　$duration　$status\n參考分數 ${session.confidence}/100（非準確率）・${session.reason}") { showSession(session) }
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
        TimePicker(this).apply {
            setIs24HourView(true); hour = start.hour; minute = start.minute
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("修正入睡時間（保留日期）").setView(this)
                .setPositiveButton("下一步") { _, _ -> editEnd(session, hour, minute) }.setNegativeButton("取消", null).show()
        }
    }

    private fun editEnd(session: SleepSession, startHour: Int, startMinute: Int) {
        val zone = ZoneId.systemDefault()
        val end = Instant.ofEpochMilli(session.endMillis).atZone(zone)
        TimePicker(this).apply {
            setIs24HourView(true); hour = end.hour; minute = end.minute
            MaterialAlertDialogBuilder(this@MainActivity).setTitle("修正醒來時間（保留日期）").setView(this)
                .setPositiveButton("儲存") { _, _ ->
                    var newStart = Instant.ofEpochMilli(session.startMillis).atZone(zone).withHour(startHour).withMinute(startMinute).withSecond(0).withNano(0).toInstant().toEpochMilli()
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

    private fun addTitle(text: String) = content.addView(TextView(this).apply { this.text = text; textSize = 20f; setPadding(0, dp(12), 0, dp(6)) })
    private fun addText(text: String) = content.addView(TextView(this).apply { this.text = text; textSize = 15f; setPadding(0, 0, 0, dp(8)) })
    private fun addButton(text: String, action: () -> Unit) = content.addView(Button(this).apply { this.text = text; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL; setOnClickListener { action() } })
    private fun showMessage(text: String) = MaterialAlertDialogBuilder(this).setMessage(text).setPositiveButton("知道了", null).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
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
