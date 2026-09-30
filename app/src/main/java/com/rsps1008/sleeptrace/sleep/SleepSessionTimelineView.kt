package com.rsps1008.sleeptrace.sleep

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/** Compact detail-only timeline using the active theme colors for Awake, Light and Deep. */
class SleepSessionTimelineView(context: Context, private val session: SleepSession) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MaterialColors.getColor(this@SleepSessionTimelineView, android.R.attr.textColorSecondary, Color.GRAY)
        textSize = 10 * density
    }
    private val primaryColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.GRAY)
    private val lightColor = ColorUtils.setAlphaComponent(primaryColor, 150)
    private val deepColor = MaterialColors.getColor(
        this,
        com.google.android.material.R.attr.colorTertiary,
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryVariant, primaryColor)
    )
    private val awakeColor = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError, primaryColor)
    private val trackColor = ColorUtils.setAlphaComponent(primaryColor, 45)
    private val track = RectF()
    private val clip = Path()

    init {
        contentDescription = "清醒、推估淺眠、推估深眠、深淺未判定時間軸；依手機活動與 Google Sleep API 推估，非醫療睡眠分期"
        minimumHeight = (70 * density).toInt()
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
        canvas.save()
        clip.reset()
        clip.addRoundRect(track, radius, radius, Path.Direction.CW)
        canvas.clipPath(clip)
        sleepParts(session).forEach { part ->
            paint.color = when (part.stage) {
                SleepStage.AWAKE -> awakeColor
                SleepStage.LIGHT -> lightColor
                SleepStage.DEEP -> deepColor
                SleepStage.SLEEPING -> trackColor
            }
            val start = left + (part.start - session.startMillis).toFloat() / span * track.width()
            val end = left + (part.end - session.startMillis).toFloat() / span * track.width()
            canvas.drawRect(max(left, start), top, min(right, end), bottom, paint)
            if (part.stage == SleepStage.SLEEPING) {
                paint.color = primaryColor; paint.strokeWidth = density
                var x = max(left, start)
                while (x < min(right, end)) {
                    canvas.drawLine(x, bottom, minOf(x + 8 * density, end), top, paint)
                    x += 8 * density
                }
            }
        }
        canvas.restore()
        canvas.drawText("入睡", left, 12 * density, labelPaint)
        val endLabel = "醒來"
        canvas.drawText(endLabel, right - labelPaint.measureText(endLabel), 12 * density, labelPaint)
        val legendY = bottom + 22 * density
        val legend = listOf("清醒" to awakeColor, "淺眠" to lightColor,
            "深眠" to deepColor, "未判定／斜線" to trackColor)
        val sectionWidth = track.width() / legend.size
        legend.forEachIndexed { index, (label, color) ->
            val markerX = left + sectionWidth * index
            paint.color = color
            canvas.drawCircle(markerX + 4 * density, legendY - 4 * density, 4 * density, paint)
            canvas.drawText(label, markerX + 12 * density, legendY, labelPaint)
        }
    }
}
