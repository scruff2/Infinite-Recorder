package com.mark.infiniterecorder.audio

import kotlin.math.log10
import kotlin.math.sqrt
import java.util.ArrayDeque

class SoundActivityDetector(
    sensitivity: String,
) {
    data class Result(
        val soundDetected: Boolean,
        val levelPercent: Int,
        val dbFs: Double,
    )

    private val marginDb = when (sensitivity) {
        "Low" -> 18.0
        "High" -> 8.0
        else -> 12.0
    }
    private val absoluteFloorDb = when (sensitivity) {
        "Low" -> -38.0
        "High" -> -52.0
        else -> -45.0
    }
    private val calibrationSoundDb = when (sensitivity) {
        "Low" -> -24.0
        "High" -> -36.0
        else -> -30.0
    }

    private var noiseFloorDb = -65.0
    private var framesSeen = 0
    private var calibrationSamples = 0
    private val recentActivity = ArrayDeque<Boolean>()

    fun analyze(pcm: ShortArray): Result {
        if (pcm.isEmpty()) return Result(false, 0, -96.0)

        var sum = 0.0
        for (sample in pcm) {
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sum += normalized * normalized
        }
        val rms = sqrt(sum / pcm.size).coerceAtLeast(0.000_001)
        val db = (20.0 * log10(rms)).coerceIn(-96.0, 0.0)
        framesSeen++

        val calibrating = framesSeen <= CALIBRATION_FRAMES
        if (calibrating && db < calibrationSoundDb) {
            noiseFloorDb = if (calibrationSamples == 0) {
                db
            } else {
                noiseFloorDb * 0.9 + db * 0.1
            }.coerceIn(-80.0, -25.0)
            calibrationSamples++
        }

        val threshold = maxOf(absoluteFloorDb, noiseFloorDb + marginDb)
        val rawDetected = if (calibrating) {
            db >= calibrationSoundDb
        } else {
            db >= threshold
        }

        if (!calibrating && !rawDetected) {
            val rate = if (db < noiseFloorDb) 0.08 else 0.01
            noiseFloorDb = (noiseFloorDb * (1.0 - rate) + db * rate)
                .coerceIn(-80.0, -25.0)
        }

        recentActivity.addLast(rawDetected)
        while (recentActivity.size > ACTIVITY_WINDOW_FRAMES) recentActivity.removeFirst()
        val detected = recentActivity.count { it } >= REQUIRED_ACTIVE_FRAMES

        val percent = (((db + 72.0) / 60.0) * 100.0)
            .toInt()
            .coerceIn(0, 100)
        return Result(detected, percent, db)
    }

    companion object {
        private const val CALIBRATION_FRAMES = 50 // About one second.
        private const val ACTIVITY_WINDOW_FRAMES = 10 // About 200 ms.
        private const val REQUIRED_ACTIVE_FRAMES = 3 // Reject isolated clicks/spikes.
    }
}
