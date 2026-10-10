package org.seedhecode.incidentassistant.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject

/** SUBMIT, ported from backend/submit_manager.py. The only code path that creates an incidents row. */
class SubmitManager(private val database: IncidentDatabase) {

    /** Idempotent per (session, confirmed revision): a repeat call returns the existing incident id. */
    fun submitIncident(sessionId: String): String = database.writableDatabase.inTransaction {
        val session = session(sessionId)
        check(session.state in setOf(SessionState.REVIEWING, SessionState.SUBMITTED) &&
            session.confirmedRevision != null && session.confirmedRevision == session.currentRevision) {
            "Session is not confirmed or in invalid state: ${session.state}"
        }
        val confirmed = session.confirmedRevision.toString()
        string("SELECT incident_id FROM incidents WHERE session_id = ? AND confirmed_revision = ?", sessionId, confirmed)
            ?.let { return@inTransaction it }

        val typeId = requireNotNull(session.proposedIncidentTypeId) { "Invalid incident_type_id" }
        val departmentId = requireNotNull(string("SELECT department_id FROM incident_types WHERE incident_type_id = ?", typeId)) {
            "Invalid incident_type_id"
        }
        val extracted = rawQuery("SELECT f.field_name, e.value FROM extracted_fields e JOIN form_fields f ON e.field_id = f.field_id " +
            "WHERE e.session_id = ? AND e.draft_revision <= ? AND e.superseded = 0", arrayOf(sessionId, confirmed)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), if (c.isNull(1)) null else c.getString(1)) }
        }
        // The taxonomy has no priority form field, so fall back to the type's default before Python's "Medium".
        var priority = FieldRole.valueOf(FieldRole.PRIORITY, extracted)
            ?: string("SELECT default_priority FROM incident_types WHERE incident_type_id = ?", typeId) ?: "Medium"
        latestCallResult(sessionId, "classify_priority")?.let { result -> (result.opt("priority") as? String)?.let { priority = it } }

        val incidentId = "inc_" + newId()
        execSQL("INSERT INTO incidents (incident_id, org_id, session_id, reporter_user_id, zone_id, incident_type_id, department_id, " +
            "linked_group_id, description_en, original_lang, reported_at, incident_time, priority, status, confirmed_revision, " +
            "local_submission_only) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'en', ?, NULL, ?, 'Open', ?, 1)",
            arrayOf(incidentId, session.orgId, sessionId, session.reporterUserId, resolveZone(session, extracted), typeId,
                departmentId, session.linkedGroupId, FieldRole.valueOf(FieldRole.DESCRIPTION, extracted) ?: "No description provided", now(), priority,
                session.confirmedRevision))
        setState(sessionId, SessionState.SUBMITTED)
        incidentId
    }

    /** get_zone_details result, then a case-insensitive LOCATION name match, else null. Never blocks submission. */
    private fun SQLiteDatabase.resolveZone(session: SessionRow, confirmedFields: Map<String, String?>): String? {
        val fromTool = latestCallResult(session.sessionId, "get_zone_details")?.opt("zone_id") as? String
        // A zone id this org does not have would violate the FK and block SUBMIT; treat it as unresolved.
        if (fromTool != null && string("SELECT zone_id FROM zones WHERE zone_id = ? AND org_id = ?", fromTool, session.orgId) != null)
            return fromTool
        val location = FieldRole.locationSignal(confirmedFields) ?: return null
        return string("SELECT zone_id FROM zones WHERE org_id = ? AND name = ? COLLATE NOCASE", session.orgId, location)
    }

    private fun SQLiteDatabase.latestCallResult(sessionId: String, functionName: String): JSONObject? =
        string("SELECT result_json FROM function_calls WHERE session_id = ? AND function_name = ? " +
            "ORDER BY called_at DESC, rowid DESC LIMIT 1", sessionId, functionName)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
}
