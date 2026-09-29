package com.rsps1008.sleeptrace

import android.app.Application
import android.content.Context
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.health.HealthConnectSync
import com.rsps1008.sleeptrace.motion.MotionSettings
import com.rsps1008.sleeptrace.motion.MotionStore
import com.rsps1008.sleeptrace.power.BackgroundAccess

/** Application-scoped dependency graph shared by UI, receivers, services, and workers. */
class SleepTraceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setThemeOverlay(com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_DayNight)
                .build()
        )
    }

    val dependencies by lazy { SleepDependencies(this) }
}

class SleepDependencies(application: Application) {
    val preferences = SleepPreferences(application)
    val store = SleepStore(application)
    val motionStore = MotionStore(application)
    val motionSettings = MotionSettings(application)
    val backgroundAccess = BackgroundAccess(application)
    val healthSync = HealthConnectSync(application)
}

fun Context.sleepDependencies(): SleepDependencies =
    (applicationContext as SleepTraceApplication).dependencies
