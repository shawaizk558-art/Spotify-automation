package com.example.spotifybot;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 1: open Spotify → Search tab (content-desc) → type query → submit search (IME / keyboard Search / go btn).
 */
public class SpotifyController {

    private static final String TAG = "SpotifyController";
    private static final String SPOTIFY_PACKAGE = "com.spotify.music";
    /** Fallback when full Spotify app is not installed. */
    private static final String SPOTIFY_LITE_PACKAGE = "com.spotify.lite";
    private static final int ACCESSIBILITY_ACTION_IME_ENTER = 0x01020054;

    // UI Element IDs
    private static final String SEARCH_TAB_CONTENT_DESC_SUBSTRING = "search, tab";
    private static final String ID_QUERY = SPOTIFY_PACKAGE + ":id/query";
    private static final String ID_BUTTON_PLAY_PAUSE = SPOTIFY_PACKAGE + ":id/button_play_and_pause";
    private static final String ID_CONTEXT_MENU_BUTTON = SPOTIFY_PACKAGE + ":id/context_menu_button";
    private static final String ID_FOLLOW_BUTTON = SPOTIFY_PACKAGE + ":id/follow_button";
    private static final String ID_RESULT_TITLE = SPOTIFY_PACKAGE + ":id/title";
    private static final String ID_RESULT_SUBTITLE = SPOTIFY_PACKAGE + ":id/subtitle";
    /** e.g. content-desc "Open context menu for 52 Bars" (Spotify song rows). */
    private static final String DESC_OPEN_CONTEXT_MENU_PREFIX = "open context menu for";
    /** Song screen 3-dots desc, e.g. "More options for song <name>". */
    private static final String DESC_MORE_OPTIONS_FOR_SONG_PREFIX = "more options for song";
    /**
     * If any of these are visible, treat Spotify's track overflow sheet as open.
     * Used so we only press BACK when the menu is still showing — after Like, Spotify often closes
     * the sheet itself; an extra BACK would pop the playlist and land on search results.
     */
    private static final String[] OVERFLOW_MENU_OPEN_NEEDLES = {
            "add to liked songs",
            "add to your liked songs",
            "save to your liked songs",
            "like song",
            "remove from liked songs",
            "remove from your liked songs",
            "add to playlist",
            "go to album",
            "go to artist",
            "view album",
            "view artist",
            "add to queue",
            "go to song radio",
            "sleep timer",
            "report",
    };
    private static final String DESC_ADD_PLAYLIST_TO_LIBRARY = "add playlist to your library";
    /** Playlist pehle se library mein ho to yeh strings (desc/text) aksar dikhte hain — tap mat karo. */
    private static final String[] PLAYLIST_SAVED_UI_NEEDLES = {
            "remove from your library",
            "remove playlist from your library",
            "remove playlist from",
            "remove from library",
    };
    private static final Pattern WORD_FOLLOWING = Pattern.compile("\\bfollowing\\b", Pattern.CASE_INSENSITIVE);
    private static final String[] RESULT_ROW_IDS = {
            SPOTIFY_PACKAGE + ":id/row_root",
            SPOTIFY_PACKAGE + ":id/result_row",
            SPOTIFY_PACKAGE + ":id/track_row",
            SPOTIFY_PACKAGE + ":id/entity_row",
            SPOTIFY_PACKAGE + ":id/image_row"
    };

    private final Context context;
    private AtomicBoolean cancelToken;
    private long sessionDeadlineMs = Long.MAX_VALUE;
    private volatile String lastSearchSubmitUsed;
    private volatile String lastPlayResultUsed;
    private volatile String currentPlayType = "";
    private volatile String lastSkipPickedTitle = "";
    private volatile long lastSkipPickedAtMs = 0L;

    /** Bearer token with {@code user-library-modify}; from {@code EXECUTE_SESSION.spotify_access_token}. */
    private volatile String spotifyAccessToken = "";
    /** e.g. {@code spotify:playlist:...} or {@code spotify:artist:...}; from {@code EXECUTE_SESSION.spotify_context_uri}. */
    private volatile String spotifyContextUri = "";

    private final Object apiLikeLock = new Object();
    private boolean apiLikePoolInitialized;
    private List<String> apiShuffledTrackIds;
    private int apiLikePoolNextIndex;
    /** Prevent reopening the same row context-menu repeatedly in UI-like mode. */
    private final Set<String> uiLikeProcessedTargetKeys = new HashSet<>();

    /** Last failure from {@link #likeSongsFromCurrentList}; for BotService progress detail. */
    private volatile String lastLikeFailureReason = "";

    public SpotifyController(Context context) {
        this.context = context;
    }

    public void setCancelToken(AtomicBoolean token) {
        this.cancelToken = token;
    }

    public void setSessionDeadlineMs(long deadlineEpochMs) {
        this.sessionDeadlineMs = deadlineEpochMs;
    }

    public void setCurrentPlayType(String playTypeRaw) {
        String t = playTypeRaw != null ? playTypeRaw.trim().toLowerCase(Locale.ROOT) : "";
        this.currentPlayType = t;
        uiLikeProcessedTargetKeys.clear();
    }

    /**
     * Resets API like pool. Call when starting a session; backend should send token + context URI for artist/playlist likes.
     */
    public void setSpotifyApiConfig(String accessToken, String contextUri) {
        this.spotifyAccessToken = accessToken != null ? accessToken.trim() : "";
        this.spotifyContextUri = contextUri != null ? contextUri.trim() : "";
        synchronized (apiLikeLock) {
            apiLikePoolInitialized = false;
            apiShuffledTrackIds = null;
            apiLikePoolNextIndex = 0;
        }
    }

    public boolean isSessionDeadlinePassed() {
        return System.currentTimeMillis() >= sessionDeadlineMs;
    }

    public String getLastSearchSubmitUsed() {
        return lastSearchSubmitUsed;
    }

    public String getLastPlayResultUsed() {
        return lastPlayResultUsed;
    }

    /** Non-empty when the last {@link #likeSongsFromCurrentList} call returned 0. */
    public String getLastLikeFailureReason() {
        return lastLikeFailureReason != null ? lastLikeFailureReason : "";
    }

    public boolean hasSpotifyApiLikeConfig() {
        String t = spotifyAccessToken != null ? spotifyAccessToken.trim() : "";
        String u = spotifyContextUri != null ? spotifyContextUri.trim() : "";
        return !t.isEmpty() && !u.isEmpty();
    }

    // ==================== UTILITY METHODS ====================

    private AccessibilityNodeInfo getRoot() {
        MyAccessibilityService service = MyAccessibilityService.getInstance();
        if (service == null) {
            Log.e(TAG, "Accessibility Service not connected");
            return null;
        }
        return service.getRootNode();
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isCancelled() {
        return cancelToken != null && cancelToken.get();
    }

    private boolean sleepUnlessCancelled(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (isCancelled() || isSessionDeadlinePassed()) {
                return false;
            }
            long left = end - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(200L, Math.max(1L, left)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !isCancelled() && !isSessionDeadlinePassed();
    }

    private boolean waitForRoot(int maxAttempts, long sleepMs) {
        for (int i = 0; i < maxAttempts; i++) {
            if (getRoot() != null) return true;
            sleep(sleepMs);
        }
        return getRoot() != null;
    }

    private boolean performClickOnNodeOrAncestor(AccessibilityNodeInfo node) {
        if (node == null) return false;
        AccessibilityNodeInfo n = node;
        for (int depth = 0; depth < 8 && n != null; depth++) {
            ensureNodeVisibleBeforeClick(n);
            if (n.isVisibleToUser() && n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    private void ensureNodeVisibleBeforeClick(AccessibilityNodeInfo node) {
        if (node == null || node.isVisibleToUser()) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
            sleep(220);
            if (node.isVisibleToUser()) return;
        }

        AccessibilityNodeInfo p = node.getParent();
        for (int depth = 0; depth < 8 && p != null; depth++) {
            if (p.isScrollable()) {
                boolean moved = p.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                if (!moved) {
                    moved = p.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
                }
                if (moved) {
                    sleep(280);
                    if (node.isVisibleToUser()) return;
                }
            }
            p = p.getParent();
        }
    }

    private AccessibilityNodeInfo findNodeByProperty(AccessibilityNodeInfo root, String searchText, boolean useContentDesc) {
        if (root == null || searchText == null) return null;
        String needle = searchText.toLowerCase();

        CharSequence value = useContentDesc ? root.getContentDescription() : root.getText();
        if (value != null && value.toString().toLowerCase().contains(needle)) {
            return root;
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo found = findNodeByProperty(child, searchText, useContentDesc);
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickContentDescContaining(String substring) {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;
        AccessibilityNodeInfo target = findNodeByProperty(root, substring, true);
        if (target == null) return false;
        if (performClickOnNodeOrAncestor(target)) {
            Log.d(TAG, "Clicked content-desc containing: " + substring);
            return true;
        }
        return false;
    }

    private static AccessibilityNodeInfo takeFirstRecycleRest(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        AccessibilityNodeInfo keep = null;
        for (AccessibilityNodeInfo n : nodes) {
            if (n == null) continue;
            if (keep == null) {
                keep = n;
            } else {
                n.recycle();
            }
        }
        return keep;
    }

    private static void recycleList(List<AccessibilityNodeInfo> nodes) {
        if (nodes == null) return;
        for (AccessibilityNodeInfo n : nodes) {
            if (n != null) n.recycle();
        }
    }

    private static boolean isSpotifyNode(AccessibilityNodeInfo n) {
        CharSequence pkg = n.getPackageName();
        return pkg != null && SPOTIFY_PACKAGE.contentEquals(pkg);
    }

    private int dp(int d) {
        return Math.round(d * context.getResources().getDisplayMetrics().density);
    }

    private String collectTextRecursive(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        CharSequence t = node.getText();
        if (t != null) sb.append(t);

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                sb.append(' ').append(collectTextRecursive(child));
            }
        }
        return sb.toString();
    }

    // ==================== PHASE 1: OPEN & SEARCH ====================

    /**
     * Resolves a launcher intent when {@link PackageManager#getLaunchIntentForPackage} is null
     * (OEM / disabled / dual-app edge cases).
     */
    private static Intent resolveLauncherIntent(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        Intent def = pm.getLaunchIntentForPackage(pkg);
        if (def != null) {
            return def;
        }
        Intent probe = new Intent(Intent.ACTION_MAIN);
        probe.addCategory(Intent.CATEGORY_LAUNCHER);
        probe.setPackage(pkg);
        List<ResolveInfo> list = pm.queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY);
        if (list == null || list.isEmpty()) {
            return null;
        }
        ResolveInfo ri = list.get(0);
        Intent out = new Intent(Intent.ACTION_MAIN);
        out.addCategory(Intent.CATEGORY_LAUNCHER);
        out.setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
        return out;
    }

    /**
     * Foreground service starting an activity: Android 10+ / 14+ may block or throw without BAL hints.
     */
    private void startExternalAppBal(Intent intent) {
        if ((intent.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        Bundle opts = null;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                ActivityOptions ao = ActivityOptions.makeBasic();
                int modeAllowed = 1; // MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                if (Build.VERSION.SDK_INT >= 35) {
                    try {
                        ActivityOptions.class.getMethod("setBackgroundActivityStartMode", int.class)
                                .invoke(ao, modeAllowed);
                    } catch (ReflectiveOperationException e) {
                        ActivityOptions.class.getMethod(
                                        "setPendingIntentBackgroundActivityStartMode", int.class)
                                .invoke(ao, modeAllowed);
                    }
                } else {
                    ActivityOptions.class.getMethod(
                                    "setPendingIntentBackgroundActivityStartMode", int.class)
                            .invoke(ao, modeAllowed);
                }
                opts = ao.toBundle();
            } catch (Throwable t) {
                Log.w(TAG, "ActivityOptions BAL not applied: " + t.getMessage());
            }
        }
        if (opts != null) {
            context.startActivity(intent, opts);
        } else {
            context.startActivity(intent);
        }
    }

    public boolean openSpotify() {
        Log.d(TAG, "Opening Spotify...");
        final String[] pkgs = new String[]{SPOTIFY_PACKAGE, SPOTIFY_LITE_PACKAGE};
        for (String pkg : pkgs) {
            try {
                Intent intent = resolveLauncherIntent(context, pkg);
                if (intent == null) {
                    Log.w(TAG, "No launcher for package " + pkg);
                    continue;
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startExternalAppBal(intent);
                sleep(3000);
                Log.i(TAG, "Spotify activity started (package=" + pkg + ")");
                return true;
            } catch (Throwable e) {
                Log.e(TAG, "Open failed pkg=" + pkg + " " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        Log.e(TAG, "Spotify not installed or launch blocked — need " + SPOTIFY_PACKAGE + " or " + SPOTIFY_LITE_PACKAGE);
        return false;
    }

    public boolean clickSearchTab() {
        Log.d(TAG, "Clicking search tab (content-desc ~ \"" + SEARCH_TAB_CONTENT_DESC_SUBSTRING + "\")...");
        waitForRoot(3, 400);

        if (clickContentDescContaining(SEARCH_TAB_CONTENT_DESC_SUBSTRING)) {
            sleep(1600);
            return true;
        }
        Log.e(TAG, "Search tab not found (content-desc)");
        return false;
    }

    public boolean typeSearchQuery(String raw) {
        String query = raw != null ? raw : "";
        waitForRoot(3, 400);

        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc == null) return false;

        sleep(800);

        AccessibilityNodeInfo input = findQueryField();
        if (input == null) {
            if (!clickSearchTab()) {
                Log.e(TAG, "Search entry not available for typing");
                logSearchUiDebug(svc);
                return false;
            }
            sleep(1200);
            input = findQueryField();
        }

        if (input == null) {
            Log.e(TAG, "query field not found: " + ID_QUERY);
            logSearchUiDebug(svc);
            return false;
        }

        input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        sleep(400);

        boolean ok = typeIntoQueryFieldHumanLike(input, query);
        sleep(500);

        if (ok) {
            Log.i(TAG, "QUERY_TYPING_USED " + ID_QUERY + " via=active_root");
        } else {
            Log.w(TAG, "ACTION_SET_TEXT failed for " + ID_QUERY);
            logSearchUiDebug(svc);
        }
        return ok;
    }

    private boolean typeIntoQueryFieldHumanLike(AccessibilityNodeInfo input, String query) {
        if (input == null) return false;
        String text = query != null ? query : "";

        Bundle clear = new Bundle();
        clear.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clear);
        sleep(120);

        if (text.isEmpty()) return true;

        StringBuilder built = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            built.append(text.charAt(i));
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, built.toString());
            boolean ok = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (!ok) {
                return false;
            }
            sleep(55 + (long) (Math.random() * 120L));
        }
        return true;
    }

    private AccessibilityNodeInfo findQueryField() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(ID_QUERY);
        return takeFirstRecycleRest(nodes);
    }

    private void logSearchUiDebug(MyAccessibilityService svc) {
        AccessibilityNodeInfo r = svc.getRootInActiveWindow();
        if (r == null) {
            Log.e(TAG, "QUERY_DEBUG activeRoot=null");
            return;
        }
        List<AccessibilityNodeInfo> q = r.findAccessibilityNodeInfosByViewId(ID_QUERY);
        boolean hasQ = q != null && !q.isEmpty();
        recycleList(q);
        Log.e(TAG, "QUERY_DEBUG pkg=" + r.getPackageName()
                + " class=" + r.getClassName()
                + " children=" + r.getChildCount()
                + " hasQueryId=" + hasQ);
    }

    public boolean submitSearchQuery() {
        lastSearchSubmitUsed = null;
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc == null) return false;

        sleep(400);

        AccessibilityNodeInfo activeRoot = svc.getRootInActiveWindow();
        AccessibilityNodeInfo input = null;
        boolean recycleInput = false;

        if (activeRoot != null) {
            List<AccessibilityNodeInfo> ql = activeRoot.findAccessibilityNodeInfosByViewId(ID_QUERY);
            input = takeFirstRecycleRest(ql);
            if (input != null) {
                recycleInput = true;
            } else {
                AccessibilityNodeInfo f = activeRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f != null && isSpotifyNode(f)) {
                    input = f;
                }
            }
        }

