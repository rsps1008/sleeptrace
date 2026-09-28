package com.rsps1008.sleeptrace.power

import android.app.ActivityManager
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri

/** System state is read afresh; opening Settings never counts as granting access. */
class BackgroundAccess(private val context: Context) {
    private val prefs = context.getSharedPreferences("sleeptrace_motion", Context.MODE_PRIVATE)
    val restricted: Boolean
        get() = context.getSystemService(ActivityManager::class.java).isBackgroundRestricted
    val exempt: Boolean
        get() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    val batteryReady: Boolean get() = !restricted && exempt
    val isXiaomi: Boolean get() = listOf(Build.MANUFACTURER, Build.BRAND).any {
        it.equals("xiaomi", true) || it.equals("redmi", true) || it.equals("poco", true)
    }

    var batteryGuideShown: Boolean
        get() = prefs.getBoolean("battery_guide_shown", false)
        set(value) = prefs.edit { putBoolean("battery_guide_shown", value) }
    // This is only a navigation reminder, never an autostart permission status.
    var xiaomiGuideShown: Boolean
        get() = prefs.getBoolean("xiaomi_guide_shown", false)
        set(value) = prefs.edit { putBoolean("xiaomi_guide_shown", value) }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())

    // Core functionality records local sensor data overnight; FCM/deferrable jobs cannot replace it.
    // Request once, keep recording on denial, and retain our own low-power sampling policy.
    @SuppressLint("BatteryLife")
    fun batteryIntents(): List<Intent> = buildList {
        // Explicit background restriction must be removed in the app's system settings.
        if (!restricted && !exempt) add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri()))
        add(appDetails())
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(Intent(Settings.ACTION_SETTINGS))
    }

    fun xiaomiIntents(): List<Intent> = listOf(
        // MIUI/HyperOS private entry point: may be absent or inaccessible on some versions.
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        appDetails(),
        Intent(Settings.ACTION_SETTINGS)
    )
}
