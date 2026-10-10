from typing import Dict, Any, List, Optional
from pydantic import BaseModel

class TranscriptInfo(BaseModel):
    text: str
    is_final: bool
    lang_detected: str

class IncidentTypeCandidate(BaseModel):
    incident_type_id: str
    confidence: float

class CandidateField(BaseModel):
    field_name: str
    value: str
    confidence: float
    provenance: str

class FunctionCall(BaseModel):
    function_name: str
    arguments: dict
    result: dict

class TurnResponse(BaseModel):
    transcript: TranscriptInfo
    dialogue_act: Optional[str] = None
    candidate_incident_types: List[IncidentTypeCandidate] = []
    candidate_fields: List[CandidateField] = []
    function_calls_made: List[FunctionCall] = []
    assistant_followup_text: Optional[str] = None
    assistant_followup_audio_ref: Optional[str] = None
    distinct_incidents_mentioned: bool = False
    user_wants_to_stop_clarifying: bool = False

class StubConversationEngine:
    """
    A STUB implementation of the Conversation Engine interface.
    """
    def __init__(self, script: Dict[str, List[TurnResponse]]):
        self.script = script
        self.cursor: Dict[str, int] = {}
        
    def process_turn(self, session_id: str, audio_or_text_input: Any) -> TurnResponse:
        """
        Interface contract for Phase 3/4 against the Conversation Engine.
        Returns TurnResponse directly as requested.
        """
        if session_id not in self.script:
            raise ValueError(f"No script found for session {session_id}")
            
        if session_id not in self.cursor:
            self.cursor[session_id] = 0
            
        turn_idx = self.cursor[session_id]
        sequence = self.script[session_id]
        
        if turn_idx >= len(sequence):
            raise IndexError(f"Conversation script exhausted for session {session_id}")
            
        response = sequence[turn_idx]
        self.cursor[session_id] += 1
        
        return response
