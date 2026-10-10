import uuid
from .database import get_db_connection
from .stub_engine import TurnResponse
from typing import Tuple, Optional, List, Any

MAX_FORM_FILLING_TURNS = 6
MAX_CLARIFYING_QUESTIONS = 3

def process_identify_stage(session_id: str, turn: TurnResponse) -> Tuple[str, Optional[str]]:
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM draft_sessions WHERE session_id = ?", (session_id,))
    session = dict(cursor.fetchone())
    
    candidates = sorted(turn.candidate_incident_types, key=lambda c: c.confidence, reverse=True)
    
    def evaluate_candidates(cand_list: List[Any]) -> Tuple[Optional[str], str, Optional[str]]:
        if len(cand_list) == 0:
            return None, 'CLASSIFYING', "What kind of issue is this?"
        top_c = cand_list[0]
        if top_c.confidence >= 0.8 and (len(cand_list) == 1 or (top_c.confidence - cand_list[1].confidence >= 0.15)):
            return top_c.incident_type_id, 'FORM_FILLING', None
        else:
            return None, 'DISAMBIGUATING', None
            
    if turn.distinct_incidents_mentioned:
        primary_cands = [candidates[0]] if len(candidates) > 0 else []
        secondary_cands = [candidates[1]] if len(candidates) > 1 else []
        
        prim_proposed, prim_state, prim_followup = evaluate_candidates(primary_cands)
        sec_proposed, sec_state, sec_followup = evaluate_candidates(secondary_cands)
        
        group_id = session.get('linked_group_id') or f"group_{session_id}"
            
        cursor.execute("UPDATE draft_sessions SET proposed_incident_type_id = ?, state = ?, linked_group_id = ? WHERE session_id = ?", 
                       (prim_proposed, prim_state, group_id, session_id))
                       
        new_session_id = f"{session_id}_linked"
        cursor.execute("SELECT session_id FROM draft_sessions WHERE session_id = ?", (new_session_id,))
        if not cursor.fetchone():
            cursor.execute("""
                INSERT INTO draft_sessions (session_id, org_id, reporter_user_id, started_at, last_active_at, state, proposed_incident_type_id, linked_group_id)
                VALUES (?, ?, ?, datetime('now'), datetime('now'), ?, ?, ?)
            """, (new_session_id, session['org_id'], session['reporter_user_id'], sec_state, sec_proposed, group_id))
        else:
            cursor.execute("UPDATE draft_sessions SET proposed_incident_type_id = ?, state = ? WHERE session_id = ?", 
                           (sec_proposed, sec_state, new_session_id))
                           
        conn.commit()
        conn.close()
        return (prim_state, prim_followup)
    else:
        proposed_id, new_state, followup_text = evaluate_candidates(candidates)
        cursor.execute("UPDATE draft_sessions SET proposed_incident_type_id = ?, state = ? WHERE session_id = ?", 
                       (proposed_id, new_state, session_id))
        conn.commit()
        conn.close()
        return (new_state, followup_text)

