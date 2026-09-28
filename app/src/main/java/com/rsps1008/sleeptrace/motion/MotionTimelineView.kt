package com.rsps1008.sleeptrace.motion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.rsps1008.sleeptrace.sleep.UsageInterval

class MotionTimelineView @JvmOverloads constructor(context: Context, private val minutes: List<MotionMinute> = emptyList(), private val usage: List<UsageInterval> = emptyList()) : View(context) {
    private val paint = Paint()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (minutes.isEmpty()) return
        val start = minutes.first().startMillis
        val end = minutes.last().startMillis + MINUTE_MS
        val range = (end - start).toFloat()
        paint.color = Color.GRAY
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        minutes.forEach { minute ->
            paint.color = when {
                minute.placement != Placement.BED -> Color.GRAY
                minute.level == MotionLevel.UNKNOWN -> Color.GRAY
                minute.level == MotionLevel.ACTIVE -> Color.rgb(225, 139, 38)
                else -> Color.rgb(71, 113, 193)
            }
            canvas.drawRect((minute.startMillis - start) / range * width, 0f,
                (minute.startMillis + MINUTE_MS - start) / range * width, height.toFloat(), paint)
        }
        paint.color = Color.rgb(210, 67, 67)
        usage.forEach {
            val left = maxOf(start, it.startMillis)
            val right = minOf(end, it.endMillis)
            if (right > left) canvas.drawRect((left - start) / range * width, 0f, (right - start) / range * width, height.toFloat(), paint)
        }
    }
}