        try {
            if (input != null) {
                input.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                sleep(220);
            }

            // Method 1: IME action enter
            if (input != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (input.performAction(ACCESSIBILITY_ACTION_IME_ENTER)) {
                    lastSearchSubmitUsed = "ime_action_enter";
                    Log.i(TAG, "SEARCH_SUBMIT_USED " + lastSearchSubmitUsed);
                    sleep(900);
                    return true;
                }
            }

            // Method 2: Keyboard search key
            if (clickKeyboardSearchKey(svc)) {
                lastSearchSubmitUsed = "keyboard_search_key";
                Log.i(TAG, "SEARCH_SUBMIT_USED " + lastSearchSubmitUsed);
                sleep(900);
                return true;
            }

            // Method 3: Search go button
            AccessibilityNodeInfo spotifyRoot = svc.getRootInActiveWindow();
            if (spotifyRoot != null) {
                List<AccessibilityNodeInfo> goList = spotifyRoot.findAccessibilityNodeInfosByViewId("android:id/search_go_btn");
                AccessibilityNodeInfo go = takeFirstRecycleRest(goList);
                if (go != null) {
                    boolean c = performClickOnNodeOrAncestor(go);
                    go.recycle();
                    if (c) {
                        lastSearchSubmitUsed = "android_id_search_go_btn";
                        Log.i(TAG, "SEARCH_SUBMIT_USED " + lastSearchSubmitUsed);
                        sleep(900);
                        return true;
                    }
                }
            }

            Log.w(TAG, "SEARCH_SUBMIT_FAIL (ime, keyboard Search, search_go_btn all missed)");
            return false;
        } finally {
            if (recycleInput && input != null) {
                input.recycle();
            }
        }
    }

    private boolean clickKeyboardSearchKey(MyAccessibilityService svc) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        List<AccessibilityWindowInfo> wins = svc.getWindows();
        if (wins == null) return false;

        for (AccessibilityWindowInfo w : wins) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                w.recycle();
                continue;
            }
            AccessibilityNodeInfo r = w.getRoot();
            w.recycle();
            if (r == null) continue;

            boolean ok = findAndClickKeyboardSearchNode(r);
            r.recycle();
            if (ok) return true;
        }
        return false;
    }

    private boolean findAndClickKeyboardSearchNode(AccessibilityNodeInfo node) {
        if (node == null) return false;

        if (node.isClickable()) {
            StringBuilder sb = new StringBuilder();
            if (node.getContentDescription() != null) sb.append(node.getContentDescription());
            if (node.getText() != null) sb.append(' ').append(node.getText());

            String label = sb.toString().toLowerCase();
            if (label.contains("search")) {
                if (performClickOnNodeOrAncestor(node)) {
                    return true;
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (findAndClickKeyboardSearchNode(node.getChild(i))) return true;
        }
        return false;
    }

    // ==================== PHASE 2: PLAY FROM RESULTS ====================

    public void preparePlayFromResultsRetry(int zeroBasedAttemptIndex) {
        if (zeroBasedAttemptIndex <= 0) return;
        Log.i(TAG, "preparePlayFromResultsRetry back stack walk (attempt " + (zeroBasedAttemptIndex + 1) + ")");
        for (int i = 0; i < 8; i++) {
            if (findQueryField() != null) {
                sleep(700);
                return;
            }
            MyAccessibilityService svc = MyAccessibilityService.getInstance();
            if (svc != null) {
                svc.performGlobalBack();
            }
            sleep(500);
        }
    }

    public boolean playFromSearchResults(String playTypeRaw, String queryRaw) {
        String playType = playTypeRaw != null ? playTypeRaw.trim().toLowerCase() : "";
        if (playType.isEmpty()) playType = "artist";
        String query = queryRaw != null ? queryRaw.trim() : "";
        lastPlayResultUsed = null;

        boolean songish = "song".equals(playType) || "track".equals(playType);
        boolean artistish = "artist".equals(playType);
        long waitAfterSearch = songish ? 2400L : (artistish ? 3200L : 2000L);
        long waitAfterTab = songish ? 1600L : (artistish ? 1800L : 1200L);
        long waitAfterRowOpen = songish ? 3200L : (artistish ? 3500L : 2600L);
        long waitBeforeVerify = songish ? 4000L : (artistish ? 3800L : 2800L);

        if (isCancelled() || isSessionDeadlinePassed()) return false;
        if (!sleepUnlessCancelled(waitAfterSearch)) return false;

        selectResultsTabForType(playType);
        if (artistish) {
            sleepUnlessCancelled(400);
            selectResultsTabForType(playType);
        }

        if (isCancelled() || isSessionDeadlinePassed()) return false;
        if (!sleepUnlessCancelled(waitAfterTab)) return false;

        if (songish && tryInlineSongPlayFromResults(query)) {
            if (isCancelled() || isSessionDeadlinePassed()) return false;
            if (!sleepUnlessCancelled(waitBeforeVerify)) return false;
            boolean playing = verifyPlayingWithSettle();
            if (playing) {
                Log.i(TAG, "PLAY_RESULT_OK type=" + playType + " via=" + lastPlayResultUsed);
            } else {
                Log.w(TAG, "PLAY_RESULT_FAIL verifyPlaying=false after inline row play");
            }
            return playing;
        }

        boolean opened = false;
        for (int scanRound = 0; scanRound < 4 && !opened; scanRound++) {
            opened = clickResultRowForQuery(playType, query);
            if (!opened) {
                opened = clickRowViaTitleNodesGlobalScan(playType, query);
            }
            if (opened) break;
            AccessibilityNodeInfo sr = getRoot();
            if (sr == null) break;
            if (!scrollSearchResultsForward(sr, 0)) break;
            if (!sleepUnlessCancelled(700)) return false;
        }
        if (!opened) {
            Log.w(TAG, "PLAY_RESULT_FAIL no clickable result row (type=" + playType + ", query=" + query + ")");
            return false;
        }

        if (isCancelled() || isSessionDeadlinePassed()) return false;
        if (!sleepUnlessCancelled(waitAfterRowOpen)) return false;

        boolean started = startPlaybackControl(playType);
        if (!started) {
            Log.w(TAG, "PLAY_RESULT_FAIL playback control not found after opening result");
            return false;
        }

        if (isCancelled() || isSessionDeadlinePassed()) return false;
        if (!sleepUnlessCancelled(waitBeforeVerify)) return false;

        boolean playing = verifyPlayingWithSettle();
        if (playing) {
            Log.i(TAG, "PLAY_RESULT_OK type=" + playType + " via=" + lastPlayResultUsed);
        } else {
            Log.w(TAG, "PLAY_RESULT_FAIL verifyPlaying=false after detail Play (type=" + playType + ")");
        }
        return playing;
    }

    private boolean tryInlineSongPlayFromResults(String query) {
        if (query == null || query.trim().isEmpty()) return false;
        query = query.trim();
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;

        String[] words = query.split("\\s+");
        String firstWord = words.length > 0 ? words[0] : "";

        for (String rowId : RESULT_ROW_IDS) {
            List<AccessibilityNodeInfo> rows = root.findAccessibilityNodeInfosByViewId(rowId);
            if (rows == null || rows.isEmpty()) continue;
            try {
                for (AccessibilityNodeInfo row : rows) {
                    if (row == null) continue;
                    if (!rowMatchesTitleAndSubtitle(row, query, firstWord, "song")) continue;
                    if (clickPlayControlInsideRow(row)) {
                        lastPlayResultUsed = "song/inline_row_play";
                        Log.i(TAG, "SONG_INLINE_PLAY matched row + play control");
                        return true;
                    }
                }
            } finally {
                recycleList(rows);
            }
        }
        return false;
    }

    private boolean clickPlayControlInsideRow(AccessibilityNodeInfo row) {
        return findClickInlinePlayInSubtree(row, 0);
    }

    private boolean findClickInlinePlayInSubtree(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 18) return false;
        if (node.isClickable()) {
            String label = combinedAccessibleLabel(node);
            if (isLikelyTrackRowPlayLabel(label) && performClickOnNodeOrAncestor(node)) {
                return true;
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (findClickInlinePlayInSubtree(c, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private static String combinedAccessibleLabel(AccessibilityNodeInfo node) {
        StringBuilder sb = new StringBuilder();
        CharSequence cd = node.getContentDescription();
        CharSequence tx = node.getText();
        if (cd != null) sb.append(cd).append(' ');
        if (tx != null) sb.append(tx);
        return sb.toString().trim().toLowerCase();
    }

    private static boolean isLikelyTrackRowPlayLabel(String label) {
        if (label.isEmpty()) return false;
        if (!label.contains("play")) return false;
        if (label.contains("shuffle")) return false;
        if (label.contains("playlist")) return false;
        if (label.contains("google play")) return false;
        if (label.contains("display")) return false;
        return true;
    }

    private boolean verifyPlayingWithSettle() {
        for (int i = 0; i < 6; i++) {
            if (verifyPlaying()) {
                return true;
            }
            if (!sleepUnlessCancelled(750)) {
                return false;
            }
        }
        return false;
    }

    private void selectResultsTabForType(String playType) {
        if ("song".equals(playType) || "track".equals(playType)) {
            if (clickTextExact("Songs") || clickTextExact("Top")) {
                Log.d(TAG, "Selected Songs/Top tab for play type");
            }
            return;
        }
        if ("playlist".equals(playType)) {
            if (clickTextExact("Playlists")) {
                Log.d(TAG, "Selected Playlists tab");
            }
            return;
        }
        if ("artist".equals(playType)) {
            if (clickTextExact("Artists") || clickTextExact("Top")) {
                Log.d(TAG, "Selected Artists/Top tab");
            }
            return;
        }
        clickTextExact("Top");
    }

    private static final class RowWithTop {
        final AccessibilityNodeInfo row;
        final int top;

        RowWithTop(AccessibilityNodeInfo row, int top) {
            this.row = row;
            this.top = top;
        }
    }

    private boolean clickResultRowForQuery(String playType, String query) {
        if (query == null || query.trim().isEmpty()) return false;
        query = query.trim();

        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;

        String[] words = query.split("\\s+");
        String firstWord = words.length > 0 ? words[0] : "";
        String cat = normalizeSearchResultCategory(playType);

        List<RowWithTop> matched = new ArrayList<>();
        for (String id : RESULT_ROW_IDS) {
            List<AccessibilityNodeInfo> rows = root.findAccessibilityNodeInfosByViewId(id);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            for (AccessibilityNodeInfo row : rows) {
                if (row == null) {
                    continue;
                }
                if (rowMatchesTitleAndSubtitle(row, query, firstWord, cat)) {
                    Rect r = new Rect();
                    row.getBoundsInScreen(r);
                    matched.add(new RowWithTop(row, r.top));
                } else {
                    row.recycle();
                }
            }
        }
        if (matched.isEmpty()) {
            return false;
        }
        Collections.sort(matched, new Comparator<RowWithTop>() {
            @Override
            public int compare(RowWithTop a, RowWithTop b) {
                return Integer.compare(a.top, b.top);
            }
        });
        try {
            for (RowWithTop rw : matched) {
                if (performClickOnNodeOrAncestor(rw.row)) {
                    lastPlayResultUsed = playType + "/row_title_subtitle";
                    Log.i(TAG, "PLAY row_title_subtitle top=" + rw.top);
                    return true;
                }
            }
            return false;
        } finally {
            for (RowWithTop rw : matched) {
                rw.row.recycle();
            }
        }
    }

    private boolean rowMatchesTitleAndSubtitle(AccessibilityNodeInfo row, String full, String firstWord, String category) {
        if (row == null) {
            return false;
        }
        AccessibilityNodeInfo titleN = findDescendantWithViewId(row, ID_RESULT_TITLE, 0);
        AccessibilityNodeInfo subN = findDescendantWithViewId(row, ID_RESULT_SUBTITLE, 0);
        if (titleN == null || subN == null) {
            if (titleN != null) {
                titleN.recycle();
            }
            if (subN != null) {
                subN.recycle();
            }
            return false;
        }
        try {
            String titleLower = combinedNodeTextLower(titleN);
            String subLower = combinedNodeTextLower(subN);
            if (titleLower.isEmpty() || subLower.isEmpty()) {
                return false;
            }
            if (!rowTitleMatchesPlayQueryText(titleLower, full, firstWord, category)) {
                return false;
            }
            return rowSubtitleMatchesPlayTypeText(subLower, category);
        } finally {
            titleN.recycle();
            subN.recycle();
        }
    }

    private static boolean rowTitleMatchesPlayQueryText(String titleLower, String full, String firstWord, String category) {
        return titleMatchesForCategory(titleLower, full, firstWord, category);
    }

    private static boolean rowSubtitleMatchesPlayTypeText(String subLower, String category) {
        return subtitleMatchesCategoryLabel(subLower, category);
    }

    private static String combinedNodeTextLower(AccessibilityNodeInfo node) {
        if (node == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            sb.append(t).append(' ');
        }
        CharSequence d = node.getContentDescription();
        if (d != null && d.length() > 0) {
            sb.append(d);
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSpacesLower(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String normalizeTitleKey(String s) {
        if (s == null) {
            return "";
        }
        String n = Normalizer.normalize(s.trim(), Normalizer.Form.NFC);
        n = n.replaceAll("[\\u200B-\\u200D\\uFEFF\\u2060]", "");
        return n.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeSubtitleForMatch(String subLower) {
        if (subLower == null || subLower.isEmpty()) {
            return "";
        }
        String n = Normalizer.normalize(subLower, Normalizer.Form.NFC);
        n = n.replace('\u00b7', ' ')
                .replace('\u2022', ' ')
                .replace('\u2219', ' ')
                .replace('\u2027', ' ')
                .replace('\u0387', ' ')
                .replace('\u30fb', ' ');
        n = n.replaceAll("[·•‧∙⋅]", " ");
        return n.replaceAll("\\s+", " ").trim();
    }

    private static boolean containsUnicodeToken(String hayLower, String asciiTokenLower) {
        if (hayLower == null || asciiTokenLower == null || asciiTokenLower.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}_])" + Pattern.quote(asciiTokenLower) + "(?![\\p{L}\\p{M}\\p{N}_])")
                .matcher(hayLower)
                .find();
    }

    private static boolean titleMatchesForCategory(String titleLower, String full, String firstWord, String category) {
        String cat = normalizeSearchResultCategory(category);
        if ("artist".equals(cat)) {
            if (full.trim().isEmpty()) {
                return false;
            }
            return normalizeTitleKey(titleLower).equals(normalizeTitleKey(full));
        }
        return rowNameMatchesQuery(titleLower, full, firstWord);
    }

    private AccessibilityNodeInfo findParentResultRow(AccessibilityNodeInfo inner) {
        AccessibilityNodeInfo current = inner;
        for (int d = 0; d < 16; d++) {
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) {
                return null;
            }
            CharSequence vid = parent.getViewIdResourceName();
            if (vid != null) {
                String v = vid.toString();
                for (String rid : RESULT_ROW_IDS) {
                    if (rid.equals(v)) {
                        return parent;
                    }
                }
            }
            current = parent;
        }
        return null;
    }

    private boolean clickRowViaTitleNodesGlobalScan(String playType, String queryRaw) {
        if (queryRaw == null || queryRaw.trim().isEmpty()) {
            return false;
        }
        String query = queryRaw.trim();
        String[] words = query.split("\\s+");
        String firstWord = words.length > 0 ? words[0] : "";
        String cat = normalizeSearchResultCategory(playType);
        AccessibilityNodeInfo root = getRoot();
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(ID_RESULT_TITLE);
        if (titles == null || titles.isEmpty()) {
            return false;
        }
        List<RowWithTop> matched = new ArrayList<>();
        try {
            for (AccessibilityNodeInfo titleNode : titles) {
                if (titleNode == null) {
                    continue;
                }
                String titleLower = combinedNodeTextLower(titleNode);
                if (titleLower.isEmpty() || !titleMatchesForCategory(titleLower, query, firstWord, cat)) {
                    continue;
                }
                AccessibilityNodeInfo row = findParentResultRow(titleNode);
                if (row == null) {
                    continue;
                }
                if (!rowMatchesTitleAndSubtitle(row, query, firstWord, cat)) {
                    row.recycle();
                    continue;
                }
                Rect r = new Rect();
                row.getBoundsInScreen(r);
                matched.add(new RowWithTop(row, r.top));
            }
        } finally {
            recycleList(titles);
        }
        if (matched.isEmpty()) {
            return false;
        }
        Collections.sort(matched, new Comparator<RowWithTop>() {
            @Override
            public int compare(RowWithTop a, RowWithTop b) {
                return Integer.compare(a.top, b.top);
            }
        });
        try {
            for (RowWithTop rw : matched) {
                if (performClickOnNodeOrAncestor(rw.row)) {
                    lastPlayResultUsed = playType + "/title_subtitle_global_scan";
                    Log.i(TAG, "PLAY title+subtitle global scan top=" + rw.top);
                    return true;
                }
            }
            return false;
        } finally {
            for (RowWithTop rw : matched) {
                rw.row.recycle();
            }
        }
    }

    private static String normalizeSearchResultCategory(String playTypeRaw) {
        if (playTypeRaw == null) {
            return "artist";
        }
        String t = playTypeRaw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return "artist";
        }
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
                Log.w(TAG, "Unknown play_type for row match '" + playTypeRaw + "' -> artist");
                return "artist";
        }
    }

    private static boolean rowNameMatchesQuery(String blobLower, String full, String firstWord) {
        if (!full.isEmpty() && blobLower.contains(full.toLowerCase(Locale.ROOT))) {
            return true;
        }
        return firstWord != null && firstWord.length() >= 2
                && blobLower.contains(firstWord.toLowerCase(Locale.ROOT));
    }

    private static boolean subtitleMatchesCategoryLabel(String subLower, String category) {
        if (category == null || category.isEmpty()) {
            return true;
        }
        String cat = normalizeSearchResultCategory(category);
        String norm = normalizeSubtitleForMatch(subLower);
        if (norm.isEmpty()) {
            return false;
        }
        switch (cat) {
            case "artist":
                return subtitleIsArtistRow(norm);
            case "playlist":
                return containsUnicodeToken(norm, "playlist");
            case "song":
                if (containsUnicodeToken(norm, "playlist") || containsUnicodeToken(norm, "podcast")) {
                    return false;
                }
                if (containsUnicodeToken(norm, "song")
                        || containsUnicodeToken(norm, "track")
                        || containsUnicodeToken(norm, "single")) {
                    return true;
                }
                return Pattern.compile("\\d{1,2}:\\d{2}").matcher(norm).find();
            default:
                Log.w(TAG, "subtitleMatchesCategoryLabel: unhandled category " + cat);
                return false;
        }
    }

    private static boolean subtitleIsArtistRow(String normSubLower) {
        if (normSubLower.isEmpty()) {
            return false;
        }
        if (containsUnicodeToken(normSubLower, "playlist") || containsUnicodeToken(normSubLower, "podcast")) {
            return false;
        }
        if ("artist".equals(normSubLower)) {
            return true;
        }
        if (normSubLower.startsWith("artist ") || normSubLower.startsWith("artist.")) {
            return true;
        }
        return containsUnicodeToken(normSubLower, "artist");
    }

    /**
     * Artist follow toggle: agar pehle se follow ho chuka ho to true (tap mat karna — unfollow ho jata hai).
     */
    private static boolean followControlLooksAlreadyFollowing(AccessibilityNodeInfo followBtn) {
        if (followBtn == null) {
            return false;
        }
        if (followBtn.isChecked()) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            CharSequence st = followBtn.getStateDescription();
            if (st != null) {
                String s = st.toString().toLowerCase(Locale.ROOT);
                if (s.contains("following") || s.contains("followed") || s.contains("unfollow")) {
                    return true;
                }
            }
        }
        CharSequence cd = followBtn.getContentDescription();
        CharSequence tx = followBtn.getText();
        String d = cd != null ? cd.toString().toLowerCase(Locale.ROOT) : "";
        String t = tx != null ? tx.toString().toLowerCase(Locale.ROOT) : "";
        if (d.contains("unfollow") || t.contains("unfollow")) {
            return true;
        }
        if (WORD_FOLLOWING.matcher(d).find() || WORD_FOLLOWING.matcher(t).find()) {
            return true;
        }
        return false;
    }

    private static boolean rootShowsPlaylistAlreadyInLibrary(AccessibilityNodeInfo root) {
        if (root == null) {
            return false;
        }
        for (String needle : PLAYLIST_SAVED_UI_NEEDLES) {
            if (findNodeByPropertyStatic(root, needle, true) != null) {
                return true;
            }
            if (findNodeByPropertyStatic(root, needle, false) != null) {
                return true;
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo findNodeByPropertyStatic(AccessibilityNodeInfo root, String searchText,
                                                                  boolean useContentDesc) {
        if (root == null || searchText == null) {
            return null;
        }
        String needle = searchText.toLowerCase(Locale.ROOT);
        CharSequence value = useContentDesc ? root.getContentDescription() : root.getText();
        if (value != null && value.toString().toLowerCase(Locale.ROOT).contains(needle)) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo found = findNodeByPropertyStatic(child, searchText, useContentDesc);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    public boolean tryFollowInSearchResultsRow(String searchQuery, String playTypeRaw) {
        if (searchQuery == null || searchQuery.trim().isEmpty()) {
            return false;
        }
        String query = searchQuery.trim();
        String[] words = query.split("\\s+");
        String firstWord = words.length > 0 ? words[0] : "";

        String playType = playTypeRaw != null ? playTypeRaw.trim().toLowerCase(Locale.ROOT) : "";
        if (playType.isEmpty()) {
            playType = "artist";
        }
        String cat = normalizeSearchResultCategory(playType);
        boolean songish = "song".equals(playType) || "track".equals(playType);
        boolean artistish = "artist".equals(playType);
        boolean playlistish = "playlist".equals(playType);
        long waitAfterSearch = songish ? 2400L : (artistish ? 3200L : 2000L);

        if (!sleepUnlessCancelled(waitAfterSearch)) {
            return false;
        }
        tryDismissBlockingOverlays();

        AccessibilityNodeInfo rootLibCheck = getRoot();
        if (playlistish && rootLibCheck != null && rootShowsPlaylistAlreadyInLibrary(rootLibCheck)) {
            Log.i(TAG, "FOLLOW_PLAYLIST_SKIP already in library (no tap)");
            return true;
        }

        if (playlistish && clickAddPlaylistToLibraryButtonOnCurrentScreen()) {
            sleep(450);
            return true;
        }

        for (int round = 0; round < 4; round++) {
            if (isCancelled() || isSessionDeadlinePassed()) {
                return false;
            }
            AccessibilityNodeInfo root = getRoot();
            if (playlistish && clickAddPlaylistToLibraryButtonOnCurrentScreen()) {
                sleep(450);
                return true;
            }
            if (root != null && clickFollowButtonInMatchedResultRow(root, query, firstWord, cat)) {
                sleep(500);
                return true;
            }
            if (round == 0) {
                selectResultsTabForType(playType);
                if (artistish) {
                    sleepUnlessCancelled(400);
                    selectResultsTabForType(playType);
                }
                sleepUnlessCancelled(artistish ? 1000L : 900L);
            } else if (round == 1) {
                selectResultsTabForType(playType);
                sleepUnlessCancelled(900);
            } else {
                root = getRoot();
                if (root != null) {
                    scrollSearchResultsForward(root, 0);
                    sleepUnlessCancelled(650);
                }
            }
        }
        Log.w(TAG, "FOLLOW_FAIL no follow_button in row matching query=" + query + " playType=" + playType);
        return false;
    }

    private boolean clickAddPlaylistToLibraryButtonOnCurrentScreen() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;
        if (rootShowsPlaylistAlreadyInLibrary(root)) {
            Log.i(TAG, "FOLLOW_PLAYLIST_SKIP already in library (no tap)");
            return true;
        }
        AccessibilityNodeInfo addByDesc = findNodeByProperty(root, DESC_ADD_PLAYLIST_TO_LIBRARY, true);
        if (addByDesc != null && performClickOnNodeOrAncestor(addByDesc)) {
            Log.i(TAG, "FOLLOW_PLAYLIST_OK via content-desc Add playlist to Your Library");
            return true;
        }
        return false;
    }

    public boolean followFromCurrentPlaybackContext(String queryRaw, String playTypeRaw) {
        String playType = playTypeRaw != null ? playTypeRaw.trim().toLowerCase(Locale.ROOT) : "";
        String query = queryRaw != null ? queryRaw.trim() : "";
        if ("playlist".equals(playType)) {
            for (int i = 0; i < 6 && !isCancelled() && !isSessionDeadlinePassed(); i++) {
                tryDismissBlockingOverlays();
                if (clickAddPlaylistToLibraryButtonOnCurrentScreen()) {
                    sleep(450);
                    return true;
                }
                sleep(320);
            }
            return false;
        }
        if (query.isEmpty()) return false;
        return followArtistPreferCurrentPage(query);
    }

    public boolean tryFollowArtistOnSearchResults(String artistQuery) {
        return tryFollowInSearchResultsRow(artistQuery, "artist");
    }

    public boolean followArtistPreferCurrentPage(String artistName) {
        if (artistName == null || artistName.trim().isEmpty()) {
            return false;
        }
        return tryFollowInSearchResultsRow(artistName.trim(), "artist");
    }

    private boolean clickFollowButtonInMatchedResultRow(AccessibilityNodeInfo root, String query, String firstWord,
                                                        String category) {
        List<RowWithTop> matched = new ArrayList<>();
        for (String rowId : RESULT_ROW_IDS) {
            List<AccessibilityNodeInfo> rows = root.findAccessibilityNodeInfosByViewId(rowId);
            if (rows == null || rows.isEmpty()) {
                continue;
            }
            for (AccessibilityNodeInfo row : rows) {
                if (row == null) {
                    continue;
                }
                if (!rowMatchesTitleAndSubtitle(row, query, firstWord, category)) {
                    row.recycle();
                    continue;
                }
                Rect r = new Rect();
                row.getBoundsInScreen(r);
                matched.add(new RowWithTop(row, r.top));
            }
        }
        if (matched.isEmpty()) {
            return clickFollowViaTitleNodesGlobalScan(root, query, firstWord, category);
        }
        Collections.sort(matched, new Comparator<RowWithTop>() {
            @Override
            public int compare(RowWithTop a, RowWithTop b) {
                return Integer.compare(a.top, b.top);
            }
        });
        try {
            for (RowWithTop rw : matched) {
                AccessibilityNodeInfo btn = findDescendantWithViewId(rw.row, ID_FOLLOW_BUTTON, 0);
                if (btn == null) {
                    continue;
                }
                try {
                    if (followControlLooksAlreadyFollowing(btn)) {
                        Log.i(TAG, "FOLLOW_SKIP already following artist (no tap) top=" + rw.top);
                        return true;
                    }
                    boolean ok = (btn.isClickable() && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            || performClickOnNodeOrAncestor(btn);
                    if (ok) {
                        Log.i(TAG, "FOLLOW_OK follow_button top=" + rw.top);
                        return true;
                    }
                } finally {
                    btn.recycle();
                }
            }
            return clickFollowViaTitleNodesGlobalScan(root, query, firstWord, category);
        } finally {
            for (RowWithTop rw : matched) {
                rw.row.recycle();
            }
        }
    }

    private boolean clickFollowViaTitleNodesGlobalScan(AccessibilityNodeInfo root, String query, String firstWord,
                                                       String category) {
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> titles = root.findAccessibilityNodeInfosByViewId(ID_RESULT_TITLE);
        if (titles == null || titles.isEmpty()) {
            return false;
        }
        List<RowWithTop> matched = new ArrayList<>();
        try {
            for (AccessibilityNodeInfo titleNode : titles) {
                if (titleNode == null) {
                    continue;
                }
                String titleLower = combinedNodeTextLower(titleNode);
                if (titleLower.isEmpty() || !titleMatchesForCategory(titleLower, query, firstWord, category)) {
                    continue;
                }
                AccessibilityNodeInfo row = findParentResultRow(titleNode);
                if (row == null) {
                    continue;
                }
                if (!rowMatchesTitleAndSubtitle(row, query, firstWord, category)) {
                    row.recycle();
                    continue;
                }
                Rect r = new Rect();
                row.getBoundsInScreen(r);
                matched.add(new RowWithTop(row, r.top));
            }
        } finally {
            recycleList(titles);
        }
        if (matched.isEmpty()) {
            return false;
        }
        Collections.sort(matched, new Comparator<RowWithTop>() {
            @Override
            public int compare(RowWithTop a, RowWithTop b) {
                return Integer.compare(a.top, b.top);
            }
        });
        try {
            for (RowWithTop rw : matched) {
                AccessibilityNodeInfo btn = findDescendantWithViewId(rw.row, ID_FOLLOW_BUTTON, 0);
                if (btn == null) {
                    continue;
                }
                try {
                    if (followControlLooksAlreadyFollowing(btn)) {
                        Log.i(TAG, "FOLLOW_SKIP already following artist (no tap) global top=" + rw.top);
                        return true;
                    }
                    boolean ok = (btn.isClickable() && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                            || performClickOnNodeOrAncestor(btn);
                    if (ok) {
                        Log.i(TAG, "FOLLOW_OK follow_button global scan top=" + rw.top);
                        return true;
                    }
                } finally {
                    btn.recycle();
                }
            }
            return false;
        } finally {
            for (RowWithTop rw : matched) {
                rw.row.recycle();
            }
        }
    }

    private static AccessibilityNodeInfo findDescendantWithViewId(AccessibilityNodeInfo node, String viewId, int depth) {
        if (node == null || depth > 48) {
            return null;
        }
        CharSequence vid = node.getViewIdResourceName();
        if (vid != null && viewId.contentEquals(vid)) {
            return node;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo ch = node.getChild(i);
            AccessibilityNodeInfo hit = findDescendantWithViewId(ch, viewId, depth + 1);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private boolean scrollSearchResultsForward(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 36) {
            return false;
        }
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
            return true;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo ch = node.getChild(i);
            if (scrollSearchResultsForward(ch, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean startPlaybackControl(String playType) {
        sleep(350);
        boolean didShuffle = false;

        if ("playlist".equals(playType)) {
            didShuffle = clickHeaderChromeControl("shuffle", "playlist");
            if (didShuffle) sleep(650);
        }

        if (!clickHeaderChromeControl("play", playType)) {
            Log.w(TAG, "DETAIL_PLAY no header chrome Play; trying MEDIA_PLAY key");
            Log.w(TAG, "DETAIL_PLAY header play failed; trying tree/content-desc fallbacks");
            AccessibilityNodeInfo r = getRoot();
            if (r != null) {
                if ("playlist".equals(playType)) {
                    AccessibilityNodeInfo shufflePlayPlaylistNode =
                            findNodeByExactContentDesc(r, "Shuffle Play playlist");
                    if (shufflePlayPlaylistNode != null && clickNodeByBoundsCenter(shufflePlayPlaylistNode)) {
                        sleep(600);
                        lastPlayResultUsed = "playlist/desc_shuffle_play_playlist_bounds";
                        return true;
                    }

                    AccessibilityNodeInfo playPlaylistExactNode =
                            findNodeByExactContentDesc(r, "Play playlist");
                    if (playPlaylistExactNode != null && clickNodeByBoundsCenter(playPlaylistExactNode)) {
                        sleep(600);
                        lastPlayResultUsed = "playlist/desc_play_playlist_bounds";
                        return true;
                    }

                    AccessibilityNodeInfo playPlaylistNode = findNodeByProperty(r, "play playlist", true);
                    if (playPlaylistNode != null && performClickOnNodeOrAncestor(playPlaylistNode)) {
                        sleep(600);
                        lastPlayResultUsed = "playlist/desc_play_playlist";
                        return true;
                    }
                }
                AccessibilityNodeInfo playNode = findNodeByProperty(r, "play", true);
                if (playNode != null && performClickOnNodeOrAncestor(playNode)) {
                    sleep(600);
                    lastPlayResultUsed = playType + "/desc_play_fallback";
                    return true;
                }
            }
            if (dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY, "MEDIA_PLAY")) {
                sleep(900);
                lastPlayResultUsed = playType + "/media_play_key";
                return true;
            }
            return false;
        }

        if ("playlist".equals(playType) && didShuffle) {
            lastPlayResultUsed = "playlist/shuffle+detail_play";
        } else {
            lastPlayResultUsed = playType + "/detail_play";
        }
        return true;
    }

    private AccessibilityNodeInfo findNodeByExactContentDesc(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return null;
        CharSequence cd = root.getContentDescription();
        if (cd != null && text.equalsIgnoreCase(cd.toString().trim())) {
            return root;
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            AccessibilityNodeInfo found = findNodeByExactContentDesc(child, text);
            if (found != null) return found;
        }
        return null;
    }

    private boolean clickHeaderChromeControl(String kind, String playTypeForLog) {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;

        int sh = context.getResources().getDisplayMetrics().heightPixels;
        int strip = Math.max(dp(210), (int) (sh * 0.30f));
        int maxBottom = sh - strip;

        List<ChromeCandidate> cands = new ArrayList<>();
        collectHeaderChromeCandidates(root, kind, maxBottom, cands);

        Collections.sort(cands, new Comparator<ChromeCandidate>() {
            @Override
            public int compare(ChromeCandidate a, ChromeCandidate b) {
                return Integer.compare(b.score, a.score);
            }
        });

        for (ChromeCandidate c : cands) {
            if (performClickOnNodeOrAncestor(c.node)) {
                Log.i(TAG, "HEADER_CHROME_CLICK kind=" + kind + " score=" + c.score
                        + " playType=" + playTypeForLog + " class=" + c.className + " desc=" + c.descHint);
                return true;
            }
        }
        return false;
    }

    private static final class ChromeCandidate {
        final AccessibilityNodeInfo node;
        final int score;
        final String className;
        final String descHint;

        ChromeCandidate(AccessibilityNodeInfo node, int score, String className, String descHint) {
            this.node = node;
            this.score = score;
            this.className = className;
            this.descHint = descHint;
        }
    }

    private void collectHeaderChromeCandidates(AccessibilityNodeInfo node, String kind, int maxBottomPx, List<ChromeCandidate> out) {
        if (node == null) return;

        Rect r = new Rect();
        node.getBoundsInScreen(r);

        if (r.isEmpty() || r.bottom > maxBottomPx || r.top < dp(56)) {
            // Skip: mini-player/nav strip or status bar
        } else {
            String cls = node.getClassName() != null ? node.getClassName().toString() : "";
            CharSequence cd = node.getContentDescription();
            CharSequence tx = node.getText();
            String label = ((cd != null ? cd.toString() : "") + " " + (tx != null ? tx.toString() : ""))
                    .trim().toLowerCase();

            boolean isPlay = "play".equals(kind) && label.contains("play") && !label.contains("shuffle");
            boolean isShuffle = "shuffle".equals(kind) && label.contains("shuffle");

            if ((isPlay || isShuffle) && node.isClickable()) {
                int area = Math.max(0, r.width()) * Math.max(0, r.height());
                if (area >= dp(28) * dp(28)) {
                    int score = area;
                    if (cls.contains("Button")) score += 80_000;
                    int cy = r.centerY();
                    score += Math.max(0, maxBottomPx - cy);

                    String hint = cd != null ? cd.toString() : (tx != null ? tx.toString() : "");
                    out.add(new ChromeCandidate(node, score, cls, hint));
                }
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collectHeaderChromeCandidates(node.getChild(i), kind, maxBottomPx, out);
        }
    }

    private boolean verifyPlaying() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;

        List<AccessibilityNodeInfo> bar = root.findAccessibilityNodeInfosByViewId(ID_BUTTON_PLAY_PAUSE);
        try {
            if (bar != null) {
                for (AccessibilityNodeInfo btn : bar) {
                    if (btn == null) continue;
                    CharSequence cd = btn.getContentDescription();
                    if (cd != null && cd.toString().toLowerCase().contains("pause")) {
                        return true;
                    }
                }
            }
        } finally {
            recycleList(bar);
        }

        AccessibilityNodeInfo pauseByDesc = findNodeByProperty(root, "pause", true);
        if (pauseByDesc != null) return true;

        AccessibilityNodeInfo pauseByText = findNodeByProperty(root, "pause", false);
        return pauseByText != null;
    }

    private boolean clickTextExact(String text) {
        AccessibilityNodeInfo root = getRoot();
        return root != null && clickTextExactOnRoot(root, text);
    }

    private boolean clickTextExactOnRoot(AccessibilityNodeInfo root, String text) {
        if (text == null || text.isEmpty() || root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes == null || nodes.isEmpty()) return false;
        try {
            for (AccessibilityNodeInfo n : nodes) {
                if (n == null) continue;
                CharSequence t = n.getText();
                if (t != null && text.equalsIgnoreCase(t.toString().trim()) && performClickOnNodeOrAncestor(n)) {
                    return true;
                }
            }
        } finally {
            recycleList(nodes);
        }
        return false;
    }

    // ==================== LIKE SONGS (SINGLE CLEAN FLOW) ====================

    public boolean likeCurrentTrackForListenPhase() {
        if (isSongPlayType()) {
            bringSpotifyToForegroundQuick();
            sleep(350);
            tryDismissBlockingOverlays();
        }
        return likeSongsFromCurrentList(1) > 0;
    }

    private boolean isSongPlayType() {
        return "song".equals(currentPlayType) || "track".equals(currentPlayType);
    }

    /**
     * {@code song}/{@code track}: dedicated one-shot song menu flow.
     * {@code artist}/{@code playlist}: bounded random UI flow (no API path).
     */
    public int likeSongsFromCurrentList(int wantCount) {
        lastLikeFailureReason = "";
        if (wantCount <= 0) {
            lastLikeFailureReason = "WANT_COUNT_ZERO";
            return 0;
        }
        if (isSongPlayType()) {
            int n = likeCurrentSongViaContextMenuSingleCheck();
            if (n == 0) {
                lastLikeFailureReason = nonEmptyOr(lastLikeFailureReason, "SONG_UI_NO_LIKE");
            }
            return n;
        }
        int ui = likeSongsFromCurrentListUi(wantCount);
        if (ui > 0) {
            lastLikeFailureReason = "";
            return ui;
        }
        lastLikeFailureReason = nonEmptyOr(lastLikeFailureReason, "UI_FALLBACK_NO_LIKE");
        return 0;
    }

    /**
     * Song/track mode: one-shot context-menu check only.
     * Use song screen 3-dots ({@code context_menu_button} + desc "More options for song ..."), then:
     * - if "Add to liked songs" exists => click like
     * - else close menu and return (already liked / option missing)
     */
    private int likeCurrentSongViaContextMenuSingleCheck() {
        if (isCancelled() || isSessionDeadlinePassed()) {
            lastLikeFailureReason = "CANCELLED_OR_SESSION_DEADLINE";
            return 0;
        }
        bringSpotifyToForegroundQuick();
        sleep(350);
        tryDismissBlockingOverlays();
        AccessibilityNodeInfo root = getRoot();
        if (root == null) {
            lastLikeFailureReason = "SONG_ROOT_NULL";
            return 0;
        }
        List<AccessibilityNodeInfo> menuTargets = root.findAccessibilityNodeInfosByViewId(ID_CONTEXT_MENU_BUTTON);
        if (menuTargets == null || menuTargets.isEmpty()) {
            lastLikeFailureReason = "SONG_CONTEXT_MENU_BUTTON_NOT_FOUND";
            return 0;
        }

        AccessibilityNodeInfo chosen = null;
        for (AccessibilityNodeInfo n : menuTargets) {
            if (n == null || !n.isVisibleToUser()) continue;
            CharSequence cd = n.getContentDescription();
            String s = cd != null ? cd.toString().toLowerCase(Locale.ROOT) : "";
            if (s.contains(DESC_MORE_OPTIONS_FOR_SONG_PREFIX)) {
                chosen = n;
                break;
            }
        }
        if (chosen == null) {
            lastLikeFailureReason = "SONG_CONTEXT_MENU_DESC_NOT_FOUND";
            return 0;
        }
        if (!clickOpenContextMenuFromDescNode(chosen)) {
            lastLikeFailureReason = "SONG_CONTEXT_MENU_OPEN_FAIL";
            return 0;
        }

        sleepUnlessCancelled(420L);
        boolean liked = clickLikeInMenuSingleCheck();
        if (liked) {
            lastLikeFailureReason = "";
            Log.i(TAG, "LIKE_SONG one-shot context menu like OK");
            return 1;
        }
        return 0;
    }

    private boolean clickLikeInMenuSingleCheck() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) {
            closeContextMenuIfNeeded();
            lastLikeFailureReason = "SONG_MENU_ROOT_NULL";
            return false;
        }
        if (findAndClickMenuOption(root, "remove from liked songs")
                || findAndClickMenuOption(root, "remove from your liked songs")) {
            closeContextMenuIfNeeded();
            lastLikeFailureReason = "SONG_ALREADY_LIKED_SKIP_CLICK";
            return false;
        }
        if (findAndClickMenuOption(root, "add to liked songs")
                || findAndClickMenuOption(root, "add to your liked songs")
                || findAndClickMenuOption(root, "save to your liked songs")
                || findAndClickMenuOption(root, "like song")) {
            closeContextMenuIfNeeded();
            return true;
        }
        closeContextMenuIfNeeded();
        lastLikeFailureReason = "SONG_MENU_ADD_OPTION_NOT_FOUND";
        return false;
    }

    private static String nonEmptyOr(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    /**
     * Spotify Web API: save random tracks from playlist (all pages, capped) or artist top tracks. Requires token + URI.
     */
    private int likeTracksViaSpotifyApi(int wantCount) {
        if (isCancelled() || isSessionDeadlinePassed()) {
            lastLikeFailureReason = "CANCELLED_OR_SESSION_DEADLINE";
            return 0;
        }
        String token = spotifyAccessToken != null ? spotifyAccessToken.trim() : "";
        String uri = spotifyContextUri != null ? spotifyContextUri.trim() : "";
        if (token.isEmpty() || uri.isEmpty()) {
            lastLikeFailureReason = "API_MISSING_TOKEN_OR_CONTEXT_URI";
            Log.w(TAG, "LIKE_API missing spotify_access_token or spotify_context_uri (artist/playlist likes need both)");
            return 0;
        }
        try {
            synchronized (apiLikeLock) {
                ensureApiLikePoolLoaded(token, uri);
                if (apiShuffledTrackIds == null || apiShuffledTrackIds.isEmpty()) {
                    lastLikeFailureReason = "API_EMPTY_POOL_CHECK_URI_SCOPES_401_PLAYLIST_PRIVATE";
                    Log.w(TAG, "LIKE_API empty track pool for uri=" + uri);
                    return 0;
                }
                List<String> batch = new ArrayList<>();
                int batchStart = apiLikePoolNextIndex;
                while (batch.size() < wantCount && apiLikePoolNextIndex < apiShuffledTrackIds.size()) {
                    batch.add(apiShuffledTrackIds.get(apiLikePoolNextIndex++));
                }
                if (batch.isEmpty()) {
                    lastLikeFailureReason = "API_POOL_EXHAUSTED_ALL_TRACKS_TRIED";
                    Log.w(TAG, "LIKE_API pool exhausted");
                    return 0;
                }
                boolean ok = SpotifyWebApiClient.saveTracksToLibrary(token, batch);
                if (ok) {
                    Log.i(TAG, "LIKE_API saved count=" + batch.size() + " playType=" + currentPlayType);
                    return batch.size();
                }
                apiLikePoolNextIndex = batchStart;
                lastLikeFailureReason = "API_SAVE_FAILED_CHECK_TOKEN_SCOPE_user-library-modify";
                Log.w(TAG, "LIKE_API saveTracksToLibrary failed (pool index restored)");
                return 0;
            }
        } catch (Exception e) {
            lastLikeFailureReason = "API_ERROR_" + (e.getMessage() != null ? e.getMessage() : "unknown");
            Log.e(TAG, "LIKE_API " + e.getMessage());
            return 0;
        }
    }

    private void ensureApiLikePoolLoaded(String token, String uri) throws Exception {
        if (apiLikePoolInitialized) {
            return;
        }
        apiLikePoolInitialized = true;
        apiShuffledTrackIds = new ArrayList<>();
        apiLikePoolNextIndex = 0;
        SpotifyWebApiClient.ParsedUri parsed = SpotifyWebApiClient.parseSpotifyUri(uri);
        if (parsed == null) {
            Log.w(TAG, "LIKE_API bad spotify_context_uri (need spotify:playlist:ID or spotify:artist:ID): " + uri);
            return;
        }
        List<String> ids;
        if ("playlist".equals(parsed.type)) {
            ids = SpotifyWebApiClient.fetchPlaylistTrackIds(token, parsed.id, 500);
        } else if ("artist".equals(parsed.type)) {
            ids = SpotifyWebApiClient.fetchArtistTopTrackIds(token, parsed.id, "US");
        } else {
            return;
        }
        Set<String> unique = new LinkedHashSet<>(ids);
        apiShuffledTrackIds = new ArrayList<>(unique);
        Collections.shuffle(apiShuffledTrackIds);
        Log.i(TAG, "LIKE_API pool tracks=" + apiShuffledTrackIds.size() + " type=" + parsed.type);
    }

    /**
     * Song list: find nodes whose {@code contentDescription} contains "Open context menu for …",
     * then click the nearest clickable ancestor (no {@code trailing_slot} id).
     */
    private int likeSongsFromCurrentListUi(int wantCount) {
        if (wantCount <= 0) return 0;

        bringSpotifyToForegroundQuick();
        sleep(450);
        tryDismissBlockingOverlays();

        // Artist/playlist: short random scroll(s), then only act on currently visible rows.
        int preScrolls = 1 + (int) (Math.random() * 2); // 1..2
        for (int i = 0; i < preScrolls && !isCancelled() && !isSessionDeadlinePassed(); i++) {
            AccessibilityNodeInfo r = getRoot();
            if (r == null || !scrollSearchResultsDirectional(r, Math.random() < 0.5)) {
                break;
            }
            // Slower pre-like scroll pacing for playlist stability.
            sleepUnlessCancelled(720L + (long) (Math.random() * 360L));
        }

        AccessibilityNodeInfo root = getRoot();
        if (root == null) {
            lastLikeFailureReason = "UI_ROOT_NULL";
            return 0;
        }
        List<AccessibilityNodeInfo> menuTargets = new ArrayList<>();
        collectNodesContentDescContainingIgnoreCase(root, DESC_OPEN_CONTEXT_MENU_PREFIX, menuTargets, 0);
        if (menuTargets.isEmpty()) {
            lastLikeFailureReason = "UI_NO_OPEN_CONTEXT_MENU_DESC_VISIBLE";
            return 0;
        }

        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        for (AccessibilityNodeInfo target : menuTargets) {
            if (target == null || !target.isVisibleToUser()) continue;
            String key = buildUiLikeTargetKey(target);
            if (uiLikeProcessedTargetKeys.contains(key)) continue;
            candidates.add(target);
        }
        if (candidates.isEmpty()) {
            lastLikeFailureReason = "UI_VISIBLE_TARGETS_ALREADY_PROCESSED";
            return 0;
        }

        Collections.shuffle(candidates);
        int pickCount = Math.min(Math.min(wantCount, 2), candidates.size());
        int liked = 0;
        for (int i = 0; i < pickCount && !isCancelled() && !isSessionDeadlinePassed(); i++) {
            AccessibilityNodeInfo target = candidates.get(i);
            if (target == null || !target.isVisibleToUser()) continue;
            String key = buildUiLikeTargetKey(target);
            uiLikeProcessedTargetKeys.add(key);

            if (isAlreadyLiked(target)) {
                Log.d(TAG, "LIKE_SKIP target already liked key=" + key);
                continue;
            }
            if (!clickOpenContextMenuFromDescNode(target)) {
                Log.w(TAG, "LIKE_SKIP cannot open context menu key=" + key);
                continue;
            }
            sleepUnlessCancelled(380L);
            if (clickLikeInMenuSingleCheck()) {
                liked++;
                Log.i(TAG, "LIKE_OK random_ui liked=" + liked + "/" + pickCount + " key=" + key);
            } else {
                closeContextMenuIfNeeded();
            }
            sleepUnlessCancelled(220L);
        }

        if (liked == 0 && wantCount > 0) {
            lastLikeFailureReason = nonEmptyOr(lastLikeFailureReason,
                    "UI_RANDOM_BOUND_NO_LIKE_FROM_VISIBLE_TARGETS");
        }
        return liked;
    }

    private String buildUiLikeTargetKey(AccessibilityNodeInfo node) {
        if (node == null) return "null";
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        String cd = node.getContentDescription() != null
                ? node.getContentDescription().toString().trim().toLowerCase(Locale.ROOT)
                : "";
        return cd + "|" + r.left + "," + r.top + "," + r.right + "," + r.bottom;
    }

    private void collectNodesContentDescContainingIgnoreCase(AccessibilityNodeInfo node, String needleLower,
                                                           List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || needleLower == null || depth > 160) {
            return;
        }
        if (node.isVisibleToUser()) {
            CharSequence cd = node.getContentDescription();
            if (cd != null) {
                String s = cd.toString().toLowerCase(Locale.ROOT);
                if (s.contains(needleLower)) {
                    out.add(node);
                }
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo ch = node.getChild(i);
            collectNodesContentDescContainingIgnoreCase(ch, needleLower, out, depth + 1);
        }
    }

    /**
     * Node is the one with content-desc like "Open context menu for …". Click nearest clickable ancestor.
     */
    private boolean clickOpenContextMenuFromDescNode(AccessibilityNodeInfo descNode) {
        if (descNode == null) return false;
        ensureNodeVisibleBeforeClick(descNode);
        if (!descNode.isVisibleToUser()) return false;

        AccessibilityNodeInfo clickTarget = findSmallestClickableAncestorContainingDesc(descNode, 14);
        if (clickTarget != null && clickTarget.isVisibleToUser()) {
            if (performClickOnNodeOrAncestor(clickTarget)) {
                Log.d(TAG, "LIKE_CTX_MENU ACTION_CLICK ancestor for desc=" + descNode.getContentDescription());
                return true;
            }
            MyAccessibilityService svc = MyAccessibilityService.getInstance();
            if (svc != null) {
                Rect cr = new Rect();
                clickTarget.getBoundsInScreen(cr);
                if (!cr.isEmpty() && svc.tapAt(cr.centerX(), cr.centerY(), 110)) {
                    Log.d(TAG, "LIKE_CTX_MENU tap ancestor bounds for desc=" + descNode.getContentDescription());
                    return true;
                }
            }
        }

        MyAccessibilityService svc2 = MyAccessibilityService.getInstance();
        if (svc2 == null) return false;
        Rect r = new Rect();
        descNode.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        int inset = Math.max(4, Math.min(20, r.width() / 6));
        int x = Math.max(r.left + 2, Math.min(r.right - 2, r.right - inset));
        int y = r.centerY();
        if (svc2.tapAt(x, y, 110)) {
            Log.d(TAG, "LIKE_CTX_MENU tap desc bounds fallback x=" + x + " y=" + y);
            return true;
        }
        if (svc2.tapAt(r.centerX(), y, 110)) {
            Log.d(TAG, "LIKE_CTX_MENU tap desc center fallback");
            return true;
        }
        Log.w(TAG, "LIKE_CTX_MENU failed desc=" + descNode.getContentDescription());
        return false;
    }

    private boolean tryScrollForMoreLikeRows(AccessibilityNodeInfo root) {
        if (root == null) return false;
        if (scrollSearchResultsForward(root, 0)) {
            return true;
        }
        AccessibilityNodeInfo r2 = getRoot();
        return r2 != null && scrollSearchResultsBackward(r2, 0);
    }

    private boolean isRowAlreadyLiked(AccessibilityNodeInfo row) {
        if (row == null) return false;
        if (findNodeByProperty(row, "item added", true) != null) return true;
        if (findNodeByProperty(row, "item added", false) != null) return true;
        if (findNodeByProperty(row, "added to liked", true) != null) return true;
        if (findNodeByProperty(row, "added to liked", false) != null) return true;
        return false;
    }

    private boolean isAlreadyLiked(AccessibilityNodeInfo slotNode) {
        if (slotNode == null) return false;
        AccessibilityNodeInfo row = findParentResultRow(slotNode);
        if (row != null && isRowAlreadyLiked(row)) {
            return true;
        }
        AccessibilityNodeInfo current = slotNode;
        for (int depth = 0; depth < 8; depth++) {
            AccessibilityNodeInfo parent = current.getParent();
            if (parent == null) break;
            int childCount = parent.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = parent.getChild(i);
                if (child == null) continue;
                try {
                    if (contentLooksLikedIndicator(child.getContentDescription())
                            || contentLooksLikedIndicator(child.getText())) {
                        return true;
                    }
                } finally {
                    child.recycle();
                }
            }
            current = parent;
        }
        return false;
    }

    private static boolean contentLooksLikedIndicator(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().toLowerCase(Locale.ROOT).trim();
        if (s.isEmpty()) return false;
        if ("item added".equals(s)) return true;
        if (s.contains("item added")) return true;
        if (s.contains("added to liked")) return true;
        if (s.contains("remove from liked")) return true;
        return false;
    }

    private boolean clickLikeInMenu() {
        for (int attempt = 0; attempt < 10; attempt++) {
            AccessibilityNodeInfo root = getRoot();
            if (root == null) {
                sleep(140);
                continue;
            }

            if (findAndClickMenuOption(root, "remove from liked songs")
                    || findAndClickMenuOption(root, "remove from your liked songs")) {
                closeContextMenuIfNeeded();
                return false;
            }

            if (findAndClickMenuOption(root, "add to liked songs")
                    || findAndClickMenuOption(root, "add to your liked songs")
                    || findAndClickMenuOption(root, "save to your liked songs")
                    || findAndClickMenuOption(root, "like song")) {
                closeContextMenuIfNeeded();
                return true;
            }

            sleep(160 + (long) (Math.random() * 120L));
        }
        return false;
    }

    private boolean findAndClickMenuOption(AccessibilityNodeInfo root, String needle) {
        AccessibilityNodeInfo node = findNodeByProperty(root, needle, false);
        if (node == null) {
            node = findNodeByProperty(root, needle, true);
        }
        if (node == null) {
            return false;
        }
        ensureNodeVisibleBeforeClick(node);
        if (performClickOnNodeOrAncestor(node)) {
            sleep(240);
            return true;
        }
        if (clickNodeByBoundsCenter(node)) {
            sleep(240);
            return true;
        }
        return false;
    }

    private boolean clickSeeMoreIfVisible() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;

        AccessibilityNodeInfo textNode = findSeeMoreTextNodeDeep(root, 0);
        if (textNode == null) {
            return false;
        }

        // Strict: click the nearest clickable parent/ancestor only (bounded depth).
        AccessibilityNodeInfo parentClickable = findClickableAncestorBounded(textNode, 10);
        if (parentClickable == null || !parentClickable.isVisibleToUser()) {
            Log.d(TAG, "SEE_MORE found text but no visible clickable ancestor");
            return false;
        }

        if (parentClickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.i(TAG, "SEE_MORE clicked via clickable ancestor ACTION_CLICK");
            sleep(350);
            return true;
        }
        if (clickNodeByBoundsCenter(parentClickable)) {
            Log.i(TAG, "SEE_MORE clicked via clickable ancestor bounds tap");
            sleep(350);
            return true;
        }
        return false;
    }

    private AccessibilityNodeInfo findSeeMoreTextNodeDeep(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 140) return null;

        CharSequence t = node.getText();
        if (t != null) {
            String s = t.toString().trim();
            if ("See more".equalsIgnoreCase(s) && node.isVisibleToUser()) {
                return node;
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findSeeMoreTextNodeDeep(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private AccessibilityNodeInfo findClickableAncestorBounded(AccessibilityNodeInfo node, int maxDepth) {
        AccessibilityNodeInfo cur = node;
        for (int d = 0; d < maxDepth && cur != null; d++) {
            if (cur.isClickable()) {
                return cur;
            }
            cur = cur.getParent();
        }
        return null;
    }

    /**
     * Smallest clickable ancestor whose bounds contain the ⋮ desc node — avoids activating the whole row
     * (which can open the track page) when a parent row is also clickable.
     */
    private AccessibilityNodeInfo findSmallestClickableAncestorContainingDesc(AccessibilityNodeInfo descNode, int maxDepth) {
        if (descNode == null) return null;
        Rect desc = new Rect();
        descNode.getBoundsInScreen(desc);
        if (desc.isEmpty()) {
            return findClickableAncestorBounded(descNode, maxDepth);
        }
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        AccessibilityNodeInfo cur = descNode;
        for (int d = 0; d < maxDepth && cur != null; d++) {
            if (cur.isClickable()) {
                Rect cr = new Rect();
                cur.getBoundsInScreen(cr);
                if (!cr.isEmpty() && cr.contains(desc)) {
                    long area = (long) cr.width() * (long) cr.height();
                    if (area < bestArea) {
                        bestArea = area;
                        best = cur;
                    }
                }
            }
            cur = cur.getParent();
        }
        return best != null ? best : findClickableAncestorBounded(descNode, maxDepth);
    }

    private boolean isSpotifyOverflowMenuProbablyOpen() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;
        for (String needle : OVERFLOW_MENU_OPEN_NEEDLES) {
            AccessibilityNodeInfo n = findNodeByProperty(root, needle, false);
            if (n != null && n.isVisibleToUser()) {
                return true;
            }
            n = findNodeByProperty(root, needle, true);
            if (n != null && n.isVisibleToUser()) {
                return true;
            }
        }
        return false;
    }

    private boolean isSeeLessVisibleDeep() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;
        AccessibilityNodeInfo node = findTextNodeDeep(root, "See less", 0);
        return node != null && node.isVisibleToUser();
    }

    private AccessibilityNodeInfo findTextNodeDeep(AccessibilityNodeInfo node, String targetText, int depth) {
        if (node == null || targetText == null || depth > 140) return null;
        CharSequence t = node.getText();
        if (t != null && targetText.equalsIgnoreCase(t.toString().trim()) && node.isVisibleToUser()) {
            return node;
        }
        CharSequence d = node.getContentDescription();
        if (d != null && targetText.equalsIgnoreCase(d.toString().trim()) && node.isVisibleToUser()) {
            return node;
        }
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findTextNodeDeep(child, targetText, depth + 1);
            if (found != null) return found;
        }
        return null;
    }


    // ==================== INTERACT (skip + overlays) ====================

    public void dismissBlockingOverlaysAggressive() {
        for (int i = 0; i < 5; i++) {
            if (!tryDismissBlockingOverlays()) {
                break;
            }
            sleep(320);
        }
        AccessibilityNodeInfo r = getRoot();
        if (r != null) {
            String cls = r.getClassName() != null ? r.getClassName().toString() : "";
            String blob = collectTextRecursive(r).toLowerCase(Locale.ROOT);
            if (cls.contains("Dialog")
                    || blob.contains("permission")
                    || blob.contains("allow spotify")
                    || blob.contains("something went wrong")) {
                MyAccessibilityService svc = MyAccessibilityService.getInstance();
                if (svc != null) {
                    svc.performGlobalBack();
                    sleep(450);
                }
            }
        }
    }

    // ==================== SKIP — NEW CLEAN FLOW ====================

    /**
     * Heavy entry point: aggressive dismiss pehle, phir V2 flow.
     * BotService jab explicit skip command bhejta hai toh yeh call karo.
     */
    public boolean skipToNextTrack() {
        dismissBlockingOverlaysAggressive();
        return skipToNextTrackV2();
    }

    /**
     * Light entry point: sirf ek light dismiss, phir V2 flow.
     * Listen phase ke andar random skip ke liye yeh call karo.
     */
    public boolean skipToNextTrackLight() {
        tryDismissBlockingOverlays();
        return skipToNextTrackV2();
    }

    /**
     * Before skip, wait a short bounded time for blocking popups to clear.
     */
    public boolean waitForBlockingOverlayToClear(long maxWaitMs) {
        long end = System.currentTimeMillis() + Math.max(0L, maxWaitMs);
        while (System.currentTimeMillis() < end && !isCancelled() && !isSessionDeadlinePassed()) {
            if (!isLikelyBlockingPopupVisible()) {
                return true;
            }
            tryDismissBlockingOverlays();
            sleep(260);
        }
        return !isLikelyBlockingPopupVisible();
    }

    /**
     * Skip se pehle 1 ya 2 bar random scroll (forward/backward). Ek direction stuck ho to doosri try.
     * "See less" visible = expanded bio/section — upar wale tracks scroll se hiltay hain; yahan scroll mat karo.
     */
    private void performPreSkipRandomScrolls() {
        if (isSeeLessVisibleDeep()) {
            return;
        }
        int scrollCount = 1 + (int) (Math.random() * 2); // 1 or 2
        for (int i = 0; i < scrollCount && !isCancelled() && !isSessionDeadlinePassed(); i++) {
            boolean forward = Math.random() < 0.5;
            AccessibilityNodeInfo r = getRoot();
            if (r == null) return;
            boolean moved = scrollSearchResultsDirectional(r, forward);
            if (!moved) {
                forward = !forward;
                r = getRoot();
                if (r == null) return;
                scrollSearchResultsDirectional(r, forward);
            }
            sleepUnlessCancelled(380L + (long) (Math.random() * 220L));
        }
    }

    /**
     * Artist mode gate: do not continue until See more is expanded.
     * 1) If See more already visible, click immediately.
     * 2) Else do exactly one controlled scroll, then click once more.
     */
    private boolean performArtistSeeMoreExpandOnce() {
        if (clickSeeMoreIfVisible()) {
            return true;
        }
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;
        // Keep artist flow same; only avoid further down drift when "See less" is visible.
        boolean seeLessVisible = isSeeLessVisibleDeep();
        boolean moved = scrollSearchResultsDirectional(root, !seeLessVisible);
        if (!moved) {
            Log.d(TAG, "ARTIST_SEE_MORE no scroll movement before click attempt");
        }
        sleepUnlessCancelled(320L + (long) (Math.random() * 200L));
        boolean expanded = clickSeeMoreIfVisible();
        if (!expanded) {
            Log.d(TAG, "ARTIST_SEE_MORE not found/clicked after one controlled scroll");
        }
        return expanded;
    }

    /**
     * Core skip logic — dono entry points yahan aate hain.
     *
     * Flow:
     * 0. Har skip command par pehle 1 ya 2 bar random direction scroll (up/down), phir ruk jao
     * 1. Current screen pe visible rows check karo (wahi collectSongRowsLoose + pickRandomAndPlay)
     * 2. Agar rows milein → random pick + play
     * 3. Agar nahi milein ya play fail ho → scrollAndPickRandom(3)
     */
    private boolean skipToNextTrackV2() {
        if (isCancelled() || isSessionDeadlinePassed()) return false;

        if ("artist".equals(currentPlayType)) {
            // Artist: prevent drift; expand See more first with max one controlled scroll.
            boolean expanded = performArtistSeeMoreExpandOnce();
            if (!expanded) {
                Log.w(TAG, "SKIP_V2 artist mode blocked: See more not expanded");
                return false;
            }
        } else {
            performPreSkipRandomScrolls();
        }
        // See less on screen = list upar fixed rehni chahiye; lambi wait skip karo taake jaldi track tap ho sake
        if (isSeeLessVisibleDeep()) {
            if (!sleepUnlessCancelled(380L + (long) (Math.random() * 420L))) return false;
        } else {
            if (!waitRandomAfterScroll()) return false;
        }

        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;

        // Step 1: Current screen pe sample karo
        List<AccessibilityNodeInfo> candidates = collectSongRowsLoose(root);

        if (!candidates.isEmpty()) {
            boolean played = pickRandomAndPlay(candidates);
            recycleList(candidates);
            if (played) {
                sleep(550);
                Log.i(TAG, "SKIP_V2 played from current screen (after pre-skip scroll)");
                return true;
            }
        } else {
            recycleList(candidates);
        }

        // Step 2: Scroll karke try karo
        Log.d(TAG, "SKIP_V2 current screen empty/failed, trying scrollAndPickRandom");
        return scrollAndPickRandom(3);
    }

    /**
     * API-compatible replacement for {@code AccessibilityNodeInfo.findAccessibilityNodeInfosByClassName}
     * (added in API 21). Walks the tree; recycles non-matching child nodes; matching nodes are added to
     * {@code out} and must be recycled by the caller when no longer needed. Does not recycle {@code root}.
     */
    private void collectNodesByClassName(AccessibilityNodeInfo node, String className,
                                         List<AccessibilityNodeInfo> out, int depth) {
        if (node == null || depth > 200) return;
        CharSequence cn = node.getClassName();
        if (cn != null && className.contentEquals(cn.toString())) {
            out.add(node);
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo ch = node.getChild(i);
            if (ch != null) {
                collectNodesByClassName(ch, className, out, depth + 1);
                if (!out.contains(ch)) {
                    ch.recycle();
                }
            }
        }
    }

    /**
     * Visible song title TextViews collect karo — class-based search.
     *
     * Screenshot se pata chala ke Spotify playlist screen pe generic android.view.View
     * use hoti hai — koi Spotify-specific row ID nahi hoti. Isliye findAccessibilityNodeInfosByViewId
     * kuch return nahi karta.
     *
     * Naya approach:
     * - Saare android.widget.TextView nodes dhoondhо by class name
     * - Screen ke content zone mein filter karo (top nav aur bottom nav bar exclude)
     * - Duplicate Y-positions filter karo (title + subtitle ek hi row mein hain)
     * - Sirf title-looking TextViews rakho (pehla TextView har row group mein)
     *
     * Return: title TextView nodes ki list — inhi pe tapAt() karunga
     */
    private static boolean isListChromeOrNonTrackTitle(String txt) {
        if (txt == null) return true;
        String t = txt.trim().toLowerCase(Locale.ROOT);
        if (t.length() < 2) return true;
        if ("see less".equals(t) || "see more".equals(t)) return true;
        if ("popular".equals(t) || "discography".equals(t)) return true;
        if (t.startsWith("about ")) return true;
        if (t.contains("monthly listeners") || t.contains("followers")) return true;
        if (t.contains("fans also like") || t.contains("more by ")) return true;
        if (t.contains("appears on")) return true;
        return false;
    }

    private List<AccessibilityNodeInfo> collectSongRowsLoose(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;

        float density = context.getResources().getDisplayMetrics().density;
        int screenH = context.getResources().getDisplayMetrics().heightPixels;

        // Top nav area chhodhо (status bar + toolbar ~ top 15%)
        int topCutoff = (int) (screenH * 0.15f);
        // Bottom nav bar chhodhо (mini player + nav ~ bottom 22%)
        int bottomCutoff = (int) (screenH * 0.78f);

        // Saare TextViews dhoondhо (manual walk — findAccessibilityNodeInfosByClassName is API 21+)
        List<AccessibilityNodeInfo> allTextViews = new ArrayList<>();
        collectNodesByClassName(root, "android.widget.TextView", allTextViews, 0);
        if (allTextViews.isEmpty()) return out;

        // Sirf woh TextViews rakho jo content zone mein hain aur non-empty hain
        List<AccessibilityNodeInfo> contentZone = new ArrayList<>();
        for (AccessibilityNodeInfo tv : allTextViews) {
            if (tv == null || !tv.isVisibleToUser()) continue;
            CharSequence text = tv.getText();
            if (text == null || text.toString().trim().isEmpty()) continue;

            Rect bounds = new Rect();
            tv.getBoundsInScreen(bounds);
            if (bounds.isEmpty()) continue;

            // Content zone filter
            if (bounds.top < topCutoff || bounds.bottom > bottomCutoff) continue;

            // Bahut chhotay strings skip karo
            String txt = text.toString().trim();
            if (txt.length() < 2) continue;
            if (isListChromeOrNonTrackTitle(txt)) continue;

            contentZone.add(tv);
        }
        for (AccessibilityNodeInfo tv : allTextViews) {
            if (tv != null && !contentZone.contains(tv)) {
                tv.recycle();
            }
        }

        if (contentZone.isEmpty()) return out;

        // Y-position ke hisaab se sort karo
        Collections.sort(contentZone, new Comparator<AccessibilityNodeInfo>() {
            @Override
            public int compare(AccessibilityNodeInfo a, AccessibilityNodeInfo b) {
                Rect ra = new Rect(), rb = new Rect();
                a.getBoundsInScreen(ra);
                b.getBoundsInScreen(rb);
                return Integer.compare(ra.top, rb.top);
            }
        });

        // Har unique Y-group mein se sirf pehla TextView rakho (woh title hoga)
        // Title + subtitle ek hi row mein hote hain — 20px ke andar
        int lastRowTop = -999;
        for (AccessibilityNodeInfo tv : contentZone) {
            Rect bounds = new Rect();
            tv.getBoundsInScreen(bounds);
            if (bounds.top - lastRowTop > 20) {
                out.add(tv);
                lastRowTop = bounds.top;
            }
        }

        // "See less" ke neeche bio / fluff tap na ho; upar wale track titles prefer karo
        AccessibilityNodeInfo seeLessNode = findTextNodeDeep(root, "See less", 0);
        if (seeLessNode != null && seeLessNode.isVisibleToUser()) {
            Rect sr = new Rect();
            seeLessNode.getBoundsInScreen(sr);
            int margin = (int) (8f * density + 0.5f);
            int thresholdY = sr.top - margin;
            List<AccessibilityNodeInfo> above = new ArrayList<>();
            for (AccessibilityNodeInfo tv : out) {
                Rect b = new Rect();
                tv.getBoundsInScreen(b);
                if (b.bottom <= thresholdY) {
                    above.add(tv);
                }
            }
            if (!above.isEmpty()) {
                out = above;
                Log.d(TAG, "COLLECT_ROWS_LOOSE SEE_LESS keptAbove=" + out.size() + " thresholdY=" + thresholdY);
            }
        }

        // Baki recycle karo jo out mein nahi gaye
        for (AccessibilityNodeInfo tv : contentZone) {
            if (!out.contains(tv)) tv.recycle();
        }

        Log.d(TAG, "COLLECT_ROWS_LOOSE found=" + out.size()
                + " screenH=" + screenH + " topCut=" + topCutoff + " botCut=" + bottomCutoff);
        return out;
    }

    /**
     * collectSongRowsLoose se mili TextView nodes mein se ek random chunke tapAt() karo.
     *
     * Ab nodes = title TextViews hain (row containers nahi).
     * Inki bounds directly screen pe sahi jagah point karti hain.
     * tapAt() OS-level real touch simulate karta hai — bilkul manually tap karne jaisa.
     */
    private boolean pickRandomAndPlay(List<AccessibilityNodeInfo> rows) {
        if (rows == null || rows.isEmpty()) return false;

        // Sirf actually visible nodes
        List<AccessibilityNodeInfo> visible = new ArrayList<>();
        for (AccessibilityNodeInfo r : rows) {
            if (r != null && r.isVisibleToUser()) visible.add(r);
        }
        if (visible.isEmpty()) return false;

        int pick = (int) (Math.random() * visible.size());
        AccessibilityNodeInfo chosen = visible.get(pick);
        long now = System.currentTimeMillis();
        // Avoid repeatedly tapping the same title in quick succession when multiple candidates exist.
        if (visible.size() > 1 && now - lastSkipPickedAtMs < 20_000L) {
            for (int i = 0; i < visible.size(); i++) {
                AccessibilityNodeInfo candidate = visible.get((pick + i) % visible.size());
                CharSequence tx = candidate != null ? candidate.getText() : null;
                String t = tx != null ? tx.toString().trim().toLowerCase(Locale.ROOT) : "";
                if (!t.isEmpty() && !t.equals(lastSkipPickedTitle)) {
                    chosen = candidate;
                    pick = (pick + i) % visible.size();
                    break;
                }
            }
        }

        Rect bounds = new Rect();
        chosen.getBoundsInScreen(bounds);

        Log.d(TAG, "SKIP_PICK index=" + pick + " of " + visible.size()
                + " text=" + chosen.getText() + " bounds=" + bounds);

        if (bounds.isEmpty()) {
            Log.w(TAG, "SKIP_PICK bounds empty, skipping");
            return false;
        }

        int cx = bounds.centerX();
        int cy = bounds.centerY();

        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc == null) return false;

        // tapAt — OS level real touch, clickable check nahi hota
        if (svc.tapAt(cx, cy)) {
            Log.i(TAG, "SKIP_TAP_OK text=" + chosen.getText() + " x=" + cx + " y=" + cy);
            CharSequence tx = chosen.getText();
            lastSkipPickedTitle = tx != null ? tx.toString().trim().toLowerCase(Locale.ROOT) : "";
            lastSkipPickedAtMs = System.currentTimeMillis();
            return true;
        }

        Log.w(TAG, "SKIP_TAP_FAIL tapAt returned false for x=" + cx + " y=" + cy);
        return false;
    }

    /**
     * Random direction scroll karo, har scroll ke baad sample + play try karo.
     * Dono directions fail ho jayen toh bahar niklo.
     */
    private boolean scrollAndPickRandom(int maxScrollOps) {
        boolean forward = Math.random() < 0.5;

        for (int i = 0; i < maxScrollOps; i++) {
            if (isCancelled() || isSessionDeadlinePassed()) return false;

            boolean seeLessVisible = isSeeLessVisibleDeep();
            if (seeLessVisible) {
                AccessibilityNodeInfo rSnap = getRoot();
                if (rSnap != null) {
                    List<AccessibilityNodeInfo> snap = collectSongRowsLoose(rSnap);
                    if (!snap.isEmpty() && pickRandomAndPlay(snap)) {
                        recycleList(snap);
                        sleep(550);
                        Log.i(TAG, "SKIP_SCROLL_OK see_less retry before scroll i=" + i);
                        return true;
                    }
                    recycleList(snap);
                }
            }

            // Scroll karo
            AccessibilityNodeInfo root = getRoot();
            if (root == null) return false;
            if (seeLessVisible) {
                forward = false;
                Log.d(TAG, "SEE_LESS visible -> forcing upward scroll");
            }

            boolean moved = scrollSearchResultsDirectional(root, forward);

            // Agar ek direction stuck hai toh doosri try karo
            if (!moved) {
                if (seeLessVisible) {
                    // Strict rule: do not fallback to downward scroll under SEE_LESS.
                    root = getRoot();
                    if (root == null) return false;
                    List<AccessibilityNodeInfo> candidates = collectSongRowsLoose(root);
                    if (!candidates.isEmpty()) {
                        boolean played = pickRandomAndPlay(candidates);
                        recycleList(candidates);
                        if (played) {
                            sleep(550);
                            Log.i(TAG, "SKIP_SCROLL_OK played without down-fallback under SEE_LESS");
                            return true;
                        }
                    } else {
                        recycleList(candidates);
                    }
                    continue;
                }

                forward = !forward;
                root = getRoot();
                if (root == null) return false;
                moved = scrollSearchResultsDirectional(root, forward);
            }

            // Dono directions stuck — kuch nahi karna
            if (!moved) {
                Log.d(TAG, "SKIP_SCROLL both directions stuck at i=" + i);
                break;
            }

            if (seeLessVisible) {
                if (!sleepUnlessCancelled(260L + (long) (Math.random() * 360L))) return false;
            } else {
                if (!waitRandomAfterScroll()) return false;
            }

            // Scroll ke baad sample karo
            root = getRoot();
            if (root == null) return false;
            List<AccessibilityNodeInfo> candidates = collectSongRowsLoose(root);

            if (!candidates.isEmpty()) {
                boolean played = pickRandomAndPlay(candidates);
                recycleList(candidates);
                if (played) {
                    sleep(550);
                    Log.i(TAG, "SKIP_SCROLL_OK scroll_op=" + i + " direction=" + (forward ? "fwd" : "bwd"));
                    return true;
                }
            } else {
                recycleList(candidates);
            }
        }

        Log.w(TAG, "SKIP_V2 scrollAndPickRandom failed after " + maxScrollOps + " ops");
        return false;
    }


    private boolean waitRandomAfterScroll() {
        long waitMs = 1000L + (long) (Math.random() * 2000L); // 1..3s
        return sleepUnlessCancelled(waitMs);
    }

    private boolean scrollSearchResultsBackward(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 36) {
            return false;
        }
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            return true;
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo ch = node.getChild(i);
            if (scrollSearchResultsBackward(ch, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean scrollSearchResultsDirectional(AccessibilityNodeInfo root, boolean forward) {
        return forward ? scrollSearchResultsForward(root, 0) : scrollSearchResultsBackward(root, 0);
    }

    public boolean skipToNextTrackOld() {
        dismissBlockingOverlaysAggressive();
        return skipToNextTrackLight();
    }

    public boolean skipToNextTrack_legacy() {
        tryDismissBlockingOverlays();
        if (tryPlayRandomVisibleSongFromPlaylistRows_legacy()) {
            sleep(550);
            return true;
        }
        Log.w(TAG, "SKIP_LIGHT no visible song row selected; direct next fallback disabled");
        return false;
    }

    private boolean tryPlayRandomVisibleSongFromPlaylistRows_legacy() {
        final int maxPasses = 6;
        final int maxTotalScrollOps = 8;
        int totalScrollOps = 0;
        String lastFingerprint = "";
        boolean preferForward = Math.random() < 0.5;

        for (int pass = 0; pass < maxPasses && !isCancelled() && !isSessionDeadlinePassed(); pass++) {
            AccessibilityNodeInfo root = getRoot();
            if (root == null) return false;

            List<AccessibilityNodeInfo> candidates = collectVisiblePlaylistSongRows(root);
            try {
                if (!candidates.isEmpty()) {
                    int pick = (int) (Math.random() * candidates.size());
                    AccessibilityNodeInfo row = candidates.get(pick);
                    if (row != null && row.isVisibleToUser() && clickPlayableControlInsideRowOrRow(row)) {
                        Log.i(TAG, "SKIP_VISIBLE_ROW_OK pass=" + pass + " pick=" + pick + " size=" + candidates.size());
                        return true;
                    }
                }
            } finally {
                recycleList(candidates);
            }

            if (totalScrollOps >= maxTotalScrollOps) break;
            int randomSteps = 1 + (int) (Math.random() * 3);
            for (int step = 0; step < randomSteps && totalScrollOps < maxTotalScrollOps; step++) {
                root = getRoot();
                if (root == null) return false;
                String before = buildVisibleViewportFingerprint(root);
                boolean moved = scrollSearchResultsDirectional(root, preferForward);
                totalScrollOps++;
                if (!moved) {
                    preferForward = !preferForward;
                    root = getRoot();
                    if (root == null) return false;
                    moved = scrollSearchResultsDirectional(root, preferForward);
                    totalScrollOps++;
                    if (!moved) break;
                }
                if (!sleepUnlessCancelled(420)) return false;

                root = getRoot();
                if (root == null) return false;
                String after = buildVisibleViewportFingerprint(root);
                if (!before.isEmpty() && before.equals(after) && before.equals(lastFingerprint)) {
                    preferForward = !preferForward;
                    break;
                }
                lastFingerprint = after;

                List<AccessibilityNodeInfo> fresh = collectVisiblePlaylistSongRows(root);
                try {
                    if (!fresh.isEmpty()) {
                        int pick = (int) (Math.random() * fresh.size());
                        AccessibilityNodeInfo row = fresh.get(pick);
                        if (row != null && row.isVisibleToUser() && clickPlayableControlInsideRowOrRow(row)) {
                            Log.i(TAG, "SKIP_VISIBLE_ROW_AFTER_SCROLL_OK pass=" + pass + " step=" + step);
                            return true;
                        }
                    }
                } finally {
                    recycleList(fresh);
                }
            }
        }
        return false;
    }

    private List<AccessibilityNodeInfo> collectVisiblePlaylistSongRows(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        for (String rowId : RESULT_ROW_IDS) {
            List<AccessibilityNodeInfo> rows = root.findAccessibilityNodeInfosByViewId(rowId);
            if (rows == null || rows.isEmpty()) continue;
            for (AccessibilityNodeInfo row : rows) {
                if (row == null || !row.isVisibleToUser()) continue;
                AccessibilityNodeInfo titleN = findDescendantWithViewId(row, ID_RESULT_TITLE, 0);
                AccessibilityNodeInfo subN = findDescendantWithViewId(row, ID_RESULT_SUBTITLE, 0);
                try {
                    String titleLower = combinedNodeTextLower(titleN);
                    String subLower = combinedNodeTextLower(subN);
                    if (titleLower.isEmpty()) continue;
                    boolean subtitleLooksSong = !subLower.isEmpty() && subtitleMatchesCategoryLabel(subLower, "song");
                    if (!subtitleLooksSong && !hasInlinePlayControl(row, 0)) continue;
                    out.add(row);
                } finally {
                    if (titleN != null) titleN.recycle();
                    if (subN != null) subN.recycle();
                }
            }
        }
        return out;
    }

    private boolean clickPlayableControlInsideRowOrRow(AccessibilityNodeInfo row) {
        if (row == null || !row.isVisibleToUser()) return false;
        if (clickPlayControlInsideRow(row)) return true;
        return performClickOnNodeOrAncestor(row);
    }

    private boolean hasInlinePlayControl(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 18) return false;
        if (node.isClickable()) {
            String label = combinedAccessibleLabel(node);
            if (isLikelyTrackRowPlayLabel(label)) {
                return true;
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            AccessibilityNodeInfo c = node.getChild(i);
            if (hasInlinePlayControl(c, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private String buildVisibleViewportFingerprint(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> rows = collectVisiblePlaylistSongRows(root);
        try {
            if (rows.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int limit = Math.min(3, rows.size());
            for (int i = 0; i < limit; i++) {
                AccessibilityNodeInfo titleN = findDescendantWithViewId(rows.get(i), ID_RESULT_TITLE, 0);
                try {
                    String t = normalizeSpacesLower(combinedNodeTextLower(titleN));
                    if (!t.isEmpty()) sb.append(t).append('|');
                } finally {
                    if (titleN != null) titleN.recycle();
                }
            }
            return sb.toString();
        } finally {
            recycleList(rows);
        }
    }

    public boolean skipToNextTrack_full() {
        dismissBlockingOverlaysAggressive();
        return skipToNextTrack_legacy();
    }

    // ==================== PHASE 3: PAUSE & CLOSE ====================

    public void bringSpotifyToForegroundQuick() {
        try {
            Intent intent = resolveLauncherIntent(context, SPOTIFY_PACKAGE);
            if (intent == null) {
                intent = resolveLauncherIntent(context, SPOTIFY_LITE_PACKAGE);
            }
            if (intent == null) return;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startExternalAppBal(intent);
        } catch (Exception e) {
            Log.e(TAG, "bringSpotifyToForegroundQuick: " + e.getMessage());
        }
    }

    public boolean pauseSpotifyPlayback() {
        if (dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE, "MEDIA_PAUSE")) {
            sleep(400);
            return true;
        }
        if (dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "PLAY_PAUSE")) {
            sleep(400);
            return true;
        }

        bringSpotifyToForegroundQuick();
        sleep(1500);

        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;

        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(ID_BUTTON_PLAY_PAUSE);
        if (nodes == null || nodes.isEmpty()) {
            Log.w(TAG, "SPOTIFY_PAUSE no button_play_and_pause on active root");
            return false;
        }

        AccessibilityNodeInfo btn = nodes.get(0);
        boolean ok = false;
        try {
            CharSequence cd = btn.getContentDescription();
            if (cd != null) {
                String d = cd.toString().toLowerCase();
                if (d.contains("pause")) {
                    ok = performClickOnNodeOrAncestor(btn);
                    if (ok) Log.i(TAG, "SPOTIFY_PAUSE clicked (control was Pause / playing)");
                    return ok;
                }
                if (d.contains("play")) {
                    Log.d(TAG, "SPOTIFY_PAUSE skip (already shows Play / paused)");
                    return true;
                }
            }
            ok = performClickOnNodeOrAncestor(btn);
            return ok;
        } finally {
            recycleList(nodes);
        }
    }

    private boolean dispatchMediaKey(int keyCode, String logName) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;

            long t = System.currentTimeMillis();
            KeyEvent down = new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0);
            KeyEvent up = new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0);
            am.dispatchMediaKeyEvent(down);
            am.dispatchMediaKeyEvent(up);

            Log.i(TAG, "SPOTIFY_PAUSE " + logName + " dispatched");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "dispatchMediaKey: " + e.getMessage());
            return false;
        }
    }

    public boolean closeSpotifyApp() {
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(home);
            sleep(900);

            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                am.killBackgroundProcesses(SPOTIFY_PACKAGE);
                Log.i(TAG, "SPOTIFY_CLOSE HOME then killBackgroundProcesses " + SPOTIFY_PACKAGE);
                sleep(600);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "closeSpotifyApp: " + e.getMessage());
        }
        return false;
    }

    private boolean clickNodeByBoundsCenter(AccessibilityNodeInfo node) {
        if (node == null) return false;
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc == null) return false;
        ensureNodeVisibleBeforeClick(node);
        if (!node.isVisibleToUser()) return false;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        if (r.isEmpty()) return false;
        return svc.tapAt(r.centerX(), r.centerY());
    }

    private void closeContextMenuIfNeeded() {
        sleepUnlessCancelled(200L);
        if (!isSpotifyOverflowMenuProbablyOpen()) {
            sleepUnlessCancelled(280L);
            if (!isSpotifyOverflowMenuProbablyOpen()) {
                Log.d(TAG, "CTX_MENU_CLOSE skip BACK (overflow sheet not visible — likely already dismissed)");
                return;
            }
        }
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc != null) {
            svc.performGlobalBack();
            sleepUnlessCancelled(240L);
        }
    }

    public String detectBlockingScreenHint() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return null;
        String blob = collectTextRecursive(root).toLowerCase();
        if (blob.contains("log in") || blob.contains("sign in") || blob.contains("login")) {
            return "LOGIN_REQUIRED";
        }
        if (blob.contains("no internet") || blob.contains("offline") || blob.contains("connection")) {
            return "OFFLINE_OR_NETWORK";
        }
        return null;
    }

    public void pressBackUntilPlaying(int maxBacks) {
        MyAccessibilityService svc = MyAccessibilityService.getInstance();
        if (svc == null) return;
        for (int i = 0; i < maxBacks; i++) {
            if (verifyPlaying()) return;
            svc.performGlobalBack();
            sleep(450);
        }
    }

    public boolean tryDismissBlockingOverlays() {
        if (!isLikelyBlockingPopupVisible()) {
            return false;
        }
        String[] labels = {
                "OK", "Ok", "Close", "Dismiss", "Got it", "Allow", "Not now", "No thanks",
                "Cancel", "I understand", "Continue", "Maybe later", "Later", "Skip",
                "No, thanks", "Don't show again", "Hide", "Agree", "Accept",
                "Not interested", "Turn off", "Leave", "Done", "Thanks"
        };
        for (String label : labels) {
            if (clickTextExact(label)) {
                sleep(350);
                return true;
            }
        }
        return false;
    }

    private boolean isLikelyBlockingPopupVisible() {
        AccessibilityNodeInfo root = getRoot();
        if (root == null) return false;
        String cls = root.getClassName() != null ? root.getClassName().toString().toLowerCase(Locale.ROOT) : "";
        String blob = collectTextRecursive(root).toLowerCase(Locale.ROOT);
        return cls.contains("dialog")
                || blob.contains("permission")
                || blob.contains("allow")
                || blob.contains("not now")
                || blob.contains("something went wrong")
                || blob.contains("continue")
                || blob.contains("got it")
                || blob.contains("dismiss")
                || blob.contains("close");
    }
}
