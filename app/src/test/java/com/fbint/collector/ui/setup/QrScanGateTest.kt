package com.fbint.collector.ui.setup

import org.junit.Assert.*
import org.junit.Test

class QrScanGateTest {
    @Test fun rejectedCodeDoesNotPreventNextSetupCode() {
        val gate = QrScanGate()
        val attempted = mutableListOf<String>()
        val accept: (String) -> Boolean = { attempted.add(it); it == "valid-setup" }
        gate.offer("unrelated-qr", accept)
        assertFalse(gate.consumed)
        gate.offer("valid-setup", accept)
        assertTrue(gate.consumed)
        gate.offer("valid-setup", accept)
        assertEquals(listOf("unrelated-qr", "valid-setup"), attempted)
    }
}
