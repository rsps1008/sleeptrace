package com.rsps1008.sleeptrace.sleep

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.SleepClassifyEvent
import com.google.android.gms.location.SleepSegmentEvent
import com.rsps1008.sleeptrace.data.SleepStore
import com.rsps1008.sleeptrace.motion.MotionService
import com.rsps1008.sleeptrace.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SleepUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = SleepStore(context)
                if (SleepSegmentEvent.hasEvents(intent)) {
                    store.appendSegments(SleepSegmentEvent.extractEvents(intent)
                        .filter { it.status != SleepSegmentEvent.STATUS_NOT_DETECTED }
                        .map {
                            SleepSegment(
                                it.startTimeMillis,
                                it.endTimeMillis,
                                if (it.status == SleepSegmentEvent.STATUS_SUCCESSFUL) 100 else 60
                            )
                        })
                }
                if (SleepClassifyEvent.hasEvents(intent)) {
                    val samples = SleepClassifyEvent.extractEvents(intent).map {
                        ClassificationSample(it.timestampMillis, it.confidence, it.motion, it.light)
                    }
                    store.appendSamples(samples)
                    MotionService.active?.onSleepClassifications(samples)
                }
                // Classification arrives frequently. Reconcile on completed segments or the periodic worker.
                if (SleepSegmentEvent.hasEvents(intent)) WorkScheduler.reconcileSoon(context)
            } finally { pending.finish() }
        }
    }
}
