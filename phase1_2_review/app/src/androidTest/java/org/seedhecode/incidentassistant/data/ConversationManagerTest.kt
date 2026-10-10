package org.seedhecode.incidentassistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.seedhecode.core.inference.DialogueAct
import org.seedhecode.incidentassistant.data.SessionState.*

/** Ports backend/tests/test_form_and_clarify.py (tests 1-8) and test_identify_stage.py (cases 1-5). */
@RunWith(AndroidJUnit4::class)
class ConversationManagerTest : DatabaseTest() {
    private lateinit var manager: ConversationManager
    private val bothRequired = listOf("Location" to "kitchen", "Severity" to "big")
    private val iDontKnow get() = turn("I don't know", act = DialogueAct.UNCERTAINTY)
    private val stopClarifying get() = turn("Stop asking questions", stop = true)

    @Before fun seed() {
        seedOrg()
        exec("INSERT INTO departments (department_id, org_id, name) VALUES ('d1', 'org_1', 'Dept')")
        seedType("t1"); seedType("t2")
        seedField("t1_loc", "t1", "Location", "required", "Where is it?", 1)
        seedField("t1_sev", "t1", "Severity", "required", "How bad is it?", 2)
        seedField("t1_col", "t1", "Color", "clarifying", "What color is the liquid?", 3)
        seedField("t1_sml", "t1", "Smell", "clarifying", "Does it smell?", 4)
        seedField("t2_tool", "t2", "Tool", "required", "Which tool?", 1)
        seedField("t2_safe", "t2", "Safety", "clarifying", "Is it safe?", 2)
        // Identify-stage types. The Python identify test never inserted these, so it fails under FK enforcement.
        for (id in listOf("leak", "other", "electrical", "glass_broken", "hvac_leak", "access_control")) seedType(id)
        manager = ConversationManager(database)
    }

    @Test fun test1_oneAnswerFillsMultipleRequiredFields() {
        val s = createSession("s1", FORM_FILLING, "t1")
        val out = manager.processTurn(s, turn("It's a big leak in the kitchen", fields = bothRequired))
        assertEquals(TurnOutcome(CLARIFYING, "What color is the liquid?"), out)
        assertEquals("known", liveStatus(s, "Location")); assertEquals("known", liveStatus(s, "Severity"))
    }

    @Test fun test2_iDontKnowStoresUnknownAndIsNeverAskedAgain() {
        val s = createSession("s2", FORM_FILLING, "t1")
        assertEquals(TurnOutcome(FORM_FILLING, "How bad is it?"), manager.processTurn(s, turn("kitchen", fields = listOf("Location" to "kitchen"))))
        assertEquals(TurnOutcome(CLARIFYING, "What color is the liquid?"), manager.processTurn(s, iDontKnow))
        assertEquals("unknown", liveStatus(s, "Severity"))
        // Cascaded CLARIFYING must not reuse the uncertainty for Color.
        assertNull(liveStatus(s, "Color"))
    }

    @Test fun test3_knownFieldIsNotReAsked() {
        val s = createSession("s3", FORM_FILLING, "t1")
        manager.processTurn(s, turn("kitchen", fields = listOf("Location" to "kitchen")))
        assertEquals(TurnOutcome(FORM_FILLING, "How bad is it?"), manager.processTurn(s, turn("Oh boy")))
    }

    @Test fun test4_clarifyingStopsAtQuestionBound() {
        val s = createSession("s4", CLARIFYING, "t1")
        assertEquals(TurnOutcome(CLARIFYING, "What color is the liquid?"), manager.processTurn(s, turn("Oh boy")))
        assertEquals(CLARIFYING, manager.processTurn(s, turn("Oh boy")).state)
        assertEquals(TurnOutcome(REVIEWING, null), manager.processTurn(s, turn("Oh boy")))
        assertEquals(REVIEWING, state(s))
    }

    @Test fun test5_clarifyingStopsOnUserSignal() {
        val s = createSession("s5", CLARIFYING, "t1")
        assertEquals(TurnOutcome(REVIEWING, null), manager.processTurn(s, stopClarifying))
    }

    @Test fun test6_endToEndPhase3() {
        val s = createSession("s6", LISTENING)
        assertEquals(DISAMBIGUATING, manager.processTurn(s, turn("Problem", candidates = listOf("t1" to 0.75, "other" to 0.70))).state)
        assertEquals(TurnOutcome(FORM_FILLING, "Where is it?"), manager.processTurn(s, turn("It is Test Inc", candidates = listOf("t1" to 0.95))))
        assertEquals(TurnOutcome(FORM_FILLING, "How bad is it?"), manager.processTurn(s, iDontKnow))
        assertEquals(TurnOutcome(CLARIFYING, "What color is the liquid?"), manager.processTurn(s, iDontKnow))
        assertEquals(REVIEWING, manager.processTurn(s, stopClarifying).state)
        assertEquals("unknown", liveStatus(s, "Location")); assertEquals("unknown", liveStatus(s, "Severity"))
    }

