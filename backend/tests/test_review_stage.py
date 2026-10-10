import uuid
from backend.database import get_db_connection, init_db
from backend.review_manager import (
    generate_review_summary,
    action_confirm,
    action_correct,
    action_skip_clarification,
    action_cancel
)

def setup_db():
    init_db()
    conn = get_db_connection()
    org_id = str(uuid.uuid4())
    user_id = str(uuid.uuid4())
    inc_type_id = "test_inc_" + str(uuid.uuid4())
    
    conn.execute("INSERT OR IGNORE INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) VALUES (?, 'Test Org', '2026', 'x', 1, 1)", (org_id,))
    conn.execute("INSERT OR IGNORE INTO users (user_id, org_id, display_name, role) VALUES (?, ?, 'Tester', 'employee')", (user_id, org_id))
    
    conn.execute("INSERT OR IGNORE INTO departments (department_id, org_id, name) VALUES ('d1', ?, 'Dept')", (org_id,))
    conn.execute("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority) VALUES (?, ?, 'Test Inc', 'd1', 'Low')", (inc_type_id, org_id))
    
    # Required Fields
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Severity', 'required', 'text', 'How bad is it?', 1)", ('f_sev', inc_type_id))
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Priority', 'required', 'text', 'Priority?', 2)", ('f_prio', inc_type_id))
    # Clarifying Fields
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Color', 'clarifying', 'text', 'What color is the liquid?', 3)", ('f_col', inc_type_id))
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Smell', 'clarifying', 'text', 'Does it smell?', 4)", ('f_sml', inc_type_id))
    
    conn.commit()
    return org_id, user_id, inc_type_id, conn

def mock_session_at_review(conn, org_id, user_id, inc_type_id):
    session_id = "sess_" + str(uuid.uuid4())
    conn.execute("""
        INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, current_revision, state, proposed_incident_type_id)
        VALUES (?, ?, ?, '2026', '2026', 1, 'REVIEWING', ?)
    """, (session_id, org_id, user_id, inc_type_id))
    
    # Insert extracted fields at rev 1
    # Severity: high
    conn.execute("""
        INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence)
        VALUES (?, ?, 'f_sev', 1, 'high', 'known', 'user', 1.0)
    """, (str(uuid.uuid4()), session_id))
    # Priority: P1
    conn.execute("""
        INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence)
        VALUES (?, ?, 'f_prio', 1, 'P1', 'known', 'system', 1.0)
    """, (str(uuid.uuid4()), session_id))
    # Color: red (answered clarifying field)
    conn.execute("""
        INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence)
        VALUES (?, ?, 'f_col', 1, 'red', 'known', 'user', 1.0)
    """, (str(uuid.uuid4()), session_id))
    # Smell is left unanswered (no row)
    
    conn.commit()
    return session_id

def print_summary(summary):
    for k, v in summary.items():
        print(f"  {k}: {v['value']} ({v['status']}) - {v['kind']}")

def run_tests():
    # We must wipe and reinit DB to ensure schema changes apply cleanly
    import os
    db_path = os.path.join(os.path.dirname(__file__), "..", "incident_assistant.db")
    if os.path.exists(db_path):
        os.remove(db_path)
    
    org_id, user_id, inc_type_id, conn = setup_db()
    
    print("\n--- Test A: Clean CONFIRM ---")
    sa = mock_session_at_review(conn, org_id, user_id, inc_type_id)
    summary_a = generate_review_summary(sa)
    print("Initial Summary:")
    print_summary(summary_a)
    action_confirm(sa)
    cursor = conn.cursor()
    cursor.execute("SELECT confirmed_revision FROM draft_sessions WHERE session_id = ?", (sa,))
    print(f"Confirmed revision: {cursor.fetchone()['confirmed_revision']}")
    
    print("\n--- Test B: CORRECT invalidating a dependent field -> CONFIRM ---")
    sb = mock_session_at_review(conn, org_id, user_id, inc_type_id)
    # Insert a dummy turn to satisfy the new action_correct signature
    dummy_turn = str(uuid.uuid4())
    conn.execute("INSERT INTO dialogue_turns (turn_id, session_id, turn_index, draft_revision, speaker, stage, raw_text, timestamp) VALUES (?, ?, 1, 1, 'user', 'REVIEWING', 'test', '2026')", (dummy_turn, sb))
    conn.commit()

    print("Correcting Severity to 'low'...")
    summary_b = action_correct(sb, 'Severity', 'low', dummy_turn)
    print("Regenerated Summary:")
    print_summary(summary_b)
    action_confirm(sb)
    cursor.execute("SELECT confirmed_revision FROM draft_sessions WHERE session_id = ?", (sb,))
    print(f"Confirmed revision: {cursor.fetchone()['confirmed_revision']}")
    
    print("\n--- Test C: CORRECT twice in a row ---")
    sc = mock_session_at_review(conn, org_id, user_id, inc_type_id)
    dummy_turn_c = str(uuid.uuid4())
    conn.execute("INSERT INTO dialogue_turns (turn_id, session_id, turn_index, draft_revision, speaker, stage, raw_text, timestamp) VALUES (?, ?, 1, 1, 'user', 'REVIEWING', 'test', '2026')", (dummy_turn_c, sc))
    conn.commit()
    
    print("Correction 1: Color to 'blue'")
    action_correct(sc, 'Color', 'blue', dummy_turn_c)
    print("Correction 2: Color to 'green'")
    action_correct(sc, 'Color', 'green', dummy_turn_c)
    
    cursor.execute("SELECT current_revision FROM draft_sessions WHERE session_id = ?", (sc,))
    print(f"Draft Revision: {cursor.fetchone()['current_revision']}")
    cursor.execute("SELECT COUNT(*) as c FROM correction_history WHERE session_id = ?", (sc,))
    print(f"Correction History Rows: {cursor.fetchone()['c']}")
    
    print("\n--- Test D: SKIP remaining clarification -> CONFIRM ---")
    # For this test, let's start in CLARIFYING
    sd = mock_session_at_review(conn, org_id, user_id, inc_type_id)
    conn.execute("UPDATE draft_sessions SET state = 'CLARIFYING' WHERE session_id = ?", (sd,))
    conn.commit()
    
    summary_d = action_skip_clarification(sd)
    print("Summary after SKIP:")
    print_summary(summary_d)
    action_confirm(sd)
    cursor.execute("SELECT state, confirmed_revision FROM draft_sessions WHERE session_id = ?", (sd,))
    row_d = cursor.fetchone()
    print(f"State: {row_d['state']}, Confirmed Revision: {row_d['confirmed_revision']}")
    
    print("\n--- Test E: CANCEL ---")
    se = mock_session_at_review(conn, org_id, user_id, inc_type_id)
    action_cancel(se)
    cursor.execute("SELECT state FROM draft_sessions WHERE session_id = ?", (se,))
    print(f"State: {cursor.fetchone()['state']}")

run_tests()
