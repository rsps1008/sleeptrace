package com.rsps1008.sleeptrace.health

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.rsps1008.sleeptrace.R

/** Health Connect's required permission-rationale destination; no extra consent step. */
class HealthPrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.health_data_usage_title)
        val padding = (24 * resources.displayMetrics.density).toInt()
        setContentView(ScrollView(this).apply {
            addView(TextView(this@HealthPrivacyActivity).apply {
                textSize = 17f
                setPadding(padding, padding, padding, padding)
                setText(R.string.health_data_usage)
            })
        })
    }
}
