package com.yourname.womensafety.utils

import android.content.res.AssetManager
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.channels.FileChannel

data class ModelMetadata(
    @SerializedName("model_name") val modelName: String,
    @SerializedName("recommended_threshold") val recommendedThreshold: Float,
    @SerializedName("input") val input: ModelInput
)

data class ModelInput(
    @SerializedName("normalization") val normalization: ModelNormalization
)

data class ModelNormalization(
    @SerializedName("mean") val mean: List<Float>,
    @SerializedName("scale") val scale: List<Float>
)

class SOSDetector(private val assetManager: AssetManager) {

    private var interpreter: Interpreter
    val metadata: ModelMetadata

    init {
        // Load metadata
        val jsonString = assetManager.open("model_metadata.json").bufferedReader().use { it.readText() }
        metadata = Gson().fromJson(jsonString, ModelMetadata::class.java)

        // Load TFLite Model
        val model = loadModelFile(assetManager, "auto_sos_mobile.tflite")
        interpreter = Interpreter(model)
    }

    private fun loadModelFile(assetManager: AssetManager, modelPath: String): java.nio.MappedByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        return fileChannel.map(
            FileChannel.MapMode.READ_ONLY,
            fileDescriptor.startOffset,
            fileDescriptor.declaredLength
        )
    }

    /**
     * Receives 17 raw features.
     * Normalizes them based on metadata, then runs inference.
     * Returns the probability of Danger (0.0 to 1.0).
     */
    fun predictDanger(rawFeatures: FloatArray): Float {
        if (rawFeatures.size != 17) {
            throw IllegalArgumentException("Expected 17 features, got ${rawFeatures.size}")
        }

        val means = metadata.input.normalization.mean
        val scales = metadata.input.normalization.scale

        val normalizedFeatures = FloatArray(17)
        for (i in 0 until 17) {
            normalizedFeatures[i] = (rawFeatures[i] - means[i]) / scales[i]
        }

        val input = arrayOf(normalizedFeatures) // Shape: [1, 17]
        val output = arrayOf(FloatArray(1)) // Shape: [1, 1]

        interpreter.run(input, output)
        
        return output[0][0]
    }
    
    /**
     * Helper method to determine if SOS should be triggered based on recommended threshold.
     */
    fun shouldTriggerSOS(probability: Float): Boolean {
        return probability >= metadata.recommendedThreshold
    }
}
