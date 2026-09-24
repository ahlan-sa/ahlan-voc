package com.fbint.collector.data.remote

import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * Builds Retrofit instances whose base URL is resolved at call time. The base URL is
 * configured per device (admin enters it during setup), so we wrap it in a lambda and rebuild
 * the [Retrofit] only when the URL changes.
 */
class FormbricksApiFactory(
    private val client: OkHttpClient,
    private val moshi: Moshi,
) {
    /**
     * Client used for the client API, which carries the non-idempotent POSTs (responses,
     * displays, storage presign). OkHttp's default `retryOnConnectionFailure` transparently
     * re-sends a request on a recoverable connection failure — for `POST /responses` that
     * silently creates a second response whenever the server already processed the first
     * attempt. That retry happens inside a single call, below `queued_responses.sendingAt`,
     * so the in-flight marker cannot see it; stopping OkHttp from retrying is the only fix.
     *
     * Retries stay enabled on [client] for the management API, whose GETs are idempotent and
     * benefit from them on flaky venue Wi-Fi. `newBuilder` shares the connection pool and
     * dispatcher, so the extra client costs nothing.
     */
    private val noRetryClient: OkHttpClient by lazy {
        client.newBuilder().retryOnConnectionFailure(false)
            .callTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .eventListenerFactory { call -> UploadProgressListener(call.request().tag(UploadTrace::class.java)) }
            .addInterceptor(UnsentRequestInterceptor())
            .build()
    }

    private var managementCacheUrl: String? = null
    private var managementCache: FormbricksManagementApi? = null
    private var clientCacheUrl: String? = null
    private var clientCache: FormbricksClientApi? = null

    @Synchronized
    fun management(baseUrlProvider: () -> String): FormbricksManagementApi {
        val url = baseUrlProvider().normalizeBaseUrl()
        if (managementCacheUrl != url || managementCache == null) {
            managementCacheUrl = url
            managementCache = build(url, client).create(FormbricksManagementApi::class.java)
        }
        return managementCache!!
    }

    @Synchronized
    fun client(baseUrlProvider: () -> String): FormbricksClientApi {
        val url = baseUrlProvider().normalizeBaseUrl()
        if (clientCacheUrl != url || clientCache == null) {
            clientCacheUrl = url
            clientCache = build(url, noRetryClient, trackUpload = true).create(FormbricksClientApi::class.java)
        }
        return clientCache!!
    }

    private fun build(baseUrl: String, httpClient: OkHttpClient, trackUpload: Boolean = false): Retrofit = Retrofit.Builder()
        .baseUrl(baseUrl.toHttpUrl())
        .callFactory(okhttp3.Call.Factory { request ->
            httpClient.newCall(if (trackUpload) request.newBuilder().tag(UploadTrace::class.java, UploadTrace()).build() else request)
        })
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private fun String.normalizeBaseUrl(): String = if (endsWith("/")) this else "$this/"
}
