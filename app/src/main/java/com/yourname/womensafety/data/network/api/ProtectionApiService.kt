package com.yourname.womensafety.data.network.api

import com.yourname.womensafety.data.network.dto.*
import retrofit2.Response
import retrofit2.http.*

interface ProtectionApiService {

    @POST("protection/toggle")
    suspend fun toggleProtection(
        @Body request: ToggleProtectionRequest
    ): Response<ApiResponse<ProtectionStatus>>

    @GET("protection/status")
    suspend fun getProtectionStatus(): Response<ApiResponse<ProtectionStatus>>

    @POST("protection/sensor-data")
    suspend fun sendSensorData(
        @Body request: SensorDataRequest
    ): Response<ApiResponse<SensorAnalysisResult>>

    /**
     * ML danger prediction from a pre-windowed raw [x,y,z] reading list.
     * Send 300 readings for best accuracy. GPS coordinates are optional but preferred.
     */
    @POST("protection/predict")
    suspend fun predict(
        @Body request: SensorWindowRequest
    ): Response<ApiResponse<PredictionResult>>

    /**
     * Submit true/false alarm feedback after an Auto SOS resolves.
     * Re-labels the sensor data captured at alert time for next model training run.
     */
    @POST("protection/feedback/{alertId}")
    suspend fun submitFeedback(
        @Path("alertId") alertId: String,
        @Body request: FeedbackRequest
    ): Response<ApiResponse<Unit>>

    /**
     * Collect a labeled training window for ML retraining.
     * Send the raw 300-point window — the backend extracts the 39 features.
     * label: 0 = safe, 1 = danger
     */
    @POST("protection/collect")
    suspend fun collectData(
        @Body request: SensorTrainingRequest
    ): Response<ApiResponse<Unit>>

    /**
     * Trigger background model retraining.
     * Requires at least one SAFE and one DANGER window to have been collected.
     */
    @POST("protection/train-model")
    suspend fun trainModel(): Response<ApiResponse<Unit>>
}
