package com.mark.infiniterecorder.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SoundActivityDetectorTest {
    @Test
    fun silenceIsNotDetectedAsSound() {
        val detector = SoundActivityDetector("Medium")
        val result = detector.analyze(ShortArray(320))

        assertFalse(result.soundDetected)
        assertTrue(result.levelPercent <= 5)
    }

    @Test
    fun normalSpeechLevelSignalIsDetected() {
        val detector = SoundActivityDetector("Medium")
        val pcm = ShortArray(320) { index ->
            (sin(index * 2.0 * PI / 40.0) * 8_000.0).toInt().toShort()
        }
        val result = detector.analyze(pcm)

        assertTrue(result.soundDetected)
        assertTrue(result.levelPercent > 40)
    }

    @Test
    fun sensitivityChangesQuietSoundClassification() {
        val quietPcm = ShortArray(320) { index ->
            (sin(index * 2.0 * PI / 40.0) * 170.0).toInt().toShort()
        }

        assertFalse(SoundActivityDetector("Low").analyze(quietPcm).soundDetected)
        assertTrue(SoundActivityDetector("High").analyze(quietPcm).soundDetected)
    }
}
