import json
from typing import List, Dict, Any
from backend.database import get_db_connection

def evaluate_harness(session_id: str, expected_outcomes: Dict[str, Any]) -> Dict[str, Any]:
    """
    Computes TRD Section 8 validation metrics for a completed scripted session.
    
    expected_outcomes = {
        "incident_type_id": "...",
        "department_id": "...",
        "fields": { "Severity": "High", "Color": "Red" },
        "total_turns": 4,
        "max_repeated_questions": 0
    }
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    
    metrics = {
        "routing_accuracy": 0.0,
        "field_accuracy": 0.0,
        "false_fill_count": 0,
        "repeated_question_count": 0,
        "completion_rate": 0.0
    }
    
    # Check Session State
    cursor.execute("SELECT state, proposed_incident_type_id FROM draft_sessions WHERE session_id = ?", (session_id,))
    session = cursor.fetchone()
    if not session:
        return metrics
        
    if session['state'] in ['REVIEWING', 'SUBMITTED']:
        metrics['completion_rate'] = 1.0
        
    # Routing Accuracy
    inc_type = session['proposed_incident_type_id']
    expected_inc_type = expected_outcomes.get("incident_type_id")
    if expected_inc_type and inc_type == expected_inc_type:
        metrics['routing_accuracy'] = 1.0
        
    # Field Accuracy & False Fills
    cursor.execute("""
        SELECT f.field_name, e.value 
        FROM extracted_fields e
        JOIN form_fields f ON e.field_id = f.field_id
        WHERE e.session_id = ? AND e.superseded = 0 AND e.status = 'known'
    """, (session_id,))
    extracted = {r['field_name']: r['value'] for r in cursor.fetchall()}
    
    expected_fields = expected_outcomes.get("fields", {})
    correct_fields = 0
    false_fills = 0
    
    for k, v in extracted.items():
        if k in expected_fields:
            if str(v).lower() == str(expected_fields[k]).lower():
                correct_fields += 1
            else:
                false_fills += 1
        else:
            false_fills += 1 # Filled a field that wasn't expected
            
    if expected_fields:
        metrics['field_accuracy'] = correct_fields / len(expected_fields)
    metrics['false_fill_count'] = false_fills
    
    # Check for repeated questions (basic heuristic: looking for consecutive system questions about same field)
    # This requires analyzing dialogue_turns. In a real test, this would parse the 'raw_text' or 'dialogue_act'
    # We leave a placeholder metric for the harness structure.
    metrics['repeated_question_count'] = 0 
    
    conn.close()
    return metrics
