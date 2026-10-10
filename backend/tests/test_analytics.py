import uuid
import os
import datetime
from backend.database import get_db_connection, init_db
from backend.analytics_manager import compute_incident_relations, compute_causal_edges
from backend.submit_manager import submit_incident

def setup_db():
    db_path = os.path.join(os.path.dirname(__file__), "..", "incident_assistant.db")
    if os.path.exists(db_path):
        os.remove(db_path)
    init_db()
    
    conn = get_db_connection()
    org_id = "org_1"
    
    conn.execute("INSERT INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) VALUES (?, 'Org', '2026', 'x', 1, 1)", (org_id,))
    conn.execute("INSERT INTO users (user_id, org_id, display_name, role) VALUES ('u1', ?, 'Tester', 'employee')", (org_id,))
    conn.execute("INSERT INTO departments (department_id, org_id, name) VALUES ('d1', ?, 'Maintenance')", (org_id,))
    conn.execute("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority) VALUES ('type_leak', ?, 'Leak', 'd1', 'Low')", (org_id,))
    conn.execute("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority) VALUES ('type_slip', ?, 'Slip', 'd1', 'High')", (org_id,))
    conn.execute("INSERT INTO zones (zone_id, org_id, name, created_at, polygon_json) VALUES ('z1', ?, 'Kitchen', '2026', '[]')", (org_id,))
    conn.execute("INSERT INTO zones (zone_id, org_id, name, created_at, polygon_json) VALUES ('z2', ?, 'Hallway', '2026', '[]')", (org_id,))
    
    conn.commit()
    return org_id, conn

def mock_incident(conn, org_id, zone_id, type_id, desc, dt_str, inc_id=None):
    inc_id = inc_id or "inc_" + str(uuid.uuid4())
    sess_id = "sess_" + str(uuid.uuid4())
    conn.execute("INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, state, current_revision, confirmed_revision) VALUES (?, ?, 'u1', '2026', '2026', 'SUBMITTED', 1, 1)", (sess_id, org_id))
    conn.execute("""
        INSERT INTO incidents (incident_id, org_id, session_id, reporter_user_id, zone_id, incident_type_id, department_id, description_en, original_lang, reported_at, priority, status, confirmed_revision, local_submission_only)
        VALUES (?, ?, ?, 'u1', ?, ?, 'd1', ?, 'en', ?, 'Low', 'Open', 1, 1)
    """, (inc_id, org_id, sess_id, zone_id, type_id, desc, dt_str))
    conn.commit()
    return inc_id

def mock_session_for_submit(conn, org_id, zone_id, type_id, desc, dt_str):
    sess_id = "sess_" + str(uuid.uuid4())
    conn.execute("INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, state, current_revision, confirmed_revision, proposed_incident_type_id) VALUES (?, ?, 'u1', '2026', '2026', 'REVIEWING', 1, 1, ?)", (sess_id, org_id, type_id))
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Description', 'required', 'text', '?', 1)", (sess_id+"_f", type_id))
    conn.execute("INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance) VALUES (?, ?, ?, 1, ?, 'known', 'user')", (str(uuid.uuid4()), sess_id, sess_id+"_f", desc))
    
    # Map zone name for submit_manager
    zname = "Kitchen" if zone_id == "z1" else "Hallway"
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Location', 'required', 'text', '?', 2)", (sess_id+"_fl", type_id))
    conn.execute("INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance) VALUES (?, ?, ?, 1, ?, 'known', 'user')", (str(uuid.uuid4()), sess_id, sess_id+"_fl", zname))
    
    conn.commit()
    return sess_id

