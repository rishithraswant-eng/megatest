import uuid
import os
from backend.database import get_db_connection, init_db
from backend.submit_manager import submit_incident
from backend.auth_manager import hash_password, login_admin, check_department_access

def setup_db():
    db_path = os.path.join(os.path.dirname(__file__), "..", "incident_assistant.db")
    if os.path.exists(db_path):
        os.remove(db_path)
    init_db()
    
    conn = get_db_connection()
    org_id = "org_1"
    user_id = "user_1"
    
    conn.execute("INSERT INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) VALUES (?, 'Org', '2026', 'x', 1, 1)", (org_id,))
    conn.execute("INSERT INTO users (user_id, org_id, display_name, role) VALUES (?, ?, 'Tester', 'employee')", (user_id, org_id))
    
    # Zones
    conn.execute("INSERT INTO zones (zone_id, org_id, name, created_at, polygon_json) VALUES ('z1', ?, 'Kitchen', '2026', '[]')", (org_id,))
    
    # Departments
    conn.execute("INSERT INTO departments (department_id, org_id, name) VALUES ('d1', ?, 'Maintenance')", (org_id,))
    conn.execute("INSERT INTO departments (department_id, org_id, name) VALUES ('d2', ?, 'IT')", (org_id,))
    
    # Incident Types
    conn.execute("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority) VALUES ('t1', ?, 'Leak', 'd1', 'Low')", (org_id,))
    
    # Admin Credentials
    # Dept Admin for d1
    conn.execute("INSERT INTO users (user_id, org_id, display_name, role) VALUES ('admin_d1', ?, 'Admin D1', 'admin')", (org_id,))
    conn.execute("INSERT INTO admin_credentials (admin_id, user_id, password_hash, role_level, department_id, created_at) VALUES ('a_d1', 'admin_d1', ?, 'department_admin', 'd1', '2026')", (hash_password("pass123"),))
    
    # Super Admin
    conn.execute("INSERT INTO users (user_id, org_id, display_name, role) VALUES ('admin_super', ?, 'Super Admin', 'admin')", (org_id,))
    conn.execute("INSERT INTO admin_credentials (admin_id, user_id, password_hash, role_level, created_at) VALUES ('a_sup', 'admin_super', ?, 'super_admin', '2026')", (hash_password("super456"),))
    
    conn.commit()
    return org_id, user_id, conn

def mock_session(conn, org_id, user_id, loc_val, confirmed=True):
    session_id = "sess_" + str(uuid.uuid4())
    state = 'REVIEWING' if confirmed else 'FORM_FILLING'
    conf_rev = 1 if confirmed else None
    
    conn.execute("""
        INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, current_revision, state, proposed_incident_type_id, confirmed_revision)
        VALUES (?, ?, ?, '2026', '2026', 1, ?, 't1', ?)
    """, (session_id, org_id, user_id, state, conf_rev))
    
    # Insert extracted location to test zone matching
    if loc_val:
        conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, 't1', 'Location', 'required', 'text', '?', 1)", (session_id+"_f",))
        conn.execute("""
            INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence)
            VALUES (?, ?, ?, 1, ?, 'known', 'user', 1.0)
        """, (str(uuid.uuid4()), session_id, session_id+"_f", loc_val))
    
    conn.commit()
    return session_id

def run_tests():
    org_id, user_id, conn = setup_db()
    
    print("--- 4.2: SUBMIT TRANSACTION LOGIC ---")
    
    # a. clean submit with a known zone (Location = 'Kitchen')
    print("\nTest 4.2.a: Clean submit with known zone")
    s_a = mock_session(conn, org_id, user_id, "Kitchen")
    inc_a = submit_incident(s_a)
    cursor = conn.cursor()
    cursor.execute("SELECT zone_id, status FROM incidents WHERE incident_id = ?", (inc_a,))
    row = cursor.fetchone()
    print(f"Incident {inc_a} created. Zone ID: {row['zone_id']}, Status: {row['status']}")
    
    # b. submit with zone unknown
    print("\nTest 4.2.b: Submit with zone unknown")
    s_b = mock_session(conn, org_id, user_id, "Hallway")
    inc_b = submit_incident(s_b)
    cursor.execute("SELECT zone_id FROM incidents WHERE incident_id = ?", (inc_b,))
    row = cursor.fetchone()
    print(f"Incident {inc_b} created. Zone ID: {row['zone_id']} (should be None)")
    
    # c. calling twice (duplicate protection)
    print("\nTest 4.2.c: Call submit twice (Idempotency)")
    inc_c1 = submit_incident(s_b) # call on s_b again
    print(f"Second call returned: {inc_c1} (Matches first: {inc_c1 == inc_b})")
    
    # d. attempting submit on unconfirmed session
    print("\nTest 4.2.d: Submit unconfirmed session")
    s_d = mock_session(conn, org_id, user_id, "Kitchen", confirmed=False)
    try:
        submit_incident(s_d)
        print("FAIL: Should have raised ValueError")
    except ValueError as e:
        print(f"Successfully refused: {e}")
        
    print("\n--- 4.3: ADMIN AUTH & ROLE ENFORCEMENT ---")
    
    print("\nTest 4.3.a: Successful login")
    login1 = login_admin('admin_d1', 'pass123')
    print(f"Login admin_d1: Success, Role: {login1['role_level']}")
    
    print("\nTest 4.3.b: Failed login (wrong password)")
    login2 = login_admin('admin_d1', 'wrong')
    print(f"Login admin_d1 wrong password: {'Failed' if not login2 else 'Success'}")
    
    print("\nTest 4.3.c: Department Admin blocked from another dept's data")
    can_see_d1 = check_department_access('a_d1', 'd1')
    can_see_d2 = check_department_access('a_d1', 'd2')
    print(f"admin_d1 -> d1 (Maintenance): {can_see_d1}")
    print(f"admin_d1 -> d2 (IT): {can_see_d2}")
    
    print("\nTest 4.3.d: Super Admin allowed everywhere")
    sup_can_see_d1 = check_department_access('a_sup', 'd1')
    sup_can_see_d2 = check_department_access('a_sup', 'd2')
    print(f"admin_super -> d1: {sup_can_see_d1}")
    print(f"admin_super -> d2: {sup_can_see_d2}")

run_tests()
