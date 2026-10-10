package org.seedhecode.incidentassistant.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.seedhecode.core.inference.ToolResult

/** Ports backend/tests/test_submit_and_auth.py section 4.2. */
@RunWith(AndroidJUnit4::class)
class SubmitManagerTest : DatabaseTest() {
    private lateinit var submit: SubmitManager

    @Before fun seed() {
        seedOrg()
        // FieldRole.LOCATION matches the space_id answer against zones.name.
        exec("INSERT INTO zones (zone_id, org_id, name, created_at, polygon_json) VALUES ('z1', 'org_1', 'Pantry', '2026', '[]')")
        exec("INSERT INTO zones (zone_id, org_id, name, created_at, polygon_json) VALUES ('z2', 'org_1', 'Conference Room 2', '2026', '[]')")
        exec("INSERT INTO departments (department_id, org_id, name) VALUES ('d1', 'org_1', 'Maintenance')")
        seedType("t1")
        seedField("f_space", "t1", "space_id", "required", "?", 1)
        seedField("f_desc", "t1", "description", "required", "?", 2)
        submit = SubmitManager(database)
    }

    /** [location] is the space_id answer, or null for none. */
    private fun session(id: String, location: String?, confirmed: Boolean = true): String {
        createSession(id, if (confirmed) SessionState.REVIEWING else SessionState.FORM_FILLING, "t1", confirmed = if (confirmed) 1 else null)
        if (location != null) putField(id, "f_space", location)
        return id
    }
    private fun zoneOf(incidentId: String) = query("SELECT zone_id FROM incidents WHERE incident_id = ?", incidentId)

    @Test fun a_cleanSubmitWithKnownZone() {
        val s = session("sa", "the pantry") // article dropped, then a case-insensitive match against 'Pantry'
        val id = submit.submitIncident(s)
        assertEquals("z1", zoneOf(id))
        assertEquals("Open", query("SELECT status FROM incidents WHERE incident_id = ?", id))
        assertEquals("d1", query("SELECT department_id FROM incidents WHERE incident_id = ?", id))
        assertEquals("1", query("SELECT local_submission_only FROM incidents WHERE incident_id = ?", id))
        assertEquals(SessionState.SUBMITTED, state(s))
        assertEquals("the pantry", query("SELECT value FROM extracted_fields WHERE session_id = ? AND field_id = 'f_space'", s))
    }

    @Test fun b_unresolvedZoneSubmitsWithNull() {
        val id = submit.submitIncident(session("sb", "Hallway"))
        assertEquals(1L, count("SELECT COUNT(*) FROM incidents WHERE incident_id = ? AND zone_id IS NULL", id))
    }

    @Test fun noSpaceAnswerLeavesZoneUnresolved() {
        val id = submit.submitIncident(session("sh", null))
        assertEquals(1L, count("SELECT COUNT(*) FROM incidents WHERE incident_id = ? AND zone_id IS NULL", id))
    }

    @Test fun descriptionRoleAndTypeDefaultPriorityAreUsed() {
        val s = session("si", "Pantry")
        putField(s, "f_desc", "Water dripping from ceiling")
        val id = submit.submitIncident(s)
        assertEquals("Water dripping from ceiling", query("SELECT description_en FROM incidents WHERE incident_id = ?", id))
        // No priority form field: the incident type's default_priority ('Low' in seedType), not Python's 'Medium'.
        assertEquals("Low", query("SELECT priority FROM incidents WHERE incident_id = ?", id))
    }

    @Test fun c_duplicateSubmitReturnsSameIncident() {
        val s = session("sc", "Hallway")
        val first = submit.submitIncident(s)
        assertEquals(first, submit.submitIncident(s))
        assertEquals(1L, count("SELECT COUNT(*) FROM incidents WHERE session_id = ?", s))
    }

    @Test fun d_unconfirmedSessionIsRefused() {
        val s = session("sd", "Pantry", confirmed = false)
        try { submit.submitIncident(s); fail("Expected refusal") } catch (e: IllegalStateException) {
            assertEquals("Session is not confirmed or in invalid state: FORM_FILLING", e.message)
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM incidents"))
    }

    @Test fun staleConfirmationIsRefused() {
        val s = session("se", "Pantry")
        exec("UPDATE draft_sessions SET current_revision = 2 WHERE session_id = ?", s)
        try { submit.submitIncident(s); fail("Expected refusal") } catch (_: IllegalStateException) { }
    }

    @Test fun zoneDetailsCallWinsOverLocationName() {
        val s = session("sf", "Pantry")
        ConversationManager(database).processTurn(s, turn(calls = listOf(
            ToolResult("get_zone_details", mapOf("zone_name" to "Pantry"), """{"zone_id":"z1"}"""),
            ToolResult("get_zone_details", mapOf("zone_name" to "Conference Room 2"), """{"zone_id":"z2"}"""))))
        assertEquals("z2", zoneOf(submit.submitIncident(s)))
    }

    @Test fun unknownZoneFromToolFallsBackInsteadOfBlocking() {
        val s = session("sg", "Pantry")
        ConversationManager(database).processTurn(s, turn(calls = listOf(
            ToolResult("get_zone_details", mapOf("zone_name" to "Mars"), """{"zone_id":"no_such_zone"}"""))))
        assertEquals("z1", zoneOf(submit.submitIncident(s)))
    }
}
