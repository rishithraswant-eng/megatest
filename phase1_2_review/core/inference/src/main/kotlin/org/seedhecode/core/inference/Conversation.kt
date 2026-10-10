package org.seedhecode.core.inference

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

data class TurnKey(val sessionId: String, val turnId: Long, val draftRevision: Long)
data class Transcript(val text: String, val sourceText: String, val language: String, val isFinal: Boolean)
data class Evidence(val text: String, val start: Int, val end: Int)
data class CandidateField(val name: String, val value: Any?, val confidence: Double,
    val evidence: Evidence, val status: String = "known", val provenance: String = "llm")
data class IncidentCandidate(val incidentTypeId: String, val confidence: Double)
/**
 * Laya's closed outcome set. UNDETERMINED is also used when no Laya pass ran, either because
 * there was no relevant speech or because the pipeline runs without Laya ([PipelineMode.ASR_LLM]);
 * [TurnResult.stageStatus] records which.
 */
enum class ClassificationOutcome(val wire: String) {
    INCIDENT("incident"), NONE("none"), UNDETERMINED("undetermined");

    companion object {
        fun fromWire(value: String): ClassificationOutcome = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown classification outcome: $value")
    }
}
data class Classification(val outcome: ClassificationOutcome, val candidates: List<IncidentCandidate>,
    val probabilities: Map<String, Double>, val needsConfirmation: Boolean)
data class ToolRequest(val name: String, val arguments: Map<String, Any?>)
data class ToolResult(val name: String, val arguments: Map<String, Any?>, val result: String)
/** Closed set from the frozen engine contract; "no act" is a null [DialogueAct], never a string. */
enum class DialogueAct(val wire: String) {
    ANSWER("answer"), CORRECTION("correction"), NEGATION("negation"),
    UNCERTAINTY("uncertainty"), CONFIRMATION("confirmation");

    companion object {
        /** Unrecognised wire values are rejected rather than coerced to null. */
        fun fromWire(value: String?): DialogueAct? = when (value) {
            null, "", "null" -> null
            else -> entries.firstOrNull { it.wire == value }
                ?: throw IllegalArgumentException("Unknown dialogue act: $value")
        }
    }
}
data class Extraction(val fields: List<CandidateField>, val dialogueAct: DialogueAct?,
    val calls: List<ToolRequest>, val incidentGroups: List<String> = emptyList(),
    val distinctIncidentsMentioned: Boolean, val userWantsToStopClarifying: Boolean)
data class Relevance(val sentence: String, val relevant: Boolean, val probability: Double)
/** Follow-ups are chosen by app logic from the form state, so the engine leaves them null. */
data class TurnResult(val key: TurnKey, val transcript: Transcript,
    val classification: Classification, val extraction: Extraction,
    val functionCalls: List<ToolResult>, val relevance: List<Relevance>,
    val elapsedMs: Long, val modelVersions: Map<String, String>,
    val stageTimingsMs: Map<String, Double> = emptyMap(),
    val stageStatus: Map<String, String> = emptyMap(),
    val assistantFollowupText: String? = null, val assistantFollowupAudioRef: String? = null)

/** Missing roles are allowed only through an explicitly selected pipeline. */
enum class PipelineMode(val manifestName: String, val roles: Set<String>) {
    FULL("full", setOf("asr", "llm", "cfc", "laya")),
    ASR_LLM("asr_llm", setOf("asr", "llm"));
    companion object {
        fun parse(name: String): PipelineMode = entries.firstOrNull { it.manifestName == name }
            ?: throw IllegalArgumentException("Unsupported pipeline: $name")
    }
}

interface SpeechModel : AutoCloseable {
    fun transcribe(audio16k: FloatArray, language: String, final: Boolean): Transcript
}
interface RelevanceModel : AutoCloseable {
    fun evaluate(sentence: String, question: String?): Relevance
    fun reset()
}
interface IncidentModel : AutoCloseable { fun classify(text: String): Classification }
interface ExtractionModel : AutoCloseable {
    fun extract(text: String, question: String?, toolResults: List<ToolResult>, classification: Classification? = null): Extraction
}
interface LocalTools { fun execute(key: TurnKey, request: ToolRequest): ToolResult }

