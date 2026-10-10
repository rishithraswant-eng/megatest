package org.seedhecode.incidentassistant.data

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.seedhecode.core.inference.CandidateField
import org.seedhecode.core.inference.DialogueAct
import org.seedhecode.core.inference.IncidentCandidate
import org.seedhecode.core.inference.TurnResult

data class TurnOutcome(val state: SessionState, val followupText: String?)

/** Outcome of evaluating one candidate list; [proposedIncidentTypeId] is set only on a confident match. */
data class Identification(val proposedIncidentTypeId: String?, val state: SessionState, val followupText: String?)

/**
 * IDENTIFY -> FORM_FILLING -> CLARIFYING routing, ported from backend/conversation_manager.py.
 * The engine only proposes; this class owns every draft_sessions / extracted_fields write.
 */
class ConversationManager(private val database: IncidentDatabase) {

    /** The parts of a turn the stages read; [CLEAN] is what a cascaded stage sees. */
    private data class StageInput(
        val candidates: List<IncidentCandidate>, val fields: List<CandidateField>, val dialogueAct: DialogueAct?,
        val distinctIncidentsMentioned: Boolean, val userWantsToStopClarifying: Boolean,
    ) {
        companion object {
            val CLEAN = StageInput(emptyList(), emptyList(), null, false, false)
            fun of(turn: TurnResult) = StageInput(turn.classification.candidates, turn.extraction.fields,
                turn.extraction.dialogueAct, turn.extraction.distinctIncidentsMentioned, turn.extraction.userWantsToStopClarifying)
        }
    }

