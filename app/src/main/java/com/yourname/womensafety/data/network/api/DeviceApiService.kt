package com.yourname.womensafety.data.network.api

import com.yourname.womensafety.data.network.dto.*
import retrofit2.Response
import retrofit2.http.*

interface DeviceApiService {

    @POST("device/register")
    suspend fun registerDevice(
        @Body request: RegisterDeviceRequest
    ): Response<ApiResponse<DeviceData>>

    @GET("device/status")
    suspend fun getDeviceStatus(): Response<ApiResponse<DeviceData>>

    @PUT("device/{id}/status")
    suspend fun updateDeviceStatus(
        @Path("id") deviceId: String,
        @Body request: UpdateDeviceStatusRequest
    ): Response<ApiResponse<DeviceData>>

    @DELETE("device/{id}")
    suspend fun removeDevice(
        @Path("id") deviceId: String
    ): Response<ApiResponse<Unit>>

    @POST("device/button-event")
    suspend fun sendButtonEvent(
        @Body request: DeviceButtonEventRequest
    ): Response<ApiResponse<Unit>>
    
    @POST("device/alert")
    suspend fun sendDeviceAlert(
        @Body request: DeviceAlertRequest
    ): Response<ApiResponse<Unit>>
}
