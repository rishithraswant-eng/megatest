package org.seedhecode.incidentassistant.data

import org.seedhecode.core.inference.TurnResult

/** What the reporting screen shows after any action. */
data class WorkflowStep(
    val sessionId: String, val state: SessionState, val revision: Long, val confirmedRevision: Long?,
    val question: String?, val summary: Map<String, ReviewEntry>, val incidentId: String? = null,
    /** The other half of a two-incident narration that still needs its own report, if any. */
    val pendingLinkedSessionId: String? = null,
) {
    val canConfirm get() = state == SessionState.REVIEWING && confirmedRevision != revision
    val canSubmit get() = state == SessionState.REVIEWING && confirmedRevision == revision
}

data class IncidentTypeOption(val incidentTypeId: String, val label: String, val departmentName: String)

/** An unfinished draft offered for recovery on app start (05_UIUX_REQUIREMENTS 2.7). Both labels are provisional. */
data class RecoverableDraft(val sessionId: String, val incidentTypeLabel: String?, val zoneName: String?) {
    val description: String get() = when {
        incidentTypeLabel != null && zoneName != null -> "${incidentTypeLabel.lowercase()} in $zoneName"
        incidentTypeLabel != null -> incidentTypeLabel.lowercase()
        zoneName != null -> "an incident in $zoneName"
        else -> "an incident you started describing"
    }
}

/**
 * Glue between the engine and the Phase 3-4 managers. Every call is synchronous and touches the
 * database, so callers run it on one background thread to keep actions in order.
 */
