package com.fbint.collector.data.repository

/** Formbricks expects milliseconds and computes _total itself for finished responses. */
internal fun interviewTiming(autoStamps: Map<String, Any?>): Map<String, Long>? {
    val value = autoStamps["time_to_complete_seconds"]
    if (value !is String && value !is Number) return null
    val seconds = value.toString().trim().toBigDecimalOrNull() ?: return null
    if (seconds.signum() < 0) return null
    // Exact conversion rejects overflow and fractions smaller than one millisecond.
    val milliseconds = try {
        seconds.movePointRight(3).longValueExact()
    } catch (_: ArithmeticException) {
        return null
    }
    return mapOf("interview" to milliseconds)
}
