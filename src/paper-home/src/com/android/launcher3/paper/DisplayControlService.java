package com.android.launcher3.paper;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.android.launcher3.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Calendar;

/**
 * Small persistent e-ink control palette shown above reader applications.
 * It only writes command files in Paper Home's private directory; the native
 * panel bridge remains the sole owner of reMarkable display ioctls.
 */
public final class DisplayControlService extends Service {
    private static final String TAG = "PaperDisplayControl";
    private static final String PROFILE_FILE = "paper-display-mode";
    private static final String COLOR_FILE = "paper-color-mode";
    private static final String INVERT_FILE = "paper-invert-mode";
    private static final String REFRESH_FILE = "paper-refresh-request";
    private static final String FRONTLIGHT_FILE = "paper-frontlight-level";
    private static final String SCREEN_STATE_FILE = "paper-screen-state";
    private static final String NOTE_ACTIVE_FILE = "paper-note-active";
    private static final String AUTO_POWEROFF_FILE =
            "paper-auto-poweroff-minutes";
    private static final int FRONTLIGHT_STEP = 32;
    /*
     * The automatic light policy only changes at hour boundaries. Waking the
     * persistent launcher every minute added needless idle work, especially
     * while a reader app was open or the screen was off.
     */
    private static final long AUTOMATIC_SYNC_INTERVAL_MS = 15 * 60_000L;

