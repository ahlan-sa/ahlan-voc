package com.fbint.collector.ui.setup

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi

/** Payload encoded into the admin-generated QR. Surveyor devices decode and persist it verbatim. */
@JsonClass(generateAdapter = true)
data class SetupConfig(
    val baseUrl: String,
    val apiKey: String,
    val environmentId: String = "",
    val projectName: String? = null,
    val workspaceId: String? = null,
) {
    fun connectionId(): String = environmentId.ifBlank { workspaceId.orEmpty() }
}

object SetupConfigCodec {
    private val adapter = Moshi.Builder().build().adapter(SetupConfig::class.java)
    fun encode(config: SetupConfig): String = adapter.toJson(config)
    fun decode(json: String): SetupConfig? = runCatching { adapter.fromJson(json) }.getOrNull()
}
