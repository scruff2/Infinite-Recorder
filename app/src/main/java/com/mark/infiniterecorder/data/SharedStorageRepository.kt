package com.mark.infiniterecorder.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.AtomicFile
import com.mark.infiniterecorder.model.RecordingEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

class SharedStorageRepository(
    private val context: Context,
) {
    data class OutputTarget(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val descriptor: ParcelFileDescriptor,
        val legacyFile: File? = null,
    )

    private val resolver = context.contentResolver
    private val dayFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val fileFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

    fun createAudioOutput(captureTimeMs: Long): OutputTarget {
        val currentSettings = SettingsRepository(context)
        val estimatedSegmentBytes = (
            currentSettings.bitrate.toLong() *
                currentSettings.segmentMinutes *
                60L / 8L * 11L / 10L
            ).coerceAtLeast(2_000_000L)
        ensureCapacity(estimatedSegmentBytes)
        val dateTime = Instant.ofEpochMilli(captureTimeMs).atZone(ZoneId.systemDefault())
        val day = dateTime.format(dayFormatter)
        val name = "recording_${dateTime.format(fileFormatter)}.m4a"
        val relativePath = "Download/Infinite-Recorder/$day/"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "audio/mp4")
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.Downloads.DATE_ADDED, captureTimeMs / 1000L)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android could not create a shared recording file.")
            val descriptor = resolver.openFileDescriptor(uri, "rw")
                ?: run {
                    resolver.delete(uri, null, null)
                    error("Android could not open the new recording file.")
                }
            return OutputTarget(uri, name, relativePath, descriptor)
        }

        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(root, "Infinite-Recorder/$day").apply {
            if (!exists() && !mkdirs()) error("Could not create the recordings folder.")
        }
        val file = File(directory, name)
        val descriptor = ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_READ_WRITE or
                ParcelFileDescriptor.MODE_TRUNCATE,
        )
        return OutputTarget(Uri.fromFile(file), name, relativePath, descriptor, file)
    }

    fun finalizeAudio(target: OutputTarget) {
        runCatching { target.descriptor.close() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.legacyFile == null) {
            resolver.update(
                target.uri,
                ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                    put(MediaStore.Downloads.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                },
                null,
                null,
            )
        } else {
            target.legacyFile?.let {
                MediaScannerConnection.scanFile(context, arrayOf(it.absolutePath), arrayOf("audio/mp4"), null)
            }
        }
    }

    fun abandonAudio(target: OutputTarget) {
        runCatching { target.descriptor.close() }
        if (target.legacyFile != null) {
            target.legacyFile.delete()
        } else {
            resolver.delete(target.uri, null, null)
        }
    }

    fun listRecordings(): List<RecordingEntry> {
        val entries = mutableListOf<RecordingEntry>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.RELATIVE_PATH,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_ADDED,
                MediaStore.Downloads.MIME_TYPE,
            )
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                    "${MediaStore.Downloads.IS_PENDING}=0",
                arrayOf("Download/Infinite-Recorder/%", "recording_%.m4a"),
                "${MediaStore.Downloads.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.SIZE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.MIME_TYPE)
                while (cursor.moveToNext()) {
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idIndex),
                    )
                    val name = cursor.getString(nameIndex)
                    val mime = cursor.getString(mimeIndex).orEmpty()
                    val partial = name.contains(".partial.") || mime != "audio/mp4"
                    val duration = if (partial) 0L else runCatching {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                ?.toLongOrNull()
                                ?: 0L
                        } finally {
                            retriever.release()
                        }
                    }.getOrDefault(0L)
                    entries += RecordingEntry(
                        uri = uri,
                        displayName = name,
                        relativePath = cursor.getString(pathIndex) ?: "",
                        sizeBytes = cursor.getLong(sizeIndex),
                        durationMs = duration,
                        dateAddedSeconds = cursor.getLong(dateIndex),
                        isPartial = partial,
                    )
                }
            }
            return entries
        }

        @Suppress("DEPRECATION")
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Infinite-Recorder",
        )
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
            .forEach { file ->
                val duration = runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.absolutePath)
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                            ?.toLongOrNull()
                            ?: 0L
                    } finally {
                        retriever.release()
                    }
                }.getOrDefault(0L)
                entries += RecordingEntry(
                    uri = Uri.fromFile(file),
                    displayName = file.name,
                    relativePath = file.parentFile?.name.orEmpty(),
                    sizeBytes = file.length(),
                    durationMs = duration,
                    dateAddedSeconds = file.lastModified() / 1000L,
                    isPartial = file.name.contains(".partial."),
                )
            }
        return entries.sortedByDescending { it.dateAddedSeconds }
    }

    fun totalUsageBytes(): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var total = 0L
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                arrayOf("Download/Infinite-Recorder/%"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) total += cursor.getLong(0).coerceAtLeast(0L)
            }
            return total
        }
        @Suppress("DEPRECATION")
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Infinite-Recorder",
        )
        return if (root.exists()) {
            root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            0L
        }
    }

    fun delete(entry: RecordingEntry): Boolean =
        if (entry.uri.scheme == "file") {
            entry.uri.path?.let(::File)?.delete() == true
        } else {
            resolver.delete(entry.uri, null, null) > 0
        }

    fun deleteRecording(entry: RecordingEntry): Boolean {
        if (!delete(entry)) return false
        val dayStillHasAudio = listRecordings().any { it.day == entry.day }
        return if (dayStillHasAudio) {
            markRecordingDeleted(entry.day, entry.displayName)
            true
        } else {
            deleteDailyMetadata(entry.day)
        }
    }

    fun recoverInterruptedOutputs(): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val recovered = mutableListOf<String>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
            ),
            "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Downloads.MIME_TYPE}=? AND ${MediaStore.Downloads.IS_PENDING}=1",
            arrayOf("Download/Infinite-Recorder/%", "audio/mp4"),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    cursor.getLong(0),
                )
                val oldName = cursor.getString(1)
                val newName = if (oldName.endsWith(".m4a")) {
                    oldName.removeSuffix(".m4a") + ".partial.m4a"
                } else {
                    "$oldName.partial"
                }
                resolver.update(
                    uri,
                    ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, newName)
                        put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                        put(MediaStore.Downloads.IS_PENDING, 0)
                    },
                    null,
                    null,
                )
                recovered += newName
            }
        }
        return recovered
    }

    fun writeDailyManifest(day: String, json: String) {
        val name = "session_$day.json"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(MediaStore.Downloads._ID)
            val relativePath = "Download/Infinite-Recorder/$day/"
            val existing = resolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, relativePath),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
            val uri = existing ?: resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 0)
                },
            ) ?: error("Could not create session metadata.")
            resolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use {
                it.write(json)
            } ?: error("Could not write session metadata.")
            return
        }

        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val directory = File(root, "Infinite-Recorder/$day").apply { mkdirs() }
        File(directory, name).writeText(json, Charsets.UTF_8)
    }

    fun readDailyManifest(day: String): String? {
        val name = "session_$day.json"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = "Download/Infinite-Recorder/$day/"
            val uri = resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, relativePath),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            } ?: return null
            return resolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        }

        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val file = File(root, "Infinite-Recorder/$day/$name")
        return file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
    }

    fun dailyManifestUri(day: String): Uri? {
        val name = "session_$day.json"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val relativePath = "Download/Infinite-Recorder/$day/"
            return resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?",
                arrayOf(name, relativePath),
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                } else {
                    null
                }
            }
        }
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        return File(root, "Infinite-Recorder/$day/$name")
            .takeIf { it.isFile }
            ?.let(Uri::fromFile)
    }

    fun processingStatus(day: String): String =
        readDailyManifest(day)
            ?.let { runCatching { JSONObject(it).optString("processingStatus", "Unprocessed") }.getOrNull() }
            ?: "Unprocessed"

    fun setProcessingStatus(day: String, status: String) {
        val root = readDailyManifest(day)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: JSONObject().apply {
                put("schemaVersion", 1)
                put("day", day)
                put("sessions", org.json.JSONArray())
            }
        root.put("processingStatus", status)
        writeManifestCopies(day, root.toString(2))
    }

    fun markRecordingDeleted(day: String, displayName: String) {
        val root = readDailyManifest(day)?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return
        val sessions = root.optJSONArray("sessions") ?: return
        for (sessionIndex in 0 until sessions.length()) {
            val segments = sessions.optJSONObject(sessionIndex)
                ?.optJSONArray("segments")
                ?: continue
            for (segmentIndex in 0 until segments.length()) {
                val segment = segments.optJSONObject(segmentIndex) ?: continue
                if (segment.optString("filename") == displayName) {
                    segment.put("status", "Deleted")
                    segment.put("deletedUtc", java.time.Instant.now().toString())
                    segment.put("sizeBytes", 0)
                }
            }
        }
        writeManifestCopies(day, root.toString(2))
    }

    fun deleteDay(day: String, entries: List<RecordingEntry>): Boolean {
        var success = true
        entries.forEach { if (!delete(it)) success = false }
        if (!deleteDailyMetadata(day)) success = false
        return success
    }

    fun cleanupEmptyDailyMetadata(): Int {
        val daysWithAudio = listRecordings().mapTo(mutableSetOf()) { it.day }
        val emptyDays = listManifestDays().filterNot(daysWithAudio::contains)
        return emptyDays.count { deleteDailyMetadata(it) }
    }

    @Synchronized
    private fun ensureCapacity(requiredBytes: Long) {
        var usage = totalUsageBytes()
        if (usage + requiredBytes <= SettingsRepository.MAX_STORAGE_BYTES) return

        val entriesByDay = listRecordings()
            .groupBy { it.day }
            .toSortedMap()
        for ((day, entries) in entriesByDay) {
            if (processingStatus(day) != "Processed") continue
            entries.forEach(::delete)
            deleteDailyMetadata(day)
            usage = totalUsageBytes()
            if (usage + requiredBytes <= SettingsRepository.MAX_STORAGE_BYTES) return
        }
        error(
            "The 5 GB recording limit cannot safely fit another segment. " +
                "Mark older days Processed or delete recordings.",
        )
    }

    private fun writeManifestCopies(day: String, json: String) {
        val atomicFile = AtomicFile(File(context.filesDir, "timeline-$day.json"))
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(json.toByteArray(Charsets.UTF_8))
            stream.flush()
            atomicFile.finishWrite(stream)
            stream = null
        } finally {
            if (stream != null) atomicFile.failWrite(stream)
        }
        writeDailyManifest(day, json)
    }

    private fun deleteDailyMetadata(day: String): Boolean {
        AtomicFile(File(context.filesDir, "timeline-$day.json")).delete()
        val uri = dailyManifestUri(day) ?: return true
        return if (uri.scheme == "file") {
            uri.path?.let(::File)?.delete() == true
        } else {
            resolver.delete(uri, null, null) > 0
        }
    }

    private fun listManifestDays(): Set<String> {
        val privateDays = context.filesDir.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.startsWith("timeline-") && it.name.endsWith(".json") }
            .mapNotNull { Regex("""\d{4}-\d{2}-\d{2}""").find(it.name)?.value }
            .toMutableSet()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val days = privateDays
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Downloads.DISPLAY_NAME,
                    MediaStore.Downloads.RELATIVE_PATH,
                ),
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ? AND " +
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
                arrayOf("Download/Infinite-Recorder/%", "session_%.json"),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0).orEmpty()
                    val path = cursor.getString(1).orEmpty()
                    Regex("""\d{4}-\d{2}-\d{2}""").find(name)?.value
                        ?.let(days::add)
                        ?: Regex("""\d{4}-\d{2}-\d{2}""").find(path)?.value
                            ?.let(days::add)
                }
            }
            return days
        }

        @Suppress("DEPRECATION")
        val root = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "Infinite-Recorder",
        )
        root.listFiles()
            .orEmpty()
            .filter { directory ->
                directory.isDirectory &&
                    File(directory, "session_${directory.name}.json").isFile
            }
            .mapTo(privateDays) { it.name }
        return privateDays
    }
}
