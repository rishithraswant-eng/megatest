package org.seedhecode.incidentassistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.seedhecode.core.inference.DialogueAct
import org.seedhecode.incidentassistant.data.SessionState.*

/** End-to-end over the real seeded taxonomy, with synthetic engine turns standing in for the models. */
@RunWith(AndroidJUnit4::class)
class ReportingWorkflowTest : DatabaseTest() {
    private lateinit var workflow: ReportingWorkflow

    @Before fun seed() {
        fun asset(name: String) = context.assets.open(name).bufferedReader().use { JSONObject(it.readText()) }
        TaxonomySeeder(database, asset("incident_taxonomy.json")).run { seedIfEmpty(); seedDemoZones(asset("demo_zones.json")) }
        workflow = ReportingWorkflow(database)
    }

    private fun send(step: WorkflowStep, text: String = "", candidates: List<Pair<String, Double>> = emptyList(),
        fields: List<Pair<String, String>> = emptyList(), act: DialogueAct? = null, distinct: Boolean = false, stop: Boolean = false) =
        workflow.onEngineResult(turn(text, candidates, fields, act, distinct, stop, sessionId = step.sessionId, revision = step.revision))!!

    /** Drives a water-leak report from first narration to the review screen. */
    private fun waterLeakToReview(sessionId: String): WorkflowStep {
        var step = workflow.startDraft(sessionId)
        assertEquals(TurnOutcome(LISTENING, null), TurnOutcome(step.state, step.question))
        step = send(step, "Water is leaking from a pipe near the sockets in building N", listOf("water_leak" to 0.92),
            listOf("description" to "Water leaking from a pipe near the sockets", "leak_source" to "pipe",
                "near_electrical" to "true", "building" to "N"))
        assertEquals(FORM_FILLING, step.state)
        assertEquals("Is the water contained, or still spreading?", step.question)
        step = send(step, "It's spreading", fields = listOf("water_spread" to "spreading"))
        assertEquals("Which floor?", step.question)
        step = send(step, "Third floor, the pantry", fields = listOf("floor" to "3", "space_id" to "Pantry"))
        assertEquals("Roughly when did this happen or when did you notice it?", step.question)
        step = send(step, "I don't know", act = DialogueAct.UNCERTAINTY)
        assertEquals(CLARIFYING, step.state)
        step = send(step, "That's all", stop = true)
        assertEquals(REVIEWING, step.state)
        return step
    }

    @Test fun reportFlowsFromNarrationToLocalIncident() {
        var step = waterLeakToReview("w1")
        assertEquals(ReviewEntry("pipe", FieldStatus.KNOWN, FieldKind.REQUIRED), step.summary["leak_source"])
        assertEquals(FieldStatus.UNKNOWN, step.summary["occurred_at"]?.status)
        assertNull(step.summary["immediate_actions_taken"]) // unanswered optional field stays hidden
        assertTrue(step.canConfirm); assertFalse(step.canSubmit)
        step = workflow.confirm("w1")
        assertTrue(step.canSubmit)
        step = workflow.submit("w1")
        assertEquals(SUBMITTED, step.state)
        val id = step.incidentId!!
        assertEquals("FAC", query("SELECT department_id FROM incidents WHERE incident_id = ?", id))
        assertEquals("P3", query("SELECT priority FROM incidents WHERE incident_id = ?", id))
        assertEquals("Water leaking from a pipe near the sockets", query("SELECT description_en FROM incidents WHERE incident_id = ?", id))
        // space_id "Pantry" resolves to the seeded demo zone, so the incident gets a map pin.
        assertEquals("zone_n1_pantry", query("SELECT zone_id FROM incidents WHERE incident_id = ?", id))
        assertEquals(id, workflow.submit("w1").incidentId) // duplicate tap returns the same incident
        assertEquals(1L, count("SELECT COUNT(*) FROM incidents"))
    }

