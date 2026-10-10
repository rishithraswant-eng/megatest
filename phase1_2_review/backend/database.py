import sqlite3
import os

DB_PATH = os.path.join(os.path.dirname(__file__), "incident_assistant.db")

def get_db_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    # Enable foreign keys
    conn.execute("PRAGMA foreign_keys = ON")
    return conn

def init_db():
    conn = get_db_connection()
    cursor = conn.cursor()
    
    # --------------------------------------------------------------------------------
    # TABLE: organizations
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS organizations (
        org_id          TEXT PRIMARY KEY,
        name            TEXT NOT NULL,
        created_at      TEXT NOT NULL,
        floorplan_path  TEXT NOT NULL,
        floorplan_w_px  INTEGER NOT NULL,
        floorplan_h_px  INTEGER NOT NULL
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: zones
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS zones (
        zone_id         TEXT PRIMARY KEY,
        org_id          TEXT NOT NULL REFERENCES organizations(org_id),
        name            TEXT NOT NULL,
        floor           TEXT,
        polygon_json    TEXT NOT NULL,
        category_hints  TEXT,
        created_at      TEXT NOT NULL
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: departments
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS departments (
        department_id   TEXT PRIMARY KEY,
        org_id          TEXT NOT NULL REFERENCES organizations(org_id),
        name            TEXT NOT NULL,
        description     TEXT
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: incident_types
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS incident_types (
        incident_type_id    TEXT PRIMARY KEY,
        org_id              TEXT NOT NULL REFERENCES organizations(org_id),
        label               TEXT NOT NULL,
        department_id       TEXT NOT NULL REFERENCES departments(department_id),
        default_priority    TEXT NOT NULL,
        responsible_roles   TEXT,
        disambiguation_hint TEXT
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: form_fields
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS form_fields (
        field_id          TEXT PRIMARY KEY,
        incident_type_id  TEXT NOT NULL REFERENCES incident_types(incident_type_id),
        field_name        TEXT NOT NULL,
        field_kind        TEXT NOT NULL,
        data_type         TEXT NOT NULL,
        enum_values_json  TEXT,
        prompt_template   TEXT NOT NULL,
        display_order     INTEGER NOT NULL
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: users
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS users (
        user_id         TEXT PRIMARY KEY,
        org_id          TEXT NOT NULL REFERENCES organizations(org_id),
        display_name    TEXT NOT NULL,
        role            TEXT NOT NULL,
        home_zone_id    TEXT REFERENCES zones(zone_id),
        preferred_lang  TEXT DEFAULT 'auto'
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: admin_credentials
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS admin_credentials (
        admin_id        TEXT PRIMARY KEY,
        user_id         TEXT NOT NULL REFERENCES users(user_id),
        password_hash   TEXT NOT NULL,
        role_level      TEXT NOT NULL,
        department_id   TEXT REFERENCES departments(department_id),
        created_at      TEXT NOT NULL
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: draft_sessions
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS draft_sessions (
        session_id                TEXT PRIMARY KEY,
        org_id                    TEXT NOT NULL REFERENCES organizations(org_id),
        reporter_user_id          TEXT NOT NULL REFERENCES users(user_id),
        started_at                TEXT NOT NULL,
        last_active_at            TEXT NOT NULL,
        current_revision          INTEGER NOT NULL DEFAULT 0,
        state                     TEXT NOT NULL,
        proposed_incident_type_id TEXT REFERENCES incident_types(incident_type_id),
        linked_group_id           TEXT,
        confirmed_revision        INTEGER
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: dialogue_turns
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS dialogue_turns (
        turn_id           TEXT PRIMARY KEY,
        session_id        TEXT NOT NULL REFERENCES draft_sessions(session_id),
        turn_index        INTEGER NOT NULL,
        draft_revision    INTEGER NOT NULL,
        speaker           TEXT NOT NULL,
        stage             TEXT NOT NULL,
        raw_text          TEXT NOT NULL,
        is_final          INTEGER NOT NULL DEFAULT 0,
        normalized_text   TEXT,
        lang_detected     TEXT,
        dialogue_act      TEXT,
        timestamp         TEXT NOT NULL
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: extracted_fields
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS extracted_fields (
        field_row_id      TEXT PRIMARY KEY,
        session_id        TEXT NOT NULL REFERENCES draft_sessions(session_id),
        field_id          TEXT NOT NULL REFERENCES form_fields(field_id),
        draft_revision    INTEGER NOT NULL,
        value             TEXT,
        status            TEXT NOT NULL,
        provenance        TEXT NOT NULL, -- values: lnn, llm, user_direct, system_invalidated
        confidence        REAL,
        evidence_turn_id  TEXT REFERENCES dialogue_turns(turn_id),
        superseded        INTEGER NOT NULL DEFAULT 0
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: correction_history
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS correction_history (
        correction_id       TEXT PRIMARY KEY,
        session_id          TEXT NOT NULL REFERENCES draft_sessions(session_id),
        field_row_id_before TEXT NOT NULL REFERENCES extracted_fields(field_row_id),
        field_row_id_after  TEXT NOT NULL REFERENCES extracted_fields(field_row_id),
        invalidated_json    TEXT,
        corrected_at        TEXT NOT NULL,
        triggering_turn_id  TEXT NOT NULL REFERENCES dialogue_turns(turn_id)
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: incidents
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS incidents (
        incident_id           TEXT PRIMARY KEY,
        org_id                TEXT NOT NULL REFERENCES organizations(org_id),
        session_id            TEXT NOT NULL REFERENCES draft_sessions(session_id),
        reporter_user_id      TEXT NOT NULL REFERENCES users(user_id),
        zone_id               TEXT REFERENCES zones(zone_id),
        incident_type_id      TEXT NOT NULL REFERENCES incident_types(incident_type_id),
        department_id         TEXT NOT NULL REFERENCES departments(department_id),
        linked_group_id       TEXT,
        description_en        TEXT NOT NULL,
        original_lang         TEXT NOT NULL,
        reported_at           TEXT NOT NULL,
        incident_time         TEXT,
        priority              TEXT NOT NULL,
        status                TEXT NOT NULL DEFAULT 'Open',
        confirmed_revision    INTEGER NOT NULL,
        local_submission_only INTEGER NOT NULL DEFAULT 1
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: function_calls
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS function_calls (
        call_id         TEXT PRIMARY KEY,
        session_id      TEXT NOT NULL REFERENCES draft_sessions(session_id),
        function_name   TEXT NOT NULL,
        arguments_json  TEXT NOT NULL,
        result_json     TEXT NOT NULL,
        called_at       TEXT NOT NULL
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: schema_versions / model_versions
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS schema_versions (
        version_id      TEXT PRIMARY KEY,
        artifact_type   TEXT NOT NULL,
        artifact_name   TEXT NOT NULL,
        version_label   TEXT NOT NULL,
        is_active       INTEGER NOT NULL DEFAULT 0,
        released_at     TEXT NOT NULL,
        rollback_of     TEXT REFERENCES schema_versions(version_id)
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: incident_relations
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS incident_relations (
        relation_id        TEXT PRIMARY KEY,
        incident_id_a      TEXT NOT NULL REFERENCES incidents(incident_id),
        incident_id_b      TEXT NOT NULL REFERENCES incidents(incident_id),
        relation_score     REAL NOT NULL,
        relation_reason    TEXT,
        computed_at        TEXT NOT NULL
    )
    """)

    # --------------------------------------------------------------------------------
    # TABLE: causal_edges
    # --------------------------------------------------------------------------------
    cursor.execute("""
    CREATE TABLE IF NOT EXISTS causal_edges (
        edge_id                 TEXT PRIMARY KEY,
        org_id                  TEXT NOT NULL REFERENCES organizations(org_id),
        cause_factor            TEXT NOT NULL,
        effect_incident_type_id TEXT NOT NULL REFERENCES incident_types(incident_type_id),
        weight                  REAL NOT NULL,
        lag_hint                TEXT,
        computed_at             TEXT NOT NULL
    )
    """)

    # --------------------------------------------------------------------------------
    # INDEXES
    # --------------------------------------------------------------------------------
    indexes = [
        "CREATE INDEX IF NOT EXISTS idx_incidents_zone ON incidents(zone_id)",
        "CREATE INDEX IF NOT EXISTS idx_incidents_dept ON incidents(department_id, status)",
        "CREATE INDEX IF NOT EXISTS idx_incidents_reported ON incidents(reported_at)",
        "CREATE INDEX IF NOT EXISTS idx_dialogue_session ON dialogue_turns(session_id, turn_index)",
        "CREATE INDEX IF NOT EXISTS idx_fields_session_rev ON extracted_fields(session_id, draft_revision)",
        "CREATE INDEX IF NOT EXISTS idx_relations_a ON incident_relations(incident_id_a)",
        "CREATE INDEX IF NOT EXISTS idx_relations_b ON incident_relations(incident_id_b)"
    ]
    for idx in indexes:
        cursor.execute(idx)

    conn.commit()
    conn.close()

if __name__ == "__main__":
    init_db()
    print("Database initialized successfully.")
