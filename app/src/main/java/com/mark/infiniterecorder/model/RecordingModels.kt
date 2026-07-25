package com.mark.infiniterecorder.model

import android.net.Uri

enum class RecorderState {
    IDLE,
    PREPARING,
    LISTENING,
    RECORDING_SOUND,
    SILENCE_SUPPRESSED,
    PAUSED,
    STOPPING,
    ERROR,
}

data class RecordingSnapshot(
    val state: RecorderState = RecorderState.IDLE,
    val sessionStartedAtMs: Long = 0L,
    val pausedDurationMs: Long = 0L,
    val pauseStartedAtMs: Long = 0L,
    val savedAudioDurationMs: Long = 0L,
    val currentFile: String = "",
    val soundLevel: Int = 0,
    val storageBytes: Long = 0L,
    val error: String = "",
)

data class RecordingEntry(
    val uri: Uri,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val dateAddedSeconds: Long,
    val isPartial: Boolean = false,
) {
    val day: String
        get() = Regex("""\d{4}-\d{2}-\d{2}""")
            .find(relativePath)
            ?.value
            ?: Regex("""\d{4}-\d{2}-\d{2}""").find(displayName)?.value
            ?: "Unknown date"
}