class ReportingWorkflow(
    private val database: IncidentDatabase,
    private val orgId: String = TaxonomySeeder.ORG_ID,
    private val reporterUserId: String = TaxonomySeeder.REPORTER_ID,
) {
    private val conversation = ConversationManager(database)
    private val review = ReviewManager(database)
    private val submit = SubmitManager(database)

    /** Creates a draft that is LISTENING at revision 0; nothing reaches incidents until SUBMIT. */
    fun startDraft(sessionId: String): WorkflowStep {
        val at = now()
        database.writableDatabase.execSQL("INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, " +
            "last_active_at, current_revision, state) VALUES (?, ?, ?, ?, ?, 0, ?)",
            arrayOf(sessionId, orgId, reporterUserId, at, at, SessionState.LISTENING.name))
        return step(sessionId, null)
    }

    /** Resumes an existing draft (e.g. a linked second incident) with the question it is waiting on. */
    fun resume(sessionId: String): WorkflowStep = step(sessionId, conversation.pendingQuestion(sessionId))

    /**
     * The reporter's most recently active unfinished draft, or null. Finished (submitted/cancelled) and abandoned
     * drafts are excluded, and so are drafts with no reporter turn yet: there is nothing in them to continue.
     */
    fun recoverableDraft(): RecoverableDraft? = database.readableDatabase.rawQuery(
        "SELECT s.session_id, t.label FROM draft_sessions s LEFT JOIN incident_types t ON s.proposed_incident_type_id = t.incident_type_id " +
            "WHERE s.org_id = ? AND s.reporter_user_id = ? AND s.state NOT IN (?, ?, ?) " +
            "AND EXISTS (SELECT 1 FROM dialogue_turns d WHERE d.session_id = s.session_id) " +
            "ORDER BY s.last_active_at DESC, s.started_at DESC LIMIT 1",
        arrayOf(orgId, reporterUserId, SessionState.SUBMITTED.name, SessionState.CANCELLED.name, SessionState.ABANDONED.name),
    ).use { c ->
        if (!c.moveToFirst()) return null
        val sessionId = c.getString(0)
        RecoverableDraft(sessionId, if (c.isNull(1)) null else c.getString(1), currentLocation(sessionId))
    }

    /** Discard from the recovery prompt: the draft becomes ABANDONED and is never offered again. */
    fun discard(sessionId: String) {
        database.writableDatabase.execSQL("UPDATE draft_sessions SET state = ?, last_active_at = ? WHERE session_id = ? AND state NOT IN (?, ?, ?)",
            arrayOf(SessionState.ABANDONED.name, now(), sessionId, SessionState.SUBMITTED.name, SessionState.CANCELLED.name, SessionState.ABANDONED.name))
    }

    /** The draft's current known LOCATION value (space_id), as the reporter said it; not yet matched to a zone. */
    private fun currentLocation(sessionId: String): String? = FieldRole.LOCATION.fieldNames.singleOrNull()?.let { field ->
        database.readableDatabase.string("SELECT e.value FROM extracted_fields e JOIN form_fields f ON e.field_id = f.field_id " +
            "WHERE e.session_id = ? AND f.field_name = ? AND e.superseded = 0 AND e.status = ? AND e.value IS NOT NULL " +
            "ORDER BY e.draft_revision DESC LIMIT 1", sessionId, field, FieldStatus.KNOWN.wire)
    }

    /**
     * Applies one final engine turn. Returns null, and writes nothing, when the result is stale: from another
     * session, an older draft revision, or a session that is already finished.
     */
    fun onEngineResult(result: TurnResult): WorkflowStep? {
        val session = runCatching { database.readableDatabase.session(result.key.sessionId) }.getOrNull() ?: return null
        if (result.key.draftRevision != session.currentRevision) return null
        if (session.state in setOf(SessionState.SUBMITTED, SessionState.CANCELLED, SessionState.ABANDONED)) return null
        val outcome = conversation.processTurn(session.sessionId, result)
        database.writableDatabase.execSQL("UPDATE draft_sessions SET last_active_at = ? WHERE session_id = ?", arrayOf(now(), session.sessionId))
        return step(session.sessionId, outcome.followupText)
    }

    /** Incident types the reporter can choose from when no classifier is installed, in taxonomy (seed) order. */
    fun incidentTypes(): List<IncidentTypeOption> = database.readableDatabase.rawQuery(
        "SELECT t.incident_type_id, t.label, d.name FROM incident_types t JOIN departments d ON t.department_id = d.department_id " +
            "WHERE t.org_id = ? ORDER BY t.rowid", arrayOf(orgId)).use { c ->
        buildList { while (c.moveToNext()) add(IncidentTypeOption(c.getString(0), c.getString(1), c.getString(2))) }
    }

    /** ASR_LLM manual identification: continues exactly like a confident classification of [turn]. */
    fun chooseIncidentType(sessionId: String, incidentTypeId: String, turn: TurnResult?): WorkflowStep {
        val outcome = conversation.confirmIncidentType(sessionId, incidentTypeId, turn)
        return step(sessionId, outcome.followupText)
    }

    fun skipClarification(sessionId: String): WorkflowStep { review.actionSkipClarification(sessionId); return step(sessionId, null) }
    fun confirm(sessionId: String): WorkflowStep { review.actionConfirm(sessionId); return step(sessionId, null) }
    fun cancel(sessionId: String): WorkflowStep { review.actionCancel(sessionId); return step(sessionId, null) }

    /** A typed correction from the review screen; bumps the revision, so pending engine results become stale. */
    fun correct(sessionId: String, fieldName: String, newValue: String): WorkflowStep {
        require(newValue.isNotBlank()) { "Enter the corrected value." }
        val turnId = conversation.recordTurn(sessionId, "Correct $fieldName to $newValue")
        review.actionCorrect(sessionId, fieldName, newValue.trim(), turnId)
        return step(sessionId, null)
    }

    fun submit(sessionId: String): WorkflowStep {
        val incidentId = submit.submitIncident(sessionId)
        return step(sessionId, null).copy(incidentId = incidentId)
    }

    /** Field names the reporter may correct on the review screen, in form order. */
    fun correctableFields(sessionId: String): List<String> =
        database.readableDatabase.run { formFields(session(sessionId).proposedIncidentTypeId).map { it.fieldName } }

    private fun step(sessionId: String, question: String?): WorkflowStep {
        val db = database.readableDatabase
        val session = db.session(sessionId)
        val summary = if (session.state in setOf(SessionState.REVIEWING, SessionState.SUBMITTED)) review.generateReviewSummary(sessionId) else emptyMap()
        val linked = session.linkedGroupId?.let { group ->
            db.string("SELECT session_id FROM draft_sessions WHERE linked_group_id = ? AND session_id != ? AND state NOT IN (?, ?, ?) " +
                "ORDER BY started_at LIMIT 1", group, sessionId, SessionState.SUBMITTED.name, SessionState.CANCELLED.name, SessionState.ABANDONED.name)
        }
        return WorkflowStep(sessionId, session.state, session.currentRevision, session.confirmedRevision, question, summary,
            pendingLinkedSessionId = linked)
    }
}
