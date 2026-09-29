package com.rsps1008.sleeptrace.sleep

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/** Compact detail-only timeline: sleeping is blue and persisted phone-use deductions are awake. */
class SleepSessionTimelineView(context: Context, private val session: SleepSession) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SleepSessionTimelineView, android.R.attr.textColorSecondary, Color.rgb(85, 83, 110))
        textSize = 12 * density
    }
    private val trackColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.rgb(103, 80, 164))
    private val awakeColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError, Color.rgb(198, 72, 113))
    private val track = RectF()
    private val clip = Path()

    init {
        contentDescription = "睡眠時段；紅色區塊是已扣除的手機使用時間"
        minimumHeight = (58 * density).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        val top = paddingTop + 20 * density
        val bottom = top + 16 * density
        track.set(left, top, right, bottom)
        paint.color = trackColor
        val radius = 8 * density
        canvas.drawRoundRect(track, radius, radius, paint)
        val span = (session.endMillis - session.startMillis).coerceAtLeast(1)
        paint.color = awakeColor
        canvas.save()
        clip.reset()
        clip.addRoundRect(track, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        normalizedAwake(session.startMillis, session.endMillis, session.awakeIntervals).forEach { awake ->
            val start = left + (awake.startMillis - session.startMillis).toFloat() / span * track.width()
            val end = left + (awake.endMillis - session.startMillis).toFloat() / span * track.width()
            canvas.drawRect(max(left, start), top, min(right, end), bottom, paint)
        }
        canvas.restore()
        canvas.drawText("入睡", left, 12 * density, labelPaint)
        val endLabel = "醒來"
        canvas.drawText(endLabel, right - labelPaint.measureText(endLabel), 12 * density, labelPaint)
        paint.color = awakeColor
        canvas.drawCircle(left, bottom + 17 * density, 4 * density, paint)
        canvas.drawText("已扣除的手機使用／清醒", left + 11 * density, bottom + 21 * density, labelPaint)
    }
}
