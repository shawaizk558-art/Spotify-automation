import os
import json
import uuid
import time
import datetime
import asyncio
from contextlib import asynccontextmanager
from datetime import timedelta
from typing import Dict, Optional
from dotenv import load_dotenv

from fastapi import FastAPI, Depends, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from sqlalchemy.orm import Session

load_dotenv()

from .database import engine, get_db, Base, SessionLocal  # ← Added SessionLocal
from .models import User, Device, Session as SessionModel, SessionLog
from .schemas import UserLogin, Token, SessionCreate
from .auth import hash_password, verify_password, create_token

Base.metadata.create_all(bind=engine)


def ensure_default_admin_user() -> None:
    """Fresh SQLite DB has no rows — login would always fail. Create one dev admin if table is empty."""
    db = SessionLocal()
    try:
        if db.query(User).first() is not None:
            return
        username = (os.getenv("DEFAULT_ADMIN_USERNAME") or "admin").strip() or "admin"
        password = os.getenv("DEFAULT_ADMIN_PASSWORD") or "admin123"
        db.add(User(username=username, password=hash_password(password)))
        db.commit()
        print(
            f"✅ Default dashboard user created (no users existed yet): "
            f"username={username!r} — use password from DEFAULT_ADMIN_PASSWORD or default `admin123`"
        )
    finally:
        db.close()


ensure_default_admin_user()

ADMIN_TOKEN = os.getenv("ADMIN_TOKEN", "")
SCHEDULER_POLL_SECONDS = int(os.getenv("SCHEDULER_POLL_SECONDS", "10"))

# Connected devices store (same asyncio process as WebSocket — no HTTP self-call for scheduled starts)
connected_devices: Dict[str, WebSocket] = {}

def normalize_play_type_for_device(raw: Optional[str]) -> str:
    """Map DB / legacy values to what the Android bot expects (artist, song, playlist)."""
    if not raw:
        return "artist"
    t = str(raw).strip().lower()
    if t in ("play_artist", "artist"):
        return "artist"
    if t in ("play_song", "song", "track"):
        return "song"
    if t in ("play_playlist", "playlist"):
        return "playlist"
    # Do not forward arbitrary strings — Android row matcher used to treat them as "match any subtitle".
    return "artist"


async def execute_session_dispatch(db: Session, session_id: int) -> dict:
    """Send EXECUTE_SESSION to the device (used by manual /start and in-process scheduler)."""
    session = db.query(SessionModel).filter(SessionModel.id == session_id).first()
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")

    if session.device_id not in connected_devices:
        print(
            f"⚠️ start session {session_id} rejected: device offline "
            f"(device_id={session.device_id!r}, connected={list(connected_devices.keys())})"
        )
        raise HTTPException(
            status_code=400,
            detail="Device offline — open the Android app and wait until it shows connected to this server.",
        )

    other_active = (
        db.query(SessionModel)
        .filter(
            SessionModel.device_id == session.device_id,
            SessionModel.status.in_(("running", "stopping")),
            SessionModel.id != session.id,
        )
        .first()
    )
    if other_active:
        d = f"Device already has session {other_active.id} in status={other_active.status}"
        print(f"⚠️ start session {session_id} rejected: {d}")
        raise HTTPException(status_code=400, detail=d)

    if session.status in ("running", "stopping"):
        d = f"Session already active (status={session.status})"
        print(f"⚠️ start session {session_id} rejected: {d}")
        raise HTTPException(status_code=400, detail=d)

    if session.status not in ("pending", "scheduled"):
        d = f"Cannot start session in status={session.status} — create a new session or delete this one."
        print(f"⚠️ start session {session_id} rejected: {d}")
        raise HTTPException(status_code=400, detail=d)

    session.status = "running"
    now_utc = datetime.datetime.utcnow()
    session.started_at = now_utc
    # Pending sessions had no end_time; window is [start, start + duration] per spec.
    if session.end_time is None:
        dm_fix = int(session.duration_minutes) if session.duration_minutes is not None else 30
        session.end_time = now_utc + timedelta(minutes=max(1, dm_fix))
    db.commit()

    command_id = str(uuid.uuid4())

    effective_query = (session.play_query or session.query or "").strip()
    play_kind = normalize_play_type_for_device(session.play_type or session.action_type)
    interact_actions = session.interact_actions if session.interact_actions is not None else []
    dm = int(session.duration_minutes) if session.duration_minutes is not None else 30
    if dm < 1:
        dm = 1

    end_dt = session.end_time or (now_utc + timedelta(minutes=dm))
    session_end_epoch_ms = int(end_dt.timestamp() * 1000)
    now_ms = int(now_utc.timestamp() * 1000)
    window_left_ms = max(60_000, session_end_epoch_ms - now_ms)
    ttl_ms = max(dm * 60 * 1000, window_left_ms + 25 * 60 * 1000)

    play_enabled = bool(session.play_enabled) if session.play_enabled is not None else True
    # APK uses one search+play; likes/skips are scheduled inside the listen window (no repeat search).
    repeat_until_session_end = False

    payload = {
        "type": "EXECUTE_SESSION",
        "command_id": command_id,
        "session_id": session.id,
        "action_type": play_kind,
        "play_type": play_kind,
        "query": effective_query,
        "duration_minutes": dm,
        "target_count": session.target_count,
        "ttl_ms": ttl_ms,
        "issued_at": now_ms,
        "session_end_epoch_ms": session_end_epoch_ms,
        "repeat_until_session_end": repeat_until_session_end,
        "play_enabled": play_enabled,
        "interact_actions": interact_actions,
    }

    ws = connected_devices[session.device_id]
    await ws.send_text(json.dumps(payload))

    log = SessionLog(
        session_id=session.id,
        step="SESSION_STARTED",
        status="ok",
        message=f"Command sent to {session.device_id} (play_type={play_kind}, query_len={len(effective_query)})",
    )
    db.add(log)
    db.commit()

    print(f"▶️ Session {session_id} started — command sent to {session.device_id}")

    return {
        "session_id": session_id,
        "command_id": command_id,
        "status": "started",
    }


