package org.seedhecode.incidentassistant.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray

data class ReviewEntry(val value: String?, val status: FieldStatus, val kind: FieldKind)

/** REVIEWING actions, ported from backend/review_manager.py. None of these ever writes an incidents row. */
class ReviewManager(private val database: IncidentDatabase) {

    /** Required fields always appear; clarifying fields appear only once they have a non-missing status. */
    fun generateReviewSummary(sessionId: String): Map<String, ReviewEntry> =
        database.readableDatabase.summary(sessionId)

    /** Marks the current revision as the one the reporter confirmed. */
    fun actionConfirm(sessionId: String) = database.writableDatabase.inTransaction {
        execSQL("UPDATE draft_sessions SET confirmed_revision = ? WHERE session_id = ?",
            arrayOf(session(sessionId).currentRevision, sessionId))
    }

    fun actionCorrect(sessionId: String, fieldName: String, newValue: String, triggeringTurnId: String): Map<String, ReviewEntry> =
        database.writableDatabase.inTransaction {
            val session = session(sessionId)
            val newRevision = session.currentRevision + 1
            execSQL("UPDATE draft_sessions SET current_revision = ? WHERE session_id = ?", arrayOf(newRevision, sessionId))
            val fields = formFields(session.proposedIncidentTypeId).associateBy { it.fieldName }
            val target = fields[fieldName] ?: throw IllegalArgumentException("Field $fieldName not found")

            val beforeRowId = liveRowId(sessionId, target.fieldId)
            if (beforeRowId != null) supersede(beforeRowId)
            val afterRowId = newId()
            execSQL("INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, " +
                "provenance, confidence, evidence_turn_id) VALUES (?, ?, ?, ?, ?, ?, 'correction', 1.0, ?)",
                arrayOf(afterRowId, sessionId, target.fieldId, newRevision, newValue, FieldStatus.KNOWN.wire, triggeringTurnId))

            // Derived values computed from the corrected field must be re-established, not kept.
            val invalidated = mutableListOf<String>()
            val dependentRoles = FieldRole.of(fieldName)?.let { FieldRole.DEPENDENCIES[it] }.orEmpty()
            for (dependent in fields.values.filter { field -> dependentRoles.any { it.matches(field.fieldName) } }) {
                val dependentRowId = liveRowId(sessionId, dependent.fieldId) ?: continue
                invalidated.add(dependentRowId)
                supersede(dependentRowId)
                execSQL("INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, " +
                    "provenance, confidence) VALUES (?, ?, ?, ?, NULL, ?, 'system_invalidated', 1.0)",
                    arrayOf(newId(), sessionId, dependent.fieldId, newRevision, FieldStatus.MISSING.wire))
            }
            if (beforeRowId != null) execSQL("INSERT INTO correction_history (correction_id, session_id, field_row_id_before, " +
                "field_row_id_after, invalidated_json, corrected_at, triggering_turn_id) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(newId(), sessionId, beforeRowId, afterRowId, JSONArray(invalidated).toString(), now(), triggeringTurnId))
            // A correction voids any earlier confirmation, forcing a fresh review.
            execSQL("UPDATE draft_sessions SET confirmed_revision = NULL WHERE session_id = ?", arrayOf(sessionId))
            summary(sessionId)
        }

    fun actionSkipClarification(sessionId: String): Map<String, ReviewEntry> = database.writableDatabase.inTransaction {
        setState(sessionId, SessionState.REVIEWING)
        summary(sessionId)
    }

    fun actionCancel(sessionId: String) = database.writableDatabase.inTransaction {
        setState(sessionId, SessionState.CANCELLED)
    }

    private fun SQLiteDatabase.liveRowId(sessionId: String, fieldId: String): String? = string(
        "SELECT field_row_id FROM extracted_fields WHERE session_id = ? AND field_id = ? AND superseded = 0", sessionId, fieldId)

    private fun SQLiteDatabase.supersede(fieldRowId: String) =
        execSQL("UPDATE extracted_fields SET superseded = 1 WHERE field_row_id = ?", arrayOf(fieldRowId))

    private fun SQLiteDatabase.summary(sessionId: String): Map<String, ReviewEntry> {
        val session = session(sessionId)
        val live = rawQuery("SELECT field_id, value, status FROM extracted_fields WHERE session_id = ? AND superseded = 0",
            arrayOf(sessionId)).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), (if (c.isNull(1)) null else c.getString(1)) to FieldStatus.fromWire(c.getString(2))) }
        }
        val summary = LinkedHashMap<String, ReviewEntry>()
        for (field in formFields(session.proposedIncidentTypeId)) {
            val row = live[field.fieldId]
            when {
                row != null && (field.kind == FieldKind.REQUIRED || row.second != FieldStatus.MISSING) ->
                    summary[field.fieldName] = ReviewEntry(row.first, row.second, field.kind)
                row == null && field.kind == FieldKind.REQUIRED ->
                    summary[field.fieldName] = ReviewEntry(null, FieldStatus.MISSING, FieldKind.REQUIRED)
            }
        }
        return summary
    }
}
