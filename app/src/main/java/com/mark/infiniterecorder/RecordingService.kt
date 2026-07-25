package com.mark.infiniterecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.mark.infiniterecorder.audio.RecordingManager
import com.mark.infiniterecorder.model.RecorderState
import com.mark.infiniterecorder.model.RecordingSnapshot
import kotlin.concurrent.thread

class RecordingService : Service(), RecordingManager.Listener {
    private val stateLock = Any()
    private var manager: RecordingManager? = null
    private var snapshot = RecordingSnapshot()
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopInProgress = false
    private var lastBroadcastState: RecorderState? = null
    private var lastBroadcastDetail = ""
    private var lastRuntimePublishMs = 0L

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        snapshot = RecordingContract.loadSnapshot(this)
        if (snapshot.state !in setOf(RecorderState.IDLE, RecorderState.ERROR)) {
            snapshot = snapshot.copy(
                state = RecorderState.ERROR,
                error = "A previous recording ended unexpectedly.",
            )
            publish(force = true)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RecordingContract.ACTION_START -> startRecording()
            RecordingContract.ACTION_PAUSE -> manager?.pause()
            RecordingContract.ACTION_RESUME -> {
                acquireWakeLock()
                manager?.resume()
            }
            RecordingContract.ACTION_BOOKMARK -> {
                manager?.bookmark(intent.getStringExtra(RecordingContract.EXTRA_BOOKMARK_LABEL).orEmpty())
                onState(snapshot.state, "Bookmark saved")
            }
            RecordingContract.ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onState(state: RecorderState, detail: String) {
        synchronized(stateLock) {
            if (state == lastBroadcastState && detail == lastBroadcastDetail) return
            if (state == RecorderState.PAUSED && snapshot.state != RecorderState.PAUSED) {
                snapshot = snapshot.copy(pauseStartedAtMs = System.currentTimeMillis())
                releaseWakeLock()
            } else if (snapshot.state == RecorderState.PAUSED && state != RecorderState.PAUSED) {
                if (snapshot.pauseStartedAtMs > 0L) {
                    snapshot = snapshot.copy(
                        pausedDurationMs = snapshot.pausedDurationMs +
                            (System.currentTimeMillis() - snapshot.pauseStartedAtMs),
                        pauseStartedAtMs = 0L,
                    )
                }
                acquireWakeLock()
            }
            snapshot = snapshot.copy(state = state, error = "")
            publish(
                force = state != lastBroadcastState || detail != lastBroadcastDetail,
                detail = detail,
            )
            lastBroadcastState = state
            lastBroadcastDetail = detail
        }
    }

    override fun onSoundLevel(level: Int) {
        synchronized(stateLock) {
            snapshot = snapshot.copy(soundLevel = level)
            publish(force = false)
        }
    }

    override fun onSavedDuration(durationMs: Long) {
        synchronized(stateLock) {
            snapshot = snapshot.copy(savedAudioDurationMs = durationMs)
            publish(force = false)
        }
    }

    override fun onCurrentFile(name: String) {
        synchronized(stateLock) {
            snapshot = snapshot.copy(currentFile = name)
            publish(force = true)
        }
    }

    override fun onStorageChanged(bytes: Long) {
        synchronized(stateLock) {
            snapshot = snapshot.copy(storageBytes = bytes)
            publish(force = true)
        }
    }

    override fun onStopped(error: String?) {
        synchronized(stateLock) {
            releaseWakeLock()
            manager = null
            stopInProgress = false
            snapshot = if (error.isNullOrBlank()) {
                RecordingSnapshot(
                    state = RecorderState.IDLE,
                    storageBytes = snapshot.storageBytes,
                )
            } else {
                snapshot.copy(
                    state = RecorderState.ERROR,
                    soundLevel = 0,
                    error = error,
                )
            }
            publish(force = true)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        isRunning = false
        releaseWakeLock()
        val active = manager
        if (active != null && !stopInProgress) {
            stopInProgress = true
            thread(name = "InfiniteRecorder-Destroy") { active.stopAndWait() }
        }
        super.onDestroy()
    }

    private fun startRecording() {
        if (manager != null || stopInProgress) return
        snapshot = RecordingSnapshot(
            state = RecorderState.PREPARING,
            sessionStartedAtMs = System.currentTimeMillis(),
            storageBytes = snapshot.storageBytes,
        )
        val notification = buildNotification("Preparing microphone")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        acquireWakeLock()
        try {
            RecordingManager(this, this).also {
                manager = it
                snapshot = snapshot.copy(sessionStartedAtMs = it.startedAtMs)
                publish(force = true)
                it.start()
            }
        } catch (throwable: Throwable) {
            onStopped(throwable.message ?: "Recording could not start.")
        }
    }

    private fun stopRecording() {
        val active = manager ?: return
        if (stopInProgress) return
        stopInProgress = true
        onState(RecorderState.STOPPING, "Finalizing recording")
        thread(name = "InfiniteRecorder-Stop") {
            active.stopAndWait()
        }
    }

    private fun publish(force: Boolean, detail: String = "") {
        val now = System.currentTimeMillis()
        if (!force && now - lastRuntimePublishMs < 250L) return
        lastRuntimePublishMs = now
        RecordingContract.saveSnapshot(this, snapshot)
        sendBroadcast(RecordingContract.stateIntent(this, snapshot))
        if (force && snapshot.state != RecorderState.IDLE) {
            val text = when {
                detail.isNotBlank() -> detail
                snapshot.error.isNotBlank() -> snapshot.error
                else -> notificationText(snapshot.state)
            }
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.mark.infiniterecorder.R.drawable.ic_app)
            .setContentTitle(notificationTitle(snapshot.state))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(snapshot.state !in setOf(RecorderState.IDLE, RecorderState.ERROR))
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)

        if (snapshot.state == RecorderState.PAUSED) {
            builder.addAction(
                notificationAction(
                    "Resume",
                    RecordingContract.ACTION_RESUME,
                    REQUEST_RESUME,
                ),
            )
        } else if (snapshot.state in ACTIVE_STATES) {
            builder.addAction(
                notificationAction(
                    "Pause",
                    RecordingContract.ACTION_PAUSE,
                    REQUEST_PAUSE,
                ),
            )
        }
        if (snapshot.state in ACTIVE_STATES || snapshot.state == RecorderState.PAUSED) {
            builder.addAction(
                notificationAction(
                    "Bookmark",
                    RecordingContract.ACTION_BOOKMARK,
                    REQUEST_BOOKMARK,
                ),
            )
            builder.addAction(
                notificationAction(
                    "Stop",
                    RecordingContract.ACTION_STOP,
                    REQUEST_STOP,
                ),
            )
        }
        return builder.build()
    }

    private fun notificationAction(label: String, action: String, requestCode: Int): Notification.Action {
        val pending = PendingIntent.getService(
            this,
            requestCode,
            RecordingContract.serviceIntent(this, action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            com.mark.infiniterecorder.R.drawable.ic_app,
            label,
            pending,
        ).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(com.mark.infiniterecorder.R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(
                com.mark.infiniterecorder.R.string.notification_channel_description,
            )
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:audio-capture",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun notificationTitle(state: RecorderState): String = when (state) {
        RecorderState.PAUSED -> "Recording paused"
        RecorderState.SILENCE_SUPPRESSED -> "Listening — silence omitted"
        RecorderState.ERROR -> "Recording interrupted"
        else -> "Infinite Recorder is active"
    }

    private fun notificationText(state: RecorderState): String = when (state) {
        RecorderState.PREPARING -> "Preparing microphone"
        RecorderState.LISTENING -> "Listening for sound"
        RecorderState.RECORDING_SOUND -> "Sound is being saved"
        RecorderState.SILENCE_SUPPRESSED -> "Silence is not being saved"
        RecorderState.PAUSED -> "Tap Resume to continue"
        RecorderState.STOPPING -> "Finalizing the current file"
        RecorderState.ERROR -> snapshot.error
        RecorderState.IDLE -> "Ready"
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        private const val CHANNEL_ID = "active_recording"
        private const val NOTIFICATION_ID = 4101
        private const val REQUEST_PAUSE = 11
        private const val REQUEST_RESUME = 12
        private const val REQUEST_BOOKMARK = 13
        private const val REQUEST_STOP = 14
        private val ACTIVE_STATES = setOf(
            RecorderState.PREPARING,
            RecorderState.LISTENING,
            RecorderState.RECORDING_SOUND,
            RecorderState.SILENCE_SUPPRESSED,
        )
    }
}