    @Test fun correctionRequiresReconfirmAndDropsStaleEngineResults() {
        val atReview = waterLeakToReview("w2")
        workflow.confirm("w2")
        val corrected = workflow.correct("w2", "space_id", "Conference Room 2")
        assertEquals(atReview.revision + 1, corrected.revision)
        assertNull(corrected.confirmedRevision); assertFalse(corrected.canSubmit)
        assertEquals("Conference Room 2", corrected.summary["space_id"]?.value)
        assertEquals(1L, count("SELECT COUNT(*) FROM correction_history WHERE session_id = 'w2'"))

        val turnsBefore = count("SELECT COUNT(*) FROM dialogue_turns WHERE session_id = 'w2'")
        val stale = turn("Actually it is the lobby", fields = listOf("space_id" to "Lobby"), sessionId = "w2", revision = atReview.revision)
        assertNull(workflow.onEngineResult(stale))
        assertEquals(turnsBefore, count("SELECT COUNT(*) FROM dialogue_turns WHERE session_id = 'w2'"))
        try { workflow.submit("w2"); fail("Unconfirmed correction must not submit") } catch (_: IllegalStateException) { }
        workflow.confirm("w2")
        val submitted = workflow.submit("w2")
        assertEquals(SUBMITTED, submitted.state)
        // The corrected space_id, not the original "Pantry", decides the zone.
        assertEquals("zone_n1_conf2", query("SELECT zone_id FROM incidents WHERE incident_id = ?", submitted.incidentId!!))
    }

    private fun submittedWaterLeak(sessionId: String): String {
        waterLeakToReview(sessionId); workflow.confirm(sessionId)
        return workflow.submit(sessionId).incidentId!!
    }
    private fun storedStatus(incidentId: String) = query("SELECT status FROM incidents WHERE incident_id = ?", incidentId)

    @Test fun mapDataPinsResolvedZonesAndSkipsUnmatchedOnes() {
        val pinned = submittedWaterLeak("p1")                     // space_id "Pantry"
        waterLeakToReview("p2"); workflow.correct("p2", "space_id", "Hallway"); workflow.confirm("p2")
        val unmatched = workflow.submit("p2").incidentId!!         // no zone called Hallway
        val auth = AuthManager(database)
        auth.createAdmin(TaxonomySeeder.ORG_ID, "Root", "correct horse", "super_admin")
        auth.createAdmin(TaxonomySeeder.ORG_ID, "IT Lead", "itworks12", "department_admin", "ITW")

        val data = auth.mapData(auth.loginAdmin("Root", "correct horse")!!)
        assertEquals(1000f, data.planWidth); assertEquals(600f, data.planHeight)
        assertEquals(7, data.zones.count { it.drawable })
        assertEquals(mapOf(pinned to "zone_n1_pantry", unmatched to null), data.incidents.associate { it.incidentId to it.zoneId })
        assertEquals("N-1", MapLayout.defaultFloor(data))
        val layout = MapLayout(data, "N-1", 1000f, 600f, 10f, 0f)
        assertEquals(listOf(pinned), layout.pins.map { it.incident.incidentId })
        assertEquals(2, layout.shapes.single { it.zone.zoneId == "zone_n1_pantry" }.urgentRank) // P3 water leak
        // Parking is stored as just "N" (no floor number), so it sorts first within building N.
        assertEquals(listOf("N", "N-1", "N-2", "QRC-1"), MapLayout.floors(data))

        // Zones are not restricted, but another department's incidents never reach its map.
        val itData = auth.mapData(auth.loginAdmin("IT Lead", "itworks12")!!)
        assertEquals(7, itData.zones.size); assertTrue(itData.incidents.isEmpty())
    }

    @Test fun adminMovesIncidentForwardAndTheRowIsUpdated() {
        val id = submittedWaterLeak("s1")
        val auth = AuthManager(database)
        auth.createAdmin(TaxonomySeeder.ORG_ID, "Fac Lead", "facilities1", "department_admin", "FAC")
        val admin = auth.loginAdmin("Fac Lead", "facilities1")!!
        assertEquals(IncidentStatus.OPEN, auth.visibleIncidents(admin).single().status)

        val inProgress = auth.updateIncidentStatus(admin, id, IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS)
        assertEquals(IncidentStatus.IN_PROGRESS, inProgress.status)
        assertEquals("In Progress", storedStatus(id))
        assertEquals(IncidentStatus.IN_PROGRESS, auth.visibleIncidents(admin).single().status)

        assertEquals(IncidentStatus.RESOLVED, auth.updateIncidentStatus(admin, id, IncidentStatus.IN_PROGRESS, IncidentStatus.RESOLVED).status)
        assertEquals("Resolved", storedStatus(id))
        assertNull(IncidentStatus.RESOLVED.next) // the admin list offers no further step
    }

