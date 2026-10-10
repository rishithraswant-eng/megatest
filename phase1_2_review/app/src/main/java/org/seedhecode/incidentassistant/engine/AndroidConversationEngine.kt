package org.seedhecode.incidentassistant.engine

import org.seedhecode.core.inference.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Composition boundary for the real engine and the friend's Phase 3-4 consumer. */
class AndroidConversationEngine(
    pack: ModelPack,
    private val repository: LocalRepository,
    private val onTranscript: (TurnKey, Transcript) -> Unit,
    private val onResult: (TurnResult) -> Unit,
    private val onError: (String) -> Unit,
    private val onSpeechStart: () -> Unit,
    private val onStreamingRelevance: (TurnKey, List<Relevance>) -> Unit = { _, _ -> },
    private val tools: LocalTools = repository,
    private val configuredFieldNames: Set<String> = repository.fieldNames,
    private val configuredIncidentIds: Set<String> = repository.incidentIds,
) : VoiceEngine {
    private val speech: WhisperSpeech
    private val extraction: QwenExtraction
    private val pipeline: ConversationPipeline
    private val gate = RevisionGate()
    private val frames = worker("audio-endpoint", 64)
    private val asr = worker("local-asr", 3)
    private val generation = AtomicLong()
    private val captureEpoch = AtomicLong()
    private val lifecycle = Any()
    @Volatile private var closed = false
    @Volatile private var session = java.util.UUID.randomUUID().toString()
    @Volatile private var revision = 0L
    private var turn = 0L
    @Volatile private var key: TurnKey? = null
    @Volatile var language = "auto"
    @Volatile var currentQuestion: String? = null
    private var resampler = PcmResampler(16000)
    private val endpoint = PauseEndpoint()
    private var frameSamples = 0L
    private var previewAt = 0L
    private val english = StringBuilder()
    private val source = StringBuilder()

    init {
        var s: WhisperSpeech? = null; var e: QwenExtraction? = null
        var c: CfcRelevance? = null; var l: LayaIncident? = null
        try {
            s = WhisperSpeech(pack); e = QwenExtraction(pack, configuredFieldNames, configuredIncidentIds)
            if (pack.mode == PipelineMode.FULL) {
                c = CfcRelevance(pack); l = LayaIncident(pack, configuredIncidentIds)
            }
            speech = s; extraction = e
            pipeline = ConversationPipeline(c, l, e, tools, gate, pack.versions(), { result ->
                repository.save(result)
                repository.metric("raw_turn_processing", result.elapsedMs.toDouble())
                result.stageTimingsMs.forEach { (name, millis) -> repository.metric(name, millis) }
                onResult(result)
            }, { _, error -> onError(error) })
        } catch (error: Exception) {
            s?.close(); e?.close(); c?.close(); l?.close(); frames.shutdownNow(); asr.shutdownNow()
            throw error
        }
    }

    override fun beginSession(sessionId: String, sampleRateHz: Int) {
        cancel()
        session = sessionId
        enqueue(frames) {
            resampler = PcmResampler(sampleRateHz); endpoint.reset(); frameSamples = 0; previewAt = 0
        }
    }
    override fun accept(frame: AudioFrame) {
        if (closed) return
        val frameEpoch = captureEpoch.get()
        enqueue(frames) {
            if (frameEpoch != captureEpoch.get()) return@enqueue
            val pcm = resampler.accept(frame.samples)
            val event = endpoint.accept(pcm)
            frameSamples += pcm.size
            if (event.speechStarted) {
                synchronized(lifecycle) {
                    if (closed || frameEpoch != captureEpoch.get()) return@enqueue
                    cancelPending()
                    turn++
                    key = TurnKey(session, turn, revision).also { gate.activate(it) }
                }
                val stamp = generation.get()
                enqueue(asr) { if (generation.get() == stamp) { english.clear(); source.clear() } }
                onSpeechStart()
            }
            val current = key ?: return@enqueue
            val segment = event.segment
            if (segment != null) {
                transcribe(current, segment, committed = true, turnEnded = event.turnEnded)
            } else if (frameSamples - previewAt >= 32000) {
                previewAt = frameSamples
                // A busy ASR lane drops tentative previews, never final speech segments.
                if (asr.queue.isEmpty() && asr.activeCount == 0) endpoint.preview()?.let {
                    transcribe(current, it, committed = false, turnEnded = false)
                }
            }
        }
    }

    private fun transcribe(current: TurnKey, pcm: FloatArray, committed: Boolean, turnEnded: Boolean) {
        val stamp = generation.get()
        val requestedLanguage = language
        enqueue(asr) {
            if (generation.get() != stamp || !gate.valid(current)) return@enqueue
            val transcript = speech.transcribe(pcm, requestedLanguage, committed)
            if (generation.get() != stamp || !gate.valid(current)) return@enqueue
            val en = (english.toString() + " " + transcript.text).trim()
            val original = (source.toString() + " " + transcript.sourceText).trim()
            if (committed) { english.clear().append(en); source.clear().append(original) }
            val full = Transcript(en, original, transcript.language, turnEnded)
            gate.ifCurrent(current) { onTranscript(current, full) }
            if (turnEnded) {
                if (en.isNotBlank()) pipeline.process(current, full, currentQuestion)
                english.clear(); source.clear()
            } else if (en.isNotBlank()) {
                pipeline.observe(current, full, currentQuestion) { decisions, _ -> onStreamingRelevance(current, decisions) }
            }
        }
    }

    /** The app passes its approved English question and advances revision on corrections. */
    fun beginDraft(sessionId: String, revision: Long, question: String?) {
        require(sessionId.isNotBlank() && revision >= 0)
        synchronized(lifecycle) {
            if (closed) return
            if (sessionId == session) require(revision >= this.revision)
            cancel()
            session = sessionId
            this.revision = revision
            currentQuestion = question
        }
    }

    fun updateDraft(revision: Long, question: String?) {
        synchronized(lifecycle) {
            if (closed) return
            require(revision >= this.revision)
            cancel()
            this.revision = revision
            currentQuestion = question
        }
    }
    fun processText(text: String) {
        require(text.isNotBlank() && text.length <= 12000)
        enqueue(frames) {
            val next = synchronized(lifecycle) {
                if (closed) return@enqueue
                cancelPending()
                turn++
                TurnKey(session, turn, revision).also { key = it; gate.activate(it) }
            }
            endpoint.reset()
            val transcript = Transcript(text, text, "en", true)
            onTranscript(next, transcript)
            pipeline.process(next, transcript, currentQuestion)
        }
    }
    fun isCurrent(key: TurnKey): Boolean = gate.valid(key)
    override fun endSession() {
        enqueue(frames) {
            val current = key
            val remaining = endpoint.flush()
            if (current != null && remaining != null) transcribe(current, remaining, true, true)
        }
    }
    private fun cancelPending() {
        synchronized(lifecycle) {
            generation.incrementAndGet(); gate.invalidate(); pipeline.cancel()
            extraction.cancel(); asr.queue.clear()
        }
    }
    fun cancel() {
        synchronized(lifecycle) {
            if (closed) return
            captureEpoch.incrementAndGet()
            cancelPending()
            key = null
            frames.queue.clear()
            runCatching { frames.execute { if (!closed) endpoint.reset() } }
        }
    }
    private fun enqueue(worker: ThreadPoolExecutor, action: () -> Unit) {
        if (closed) return
        try { worker.execute {
            if (!closed) try { action() } catch (error: Exception) {
                onError(error.message ?: "Local engine failed.")
            }
        } } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Continuing after dropped audio could fabricate a coherent transcript.
            cancel()
            onError("Local inference is overloaded. Stop capture and retry.")
        }
    }
    override fun close() {
        synchronized(lifecycle) {
            if (closed) return
            closed = true; captureEpoch.incrementAndGet(); cancelPending(); key = null; frames.queue.clear()
        }
        frames.shutdown(); asr.shutdown()
        Thread {
            frames.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
            asr.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
            speech.close(); pipeline.close()
        }.apply { isDaemon = true; start() }
    }
    private fun worker(name: String, capacity: Int) = ThreadPoolExecutor(1, 1, 0,
        TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity), { action -> Thread(action, name) },
        ThreadPoolExecutor.AbortPolicy())
}
