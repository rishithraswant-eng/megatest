# AI Incident Report Assistant - Phase 3 Implementation

This repository contains the Phase 3 implementation of the AI Incident Report Assistant, focusing on the Routing and Form Engine and the local application shell.

## Setup and Running Locally

1. **Install dependencies:**
   Ensure you have Python 3.9+ installed.
   ```bash
   pip install fastapi uvicorn pydantic
   ```

2. **Run the backend:**
   From the project root directory, run the FastAPI app using uvicorn:
   ```bash
   uvicorn backend.main:app --reload
   ```

3. **Access the Local Web UI:**
   Open your browser and navigate to `http://localhost:8000/`. The UI is served entirely locally from the `backend/static` directory.

## Testing with the Stub Conversation Engine

The Conversation Engine interface has been implemented as a stub (`backend/stub_engine.py`) to allow for offline testing of the routing and form engine without requiring access to the physical Qualcomm Dev Kit or real AI models.

To feed the stub engine a scripted test conversation:

1. Create a scripted sequence of `TurnResponse` objects.
2. Initialize the `StubConversationEngine` with a dictionary mapping a `session_id` to the scripted sequence.
3. Call `process_turn(session_id, input_data)` on the engine. It will sequentially return the predefined outputs for each turn.

Example:
```python
from backend.stub_engine import StubConversationEngine, TurnResponse, TranscriptInfo

test_script = {
    "session_123": [
        TurnResponse(
            transcript=TranscriptInfo(text="There is a leak here.", is_final=True, lang_detected="en"),
            dialogue_act="answer",
            candidate_incident_types=[{"incident_type_id": "water_leak", "confidence": 0.95}],
            candidate_fields=[],
            function_calls_made=[],
            assistant_followup_text="Where exactly is the leak?",
            assistant_followup_audio_ref=None
        )
    ]
}

engine = StubConversationEngine(script=test_script)
output = engine.process_turn("session_123", "There is a leak here.")
print(output)
```

## Data Privacy and Cloud Calls Confirmation

**Explicit Confirmation:** There are **NO network or cloud calls** anywhere in this application.
- All logic runs locally.
- The UI uses plain HTML/JS served directly from the local file system.
- The database is a local SQLite file (`incident_assistant.db`).
- There are no outbound HTTP requests, API integrations, telemetry, or analytics tracking sent to any external server. 
- All data remains on-device as required.
