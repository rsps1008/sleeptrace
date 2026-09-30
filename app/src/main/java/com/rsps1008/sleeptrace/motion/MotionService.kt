package com.rsps1008.sleeptrace.motion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rsps1008.sleeptrace.MainActivity
import com.rsps1008.sleeptrace.sleepDependencies
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepTracker
import com.rsps1008.sleeptrace.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Automatic foreground recording after setup; no continuous wake lock or raw sensor persistence. */
class MotionService : Service(), SensorEventListener2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sensors: SensorManager
    private lateinit var settings: MotionSettings
    private lateinit var store: MotionStore
    private var sensor: Sensor? = null
    private var plan: SamplingPlan? = null
    private var accumulator: MotionAccumulator? = null
    private var schedule: SleepSchedule? = null
    private var triggeredWindowStart: Long? = null
    private var fallbackWindowStart: Long? = null
    @Volatile private var screenOffSince: Long? = null
    private var recentClassifications: List<ClassificationSample> = emptyList()
    private var windowEndedNormally = false
    private var clockOffset = 0L
    private var lastPersistElapsedRealtime = 0L
    private val pendingMinutes = mutableListOf<MotionMinute>()
    private var pendingChange: (() -> Unit)? = null
    private var stopped = false
    private var destroyed = false
    private var notice = "準備自動記錄睡眠"
    private val finishChange = Runnable { finishTransition() }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = System.currentTimeMillis()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffSince = now
                    return
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOffSince = null
                    return
                }
                Intent.ACTION_TIME_CHANGED -> clockOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            }
            refreshConfiguration()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = sleepDependencies().motionSettings
        store = sleepDependencies().motionStore
        sensors = getSystemService(SensorManager::class.java)
        // Wake-up FIFO can retain events while the CPU sleeps. Prefer it over non-wake-up sensors.
        sensor = sensors.getSensorList(Sensor.TYPE_ACCELEROMETER).sortedWith(
            compareByDescending<Sensor> { it.isWakeUpSensor && it.fifoMaxEventCount > 0 }
                .thenByDescending { it.fifoMaxEventCount > 0 }.thenBy { it.power }
        ).firstOrNull()
        thread = HandlerThread("sleeptrace-motion").apply { start() }
        handler = Handler(thread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "自動睡眠記錄", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.enabled = false
            runCatching { SleepTracker.unsubscribe(this) }
            SleepWindowScheduler.cancel(this)
            handler.post {
                stopped = true
                transition { stopSelf() }
            }
            return START_NOT_STICKY
        }
        if (!settings.enabled || !SleepTracker.hasActivityRecognition(this)) {
            settings.status = "尚未啟動：請允許活動辨識並在 App 中啟動"
            stopSelf(); return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH else 0)
        } catch (error: RuntimeException) {
            settings.status = "無法啟動：${error.message}"
            stopSelf(); return START_NOT_STICKY
        }
        if (active == null) {
            active = this
            ContextCompat.registerReceiver(this, powerReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_LOW)
                addAction(Intent.ACTION_BATTERY_OKAY)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            if (!getSystemService(PowerManager::class.java).isInteractive) screenOffSince = System.currentTimeMillis()
        }
        refreshConfiguration()
        return START_STICKY
    }

    fun refreshConfiguration() {
        scope.launch(Dispatchers.IO) {
            val prefs = sleepDependencies().preferences
            val newSchedule = if (prefs.configured()) prefs.schedule() else null
            val classifications = sleepDependencies().store.recentSamples(
                System.currentTimeMillis() - SleepClassificationTrigger.MAX_EVENT_AGE_MILLIS
            )
            handler.post {
                if (!stopped && !destroyed) {
                    recentClassifications = classifications
                    configure(newSchedule, classifications)
                }
            }
        }
    }

    /** Called after Google Play services delivers sleep classifications. */
    fun onSleepClassifications(samples: List<ClassificationSample>) {
        handler.post {
            if (!stopped && !destroyed) {
                recentClassifications = samples
                configure(schedule, samples)
            }
        }
    }

    private fun configure(newSchedule: SleepSchedule?, classifications: List<ClassificationSample> = recentClassifications) {
        schedule = newSchedule
        if (!settings.enabled || !SleepTracker.hasActivityRecognition(this)) {
            stopped = true
            transition { stopSelf() }; return
        }
        SleepWindowScheduler.schedule(this, newSchedule)
        val now = System.currentTimeMillis()
        val window = newSchedule?.windowAt(now)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val lowBattery = !charging && level >= 0 && scale > 0 && level.toDouble() / scale <= 0.15
        if (window == null) {
            triggeredWindowStart = null
            fallbackWindowStart = null
        }
        if (window != null && triggeredWindowStart != window.start &&
            SleepClassificationTrigger.shouldStart(classifications, window, now)) {
            triggeredWindowStart = window.start
            fallbackWindowStart = null
        } else if (window != null && triggeredWindowStart != window.start &&
            SleepClassificationTrigger.shouldFallback(window, now, screenOffSince)) {
            triggeredWindowStart = window.start
            fallbackWindowStart = window.start
        }
        val pauseReason = when {
            sensor == null -> "這支手機沒有可用的加速度計"
            window == null -> "睡眠窗外，沒有取樣"
            triggeredWindowStart != window.start -> "等待 Google 判斷進入睡眠"
            lowBattery -> "電量 ≤ 15%，暫停動作偵測"
            else -> null
        }
        if (pauseReason != null) {
            val wasRecording = accumulator != null
            val stopOutsideWindow = window == null
            transition {
                publish(if (stopOutsideWindow) "睡眠窗外，背景服務已停止" else pauseReason)
                if (wasRecording) WorkScheduler.reconcileSoon(this)
                if (stopOutsideWindow) {
                    windowEndedNormally = true
                    stopSelf()
                }
            }
            return
        }
        val activeWindow = requireNotNull(window)
        val selected = sensor!!
        val next = SamplingPlan.choose(selected.fifoMaxEventCount, selected.minDelay)
        val offset = now - SystemClock.elapsedRealtime()
        if (plan == next && kotlin.math.abs(clockOffset - offset) < 2_000 && pendingChange == null) return
        transition {
            clockOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            accumulator = MotionAccumulator(next, Placement.AUTO)
            val registered = runCatching { sensors.registerListener(this, selected, next.periodUs, next.latencyUs, handler) }.getOrDefault(false)
            if (!registered) {
                accumulator = null
                publish("加速度計註冊失敗，請重新啟動動作偵測")
            } else {
                plan = next
                val batching = if (next.latencyUs > 0) "批次上限 ${next.latencyUs / 1_000_000} 秒" else "無硬體 FIFO"
                val sleepHint = if (!selected.isWakeUpSensor) "；休眠時可能缺資料" else ""
                val source = if (fallbackWindowStart == activeWindow.start) {
                    "Google 分類延遲時的低頻備援"
                } else "Google 已判斷入睡"
                publish("$source · ${1_000_000 / next.periodUs} Hz · $batching$sleepHint")
            }
        }
    }

    private fun transition(action: () -> Unit) {
        val alreadyFlushing = pendingChange != null
        pendingChange = action
        if (alreadyFlushing) return
        if (accumulator == null || !runCatching { sensors.flush(this) }.getOrDefault(false)) finishTransition()
        else handler.postDelayed(finishChange, 2_000)
    }

    private fun finishTransition() {
        val action = pendingChange ?: return
        pendingChange = null
        handler.removeCallbacks(finishChange)
        sensors.unregisterListener(this)
        accumulator?.let { pendingMinutes += it.drain(Long.MAX_VALUE, includePartial = true) }
        accumulator = null; plan = null
        persist()
        if (!destroyed) action()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val engine = accumulator ?: return
        val time = clockOffset + event.timestamp / 1_000_000
        val window = schedule?.windowAt(time) ?: return
        if (time < window.start || time >= window.end) {
            if (pendingChange == null && !stopped) configure(schedule)
            return
        }
        if (!engine.add(time, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())) return
        pendingMinutes += engine.drain(time)
        // Sensor timestamps can jump across a whole FIFO batch. Throttle by elapsed wall time,
        // which continues during device suspend, not by sample event time.
        if (SystemClock.elapsedRealtime() - lastPersistElapsedRealtime >= PERSIST_INTERVAL_MS) persist()
    }
    override fun onFlushCompleted(sensor: Sensor?) { finishTransition() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun persist() {
        if (pendingMinutes.isEmpty()) return
        try { store.append(pendingMinutes.toList()); pendingMinutes.clear() }
        catch (_: RuntimeException) {
            // Bound memory if storage is full; never invent coverage for discarded data.
            pendingMinutes.clear()
            publish("動作資料儲存失敗，請檢查儲存空間")
        }
        lastPersistElapsedRealtime = SystemClock.elapsedRealtime()
    }

    private fun publish(text: String) {
        if (notice == text) return
        notice = text; settings.status = text
        if (!destroyed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(com.rsps1008.sleeptrace.R.drawable.ic_motion_notification)
        .setContentTitle("眠迹 · 自動睡眠記錄")
        .setContentText(if (accumulator == null) "依設定時段與電量自動安排記錄" else "正在為你記錄睡眠，醒來後自動整理")
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 2001, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "暫停記錄", PendingIntent.getService(this, 2002,
            Intent(this, MotionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        active = null
        scope.cancel()
        runCatching { unregisterReceiver(powerReceiver) }
        handler.post {
            destroyed = true
            sensors.unregisterListener(this)
            accumulator?.let { pendingMinutes += it.drain(Long.MAX_VALUE, true) }
            accumulator = null; pendingChange = null
            handler.removeCallbacks(finishChange)
            persist()
            settings.status = when {
                !settings.enabled -> "動作偵測已關閉"
                windowEndedNormally -> "睡眠窗外，已停止背景服務並等待下一個排程"
                else -> "動作偵測已中斷，請開啟 App 重新啟動"
            }
            WorkScheduler.reconcileSoon(this)
            thread.quitSafely()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.rsps1008.sleeptrace.STOP_MOTION"
        private const val CHANNEL = "motion_tracking"
        private const val NOTIFICATION_ID = 2000
        private const val PERSIST_INTERVAL_MS = 5 * MINUTE_MS
        @Volatile var active: MotionService? = null
            private set
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, MotionService::class.java))
    }
}

class MotionBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SleepWindowScheduler.ACTION_BOUNDARY) return
        val isWindowEnd = intent.getBooleanExtra(SleepWindowScheduler.EXTRA_WINDOW_END, false)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val dependencies = context.sleepDependencies()
                val preferences = dependencies.preferences
                val schedule = if (preferences.configured()) preferences.schedule() else null
                val enabled = dependencies.motionSettings.enabled && schedule != null && SleepTracker.hasActivityRecognition(context)
                if (!enabled) {
                    SleepWindowScheduler.cancel(context)
                    runCatching { SleepTracker.unsubscribe(context) }
                    MotionService.active?.refreshConfiguration()
                    return@launch
                }
                val activeSchedule = requireNotNull(schedule)

                SleepWindowScheduler.clearDelivered(context)
                SleepWindowScheduler.schedule(context, activeSchedule)
                SleepTracker.syncSubscription(context, activeSchedule, enabled, System.currentTimeMillis())
                val inWindow = activeSchedule.windowAt(System.currentTimeMillis()) != null
                val service = MotionService.active
                if (service != null) {
                    service.refreshConfiguration()
                } else if (inWindow) {
                    runCatching { MotionService.start(context) }.onFailure {
                        dependencies.motionSettings.status = if (SleepWindowScheduler.hasExactAlarmAccess(context)) {
                            "系統未能於睡眠窗啟動背景記錄；開啟 App 可重新安排"
                        } else {
                            "未允許鬧鐘與提醒，Android 限制睡眠窗背景啟動；開啟 App 可恢復記錄"
                        }
                    }
                }
                if (isWindowEnd) WorkScheduler.reconcileSoon(context)
            } finally {
                pending.finish()
            }
        }
    }
}
