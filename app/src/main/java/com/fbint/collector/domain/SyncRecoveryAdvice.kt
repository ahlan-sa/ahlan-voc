package com.fbint.collector.domain

internal fun syncRecoveryAdvice(error: String): String = recoveryAdvice(error.removePrefix("Attachment upload: "))

private fun recoveryAdvice(error: String): String = when {
    error.startsWith("HTTP 400") || error.startsWith("HTTP 422") ->
        "Saved on this device. Ask your coordinator to review the error below. Do not collect this response again."
    error.startsWith("HTTP 401") || error.startsWith("HTTP 403") ->
        "Saved on this device. Ask your coordinator to check survey access and connection settings."
    error.startsWith("HTTP 404") ->
        "Saved on this device. Ask your coordinator to check that the original survey is still available."
    else -> "Saved on this device. Upload will retry automatically when possible. You can continue collecting."
}
