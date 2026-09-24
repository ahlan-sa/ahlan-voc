package com.fbint.collector.data.remote

import com.squareup.moshi.Moshi
import retrofit2.HttpException

/** Keep Formbricks validation details on the device, without logging request bodies. */
internal fun syncErrorMessage(error: Throwable): String {
    if (error !is HttpException) return (error.message ?: error.javaClass.simpleName).take(1200)
    val prefix = "HTTP ${error.code()}"
    val details = runCatching {
        error.response()?.errorBody()?.use { body ->
            val source = body.source()
            // Never load an unbounded proxy error page or echo an HTML response.
            source.request(16_385)
            if (source.buffer.size > 16_384) return@use null
            val json = Moshi.Builder().build().adapter(Any::class.java)
                .fromJson(source.readUtf8()) as? Map<*, *> ?: return@use null
            buildList {
                (json["message"] as? String)?.let { add(it) }
                val errors = json["details"] as? Map<*, *>
                errors?.entries?.take(8)?.forEach { (field, reason) ->
                    if (field is String && reason is String) add("$field: $reason")
                }
            }.joinToString(" · ").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
    return (if (details == null) prefix else "$prefix: $details")
        .replace(Regex("fbk_[A-Za-z0-9]+"), "[redacted]")
        .replace(Regex("[\\p{Cc}\\p{Cf}]+"), " ")
        .take(1200)
}
