package com.mark.infiniterecorder.data

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class SessionTimeline(
    context: Context,
    private val storage: SharedStorageRepository,
    private val sessionStartMs: Long,
) {
    private val zone = ZoneId.systemDefault()
    private val day = Instant.ofEpochMilli(sessionStartMs)
        .atZone(zone)
        .format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val privateFile = AtomicFile(File(context.filesDir, "timeline-$day.json"))
    private val root: JSONObject
    private val session: JSONObject
    private val segments = JSONArray()
    private val pauses = JSONArray()
    private val silences = JSONArray()
    private val bookmarks = JSONArray()
    private val errors = JSONArray()
    private var activeSegment: JSONObject? = null
    private var pauseStartMs: Long? = null

    init {
        root = loadExisting() ?: JSONObject().apply {
            put("schemaVersion", 1)
            put("day", day)
            put("processingStatus", "Unprocessed")
            put("sessions", JSONArray())
        }
        session = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("startedUtc", isoUtc(sessionStartMs))
            put("startedLocal", isoLocal(sessionStartMs))
            put("timeZone", zone.id)
            put("segments", segments)
            put("pauses", pauses)
            put("suppressedSilence", silences)
            put("bookmarks", bookmarks)
            put("errors", errors)
            put("status", "Active")
        }
        root.getJSONArray("sessions").put(session)
        persist()
    }

    @Synchronized
    fun segmentStarted(name: String, captureTimeMs: Long, audioOffsetMs: Long) {
        activeSegment?.let { finishSegment(captureTimeMs, audioOffsetMs, 0L) }
        activeSegment = JSONObject().apply {
            put("filename", name)
            put("captureStartedUtc", isoUtc(captureTimeMs))
            put("captureStartedLocal", isoLocal(captureTimeMs))
            put("sessionAudioOffsetMs", audioOffsetMs)
            put("status", "Writing")
        }.also { segments.put(it) }
        persist()
    }

    @Synchronized
    fun segmentFinished(
        captureTimeMs: Long,
        audioOffsetMs: Long,
        sizeBytes: Long,
    ) {
        finishSegment(captureTimeMs, audioOffsetMs, sizeBytes)
        persist()
    }

    @Synchronized
    fun pauseStarted(timeMs: Long) {
        if (pauseStartMs == null) pauseStartMs = timeMs
        persist()
    }

    @Synchronized
    fun pauseEnded(timeMs: Long) {
        val start = pauseStartMs ?: return
        pauses.put(interval(start, timeMs))
        pauseStartMs = null
        persist()
    }

    @Synchronized
    fun suppressedSilence(startMs: Long, endMs: Long) {
        if (endMs > startMs) {
            silences.put(interval(startMs, endMs))
            persist()
        }
    }

    @Synchronized
    fun bookmark(timeMs: Long, audioOffsetMs: Long, label: String) {
        bookmarks.put(
            JSONObject().apply {
                put("utc", isoUtc(timeMs))
                put("local", isoLocal(timeMs))
                put("audioOffsetMs", audioOffsetMs)
                if (label.isNotBlank()) put("label", label.trim())
            },
        )
        persist()
    }

    @Synchronized
    fun error(timeMs: Long, message: String) {
        errors.put(
            JSONObject().apply {
                put("utc", isoUtc(timeMs))
                put("local", isoLocal(timeMs))
                put("message", message)
            },
        )
        session.put("status", "Interrupted")
        persist()
    }

    @Synchronized
    fun finish(timeMs: Long, audioDurationMs: Long, status: String = "Completed") {
        pauseStartMs?.let {
            pauses.put(interval(it, timeMs))
            pauseStartMs = null
        }
        session.put("endedUtc", isoUtc(timeMs))
        session.put("endedLocal", isoLocal(timeMs))
        session.put("savedAudioDurationMs", audioDurationMs)
        session.put("status", status)
        persist()
    }

    @Synchronized
    fun persist() {
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = privateFile.startWrite()
            stream.write(bytes)
            stream.flush()
            privateFile.finishWrite(stream)
            stream = null
        } finally {
            if (stream != null) privateFile.failWrite(stream)
        }
        storage.writeDailyManifest(day, root.toString(2))
    }

    private fun finishSegment(captureTimeMs: Long, audioOffsetMs: Long, sizeBytes: Long) {
        activeSegment?.apply {
            put("captureEndedUtc", isoUtc(captureTimeMs))
            put("captureEndedLocal", isoLocal(captureTimeMs))
            put("sessionAudioEndOffsetMs", audioOffsetMs)
            put("sizeBytes", sizeBytes)
            put("status", "Completed")
        }
        activeSegment = null
    }

    private fun loadExisting(): JSONObject? {
        val privateJson = runCatching {
            privateFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrNull()
        val sharedJson = storage.readDailyManifest(day)
        return sequenceOf(privateJson, sharedJson)
            .filterNotNull()
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .firstOrNull { it.optString("day") == day && it.has("sessions") }
    }

    private fun interval(startMs: Long, endMs: Long) = JSONObject().apply {
        put("startUtc", isoUtc(startMs))
        put("endUtc", isoUtc(endMs))
        put("startLocal", isoLocal(startMs))
        put("endLocal", isoLocal(endMs))
        put("durationMs", (endMs - startMs).coerceAtLeast(0L))
    }

    private fun isoUtc(timeMs: Long): String = Instant.ofEpochMilli(timeMs).toString()

    private fun isoLocal(timeMs: Long): String =
        Instant.ofEpochMilli(timeMs).atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
