package org.seedhecode.incidentassistant.engine

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import org.seedhecode.core.inference.*

/** Adapted from codebreaker77/Main: the extraction contract now includes the two 5.1 flags and typed acts. */
class QwenResponseTest {
    private val empty = """{"fields":[],"dialogue_act":null,"tool_calls":[],"incident_groups":[],""" +
        """"distinct_incidents_mentioned":false,"user_wants_to_stop_clarifying":false}"""
    private fun parse(raw: String, mode: PipelineMode = PipelineMode.ASR_LLM) =
        QwenResponse.parse(raw, "Leak in Room 2", setOf("location"), mode)
    private fun rejects(raw: String) {
        try { parse(raw); fail("Invalid output accepted: $raw") }
        catch (_: IllegalArgumentException) { }
    }
    private fun with(key: String, value: Any?) = JSONObject(empty).put(key, value ?: JSONObject.NULL).toString()

    @Test fun `empty extraction is valid only with complete contract`() {
        val parsed = parse(empty)
        assertTrue(parsed.fields.isEmpty())
        assertNull(parsed.dialogueAct)
        assertFalse(parsed.distinctIncidentsMentioned); assertFalse(parsed.userWantsToStopClarifying)
        rejects("{}")
        // Main's four-key shape, without the 5.1 flags, no longer satisfies the contract.
        rejects("""{"fields":[],"dialogue_act":null,"tool_calls":[],"incident_groups":[]}""")
        rejects(with("extra_key", 1))
    }
    @Test fun `5_1 flags are parsed as booleans and nothing else`() {
        val raw = JSONObject(empty).put("distinct_incidents_mentioned", true).put("user_wants_to_stop_clarifying", true).toString()
        val parsed = parse(raw)
        assertTrue(parsed.distinctIncidentsMentioned); assertTrue(parsed.userWantsToStopClarifying)
        for (bad in listOf("true", 1, null)) {
            rejects(with("distinct_incidents_mentioned", bad))
            rejects(with("user_wants_to_stop_clarifying", bad))
        }
    }
    @Test fun `dialogue acts map to the enum and unknown acts are rejected`() {
        for (act in DialogueAct.entries) assertEquals(act, parse(with("dialogue_act", act.wire)).dialogueAct)
        rejects(with("dialogue_act", "Answer"))
        rejects(with("dialogue_act", "agreement"))
    }
    @Test fun `valid evidence and confidence produce grounded field`() {
        val raw = JSONObject(empty).put("fields", org.json.JSONArray("""[{"field_name":"location","value":"Room 2","confidence":0.8,"evidence":"Room 2","status":"known"}]"""))
        assertEquals(8, parse(raw.toString()).fields.single().evidence.start)
    }
    @Test fun `hallucinated evidence is rejected`() {
        rejects(JSONObject(empty).put("fields", org.json.JSONArray("""[{"field_name":"location","value":"Room 99","confidence":0.8,"evidence":"Room 99","status":"known"}]""")).toString())
    }
    @Test fun `unsupported tool and unclassified form lookup rejected`() {
        for (name in listOf("submit_report", "get_form_fields")) {
            rejects(JSONObject(empty).put("tool_calls", org.json.JSONArray("""[{"name":"$name","arguments":{}}]""")).toString())
        }
        // With Laya present the same form lookup is allowed.
        val form = JSONObject(empty).put("tool_calls", org.json.JSONArray("""[{"name":"get_form_fields","arguments":{"incident_type_id":"water_leak"}}]"""))
        assertEquals("get_form_fields", parse(form.toString(), PipelineMode.FULL).calls.single().name)
    }
    @Test fun `bad confidence and status rejected`() {
        val raw = """{"field_name":"location","value":"Room 2","confidence":0.8,"evidence":"Room 2","status":"known"}"""
        for (field in listOf(JSONObject(raw).put("confidence", 2), JSONObject(raw).put("status", "confirmed"))) {
            rejects(JSONObject(empty).put("fields", org.json.JSONArray().put(field)).toString())
        }
    }
    @Test fun `model bundle mode selects only installed roles`() {
        val models = JSONObject().put("asr", JSONObject().put("version", "base").put("backend", "whisper.cpp-cpu"))
            .put("llm", JSONObject().put("version", "qwen").put("backend", "llama.cpp-cpu"))
        val pack = ModelPack(java.io.File("."), JSONObject().put("pipeline", "asr_llm").put("models", models))
        assertEquals(PipelineMode.ASR_LLM, pack.mode)
        assertEquals(setOf("asr", "llm"), pack.versions().keys)
    }
    @Test fun `known null and unknown guessed values rejected`() {
        for ((status, value) in listOf("known" to "null", "unknown" to "\"Room 2\"")) {
            rejects(JSONObject(empty).put("fields", org.json.JSONArray(
                """[{"field_name":"location","value":$value,"confidence":0.8,"evidence":"Room 2","status":"$status"}]""")).toString())
        }
    }
    @Test fun `export explicitly declares deferred stages and no classification`() {
        val result = TurnResult(TurnKey("reference", 1, 0), Transcript("Leak", "Leak", "en", true),
            Classification(ClassificationOutcome.UNDETERMINED, emptyList(), emptyMap(), false),
            Extraction(emptyList(), null, emptyList(), distinctIncidentsMentioned = false, userWantsToStopClarifying = false),
            emptyList(), emptyList(), 1, mapOf("llm" to "qwen"),
            stageStatus = mapOf("cfc_filter" to "disabled", "laya_classifier" to "disabled", "llm" to "enabled"))
        val json = result.toJson()
        assertEquals(0, json.getJSONArray("candidate_incident_types").length())
        assertTrue(json.isNull("dialogue_act"))
        assertFalse(json.getBoolean("distinct_incidents_mentioned")); assertFalse(json.getBoolean("user_wants_to_stop_clarifying"))
        assertTrue(json.isNull("assistant_followup_text")); assertTrue(json.isNull("assistant_followup_audio_ref"))
        val metadata = json.getJSONObject("metadata")
        // Main exported "not_run" here; the typed contract says UNDETERMINED and stage_status records why.
        assertEquals("undetermined", metadata.getString("classification_outcome"))
        assertEquals("no_laya_prediction", metadata.getString("classification_source"))
        assertEquals("disabled", metadata.getJSONObject("stage_status").getString("cfc_filter"))
        assertEquals("disabled", metadata.getJSONObject("stage_status").getString("laya_classifier"))
        assertEquals(0, metadata.getJSONArray("relevance").length())
    }
    @Test fun `export carries the 5_1 fields set by the engine and app`() {
        val result = TurnResult(TurnKey("reference", 2, 1), Transcript("Leak", "Leak", "en", true),
            Classification(ClassificationOutcome.INCIDENT, listOf(IncidentCandidate("water_leak", 0.9)), mapOf("water_leak" to 0.9), false),
            Extraction(emptyList(), DialogueAct.CORRECTION, emptyList(), distinctIncidentsMentioned = true, userWantsToStopClarifying = true),
            emptyList(), emptyList(), 1, emptyMap(), assistantFollowupText = "Which floor?")
        val json = result.toJson()
        assertEquals("correction", json.getString("dialogue_act"))
        assertTrue(json.getBoolean("distinct_incidents_mentioned")); assertTrue(json.getBoolean("user_wants_to_stop_clarifying"))
        assertEquals("Which floor?", json.getString("assistant_followup_text"))
        assertEquals("incident", json.getJSONObject("metadata").getString("classification_outcome"))
    }
}
