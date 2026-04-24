package com.yourname.womensafety.data.network.api

import com.yourname.womensafety.data.network.dto.*
import retrofit2.Response
import retrofit2.http.*

interface SosApiService {

    @POST("sos/trigger")
    suspend fun triggerSos(
        @Body request: SosTriggerRequest
    ): Response<ApiResponse<SosAlertData>>

    @POST("sos/send-now")
    suspend fun sendSosNow(
        @Body request: SosSendNowRequest
    ): Response<ApiResponse<Unit>>   // backend returns delivery_report, not data

    @POST("sos/cancel")
    suspend fun cancelSos(
        @Body request: SosCancelRequest
    ): Response<ApiResponse<Unit>>

    @POST("sos/safe")
    suspend fun markUserSafe(
        @Body request: SosSafeRequest
    ): Response<ApiResponse<SosSafeData>>

    @GET("sos/history")
    suspend fun getSosHistory(): Response<ApiResponse<List<SosHistoryItem>>>

    @GET("sos/countdown/{alertId}")
    suspend fun getSosCountdown(
        @Path("alertId") alertId: String
    ): Response<ApiResponse<SosCountdownData>>

    @POST("sos/test-whatsapp")
    suspend fun testWhatsApp(
        @Body request: TestWhatsAppRequest
    ): Response<ApiResponse<Unit>>
}
