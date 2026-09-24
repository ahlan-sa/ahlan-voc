package com.fbint.collector.ui.runner

import android.net.Uri

/**
 * Bridge handed to file-upload composables so they can copy a picked URI into the queue without
 * pulling repository dependencies into the composable layer. Implemented by the runner
 * ViewModel.
 */
interface FileUploadDelegate {
    fun setFileImportInProgress(active: Boolean) {}
    /** Copy the picked URI into private storage; return the placeholder for the answer list. */
    suspend fun ingestFile(uri: Uri, questionId: String, suggestedName: String?): String
}
