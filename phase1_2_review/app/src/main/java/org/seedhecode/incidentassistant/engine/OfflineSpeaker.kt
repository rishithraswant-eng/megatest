package org.seedhecode.incidentassistant.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale
import java.util.UUID

/** Local device TTS reference adapter. It never selects a network-required voice. */
class OfflineSpeaker(context: Context, private val onStatus: (String) -> Unit,
    private val onAudioStart: (String, Double) -> Unit) : AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    @Volatile private var closed = false
    @Volatile var speaking = false; private set
    private val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val engine: TextToSpeech = TextToSpeech(context) { code ->
        handler.post {
            if (!closed) initialize(code)
        }
    }
    private fun initialize(code: Int) {
        if (code != TextToSpeech.SUCCESS) { onStatus("Offline speech output unavailable."); return }
        val voice = engine.voices?.filter { !it.isNetworkConnectionRequired && it.locale.language == "en" }
            ?.maxByOrNull { it.quality }
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            onStatus("Install an offline English voice in Android speech settings."); return
        }
        ready = true
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {
                if (closed) return
                speaking = true
                starts.remove(id)?.let { start -> onAudioStart(id, (System.nanoTime() - start) / 1_000_000.0) }
            }
            override fun onDone(id: String) { speaking = false; starts.remove(id) }
            @Deprecated("Legacy callback") override fun onError(id: String) {
                speaking = false; starts.remove(id); handler.post { if (!closed) onStatus("Local speech output failed.") }
            }
            override fun onStop(id: String, interrupted: Boolean) { speaking = false; starts.remove(id) }
        })
        onStatus("Offline English voice ready (Android device TTS reference).")
    }
    fun speak(text: String, responseStartNanos: Long = System.nanoTime()): Boolean {
        if (!ready || closed) { onStatus("Offline voice is not ready; question remains on screen."); return false }
        stop()
        val id = UUID.randomUUID().toString()
        starts[id] = responseStartNanos
        speaking = true
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            starts.remove(id); speaking = false; return false
        }
        return true
    }
    fun stop(): Double {
        val start = System.nanoTime()
        engine.stop(); speaking = false; starts.clear()
        // Stop request latency only; physical acoustic stop must be measured on the kit.
        return (System.nanoTime() - start) / 1_000_000.0
    }
    override fun close() { closed = true; stop(); engine.shutdown() }
}
