package org.seedhecode.incidentassistant.data

import android.database.sqlite.SQLiteDatabase
import java.time.Instant
import java.util.UUID

/** draft_sessions.state, stored by name (matches 04_DESIGN_FLOW.txt). */
enum class SessionState {
    LISTENING, CLASSIFYING, DISAMBIGUATING, FORM_FILLING, CLARIFYING, REVIEWING, SUBMITTED, CANCELLED, ABANDONED
}

/** extracted_fields.status. */
enum class FieldStatus(val wire: String) {
    MISSING("missing"), KNOWN("known"), UNKNOWN("unknown"), AMBIGUOUS("ambiguous");

    companion object {
        fun fromWire(value: String): FieldStatus = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown field status: $value")
    }
}

/** form_fields.field_kind. */
enum class FieldKind(val wire: String) {
    REQUIRED("required"), CLARIFYING("clarifying");

    companion object {
        fun fromWire(value: String): FieldKind = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown field kind: $value")
    }
}

/** incidents.status per 02_DATABASE_SCHEMA.txt; moves forward one step at a time. */
enum class IncidentStatus(val wire: String) {
    OPEN("Open"), IN_PROGRESS("In Progress"), RESOLVED("Resolved");

    val next: IncidentStatus? get() = entries.getOrNull(ordinal + 1)

    companion object {
        fun fromWire(value: String): IncidentStatus = entries.firstOrNull { it.wire == value }
            ?: throw IllegalArgumentException("Unknown incident status: $value")
    }
}

internal data class SessionRow(
    val sessionId: String, val orgId: String, val reporterUserId: String, val currentRevision: Long,
    val confirmedRevision: Long?, val state: SessionState, val proposedIncidentTypeId: String?,
    val linkedGroupId: String?,
)

internal data class FormField(val fieldId: String, val fieldName: String, val kind: FieldKind, val promptTemplate: String)

internal fun newId(): String = UUID.randomUUID().toString()
internal fun now(): String = Instant.now().toString()

internal inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
    beginTransaction()
    try {
        val result = block()
        setTransactionSuccessful()
        return result
    } finally { endTransaction() }
}

/** First column of the first row, or null. Arguments must be non-null (Android cannot bind null here). */
internal fun SQLiteDatabase.string(sql: String, vararg args: String): String? =
    rawQuery(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

internal fun SQLiteDatabase.long(sql: String, vararg args: String): Long =
    rawQuery(sql, args).use { it.moveToFirst(); it.getLong(0) }

internal fun SQLiteDatabase.session(sessionId: String): SessionRow = rawQuery(
    "SELECT session_id, org_id, reporter_user_id, current_revision, confirmed_revision, state, " +
        "proposed_incident_type_id, linked_group_id FROM draft_sessions WHERE session_id = ?", arrayOf(sessionId),
).use { c ->
    check(c.moveToFirst()) { "Session not found: $sessionId" }
    SessionRow(c.getString(0), c.getString(1), c.getString(2), c.getLong(3),
        if (c.isNull(4)) null else c.getLong(4), SessionState.valueOf(c.getString(5)),
        if (c.isNull(6)) null else c.getString(6), if (c.isNull(7)) null else c.getString(7))
}

internal fun SQLiteDatabase.setState(sessionId: String, state: SessionState) =
    execSQL("UPDATE draft_sessions SET state = ? WHERE session_id = ?", arrayOf(state.name, sessionId))

/** Form fields of one incident type in display order, optionally restricted to one kind. */
internal fun SQLiteDatabase.formFields(incidentTypeId: String?, kind: FieldKind? = null): List<FormField> {
    if (incidentTypeId == null) return emptyList()
    val sql = "SELECT field_id, field_name, field_kind, prompt_template FROM form_fields WHERE incident_type_id = ?" +
        (if (kind != null) " AND field_kind = ?" else "") + " ORDER BY display_order ASC"
    val args = if (kind != null) arrayOf(incidentTypeId, kind.wire) else arrayOf(incidentTypeId)
    return rawQuery(sql, args).use { c ->
        buildList { while (c.moveToNext()) add(FormField(c.getString(0), c.getString(1), FieldKind.fromWire(c.getString(2)), c.getString(3))) }
    }
}

/** Latest non-superseded status for a field; a field with no row is MISSING. */
internal fun SQLiteDatabase.fieldStatus(sessionId: String, fieldId: String): FieldStatus =
    string("SELECT status FROM extracted_fields WHERE session_id = ? AND field_id = ? AND superseded = 0 " +
        "ORDER BY draft_revision DESC LIMIT 1", sessionId, fieldId)?.let(FieldStatus::fromWire) ?: FieldStatus.MISSING

/** Supersedes every earlier row for the field, then writes the new one at [revision]. */
internal fun SQLiteDatabase.setField(
    sessionId: String, fieldId: String, revision: Long, status: FieldStatus, value: String?,
    confidence: Double?, provenance: String?, evidenceTurnId: String?,
) {
    execSQL("UPDATE extracted_fields SET superseded = 1 WHERE session_id = ? AND field_id = ?", arrayOf(sessionId, fieldId))
    execSQL("INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, " +
        "provenance, confidence, evidence_turn_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        arrayOf(newId(), sessionId, fieldId, revision, value, status.wire, provenance ?: "system", confidence, evidenceTurnId))
}

internal val OPEN_STATUSES = setOf(FieldStatus.MISSING, FieldStatus.AMBIGUOUS)
