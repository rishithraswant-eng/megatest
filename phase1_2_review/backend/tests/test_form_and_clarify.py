from backend.stub_engine import TurnResponse, TranscriptInfo, IncidentTypeCandidate, CandidateField
from backend.conversation_manager import process_turn_logic
from backend.database import get_db_connection
import uuid

def setup_db():
    conn = get_db_connection()
    org_id = str(uuid.uuid4())
    user_id = str(uuid.uuid4())
    inc_type_id = "test_inc_" + str(uuid.uuid4())
    inc_type_2 = "test_inc2_" + str(uuid.uuid4())
    
    conn.execute("INSERT OR IGNORE INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) VALUES (?, 'Test Org', '2026', 'x', 1, 1)", (org_id,))
    conn.execute("INSERT OR IGNORE INTO users (user_id, org_id, display_name, role) VALUES (?, ?, 'Tester', 'employee')", (user_id, org_id))
    
    conn.execute("INSERT OR IGNORE INTO departments (department_id, org_id, name) VALUES ('d1', ?, 'Dept')", (org_id,))
    conn.execute("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority) VALUES (?, ?, 'Test Inc', 'd1', 'Low')", (inc_type_id, org_id))
    conn.execute("INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority) VALUES (?, ?, 'Test Inc 2', 'd1', 'Low')", (inc_type_2, org_id))
    
    # Required Fields for Inc 1
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Location', 'required', 'text', 'Where is it?', 1)", (str(uuid.uuid4()), inc_type_id))
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Severity', 'required', 'text', 'How bad is it?', 2)", (str(uuid.uuid4()), inc_type_id))
    
    # Clarifying Fields for Inc 1
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Color', 'clarifying', 'text', 'What color is the liquid?', 3)", (str(uuid.uuid4()), inc_type_id))
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Smell', 'clarifying', 'text', 'Does it smell?', 4)", (str(uuid.uuid4()), inc_type_id))
    
    # Required Fields for Inc 2
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Tool', 'required', 'text', 'Which tool?', 1)", (str(uuid.uuid4()), inc_type_2))
    
    # Clarifying Fields for Inc 2
    conn.execute("INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, prompt_template, display_order) VALUES (?, ?, 'Safety', 'clarifying', 'text', 'Is it safe?', 2)", (str(uuid.uuid4()), inc_type_2))
    
    conn.commit()
    return org_id, user_id, inc_type_id, inc_type_2, conn

def create_session(conn, org_id, user_id, state='FORM_FILLING', inc_type_id=None):
    session_id = "sess_" + str(uuid.uuid4())
    conn.execute("""
        INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, state, proposed_incident_type_id, current_revision)
        VALUES (?, ?, ?, '2026', '2026', ?, ?, 1)
    """, (session_id, org_id, user_id, state, inc_type_id))
    conn.commit()
    return session_id

