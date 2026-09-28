package com.rsps1008.sleeptrace

import android.Manifest
import android.content.Intent
import android.os.Build
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ScrollView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        if (Build.VERSION.SDK_INT >= 34) automation.grantRuntimePermission(context.packageName, "android.permission.health.WRITE_SLEEP")
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        runBlocking { SleepPreferences(context).saveSchedule(SleepSchedule(0, 0)) }
        // Upgrade from old manual opt-out/bedside settings must still enable the new automatic mode.
        context.getSharedPreferences("sleeptrace_motion", 0).edit().remove("recording_enabled")
            .putBoolean("enabled", false).putString("placement", "BEDSIDE").commit()
        val settings = MotionSettings(context)
        assertTrue(settings.enabled)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set level 70")
            instrumentation.waitForIdleSync()
            // Merely opening the configured app must start recording; no motion button/API call.
            await("Foreground service did not register sensor: ${settings.status}") { MotionService.active != null && settings.status.contains("Hz") }
            fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
            await("Home did not render") {
                var ready = false
                instrumentation.runOnMainSync { ready = descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "最近睡眠紀錄" } }
                ready
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                val title = views.filterIsInstance<TextView>().single { it.text.toString() == context.getString(R.string.app_name) }
                val safe = ViewCompat.getRootWindowInsets(title)!!.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val location = IntArray(2)
                title.getLocationOnScreen(location)
                assertTrue("Title overlaps status bar/cutout", location[1] >= safe.top)
                val visible = Rect()
                assertTrue(title.getGlobalVisibleRect(visible))
                assertEquals("Title was clipped or auto-scrolled away", title.height, visible.height())
                val scroll = views.filterIsInstance<ScrollView>().single()
                assertTrue(scroll.paddingBottom >= safe.bottom)
                val text = views.filterIsInstance<TextView>().joinToString { it.text }
                assertFalse(text.contains("變更放置位置"))
                assertFalse(text.contains("動作感測"))
                assertFalse(text.contains("Hz"))
            }
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.getExternalFilesDir(null), "automatic-home.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            Thread.sleep(5_000)
            shell("dumpsys battery set level 10")
            await("Low battery did not pause sampling: ${settings.status}") { settings.status.contains("15%") }
            val rows = MotionStore(context).use { it.read(System.currentTimeMillis() - 10 * MINUTE_MS, System.currentTimeMillis() + MINUTE_MS) }
            assertTrue("No minute summaries were persisted on sensor flush", rows.isNotEmpty())
            assertTrue(rows.any { it.sampleCount > 0 })
            assertTrue(rows.all { it.placement == com.rsps1008.sleeptrace.motion.Placement.AUTO })
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
