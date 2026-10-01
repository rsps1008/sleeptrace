package com.rsps1008.sleeptrace.sleep

/** Serializes asynchronous provider writes and coalesces requests to the latest desired mode. */
internal class SleepSubscriptionController(
    private val submit: (Int?, complete: (Boolean) -> Unit) -> Unit
) {
    private data class Request(val mode: Int?, val revision: Long)
    private var desired: Int? = null
    private var revision = 0L
    private var applied: Int? = null
    private var appliedKnown = false
    private var pending: Request? = null

    @Synchronized
    fun request(mode: Int?, force: Boolean = false) {
        if (desired != mode || force) revision++
        desired = mode
        if (force) appliedKnown = false
        if (pending == null && (!appliedKnown || applied != desired)) start()
    }

    private fun start() {
        val request = Request(desired, revision)
        pending = request
        submit(request.mode) { success -> complete(request, success) }
    }

    @Synchronized
    private fun complete(request: Request, success: Boolean) {
        if (pending !== request) return
        pending = null
        appliedKnown = success
        if (success) applied = request.mode
        // A pause/window change received during the write must be applied afterwards.
        // Failure of an unchanged request waits for the next external trigger, not a tight retry.
        if (revision != request.revision) start()
    }
}
