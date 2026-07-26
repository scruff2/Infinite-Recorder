package com.mark.infiniterecorder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.mark.infiniterecorder.data.SharedStorageRepository
import java.nio.ByteOrder
import java.util.ArrayDeque

class AacSegmentWriter(
    private val storage: SharedStorageRepository,
    bitrate: Int,
    private val initialCaptureTimeMs: Long,
    private val listener: Listener,
) {
    interface Listener {
        fun onSegmentStarted(name: String, captureTimeMs: Long, audioOffsetMs: Long)
        fun onSegmentFinished(captureTimeMs: Long, audioOffsetMs: Long, sizeBytes: Long)
    }

    private data class Rotation(
        val audioPtsUs: Long,
        val captureTimeMs: Long,
    )

    private val codec = MediaCodec.createEncoderByType(MIME_TYPE)
    private val info = MediaCodec.BufferInfo()
    private val rotations = ArrayDeque<Rotation>()

    private var outputFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var muxerTrack = -1
    private var outputTarget: SharedStorageRepository.OutputTarget? = null
    private var segmentCaptureTimeMs = initialCaptureTimeMs
    private var segmentStartPtsUs = 0L
    private var lastCaptureTimeMs = initialCaptureTimeMs
    private var segmentSampleCount = 0
    private var totalInputSamples = 0L
    private var sawEndOfStream = false
    private var closed = false

    val nextInputPtsUs: Long
        get() = totalInputSamples * 1_000_000L / SAMPLE_RATE

    val savedDurationMs: Long
        get() = totalInputSamples * 1_000L / SAMPLE_RATE

    init {
        val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, CHANNEL_COUNT).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    fun scheduleRotation(captureTimeMs: Long) {
        val point = nextInputPtsUs
        if (rotations.lastOrNull()?.audioPtsUs == point) {
            rotations.removeLast()
        }
        rotations.addLast(Rotation(point, captureTimeMs))
    }

    fun encode(pcm: ShortArray, captureTimeMs: Long) {
        check(!closed) { "AAC writer is closed." }
        lastCaptureTimeMs = captureTimeMs
        var queued = false
        var attempts = 0
        while (!queued && attempts++ < 20) {
            val index = codec.dequeueInputBuffer(10_000L)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: error("AAC encoder input unavailable.")
                buffer.clear()
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val count = minOf(pcm.size, buffer.remaining() / 2)
                buffer.asShortBuffer().put(pcm, 0, count)
                codec.queueInputBuffer(
                    index,
                    0,
                    count * 2,
                    nextInputPtsUs,
                    0,
                )
                totalInputSamples += count
                queued = true
            } else {
                drain(false)
            }
        }
        if (!queued) error("AAC encoder did not accept microphone audio.")
        drain(false)
    }

    fun close(captureTimeMs: Long = System.currentTimeMillis()) {
        if (closed) return
        lastCaptureTimeMs = captureTimeMs
        try {
            if (totalInputSamples == 0L) return
            queueEndOfStream()
            var attempts = 0
            while (!sawEndOfStream && attempts++ < 100) {
                drain(true)
            }
            finishSegment(captureTimeMs, savedDurationMs)
        } finally {
            closed = true
            runCatching { codec.stop() }
            codec.release()
            if (outputTarget != null) {
                abandonCurrent()
            }
        }
    }

    fun abort() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        abandonCurrent()
    }

    private fun queueEndOfStream() {
        var queued = false
        var attempts = 0
        while (!queued && attempts++ < 50) {
            val index = codec.dequeueInputBuffer(10_000L)
            if (index >= 0) {
                codec.queueInputBuffer(
                    index,
                    0,
                    0,
                    nextInputPtsUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
                queued = true
            } else {
                drain(false)
            }
        }
        if (!queued) error("Could not finalize the AAC encoder.")
    }

    private fun drain(waitForOutput: Boolean) {
        while (true) {
            val timeoutUs = if (waitForOutput) 10_000L else 0L
            val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(outputFormat == null) { "AAC output format changed more than once." }
                    outputFormat = codec.outputFormat
                }
                outputIndex >= 0 -> {
                    val output = codec.getOutputBuffer(outputIndex)
                        ?: error("AAC encoder output unavailable.")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        info.size = 0
                    }
                    if (info.size > 0) {
                        rotateIfNeeded(info.presentationTimeUs)
                        ensureSegment(info.presentationTimeUs)
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        val normalized = MediaCodec.BufferInfo().apply {
                            set(
                                0,
                                info.size,
                                (info.presentationTimeUs - segmentStartPtsUs).coerceAtLeast(0L),
                                info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv(),
                            )
                        }
                        muxer?.writeSampleData(muxerTrack, output, normalized)
                        segmentSampleCount++
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawEndOfStream = true
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (sawEndOfStream) return
                }
            }
        }
    }

    private fun rotateIfNeeded(samplePtsUs: Long) {
        while (rotations.isNotEmpty() && rotations.first.audioPtsUs <= samplePtsUs) {
            val rotation = rotations.removeFirst()
            if (outputTarget != null) {
                // AAC output is emitted on codec frame boundaries. Use the first
                // frame timestamp of the new segment as the preceding segment's
                // end offset so the timeline expresses the exact gapless handoff.
                finishSegment(rotation.captureTimeMs, samplePtsUs / 1_000L)
            }
            segmentCaptureTimeMs = rotation.captureTimeMs
        }
    }

    private fun ensureSegment(firstSamplePtsUs: Long) {
        if (outputTarget != null) return
        val format = outputFormat ?: error("AAC encoder format is unavailable.")
        val target = storage.createAudioOutput(segmentCaptureTimeMs)
        try {
            val newMuxer = MediaMuxer(
                target.descriptor.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            muxerTrack = newMuxer.addTrack(format)
            newMuxer.start()
            muxer = newMuxer
            outputTarget = target
            segmentStartPtsUs = firstSamplePtsUs
            segmentSampleCount = 0
            listener.onSegmentStarted(
                target.displayName,
                segmentCaptureTimeMs,
                firstSamplePtsUs / 1_000L,
            )
        } catch (throwable: Throwable) {
            storage.abandonAudio(target)
            throw throwable
        }
    }

    private fun finishSegment(captureTimeMs: Long, audioEndOffsetMs: Long) {
        val target = outputTarget ?: return
        val currentMuxer = muxer
        if (segmentSampleCount <= 0 || currentMuxer == null) {
            abandonCurrent()
            return
        }
        try {
            currentMuxer.stop()
            currentMuxer.release()
            muxer = null
            val size = target.descriptor.statSize.coerceAtLeast(0L)
            storage.finalizeAudio(target)
            outputTarget = null
            listener.onSegmentFinished(
                captureTimeMs,
                audioEndOffsetMs,
                size,
            )
        } catch (throwable: Throwable) {
            muxer = null
            outputTarget = null
            runCatching { currentMuxer.release() }
            storage.abandonAudio(target)
            throw throwable
        }
    }

    private fun abandonCurrent() {
        val target = outputTarget
        runCatching { muxer?.release() }
        muxer = null
        outputTarget = null
        if (target != null) storage.abandonAudio(target)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_COUNT = 1
        const val FRAME_SAMPLES = 320
        private const val MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC
    }
}
