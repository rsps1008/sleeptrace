package com.rsps1008.sleeptrace

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.motion.MotionService
import com.rsps1008.sleeptrace.motion.MotionSettings
import com.rsps1008.sleeptrace.motion.MotionStore
import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run only on a disposable emulator: exercises foreground sensor lifecycle and battery broadcasts. */
@RunWith(AndroidJUnit4::class)
class MotionRuntimeTest {
    @Test fun foregroundTrackingPersistsAndPausesOnLowBattery() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use a disposable emulator for this test" }
        fun shell(command: String) = automation.executeShellCommand(command).use { descriptor ->
            java.io.FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = android.os.SystemClock.elapsedRealtime() + 12_000
            while (!condition() && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertTrue(message, condition())
        }
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { SleepPreferences(context).saveSchedule(SleepSchedule(0, 0)) }
        val settings = MotionSettings(context)
        settings.enabled = true
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set level 70")
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { MotionService.start(context) }
            await("Foreground service did not register sensor: ${settings.status}") { MotionService.active != null && settings.status.contains("Hz") }
            Thread.sleep(5_000)
            shell("dumpsys battery set level 10")
            await("Low battery did not pause sampling: ${settings.status}") { settings.status.contains("15%") }
            val rows = MotionStore(context).use { it.read(System.currentTimeMillis() - 10 * MINUTE_MS, System.currentTimeMillis() + MINUTE_MS) }
            assertTrue("No minute summaries were persisted on sensor flush", rows.isNotEmpty())
            assertTrue(rows.any { it.sampleCount > 0 })
            shell("dumpsys battery set ac 1")
            await("Charging did not resume sampling: ${settings.status}") { settings.status.contains("供電中") && settings.status.contains("Hz") }
            context.startService(Intent(context, MotionService::class.java).setAction(MotionService.ACTION_STOP))
            await("Foreground service did not stop") { MotionService.active == null && !settings.enabled }
        } finally {
            settings.enabled = false
            context.stopService(Intent(context, MotionService::class.java))
            shell("dumpsys battery reset")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
