package com.yourname.womensafety.utils

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.nio.FloatBuffer

private const val TAG = "SOSDetector"

/**
 * Scaler parameters loaded from scaler_params.json.
 * Normalization formula: normalized = (raw - mean) * scale
 * (Note: this MULTIPLIES by scale, not divides — as per backend's Android_Integration_Guide.md)
 */
data class ScalerParams(
    @SerializedName("mean")  val mean: List<Float>,
    @SerializedName("scale") val scale: List<Float>
)

/**
 * SOSDetector — On-device danger detection using the LightGBM ONNX model.
 *
 * Model: asfalis_sos_lgb.onnx
 * Scaler: scaler_params.json
 * Input:  17-feature vector [shape: 1 x 17]
 * Output: Probability array — index [1] = P(DANGER)
 *
 * Thresholds (from Android_Integration_Guide.md):
 *   < 0.50 → Safe
 *   0.50 – 0.85 → Caution
 *   > 0.85 → CRITICAL — trigger SOS
 */
class SOSDetector(private val assetManager: AssetManager) {

    companion object {
        /** Danger probability above this triggers the SOS countdown. */
        const val DANGER_THRESHOLD = 0.85f
        private const val MODEL_FILE   = "asfalis_sos_lgb.onnx"
        private const val SCALER_FILE  = "scaler_params.json"
        private const val INPUT_NAME   = "input"
        private const val FEATURE_COUNT = 17
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    val scalerParams: ScalerParams

    init {
        // Load scaler params
        val json = assetManager.open(SCALER_FILE).bufferedReader().use { it.readText() }
        scalerParams = Gson().fromJson(json, ScalerParams::class.java)

        // Load ONNX model
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val modelBytes = assetManager.open(MODEL_FILE).readBytes()
            ortSession = ortEnv!!.createSession(modelBytes, OrtSession.SessionOptions())
            Log.i(TAG, "ONNX session created successfully (model=$MODEL_FILE)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ONNX model: ${e.message}", e)
            ortSession = null
            ortEnv = null
        }
    }

    /**
     * Receives 17 raw features extracted from a 300-point sensor window.
     * Applies Z-score normalization then runs ONNX inference.
     *
     * @return Probability of DANGER (0.0 to 1.0), or 0.0 if model is unavailable.
     */
    fun predictDanger(rawFeatures: FloatArray): Float {
        val env     = ortEnv     ?: return 0.0f
        val session = ortSession ?: return 0.0f

        require(rawFeatures.size == FEATURE_COUNT) {
            "Expected $FEATURE_COUNT features, got ${rawFeatures.size}"
        }

        val means  = scalerParams.mean
        val scales = scalerParams.scale

        // Normalize: (raw - mean) * scale  [MULTIPLY — not divide]
        val normalized = FloatArray(FEATURE_COUNT) { i ->
            (rawFeatures[i] - means[i]) * scales[i]
        }

        return try {
            // Shape: [1, 17]
            val shape = longArrayOf(1, FEATURE_COUNT.toLong())
            val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(normalized), shape)

            val results = session.run(mapOf(INPUT_NAME to tensor))

            // Output at index 1 is the probability array (zipmap=False)
            // outputProbas[0][1] = P(DANGER class)
            val outputProbas = results[1].value as Array<FloatArray>
            val dangerProb = outputProbas[0][1]

            Log.d(TAG, "ONNX inference: P(DANGER)=%.4f, P(SAFE)=%.4f".format(dangerProb, outputProbas[0][0]))

            tensor.close()
            results.close()

            dangerProb
        } catch (e: Exception) {
            Log.e(TAG, "ONNX inference failed: ${e.message}", e)
            0.0f
        }
    }

    /**
     * Returns true if the danger probability exceeds the hardcoded threshold (0.85).
     */
    fun shouldTriggerSOS(probability: Float): Boolean = probability >= DANGER_THRESHOLD

    /**
     * Release ONNX resources. Call this when the monitoring service stops.
     */
    fun close() {
        try {
            ortSession?.close()
            ortEnv?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX session: ${e.message}")
        } finally {
            ortSession = null
            ortEnv = null
        }
    }
}
