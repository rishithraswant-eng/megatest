import uuid
import datetime
from .database import get_db_connection

def publish_version(artifact_type: str, artifact_name: str, version_label: str) -> str:
    """
    Publish a new version of an artifact (schema or model).
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    version_id = "ver_" + str(uuid.uuid4())
    cursor.execute("""
        INSERT INTO schema_versions (version_id, artifact_type, artifact_name, version_label, is_active, released_at)
        VALUES (?, ?, ?, ?, 0, ?)
    """, (version_id, artifact_type, artifact_name, version_label, datetime.datetime.utcnow().isoformat()))
    conn.commit()
    conn.close()
    return version_id

def activate_version(version_id: str):
    """
    Activates a specific version. Deactivates all others for that artifact_type/artifact_name.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("SELECT artifact_type, artifact_name FROM schema_versions WHERE version_id = ?", (version_id,))
    row = cursor.fetchone()
    if not row:
        conn.close()
        raise ValueError("Version not found")
        
    cursor.execute("""
        UPDATE schema_versions SET is_active = 0 
        WHERE artifact_type = ? AND artifact_name = ?
    """, (row['artifact_type'], row['artifact_name']))
    
    cursor.execute("UPDATE schema_versions SET is_active = 1 WHERE version_id = ?", (version_id,))
    conn.commit()
    conn.close()

def rollback_version(version_id_to_rollback: str, target_version_id: str):
    """
    Rolls back an active version by activating the target version and logging the rollback constraint.
    """
    conn = get_db_connection()
    cursor = conn.cursor()
    
    cursor.execute("SELECT artifact_type, artifact_name FROM schema_versions WHERE version_id = ?", (version_id_to_rollback,))
    rollback_row = cursor.fetchone()
    
    cursor.execute("SELECT artifact_type, artifact_name FROM schema_versions WHERE version_id = ?", (target_version_id,))
    target_row = cursor.fetchone()
    
    if not rollback_row or not target_row:
        conn.close()
        raise ValueError("Version not found")
        
    if rollback_row['artifact_type'] != target_row['artifact_type'] or rollback_row['artifact_name'] != target_row['artifact_name']:
        conn.close()
        raise ValueError("Artifact mismatch")
        
    # Deactivate current
    cursor.execute("""
        UPDATE schema_versions SET is_active = 0 
        WHERE artifact_type = ? AND artifact_name = ?
    """, (rollback_row['artifact_type'], rollback_row['artifact_name']))
    
    # Activate target and mark as a rollback
    cursor.execute("UPDATE schema_versions SET is_active = 1, rollback_of = ? WHERE version_id = ?", (version_id_to_rollback, target_version_id))
    
    conn.commit()
    conn.close()
