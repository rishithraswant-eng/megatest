package org.seedhecode.incidentassistant.data

/**
 * On-device schema, REV. 2 of 02_DATABASE_SCHEMA.txt. Statements are in dependency order.
 * incidents.zone_id is nullable because Location can end in status='unknown'.
 */
object IncidentSchema {
    const val VERSION = 1

    val TABLES = listOf(
        "organizations", "zones", "departments", "incident_types", "form_fields", "users",
        "admin_credentials", "draft_sessions", "dialogue_turns", "extracted_fields",
        "correction_history", "incidents", "function_calls", "schema_versions",
        "incident_relations", "causal_edges",
    )

    val CREATE_STATEMENTS = listOf(
        """
        CREATE TABLE organizations (
            org_id          TEXT PRIMARY KEY,
            name            TEXT NOT NULL,
            created_at      TEXT NOT NULL,
            floorplan_path  TEXT NOT NULL,
            floorplan_w_px  INTEGER NOT NULL,
            floorplan_h_px  INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE zones (
            zone_id         TEXT PRIMARY KEY,
            org_id          TEXT NOT NULL REFERENCES organizations(org_id),
            name            TEXT NOT NULL,
            floor           TEXT,
            polygon_json    TEXT NOT NULL,
            category_hints  TEXT,
            created_at      TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE departments (
            department_id   TEXT PRIMARY KEY,
            org_id          TEXT NOT NULL REFERENCES organizations(org_id),
            name            TEXT NOT NULL,
            description     TEXT
        )
        """,
        """
        CREATE TABLE incident_types (
            incident_type_id    TEXT PRIMARY KEY,
            org_id              TEXT NOT NULL REFERENCES organizations(org_id),
            label               TEXT NOT NULL,
            department_id       TEXT NOT NULL REFERENCES departments(department_id),
            default_priority    TEXT NOT NULL,
            responsible_roles   TEXT,
            disambiguation_hint TEXT
        )
        """,
        """
        CREATE TABLE form_fields (
            field_id          TEXT PRIMARY KEY,
            incident_type_id  TEXT NOT NULL REFERENCES incident_types(incident_type_id),
            field_name        TEXT NOT NULL,
            field_kind        TEXT NOT NULL,
            data_type         TEXT NOT NULL,
            enum_values_json  TEXT,
            prompt_template   TEXT NOT NULL,
            display_order     INTEGER NOT NULL
        )
        """,
        """
        CREATE TABLE users (
            user_id         TEXT PRIMARY KEY,
            org_id          TEXT NOT NULL REFERENCES organizations(org_id),
            display_name    TEXT NOT NULL,
            role            TEXT NOT NULL,
            home_zone_id    TEXT REFERENCES zones(zone_id),
            preferred_lang  TEXT DEFAULT 'auto'
        )
        """,
        """
        CREATE TABLE admin_credentials (
            admin_id        TEXT PRIMARY KEY,
            user_id         TEXT NOT NULL REFERENCES users(user_id),
            password_hash   TEXT NOT NULL,
            role_level      TEXT NOT NULL,
            department_id   TEXT REFERENCES departments(department_id),
            created_at      TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE draft_sessions (
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
        """,
        """
        CREATE TABLE dialogue_turns (
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
        """,
        """
        CREATE TABLE extracted_fields (
            field_row_id      TEXT PRIMARY KEY,
            session_id        TEXT NOT NULL REFERENCES draft_sessions(session_id),
            field_id          TEXT NOT NULL REFERENCES form_fields(field_id),
            draft_revision    INTEGER NOT NULL,
            value             TEXT,
            status            TEXT NOT NULL,
            provenance        TEXT NOT NULL,
            confidence        REAL,
            evidence_turn_id  TEXT REFERENCES dialogue_turns(turn_id),
            superseded        INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE correction_history (
            correction_id       TEXT PRIMARY KEY,
            session_id          TEXT NOT NULL REFERENCES draft_sessions(session_id),
            field_row_id_before TEXT NOT NULL REFERENCES extracted_fields(field_row_id),
            field_row_id_after  TEXT NOT NULL REFERENCES extracted_fields(field_row_id),
            invalidated_json    TEXT,
            corrected_at        TEXT NOT NULL,
            triggering_turn_id  TEXT NOT NULL REFERENCES dialogue_turns(turn_id)
        )
        """,
        """
        CREATE TABLE incidents (
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
        """,
        """
        CREATE TABLE function_calls (
            call_id         TEXT PRIMARY KEY,
            session_id      TEXT NOT NULL REFERENCES draft_sessions(session_id),
            function_name   TEXT NOT NULL,
            arguments_json  TEXT NOT NULL,
            result_json     TEXT NOT NULL,
            called_at       TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE schema_versions (
            version_id      TEXT PRIMARY KEY,
            artifact_type   TEXT NOT NULL,
            artifact_name   TEXT NOT NULL,
            version_label   TEXT NOT NULL,
            is_active       INTEGER NOT NULL DEFAULT 0,
            released_at     TEXT NOT NULL,
            rollback_of     TEXT REFERENCES schema_versions(version_id)
        )
        """,
        """
        CREATE TABLE incident_relations (
            relation_id     TEXT PRIMARY KEY,
            incident_id_a   TEXT NOT NULL REFERENCES incidents(incident_id),
            incident_id_b   TEXT NOT NULL REFERENCES incidents(incident_id),
            relation_score  REAL NOT NULL,
            relation_reason TEXT,
            computed_at     TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE causal_edges (
            edge_id                 TEXT PRIMARY KEY,
            org_id                  TEXT NOT NULL REFERENCES organizations(org_id),
            cause_factor            TEXT NOT NULL,
            effect_incident_type_id TEXT NOT NULL REFERENCES incident_types(incident_type_id),
            weight                  REAL NOT NULL,
            lag_hint                TEXT,
            computed_at             TEXT NOT NULL
        )
        """,
        "CREATE INDEX idx_incidents_zone ON incidents(zone_id)",
        "CREATE INDEX idx_incidents_dept ON incidents(department_id, status)",
        "CREATE INDEX idx_incidents_reported ON incidents(reported_at)",
        "CREATE INDEX idx_dialogue_session ON dialogue_turns(session_id, turn_index)",
        "CREATE INDEX idx_fields_session_rev ON extracted_fields(session_id, draft_revision)",
        "CREATE INDEX idx_relations_a ON incident_relations(incident_id_a)",
        "CREATE INDEX idx_relations_b ON incident_relations(incident_id_b)",
    ).map { it.trimIndent() }
}
