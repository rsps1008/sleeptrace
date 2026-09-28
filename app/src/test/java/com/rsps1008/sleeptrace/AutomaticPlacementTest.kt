package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.*
import com.rsps1008.sleeptrace.sleep.*
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class AutomaticPlacementTest {
    private val start = LocalDate.of(2026, 9, 28).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private fun rows(moving: Set<Int> = emptySet(), count: Int = 60) = (0 until count).map {
        MotionMinute(start + it * MINUTE_MS, MINUTE_MS, if (it in moving) 1_000 else 0,
            if (it in moving) 0.05 * 0.05 * MINUTE_MS else 0.0, 300, Placement.AUTO)
    }

    @Test fun `prolonged stillness suggests bedside but cannot create sleep alone`() {
        val resolved = AutomaticPlacement.resolve(rows(), emptyList())
        assertTrue(resolved.all { it.placement == Placement.BEDSIDE })
        assertTrue(MotionSleepEstimator.estimate(resolved, emptyList(), SleepSchedule(0, 60), start + 60 * MINUTE_MS).isEmpty())
    }

    @Test fun `separated brief movements provide bed evidence without manual settings`() {
        val resolved = AutomaticPlacement.resolve(rows(setOf(5, 15, 25, 35, 45, 55)), emptyList())
        assertTrue(resolved.all { it.placement == Placement.BED })
        val sleep = MotionSleepEstimator.estimate(resolved, emptyList(), SleepSchedule(0, 60), start + 60 * MINUTE_MS)
        assertEquals(60 * MINUTE_MS, sleep.single().durationMillis)
        assertEquals(SyncState.PENDING, sleep.single().state)
    }

    @Test fun `single vibration or brief sample cannot establish bed placement`() {
        assertTrue(AutomaticPlacement.resolve(rows(setOf(10)), emptyList()).none { it.placement == Placement.BED })
        assertTrue(AutomaticPlacement.resolve(rows(setOf(1, 5, 9), 10), emptyList()).all { it.placement == Placement.UNKNOWN })
    }

    @Test fun `phone use and surrounding handling do not establish bed evidence`() {
        val moving = setOf(5, 15, 25, 35, 45, 55)
        val usage = moving.map { UsageInterval(start + it * MINUTE_MS, start + (it + 1) * MINUTE_MS) }
        assertTrue(AutomaticPlacement.resolve(rows(moving), usage).none { it.placement == Placement.BED })
    }

    @Test fun `missing data separates placement evidence and preserves unknown`() {
        val input = rows(setOf(5, 15, 25, 35, 45, 55)).mapIndexed { index, row ->
            if (index % 10 == 0) row.copy(coveredMillis = 1_000) else row
        }
        assertTrue(AutomaticPlacement.resolve(input, emptyList()).all { it.placement == Placement.UNKNOWN })
    }

    @Test fun `placement changes are reevaluated instead of applying one label to all night`() {
        val resolved = AutomaticPlacement.resolve(rows(setOf(5, 15, 25), 120), emptyList())
        assertEquals(Placement.BED, resolved[10].placement)
        assertEquals(Placement.BEDSIDE, resolved[100].placement)
    }

    @Test fun `legacy minute placement remains readable but cannot spread into new records`() {
        val input = listOf(rows().first().copy(placement = Placement.BED)) + rows().drop(1)
        val resolved = AutomaticPlacement.resolve(input, emptyList())
        assertEquals(Placement.BED, resolved.first().placement)
        assertTrue(resolved.drop(1).none { it.placement == Placement.BED })
    }
}
