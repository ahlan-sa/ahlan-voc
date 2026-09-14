package com.fbint.collector.data.repository

import com.fbint.collector.data.remote.dto.ResponseHistoryItem
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class TeamPerformanceTest {
    private val start = Instant.parse("2026-09-14T00:00:00Z").toEpochMilli()
    private val end = start + 86_400_000
    private fun response() = ResponseHistoryItem("server", mapOf("source" to "fbint:capture", "surveyor" to "Essam"),
        "2026-09-14T12:00:00Z", true, mapOf("submitted_at" to "2026-09-14T10:00:00Z"))

    @Test fun usesCaptureDayInsteadOfUploadDay() {
        assertEquals("Essam" to "fbint:capture", TeamPerformanceRepository.collectorResponse(response(), start, end))
        assertNull(TeamPerformanceRepository.collectorResponse(response().copy(
            data = mapOf("submitted_at" to "2026-09-13T22:00:00Z")), start, end))
    }
    @Test fun ignoresIncompleteAndNonCollectorResponses() {
        assertNull(TeamPerformanceRepository.collectorResponse(response().copy(finished = false), start, end))
        assertNull(TeamPerformanceRepository.collectorResponse(response().copy(meta = mapOf("source" to "website")), start, end))
    }
    @Test fun oldCollectorMetadataStillCountsAndMidnightBelongsToNextDay() {
        assertEquals("Essam" to "fbint:capture", TeamPerformanceRepository.collectorResponse(response().copy(data = emptyMap()), start, end))
        assertNull(TeamPerformanceRepository.collectorResponse(response().copy(data = mapOf("submitted_at" to "2026-09-15T00:00:00Z")), start, end))
    }
}
