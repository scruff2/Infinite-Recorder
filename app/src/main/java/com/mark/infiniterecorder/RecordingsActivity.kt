package com.mark.infiniterecorder

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.mark.infiniterecorder.data.SharedStorageRepository
import com.mark.infiniterecorder.databinding.ActivityRecordingsBinding
import com.mark.infiniterecorder.model.RecordingEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class RecordingsActivity : Activity() {
    private lateinit var binding: ActivityRecordingsBinding
    private lateinit var storage: SharedStorageRepository
    private var rows: List<Row> = emptyList()
    private lateinit var adapter: RecordingAdapter
    private var player: MediaPlayer? = null
    private var playingUri: Uri? = null
    private var playerPrepared = false
    private val handler = Handler(Looper.getMainLooper())

    private sealed interface Row {
        data class Day(
            val day: String,
            val entries: List<RecordingEntry>,
            val status: String,
        ) : Row

        data class Audio(val entry: RecordingEntry) : Row
    }

    private val playbackTicker = object : Runnable {
        override fun run() {
            adapter.notifyDataSetChanged()
            if (player != null) handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySystemBarInsets(binding.root)
        storage = SharedStorageRepository(this)
        adapter = RecordingAdapter()
        binding.recordingsList.adapter = adapter
        binding.backButton.setOnClickListener { finish() }
        binding.refreshButton.setOnClickListener { loadRecordings() }
        binding.deleteAllButton.setOnClickListener { confirmDeleteAll() }
        loadRecordings()
    }

    override fun onDestroy() {
        handler.removeCallbacks(playbackTicker)
        player?.release()
        player = null
        playerPrepared = false
        super.onDestroy()
    }

    private fun loadRecordings() {
        binding.summaryText.text = "Loading recordings…"
        thread(name = "InfiniteRecorder-List") {
            val entries = runCatching { storage.listRecordings() }
            val failure = entries.exceptionOrNull()
            val list = entries.getOrDefault(emptyList())
            val grouped = list.groupBy { it.day }
            val newRows = buildList {
                grouped.toSortedMap(compareByDescending { it }).forEach { (day, dayEntries) ->
                    add(Row.Day(day, dayEntries, storage.processingStatus(day)))
                    dayEntries.forEach { add(Row.Audio(it)) }
                }
            }
            runOnUiThread {
                rows = newRows
                adapter.notifyDataSetChanged()
                binding.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                binding.recordingsList.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                binding.summaryText.text = failure?.message ?: run {
                    val size = list.sumOf { it.sizeBytes }
                    "${list.size} files • ${formatBytes(size)} • grouped by day"
                }
            }
        }
    }

    private fun playOrPause(entry: RecordingEntry) {
        if (entry.isPartial) {
            Toast.makeText(
                this,
                "This interrupted file is incomplete and may not be playable.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (playingUri == entry.uri && player != null) {
            if (!playerPrepared) return
            player?.let {
                if (it.isPlaying) it.pause() else it.start()
            }
            adapter.notifyDataSetChanged()
            return
        }
        player?.release()
        playerPrepared = false
        player = MediaPlayer().apply {
            setDataSource(this@RecordingsActivity, entry.uri)
            setOnPreparedListener {
                playerPrepared = true
                it.start()
                handler.removeCallbacks(playbackTicker)
                handler.post(playbackTicker)
                adapter.notifyDataSetChanged()
            }
            setOnCompletionListener {
                playingUri = null
                it.release()
                player = null
                playerPrepared = false
                handler.removeCallbacks(playbackTicker)
                adapter.notifyDataSetChanged()
            }
            setOnErrorListener { _, what, extra ->
                Toast.makeText(
                    this@RecordingsActivity,
                    "Playback failed ($what/$extra)",
                    Toast.LENGTH_LONG,
                ).show()
                playingUri = null
                playerPrepared = false
                true
            }
            prepareAsync()
        }
        playingUri = entry.uri
        adapter.notifyDataSetChanged()
    }

    private fun share(entry: RecordingEntry) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, entry.uri)
            clipData = ClipData.newUri(contentResolver, entry.displayName, entry.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share recording"))
    }

    private fun open(entry: RecordingEntry) {
        if (entry.isPartial) {
            Toast.makeText(this, "Incomplete recordings cannot be opened as audio.", Toast.LENGTH_LONG)
                .show()
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(entry.uri, "audio/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }.onFailure {
            Toast.makeText(this, "No compatible audio app is installed.", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(entry: RecordingEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete recording?")
            .setMessage(entry.displayName)
            .setPositiveButton("Delete") { _, _ ->
                if (playingUri == entry.uri) {
                    player?.release()
                    player = null
                    playingUri = null
                    playerPrepared = false
                }
                thread {
                    val deleted = runCatching { storage.delete(entry) }.getOrDefault(false)
                    if (deleted) {
                        runCatching { storage.markRecordingDeleted(entry.day, entry.displayName) }
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (deleted) "Recording deleted" else "Could not delete recording",
                            Toast.LENGTH_SHORT,
                        ).show()
                        loadRecordings()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareDay(day: Row.Day) {
        val uris = ArrayList(day.entries.map { it.uri })
        storage.dailyManifestUri(day.day)?.let(uris::add)
        if (uris.isEmpty()) return
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(contentResolver, day.day, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share ${day.day}"))
    }

    private fun confirmDeleteDay(day: Row.Day) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${friendlyDay(day.day)}?")
            .setMessage(
                "This permanently deletes ${day.entries.size} recording file(s) " +
                    "and the day's timeline metadata.",
            )
            .setPositiveButton("Delete day") { _, _ ->
                releasePlayerIfNeeded(day.entries)
                thread {
                    val deleted = runCatching {
                        storage.deleteDay(day.day, day.entries)
                    }.getOrDefault(false)
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (deleted) "Daily recording deleted" else "Some files could not be deleted",
                            Toast.LENGTH_LONG,
                        ).show()
                        loadRecordings()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAll() {
        val days = rows.filterIsInstance<Row.Day>()
        val count = days.sumOf { it.entries.size }
        if (count == 0) {
            Toast.makeText(this, "There are no completed recordings to delete.", Toast.LENGTH_SHORT)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Delete all recordings?")
            .setMessage(
                "This permanently deletes $count completed recording file(s) " +
                    "and all associated daily timeline metadata.",
            )
            .setPositiveButton("Delete all") { _, _ ->
                releasePlayerIfNeeded(days.flatMap { it.entries })
                thread {
                    val success = days.all { day ->
                        runCatching { storage.deleteDay(day.day, day.entries) }.getOrDefault(false)
                    }
                    runOnUiThread {
                        Toast.makeText(
                            this,
                            if (success) "All recordings deleted" else "Some files could not be deleted",
                            Toast.LENGTH_LONG,
                        ).show()
                        loadRecordings()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun releasePlayerIfNeeded(entries: List<RecordingEntry>) {
        if (entries.none { it.uri == playingUri }) return
        player?.release()
        player = null
        playerPrepared = false
        playingUri = null
        handler.removeCallbacks(playbackTicker)
    }

    private inner class RecordingAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.Day) 0 else 1
        override fun isEnabled(position: Int): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val row = rows[position]) {
                is Row.Day -> dayView(row, convertView, parent)
                is Row.Audio -> audioView(row.entry, convertView, parent)
            }
        }

        private fun dayView(row: Row.Day, recycled: View?, parent: ViewGroup): View {
            val view = if (recycled?.tag == "day") recycled else {
                LayoutInflater.from(this@RecordingsActivity)
                    .inflate(R.layout.item_day_header, parent, false)
                    .apply { tag = "day" }
            }
            view.findViewById<TextView>(R.id.dayText).text = friendlyDay(row.day)
            view.findViewById<TextView>(R.id.dayDetailText).text =
                "${row.entries.size} files • ${formatBytes(row.entries.sumOf { it.sizeBytes })}"
            val spinner = view.findViewById<Spinner>(R.id.statusSpinner)
            val statuses = listOf("Unprocessed", "Processed", "Keep")
            spinner.adapter = ArrayAdapter(
                this@RecordingsActivity,
                android.R.layout.simple_spinner_item,
                statuses,
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            spinner.setSelection(statuses.indexOf(row.status).coerceAtLeast(0), false)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    selected: View?,
                    index: Int,
                    id: Long,
                ) {
                    val status = statuses[index]
                    if (status != row.status) thread { storage.setProcessingStatus(row.day, status) }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
            view.findViewById<Button>(R.id.shareDayButton).setOnClickListener { shareDay(row) }
            view.findViewById<Button>(R.id.deleteDayButton).setOnClickListener {
                confirmDeleteDay(row)
            }
            return view
        }

        private fun audioView(
            entry: RecordingEntry,
            recycled: View?,
            parent: ViewGroup,
        ): View {
            val view = if (recycled?.tag == "audio") recycled else {
                LayoutInflater.from(this@RecordingsActivity)
                    .inflate(R.layout.item_recording, parent, false)
                    .apply { tag = "audio" }
            }
            val isCurrent = playingUri == entry.uri
            val currentPlayer = player
            view.findViewById<TextView>(R.id.nameText).text = entry.displayName
            view.findViewById<TextView>(R.id.detailText).text =
                if (entry.isPartial) {
                    "${formatTime(entry.dateAddedSeconds * 1000L)} • INTERRUPTED PARTIAL • " +
                        formatBytes(entry.sizeBytes)
                } else {
                    "${formatTime(entry.dateAddedSeconds * 1000L)} • " +
                        "${formatDuration(entry.durationMs)} • ${formatBytes(entry.sizeBytes)}"
                }
            view.findViewById<Button>(R.id.playButton).apply {
                val isPlaying = isCurrent && playerPrepared &&
                    runCatching { currentPlayer?.isPlaying == true }.getOrDefault(false)
                text = if (isPlaying) "Pause" else "Play"
                isEnabled = !entry.isPartial
                setOnClickListener { playOrPause(entry) }
            }
            view.findViewById<Button>(R.id.shareButton).setOnClickListener { share(entry) }
            view.findViewById<Button>(R.id.openButton).setOnClickListener { open(entry) }
            view.findViewById<Button>(R.id.openButton).isEnabled = !entry.isPartial
            view.findViewById<Button>(R.id.deleteButton).setOnClickListener {
                confirmDelete(entry)
            }
            view.findViewById<SeekBar>(R.id.playbackSeek).apply {
                visibility = if (isCurrent && currentPlayer != null) View.VISIBLE else View.GONE
                if (isCurrent && playerPrepared && currentPlayer != null) {
                    val duration = runCatching { currentPlayer.duration }.getOrDefault(0)
                    if (duration > 0) {
                        progress = (
                            runCatching { currentPlayer.currentPosition }.getOrDefault(0) *
                                1000L / duration
                            ).toInt()
                    }
                }
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                        if (fromUser && isCurrent && playerPrepared && currentPlayer != null) {
                            currentPlayer.seekTo((currentPlayer.duration * value / 1000.0).toInt())
                        }
                    }

                    override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                    override fun onStopTrackingTouch(bar: SeekBar?) = Unit
                })
            }
            return view
        }
    }

    private fun friendlyDay(day: String): String = runCatching {
        val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(day)!!
        SimpleDateFormat("EEEE, MMMM d", Locale.US).format(parsed)
    }.getOrDefault(day)

    private fun formatTime(timeMs: Long): String =
        SimpleDateFormat("h:mm a", Locale.US).format(Date(timeMs))

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "duration pending"
        val total = ms / 1000L
        return if (total >= 3600L) {
            String.format(Locale.US, "%d:%02d:%02d", total / 3600, total / 60 % 60, total % 60)
        } else {
            String.format(Locale.US, "%d:%02d", total / 60, total % 60)
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
        bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.0f KB", bytes / 1e3)
    }
}
