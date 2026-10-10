package org.seedhecode.incidentassistant.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.OnnxJavaType
import org.seedhecode.core.inference.*
import org.json.JSONArray
import org.json.JSONObject
import java.nio.LongBuffer
import kotlin.math.exp

/** Explicit wrapper ABI. Training/export must use this exact vocabulary and normalization.
 * A recurrent CfC export must include its trained unroll inside the graph, not omit state.
 */
class OnnxTextScores(pack: ModelPack, role: String) : AutoCloseable {
    private val spec = pack.spec(role)
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val vocabulary = JSONObject(pack.file(spec.getString("vocabulary")).readText())
    private val maxTokens = spec.getInt("max_tokens")
    val labels: List<String> = spec.getJSONArray("labels").let { a -> (0 until a.length()).map { a.getString(it) } }
    init {
        require(labels.isNotEmpty() && labels.distinct().size == labels.size)
        val created = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(2)
            environment.createSession(pack.artifact(role).absolutePath, options)
        }
        try {
            require(created.inputNames == setOf("input_ids", "attention_mask")) { "ONNX input ABI mismatch for $role" }
            require(created.outputNames.contains("scores")) { "ONNX scores output missing for $role" }
            for (name in listOf("input_ids", "attention_mask")) {
                val info = created.inputInfo.getValue(name).info as? TensorInfo
                    ?: error("$name must be a tensor.")
                require(info.type == OnnxJavaType.INT64 && info.shape.size == 2 &&
                    info.shape[0] in listOf(-1L, 1L) && info.shape[1] in listOf(-1L, maxTokens.toLong())) {
                    "$name must have INT64 [1,max_tokens] ABI for $role."
                }
            }
            val output = created.outputInfo.getValue("scores").info as? TensorInfo
                ?: error("Scores must be a tensor.")
            require(output.type == OnnxJavaType.FLOAT) { "Scores must be FLOAT logits/probabilities." }
            require(spec.getLong("unknown_id") >= 0 && spec.getLong("padding_id") >= 0)
            vocabulary.keys().asSequence().forEach { token ->
                val value = vocabulary.get(token)
                require(value is Number && value.toDouble() >= 0 && value.toDouble() == value.toLong().toDouble()) {
                    "Vocabulary must contain non-negative integer IDs."
                }
            }
            session = created
        } catch (error: Exception) { created.close(); throw error }
    }
    fun score(text: String): Map<String, Double> {
        val tokens = Regex("[\\p{L}\\p{N}_]+|[^\\s\\p{L}\\p{N}_]").findAll(text.lowercase(java.util.Locale.ROOT))
            .map { it.value }.toList()
        require(tokens.size <= maxTokens) { "Text exceeds the trained ONNX input length; no silent truncation." }
        val unknown = spec.getLong("unknown_id")
        val ids = LongArray(maxTokens) { spec.getLong("padding_id") }
        val mask = LongArray(maxTokens)
        tokens.forEachIndexed { index, token -> ids[index] = vocabulary.optLong(token, unknown); mask[index] = 1 }
        OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), longArrayOf(1, maxTokens.toLong())).use { input ->
            OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), longArrayOf(1, maxTokens.toLong())).use { attention ->
                session.run(mapOf("input_ids" to input, "attention_mask" to attention)).use { result ->
                    val tensor = result.get("scores").get() as OnnxTensor
                    val buffer = tensor.floatBuffer
                    val scores = FloatArray(buffer.remaining()); buffer.get(scores)
                    require(scores.size == labels.size && scores.all { it.isFinite() }) { "Invalid model scores." }
                    val probabilities = when (spec.getString("output_kind")) {
                        "logits" -> {
                            val top = scores.maxOrNull()!!.toDouble()
                            val weights = scores.map { exp(it - top) }; val sum = weights.sum()
                            weights.map { it / sum }
                        }
                        "probabilities" -> scores.map { it.toDouble() }.also { p ->
                            require(p.all { it in 0.0..1.0 } && kotlin.math.abs(p.sum() - 1) < 0.01)
                        }
                        else -> error("Unsupported score output kind.")
                    }
                    return labels.zip(probabilities).toMap()
                }
            }
        }
    }
    override fun close() = session.close()
}

class CfcRelevance(pack: ModelPack) : RelevanceModel {
    private val threshold = pack.spec("cfc").getDouble("threshold")
    private val scores = OnnxTextScores(pack, "cfc")
    init {
        try { require(scores.labels.toSet() == setOf("relevant", "filler")); require(threshold in 0.0..1.0) }
        catch (error: Exception) { scores.close(); throw error }
    }
    override fun evaluate(sentence: String, question: String?): Relevance {
        // Context format is part of the export ABI and must match training.
        val input = if (question.isNullOrBlank()) sentence else "question: $question answer: $sentence"
        val probability = scores.score(input).getValue("relevant")
        return Relevance(sentence, probability >= threshold, probability)
    }
    override fun reset() = Unit // State/unroll is internal to the declared sentence graph.
    override fun close() = scores.close()
}

class LayaIncident(pack: ModelPack, taxonomyIds: Set<String>) : IncidentModel {
    private val scores = OnnxTextScores(pack, "laya")
    init {
        try { require(scores.labels.toSet() == taxonomyIds + setOf("none", "undetermined")) {
            "Laya label map does not match the configured 28 incident types."
        } } catch (error: Exception) { scores.close(); throw error }
    }
    override fun classify(text: String): Classification {
        val probabilities = scores.score(text)
        val sorted = probabilities.entries.sortedByDescending { it.value }
        val top = sorted.first()
        val outcome = when (top.key) {
            "none" -> ClassificationOutcome.NONE
            "undetermined" -> ClassificationOutcome.UNDETERMINED
            else -> ClassificationOutcome.INCIDENT
        }
        val candidates = sorted.filter { it.key !in setOf("none", "undetermined") }.take(2)
            .map { IncidentCandidate(it.key, it.value) }
        return Classification(outcome, if (outcome == ClassificationOutcome.NONE) emptyList() else candidates,
            probabilities, outcome == ClassificationOutcome.UNDETERMINED ||
                (outcome == ClassificationOutcome.INCIDENT && top.value < 0.6))
    }
    override fun close() = scores.close()
}
