package com.yourname.womensafety.utils

import kotlin.math.sqrt

/**
 * FeatureExtractor — extracts the 17 statistical features from a 300-point
 * accelerometer/gyroscope window that [SOSDetector] expects for local TFLite inference.
 *
 * Feature order (must match model_metadata.json exactly):
 *   0  X_mean        1  X_std         2  X_max         3  X_min         4  X_sum_sq
 *   5  Y_mean        6  Y_std         7  Y_max         8  Y_min         9  Y_sum_sq
 *  10  Z_mean       11  Z_std        12  Z_max        13  Z_min        14  Z_sum_sq
 *  15  is_accelerometer (1.0 or 0.0)
 *  16  is_gyroscope     (0.0 or 1.0)
 *
 * Usage:
 *   val features = FeatureExtractor.extract(window, sensorType = "accelerometer")
 *   val probability = sosDetector.predictDanger(features)
 */
object FeatureExtractor {

    /**
     * Extracts the 17 features required by the local TFLite model.
     *
     * @param window     List of [x, y, z] triplets — raw sensor readings (ideally 300 points).
     * @param sensorType "accelerometer" (default) or "gyroscope"
     * @return FloatArray of exactly 17 features ready for normalization → inference.
     * @throws IllegalArgumentException if window is empty.
     */
    fun extract(window: List<List<Float>>, sensorType: String = "accelerometer"): FloatArray {
        require(window.isNotEmpty()) { "Sensor window must not be empty" }

        val xs = FloatArray(window.size) { window[it][0] }
        val ys = FloatArray(window.size) { window[it][1] }
        val zs = FloatArray(window.size) { window[it][2] }

        val features = FloatArray(17)

        // X-axis (indices 0–4)
        features[0] = mean(xs)
        features[1] = std(xs, features[0])
        features[2] = xs.max()
        features[3] = xs.min()
        features[4] = sumOfSquares(xs)

        // Y-axis (indices 5–9)
        features[5] = mean(ys)
        features[6] = std(ys, features[5])
        features[7] = ys.max()
        features[8] = ys.min()
        features[9] = sumOfSquares(ys)

        // Z-axis (indices 10–14)
        features[10] = mean(zs)
        features[11] = std(zs, features[10])
        features[12] = zs.max()
        features[13] = zs.min()
        features[14] = sumOfSquares(zs)

        // One-hot sensor type (indices 15–16)
        features[15] = if (sensorType.equals("accelerometer", ignoreCase = true)) 1.0f else 0.0f
        features[16] = if (sensorType.equals("gyroscope",     ignoreCase = true)) 1.0f else 0.0f

        return features
    }

    // ── Math helpers ─────────────────────────────────────────────────────────

    private fun mean(arr: FloatArray): Float {
        if (arr.isEmpty()) return 0f
        return arr.sum() / arr.size
    }

    private fun std(arr: FloatArray, mean: Float): Float {
        if (arr.size < 2) return 0f
        var variance = 0.0
        for (v in arr) {
            val diff = (v - mean).toDouble()
            variance += diff * diff
        }
        return sqrt(variance / arr.size).toFloat()
    }

    private fun sumOfSquares(arr: FloatArray): Float {
        var s = 0f
        for (v in arr) s += v * v
        return s
    }
}
