from backend.stub_engine import StubConversationEngine, TurnResponse, TranscriptInfo

def test_stub_engine_responses():
    test_script = {
        "session_1": [
            TurnResponse(
                transcript=TranscriptInfo(text="Water leak in zone A", is_final=True, lang_detected="en"),
                dialogue_act="answer",
                candidate_incident_types=[{"incident_type_id": "water_leak", "confidence": 0.9}],
                candidate_fields=[{"field_name": "zone", "value": "zone A", "confidence": 0.99, "provenance": "lnn"}],
                function_calls_made=[],
                assistant_followup_text="Got it. Is it severe?",
                assistant_followup_audio_ref=None
            )
        ]
    }
    engine = StubConversationEngine(script=test_script)
    output = engine.process_turn("session_1", "Water leak in zone A")
    
    assert output["transcript"]["text"] == "Water leak in zone A"
    assert output["candidate_incident_types"][0]["incident_type_id"] == "water_leak"
