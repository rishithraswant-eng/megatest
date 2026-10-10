import json
import uuid
from typing import Dict, Any
from .database import get_db_connection

# Simple static dependency map for invalidation
# "If X is corrected, then [Y, Z] are invalidated"
DEPENDENCY_MAP = {
    'Description': ['Priority'],
    'Severity': ['Priority']
}

def generate_review_summary(session_id: str) -> Dict[str, Any]:
    """
    Returns a dictionary of fields at the session's current_revision.
    Includes all required fields, and any answered clarifying fields.
    Each entry contains the value and status explicitly shown.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT proposed_incident_type_id, current_revision FROM draft_sessions WHERE session_id = ?", (session_id,))
    row = cursor.fetchone()
    inc_type_id = row['proposed_incident_type_id']
    
    # Get all form fields for this incident type
    cursor.execute("SELECT field_id, field_name, field_kind FROM form_fields WHERE incident_type_id = ?", (inc_type_id,))
    all_fields = {r['field_id']: dict(r) for r in cursor.fetchall()}
    
    # Get extracted_fields at the current revision (the ones with superseded = 0)
    cursor.execute("""
        SELECT field_id, value, status 
        FROM extracted_fields 
        WHERE session_id = ? AND superseded = 0
    """, (session_id,))
    extracted = {r['field_id']: dict(r) for r in cursor.fetchall()}
    
    summary = {}
    for f_id, f_data in all_fields.items():
        ext = extracted.get(f_id)
        if ext:
            if f_data['field_kind'] == 'required' or ext['status'] in ['known', 'unknown', 'ambiguous']:
                summary[f_data['field_name']] = {
                    'value': ext['value'],
                    'status': ext['status'],
                    'kind': f_data['field_kind']
                }
        else:
            # If a required field has no extracted row, it is implicitly missing.
            if f_data['field_kind'] == 'required':
                summary[f_data['field_name']] = {
                    'value': None,
                    'status': 'missing',
                    'kind': 'required'
                }
                
    conn.close()
    return summary

def action_confirm(session_id: str):
    """
    CONFIRM: sets incidents-eligible flag (confirmed_revision = current_revision)
    Does not write incidents row.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT current_revision FROM draft_sessions WHERE session_id = ?", (session_id,))
    rev = cursor.fetchone()['current_revision']
    
    # Mark session as confirmed-at-this-revision
    cursor.execute("UPDATE draft_sessions SET confirmed_revision = ? WHERE session_id = ?", (rev, session_id))
    conn.commit()
    conn.close()

def action_correct(session_id: str, field_name: str, new_value: str, triggering_turn_id: str) -> Dict[str, Any]:
    """
    CORRECT: re-opens a field, writes a new extracted_fields row at an incremented draft_revision,
    writes a correction_history row. Invalidates dependent fields based on DEPENDENCY_MAP.
    Regenerates and returns the review summary.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    
    # 1. Get session info
    cursor.execute("SELECT proposed_incident_type_id, current_revision FROM draft_sessions WHERE session_id = ?", (session_id,))
    session = cursor.fetchone()
    inc_type_id = session['proposed_incident_type_id']
    old_rev = session['current_revision']
    new_rev = old_rev + 1
    
    # Increment revision
    cursor.execute("UPDATE draft_sessions SET current_revision = ? WHERE session_id = ?", (new_rev, session_id))
    
    # (Removed dummy turn creation - we now require the real turn_id from the live loop)
    
    # 2. Get the field being corrected
    cursor.execute("SELECT field_id FROM form_fields WHERE incident_type_id = ? AND field_name = ?", (inc_type_id, field_name))
    target_field = cursor.fetchone()
    if not target_field:
        conn.close()
        raise ValueError(f"Field {field_name} not found")
    target_f_id = target_field['field_id']
    
    # Find the 'before' extracted field row
    cursor.execute("SELECT field_row_id FROM extracted_fields WHERE session_id = ? AND field_id = ? AND superseded = 0", (session_id, target_f_id))
    before_row = cursor.fetchone()
    before_row_id = before_row['field_row_id'] if before_row else None
    
    # Supersede the old row
    if before_row_id:
        cursor.execute("UPDATE extracted_fields SET superseded = 1 WHERE field_row_id = ?", (before_row_id,))
        
    # Write the new row
    after_row_id = str(uuid.uuid4())
    cursor.execute("""
        INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence)
        VALUES (?, ?, ?, ?, ?, 'known', 'correction', 1.0)
    """, (after_row_id, session_id, target_f_id, new_rev, new_value))
    
    # 3. Handle Invalidations based on DEPENDENCY_MAP
    invalidated_ids = []
    deps = DEPENDENCY_MAP.get(field_name, [])
    for dep_name in deps:
        cursor.execute("SELECT field_id FROM form_fields WHERE incident_type_id = ? AND field_name = ?", (inc_type_id, dep_name))
        dep_field = cursor.fetchone()
        if dep_field:
            dep_f_id = dep_field['field_id']
            cursor.execute("SELECT field_row_id FROM extracted_fields WHERE session_id = ? AND field_id = ? AND superseded = 0", (session_id, dep_f_id))
            dep_row = cursor.fetchone()
            if dep_row:
                dep_row_id = dep_row['field_row_id']
                invalidated_ids.append(dep_row_id)
                cursor.execute("UPDATE extracted_fields SET superseded = 1 WHERE field_row_id = ?", (dep_row_id,))
                
                # Insert a new missing/re-evaluated row for the invalidated field
                new_dep_row_id = str(uuid.uuid4())
                cursor.execute("""
                    INSERT INTO extracted_fields (field_row_id, session_id, field_id, draft_revision, value, status, provenance, confidence)
                    VALUES (?, ?, ?, ?, NULL, 'missing', 'system_invalidated', 1.0)
                """, (new_dep_row_id, session_id, dep_f_id, new_rev))
                
    # 4. Write correction_history
    if before_row_id:
        corr_id = str(uuid.uuid4())
        cursor.execute("""
            INSERT INTO correction_history (correction_id, session_id, field_row_id_before, field_row_id_after, invalidated_json, corrected_at, triggering_turn_id)
            VALUES (?, ?, ?, ?, ?, datetime('now'), ?)
        """, (corr_id, session_id, before_row_id, after_row_id, json.dumps(invalidated_ids), triggering_turn_id))
        
    # Un-confirm if it was confirmed (void prior confirmation)
    cursor.execute("UPDATE draft_sessions SET confirmed_revision = NULL WHERE session_id = ?", (session_id,))
    
    conn.commit()
    conn.close()
    
    return generate_review_summary(session_id)

def action_skip_clarification(session_id: str) -> Dict[str, Any]:
    """
    SKIP remaining clarification: transitions state to REVIEWING. 
    Clarifying fields that were not answered will just not appear in the summary, 
    so we don't have to explicitly mark them missing in the db unless required.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("UPDATE draft_sessions SET state = 'REVIEWING' WHERE session_id = ?", (session_id,))
    conn.commit()
    conn.close()
    return generate_review_summary(session_id)

def action_cancel(session_id: str):
    """
    CANCEL: sets draft_sessions.state = "CANCELLED". No incidents row is ever created.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("UPDATE draft_sessions SET state = 'CANCELLED' WHERE session_id = ?", (session_id,))
    conn.commit()
    conn.close()
