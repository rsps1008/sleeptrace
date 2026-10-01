package com.rsps1008.sleeptrace.motion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ReceiveChannel

/** One consumer preserves read/publication order; its conflated input bounds redundant refreshes. */
internal suspend fun consumeMotionConfigurationRefreshes(
    requests: ReceiveChannel<Unit>,
    refresh: suspend () -> Unit,
    onFailure: (Exception) -> Unit
) {
    for (ignored in requests) {
        try {
            refresh()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A failed read must not kill the consumer or replace the last known configuration.
            onFailure(error)
        }
    }
}
