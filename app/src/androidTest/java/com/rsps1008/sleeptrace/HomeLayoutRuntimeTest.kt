package com.rsps1008.sleeptrace

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.data.SleepPreferences
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.motion.CaptureDiagnostics
import com.rsps1008.sleeptrace.motion.CaptureUpdates
import com.rsps1008.sleeptrace.motion.MotionSettings
import com.rsps1008.sleeptrace.motion.MotionStore
import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSession
import com.rsps1008.sleeptrace.sleep.SyncState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic UI fixtures, only on a disposable emulator. Never writes records to Health Connect. */
@RunWith(AndroidJUnit4::class)
class HomeLayoutRuntimeTest {
    @Test fun savedHomeRemainsReadableAndKeepsExpandedDetailsOnRefresh() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk")) { "Use a disposable emulator" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val automation = instrumentation.uiAutomation
        fun shell(command: String) = automation.executeShellCommand(command).use {
            java.io.FileInputStream(it.fileDescriptor).bufferedReader().use { reader -> reader.readText() }
        }
        automation.grantRuntimePermission(context.packageName, Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) automation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 34) automation.grantRuntimePermission(context.packageName, "android.permission.health.WRITE_SLEEP")
        shell("appops set ${context.packageName} GET_USAGE_STATS allow")
        shell("dumpsys deviceidle whitelist +${context.packageName}")
        MotionSettings(context).enabled = false
        context.getSharedPreferences("sleeptrace_motion", 0).edit()
            .putBoolean("battery_guide_shown", true).putBoolean("xiaomi_guide_shown", true)
            .putBoolean("window_alarm_guide_shown", true).commit()
        context.getSharedPreferences("sleeptrace_maintenance", 0).edit().putBoolean("usage_access_last_seen", true).commit()
        runBlocking { SleepPreferences(context).saveSchedule(SleepSchedule(23 * 60, 7 * 60, 30, 9 * 60)) }
        val now = System.currentTimeMillis()
        // Old, already-synced manual fixtures stay outside reconciliation; these are UI examples only.
        val sessions = (0..5).map { index ->
            val end = now - (20 + index) * 86_400_000L
            SleepSession("home-ui-$index", end - 470 * 60_000, end, 78, 15 * 60_000,
                if (index == 1) SyncState.FAILED_PERMANENT else SyncState.SYNCED,
                "UI 排版測試範例，不是真實睡眠紀錄。", manuallyEdited = true, usageSnapshotApplied = true)
        }
        SleepStore(context).apply {
            saveSessions(sessions)
            appendSamples(listOf(ClassificationSample(now, 87, 0, 0)))
        }
        val capture = CaptureDiagnostics(now, now - 600_000, now - 600_000, "UI_FIXTURE",
            100_000, 100_000, 35_200_000, 5_000, 44_000, 0, 1_000, true,
            now - 600_000, 10_000, 0, 44.35, 45)
        MotionStore(context).use {
            it.writableDatabase.delete("capture_runs", null, null)
            it.saveCapture(capture)
        }
        AutomaticWorkSignals.markReconciled(context, AutomaticWorkSignals.generation(context))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
            (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun awaitReady() {
            val deadline = android.os.SystemClock.elapsedRealtime() + 10_000
            var ready = false
            while (!ready && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    ready = descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "87 / 100" }
                }
                if (!ready) Thread.sleep(100)
            }
            assertTrue("Home data was not rendered", ready)
            instrumentation.waitForIdleSync()
        }
        val variant = InstrumentationRegistry.getArguments().getString("variant") ?: "default"
        fun screenshot(name: String) {
            instrumentation.waitForIdleSync()
            // Allow the posted scroll position to reach the next frame.
            Thread.sleep(300)
            automation.takeScreenshot()?.let { bitmap ->
                java.io.File(context.getExternalFilesDir(null), "home-$variant-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
        }
        fun assertTextFits() = instrumentation.runOnMainSync {
            val root = activity.window.decorView
            descendants(root).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }.forEach { view ->
                val layout = requireNotNull(view.layout)
                assertTrue("Text clipped vertically: ${view.text}", layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                repeat(layout.lineCount) { line ->
                    assertEquals("Text ellipsized: ${view.text}", 0, layout.getEllipsisCount(line))
                    // getLineWidth includes invisible trailing spaces at a wrapping boundary.
                    assertTrue("Text overflows horizontally: ${view.text} (${layout.getLineMax(line)} > ${layout.width})",
                        layout.getLineMax(line) <= layout.width + 2)
                }
                val position = IntArray(2)
                view.getLocationOnScreen(position)
                assertTrue("View outside horizontal viewport: ${view.text}", position[0] >= 0 && position[0] + view.width <= root.width)
            }
        }
        try {
            awaitReady()
            WorkManager.getInstance(context).cancelAllWork().result.get()
            screenshot("sleep")
            assertTextFits()
            onView(withText("查看全部紀錄")).perform(revealHomeView(), click())
            onView(withText("全部睡眠紀錄")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("關閉")).inRoot(isDialog()).perform(click())
            onView(withText("自動記錄已暫停")).perform(revealHomeView())
            screenshot("schedule")
            onView(withText("原始事件實測")).perform(revealHomeView())
            screenshot("capture")
            onView(withText("展開硬體與批次說明")).perform(revealHomeView(), click())
            onView(withText("硬體 FIFO 容量")).perform(revealHomeView())
            screenshot("fifo")
            assertTextFits()
            var previousScroll = 0
            var captureView: HomeCaptureView? = null
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                captureView = views.filterIsInstance<HomeCaptureView>().single()
                assertTrue(captureView!!.detailsExpanded)
                previousScroll = views.filterIsInstance<ScrollView>().single().scrollY
            }
            MotionStore(context).use { it.saveCapture(capture.copy(rawEvents = 12_000, meanIntervalMillis = 50.0)) }
            CaptureUpdates.notifyPersisted()
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            var refreshed = false
            while (!refreshed && android.os.SystemClock.elapsedRealtime() < deadline) {
                instrumentation.runOnMainSync {
                    refreshed = descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.text == "約 20.00 Hz" }
                }
                if (!refreshed) Thread.sleep(100)
            }
            assertTrue("Persisted capture did not refresh", refreshed)
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                assertSame("Refresh replaced the capture view", captureView, views.filterIsInstance<HomeCaptureView>().single())
                assertTrue(captureView!!.detailsExpanded)
                assertEquals("Refresh lost scroll position", previousScroll, views.filterIsInstance<ScrollView>().single().scrollY)
            }
            onView(withText("收合硬體與批次說明")).perform(revealHomeView(), click())
            assertTextFits()
        } finally {
            WorkManager.getInstance(context).cancelAllWork().result.get()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
