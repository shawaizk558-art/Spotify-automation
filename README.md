# 🎵 Spotify Automation Bot

A powerful Android-based Spotify automation system that uses **Accessibility Services** to control Spotify — search artists/songs/playlists, play music, like tracks, follow artists, and skip songs — all orchestrated from a **FastAPI backend** via WebSocket.

---

## 🏗️ Architecture Overview

```
┌─────────────────┐        WebSocket (wss://)        ┌──────────────────────┐
│  FastAPI Backend │ ◄────────────────────────────── │  Android Bot App     │
│  (Python)        │ ────────────────────────────── ► │  (Java / Android)    │
│  + React Frontend│                                  │  Accessibility Svc   │
└─────────────────┘                                  └──────────────────────┘
         │                                                      │
         │                                                      ▼
    SQLite DB                                           Spotify App (UI)
```

**Three main components:**

| Component | Tech Stack | Role |
|-----------|-----------|------|
| `BotService.java` | Android Java | Background service, WebSocket client |
| `SpotifyController.java` | Android Java | Accessibility-based Spotify UI control |
| `main.py` (FastAPI) | Python 3 | Backend server, session scheduler, REST API |
| `App.jsx` (React) | Vite + React | Dashboard frontend |

---

## ✨ Features

- **🔍 Auto Search** — Searches Spotify for artists, songs, or playlists
- **▶️ Auto Play** — Clicks play on search results using Accessibility Service
- **❤️ Auto Like** — Likes songs via Spotify's context menu
- **➡️ Auto Skip** — Randomly skips to different tracks in a playlist/artist page
- **👤 Auto Follow** — Follows artists or saves playlists to library
- **⏱️ Session Scheduling** — Schedule sessions to run at a specific time
- **📡 Real-time Progress** — Live session status updates via WebSocket
- **🔄 Auto Reconnect** — WebSocket auto-reconnects on disconnect
- **🤖 Human-like Behavior** — Randomized delays, scroll patterns, and timing

---

## 📋 Requirements

### Android App
- Android 8.0+ (API 26+)
- Spotify app installed (`com.spotify.music`)
- Accessibility Service permission granted
- Battery optimization disabled for the app

### Backend Server
- Python 3.9+
- FastAPI
- SQLAlchemy
- `java_websocket` (Android)

### Frontend
- Node.js 18+
- React + Vite

---

## 🚀 Setup & Installation

### 1. Backend Setup

```bash
# Clone the repo
git clone https://github.com/shawaizk558-art/Spotify-automation.git
cd Spotify-automation

# Create virtual environment
python -m venv venv
source venv/bin/activate  # Windows: venv\Scripts\activate

# Install dependencies
pip install -r requirements.txt

# Configure environment variables
cp .env.example .env
# Edit .env with your settings
```

**`.env` configuration:**
```env
DEFAULT_ADMIN_USERNAME=admin
DEFAULT_ADMIN_PASSWORD=your_secure_password
DEVICE_SHARED_SECRET=your_secret_token
CORS_ORIGINS=http://localhost:5173
SCHEDULER_POLL_SECONDS=10
```

```bash
# Start the backend
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

---

### 2. Frontend Setup

```bash
cd frontend

# Install dependencies
npm install

# Configure API URL
echo "VITE_API_URL=http://your-server-ip:8000" > .env

# Start dev server
npm run dev

# Build for production
npm run build
```

---

### 3. Android App Setup

1. Open the project in **Android Studio**
2. In `res/values/strings.xml`, configure:

```xml
<string name="ws_server_url">wss://your-server-ip:8000/ws/device</string>
<string name="default_device_auth_token">your_secret_token</string>
```

3. Build and install the APK on your Android device
4. Open the app → Go to **Settings → Accessibility → SpotifyBot** → Enable it
5. Disable battery optimization for the app
6. Keep the app running in the background

---

## 📱 How It Works

### Session Flow

```
Dashboard → Create Session → Backend stores session
    → EXECUTE_SESSION sent via WebSocket
    → Android BotService receives command
    → SpotifyController executes:
         1. Open Spotify
         2. Click Search Tab
         3. Type query
         4. Submit search
         5. Click result row
         6. Start playback
         7. Like / Skip / Follow (as scheduled)
         8. Pause & Close after session ends
    → PROGRESS updates sent back to backend
    → Dashboard shows real-time logs
