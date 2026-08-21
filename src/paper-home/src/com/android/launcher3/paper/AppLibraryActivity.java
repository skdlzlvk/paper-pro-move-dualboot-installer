package com.android.launcher3.paper;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

import com.android.launcher3.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A paged app library that avoids animated scrolling on E Ink. */
public final class AppLibraryActivity extends Activity {
    private static final long PAGE_SETTLE_REFRESH_MS = 260L;
    private AppLibraryView libraryView;
    private final Runnable settledPageRefresh = this::requestPanelRefresh;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        libraryView = new AppLibraryView();
        PaperSystemBars.setContent(this, libraryView);
        enterEinkFullscreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterEinkFullscreen();
        libraryView.reload();
        scheduleSettledPageRefresh();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterEinkFullscreen();
            scheduleSettledPageRefresh();
        }
    }

    @Override
    protected void onDestroy() {
        if (libraryView != null) {
            libraryView.removeCallbacks(settledPageRefresh);
        }
        super.onDestroy();
    }

    private void scheduleSettledPageRefresh() {
        if (libraryView == null) {
            return;
        }
        libraryView.removeCallbacks(settledPageRefresh);
        libraryView.postDelayed(settledPageRefresh, PAGE_SETTLE_REFRESH_MS);
    }

    private void requestPanelRefresh() {
        File request = new File(getFilesDir(), "paper-refresh-request");
        try (FileOutputStream output = new FileOutputStream(request, false)) {
            output.write(Long.toString(System.currentTimeMillis())
                    .getBytes(StandardCharsets.US_ASCII));
            output.flush();
        } catch (IOException ignored) {
            // Navigation must remain responsive if a cosmetic cleanup misses.
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

    private final class AppLibraryView extends View {
        private static final int APPS_PER_PAGE = 8;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF backButton = new RectF();
        private final RectF recommendedButton = new RectF();
        private final RectF previousButton = new RectF();
        private final RectF nextButton = new RectF();
        private final List<AppHit> appHits = new ArrayList<>();
        private List<InstalledApps.AppEntry> apps = new ArrayList<>();
        private int page;

        AppLibraryView() {
            super(AppLibraryActivity.this);
            setBackgroundColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2f);
            reload();
        }

        void reload() {
            apps = InstalledApps.query(AppLibraryActivity.this, true);
            int pages = pageCount();
            if (page >= pages) {
                page = Math.max(0, pages - 1);
            }
            invalidate();
        }

        private int pageCount() {
            return Math.max(1,
                    (apps.size() + APPS_PER_PAGE - 1) / APPS_PER_PAGE);
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
            canvas.drawRoundRect(box, 15f, 15f, paint);
        }

        private void appMark(Canvas canvas, InstalledApps.AppEntry app,
                             float x, float y) {
            RectF mark = new RectF(x, y, x + 66f, y + 66f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(mark, 14f, 14f, paint);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2.5f);
            stroke.setColor(Color.rgb(70, 70, 68));
            canvas.drawRoundRect(mark, 14f, 14f, stroke);
            paint.setTextAlign(Paint.Align.CENTER);
            text(canvas, app.mark, mark.centerX(), mark.centerY() + 12f,
                    32f, true, Color.rgb(28, 28, 27));
            paint.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            final int ink = Color.rgb(24, 24, 24);
            final int quiet = Color.rgb(88, 87, 82);
            final int soft = Color.rgb(246, 246, 242);
            final float width = getWidth();
            final float height = getHeight();

            canvas.drawColor(Color.WHITE);
            backButton.set(24f, 24f, 142f, 88f);
            surface(canvas, backButton, soft);
            text(canvas, getString(R.string.nav_home),
                    43f, 66f, 22f, true, ink);
            recommendedButton.set(width - 290f, 24f, width - 24f, 88f);
            surface(canvas, recommendedButton, soft);
            text(canvas, getString(R.string.recommended_apps_button),
                    recommendedButton.left + 22f, 66f, 21f, true, ink);
            text(canvas, getString(R.string.app_library_title),
                    28f, 145f, 39f, true, ink);
            text(canvas, getString(
                            R.string.installed_count_detail, apps.size()),
                    28f, 181f, 19f, false, quiet);

            appHits.clear();
            final float left = 24f;
            final float right = width - 24f;
            final float gap = 16f;
            final float cardWidth = (right - left - gap) / 2f;
            final float cardHeight = 258f;
            final int start = page * APPS_PER_PAGE;
            final int end = Math.min(apps.size(), start + APPS_PER_PAGE);

            for (int index = start; index < end; index++) {
                int slot = index - start;
                int column = slot % 2;
                int row = slot / 2;
                float x = left + column * (cardWidth + gap);
                float y = 214f + row * (cardHeight + gap);
                RectF bounds = new RectF(
                        x, y, x + cardWidth, y + cardHeight);
                InstalledApps.AppEntry app = apps.get(index);
                appHits.add(new AppHit(bounds, app));
                surface(canvas, bounds, soft);
                appMark(canvas, app, x + 22f, y + 22f);
                text(canvas, getString(R.string.category_app),
                        x + 105f, y + 63f,
                        18f, true, quiet);
                text(canvas, fit(app.label, cardWidth - 46f, 29f, true),
                        x + 23f, y + 137f, 29f, true, ink);
                text(canvas, fit(app.detail, cardWidth - 66f,
                                18f, false),
                        x + 23f, y + 181f, 18f, false, quiet);
                text(canvas, getString(R.string.action_open),
                        x + 23f, y + 228f,
                        18f, true, ink);
            }

            int pages = pageCount();
            previousButton.set(24f, height - 101f,
                    width / 2f - 8f, height - 27f);
            nextButton.set(width / 2f + 8f, height - 101f,
                    width - 24f, height - 27f);
            surface(canvas, previousButton,
                    page > 0 ? soft : Color.rgb(252, 252, 249));
            surface(canvas, nextButton,
                    page + 1 < pages ? soft : Color.rgb(252, 252, 249));
            text(canvas, getString(R.string.action_previous),
                    previousButton.left + 24f,
                    previousButton.centerY() + 8f, 21f, true,
                    page > 0 ? ink : Color.rgb(170, 170, 166));
            text(canvas, getString(R.string.action_next),
                    nextButton.left + 24f,
                    nextButton.centerY() + 8f, 21f, true,
                    page + 1 < pages ? ink : Color.rgb(170, 170, 166));
            paint.setTextAlign(Paint.Align.CENTER);
            text(canvas, (page + 1) + " / " + pages,
                    width / 2f, height - 122f,
                    17f, false, quiet);
            paint.setTextAlign(Paint.Align.LEFT);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                return true;
            }
            float x = event.getX();
            float y = event.getY();
            if (backButton.contains(x, y)) {
                finish();
                overridePendingTransition(0, 0);
                return true;
            }
            if (recommendedButton.contains(x, y)) {
                Intent recommendations = new Intent(
                        AppLibraryActivity.this,
                        RecommendedAppsActivity.class);
                recommendations.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(recommendations);
                overridePendingTransition(0, 0);
                return true;
            }
            if (previousButton.contains(x, y) && page > 0) {
                page--;
                invalidate();
                return true;
            }
            if (nextButton.contains(x, y) && page + 1 < pageCount()) {
                page++;
                invalidate();
                return true;
            }
            for (AppHit hit : appHits) {
                if (hit.bounds.contains(x, y)) {
                    Intent launch = new Intent(hit.app.launchIntent);
                    startActivity(launch);
                    overridePendingTransition(0, 0);
                    return true;
                }
            }
            return true;
        }
    }

    private static final class AppHit {
        final RectF bounds;
        final InstalledApps.AppEntry app;

        AppHit(RectF bounds, InstalledApps.AppEntry app) {
            this.bounds = bounds;
            this.app = app;
        }
    }
}
