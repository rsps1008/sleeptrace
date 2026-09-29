package com.rsps1008.sleeptrace

import android.app.Application
import android.content.Context
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.health.HealthConnectSync
import com.rsps1008.sleeptrace.motion.MotionSettings
import com.rsps1008.sleeptrace.power.BackgroundAccess

/** Application-scoped dependency graph shared by UI, receivers, services, and workers. */
class SleepTraceApplication : Application() {
    val dependencies by lazy { SleepDependencies(this) }
}

class SleepDependencies(application: Application) {
    val preferences = SleepPreferences(application)
    val store = SleepStore(application)
    val motionSettings = MotionSettings(application)
    val backgroundAccess = BackgroundAccess(application)
    val healthSync = HealthConnectSync(application)
}

fun Context.sleepDependencies(): SleepDependencies =
    (applicationContext as SleepTraceApplication).dependencies
