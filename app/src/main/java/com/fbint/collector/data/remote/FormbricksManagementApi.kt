package com.fbint.collector.data.remote

import com.fbint.collector.data.remote.dto.MeDto
import com.fbint.collector.data.remote.dto.SurveyEnvelope
import com.fbint.collector.data.remote.dto.SurveyListEnvelope
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import retrofit2.http.Path

interface FormbricksManagementApi {
    @GET("api/v1/management/responses")
    suspend fun listResponses(
        @Header("x-api-key") apiKey: String,
        @retrofit2.http.Query("surveyId") surveyId: String,
        @retrofit2.http.Query("limit") limit: Int,
        @retrofit2.http.Query("skip") skip: Int,
    ): com.fbint.collector.data.remote.dto.ResponseHistoryEnvelope


    @GET("api/v1/management/me")
    suspend fun me(@Header("x-api-key") apiKey: String): MeDto

    @GET("api/v1/management/surveys")
    suspend fun listSurveys(@Header("x-api-key") apiKey: String): SurveyListEnvelope

    @GET("api/v1/management/surveys/{id}")
    suspend fun getSurvey(
        @Header("x-api-key") apiKey: String,
        @Path("id") id: String,
    ): SurveyEnvelope

    /**
     * Partial update — used by the app to register the auto-stamped hidden field IDs without
     * touching anything else. Returns Response so we can detect the 401 (read-only key) cleanly.
     */
    @PUT("api/v1/management/surveys/{id}")
    suspend fun updateSurvey(
        @Header("x-api-key") apiKey: String,
        @Path("id") id: String,
        @Body body: Map<String, Any?>,
    ): Response<Unit>
}
