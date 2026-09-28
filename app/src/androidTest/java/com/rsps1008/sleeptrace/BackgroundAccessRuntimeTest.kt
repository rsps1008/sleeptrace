package com.rsps1008.sleeptrace

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.motion.MotionService
import com.rsps1008.sleeptrace.motion.MotionSettings
import com.rsps1008.sleeptrace.power.BackgroundAccess
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only: modifies this test app's allowlist/app-op, never a user's device. */
@RunWith(AndroidJUnit4::class)
class BackgroundAccessRuntimeTest {
    @Test fun declinedRequestDoesNotBlockRecordingOrRepeatAndSettingsAreRechecked() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue(message, condition())
        }
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun containsText(activity: Activity, text: String): Boolean {
            var found = false
            instrumentation.runOnMainSync {
                found = descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains(text) }
            }
            return found
        }
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 34) automation.grantRuntimePermission(context.packageName, "android.permission.health.WRITE_SLEEP")
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        shell("appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow")
        shell("dumpsys deviceidle whitelist -${context.packageName}")
        runBlocking { SleepPreferences(context).saveSchedule(SleepSchedule(0, 0)) }
        val access = BackgroundAccess(context)
        access.batteryGuideShown = false
        MotionSettings(context).enabled = true
        // Return a denial from the real Activity Result flow without touching the system dialog.
        val monitor = instrumentation.addMonitor(IntentFilter(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            addDataScheme("package")
        }, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        var activity: Activity? = null
        fun open(): Activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            activity = open()
            await("First setup did not request the battery exemption") { monitor.hits == 1 }
            await("Declining battery exemption blocked automatic recording") { MotionService.active != null }
            assertFalse(access.exempt)
            assertTrue(access.batteryGuideShown)
            await("Home did not retain the optional battery settings entry") { containsText(activity!!, context.getString(R.string.allow_overnight_recording)) }
            instrumentation.runOnMainSync { activity!!.finish() }
            instrumentation.waitForIdleSync()
            activity = open()
            await("Home did not render after reopening") { containsText(activity!!, context.getString(R.string.allow_overnight_recording)) }
            instrumentation.waitForIdleSync()
            assertEquals("A declined request must not auto-open again", 1, monitor.hits)

            // Simulate changing the setting while the Activity is away, then resume the same app.
            shell("input keyevent KEYCODE_HOME")
            shell("dumpsys deviceidle whitelist +${context.packageName}")
            assertTrue(access.exempt)
            shell("am start -n ${context.packageName}/.MainActivity")
            await("Returning from settings did not hide the completed battery guide") {
                !containsText(activity!!, context.getString(R.string.allow_overnight_recording))
            }
            shell("input keyevent KEYCODE_HOME")
            shell("dumpsys deviceidle whitelist -${context.packageName}")
            shell("appops set ${context.packageName} RUN_ANY_IN_BACKGROUND ignore")
            shell("am start -n ${context.packageName}/.MainActivity")
            await("Explicit background restriction was not detected") { access.restricted }
            await("Restricted state did not update on return") {
                containsText(activity!!, context.getString(R.string.battery_restricted_description))
            }
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, access.batteryIntents().first().action)
            assertEquals(1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
            shell("appops set ${context.packageName} RUN_ANY_IN_BACKGROUND allow")
            shell("dumpsys deviceidle whitelist -${context.packageName}")
            MotionSettings(context).enabled = false
            context.stopService(Intent(context, MotionService::class.java))
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }
}
