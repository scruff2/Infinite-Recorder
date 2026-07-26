package com.mark.infiniterecorder.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mark.infiniterecorder.data.SessionTimeline
import com.mark.infiniterecorder.data.SettingsRepository
import com.mark.infiniterecorder.data.SharedStorageRepository
import com.mark.infiniterecorder.model.RecorderState
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RecordingManager(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onState(state: RecorderState, detail: String = "")
        fun onSoundLevel(level: Int)
        fun onSavedDuration(durationMs: Long)
        fun onCurrentFile(name: String)
        fun onStorageChanged(bytes: Long)
        fun onStopped(error: String?)
    }

    private data class Frame(
        val pcm: ShortArray,
        val captureTimeMs: Long,
    )

    private val settings = SettingsRepository(context)
    private val storage = SharedStorageRepository(context)
    private val sessionStartMs = System.currentTimeMillis()
    private val timelineLock = Any()
    private var activeTimelineDay = localDay(sessionStartMs)
    private var activeTimeline = SessionTimeline(context, storage, sessionStartMs)
    private var segmentTimeline: SessionTimeline? = null
    private val detector = SoundActivityDetector(settings.sensitivity)
    private val running = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val pauseMonitor = Object()

    @Volatile
    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null
    private lateinit var writer: AacSegmentWriter

    private val preRoll = ArrayDeque<Frame>()
    private val pendingQuiet = ArrayDeque<Frame>()
    private var retainingSound = false
    private var suppressedStartMs: Long? = null
    private var pauseHandled = false
    private var segmentAnchorMs = sessionStartMs
    private var nextBoundaryMs = calculateNextBoundary(segmentAnchorMs)
    private var framesSinceUi = 0

    val startedAtMs: Long
        get() = sessionStartMs

    val savedDurationMs: Long
        get() = if (::writer.isInitialized) writer.savedDurationMs else 0L

    fun start() {
        check(!running.getAndSet(true)) { "Recording is already active." }
        worker = thread(name = "InfiniteRecorder-Capture", priority = Thread.MAX_PRIORITY) {
            captureLoop()
        }
    }

    fun pause() {
        if (!running.get() || paused.getAndSet(true)) return
        runCatching { audioRecord?.stop() }
        synchronized(pauseMonitor) { pauseMonitor.notifyAll() }
    }

    fun resume() {
        if (!running.get() || !paused.getAndSet(false)) return
        synchronized(pauseMonitor) { pauseMonitor.notifyAll() }
    }

    fun bookmark(label: String = "") {
        if (!running.get()) return
        val now = System.currentTimeMillis()
        timelineFor(now).bookmark(now, savedDurationMs, label)
    }

    fun stopAndWait() {
        if (!running.getAndSet(false)) return
        synchronized(pauseMonitor) { pauseMonitor.notifyAll() }
        runCatching { audioRecord?.stop() }
        worker?.join(15_000L)
    }

    @SuppressLint("MissingPermission")
    private fun captureLoop() {
        var failure: String? = null
        try {
            check(
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED,
            ) { "Microphone permission is not granted." }

            listener.onState(RecorderState.PREPARING, "Preparing microphone")
            writer = AacSegmentWriter(
                storage = storage,
                bitrate = settings.bitrate,
                initialCaptureTimeMs = sessionStartMs,
                listener = object : AacSegmentWriter.Listener {
                    override fun onSegmentStarted(
                        name: String,
                        captureTimeMs: Long,
                        audioOffsetMs: Long,
                    ) {
                        val timeline = timelineFor(captureTimeMs)
                        segmentTimeline = timeline
                        timeline.segmentStarted(name, captureTimeMs, audioOffsetMs)
                        listener.onCurrentFile(name)
                    }

                    override fun onSegmentFinished(
                        captureTimeMs: Long,
                        audioOffsetMs: Long,
                        sizeBytes: Long,
                    ) {
                        segmentTimeline?.segmentFinished(
                            captureTimeMs,
                            audioOffsetMs,
                            sizeBytes,
                        )
                        segmentTimeline = null
                        listener.onStorageChanged(storage.totalUsageBytes())
                    }
                },
            )
            val recorder = buildAudioRecord()
            audioRecord = recorder
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "Android could not start microphone capture."
            }
            listener.onStorageChanged(storage.totalUsageBytes())
            listener.onState(
                if (settings.silenceSuppression) RecorderState.LISTENING
                else RecorderState.RECORDING_SOUND,
                if (settings.silenceSuppression) "Listening for sound" else "Saving all audio",
            )

            val buffer = ShortArray(AacSegmentWriter.FRAME_SAMPLES)
            while (running.get()) {
                if (paused.get()) {
                    enterPausedState(recorder)
                    continue
                }
                if (pauseHandled) {
                    val now = System.currentTimeMillis()
                    timelineFor(now).pauseEnded(now)
                    pauseHandled = false
                    listener.onState(
                        if (settings.silenceSuppression) RecorderState.LISTENING
                        else RecorderState.RECORDING_SOUND,
                        if (settings.silenceSuppression) "Listening for sound"
                        else "Saving all audio",
                    )
                }
                if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.startRecording()
                }

                val read = recorder.read(
                    buffer,
                    0,
                    buffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (!running.get()) break
                if (paused.get()) continue
                if (read < 0) error(audioReadError(read))
                if (read == 0) continue

                val frame = Frame(buffer.copyOf(read), System.currentTimeMillis())
                processFrame(frame)
            }

            finishPendingAudio(System.currentTimeMillis())
            listener.onState(RecorderState.STOPPING, "Finalizing recording")
            writer.close()
            activeTimeline.finish(System.currentTimeMillis(), writer.savedDurationMs)
            if (writer.savedDurationMs == 0L) {
                storage.cleanupEmptyDailyMetadata()
            }
        } catch (throwable: Throwable) {
            failure = throwable.message ?: throwable.javaClass.simpleName
            runCatching {
                finishPendingAudio(System.currentTimeMillis())
                if (::writer.isInitialized) writer.close()
            }.onFailure {
                if (::writer.isInitialized) writer.abort()
            }
            runCatching { activeTimeline.error(System.currentTimeMillis(), failure) }
            runCatching {
                activeTimeline.finish(
                    System.currentTimeMillis(),
                    savedDurationMs,
                    status = "Interrupted",
                )
            }
        } finally {
            running.set(false)
            runCatching {
                val recorder = audioRecord
                if (recorder?.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
                recorder?.release()
            }
            audioRecord = null
            listener.onStorageChanged(runCatching { storage.totalUsageBytes() }.getOrDefault(0L))
            listener.onStopped(failure)
        }
    }

    private fun processFrame(frame: Frame) {
        val result = detector.analyze(frame.pcm)
        framesSinceUi++
        if (framesSinceUi >= 5) {
            framesSinceUi = 0
            listener.onSoundLevel(result.levelPercent)
            listener.onSavedDuration(writer.savedDurationMs)
        }

        if (!settings.silenceSuppression) {
            encodeFrame(frame)
            return
        }

        if (retainingSound) {
            if (result.soundDetected) {
                while (pendingQuiet.isNotEmpty()) encodeFrame(pendingQuiet.removeFirst())
                encodeFrame(frame)
                listener.onState(RecorderState.RECORDING_SOUND, "Sound detected")
            } else {
                pendingQuiet.addLast(frame)
                if (pendingQuiet.size >= SILENCE_CONFIRM_FRAMES) {
                    repeat(minOf(TRAILING_FRAMES, pendingQuiet.size)) {
                        encodeFrame(pendingQuiet.removeFirst())
                    }
                    val firstOmitted = pendingQuiet.firstOrNull()?.captureTimeMs ?: frame.captureTimeMs
                    suppressedStartMs = firstOmitted
                    preRoll.clear()
                    while (pendingQuiet.isNotEmpty()) {
                        addPreRoll(pendingQuiet.removeFirst())
                    }
                    retainingSound = false
                    listener.onState(
                        RecorderState.SILENCE_SUPPRESSED,
                        "Silence is not being saved",
                    )
                }
            }
            return
        }

        addPreRoll(frame)
        if (result.soundDetected) {
            val retainedFromMs = preRoll.firstOrNull()?.captureTimeMs ?: frame.captureTimeMs
            suppressedStartMs?.let { start ->
                timelineFor(start).suppressedSilence(start, retainedFromMs)
            }
            suppressedStartMs = null
            while (preRoll.isNotEmpty()) encodeFrame(preRoll.removeFirst())
            retainingSound = true
            listener.onState(RecorderState.RECORDING_SOUND, "Sound detected")
        } else {
            listener.onState(
                if (suppressedStartMs == null) RecorderState.LISTENING
                else RecorderState.SILENCE_SUPPRESSED,
                if (suppressedStartMs == null) "Listening for sound"
                else "Silence is not being saved",
            )
        }
    }

    private fun encodeFrame(frame: Frame) {
        rotateForCaptureTime(frame.captureTimeMs)
        writer.encode(frame.pcm, frame.captureTimeMs)
    }

    private fun rotateForCaptureTime(captureTimeMs: Long) {
        if (captureTimeMs < nextBoundaryMs) return
        writer.scheduleRotation(captureTimeMs)
        do {
            segmentAnchorMs = nextBoundaryMs
            nextBoundaryMs = calculateNextBoundary(segmentAnchorMs)
        } while (captureTimeMs >= nextBoundaryMs)
    }

    private fun enterPausedState(recorder: AudioRecord) {
        if (!pauseHandled) {
            finishPendingAudio(System.currentTimeMillis())
            preRoll.clear()
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
            val now = System.currentTimeMillis()
            timelineFor(now).pauseStarted(now)
            pauseHandled = true
            listener.onState(RecorderState.PAUSED, "Recording paused")
            listener.onSoundLevel(0)
        }
        synchronized(pauseMonitor) {
            if (running.get() && paused.get()) pauseMonitor.wait(500L)
        }
    }

    private fun finishPendingAudio(nowMs: Long) {
        if (!::writer.isInitialized) return
        if (retainingSound) {
            while (pendingQuiet.isNotEmpty()) encodeFrame(pendingQuiet.removeFirst())
        } else {
            suppressedStartMs?.let { timelineFor(it).suppressedSilence(it, nowMs) }
        }
        pendingQuiet.clear()
        preRoll.clear()
        retainingSound = false
        suppressedStartMs = null
    }

    private fun addPreRoll(frame: Frame) {
        preRoll.addLast(frame)
        while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(
            AacSegmentWriter.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "The microphone does not support 16 kHz mono PCM." }
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AacSegmentWriter.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minimum * 2, AacSegmentWriter.FRAME_SAMPLES * 8))
            .build()
    }

    private fun calculateNextBoundary(fromMs: Long): Long {
        val segmentEnd = fromMs + settings.segmentMinutes * 60_000L
        val zoned = Instant.ofEpochMilli(fromMs).atZone(ZoneId.systemDefault())
        val midnight = zoned.toLocalDate().plusDays(1).atStartOfDay(zoned.zone)
            .toInstant()
            .toEpochMilli()
        return minOf(segmentEnd, midnight)
    }

    private fun timelineFor(timeMs: Long): SessionTimeline {
        synchronized(timelineLock) {
            val day = localDay(timeMs)
            if (day != activeTimelineDay) {
                activeTimeline.finish(timeMs, savedDurationMs)
                activeTimeline = SessionTimeline(context, storage, timeMs)
                activeTimelineDay = day
            }
            return activeTimeline
        }
    }

    private fun audioReadError(code: Int): String = when (code) {
        AudioRecord.ERROR_BAD_VALUE -> "The microphone returned invalid audio data."
        AudioRecord.ERROR_DEAD_OBJECT -> "Microphone access was lost."
        AudioRecord.ERROR_INVALID_OPERATION -> "The microphone entered an invalid state."
        else -> "Microphone read failed ($code)."
    }

    companion object {
        private const val PRE_ROLL_FRAMES = 100 // About 2 seconds.
        private const val SILENCE_CONFIRM_FRAMES = 250 // About 5 seconds.
        private const val TRAILING_FRAMES = 50 // About 1 second.

        private fun localDay(timeMs: Long): String =
            Instant.ofEpochMilli(timeMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
    }
}
