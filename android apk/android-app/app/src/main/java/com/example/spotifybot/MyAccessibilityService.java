package com.example.spotifybot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Build;
import android.os.Process;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

public class MyAccessibilityService extends AccessibilityService {

    private static final String TAG = "MyAccessibilityService";
    private static volatile MyAccessibilityService instance;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not needed for now
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.e(TAG, "Accessibility onCreate pid=" + Process.myPid());
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.e(TAG, "Accessibility onServiceConnected pid=" + Process.myPid());
    }

    @Override
    public void onDestroy() {
        instance = null;
        Log.w(TAG, "Accessibility Service onDestroy (instance cleared)");
        super.onDestroy();
    }

    public static MyAccessibilityService getInstance() {
        return instance;
    }

    public AccessibilityNodeInfo getRootNode() {
        return getRootInActiveWindow();
    }

    /** Dismiss blocking sheets / stray screens (document: popups, unknown UI). */
    public boolean performGlobalBack() {
        return performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** Best-effort tap by absolute screen coordinates (short press). */
    public boolean tapAt(int x, int y) {
        return tapAt(x, y, 80);
    }

    /**
     * Same as {@link #tapAt(int, int)} but stroke duration in ms (50–350).
     * Slightly longer presses help Spotify register ⋯ / trailing targets after scroll.
     */
    public boolean tapAt(int x, int y, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        long d = Math.max(50L, Math.min(350L, durationMs));
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(p, 0, d);
            GestureDescription g = new GestureDescription.Builder().addStroke(stroke).build();
            return dispatchGesture(g, null, null);
        } catch (Exception e) {
            Log.w(TAG, "tapAt failed: " + e.getMessage());
            return false;
        }
    }
}