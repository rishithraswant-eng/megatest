package org.seedhecode.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import org.seedhecode.core.inference.AudioFrame
import org.seedhecode.core.inference.VoiceEngine
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/** Foreground conversation capture; no fixed turn timer and no raw-audio persistence. */
class ConversationCapture(private val engine: VoiceEngine, private val listener: Listener,
    private val sessionId: String = UUID.randomUUID().toString()) : AutoCloseable {
    interface Listener {
        fun onReady(sampleRate: Int, echoCancellation: Boolean)
        fun onLevel(level: Float, capturedAtNanos: Long)
        fun onStopped()
        fun onError(message: String)
    }
    private val worker = Executors.newSingleThreadExecutor()
    private val active = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var mic: AudioRecord? = null
    fun start() {
        check(!closed.get())
        if (!active.compareAndSet(false, true)) return
        stopping.set(false)
        worker.execute { capture() }
    }
    fun stop() { stopping.set(true); runCatching { mic?.stop() } }

    @SuppressLint("MissingPermission")
    private fun microphone(): Pair<AudioRecord, Int> {
        for (rate in listOf(16000, 48000, 44100)) {
            val minimum = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minimum <= 0) continue
            val candidate = runCatching { AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minimum * 2, rate / 5 * 2)).build() }.getOrNull() ?: continue
            if (candidate.state == AudioRecord.STATE_INITIALIZED) return candidate to rate
            candidate.release()
        }
        error("Microphone does not support conversation capture.")
    }
    private fun capture() {
        var echo: AcousticEchoCanceler? = null
        var begun = false
        try {
            val (recorder, rate) = microphone()
            mic = recorder
            check(!stopping.get()) { "Capture cancelled." }
            if (AcousticEchoCanceler.isAvailable()) echo = runCatching {
                AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }.getOrNull()
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone is busy." }
            engine.beginSession(sessionId, rate); begun = true
            listener.onReady(rate, echo?.enabled == true)
            val buffer = ShortArray(rate / 50) // 20 ms; never wait on inference here.
            var offset = 0L
            var feedbackAt = 0L
            while (!stopping.get()) {
                val count = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) { if (stopping.get()) break else error("Audio capture failed ($count).") }
                engine.accept(AudioFrame(buffer.copyOf(count), rate, offset)); offset += count
                val now = System.nanoTime()
                if (now - feedbackAt >= 50_000_000) {
                    var sum = 0.0
                    for (i in 0 until count) { val value = buffer[i] / 32768.0; sum += value * value }
                    listener.onLevel((sqrt(sum / count) * 4).toFloat().coerceIn(0f, 1f), now)
                    feedbackAt = now
                }
            }
        } catch (error: Exception) {
            if (!stopping.get()) listener.onError(error.message ?: "Microphone error.")
        } finally {
            runCatching { mic?.stop() }; echo?.release(); mic?.release(); mic = null
            if (begun) engine.endSession()
            active.set(false)
            listener.onStopped()
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) { stop(); worker.shutdown() } }
}
