package com.yourname.womensafety.data.repository

import com.yourname.womensafety.data.network.api.ProtectionApiService
import com.yourname.womensafety.data.network.dto.*

class ProtectionRepository(
    private val protectionApi: ProtectionApiService
) : BaseRepository() {

    suspend fun getProtectionStatus(): NetworkResult<ProtectionStatus> {
        return safeApiCall { protectionApi.getProtectionStatus() }
    }

    suspend fun toggleProtection(isActive: Boolean): NetworkResult<ProtectionStatus> {
        return safeApiCall { protectionApi.toggleProtection(ToggleProtectionRequest(isActive)) }
    }

    /**
     * Submit a raw sensor window to the backend ML model for prediction.
     * Send 300 readings for best accuracy. GPS coordinates are optional but preferred.
     *
     * @param window     List of [x, y, z] triplets — raw accelerometer/gyroscope values.
     * @param sensorType "accelerometer" (default) or "gyroscope"
     * @param latitude   Optional GPS latitude
     * @param longitude  Optional GPS longitude
     */
    suspend fun predict(
        window: List<List<Float>>,
        sensorType: String = "accelerometer",
        latitude: Double? = null,
        longitude: Double? = null
    ): NetworkResult<PredictionResult> {
        return safeApiCall {
            protectionApi.predict(SensorWindowRequest(window, sensorType, latitude, longitude))
        }
    }

    /**
     * Collect a labeled training window so the backend can retrain the model.
     * The frontend now extracts 39 statistical features natively (matching labeled_windows.csv)
     * via the AdvancedFeatureExtractor to satisfy the backend's strict Pydantic parsing.
     *
     * @param window           Raw [SensorReading] list (300 readings @ 50 Hz = 6 seconds).
     * @param label            "safe" or "danger"
     * @param datasetName      Optional annotation label, e.g. "fast_walking"
     * @param motionDescription Optional human description, e.g. "SAFE — Fast Walking"
     */
    suspend fun collectLabeledWindow(
        window: List<SensorReading>,
        label: String,
        datasetName: String? = null,
        motionDescription: String? = null
    ): NetworkResult<Unit> {
        val dangerLabel = if (label.lowercase() == "danger") 1 else 0
        
        val trainingRequest = com.yourname.womensafety.utils.AdvancedFeatureExtractor.extractToTrainingRequest(
            window = window,
            sensorType = datasetName ?: "accelerometer",
            datasetName = datasetName,
            dangerLabel = dangerLabel,
            motionDescription = motionDescription
        )

        return safeApiCall {
            protectionApi.collectData(trainingRequest)
        }
    }

    /**
     * Submit feedback after an Auto SOS resolves.
     * Must always be called once per auto-triggered alert to re-label training data.
     *
     * @param alertId      The alert_id received from predict()
     * @param isFalseAlarm true = false alarm (cancelled / I'm safe), false = genuine danger
     */
    suspend fun submitFeedback(alertId: String, isFalseAlarm: Boolean): NetworkResult<Unit> {
        return safeApiCall {
            protectionApi.submitFeedback(alertId, FeedbackRequest(isFalseAlarm))
        }
    }

    /**
     * Trigger background ML model retraining.
     * Requires at least one SAFE and one DANGER window to have been collected first.
     */
    suspend fun trainModel(): NetworkResult<Unit> {
        return safeApiCall { protectionApi.trainModel() }
    }
}
