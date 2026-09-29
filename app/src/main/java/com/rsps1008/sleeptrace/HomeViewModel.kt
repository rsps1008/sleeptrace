package com.rsps1008.sleeptrace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.health.HealthConnectSync
import com.rsps1008.sleeptrace.motion.MotionSettings
import com.rsps1008.sleeptrace.power.BackgroundAccess
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import kotlinx.coroutines.Dispatchers
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
    private val preferences = SleepPreferences(application)
    private val store = SleepStore(application)
    private val healthSync = HealthConnectSync(application)
    private val motionSettings = MotionSettings(application)
    private val backgroundAccess = BackgroundAccess(application)
    private val mutableState = MutableStateFlow<HomeSnapshot?>(null)
    val state: StateFlow<HomeSnapshot?> = mutableState.asStateFlow()

    fun refresh() = viewModelScope.launch {
        val configured = preferences.configured()
        val schedule = if (configured) preferences.schedule() else null
        val (sessions, latestClassification) = withContext(Dispatchers.IO) {
            store.sessions() to store.samples().maxByOrNull { it.timeMillis }
        }
        mutableState.value = HomeSnapshot(
            configured = configured,
            schedule = schedule,
            sessions = sessions,
            latestClassification = latestClassification,
            healthGranted = healthSync.hasWritePermission(),
            recordingEnabled = motionSettings.enabled,
            backgroundRestricted = backgroundAccess.restricted,
            batteryExempt = backgroundAccess.exempt
        )
    }
}
