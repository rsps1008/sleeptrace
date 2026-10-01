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
import android.util.Log
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Automatic foreground recording after setup; no continuous wake lock or raw sensor persistence. */
class MotionService : Service(), SensorEventListener2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val configurationRequests = Channel<Unit>(Channel.CONFLATED)
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sensors: SensorManager
    private lateinit var settings: MotionSettings
    private lateinit var store: MotionStore
    private var sensor: Sensor? = null
    private var plan: SamplingPlan? = null
    private var accumulator: MotionAccumulator? = null
    private var capture: CaptureDiagnostics? = null
    private var configuredExperiment = CaptureExperiment.OFF
    private var schedule: SleepSchedule? = null
    private val eventWindows = MotionWindowLookup()
    private var boundaryRefreshPending = false
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
                // Preserve the old event mapping through flush; configure will start a new recording epoch.
                Intent.ACTION_TIME_CHANGED -> Unit
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
        scope.launch(Dispatchers.IO) {
            consumeMotionConfigurationRefreshes(configurationRequests, refresh = {
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
            }, onFailure = { Log.w("MotionService", "睡眠記錄設定更新失敗，等待下次更新", it) })
        }
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
        configurationRequests.trySend(Unit)
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
        eventWindows.update(newSchedule)
        boundaryRefreshPending = false
        if (!settings.enabled || !SleepTracker.hasActivityRecognition(this)) {
            stopped = true
            transition { stopSelf() }; return
        }
        SleepWindowScheduler.schedule(this, newSchedule)
        val experiment = settings.experiment
        val modeChanged = configuredExperiment != experiment
        if (modeChanged) {
            configuredExperiment = experiment
            triggeredWindowStart = null; fallbackWindowStart = null
        }
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
        if (window != null && settings.experiment != CaptureExperiment.OFF) {
            triggeredWindowStart = window.start; fallbackWindowStart = null
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
        val next = capturePlan(
            experiment = experiment,
            fifoMaxEventCount = selected.fifoMaxEventCount,
            minDelayUs = selected.minDelay,
            maxDelayUs = selected.maxDelay,
            fifoReservedEventCount = selected.fifoReservedEventCount
        )
        val offset = now - SystemClock.elapsedRealtime()
        if (!modeChanged && plan == next && capture?.windowStart == activeWindow.start &&
            kotlin.math.abs(clockOffset - offset) < 2_000 && pendingChange == null) return
        transition {
            clockOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            val captureId = SystemClock.elapsedRealtimeNanos()
            accumulator = MotionAccumulator(next, Placement.AUTO, captureId)
            val registered = runCatching { sensors.registerListener(this, selected, next.periodUs, next.latencyUs, handler) }.getOrDefault(false)
            if (!registered) {
                accumulator = null
                publish("加速度計註冊失敗，請重新啟動動作偵測")
            } else {
                plan = next
                val registeredAt = System.currentTimeMillis()
                lastPersistElapsedRealtime = SystemClock.elapsedRealtime()
                capture = CaptureDiagnostics(
                    id = captureId,
                    windowStart = activeWindow.start,
                    registeredAt = registeredAt,
                    trigger = when {
                        settings.experiment != CaptureExperiment.OFF -> settings.experiment.name
                        fallbackWindowStart == activeWindow.start -> "SCREEN_OFF_2H_BACKUP"
                        else -> "GOOGLE_CLASSIFICATION"
                    },
                    targetPeriodUs = next.targetPeriodUs,
                    periodUs = next.periodUs,
                    latencyUs = next.latencyUs,
                    sensorMinDelayUs = selected.minDelay,
                    sensorMaxDelayUs = selected.maxDelay,
                    fifoReservedEventCount = selected.fifoReservedEventCount,
                    fifoMaxEventCount = selected.fifoMaxEventCount,
                    wakeUp = selected.isWakeUpSensor
                )
                capture?.let { diagnostics ->
                    runCatching { store.saveCapture(diagnostics) }
                        .onSuccess { CaptureUpdates.notifyPersisted() }
                }
                val batching = if (next.latencyUs > 0) "批次上限 ${next.latencyUs / 1_000_000} 秒" else "無硬體 FIFO"
                val sleepHint = if (!selected.isWakeUpSensor) "；休眠時可能缺資料" else ""
                val source = if (fallbackWindowStart == activeWindow.start) {
                    "Google 分類延遲時的動作備援"
                } else "Google 已判斷入睡"
                val requestedHz = String.format(java.util.Locale.US, "%.2f", 1_000_000.0 / next.periodUs)
                publish("$source · 要求 $requestedHz Hz · $batching$sleepHint")
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
        persist(force = true)
        accumulator = null; plan = null; capture = null
        if (!destroyed) action()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val engine = accumulator ?: return
        val time = clockOffset + event.timestamp / 1_000_000
        // Resolve calendar/DST boundaries only when entering a different window or after
        // configuration changes, not on each raw callback (which may exceed requested Hz).
        val eventWindow = eventWindows.windowAt(time)
        if (eventWindow == null || eventWindow.start != capture?.windowStart) {
            // An overdue/missing boundary broadcast must not leave a live listener running
            // outside its window. One existing sensor callback requests a fresh schedule;
            // no polling alarm or extra wake lock is added. Ignore late pre-window batches.
            if (time >= (capture?.windowStart ?: Long.MAX_VALUE) &&
                !boundaryRefreshPending && pendingChange == null && !stopped) {
                boundaryRefreshPending = true
                refreshConfiguration()
            }
            return
        }
        if (capture?.firstEvent == null) capture = capture?.copy(firstEvent = time)
        if (!engine.add(time, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())) return
        pendingMinutes += engine.drain(time)
        // Sensor timestamps can jump across a whole FIFO batch. Throttle by elapsed wall time,
        // which continues during device suspend, not by sample event time.
        if (SystemClock.elapsedRealtime() - lastPersistElapsedRealtime >= PERSIST_INTERVAL_MS) persist()
    }
    override fun onFlushCompleted(sensor: Sensor?) { finishTransition() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun persist(force: Boolean = false) {
        if (!force && pendingMinutes.isEmpty()) return
        accumulator?.let { engine -> capture = capture?.copy(rawEvents = engine.rawEventCount,
            rejectedEvents = engine.rejectedEvents,
            meanIntervalMillis = if (engine.rawEventCount > 1) engine.intervalSumMillis.toDouble() / (engine.rawEventCount - 1) else null,
            maxIntervalMillis = if (engine.rawEventCount > 1) engine.intervalMaxMillis else null) }
        capture?.let { diagnostics ->
            runCatching { store.saveCapture(diagnostics) }
                .onSuccess { CaptureUpdates.notifyPersisted() }
        }
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
        configurationRequests.close()
        scope.cancel()
        runCatching { unregisterReceiver(powerReceiver) }
        handler.post {
            destroyed = true
            sensors.unregisterListener(this)
            accumulator?.let { pendingMinutes += it.drain(Long.MAX_VALUE, true) }
            pendingChange = null
            handler.removeCallbacks(finishChange)
            persist(force = true)
            accumulator = null; capture = null
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