    fun processTurn(sessionId: String, turn: TurnResult): TurnOutcome = database.writableDatabase.inTransaction {
        val session = session(sessionId)
        val turnId = newId()
        val turnIndex = long("SELECT COUNT(*) FROM dialogue_turns WHERE session_id = ?", sessionId)
        // stage records the state the turn arrived in; the per-stage turn bounds count these rows.
        execSQL("INSERT INTO dialogue_turns (turn_id, session_id, turn_index, draft_revision, speaker, stage, raw_text, " +
            "is_final, normalized_text, lang_detected, dialogue_act, timestamp) VALUES (?, ?, ?, ?, 'user', ?, ?, ?, ?, ?, ?, ?)",
            arrayOf(turnId, sessionId, turnIndex, session.currentRevision, session.state.name, turn.transcript.sourceText,
                if (turn.transcript.isFinal) 1 else 0, turn.transcript.text, turn.transcript.language,
                turn.extraction.dialogueAct?.wire, now()))
        // Logged so SUBMIT can resolve the zone from the most recent get_zone_details result.
        for (call in turn.functionCalls) execSQL("INSERT INTO function_calls (call_id, session_id, function_name, " +
            "arguments_json, result_json, called_at) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf(newId(), sessionId, call.name, JSONObject(call.arguments).toString(), call.result, now()))

        val input = StageInput.of(turn)
        when (session.state) {
            SessionState.LISTENING, SessionState.CLASSIFYING, SessionState.DISAMBIGUATING -> {
                val identified = identify(session, input)
                if (identified.state == SessionState.FORM_FILLING) fillAndCascade(sessionId, input, turnId) else identified
            }
            SessionState.FORM_FILLING -> fillAndCascade(sessionId, input, turnId)
            SessionState.CLARIFYING -> clarify(sessionId, input, turnId)
            else -> TurnOutcome(session.state, null)
        }
    }

    /** Records a non-engine user turn (e.g. a typed correction) and returns its turn_id. */
    fun recordTurn(sessionId: String, text: String): String = database.writableDatabase.inTransaction {
        val session = session(sessionId)
        val turnId = newId()
        execSQL("INSERT INTO dialogue_turns (turn_id, session_id, turn_index, draft_revision, speaker, stage, raw_text, " +
            "is_final, normalized_text, lang_detected, dialogue_act, timestamp) VALUES (?, ?, ?, ?, 'user', ?, ?, 1, ?, 'en', ?, ?)",
            arrayOf(turnId, sessionId, long("SELECT COUNT(*) FROM dialogue_turns WHERE session_id = ?", sessionId),
                session.currentRevision, session.state.name, text, text, DialogueAct.CORRECTION.wire, now()))
        turnId
    }

    /** The question a session is waiting on, for resuming it (e.g. the second half of a split). */
    fun pendingQuestion(sessionId: String): String? = database.readableDatabase.run {
        val session = session(sessionId)
        val kind = when (session.state) {
            SessionState.FORM_FILLING -> FieldKind.REQUIRED
            SessionState.CLARIFYING -> FieldKind.CLARIFYING
            SessionState.LISTENING, SessionState.CLASSIFYING, SessionState.DISAMBIGUATING -> return@run GENERIC_CLASSIFY_QUESTION
            else -> return@run null
        }
        formFields(session.proposedIncidentTypeId, kind).firstOrNull { fieldStatus(sessionId, it.fieldId) in OPEN_STATUSES }?.promptTemplate
    }

    /** IDENTIFY only, without the FORM_FILLING cascade or a dialogue_turns row. */
    internal fun processIdentifyStage(sessionId: String, turn: TurnResult): TurnOutcome =
        database.writableDatabase.inTransaction { identify(session(sessionId), StageInput.of(turn)) }

    private fun SQLiteDatabase.fillAndCascade(sessionId: String, input: StageInput, turnId: String): TurnOutcome {
        val filled = formFilling(sessionId, input, turnId)
        // The answer was consumed by FORM_FILLING; CLARIFYING must not re-apply it.
        return if (filled.state == SessionState.CLARIFYING) clarify(sessionId, StageInput.CLEAN, turnId) else filled
    }

    private fun SQLiteDatabase.identify(session: SessionRow, input: StageInput): TurnOutcome {
        val candidates = input.candidates.sortedByDescending { it.confidence }
        val hint = { typeId: String ->
            string("SELECT disambiguation_hint FROM incident_types WHERE incident_type_id = ?", typeId)
        }
        if (input.distinctIncidentsMentioned) {
            // Split by candidate position; each half is evaluated on its own.
            val primary = evaluateCandidates(listOfNotNull(candidates.getOrNull(0)), hint)
            val secondary = evaluateCandidates(listOfNotNull(candidates.getOrNull(1)), hint)
            val groupId = session.linkedGroupId ?: "group_${session.sessionId}"
            execSQL("UPDATE draft_sessions SET proposed_incident_type_id = ?, state = ?, linked_group_id = ? WHERE session_id = ?",
                arrayOf(primary.proposedIncidentTypeId, primary.state.name, groupId, session.sessionId))
            val linkedId = "${session.sessionId}_linked"
            if (string("SELECT session_id FROM draft_sessions WHERE session_id = ?", linkedId) == null) {
                val at = now()
                execSQL("INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, state, " +
                    "proposed_incident_type_id, linked_group_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(linkedId, session.orgId, session.reporterUserId, at, at, secondary.state.name,
                        secondary.proposedIncidentTypeId, groupId))
            } else {
                execSQL("UPDATE draft_sessions SET proposed_incident_type_id = ?, state = ? WHERE session_id = ?",
                    arrayOf(secondary.proposedIncidentTypeId, secondary.state.name, linkedId))
            }
            return TurnOutcome(primary.state, primary.followupText)
        }
        val result = evaluateCandidates(candidates, hint)
        execSQL("UPDATE draft_sessions SET proposed_incident_type_id = ?, state = ? WHERE session_id = ?",
            arrayOf(result.proposedIncidentTypeId, result.state.name, session.sessionId))
        return TurnOutcome(result.state, result.followupText)
    }

