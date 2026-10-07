package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.data.AutomaticWorkSignals
import com.rsps1008.sleeptrace.sleep.SleepWindow
import com.rsps1008.sleeptrace.sleep.contiguousCompletedWindowEnd
import org.junit.Assert.assertEquals
import org.junit.Test

class ReconciliationProgressTest {
    @Test fun `first upgraded run scans retained raw evidence`() {
        assertEquals(100L, AutomaticWorkSignals.reconciliationStartFor(100L, 800L, 0L, 500L))
    }

    @Test fun `newer pending event cannot skip older incomplete window`() {
        assertEquals(400L, AutomaticWorkSignals.reconciliationStartFor(100L, 900L, 400L, 500L))
    }

    @Test fun `cursor only advances through consecutive completed nights`() {
        val first = SleepWindow(100L, 200L)
        val missing = SleepWindow(300L, 400L)
        val later = SleepWindow(500L, 600L)
        assertEquals(200L, contiguousCompletedWindowEnd(listOf(first, missing, later), setOf(100L, 500L)))
    }
}
