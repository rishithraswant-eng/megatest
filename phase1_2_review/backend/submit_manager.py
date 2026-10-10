import uuid
import json
from datetime import datetime
from typing import Optional
from .database import get_db_connection

def submit_incident(session_id: str) -> str:
    """
    Submits a confirmed draft session as an Incident.
    Returns the incident_id.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    
    # Preconditions check
    cursor.execute("""
        SELECT state, current_revision, confirmed_revision, proposed_incident_type_id, reporter_user_id, org_id, linked_group_id
        FROM draft_sessions 
        WHERE session_id = ?
    """, (session_id,))
    session = cursor.fetchone()
    if not session:
        conn.close()
        raise ValueError("Session not found")
        
    if session['state'] not in ['REVIEWING', 'SUBMITTED'] or session['confirmed_revision'] is None or session['confirmed_revision'] != session['current_revision']:
        conn.close()
        raise ValueError(f"Session is not confirmed or in invalid state: {session['state']}")
        
    org_id = session['org_id']
    reporter_user_id = session['reporter_user_id']
    confirmed_rev = session['confirmed_revision']
    inc_type_id = session['proposed_incident_type_id']
    linked_group_id = session['linked_group_id']
    
    # Duplicate-submission protection
    cursor.execute("""
        SELECT incident_id FROM incidents 
        WHERE session_id = ? AND confirmed_revision = ?
    """, (session_id, confirmed_rev))
    existing = cursor.fetchone()
    if existing:
        conn.close()
        return existing['incident_id']
        
    # Get Department from Incident Type
    cursor.execute("SELECT department_id FROM incident_types WHERE incident_type_id = ?", (inc_type_id,))
    dept_row = cursor.fetchone()
    if not dept_row:
        conn.close()
        raise ValueError("Invalid incident_type_id")
    dept_id = dept_row['department_id']
    
    # Resolve zone_id
    zone_id = None
    # 1. Check function_calls for get_zone_details
    cursor.execute("SELECT result_json FROM function_calls WHERE session_id = ? AND function_name = 'get_zone_details' ORDER BY called_at DESC LIMIT 1", (session_id,))
    func_call = cursor.fetchone()
    if func_call:
        res = json.loads(func_call['result_json'])
        zone_id = res.get('zone_id')
        
    # 2. Try direct match on Location field
    if not zone_id:
        cursor.execute("""
            SELECT e.value 
            FROM extracted_fields e 
            JOIN form_fields f ON e.field_id = f.field_id
            WHERE e.session_id = ? AND e.draft_revision <= ? AND e.superseded = 0 AND f.field_name = 'Location'
        """, (session_id, confirmed_rev))
        loc_row = cursor.fetchone()
        if loc_row and loc_row['value']:
            loc_val = loc_row['value']
            # direct name match against zones.name
            cursor.execute("SELECT zone_id FROM zones WHERE org_id = ? AND name = ? COLLATE NOCASE", (org_id, loc_val))
            zone_row = cursor.fetchone()
            if zone_row:
                zone_id = zone_row['zone_id']
                
    # Build incident details
    cursor.execute("""
        SELECT f.field_name, e.value 
        FROM extracted_fields e 
        JOIN form_fields f ON e.field_id = f.field_id
        WHERE e.session_id = ? AND e.draft_revision <= ? AND e.superseded = 0
    """, (session_id, confirmed_rev))
    extracted = {r['field_name']: r['value'] for r in cursor.fetchall()}
    
    description_en = extracted.get('Description', 'No description provided')
    priority = extracted.get('Priority', 'Medium')
    
    # Check classify_priority
    cursor.execute("SELECT result_json FROM function_calls WHERE session_id = ? AND function_name = 'classify_priority' ORDER BY called_at DESC LIMIT 1", (session_id,))
    prio_call = cursor.fetchone()
    if prio_call:
        prio_res = json.loads(prio_call['result_json'])
        priority = prio_res.get('priority', priority)
        
    incident_id = "inc_" + str(uuid.uuid4())
    
    try:
        cursor.execute("""
            INSERT INTO incidents (
                incident_id, org_id, session_id, reporter_user_id, zone_id, incident_type_id, department_id,
                linked_group_id, description_en, original_lang, reported_at, incident_time, priority, status,
                confirmed_revision, local_submission_only
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'en', ?, NULL, ?, 'Open', ?, 1)
        """, (
            incident_id, org_id, session_id, reporter_user_id, zone_id, inc_type_id, dept_id,
            linked_group_id, description_en, datetime.utcnow().isoformat(), priority, confirmed_rev
        ))
        
        cursor.execute("UPDATE draft_sessions SET state = 'SUBMITTED' WHERE session_id = ?", (session_id,))
        conn.commit()
    except Exception as e:
        conn.rollback()
        raise e
    finally:
        conn.close()
        
    try:
        from .analytics_manager import compute_incident_relations
        compute_incident_relations(incident_id)
    except Exception as e:
        print(f"Warning: Incident relations heuristic failed: {e}")
        
    return incident_id
