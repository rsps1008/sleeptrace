package com.rsps1008.sleeptrace

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.rsps1008.sleeptrace.motion.CaptureDiagnostics
import com.rsps1008.sleeptrace.motion.CapturePresentationField
import com.rsps1008.sleeptrace.motion.homePresentation
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Read-only content for the export card. The parent owns refreshes and saved expansion state. */
class HomeCaptureView(context: Context) : LinearLayout(context) {
    private val started = FieldView(context)
    private val requestedRate = FieldView(context, prominent = true)
    private val registeredRate = FieldView(context)
    private val observedRate = FieldView(context, prominent = true)
    private val featureCeiling = FieldView(context, prominent = true)
    private val fifoCapacity = FieldView(context)
    private val batchWait = FieldView(context)
    private val wakeUp = FieldView(context)
    private val details = LinearLayout(context).apply { orientation = VERTICAL }
    private val toggle = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        isAllCaps = false
        textSize = 14f
        minimumHeight = dp(48)
        cornerRadius = dp(14)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        insetTop = 0
        insetBottom = 0
        maxLines = Int.MAX_VALUE
        ellipsize = null
        setTextColor(color(R.color.text_primary))
        strokeColor = android.content.res.ColorStateList.valueOf(color(R.color.card_stroke))
        setOnClickListener { detailsExpanded = !detailsExpanded }
    }

    var detailsExpanded: Boolean = false
        set(value) {
            field = value
            updateExpansion()
        }

    init {
        orientation = VERTICAL
        addView(started, fullWidth())
        val rates = LinearLayout(context).apply {
            orientation = VERTICAL
            background = panelBackground()
            setPadding(dp(12), dp(4), dp(12), dp(4))
            addView(requestedRate, fullWidth())
            addView(registeredRate, fullWidth())
            addView(divider())
            addView(observedRate, fullWidth())
            addView(divider())
            addView(featureCeiling, fullWidth())
        }
        addView(rates, fullWidth())
        addView(toggle, fullWidth().apply { topMargin = dp(8) })
        details.apply {
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = panelBackground()
            addView(fifoCapacity, fullWidth())
            addView(divider())
            addView(batchWait, fullWidth())
            addView(divider())
            addView(wakeUp, fullWidth())
        }
        addView(details, fullWidth().apply { topMargin = dp(8) })
        bind(null)
        updateExpansion()
    }

    /** Rebinding persisted data never resets the user's expanded/collapsed choice. */
    fun bind(capture: CaptureDiagnostics?) {
        val presentation = capture.homePresentation()
        started.bind(presentation.started)
        requestedRate.bind(presentation.requestedRate)
        registeredRate.visibility = if (presentation.registeredRate == null) GONE else VISIBLE
        presentation.registeredRate?.let(registeredRate::bind)
        observedRate.bind(presentation.observedRate)
        featureCeiling.bind(presentation.featureCeiling)
        fifoCapacity.bind(presentation.fifoCapacity)
        batchWait.bind(presentation.batchWait)
        wakeUp.bind(presentation.wakeUp)
    }

    private fun updateExpansion() {
        details.visibility = if (detailsExpanded) VISIBLE else GONE
        toggle.text = if (detailsExpanded) "收合硬體與批次說明" else "展開硬體與批次說明"
        ViewCompat.setStateDescription(toggle, if (detailsExpanded) "已展開" else "已收合")
    }

    private fun panelBackground() = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(color(R.color.home_panel))
        setStroke(dp(1), color(R.color.card_stroke))
    }

    private fun divider() = View(context).apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(color(R.color.card_stroke))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun fullWidth() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    /** Each note is a separate wrapping block; existing views are reused across capture updates. */
    private class FieldView(context: Context, prominent: Boolean = false) : LinearLayout(context) {
        private val row = ValueRow(context, prominent)
        private val notes = mutableListOf<TextView>()

        init {
            orientation = VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        fun bind(field: CapturePresentationField) {
            row.bind(field.label, field.value)
            while (notes.size < field.notes.size) {
                val note = TextView(context).apply {
                    textSize = 13f
                    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                    setPadding(0, dp(4), 0, 0)
                    setLineSpacing(dp(2).toFloat(), 1.15f)
                }
                notes += note
                addView(note, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
            notes.forEachIndexed { index, note ->
                note.text = field.notes.getOrNull(index).orEmpty()
                note.visibility = if (index < field.notes.size) VISIBLE else GONE
            }
        }

        private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    }

    /** Stack before measuring if the natural text widths do not fit, including large font scales. */
    private class ValueRow(context: Context, prominent: Boolean) : LinearLayout(context) {
        private val label = TextView(context).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }
        private val value = TextView(context).apply {
            textSize = if (prominent) 18f else 14f
            if (prominent) typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(context, if (prominent) R.color.home_accent else R.color.text_primary))
        }

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(value, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) })
        }

        fun bind(labelText: String, valueText: String) {
            label.text = labelText
            value.text = valueText
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
            val naturalWidth = ceil(Layout.getDesiredWidth(label.text, label.paint).toDouble()).toInt() +
                ceil(Layout.getDesiredWidth(value.text, value.paint).toDouble()).toInt() + dp(16)
            val horizontal = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED &&
                !value.text.contains('\n') && naturalWidth <= available
            val nextOrientation = if (horizontal) HORIZONTAL else VERTICAL
            if (orientation != nextOrientation) {
                orientation = nextOrientation
                label.layoutParams = (label.layoutParams as LayoutParams).apply {
                    width = if (horizontal) 0 else LayoutParams.MATCH_PARENT
                    weight = if (horizontal) 1f else 0f
                }
                value.layoutParams = (value.layoutParams as LayoutParams).apply {
                    width = if (horizontal) LayoutParams.WRAP_CONTENT else LayoutParams.MATCH_PARENT
                    marginStart = if (horizontal) dp(16) else 0
                    topMargin = if (horizontal) 0 else dp(2)
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }

        private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    }
}