def process_form_filling_stage(session_id: str, turn: TurnResponse) -> Tuple[str, Optional[str]]:
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM draft_sessions WHERE session_id = ?", (session_id,))
    session = dict(cursor.fetchone())
    incident_type_id = session['proposed_incident_type_id']
    rev = session['current_revision']
    
    cursor.execute("SELECT COUNT(*) FROM dialogue_turns WHERE session_id = ? AND stage = 'FORM_FILLING'", (session_id,))
    turn_count = cursor.fetchone()[0]

    def get_field_status(f_id):
        cursor.execute("""
            SELECT status FROM extracted_fields 
            WHERE session_id = ? AND field_id = ? AND superseded = 0
            ORDER BY draft_revision DESC LIMIT 1
        """, (session_id, f_id))
        row = cursor.fetchone()
        return row[0] if row else 'missing'
        
    def set_field_status(f_id, status, value=None, conf=None, prov=None):
        fid_row = str(uuid.uuid4())
        cursor.execute("UPDATE extracted_fields SET superseded = 1 WHERE session_id = ? AND field_id = ?", (session_id, f_id))
        cursor.execute("""
            INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (fid_row, session_id, f_id, rev, value, status, prov or 'system', conf))

    cursor.execute("SELECT field_id, field_name, prompt_template FROM form_fields WHERE incident_type_id = ? AND field_kind = 'required' ORDER BY display_order ASC", (incident_type_id,))
    req_fields = [dict(r) for r in cursor.fetchall()]
    
    currently_asked_field = None
    for f in req_fields:
        if get_field_status(f['field_id']) in ['missing', 'ambiguous']:
            currently_asked_field = f
            break
            
    field_map = {}
    cursor.execute("SELECT field_name, field_id FROM form_fields WHERE incident_type_id = ?", (incident_type_id,))
    for row in cursor.fetchall():
        field_map[row['field_name']] = row['field_id']
        
    for cf in turn.candidate_fields:
        f_id = field_map.get(cf.field_name)
        if f_id:
            set_field_status(f_id, 'known', cf.value, cf.confidence, cf.provenance)
            
    if turn.dialogue_act == 'uncertainty' and currently_asked_field:
        set_field_status(currently_asked_field['field_id'], 'unknown', None, 1.0, 'user')
        
    askable = []
    for f in req_fields:
        if get_field_status(f['field_id']) in ['missing', 'ambiguous']:
            askable.append(f)
            
    if turn_count >= MAX_FORM_FILLING_TURNS:
        for f in askable:
            set_field_status(f['field_id'], 'unknown', None, 1.0, 'system_forced')
        new_state = 'REVIEWING'
        followup = None
    elif len(askable) == 0:
        new_state = 'CLARIFYING'
        followup = None
    else:
        new_state = 'FORM_FILLING'
        followup = askable[0]['prompt_template']
        
    cursor.execute("UPDATE draft_sessions SET state = ? WHERE session_id = ?", (new_state, session_id))
    conn.commit()
    conn.close()
    return new_state, followup

def process_clarifying_stage(session_id: str, turn: TurnResponse) -> Tuple[str, Optional[str]]:
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM draft_sessions WHERE session_id = ?", (session_id,))
    session = dict(cursor.fetchone())
    incident_type_id = session['proposed_incident_type_id']
    rev = session['current_revision']
    
    cursor.execute("SELECT COUNT(*) FROM dialogue_turns WHERE session_id = ? AND stage = 'CLARIFYING'", (session_id,))
    turn_count = cursor.fetchone()[0]

    def get_field_status(f_id):
        cursor.execute("""
            SELECT status FROM extracted_fields 
            WHERE session_id = ? AND field_id = ? AND superseded = 0
            ORDER BY draft_revision DESC LIMIT 1
        """, (session_id, f_id))
        row = cursor.fetchone()
        return row[0] if row else 'missing'
        
    def set_field_status(f_id, status, value=None, conf=None, prov=None):
        fid_row = str(uuid.uuid4())
        cursor.execute("UPDATE extracted_fields SET superseded = 1 WHERE session_id = ? AND field_id = ?", (session_id, f_id))
        cursor.execute("""
            INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """, (fid_row, session_id, f_id, rev, value, status, prov or 'system', conf))

    cursor.execute("SELECT field_id, field_name, prompt_template FROM form_fields WHERE incident_type_id = ? AND field_kind = 'clarifying' ORDER BY display_order ASC", (incident_type_id,))
    clarifying_fields = [dict(r) for r in cursor.fetchall()]
    
    currently_asked_field = None
    for f in clarifying_fields:
        if get_field_status(f['field_id']) in ['missing', 'ambiguous']:
            currently_asked_field = f
            break
            
    field_map = {}
    cursor.execute("SELECT field_name, field_id FROM form_fields WHERE incident_type_id = ?", (incident_type_id,))
    for row in cursor.fetchall():
        field_map[row['field_name']] = row['field_id']
        
    for cf in turn.candidate_fields:
        f_id = field_map.get(cf.field_name)
        if f_id:
            set_field_status(f_id, 'known', cf.value, cf.confidence, cf.provenance)
            
    if turn.dialogue_act == 'uncertainty' and currently_asked_field:
        set_field_status(currently_asked_field['field_id'], 'unknown', None, 1.0, 'user')
        
    askable = []
    for f in clarifying_fields:
        if get_field_status(f['field_id']) in ['missing', 'ambiguous']:
            askable.append(f)
            
    if turn.user_wants_to_stop_clarifying:
        new_state = 'REVIEWING'
        followup = None
    elif turn_count >= MAX_CLARIFYING_QUESTIONS:
        new_state = 'REVIEWING'
        followup = None
    elif len(askable) == 0:
        new_state = 'REVIEWING'
        followup = None
    else:
        new_state = 'CLARIFYING'
        followup = askable[0]['prompt_template']
        
    cursor.execute("UPDATE draft_sessions SET state = ? WHERE session_id = ?", (new_state, session_id))
    conn.commit()
    conn.close()
    return new_state, followup

def process_turn_logic(session_id: str, turn: TurnResponse) -> Tuple[str, Optional[str]]:
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT state, current_revision FROM draft_sessions WHERE session_id = ?", (session_id,))
    row = cursor.fetchone()
    state = row['state']
    rev = row['current_revision']
    
    turn_id = str(uuid.uuid4())
    cursor.execute("""
        INSERT INTO dialogue_turns (turn_id, session_id, turn_index, draft_revision, speaker, stage, raw_text, timestamp, dialogue_act)
        VALUES (?, ?, 0, ?, 'user', ?, ?, datetime('now'), ?)
    """, (turn_id, session_id, rev, state, turn.transcript.text, turn.dialogue_act))
    conn.commit()
    conn.close()
    
    followup = None
    
    def get_clean_cascade_turn(t):
        return TurnResponse(
            transcript=t.transcript,
            dialogue_act=None,
            candidate_incident_types=[],
            candidate_fields=[],
            function_calls_made=[],
            distinct_incidents_mentioned=False,
            user_wants_to_stop_clarifying=False
        )

    if state in ['LISTENING', 'CLASSIFYING', 'DISAMBIGUATING']:
        new_state, followup = process_identify_stage(session_id, turn)
        if new_state == 'FORM_FILLING':
            new_state, followup = process_form_filling_stage(session_id, turn)
            if new_state == 'CLARIFYING':
                new_state, followup = process_clarifying_stage(session_id, get_clean_cascade_turn(turn))
    elif state == 'FORM_FILLING':
        new_state, followup = process_form_filling_stage(session_id, turn)
        if new_state == 'CLARIFYING':
            new_state, followup = process_clarifying_stage(session_id, get_clean_cascade_turn(turn))
    elif state == 'CLARIFYING':
        new_state, followup = process_clarifying_stage(session_id, turn)
    else:
        new_state = state
        
    return new_state, followup
