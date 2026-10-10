package org.seedhecode.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.seedhecode.core.inference.AudioFrame
import org.seedhecode.core.inference.VoiceEngine
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Foreground-only recording. Permission is granted by the caller before start. */
class MicrophoneRecorder(
    private val store: RecordingStore,
    private val engine: VoiceEngine,
) : AutoCloseable {
    interface Listener {
        fun onStarted(sampleRateHz: Int)
        fun onProgress(durationMs: Long, level: Float)
        fun onSaved(recording: VoiceRecording)
        fun onError(message: String)
    }

    @Volatile var listener: Listener? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var microphone: AudioRecord? = null

    fun start() {
        check(!closed.get()) { "Recorder is closed." }
        if (!busy.compareAndSet(false, true)) return
        stopRequested.set(false)
        executor.execute { capture() }
    }

    fun stop() {
        stopRequested.set(true)
        // Unblock a pending read. The worker alone releases AudioRecord.
        runCatching { microphone?.stop() }
    }

    @SuppressLint("MissingPermission")
    private fun createMicrophone(): Pair<AudioRecord, Int> {
        for (rate in listOf(16_000, 48_000, 44_100)) {
            val minBytes = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBytes <= 0) continue
            val candidate = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBytes * 2, rate / 5 * 2))
                .build()
            if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate to rate
            candidate.release()
        }
        error("This microphone does not support PCM recording.")
    }

    private fun capture() {
        var partial: File? = null
        var sessionStarted = false
        var saved: VoiceRecording? = null
        var failure: String? = null
        try {
            val (recorder, rate) = createMicrophone()
            microphone = recorder
            val sessionId = UUID.randomUUID().toString()
            partial = File(store.directory, "$sessionId.wav.part")
            val completed = File(store.directory, "$sessionId.wav")
            WavFile(partial, rate).use { wav ->
                if (stopRequested.get()) error("Recording cancelled before it started.")
                recorder.startRecording()
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Microphone is unavailable. Close other apps using it and retry."
                }
                engine.beginSession(sessionId, rate)
                sessionStarted = true
                listener?.onStarted(rate)
                val buffer = ShortArray(rate / 10)
                val maxSamples = rate * MAX_DURATION_SECONDS.toLong()
                while (!stopRequested.get() && wav.sampleCount < maxSamples) {
                    val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    if (count <= 0) {
                        if (stopRequested.get()) break
                        error("Microphone capture interrupted (code $count).")
                    }
                    val offset = wav.sampleCount
                    wav.append(buffer, count)
                    engine.accept(AudioFrame(buffer.copyOf(count), rate, offset))
                    var sum = 0.0
                    for (index in 0 until count) {
                        val value = buffer[index] / 32768.0
                        sum += value * value
                    }
                    listener?.onProgress(wav.sampleCount * 1000 / rate,
                        (sqrt(sum / count) * 4).toFloat().coerceIn(0f, 1f))
                }
                check(wav.sampleCount > 0) { "No audio captured. Please try again." }
            }
            check(partial.renameTo(completed)) { "Could not save the recording." }
            saved = store.read(completed) ?: error("Could not read the saved recording.")
        } catch (exception: Exception) {
            partial?.delete()
            failure = exception.message ?: "Unable to record audio. Please try again."
        } finally {
            runCatching { microphone?.stop() }
            microphone?.release()
            microphone = null
            if (sessionStarted) runCatching { engine.endSession() }
            busy.set(false)
        }
        // Completion occurs only after resources are released, so the next
        // recording cannot be stopped/released by the previous worker.
        saved?.let { listener?.onSaved(it) }
        failure?.let { listener?.onError(it) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener = null
        stop()
        executor.execute { engine.close() }
        executor.shutdown()
    }

    companion object { const val MAX_DURATION_SECONDS = 120 }
}
