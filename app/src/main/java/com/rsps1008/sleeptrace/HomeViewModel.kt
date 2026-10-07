package com.rsps1008.sleeptrace

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.motion.SleepWindowScheduler
import com.rsps1008.sleeptrace.motion.CaptureDiagnostics
import com.rsps1008.sleeptrace.motion.CaptureUpdates
import com.rsps1008.sleeptrace.motion.RecordingMode
import com.rsps1008.sleeptrace.sleep.SleepSubscriptionHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeSnapshot(
    val configured: Boolean,
    val schedule: SleepSchedule?,
    val sessions: List<SleepSession>,
    val latestClassification: ClassificationSample?,
    val latestCapture: CaptureDiagnostics?,
    val healthGranted: Boolean,
    val recordingEnabled: Boolean,
    val recordingMode: RecordingMode,
    val backgroundRestricted: Boolean,
    val batteryExempt: Boolean,
    val unusedAppPermissionsProtected: Boolean?,
    val exactAlarmAllowed: Boolean,
    val subscriptionHealth: SleepSubscriptionHealth,
    val saverPendingStatus: String?
)

/** Owns homepage data loading so activity rendering stays separate from repository access. */
class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val dependencies = application.sleepDependencies()
    private val preferences = dependencies.preferences
    private val store = dependencies.store
    private val healthSync = dependencies.healthSync
    private val motionSettings = dependencies.motionSettings
    private val backgroundAccess = dependencies.backgroundAccess
    private val mutableState = MutableStateFlow<HomeSnapshot?>(null)
    val state: StateFlow<HomeSnapshot?> = mutableState.asStateFlow()
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            for (ignored in refreshRequests) {
                try {
                    val snapshot = withContext(Dispatchers.IO) {
                        val configured = preferences.configured()
                        val schedule = if (configured) preferences.schedule() else null
                        val latest = store.latestSample()
                        val sessions = store.sessions(limit = HOME_SESSION_LIMIT, includeAwakeIntervals = false)
                        val now = System.currentTimeMillis()
                        val saverPending = schedule?.takeIf { motionSettings.recordingMode == RecordingMode.BATTERY_SAVER }
                            ?.saverClassificationWindowAt(now)
                            ?.takeIf { schedule.isAfterWindowBeforeNextStart(now) }
                            ?.let { window ->
                                if ((latest?.timeMillis ?: Long.MIN_VALUE) >= window.startMillis) "等待 Google 起床回報"
                                else "尚未收到 Google 睡眠證據"
                            } ?: sessions.firstOrNull { it.state in setOf(
                                com.rsps1008.sleeptrace.sleep.SyncState.PENDING,
                                com.rsps1008.sleeptrace.sleep.SyncState.SYNCING,
                                com.rsps1008.sleeptrace.sleep.SyncState.FAILED_RETRYABLE
                            ) }?.let { "睡眠已整理，等待同步" }
                        HomeSnapshot(
                            configured = configured,
                            schedule = schedule,
                            sessions = sessions,
                            latestClassification = latest,
                            latestCapture = dependencies.motionStore.latestCapture(),
                            healthGranted = healthSync.hasWritePermission(),
                            recordingEnabled = motionSettings.enabled,
                            recordingMode = motionSettings.recordingMode,
                            backgroundRestricted = backgroundAccess.restricted,
                            batteryExempt = backgroundAccess.exempt,
                            unusedAppPermissionsProtected = backgroundAccess.unusedAppPermissionsProtected,
                            exactAlarmAllowed = SleepWindowScheduler.hasExactAlarmAccess(getApplication()),
                            subscriptionHealth = SleepSubscriptionHealth.read(getApplication()),
                            saverPendingStatus = saverPending
                        )
                    }
                    mutableState.value = snapshot
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // Keep the conflated consumer alive so a later refresh can recover.
                    Log.w(TAG, "首頁資料刷新失敗", error)
                }
            }
        }
        viewModelScope.launch {
            CaptureUpdates.updates.collect { refreshRequests.trySend(Unit) }
        }
    }

    fun refresh() { refreshRequests.trySend(Unit) }

    override fun onCleared() {
        refreshRequests.close()
    }

    private companion object {
        const val HOME_SESSION_LIMIT = 5
        const val TAG = "HomeViewModel"
    }
}
