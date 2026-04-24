package com.yourname.womensafety.data.network.dto

import com.google.gson.annotations.SerializedName

data class ToggleProtectionRequest(
    @SerializedName("is_active") val isActive: Boolean
)

data class ProtectionStatus(
    @SerializedName("is_active") val isActive: Boolean,
    @SerializedName("activated_at") val activatedAt: String? = null,
    @SerializedName("monitoring_duration_minutes") val monitoringDurationMinutes: Int? = null,
    @SerializedName("bracelet_connected") val braceletConnected: Boolean = false
)

data class SensorDataRequest(
    @SerializedName("sensor_type") val sensorType: String,
    @SerializedName("data") val data: List<SensorReading>,
    @SerializedName("sensitivity") val sensitivity: String
)

data class SensorReading(
    @SerializedName("x") val x: Float,
    @SerializedName("y") val y: Float,
    @SerializedName("z") val z: Float,
    @SerializedName("timestamp") val timestamp: Long
)

/** Response from POST /protection/sensor-data */
data class SensorAnalysisResult(
    @SerializedName("alert_triggered") val alertTriggered: Boolean,
    @SerializedName("alert_id") val alertId: String? = null,
    @SerializedName("confidence") val confidence: Float? = null,
    @SerializedName("trigger_reason") val triggerReason: String? = null,
    @SerializedName("countdown_seconds") val countdownSeconds: Int? = null
)

/** For POST /protection/predict — ML danger prediction via raw [x,y,z] window. */
data class SensorWindowRequest(
    /** List of [x, y, z] triplets. Send 300 readings for best accuracy. */
    @SerializedName("window") val window: List<List<Float>>,
    /** "accelerometer" or "gyroscope" */
    @SerializedName("sensor_type") val sensorType: String = "accelerometer",
    /** Optional GPS location for context */
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null
)

/** Response from POST /protection/predict */
data class PredictionResult(
    @SerializedName("prediction") val prediction: Int = 0,
    @SerializedName("confidence") val confidence: Float = 0f,
    @SerializedName("sensor_type") val sensorType: String? = null,
    @SerializedName("sos_sent") val sosSent: Boolean = false,
    /** Present when sos_sent = true. Persist immediately — needed for countdown and feedback. */
    @SerializedName("alert_id") val alertId: String? = null,
    @SerializedName("trigger_reason") val triggerReason: String? = null,
    @SerializedName("countdown_seconds") val countdownSeconds: Int? = null,
    @SerializedName("message") val message: String? = null,
    /** Present when rate-limited. Seconds to wait before Auto SOS can trigger again. */
    @SerializedName("retry_after_seconds") val retryAfterSeconds: Int? = null
)

/** POST /api/protection/feedback/<alert_id> */
data class FeedbackRequest(
    @SerializedName("is_false_alarm") val isFalseAlarm: Boolean
)

/**
 * POST /protection/collect — Labeled training window.
 * The backend expects 39 extracted statistical features (matching labeled_windows.csv).
 * label: 0 = safe, 1 = danger
 */
data class SensorTrainingRequest(
    @SerializedName("sensor_type") val sensorType: String,
    @SerializedName("dataset_name") val datasetName: String? = null,
    @SerializedName("danger_label") val dangerLabel: Int, // 0 = safe, 1 = danger
    @SerializedName("motion_description") val motionDescription: String? = null,

    // X-axis
    @SerializedName("x_mean") val xMean: Float,
    @SerializedName("x_std") val xStd: Float,
    @SerializedName("x_min") val xMin: Float,
    @SerializedName("x_max") val xMax: Float,
    @SerializedName("x_range") val xRange: Float,
    @SerializedName("x_median") val xMedian: Float,
    @SerializedName("x_iqr") val xIqr: Float,
    @SerializedName("x_rms") val xRms: Float,

    // Y-axis
    @SerializedName("y_mean") val yMean: Float,
    @SerializedName("y_std") val yStd: Float,
    @SerializedName("y_min") val yMin: Float,
    @SerializedName("y_max") val yMax: Float,
    @SerializedName("y_range") val yRange: Float,
    @SerializedName("y_median") val yMedian: Float,
    @SerializedName("y_iqr") val yIqr: Float,
    @SerializedName("y_rms") val yRms: Float,

    // Z-axis
    @SerializedName("z_mean") val zMean: Float,
    @SerializedName("z_std") val zStd: Float,
    @SerializedName("z_min") val zMin: Float,
    @SerializedName("z_max") val zMax: Float,
    @SerializedName("z_range") val zRange: Float,
    @SerializedName("z_median") val zMedian: Float,
    @SerializedName("z_iqr") val zIqr: Float,
    @SerializedName("z_rms") val zRms: Float,

    // Magnitude
    @SerializedName("mag_mean") val magMean: Float,
    @SerializedName("mag_std") val magStd: Float,
    @SerializedName("mag_min") val magMin: Float,
    @SerializedName("mag_max") val magMax: Float,
    @SerializedName("mag_range") val magRange: Float,
    @SerializedName("mag_median") val magMedian: Float,
    @SerializedName("mag_iqr") val magIqr: Float,
    @SerializedName("mag_rms") val magRms: Float,

    // Correlations
    @SerializedName("xy_corr") val xyCorr: Float,
    @SerializedName("xz_corr") val xzCorr: Float,
    @SerializedName("yz_corr") val yzCorr: Float
)
