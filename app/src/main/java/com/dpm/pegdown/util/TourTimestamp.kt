package com.dpm.pegdown.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Parses persisted tour timestamps, including legacy entries without milliseconds. */
object TourTimestamp {
    private val formats = listOf(
        "yyyy-MM-dd HH:mm:ss.SSS",
        "yyyy-MM-dd HH:mm:ss",
    )

    fun parseMillis(value: String): Long? {
        for (pattern in formats) {
            val position = ParsePosition(0)
            val parsed = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }.parse(value, position)

            if (parsed != null && position.index == value.length) {
                return parsed.time
            }
        }
        return null
    }
}
