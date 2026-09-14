package com.fbint.collector.ui.runner

import com.fbint.collector.data.remote.dto.SurveyDto

/** Encrypted draft snapshot. A stable submission ID closes the enqueue/draft-delete crash gap. */
data class RunnerDraft(
    val submissionId: String,
    val survey: SurveyDto,
    val stage: String,
    val answers: Map<String, Any?>,
    val variables: Map<String, Any?>,
    val hiddenFields: Map<String, Any?>,
    val language: String,
    val surveyorId: String?,
    val startedAtMs: Long,
    val elapsedMs: Long,
    val savedAtMs: Long,
    val history: List<RunnerHistory>,
)

data class RunnerHistory(val stage: String, val variables: Map<String, Any?>)
