package com.example.spotifybot;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Debug-mode NDJSON ingest (session ad785a). Device must reach host ingest, e.g.
 * {@code adb reverse tcp:7790 tcp:7790}.
 */
final class DebugSessionLog {
    private static final String SESSION = "ad785a";
    private static final String ENDPOINT =
            "http://127.0.0.1:7790/ingest/f343bf9a-fd0b-4b07-94bc-8b35df09dde4";

    private DebugSessionLog() {}

    static void agentLog(String hypothesisId, String location, String message, JSONObject data) {
        new Thread(() -> post(hypothesisId, location, message, data), "agent-debug-log").start();
    }

    private static void post(String hypothesisId, String location, String message, JSONObject data) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("sessionId", SESSION);
            payload.put("timestamp", System.currentTimeMillis());
            payload.put("hypothesisId", hypothesisId);
            payload.put("location", location);
            payload.put("message", message);
            payload.put("data", data != null ? data : new JSONObject());
            URL url = new URL(ENDPOINT);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("X-Debug-Session-Id", SESSION);
            c.setDoOutput(true);
            c.setConnectTimeout(2500);
            c.setReadTimeout(2500);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            OutputStream os = c.getOutputStream();
            os.write(body);
            os.close();
            try {
                c.getInputStream().close();
            } catch (Exception ignored) {
            }
            c.disconnect();
        } catch (Throwable ignored) {
        }
    }
}