    @Test fun invalidStatusChangesAreRefusedAndChangeNothing() {
        val id = submittedWaterLeak("s2")
        val auth = AuthManager(database)
        auth.createAdmin(TaxonomySeeder.ORG_ID, "Root", "correct horse", "super_admin")
        auth.createAdmin(TaxonomySeeder.ORG_ID, "IT Lead", "itworks12", "department_admin", "ITW")
        val root = auth.loginAdmin("Root", "correct horse")!!
        val itLead = auth.loginAdmin("IT Lead", "itworks12")!!

        fun refused(block: () -> Unit) = try { block(); fail("Expected refusal") } catch (_: RuntimeException) { }
        refused { auth.updateIncidentStatus(root, id, IncidentStatus.OPEN, IncidentStatus.RESOLVED) }      // skips a step
        refused { auth.updateIncidentStatus(root, id, IncidentStatus.OPEN, IncidentStatus.OPEN) }          // no-op
        refused { auth.updateIncidentStatus(itLead, id, IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS) } // other department
        refused { auth.updateIncidentStatus(root, "inc_missing", IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS) }
        assertEquals("Open", storedStatus(id))

        // Two admins acting on the same Open card: the second, now stale, is refused.
        auth.updateIncidentStatus(root, id, IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS)
        try { auth.updateIncidentStatus(root, id, IncidentStatus.OPEN, IncidentStatus.IN_PROGRESS); fail("Stale change accepted") }
        catch (e: IllegalStateException) { assertEquals("This incident is now In Progress; refresh and try again.", e.message) }
        refused { auth.updateIncidentStatus(root, id, IncidentStatus.IN_PROGRESS, IncidentStatus.OPEN) }    // backwards
        assertEquals("In Progress", storedStatus(id))
    }

