package com.android.launcher3.paper;

import android.app.Service;
import android.app.ActivityManager;
import android.content.ComponentName;
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
import android.os.SystemClock;
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
import java.util.List;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Small persistent e-ink control palette shown above reader applications.
 * It only writes command files in Paper Home's private directory; the native
 * panel bridge remains the sole owner of reMarkable display ioctls.
 */
public final class DisplayControlService extends Service {
    private static final String TAG = "PaperDisplayControl";
    /*
     * TYPE_APPLICATION_OVERLAY is deliberately hidden by activities that set
     * HIDE_NON_SYSTEM_OVERLAY_WINDOWS (Android Settings does this). Paper Home
     * is a platform-signed privileged package, so its persistent controls use
     * the real system navigation-panel layer instead.
     */
    private static final int TYPE_NAVIGATION_BAR = 2019;
    /*
     * Android permits only one TYPE_NAVIGATION_BAR window per display.  The
     * real SystemUI navigation bar owns that singleton layer, so the optional
     * reading-light affordance must use the ordinary application-overlay
     * layer.  Reusing 2019 here makes SystemUI crash-loop with BadTokenException.
     */
    private static final int TYPE_FRONTLIGHT_OVERLAY =
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    /*
     * A normal application overlay (2038) is below Android's real status bar,
     * so third-party apps let SystemUI consume the top-edge swipe first. This
     * platform-signed privileged package has INTERNAL_SYSTEM_WINDOW; use the
     * status-bar sub-panel layer (hidden API value 2017) for the global handle
     * and shade. Unlike TYPE_NAVIGATION_BAR, it is not the singleton SystemUI
     * navigation window. Runtime failures fall back to 2038 below.
     */
    private static final int TYPE_STATUS_BAR_SUB_PANEL = 2017;
    // StatusBarManager.DISABLE_EXPAND is hidden from ordinary SDK clients.
    // Paper Home is a platform-signed privileged app with STATUS_BAR_SERVICE,
    // so it may reserve the top-edge gesture for the reading-light shade.
    private static final int STATUS_BAR_DISABLE_EXPAND = 0x00010000;
    // Context.RECEIVER_EXPORTED (API 33), expressed as a literal to keep the
    // Android 12 rollback build source-compatible.  The receiver is still
    // protected by STATUS_BAR_SERVICE, so only a trusted system component can
    // invoke the SystemUI refresh command.
    private static final int RECEIVER_EXPORTED_COMPAT = 0x2;
    private static final String ACTION_SYSTEMUI_REFRESH =
            "com.android.launcher3.paper.action.REFRESH";
    /*
     * The Android 16 image now carries a real SystemUI navigation bar.  Keep
     * this service for panel, light, sleep and refresh policy, but do not add
     * the old launcher-owned TYPE_NAVIGATION_BAR window on top of applications.
     */
    private static final boolean USE_SYSTEMUI_NAVIGATION_BAR = true;
    private static final String PROFILE_FILE = "paper-display-mode";
    private static final String QUALITY_PROFILE_MIGRATION_FILE =
            "paper-quality-profile-v1";
    private static final String COLOR_FILE = "paper-color-mode";
    private static final String INVERT_FILE = "paper-invert-mode";
    private static final String REFRESH_FILE = "paper-refresh-request";
    private static final String PANEL_REFRESH_FILE =
            "paper-note-toolbar-refresh";
    private static final String FRONTLIGHT_FILE = "paper-frontlight-level";
    private static final String SCREEN_STATE_FILE = "paper-screen-state";
    private static final String LOCK_STYLE_FILE = "paper-lock-style";
    /** Reader page-refresh policy consumed by the native display bridge. */
    private static final String READER_REFRESH_FILE = "paper-reader-refresh";
    /** Ghost-removal method consumed by the native display bridge. */
    private static final String GHOST_CONTROL_FILE = "paper-ghost-control";
    /** Text darkness curve applied by the native display bridge. */
    private static final String TEXT_CONTRAST_FILE = "paper-text-contrast";
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
    private static final long NAVIGATION_POLL_MS = 750L;
    private static final long NAVIGATION_EXPANDED_MS = 8000L;
    private static final int NAVIGATION_HEIGHT_DP = 48;
    private static final int NAVIGATION_HANDLE_DP = 10;
    private static final int LIGHT_GESTURE_HEIGHT_DP = 18;
    private static final int LIGHT_SHADE_HEIGHT_DP = 224;
    private static final long LIGHT_PANEL_REFRESH_INTERVAL_MS = 50L;
    private static final int LIGHT_SWIPE_THRESHOLD_DP = 42;

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
    private final Runnable navigationModeSync = new Runnable() {
        @Override
        public void run() {
            syncNavigationMode();
            handler.postDelayed(this, NAVIGATION_POLL_MS);
        }
    };
    private final BroadcastReceiver screenReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();
                    if (ACTION_SYSTEMUI_REFRESH.equals(action)) {
                        requestRefresh(false);
                        Log.i(TAG, "SystemUI navigation refresh requested");
                    } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
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
    private NavigationBarView bubble;
    private View menu;
    private FrontlightGestureView frontlightGesture;
    private FrontlightShadeView frontlightShade;
    private WindowManager.LayoutParams bubbleParams;
    private boolean brightnessObserverRegistered;
    private boolean screenReceiverRegistered;
    private boolean screenInteractive = true;
    private boolean navigationCollapsed;
    private boolean statusBarExpansionDisabled;
    private long navigationExpandedUntil;

