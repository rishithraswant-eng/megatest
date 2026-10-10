from backend.stub_engine import StubConversationEngine, TurnResponse, TranscriptInfo, IncidentTypeCandidate
from backend.conversation_manager import process_identify_stage
from backend.database import get_db_connection
import uuid
import os

def setup_test_data():
    conn = get_db_connection()
    org_id = str(uuid.uuid4())
    user_id = str(uuid.uuid4())
    
    conn.execute("INSERT OR IGNORE INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) VALUES (?, 'Test Org', '2026', 'x', 1, 1)", (org_id,))
    conn.execute("INSERT OR IGNORE INTO users (user_id, org_id, display_name, role) VALUES (?, ?, 'Tester', 'employee')", (user_id, org_id))
    
    conn.commit()
    return org_id, user_id, conn

def create_session(conn, org_id, user_id, state='CLASSIFYING'):
    session_id = "sess_" + str(uuid.uuid4())
    conn.execute("""
        INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, state)
        VALUES (?, ?, ?, '2026', '2026', ?)
    """, (session_id, org_id, user_id, state))
    conn.commit()
    return session_id

def test_five_cases():
    org_id, user_id, conn = setup_test_data()

    # 1. Confident Match (Top >= 0.8, Gap >= 0.15)
    s1 = create_session(conn, org_id, user_id)
    t1 = TurnResponse(
        transcript=TranscriptInfo(text="Water leak here", is_final=True, lang_detected="en"),
        candidate_incident_types=[
            IncidentTypeCandidate(incident_type_id="leak", confidence=0.90),
            IncidentTypeCandidate(incident_type_id="other", confidence=0.70)
        ],
        candidate_fields=[], function_calls_made=[]
    )
    res1 = process_identify_stage(s1, t1)
    print(f"Case 1 (Confident Match) State: {res1[0]}") # Expected: FORM_FILLING

    # 2. Close Disambiguation (Top >= 0.8 but Gap < 0.15)
    s2 = create_session(conn, org_id, user_id)
    t2 = TurnResponse(
        transcript=TranscriptInfo(text="Wet electrical panel", is_final=True, lang_detected="en"),
        candidate_incident_types=[
            IncidentTypeCandidate(incident_type_id="leak", confidence=0.85),
            IncidentTypeCandidate(incident_type_id="electrical", confidence=0.80)
        ],
        candidate_fields=[], function_calls_made=[]
    )
    res2 = process_identify_stage(s2, t2)
    print(f"Case 2 (Close Disambiguation) State: {res2[0]}") # Expected: DISAMBIGUATING

    # 3. Zero Candidates
    s3 = create_session(conn, org_id, user_id)
    t3 = TurnResponse(
        transcript=TranscriptInfo(text="I have a problem", is_final=True, lang_detected="en"),
        candidate_incident_types=[],
        candidate_fields=[], function_calls_made=[]
    )
    res3 = process_identify_stage(s3, t3)
    print(f"Case 3 (Zero Candidates) State: {res3[0]} | Question: {res3[1]}") # Expected: CLASSIFYING, "What kind of issue is this?"

    # 4. Distinct Incidents Mentioned = True (HIGH Confidence)
    s4 = create_session(conn, org_id, user_id)
    t4 = TurnResponse(
        transcript=TranscriptInfo(text="The window is broken and the AC is leaking.", is_final=True, lang_detected="en"),
        candidate_incident_types=[
            IncidentTypeCandidate(incident_type_id="glass_broken", confidence=0.99),
            IncidentTypeCandidate(incident_type_id="hvac_leak", confidence=0.99)
        ],
        candidate_fields=[], function_calls_made=[],
        distinct_incidents_mentioned=True
    )
    res4 = process_identify_stage(s4, t4)
    print(f"Case 4 (Distinct Incidents - High) Primary State: {res4[0]}") # Expected: FORM_FILLING
    
    cur = conn.cursor()
    cur.execute("SELECT state, proposed_incident_type_id FROM draft_sessions WHERE session_id = ?", (f"{s4}_linked",))
    linked4 = cur.fetchone()
    print(f"Case 4 Linked Session State: {linked4[0]} | Proposed Type: {linked4[1]}") # Expected: FORM_FILLING | hvac_leak

    # 5. Distinct Incidents Mentioned = True (LOW Confidence)
    s5 = create_session(conn, org_id, user_id)
    t5 = TurnResponse(
        transcript=TranscriptInfo(text="I see a puddle and the door won't lock.", is_final=True, lang_detected="en"),
        candidate_incident_types=[
            IncidentTypeCandidate(incident_type_id="leak", confidence=0.55),
            IncidentTypeCandidate(incident_type_id="access_control", confidence=0.50)
        ],
        candidate_fields=[], function_calls_made=[],
        distinct_incidents_mentioned=True
    )
    res5 = process_identify_stage(s5, t5)
    print(f"Case 5 (Distinct Incidents - Low) Primary State: {res5[0]}") # Expected: DISAMBIGUATING
    
    cur.execute("SELECT state, proposed_incident_type_id FROM draft_sessions WHERE session_id = ?", (f"{s5}_linked",))
    linked5 = cur.fetchone()
    print(f"Case 5 Linked Session State: {linked5[0]} | Proposed Type: {linked5[1]}") # Expected: DISAMBIGUATING | None

    conn.close()

if __name__ == "__main__":
    test_five_cases()
