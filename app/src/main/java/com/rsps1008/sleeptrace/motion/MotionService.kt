package com.rsps1008.sleeptrace.motion

import android.app.AlarmManager
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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.rsps1008.sleeptrace.MainActivity
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepTracker
import com.rsps1008.sleeptrace.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** User-started foreground service. No continuous CPU wake lock and no raw sensor persistence. */
class MotionService : Service(), SensorEventListener2 {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sensors: SensorManager
    private lateinit var settings: MotionSettings
    private lateinit var store: MotionStore
    private var sensor: Sensor? = null
    private var plan: SamplingPlan? = null
    private var placement = Placement.BED
    private var accumulator: MotionAccumulator? = null
    private var schedule: SleepSchedule? = null
    private var clockOffset = 0L
    private var lastPersist = 0L
    private val pendingMinutes = mutableListOf<MotionMinute>()
    private var pendingChange: (() -> Unit)? = null
    private var stopped = false
    private var destroyed = false
    private var notice = "準備動作偵測"
    private val finishChange = Runnable { finishTransition() }
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshConfiguration() }
    }

    override fun onCreate() {
        super.onCreate()
        settings = MotionSettings(this)
        store = MotionStore(this)
        sensors = getSystemService(SensorManager::class.java)
        // Wake-up FIFO can retain events while the CPU sleeps. Prefer it over non-wake-up sensors.
        sensor = sensors.getSensorList(Sensor.TYPE_ACCELEROMETER).sortedWith(
            compareByDescending<Sensor> { it.isWakeUpSensor && it.fifoMaxEventCount > 0 }
                .thenByDescending { it.fifoMaxEventCount > 0 }.thenBy { it.power }
        ).firstOrNull()
        thread = HandlerThread("sleeptrace-motion").apply { start() }
        handler = Handler(thread.looper)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "床上動作偵測", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settings.enabled = false
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
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        refreshConfiguration()
        return START_NOT_STICKY
    }

    fun refreshConfiguration() {
        scope.launch {
            val prefs = SleepPreferences(this@MotionService)
            val newSchedule = if (prefs.configured()) prefs.schedule() else null
            handler.post { if (!stopped && !destroyed) configure(newSchedule) }
        }
    }

    private fun configure(newSchedule: SleepSchedule?) {
        schedule = newSchedule
        if (!settings.enabled || !SleepTracker.hasActivityRecognition(this)) {
            stopped = true
            transition { stopSelf() }; return
        }
        scheduleBoundary(newSchedule)
        val now = System.currentTimeMillis()
        val window = newSchedule?.windowAt(now)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val lowBattery = !charging && level >= 0 && scale > 0 && level.toDouble() / scale <= 0.15
        val pauseReason = when {
            sensor == null -> "這支手機沒有可用的加速度計"
            window == null || now < window.start || now >= window.end -> "等待設定的偵測時段"
            lowBattery -> "電量 ≤ 15%，暫停動作偵測"
            else -> null
        }
        if (pauseReason != null) {
            val wasRecording = accumulator != null
            transition { publish(pauseReason); if (wasRecording) WorkScheduler.reconcileSoon(this) }; return
        }
        val selected = sensor!!
        val next = SamplingPlan.choose(charging, selected.fifoMaxEventCount, selected.minDelay)
        val offset = now - SystemClock.elapsedRealtime()
        if (plan == next && placement == settings.placement && kotlin.math.abs(clockOffset - offset) < 2_000 && pendingChange == null) return
        transition {
            clockOffset = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            placement = settings.placement
            accumulator = MotionAccumulator(next, placement)
            val registered = runCatching { sensors.registerListener(this, selected, next.periodUs, next.latencyUs, handler) }.getOrDefault(false)
            if (!registered) {
                accumulator = null
                publish("加速度計註冊失敗，請重新啟動動作偵測")
            } else {
                plan = next
                val batching = if (next.latencyUs > 0) "批次上限 ${next.latencyUs / 1_000_000} 秒" else "無硬體 FIFO，降為 1 Hz"
                val sleepHint = if (!selected.isWakeUpSensor) "；休眠時可能缺資料" else ""
                publish("${if (charging) "供電中" else "省電"} · ${1_000_000 / next.periodUs} Hz · $batching$sleepHint")
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
        engine.add(time, event.values[0].toDouble(), event.values[1].toDouble(), event.values[2].toDouble())
        pendingMinutes += engine.drain(time)
        if (time - lastPersist >= 5 * MINUTE_MS) { persist(); lastPersist = time }
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
    }

    private fun scheduleBoundary(value: SleepSchedule?) {
        val alarm = getSystemService(AlarmManager::class.java)
        alarm.cancel(boundaryIntent())
        if (value == null) return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val next = (0L..2L).flatMap { day ->
            listOf(value.startMinute, value.endMinute).map { minute ->
                today.plusDays(day).atStartOfDay().plusMinutes(minute.toLong()).atZone(zone).toInstant().toEpochMilli()
            }
        }.filter { it > now }.min()
        // Inexact idle-aware boundary only; no periodic wake-up and no exact-alarm permission.
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, boundaryIntent())
    }

    private fun boundaryIntent() = PendingIntent.getBroadcast(this, 2003,
        Intent(this, MotionBoundaryReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun publish(text: String) {
        if (notice == text) return
        notice = text; settings.status = text
        if (!destroyed) getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(com.rsps1008.sleeptrace.R.drawable.ic_motion_notification)
        .setContentTitle("眠迹 · 床上動作偵測")
        .setContentText(notice).setStyle(NotificationCompat.BigTextStyle().bigText(notice))
        .setOngoing(true).setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 2001, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, "停止", PendingIntent.getService(this, 2002,
            Intent(this, MotionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onDestroy() {
        active = null
        scope.cancel()
        runCatching { unregisterReceiver(powerReceiver) }
        getSystemService(AlarmManager::class.java).cancel(boundaryIntent())
        handler.post {
            destroyed = true
            sensors.unregisterListener(this)
            accumulator?.let { pendingMinutes += it.drain(Long.MAX_VALUE, true) }
            accumulator = null; pendingChange = null
            handler.removeCallbacks(finishChange)
            persist(); store.close()
            settings.status = if (settings.enabled) "動作偵測已中斷，請開啟 App 重新啟動" else "動作偵測已關閉"
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
        @Volatile var active: MotionService? = null
            private set
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, MotionService::class.java))
    }
}

class MotionBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Never restart a killed service from the background; user must restart from the app.
        MotionService.active?.refreshConfiguration()
    }
}