```

### Supported Play Types

| Type | Description |
|------|-------------|
| `artist` | Search for an artist and play their music |
| `song` | Search for a specific song and play it |
| `playlist` | Search for a playlist and shuffle play |

### Interact Actions

You can attach actions to a session:

```json
{
  "interact_actions": [
    { "type": "like",   "count": 3 },
    { "type": "skip",   "count": 5 },
    { "type": "follow", "count": 1 }
  ]
}
```

---

## 🌐 API Reference

### Authentication

```http
POST /login
Content-Type: application/json

{ "username": "admin", "password": "admin123" }
```

### Devices

```http
GET /devices
```

### Sessions

```http
# Create session
POST /sessions
{
  "device_id": "droid_xxxx",
  "play_type": "artist",
  "play_query": "Arijit Singh",
  "duration_minutes": 30,
  "play_enabled": true,
  "interact_actions": [
    { "type": "like", "count": 2 },
    { "type": "skip", "count": 3 }
  ]
}

# Start session
POST /sessions/{session_id}/start

# Stop session
POST /sessions/{session_id}/stop

# List sessions
GET /sessions

# Delete session
DELETE /sessions/{session_id}
```

---

## 🔌 WebSocket Protocol

### Device → Backend

```json
{ "type": "DEVICE_HELLO", "device_id": "droid_xxxx", "name": "Pixel 7", "auth_token": "secret" }
{ "type": "PROGRESS", "session_id": 1, "step": "PHASE2_PLAY_OK", "status": "ok", "message": "Playback started" }
```

### Backend → Device

```json
{ "type": "AUTH_OK" }
{ "type": "EXECUTE_SESSION", "session_id": 1, "play_type": "artist", "query": "Arijit Singh", ... }
{ "type": "STOP_SESSION", "session_id": 1 }
```

---

## 📊 Session Progress Steps

| Step | Meaning |
|------|---------|
| `OPEN_OK` | Spotify opened successfully |
| `SEARCH_TAB_OK` | Search tab clicked |
| `SEARCH_QUERY_OK` | Query typed into search field |
| `SEARCH_SUBMIT_OK` | Search submitted |
| `PHASE1_DONE` | Search phase complete |
| `PHASE2_PLAY_OK` | Playback started |
| `INTERACT_LIKE_OK` | Song liked |
| `INTERACT_SKIP_OK` | Track skipped |
| `INTERACT_FOLLOW_OK` | Artist/playlist followed |
| `SESSION_COMPLETE` | Session ended, Spotify closed |

---

## ⚠️ Important Notes

- This project uses Android **Accessibility Services** to automate UI interactions. Use responsibly and in accordance with Spotify's Terms of Service.
- The bot simulates human-like behavior (random delays, scroll patterns) to reduce detection risk.
- Make sure your Android device has **Spotify Premium** for uninterrupted playback.
- Keep the device **plugged in** during long sessions to prevent battery optimization from killing the service.

---

## 🔧 Troubleshooting

**Accessibility service not binding?**
- Reinstall the app → Enable accessibility → Disable battery optimization
- Check logcat for: `Accessibility onServiceConnected`

**WebSocket not connecting?**
- Verify `ws_server_url` in `strings.xml` points to your server
- Ensure port 8000 is open and accessible from the device
- Check that `DEVICE_SHARED_SECRET` matches on both sides

**Spotify not opening?**
- Ensure Spotify (`com.spotify.music`) is installed
- Grant the app permission to start background activities

---

## 📁 Project Structure

```
Spotify-automation/
├── app/                        # FastAPI backend
│   ├── main.py                 # Routes, WebSocket, scheduler
│   ├── models.py               # SQLAlchemy models
│   ├── schemas.py              # Pydantic schemas
│   ├── auth.py                 # JWT auth
│   └── database.py             # DB setup
├── frontend/                   # React dashboard
│   ├── src/
│   │   ├── App.jsx             # Login screen
│   │   └── Dashboard.jsx       # Session management UI
│   └── package.json
├── android/                    # Android bot app
│   └── app/src/main/java/com/example/spotifybot/
│       ├── BotService.java     # Background service + WebSocket
│       ├── SpotifyController.java  # Spotify UI automation
│       └── MyAccessibilityService.java
├── requirements.txt
└── README.md
```

---

## 📄 License

MIT License — feel free to use, modify, and distribute.

---

> Built by [Shawaiz Khan](https://github.com/shawaizk558-art) 🚀
