package org.seedhecode.core.inference

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConversationPipelineTest {
    private val key = TurnKey("session", 1, 0)
    private fun classifier() = object : IncidentModel {
        override fun classify(text: String) = Classification(ClassificationOutcome.INCIDENT,
            listOf(IncidentCandidate("water_leak", 0.8)), mapOf("water_leak" to 0.8), false)
        override fun close() = Unit
    }
    private fun filter() = object : RelevanceModel {
        override fun evaluate(sentence: String, question: String?) = Relevance(sentence, !sentence.contains("weather"), 0.9)
        override fun reset() = Unit
        override fun close() = Unit
    }
    @Test fun `filler is excluded and evidence offsets refer to full transcript`() {
        val finished = CountDownLatch(1)
        var result: TurnResult? = null
        var error: String? = null
        val extractor = object : ExtractionModel {
            override fun extract(text: String, question: String?, toolResults: List<ToolResult>, classification: Classification?): Extraction {
                assertFalse(text.contains("weather"))
                val start = text.indexOf("leak")
                return Extraction(listOf(CandidateField("description", "leak", 0.8,
                    Evidence("leak", start, start + 4))), DialogueAct.ANSWER, emptyList(),
                    distinctIncidentsMentioned = false, userWantsToStopClarifying = false)
            }
            override fun close() = Unit
        }
        val gate = RevisionGate().apply { activate(key) }
        val tools = object : LocalTools { override fun execute(key: TurnKey, request: ToolRequest): ToolResult = error("Unexpected tool call") }
        val pipeline = ConversationPipeline(filter(), classifier(), extractor, tools, gate, emptyMap(),
            { result = it; finished.countDown() }, { _, message -> error = message; finished.countDown() })
        try {
            val text = "Nice weather. There is a leak."
            pipeline.process(key, Transcript(text, text, "en", true), null)
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertNull(error)
            val field = result!!.extraction.fields.single()
            assertEquals("leak", text.substring(field.evidence.start, field.evidence.end))
            assertEquals(text.indexOf("leak"), field.evidence.start)
        } finally { pipeline.close() }
    }

    @Test fun `revision change while model runs prevents tools and result publication`() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val exited = CountDownLatch(1)
        val calls = AtomicInteger(); val outputs = AtomicInteger()
        val extractor = object : ExtractionModel {
            override fun extract(text: String, question: String?, toolResults: List<ToolResult>, classification: Classification?): Extraction {
                entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)); exited.countDown()
                return Extraction(emptyList(), DialogueAct.ANSWER, listOf(ToolRequest("get_zone_details", mapOf("zone_name" to "Room 2"))),
                    distinctIncidentsMentioned = false, userWantsToStopClarifying = false)
            }
            override fun close() = Unit
        }
        val gate = RevisionGate().apply { activate(key) }
        val tools = object : LocalTools { override fun execute(key: TurnKey, request: ToolRequest): ToolResult {
            calls.incrementAndGet(); return ToolResult(request.name, request.arguments, "{}")
        } }
        val pipeline = ConversationPipeline(filter(), classifier(), extractor, tools, gate, emptyMap(),
            { outputs.incrementAndGet() }, { _, _ -> outputs.incrementAndGet() })
        try {
            pipeline.process(key, Transcript("There is a leak.", "There is a leak.", "en", true), null)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            gate.activate(key.copy(draftRevision = 1))
            release.countDown(); assertTrue(exited.await(3, TimeUnit.SECONDS))
            pipeline.close()
            assertEquals(0, calls.get()); assertEquals(0, outputs.get())
        } finally { release.countDown(); pipeline.close() }
    }

    @Test fun `fabricated evidence is rejected instead of marked known`() {
        val finished = CountDownLatch(1); var error: String? = null
        val extractor = object : ExtractionModel {
            override fun extract(text: String, question: String?, toolResults: List<ToolResult>, classification: Classification?) = Extraction(
                listOf(CandidateField("floor", 99, 0.9, Evidence("floor 99", 0, 8))), DialogueAct.ANSWER, emptyList(),
                distinctIncidentsMentioned = false, userWantsToStopClarifying = false)
            override fun close() = Unit
        }
        val gate = RevisionGate().apply { activate(key) }
        val pipeline = ConversationPipeline(filter(), classifier(), extractor,
            object : LocalTools { override fun execute(key: TurnKey, request: ToolRequest) = error("Unexpected call") },
            gate, emptyMap(), { fail("Ungrounded result must not publish") }, { _, message -> error = message; finished.countDown() })
        try {
            pipeline.process(key, Transcript("There is a leak.", "There is a leak.", "en", true), null)
            assertTrue(finished.await(3, TimeUnit.SECONDS)); assertTrue(error!!.contains("evidence"))
        } finally { pipeline.close() }
    }

    @Test fun `tentative transcript cannot run final tools`() {
        val gate = RevisionGate().apply { activate(key) }
        val pipeline = ConversationPipeline(filter(), classifier(), object : ExtractionModel {
            override fun extract(text: String, question: String?, toolResults: List<ToolResult>, classification: Classification?) = error("Unexpected inference")
            override fun close() = Unit
        }, object : LocalTools { override fun execute(key: TurnKey, request: ToolRequest) = error("Unexpected tool") },
            gate, emptyMap(), {}, { _, _ -> })
        try {
            try { pipeline.process(key, Transcript("tentative", "tentative", "en", false), null); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        } finally { pipeline.close() }
    }

    @Test fun `streaming extraction preview never executes requested tools`() {
        val finished = CountDownLatch(1)
        val calls = AtomicInteger(); val published = AtomicInteger()
        val gate = RevisionGate().apply { activate(key) }
        val extractor = object : ExtractionModel {
            override fun extract(text: String, question: String?, toolResults: List<ToolResult>, classification: Classification?) =
                Extraction(emptyList(), DialogueAct.ANSWER, listOf(ToolRequest("get_zone_details", mapOf("zone_name" to "Room 2"))),
                    distinctIncidentsMentioned = false, userWantsToStopClarifying = false)
            override fun close() = Unit
        }
        val tools = object : LocalTools {
            override fun execute(key: TurnKey, request: ToolRequest): ToolResult {
                calls.incrementAndGet(); return ToolResult(request.name, request.arguments, "{}")
            }
        }
        val pipeline = ConversationPipeline(filter(), classifier(), extractor, tools, gate, emptyMap(),
            { published.incrementAndGet() }, { _, message -> fail(message) })
        try {
            pipeline.observe(key, Transcript("There is a leak.", "There is a leak.", "en", false), null) { _, proposed ->
                if (proposed != null) finished.countDown()
            }
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(0, calls.get()); assertEquals(0, published.get())
        } finally { pipeline.close() }
    }

    @Test fun `dialogue act wire values round trip and unknown values are rejected`() {
        for (act in DialogueAct.entries) assertEquals(act, DialogueAct.fromWire(act.wire))
        assertEquals(listOf("answer", "correction", "negation", "uncertainty", "confirmation"),
            DialogueAct.entries.map { it.wire })
        assertNull(DialogueAct.fromWire(null)); assertNull(DialogueAct.fromWire("")); assertNull(DialogueAct.fromWire("null"))
        try { DialogueAct.fromWire("Answer"); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `classification outcome wire values round trip and unknown values are rejected`() {
        for (outcome in ClassificationOutcome.entries) assertEquals(outcome, ClassificationOutcome.fromWire(outcome.wire))
        assertEquals(listOf("incident", "none", "undetermined"), ClassificationOutcome.entries.map { it.wire })
        try { ClassificationOutcome.fromWire("unknown"); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `blank relevant text publishes undetermined without a Laya pass`() {
        val finished = CountDownLatch(1); var result: TurnResult? = null
        val gate = RevisionGate().apply { activate(key) }
        val pipeline = ConversationPipeline(filter(), object : IncidentModel {
            override fun classify(text: String): Classification = error("Laya must not run")
            override fun close() = Unit
        }, object : ExtractionModel {
            override fun extract(text: String, question: String?, toolResults: List<ToolResult>, classification: Classification?) = error("Unexpected inference")
            override fun close() = Unit
        }, object : LocalTools { override fun execute(key: TurnKey, request: ToolRequest) = error("Unexpected tool") },
            gate, emptyMap(), { result = it; finished.countDown() }, { _, message -> fail(message) })
        try {
            pipeline.process(key, Transcript("Nice weather.", "Nice weather.", "en", true), null)
            assertTrue(finished.await(3, TimeUnit.SECONDS))
            assertEquals(ClassificationOutcome.UNDETERMINED, result!!.classification.outcome)
            assertNull(result!!.extraction.dialogueAct)
            assertFalse(result!!.extraction.distinctIncidentsMentioned)
        } finally { pipeline.close() }
    }
}