/** All app-state changes invalidate pending model results and tool calls. */
class RevisionGate {
    @Volatile private var current: TurnKey? = null
    @Synchronized fun activate(key: TurnKey) { current = key }
    @Synchronized fun invalidate() { current = null }
    @Synchronized fun valid(key: TurnKey): Boolean = current == key
    @Synchronized fun <T> ifCurrent(key: TurnKey, action: () -> T): T? =
        if (current == key) action() else null
}

/** Sentence filtering and semantic processing have separate bounded workers. */
class ConversationPipeline(
    private val filter: RelevanceModel?,
    private val classifier: IncidentModel?,
    private val extractor: ExtractionModel,
    private val tools: LocalTools,
    val gate: RevisionGate,
    private val versions: Map<String, String>,
    private val onResult: (TurnResult) -> Unit,
    private val onError: (TurnKey, String) -> Unit,
) : AutoCloseable {
    private val filterWorker = worker("sentence-filter", 8)
    private val semanticWorker = worker("semantic-engine", 4)
    private val epoch = AtomicLong()
    private val previewSequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val stages get() = mapOf("cfc_filter" to if (filter == null) "disabled" else "enabled",
        "laya_classifier" to if (classifier == null) "disabled" else "enabled", "llm" to "enabled")

    private fun decisions(text: String, question: String?): List<Relevance> =
        filter?.let { model -> text.split(Regex("(?<=[.!?])\\s+|\\n+"))
            .filter { it.isNotBlank() }.map { model.evaluate(it, question) } } ?: emptyList()

    private fun accepted(text: String, decisions: List<Relevance>) =
        if (filter == null) text else decisions.filter { it.relevant }.joinToString(" ") { it.sentence }

    /** Tentative inference is useful for streaming, but cannot call tools or commit fields. */
    fun observe(key: TurnKey, transcript: Transcript, question: String?,
        onPreview: (List<Relevance>, Extraction?) -> Unit) {
        if (closed.get()) return
        if (filterWorker.queue.isNotEmpty() || semanticWorker.queue.isNotEmpty()) return
        val sequence = previewSequence.incrementAndGet()
        submit(filterWorker, key) filterTask@ {
            if (!gate.valid(key) || previewSequence.get() != sequence) return@filterTask
            val decisions = decisions(transcript.text, question)
            val relevant = accepted(transcript.text, decisions)
            gate.ifCurrent(key) { if (previewSequence.get() == sequence) onPreview(decisions, null) }
            if (relevant.isNotBlank()) submit(semanticWorker, key) semanticTask@ {
                if (!gate.valid(key) || previewSequence.get() != sequence) return@semanticTask
                val proposed = try { extractor.extract(relevant, question, emptyList()) }
                    catch (_: Exception) { return@semanticTask }
                // Intentionally discard tool requests and uncommitted preview fields.
                gate.ifCurrent(key) { if (previewSequence.get() == sequence) onPreview(decisions, proposed) }
            }
        }
    }

    fun process(key: TurnKey, transcript: Transcript, question: String?) {
        check(!closed.get()) { "Pipeline is closed." }
        require(transcript.isFinal) { "Tentative transcripts cannot execute tools." }
        previewSequence.incrementAndGet()
        val started = System.nanoTime()
        submit(filterWorker, key) filterTask@ {
            if (!gate.valid(key)) return@filterTask
            filter?.reset()
            val filterStart = System.nanoTime()
            val decisions = decisions(transcript.text, question)
            val relevantText = accepted(transcript.text, decisions)
            val filterMs = (System.nanoTime() - filterStart) / 1_000_000.0
            val timings = mutableMapOf<String, Double>()
            if (filter != null) timings["cfc_filter"] = filterMs
            submit(semanticWorker, key) semanticTask@ {
                if (!gate.valid(key)) return@semanticTask
                if (relevantText.isBlank()) {
                    gate.ifCurrent(key) { onResult(TurnResult(key, transcript,
                        // No Laya pass occurred: do not invent a non-incident classification.
                        Classification(ClassificationOutcome.UNDETERMINED, emptyList(), emptyMap(), false),
                        Extraction(emptyList(), null, emptyList(), distinctIncidentsMentioned = false,
                            userWantsToStopClarifying = false), emptyList(), decisions,
                        (System.nanoTime() - started) / 1_000_000, versions,
                        timings, stages + ("llm" to "not_run") + ("laya_classifier" to
                            if (classifier == null) "disabled" else "not_run"))) }
                    return@semanticTask
                }
                val classifyStart = System.nanoTime()
                // Without Laya (ASR_LLM) there is no prediction; stageStatus says "disabled".
                val classification = classifier?.classify(relevantText)
                    ?: Classification(ClassificationOutcome.UNDETERMINED, emptyList(), emptyMap(), false)
                val classifyMs = (System.nanoTime() - classifyStart) / 1_000_000.0
                if (classifier != null) timings["laya_classifier"] = classifyMs
                if (!gate.valid(key)) return@semanticTask
                val extractStart = System.nanoTime()
                val results = mutableListOf<ToolResult>()
                var extraction = extractor.extract(relevantText, question, results, classification)
                var rounds = 0
                while (extraction.calls.isNotEmpty()) {
                    check(rounds++ < 3 && results.size + extraction.calls.size <= 8) {
                        "Model exceeded the local tool-call budget."
                    }
                    for (call in extraction.calls) {
                        require(classifier != null || call.name != "get_form_fields") {
                            "Form lookup requires an incident classification or app-confirmed incident type."
                        }
                        val result = gate.ifCurrent(key) { tools.execute(key, call) } ?: return@semanticTask
                        results.add(result)
                    }
                    if (!gate.valid(key)) return@semanticTask
                    extraction = extractor.extract(relevantText, question, results, classification)
                }
                // Evidence is validated against the accepted English transcript.
                extraction.fields.forEach {
                    require(it.evidence.start >= 0 && it.evidence.end <= relevantText.length &&
                        it.evidence.start < it.evidence.end &&
                        relevantText.substring(it.evidence.start, it.evidence.end) == it.evidence.text) {
                        "Model proposed a field without matching transcript evidence: ${it.name}"
                    }
                    require(it.status in setOf("known", "unknown", "ambiguous"))
                    require(it.status != "known" || it.value != null)
                    require(it.status != "unknown" || it.value == null)
                    require(it.confidence.isFinite() && it.confidence in 0.0..1.0)
                }
                // Export offsets into the full English transcript, not the filtered string.
                val offsets = mutableListOf<Int>()
                if (filter == null) offsets.addAll(transcript.text.indices)
                var cursor = 0
                for (decision in decisions) {
                    val start = transcript.text.indexOf(decision.sentence, cursor)
                    require(start >= 0)
                    cursor = start + decision.sentence.length
                    if (decision.relevant) {
                        if (offsets.isNotEmpty()) offsets.add(start - 1)
                        decision.sentence.indices.forEach { offsets.add(start + it) }
                    }
                }
                extraction = extraction.copy(fields = extraction.fields.map { field ->
                    val start = offsets[field.evidence.start]
                    val end = offsets[field.evidence.end - 1] + 1
                    require(transcript.text.substring(start, end) == field.evidence.text) {
                        "Field evidence crosses discarded speech."
                    }
                    field.copy(evidence = field.evidence.copy(start = start, end = end))
                })
                gate.ifCurrent(key) { onResult(TurnResult(key, transcript, classification,
                    extraction, results.toList(), decisions, (System.nanoTime() - started) / 1_000_000, versions,
                    timings + ("llm_and_tools" to (System.nanoTime() - extractStart) / 1_000_000.0), stages)) }
            }
        }
    }

    private fun submit(worker: ThreadPoolExecutor, key: TurnKey, block: () -> Unit) {
        if (closed.get()) return
        val stamp = epoch.get()
        try {
            worker.execute {
                if (epoch.get() != stamp || !gate.valid(key)) return@execute
                try { block() } catch (error: Exception) {
                    gate.ifCurrent(key) { onError(key, error.message ?: "Local inference failed.") }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            gate.ifCurrent(key) { onError(key, "Inference queue is full; pause and retry.") }
        }
    }

    fun cancel() {
        gate.invalidate()
        epoch.incrementAndGet()
        previewSequence.incrementAndGet()
        filterWorker.queue.clear()
        semanticWorker.queue.clear()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cancel()
        filterWorker.shutdown()
        semanticWorker.shutdown()
        // Close each backend on the lane that owns it, after outstanding work.
        Thread {
            filterWorker.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
            semanticWorker.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS)
            filter?.close(); classifier?.close(); extractor.close()
        }.apply { isDaemon = true; start() }
    }

    private fun worker(name: String, capacity: Int) = ThreadPoolExecutor(1, 1, 0,
        TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity), { task -> Thread(task, name) },
        ThreadPoolExecutor.AbortPolicy())
}
