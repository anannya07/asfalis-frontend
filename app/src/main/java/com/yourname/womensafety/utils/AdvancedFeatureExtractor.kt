package com.yourname.womensafety.utils

import com.yourname.womensafety.data.network.dto.SensorReading
import com.yourname.womensafety.data.network.dto.SensorTrainingRequest
import kotlin.math.sqrt

/**
 * AdvancedFeatureExtractor — computes the 39 statistical features exactly
 * as expected by the backend's POST /api/protection/collect endpoint.
 */
object AdvancedFeatureExtractor {

    fun extractToTrainingRequest(
        window: List<SensorReading>,
        sensorType: String,
        datasetName: String?,
        dangerLabel: Int,
        motionDescription: String?
    ): SensorTrainingRequest {
        
        if (window.isEmpty()) {
            return fallbackEmptyRequest(sensorType, datasetName, dangerLabel, motionDescription)
        }

        val xs = window.map { it.x }.toFloatArray()
        val ys = window.map { it.y }.toFloatArray()
        val zs = window.map { it.z }.toFloatArray()
        val mags = window.map { sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }.toFloatArray()

        return SensorTrainingRequest(
            sensorType = sensorType,
            datasetName = datasetName,
            dangerLabel = dangerLabel,
            motionDescription = motionDescription,

            xMean = mean(xs), xStd = std(xs), xMin = xs.minOrNull() ?: 0f, xMax = xs.maxOrNull() ?: 0f, 
            xRange = (xs.maxOrNull() ?: 0f) - (xs.minOrNull() ?: 0f), xMedian = median(xs), xIqr = iqr(xs), xRms = rms(xs),

            yMean = mean(ys), yStd = std(ys), yMin = ys.minOrNull() ?: 0f, yMax = ys.maxOrNull() ?: 0f, 
            yRange = (ys.maxOrNull() ?: 0f) - (ys.minOrNull() ?: 0f), yMedian = median(ys), yIqr = iqr(ys), yRms = rms(ys),

            zMean = mean(zs), zStd = std(zs), zMin = zs.minOrNull() ?: 0f, zMax = zs.maxOrNull() ?: 0f, 
            zRange = (zs.maxOrNull() ?: 0f) - (zs.minOrNull() ?: 0f), zMedian = median(zs), zIqr = iqr(zs), zRms = rms(zs),

            magMean = mean(mags), magStd = std(mags), magMin = mags.minOrNull() ?: 0f, magMax = mags.maxOrNull() ?: 0f, 
            magRange = (mags.maxOrNull() ?: 0f) - (mags.minOrNull() ?: 0f), magMedian = median(mags), magIqr = iqr(mags), magRms = rms(mags),

            xyCorr = correlation(xs, ys),
            xzCorr = correlation(xs, zs),
            yzCorr = correlation(ys, zs)
        )
    }

    private fun mean(arr: FloatArray): Float {
        if (arr.isEmpty()) return 0f
        return arr.average().toFloat()
    }

    private fun std(arr: FloatArray): Float {
        if (arr.size < 2) return 0f
        val m = mean(arr)
        val variance = arr.map { (it - m) * (it - m) }.average().toFloat()
        return sqrt(variance)
    }

    private fun median(arr: FloatArray): Float {
        if (arr.isEmpty()) return 0f
        val sorted = arr.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2f
        } else {
            sorted[mid]
        }
    }

    private fun iqr(arr: FloatArray): Float {
        if (arr.size < 4) return 0f
        val sorted = arr.sortedArray()
        val q1Index = sorted.size / 4
        val q3Index = (sorted.size * 3) / 4
        return sorted[q3Index] - sorted[q1Index]
    }

    private fun rms(arr: FloatArray): Float {
        if (arr.isEmpty()) return 0f
        val meanSquare = arr.map { it * it }.average().toFloat()
        return sqrt(meanSquare)
    }

    private fun correlation(x: FloatArray, y: FloatArray): Float {
        if (x.size != y.size || x.size < 2) return 0f
        val meanX = mean(x)
        val meanY = mean(y)

        var covariance = 0f
        var varX = 0f
        var varY = 0f

        for (i in x.indices) {
            val dx = x[i] - meanX
            val dy = y[i] - meanY
            covariance += dx * dy
            varX += dx * dx
            varY += dy * dy
        }

        if (varX == 0f || varY == 0f) return 0f
        return (covariance / sqrt(varX * varY))
    }

    private fun fallbackEmptyRequest(sensorType: String, datasetName: String?, dangerLabel: Int, motionDescription: String?): SensorTrainingRequest {
        return SensorTrainingRequest(
            sensorType = sensorType, datasetName = datasetName, dangerLabel = dangerLabel, motionDescription = motionDescription,
            xMean = 0f, xStd = 0f, xMin = 0f, xMax = 0f, xRange = 0f, xMedian = 0f, xIqr = 0f, xRms = 0f,
            yMean = 0f, yStd = 0f, yMin = 0f, yMax = 0f, yRange = 0f, yMedian = 0f, yIqr = 0f, yRms = 0f,
            zMean = 0f, zStd = 0f, zMin = 0f, zMax = 0f, zRange = 0f, zMedian = 0f, zIqr = 0f, zRms = 0f,
            magMean = 0f, magStd = 0f, magMin = 0f, magMax = 0f, magRange = 0f, magMedian = 0f, magIqr = 0f, magRms = 0f,
            xyCorr = 0f, xzCorr = 0f, yzCorr = 0f
        )
    }
}