async def enforce_session_end_times(db: Session) -> None:
    """When end_time passes, request STOP on device (same as /stop) so the window matches the document."""
    now = datetime.datetime.utcnow()
    running_past = (
        db.query(SessionModel)
        .filter(
            SessionModel.status == "running",
            SessionModel.end_time.isnot(None),
            SessionModel.end_time <= now,
        )
        .all()
    )
    for sess in running_past:
        if sess.device_id not in connected_devices:
            sess.status = "completed"
            sess.ended_at = now
            db.commit()
            db.add(
                SessionLog(
                    session_id=sess.id,
                    step="SESSION_END_DEVICE_OFFLINE",
                    status="ok",
                    message="end_time reached; device offline — marked completed",
                )
            )
            db.commit()
            continue
        sess.status = "stopping"
        db.commit()
        ws = connected_devices[sess.device_id]
        await ws.send_text(json.dumps({"type": "STOP_SESSION", "session_id": int(sess.id)}))
        db.add(
            SessionLog(
                session_id=sess.id,
                step="SESSION_END_STOP_SENT",
                status="ok",
                message="end_time reached — STOP_SESSION sent",
            )
        )
        db.commit()
        print(f"⏱ Session {sess.id} end_time reached — STOP_SESSION sent to {sess.device_id}")


async def scheduled_sessions_tick() -> None:
    db = SessionLocal()
    try:
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        await enforce_session_end_times(db)
        all_scheduled = (
            db.query(SessionModel)
            .filter(SessionModel.status == "scheduled", SessionModel.start_time.isnot(None))
            .all()
        )
        if all_scheduled:
            print(f"[scheduler] utc_now(naive)={now.isoformat()} poll={SCHEDULER_POLL_SECONDS}s scheduled_count={len(all_scheduled)}")
            for s in all_scheduled:
                is_due = s.start_time <= now
                print(f"  · session {s.id} start_time={s.start_time} device={s.device_id} due_now={is_due}")
        due = [s for s in all_scheduled if s.start_time <= now]
        for sess in due:
            try:
                await execute_session_dispatch(db, sess.id)
                print(f"✅ Scheduled session {sess.id} auto-started")
            except HTTPException as he:
                print(f"⏭ Scheduled session {sess.id} skipped: {he.status_code} {he.detail}")
            except Exception as ex:
                print(f"❌ Scheduled session {sess.id} error: {ex}")
    finally:
        db.close()


async def scheduled_sessions_loop() -> None:
    await asyncio.sleep(2)
    while True:
        try:
            await scheduled_sessions_tick()
        except Exception as e:
            print(f"scheduled_sessions_tick error: {e}")
        await asyncio.sleep(SCHEDULER_POLL_SECONDS)


@asynccontextmanager
async def lifespan(app: FastAPI):
    task = asyncio.create_task(scheduled_sessions_loop())
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


app = FastAPI(title="Spotify Automation API", lifespan=lifespan)


def _cors_settings() -> tuple[list[str], Optional[str]]:
    """Browser blocks /devices if UI Origin is not allowed (e.g. http://192.168.x.x:5173)."""
    origins = ["http://localhost:5173", "http://127.0.0.1:5173"]
    extra = os.getenv("CORS_ORIGINS", "")
    for part in extra.split(","):
        p = part.strip()
        if p and p not in origins:
            origins.append(p)
    regex: Optional[str] = None
    if os.getenv("CORS_ALLOW_LAN_VITE", "1").strip().lower() in ("1", "true", "yes", ""):
        regex = r"^http://[\w\.-]+:5173$"
    return origins, regex


