from sqlalchemy import Column, Integer, String, DateTime, Text, Boolean, JSON
from sqlalchemy.sql import func
from .database import Base

class User(Base):
    __tablename__ = "users"
    id       = Column(Integer, primary_key=True, index=True)
    username = Column(String, unique=True, index=True)
    password = Column(String)

class Device(Base):
    __tablename__ = "devices"
    id        = Column(Integer, primary_key=True, index=True)
    device_id = Column(String, unique=True, index=True)
    name      = Column(String)
    status    = Column(String, default="offline")

class Session(Base):
    __tablename__ = "sessions"
    id               = Column(Integer, primary_key=True, index=True)
    query            = Column(String)               # kept for backward compatibility
    duration_minutes = Column(Integer, default=30)
    device_id        = Column(String)
    status           = Column(String, default="pending")
    started_at       = Column(DateTime, nullable=True)
    ended_at         = Column(DateTime, nullable=True)
    created_at       = Column(DateTime, server_default=func.now())
    
    # Old fields
    action_type = Column(String, default="play_artist")   # deprecated
    start_time  = Column(DateTime, nullable=True)
    end_time    = Column(DateTime, nullable=True)
    target_count = Column(Integer, default=0)             # deprecated
    
    # New fields for play + interact
    play_enabled     = Column(Boolean, default=True)
    play_type        = Column(String, nullable=True)      # "artist", "song", "playlist"
    play_query       = Column(String, nullable=True)
    interact_actions = Column(JSON, default=list)        # store list of dicts

class SessionLog(Base):
    __tablename__ = "session_logs"
    id         = Column(Integer, primary_key=True, index=True)
    session_id = Column(Integer)
    step       = Column(String)
    status     = Column(String)
    message    = Column(Text, default="")
    timestamp  = Column(DateTime, server_default=func.now())