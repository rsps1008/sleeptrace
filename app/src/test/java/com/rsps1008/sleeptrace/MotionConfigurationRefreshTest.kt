package com.rsps1008.sleeptrace

import com.rsps1008.sleeptrace.motion.consumeMotionConfigurationRefreshes
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class MotionConfigurationRefreshTest {
    @Test fun `refresh bursts are conflated and cannot publish old configuration after closure`() = runBlocking {
        val requests = Channel<Unit>(Channel.CONFLATED)
        val firstRead = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var closed = false
        val published = mutableListOf<Boolean>()
        var reads = 0
        val consumer = launch {
            consumeMotionConfigurationRefreshes(requests, refresh = {
                val snapshot = closed
                if (++reads == 1) { firstRead.complete(Unit); releaseFirst.await() }
                published += snapshot
                if (reads == 2) finished.complete(Unit)
            }, onFailure = { throw AssertionError(it) })
        }
        requests.send(Unit)
        firstRead.await()
        closed = true
        repeat(100) { requests.trySend(Unit) }
        releaseFirst.complete(Unit)
        withTimeout(5_000) { finished.await() }
        requests.close(); consumer.join()
        assertEquals(2, reads)
        assertEquals(listOf(false, true), published)
    }

    @Test fun `failed refresh can recover on next request while cancellation propagates`() = runBlocking {
        val requests = Channel<Unit>(Channel.CONFLATED)
        var attempts = 0
        val failed = CompletableDeferred<Unit>()
        val recovered = CompletableDeferred<Unit>()
        val consumer = launch {
            consumeMotionConfigurationRefreshes(requests, refresh = {
                if (++attempts == 1) error("read failed")
                recovered.complete(Unit)
            }, onFailure = { failed.complete(Unit) })
        }
        requests.send(Unit); failed.await()
        requests.send(Unit); recovered.await()
        consumer.cancelAndJoin()
        assertTrue(consumer.isCancelled)
        assertEquals(2, attempts)
        requests.close()

        val cancelledRequests = Channel<Unit>(1).apply { trySend(Unit) }
        try {
            consumeMotionConfigurationRefreshes(cancelledRequests,
                refresh = { throw CancellationException("stop") },
                onFailure = { fail("Cancellation must propagate") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        cancelledRequests.close()
        Unit
    }
}
