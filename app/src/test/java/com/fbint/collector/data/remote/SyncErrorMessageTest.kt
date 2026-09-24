package com.fbint.collector.data.remote

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class SyncErrorMessageTest {
    private fun message(body: String) = syncErrorMessage(
        HttpException(Response.error<Any>(400, body.toResponseBody()))
    )

    @Test fun serverMessageIsVisibleWithoutDumpingOtherFields() {
        assertEquals("HTTP 400: Invalid file upload response", message(
            """{"message":"Invalid file upload response","data":{"answer":"private answer"}}"""
        ))
    }

    @Test fun malformedAndOversizeErrorsFallBackToStatus() {
        assertEquals("HTTP 400", message("<html>proxy error</html>"))
        assertEquals("HTTP 400", message("x".repeat(20_000)))
    }

    @Test fun credentialsAreRedactedAndTextIsBounded() {
        val text = message("""{"message":"Error fbk_secret123 ${"x".repeat(2000)}"}""")
        assertFalse(text.contains("fbk_secret123"))
        assertTrue(text.contains("[redacted]"))
        assertEquals(1200, text.length)
    }
}
