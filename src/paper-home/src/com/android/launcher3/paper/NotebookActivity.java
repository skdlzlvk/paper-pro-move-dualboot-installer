package com.android.launcher3.paper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.UserManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import com.android.launcher3.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * E-ink-first note library and template picker.
 *
 * Pages are deliberately used instead of animated scrolling. A single tap
 * therefore produces one stable Android frame and one panel update.
 */
public final class NotebookActivity extends Activity {
    public static final String EXTRA_NOTE_ID = "paper.note.id";
    public static final String EXTRA_NOTE_TITLE = "paper.note.title";
    public static final String EXTRA_NOTE_TEMPLATE = "paper.note.template";

    public static final String[] TEMPLATE_IDS = {
            "blank", "ruled", "grid", "dots", "checklist",
            "cornell", "daily", "weekly", "storyboard", "music",
            "meeting", "daily_todos", "focus", "eisenhower", "gratitude"
    };
    private static final int[] TEMPLATE_LABEL_RES_IDS = {
            R.string.template_blank,
            R.string.template_ruled,
            R.string.template_grid,
            R.string.template_dots,
            R.string.template_checklist,
            R.string.template_cornell,
            R.string.template_daily,
            R.string.template_weekly,
            R.string.template_storyboard,
            R.string.template_music,
            R.string.template_meeting,
            R.string.template_daily_todos,
            R.string.template_focus,
            R.string.template_eisenhower,
            R.string.template_gratitude
    };