_cors_origins, _cors_regex = _cors_settings()
app.add_middleware(
    CORSMiddleware,
    allow_origins=_cors_origins,
    allow_origin_regex=_cors_regex,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

print("✅ In-process scheduled session worker enabled (same event loop as WebSocket)")

# ── LOGIN ──────────────────────────────────────────
@app.post("/login", response_model=Token)
def login(data: UserLogin, db: Session = Depends(get_db)):
    user = db.query(User).filter(User.username == data.username).first()
    if not user or not verify_password(data.password, user.password):
        raise HTTPException(status_code=401, detail="Wrong username or password")
    token = create_token({"sub": user.username})
    return {"access_token": token, "token_type": "bearer"}

# ── DEVICES ───────────────────────────────────────
@app.get("/devices")
def get_devices(db: Session = Depends(get_db)):
    devices = db.query(Device).all()
    result = []
    for d in devices:
        result.append({
            "id": d.id,
            "device_id": d.device_id,
            "name": d.name,
            "status": "online" if d.device_id in connected_devices else "offline"
        })
    return result

# ── SESSIONS ──────────────────────────────────────
@app.post("/sessions")
def create_session(data: SessionCreate, db: Session = Depends(get_db)):
    # Compute end_time if start_time is provided
    end_time = None
    start_time = None
    status = "pending"

    if data.start_time:
        start_time = data.start_time
        # Always store naive UTC — scheduler compares with utc now (naive).
        if start_time.tzinfo is None:
            # No offset in JSON: treat wall clock as UTC (browser should use toISOString() → Z).
            start_time = start_time.replace(tzinfo=datetime.timezone.utc)
        start_time = start_time.astimezone(datetime.timezone.utc).replace(tzinfo=None)
        status = "scheduled"
        end_time = start_time + timedelta(minutes=int(data.duration_minutes))
    
    interact_actions_json = [action.model_dump() for action in data.interact_actions]
    
    session = SessionModel(
        duration_minutes=data.duration_minutes,
        device_id=data.device_id,
        status=status,
        start_time=start_time,
        end_time=end_time,
        # New fields
        play_enabled=data.play_enabled,
        play_type=data.play_type,
        play_query=data.play_query,
        interact_actions=interact_actions_json,
        # Keep old fields for compatibility (optional)
        query=data.play_query if data.play_enabled else None,
        action_type=data.play_type if data.play_enabled else None,
        target_count=0,  # not used anymore
    )
    db.add(session)
    db.commit()
    db.refresh(session)
    return {"session_id": session.id, "status": "created"}

@app.get("/sessions")
def get_sessions(db: Session = Depends(get_db)):
    return db.query(SessionModel).all()

# ── WEBSOCKET ─────────────────────────────────────
# Canonical path is /ws/device; /ws/devices is an alias (some clients used plural — unmatched WS paths get 403 from ASGI).
async def _device_websocket_core(websocket: WebSocket, db: Session):
    await websocket.accept()
    device_id = None
    try:
        raw = await websocket.receive_text()
        hello = json.loads(raw)

        if hello.get("type") != "DEVICE_HELLO":
            await websocket.close(code=4001)
            return

        # Empty DEVICE_SHARED_SECRET = dev mode (accept any client token, including "").
        # Otherwise require exact match — note JSON sends "" not null, which must not compare to None.
        secret = (os.getenv("DEVICE_SHARED_SECRET") or "").strip()
        token = hello.get("auth_token")
        token = "" if token is None else str(token).strip()
        if secret and token != secret:
            print(
                "WebSocket auth failed: DEVICE_SHARED_SECRET is set in env but "
                "DEVICE_HELLO auth_token did not match (check Android default_device_auth_token / prefs)."
            )
            await websocket.close(code=4003)
            return

        device_id = hello.get("device_id")
        if not device_id or not isinstance(device_id, str):
            await websocket.close(code=4002)
            return
        device_id = device_id.strip()
        if not device_id:
            await websocket.close(code=4002)
            return
        device_name = hello.get("name") or device_id
        if not isinstance(device_name, str):
            device_name = str(device_id)

        connected_devices[device_id] = websocket

        existing = db.query(Device).filter(Device.device_id == device_id).first()
        if not existing:
            db.add(Device(device_id=device_id, name=device_name, status="online"))
            db.commit()
        else:
            existing.status = "online"
            db.commit()

        await websocket.send_text(json.dumps({
            "type": "AUTH_OK",
            "message": "Connected successfully"
        }))

        print(f"✅ Device connected: {device_id}")

        while True:
            msg = await websocket.receive_text()
            data = json.loads(msg)
            print(f"📱 Message from {device_id}: {data}")

            session_id = data.get("session_id")
            if session_id:
                msg_type = data.get("type", "")
                if msg_type == "PROGRESS":
                    log_status = data.get("status", "ok")
                    msg = data.get("message", "")
                    rc = data.get("reason_code")
                    if rc:
                        msg = f"[{rc}] {msg}" if msg else f"[{rc}]"
                    log = SessionLog(
                        session_id=session_id,
                        step=data.get("step", ""),
                        status=log_status,
                        message=msg,
                    )
                else:
                    log = SessionLog(
                        session_id=session_id,
                        step=data.get("step", ""),
                        status=msg_type,
                        message=data.get("message", ""),
                    )
                db.add(log)
                db.commit()

                # If device reports it stopped, persist status on backend.
                if data.get("type") == "PROGRESS" and data.get("step") == "SESSION_STOPPED":
                    sess = db.query(SessionModel).filter(SessionModel.id == session_id).first()
                    if sess:
                        sess.status = "stopped"
                        sess.ended_at = datetime.datetime.utcnow()
                        db.commit()

                if data.get("type") == "PROGRESS" and data.get("step") == "SESSION_COMPLETE":
                    sess = db.query(SessionModel).filter(SessionModel.id == session_id).first()
                    if sess and sess.status in ("running", "stopping"):
                        sess.status = "completed"
                        sess.ended_at = datetime.datetime.utcnow()
                        db.commit()

    except WebSocketDisconnect:
        print(f"❌ Device disconnected: {device_id}")
        if device_id:
            if device_id in connected_devices:
                del connected_devices[device_id]
            dev = db.query(Device).filter(Device.device_id == device_id).first()
            if dev:
                dev.status = "offline"
                db.commit()


@app.websocket("/ws/device")
async def device_websocket(websocket: WebSocket, db: Session = Depends(get_db)):
    await _device_websocket_core(websocket, db)


@app.websocket("/ws/devices")
async def device_websocket_plural_alias(websocket: WebSocket, db: Session = Depends(get_db)):
    await _device_websocket_core(websocket, db)

# ── SESSION START ──────────────────────────────────
@app.post("/sessions/{session_id}/start")
async def start_session(session_id: int, db: Session = Depends(get_db)):
    return await execute_session_dispatch(db, session_id)


# ── SESSION STOP ───────────────────────────────────
@app.post("/sessions/{session_id}/stop")
async def stop_session(session_id: int, db: Session = Depends(get_db)):
    session = db.query(SessionModel).filter(SessionModel.id == session_id).first()
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")

    # Not started yet: never send STOP_SESSION to the device (no active run for this session_id).
    if session.status in ("pending", "scheduled"):
        session.status = "stopped"
        session.ended_at = datetime.datetime.utcnow()
        db.commit()
        log = SessionLog(
            session_id=session.id,
            step="SESSION_CANCELLED_BEFORE_START",
            status="ok",
            message="Pending/scheduled session cancelled (no device signal)",
        )
        db.add(log)
        db.commit()
        return {"session_id": session.id, "status": "stopped"}

    if session.status not in ("running", "stopping"):
        raise HTTPException(status_code=400, detail=f"Cannot stop session in status={session.status}")

    if session.device_id not in connected_devices:
        raise HTTPException(status_code=400, detail="Device offline")

    # Mark as stopping so UI can disable conflicting actions.
    session.status = "stopping"
    db.commit()

    payload = {
        "type": "STOP_SESSION",
        "session_id": int(session.id),
    }

    ws = connected_devices[session.device_id]
    await ws.send_text(json.dumps(payload))

    log = SessionLog(
        session_id=session.id,
        step="SESSION_STOP_REQUESTED",
        status="ok",
        message=f"Stop requested for {session.device_id}"
    )
    db.add(log)
    db.commit()

    return {"session_id": session.id, "status": "stopping"}


# ── SESSION DELETE ─────────────────────────────────
@app.delete("/sessions/{session_id}")
def delete_session(session_id: int, db: Session = Depends(get_db)):
    session = db.query(SessionModel).filter(SessionModel.id == session_id).first()
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")

    # If session is still running, don't delete.
    # If it's already stopping, allow cleanup to remove stuck sessions from UI.
    if session.status == "running":
        raise HTTPException(status_code=400, detail=f"Stop session before delete (status={session.status})")

    # Delete logs + session row.
    db.query(SessionLog).filter(SessionLog.session_id == session_id).delete()
    db.query(SessionModel).filter(SessionModel.id == session_id).delete()
    db.commit()

    return {"deleted": True}