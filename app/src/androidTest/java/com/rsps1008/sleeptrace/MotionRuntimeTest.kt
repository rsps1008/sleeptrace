package com.rsps1008.sleeptrace

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ScrollView
import android.widget.DatePicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.data.SleepEventStore
import com.rsps1008.sleeptrace.motion.MotionService
import com.rsps1008.sleeptrace.motion.MotionSettings
import com.rsps1008.sleeptrace.motion.MotionStore
import com.rsps1008.sleeptrace.motion.MINUTE_MS
import com.rsps1008.sleeptrace.motion.MotionAccumulator
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.DIAGNOSTIC_CSV_HEADER
import com.rsps1008.sleeptrace.sleep.diagnosticCsvHeaders
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SleepSessionTimelineView
import com.rsps1008.sleeptrace.sleep.SyncState
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
            .putBoolean("enabled", false).putString("placement", "BEDSIDE")
            .putString("capture_experiment", "OFF")
            .putBoolean("battery_guide_shown", true).putBoolean("xiaomi_guide_shown", true)
            .putBoolean("window_alarm_guide_shown", true).commit()
        context.getSharedPreferences("sleeptrace_records", 0).edit().remove("samples").commit()
        // This emulator-only test must start without an old high-confidence trigger.
        // Raw samples now live in SQLite, not the legacy JSON preference above.
        SleepEventStore(context).use { it.writableDatabase.delete("samples", null, null) }
        MotionStore(context).use {
            // Repeated runs must not merge a prior capture into this run's minute:
            // mixed recordingId=0 intentionally has no attributable capture metadata.
            it.writableDatabase.delete("minutes", null, null)
            it.writableDatabase.delete("capture_runs", null, null)
        }
        SleepStore(context).appendSamples(listOf(ClassificationSample(System.currentTimeMillis(), 37, 0, 0)))
        val settings = MotionSettings(context)
        assertTrue(settings.enabled)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            shell("dumpsys battery unplug")
            shell("dumpsys battery set level 70")
            instrumentation.waitForIdleSync()
            // Opening the configured app keeps the service ready without sampling before Google indicates sleep.
            await("Foreground service did not wait for Google classification: ${settings.status}") {
                MotionService.active != null && settings.status.contains("等待 Google")
            }
            MotionService.active!!.onSleepClassifications(listOf(ClassificationSample(System.currentTimeMillis(), 90, 0, 0)))
            await("Foreground service did not register sensor after sleep classification: ${settings.status}") {
                settings.status.contains("Google 已判斷入睡") && settings.status.contains("10.00 Hz")
            }
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
                assertTrue(text.contains("App 要求頻率"))
                assertTrue(text.contains("特徵正規化上限"))
                assertTrue(text.contains("10.00 Hz"))
                assertTrue(text.contains("37 / 100"))
                assertTrue(text.contains("Sleep API 睡眠信心，非準確率"))
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
            assertTrue(rows.all { it.featureVersion == MotionAccumulator.CURRENT_FEATURE_VERSION })
            val capture = MotionStore(context).use { requireNotNull(it.latestCapture()) }
            assertEquals(100_000, capture.targetPeriodUs)
            assertEquals(100_000, capture.periodUs)
            assertTrue(capture.rawEvents > 1)
            assertNotNull(capture.observedRawHertz)
            await("Persisted capture did not refresh while the homepage stayed open") {
                var refreshed = false
                instrumentation.runOnMainSync {
                    refreshed = descendants(activity.window.decorView).filterIsInstance<TextView>()
                        .any { it.text.startsWith("約 ") && it.text.contains("Hz") }
                }
                refreshed
            }

            // Exercise the real button/date-picker/result callback and CSV writer.
            // Only the document picker is replaced with an app-cache destination.
            val csv = java.io.File(context.cacheDir, "runtime-motion.csv").apply { delete() }
            val documentMonitor = instrumentation.addMonitor(
                IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addDataType("text/csv") },
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(csv))), true
            )
            try {
                onView(withText("匯出每分鐘動作資料")).perform(revealHomeView(), click())
                onView(isAssignableFrom(DatePicker::class.java)).perform(object : ViewAction {
                    override fun getConstraints() = isAssignableFrom(DatePicker::class.java)
                    override fun getDescription() = "select today's motion export"
                    override fun perform(uiController: UiController, view: View) {
                        val today = java.time.LocalDate.now()
                        (view as DatePicker).updateDate(today.year, today.monthValue - 1, today.dayOfMonth)
                        uiController.loopMainThreadUntilIdle()
                    }
                })
                onView(androidx.test.espresso.matcher.ViewMatchers.withId(android.R.id.button1)).perform(click())
                await("Motion CSV was not written by the real export callback") {
                    csv.exists() && csv.readLines().size > 1
                }
                val lines = csv.readLines()
                assertEquals(DIAGNOSTIC_CSV_HEADER, lines.first())
                // This raw-only fixture contains no user strings or quoted CSV fields.
                val exported = lines.drop(1).map { line ->
                    val fields = line.split(',')
                    assertEquals(diagnosticCsvHeaders.size, fields.size)
                    diagnosticCsvHeaders.zip(fields).toMap()
                }
                assertTrue(exported.isNotEmpty())
                assertTrue(exported.all { it["feature_version"] == "7" })
                assertTrue(exported.all { it["requested_period_us"] == "100000" })
                assertTrue(exported.all { it["feature_target_hz"] == "10.000000" })
                assertTrue(exported.all { it["observed_raw_event_hz"]?.toDoubleOrNull()?.let { hz -> hz > 0 } == true })
                assertTrue(exported.all { it["coupling_current_blocker"].orEmpty().isNotBlank() })
                assertTrue(exported.all { it["relative_quiet_method"].orEmpty().isEmpty() })
            } finally {
                instrumentation.removeMonitor(documentMonitor)
                csv.delete()
            }
            shell("dumpsys battery set ac 1")
            await("Charging did not resume sampling: ${settings.status}") {
                settings.status.contains("Google 已判斷入睡") && settings.status.contains("10.00 Hz")
            }
            assertNotEquals(capture.id, MotionStore(context).use { it.latestCapture()?.id })
            context.startService(Intent(context, MotionService::class.java).setAction(MotionService.ACTION_STOP))
            await("Foreground service did not stop") { MotionService.active == null && !settings.enabled }
            // A long diagnostic must not hide the timeline below a fixed-height dialog.
            instrumentation.runOnMainSync {
                val now = System.currentTimeMillis()
                SleepDialogHelper.showSession(activity,
                    SleepSession("layout-only", now - 360 * MINUTE_MS, now, 50, 0,
                        SyncState.PENDING, "工程測試；沒有儲存或同步這筆示範紀錄。"),
                    formatDuration = { "${it / MINUTE_MS} 分鐘" }, onEdit = {},
                    stagingExplanation = "床面動作支持不足；回退 Light 不代表生理淺眠。\n".repeat(20))
            }
            onView(org.hamcrest.Matchers.allOf(isAssignableFrom(ScrollView::class.java),
                hasDescendant(isAssignableFrom(SleepSessionTimelineView::class.java))))
                .inRoot(isDialog()).perform(object : ViewAction {
                override fun getConstraints() = isAssignableFrom(ScrollView::class.java)
                override fun getDescription() = "scroll long sleep details to the timeline"
                override fun perform(uiController: UiController, view: View) {
                    assertTrue("Long detail does not expose a scroll range", view.canScrollVertically(1))
                    (view as ScrollView).fullScroll(View.FOCUS_DOWN)
                    uiController.loopMainThreadForAtLeast(500)
                }
            })
            onView(isAssignableFrom(SleepSessionTimelineView::class.java)).inRoot(isDialog())
                .check(matches(isCompletelyDisplayed()))
            onView(withText("關閉")).inRoot(isDialog()).check(matches(isCompletelyDisplayed())).perform(click())
        } finally {
            settings.enabled = false
            context.stopService(Intent(context, MotionService::class.java))
            shell("dumpsys battery reset")
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
