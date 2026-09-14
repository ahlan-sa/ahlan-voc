package com.fbint.collector.ui.setup

/** Stop only after setup accepts a code; unrelated QR codes must not lock the scanner. */
internal class QrScanGate {
    @Volatile var consumed: Boolean = false
        private set

    fun offer(payload: String, accept: (String) -> Boolean) {
        if (!consumed) consumed = accept(payload)
    }
}