    private fun SQLiteDatabase.formFilling(sessionId: String, input: StageInput, turnId: String): TurnOutcome {
        val session = session(sessionId)
        val turnCount = long("SELECT COUNT(*) FROM dialogue_turns WHERE session_id = ? AND stage = ?",
            sessionId, SessionState.FORM_FILLING.name)
        val required = formFields(session.proposedIncidentTypeId, FieldKind.REQUIRED)
        val asked = required.firstOrNull { fieldStatus(sessionId, it.fieldId) in OPEN_STATUSES }
        applyAnswer(session, input, asked, turnId)
        val askable = required.filter { fieldStatus(sessionId, it.fieldId) in OPEN_STATUSES }
        val outcome = when {
            turnCount >= MAX_FORM_FILLING_TURNS -> {
                // Safety bound: stop asking and move on with the gaps recorded as unknown.
                for (field in askable) setField(sessionId, field.fieldId, session.currentRevision, FieldStatus.UNKNOWN,
                    null, 1.0, "system_forced", null)
                TurnOutcome(SessionState.REVIEWING, null)
            }
            askable.isEmpty() -> TurnOutcome(SessionState.CLARIFYING, null)
            else -> TurnOutcome(SessionState.FORM_FILLING, askable[0].promptTemplate)
        }
        setState(sessionId, outcome.state)
        return outcome
    }

    private fun SQLiteDatabase.clarify(sessionId: String, input: StageInput, turnId: String): TurnOutcome {
        val session = session(sessionId)
        val turnCount = long("SELECT COUNT(*) FROM dialogue_turns WHERE session_id = ? AND stage = ?",
            sessionId, SessionState.CLARIFYING.name)
        val clarifying = formFields(session.proposedIncidentTypeId, FieldKind.CLARIFYING)
        val asked = clarifying.firstOrNull { fieldStatus(sessionId, it.fieldId) in OPEN_STATUSES }
        applyAnswer(session, input, asked, turnId)
        val askable = clarifying.filter { fieldStatus(sessionId, it.fieldId) in OPEN_STATUSES }
        val outcome = when {
            input.userWantsToStopClarifying -> TurnOutcome(SessionState.REVIEWING, null)
            turnCount >= MAX_CLARIFYING_QUESTIONS -> TurnOutcome(SessionState.REVIEWING, null)
            askable.isEmpty() -> TurnOutcome(SessionState.REVIEWING, null)
            else -> TurnOutcome(SessionState.CLARIFYING, askable[0].promptTemplate)
        }
        setState(sessionId, outcome.state)
        return outcome
    }

    /** One answer may fill several fields; "I don't know" closes only the field that was being asked. */
    private fun SQLiteDatabase.applyAnswer(session: SessionRow, input: StageInput, asked: FormField?, turnId: String) {
        val byName = formFields(session.proposedIncidentTypeId).associate { it.fieldName to it.fieldId }
        for (candidate in input.fields) {
            val fieldId = byName[candidate.name] ?: continue
            setField(session.sessionId, fieldId, session.currentRevision, FieldStatus.fromWire(candidate.status),
                candidate.value?.toString(), candidate.confidence, candidate.provenance, turnId)
        }
        if (input.dialogueAct == DialogueAct.UNCERTAINTY && asked != null)
            setField(session.sessionId, asked.fieldId, session.currentRevision, FieldStatus.UNKNOWN, null, 1.0, "user", turnId)
    }

    companion object {
        const val MAX_FORM_FILLING_TURNS = 6
        const val MAX_CLARIFYING_QUESTIONS = 3
        const val CONFIDENT_TOP = 0.8
        const val CONFIDENT_GAP = 0.15
        const val GENERIC_CLASSIFY_QUESTION = "What kind of issue is this?"

        /** [candidates] must already be sorted by confidence, highest first. */
        fun evaluateCandidates(candidates: List<IncidentCandidate>, disambiguationHint: (String) -> String?): Identification {
            if (candidates.isEmpty()) return Identification(null, SessionState.CLASSIFYING, GENERIC_CLASSIFY_QUESTION)
            val top = candidates[0]
            val confident = top.confidence >= CONFIDENT_TOP &&
                (candidates.size == 1 || top.confidence - candidates[1].confidence >= CONFIDENT_GAP)
            return if (confident) Identification(top.incidentTypeId, SessionState.FORM_FILLING, null)
            else Identification(null, SessionState.DISAMBIGUATING, disambiguationHint(top.incidentTypeId))
        }
    }
}
