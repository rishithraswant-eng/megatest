import uuid
import os
from backend.database import get_db_connection, init_db
from backend.version_manager import publish_version, activate_version, rollback_version
from backend.tests.validation_harness import evaluate_harness

def run_tests():
    db_path = os.path.join(os.path.dirname(__file__), "..", "incident_assistant.db")
    if os.path.exists(db_path):
        os.remove(db_path)
    init_db()
    
    print("--- 4.4: VERSION MANAGER TESTS ---")
    v1 = publish_version("schema", "form_fields", "v1.0")
    v2 = publish_version("schema", "form_fields", "v1.1")
    
    activate_version(v1)
    activate_version(v2)
    print(f"Activated v2 ({v2})")
    
    rollback_version(v2, v1)
    print(f"Rolled back v2 to v1 ({v1})")
    
    conn = get_db_connection()
    c = conn.cursor()
    c.execute("SELECT version_id, is_active, rollback_of FROM schema_versions ORDER BY released_at ASC")
    for r in c.fetchall():
        print(f"Version: {r['version_id']}, Active: {r['is_active']}, RollbackOf: {r['rollback_of']}")
    
    print("\n--- 4.5: VALIDATION HARNESS TESTS ---")
    org_id = "org_1"
    user_id = "user_1"
    inc_type_id = "type_1"
    sess_id = "sess_" + str(uuid.uuid4())
    
    conn.execute("INSERT INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) VALUES (?, 'Org', '2026', 'x', 1, 1)", (org_id,))
    conn.execute("INSERT INTO users (user_id, org_id, display_name, role) VALUES (?, ?, 'Tester', 'employee')", (user_id, org_id))
    conn.execute("INSERT INTO departments (department_id, org_id, name) VALUES ('d1', ?, 'Dept')", (org_id,))
    conn.execute("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority) VALUES (?, ?, 'Leak', 'd1', 'Low')", (inc_type_id, org_id))
    
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES ('f1', ?, 'Severity', 'required', 'text', '?', 1)", (inc_type_id,))
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES ('f2', ?, 'Color', 'clarifying', 'text', '?', 2)", (inc_type_id,))
    
    conn.execute("INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, state, proposed_incident_type_id) VALUES (?, ?, ?, '2026', '2026', 'REVIEWING', ?)", (sess_id, org_id, user_id, inc_type_id))
    
    conn.execute("INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance) VALUES (?, ?, 'f1', 1, 'High', 'known', 'llm')", (str(uuid.uuid4()), sess_id))
    conn.execute("INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance) VALUES (?, ?, 'f2', 1, 'Blue', 'known', 'llm')", (str(uuid.uuid4()), sess_id))
    
    conn.commit()
    conn.close()
    
    expected = {
        "incident_type_id": "type_1",
        "fields": {"Severity": "High", "Color": "Red"} # Intentionally expecting Red to test false fill
    }
    
    metrics = evaluate_harness(sess_id, expected)
    print("Harness Metrics:")
    for k, v in metrics.items():
        print(f"  {k}: {v}")

if __name__ == '__main__':
    run_tests()
