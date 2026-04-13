package com.example.spotifybot;

/**
 * Shared prefs name and keys for backend WebSocket URL + device auth.
 * Kept separate so {@link MainActivity} does not need to reference {@link BotService} for constants only.
 */
public final class ConnectionPrefs {

    private ConnectionPrefs() {
    }

    public static final String NAME = "spotify_bot_connection";
    public static final String KEY_BACKEND_WS_URL = "backend_ws_url";
    public static final String KEY_DEVICE_AUTH_TOKEN = "device_auth_token";
    public static final String KEY_CACHED_DEVICE_ID = "device_id_cached";
}
