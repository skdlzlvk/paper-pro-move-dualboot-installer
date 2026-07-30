package com.android.launcher3.paper;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.wifi.ScanResult;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.launcher3.R;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-contrast e-ink front-end for Android's native Wi-Fi stack.
 * The UI is custom-drawn, but all state changes, scans, saved networks,
 * authentication, DHCP, and connectivity are owned by WifiManager.
 */
public final class WifiActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private WifiView wifiView;
    private WifiManager wifiManager;
    private String lastStatus = "";
    private boolean receiverRegistered;
    private long lastScanRequestAt;

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            if (wifiView != null) {
                String current = buildStatus();
                if (!current.equals(lastStatus)) {
                    lastStatus = current;
                    wifiView.setStatus(current);
                }
                handler.postDelayed(this, 1500L);
            }
        }
    };

    private final BroadcastReceiver wifiEvents = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshNow();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        wifiManager = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        wifiView = new WifiView();
        setContentView(wifiView);
        enterFullscreen();
        if (wifiManager == null) {
            Toast.makeText(this, getString(R.string.wifi_service_missing),
                    Toast.LENGTH_LONG).show();
        } else {
            if (!wifiManager.isWifiEnabled()) {
                wifiManager.setWifiEnabled(true);
            }
            handler.postDelayed(this::requestScan, 1200L);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterFullscreen();
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        registerReceiver(wifiEvents, filter);
        receiverRegistered = true;
        handler.removeCallbacks(statusPoll);
        handler.post(statusPoll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(statusPoll);
        if (receiverRegistered) {
            unregisterReceiver(wifiEvents);
            receiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void refreshNow() {
        if (wifiView == null) {
            return;
        }
        String current = buildStatus();
        lastStatus = current;
        wifiView.setStatus(current);
    }

    private void requestScan() {
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            refreshNow();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastScanRequestAt < 8000L) {
            return;
        }
        lastScanRequestAt = now;
        try {
            if (!wifiManager.startScan()) {
                wifiView.setMessage(
                        getString(R.string.wifi_scan_not_ready));
            }
        } catch (SecurityException failure) {
            wifiView.setMessage(
                    getString(R.string.wifi_scan_permission_applying));
        }
        refreshNow();
    }

    private String buildStatus() {
        StringBuilder raw = new StringBuilder();
        if (wifiManager == null) {
            return "WIFI_ENABLED=0\nSTATE=ERROR\n"
                    + "MESSAGE=" + getString(
                            R.string.wifi_service_missing_short);
        }
        boolean enabled = wifiManager.isWifiEnabled();
        raw.append("WIFI_ENABLED=").append(enabled ? '1' : '0').append('\n');
        if (!enabled) {
            return raw.append("STATE=DISABLED\n")
                    .append("MESSAGE=")
                    .append(getString(R.string.wifi_disabled_message))
                    .toString();
        }

        WifiInfo info = null;
        try {
            info = wifiManager.getConnectionInfo();
        } catch (SecurityException ignored) {
            // NETWORK_SETTINGS normally permits this on the platform-signed
            // launcher. Keep the UI usable if a build applies stricter rules.
        }
        String activeSsid = info == null ? ""
                : cleanSsid(info.getSSID());
        SupplicantState supplicantState = info == null
                ? SupplicantState.DISCONNECTED : info.getSupplicantState();
        boolean connected = info != null
                && info.getNetworkId() >= 0
                && supplicantState == SupplicantState.COMPLETED;
        String ipAddress = "";
        if (connected && info.getIpAddress() != 0) {
            ipAddress = Formatter.formatIpAddress(info.getIpAddress());
        }
        if (connected) {
            raw.append("STATE=CONNECTED\n")
                    .append("MESSAGE=")
                    .append(getString(R.string.wifi_connected_system))
                    .append('\n');
        } else if (supplicantState == SupplicantState.ASSOCIATING
                || supplicantState == SupplicantState.ASSOCIATED
                || supplicantState == SupplicantState.AUTHENTICATING
                || supplicantState == SupplicantState.FOUR_WAY_HANDSHAKE
                || supplicantState == SupplicantState.GROUP_HANDSHAKE) {
            raw.append("STATE=ASSOCIATING\n")
                    .append("MESSAGE=")
                    .append(getString(R.string.wifi_authenticating))
                    .append('\n');
        } else {
            raw.append("STATE=SCANNING\n")
                    .append("MESSAGE=")
                    .append(getString(R.string.wifi_choose_network))
                    .append('\n');
        }
        raw.append("SSIDHEX=").append(encodeHex(activeSsid)).append('\n');
        raw.append("IP=").append(ipAddress).append('\n');

        Set<String> knownSsids = new HashSet<>();
        try {
            List<WifiConfiguration> configured =
                    wifiManager.getConfiguredNetworks();
            if (configured != null) {
                for (WifiConfiguration item : configured) {
                    knownSsids.add(cleanSsid(item.SSID));
                }
            }
        } catch (SecurityException ignored) {
            // A scan remains useful even when configured-network access is
            // restricted by a future framework build.
        }

        Map<String, ScanResult> strongest = new HashMap<>();
        try {
            List<ScanResult> results = wifiManager.getScanResults();
            if (results != null) {
                for (ScanResult result : results) {
                    if (result.SSID == null || result.SSID.isEmpty()) {
                        continue;
                    }
                    ScanResult previous = strongest.get(result.SSID);
                    if (previous == null || result.level > previous.level) {
                        strongest.put(result.SSID, result);
                    }
                }
            }
        } catch (SecurityException ignored) {
            raw.append("MESSAGE=")
                    .append(getString(
                            R.string.wifi_scan_permission_required))
                    .append('\n');
        }
        List<ScanResult> results = new ArrayList<>(strongest.values());
        Collections.sort(results,
                (left, right) -> Integer.compare(right.level, left.level));
        for (ScanResult result : results) {
            raw.append("NETWORK|")
                    .append(encodeHex(result.SSID)).append('|')
                    .append(result.level).append('|')
                    .append(securityOf(result)).append('|')
                    .append(knownSsids.contains(result.SSID) ? '1' : '0')
                    .append('\n');
        }
        return raw.toString();
    }

    private void setWifiEnabled(boolean enabled) {
        if (wifiManager == null) {
            return;
        }
        try {
            if (!wifiManager.setWifiEnabled(enabled)) {
                Toast.makeText(this,
                        getString(R.string.wifi_change_failed),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (SecurityException failure) {
            Toast.makeText(this,
                    getString(R.string.wifi_change_permission_missing),
                    Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(this::refreshNow, 800L);
        if (enabled) {
            handler.postDelayed(this::requestScan, 1400L);
        }
    }

    private WifiConfiguration configuredNetwork(String ssid) {
        if (wifiManager == null) {
            return null;
        }
        try {
            List<WifiConfiguration> configured =
                    wifiManager.getConfiguredNetworks();
            if (configured != null) {
                for (WifiConfiguration item : configured) {
                    if (ssid.equals(cleanSsid(item.SSID))) {
                        return item;
                    }
                }
            }
        } catch (SecurityException ignored) {
            return null;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private void connectNetwork(NetworkRow row, String password) {
        if (wifiManager == null) {
            return;
        }
        if (!wifiManager.isWifiEnabled()) {
            setWifiEnabled(true);
        }
        if ("EAP".equals(row.security)) {
            Toast.makeText(this,
                    getString(R.string.wifi_eap_requires_settings),
                    Toast.LENGTH_LONG).show();
            return;
        }
        WifiConfiguration existing = configuredNetwork(row.ssid);
        int networkId = existing == null ? -1 : existing.networkId;
        if (networkId < 0 || password != null) {
            WifiConfiguration config = new WifiConfiguration();
            config.SSID = quote(row.ssid);
            if ("OPEN".equals(row.security)) {
                config.allowedKeyManagement.set(
                        WifiConfiguration.KeyMgmt.NONE);
            } else if ("SAE".equals(row.security)) {
                config.allowedKeyManagement.set(8);
                config.preSharedKey = quote(password == null ? "" : password);
            } else {
                config.allowedKeyManagement.set(
                        WifiConfiguration.KeyMgmt.WPA_PSK);
                config.preSharedKey = quote(password == null ? "" : password);
            }
            try {
                networkId = wifiManager.addNetwork(config);
                wifiManager.saveConfiguration();
            } catch (SecurityException failure) {
                networkId = -1;
            }
        }
        if (networkId < 0) {
            Toast.makeText(this,
                    getString(R.string.wifi_save_failed),
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            boolean enabled = wifiManager.enableNetwork(networkId, true);
            wifiManager.reconnect();
            if (!enabled) {
                Toast.makeText(this,
                        getString(R.string.wifi_enable_network_failed),
                        Toast.LENGTH_LONG).show();
            }
        } catch (SecurityException failure) {
            Toast.makeText(this,
                    getString(R.string.wifi_connect_permission_missing),
                    Toast.LENGTH_LONG).show();
        }
        handler.postDelayed(this::refreshNow, 1000L);
    }

    private static String securityOf(ScanResult result) {
        String capabilities = result.capabilities == null
                ? "" : result.capabilities;
        if (capabilities.contains("SAE")) {
            return "SAE";
        }
        if (capabilities.contains("PSK")) {
            return "WPA2";
        }
        if (capabilities.contains("EAP")) {
            return "EAP";
        }
        return "OPEN";
    }

    private static String cleanSsid(String value) {
        if (value == null || "<unknown ssid>".equals(value)) {
            return "";
        }
        if (value.length() >= 2 && value.charAt(0) == '"'
                && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    private String encodeHex(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            encoded.append(Character.forDigit((item >>> 4) & 0x0f, 16));
            encoded.append(Character.forDigit(item & 0x0f, 16));
        }
        return encoded.toString();
    }

    private static final class NetworkRow {
        final RectF bounds = new RectF();
        String ssidHex;
        String ssid;
        String signal;
        String security;
        boolean known;
    }

    private final class WifiView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF back = new RectF();
        private final RectF toggle = new RectF();
        private final RectF scan = new RectF();
        private final RectF cancel = new RectF();
        private final RectF connect = new RectF();
        private final RectF eraseKey = new RectF();
        private final RectF shiftKey = new RectF();
        private final List<NetworkRow> networks = new ArrayList<>();
        private String state = "STARTING";
        private String activeSsid = "";
        private String ipAddress = "";
        private String message;
        private boolean wifiEnabled;
        private NetworkRow selected;
        private final StringBuilder password = new StringBuilder();
        private boolean passwordMode;
        private boolean shifted;

        WifiView() {
            super(WifiActivity.this);
            setBackgroundColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            message = getString(R.string.wifi_service_starting);
        }

        void setStatus(String raw) {
            networks.clear();
            for (String line : raw.split("\\r?\\n")) {
                String[] fields = line.split("\\|", -1);
                if (line.startsWith("WIFI_ENABLED=")) {
                    wifiEnabled = "1".equals(line.substring(13));
                } else if (line.startsWith("STATE=")) {
                    state = line.substring(6);
                } else if (line.startsWith("SSIDHEX=")) {
                    activeSsid = decodeHex(line.substring(8));
                } else if (line.startsWith("IP=")) {
                    ipAddress = line.substring(3);
                } else if (line.startsWith("MESSAGE=")) {
                    message = line.substring(8);
                } else if (fields.length >= 5
                        && "NETWORK".equals(fields[0])) {
                    NetworkRow row = new NetworkRow();
                    row.ssidHex = fields[1];
                    row.ssid = decodeHex(fields[1]);
                    row.signal = fields[2];
                    row.security = fields[3];
                    row.known = "1".equals(fields[4]);
                    if (!row.ssid.isEmpty()) {
                        networks.add(row);
                    }
                }
            }
            invalidate();
        }

        void setMessage(String value) {
            message = value;
            invalidate();
        }

        private String decodeHex(String value) {
            try {
                if ((value.length() & 1) != 0) {
                    return value;
                }
                byte[] decoded = new byte[value.length() / 2];
                for (int index = 0; index < decoded.length; index++) {
                    decoded[index] = (byte) Integer.parseInt(
                            value.substring(index * 2, index * 2 + 2), 16);
                }
                return new String(decoded, StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return value;
            }
        }

        private void text(Canvas canvas, String value, float x, float y,
                          float size, boolean bold, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setFakeBoldText(bold);
            canvas.drawText(value, x, y, paint);
        }

        private void box(Canvas canvas, RectF value, int fill, int line) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRoundRect(value, 8f, 8f, paint);
            stroke.setColor(line);
            canvas.drawRoundRect(value, 8f, 8f, stroke);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int ink = Color.rgb(25, 25, 24);
            int quiet = Color.rgb(88, 88, 84);
            int line = Color.rgb(165, 165, 158);
            int pale = Color.rgb(244, 244, 240);
            float width = getWidth();

            canvas.drawColor(Color.WHITE);
            back.set(22f, 20f, 132f, 82f);
            toggle.set(width - 364f, 20f, width - 194f, 82f);
            scan.set(width - 180f, 20f, width - 22f, 82f);
            box(canvas, back, Color.WHITE, line);
            box(canvas, toggle, wifiEnabled ? pale : Color.WHITE, ink);
            box(canvas, scan, Color.WHITE, line);
            text(canvas, getString(R.string.wifi_back),
                    42f, 61f, 23f, true, ink);
            text(canvas, getString(wifiEnabled
                            ? R.string.wifi_turn_off
                            : R.string.wifi_turn_on),
                    toggle.left + 29f, 61f, 21f, true, ink);
            text(canvas, getString(R.string.wifi_rescan),
                    scan.left + 25f, 61f,
                    22f, true, ink);
            text(canvas, getString(R.string.wifi_title),
                    166f, 64f, 34f, true, ink);
            text(canvas, getString(R.string.wifi_subtitle),
                    34f, 124f, 20f, false, quiet);

            RectF status = new RectF(34f, 150f, width - 34f, 252f);
            box(canvas, status, pale, line);
            String headline;
            if (!wifiEnabled || "DISABLED".equals(state)) {
                headline = getString(R.string.wifi_headline_disabled);
            } else if ("CONNECTED".equals(state)) {
                headline = getString(
                        R.string.wifi_headline_connected, activeSsid);
            } else if ("ASSOCIATING".equals(state)) {
                headline = getString(
                        R.string.wifi_headline_connecting, message);
            } else if ("SCANNING".equals(state)) {
                headline = getString(R.string.wifi_headline_scanning);
            } else {
                headline = getString(
                        R.string.wifi_headline_offline, message);
            }
            text(canvas, headline, 58f, 194f, 25f, true, ink);
            if (!ipAddress.isEmpty()) {
                text(canvas, "IP " + ipAddress, 58f, 228f,
                        19f, false, quiet);
            }

            text(canvas, getString(R.string.wifi_available_networks),
                    34f, 310f,
                    28f, true, ink);
            if (networks.isEmpty()) {
                text(canvas, getString(R.string.wifi_no_scan_results),
                        36f, 374f, 21f, false, quiet);
            }
            float top = 338f;
            int count = Math.min(networks.size(), 8);
            for (int index = 0; index < count; index++) {
                NetworkRow row = networks.get(index);
                float y = top + index * 104f;
                row.bounds.set(34f, y, width - 34f, y + 86f);
                box(canvas, row.bounds, Color.WHITE, line);
                text(canvas, row.ssid, 58f, y + 38f, 24f, true, ink);
                String detail = row.security
                        + ("OPEN".equals(row.security) ? ""
                                : getString(R.string.wifi_detail_locked))
                        + (row.known
                                ? getString(R.string.wifi_detail_saved)
                                : "")
                        + getString(
                                R.string.wifi_detail_signal, row.signal);
                text(canvas, detail, 58f, y + 68f,
                        18f, false, quiet);
                text(canvas, "›", width - 77f, y + 57f,
                        32f, false, ink);
            }

            if (passwordMode && selected != null) {
                drawPasswordPanel(canvas, width, ink, quiet, line, pale);
            }
        }

        private void drawPasswordPanel(Canvas canvas, float width,
                                       int ink, int quiet, int line,
                                       int pale) {
            paint.setColor(Color.WHITE);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawRect(0f, 300f, width, getHeight(), paint);
            stroke.setColor(ink);
            canvas.drawLine(0f, 300f, width, 300f, stroke);
            text(canvas, selected.ssid, 34f, 356f, 31f, true, ink);
            text(canvas, getString(R.string.wifi_enter_password),
                    34f, 394f, 20f, false, quiet);

            RectF field = new RectF(34f, 426f, width - 34f, 508f);
            box(canvas, field, pale, line);
            StringBuilder dots = new StringBuilder();
            for (int index = 0; index < password.length(); index++) {
                dots.append('•');
            }
            text(canvas, dots.toString(), 56f, 480f, 29f, true, ink);
            eraseKey.set(width - 154f, 438f, width - 48f, 496f);
            box(canvas, eraseKey, Color.WHITE, line);
            text(canvas, getString(R.string.wifi_erase),
                    eraseKey.left + 18f, 477f,
                    20f, true, ink);

            String[] rows = {
                    "1234567890",
                    shifted ? "QWERTYUIOP" : "qwertyuiop",
                    shifted ? "ASDFGHJKL" : "asdfghjkl",
                    shifted ? "ZXCVBNM-_.@" : "zxcvbnm-_.@",
                    "!#$%&*()+?"
            };
            float y = 548f;
            for (String row : rows) {
                float gap = 8f;
                float keyWidth =
                        (width - 48f - gap * (row.length() - 1))
                                / row.length();
                for (int index = 0; index < row.length(); index++) {
                    float x = 24f + index * (keyWidth + gap);
                    RectF key = new RectF(x, y, x + keyWidth, y + 72f);
                    box(canvas, key, Color.WHITE, line);
                    text(canvas, Character.toString(row.charAt(index)),
                            key.centerX() - 10f, y + 48f,
                            25f, true, ink);
                }
                y += 88f;
            }
            shiftKey.set(24f, y + 8f, 212f, y + 78f);
            cancel.set(230f, y + 8f, 418f, y + 78f);
            connect.set(438f, y + 8f, width - 24f, y + 78f);
            box(canvas, shiftKey, Color.WHITE, line);
            box(canvas, cancel, Color.WHITE, line);
            box(canvas, connect, pale, ink);
            text(canvas, getString(shifted
                            ? R.string.wifi_lowercase
                            : R.string.wifi_uppercase),
                    shiftKey.left + 48f, shiftKey.top + 46f,
                    21f, true, ink);
            text(canvas, getString(R.string.action_cancel),
                    cancel.left + 69f, cancel.top + 46f,
                    21f, true, ink);
            text(canvas, getString(R.string.action_connect),
                    connect.centerX() - 24f,
                    connect.top + 46f, 23f, true, ink);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                return true;
            }
            float x = event.getX();
            float y = event.getY();
            if (passwordMode) {
                return handlePasswordTouch(x, y);
            }
            if (back.contains(x, y)) {
                finish();
                overridePendingTransition(0, 0);
                return true;
            }
            if (scan.contains(x, y)) {
                requestScan();
                message = getString(R.string.wifi_scan_requested);
                invalidate();
                return true;
            }
            if (toggle.contains(x, y)) {
                setWifiEnabled(!wifiEnabled);
                message = getString(wifiEnabled
                        ? R.string.wifi_turning_off
                        : R.string.wifi_turning_on);
                invalidate();
                return true;
            }
            for (NetworkRow row : networks) {
                if (!row.bounds.contains(x, y)) {
                    continue;
                }
                if (row.known) {
                    connectNetwork(row, null);
                    message = getString(
                            R.string.wifi_connecting_ssid, row.ssid);
                } else if ("OPEN".equals(row.security)) {
                    connectNetwork(row, null);
                    message = getString(
                            R.string.wifi_connecting_ssid, row.ssid);
                } else {
                    selected = row;
                    password.setLength(0);
                    passwordMode = true;
                }
                invalidate();
                return true;
            }
            return true;
        }

        private boolean handlePasswordTouch(float x, float y) {
            if (eraseKey.contains(x, y)) {
                if (password.length() > 0) {
                    password.deleteCharAt(password.length() - 1);
                    invalidate();
                }
                return true;
            }
            if (shiftKey.contains(x, y)) {
                shifted = !shifted;
                invalidate();
                return true;
            }
            if (cancel.contains(x, y)) {
                passwordMode = false;
                selected = null;
                invalidate();
                return true;
            }
            if (connect.contains(x, y)) {
                if (password.length() < 8) {
                    Toast.makeText(WifiActivity.this,
                            getString(R.string.wifi_password_too_short),
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                connectNetwork(selected, password.toString());
                message = getString(
                        R.string.wifi_connecting_ssid, selected.ssid);
                password.setLength(0);
                passwordMode = false;
                selected = null;
                invalidate();
                return true;
            }

            String[] rows = {
                    "1234567890",
                    shifted ? "QWERTYUIOP" : "qwertyuiop",
                    shifted ? "ASDFGHJKL" : "asdfghjkl",
                    shifted ? "ZXCVBNM-_.@" : "zxcvbnm-_.@",
                    "!#$%&*()+?"
            };
            float width = getWidth();
            float rowY = 548f;
            for (String row : rows) {
                if (y >= rowY && y <= rowY + 72f) {
                    float gap = 8f;
                    float keyWidth =
                            (width - 48f - gap * (row.length() - 1))
                                    / row.length();
                    int index = (int) ((x - 24f) / (keyWidth + gap));
                    if (index >= 0 && index < row.length()) {
                        float keyStart = 24f + index * (keyWidth + gap);
                        if (x <= keyStart + keyWidth
                                && password.length() < 63) {
                            password.append(row.charAt(index));
                            invalidate(24, 410,
                                    (int) width - 24, 520);
                        }
                    }
                    return true;
                }
                rowY += 88f;
            }
            return true;
        }
    }
}
