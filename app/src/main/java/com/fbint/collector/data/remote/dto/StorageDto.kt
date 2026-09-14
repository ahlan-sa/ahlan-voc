package com.fbint.collector.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class UploadFileRequest(
    val fileName: String,
    val fileType: String,
    val surveyId: String,
    val elementId: String,
    val allowedFileExtensions: List<String>? = null,
)

@JsonClass(generateAdapter = true)
data class UploadFileEnvelope(val data: UploadFileResult)

/**
 * The response can take three shapes depending on storage backend:
 *  - Self-hosted local: `signedUrl` + `signingData` (PUT with X-File-Signature/X-Timestamp/X-UUID).
 *  - S3 native presign: `signedUrl` only (PUT with Content-Type).
 *  - S3 POST presign: `signedUrl` + `presignedFields` (multipart POST).
 *
 * `fileUrl` is the canonical URL to persist into the survey response data.
 */
@JsonClass(generateAdapter = true)
data class UploadFileResult(
    val signedUrl: String,
    val fileUrl: String,
    val signingData: SigningDataDto? = null,
    val presignedFields: Map<String, String>? = null,
    val updatedFileName: String? = null,
)

@JsonClass(generateAdapter = true)
data class SigningDataDto(
    val signature: String,
    val timestamp: Long,
    val uuid: String,
)