def run_tests():
    org_id, user_id, inc_type_id, inc_type_2, conn = setup_db()
    
    print("\n--- Test 1: One answer fills multiple required fields ---")
    s1 = create_session(conn, org_id, user_id, 'FORM_FILLING', inc_type_id)
    t1 = TurnResponse(
        transcript=TranscriptInfo(text="It's a big leak in the kitchen", is_final=True, lang_detected="en"),
        candidate_fields=[
            CandidateField(field_name="Location", value="kitchen", confidence=0.9, provenance="user"),
            CandidateField(field_name="Severity", value="big", confidence=0.9, provenance="user")
        ]
    )
    state, followup = process_turn_logic(s1, t1)
    print(f"State: {state} | Followup: {followup}")

    print("\n--- Test 2: 'I don't know' sets field to unknown and never asked again ---")
    s2 = create_session(conn, org_id, user_id, 'FORM_FILLING', inc_type_id)
    t2_a = TurnResponse(
        transcript=TranscriptInfo(text="kitchen", is_final=True, lang_detected="en"),
        candidate_fields=[CandidateField(field_name="Location", value="kitchen", confidence=0.9, provenance="user")]
    )
    process_turn_logic(s2, t2_a) # Ask Severity
    
    t2_b = TurnResponse(
        transcript=TranscriptInfo(text="I don't know", is_final=True, lang_detected="en"),
        dialogue_act="uncertainty"
    )
    state, followup = process_turn_logic(s2, t2_b)
    print(f"State: {state} | Followup: {followup}")

    print("\n--- Test 3: Field already known/unknown is not re-asked ---")
    s3 = create_session(conn, org_id, user_id, 'FORM_FILLING', inc_type_id)
    process_turn_logic(s3, t2_a) # Known Location
    t3_b = TurnResponse(transcript=TranscriptInfo(text="Oh boy", is_final=True, lang_detected="en"))
    state, followup = process_turn_logic(s3, t3_b)
    print(f"State: {state} | Followup: {followup}")

    print("\n--- Test 4: CLARIFYING stops via max-question bound ---")
    s4 = create_session(conn, org_id, user_id, 'CLARIFYING', inc_type_id)
    for i in range(3):
        state, followup = process_turn_logic(s4, t3_b)
    print(f"After 3 clarifying turns State: {state}")

    print("\n--- Test 5: CLARIFYING stops via user's 'enough' signal ---")
    s5 = create_session(conn, org_id, user_id, 'CLARIFYING', inc_type_id)
    t5 = TurnResponse(
        transcript=TranscriptInfo(text="Stop asking questions", is_final=True, lang_detected="en"),
        user_wants_to_stop_clarifying=True
    )
    state, followup = process_turn_logic(s5, t5)
    print(f"State: {state}")

    print("\n--- Test 6: End to End Phase 3 ---")
    s6 = create_session(conn, org_id, user_id, 'LISTENING')
    
    t6_a = TurnResponse(
        transcript=TranscriptInfo(text="Problem", is_final=True, lang_detected="en"),
        candidate_incident_types=[
            IncidentTypeCandidate(incident_type_id=inc_type_id, confidence=0.75),
            IncidentTypeCandidate(incident_type_id="other", confidence=0.70)
        ]
    )
    state, followup = process_turn_logic(s6, t6_a)
    print(f"1. Identify Ambiguous -> State: {state}")
    
    t6_b = TurnResponse(
        transcript=TranscriptInfo(text="It is Test Inc", is_final=True, lang_detected="en"),
        candidate_incident_types=[IncidentTypeCandidate(incident_type_id=inc_type_id, confidence=0.95)]
    )
    state, followup = process_turn_logic(s6, t6_b)
    print(f"2. Disambiguation Resolved -> State: {state}, Followup: {followup}")
    
    t6_c = TurnResponse(
        transcript=TranscriptInfo(text="I don't know", is_final=True, lang_detected="en"),
        dialogue_act="uncertainty"
    )
    state, followup = process_turn_logic(s6, t6_c) 
    print(f"3. I don't know (Location) -> State: {state}, Followup: {followup}")
    
    t6_d = TurnResponse(
        transcript=TranscriptInfo(text="I don't know", is_final=True, lang_detected="en"),
        dialogue_act="uncertainty"
    )
    state, followup = process_turn_logic(s6, t6_d)
    print(f"4. I don't know (Severity) -> State: {state}, Followup: {followup}")
    
    state, followup = process_turn_logic(s6, t5)
    print(f"5. Stop clarifying -> State: {state}")
    
    print("\n--- Test 7: FORM_FILLING forced safety bound ---")
    s7 = create_session(conn, org_id, user_id, 'FORM_FILLING', inc_type_id)
    t7_empty = TurnResponse(transcript=TranscriptInfo(text="nothing", is_final=True, lang_detected="en"))
    for i in range(5):
        process_turn_logic(s7, t7_empty) # Consumes 5 turns
    state, followup = process_turn_logic(s7, t7_empty) # 6th turn, forces bound
    print(f"After 6 form filling turns State: {state}") # REVIEWING
    
    # Check extracted fields for s7
    cursor = conn.cursor()
    cursor.execute("SELECT f.field_name, e.status FROM extracted_fields e JOIN form_fields f ON e.field_id = f.field_id WHERE e.session_id = ?", (s7,))
    results = cursor.fetchall()
    print("Forced fields status:")
    for r in results:
        print(f"  {r['field_name']}: {r['status']}")

    print("\n--- Test 8: Multi-incident End-to-End ---")
    s8 = create_session(conn, org_id, user_id, 'LISTENING')
    t8_split = TurnResponse(
        transcript=TranscriptInfo(text="Location big problem and Tool problem", is_final=True, lang_detected="en"),
        candidate_incident_types=[
            IncidentTypeCandidate(incident_type_id=inc_type_id, confidence=0.99),
            IncidentTypeCandidate(incident_type_id=inc_type_2, confidence=0.99)
        ],
        distinct_incidents_mentioned=True
    )
    state, followup = process_turn_logic(s8, t8_split)
    print(f"Split -> Primary State: {state}, Followup: {followup}") # FORM_FILLING, Where is it?
    
    cursor.execute("SELECT session_id, state FROM draft_sessions WHERE linked_group_id = (SELECT linked_group_id FROM draft_sessions WHERE session_id = ?)", (s8,))
    sessions = cursor.fetchall()
    
    # Answer required fields for Primary
    t8_prim = TurnResponse(transcript=TranscriptInfo(text="kitchen, big", is_final=True, lang_detected="en"),
        candidate_fields=[
            CandidateField(field_name="Location", value="kitchen", confidence=0.9, provenance="user"),
            CandidateField(field_name="Severity", value="big", confidence=0.9, provenance="user")
        ]
    )
    state_prim, _ = process_turn_logic(s8, t8_prim)
    print(f"Primary Answer Required -> State: {state_prim}") # CLARIFYING
    
    state_prim, _ = process_turn_logic(s8, t5) # Stop clarifying
    print(f"Primary Stop Clarifying -> State: {state_prim}") # REVIEWING
    
    # Process Secondary (linked) session
    s8_linked = [r['session_id'] for r in sessions if r['session_id'] != s8][0]
    cursor.execute("SELECT state FROM draft_sessions WHERE session_id = ?", (s8_linked,))
    linked_initial_state = cursor.fetchone()[0]
    print(f"Secondary Initial State: {linked_initial_state}") # FORM_FILLING
    
    # Secondary gets 'uncertainty' to 'Tool' requirement
    t8_sec = TurnResponse(transcript=TranscriptInfo(text="I don't know", is_final=True, lang_detected="en"), dialogue_act="uncertainty")
    state_sec, _ = process_turn_logic(s8_linked, t8_sec)
    print(f"Secondary I don't know (Tool) -> State: {state_sec}") # CLARIFYING
    
    # Stop Clarifying for Secondary
    state_sec, _ = process_turn_logic(s8_linked, t5)
    print(f"Secondary Stop Clarifying -> State: {state_sec}") # REVIEWING
    
run_tests()
