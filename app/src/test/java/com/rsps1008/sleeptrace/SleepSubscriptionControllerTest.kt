package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.sleep.SleepSubscriptionController
import org.junit.Assert.*
import org.junit.Test

class SleepSubscriptionControllerTest {
    private class Provider {
        val modes = mutableListOf<Int?>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        val controller = SleepSubscriptionController { mode, complete -> modes += mode; completions += complete }
    }

    @Test fun `pause waits for earlier subscribe then removes and resume really subscribes again`() {
        val p = Provider()
        p.controller.request(1)
        p.controller.request(null)
        assertEquals(listOf(1), p.modes)
        p.completions[0](true)
        assertEquals(listOf(1, null), p.modes)
        p.completions[1](true)
        p.controller.request(1)
        assertEquals(listOf(1, null, 1), p.modes)
        p.completions[2](true)
        p.controller.request(1)
        assertEquals(3, p.modes.size)
    }

    @Test fun `a burst coalesces while a mode change is applied after the in-flight request`() {
        val p = Provider()
        repeat(100) { p.controller.request(1) }
        assertEquals(listOf(1), p.modes)
        p.controller.request(2)
        p.controller.request(null)
        p.completions[0](true)
        assertEquals(listOf(1, null), p.modes)
    }

    @Test fun `failure does not spin and a later trigger retries even the same mode`() {
        val p = Provider()
        p.controller.request(1)
        p.completions[0](false)
        assertEquals(listOf(1), p.modes)
        p.controller.request(1)
        assertEquals(listOf(1, 1), p.modes)
        p.controller.request(null)
        p.completions[1](false)
        assertEquals(listOf(1, 1, null), p.modes)
    }

    @Test fun `forced refresh survives an in-flight request and stale callbacks cannot undo it`() {
        val p = Provider()
        p.controller.request(1)
        p.controller.request(1, force = true)
        p.completions[0](true)
        assertEquals(listOf(1, 1), p.modes)
        p.controller.request(null)
        p.completions[0](true)
        assertEquals(2, p.modes.size)
        p.completions[1](true)
        assertEquals(listOf(1, 1, null), p.modes)
    }

    @Test fun `repeated successful removal is idle until explicit force or subscription change`() {
        val p = Provider()
        p.controller.request(null)
        p.completions[0](true)
        repeat(100) { p.controller.request(null) }
        assertEquals(listOf<Int?>(null), p.modes)
        p.controller.request(null, force = true)
        assertEquals(listOf<Int?>(null, null), p.modes)
    }
}
