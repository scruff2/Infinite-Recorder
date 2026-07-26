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
        var result = detector.analyze(pcm)
        repeat(2) { result = detector.analyze(pcm) }

        assertTrue(result.soundDetected)
        assertTrue(result.levelPercent > 40)
    }

    @Test
    fun sensitivityChangesQuietSoundClassification() {
        val quietPcm = ShortArray(320) { index ->
            (sin(index * 2.0 * PI / 40.0) * 170.0).toInt().toShort()
        }
        val low = SoundActivityDetector("Low")
        val high = SoundActivityDetector("High")
        repeat(50) {
            low.analyze(ShortArray(320))
            high.analyze(ShortArray(320))
        }

        var lowResult = low.analyze(quietPcm)
        var highResult = high.analyze(quietPcm)
        repeat(2) {
            lowResult = low.analyze(quietPcm)
            highResult = high.analyze(quietPcm)
        }

        assertFalse(lowResult.soundDetected)
        assertTrue(highResult.soundDetected)
    }

    @Test
    fun isolatedLoudSpikeIsRejected() {
        val detector = SoundActivityDetector("Medium")
        repeat(50) { detector.analyze(ShortArray(320)) }
        val spike = ShortArray(320) { index ->
            (sin(index * 2.0 * PI / 40.0) * 10_000.0).toInt().toShort()
        }

        assertFalse(detector.analyze(spike).soundDetected)
        assertFalse(detector.analyze(ShortArray(320)).soundDetected)
    }

    @Test
    fun steadyRoomNoiseIsLearnedDuringCalibration() {
        val detector = SoundActivityDetector("Medium")
        val background = ShortArray(320) { index ->
            (sin(index * 2.0 * PI / 40.0) * 460.0).toInt().toShort()
        }
        var result = detector.analyze(background)
        repeat(59) { result = detector.analyze(background) }

        assertFalse(result.soundDetected)

        val speech = ShortArray(320) { index ->
            (sin(index * 2.0 * PI / 40.0) * 2_500.0).toInt().toShort()
        }
        repeat(3) { result = detector.analyze(speech) }
        assertTrue(result.soundDetected)
    }
}
