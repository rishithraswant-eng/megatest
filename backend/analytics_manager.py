import uuid
import datetime
from .database import get_db_connection

def jaccard_similarity(s1: str, s2: str) -> float:
    set1 = set(s1.lower().split())
    set2 = set(s2.lower().split())
    if not set1 and not set2:
        return 0.0
    return len(set1.intersection(set2)) / len(set1.union(set2))

def compute_incident_relations(new_incident_id: str, throw_for_test: bool = False):
    """
    Heuristic scorer to link related incidents (NOT a trained GNN).
    Designed to be called as a best-effort step after an incident is saved.
    """
    if throw_for_test:
        raise RuntimeError("Forced exception for testing")
        
    conn = get_db_connection()
    cursor = conn.cursor()
    
    cursor.execute("""
        SELECT org_id, zone_id, incident_type_id, department_id, description_en, reported_at
        FROM incidents WHERE incident_id = ?
    """, (new_incident_id,))
    new_inc = cursor.fetchone()
    if not new_inc:
        conn.close()
        return
        
    org_id = new_inc['org_id']
    target_dt = datetime.datetime.fromisoformat(new_inc['reported_at'])
    cutoff_dt = target_dt - datetime.timedelta(days=7)
    
    # Bounded comparison: last 7 days, max 50
    cursor.execute("""
        SELECT incident_id, zone_id, incident_type_id, department_id, description_en, reported_at
        FROM incidents 
        WHERE org_id = ? AND incident_id != ? AND reported_at >= ?
        ORDER BY reported_at DESC LIMIT 50
    """, (org_id, new_incident_id, cutoff_dt.isoformat()))
    candidates = cursor.fetchall()
    
    THRESHOLD = 0.6
    
    for cand in candidates:
        cand_dt = datetime.datetime.fromisoformat(cand['reported_at'])
        score = 0.0
        reasons = []
        
        # Zone match (0.3)
        if new_inc['zone_id'] and new_inc['zone_id'] == cand['zone_id']:
            score += 0.3
            reasons.append("same_zone")
            
        # Type/Dept match (0.2 or 0.1)
        if new_inc['incident_type_id'] == cand['incident_type_id']:
            score += 0.2
            reasons.append("same_type")
        elif new_inc['department_id'] == cand['department_id']:
            score += 0.1
            reasons.append("same_dept")
            
        # Temporal proximity (0.2 max, decays over 7 days)
        hours_diff = abs((target_dt - cand_dt).total_seconds()) / 3600.0
        time_score = max(0.0, 0.2 * (1.0 - (hours_diff / (7.0 * 24.0))))
        score += time_score
        if time_score > 0.1:
            reasons.append("temporal_proximity")
            
        # Jaccard overlap (0.3 max)
        jaccard = jaccard_similarity(new_inc['description_en'], cand['description_en'])
        if jaccard > 0.2:
            score += (jaccard * 0.3)
            reasons.append("description_overlap")
            
        if score >= THRESHOLD:
            rel_id = "rel_" + str(uuid.uuid4())
            reason_str = "+".join(reasons)
            cursor.execute("""
                INSERT INTO incident_relations (relation_id, incident_id_a, incident_id_b, relation_score, relation_reason, computed_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """, (rel_id, new_incident_id, cand['incident_id'], round(score, 3), reason_str, datetime.datetime.utcnow().isoformat()))
            
    conn.commit()
    conn.close()

def compute_causal_edges(org_id: str):
    """
    A standalone batch job to compute a simplified statistical association heuristic 
    inspired by DynoTears, not a validated causal model.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    
    cursor.execute("""
        SELECT incident_id, zone_id, department_id, incident_type_id, reported_at
        FROM incidents
        WHERE org_id = ?
        ORDER BY reported_at ASC
    """, (org_id,))
    incidents = cursor.fetchall()
    
    MIN_SAMPLE_SIZE = 5
    LAG_WINDOW_HOURS_MIN = 24
    LAG_WINDOW_HOURS_MAX = 72
    
    factor_counts = {}
    co_occurrences = {} # (factor, effect_type) -> count
    
    parsed_incidents = []
    for inc in incidents:
        dt = datetime.datetime.fromisoformat(inc['reported_at'])
        hour_bucket = f"hour_{dt.hour // 4 * 4}-{dt.hour // 4 * 4 + 4}"
        factors = []
        if inc['zone_id']: factors.append(f"zone:{inc['zone_id']}")
        if inc['department_id']: factors.append(f"dept:{inc['department_id']}")
        factors.append(f"type:{inc['incident_type_id']}")
        factors.append(hour_bucket)
        
        parsed_incidents.append((inc, dt, factors))
        
        for f in factors:
            factor_counts[f] = factor_counts.get(f, 0) + 1
            
    # Compute lagged co-occurrences
    for i, (inc_a, dt_a, factors_a) in enumerate(parsed_incidents):
        for j in range(i + 1, len(parsed_incidents)):
            inc_b, dt_b, factors_b = parsed_incidents[j]
            hours_diff = (dt_b - dt_a).total_seconds() / 3600.0
            
            if hours_diff > LAG_WINDOW_HOURS_MAX:
                break
                
            if hours_diff >= LAG_WINDOW_HOURS_MIN:
                effect_type = inc_b['incident_type_id']
                for f in factors_a:
                    pair = (f, effect_type)
                    co_occurrences[pair] = co_occurrences.get(pair, 0) + 1
                    
    cursor.execute("DELETE FROM causal_edges WHERE org_id = ?", (org_id,))
    
    for (factor, effect_type), co_count in co_occurrences.items():
        if co_count >= MIN_SAMPLE_SIZE:
            p_effect_given_factor = co_count / factor_counts[factor]
            
            effect_total_count = sum(1 for inc, _, _ in parsed_incidents if inc['incident_type_id'] == effect_type)
            p_effect = effect_total_count / len(parsed_incidents) if parsed_incidents else 0
            
            lift = p_effect_given_factor / p_effect if p_effect > 0 else 0
            
            if lift > 1.2:
                edge_id = "edge_" + str(uuid.uuid4())
                cursor.execute("""
                    INSERT INTO causal_edges (edge_id, org_id, cause_factor, effect_incident_type_id, weight, lag_hint, computed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                """, (edge_id, org_id, factor, effect_type, round(lift, 3), f"{LAG_WINDOW_HOURS_MIN}-{LAG_WINDOW_HOURS_MAX}h", datetime.datetime.utcnow().isoformat()))
                
    conn.commit()
    conn.close()
