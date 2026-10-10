package org.seedhecode.incidentassistant

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.seedhecode.core.audio.MicrophoneRecorder
import org.seedhecode.core.audio.RecordingStore
import org.seedhecode.core.audio.VoiceRecording
import org.seedhecode.core.inference.CaptureOnlyVoiceEngine
import org.seedhecode.incidentassistant.feature.report.CaptureState
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), MicrophoneRecorder.Listener {
    private lateinit var status: TextView
    private lateinit var timer: TextView
    private lateinit var level: ProgressBar
    private lateinit var recordButton: Button
    private lateinit var library: LinearLayout
    private lateinit var store: RecordingStore
    private lateinit var recorder: MicrophoneRecorder
    private var captureState = CaptureState.IDLE
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var foreground = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.capture_status)
        timer = findViewById(R.id.capture_timer)
        level = findViewById(R.id.microphone_level)
        recordButton = findViewById(R.id.record_button)
        library = findViewById(R.id.recording_library)
        findViewById<Button>(R.id.conversation_button).setOnClickListener {
            startActivity(Intent(this, ConversationActivity::class.java))
        }
        findViewById<Button>(R.id.admin_button).setOnClickListener {
            startActivity(Intent(this, AdminActivity::class.java))
        }
        try {
            store = RecordingStore(File(filesDir, "recordings"))
            recorder = MicrophoneRecorder(store, CaptureOnlyVoiceEngine()).also { it.listener = this }
        } catch (exception: Exception) {
            status.text = exception.message ?: getString(R.string.recording_failed)
            recordButton.isEnabled = false
            return
        }
        recordButton.setOnClickListener {
            when (captureState) {
                CaptureState.RECORDING -> finishCapture()
                CaptureState.IDLE -> requestCapture()
                else -> Unit
            }
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::store.isInitialized) refreshLibrary()
    }

    private fun requestCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startCapture()
            return
        }
        val preferences = getPreferences(MODE_PRIVATE)
        val askedBefore = preferences.getBoolean("microphone_permission_requested", false)
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_title).setMessage(R.string.permission_settings)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")))
                }.setNegativeButton(R.string.cancel, null).show()
        } else if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            AlertDialog.Builder(this)
                .setTitle(R.string.permission_title).setMessage(R.string.permission_explanation)
                .setPositiveButton(R.string.continue_action) { _, _ -> askPermission() }
                .setNegativeButton(R.string.cancel, null).show()
        } else {
            askPermission()
        }
    }

    private fun askPermission() {
        getPreferences(MODE_PRIVATE).edit().putBoolean("microphone_permission_requested", true).apply()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != MICROPHONE_PERMISSION) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED && foreground) {
            startCapture()
        } else {
            status.setText(R.string.permission_denied)
        }
    }

    private fun startCapture() {
        if (!foreground || captureState != CaptureState.IDLE) return
        stopPlayback()
        captureState = CaptureState.STARTING
        status.setText(R.string.starting)
        timer.text = durationText(0)
        level.progress = 0
        updateControls()
        runCatching { recorder.start() }.onFailure {
            captureState = CaptureState.IDLE
            status.text = it.message ?: getString(R.string.recording_failed)
            updateControls()
        }
    }

    private fun finishCapture() {
        captureState = CaptureState.SAVING
        status.setText(R.string.saving)
        level.progress = 0
        updateControls()
        recorder.stop()
    }

    override fun onStarted(sampleRateHz: Int) = onUi {
        if (!foreground || captureState == CaptureState.SAVING) {
            recorder.stop()
        } else {
            captureState = CaptureState.RECORDING
            status.setText(R.string.listening)
            updateControls()
        }
    }

    override fun onProgress(durationMs: Long, level: Float) = onUi {
        if (captureState == CaptureState.RECORDING) {
            timer.text = durationText(durationMs)
            this.level.progress = (level * 100).toInt()
        }
    }

    override fun onSaved(recording: VoiceRecording) = onUi {
        captureState = CaptureState.IDLE
        status.setText(R.string.saved)
        timer.text = durationText(recording.durationMs)
        level.progress = 0
        updateControls()
    }

    override fun onError(message: String) = onUi {
        captureState = CaptureState.IDLE
        status.text = message
        level.progress = 0
        updateControls()
    }

    private fun onUi(action: () -> Unit) {
        runOnUiThread { if (!isDestroyed && !isFinishing) action() }
    }

    private fun updateControls() {
        recordButton.isEnabled = captureState == CaptureState.IDLE || captureState == CaptureState.RECORDING
        recordButton.setText(if (captureState == CaptureState.RECORDING) R.string.stop_recording else R.string.record)
        refreshLibrary()
    }

    private fun refreshLibrary() {
        library.removeAllViews()
        val recordings = store.list()
        if (recordings.isEmpty()) {
            library.addView(label(getString(R.string.empty_library), 14f, secondary = true))
            return
        }
        recordings.forEach { recording ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(18), dp(18), dp(12))
                setBackgroundResource(R.drawable.card_background)
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            }
            val date = Date(recording.createdAtMs)
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(date)
            card.addView(label(getString(R.string.clip_title, time), 17f))
            card.addView(label(getString(R.string.clip_details,
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(date),
                durationText(recording.durationMs),
                String.format(Locale.US, "%.1f", recording.sampleRateHz / 1000.0)), 12f, true))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(Button(this).apply {
                setText(if (playingFile == recording.file) R.string.stop_playback else R.string.play)
                isAllCaps = false
                isEnabled = captureState == CaptureState.IDLE
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener {
                    if (playingFile == recording.file) stopPlayback() else play(recording)
                    refreshLibrary()
                }
            })
            actions.addView(Button(this).apply {
                setText(R.string.delete)
                isAllCaps = false
                isEnabled = captureState == CaptureState.IDLE
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { confirmDelete(recording) }
            })
            card.addView(actions)
            library.addView(card)
        }
    }

    private fun label(text: String, size: Float, secondary: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(getColor(if (secondary) R.color.text_secondary else R.color.text_primary))
        setPadding(0, 0, 0, dp(8))
    }

    private fun play(recording: VoiceRecording) {
        stopPlayback()
        val next = MediaPlayer()
        player = next
        playingFile = recording.file
        try {
            next.setDataSource(recording.file.absolutePath)
            next.setOnPreparedListener { if (player === it && foreground) it.start() }
            next.setOnCompletionListener { if (player === it) { stopPlayback(); refreshLibrary() } }
            next.setOnErrorListener { mediaPlayer, _, _ ->
                if (player === mediaPlayer) {
                    stopPlayback()
                    refreshLibrary()
                    Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show()
                }
                true
            }
            next.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
            Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        playingFile = null
    }

    private fun confirmDelete(recording: VoiceRecording) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title).setMessage(R.string.delete_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (playingFile == recording.file) stopPlayback()
                if (!store.delete(recording)) Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                refreshLibrary()
            }.show()
    }

    override fun onStop() {
        foreground = false
        stopPlayback()
        if (::recorder.isInitialized && captureState != CaptureState.IDLE) finishCapture()
        super.onStop()
    }

    override fun onDestroy() {
        if (::recorder.isInitialized) recorder.close()
        super.onDestroy()
    }

    private fun durationText(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return getString(R.string.timer, seconds / 60, seconds % 60)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object { private const val MICROPHONE_PERMISSION = 100 }
}
