package com.android.launcher3.paper;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Provides the standard system Back action for the fixed E Ink navigation
 * bar. This avoids privileged raw-input injection and works inside Settings
 * and third-party reader applications.
 */
public final class PaperNavigationAccessibilityService
        extends AccessibilityService {
    private static final String TAG = "PaperNavigation";
    private static final String ACTION_SYSTEMUI_REFRESH =
            "com.android.launcher3.paper.action.REFRESH";
    private static final long WINDOW_SETTLE_REFRESH_MS = 220L;
    private static final long TYPING_BURST_TAIL_MS = 700L;
    private static final String TYPING_ACTIVE_FILE = "paper-typing-active";
    private static final String PARTIAL_REFRESH_FILE =
            "paper-note-toolbar-refresh";
    private static final String GHOST_REQUEST_FILE = "paper-ghost-request";
    private static volatile PaperNavigationAccessibilityService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String lastWindowKey = "";
    private final Runnable finishTypingBurst = () -> {
        File marker = new File(getFilesDir(), TYPING_ACTIVE_FILE);
        if (marker.exists() && !marker.delete()) {
            Log.w(TAG, "could not clear E Ink typing marker");
        }
    };
    private final Runnable settledWindowRefresh = () -> {
        Intent refresh = new Intent(ACTION_SYSTEMUI_REFRESH);
        refresh.setPackage(getPackageName());
        sendBroadcast(refresh, "android.permission.STATUS_BAR_SERVICE");
        requestGhostRemoval();
        Log.i(TAG, "window transition settled; E Ink navigation refreshed");
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        Log.i(TAG, "global navigation service connected");
    }

    static boolean performBack() {
        PaperNavigationAccessibilityService service = instance;
        return service != null
                && service.performGlobalAction(GLOBAL_ACTION_BACK);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
            keepTypingBurstFast();
            requestTypingFeedbackRefresh();
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        CharSequence packageName = event.getPackageName();
        CharSequence className = event.getClassName();
        trackReaderForeground(packageName, className);
        String windowKey = String.valueOf(packageName) + "/" +
                String.valueOf(className);
        if (windowKey.equals(lastWindowKey)) {
            return;
        }
        lastWindowKey = windowKey;
        handler.removeCallbacks(settledWindowRefresh);
        handler.postDelayed(settledWindowRefresh, WINDOW_SETTLE_REFRESH_MS);
    }

    /**
     * Keep the panel in its low-latency monochrome path while text is being
     * entered. No event text, node, package data or password content is read;
     * the service only records a short-lived activity marker for the native
     * display bridge.
     */
    private void keepTypingBurstFast() {
        File marker = new File(getFilesDir(), TYPING_ACTIVE_FILE);
        if (!marker.isFile()) {
            try (FileOutputStream output =
                         new FileOutputStream(marker, false)) {
                output.write("active\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
            } catch (IOException error) {
                Log.w(TAG, "could not start E Ink typing burst", error);
                return;
            }
        }
        handler.removeCallbacks(finishTypingBurst);
        handler.postDelayed(finishTypingBurst, TYPING_BURST_TAIL_MS);
    }

    /**
     * Older deployed display bridges do not consume the typing marker yet,
     * but they do consume the bounded UI refresh request used by Paper Home.
     * Reuse that content-free request so ordinary IME input, including an
     * Amazon login WebView, becomes visible without waiting for a later large
     * screen update. The upper three quarters include the focused form field
     * while avoiding most of the keyboard surface and its unnecessary churn.
     */
    private void requestTypingFeedbackRefresh() {
        int width = Math.max(
                1, getResources().getDisplayMetrics().widthPixels);
        int height = Math.max(
                1, getResources().getDisplayMetrics().heightPixels);
        int bottom = Math.max(1, (height * 3) / 4);
        File request = new File(getFilesDir(), PARTIAL_REFRESH_FILE);
        try (FileOutputStream output = new FileOutputStream(request, false)) {
            output.write((width + "," + bottom)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException error) {
            Log.w(TAG, "could not request E Ink typing refresh", error);
        }
    }

    /**
     * Event-driven reader detection for the native display bridge. Activity
     * windows report their own class name; system dialogs, overlays, toasts
     * and input methods report framework classes or system packages and must
     * not count as leaving the book. Only the package identity is used.
     */
    private void trackReaderForeground(CharSequence packageName,
            CharSequence className) {
        if (packageName == null || className == null) {
            return;
        }
        String owner = packageName.toString();
        String window = className.toString();
        if (window.startsWith("android.")
                || "android".equals(owner)
                || "com.android.systemui".equals(owner)
                || owner.contains(".inputmethod")) {
            return;
        }
        ReaderForeground.update(this, owner, TAG);
    }

    /**
     * Mirrors the stock GhostBuster view-change notification: a settled
     * window transition asks the native display bridge to schedule ghost
     * removal under the user's page-refresh policy. Only a constant marker is
     * written; no event text, package data or window content is recorded.
     */
    private void requestGhostRemoval() {
        File request = new File(getFilesDir(), GHOST_REQUEST_FILE);
        try (FileOutputStream output = new FileOutputStream(request, false)) {
            output.write("window\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
        } catch (IOException error) {
            Log.w(TAG, "could not request E Ink ghost removal", error);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(settledWindowRefresh);
        handler.removeCallbacks(finishTypingBurst);
        finishTypingBurst.run();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }
}