    private NotebookView notebookView;
    private final ExecutorService thumbnailExecutor =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(
                        runnable, "paper-note-thumbnails");
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
    private boolean firstResume = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        UserManager userManager =
                (UserManager) getSystemService(USER_SERVICE);
        if (userManager != null && !userManager.isUserUnlocked()) {
            finish();
            return;
        }
        notebookView = new NotebookView();
        PaperSystemBars.setContent(this, notebookView);
        enterFullscreen();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterFullscreen();
        if (notebookView != null) {
            // The view constructor already populated the first library page.
            // Avoid doing the same storage scan twice during cold launch.
            if (firstResume) {
                firstResume = false;
            } else {
                notebookView.reloadNotes();
            }
            notebookView.invalidate();
        }
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        thumbnailExecutor.shutdownNow();
        if (notebookView != null) {
            notebookView.releaseThumbnails();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterFullscreen();
        }
    }

    private void enterFullscreen() {
        PaperSystemBars.applyEinkSystemBarContrast(this);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private final class NotebookView extends View {
        private static final int NOTES_PER_PAGE = 4;
        private static final int TEMPLATES_PER_PAGE = 10;
        private static final int MODE_LIBRARY = 0;
        private static final int MODE_TEMPLATES = 1;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final ColorMatrixColorFilter thumbnailContrastFilter =
                new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                        1.32f, 0f, 0f, 0f, -40.8f,
                        0f, 1.32f, 0f, 0f, -40.8f,
                        0f, 0f, 1.32f, 0f, -40.8f,
                        0f, 0f, 0f, 1f, 0f
                }));
        private final RectF backButton = new RectF();
        private final RectF newButton = new RectF();
        private final RectF previousButton = new RectF();
        private final RectF nextButton = new RectF();
        private final RectF menuPanel = new RectF();
        private final RectF menuOpenButton = new RectF();
        private final RectF menuRenameButton = new RectF();
        private final RectF menuDeleteButton = new RectF();
        private final RectF menuCloseButton = new RectF();
        private final RectF[] cardBounds = {
                new RectF(), new RectF(), new RectF(), new RectF(),
                new RectF(), new RectF(), new RectF(), new RectF(),
                new RectF(), new RectF()
        };
        private final RectF[] menuBounds = {
                new RectF(), new RectF(), new RectF(), new RectF()
        };
        private final ArrayList<NoteEntry> notes = new ArrayList<>();
        private final SharedPreferences metadata;

        private int mode = MODE_LIBRARY;
        private int pageIndex;
        private int templatePageIndex;
        private int activeMenuIndex = -1;
        private boolean confirmDelete;
        private int thumbnailGeneration;

        NotebookView() {
            super(NotebookActivity.this);
            setBackgroundColor(Color.WHITE);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.8f);
            metadata = getSharedPreferences("paper-notes", MODE_PRIVATE);
            reloadNotes();
        }

        void reloadNotes() {
            thumbnailGeneration++;
            releaseThumbnails();
            notes.clear();
            Set<String> knownIds = new HashSet<>();
            File legacy = new File(getFilesDir(), "quick-note.png");
            if (legacy.isFile()) {
                notes.add(new NoteEntry(
                        "quick-note", getString(R.string.quick_note), "blank",
                        legacy.lastModified(), legacy));
                knownIds.add("quick-note");
            }

            File directory = new File(getFilesDir(), "notes");
            File[] files = directory.listFiles((dir, name) ->
                    name.startsWith("note-")
                            && name.endsWith(".png")
                            && !name.matches(".*-page-[0-9]+\\.png"));
            if (files != null) {
                for (File file : files) {
                    String id = file.getName().substring(
                            0, file.getName().length() - 4);
                    addNoteFromMetadata(
                            id, file, file.lastModified(), knownIds);
                }
            }

            for (Map.Entry<String, ?> entry
                    : metadata.getAll().entrySet()) {
                String key = entry.getKey();
                if (!key.endsWith(".title")) {
                    continue;
                }
                String id = key.substring(0, key.length() - 6);
                if (!id.startsWith("note-") || knownIds.contains(id)) {
                    continue;
                }
                File file = new File(directory, id + ".png");
                addNoteFromMetadata(
                        id, file,
                        metadata.getLong(id + ".updated", 0L), knownIds);
            }

            Collections.sort(notes, new Comparator<NoteEntry>() {
                @Override
                public int compare(NoteEntry left, NoteEntry right) {
                    return Long.compare(right.updated, left.updated);
                }
            });
            int pageCount = Math.max(1,
                    (notes.size() + NOTES_PER_PAGE - 1) / NOTES_PER_PAGE);
            pageIndex = Math.max(0, Math.min(pageIndex, pageCount - 1));
        }

        private void addNoteFromMetadata(String id, File file,
                                         long fallbackUpdated,
                                         Set<String> knownIds) {
            String title = metadata.getString(
                    id + ".title", defaultTitle(fallbackUpdated));
            String template = metadata.getString(
                    id + ".template", "blank");
            long updated = metadata.getLong(
                    id + ".updated", fallbackUpdated);
            notes.add(new NoteEntry(
                    id, title, template, updated, file));
            knownIds.add(id);
        }

        private void releaseThumbnails() {
            for (NoteEntry note : notes) {
                if (note.thumbnail != null) {
                    note.thumbnail.recycle();
                    note.thumbnail = null;
                }
            }
        }

        private String defaultTitle(long time) {
            long value = time > 0L ? time : System.currentTimeMillis();
            String date = new SimpleDateFormat(
                    getString(R.string.note_date_pattern),
                    PaperLocale.current(NotebookActivity.this))
                    .format(new Date(value));
            return getString(R.string.note_default_title, date);
        }

        private void text(Canvas canvas, String value, float x, float y,
                          float size, boolean bold, int color) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setFakeBoldText(bold);
            canvas.drawText(value, x, y, paint);
        }

        private void centered(Canvas canvas, String value, RectF bounds,
                              float size, boolean bold, int color) {
            paint.setTextSize(size);
            paint.setFakeBoldText(bold);
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            float x = bounds.centerX() - paint.measureText(value) * 0.5f;
            float y = bounds.centerY()
                    - (paint.ascent() + paint.descent()) * 0.5f;
            canvas.drawText(value, x, y, paint);
        }

        private void box(Canvas canvas, RectF bounds,
                         int fill, int line, float radius) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(fill);
            canvas.drawRoundRect(bounds, radius, radius, paint);
            if (Color.alpha(line) != 0) {
                stroke.setStyle(Paint.Style.STROKE);
                stroke.setStrokeWidth(1.2f);
                stroke.setColor(line);
                canvas.drawRoundRect(bounds, radius, radius, stroke);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.WHITE);
            final int ink = Color.rgb(30, 30, 28);
            final int quiet = Color.rgb(92, 91, 87);
            final int line = Color.rgb(174, 172, 165);

            backButton.set(18f, 16f, 122f, 78f);
            box(canvas, backButton, Color.rgb(242, 242, 238),
                    Color.TRANSPARENT, 18f);
            centered(canvas, getString(mode == MODE_LIBRARY
                            ? R.string.notebook_back_home
                            : R.string.notebook_back_list),
                    backButton, 19f, true, ink);

            if (mode == MODE_LIBRARY) {
                drawLibrary(canvas, ink, quiet, line);
            } else {
                drawTemplates(canvas, ink, quiet, line);
            }
        }

        private void drawLibrary(Canvas canvas, int ink,
                                 int quiet, int line) {
            text(canvas, getString(R.string.notebook_title),
                    150f, 59f, 33f, true, ink);
            text(canvas, getString(
                            R.string.notebook_count_recent, notes.size()),
                    150f, 92f, 17f, false, quiet);
            newButton.set(getWidth() - 218f, 16f,
                    getWidth() - 18f, 78f);
            box(canvas, newButton, ink, Color.TRANSPARENT, 18f);
            centered(canvas, getString(R.string.notebook_new_note), newButton,
                    20f, true, Color.WHITE);

            float gap = 18f;
            float left = 24f;
            float right = getWidth() - 24f;
            float top = 128f;
            float footerTop = getHeight() - 106f;
            float cardWidth = (right - left - gap) / 2f;
            float cardHeight = (footerTop - top - gap) / 2f;
            int start = pageIndex * NOTES_PER_PAGE;
            int visible = Math.min(NOTES_PER_PAGE, notes.size() - start);
            for (int slot = 0; slot < NOTES_PER_PAGE; slot++) {
                int column = slot % 2;
                int row = slot / 2;
                float x = left + column * (cardWidth + gap);
                float y = top + row * (cardHeight + gap);
                RectF bounds = cardBounds[slot];
                bounds.set(x, y, x + cardWidth, y + cardHeight);
                if (slot >= visible) {
                    bounds.setEmpty();
                    menuBounds[slot].setEmpty();
                    continue;
                }
                NoteEntry note = notes.get(start + slot);
                box(canvas, bounds, Color.rgb(245, 245, 241),
                        Color.TRANSPARENT, 18f);
                RectF preview = new RectF(
                        x + 14f, y + 14f,
                        x + cardWidth - 14f, y + cardHeight - 104f);
                drawNotePreview(canvas, note, preview, line);
                text(canvas, ellipsize(note.title, cardWidth - 136f),
                        x + 18f, preview.bottom + 39f,
                        22f, true, ink);
                text(canvas, templateLabel(note.template)
                                + "  ·  " + formatUpdated(note.updated),
                        x + 18f, preview.bottom + 72f,
                        15f, false, quiet);
                RectF manage = menuBounds[slot];
                manage.set(x + cardWidth - 112f, preview.bottom + 14f,
                        x + cardWidth - 18f, preview.bottom + 72f);
                box(canvas, manage, Color.rgb(229, 229, 224),
                        Color.TRANSPARENT, 17f);
                centered(canvas, getString(R.string.notebook_manage),
                        manage, 17f, true, ink);
            }

            int pageCount = Math.max(1,
                    (notes.size() + NOTES_PER_PAGE - 1) / NOTES_PER_PAGE);
            previousButton.set(24f, getHeight() - 82f,
                    154f, getHeight() - 22f);
            nextButton.set(getWidth() - 272f, getHeight() - 82f,
                    getWidth() - 118f, getHeight() - 22f);
            box(canvas, previousButton, Color.rgb(242, 242, 238),
                    Color.TRANSPARENT, 18f);
            box(canvas, nextButton, Color.rgb(242, 242, 238),
                    Color.TRANSPARENT, 18f);
            centered(canvas, getString(R.string.notebook_previous),
                    previousButton,
                    18f, pageIndex > 0, pageIndex > 0 ? ink : line);
            centered(canvas, getString(R.string.notebook_next), nextButton,
                    18f, pageIndex + 1 < pageCount,
                    pageIndex + 1 < pageCount ? ink : line);
            String page = (pageIndex + 1) + " / " + pageCount;
            paint.setTextSize(17f);
            text(canvas, page,
                    getWidth() * 0.5f - paint.measureText(page) * 0.5f,
                    getHeight() - 45f, 17f, false, quiet);

            if (activeMenuIndex >= 0
                    && activeMenuIndex < notes.size()) {
                drawNoteMenu(canvas, notes.get(activeMenuIndex),
                        ink, quiet);
            }
        }

        private void drawNoteMenu(Canvas canvas, NoteEntry note,
                                  int ink, int quiet) {
            menuPanel.set(96f, 330f, getWidth() - 96f,
                    getHeight() - 250f);
            box(canvas, menuPanel, Color.rgb(239, 239, 235),
                    Color.TRANSPARENT, 24f);
            text(canvas, getString(R.string.notebook_manage_title),
                    menuPanel.left + 40f,
                    menuPanel.top + 62f, 25f, true, ink);
            text(canvas, ellipsize(note.title,
                            menuPanel.width() - 80f),
                    menuPanel.left + 40f, menuPanel.top + 108f,
                    22f, true, ink);
            text(canvas, templateLabel(note.template),
                    menuPanel.left + 40f, menuPanel.top + 142f,
                    16f, false, quiet);

            float left = menuPanel.left + 34f;
            float right = menuPanel.right - 34f;
            float top = menuPanel.top + 184f;
            float height = 78f;
            float gap = 18f;
            menuOpenButton.set(left, top, right, top + height);
            menuRenameButton.set(left, top + height + gap,
                    right, top + height * 2f + gap);
            menuDeleteButton.set(left, top + (height + gap) * 2f,
                    right, top + height * 3f + gap * 2f);
            menuCloseButton.set(left, top + (height + gap) * 3f,
                    right, top + height * 4f + gap * 3f);

            box(canvas, menuOpenButton, ink,
                    Color.TRANSPARENT, 20f);
            box(canvas, menuRenameButton, Color.WHITE,
                    Color.TRANSPARENT, 20f);
            box(canvas, menuDeleteButton,
                    confirmDelete ? Color.rgb(211, 211, 205)
                            : Color.WHITE,
                    Color.TRANSPARENT, 20f);
            box(canvas, menuCloseButton, Color.rgb(226, 226, 221),
                    Color.TRANSPARENT, 20f);
            centered(canvas, getString(R.string.notebook_open),
                    menuOpenButton,
                    20f, true, Color.WHITE);
            centered(canvas, getString(R.string.notebook_rename),
                    menuRenameButton,
                    20f, true, ink);
            centered(canvas,
                    confirmDelete
                            ? getString(
                                    R.string.notebook_delete_confirm)
                            : ("quick-note".equals(note.id)
                            ? getString(
                                    R.string.notebook_quick_note_no_delete)
                            : getString(R.string.action_delete)),
                    menuDeleteButton,
                    confirmDelete ? 17f : 20f, true, ink);
            centered(canvas, getString(R.string.notebook_close),
                    menuCloseButton,
                    20f, true, ink);
        }

        private void drawNotePreview(Canvas canvas, NoteEntry note,
                                     RectF bounds, int line) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRect(bounds, paint);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.2f);
            stroke.setColor(line);
            canvas.drawRect(bounds, stroke);

            Bitmap thumbnail = ensureThumbnail(note);
            if (thumbnail == null) {
                drawTemplatePreview(canvas, note.template, bounds, line);
                return;
            }
            int cropTop = Math.min(
                    thumbnail.getHeight() - 1,
                    Math.round(thumbnail.getHeight() * 0.052f));
            Rect source = new Rect(
                    0, cropTop, thumbnail.getWidth(), thumbnail.getHeight());
            float sourceRatio =
                    source.width() / (float) source.height();
            float targetRatio = bounds.width() / bounds.height();
            RectF destination = new RectF(bounds);
            if (sourceRatio > targetRatio) {
                float height = bounds.width() / sourceRatio;
                destination.top = bounds.centerY() - height * 0.5f;
                destination.bottom = destination.top + height;
            } else {
                float width = bounds.height() * sourceRatio;
                destination.left = bounds.centerX() - width * 0.5f;
                destination.right = destination.left + width;
            }
            // The previous 1/4 decode was enlarged again into this card,
            // turning one-pixel handwriting into pale bilinear gray. Keep a
            // near-target bitmap and add modest contrast so thin ink survives
            // the final downscale on Gallery 3.
            paint.setColorFilter(thumbnailContrastFilter);
            paint.setFilterBitmap(true);
            canvas.drawBitmap(thumbnail, source, destination, paint);
            paint.setFilterBitmap(false);
            paint.setColorFilter(null);
            canvas.drawRect(bounds, stroke);
        }

        private Bitmap ensureThumbnail(NoteEntry note) {
            if (note.thumbnail != null && !note.thumbnail.isRecycled()) {
                return note.thumbnail;
            }
            if (note.file == null || !note.file.isFile()) {
                return null;
            }
            if (note.thumbnailLoading) {
                return null;
            }
            note.thumbnailLoading = true;
            final int generation = thumbnailGeneration;
            final String path = note.file.getPath();
            thumbnailExecutor.execute(() -> {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inSampleSize = 2;
                final Bitmap decoded = BitmapFactory.decodeFile(path, options);
                post(() -> {
                    note.thumbnailLoading = false;
                    if (generation != thumbnailGeneration
                            || !notes.contains(note)
                            || isFinishing() || isDestroyed()) {
                        if (decoded != null) {
                            decoded.recycle();
                        }
                        return;
                    }
                    note.thumbnail = decoded;
                    // The library and template placeholder are already visible;
                    // replace only this later frame instead of blocking the
                    // activity's first focus event on PNG decoding.
                    invalidate();
                });
            });
            return null;
        }

        private void drawTemplates(Canvas canvas, int ink,
                                   int quiet, int line) {
            text(canvas, getString(R.string.notebook_template_title),
                    150f, 59f, 33f, true, ink);
            text(canvas, getString(
                            R.string.notebook_template_summary,
                            TEMPLATE_IDS.length),
                    150f, 92f, 17f, false, quiet);
            newButton.setEmpty();

            float gap = 18f;
            float left = 24f;
            float right = getWidth() - 24f;
            float top = 126f;
            float bottom = getHeight() - 106f;
            float cardWidth = (right - left - gap) / 2f;
            float cardHeight = (bottom - top - gap * 4f) / 5f;
            int start = templatePageIndex * TEMPLATES_PER_PAGE;
            int visible = Math.min(
                    TEMPLATES_PER_PAGE, TEMPLATE_IDS.length - start);
            for (int slot = 0; slot < TEMPLATES_PER_PAGE; slot++) {
                int column = slot % 2;
                int row = slot / 2;
                float x = left + column * (cardWidth + gap);
                float y = top + row * (cardHeight + gap);
                RectF bounds = cardBounds[slot];
                bounds.set(x, y, x + cardWidth, y + cardHeight);
                if (slot >= visible) {
                    bounds.setEmpty();
                    continue;
                }
                int templateIndex = start + slot;
                box(canvas, bounds, Color.rgb(245, 245, 241),
                        Color.TRANSPARENT, 18f);
                RectF preview = new RectF(
                        x + 18f, y + 18f,
                        x + 126f, y + cardHeight - 18f);
                drawTemplatePreview(
                        canvas, TEMPLATE_IDS[templateIndex], preview, line);
                text(canvas, getString(
                                TEMPLATE_LABEL_RES_IDS[templateIndex]),
                        x + 150f, y + cardHeight * 0.52f,
                        23f, true, ink);
                text(canvas, "＋", x + cardWidth - 43f,
                        y + cardHeight - 22f, 19f, true, quiet);
            }

            int pageCount = Math.max(1,
                    (TEMPLATE_IDS.length + TEMPLATES_PER_PAGE - 1)
                            / TEMPLATES_PER_PAGE);
            previousButton.set(24f, getHeight() - 82f,
                    154f, getHeight() - 22f);
            nextButton.set(getWidth() - 272f, getHeight() - 82f,
                    getWidth() - 118f, getHeight() - 22f);
            box(canvas, previousButton, Color.rgb(242, 242, 238),
                    Color.TRANSPARENT, 18f);
            box(canvas, nextButton, Color.rgb(242, 242, 238),
                    Color.TRANSPARENT, 18f);
            centered(canvas, getString(R.string.notebook_previous),
                    previousButton,
                    18f, templatePageIndex > 0,
                    templatePageIndex > 0 ? ink : line);
            centered(canvas, getString(R.string.notebook_next), nextButton,
                    18f, templatePageIndex + 1 < pageCount,
                    templatePageIndex + 1 < pageCount ? ink : line);
            String page = (templatePageIndex + 1) + " / " + pageCount;
            paint.setTextSize(17f);
            text(canvas, page,
                    getWidth() * 0.5f - paint.measureText(page) * 0.5f,
                    getHeight() - 45f, 17f, false, quiet);
        }

        private void drawTemplatePreview(Canvas canvas, String template,
                                         RectF bounds, int line) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            canvas.drawRect(bounds, paint);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.5f);
            stroke.setColor(Color.rgb(145, 145, 139));
            canvas.drawRect(bounds, stroke);
            float left = bounds.left + 8f;
            float right = bounds.right - 8f;
            float top = bounds.top + 8f;
            float bottom = bounds.bottom - 8f;
            if ("dots".equals(template)) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.rgb(128, 128, 123));
                for (float y = top + 7f; y < bottom; y += 12f) {
                    for (float x = left + 7f; x < right; x += 12f) {
                        canvas.drawCircle(x, y, 1.8f, paint);
                    }
                }
            } else if ("grid".equals(template)
                    || "checklist".equals(template)) {
                for (float y = top + 12f; y < bottom; y += 14f) {
                    canvas.drawLine(left, y, right, y, stroke);
                }
                if ("grid".equals(template)) {
                    for (float x = left + 12f; x < right; x += 14f) {
                        canvas.drawLine(x, top, x, bottom, stroke);
                    }
                } else {
                    canvas.drawLine(left + 18f, top,
                            left + 18f, bottom, stroke);
                }
            } else if ("cornell".equals(template)) {
                canvas.drawLine(left + 27f, top, left + 27f,
                        bottom - 20f, stroke);
                canvas.drawLine(left, bottom - 20f, right,
                        bottom - 20f, stroke);
                drawPreviewRules(canvas, left, right, top, bottom, stroke);
            } else if ("daily".equals(template)) {
                canvas.drawLine(left, top + 17f, right, top + 17f, stroke);
                canvas.drawLine(left + 28f, top + 17f,
                        left + 28f, bottom, stroke);
                drawPreviewRules(canvas, left, right, top, bottom, stroke);
            } else if ("weekly".equals(template)) {
                canvas.drawLine(left, top + 16f, right, top + 16f, stroke);
                for (int column = 1; column < 4; column++) {
                    float x = left + (right - left) * column / 4f;
                    canvas.drawLine(x, top, x, bottom, stroke);
                }
            } else if ("storyboard".equals(template)) {
                float middle = (left + right) * 0.5f;
                float row = (bottom - top) / 3f;
                canvas.drawLine(middle, top, middle, bottom, stroke);
                canvas.drawLine(left, top + row, right, top + row, stroke);
                canvas.drawLine(left, top + row * 2f,
                        right, top + row * 2f, stroke);
            } else if ("music".equals(template)) {
                for (float staff = top + 8f; staff < bottom; staff += 31f) {
                    for (int lineIndex = 0; lineIndex < 5; lineIndex++) {
                        float y = staff + lineIndex * 3f;
                        canvas.drawLine(left, y, right, y, stroke);
                    }
                }
            } else if ("meeting".equals(template)) {
                canvas.drawLine(left, top + 18f, right, top + 18f, stroke);
                canvas.drawLine(left, top + 42f, right, top + 42f, stroke);
                canvas.drawLine(left, bottom - 32f,
                        right, bottom - 32f, stroke);
                drawPreviewRules(canvas, left, right,
                        top + 46f, bottom - 34f, stroke);
            } else if ("daily_todos".equals(template)) {
                for (float y = top + 13f; y < bottom; y += 16f) {
                    canvas.drawRect(left, y - 5f,
                            left + 7f, y + 2f, stroke);
                    canvas.drawLine(left + 13f, y + 2f,
                            right, y + 2f, stroke);
                }
            } else if ("focus".equals(template)) {
                canvas.drawLine(left, top + 20f, right, top + 20f, stroke);
                canvas.drawLine(left + 25f, top + 20f,
                        left + 25f, bottom, stroke);
                drawPreviewRules(canvas, left, right,
                        top + 22f, bottom, stroke);
            } else if ("eisenhower".equals(template)) {
                canvas.drawLine((left + right) * 0.5f, top,
                        (left + right) * 0.5f, bottom, stroke);
                canvas.drawLine(left, (top + bottom) * 0.5f,
                        right, (top + bottom) * 0.5f, stroke);
            } else if ("gratitude".equals(template)) {
                canvas.drawLine(left, top + 18f, right, top + 18f, stroke);
                canvas.drawLine(left, top + 52f, right, top + 52f, stroke);
                canvas.drawLine(left, top + 86f, right, top + 86f, stroke);
                canvas.drawLine(left, bottom - 28f,
                        right, bottom - 28f, stroke);
            } else if ("ruled".equals(template)) {
                drawPreviewRules(canvas, left, right, top, bottom, stroke);
            }
        }

        private void drawPreviewRules(Canvas canvas, float left, float right,
                                      float top, float bottom, Paint rule) {
            for (float y = top + 13f; y < bottom; y += 14f) {
                canvas.drawLine(left, y, right, y, rule);
            }
        }

        private String ellipsize(String value, float maximumWidth) {
            paint.setTextSize(23f);
            if (paint.measureText(value) <= maximumWidth) {
                return value;
            }
            String suffix = "…";
            int end = value.length();
            while (end > 1
                    && paint.measureText(
                    value.substring(0, end) + suffix) > maximumWidth) {
                end--;
            }
            return value.substring(0, end) + suffix;
        }

        private String formatUpdated(long updated) {
            if (updated <= 0L) {
                return getString(R.string.notebook_new_unmodified);
            }
            return new SimpleDateFormat(
                    getString(R.string.note_date_pattern),
                    PaperLocale.current(NotebookActivity.this))
                    .format(new Date(updated));
        }

        private String templateLabel(String template) {
            for (int index = 0; index < TEMPLATE_IDS.length; index++) {
                if (TEMPLATE_IDS[index].equals(template)) {
                    return getString(TEMPLATE_LABEL_RES_IDS[index]);
                }
            }
            return getString(TEMPLATE_LABEL_RES_IDS[0]);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_UP) {
                return true;
            }
            float x = event.getX();
            float y = event.getY();
            if (mode == MODE_LIBRARY && activeMenuIndex >= 0) {
                handleNoteMenuTouch(x, y);
                return true;
            }
            if (backButton.contains(x, y)) {
                if (mode == MODE_TEMPLATES) {
                    mode = MODE_LIBRARY;
                    invalidate();
                } else {
                    finish();
                    overridePendingTransition(0, 0);
                }
                return true;
            }
            if (mode == MODE_LIBRARY) {
                if (newButton.contains(x, y)) {
                    mode = MODE_TEMPLATES;
                    templatePageIndex = 0;
                    invalidate();
                    return true;
                }
                int start = pageIndex * NOTES_PER_PAGE;
                int visible = Math.min(
                        NOTES_PER_PAGE, notes.size() - start);
                for (int slot = 0; slot < visible; slot++) {
                    if (menuBounds[slot].contains(x, y)) {
                        activeMenuIndex = start + slot;
                        confirmDelete = false;
                        invalidate();
                        return true;
                    }
                    if (cardBounds[slot].contains(x, y)) {
                        openNote(notes.get(start + slot));
                        return true;
                    }
                }
                int pageCount = Math.max(1,
                        (notes.size() + NOTES_PER_PAGE - 1)
                                / NOTES_PER_PAGE);
                if (previousButton.contains(x, y) && pageIndex > 0) {
                    releaseThumbnails();
                    pageIndex--;
                    invalidate();
                    return true;
                }
                if (nextButton.contains(x, y)
                        && pageIndex + 1 < pageCount) {
                    releaseThumbnails();
                    pageIndex++;
                    invalidate();
                    return true;
                }
            } else {
                int start = templatePageIndex * TEMPLATES_PER_PAGE;
                int visible = Math.min(
                        TEMPLATES_PER_PAGE, TEMPLATE_IDS.length - start);
                for (int slot = 0; slot < visible; slot++) {
                    if (cardBounds[slot].contains(x, y)) {
                        createNote(TEMPLATE_IDS[start + slot]);
                        return true;
                    }
                }
                int pageCount = Math.max(1,
                        (TEMPLATE_IDS.length + TEMPLATES_PER_PAGE - 1)
                                / TEMPLATES_PER_PAGE);
                if (previousButton.contains(x, y)
                        && templatePageIndex > 0) {
                    templatePageIndex--;
                    invalidate();
                    return true;
                }
                if (nextButton.contains(x, y)
                        && templatePageIndex + 1 < pageCount) {
                    templatePageIndex++;
                    invalidate();
                    return true;
                }
            }
            return true;
        }

        private void handleNoteMenuTouch(float x, float y) {
            if (activeMenuIndex < 0
                    || activeMenuIndex >= notes.size()) {
                activeMenuIndex = -1;
                confirmDelete = false;
                invalidate();
                return;
            }
            NoteEntry note = notes.get(activeMenuIndex);
            if (menuOpenButton.contains(x, y)) {
                activeMenuIndex = -1;
                confirmDelete = false;
                openNote(note);
                return;
            }
            if (menuRenameButton.contains(x, y)) {
                showRenameDialog(note);
                return;
            }
            if (menuDeleteButton.contains(x, y)) {
                if ("quick-note".equals(note.id)) {
                    Toast.makeText(NotebookActivity.this,
                            getString(
                                    R.string.notebook_quick_note_delete_error),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!confirmDelete) {
                    confirmDelete = true;
                    invalidate();
                    return;
                }
                moveNoteToTrash(note);
                return;
            }
            if (menuCloseButton.contains(x, y)
                    || !menuPanel.contains(x, y)) {
                activeMenuIndex = -1;
                confirmDelete = false;
                invalidate();
            }
        }

        private void showRenameDialog(final NoteEntry note) {
            final EditText input = new EditText(NotebookActivity.this);
            input.setSingleLine(true);
            input.setText(note.title);
            input.setSelectAllOnFocus(true);
            AlertDialog dialog = new AlertDialog.Builder(
                    NotebookActivity.this)
                    .setTitle(getString(R.string.notebook_rename_title))
                    .setView(input)
                    .setNegativeButton(
                            getString(R.string.action_cancel), null)
                    .setPositiveButton(
                            getString(R.string.action_save),
                            (whichDialog, which) -> {
                        String title = input.getText().toString().trim();
                        if (title.length() == 0) {
                            return;
                        }
                        metadata.edit()
                                .putString(note.id + ".title", title)
                                .putLong(note.id + ".updated",
                                        System.currentTimeMillis())
                                .commit();
                        activeMenuIndex = -1;
                        confirmDelete = false;
                        reloadNotes();
                        invalidate();
                    })
                    .create();
            dialog.setOnDismissListener(which -> {
                enterFullscreen();
                invalidate();
            });
            dialog.getWindow();
            dialog.setOnShowListener(which -> {
                input.requestFocus();
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setSoftInputMode(
                            WindowManager.LayoutParams
                                    .SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                }
            });
            dialog.show();
        }

        private void moveNoteToTrash(NoteEntry note) {
            if (note.thumbnail != null) {
                note.thumbnail.recycle();
                note.thumbnail = null;
            }
            File trash = new File(getFilesDir(), "notes-trash");
            if (!trash.isDirectory() && !trash.mkdirs()) {
                Toast.makeText(NotebookActivity.this,
                        getString(R.string.notebook_move_failed),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            long trashStamp = System.currentTimeMillis();
            ArrayList<File> sources = new ArrayList<>();
            if (note.file != null) {
                if (note.file.isFile()) {
                    sources.add(note.file);
                }
                File parent = note.file.getParentFile();
                File baseVector = new File(parent,
                        note.file.getName().replaceAll(
                                "\\.png$", ".pnote"));
                if (baseVector.isFile()) {
                    sources.add(baseVector);
                }
                File[] pageFiles = parent.listFiles((directory, name) ->
                        name.startsWith(note.id + "-page-")
                                && (name.endsWith(".png")
                                    || name.endsWith(".pnote")));
                if (pageFiles != null) {
                    Collections.addAll(sources, pageFiles);
                }
            }
            ArrayList<File> destinations = new ArrayList<>();
            for (File source : sources) {
                File destination = new File(trash,
                        note.id + "-" + trashStamp + "-"
                                + source.getName());
                if (!source.renameTo(destination)) {
                    for (int index = destinations.size() - 1;
                         index >= 0; index--) {
                        destinations.get(index).renameTo(
                                sources.get(index));
                    }
                    Toast.makeText(NotebookActivity.this,
                            getString(R.string.notebook_move_failed),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                destinations.add(destination);
            }
            metadata.edit()
                    .remove(note.id + ".title")
                    .remove(note.id + ".template")
                    .remove(note.id + ".updated")
                    .remove(note.id + ".template-render-version")
                    .remove(note.id + ".page-count")
                    .remove(note.id + ".page-mode")
                    .commit();
            activeMenuIndex = -1;
            confirmDelete = false;
            reloadNotes();
            invalidate();
        }

        private void createNote(String template) {
            long now = System.currentTimeMillis();
            String id = "note-" + now;
            String date = new SimpleDateFormat(
                    getString(R.string.note_date_pattern),
                    PaperLocale.current(NotebookActivity.this))
                    .format(new Date(now));
            String title = templateLabel(template) + " · "
                    + date;
            metadata.edit()
                    .putString(id + ".title", title)
                    .putString(id + ".template", template)
                    .putLong(id + ".updated", now)
                    .apply();
            File notesDirectory = new File(getFilesDir(), "notes");
            openNote(new NoteEntry(
                    id, title, template, now,
                    new File(notesDirectory, id + ".png")));
        }

        private void openNote(NoteEntry note) {
            Intent intent = new Intent(
                    NotebookActivity.this, NoteActivity.class);
            intent.putExtra(EXTRA_NOTE_ID, note.id);
            intent.putExtra(EXTRA_NOTE_TITLE, note.title);
            intent.putExtra(EXTRA_NOTE_TEMPLATE, note.template);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            overridePendingTransition(0, 0);
        }
    }

    private static final class NoteEntry {
        final String id;
        final String title;
        final String template;
        final long updated;
        final File file;
        Bitmap thumbnail;
        boolean thumbnailLoading;

        NoteEntry(String id, String title, String template,
                  long updated, File file) {
            this.id = id;
            this.title = title;
            this.template = template;
            this.updated = updated;
            this.file = file;
        }
    }
}
