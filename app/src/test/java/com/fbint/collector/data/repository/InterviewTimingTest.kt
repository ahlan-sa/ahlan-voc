package com.fbint.collector.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterviewTimingTest {
    private fun timing(value: Any?) = interviewTiming(mapOf("time_to_complete_seconds" to value))

    @Test fun convertsStoredSecondsToMillisecondsWithoutSendingTotal() {
        assertEquals(mapOf("interview" to 123000L), timing("123"))
        assertEquals(mapOf("interview" to 123000L), timing(123.0))
        assertEquals(mapOf("interview" to 1250L), timing("1.25"))
    }

    @Test fun zeroIsValid() {
        assertEquals(mapOf("interview" to 0L), timing("0"))
    }

    @Test fun omitsMissingInvalidNegativeAndNonFiniteDurations() {
        assertNull(interviewTiming(emptyMap()))
        listOf(null, "", "bad", "-1", -0.1, true, listOf(1), "NaN", Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach {
            assertNull("Unexpected timing for $it", timing(it))
        }
    }

    @Test fun rejectsOverflowAndSubMillisecondValuesWithoutWrappingOrTruncating() {
        assertNull(timing(Long.MAX_VALUE))
        assertNull(timing("9223372036854775.808"))
        assertNull(timing("0.0001"))
        assertEquals(mapOf("interview" to Long.MAX_VALUE), timing("9223372036854775.807"))
    }

    @Test fun leavesExistingStampsUnchanged() {
        val stamps = mapOf("time_to_complete_seconds" to "123", "surveyor_id" to "collector")
        val original = stamps.toMap()
        interviewTiming(stamps)
        assertEquals(original, stamps)
    }
}