    @Override
    public void onCreate() {
        super.onCreate();
        // A crash or forced reboot can leave the note marker behind. Native
        // pen rendering must stay disabled until NoteActivity resumes again.
        new File(getFilesDir(), NOTE_ACTIVE_FILE).delete();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        setStatusBarExpansionDisabled(true);
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
            screenFilter.addAction(ACTION_SYSTEMUI_REFRESH);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                        screenReceiver,
                        screenFilter,
                        "android.permission.STATUS_BAR_SERVICE",
                        null,
                        RECEIVER_EXPORTED_COMPAT);
            } else {
                registerReceiver(
                        screenReceiver,
                        screenFilter,
                        "android.permission.STATUS_BAR_SERVICE",
                        null);
            }
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
        // Fast mode reduces every gray edge to a visible 1-bit Bayer pattern.
        // Use the eight-level grayscale path by default so text and cards are
        // as crisp as the stock UI. Users can still select Fast explicitly.
        ensureControlDefault(PROFILE_FILE, "quality");
        ensureControlDefault(READER_REFRESH_FILE, "every-5");
        ensureControlDefault(GHOST_CONTROL_FILE, "off");
        ensureControlDefault(TEXT_CONTRAST_FILE, "normal");
        ensureNavigationService();
        // The launcher starts on its own home screen: a reader marker left by
        // an earlier session (reader open at reboot) must not survive it.
        ReaderForeground.update(this, getPackageName(), TAG);
        migrateLegacyFastProfile();
        ensureControlDefault(COLOR_FILE, "auto");
        ensureControlDefault(LOCK_STYLE_FILE, "fade");
        ensureControlDefault(AUTO_POWEROFF_FILE, "30");
        scheduleFrontlightSync();
        scheduleAutomaticFrontlightSync();
        handler.post(this::ensureFrontlightGesture);
        if (!USE_SYSTEMUI_NAVIGATION_BAR) {
            handler.post(overlayRetry);
            handler.post(navigationModeSync);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ensureFrontlightGesture();
        if (!USE_SYSTEMUI_NAVIGATION_BAR) {
            ensureBubble();
        }
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
        hideFrontlightShade();
        if (frontlightGesture != null) {
            try {
                windowManager.removeView(frontlightGesture);
            } catch (RuntimeException ignored) {
            }
            frontlightGesture = null;
        }
        handler.removeCallbacks(navigationModeSync);
        if (bubble != null) {
            try {
                windowManager.removeView(bubble);
            } catch (RuntimeException ignored) {
            }
            bubble = null;
        }
        setStatusBarExpansionDisabled(false);
        super.onDestroy();
    }

    /**
     * Reserve the top-edge drag for the global frontlight shade. The hidden
     * StatusBarManager owns a per-process Binder token, so Android also clears
     * this disable record automatically if Paper Home is killed unexpectedly.
     */
    private void setStatusBarExpansionDisabled(boolean disabled) {
        if (statusBarExpansionDisabled == disabled) {
            return;
        }
        Object statusBarManager = getSystemService("statusbar");
        if (statusBarManager == null) {
            Log.w(TAG, "status bar manager unavailable; SystemUI may consume frontlight swipe");
            return;
        }
        try {
            Method disableMethod = statusBarManager.getClass()
                    .getMethod("disable", int.class);
            disableMethod.invoke(
                    statusBarManager,
                    disabled ? STATUS_BAR_DISABLE_EXPAND : 0);
            statusBarExpansionDisabled = disabled;
            Log.i(TAG, disabled
                    ? "SystemUI panel expansion disabled for frontlight gesture"
                    : "SystemUI panel expansion restored");
        } catch (NoSuchMethodException | IllegalAccessException
                 | InvocationTargetException | RuntimeException error) {
            Log.e(TAG, "cannot update SystemUI expansion policy", error);
        }
    }

    private void ensureBubble() {
        if (USE_SYSTEMUI_NAVIGATION_BAR || bubble != null ||
                windowManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "overlay app-op is not granted yet");
            return;
        }
        final int height = dp(NAVIGATION_HEIGHT_DP);
        bubble = new NavigationBarView();
        bubble.setBackgroundColor(Color.WHITE);
        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                height,
                TYPE_NAVIGATION_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE);
        bubbleParams.gravity = Gravity.BOTTOM;
        try {
            windowManager.addView(bubble, bubbleParams);
            Log.i(TAG, "fixed E Ink navigation bar added");
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot add fixed E Ink navigation bar", error);
            bubble = null;
        }
    }

    private void ensureFrontlightGesture() {
        if (frontlightGesture != null || windowManager == null) {
            return;
        }
        frontlightGesture = new FrontlightGestureView();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(LIGHT_GESTURE_HEIGHT_DP),
                TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP;
        try {
            windowManager.addView(frontlightGesture, params);
            Log.i(TAG, "frontlight top-swipe handle added above status bar");
        } catch (RuntimeException error) {
            Log.w(TAG, "status-bar frontlight handle rejected; "
                    + "falling back to application overlay", error);
            params.type = TYPE_FRONTLIGHT_OVERLAY;
            try {
                windowManager.addView(frontlightGesture, params);
                Log.i(TAG, "frontlight top-swipe fallback handle added");
            } catch (RuntimeException fallbackError) {
                Log.e(TAG, "cannot add frontlight gesture handle",
                        fallbackError);
                frontlightGesture = null;
            }
        }
    }

    private void showFrontlightShade() {
        if (frontlightShade != null || windowManager == null) {
            return;
        }
        frontlightShade = new FrontlightShadeView();
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(LIGHT_SHADE_HEIGHT_DP),
                TYPE_STATUS_BAR_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.gravity = Gravity.TOP;
        try {
            windowManager.addView(frontlightShade, params);
            handler.postDelayed(() -> requestRefresh(false), 120L);
            Log.i(TAG, "frontlight shade opened above status bar");
        } catch (RuntimeException error) {
            Log.w(TAG, "status-bar frontlight shade rejected; "
                    + "falling back to application overlay", error);
            params.type = TYPE_FRONTLIGHT_OVERLAY;
            try {
                windowManager.addView(frontlightShade, params);
                handler.postDelayed(() -> requestRefresh(false), 120L);
                Log.i(TAG, "frontlight fallback shade opened");
            } catch (RuntimeException fallbackError) {
                Log.e(TAG, "cannot add frontlight shade", fallbackError);
                frontlightShade = null;
            }
        }
    }

    private void hideFrontlightShade() {
        if (frontlightShade == null) {
            return;
        }
        frontlightShade.cancelPendingRefresh();
        try {
            windowManager.removeView(frontlightShade);
        } catch (RuntimeException ignored) {
        }
        frontlightShade = null;
        handler.postDelayed(() -> requestRefresh(false), 120L);
    }

    private void syncNavigationMode() {
        if (bubble == null || bubbleParams == null || windowManager == null) {
            return;
        }
        boolean paperActivity = true;
        try {
            ActivityManager manager = (ActivityManager)
                    getSystemService(ACTIVITY_SERVICE);
            List<ActivityManager.RunningTaskInfo> tasks = manager == null
                    ? null : manager.getRunningTasks(1);
            if (tasks != null && !tasks.isEmpty()) {
                ComponentName top = tasks.get(0).topActivity;
                String topPackage = top == null ? null : top.getPackageName();
                paperActivity = top == null ||
                        getPackageName().equals(topPackage);
                updateReaderActiveMarker(topPackage);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot inspect foreground app", error);
        }
        boolean collapse = !paperActivity &&
                SystemClock.uptimeMillis() >= navigationExpandedUntil;
        setNavigationCollapsed(collapse);
    }

    private void setNavigationCollapsed(boolean collapsed) {
        if (bubble == null || bubbleParams == null ||
                navigationCollapsed == collapsed) {
            return;
        }
        navigationCollapsed = collapsed;
        bubbleParams.height = dp(collapsed
                ? NAVIGATION_HANDLE_DP : NAVIGATION_HEIGHT_DP);
        try {
            windowManager.updateViewLayout(bubble, bubbleParams);
            bubble.invalidate();
            Log.i(TAG, collapsed
                    ? "external app navigation handle collapsed"
                    : "navigation controls expanded");
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot resize navigation controls", error);
        }
    }

    private void temporarilyExpandNavigation() {
        navigationExpandedUntil = SystemClock.uptimeMillis()
                + NAVIGATION_EXPANDED_MS;
        setNavigationCollapsed(false);
    }

    private void navigateHome() {
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        try {
            startActivity(home);
            handler.postDelayed(() -> requestRefresh(false), 180L);
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot navigate home", error);
        }
    }

    private void navigateBack() {
        if (PaperNavigationAccessibilityService.performBack()) {
            handler.postDelayed(() -> requestRefresh(false), 180L);
        } else {
            Log.w(TAG, "navigation accessibility service is not connected");
            Toast.makeText(this,
                    getString(R.string.display_command_failed),
                    Toast.LENGTH_SHORT).show();
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
        panel.addView(menuRow(
                getString(R.string.display_reader_refresh_row,
                        readerRefreshLabel()),
                false,
                this::cycleReaderRefresh));
        panel.addView(menuRow(
                getString(R.string.display_ghost_control_row,
                        ghostControlLabel()),
                false,
                this::cycleGhostControl));
        panel.addView(menuRow(
                getString(R.string.display_text_contrast_row,
                        textContrastLabel()),
                false,
                this::cycleTextContrast));
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
        panel.addView(menuRow(
                getString(
                        R.string.display_lock_style,
                        lockStyleLabel()),
                false,
                this::cycleLockStyle));
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

    private boolean setFrontlightBrightness(int requested) {
        int value = Math.max(0, Math.min(255, requested));
        try {
            if (!Settings.System.putInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) ||
                !Settings.System.putInt(
                    getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS,
                    value)) {
                throw new IllegalStateException(
                        "Android brightness write returned false");
            }
            handler.removeCallbacks(frontlightSync);
            if (!writeControl(FRONTLIGHT_FILE, Integer.toString(value))) {
                return false;
            }
            scheduleAutomaticFrontlightSync();
            Log.i(TAG, "frontlight slider request=" + value);
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "cannot set frontlight slider", error);
            Toast.makeText(
                    this,
                    getString(R.string.display_light_change_failed),
                    Toast.LENGTH_SHORT).show();
            return false;
        }
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

    private String lockStyleLabel() {
        return lockStyleLabelFor(readControl(LOCK_STYLE_FILE, "fade"));
    }

    private String lockStyleLabelFor(String style) {
        if ("reading".equals(style)) {
            return getString(R.string.lock_style_reading);
        }
        if ("clean".equals(style)) {
            return getString(R.string.lock_style_clean);
        }
        if ("clock".equals(style)) {
            return getString(R.string.lock_style_clock);
        }
        if ("classic".equals(style)) {
            return getString(R.string.lock_style_classic);
        }
        return getString(R.string.lock_style_fade);
    }

    private void cycleLockStyle() {
        String current = readControl(LOCK_STYLE_FILE, "fade");
        String next;
        if ("fade".equals(current)) {
            next = "reading";
        } else if ("reading".equals(current)) {
            next = "clean";
        } else if ("clean".equals(current)) {
            next = "clock";
        } else if ("clock".equals(current)) {
            next = "classic";
        } else {
            next = "fade";
        }
        if (writeControl(LOCK_STYLE_FILE, next)) {
            Toast.makeText(
                    this,
                    getString(
                            R.string.display_lock_style_selected,
                            lockStyleLabelFor(next)),
                    Toast.LENGTH_SHORT).show();
        }
        hideMenu();
    }

    /**
     * The Back button, typing feedback, reader detection and ghost-removal
     * requests all run inside {@link PaperNavigationAccessibilityService}.
     * First-boot provisioning enables it, but it was observed disabled again
     * after a later boot (physical finding #22, 2026-08-21). Re-assert it
     * whenever the launcher starts; the write is idempotent and touches only
     * the two accessibility settings.
     */
    private void ensureNavigationService() {
        String service = getPackageName() + "/"
                + PaperNavigationAccessibilityService.class.getName();
        try {
            android.content.ContentResolver resolver = getContentResolver();
            String enabled = android.provider.Settings.Secure.getString(
                    resolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = enabled != null && enabled.contains(service);
            if (!listed) {
                android.provider.Settings.Secure.putString(
                        resolver,
                        android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        enabled == null || enabled.isEmpty()
                                ? service : enabled + ":" + service);
            }
            if (android.provider.Settings.Secure.getInt(resolver,
                    android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                android.provider.Settings.Secure.putInt(resolver,
                        android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
            if (!listed) {
                Log.i(TAG, "navigation accessibility service re-enabled");
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot assert navigation accessibility service", error);
        }
    }

    /**
     * Secondary reader-foreground source for builds without the SystemUI
     * navigation bar, where this service polls the top task anyway. The
     * primary, event-driven source is the navigation accessibility service;
     * both share the marker state through {@link ReaderForeground}.
     */
    private void updateReaderActiveMarker(String topPackage) {
        ReaderForeground.update(this, topPackage, TAG);
    }

    private String readerRefreshLabel() {
        String policy = readControl(READER_REFRESH_FILE, "every-5");
        if ("every-page".equals(policy)) {
            return getString(R.string.display_reader_refresh_every_page);
        }
        if ("every-3".equals(policy)) {
            return getString(R.string.display_reader_refresh_every_3);
        }
        if ("budget".equals(policy)) {
            return getString(R.string.display_reader_refresh_budget);
        }
        return getString(R.string.display_reader_refresh_every_5);
    }

    private void cycleReaderRefresh() {
        String current = readControl(READER_REFRESH_FILE, "every-5");
        String next;
        if ("every-5".equals(current)) {
            next = "every-3";
        } else if ("every-3".equals(current)) {
            next = "every-page";
        } else if ("every-page".equals(current)) {
            next = "budget";
        } else {
            next = "every-5";
        }
        if (writeControl(READER_REFRESH_FILE, next)) {
            Toast.makeText(
                    this,
                    getString(R.string.display_reader_refresh_selected,
                            readerRefreshLabel()),
                    Toast.LENGTH_SHORT).show();
        }
        hideMenu();
    }

    private String ghostControlLabel() {
        String mode = readControl(GHOST_CONTROL_FILE, "off");
        if ("bleach".equals(mode)) {
            return getString(R.string.display_ghost_control_bleach);
        }
        if ("blink-later".equals(mode)) {
            return getString(R.string.display_ghost_control_blink);
        }
        return getString(R.string.display_ghost_control_off);
    }

    private void cycleGhostControl() {
        String current = readControl(GHOST_CONTROL_FILE, "off");
        String next;
        if ("off".equals(current)) {
            next = "bleach";
        } else if ("bleach".equals(current)) {
            next = "blink-later";
        } else {
            next = "off";
        }
        if (writeControl(GHOST_CONTROL_FILE, next)) {
            Toast.makeText(
                    this,
                    getString(R.string.display_ghost_control_selected,
                            ghostControlLabel()),
                    Toast.LENGTH_SHORT).show();
        }
        hideMenu();
    }

    private String textContrastLabel() {
        String contrast = readControl(TEXT_CONTRAST_FILE, "normal");
        if ("dark".equals(contrast)) {
            return getString(R.string.display_text_contrast_dark);
        }
        if ("darker".equals(contrast)) {
            return getString(R.string.display_text_contrast_darker);
        }
        return getString(R.string.display_text_contrast_normal);
    }

    private void cycleTextContrast() {
        String current = readControl(TEXT_CONTRAST_FILE, "normal");
        String next;
        if ("normal".equals(current)) {
            next = "dark";
        } else if ("dark".equals(current)) {
            next = "darker";
        } else {
            next = "normal";
        }
        if (writeControl(TEXT_CONTRAST_FILE, next)) {
            Toast.makeText(
                    this,
                    getString(R.string.display_text_contrast_selected,
                            textContrastLabel()),
                    Toast.LENGTH_SHORT).show();
        }
        hideMenu();
    }

    private String profileLabel() {
        return profileLabelFor(readControl(PROFILE_FILE, "quality"));
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

    /**
     * Early monochrome builds persisted Fast as the normal display profile.
     * Fast is still useful during active touch and pen input, but keeping it
     * selected while idle collapses every gray edge to a 1-bit Bayer pattern.
     * Upgrade that legacy value once, then leave later user selections alone.
     */
    private void migrateLegacyFastProfile() {
        File marker = new File(getFilesDir(), QUALITY_PROFILE_MIGRATION_FILE);
        if (marker.isFile()) {
            return;
        }
        String current = readControl(PROFILE_FILE, "quality");
        if ("fast".equals(current) && !writeControl(PROFILE_FILE, "quality")) {
            Log.w(TAG, "Unable to migrate legacy Fast profile to Quality");
            return;
        }
        if (writeControl(QUALITY_PROFILE_MIGRATION_FILE, "complete")) {
            Log.i(TAG, "Display profile migration complete: "
                    + current + " -> " + readControl(PROFILE_FILE, "quality"));
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

    private final class FrontlightGestureView extends View {
        private float downY;
        private boolean opened;

        FrontlightGestureView() {
            super(DisplayControlService.this);
            setBackgroundColor(Color.TRANSPARENT);
            setContentDescription(
                    getString(R.string.frontlight_swipe_accessibility));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downY = event.getRawY();
                // SystemUI observes top-edge drags through a global input
                // monitor and can pilfer the stream before this view receives
                // enough MOVE distance. Open on the initial contact instead:
                // a tap or a downward pull now behaves identically in every
                // third-party application, while the actual brightness slider
                // remains a separate deliberate interaction.
                opened = true;
                showFrontlightShade();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && !opened &&
                    event.getRawY() - downY >=
                            dp(LIGHT_SWIPE_THRESHOLD_DP)) {
                opened = true;
                showFrontlightShade();
                return true;
            }
            if (action == MotionEvent.ACTION_UP && !opened) {
                // A deliberate tap on the tiny top handle is also usable
                // when a reader consumes swipe gestures aggressively.
                showFrontlightShade();
                return true;
            }
            return true;
        }
    }

    private final class FrontlightShadeView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF slider = new RectF();
        private final RectF automaticButton = new RectF();
        private final RectF closeButton = new RectF();
        private boolean sliderDragging;
        private int brightness = requestedFrontlightBrightness();
        private long lastPanelRefreshAt;
        private boolean panelRefreshQueued;
        private final Runnable flushPanelRefresh = () -> {
            panelRefreshQueued = false;
            lastPanelRefreshAt = SystemClock.uptimeMillis();
            writeControl(
                    PANEL_REFRESH_FILE,
                    Math.max(1, getWidth()) + "," +
                            Math.max(1, getHeight()));
        };

        FrontlightShadeView() {
            super(DisplayControlService.this);
            setBackgroundColor(Color.WHITE);
            setContentDescription(
                    getString(R.string.frontlight_panel_title));
        }

        private void text(Canvas canvas, String value,
                          float x, float y, float size, boolean bold) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            paint.setTextSize(dp((int) size));
            paint.setFakeBoldText(bold);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(value, x, y, paint);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.WHITE);
            float width = getWidth();
            int percent = Math.round(brightness * 100f / 255f);
            text(canvas, getString(R.string.frontlight_panel_title),
                    dp(24), dp(42), 22f, true);
            paint.setTextAlign(Paint.Align.RIGHT);
            paint.setTextSize(dp(20));
            paint.setFakeBoldText(true);
            paint.setColor(Color.BLACK);
            canvas.drawText(percent + "%", width - dp(24), dp(42), paint);

            slider.set(dp(28), dp(78), width - dp(28), dp(128));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(235, 235, 231));
            canvas.drawRect(slider, paint);
            float filled = slider.left +
                    slider.width() * brightness / 255f;
            paint.setColor(Color.rgb(45, 45, 43));
            canvas.drawRect(slider.left, slider.top,
                    filled, slider.bottom, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.BLACK);
            canvas.drawRect(slider, paint);
            for (int step = 1; step < 5; ++step) {
                float x = slider.left + slider.width() * step / 5f;
                canvas.drawLine(x, slider.top, x, slider.bottom, paint);
            }

            automaticButton.set(dp(28), dp(154),
                    width / 2f - dp(8), dp(208));
            closeButton.set(width / 2f + dp(8), dp(154),
                    width - dp(28), dp(208));
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawRect(automaticButton, paint);
            canvas.drawRect(closeButton, paint);
            text(canvas,
                    automaticFrontlightEnabled()
                            ? getString(R.string.frontlight_panel_auto)
                            : getString(R.string.frontlight_panel_manual),
                    automaticButton.left + dp(16), dp(188), 18f, true);
            text(canvas, getString(R.string.frontlight_panel_close),
                    closeButton.left + dp(16), dp(188), 18f, true);
        }

        private void updateSlider(float x) {
            int next = Math.round(
                    (x - slider.left) * 255f / slider.width());
            next = Math.max(0, Math.min(255, next));
            // Eight-bit writes on every pixel of a drag are pointless on an
            // E Ink slider. Quantize to 32 visible levels, while preserving
            // true off and maximum.
            if (next > 0 && next < 255) {
                next = Math.max(1, Math.min(254,
                        Math.round(next / 8f) * 8));
            }
            if (next != brightness && setFrontlightBrightness(next)) {
                brightness = next;
                requestVisiblePanelRefresh(false);
            }
        }

        private void requestVisiblePanelRefresh(boolean immediate) {
            invalidate();
            long elapsed = SystemClock.uptimeMillis() - lastPanelRefreshAt;
            if (immediate || elapsed >= LIGHT_PANEL_REFRESH_INTERVAL_MS) {
                handler.removeCallbacks(flushPanelRefresh);
                panelRefreshQueued = false;
                flushPanelRefresh.run();
                return;
            }
            if (!panelRefreshQueued) {
                panelRefreshQueued = true;
                handler.postDelayed(
                        flushPanelRefresh,
                        LIGHT_PANEL_REFRESH_INTERVAL_MS - elapsed);
            }
        }

        private void cancelPendingRefresh() {
            handler.removeCallbacks(flushPanelRefresh);
            panelRefreshQueued = false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            float x = event.getX();
            float y = event.getY();
            if (action == MotionEvent.ACTION_DOWN &&
                    (slider.contains(x, y) ||
                     (y >= slider.top - dp(20) &&
                      y <= slider.bottom + dp(20)))) {
                sliderDragging = true;
                updateSlider(x);
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE && sliderDragging) {
                updateSlider(x);
                return true;
            }
            if ((action == MotionEvent.ACTION_UP ||
                    action == MotionEvent.ACTION_CANCEL) &&
                    sliderDragging) {
                sliderDragging = false;
                updateSlider(x);
                requestVisiblePanelRefresh(true);
                return true;
            }
            if (action == MotionEvent.ACTION_UP &&
                    automaticButton.contains(x, y)) {
                toggleAutomaticFrontlight();
                brightness = requestedFrontlightBrightness();
                requestVisiblePanelRefresh(true);
                return true;
            }
            if (action == MotionEvent.ACTION_UP &&
                    closeButton.contains(x, y)) {
                hideFrontlightShade();
                return true;
            }
            return true;
        }
    }

    private final class NavigationBarView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int pressedSection = -1;
        private long pressedAt;

        NavigationBarView() {
            super(DisplayControlService.this);
            setContentDescription(
                    getString(R.string.fixed_nav_accessibility));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.WHITE);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1));
            paint.setColor(Color.BLACK);
            canvas.drawLine(0f, 0f, getWidth(), 0f, paint);
            if (navigationCollapsed) {
                float center = getWidth() / 2f;
                float middle = getHeight() / 2f + dp(1);
                canvas.drawLine(center - dp(10), middle + dp(2),
                        center, middle - dp(2), paint);
                canvas.drawLine(center, middle - dp(2),
                        center + dp(10), middle + dp(2), paint);
                return;
            }
            float third = getWidth() / 3f;
            canvas.drawLine(third, 0f, third, getHeight(), paint);
            canvas.drawLine(third * 2f, 0f,
                    third * 2f, getHeight(), paint);

            if (pressedSection >= 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(235, 235, 231));
                canvas.drawRect(third * pressedSection, 1f,
                        third * (pressedSection + 1), getHeight(), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.BLACK);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setFakeBoldText(true);
            paint.setTextSize(dp(20));
            float baseline = getHeight() / 2f
                    - (paint.ascent() + paint.descent()) / 2f;
            canvas.drawText(getString(R.string.fixed_nav_back),
                    third / 2f, baseline, paint);
            canvas.drawText(getString(R.string.fixed_nav_home),
                    third * 1.5f, baseline, paint);
            canvas.drawText(getString(R.string.fixed_nav_refresh),
                    third * 2.5f, baseline, paint);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (navigationCollapsed) {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    temporarilyExpandNavigation();
                }
                return true;
            }
            int section = Math.min(2,
                    Math.max(0, (int) (event.getX() * 3 / getWidth())));
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                pressedSection = section;
                pressedAt = event.getEventTime();
                invalidate();
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int selected = pressedSection;
                long held = event.getEventTime() - pressedAt;
                pressedSection = -1;
                invalidate();
                if (selected != section) {
                    return true;
                }
                if (selected == 0) {
                    Log.i(TAG, "fixed navigation: back");
                    navigateBack();
                } else if (selected == 1) {
                    Log.i(TAG, "fixed navigation: home");
                    navigateHome();
                } else if (held >= 650L) {
                    Log.i(TAG, "fixed navigation: display menu");
                    toggleMenu();
                } else {
                    Log.i(TAG, "fixed navigation: refresh");
                    requestRefresh(false);
                }
                return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                pressedSection = -1;
                invalidate();
                return true;
            }
            return true;
        }
    }
}