    @Test fun twoIncidentNarrationLeavesALinkedDraftToResume() {
        var step = workflow.startDraft("m1")
        step = send(step, "There's a leak and smoke from the panel", listOf("water_leak" to 0.95, "fire_smoke" to 0.93),
            listOf("description" to "Leak and smoke from the panel"), distinct = true)
        assertEquals(FORM_FILLING, step.state)
        assertEquals("m1_linked", step.pendingLinkedSessionId)
        val linked = workflow.resume("m1_linked")
        assertEquals(FORM_FILLING, linked.state)
        assertEquals("fire_smoke", query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = 'm1_linked'"))
        // The narration's description was applied to the primary only; the linked draft asks for its own.
        assertEquals("Can you briefly describe what happened?", linked.question)
        assertEquals("m1", linked.pendingLinkedSessionId)
    }

    /** PipelineMode.ASR_LLM turns: no Laya, so UNDETERMINED with no candidates (the helper's default shape). */
    @Test fun asrLlmTurnsNeverGuessATypeButStillFillFieldsAndHonourTheFlags() {
        var step = workflow.startDraft("a1")
        step = send(step, "There's a leak and smoke", fields = listOf("description" to "Leak and smoke"), distinct = true)
        // No classifier: identification asks instead of guessing, and the split creates no typed halves.
        assertEquals(TurnOutcome(CLASSIFYING, "What kind of issue is this?"), TurnOutcome(step.state, step.question))
        assertNull(query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = 'a1'"))
        assertEquals("CLASSIFYING", query("SELECT state FROM draft_sessions WHERE session_id = 'a1_linked'"))
        assertNull(query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = 'a1_linked'"))

        // Once a type is known (app-confirmed), ASR_LLM turns drive form filling, uncertainty and stop-clarifying.
        exec("UPDATE draft_sessions SET state = 'FORM_FILLING', proposed_incident_type_id = 'water_leak' WHERE session_id = 'a1'")
        step = send(workflow.resume("a1"), "Pipe, spreading, near sockets, N, floor 3, the pantry", fields = listOf(
            "description" to "Leak", "leak_source" to "pipe", "water_spread" to "spreading", "near_electrical" to "true",
            "building" to "N", "floor" to "3", "space_id" to "the pantry"))
        assertEquals("Roughly when did this happen or when did you notice it?", step.question)
        step = send(step, "No idea", act = DialogueAct.UNCERTAINTY)
        assertEquals(CLARIFYING, step.state)
        assertEquals(REVIEWING, send(step, "That's enough", stop = true).state)
    }

    @Test fun manuallyChosenTypeContinuesExactlyLikeAConfidentClassification() {
        val narration = "Water is leaking from a pipe near the sockets in building N"
        val fields = listOf("description" to "Water leaking from a pipe near the sockets", "leak_source" to "pipe",
            "near_electrical" to "true", "building" to "N")
        // Reference: Laya-style confident candidate.
        val auto = send(workflow.startDraft("auto"), narration, listOf("water_leak" to 0.92), fields)
        // ASR_LLM: the same narration with no candidate stays unclassified until the reporter picks the type.
        val start = workflow.startDraft("manual")
        val unclassified = turn(narration, emptyList(), fields, sessionId = "manual", revision = start.revision)
        assertEquals(CLASSIFYING, workflow.onEngineResult(unclassified)!!.state)
        // The picker lists every seeded taxonomy type.
        val types = workflow.incidentTypes()
        assertEquals(count("SELECT COUNT(*) FROM incident_types"), types.size.toLong())
        assertTrue(types.any { it.incidentTypeId == "water_leak" && it.departmentName.isNotBlank() })
        val manual = workflow.chooseIncidentType("manual", "water_leak", unclassified)

        assertEquals(TurnOutcome(auto.state, auto.question), TurnOutcome(manual.state, manual.question))
        assertEquals(FORM_FILLING, manual.state)
        val stored = "SELECT group_concat(f.field_name || '=' || e.value, ';') FROM (SELECT * FROM extracted_fields " +
            "WHERE session_id = ? AND superseded = 0 ORDER BY field_id) e JOIN form_fields f ON e.field_id = f.field_id"
        assertEquals(query(stored, "auto"), query(stored, "manual"))
        assertTrue(count("SELECT COUNT(*) FROM extracted_fields WHERE session_id = 'manual' AND superseded = 0 AND value IS NOT NULL") > 0)
        assertEquals("water_leak", query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = 'manual'"))
        // The rest of the flow is the untouched FORM_FILLING path.
        assertEquals("Which floor?", send(manual, "It's spreading", fields = listOf("water_spread" to "spreading")).question)
    }

    @Test fun choosingATypeIsRefusedOnceTheDraftHasOneOrForAnUnknownType() {
        val start = workflow.startDraft("m2")
        fun refused(block: () -> Unit) = try { block(); fail("Expected refusal") } catch (_: IllegalArgumentException) { }
        refused { workflow.chooseIncidentType("m2", "not_a_type", null) }
        assertEquals("LISTENING", query("SELECT state FROM draft_sessions WHERE session_id = 'm2'"))
        // Without the narration turn nothing is pre-filled: the first required question is asked.
        assertEquals(FORM_FILLING, workflow.chooseIncidentType("m2", "water_leak", null).state)
        refused { workflow.chooseIncidentType("m2", "power_outage", null) }
        assertEquals("water_leak", query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = 'm2'"))
        assertEquals(0L, count("SELECT COUNT(*) FROM extracted_fields WHERE session_id = 'm2' AND value IS NOT NULL"))
        assertEquals(LISTENING, start.state)
    }

    @Test fun cancelAndResultsAfterFinishCreateNothing() {
        val start = workflow.startDraft("c1")
        assertEquals(CANCELLED, workflow.cancel("c1").state)
        assertNull(workflow.onEngineResult(turn("more", listOf("water_leak" to 0.9), sessionId = "c1", revision = start.revision)))
        assertNull(workflow.onEngineResult(turn("unknown session", sessionId = "nope")))
        assertEquals(0L, count("SELECT COUNT(*) FROM incidents"))
    }

    @Test fun adminQueueIsFilteredByDepartment() {
        waterLeakToReview("w3"); workflow.confirm("w3"); workflow.submit("w3")
        val auth = AuthManager(database)
        assertFalse(auth.hasAnyAdmin(TaxonomySeeder.ORG_ID))
        auth.createAdmin(TaxonomySeeder.ORG_ID, "Root", "correct horse", "super_admin")
        auth.createAdmin(TaxonomySeeder.ORG_ID, "Fac Lead", "facilities1", "department_admin", "FAC")
        auth.createAdmin(TaxonomySeeder.ORG_ID, "IT Lead", "itworks12", "department_admin", "ITW")
        assertTrue(auth.hasAnyAdmin(TaxonomySeeder.ORG_ID))
        assertEquals(1, auth.visibleIncidents(auth.loginAdmin("Root", "correct horse")!!).size)
        assertEquals(listOf(query("SELECT label FROM incident_types WHERE incident_type_id = 'water_leak'")),
            auth.visibleIncidents(auth.loginAdmin("Fac Lead", "facilities1")!!).map { it.typeLabel })
        assertTrue(auth.visibleIncidents(auth.loginAdmin("IT Lead", "itworks12")!!).isEmpty())
        assertNull(auth.loginAdmin("IT Lead", "wrong-password"))
        try { auth.createAdmin(TaxonomySeeder.ORG_ID, "Short", "pw", "super_admin"); fail("Short password accepted") } catch (_: IllegalArgumentException) { }
    }
}
