package com.fbint.collector.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class CreateResponseRequest(
    val surveyId: String,
    val finished: Boolean,
    val data: Map<String, Any?>,
    val userId: String? = null,
    val meta: Map<String, String>? = null,
    val language: String? = null,
    val variables: Map<String, Any?>? = null,
    val hiddenFields: Map<String, Any?>? = null,
    val ttc: Map<String, Long>? = null,
    val endingId: String? = null,
)

@JsonClass(generateAdapter = true)
data class CreateResponseEnvelope(val data: CreateResponseResult)

@JsonClass(generateAdapter = true)
data class CreateResponseResult(val id: String)

@JsonClass(generateAdapter = true)
data class CreateDisplayRequest(
    val surveyId: String,
    val userId: String? = null,
)

@JsonClass(generateAdapter = true)
data class ResponseHistoryEnvelope(val data: List<ResponseHistoryItem>)

@JsonClass(generateAdapter = true)
data class ResponseHistoryItem(
    val id: String,
    val meta: Map<String, Any?>? = null,
    val createdAt: String? = null,
    val finished: Boolean = false,
    val data: Map<String, Any?> = emptyMap(),
)