    @Test fun test7_formFillingSafetyBoundForcesForwardAfterSixTurns() {
        val s = createSession("s7", FORM_FILLING, "t1")
        repeat(5) { assertEquals(TurnOutcome(FORM_FILLING, "Where is it?"), manager.processTurn(s, turn("nothing"))) }
        assertEquals(TurnOutcome(REVIEWING, null), manager.processTurn(s, turn("nothing")))
        for (name in listOf("Location", "Severity")) {
            assertEquals("unknown", liveStatus(s, name))
            assertEquals("system_forced", query("SELECT e.provenance FROM extracted_fields e JOIN form_fields f ON e.field_id = f.field_id " +
                "WHERE e.session_id = ? AND f.field_name = ? AND e.superseded = 0", s, name))
        }
    }

    @Test fun test8_multiIncidentSplitReEvaluatesEachSessionIndependently() {
        val s = createSession("s8", LISTENING)
        val split = manager.processTurn(s, turn("Location big problem and Tool problem",
            candidates = listOf("t1" to 0.99, "t2" to 0.99), distinct = true))
        assertEquals(TurnOutcome(FORM_FILLING, "Where is it?"), split)
        val linked = "s8_linked"
        assertEquals(query("SELECT linked_group_id FROM draft_sessions WHERE session_id = ?", s),
            query("SELECT linked_group_id FROM draft_sessions WHERE session_id = ?", linked))
        assertEquals(2L, count("SELECT COUNT(*) FROM draft_sessions WHERE linked_group_id = 'group_s8'"))
        assertEquals("t2", query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = ?", linked))

        assertEquals(CLARIFYING, manager.processTurn(s, turn("kitchen, big", fields = bothRequired)).state)
        assertEquals(REVIEWING, manager.processTurn(s, stopClarifying).state)

        assertEquals(FORM_FILLING, state(linked))
        assertEquals(CLARIFYING, manager.processTurn(linked, iDontKnow).state)
        assertEquals(REVIEWING, manager.processTurn(linked, stopClarifying).state)
        // Neither session's answers leaked into the other.
        assertEquals("unknown", liveStatus(linked, "Tool"))
        assertEquals(0L, count("SELECT COUNT(*) FROM extracted_fields WHERE session_id = ? AND field_id LIKE 't1_%'", linked))
        assertEquals(0L, count("SELECT COUNT(*) FROM extracted_fields WHERE session_id = ? AND field_id LIKE 't2_%'", s))
    }

    @Test fun identify1_confidentMatch() {
        val s = createSession("i1", CLASSIFYING, revision = 0)
        assertEquals(FORM_FILLING, manager.processIdentifyStage(s, turn(candidates = listOf("leak" to 0.90, "other" to 0.70))).state)
        assertEquals("leak", query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = ?", s))
    }

    @Test fun identify2_closeCandidatesDisambiguate() {
        val s = createSession("i2", CLASSIFYING, revision = 0)
        assertEquals(DISAMBIGUATING, manager.processIdentifyStage(s, turn(candidates = listOf("leak" to 0.85, "electrical" to 0.80))).state)
        assertNull(query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = ?", s))
    }

    @Test fun identify3_zeroCandidatesAskGenericQuestion() {
        val s = createSession("i3", CLASSIFYING, revision = 0)
        assertEquals(TurnOutcome(CLASSIFYING, "What kind of issue is this?"), manager.processIdentifyStage(s, turn()))
    }

    @Test fun identify4_distinctIncidentsHighConfidence() {
        val s = createSession("i4", CLASSIFYING, revision = 0)
        assertEquals(FORM_FILLING, manager.processIdentifyStage(s, turn(candidates = listOf("glass_broken" to 0.99, "hvac_leak" to 0.99), distinct = true)).state)
        assertEquals("FORM_FILLING", query("SELECT state FROM draft_sessions WHERE session_id = 'i4_linked'"))
        assertEquals("hvac_leak", query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = 'i4_linked'"))
    }

    @Test fun identify5_distinctIncidentsLowConfidence() {
        val s = createSession("i5", CLASSIFYING, revision = 0)
        assertEquals(DISAMBIGUATING, manager.processIdentifyStage(s, turn(candidates = listOf("leak" to 0.55, "access_control" to 0.50), distinct = true)).state)
        assertEquals("DISAMBIGUATING", query("SELECT state FROM draft_sessions WHERE session_id = 'i5_linked'"))
        assertNull(query("SELECT proposed_incident_type_id FROM draft_sessions WHERE session_id = 'i5_linked'"))
    }

    @Test fun disambiguatingAsksTopCandidatesHint() {
        seedType("pipe", hint = "Is the water coming from a pipe or the ceiling?")
        val s = createSession("h1", CLASSIFYING, revision = 0)
        assertEquals(TurnOutcome(DISAMBIGUATING, "Is the water coming from a pipe or the ceiling?"),
            manager.processIdentifyStage(s, turn(candidates = listOf("electrical" to 0.80, "pipe" to 0.85))))
    }
}
