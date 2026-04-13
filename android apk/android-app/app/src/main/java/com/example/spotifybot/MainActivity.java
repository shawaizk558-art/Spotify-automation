package com.example.spotifybot;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Setup screen. {@link BotService} is started only when the user taps
 * {@code Start background connection} — never automatically on launch — so the activity is not killed
 * by foreground-service / WebSocket work on the main thread.
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQ_POST_NOTIFICATIONS = 1001;

    /** Intent extra: BotService wakes the UI process so accessibility can bind (value ignored). */
    public static final String EXTRA_WAKE_FOR_A11Y = "EXTRA_WAKE_FOR_A11Y";

    private TextView statusView;
    private boolean botServiceStartedFromActivity;
    private boolean pendingStartAfterNotifFromUser;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Log.i(TAG, "onCreate");

        applyIntentConfig(getIntent());

        statusView = findViewById(R.id.txt_a11y_status);
        Button openBtn = findViewById(R.id.btn_open_accessibility);
        openBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettingsWithHint();
            }
        });

        Button startBtn = findViewById(R.id.btn_start_connection);
        startBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                userRequestedStartBackgroundService();
            }
        });

        updateAccessibilityStatusUi();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("pid", android.os.Process.myPid());
            d.put("thread", Thread.currentThread().getName());
            d.put("taskId", getTaskId());
            DebugSessionLog.agentLog("H_LIFECYCLE", "MainActivity.java:onCreate", "onCreate_end", d);
        } catch (Throwable ignored) {
        }
        // #endregion
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityStatusUi();
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("pid", android.os.Process.myPid());
            d.put("thread", Thread.currentThread().getName());
            d.put("hasWindowFocus", hasWindowFocus());
            DebugSessionLog.agentLog("H_LIFECYCLE", "MainActivity.java:onResume", "onResume", d);
        } catch (Throwable ignored) {
        }
        // #endregion
    }

    @Override
    protected void onPause() {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("pid", android.os.Process.myPid());
            d.put("thread", Thread.currentThread().getName());
            d.put("isFinishing", isFinishing());
            d.put("isChangingConfigurations", isChangingConfigurations());
            DebugSessionLog.agentLog("H2_H4", "MainActivity.java:onPause", "onPause", d);
        } catch (Throwable ignored) {
        }
        // #endregion
        super.onPause();
    }

    @Override
    protected void onStop() {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("pid", android.os.Process.myPid());
            d.put("isFinishing", isFinishing());
            d.put("isChangingConfigurations", isChangingConfigurations());
            DebugSessionLog.agentLog("H2_H5", "MainActivity.java:onStop", "onStop", d);
        } catch (Throwable ignored) {
        }
        // #endregion
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        // #region agent log
        try {
            JSONObject d = new JSONObject();
            d.put("pid", android.os.Process.myPid());
            d.put("isFinishing", isFinishing());
            d.put("isChangingConfigurations", isChangingConfigurations());
            DebugSessionLog.agentLog("H1_H3", "MainActivity.java:onDestroy", "onDestroy", d);
        } catch (Throwable ignored) {
        }
        // #endregion
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyIntentConfig(intent);
        startBotService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_POST_NOTIFICATIONS || !pendingStartAfterNotifFromUser) {
            return;
        }
        pendingStartAfterNotifFromUser = false;
        if (!isMyAccessibilityServiceEnabled()) {
            Toast.makeText(this, R.string.main_need_a11y_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (botServiceStartedFromActivity) {
            return;
        }
        botServiceStartedFromActivity = true;
        startBotService();
    }

    /** User explicitly starts the foreground bot (after accessibility is on). */
    private void userRequestedStartBackgroundService() {
        if (botServiceStartedFromActivity) {
            Toast.makeText(this, R.string.main_already_started, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isMyAccessibilityServiceEnabled()) {
            Toast.makeText(this, R.string.main_need_a11y_first, Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                pendingStartAfterNotifFromUser = true;
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_POST_NOTIFICATIONS);
                return;
            }
        }
        botServiceStartedFromActivity = true;
        startBotService();
    }

    private void openAccessibilitySettingsWithHint() {
        Toast.makeText(this, R.string.main_a11y_toast, Toast.LENGTH_LONG).show();
        try {
            Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            // Do not use FLAG_ACTIVITY_NEW_TASK from an Activity — avoids odd task behaviour on some OEMs.
            startActivity(i);
        } catch (Exception e) {
            Log.e(TAG, "Cannot open accessibility settings: " + e.getMessage());
        }
    }

    private void updateAccessibilityStatusUi() {
        if (statusView == null) {
            return;
        }
        statusView.setText(isMyAccessibilityServiceEnabled()
                ? R.string.main_a11y_status_on
                : R.string.main_a11y_status_off);
    }

    private boolean isMyAccessibilityServiceEnabled() {
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null || enabled.isEmpty()) {
                return false;
            }
            ComponentName cn = new ComponentName(this, MyAccessibilityService.class);
            String flat = cn.flattenToString();
            String lower = enabled.toLowerCase(Locale.ROOT);
            return lower.contains(flat.toLowerCase(Locale.ROOT))
                    || lower.contains(MyAccessibilityService.class.getName().toLowerCase(Locale.ROOT));
        } catch (Exception e) {
            return false;
        }
    }

    private void applyIntentConfig(Intent intent) {
        if (intent == null) {
            return;
        }
        String url = intent.getStringExtra("backend_ws_url");
        if (url != null && !url.trim().isEmpty()) {
            getSharedPreferences(ConnectionPrefs.NAME, MODE_PRIVATE).edit()
                    .putString(ConnectionPrefs.KEY_BACKEND_WS_URL, url.trim())
                    .apply();
            Log.i(TAG, "Saved backend_ws_url from intent");
        }
        String token = intent.getStringExtra("device_auth_token");
        if (token != null && !token.trim().isEmpty()) {
            getSharedPreferences(ConnectionPrefs.NAME, MODE_PRIVATE).edit()
                    .putString(ConnectionPrefs.KEY_DEVICE_AUTH_TOKEN, token.trim())
                    .apply();
            Log.i(TAG, "Saved device_auth_token from intent");
        }
    }

    private void startBotService() {
        Intent svc = new Intent();
        svc.setClassName(getPackageName(), "com.example.spotifybot.BotService");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
        Log.i(TAG, "BotService start requested");
    }
}
