package com.rsps1008.sleeptrace

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
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
    val healthGranted: Boolean,
    val recordingEnabled: Boolean,
    val backgroundRestricted: Boolean,
    val batteryExempt: Boolean
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
                        HomeSnapshot(
                            configured = configured,
                            schedule = schedule,
                            sessions = store.sessions(limit = HOME_SESSION_LIMIT, includeAwakeIntervals = false),
                            latestClassification = store.latestSample(),
                            healthGranted = healthSync.hasWritePermission(),
                            recordingEnabled = motionSettings.enabled,
                            backgroundRestricted = backgroundAccess.restricted,
                            batteryExempt = backgroundAccess.exempt
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
