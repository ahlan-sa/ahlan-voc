package com.fbint.collector.data.remote

import java.io.IOException
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Interceptor

/** Kept for the entire call, including redirects: a later DNS error is not proof of no upload. */
internal class UploadTrace {
    @Volatile var requestStarted = false
}

class RequestNotSentException(cause: IOException) : IOException(cause.message, cause)

internal class UploadProgressListener(private val trace: UploadTrace?) : EventListener() {
    override fun requestHeadersStart(call: Call) { trace?.requestStarted = true }
}

internal class UnsentRequestInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val trace = chain.request().tag(UploadTrace::class.java)
        try { return chain.proceed(chain.request()) }
        catch (error: IOException) {
            if (trace != null && !trace.requestStarted) throw RequestNotSentException(error)
            throw error
        }
    }
}
