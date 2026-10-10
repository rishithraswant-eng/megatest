package org.seedhecode.core.inference

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Adapted from codebreaker77/Main to the typed 5.1 contract. Each behaviour runs in both pipeline
 * modes: FULL wires fake CfC/Laya models in, ASR_LLM passes null for both, exactly as
 * AndroidConversationEngine does for an asr_llm bundle.
 */
class ReferencePipelineTest {
    private val key = TurnKey("reference", 1, 0)
    private val noTools = object : LocalTools {
        override fun execute(key: TurnKey, request: ToolRequest): ToolResult = error("Unexpected tool")
    }
    /** CfC stand-in: sentences mentioning the weather are irrelevant filler. */
    private fun cfc() = object : RelevanceModel {
        override fun evaluate(sentence: String, question: String?) = Relevance(sentence, !sentence.contains("weather"), 0.9)
        override fun reset() = Unit
        override fun close() = Unit
    }
    private fun laya() = object : IncidentModel {
        override fun classify(text: String) = Classification(ClassificationOutcome.INCIDENT,
            listOf(IncidentCandidate("water_leak", 0.9)), mapOf("water_leak" to 0.9), false)
        override fun close() = Unit
    }
    private fun run(mode: PipelineMode, text: String, extractor: ExtractionModel, tools: LocalTools = noTools): Pair<TurnResult?, String?> {
        val done = CountDownLatch(1)
        var result: TurnResult? = null
        var failure: String? = null
        val full = mode == PipelineMode.FULL
        val pipeline = ConversationPipeline(if (full) cfc() else null, if (full) laya() else null, extractor, tools,
            RevisionGate().apply { activate(key) }, mapOf("llm" to "test-double"),
            { result = it; done.countDown() }, { _, error -> failure = error; done.countDown() })
        try {
            pipeline.process(key, Transcript(text, text, "en", true), "Where is the leak?")
            assertTrue("Pipeline timed out ($mode)", done.await(3, TimeUnit.SECONDS))
            return result to failure
        } finally { pipeline.close() }
    }
    private fun extractor(block: (String, List<ToolResult>, Classification?) -> Extraction) = object : ExtractionModel {
        override fun extract(text: String, question: String?, toolResults: List<ToolResult>, classification: Classification?) =
            block(text, toolResults, classification)
        override fun close() = Unit
    }
    private fun extraction(fields: List<CandidateField> = emptyList(), act: DialogueAct? = DialogueAct.ANSWER,
        calls: List<ToolRequest> = emptyList(), groups: List<String> = emptyList(), distinct: Boolean = false, stop: Boolean = false) =
        Extraction(fields, act, calls, groups, distinctIncidentsMentioned = distinct, userWantsToStopClarifying = stop)

    @Test fun `disabled stages preserve full transcript and evidence offsets without predictions`() {
        val text = "Nice weather.\n  A leak in Room 2."
        val (result, error) = run(PipelineMode.ASR_LLM, text, extractor { actual, _, classification ->
            assertEquals(text, actual)
            // No Laya pass: the closed outcome set reports UNDETERMINED, never a guessed incident.
            assertEquals(ClassificationOutcome.UNDETERMINED, classification!!.outcome)
            val start = actual.indexOf("Room 2")
            extraction(listOf(CandidateField("location", "Room 2", 0.8, Evidence("Room 2", start, start + 6))))
        })
        assertNull(error)
        assertEquals(text.indexOf("Room 2"), result!!.extraction.fields.single().evidence.start)
        assertTrue(result.classification.candidates.isEmpty())
        assertTrue(result.classification.probabilities.isEmpty())
        assertTrue(result.relevance.isEmpty())
        assertEquals("disabled", result.stageStatus["cfc_filter"])
        assertEquals("disabled", result.stageStatus["laya_classifier"])
        assertEquals(setOf("llm_and_tools"), result.stageTimingsMs.keys)
    }

    @Test fun `full pipeline filters filler, classifies and still maps evidence into the full transcript`() {
        val text = "Nice weather.\n  A leak in Room 2."
        val (result, error) = run(PipelineMode.FULL, text, extractor { actual, _, classification ->
            assertFalse(actual.contains("weather"))
            assertEquals(ClassificationOutcome.INCIDENT, classification!!.outcome)
            val start = actual.indexOf("Room 2")
            extraction(listOf(CandidateField("location", "Room 2", 0.8, Evidence("Room 2", start, start + 6))))
        })
        assertNull(error)
        assertEquals(text.indexOf("Room 2"), result!!.extraction.fields.single().evidence.start)
        assertEquals(listOf("water_leak"), result.classification.candidates.map { it.incidentTypeId })
        assertEquals(listOf(false, true), result.relevance.map { it.relevant })
        assertEquals("enabled", result.stageStatus["cfc_filter"])
        assertEquals("enabled", result.stageStatus["laya_classifier"])
        assertEquals(setOf("cfc_filter", "laya_classifier", "llm_and_tools"), result.stageTimingsMs.keys)
    }

