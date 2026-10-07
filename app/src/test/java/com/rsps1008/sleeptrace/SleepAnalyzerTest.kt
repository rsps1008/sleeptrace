package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.ClassificationSample
import com.rsps1008.sleeptrace.sleep.SleepAnalyzer
import com.rsps1008.sleeptrace.sleep.SleepSchedule
import com.rsps1008.sleeptrace.sleep.SleepSegment
import com.rsps1008.sleeptrace.sleep.SleepWindow
import com.rsps1008.sleeptrace.sleep.SyncState
import com.rsps1008.sleeptrace.sleep.UsageInterval
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepAnalyzerTest {
    private val start = 1_700_000_000_000L
    private val segment = SleepSegment(start, start + 8 * 60 * 60 * 1000L, 100)
    private val schedule = SleepSchedule(0, 23 * 60 + 59)

    @Test fun `known awake evidence is removed from sleep duration`() {
        val session = SleepAnalyzer.analyze(listOf(segment), samples(), listOf(UsageInterval(start + 60_000, start + 16 * 60_000)), schedule).single()
        assertEquals(15 * 60_000L, session.awakeMillis)
        assertEquals(7 * 60 * 60 * 1000L + 45 * 60 * 1000L, session.durationMillis)
        assertTrue(session.reason.contains("中途清醒"))
    }

    @Test fun `missing classification still syncs the best available segment automatically`() {
        val session = SleepAnalyzer.analyze(listOf(segment), emptyList(), emptyList(), schedule).single()
        assertEquals(SyncState.PENDING, session.state)
    }

    @Test fun `missing segment falls back to classify events after the window completes`() {
        val window = SleepWindow(start, start + 8 * 60 * 60_000L)
        val classifications = (0..41).map { index ->
            ClassificationSample(start + 30 * 60_000L + index * 10 * 60_000L, 90, 0, 0)
        }

        val session = SleepAnalyzer.analyzeClassificationsByWindow(classifications, listOf(window)).single()

        assertEquals(start + 30 * 60_000L, session.startMillis)
        assertEquals(start + 7 * 60 * 60_000L + 40 * 60_000L, session.endMillis)
        assertEquals(SyncState.PENDING, session.state)
        assertTrue(session.reason.contains("分類事件"))
    }

    @Test fun `classify fallback keeps low confidence phone interval awake`() {
        val window = SleepWindow(start, start + 3 * 60 * 60_000L)
        val classifications = listOf(
            ClassificationSample(start, 90, 0, 0),
            ClassificationSample(start + 30 * 60_000L, 90, 0, 0),
            ClassificationSample(start + 60 * 60_000L, 10, 0, 0),
            ClassificationSample(start + 90 * 60_000L, 90, 0, 0),
            ClassificationSample(start + 120 * 60_000L, 90, 0, 0)
        )

        val session = SleepAnalyzer.analyzeClassificationsByWindow(classifications, listOf(window)).single()

        assertEquals(30 * 60_000L, session.awakeMillis)
        assertEquals(1, session.awakeIntervals.size)
    }

    @Test fun `schedule alone does not become a regular Sleep API candidate`() {
        val window = SleepWindow(start, start + 8 * 60 * 60_000L)

        assertTrue(SleepAnalyzer.analyzeClassificationsByWindow(emptyList(), listOf(window)).isEmpty())
        assertTrue(SleepAnalyzer.analyzeByWindow(
            emptyList(), emptyList(), emptyList(), schedule, listOf(window)
        ).isEmpty())
    }

    @Test fun `saver schedule fallback records a clearly marked rough completed night`() {
        val window = SleepWindow(start, start + 8 * 60 * 60_000L)

        val session = SleepAnalyzer.scheduledEstimateByWindow(listOf(window), emptyList()).single()

        assertEquals(window.startMillis, session.startMillis)
        assertEquals(window.endMillis, session.endMillis)
        assertEquals(0, session.confidence)
        assertEquals(SyncState.PENDING, session.state)
        assertTrue(session.reason.contains("粗略排程估計"))
    }

    @Test fun `saver schedule fallback refuses an all day observation window`() {
        val allDay = SleepWindow(start, start + 24 * 60 * 60_000L)

        assertTrue(SleepAnalyzer.scheduledEstimateByWindow(listOf(allDay), emptyList()).isEmpty())
    }

    @Test fun `saver schedule fallback uses a post window awake report as its approximate end`() {
        val window = SleepWindow(start, start + 8 * 60 * 60_000L)
        val woke = window.endMillis + 20 * 60_000L

        val session = SleepAnalyzer.scheduledEstimateByWindow(listOf(window),
            listOf(ClassificationSample(woke, 1, 0, 0))).single()

        assertEquals(woke, session.endMillis)
        assertTrue(session.reason.contains("起床後回報清醒"))
    }

    @Test fun `confident complete session is pending sync`() {
        val session = SleepAnalyzer.analyze(listOf(segment), samples(), emptyList(), schedule).single()
        assertEquals(SyncState.PENDING, session.state)
    }

    @Test fun `api segment is clamped to the overnight schedule`() {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026, 9, 27)
        val segment = SleepSegment(
            day.atTime(20, 30).atZone(zone).toInstant().toEpochMilli(),
            day.plusDays(1).atTime(8, 30).atZone(zone).toInstant().toEpochMilli(),
            100
        )

        val session = SleepAnalyzer.analyze(
            listOf(segment), emptyList(), emptyList(), SleepSchedule(23 * 60, 7 * 60)
        ).single()

        assertEquals(day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli(), session.startMillis)
        assertEquals(day.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), session.endMillis)
    }

    @Test fun `pre sleep low classifications keep tablet and phone time before true sleep`() {
        val zone = ZoneId.systemDefault()
        val schedule = SleepSchedule(23 * 60, 7 * 60)
        val window = schedule.windowForStartDate(LocalDate.of(2026, 9, 27), zone)
        val sessions = SleepAnalyzer.analyzeByWindow(
            segments = listOf(SleepSegment(window.startMillis, window.endMillis, 100)),
            classifications = listOf(
                ClassificationSample(window.startMillis + 10 * 60_000, 10, 80, 80),
                ClassificationSample(window.startMillis + 20 * 60_000, 15, 70, 70),
                ClassificationSample(window.startMillis + 30 * 60_000, 90, 0, 0)
            ),
            phoneUse = emptyList(), schedule = schedule, windows = listOf(window)
        )

        assertEquals(window.startMillis + 30 * 60_000, sessions.single().startMillis)
    }

    @Test fun `nearby SleepSegmentEvent gaps form one night with an awake interval`() {
        val zone = ZoneId.systemDefault()
        val schedule = SleepSchedule(23 * 60, 7 * 60)
        val window = schedule.windowForStartDate(LocalDate.of(2026, 9, 27), zone)
        val sessions = SleepAnalyzer.analyzeByWindow(
            segments = listOf(
                SleepSegment(window.startMillis + 30 * 60_000, window.startMillis + 3 * 60 * 60_000, 100),
                SleepSegment(window.startMillis + 3 * 60 * 60_000 + 20 * 60_000, window.endMillis, 100)
            ),
            classifications = emptyList(), phoneUse = emptyList(), schedule = schedule, windows = listOf(window)
        )

        assertEquals(1, sessions.size)
        assertEquals(20 * 60_000L, sessions.single().awakeMillis)
        assertEquals(1, sessions.single().awakeIntervals.size)
    }

    private fun samples() = (0..47).map { index ->
        ClassificationSample(start + index * 10 * 60_000L, 90, 0, 0)
    }
}
