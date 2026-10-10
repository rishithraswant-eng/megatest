package org.seedhecode.incidentassistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.seedhecode.incidentassistant.data.FieldKind.CLARIFYING
import org.seedhecode.incidentassistant.data.FieldKind.REQUIRED
import org.seedhecode.incidentassistant.data.FieldStatus.KNOWN
import org.seedhecode.incidentassistant.data.FieldStatus.MISSING

/** Ports backend/tests/test_review_stage.py (tests A-E). */
@RunWith(AndroidJUnit4::class)
class ReviewManagerTest : DatabaseTest() {
    private lateinit var review: ReviewManager
    private val initialSummary = mapOf(
        "Severity" to ReviewEntry("high", KNOWN, REQUIRED),
        "Priority" to ReviewEntry("P1", KNOWN, REQUIRED),
        "Color" to ReviewEntry("red", KNOWN, CLARIFYING),
    )

    @Before fun seed() {
        seedOrg()
        exec("INSERT INTO departments (department_id, org_id, name) VALUES ('d1', 'org_1', 'Dept')")
        seedType("t1")
        seedField("f_sev", "t1", "Severity", "required", "How bad is it?", 1)
        seedField("f_prio", "t1", "Priority", "required", "Priority?", 2)
        seedField("f_col", "t1", "Color", "clarifying", "What color is the liquid?", 3)
        seedField("f_sml", "t1", "Smell", "clarifying", "Does it smell?", 4)
        review = ReviewManager(database)
    }

    private fun sessionAtReview(id: String): String {
        createSession(id, SessionState.REVIEWING, "t1")
        putField(id, "f_sev", "high"); putField(id, "f_prio", "P1", provenance = "system"); putField(id, "f_col", "red")
        return id // Smell is left unanswered.
    }
    private fun turnFor(sessionId: String): String = newId().also {
        exec("INSERT INTO dialogue_turns (turn_id, session_id, turn_index, draft_revision, speaker, stage, raw_text, timestamp) VALUES (?, ?, 1, 1, 'user', 'REVIEWING', 'test', '2026')", it, sessionId)
    }
    private fun confirmed(id: String) = query("SELECT confirmed_revision FROM draft_sessions WHERE session_id = ?", id)

    @Test fun testA_cleanConfirm() {
        val s = sessionAtReview("sa")
        val summary = review.generateReviewSummary(s)
        assertEquals(initialSummary, summary)
        assertEquals(listOf("Severity", "Priority", "Color"), summary.keys.toList())
        review.actionConfirm(s)
        assertEquals("1", confirmed(s))
        assertEquals(0L, count("SELECT COUNT(*) FROM incidents"))
    }

    @Test fun testB_correctInvalidatesDependentFieldThenConfirm() {
        val s = sessionAtReview("sb")
        val turnId = turnFor(s)
        val summary = review.actionCorrect(s, "Severity", "low", turnId)
        assertEquals(mapOf("Severity" to ReviewEntry("low", KNOWN, REQUIRED), "Priority" to ReviewEntry(null, MISSING, REQUIRED),
            "Color" to ReviewEntry("red", KNOWN, CLARIFYING)), summary)
        assertEquals("system_invalidated", query("SELECT provenance FROM extracted_fields WHERE session_id = ? AND field_id = 'f_prio' AND superseded = 0", s))
        assertEquals("2", query("SELECT draft_revision FROM extracted_fields WHERE session_id = ? AND field_id = 'f_sev' AND superseded = 0", s))
        val invalidated = JSONArray(query("SELECT invalidated_json FROM correction_history WHERE session_id = ?", s))
        assertEquals(1, invalidated.length())
        assertEquals("P1", query("SELECT value FROM extracted_fields WHERE field_row_id = ?", invalidated.getString(0)))
        review.actionConfirm(s)
        assertEquals("2", confirmed(s))
    }

    @Test fun testC_correctTwiceInARow() {
        val s = sessionAtReview("sc")
        val turnId = turnFor(s)
        review.actionCorrect(s, "Color", "blue", turnId)
        review.actionCorrect(s, "Color", "green", turnId)
        assertEquals("3", query("SELECT current_revision FROM draft_sessions WHERE session_id = ?", s))
        assertEquals(2L, count("SELECT COUNT(*) FROM correction_history WHERE session_id = ?", s))
        assertEquals("green", review.generateReviewSummary(s)["Color"]?.value)
    }

    @Test fun testD_skipRemainingClarificationThenConfirm() {
        val s = sessionAtReview("sd")
        exec("UPDATE draft_sessions SET state = 'CLARIFYING' WHERE session_id = ?", s)
        assertEquals(initialSummary, review.actionSkipClarification(s))
        review.actionConfirm(s)
        assertEquals(SessionState.REVIEWING, state(s)); assertEquals("1", confirmed(s))
    }

    @Test fun testE_cancelNeverCreatesIncident() {
        val s = sessionAtReview("se")
        review.actionConfirm(s)
        review.actionCancel(s)
        assertEquals(SessionState.CANCELLED, state(s))
        assertEquals(0L, count("SELECT COUNT(*) FROM incidents"))
    }

    @Test fun correctionAfterConfirmForcesReReview() {
        val s = sessionAtReview("sf")
        review.actionConfirm(s)
        review.actionCorrect(s, "Color", "blue", turnFor(s))
        assertNull(confirmed(s))
    }

    @Test fun correctingUnknownFieldChangesNothing() {
        val s = sessionAtReview("sg")
        try { review.actionCorrect(s, "Nope", "x", turnFor(s)); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
        assertEquals("1", query("SELECT current_revision FROM draft_sessions WHERE session_id = ?", s))
    }
}