    @Test fun `zone tool loop runs in both modes`() {
        val tools = object : LocalTools {
            override fun execute(key: TurnKey, request: ToolRequest) =
                ToolResult(request.name, request.arguments, "{\"requires_confirmation\":true}")
        }
        for (mode in PipelineMode.entries) {
            val (result, error) = run(mode, "Room 2", extractor { _, results, _ ->
                if (results.isEmpty()) extraction(calls = listOf(ToolRequest("get_zone_details", mapOf("zone_name" to "Room 2"))))
                else extraction()
            }, tools)
            assertNull("$mode", error)
            assertEquals("$mode", "get_zone_details", result!!.functionCalls.single().name)
        }
    }

    @Test fun `form lookup cannot guess incident type without classifier`() {
        val (result, error) = run(PipelineMode.ASR_LLM, "Leak", extractor { _, _, _ ->
            extraction(calls = listOf(ToolRequest("get_form_fields", mapOf("incident_type_id" to "water_leak"))))
        })
        assertNull(result)
        assertTrue(error!!.contains("requires an incident classification"))
    }

    @Test fun `form lookup runs once Laya has classified the incident`() {
        val tools = object : LocalTools {
            override fun execute(key: TurnKey, request: ToolRequest) = ToolResult(request.name, request.arguments, "{\"required\":[]}")
        }
        val (result, error) = run(PipelineMode.FULL, "Leak", extractor { _, results, classification ->
            assertEquals(ClassificationOutcome.INCIDENT, classification!!.outcome)
            if (results.isEmpty()) extraction(calls = listOf(ToolRequest("get_form_fields", mapOf("incident_type_id" to "water_leak"))))
            else extraction()
        }, tools)
        assertNull(error)
        assertEquals("get_form_fields", result!!.functionCalls.single().name)
    }

    @Test fun `explicit unknown retains null and evidence in both modes`() {
        for (mode in PipelineMode.entries) {
            val (result, error) = run(mode, "I don't know", extractor { text, _, _ ->
                extraction(listOf(CandidateField("location", null, 0.8, Evidence(text, 0, text.length), "unknown")),
                    act = DialogueAct.UNCERTAINTY)
            })
            assertNull("$mode", error)
            assertEquals(DialogueAct.UNCERTAINTY, result!!.extraction.dialogueAct)
            assertEquals("unknown", result.extraction.fields.single().status)
            assertNull(result.extraction.fields.single().value)
        }
    }

    @Test fun `fabricated evidence is rejected in both modes`() {
        for (mode in PipelineMode.entries) {
            val (result, error) = run(mode, "Room 2", extractor { _, _, _ ->
                extraction(listOf(CandidateField("location", "Room 99", 0.9, Evidence("Room 99", 0, 7))))
            })
            assertNull("$mode", result)
            assertTrue("$mode", error!!.contains("evidence"))
        }
    }

    @Test fun `5_1 fields pass through the pipeline unchanged in both modes`() {
        val text = "Leak in Room 2. Smoke in the lab."
        for (mode in PipelineMode.entries) {
            val (result, error) = run(mode, text, extractor { actual, _, _ ->
                extraction(act = DialogueAct.CORRECTION, groups = listOf("Leak in Room 2.", "Smoke in the lab."),
                    distinct = true, stop = true).also { assertTrue(actual.contains("Smoke")) }
            })
            assertNull("$mode", error)
            val out = result!!.extraction
            assertEquals("$mode", DialogueAct.CORRECTION, out.dialogueAct)
            assertTrue("$mode", out.distinctIncidentsMentioned)
            assertTrue("$mode", out.userWantsToStopClarifying)
            assertEquals("$mode", listOf("Leak in Room 2.", "Smoke in the lab."), out.incidentGroups)
            // The engine never chooses follow-ups; app logic fills them.
            assertNull(result.assistantFollowupText); assertNull(result.assistantFollowupAudioRef)
        }
    }

    @Test fun `pipeline modes reject unsupported manifests`() {
        assertEquals(setOf("asr", "llm"), PipelineMode.parse("asr_llm").roles)
        assertEquals(4, PipelineMode.parse("full").roles.size)
        try { PipelineMode.parse("auto"); fail("Unknown mode accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `blank input does not run extraction or fabricate a classification in either mode`() {
        for (mode in PipelineMode.entries) {
            val (result, error) = run(mode, " \n ", extractor { _, _, _ -> error("Blank input reached LLM") })
            assertNull("$mode", error)
            assertEquals("$mode", ClassificationOutcome.UNDETERMINED, result!!.classification.outcome)
            assertEquals("$mode", "not_run", result.stageStatus["llm"])
            assertEquals("$mode", if (mode == PipelineMode.FULL) "not_run" else "disabled", result.stageStatus["laya_classifier"])
            assertEquals("$mode", if (mode == PipelineMode.FULL) setOf("cfc_filter") else emptySet<String>(), result.stageTimingsMs.keys)
            assertTrue(result.extraction.fields.isEmpty())
            assertNull(result.extraction.dialogueAct)
            assertFalse(result.extraction.distinctIncidentsMentioned)
            assertFalse(result.extraction.userWantsToStopClarifying)
        }
    }
}