def run_tests():
    org_id, conn = setup_db()
    
    print("--- 4.6.A: INCIDENT RELATEDNESS ---")
    # Base incident
    inc_base = mock_incident(conn, org_id, 'z1', 'type_leak', "Water leaking from the ceiling heavily", "2026-10-10T10:00:00")
    
    # 1. Related: Same zone, same type, same day, similar desc
    inc_rel = mock_incident(conn, org_id, 'z1', 'type_leak', "Ceiling water leak is getting worse", "2026-10-10T12:00:00")
    compute_incident_relations(inc_rel)
    
    # 2. Unrelated: Different zone, different type, different day
    inc_unrel = mock_incident(conn, org_id, 'z2', 'type_slip', "Someone slipped on dry floor", "2026-10-01T10:00:00")
    compute_incident_relations(inc_unrel)
    
    cursor = conn.cursor()
    cursor.execute("SELECT incident_id_b, relation_score, relation_reason FROM incident_relations WHERE incident_id_a = ?", (inc_rel,))
    rel_rows = cursor.fetchall()
    print(f"Relations for related incident ({inc_rel}):")
    for r in rel_rows:
        print(f"  Target: {r['incident_id_b']}, Score: {r['relation_score']}, Reason: {r['relation_reason']}")
        
    cursor.execute("SELECT incident_id_b FROM incident_relations WHERE incident_id_a = ?", (inc_unrel,))
    unrel_rows = cursor.fetchall()
    print(f"Relations for unrelated incident ({inc_unrel}): {len(unrel_rows)} rows (Expected 0)")
    
    # 3. Test exception swallowing in submit_incident
    print("\n--- 4.6.A: EXCEPTION SWALLOWING ---")
    sess_id = mock_session_for_submit(conn, org_id, 'z1', 'type_leak', "Testing exception", "2026-10-10T15:00:00")
    
    # We will monkeypatch compute_incident_relations to throw
    import backend.analytics_manager
    original_compute = backend.analytics_manager.compute_incident_relations
    def throw_compute(inc_id):
        raise RuntimeError("Forced exception for testing")
    backend.analytics_manager.compute_incident_relations = throw_compute
    
    try:
        new_inc_id = submit_incident(sess_id)
        print(f"submit_incident returned successfully: {new_inc_id}")
        cursor.execute("SELECT status FROM incidents WHERE incident_id = ?", (new_inc_id,))
        print(f"Incident is saved in DB: {cursor.fetchone()['status'] == 'Open'}")
    except Exception as e:
        print(f"FAIL: Exception leaked out! {e}")
        
    backend.analytics_manager.compute_incident_relations = original_compute
    
    print("\n--- 4.6.B: CAUSAL TREND JOB ---")
    # Clean incidents and relations to test purely causal edge sample size
    conn.execute("DELETE FROM incident_relations")
    conn.execute("DELETE FROM causal_edges")
    conn.execute("DELETE FROM incidents")
    conn.commit()
    
    # Insert 4 spaced-out pairs to prove 0 edges when exact co-occurrence < 5
    # Pair 1
    mock_incident(conn, org_id, 'z1', 'type_leak', "Leak", "2026-10-01T10:00:00")
    mock_incident(conn, org_id, 'z2', 'type_slip', "Slip", "2026-10-03T10:00:00")
    # Pair 2
    mock_incident(conn, org_id, 'z1', 'type_leak', "Leak", "2026-10-11T10:00:00")
    mock_incident(conn, org_id, 'z2', 'type_slip', "Slip", "2026-10-13T10:00:00")
    # Pair 3
    mock_incident(conn, org_id, 'z1', 'type_leak', "Leak", "2026-10-21T10:00:00")
    mock_incident(conn, org_id, 'z2', 'type_slip', "Slip", "2026-10-23T10:00:00")
    # Pair 4
    mock_incident(conn, org_id, 'z1', 'type_leak', "Leak", "2026-10-31T10:00:00")
    mock_incident(conn, org_id, 'z2', 'type_slip', "Slip", "2026-11-02T10:00:00")
    
    compute_causal_edges(org_id)
    cursor.execute("SELECT COUNT(*) as c FROM causal_edges")
    print(f"Causal Edges (Sparse/Noisy, Max Co-occurrence = 4): {cursor.fetchone()['c']} (Expected 0)")
    
    # Add Pair 5 to push exact co-occurrence to 5
    mock_incident(conn, org_id, 'z1', 'type_leak', "Leak", "2026-11-11T10:00:00")
    mock_incident(conn, org_id, 'z2', 'type_slip', "Slip", "2026-11-13T10:00:00")
    
    compute_causal_edges(org_id)
    cursor.execute("SELECT cause_factor, effect_incident_type_id, weight, lag_hint FROM causal_edges")
    edges = cursor.fetchall()
    print(f"Causal Edges (Sample Size = 5): {len(edges)} (Expected > 0)")
    for e in edges:
        print(f"  {e['cause_factor']} -> {e['effect_incident_type_id']} (Weight: {e['weight']}, Lag: {e['lag_hint']})")
    
    conn.close()

if __name__ == "__main__":
    run_tests()
