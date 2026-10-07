package com.rsps1008.sleeptrace.data

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import org.json.JSONArray

/** Annual Taiwan workday/holiday calendar cached locally after the first successful download. */
internal object TaiwanHolidayCalendar {
    private const val PREFS = "sleeptrace_holidays"
    private const val BASE_URL = "https://cdn.jsdelivr.net/gh/ruyut/TaiwanCalendar/data/"

    /** Runs on the preferences IO dispatcher. Network failure preserves any prior offline cache. */
    fun refreshAndRead(context: Context, year: Int = LocalDate.now().year): Map<LocalDate, Boolean> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "calendar_$year"
        val raw = prefs.getString(key, null) ?: download(year)?.also { prefs.edit().putString(key, it).apply() }
            ?: return emptyMap()
        return parse(raw)
    }

    private fun download(year: Int): String? = runCatching {
        val connection = (URL("$BASE_URL$year.json").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            requestMethod = "GET"
            useCaches = true
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun parse(raw: String): Map<LocalDate, Boolean> = runCatching {
        val entries = JSONArray(raw)
        buildMap {
            repeat(entries.length()) { index ->
                val item = entries.getJSONObject(index)
                val value = item.getString("date")
                val date = LocalDate.of(value.substring(0, 4).toInt(), value.substring(4, 6).toInt(), value.substring(6, 8).toInt())
                put(date, item.getBoolean("isHoliday"))
            }
        }
    }.getOrDefault(emptyMap())
}
