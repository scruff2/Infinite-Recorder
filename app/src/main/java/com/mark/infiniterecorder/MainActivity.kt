package com.mark.infiniterecorder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import com.mark.infiniterecorder.data.SettingsRepository
import com.mark.infiniterecorder.data.SharedStorageRepository
import com.mark.infiniterecorder.databinding.ActivityMainBinding
import com.mark.infiniterecorder.model.RecorderState
import com.mark.infiniterecorder.model.RecordingSnapshot
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private var snapshot = RecordingSnapshot()
    private var pendingStart = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == RecordingContract.ACTION_STATE_CHANGED) {
                snapshot = RecordingContract.snapshotFrom(intent)
                render()
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            // Shared runtime state is also polled so the UI remains accurate
            // across activity recreation and OEM broadcast-delivery quirks.
            snapshot = RecordingContract.loadSnapshot(this@MainActivity)
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)

        binding.startPauseButton.setOnClickListener {
            when (snapshot.state) {
                RecorderState.IDLE, RecorderState.ERROR -> requestPermissionsAndStart()
                RecorderState.PAUSED ->
                    startService(RecordingContract.serviceIntent(this, RecordingContract.ACTION_RESUME))
                RecorderState.PREPARING,
                RecorderState.LISTENING,
                RecorderState.RECORDING_SOUND,
                RecorderState.SILENCE_SUPPRESSED,
                -> startService(
                    RecordingContract.serviceIntent(this, RecordingContract.ACTION_PAUSE),
                )
                RecorderState.STOPPING -> Unit
            }
        }
        binding.stopButton.setOnClickListener {
            startService(RecordingContract.serviceIntent(this, RecordingContract.ACTION_STOP))
        }
        binding.bookmarkButton.setOnClickListener { showBookmarkDialog() }
        binding.recordingsButton.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(RecordingContract.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        snapshot = RecordingContract.loadSnapshot(this)
        val claimsActive = snapshot.state !in setOf(RecorderState.IDLE, RecorderState.ERROR)
        if (claimsActive && !RecordingService.isRunning) {
            snapshot = snapshot.copy(
                state = RecorderState.ERROR,
                soundLevel = 0,
                error = "The previous recording was interrupted by Android. " +
                    "Any incomplete file is being preserved as a partial recording.",
            )
            RecordingContract.saveSnapshot(this, snapshot)
            recoverInterruptedOutput()
        }
        render()
        refreshStorage()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        runCatching { unregisterReceiver(stateReceiver) }
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST || !pendingStart) return
        pendingStart = false
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecordingService()
        } else {
            snapshot = snapshot.copy(
                state = RecorderState.ERROR,
                error = "Microphone permission is required to record.",
            )
            render()
        }
    }

    private fun requestPermissionsAndStart() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.RECORD_AUDIO
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) {
            startRecordingService()
        } else {
            pendingStart = true
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    private fun startRecordingService() {
        snapshot = RecordingSnapshot(state = RecorderState.PREPARING)
        render()
        val intent = RecordingContract.serviceIntent(this, RecordingContract.ACTION_START)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun showBookmarkDialog() {
        val input = EditText(this).apply {
            hint = "Optional label"
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Add bookmark")
            .setMessage("A bookmark saves this clock time and audio position.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                startService(
                    RecordingContract
                        .serviceIntent(this, RecordingContract.ACTION_BOOKMARK)
                        .putExtra(RecordingContract.EXTRA_BOOKMARK_LABEL, input.text.toString()),
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun render() {
        val active = snapshot.state !in setOf(
            RecorderState.IDLE,
            RecorderState.ERROR,
            RecorderState.STOPPING,
        )
        binding.statusText.text = statusTitle(snapshot.state)
        binding.statusDetailText.text = statusDetail(snapshot.state)
        binding.soundLevel.progress = snapshot.soundLevel
        binding.currentFileText.text = snapshot.currentFile.ifBlank { "Waiting for retained sound…" }
        binding.startPauseButton.text = when (snapshot.state) {
            RecorderState.IDLE, RecorderState.ERROR -> "Start recording"
            RecorderState.PAUSED -> "Resume recording"
            RecorderState.STOPPING -> "Finalizing…"
            else -> "Pause recording"
        }
        binding.startPauseButton.isEnabled = snapshot.state != RecorderState.STOPPING
        binding.stopButton.isEnabled = active
        binding.bookmarkButton.isEnabled = active || snapshot.state == RecorderState.PAUSED
        binding.errorText.apply {
            if (snapshot.error.isBlank()) {
                visibility = android.view.View.GONE
            } else {
                text = snapshot.error
                visibility = android.view.View.VISIBLE
            }
        }
        renderStorage()
        renderDurations()
    }

    private fun renderDurations() {
        val now = System.currentTimeMillis()
        val currentPause = if (
            snapshot.state == RecorderState.PAUSED &&
            snapshot.pauseStartedAtMs > 0L
        ) {
            now - snapshot.pauseStartedAtMs
        } else {
            0L
        }
        val wall = if (snapshot.sessionStartedAtMs > 0L) {
            (now - snapshot.sessionStartedAtMs - snapshot.pausedDurationMs - currentPause)
                .coerceAtLeast(0L)
        } else {
            0L
        }
        binding.wallClockText.text = "Session time  ${formatDuration(wall)}"
        binding.savedAudioText.text =
            "Audio saved  ${formatDuration(snapshot.savedAudioDurationMs)}"
    }

    private fun renderStorage() {
        val max = SettingsRepository.MAX_STORAGE_BYTES
        val used = snapshot.storageBytes.coerceAtLeast(0L)
        binding.storageProgress.progress = ((used.toDouble() / max) * 1000).toInt()
            .coerceIn(0, 1000)
        binding.storageText.text =
            "Storage  ${formatBytes(used)} of 5 GB"
        val remaining = (max - used).coerceAtLeast(0L)
        val bitrate = SettingsRepository(this).bitrate
        val hours = remaining * 8.0 / bitrate / 3600.0
        binding.remainingText.text = String.format(
            Locale.US,
            "About %.0f saved-audio hours available",
            hours,
        )
    }

    private fun refreshStorage() {
        thread(name = "InfiniteRecorder-Storage") {
            val bytes = runCatching { SharedStorageRepository(this).totalUsageBytes() }
                .getOrDefault(snapshot.storageBytes)
            runOnUiThread {
                snapshot = snapshot.copy(storageBytes = bytes)
                renderStorage()
            }
        }
    }

    private fun recoverInterruptedOutput() {
        thread(name = "InfiniteRecorder-Recovery") {
            val recovered = runCatching {
                SharedStorageRepository(this).recoverInterruptedOutputs()
            }.getOrDefault(emptyList())
            if (recovered.isNotEmpty()) {
                runOnUiThread {
                    snapshot = snapshot.copy(
                        error = "Android interrupted the previous recording. " +
                            "${recovered.size} incomplete file(s) were preserved and clearly marked partial.",
                    )
                    RecordingContract.saveSnapshot(this, snapshot)
                    render()
                }
            }
        }
    }

    private fun statusTitle(state: RecorderState): String = when (state) {
        RecorderState.IDLE -> "Idle"
        RecorderState.PREPARING -> "Preparing"
        RecorderState.LISTENING -> "Listening"
        RecorderState.RECORDING_SOUND -> "Recording sound"
        RecorderState.SILENCE_SUPPRESSED -> "Silence suppressed"
        RecorderState.PAUSED -> "Paused"
        RecorderState.STOPPING -> "Finalizing"
        RecorderState.ERROR -> "Recording interrupted"
    }

    private fun statusDetail(state: RecorderState): String = when (state) {
        RecorderState.IDLE -> "Ready to start a private daily activity journal"
        RecorderState.PREPARING -> "Opening the microphone and encoder"
        RecorderState.LISTENING -> "Monitoring for sound; nothing saved yet"
        RecorderState.RECORDING_SOUND -> "Detected audio is being saved"
        RecorderState.SILENCE_SUPPRESSED -> "The microphone is active; silence is omitted"
        RecorderState.PAUSED -> "Microphone capture is paused"
        RecorderState.STOPPING -> "Safely closing the current file"
        RecorderState.ERROR -> snapshot.error.ifBlank { "Recording stopped unexpectedly" }
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000L
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            total / 3600,
            (total % 3600) / 60,
            total % 60,
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    }

    companion object {
        private const val PERMISSION_REQUEST = 200
    }
}
