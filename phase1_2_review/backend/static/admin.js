let currentOrgId = null;
let currentFloorplan = null;
let drawnPolygon = [];

// DOM Elements
const envForm = document.getElementById('envForm');
const zoneForm = document.getElementById('zoneForm');
const deptForm = document.getElementById('deptForm');
const incTypeForm = document.getElementById('incTypeForm');
const fieldForm = document.getElementById('fieldForm');
const canvas = document.getElementById('mapCanvas');
const ctx = canvas.getContext('2d');

// 1. Environment Setup
envForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const formData = new FormData();
    formData.append('name', document.getElementById('orgName').value);
    formData.append('floorplan', document.getElementById('orgImage').files[0]);

    const res = await fetch('/api/organizations', { method: 'POST', body: formData });
    const data = await res.json();
    currentOrgId = data.org_id;
    currentFloorplan = data.floorplan_path;
    
    document.getElementById('orgResult').innerText = `Environment created! Org ID: ${currentOrgId}`;
    
    // Unlock next sections
    document.getElementById('sec-zone').style.display = 'block';
    document.getElementById('sec-dept').style.display = 'block';
    document.getElementById('sec-inc').style.display = 'block';
    
    loadFloorplanImage();
    loadDepartments();
    loadIncidentTypes();
});

// 2. Zone Editor
function loadFloorplanImage() {
    const img = new Image();
    img.src = `/${currentFloorplan}`;
    img.onload = () => {
        ctx.clearRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
    };
}

canvas.addEventListener('click', (e) => {
    const rect = canvas.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    drawnPolygon.push({x, y});
    drawPolygon();
});

function drawPolygon() {
    loadFloorplanImage(); // Reset background
    setTimeout(() => {
        if(drawnPolygon.length === 0) return;
        ctx.beginPath();
        ctx.moveTo(drawnPolygon[0].x, drawnPolygon[0].y);
        for(let i=1; i<drawnPolygon.length; i++) {
            ctx.lineTo(drawnPolygon[i].x, drawnPolygon[i].y);
        }
        ctx.closePath();
        ctx.fillStyle = 'rgba(0, 123, 255, 0.4)';
        ctx.fill();
        ctx.stroke();
    }, 50); // slight delay to let image load
}

document.getElementById('clearCanvasBtn').addEventListener('click', () => {
    drawnPolygon = [];
    loadFloorplanImage();
});

zoneForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const payload = {
        org_id: currentOrgId,
        name: document.getElementById('zoneName').value,
        floor: document.getElementById('zoneFloor').value,
        category_hints: document.getElementById('zoneHints').value.split(','),
        polygon_json: drawnPolygon
    };
    await fetch('/api/zones', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(payload)
    });
    alert('Zone saved!');
    drawnPolygon = [];
    loadFloorplanImage();
    zoneForm.reset();
});

// 3. Department Editor
deptForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const payload = {
        org_id: currentOrgId,
        name: document.getElementById('deptName').value,
        description: document.getElementById('deptDesc').value
    };
    await fetch('/api/departments', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(payload)
    });
    deptForm.reset();
    loadDepartments();
});

async function loadDepartments() {
    const res = await fetch('/api/departments');
    const depts = await res.json();
    const sel = document.getElementById('incDept');
    sel.innerHTML = '';
    const list = document.getElementById('deptList');
    list.innerHTML = '<strong>Departments:</strong><ul>';
    depts.forEach(d => {
        sel.innerHTML += `<option value="${d.department_id}">${d.name}</option>`;
        list.innerHTML += `<li>${d.name} (${d.description})</li>`;
    });
    list.innerHTML += '</ul>';
}

// 4. Incident Type Editor
incTypeForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const payload = {
        org_id: currentOrgId,
        label: document.getElementById('incLabel').value,
        department_id: document.getElementById('incDept').value,
        default_priority: document.getElementById('incPriority').value,
        disambiguation_hint: document.getElementById('incDisambig').value
    };
    await fetch('/api/incident_types', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(payload)
    });
    incTypeForm.reset();
    loadIncidentTypes();
});

async function loadIncidentTypes() {
    const res = await fetch('/api/incident_types');
    const types = await res.json();
    const list = document.getElementById('incTypeList');
    list.innerHTML = '<strong>Incident Types (Click to edit Form):</strong><ul>';
    types.forEach(t => {
        list.innerHTML += `<li><a href="#" onclick="openFormBuilder('${t.incident_type_id}', '${t.label}')">${t.label} (Priority: ${t.default_priority})</a></li>`;
    });
    list.innerHTML += '</ul>';
}

function openFormBuilder(id, label) {
    document.getElementById('formBuilder').style.display = 'block';
    document.getElementById('currentIncTypeName').innerText = label;
    document.getElementById('fieldIncTypeId').value = id;
    loadFormFields(id);
}

fieldForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    const id = document.getElementById('fieldIncTypeId').value;
    const payload = {
        incident_type_id: id,
        field_name: document.getElementById('fieldName').value,
        field_kind: document.getElementById('fieldKind').value,
        data_type: document.getElementById('fieldType').value,
        prompt_template: document.getElementById('fieldPrompt').value,
        display_order: 1 // hardcoded for demo simplicity
    };
    await fetch('/api/form_fields', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify(payload)
    });
    fieldForm.reset();
    document.getElementById('fieldIncTypeId').value = id; // restore hidden
    loadFormFields(id);
});

async function loadFormFields(incTypeId) {
    const res = await fetch(`/api/form_fields?incident_type_id=${incTypeId}`);
    const fields = await res.json();
    const list = document.getElementById('fieldList');
    list.innerHTML = '<strong>Configured Fields:</strong><ul>';
    fields.forEach(f => {
        list.innerHTML += `<li>[${f.field_kind.toUpperCase()}] ${f.field_name} - ${f.prompt_template}</li>`;
    });
    list.innerHTML += '</ul>';
}
