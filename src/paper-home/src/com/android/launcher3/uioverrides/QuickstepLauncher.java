package com.android.launcher3.uioverrides;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Toast;

import com.android.launcher3.R;
import com.android.launcher3.paper.AppLibraryActivity;
import com.android.launcher3.paper.DisplayControlService;
import com.android.launcher3.paper.InstalledApps;
import com.android.launcher3.paper.NotebookActivity;
import com.android.launcher3.paper.PaperLocale;
import com.android.launcher3.paper.PaperSystemBars;
import com.android.launcher3.paper.RecommendedAppsActivity;
import com.android.launcher3.paper.UpdateActivity;
import com.android.launcher3.paper.WifiActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Clean-room, e-ink-first HOME replacement.  It intentionally has no
 * animation, ripple, scrolling container, live clock, wallpaper, widget, or
 * continuously invalidated surface.
 */
public final class QuickstepLauncher extends Activity {
    private static final long PAGE_TRANSITION_REFRESH_MS = 260L;
    private PaperHomeView homeView;
    private volatile boolean stockTransitionInProgress;
    private final Runnable settledPageRefresh = this::requestPanelRefresh;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setBackgroundDrawable(
                new ColorDrawable(Color.rgb(248, 248, 244)));
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        syncNativeLocale();
        homeView = new PaperHomeView(this);
        PaperSystemBars.setContent(this, homeView);
        startService(new Intent(this, DisplayControlService.class));
        enterEinkFullscreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncNativeLocale();
        enterEinkFullscreen();
        if (homeView != null) {
            homeView.refreshStatus();
            homeView.invalidate();
        }
        schedulePageTransitionRefresh();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onPause() {
        /*
         * Android only reports the pixels changed by the incoming activity.
         * On E Ink that leaves the lower Paper Home cards physically visible
         * when a mostly-white page (notably All apps) replaces the launcher.
         * Wait for the destination frame, then request one full clean update.
         * This is deliberately outside the pen/touch fast path.
         */
        schedulePageTransitionRefresh();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        getWindow().getDecorView().removeCallbacks(settledPageRefresh);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterEinkFullscreen();
        }
    }

    private void enterEinkFullscreen() {
        PaperSystemBars.applyEinkSystemBarContrast(this);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private void schedulePageTransitionRefresh() {
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(settledPageRefresh);
        decor.postDelayed(settledPageRefresh, PAGE_TRANSITION_REFRESH_MS);
    }

    private void requestPanelRefresh() {
        File request = new File(getFilesDir(), "paper-refresh-request");
        try (FileOutputStream output = new FileOutputStream(request, false)) {
            output.write(Long.toString(System.currentTimeMillis())
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (IOException ignored) {
            // A missed cleanup is cosmetic; never block app navigation.
        }
    }

    private void requestStockOs() {
        if (stockTransitionInProgress) {
            return;
        }
        stockTransitionInProgress = true;

        File stockRequest = new File(getFilesDir(), "paper-stock-request");
        File stockCommitted = new File(
                getFilesDir(), "paper-stock-boot-committed");
        File stockFailed = new File(
                getFilesDir(), "paper-stock-boot-failed");
        stockCommitted.delete();
        stockFailed.delete();

        try (FileOutputStream output =
                     new FileOutputStream(stockRequest, false)) {
            output.write(Long.toString(
                    System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            Toast.makeText(this, getString(R.string.rebooting_stock),
                    Toast.LENGTH_SHORT).show();
        } catch (IOException failure) {
            stockTransitionInProgress = false;
            Toast.makeText(this, getString(R.string.stock_request_failed),
                    Toast.LENGTH_LONG).show();
            return;
        }

        Thread acknowledgement = new Thread(() -> {
            long deadline = SystemClock.elapsedRealtime() + 15_000L;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (stockFailed.isFile()) {
                    showStockRequestFailure();
                    return;
                }
                if (stockCommitted.isFile()) {
                    PowerManager powerManager =
                            (PowerManager) getSystemService(POWER_SERVICE);
                    if (powerManager == null) {
                        showStockRequestFailure();
                        return;
                    }
                    powerManager.reboot("paper-stock");
                    showStockRequestFailure();
                    return;
                }
                SystemClock.sleep(100L);
            }
            showStockRequestFailure();
        }, "paper-stock-reboot");
        acknowledgement.setDaemon(true);
        acknowledgement.start();
    }

    private void showStockRequestFailure() {
        stockTransitionInProgress = false;
        runOnUiThread(() -> Toast.makeText(
                QuickstepLauncher.this,
                getString(R.string.stock_request_failed),
                Toast.LENGTH_LONG).show());
    }

    private void syncNativeLocale() {
        File localeFile = new File(getFilesDir(), "paper-ui-locale");
        String locale = PaperLocale.nativeLanguageTag(this) + "\n";
        try (FileOutputStream output =
                     new FileOutputStream(localeFile, false)) {
            output.write(locale.getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (IOException ignored) {
            // Android resources still follow the system locale. The native
            // boot and standby pages keep their previous language if this
            // tiny shared preference cannot be written.
        }
    }

    private static final class Tile {
        final RectF bounds = new RectF();
        final String eyebrow;
        final String title;
        final String detail;
        final int action;
        final InstalledApps.AppEntry app;

        Tile(String eyebrow, String title, String detail, int action) {
            this.eyebrow = eyebrow;
            this.title = title;
            this.detail = detail;
            this.action = action;
            this.app = null;
        }

        Tile(InstalledApps.AppEntry app, String eyebrow) {
            this.eyebrow = eyebrow;
            this.title = app.label;
            this.detail = app.detail;
            this.action = 0;
            this.app = app;
        }
    }

    private final class PaperHomeView extends View {
        private static final int ACTION_RIDI = 1;
        private static final int ACTION_NOTE = 2;
        private static final int ACTION_FILES = 3;
        private static final int ACTION_SETTINGS = 4;
        private static final int ACTION_STOCK = 5;
        private static final int ACTION_WIFI = 7;
        private static final int ACTION_STORE = 8;
        private static final int ACTION_ALL_APPS = 9;
        private static final int ACTION_UPDATE = 10;

        private final Paint paint = new Paint(
                Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final List<Tile> continueTiles = new ArrayList<>();
        private final List<Tile> appTiles = new ArrayList<>();
        private final List<Tile> toolTiles = new ArrayList<>();
        private final RectF stockButton = new RectF();
        private final RectF updateButton = new RectF();
        private final boolean ridiPinned;
        private String statusText = "";
        private boolean networkConnected;
        private int installedAppCount;

        PaperHomeView(Context context) {
            super(context);
            setBackgroundColor(Color.WHITE);
            paint.setHinting(Paint.HINTING_ON);
            paint.setDither(false);
            stroke.setDither(false);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            ridiPinned = PaperLocale.isKorean(context)
                    && getPackageManager().getLaunchIntentForPackage(
                            InstalledApps.PACKAGE_RIDI) != null;
            if (ridiPinned) {
                continueTiles.add(new Tile(
                        getString(R.string.category_read),
                        getString(R.string.tile_ridi),
                        getString(R.string.tile_ridi_detail),
                        ACTION_RIDI));
            }
            continueTiles.add(new Tile(
                    getString(R.string.category_write),
                    getString(R.string.tile_notes),
                    getString(R.string.tile_notes_detail),
                    ACTION_NOTE));
            if (!ridiPinned) {
                continueTiles.add(new Tile(
                        getString(R.string.category_document),
                        getString(R.string.tile_my_files),
                        getString(R.string.tile_files_detail),
                        ACTION_FILES));
            }
            if (ridiPinned) {
                toolTiles.add(new Tile(
                        getString(R.string.category_document),
                        getString(R.string.tile_my_files),
                        getString(R.string.tile_files_detail),
                        ACTION_FILES));
            }
            toolTiles.add(new Tile(
                    getString(R.string.category_connection),
                    getString(R.string.tile_wifi),
                    getString(R.string.tile_wifi_detail),
                    ACTION_WIFI));
            toolTiles.add(new Tile(
                    getString(R.string.category_app),
                    getString(R.string.tile_all_apps),
                    getString(R.string.tile_all_apps_detail),
                    ACTION_ALL_APPS));
            if (!ridiPinned) {
                toolTiles.add(new Tile(
                        getString(R.string.category_store),
                        getString(R.string.tile_app_store),
                        getString(R.string.tile_app_store_detail),
                        ACTION_STORE));
            }
            toolTiles.add(new Tile(
                    getString(R.string.category_system),
                    getString(R.string.tile_settings),
                    getString(R.string.tile_settings_detail),
                    ACTION_SETTINGS));
            refreshStatus();
        }

        void refreshStatus() {
            BatteryManager battery =
                    (BatteryManager) getSystemService(BATTERY_SERVICE);
            int percent = battery == null ? -1
                    : battery.getIntProperty(
                            BatteryManager.BATTERY_PROPERTY_CAPACITY);
            ConnectivityManager connectivity =
                    (ConnectivityManager) getSystemService(
                            CONNECTIVITY_SERVICE);
            Network active = connectivity == null
                    ? null : connectivity.getActiveNetwork();
            NetworkCapabilities capabilities =
                    connectivity == null || active == null
                            ? null
                            : connectivity.getNetworkCapabilities(active);
            networkConnected = capabilities != null
                    && capabilities.hasCapability(
                            NetworkCapabilities.NET_CAPABILITY_INTERNET);
            String date = new SimpleDateFormat(
                    getString(R.string.date_pattern),
                    PaperLocale.current(QuickstepLauncher.this))
                    .format(new Date());
            String batteryText =
                    percent >= 0 ? "  ·  " + getString(
                            R.string.status_battery, percent) : "";
            statusText = date + batteryText
                    + "  ·  " + getString(networkConnected
                            ? R.string.status_wifi_connected
                            : R.string.status_wifi_offline);
            refreshInstalledApps();
        }

        private void refreshInstalledApps() {
            List<InstalledApps.AppEntry> installed =
                    InstalledApps.query(QuickstepLauncher.this, true);
            installedAppCount = installed.size();
            appTiles.clear();
            for (int index = 0;
                    index < installed.size() && appTiles.size() < 4;
                    index++) {
                if (ridiPinned
                        && InstalledApps.PACKAGE_RIDI.equals(
                                installed.get(index).packageName)) {
                    continue;
                }
                appTiles.add(new Tile(
                        installed.get(index),
                        getString(R.string.category_app)));
            }
        }

        private void text(Canvas canvas, String value, float x, float y,
                          float size, boolean bold, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create(
                    "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            canvas.drawText(value, x, y, paint);
        }

        private String fit(String value, float width, float size,
                           boolean bold) {
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create(
                    "sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            if (paint.measureText(value) <= width) {
                return value;
            }
            String result = value;
            while (result.length() > 1 &&
                    paint.measureText(result + "…") > width) {
                result = result.substring(0, result.length() - 1);
            }
            return result + "…";
        }

        private void surface(Canvas canvas, RectF box, int fill) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRoundRect(box, 18f, 18f, paint);
        }

        private void iconStroke(int color, float width) {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            stroke.setStrokeWidth(width);
            stroke.setColor(color);
        }

        private void drawWifiIcon(Canvas canvas, float cx, float cy,
                                  boolean connected, int color) {
            iconStroke(color, 3f);
            if (connected) {
                canvas.drawArc(new RectF(cx - 24f, cy - 24f,
                                cx + 24f, cy + 24f),
                        220f, 100f, false, stroke);
                canvas.drawArc(new RectF(cx - 15f, cy - 15f,
                                cx + 15f, cy + 15f),
                        220f, 100f, false, stroke);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(color);
                canvas.drawCircle(cx, cy + 8f, 3.8f, paint);
            } else {
                canvas.drawCircle(cx, cy, 17f, stroke);
                canvas.drawLine(cx - 12f, cy - 12f,
                        cx + 12f, cy + 12f, stroke);
            }
        }

        private void drawTileIcon(Canvas canvas, int action,
                                  float x, float y, int color) {
            iconStroke(color, 3f);
            RectF box = new RectF(x, y, x + 36f, y + 36f);
            switch (action) {
                case ACTION_RIDI:
                    canvas.drawRoundRect(box, 3f, 3f, stroke);
                    canvas.drawLine(x + 18f, y + 3f,
                            x + 18f, y + 33f, stroke);
                    canvas.drawLine(x + 6f, y + 9f,
                            x + 14f, y + 9f, stroke);
                    break;
                case ACTION_NOTE:
                    canvas.drawLine(x + 7f, y + 30f,
                            x + 29f, y + 8f, stroke);
                    canvas.drawLine(x + 11f, y + 34f,
                            x + 33f, y + 12f, stroke);
                    canvas.drawLine(x + 7f, y + 30f,
                            x + 5f, y + 36f, stroke);
                    break;
                case ACTION_FILES:
                    Path folder = new Path();
                    folder.moveTo(x + 2f, y + 10f);
                    folder.lineTo(x + 15f, y + 10f);
                    folder.lineTo(x + 20f, y + 15f);
                    folder.lineTo(x + 34f, y + 15f);
                    folder.lineTo(x + 34f, y + 33f);
                    folder.lineTo(x + 2f, y + 33f);
                    folder.close();
                    canvas.drawPath(folder, stroke);
                    break;
                case ACTION_SETTINGS:
                    canvas.drawCircle(x + 18f, y + 18f, 11f, stroke);
                    canvas.drawCircle(x + 18f, y + 18f, 4f, stroke);
                    for (int index = 0; index < 8; index++) {
                        double angle = index * Math.PI / 4.0;
                        canvas.drawLine(
                                x + 18f + (float) Math.cos(angle) * 14f,
                                y + 18f + (float) Math.sin(angle) * 14f,
                                x + 18f + (float) Math.cos(angle) * 18f,
                                y + 18f + (float) Math.sin(angle) * 18f,
                                stroke);
                    }
                    break;
                case ACTION_WIFI:
                    drawWifiIcon(canvas, x + 18f, y + 18f,
                            networkConnected, color);
                    break;
                case ACTION_STORE:
                    canvas.drawRoundRect(box, 7f, 7f, stroke);
                    canvas.drawLine(x + 10f, y + 12f,
                            x + 13f, y + 4f, stroke);
                    canvas.drawLine(x + 26f, y + 12f,
                            x + 23f, y + 4f, stroke);
                    canvas.drawLine(x + 10f, y + 23f,
                            x + 26f, y + 23f, stroke);
                    break;
                case ACTION_ALL_APPS:
                    for (int row = 0; row < 2; row++) {
                        for (int column = 0; column < 2; column++) {
                            RectF cell = new RectF(
                                    x + 3f + column * 18f,
                                    y + 3f + row * 18f,
                                    x + 15f + column * 18f,
                                    y + 15f + row * 18f);
                            canvas.drawRoundRect(cell, 2f, 2f, stroke);
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        private void drawAppMark(Canvas canvas, Tile tile,
                                 float x, float y, int ink) {
            RectF mark = new RectF(x, y, x + 62f, y + 62f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(mark, 14f, 14f, paint);
            iconStroke(Color.rgb(48, 48, 46), 2.5f);
            canvas.drawRoundRect(mark, 14f, 14f, stroke);
            paint.setTextAlign(Paint.Align.CENTER);
            text(canvas, tile.app.mark, mark.centerX(),
                    mark.centerY() + 11f, 30f, true, ink);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final float width = getWidth();
            final float height = getHeight();
            final int ink = Color.BLACK;
            final int quiet = Color.rgb(56, 56, 52);
            final int line = Color.rgb(128, 127, 121);

            canvas.drawColor(Color.WHITE);
            text(canvas, getString(R.string.brand_name),
                    30f, 54f, 28f, true, ink);
            text(canvas, getString(R.string.home_title),
                    30f, 105f, 38f, true, ink);
            text(canvas, statusText, 30f, 143f, 20f, false, quiet);
            drawWifiIcon(canvas, width - 68f, 72f,
                    networkConnected, ink);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(line);
            canvas.drawRect(30f, 174f, width - 30f, 176f, paint);

            final float left = 26f;
            final float right = width - 26f;
            final float gap = 18f;
            final float tileWidth = (right - left - gap) / 2f;

            text(canvas, getString(R.string.section_continue),
                    30f, 226f, 28f, true, ink);
            for (int index = 0; index < 2; index++) {
                int column = index % 2;
                float x = left + column * (tileWidth + gap);
                float y = 250f;
                Tile tile = continueTiles.get(index);
                tile.bounds.set(x, y, x + tileWidth, y + 296f);
                surface(canvas, tile.bounds, Color.rgb(242, 242, 238));
                drawTileIcon(canvas, tile.action, x + 24f, y + 25f, ink);
                text(canvas, tile.eyebrow, x + 76f, y + 53f,
                        19f, true, quiet);
                text(canvas, tile.title, x + 26f, y + 130f,
                        34f, true, ink);
                text(canvas, detailFor(tile), x + 26f, y + 178f,
                        20f, false, quiet);
                text(canvas, "›", x + tileWidth - 45f,
                        y + 268f, 34f, false, ink);
            }

            text(canvas, getString(R.string.section_my_apps),
                    30f, 616f, 28f, true, ink);
            text(canvas, getString(
                            R.string.installed_count_short,
                            installedAppCount),
                    width - 172f, 616f, 17f, false, quiet);
            final float appHeight = 188f;
            final float appGap = 16f;
            for (int index = 0; index < appTiles.size(); index++) {
                int column = index % 2;
                int row = index / 2;
                float x = left + column * (tileWidth + gap);
                float y = 642f + row * (appHeight + appGap);
                Tile tile = appTiles.get(index);
                tile.bounds.set(x, y, x + tileWidth, y + appHeight);
                surface(canvas, tile.bounds, Color.rgb(242, 242, 238));
                drawAppMark(canvas, tile, x + 22f, y + 20f, ink);
                text(canvas, tile.eyebrow, x + 102f, y + 59f,
                        18f, true, quiet);
                text(canvas, fit(tile.title, tileWidth - 52f,
                                28f, true),
                        x + 24f, y + 112f, 28f, true, ink);
                text(canvas, fit(tile.detail, tileWidth - 76f,
                                17f, false),
                        x + 24f, y + 151f,
                        17f, false, quiet);
                text(canvas, "›", x + tileWidth - 37f,
                        y + appHeight - 24f, 27f, false, ink);
            }
            if (appTiles.isEmpty()) {
                RectF empty = new RectF(left, 642f, right, 830f);
                surface(canvas, empty, Color.rgb(242, 242, 238));
                text(canvas, getString(R.string.no_installed_apps),
                        left + 25f,
                        711f, 27f, true, ink);
                text(canvas, getString(R.string.install_apps_hint),
                        left + 25f, 758f, 18f, false, quiet);
            }

            text(canvas, getString(R.string.section_tools),
                    30f, 1092f, 27f, true, ink);
            final float toolGap = 12f;
            final float toolWidth =
                    (right - left - toolGap * 3f) / 4f;
            final float toolY = 1117f;
            final float toolHeight = 142f;
            for (int index = 0; index < toolTiles.size(); index++) {
                float x = left + index * (toolWidth + toolGap);
                Tile tile = toolTiles.get(index);
                tile.bounds.set(
                        x, toolY, x + toolWidth, toolY + toolHeight);
                surface(canvas, tile.bounds, Color.rgb(242, 242, 238));
                drawTileIcon(canvas, tile.action,
                        x + 18f, toolY + 17f, ink);
                text(canvas, fit(tile.title, toolWidth - 28f,
                                21f, true),
                        x + 16f, toolY + 91f, 21f, true, ink);
                text(canvas, fit(tile.detail, toolWidth - 28f,
                                15f, false),
                        x + 16f, toolY + 119f, 15f, false, quiet);
            }

            stockButton.set(26f, height - 92f, 178f, height - 28f);
            updateButton.set(width - 224f, height - 92f,
                    width - 26f, height - 28f);
            surface(canvas, stockButton, Color.rgb(242, 242, 238));
            text(canvas, getString(R.string.stock_os),
                    stockButton.left + 20f,
                    stockButton.centerY() + 8f, 20f, true, ink);
            text(canvas, fit(getString(R.string.stock_os_detail),
                            updateButton.left - 224f, 17f, false),
                    206f, height - 49f, 17f, false, quiet);
            surface(canvas, updateButton, Color.rgb(242, 242, 238));
            text(canvas, getString(R.string.update_home_button),
                    updateButton.left + 20f,
                    updateButton.centerY() + 8f, 20f, true, ink);
        }

        private String detailFor(Tile tile) {
            if (tile.action != ACTION_NOTE) {
                return tile.detail;
            }
            int count = new File(getFilesDir(), "quick-note.png").isFile()
                    ? 1 : 0;
            File directory = new File(getFilesDir(), "notes");
            File[] files = directory.listFiles((dir, name) ->
                    name.startsWith("note-") && name.endsWith(".png"));
            if (files != null) {
                count += files.length;
            }
            return getString(
                    R.string.note_summary,
                    count,
                    NotebookActivity.TEMPLATE_IDS.length);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                return true;
            }
            float x = event.getX();
            float y = event.getY();
            if (stockButton.contains(x, y)) {
                performAction(ACTION_STOCK);
                return true;
            }
            if (updateButton.contains(x, y)) {
                performAction(ACTION_UPDATE);
                return true;
            }
            for (Tile tile : continueTiles) {
                if (tile.bounds.contains(x, y)) {
                    performAction(tile.action);
                    return true;
                }
            }
            for (Tile tile : appTiles) {
                if (tile.bounds.contains(x, y)) {
                    launchInstalledApp(tile);
                    return true;
                }
            }
            for (Tile tile : toolTiles) {
                if (tile.bounds.contains(x, y)) {
                    performAction(tile.action);
                    return true;
                }
            }
            return true;
        }

        private void launchInstalledApp(Tile tile) {
            if (tile.app == null) {
                return;
            }
            try {
                startActivity(new Intent(tile.app.launchIntent));
                overridePendingTransition(0, 0);
            } catch (RuntimeException failure) {
                Toast.makeText(QuickstepLauncher.this,
                        getString(R.string.app_open_failed, tile.title),
                        Toast.LENGTH_SHORT).show();
                refreshInstalledApps();
                invalidate();
            }
        }

        private void performAction(int action) {
            switch (action) {
                case ACTION_RIDI:
                    Intent ridi = getPackageManager()
                            .getLaunchIntentForPackage("com.initialcoms.ridi");
                    if (ridi == null) {
                        Toast.makeText(QuickstepLauncher.this,
                                getString(R.string.ridi_not_found),
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    ridi.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(ridi);
                    break;
                case ACTION_NOTE:
                    Intent note = new Intent(
                            QuickstepLauncher.this, NotebookActivity.class);
                    note.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(note);
                    break;
                case ACTION_FILES:
                    Intent files = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    files.addCategory(Intent.CATEGORY_OPENABLE);
                    files.setType("*/*");
                    files.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(files);
                    break;
                case ACTION_SETTINGS:
                    Intent settings = new Intent(Settings.ACTION_SETTINGS);
                    settings.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(settings);
                    break;
                case ACTION_WIFI:
                    Intent wifi = new Intent(
                            QuickstepLauncher.this, WifiActivity.class);
                    wifi.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(wifi);
                    break;
                case ACTION_STORE:
                    Intent recommendations = new Intent(
                            QuickstepLauncher.this,
                            RecommendedAppsActivity.class);
                    recommendations.addFlags(
                            Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(recommendations);
                    break;
                case ACTION_ALL_APPS:
                    Intent allApps = new Intent(
                            QuickstepLauncher.this,
                            AppLibraryActivity.class);
                    allApps.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(allApps);
                    break;
                case ACTION_UPDATE:
                    Intent update = new Intent(
                            QuickstepLauncher.this, UpdateActivity.class);
                    update.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    startActivity(update);
                    break;
                case ACTION_STOCK:
                    requestStockOs();
                    break;
                default:
                    break;
            }
            overridePendingTransition(0, 0);
        }
    }
}
