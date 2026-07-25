package com.mark.infiniterecorder

import android.content.Context
import android.content.Intent
import com.mark.infiniterecorder.model.RecorderState
import com.mark.infiniterecorder.model.RecordingSnapshot

object RecordingContract {
    const val ACTION_START = "com.mark.infiniterecorder.action.START"
    const val ACTION_PAUSE = "com.mark.infiniterecorder.action.PAUSE"
    const val ACTION_RESUME = "com.mark.infiniterecorder.action.RESUME"
    const val ACTION_STOP = "com.mark.infiniterecorder.action.STOP"
    const val ACTION_BOOKMARK = "com.mark.infiniterecorder.action.BOOKMARK"
    const val ACTION_STATE_CHANGED = "com.mark.infiniterecorder.STATE_CHANGED"

    const val EXTRA_STATE = "state"
    const val EXTRA_SESSION_STARTED = "session_started"
    const val EXTRA_PAUSED_DURATION = "paused_duration"
    const val EXTRA_PAUSE_STARTED = "pause_started"
    const val EXTRA_SAVED_DURATION = "saved_duration"
    const val EXTRA_CURRENT_FILE = "current_file"
    const val EXTRA_SOUND_LEVEL = "sound_level"
    const val EXTRA_STORAGE_BYTES = "storage_bytes"
    const val EXTRA_ERROR = "error"
    const val EXTRA_BOOKMARK_LABEL = "bookmark_label"

    private const val RUNTIME_PREFS = "recording_runtime"

    fun serviceIntent(context: Context, action: String): Intent =
        Intent(context, RecordingService::class.java).setAction(action)

    fun saveSnapshot(context: Context, snapshot: RecordingSnapshot) {
        context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(EXTRA_STATE, snapshot.state.name)
            .putLong(EXTRA_SESSION_STARTED, snapshot.sessionStartedAtMs)
            .putLong(EXTRA_PAUSED_DURATION, snapshot.pausedDurationMs)
            .putLong(EXTRA_PAUSE_STARTED, snapshot.pauseStartedAtMs)
            .putLong(EXTRA_SAVED_DURATION, snapshot.savedAudioDurationMs)
            .putString(EXTRA_CURRENT_FILE, snapshot.currentFile)
            .putInt(EXTRA_SOUND_LEVEL, snapshot.soundLevel)
            .putLong(EXTRA_STORAGE_BYTES, snapshot.storageBytes)
            .putString(EXTRA_ERROR, snapshot.error)
            .apply()
    }

    fun loadSnapshot(context: Context): RecordingSnapshot {
        val prefs = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val state = runCatching {
            RecorderState.valueOf(prefs.getString(EXTRA_STATE, RecorderState.IDLE.name)!!)
        }.getOrDefault(RecorderState.IDLE)
        return RecordingSnapshot(
            state = state,
            sessionStartedAtMs = prefs.getLong(EXTRA_SESSION_STARTED, 0L),
            pausedDurationMs = prefs.getLong(EXTRA_PAUSED_DURATION, 0L),
            pauseStartedAtMs = prefs.getLong(EXTRA_PAUSE_STARTED, 0L),
            savedAudioDurationMs = prefs.getLong(EXTRA_SAVED_DURATION, 0L),
            currentFile = prefs.getString(EXTRA_CURRENT_FILE, "") ?: "",
            soundLevel = prefs.getInt(EXTRA_SOUND_LEVEL, 0),
            storageBytes = prefs.getLong(EXTRA_STORAGE_BYTES, 0L),
            error = prefs.getString(EXTRA_ERROR, "") ?: "",
        )
    }

    fun stateIntent(context: Context, snapshot: RecordingSnapshot): Intent =
        Intent(ACTION_STATE_CHANGED)
            .setPackage(context.packageName)
            .putExtra(EXTRA_STATE, snapshot.state.name)
            .putExtra(EXTRA_SESSION_STARTED, snapshot.sessionStartedAtMs)
            .putExtra(EXTRA_PAUSED_DURATION, snapshot.pausedDurationMs)
            .putExtra(EXTRA_PAUSE_STARTED, snapshot.pauseStartedAtMs)
            .putExtra(EXTRA_SAVED_DURATION, snapshot.savedAudioDurationMs)
            .putExtra(EXTRA_CURRENT_FILE, snapshot.currentFile)
            .putExtra(EXTRA_SOUND_LEVEL, snapshot.soundLevel)
            .putExtra(EXTRA_STORAGE_BYTES, snapshot.storageBytes)
            .putExtra(EXTRA_ERROR, snapshot.error)

    fun snapshotFrom(intent: Intent): RecordingSnapshot {
        val state = runCatching {
            RecorderState.valueOf(
                intent.getStringExtra(EXTRA_STATE) ?: RecorderState.IDLE.name,
            )
        }.getOrDefault(RecorderState.IDLE)
        return RecordingSnapshot(
            state = state,
            sessionStartedAtMs = intent.getLongExtra(EXTRA_SESSION_STARTED, 0L),
            pausedDurationMs = intent.getLongExtra(EXTRA_PAUSED_DURATION, 0L),
            pauseStartedAtMs = intent.getLongExtra(EXTRA_PAUSE_STARTED, 0L),
            savedAudioDurationMs = intent.getLongExtra(EXTRA_SAVED_DURATION, 0L),
            currentFile = intent.getStringExtra(EXTRA_CURRENT_FILE) ?: "",
            soundLevel = intent.getIntExtra(EXTRA_SOUND_LEVEL, 0),
            storageBytes = intent.getLongExtra(EXTRA_STORAGE_BYTES, 0L),
            error = intent.getStringExtra(EXTRA_ERROR) ?: "",
        )
    }
}
