from pydantic import BaseModel, field_validator
from typing import Optional, List, Any
from datetime import datetime

class UserLogin(BaseModel):
    username: str
    password: str

class Token(BaseModel):
    access_token: str
    token_type: str

class InteractAction(BaseModel):
    type: str  # "like", "follow", "skip"
    query: Optional[str] = ""
    count: int = 1

class SessionCreate(BaseModel):
    duration_minutes: int = 30
    device_id: str
    start_time: Optional[datetime] = None

    @field_validator("duration_minutes", mode="before")
    @classmethod
    def coerce_duration_minutes(cls, v: Any) -> int:
        if v is None:
            return 30
        try:
            n = int(v)
            return max(1, n)
        except (TypeError, ValueError):
            return 30

    # Play section
    play_enabled: bool = True
    play_type: Optional[str] = None   # "artist", "song", "playlist"
    play_query: Optional[str] = None
    
    # Interact section
    interact_actions: List[InteractAction] = []