package com.example.spotifybot;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Minimal Spotify Web API client for saving tracks to the user library.
 * Requires scope: {@code user-library-modify}. Uses {@link HttpURLConnection} only.
 */
final class SpotifyWebApiClient {

    private static final String TAG = "SpotifyWebApi";
    private static final String API_BASE = "https://api.spotify.com/v1";

    private SpotifyWebApiClient() {
    }

    static final class ParsedUri {
        final String type; // "playlist" | "artist"
        final String id;

        ParsedUri(String type, String id) {
            this.type = type;
            this.id = id;
        }
    }

    /**
     * Accepts {@code spotify:playlist:ID}, {@code spotify:artist:ID}, or bare ID with type implied by caller.
     */
    static ParsedUri parseSpotifyUri(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String lower = s.toLowerCase(Locale.ROOT);
        if (lower.startsWith("spotify:playlist:")) {
            String id = s.substring("spotify:playlist:".length()).trim();
            id = stripQueryFragment(id);
            return id.isEmpty() ? null : new ParsedUri("playlist", id);
        }
        if (lower.startsWith("spotify:artist:")) {
            String id = s.substring("spotify:artist:".length()).trim();
            id = stripQueryFragment(id);
            return id.isEmpty() ? null : new ParsedUri("artist", id);
        }
        // https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=...
        int p = lower.indexOf("open.spotify.com/playlist/");
        if (p >= 0) {
            String rest = s.substring(p + "open.spotify.com/playlist/".length());
            String id = stripQueryFragment(rest);
            int slash = id.indexOf('/');
            if (slash > 0) {
                id = id.substring(0, slash);
            }
            return id.isEmpty() ? null : new ParsedUri("playlist", id);
        }
        p = lower.indexOf("open.spotify.com/artist/");
        if (p >= 0) {
            String rest = s.substring(p + "open.spotify.com/artist/".length());
            String id = stripQueryFragment(rest);
            int slash = id.indexOf('/');
            if (slash > 0) {
                id = id.substring(0, slash);
            }
            return id.isEmpty() ? null : new ParsedUri("artist", id);
        }
        return null;
    }

    private static String stripQueryFragment(String id) {
        int q = id.indexOf('?');
        int h = id.indexOf('#');
        int cut = id.length();
        if (q >= 0) {
            cut = Math.min(cut, q);
        }
        if (h >= 0) {
            cut = Math.min(cut, h);
        }
        return id.substring(0, cut).trim();
    }

    static List<String> fetchPlaylistTrackIds(String accessToken, String playlistId, int maxTracks)
            throws Exception {
        Set<String> out = new LinkedHashSet<>();
        int offset = 0;
        final int limit = 100;
        while (out.size() < maxTracks) {
            String url = API_BASE + "/playlists/" + playlistId + "/tracks?limit=" + limit + "&offset=" + offset;
            String json = httpGet(url, accessToken);
            JSONObject root = new JSONObject(json);
            JSONArray items = root.optJSONArray("items");
            if (items == null || items.length() == 0) {
                break;
            }
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null || item.isNull("track")) {
                    continue;
                }
                JSONObject track = item.optJSONObject("track");
                if (track == null) {
                    continue;
                }
                String id = track.optString("id", "");
                if (!id.isEmpty()) {
                    out.add(id);
                    if (out.size() >= maxTracks) {
                        break;
                    }
                }
            }
            if (items.length() < limit) {
                break;
            }
            offset += limit;
        }
        return new ArrayList<>(out);
    }

    static List<String> fetchArtistTopTrackIds(String accessToken, String artistId, String market)
            throws Exception {
        String m = market != null && !market.isEmpty() ? market : "US";
        String url = API_BASE + "/artists/" + artistId + "/top-tracks?market=" + m;
        String json = httpGet(url, accessToken);
        JSONObject root = new JSONObject(json);
        JSONArray tracks = root.optJSONArray("tracks");
        List<String> out = new ArrayList<>();
        if (tracks == null) {
            return out;
        }
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject tr = tracks.optJSONObject(i);
            if (tr == null) {
                continue;
            }
            String id = tr.optString("id", "");
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Saves up to 50 IDs per request (chunks automatically).
     */
    static boolean saveTracksToLibrary(String accessToken, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return true;
        }
        int from = 0;
        while (from < ids.size()) {
            int to = Math.min(from + 50, ids.size());
            List<String> chunk = ids.subList(from, to);
            if (!saveTracksChunk(accessToken, chunk)) {
                return false;
            }
            from = to;
        }
        return true;
    }

    private static boolean saveTracksChunk(String accessToken, List<String> chunk) {
        try {
            URL url = new URL(API_BASE + "/me/tracks");
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(20_000);
            c.setReadTimeout(25_000);
            c.setRequestMethod("PUT");
            c.setRequestProperty("Authorization", "Bearer " + accessToken);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            JSONObject body = new JSONObject();
            JSONArray arr = new JSONArray();
            for (String id : chunk) {
                arr.put(id);
            }
            body.put("ids", arr);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(bytes);
            }
            int code = c.getResponseCode();
            if (code == 200 || code == 204) {
                return true;
            }
            String err = readStream(c.getErrorStream() != null ? c.getErrorStream() : c.getInputStream());
            Log.w(TAG, "saveTracks HTTP " + code + " " + err);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "saveTracks: " + e.getMessage());
            return false;
        }
    }

    private static String httpGet(String urlStr, String accessToken) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(20_000);
        c.setReadTimeout(25_000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Authorization", "Bearer " + accessToken);
        int code = c.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readStream(in);
        if (code < 200 || code >= 300) {
            throw new Exception("GET " + urlStr + " HTTP " + code + " " + body);
        }
        return body;
    }

    private static String readStream(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
