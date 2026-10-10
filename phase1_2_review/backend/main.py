from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse
import os
import uuid
import json
from datetime import datetime
from .database import init_db, get_db_connection

app = FastAPI(title="AI Incident Report Assistant - Phase 3")

@app.on_event("startup")
def startup_event():
    init_db()

static_dir = os.path.join(os.path.dirname(__file__), "static")
uploads_dir = os.path.join(static_dir, "uploads")
os.makedirs(static_dir, exist_ok=True)
os.makedirs(uploads_dir, exist_ok=True)

app.mount("/static", StaticFiles(directory=static_dir), name="static")

@app.get("/", response_class=HTMLResponse)
def read_root():
    return '<a href="/static/admin.html">Go to Admin Mode</a>'

# 1. Environment Setup (Organizations)
@app.post("/api/organizations")
async def create_organization(name: str = Form(...), floorplan: UploadFile = File(...)):
    org_id = str(uuid.uuid4())
    file_path = f"uploads/{org_id}_{floorplan.filename}"
    full_path = os.path.join(static_dir, file_path)
    with open(full_path, "wb") as f:
        f.write(await floorplan.read())
    
    conn = get_db_connection()
    conn.execute(
        "INSERT INTO organizations (org_id, name, created_at, floorplan_path, floorplan_w_px, floorplan_h_px) VALUES (?, ?, ?, ?, ?, ?)",
        (org_id, name, datetime.utcnow().isoformat(), file_path, 800, 600)
    )
    conn.commit()
    conn.close()
    return {"org_id": org_id, "name": name, "floorplan_path": file_path}

@app.get("/api/organizations")
def get_organizations():
    conn = get_db_connection()
    orgs = [dict(row) for row in conn.execute("SELECT * FROM organizations").fetchall()]
    conn.close()
    return orgs

# 2. Zone Editor
@app.post("/api/zones")
def create_zone(data: dict):
    zone_id = str(uuid.uuid4())
    conn = get_db_connection()
    conn.execute(
        "INSERT INTO zones (zone_id, org_id, name, floor, polygon_json, category_hints, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
        (zone_id, data['org_id'], data['name'], data.get('floor'), json.dumps(data.get('polygon_json', [])), json.dumps(data.get('category_hints', [])), datetime.utcnow().isoformat())
    )
    conn.commit()
    conn.close()
    return {"zone_id": zone_id}

@app.get("/api/zones")
def get_zones():
    conn = get_db_connection()
    zones = [dict(row) for row in conn.execute("SELECT * FROM zones").fetchall()]
    conn.close()
    return zones

# 3. Department & Incident-Type Editor
@app.post("/api/departments")
def create_department(data: dict):
    department_id = str(uuid.uuid4())
    conn = get_db_connection()
    conn.execute(
        "INSERT INTO departments (department_id, org_id, name, description) VALUES (?, ?, ?, ?)",
        (department_id, data['org_id'], data['name'], data.get('description'))
    )
    conn.commit()
    conn.close()
    return {"department_id": department_id}

@app.get("/api/departments")
def get_departments():
    conn = get_db_connection()
    deps = [dict(row) for row in conn.execute("SELECT * FROM departments").fetchall()]
    conn.close()
    return deps

@app.post("/api/incident_types")
def create_incident_type(data: dict):
    incident_type_id = str(uuid.uuid4())
    conn = get_db_connection()
    conn.execute(
        "INSERT INTO incident_types (incident_type_id, org_id, label, department_id, default_priority, responsible_roles, disambiguation_hint) VALUES (?, ?, ?, ?, ?, ?, ?)",
        (incident_type_id, data['org_id'], data['label'], data['department_id'], data['default_priority'], json.dumps(data.get('responsible_roles', [])), data.get('disambiguation_hint'))
    )
    conn.commit()
    conn.close()
    return {"incident_type_id": incident_type_id}

@app.get("/api/incident_types")
def get_incident_types():
    conn = get_db_connection()
    types = [dict(row) for row in conn.execute("SELECT * FROM incident_types").fetchall()]
    conn.close()
    return types

@app.post("/api/form_fields")
def create_form_field(data: dict):
    field_id = str(uuid.uuid4())
    conn = get_db_connection()
    conn.execute(
        "INSERT INTO form_fields (field_id, incident_type_id, field_name, field_kind, data_type, enum_values_json, prompt_template, display_order) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (field_id, data['incident_type_id'], data['field_name'], data['field_kind'], data['data_type'], json.dumps(data.get('enum_values_json', [])), data['prompt_template'], data['display_order'])
    )
    conn.commit()
    conn.close()
    return {"field_id": field_id}

@app.get("/api/form_fields")
def get_form_fields(incident_type_id: str = None):
    conn = get_db_connection()
    if incident_type_id:
        fields = [dict(row) for row in conn.execute("SELECT * FROM form_fields WHERE incident_type_id = ?", (incident_type_id,)).fetchall()]
    else:
        fields = [dict(row) for row in conn.execute("SELECT * FROM form_fields").fetchall()]
    conn.close()
    return fields
