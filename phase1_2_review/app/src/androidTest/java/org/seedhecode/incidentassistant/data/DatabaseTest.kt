package org.seedhecode.incidentassistant.data

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.seedhecode.core.inference.*

/** Fresh, FK-enforced incident database per test. */
abstract class DatabaseTest {
    protected val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "${javaClass.simpleName}.db"
    protected lateinit var database: IncidentDatabase
    protected val db: SQLiteDatabase get() = database.writableDatabase

    @Before fun openDatabase() { context.deleteDatabase(name); database = IncidentDatabase(context, name) }
    @After fun closeDatabase() { database.close(); context.deleteDatabase(name) }

    protected fun exec(sql: String, vararg args: Any?) = db.execSQL(sql, args)
    protected fun query(sql: String, vararg args: String): String? = db.string(sql, *args)
    protected fun count(sql: String, vararg args: String): Long = db.long(sql, *args)

    protected fun seedOrg(orgId: String = "org_1", userId: String = "user_1") {
        exec("INSERT INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) VALUES (?, 'Org', '2026', 'x', 1, 1)", orgId)
        exec("INSERT INTO users (user_id, org_id, display_name, role) VALUES (?, ?, 'Tester', 'employee')", userId, orgId)
    }
    protected fun seedType(typeId: String, departmentId: String = "d1", orgId: String = "org_1", hint: String? = null) =
        exec("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority, disambiguation_hint) VALUES (?, ?, ?, ?, 'Low', ?)",
            typeId, orgId, typeId, departmentId, hint)
    protected fun seedField(fieldId: String, typeId: String, name: String, kind: String, prompt: String, order: Int) =
        exec("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, ?, ?, 'text', ?, ?)",
            fieldId, typeId, name, kind, prompt, order)
    protected fun createSession(sessionId: String, state: SessionState, typeId: String? = null, revision: Int = 1,
        confirmed: Int? = null, orgId: String = "org_1", userId: String = "user_1"): String {
        exec("INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, state, proposed_incident_type_id, current_revision, confirmed_revision) VALUES (?, ?, ?, '2026', '2026', ?, ?, ?, ?)",
            sessionId, orgId, userId, state.name, typeId, revision, confirmed)
        return sessionId
    }
    protected fun putField(sessionId: String, fieldId: String, value: String?, status: String = "known", revision: Int = 1, provenance: String = "user") =
        exec("INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence) VALUES (?, ?, ?, ?, ?, ?, ?, 1.0)",
            newId(), sessionId, fieldId, revision, value, status, provenance)
    protected fun state(sessionId: String) = SessionState.valueOf(query("SELECT state FROM draft_sessions WHERE session_id = ?", sessionId)!!)
    protected fun liveStatus(sessionId: String, fieldName: String): String? = query(
        "SELECT e.status FROM extracted_fields e JOIN form_fields f ON e.field_id = f.field_id WHERE e.session_id = ? AND f.field_name = ? AND e.superseded = 0",
        sessionId, fieldName)

    /** An engine TurnResult with only the parts the app logic reads. */
    protected fun turn(text: String = "", candidates: List<Pair<String, Double>> = emptyList(),
        fields: List<Pair<String, String>> = emptyList(), act: DialogueAct? = null, distinct: Boolean = false,
        stop: Boolean = false, calls: List<ToolResult> = emptyList(), sessionId: String = "engine", revision: Long = 0) = TurnResult(
        TurnKey(sessionId, 0, revision), Transcript(text, text, "en", true),
        Classification(if (candidates.isEmpty()) ClassificationOutcome.UNDETERMINED else ClassificationOutcome.INCIDENT,
            candidates.map { IncidentCandidate(it.first, it.second) }, emptyMap(), false),
        Extraction(fields.map { (name, value) -> CandidateField(name, value, 0.9, Evidence(value, 0, value.length), "known", "user") },
            act, emptyList(), emptyList(), distinct, stop),
        calls, emptyList(), 0, emptyMap())
}
