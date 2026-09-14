package com.fbint.collector.data.repository

import com.fbint.collector.data.remote.FormbricksApiFactory
import com.fbint.collector.data.remote.dto.ResponseHistoryItem
import com.fbint.collector.data.remote.dto.resolveWorkspace
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
data class TeamPerformanceSnapshot(
    val dayStart: Long,
    val updatedAt: Long,
    val collector: String,
    val ownSources: Set<String>,
    val activeCollectors: Int,
    val totalResponses: Int,
)

/** Aggregates only completed collector responses; individual answers are never cached here. */
@Singleton
class TeamPerformanceRepository @Inject constructor(
    private val factory: FormbricksApiFactory,
    private val config: ConfigRepository,
    moshi: Moshi,
) {
    private val adapter = moshi.adapter(TeamPerformanceSnapshot::class.java)
    fun cached(): TeamPerformanceSnapshot? = runCatching {
        config.loadPerformanceSnapshot()?.let(adapter::fromJson)
    }.getOrNull()?.takeIf { it.dayStart == todayStart() && it.collector == config.surveyorId().orEmpty() }

    suspend fun refresh(): TeamPerformanceSnapshot {
        val base = requireNotNull(config.baseUrl())
        val collector = config.surveyorId().orEmpty()
        val key = requireNotNull(config.apiKey())
        val api = factory.management { base }
        val entered = config.workspaceId() ?: requireNotNull(config.environmentId())
        val connection = api.me(key).resolveWorkspace(entered)
        val surveys = api.listSurveys(key).data.filter {
            it.environmentId == connection.environmentId ||
                (connection.workspaceId != null && it.workspaceId == connection.workspaceId)
        }
        val start = todayStart()
        val end = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val counts = mutableMapOf<String, Int>()
        val seen = mutableSetOf<String>()
        val own = mutableSetOf<String>()
        for (survey in surveys) {
            var complete = false
            for (page in 0 until 100) {
                val rows = api.listResponses(key, survey.id, 100, page * 100).data
                for (row in rows) {
                    val item = collectorResponse(row, start, end) ?: continue
                    if (seen.add(item.second)) {
                        counts[item.first] = (counts[item.first] ?: 0) + 1
                        if (item.first == collector) own.add(item.second)
                    }
                }
                // Formbricks returns newest creation time first. Offline captures can be
                // older than creation time, so capture-day filtering happens above.
                if (rows.size < 100 || rows.all { parseTime(it.createdAt)?.let { time -> time < start } == true }) {
                    complete = true
                    break
                }
            }
            check(complete) { "Team history is too large to verify completely." }
        }
        check((config.workspaceId() ?: config.environmentId()) == entered && config.baseUrl() == base && config.surveyorId().orEmpty() == collector && todayStart() == start) {
            "Device settings or collection day changed during refresh."
        }
        return TeamPerformanceSnapshot(start, System.currentTimeMillis(), collector, own, counts.size, counts.values.sum()).also {
            config.savePerformanceSnapshot(adapter.toJson(it))
        }
    }

    companion object {
        fun todayStart(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        private fun parseTime(value: String?): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        internal fun collectorResponse(row: ResponseHistoryItem, start: Long, end: Long): Pair<String, String>? {
            if (!row.finished) return null
            val source = row.meta?.get("source") as? String ?: return null
            if (!source.startsWith("fbint:")) return null
            val collector = (row.data["surveyor_id"] as? String)?.takeIf { it.isNotBlank() }
                ?: (row.meta["surveyor"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            val time = parseTime(row.data["submitted_at"] as? String) ?: parseTime(row.createdAt) ?: return null
            return if (time >= start && time < end) collector.trim() to source else null
        }
    }
}