    private WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable frontlightSync = new Runnable() {
        @Override
        public void run() {
            syncFrontlightFromSettings();
        }
    };
    private final Runnable automaticFrontlightSync = new Runnable() {
        @Override
        public void run() {
            if (screenInteractive && automaticFrontlightEnabled()) {
                syncFrontlightFromSettings();
                handler.postDelayed(this, AUTOMATIC_SYNC_INTERVAL_MS);
            }
        }
    };
    private final ContentObserver brightnessObserver =
            new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    scheduleFrontlightSync();
                    scheduleAutomaticFrontlightSync();
                }
            };
    private final Runnable overlayRetry = new Runnable() {
        @Override
        public void run() {
            ensureBubble();
            if (bubble == null) {
                handler.postDelayed(this, 3000L);
            }
        }
    };
    private final BroadcastReceiver screenReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        screenInteractive = false;
                        handler.removeCallbacks(automaticFrontlightSync);
                        writeControl(SCREEN_STATE_FILE, "off");
                        hideMenu();
                        Log.i(TAG, "Android screen-off forwarded to panel");
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        screenInteractive = true;
                        writeControl(SCREEN_STATE_FILE, "on");
                        scheduleFrontlightSync();
                        scheduleAutomaticFrontlightSync();
                        requestRefresh(false);
                        Log.i(TAG, "Android screen-on forwarded to panel");
                    }
                }
            };
    private BubbleView bubble;
    private View menu;
    private WindowManager.LayoutParams bubbleParams;
    private boolean brightnessObserverRegistered;
    private boolean screenReceiverRegistered;
    private boolean screenInteractive = true;

    @Override
    public void onCreate() {
        super.onCreate();
        // A crash or forced reboot can leave the note marker behind. Native
        // pen rendering must stay disabled until NoteActivity resumes again.
        new File(getFilesDir(), NOTE_ACTIVE_FILE).delete();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(
                            Settings.System.SCREEN_BRIGHTNESS),
                    false,
                    brightnessObserver);
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(
                            Settings.System.SCREEN_BRIGHTNESS_MODE),
                    false,
                    brightnessObserver);
            brightnessObserverRegistered = true;
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot observe Android brightness", error);
        }
        try {
            IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(Intent.ACTION_SCREEN_ON);
            registerReceiver(screenReceiver, screenFilter);
            screenReceiverRegistered = true;
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot observe Android screen state", error);
        }
        PowerManager powerManager =
                (PowerManager) getSystemService(POWER_SERVICE);
        screenInteractive =
                powerManager == null || powerManager.isInteractive();
        writeControl(
                SCREEN_STATE_FILE,
                screenInteractive ? "on" : "off");
        ensureControlDefault(COLOR_FILE, "auto");
        ensureControlDefault(AUTO_POWEROFF_FILE, "30");
        scheduleFrontlightSync();
        scheduleAutomaticFrontlightSync();
        handler.post(overlayRetry);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureBubble();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(overlayRetry);
        handler.removeCallbacks(frontlightSync);
        handler.removeCallbacks(automaticFrontlightSync);
        if (brightnessObserverRegistered) {
            try {
                getContentResolver().unregisterContentObserver(
                        brightnessObserver);
            } catch (RuntimeException ignored) {
            }
            brightnessObserverRegistered = false;
        }
        if (screenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (RuntimeException ignored) {
            }
            screenReceiverRegistered = false;
        }
        hideMenu();
        if (bubble != null) {
            try {
                windowManager.removeView(bubble);
            } catch (RuntimeException ignored) {
            }
            bubble = null;
        }
        super.onDestroy();
    }

    private void ensureBubble() {
        if (bubble != null || windowManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay app-op is not granted yet");
            return;
        }
        final int size = dp(56);
        bubble = new BubbleView();
        bubble.setBackgroundColor(Color.WHITE);
        bubbleParams = new WindowManager.LayoutParams(
                size,
                size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        bubbleParams.gravity = Gravity.END | Gravity.BOTTOM;
        bubbleParams.x = dp(8);
        bubbleParams.y = dp(18);
        try {
            windowManager.addView(bubble, bubbleParams);
            Log.i(TAG, "floating display control added");
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot add floating display control", error);
            bubble = null;
        }
    }

    private void toggleMenu() {
        if (menu != null) {
            hideMenu();
            return;
        }
        menu = createMenu();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(196),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.x = dp(70);
        try {
            windowManager.addView(menu, params);
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot add display control menu", error);
            menu = null;
        }
    }

    private void hideMenu() {
        if (menu == null) {
            return;
        }
        try {
            windowManager.removeView(menu);
        } catch (RuntimeException ignored) {
        }
        menu = null;
    }

    private View createMenu() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(5), dp(5), dp(5), dp(5));
        panel.setBackgroundColor(Color.WHITE);

        TextView title = menuRow(
                getString(
                        R.string.display_control_title,
                        profileLabel(),
                        colorModeLabel()),
                true);
        panel.addView(title);
        panel.addView(menuRow(getString(R.string.display_full_refresh), false,
                this::requestRefresh));
        panel.addView(menuRow(getString(R.string.display_fast_mode), false,
                () -> selectProfile("fast")));
        panel.addView(menuRow(
                getString(R.string.display_balanced_mode), false,
                () -> selectProfile("balanced")));
        panel.addView(menuRow(
                getString(R.string.display_quality_mode), false,
                () -> selectProfile("quality")));
        panel.addView(menuRow(
                "auto".equals(readControl(COLOR_FILE, "auto"))
                        ? getString(R.string.display_disable_auto_color)
                        : getString(R.string.display_enable_auto_color),
                false,
                this::toggleAutomaticColor));
        panel.addView(menuRow(getString(R.string.display_settle_color), false,
                this::settleColorNow));
        panel.addView(menuRow(getString(R.string.display_light_brighter), false,
                () -> adjustFrontlight(FRONTLIGHT_STEP)));
        panel.addView(menuRow(getString(R.string.display_light_dimmer), false,
                () -> adjustFrontlight(-FRONTLIGHT_STEP)));
        panel.addView(menuRow(
                automaticFrontlightEnabled()
                        ? getString(
                                R.string.display_disable_scheduled_light)
                        : getString(
                                R.string.display_enable_scheduled_light),
                false,
                this::toggleAutomaticFrontlight));
        int autoPoweroff = autoPoweroffMinutes();
        panel.addView(menuRow(
                autoPoweroff == 0
                        ? getString(R.string.display_poweroff_disabled)
                        : getString(
                                R.string.display_poweroff_minutes,
                                autoPoweroff),
                false,
                this::cycleAutoPoweroff));
        panel.addView(menuRow(getString(R.string.display_invert), false,
                this::toggleInvert));
        panel.addView(menuRow(getString(R.string.action_close), false,
                this::hideMenu));
        return panel;
    }

    private TextView menuRow(String label, boolean heading) {
        return menuRow(label, heading, null);
    }

    private TextView menuRow(String label, boolean heading, Runnable action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(Color.BLACK);
        row.setTextSize(heading ? 17f : 18f);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(10), 0);
        row.setBackgroundColor(Color.WHITE);
        row.setMinHeight(dp(heading ? 44 : 49));
        if (heading) {
            row.setPaintFlags(row.getPaintFlags() | Paint.FAKE_BOLD_TEXT_FLAG);
        }
        if (action != null) {
            row.setOnClickListener(view -> action.run());
        }
        return row;
    }

    private void selectProfile(String value) {
        if (writeControl(PROFILE_FILE, value)) {
            Toast.makeText(
                    this,
                    getString(
                            R.string.display_mode_selected,
                            profileLabelFor(value)),
                    Toast.LENGTH_SHORT).show();
        }
        hideMenu();
    }

    private void toggleInvert() {
        boolean currentlyInverted =
                !"normal".equals(readControl(INVERT_FILE, "normal"));
        writeControl(INVERT_FILE, currentlyInverted ? "normal" : "invert");
        requestRefresh();
        hideMenu();
    }

    private void toggleAutomaticColor() {
        boolean automatic =
                "auto".equals(readControl(COLOR_FILE, "auto"));
        if (writeControl(COLOR_FILE, automatic ? "mono" : "auto")) {
            Toast.makeText(
                    this,
                    automatic
                            ? getString(R.string.display_fixed_mono)
                            : getString(
                                    R.string.display_auto_color_enabled),
                    Toast.LENGTH_SHORT).show();
            /*
             * A full monochrome repaint removes an already-settled color
             * image when auto color is turned off. When it is turned on, the
             * bridge reuses the newest RGB frame and settles it after idle.
             */
            requestRefresh(false);
        }
        hideMenu();
    }

    private void settleColorNow() {
        String current = readControl(COLOR_FILE, "auto");
        String request = "mono".equals(current)
                ? "once-mono" : "once-auto";
        if (writeControl(COLOR_FILE, request)) {
            Toast.makeText(
                    this,
                    getString(R.string.display_settling_color),
                    Toast.LENGTH_SHORT).show();
        }
        hideMenu();
    }

    private void requestRefresh() {
        requestRefresh(true);
    }

    private void requestRefresh(boolean closeMenu) {
        writeControl(REFRESH_FILE, "refresh");
        if (closeMenu) {
            hideMenu();
        }
    }

    private void scheduleFrontlightSync() {
        handler.removeCallbacks(frontlightSync);
        handler.postDelayed(frontlightSync, 120L);
    }

    private void scheduleAutomaticFrontlightSync() {
        handler.removeCallbacks(automaticFrontlightSync);
        if (screenInteractive && automaticFrontlightEnabled()) {
            handler.postDelayed(
                    automaticFrontlightSync,
                    AUTOMATIC_SYNC_INTERVAL_MS);
        }
    }

    private int currentAndroidBrightness() {
        int value = Settings.System.getInt(
                getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS,
                153);
        return Math.max(0, Math.min(255, value));
    }

    private boolean automaticFrontlightEnabled() {
        return Settings.System.getInt(
                getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
    }

    /**
     * Paper Pro Move does not expose an ambient-light sensor to Android.
     * Keep the Android automatic toggle useful without pretending that a
     * physical lux sensor exists: daylight uses reflected room light,
     * evening gets a moderate reading light, and late night uses a low
     * reading light.
     */
    private int scheduledFrontlightBrightness() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 7 && hour < 18) {
            return 0;
        }
        if (hour >= 18 && hour < 23) {
            return 112;
        }
        return 48;
    }

    private int requestedFrontlightBrightness() {
        return automaticFrontlightEnabled()
                ? scheduledFrontlightBrightness()
                : currentAndroidBrightness();
    }

    private void syncFrontlightFromSettings() {
        if (!screenInteractive) {
            return;
        }
        int value = requestedFrontlightBrightness();
        if (writeControl(FRONTLIGHT_FILE, Integer.toString(value))) {
            Log.i(
                    TAG,
                    "frontlight request=" + value
                            + " automatic="
                            + automaticFrontlightEnabled());
        }
    }

    private void adjustFrontlight(int delta) {
        int value = Math.max(
                0, Math.min(
                        255,
                        requestedFrontlightBrightness() + delta));
        try {
            Settings.System.putInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            if (!Settings.System.putInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    value)) {
                throw new IllegalStateException(
                        "SCREEN_BRIGHTNESS write returned false");
            }
            scheduleFrontlightSync();
            scheduleAutomaticFrontlightSync();
            int percent = Math.round(value * 100f / 255f);
            Toast.makeText(
                    this,
                    getString(R.string.display_light_percent, percent),
                    Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot change Android brightness", error);
            Toast.makeText(
                    this,
                    getString(R.string.display_light_change_failed),
                    Toast.LENGTH_SHORT).show();
        }
        hideMenu();
    }

    private void toggleAutomaticFrontlight() {
        boolean enable = !automaticFrontlightEnabled();
        try {
            if (!Settings.System.putInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    enable
                            ? Settings.System
                                    .SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                            : Settings.System
                                    .SCREEN_BRIGHTNESS_MODE_MANUAL)) {
                throw new IllegalStateException(
                        "SCREEN_BRIGHTNESS_MODE write returned false");
            }
            scheduleFrontlightSync();
            scheduleAutomaticFrontlightSync();
            Toast.makeText(
                    this,
                    enable
                            ? getString(
                                    R.string.display_scheduled_light_enabled)
                            : getString(
                                    R.string.display_manual_light_enabled),
                    Toast.LENGTH_SHORT).show();
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot change automatic frontlight mode", error);
            Toast.makeText(
                    this,
                    getString(
                            R.string.display_auto_light_change_failed),
                    Toast.LENGTH_SHORT).show();
        }
        hideMenu();
    }

    private int autoPoweroffMinutes() {
        String raw = readControl(AUTO_POWEROFF_FILE, "30");
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                return 0;
            }
            return Math.max(10, Math.min(180, value));
        } catch (NumberFormatException ignored) {
            return 30;
        }
    }

    private void cycleAutoPoweroff() {
        int current = autoPoweroffMinutes();
        int next;
        if (current == 10) {
            next = 30;
        } else if (current == 30) {
            next = 60;
        } else if (current == 60) {
            next = 0;
        } else {
            next = 10;
        }
        if (writeControl(AUTO_POWEROFF_FILE, Integer.toString(next))) {
            Toast.makeText(
                    this,
                    next == 0
                            ? getString(
                                    R.string.display_auto_poweroff_off)
                            : getString(
                                    R.string.display_auto_poweroff_after,
                                    next),
                    Toast.LENGTH_LONG).show();
        }
        hideMenu();
    }

    private String profileLabel() {
        return profileLabelFor(readControl(PROFILE_FILE, "fast"));
    }

    private String profileLabelFor(String profile) {
        if ("fast".equals(profile)) {
            return getString(R.string.display_profile_fast);
        }
        if ("quality".equals(profile)) {
            return getString(R.string.display_profile_quality);
        }
        return getString(R.string.display_profile_balanced);
    }

    private String colorModeLabel() {
        String mode = readControl(COLOR_FILE, "auto");
        if ("mono".equals(mode)) {
            return getString(R.string.display_color_mono);
        }
        if (mode.startsWith("once")) {
            return getString(R.string.display_color_preparing);
        }
        return getString(R.string.display_color_auto);
    }

    private String readControl(String name, String fallback) {
        File file = new File(getFilesDir(), name);
        if (!file.isFile()) {
            return fallback;
        }
        byte[] data = new byte[32];
        try (FileInputStream input = new FileInputStream(file)) {
            int count = input.read(data);
            if (count > 0) {
                return new String(data, 0, count, StandardCharsets.UTF_8)
                        .trim().toLowerCase();
            }
        } catch (IOException error) {
            Log.w(TAG, "cannot read " + name, error);
        }
        return fallback;
    }

    private void ensureControlDefault(String name, String value) {
        File file = new File(getFilesDir(), name);
        if (!file.isFile()) {
            writeControl(name, value);
        }
    }

    private boolean writeControl(String name, String value) {
        File file = new File(getFilesDir(), name);
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return true;
        } catch (IOException error) {
            Log.e(TAG, "cannot write " + name, error);
            Toast.makeText(this,
                    getString(R.string.display_command_failed),
                    Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BubbleView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF arc = new RectF();
        private float downRawY;
        private int downWindowY;
        private boolean dragging;

        BubbleView() {
            super(DisplayControlService.this);
            setContentDescription(
                    getString(R.string.display_control_accessibility));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.WHITE);
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float radius = Math.min(centerX, centerY) - dp(3);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(centerX, centerY, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(Color.BLACK);
            canvas.drawCircle(centerX, centerY, radius, paint);
            arc.set(centerX - dp(13), centerY - dp(13),
                    centerX + dp(13), centerY + dp(13));
            paint.setStrokeWidth(dp(3));
            canvas.drawArc(arc, -55f, 235f, false, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(centerX + dp(10), centerY - dp(9),
                    dp(3), paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawY = event.getRawY();
                    int[] location = new int[2];
                    getLocationOnScreen(location);
                    downWindowY = location[1];
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float delta = event.getRawY() - downRawY;
                    if (Math.abs(delta) > dp(8)) {
                        dragging = true;
                        hideMenu();
                        bubbleParams.gravity = Gravity.END | Gravity.TOP;
                        bubbleParams.y = Math.max(0,
                                downWindowY + Math.round(delta));
                        try {
                            windowManager.updateViewLayout(this, bubbleParams);
                        } catch (RuntimeException ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging) {
                        toggleMenu();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    return true;
                default:
                    return false;
            }
        }
    }
}
