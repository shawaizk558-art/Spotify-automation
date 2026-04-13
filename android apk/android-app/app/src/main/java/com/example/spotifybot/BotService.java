package com.example.spotifybot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.accessibility.AccessibilityManager;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.provider.Settings;
import android.net.Uri;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public class BotService extends Service {
    private static final String TAG = "BotService";

    private static final String CHANNEL_ID = "SpotifyBotChannel";

    private String serverUrl = "";
    private WebSocketClient wsClient;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingReconnect;
    /** Avoid double connect when service is started twice in quick succession. */
    private long lastConnectAttemptMs = 0L;

    /** Serializes {@link #connectToBackend}; never run socket work on the main thread. */
    private ExecutorService wsConnectExecutor;
    /** After {@link #onDestroy}, do not create a new executor (avoids stray reconnect runnable). */
    private volatile boolean wsConnectExecutorShutdown;

    /** Set true when backend sends STOP_SESSION / CANCEL_SESSION for the active run. */
    private final AtomicBoolean cancelSessionRequested = new AtomicBoolean(false);
    /** Agar stop pehle aa jaye (EXECUTE_SESSION se race), to yahan target session_id store karein. */
    private volatile int cancelForSessionId = -1;
    private volatile int activeSessionId = -1;
    private volatile String activeCommandId = "";

    /** Same command_id must not run twice (backend retry / duplicate WS message). */
    private static final ConcurrentHashMap<String, Boolean> COMMAND_IN_FLIGHT = new ConcurrentHashMap<>();

    @Override
    public void onCreate() {
        super.onCreate();
        serverUrl = resolveBackendWsUrl();
        createNotificationChannel();
        Notification n = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(1, n);
            }
        } catch (Throwable t) {
            Log.e(TAG, "startForeground (typed) failed: " + t.getMessage(), t);
            // Must still enter foreground or the system stops the process shortly after startForegroundService().
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String url = intent.getStringExtra("backend_ws_url");
            if (url != null && !url.trim().isEmpty()) {
                getSharedPreferences(ConnectionPrefs.NAME, MODE_PRIVATE).edit()
                        .putString(ConnectionPrefs.KEY_BACKEND_WS_URL, url.trim())
                        .apply();
                serverUrl = url.trim();
            }
            String token = intent.getStringExtra("device_auth_token");
            if (token != null && !token.trim().isEmpty()) {
                getSharedPreferences(ConnectionPrefs.NAME, MODE_PRIVATE).edit()
                        .putString(ConnectionPrefs.KEY_DEVICE_AUTH_TOKEN, token.trim())
                        .apply();
            }
        }
        // Avoid heavy / socket work on the main thread (can crash the whole process while MainActivity is showing).
        runConnectToBackendOnWorker();
        return START_STICKY;
    }

    private synchronized ExecutorService ensureWsConnectExecutor() {
        if (wsConnectExecutorShutdown) {
            return null;
        }
        if (wsConnectExecutor == null) {
            wsConnectExecutor = Executors.newSingleThreadExecutor(
                    r -> new Thread(r, "BotService-ws"));
        }
        return wsConnectExecutor;
    }

    private void runConnectToBackendOnWorker() {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("thread", Thread.currentThread().getName());
            d.put("pid", android.os.Process.myPid());
            DebugSessionLog.agentLog("WS-C", "BotService.java:runConnectToBackendOnWorker", "before_execute", d);
        } catch (Throwable ignored) {
        }
        // #endregion
        ExecutorService ex = ensureWsConnectExecutor();
        if (ex == null) {
            return;
        }
        try {
            ex.execute(this::connectToBackend);
        } catch (RejectedExecutionException e) {
            Log.d(TAG, "connectToBackend not scheduled (executor shutdown)");
        }
    }

    private String resolveBackendWsUrl() {
        String fromPrefs = getSharedPreferences(ConnectionPrefs.NAME, MODE_PRIVATE)
                .getString(ConnectionPrefs.KEY_BACKEND_WS_URL, null);
        if (fromPrefs != null && !fromPrefs.trim().isEmpty()) {
            return fromPrefs.trim();
        }
        return getString(R.string.ws_server_url).trim();
    }

    private String stableDeviceId() {
        android.content.SharedPreferences p = getSharedPreferences(ConnectionPrefs.NAME, MODE_PRIVATE);
        String cached = p.getString(ConnectionPrefs.KEY_CACHED_DEVICE_ID, null);
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        String aid = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        String id;
        if (aid == null || aid.isEmpty() || "9774d56d682e549c".equals(aid)) {
            id = "uuid_" + UUID.randomUUID().toString();
        } else {
            id = "droid_" + aid;
        }
        p.edit().putString(ConnectionPrefs.KEY_CACHED_DEVICE_ID, id).apply();
        return id;
    }

    private String deviceAuthToken() {
        String t = getSharedPreferences(ConnectionPrefs.NAME, MODE_PRIVATE)
                .getString(ConnectionPrefs.KEY_DEVICE_AUTH_TOKEN, null);
        if (t != null && !t.trim().isEmpty()) {
            return t.trim();
        }
        String def = getString(R.string.default_device_auth_token);
        return def != null ? def.trim() : "";
    }

    private static String deviceDisplayName() {
        String m = Build.MANUFACTURER != null ? Build.MANUFACTURER : "";
        String model = Build.MODEL != null ? Build.MODEL : "";
        String out = (m + " " + model).trim();
        return out.isEmpty() ? "Android device" : out;
    }

    private void cancelScheduledReconnect() {
        if (pendingReconnect != null) {
            mainHandler.removeCallbacks(pendingReconnect);
            pendingReconnect = null;
        }
    }

    private void scheduleReconnectDebounced(long delayMs) {
        cancelScheduledReconnect();
        pendingReconnect = () -> {
            pendingReconnect = null;
            Log.d(TAG, "Reconnecting WebSocket after delay…");
            // #region agent log
            try {
                JSONObject d = new JSONObject();
                d.put("thread", Thread.currentThread().getName());
                d.put("pid", android.os.Process.myPid());
                DebugSessionLog.agentLog("WS-B", "BotService.java:scheduleReconnectDebounced", "reconnect_runnable", d);
            } catch (Throwable ignored) {
            }
            // #endregion
            runConnectToBackendOnWorker();
        };
        mainHandler.postDelayed(pendingReconnect, delayMs);
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Spotify Bot",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Spotify Bot")
                .setContentText("Running...")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
    }

    private void connectToBackend() {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("thread", Thread.currentThread().getName());
            d.put("pid", android.os.Process.myPid());
            DebugSessionLog.agentLog("WS-A", "BotService.java:connectToBackend", "entry", d);
        } catch (Throwable ignored) {
        }
        // #endregion
        serverUrl = resolveBackendWsUrl();
        if (serverUrl.isEmpty() || serverUrl.contains("YOUR_BACKEND_HOST")) {
            Log.e(TAG, "Set backend WebSocket URL: strings.xml ws_server_url or prefs "
                    + ConnectionPrefs.KEY_BACKEND_WS_URL + " (outbound wss://… to your server)");
            scheduleReconnectDebounced(15_000L);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastConnectAttemptMs < 600L) {
            Log.d(TAG, "connectToBackend debounce skip");
            return;
        }
        lastConnectAttemptMs = now;

        if (wsClient != null) {
            try {
                wsClient.close();
            } catch (Exception ignored) {
            }
            wsClient = null;
        }
        try {
            Log.i(TAG, "Outbound WebSocket connect to " + serverUrl);
            URI uri = buildWebSocketUriWithOptionalQueryToken(serverUrl);
            Map<String, String> headers = buildWebSocketHandshakeHeaders();
            wsClient = new WebSocketClient(uri, headers) {

                @Override
                public void onOpen(ServerHandshake handshake) {
                    cancelScheduledReconnect();
                    Log.i(TAG, "WebSocket OPEN — DEVICE_HELLO sent to backend");
                    sendHello();
                }

                @Override
                public void onMessage(String message) {
                    Log.d(TAG, "Message: " + message);
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.w(TAG, "WebSocket closed code=" + code + " reason=" + reason + " remote=" + remote);
                    scheduleReconnectDebounced(5000L);
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error: " + ex.getMessage(), ex);
                    scheduleReconnectDebounced(5000L);
                }
            };
            wsClient.connect();
        } catch (URISyntaxException e) {
            Log.e(TAG, "Invalid WebSocket URL: " + e.getMessage());
            scheduleReconnectDebounced(15_000L);
        } catch (Exception e) {
            Log.e(TAG, "WebSocket connect failed: " + e.getMessage(), e);
            scheduleReconnectDebounced(10_000L);
        }
    }

    /**
     * If {@code device_auth_token} is set, append {@code ?token=} (or {@code &token=}) so backends that
     * expect a query param still receive it during the HTTP→WS upgrade.
     */
    private URI buildWebSocketUriWithOptionalQueryToken(String baseUrl) throws URISyntaxException {
        String tok = deviceAuthToken();
        if (tok == null || tok.isEmpty()) {
            return new URI(baseUrl);
        }
        String withToken = Uri.parse(baseUrl).buildUpon()
                .appendQueryParameter("token", tok)
                .build()
                .toString();
        return new URI(withToken);
    }

    private Map<String, String> buildWebSocketHandshakeHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", "SpotifyBot-Android/1.0");
        String tok = deviceAuthToken();
        if (tok != null && !tok.isEmpty()) {
            headers.put("Authorization", "Bearer " + tok);
        }
        return headers;
    }

    private void sendHello() {
        try {
            JSONObject hello = new JSONObject();
            hello.put("type", "DEVICE_HELLO");
            hello.put("device_id", stableDeviceId());
            hello.put("name", deviceDisplayName());
            hello.put("auth_token", deviceAuthToken());
            String did = stableDeviceId();
            wsClient.send(hello.toString());
            Log.d(TAG, "DEVICE_HELLO sent device_id=" + did);
        } catch (Exception e) {
            Log.e(TAG, "Hello error: " + e.getMessage());
        }
    }

    private void handleMessage(String message) {
        try {
            JSONObject data = new JSONObject(message);
            String type = data.getString("type");

            if (type.equals("AUTH_OK")) {
                Log.d(TAG, "Auth successful");
            } else if (type.equals("STOP_SESSION") || type.equals("CANCEL_SESSION")) {
                int sid = parseStopSessionId(data);
                if (sid < 0 && activeSessionId >= 0) {
                    sid = activeSessionId;
                }
                cancelForSessionId = sid;
                cancelSessionRequested.set(true);
                Log.w(TAG, "Session cancel requested (session_id=" + sid + " activeSessionId=" + activeSessionId + ")");
                // Backend marks session "stopped" when it receives PROGRESS SESSION_STOPPED.
                if (sid >= 0) {
                    sendProgress(activeCommandId != null ? activeCommandId : "", sid, "SESSION_STOPPED", "ok",
                            "Backend STOP_SESSION received");
                }
            } else if (type.equals("EXECUTE_SESSION")) {
                Log.d(TAG, "Session command received");
                new Thread(() -> runSession(data)).start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Message error: " + e.getMessage(), e);
        }
    }

    /** Robust session_id from STOP payload (int / long / string); -1 if missing or invalid. */
    private static int parseStopSessionId(JSONObject data) {
        if (data == null || !data.has("session_id") || data.isNull("session_id")) {
            return -1;
        }
        try {
            return data.getInt("session_id");
        } catch (Exception ignored) {
        }
        try {
            long asLong = data.getLong("session_id");
            if (asLong >= 0 && asLong <= Integer.MAX_VALUE) {
                return (int) asLong;
            }
        } catch (Exception ignored) {
        }
        try {
            Object raw = data.opt("session_id");
            if (raw != null) {
                return Integer.parseInt(String.valueOf(raw).trim());
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void runSession(JSONObject data) {
        SpotifyController ctrl = new SpotifyController(getApplicationContext());
        final String commandId;
        final int sessionId;
        try {
            commandId = data.getString("command_id");
            sessionId = data.getInt("session_id");
        } catch (Exception e) {
            Log.e(TAG, "runSession bad payload: " + e.getMessage());
            return;
        }

        if (COMMAND_IN_FLIGHT.putIfAbsent(commandId, Boolean.TRUE) != null) {
            sendProgress(commandId, sessionId, "DUPLICATE_COMMAND_IGNORED", "ok",
                    "Idempotent skip: duplicate command_id", "DUPLICATE_COMMAND");
            return;
        }

        try {
            boolean playEnabled = data.optBoolean("play_enabled", true);
            String query = data.optString("query", "");
            String playTypeRaw = data.optString("play_type", "").trim();
            if (playTypeRaw.isEmpty()) {
                playTypeRaw = data.optString("action_type", "artist").trim();
            }
            String playType = canonicalPlayType(playTypeRaw);
            double durationRaw = data.optDouble("duration_minutes", 30.0);
            int durationMinutes = (int) Math.round(durationRaw);
            if (durationMinutes < 1) {
                durationMinutes = 1;
            }
            if (durationMinutes > 24 * 60) {
                durationMinutes = 24 * 60;
            }
            JSONArray interactActions = data.optJSONArray("interact_actions");
            long nowWall = System.currentTimeMillis();
            long sessionEndMs = data.optLong("session_end_epoch_ms", 0L);
            if (sessionEndMs <= nowWall) {
                sessionEndMs = nowWall + (long) durationMinutes * 60_000L;
            }
            long ttlMs = data.optLong("ttl_ms", -1);
            if (ttlMs <= 0) {
                ttlMs = Math.max((long) durationMinutes * 60_000L, sessionEndMs - nowWall + 5L * 60_000L);
            }
            final long phaseBudgetMs = Math.max(ttlMs, sessionEndMs - nowWall + 25L * 60_000L);
            final long sessionDeadlineMs = nowWall + phaseBudgetMs;
            ctrl.setSessionDeadlineMs(sessionDeadlineMs);

            Log.i(TAG, "runSession playEnabled=" + playEnabled + " playType=" + playType + " query=" + query
                    + " sessionEndMs=" + sessionEndMs
                    + " interact_count=" + (interactActions != null ? interactActions.length() : 0)
                    + " (single search+play; scheduled skips only in listen window)");

            if (cancelSessionRequested.get() && cancelForSessionId == -1) {
                cancelSessionRequested.set(false);
            }

            activeSessionId = sessionId;
            activeCommandId = commandId;
            ctrl.setCancelToken(cancelSessionRequested);
            ctrl.setCurrentPlayType(playType);
            ctrl.setSpotifyApiConfig(
                    data.optString("spotify_access_token", ""),
                    data.optString("spotify_context_uri", ""));

            if (isSessionStopRequested(sessionId)) {
                Log.w(TAG, "Cancelled before start (stale STOP or same session_id as prior cancel); "
                        + "cancelForSessionId=" + cancelForSessionId + " sessionId=" + sessionId);
                sendProgress(commandId, sessionId, "SESSION_STOPPED", "ok", "Cancelled before start");
                cancelSessionRequested.set(false);
                cancelForSessionId = -1;
                return;
            }

            if (!waitForAccessibilityInstanceShort(commandId, sessionId)) {
                return;
            }
            if (!ensureAccessibilityConnected(commandId, sessionId)) {
                return;
            }

            ctrl.tryDismissBlockingOverlays();
            String blockHint = ctrl.detectBlockingScreenHint();
            if (blockHint != null) {
                sendProgress(commandId, sessionId, "BLOCKING_SCREEN", "failed",
                        "Blocked: " + blockHint, blockHint);
                stepSessionTimerPauseAndClose(ctrl, commandId, sessionId, 0L);
                return;
            }

            if (playEnabled) {
                if (!stepOpenSpotifyWithRetries(ctrl, commandId, sessionId)) {
                    stepSessionTimerPauseAndClose(ctrl, commandId, sessionId, 0L);
                    return;
                }
                if (!stepClickSearchTabWithRetries(ctrl, commandId, sessionId)) {
                    stepSessionTimerPauseAndClose(ctrl, commandId, sessionId, 0L);
                    return;
                }
                if (!stepTypeSearchQueryWithRetries(ctrl, commandId, sessionId, query)) {
                    stepSessionTimerPauseAndClose(ctrl, commandId, sessionId, 0L);
                    return;
                }
                if (!stepSubmitSearchWithRetries(ctrl, commandId, sessionId)) {
                    stepSessionTimerPauseAndClose(ctrl, commandId, sessionId, 0L);
                    return;
                }

                if (hasFollowInteract(interactActions) && "artist".equals(playType)) {
                    String followTarget = extractFirstFollowQuery(interactActions);
                    if (followTarget == null || followTarget.isEmpty()) {
                        followTarget = query;
                    }
                    if (followTarget != null && !followTarget.isEmpty()) {
                        sendProgress(commandId, sessionId, "INTERACT_FOLLOW_TRY", "ok",
                                "Follow on same search results row (playType=" + playType + "): " + followTarget);
                        if (ctrl.tryFollowInSearchResultsRow(followTarget, playType)) {
                            sendProgress(commandId, sessionId, "INTERACT_FOLLOW_OK", "ok",
                                    "follow_button in row for " + followTarget);
                        } else {
                            sendProgress(commandId, sessionId, "INTERACT_FOLLOW_FAIL", "failed",
                                    "No follow_button in matching row for " + followTarget,
                                    "FOLLOW_BUTTON_NOT_FOUND");
                        }
                    }
                }

                sendProgress(commandId, sessionId, "PHASE1_DONE", "ok",
                        "Spotify open + Search tab + query typed + search submitted. query=" + query
                                + " submit=" + ctrl.getLastSearchSubmitUsed());
                if (!stepPlayFromResults(ctrl, commandId, sessionId, playType, query)) {
                    if (isSessionStopRequested(sessionId)) {
                        sendProgress(commandId, sessionId, "PLAY_RESULTS_CANCELLED", "ok",
                                "Stopped during play phase; teardown");
                    } else if (ctrl.isSessionDeadlinePassed()) {
                        sendProgress(commandId, sessionId, "PLAY_RESULTS_TIMEOUT", "ok",
                                "Session TTL reached before playback OK; teardown");
                    }
                    stepSessionTimerPauseAndClose(ctrl, commandId, sessionId, 0L);
                    return;
                }
                sendProgress(commandId, sessionId, "PHASE2_PLAY_OK", "ok",
                        "Playback started via " + ctrl.getLastPlayResultUsed());

                if (hasFollowInteract(interactActions) && "playlist".equals(playType)) {
                    String followTarget = extractFirstFollowQuery(interactActions);
                    if (followTarget == null || followTarget.isEmpty()) {
                        followTarget = query;
                    }
                    sendProgress(commandId, sessionId, "INTERACT_FOLLOW_TRY", "ok",
                            "Follow after playback in playlist context: " + followTarget);
                    if (ctrl.followFromCurrentPlaybackContext(followTarget, playType)) {
                        sendProgress(commandId, sessionId, "INTERACT_FOLLOW_OK", "ok",
                                "Playlist follow attempted from current playback context");
                    } else {
                        sendProgress(commandId, sessionId, "INTERACT_FOLLOW_FAIL", "failed",
                                "Playlist follow not available from current playback context",
                                "FOLLOW_PLAYLIST_NOT_AVAILABLE");
                    }
                }
            } else {
                if (!stepOpenSpotifyWithRetries(ctrl, commandId, sessionId)) {
                    stepSessionTimerPauseAndClose(ctrl, commandId, sessionId, 0L);
                    return;
                }
            }

            int nLike = sumInteractCount(interactActions, "like");
            int nSkip = sumInteractCount(interactActions, "skip");
            runListenPhaseWithScheduledInteracts(ctrl, commandId, sessionId, sessionEndMs, nSkip, nLike, playType);

            if (cancelForSessionId == -1 && cancelSessionRequested.get()) {
                cancelSessionRequested.set(false);
                Log.d(TAG, "Cleared wildcard cancel before teardown");
            }

            stepSessionTimerPauseAndClose(ctrl, commandId, sessionId, 0L);
        } catch (Exception e) {
            Log.e(TAG, "Session error: " + e.getMessage(), e);
        } finally {
            COMMAND_IN_FLIGHT.remove(commandId);
            activeSessionId = -1;
            activeCommandId = "";
            // Prior runs could exit on cancel/error without clearing STOP state; next EXECUTE then looked "cancelled" immediately.
            cancelSessionRequested.set(false);
            cancelForSessionId = -1;
        }
    }

    /**
     * Same idea as backend {@code normalize_play_type_for_device}: only artist | song | playlist.
     * Unknown values used to pass through and break title/subtitle row matching (playlist rows could match).
     */
    private static String likeFailSuffix(SpotifyController ctrl) {
        String h = ctrl.getLastLikeFailureReason();
        if (h == null || h.isEmpty()) {
            return "";
        }
        return " | " + h;
    }

    private static String likeFailReasonCode(SpotifyController ctrl) {
        String h = ctrl.getLastLikeFailureReason();
        if (h == null || h.isEmpty()) {
            return "LIKE_NOT_FOUND";
        }
        if (h.startsWith("API_NOT_CONFIGURED")) {
            return "LIKE_API_NOT_CONFIGURED";
        }
        if (h.startsWith("API_EMPTY_POOL") || h.contains("401")) {
            return "LIKE_API_POOL_OR_AUTH";
        }
        if (h.startsWith("API_SAVE_FAILED") || h.contains("403")) {
            return "LIKE_API_SAVE_DENIED";
        }
        if (h.startsWith("API_ERROR")) {
            return "LIKE_API_ERROR";
        }
        if (h.startsWith("API_POOL_EXHAUSTED")) {
            return "LIKE_API_POOL_EXHAUSTED";
        }
        if (h.startsWith("UI_") || h.contains("TRAILING_SLOT") || h.contains("CONTEXT_MENU")) {
            return "LIKE_UI_MISS";
        }
        return "LIKE_NOT_FOUND";
    }

    private static String canonicalPlayType(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "artist";
        }
        String t = raw.trim().toLowerCase(Locale.ROOT);
        switch (t) {
            case "artist":
            case "play_artist":
                return "artist";
            case "song":
            case "track":
            case "play_song":
                return "song";
            case "playlist":
            case "play_playlist":
                return "playlist";
            default:
                Log.w(TAG, "Unknown play_type in payload '" + raw + "' — using artist");
                return "artist";
        }
    }

    private static final class TimedInteract {
        final long fireAtEpochMs;
        final String kind;

        TimedInteract(long fireAtEpochMs, String kind) {
            this.fireAtEpochMs = fireAtEpochMs;
            this.kind = kind;
        }
    }

    private static int sumInteractCount(JSONArray actions, String wantType) {
        if (actions == null) {
            return 0;
        }
        int sum = 0;
        for (int i = 0; i < actions.length(); i++) {
            try {
                JSONObject a = actions.getJSONObject(i);
                String t = a.optString("type", "").trim().toLowerCase();
                if (wantType.equals(t)) {
                    int c = a.optInt("count", 1);
                    sum += Math.max(1, c);
                }
            } catch (Exception ignored) {
            }
        }
        return sum;
    }

    private void runLikeOnCurrentList(SpotifyController ctrl, String commandId, int sessionId, int targetLikes) {
        if (targetLikes <= 0) {
            return;
        }
        if (isSessionStopRequested(sessionId)) {
            sendProgress(commandId, sessionId, "INTERACT_LIKE_STOPPED", "ok", "Cancelled before like phase");
            return;
        }
        sendProgress(commandId, sessionId, "INTERACT_LIKE_START", "ok",
                "Liking target=" + targetLikes + " (UI flow)");

        int liked = 0;
        final int maxPasses = 3;
        for (int pass = 1; pass <= maxPasses && liked < targetLikes; pass++) {
            if (isSessionStopRequested(sessionId)) {
                sendProgress(commandId, sessionId, "INTERACT_LIKE_STOPPED", "ok",
                        "Cancelled during like phase");
                return;
            }
            int got = ctrl.likeSongsFromCurrentList(targetLikes - liked);
            liked += Math.max(0, got);
            sendProgress(commandId, sessionId, "INTERACT_LIKE_PROGRESS", "ok",
                    "Like pass " + pass + "/" + maxPasses + " complete, liked_total=" + liked + "/" + targetLikes);
            if (liked >= targetLikes) {
                break;
            }
            if (!interruptibleSleepMsForSession(650L, sessionId)) {
                return;
            }
        }

        if (liked >= targetLikes) {
            sendProgress(commandId, sessionId, "INTERACT_LIKE_DONE", "ok",
                    "Liked " + liked + " song(s) from current list");
        } else {
            sendProgress(commandId, sessionId, "INTERACT_LIKE_PARTIAL", "failed",
                    "Could like only " + liked + "/" + targetLikes + " song(s)", "LIKE_TARGET_NOT_REACHED");
        }
    }

    private static boolean hasFollowInteract(JSONArray actions) {
        if (actions == null) {
            return false;
        }
        for (int i = 0; i < actions.length(); i++) {
            try {
                JSONObject a = actions.getJSONObject(i);
                if (!"follow".equals(a.optString("type", "").trim().toLowerCase())) {
                    continue;
                }
                if (a.optInt("count", 1) >= 1) {
                    return true;
                }
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    /** Optional override; empty → session {@code query} is used with {@code play_type} for row match. */
    private static String extractFirstFollowQuery(JSONArray actions) {
        if (actions == null) {
            return null;
        }
        for (int i = 0; i < actions.length(); i++) {
            try {
                JSONObject a = actions.getJSONObject(i);
                if (!"follow".equals(a.optString("type", "").trim().toLowerCase())) {
                    continue;
                }
                if (a.optInt("count", 1) < 1) {
                    continue;
                }
                String q = a.optString("query", "").trim();
                if (!q.isEmpty()) {
                    return q;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private List<TimedInteract> buildRandomSkipSchedule(long listenStart, long listenEnd, int nSkip) {
        List<TimedInteract> out = new ArrayList<>();
        if (nSkip <= 0) {
            return out;
        }
        long window = listenEnd - listenStart;
        long minT = listenStart + Math.min(22_000L, Math.max(8000L, window / 7));
        long maxT = listenEnd - Math.min(20_000L, Math.max(8000L, window / 5));
        if (maxT <= minT) {
            minT = listenStart + Math.max(2500L, window / 12);
            maxT = listenEnd - Math.max(2500L, window / 12);
        }
        if (maxT <= minT) {
            long t = listenStart + 1200L;
            for (int i = 0; i < nSkip; i++) {
                out.add(new TimedInteract(t, "skip"));
                t += 650L + (long) (Math.random() * 1100L);
            }
            return out;
        }
        List<Long> times = new ArrayList<>();
        for (int i = 0; i < nSkip; i++) {
            times.add(minT + (long) (Math.random() * (maxT - minT)));
        }
        Collections.sort(times);
        long minGap = 12_000L;
        if (window < nSkip * minGap + 30_000L) {
            minGap = Math.max(4500L, (window - 25_000L) / Math.max(1, nSkip));
        }
        for (int i = 1; i < times.size(); i++) {
            if (times.get(i) - times.get(i - 1) < minGap) {
                long pushed = times.get(i - 1) + minGap;
                if (pushed <= listenEnd - 7000L) {
                    times.set(i, pushed);
                }
            }
        }
        for (int i = 0; i < nSkip; i++) {
            out.add(new TimedInteract(times.get(i), "skip"));
        }
        Collections.sort(out, new Comparator<TimedInteract>() {
            @Override
            public int compare(TimedInteract a, TimedInteract b) {
                return Long.compare(a.fireAtEpochMs, b.fireAtEpochMs);
            }
        });
        return out;
    }

    /**
     * Listen phase orchestration: likes first (bounded attempts), then skip schedule.
     */
    private void runListenPhaseWithScheduledInteracts(SpotifyController ctrl, String commandId, int sessionId,
                                                    long sessionEndMs, int nSkip, int nLike, String playType) {
        long listenStart = System.currentTimeMillis();
        if (listenStart >= sessionEndMs) {
            return;
        }

        sendProgress(commandId, sessionId, "INTERACT_SCHEDULE_BUILT", "ok",
                "Listen orchestration=likes-first-then-skips | likes=" + nLike + " skips=" + nSkip);

        int likesDone = 0;

        // Phase A: perform likes first.
        while (System.currentTimeMillis() < sessionEndMs && likesDone < nLike) {
            if (isExplicitStopForThisSession(sessionId)) {
                sendProgress(commandId, sessionId, "LISTEN_STOPPED", "ok", "Stop during like phase");
                break;
            }
            ctrl.bringSpotifyToForegroundQuick();
            ctrl.tryDismissBlockingOverlays();
            int got = ctrl.likeSongsFromCurrentList(1);
            if (got > 0) {
                likesDone = Math.min(nLike, likesDone + got);
                sendProgress(commandId, sessionId, "INTERACT_LIKE_OK", "ok",
                        "like first-phase ok (" + likesDone + "/" + nLike + ")");
            } else {
                sendProgress(commandId, sessionId, "INTERACT_LIKE_FAIL", "failed",
                        "like first-phase miss" + likeFailSuffix(ctrl), likeFailReasonCode(ctrl));
            }
            if (likesDone >= nLike) break;
            long gap = 2200L + (long) (Math.random() * 2500L);
            if (!interruptibleSleepMsForSession(gap, sessionId)) {
                break;
            }
        }

        // Phase B: run skip schedule in the remaining listen window.
        long skipStart = System.currentTimeMillis();
        List<TimedInteract> schedule = buildRandomSkipSchedule(skipStart, sessionEndMs, nSkip);
        int idx = 0;
        boolean announcedAllInteractionsDone = false;
        while (System.currentTimeMillis() < sessionEndMs) {
            if (isExplicitStopForThisSession(sessionId)) {
                sendProgress(commandId, sessionId, "LISTEN_STOPPED", "ok", "Stop during scheduled listen");
                break;
            }
            if (!announcedAllInteractionsDone && likesDone >= nLike && idx >= schedule.size()) {
                announcedAllInteractionsDone = true;
                sendProgress(commandId, sessionId, "INTERACT_ALL_DONE", "ok",
                        "All like/skip interactions done; waiting for session end");
                long left = Math.max(0L, sessionEndMs - System.currentTimeMillis());
                if (left > 0) {
                    interruptibleSleepMsForSession(left, sessionId);
                }
                break;
            }
            long now = System.currentTimeMillis();
            if (idx < schedule.size() && now >= schedule.get(idx).fireAtEpochMs) {
                TimedInteract ev = schedule.get(idx++);
                ctrl.bringSpotifyToForegroundQuick();
                long preJitter = 180L + (long) (Math.random() * 520L);
                if (!interruptibleSleepMsForSession(preJitter, sessionId)) {
                    break;
                }
                ctrl.tryDismissBlockingOverlays();
                if (!ctrl.waitForBlockingOverlayToClear(2200L)) {
                    sendProgress(commandId, sessionId, "INTERACT_SKIP_WAIT_POPUP", "ok",
                            "Popup still visible; skip delayed until next slot");
                    continue;
                }
                if (ctrl.skipToNextTrackLight()) {
                    sendProgress(commandId, sessionId, "INTERACT_SKIP_OK", "ok",
                            "skip next (queue, random time — no search)");
                } else {
                    sendProgress(commandId, sessionId, "INTERACT_SKIP_FAIL", "failed",
                            "skip failed", "UI_ELEMENT_NOT_FOUND");
                }
                long cool = 380L + (long) (Math.random() * 1100L);
                interruptibleSleepMsForSession(cool, sessionId);
                continue;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (nLike > 0) {
            if (likesDone >= nLike) {
                sendProgress(commandId, sessionId, "INTERACT_LIKE_DONE", "ok",
                        "Liked target reached in listen window: " + likesDone + "/" + nLike);
            } else {
                sendProgress(commandId, sessionId, "INTERACT_LIKE_PARTIAL", "failed",
                        "Liked only " + likesDone + "/" + nLike + " in listen window", "LIKE_TARGET_NOT_REACHED");
            }
        }
    }

    private boolean interruptibleSleepMs(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (cancelSessionRequested.get()) {
                return false;
            }
            long left = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(250L, Math.max(50L, left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !cancelSessionRequested.get();
    }

    /**
     * Like {@link #interruptibleSleepMs} but only aborts when {@link #isSessionStopRequested(int)} is true
     * (so a STOP for another session_id does not disturb this run).
     */
    private boolean interruptibleSleepMsForSession(long ms, int sessionId) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            if (isSessionStopRequested(sessionId)) {
                return false;
            }
            long left = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(250L, Math.max(50L, left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isSessionStopRequested(sessionId);
    }

    /**
     * Frontend STOP: backend sends session_id. If missing ({@code -1}), only {@link #activeSessionId} run stops.
     */
    private boolean isSessionStopRequested(int sessionId) {
        if (!cancelSessionRequested.get()) {
            return false;
        }
        int sid = cancelForSessionId;
        if (sid != -1) {
            return sid == sessionId;
        }
        return sessionId == activeSessionId;
    }

    /**
     * Listen phase only: honor STOP only when it targets this {@code sessionId}.
     * Wildcard ({@code cancelForSessionId == -1} + {@code activeSessionId} match) is ignored here so stale
     * global cancel cannot end listening early; phases 1–2 still use {@link #isSessionStopRequested}.
     */
    private boolean isExplicitStopForThisSession(int sessionId) {
        return cancelSessionRequested.get() && cancelForSessionId == sessionId;
    }

    private void wakeUiProcessForAccessibility() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra(MainActivity.EXTRA_WAKE_FOR_A11Y, true);
            startActivity(i);
            Log.i(TAG, "Launched MainActivity wake for accessibility binding");
        } catch (Exception e) {
            Log.e(TAG, "wakeUiProcessForAccessibility: " + e.getMessage());
        }
    }

    private void logAccessibilityDiagnostics(String phase) {
        String myServiceId = getPackageName() + "/" + MyAccessibilityService.class.getName();
        try {
            int secureFlag = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            String enabledServicesSetting = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean inSecureList = enabledServicesSetting != null && enabledServicesSetting.contains(myServiceId);

            AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
            boolean managerOn = am != null && am.isEnabled();

            ComponentName cn = new ComponentName(this, MyAccessibilityService.class);
            int compState = getPackageManager().getComponentEnabledSetting(cn);

            Log.w(TAG, "A11y diag [" + phase + "] ACCESSIBILITY_ENABLED=" + secureFlag
                    + " manager.isEnabled=" + managerOn
                    + " inSecureList=" + inSecureList
                    + " componentState=" + compState
                    + " pid=" + android.os.Process.myPid());

            if (am != null) {
                java.util.List<AccessibilityServiceInfo> enabledAll =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                Log.w(TAG, "A11y diag enabledServiceListSize=" + (enabledAll != null ? enabledAll.size() : -1));
            }
        } catch (Exception e) {
            Log.e(TAG, "A11y diag failed: " + e.getMessage());
        }
    }

    private String buildAccessibilityFailureMessage() {
        String myServiceId = getPackageName() + "/" + MyAccessibilityService.class.getName();
        String list = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean inList = list != null && list.contains(myServiceId);
        return "Service never bound (instance null). Secure list shows SpotifyBot=" + inList
                + ". Fix: reinstall app → open SpotifyBot from launcher → Accessibility OFF/ON for SpotifyBot → "
                + "Battery unrestricted → (Android 13+) App info ⋮ Allow restricted settings. "
                + "Logcat must show \"Accessibility onServiceConnected\".";
    }

    /**
     * Up to ~5 min: wake UI periodically; open system Accessibility screen once so user can re-toggle.
     */
    private boolean ensureAccessibilityConnected(String commandId, int sessionId) {
        if (MyAccessibilityService.getInstance() != null) {
            return true;
        }
        logAccessibilityDiagnostics("long-wait-start");
        sendProgress(commandId, sessionId, "ACCESSIBILITY_WAIT", "ok",
                "Accessibility not bound yet; waiting (Spotify will open after this)…");

        boolean ready = false;
        boolean openedSettings = false;
        for (int i = 0; i < 600; i++) {
            if (isSessionStopRequested(sessionId)) {
                sendProgress(commandId, sessionId, "SESSION_STOPPED", "ok", "Cancelled during accessibility wait");
                return false;
            }
            if (i == 0 || i == 10) {
                wakeUiProcessForAccessibility();
            }
            if (i > 0 && i % 60 == 0) {
                wakeUiProcessForAccessibility();
            }

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (MyAccessibilityService.getInstance() != null) {
                ready = true;
                break;
            }
            if (i == 20 && !openedSettings) {
                openedSettings = true;
                try {
                    Log.w(TAG, "Still not ready; opening Accessibility settings…");
                    Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
        if (!ready) {
            logAccessibilityDiagnostics("long-wait-give-up");
            sendProgress(commandId, sessionId, "ERROR", "failed", buildAccessibilityFailureMessage());
            return false;
        }
        return true;
    }

    /**
     * Short wait with UI wake — {@link #ensureAccessibilityConnected} runs if still null.
     */
    private boolean waitForAccessibilityInstanceShort(String commandId, int sessionId) {
        if (MyAccessibilityService.getInstance() != null) {
            return true;
        }
        logAccessibilityDiagnostics("short-wait-start");
        wakeUiProcessForAccessibility();
        interruptibleSleepMsForSession(700, sessionId);

        sendProgress(commandId, sessionId, "ACCESSIBILITY_WAIT", "ok",
                "Waiting for SpotifyBot accessibility service to bind…");
        final int polls = 80;
        for (int i = 0; i < polls; i++) {
            if (isSessionStopRequested(sessionId)) {
                sendProgress(commandId, sessionId, "SESSION_STOPPED", "ok", "Cancelled while waiting for accessibility");
                return false;
            }
            if (MyAccessibilityService.getInstance() != null) {
                Log.i(TAG, "Accessibility ready (short wait, poll " + (i + 1) + "/" + polls + ")");
                return true;
            }
            if (i == 40) {
                wakeUiProcessForAccessibility();
            }
            if (!interruptibleSleepMsForSession(250, sessionId)) {
                return false;
            }
        }
        Log.w(TAG, "Accessibility still null after short wait; long wait runs before Spotify opens");
        return true;
    }

    /** Step 1: open Spotify; retry until OK or give up. */
    private boolean stepOpenSpotifyWithRetries(SpotifyController ctrl, String commandId, int sessionId) {
        sendProgress(commandId, sessionId, "OPEN_STARTED", "ok", "Opening Spotify (retries enabled)");
        final int maxAttempts = 8;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (isSessionStopRequested(sessionId)) {
                sendProgress(commandId, sessionId, "SESSION_STOPPED", "ok", "Cancelled during open Spotify");
                return false;
            }
            if (ctrl.openSpotify()) {
                sendProgress(commandId, sessionId, "OPEN_OK", "ok", "Spotify open OK (attempt " + attempt + ")");
                return true;
            }
            sendProgress(commandId, sessionId, "OPEN_RETRY", "ok", "Open Spotify failed, retry " + attempt + "/" + maxAttempts);
            if (!interruptibleSleepMsForSession(2000, sessionId)) {
                return false;
            }
        }
        sendProgress(commandId, sessionId, "OPEN_FAILED", "failed", "Could not open Spotify after " + maxAttempts + " attempts");
        return false;
    }

    /** Step 2: tap Search tab; retry until OK — phase 1 does not continue without this. */
    private boolean stepClickSearchTabWithRetries(SpotifyController ctrl, String commandId, int sessionId) {
        sendProgress(commandId, sessionId, "SEARCH_TAB_STARTED", "ok", "Clicking Search tab (retries until OK)");
        final int maxAttempts = 20;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (isSessionStopRequested(sessionId)) {
                sendProgress(commandId, sessionId, "SESSION_STOPPED", "ok", "Cancelled during search tab");
                return false;
            }
            if (attempt == 1) {
                long humanShiftDelay = 2000L + (long) (Math.random() * 3000L); // 2..5s
                if (!interruptibleSleepMsForSession(humanShiftDelay, sessionId)) {
                    return false;
                }
            }
            if (ctrl.clickSearchTab()) {
                sendProgress(commandId, sessionId, "SEARCH_TAB_OK", "ok",
                        "Search tab OK (attempt " + attempt + ") via content_desc:search, tab");
                return true;
            }
            sendProgress(commandId, sessionId, "SEARCH_TAB_RETRY", "ok",
                    "Search tab miss, retry " + attempt + "/" + maxAttempts);
            if (!interruptibleSleepMsForSession(900, sessionId)) {
                return false;
            }
        }
        sendProgress(commandId, sessionId, "SEARCH_TAB_FAILED", "failed",
                "Could not open Search after " + maxAttempts + " attempts");
        return false;
    }

    /** Step 3: type session query into com.spotify.music:id/query (retries until field appears). */
    private boolean stepTypeSearchQueryWithRetries(SpotifyController ctrl, String commandId, int sessionId, String query) {
        sendProgress(commandId, sessionId, "SEARCH_QUERY_STARTED", "ok",
                "Typing query into com.spotify.music:id/query");
        final int maxAttempts = 20;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (isSessionStopRequested(sessionId)) {
                sendProgress(commandId, sessionId, "SESSION_STOPPED", "ok", "Cancelled during search query");
                return false;
            }
            if (ctrl.typeSearchQuery(query)) {
                sendProgress(commandId, sessionId, "SEARCH_QUERY_OK", "ok",
                        "Query typed OK (attempt " + attempt + ") id=com.spotify.music:id/query");
                return true;
            }
            sendProgress(commandId, sessionId, "SEARCH_QUERY_RETRY", "ok",
                    "Query field miss, retry " + attempt + "/" + maxAttempts);
            if (!interruptibleSleepMsForSession(600, sessionId)) {
                return false;
            }
        }
        sendProgress(commandId, sessionId, "SEARCH_QUERY_FAILED", "failed",
                "Could not set text on query field after " + maxAttempts + " attempts");
        return false;
    }

    /** After query text: IME enter → keyboard Search key → android:id/search_go_btn */
    private boolean stepSubmitSearchWithRetries(SpotifyController ctrl, String commandId, int sessionId) {
        sendProgress(commandId, sessionId, "SEARCH_SUBMIT_STARTED", "ok",
                "Submitting search (IME / keyboard Search / search_go_btn)");
        final int maxAttempts = 12;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (isSessionStopRequested(sessionId)) {
                sendProgress(commandId, sessionId, "SESSION_STOPPED", "ok", "Cancelled during search submit");
                return false;
            }
            if (ctrl.submitSearchQuery()) {
                String how = ctrl.getLastSearchSubmitUsed() != null ? ctrl.getLastSearchSubmitUsed() : "unknown";
                sendProgress(commandId, sessionId, "SEARCH_SUBMIT_OK", "ok",
                        "Search submitted (attempt " + attempt + ") via " + how);
                return true;
            }
            sendProgress(commandId, sessionId, "SEARCH_SUBMIT_RETRY", "ok",
                    "Submit miss, retry " + attempt + "/" + maxAttempts);
            if (!interruptibleSleepMsForSession(550, sessionId)) {
                return false;
            }
        }
        sendProgress(commandId, sessionId, "SEARCH_SUBMIT_FAILED", "failed",
                "Could not submit search after " + maxAttempts + " attempts");
        return false;
    }

    /**
     * Phase 2: single pass (no outer retries). Re-running the full flow was re-tapping Songs / rows and felt like starting over.
     */
    private boolean stepPlayFromResults(SpotifyController ctrl, String commandId, int sessionId, String playType, String query) {
        sendProgress(commandId, sessionId, "PLAY_RESULTS_STARTED", "ok",
                "Opening result and starting playback for type=" + playType);
        final int maxAttempts = 6;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (isSessionStopRequested(sessionId)) {
                sendProgress(commandId, sessionId, "SESSION_STOPPED", "ok", "Cancelled during play from results");
                return false;
            }
            if (ctrl.isSessionDeadlinePassed()) {
                sendProgress(commandId, sessionId, "SESSION_PLAY_TTL", "ok",
                        "Session wall-clock TTL reached before play phase");
                return false;
            }
            if (attempt > 1) {
                sendProgress(commandId, sessionId, "PLAY_RESULTS_RETRY", "ok",
                        "Retry " + attempt + "/" + maxAttempts + " (longer list load / UI settle)");
            }
            ctrl.preparePlayFromResultsRetry(attempt - 1);
            if (ctrl.playFromSearchResults(playType, query)) {
                String how = ctrl.getLastPlayResultUsed() != null ? ctrl.getLastPlayResultUsed() : "unknown";
                sendProgress(commandId, sessionId, "PLAY_RESULTS_OK", "ok",
                        "Playback started via " + how + (attempt > 1 ? (" (attempt " + attempt + ")") : ""));
                return true;
            }
            if (attempt < maxAttempts && !interruptibleSleepMsForSession(1500, sessionId)) {
                return false;
            }
        }
        sendProgress(commandId, sessionId, "PLAY_RESULTS_FAILED", "failed",
                "Could not start playback from results after " + maxAttempts + " attempts", "PLAYBACK_START_FAILED");
        return false;
    }

    /**
     * After playback (or immediately if {@code waitMs}==0): wait until absolute end time or explicit STOP for this session;
     * then pause + close Spotify. Listen uses wall-clock end epoch (not re-derived) so nothing can shrink the window mid-wait.
     */
    private void stepSessionTimerPauseAndClose(SpotifyController ctrl, String commandId, int sessionId, long waitMs) {
        final long now0 = System.currentTimeMillis();
        final long listenUntilEpoch = now0 + Math.max(0L, waitMs);
        Log.i(TAG, "TEARDOWN phase start sessionId=" + sessionId + " waitMs=" + waitMs
                + " listenUntilEpoch=" + listenUntilEpoch + " activeSessionId=" + activeSessionId);
        if (waitMs > 0) {
            sendProgress(commandId, sessionId, "SESSION_TIMER_STARTED", "ok",
                    "Listening until wall clock (full " + (waitMs / 1000) + " s) or STOP for session "
                            + sessionId + " only, then pause + close Spotify");
        } else {
            sendProgress(commandId, sessionId, "SESSION_TIMER_STARTED", "ok",
                    "Teardown (listen window was used for scheduled skips, or zero wait)");
        }
        boolean byStopDuringListen = false;
        if (waitMs > 0) {
            while (System.currentTimeMillis() < listenUntilEpoch) {
                if (isExplicitStopForThisSession(sessionId)) {
                    byStopDuringListen = true;
                    Log.w(TAG, "LISTEN ended early: explicit STOP session_id=" + sessionId);
                    break;
                }
                long left = listenUntilEpoch - System.currentTimeMillis();
                try {
                    Thread.sleep(Math.min(500L, Math.max(1L, left)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "LISTEN sleep interrupted");
                    break;
                }
            }
        }
        boolean timeUp = waitMs <= 0 || System.currentTimeMillis() >= listenUntilEpoch;
        boolean byStop = byStopDuringListen;
        if (timeUp && !byStop) {
            sendProgress(commandId, sessionId, "SESSION_TIMER_ENDED", "ok",
                    "Listening period finished; teardown starting");
        } else if (byStop) {
            sendProgress(commandId, sessionId, "SESSION_STOP_FOR_TEARDOWN", "ok", "Stop requested; teardown starting");
        }

        sendProgress(commandId, sessionId, "SPOTIFY_PAUSE_STARTED", "ok", "Pausing Spotify playback");
        boolean pauseOk = false;
        for (int attempt = 1; attempt <= 8; attempt++) {
            if (ctrl.pauseSpotifyPlayback()) {
                pauseOk = true;
                sendProgress(commandId, sessionId, "SPOTIFY_PAUSE_OK", "ok", "Pause OK (attempt " + attempt + ")");
                break;
            }
            if (!interruptibleSleepMsForSession(450, sessionId)) {
                break;
            }
        }
        if (!pauseOk) {
            sendProgress(commandId, sessionId, "SPOTIFY_PAUSE_WARN", "ok", "Pause not confirmed; continuing to close");
        }

        interruptibleSleepMsForSession(600, sessionId);

        sendProgress(commandId, sessionId, "SPOTIFY_CLOSE_STARTED", "ok", "Closing Spotify app");
        boolean closed = ctrl.closeSpotifyApp();
        sendProgress(commandId, sessionId, closed ? "SPOTIFY_CLOSE_OK" : "SPOTIFY_CLOSE_WARN", "ok",
                closed ? "killBackgroundProcesses sent" : "close may be incomplete on this OEM");

        String reason = byStop ? "stopped from frontend" : (timeUp ? "listening timer finished" : "ended");
        sendProgress(commandId, sessionId, "SESSION_COMPLETE", "ok",
                "Session ended (" + reason + "); pause + close attempted");
        Log.d(TAG, "Session teardown done: " + reason);

        cancelSessionRequested.set(false);
        cancelForSessionId = -1;
    }

    /**
     * Backend progress: command_id + session_id + step + status + message (+ optional reason_code).
     */
    private void sendProgress(String commandId, int sessionId, String step, String status, String message) {
        sendProgress(commandId, sessionId, step, status, message, null);
    }

    private void sendProgress(String commandId, int sessionId, String step, String status, String message,
                              String reasonCode) {
        try {
            JSONObject progress = new JSONObject();
            progress.put("type", "PROGRESS");
            progress.put("command_id", commandId);
            progress.put("session_id", sessionId);
            progress.put("step", step);
            progress.put("status", status);
            progress.put("message", message != null ? message : "");
            if (reasonCode != null && !reasonCode.isEmpty()) {
                progress.put("reason_code", reasonCode);
            }
            if (wsClient != null && wsClient.isOpen()) {
                wsClient.send(progress.toString());
            }
            String tail = (message != null && !message.isEmpty()) ? (" | " + message) : "";
            Log.d(TAG, "Progress: " + step + " -> " + status + tail);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send progress: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelScheduledReconnect();
        synchronized (this) {
            wsConnectExecutorShutdown = true;
            if (wsConnectExecutor != null) {
                wsConnectExecutor.shutdownNow();
                wsConnectExecutor = null;
            }
        }
        if (wsClient != null) {
            wsClient.close();
            wsClient = null;
        }
    }
}
