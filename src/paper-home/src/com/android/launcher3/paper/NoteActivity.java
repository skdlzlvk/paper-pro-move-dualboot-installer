package com.android.launcher3.paper;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.Window;

import com.android.launcher3.R;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

/**
 * Paper Home's low-distraction notebook.
 *
 * The app bitmap is the durable document while rm-epd-bridge draws the same
 * stroke immediately on the panel. Tool values are mirrored through tiny
 * control files so both renderers use identical widths and toolbar bounds.
 */
public final class NoteActivity extends Activity {
    private static final String TAG = "PaperNote";
    private static final int TEMPLATE_RENDER_VERSION = 4;
    private static final int VECTOR_MAGIC = 0x504e4f54;
    private static final int VECTOR_VERSION = 2;
    private static final String NOTE_ACTIVE_FILE = "paper-note-active";
    private static final String NOTE_TOOL_FILE = "paper-note-tool";
    private static final String NOTE_SIZE_FILE = "paper-note-size";
    private static final String NOTE_ERASER_SIZE_FILE =
            "paper-eraser-size";
    private static final String NOTE_UI_BOTTOM_FILE =
            "paper-note-ui-bottom";
    private static final String NOTE_OVERLAY_RESET_FILE =
            "paper-note-overlay-reset";
    private static final String NOTE_TOOLBAR_REFRESH_FILE =
            "paper-note-toolbar-refresh";
    private static final String REFRESH_REQUEST_FILE =
            "paper-refresh-request";
    private NoteView noteView;
    private boolean activityPaused;
    private String noteId;
    private String noteTitle;
    private String noteTemplate;
    private File noteFile;
    private File noteVectorFile;
    private SharedPreferences noteMetadata;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        configureDocument();
        noteView = new NoteView();
        setContentView(noteView);
        noteView.syncNativeTool();
        enterFullscreen();
    }

    private void configureDocument() {
        noteId = getIntent().getStringExtra(NotebookActivity.EXTRA_NOTE_ID);
        noteTitle = getIntent().getStringExtra(
                NotebookActivity.EXTRA_NOTE_TITLE);
        noteTemplate = getIntent().getStringExtra(
                NotebookActivity.EXTRA_NOTE_TEMPLATE);
        if (noteId == null || noteId.length() == 0) {
            noteId = "quick-note";
        }
        if (noteTitle == null || noteTitle.length() == 0) {
            noteTitle = getString(R.string.quick_note);
        }
        if (noteTemplate == null || noteTemplate.length() == 0) {
            noteTemplate = "blank";
        }
        noteMetadata = getSharedPreferences("paper-notes", MODE_PRIVATE);
        if ("quick-note".equals(noteId)) {
            noteFile = new File(getFilesDir(), "quick-note.png");
        } else {
            String safeId = noteId.replaceAll("[^a-zA-Z0-9_-]", "_");
            File notes = new File(getFilesDir(), "notes");
            if (!notes.isDirectory()) {
                notes.mkdirs();
            }
            noteFile = new File(notes, safeId + ".png");
            noteMetadata.edit()
                    .putString(noteId + ".title", noteTitle)
                    .putString(noteId + ".template", noteTemplate)
                    .putLong(noteId + ".updated",
                            System.currentTimeMillis())
                    .apply();
        }
        String vectorName = noteFile.getName().replaceAll(
                "\\.png$", ".pnote");
        noteVectorFile = new File(noteFile.getParentFile(), vectorName);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityPaused = false;
        if (noteView != null) {
            noteView.syncNativeTool();
        }
        writeNativePenControl(NOTE_ACTIVE_FILE, "active");
    }

    @Override
    protected void onPause() {
        activityPaused = true;
        new File(getFilesDir(), NOTE_ACTIVE_FILE).delete();
        if (noteView != null) {
            noteView.saveAsync();
        }
        super.onPause();
    }

    private void writeNativePenControl(String name, String value) {
        try (FileOutputStream output =
                     new FileOutputStream(new File(getFilesDir(), name),
                             false)) {
            output.write(value.getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII));
            output.flush();
        } catch (Exception ignored) {
            // Android drawing remains usable if the host bridge is absent.
        }
    }

    private void requestNativeOverlayReset() {
        writeNativePenControl(NOTE_OVERLAY_RESET_FILE, "reset");
        writeNativePenControl(REFRESH_REQUEST_FILE, "refresh");
    }

    private void requestToolbarRefresh() {
        writeNativePenControl(NOTE_TOOLBAR_REFRESH_FILE, "236");
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

    private final class NoteView extends View {
        private static final float BAR_BOTTOM = 88f;
        private static final float PANEL_BOTTOM = 232f;
        private static final float PEN_SCALE_MIN = 0.45f;
        private static final float PEN_SCALE_MAX = 2.20f;
        private static final float ERASER_WIDTH_MIN = 12f;
        private static final float ERASER_WIDTH_MAX = 96f;
        private static final long IDLE_SAVE_DELAY_MS = 6000L;
        private static final long PAUSE_SAVE_DELAY_MS = 250L;
        private static final long BUSY_SAVE_RETRY_MS = 1500L;
        private static final long TOOLBAR_RENDER_DELAY_MS = 110L;
        private static final String BALLPOINT = "ballpoint";
        private static final String FINELINER = "fineliner";
        private static final String PENCIL = "pencil";
        private static final String MARKER = "marker";
        private static final String BRUSH = "brush";
        private static final int PAGE_COLOR = 0xfffafaf7;

        private final String[] penTools = {
                BALLPOINT, FINELINER, PENCIL, MARKER, BRUSH
        };
        private final String[] penLabels;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path markerPath = new Path();
        private final RectF backButton = new RectF();
        private final RectF undoButton = new RectF();
        private final RectF redoButton = new RectF();
        private final RectF penButton = new RectF();
        private final RectF eraserButton = new RectF();
        private final RectF clearButton = new RectF();
        private final RectF sliderBounds = new RectF();
        private final RectF[] penToolButtons = {
                new RectF(), new RectF(), new RectF(),
                new RectF(), new RectF()
        };
        private final RectF strokeDirty = new RectF();
        private final ArrayList<NoteCommand> commands = new ArrayList<>();
        private final ArrayList<NoteCommand> redoCommands =
                new ArrayList<>();
        private final SharedPreferences toolPreferences;
        private final Handler saveHandler =
                new Handler(Looper.getMainLooper());
        private final Object saveLock = new Object();
        private final Runnable deferredSave = this::saveIfIdle;
        private final Runnable deferredToolbarRender =
                this::renderPendingToolbar;

        private Bitmap page;
        private Bitmap basePage;
        private Bitmap pendingSave;
        private byte[] pendingVectorSave;
        private long pendingSaveGeneration;
        private long documentGeneration;
        private Canvas pageCanvas;
        private StrokeCommand currentStroke;
        private boolean drawing;
        private boolean stylusInRange;
        private boolean strokeDirtySet;
        private boolean erasing;
        private boolean panelExpanded;
        private boolean sliderDragging;
        private boolean confirmClear;
        private boolean documentDirty;
        private boolean saveWorkerRunning;
        private boolean vectorDocument;
        private int pendingToolbarTop = Integer.MAX_VALUE;
        private int pendingToolbarBottom;
        private String selectedPenTool;
        private float selectedPenScale;
        private float selectedEraserWidth;

        NoteView() {
            super(NoteActivity.this);
            penLabels = getResources().getStringArray(R.array.pen_labels);
            setBackgroundColor(Color.WHITE);
            Bitmap transparentCursor =
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            transparentCursor.eraseColor(Color.TRANSPARENT);
            setPointerIcon(PointerIcon.create(transparentCursor, 0f, 0f));
            ink.setStyle(Paint.Style.STROKE);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);

            toolPreferences = getSharedPreferences(
                    "paper-note-tools", MODE_PRIVATE);
            selectedPenTool = toolPreferences.getString(
                    "pen-tool", BALLPOINT);
            if (!isKnownPenTool(selectedPenTool)) {
                selectedPenTool = BALLPOINT;
            }
            selectedPenScale = clamp(
                    toolPreferences.getFloat("pen-scale", 1f),
                    PEN_SCALE_MIN, PEN_SCALE_MAX);
            selectedEraserWidth = clamp(
                    toolPreferences.getFloat("eraser-width", 40f),
                    ERASER_WIDTH_MIN, ERASER_WIDTH_MAX);
            erasing = toolPreferences.getBoolean("eraser-active", false);
        }

        @Override
        protected void onSizeChanged(int width, int height,
                                     int oldWidth, int oldHeight) {
            Bitmap loaded = BitmapFactory.decodeFile(noteFile.getPath());
            page = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            pageCanvas = new Canvas(page);
            pageCanvas.drawColor(PAGE_COLOR);
            boolean templateUpgraded = false;
            commands.clear();
            redoCommands.clear();
            vectorDocument = loadVectorCommands(width, height);
            if (vectorDocument) {
                drawTemplate(pageCanvas, noteTemplate, width, height);
                if (basePage != null) {
                    basePage.recycle();
                }
                basePage = page.copy(Bitmap.Config.RGB_565, false);
                for (NoteCommand command : commands) {
                    command.draw(pageCanvas);
                }
                if (loaded != null) {
                    loaded.recycle();
                    loaded = null;
                }
            } else if (loaded != null) {
                pageCanvas.drawBitmap(loaded, 0f, 0f, null);
                templateUpgraded = upgradeLegacyTemplatePixels(page);
                loaded.recycle();
                if (basePage != null) {
                    basePage.recycle();
                }
                // Legacy notes remain a stable raster base. New strokes are
                // still fast and undoable for this session; newly-created
                // notes use the durable vector sidecar below.
                basePage = page.copy(Bitmap.Config.RGB_565, false);
            } else {
                drawTemplate(pageCanvas, noteTemplate, width, height);
                if (basePage != null) {
                    basePage.recycle();
                }
                basePage = page.copy(Bitmap.Config.RGB_565, false);
                vectorDocument = true;
            }
            noteMetadata.edit()
                    .putInt(noteId + ".template-render-version",
                            TEMPLATE_RENDER_VERSION)
                    .apply();
            documentGeneration = 0L;
            documentDirty = !noteFile.isFile() || templateUpgraded;
            if (documentDirty) {
                documentGeneration = 1L;
                scheduleAutoSave(1200L);
            }
        }

        /**
         * Early Paper Home builds saved the ruled template into the page with
         * an almost-white RGB565 color. Replace only that exact legacy pixel
         * value, leaving handwriting and the page background untouched.
         */
        private boolean upgradeLegacyTemplatePixels(Bitmap bitmap) {
            if (!"ruled".equals(noteTemplate)
                    || noteMetadata.getInt(
                    noteId + ".template-render-version", 0)
                    >= TEMPLATE_RENDER_VERSION) {
                return false;
            }
            final int upgraded = Color.rgb(132, 133, 126);
            boolean changed = false;
            int[] row = new int[bitmap.getWidth()];
            for (int y = 0; y < bitmap.getHeight(); y++) {
                bitmap.getPixels(row, 0, row.length,
                        0, y, row.length, 1);
                boolean rowChanged = false;
                for (int x = 0; x < row.length; x++) {
                    int pixel = row[x];
                    int red = Color.red(pixel);
                    int green = Color.green(pixel);
                    int blue = Color.blue(pixel);
                    boolean nearlyWhiteLegacy =
                            red >= 222 && red <= 238
                            && green >= 222 && green <= 240
                            && blue >= 214 && blue <= 232
                            && Math.abs(red - green) <= 3
                            && green - blue >= 7
                            && green - blue <= 14;
                    boolean interimGray =
                            red >= 190 && red <= 205
                            && green >= 190 && green <= 205
                            && blue >= 182 && blue <= 198
                            && Math.abs(red - green) <= 3
                            && green - blue >= 6
                            && green - blue <= 13;
                    if (nearlyWhiteLegacy || interimGray) {
                        row[x] = upgraded;
                        rowChanged = true;
                    }
                }
                if (rowChanged) {
                    bitmap.setPixels(row, 0, row.length,
                            0, y, row.length, 1);
                    changed = true;
                }
            }
            return changed;
        }

        private float toolbarBottom() {
            return panelExpanded ? PANEL_BOTTOM : BAR_BOTTOM;
        }

        private String toolbarTitle() {
            ui.setTextSize(25f);
            ui.setFakeBoldText(true);
            final float maximumWidth = 122f;
            if (ui.measureText(noteTitle) <= maximumWidth) {
                return noteTitle;
            }
            int end = noteTitle.length();
            while (end > 1 && ui.measureText(
                    noteTitle.substring(0, end) + "…") > maximumWidth) {
                end--;
            }
            return noteTitle.substring(0, end) + "…";
        }

        private void drawTemplate(Canvas target, String template,
                                  int width, int height) {
            Paint rule = new Paint(Paint.ANTI_ALIAS_FLAG);
            rule.setStyle(Paint.Style.STROKE);
            rule.setStrokeWidth(2f);
            rule.setColor(Color.rgb(128, 128, 124));
            Paint strong = new Paint(Paint.ANTI_ALIAS_FLAG);
            strong.setStyle(Paint.Style.STROKE);
            strong.setStrokeWidth(2.3f);
            strong.setColor(Color.rgb(92, 92, 88));
            Paint templateText = new Paint(Paint.ANTI_ALIAS_FLAG);
            templateText.setStyle(Paint.Style.FILL);
            templateText.setColor(Color.rgb(118, 118, 112));
            templateText.setTextSize(17f);
            final float left = 40f;
            final float right = width - 40f;
            final float top = BAR_BOTTOM + 38f;
            final float bottom = height - 34f;

            if ("ruled".equals(template)) {
                drawHorizontalRules(target, left, right,
                        top, bottom, 54f, rule);
            } else if ("grid".equals(template)) {
                drawHorizontalRules(target, left, right,
                        top, bottom, 42f, rule);
                for (float x = left; x <= right; x += 42f) {
                    target.drawLine(x, top, x, bottom, rule);
                }
            } else if ("dots".equals(template)) {
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setStyle(Paint.Style.FILL);
                dot.setColor(Color.rgb(145, 145, 140));
                for (float y = top; y <= bottom; y += 42f) {
                    for (float x = left; x <= right; x += 42f) {
                        target.drawCircle(x, y, 2.4f, dot);
                    }
                }
            } else if ("checklist".equals(template)) {
                for (float y = top; y < bottom - 42f; y += 62f) {
                    RectF checkbox = new RectF(
                            left, y + 12f, left + 24f, y + 36f);
                    target.drawRoundRect(checkbox, 3f, 3f, strong);
                    target.drawLine(left + 42f, y + 48f,
                            right, y + 48f, rule);
                }
            } else if ("cornell".equals(template)) {
                float cueX = left + 235f;
                float summaryY = bottom - 220f;
                target.drawLine(cueX, top, cueX, summaryY, strong);
                target.drawLine(left, summaryY, right, summaryY, strong);
                target.drawText(getString(R.string.note_template_keyword),
                        left + 8f,
                        top + 25f, templateText);
                target.drawText(getString(R.string.note_template_notes),
                        cueX + 16f,
                        top + 25f, templateText);
                target.drawText(getString(R.string.note_template_summary),
                        left + 8f,
                        summaryY + 30f, templateText);
                drawHorizontalRules(target, cueX + 16f, right,
                        top + 40f, summaryY - 18f, 52f, rule);
                drawHorizontalRules(target, left, right,
                        summaryY + 48f, bottom, 52f, rule);
            } else if ("daily".equals(template)) {
                float timeX = left + 94f;
                target.drawText(getString(R.string.note_template_date),
                        left, top + 27f, templateText);
                target.drawLine(left + 50f, top + 32f,
                        right, top + 32f, strong);
                float scheduleTop = top + 78f;
                target.drawLine(timeX, scheduleTop,
                        timeX, bottom, strong);
                int hour = 7;
                for (float y = scheduleTop;
                     y < bottom - 44f; y += 70f) {
                    target.drawText(String.format(
                                    PaperLocale.current(NoteActivity.this),
                                    "%02d:00", hour),
                            left + 7f, y + 42f, templateText);
                    target.drawLine(left, y + 58f,
                            right, y + 58f, rule);
                    hour++;
                }
            } else if ("weekly".equals(template)) {
                float headerBottom = top + 56f;
                String[] days = getResources().getStringArray(
                        R.array.weekdays_short);
                float columnWidth = (right - left) / 7f;
                target.drawLine(left, headerBottom,
                        right, headerBottom, strong);
                for (int index = 0; index < days.length; index++) {
                    float x = left + index * columnWidth;
                    if (index > 0) {
                        target.drawLine(x, top, x, bottom, strong);
                    }
                    target.drawText(days[index],
                            x + columnWidth * 0.43f,
                            top + 35f, templateText);
                }
                drawHorizontalRules(target, left, right,
                        headerBottom + 54f, bottom, 78f, rule);
            } else if ("storyboard".equals(template)) {
                float gap = 28f;
                float boxWidth = (right - left - gap) * 0.5f;
                float boxHeight = (bottom - top - gap * 2f) / 3f;
                for (int row = 0; row < 3; row++) {
                    for (int column = 0; column < 2; column++) {
                        float x = left + column * (boxWidth + gap);
                        float y = top + row * (boxHeight + gap);
                        target.drawRect(x, y,
                                x + boxWidth, y + boxHeight, strong);
                    }
                }
            } else if ("music".equals(template)) {
                for (float staff = top + 20f;
                     staff < bottom - 76f; staff += 150f) {
                    for (int lineIndex = 0; lineIndex < 5; lineIndex++) {
                        float y = staff + lineIndex * 14f;
                        target.drawLine(left, y, right, y, rule);
                    }
                }
            } else if ("meeting".equals(template)) {
                templateText.setTextSize(25f);
                target.drawText(getString(R.string.note_template_meeting),
                        left, top + 30f, templateText);
                templateText.setTextSize(16f);
                target.drawText(getString(R.string.note_template_date),
                        left, top + 78f, templateText);
                target.drawLine(left + 52f, top + 82f,
                        left + 330f, top + 82f, rule);
                target.drawText(getString(R.string.note_template_attendees),
                        left + 370f,
                        top + 78f, templateText);
                target.drawLine(left + 430f, top + 82f,
                        right, top + 82f, rule);
                float agendaBottom = top + 330f;
                templateText.setTextSize(18f);
                target.drawText(getString(R.string.note_template_agenda),
                        left, top + 128f, templateText);
                drawHorizontalRules(target, left, right,
                        top + 166f, agendaBottom, 48f, rule);
                target.drawLine(left, agendaBottom + 34f,
                        right, agendaBottom + 34f, strong);
                target.drawText(getString(
                                R.string.note_template_meeting_body),
                        left,
                        agendaBottom + 76f, templateText);
                float actionsTop = bottom - 330f;
                drawHorizontalRules(target, left, right,
                        agendaBottom + 116f, actionsTop - 24f, 52f, rule);
                target.drawLine(left, actionsTop,
                        right, actionsTop, strong);
                target.drawText(getString(
                                R.string.note_template_decisions),
                        left,
                        actionsTop + 42f, templateText);
                drawHorizontalRules(target, left, right,
                        actionsTop + 84f, bottom, 52f, rule);
            } else if ("daily_todos".equals(template)) {
                templateText.setTextSize(25f);
                target.drawText(getString(
                                R.string.note_template_today_tasks),
                        left,
                        top + 30f, templateText);
                templateText.setTextSize(16f);
                target.drawText(getString(R.string.note_template_date),
                        right - 190f,
                        top + 30f, templateText);
                target.drawLine(right - 140f, top + 34f,
                        right, top + 34f, rule);
                templateText.setTextSize(18f);
                target.drawText(getString(
                                R.string.note_template_top_three),
                        left,
                        top + 92f, templateText);
                float y = top + 128f;
                for (int index = 0; index < 3; index++, y += 64f) {
                    target.drawRoundRect(
                            new RectF(left, y, left + 24f, y + 24f),
                            3f, 3f, strong);
                    target.drawLine(left + 42f, y + 27f,
                            right, y + 27f, rule);
                }
                target.drawLine(left, y + 12f,
                        right, y + 12f, strong);
                target.drawText(getString(R.string.note_template_tasks),
                        left,
                        y + 58f, templateText);
                y += 82f;
                while (y < bottom - 40f) {
                    target.drawRoundRect(
                            new RectF(left, y, left + 22f, y + 22f),
                            3f, 3f, strong);
                    target.drawLine(left + 40f, y + 25f,
                            right, y + 25f, rule);
                    y += 58f;
                }
            } else if ("focus".equals(template)) {
                templateText.setTextSize(25f);
                target.drawText(getString(R.string.note_template_focus_log),
                        left,
                        top + 30f, templateText);
                templateText.setTextSize(16f);
                target.drawText(getString(
                                R.string.note_template_today_goal),
                        left,
                        top + 78f, templateText);
                target.drawLine(left + 92f, top + 82f,
                        right, top + 82f, rule);
                float tableTop = top + 132f;
                float timeX = left + 126f;
                float resultX = right - 170f;
                target.drawLine(left, tableTop,
                        right, tableTop, strong);
                target.drawLine(timeX, tableTop,
                        timeX, bottom, strong);
                target.drawLine(resultX, tableTop,
                        resultX, bottom, strong);
                templateText.setTextSize(15f);
                target.drawText(getString(R.string.note_template_time),
                        left + 35f,
                        tableTop + 32f, templateText);
                target.drawText(getString(
                                R.string.note_template_focus_task),
                        timeX + 18f,
                        tableTop + 32f, templateText);
                target.drawText(getString(R.string.note_template_done),
                        resultX + 52f,
                        tableTop + 32f, templateText);
                for (float row = tableTop + 52f;
                     row <= bottom; row += 116f) {
                    target.drawLine(left, row, right, row, rule);
                }
            } else if ("eisenhower".equals(template)) {
                templateText.setTextSize(25f);
                target.drawText(getString(
                                R.string.note_template_eisenhower),
                        left,
                        top + 30f, templateText);
                float matrixTop = top + 82f;
                float middleX = (left + right) * 0.5f;
                float middleY = (matrixTop + bottom) * 0.5f;
                target.drawRect(left, matrixTop, right, bottom, strong);
                target.drawLine(middleX, matrixTop,
                        middleX, bottom, strong);
                target.drawLine(left, middleY,
                        right, middleY, strong);
                templateText.setTextSize(17f);
                target.drawText(getString(
                                R.string.note_template_urgent_important),
                        left + 18f,
                        matrixTop + 34f, templateText);
                target.drawText(getString(
                                R.string.note_template_important_plan),
                        middleX + 18f,
                        matrixTop + 34f, templateText);
                target.drawText(getString(
                                R.string.note_template_urgent_delegate),
                        left + 18f,
                        middleY + 34f, templateText);
                target.drawText(getString(
                                R.string.note_template_later_eliminate),
                        middleX + 18f,
                        middleY + 34f, templateText);
            } else if ("gratitude".equals(template)) {
                templateText.setTextSize(25f);
                target.drawText(getString(
                                R.string.note_template_gratitude),
                        left,
                        top + 30f, templateText);
                templateText.setTextSize(16f);
                target.drawText(getString(R.string.note_template_date),
                        right - 180f,
                        top + 30f, templateText);
                target.drawLine(right - 130f, top + 34f,
                        right, top + 34f, rule);
                String[] prompts = getResources().getStringArray(
                        R.array.gratitude_prompts);
                float sectionTop = top + 90f;
                float sectionHeight = (bottom - sectionTop) / 3f;
                templateText.setTextSize(18f);
                for (int index = 0; index < prompts.length; index++) {
                    float y = sectionTop + index * sectionHeight;
                    if (index > 0) {
                        target.drawLine(left, y,
                                right, y, strong);
                    }
                    target.drawText(prompts[index], left,
                            y + 38f, templateText);
                    drawHorizontalRules(target, left, right,
                            y + 78f, y + sectionHeight - 24f,
                            54f, rule);
                }
            }
        }

        private void drawHorizontalRules(Canvas target,
                                         float left, float right,
                                         float top, float bottom,
                                         float spacing, Paint rule) {
            for (float y = top; y <= bottom; y += spacing) {
                target.drawLine(left, y, right, y, rule);
            }
        }

        private void label(Canvas canvas, String value,
                           float x, float y, float size, boolean bold) {
            ui.setColor(Color.rgb(40, 40, 38));
            ui.setTextSize(size);
            ui.setFakeBoldText(bold);
            ui.setStyle(Paint.Style.FILL);
            canvas.drawText(value, x, y, ui);
        }

        private void centeredLabel(Canvas canvas, String value,
                                   RectF bounds, float size,
                                   boolean bold) {
            ui.setTextSize(size);
            ui.setFakeBoldText(bold);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(Color.rgb(40, 40, 38));
            float x = bounds.centerX() - ui.measureText(value) * 0.5f;
            float y = bounds.centerY()
                    - (ui.ascent() + ui.descent()) * 0.5f;
            canvas.drawText(value, x, y, ui);
        }

        private void roundedButton(Canvas canvas, RectF bounds,
                                   boolean selected, boolean enabled) {
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(!enabled
                    ? Color.rgb(249, 249, 246)
                    : (selected ? Color.rgb(216, 216, 210)
                                : Color.rgb(241, 241, 237)));
            canvas.drawRoundRect(bounds, 17f, 17f, ui);
            if (selected) {
                ui.setColor(Color.rgb(42, 42, 40));
                canvas.drawRoundRect(
                        bounds.left + 18f, bounds.bottom - 5f,
                        bounds.right - 18f, bounds.bottom - 2f,
                        1.5f, 1.5f, ui);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (page != null) {
                canvas.drawBitmap(page, 0f, 0f, null);
            } else {
                canvas.drawColor(Color.WHITE);
            }
            drawToolbar(canvas);
        }

        private void drawToolbar(Canvas canvas) {
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(Color.rgb(252, 252, 249));
            canvas.drawRect(0f, 0f, getWidth(), toolbarBottom(), ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1.4f);
            ui.setColor(Color.rgb(184, 182, 175));
            canvas.drawLine(0f, toolbarBottom(), getWidth(),
                    toolbarBottom(), ui);

            backButton.set(16f, 13f, 108f, 74f);
            undoButton.set(354f, 13f, 438f, 74f);
            redoButton.set(448f, 13f, 532f, 74f);
            penButton.set(getWidth() - 290f, 13f,
                    getWidth() - 156f, 74f);
            eraserButton.set(getWidth() - 146f, 13f,
                    getWidth() - 16f, 74f);

            roundedButton(canvas, backButton, false, true);
            roundedButton(canvas, undoButton, false, canUndo());
            roundedButton(canvas, redoButton, false, canRedo());
            roundedButton(canvas, penButton, !erasing, true);
            roundedButton(canvas, eraserButton, erasing, true);
            centeredLabel(canvas, getString(R.string.note_back),
                    backButton, 20f, true);
            label(canvas, toolbarTitle(), 126f, 55f, 25f, true);
            /*
             * Do not repaint the toolbar when marker proximity changes.
             * A Mono toolbar update on every HOVER_EXIT/HOVER_ENTER visibly
             * flashed the E-ink panel and blocked the first point of the next
             * stroke. Proximity remains internal autosave state.
             */
            label(canvas, getString(R.string.note_autosave),
                    265f, 53f, 14f, false);
            centeredLabel(canvas, "↶", undoButton, 30f, canUndo());
            centeredLabel(canvas, "↷", redoButton, 30f, canRedo());
            centeredLabel(canvas, getString(R.string.note_pen),
                    penButton, 20f, true);
            centeredLabel(canvas, getString(R.string.note_eraser),
                    eraserButton, 19f, true);

            if (!panelExpanded) {
                return;
            }
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1f);
            ui.setColor(Color.rgb(218, 216, 209));
            canvas.drawLine(16f, BAR_BOTTOM, getWidth() - 16f,
                    BAR_BOTTOM, ui);
            if (erasing) {
                drawEraserPanel(canvas);
            } else {
                drawPenPanel(canvas);
            }
        }

        private void drawPenPanel(Canvas canvas) {
            final float left = 18f;
            final float top = 99f;
            final float width = 108f;
            final float gap = 8f;
            for (int index = 0; index < penToolButtons.length; index++) {
                float x = left + index * (width + gap);
                RectF bounds = penToolButtons[index];
                bounds.set(x, top, x + width, 158f);
                boolean selected =
                        selectedPenTool.equals(penTools[index]);
                roundedButton(canvas, bounds, selected, true);
                centeredLabel(canvas, penLabels[index], bounds,
                        penLabels[index].length() > 4 ? 14f : 17f,
                        selected);
            }

            label(canvas, getString(R.string.note_width),
                    30f, 205f, 18f, true);
            sliderBounds.set(102f, 169f, 790f, 226f);
            drawSlider(canvas, selectedPenScale,
                    PEN_SCALE_MIN, PEN_SCALE_MAX);
            float previewWidth = penWidth(
                    selectedPenTool, 0.55f, selectedPenScale);
            drawWidthPreview(canvas, 860f, 197f, previewWidth);
            label(canvas,
                    Math.round(selectedPenScale * 100f) + "%",
                    895f, 204f, 15f, false);
        }

        private void drawEraserPanel(Canvas canvas) {
            label(canvas, getString(R.string.note_eraser_size),
                    30f, 128f, 20f, true);
            sliderBounds.set(34f, 143f, 640f, 216f);
            drawSlider(canvas, selectedEraserWidth,
                    ERASER_WIDTH_MIN, ERASER_WIDTH_MAX);
            drawWidthPreview(canvas, 670f, 180f,
                    Math.min(28f, selectedEraserWidth * 0.30f));
            label(canvas, Math.round(selectedEraserWidth) + " px",
                    700f, 186f, 16f, false);

            clearButton.set(790f, 112f, getWidth() - 18f, 206f);
            roundedButton(canvas, clearButton, confirmClear, true);
            centeredLabel(canvas,
                    getString(confirmClear
                            ? R.string.note_clear_confirm
                            : R.string.note_clear_all),
                    clearButton, confirmClear ? 14f : 17f, true);
        }

        private void drawSlider(Canvas canvas, float value,
                                float minimum, float maximum) {
            float ratio = (value - minimum) / (maximum - minimum);
            ratio = clamp(ratio, 0f, 1f);
            float trackLeft = sliderBounds.left + 18f;
            float trackRight = sliderBounds.right - 18f;
            float trackY = sliderBounds.centerY();
            float thumbX = trackLeft + ratio * (trackRight - trackLeft);

            ui.setStrokeCap(Paint.Cap.ROUND);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(6f);
            ui.setColor(Color.rgb(214, 213, 207));
            canvas.drawLine(trackLeft, trackY, trackRight, trackY, ui);
            ui.setColor(Color.rgb(54, 54, 51));
            canvas.drawLine(trackLeft, trackY, thumbX, trackY, ui);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(Color.WHITE);
            canvas.drawCircle(thumbX, trackY, 17f, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(2.2f);
            ui.setColor(Color.rgb(54, 54, 51));
            canvas.drawCircle(thumbX, trackY, 17f, ui);
        }

        private void drawWidthPreview(Canvas canvas, float x, float y,
                                      float width) {
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(Color.rgb(42, 42, 39));
            canvas.drawCircle(x, y,
                    Math.max(1.5f, Math.min(15f, width * 0.5f)), ui);
        }

        private void scheduleToolbarRender(int top, int bottom) {
            pendingToolbarTop = Math.min(pendingToolbarTop, top);
            pendingToolbarBottom = Math.max(pendingToolbarBottom, bottom);
            saveHandler.removeCallbacks(deferredToolbarRender);
            saveHandler.postDelayed(
                    deferredToolbarRender, TOOLBAR_RENDER_DELAY_MS);
        }

        private void renderPendingToolbar() {
            if (pendingToolbarTop == Integer.MAX_VALUE) {
                return;
            }
            int top = Math.max(0, pendingToolbarTop);
            int bottom = Math.min(getHeight(),
                    Math.max(top + 1, pendingToolbarBottom));
            pendingToolbarTop = Integer.MAX_VALUE;
            pendingToolbarBottom = 0;
            invalidate(0, top, getWidth(), bottom);
            requestToolbarRefresh();
        }

        @Override
        public boolean onHoverEvent(MotionEvent event) {
            if (!isStylusEvent(event)) {
                return super.onHoverEvent(event);
            }
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                case MotionEvent.ACTION_HOVER_MOVE:
                    stylusInRange = true;
                    cancelPendingAutoSave();
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    stylusInRange = false;
                    if (documentDirty) {
                        scheduleAutoSave();
                    }
                    break;
                default:
                    break;
            }
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            final float x = event.getX();
            final float y = event.getY();
            final int action = event.getActionMasked();

            if (panelExpanded && (sliderDragging
                    || sliderBounds.contains(x, y))) {
                if (action == MotionEvent.ACTION_DOWN) {
                    sliderDragging = true;
                    updateSlider(x);
                    return true;
                }
                if (sliderDragging && action == MotionEvent.ACTION_MOVE) {
                    updateSlider(x);
                    return true;
                }
                if (sliderDragging && (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL)) {
                    updateSlider(x);
                    sliderDragging = false;
                    persistToolPreferences();
                    scheduleToolbarRender(
                            (int) BAR_BOTTOM, (int) PANEL_BOTTOM + 2);
                    return true;
                }
            }

            if (action == MotionEvent.ACTION_UP
                    && backButton.contains(x, y)) {
                saveAsync();
                finish();
                overridePendingTransition(0, 0);
                return true;
            }
            if (action == MotionEvent.ACTION_UP
                    && undoButton.contains(x, y)) {
                undo();
                return true;
            }
            if (action == MotionEvent.ACTION_UP
                    && redoButton.contains(x, y)) {
                redo();
                return true;
            }
            if (action == MotionEvent.ACTION_UP
                    && penButton.contains(x, y)) {
                if (!erasing) {
                    panelExpanded = !panelExpanded;
                } else {
                    erasing = false;
                    panelExpanded = true;
                }
                confirmClear = false;
                persistToolPreferences();
                syncNativeTool();
                scheduleToolbarRender(0, (int) PANEL_BOTTOM + 2);
                return true;
            }
            if (action == MotionEvent.ACTION_UP
                    && eraserButton.contains(x, y)) {
                if (erasing) {
                    panelExpanded = !panelExpanded;
                } else {
                    erasing = true;
                    panelExpanded = true;
                }
                confirmClear = false;
                persistToolPreferences();
                syncNativeTool();
                scheduleToolbarRender(0, (int) PANEL_BOTTOM + 2);
                return true;
            }

            if (panelExpanded && action == MotionEvent.ACTION_UP) {
                if (!erasing) {
                    for (int index = 0;
                         index < penToolButtons.length; index++) {
                        if (penToolButtons[index].contains(x, y)) {
                            selectedPenTool = penTools[index];
                            persistToolPreferences();
                            syncNativeTool();
                            scheduleToolbarRender(
                                    (int) BAR_BOTTOM,
                                    (int) PANEL_BOTTOM + 2);
                            return true;
                        }
                    }
                } else if (clearButton.contains(x, y)) {
                    if (confirmClear) {
                        clearPage();
                        confirmClear = false;
                    } else {
                        confirmClear = true;
                        scheduleToolbarRender(
                                (int) BAR_BOTTOM,
                                (int) PANEL_BOTTOM + 2);
                    }
                    return true;
                }
            }

            if (y < toolbarBottom() + 4f || pageCanvas == null) {
                return true;
            }
            if (!isStylusEvent(event)) {
                if (action == MotionEvent.ACTION_CANCEL
                        || action == MotionEvent.ACTION_UP) {
                    drawing = false;
                    currentStroke = null;
                }
                return true;
            }

            stylusInRange = true;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    cancelPendingAutoSave();
                    requestUnbufferedDispatch(event);
                    beginNewStroke(event);
                    appendPointAndDraw(
                            x, y,
                            pressureOf(event, 0),
                            orientationOf(event, 0),
                            tiltOf(event, 0));
                    flushStrokeDirty();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    cancelPendingAutoSave();
                    requestUnbufferedDispatch(event);
                    if (!drawing || currentStroke == null) {
                        beginNewStroke(event);
                    }
                    beginStrokeDirty();
                    for (int index = 0; index < event.getHistorySize();
                         index++) {
                        appendPointAndDraw(
                                event.getHistoricalX(0, index),
                                event.getHistoricalY(0, index),
                                pressureOf(event, index + 1),
                                orientationOf(event, index + 1),
                                tiltOf(event, index + 1));
                    }
                    appendPointAndDraw(
                            x, y,
                            pressureOf(event, 0),
                            orientationOf(event, 0),
                            tiltOf(event, 0));
                    flushStrokeDirty();
                    return true;
                case MotionEvent.ACTION_UP:
                    if (drawing && currentStroke != null) {
                        beginStrokeDirty();
                        appendPointAndDraw(
                                x, y,
                                pressureOf(event, 0),
                                orientationOf(event, 0),
                                tiltOf(event, 0));
                        flushStrokeDirty();
                        commitCurrentStroke();
                    }
                    drawing = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (currentStroke != null
                            && !currentStroke.points.isEmpty()) {
                        commitCurrentStroke();
                    }
                    drawing = false;
                    return true;
                default:
                    return true;
            }
        }

        private void updateSlider(float x) {
            float trackLeft = sliderBounds.left + 18f;
            float trackRight = sliderBounds.right - 18f;
            float ratio = clamp(
                    (x - trackLeft) / (trackRight - trackLeft), 0f, 1f);
            if (erasing) {
                selectedEraserWidth = ERASER_WIDTH_MIN
                        + ratio * (ERASER_WIDTH_MAX - ERASER_WIDTH_MIN);
                writeNativePenControl(
                        NOTE_ERASER_SIZE_FILE,
                        Float.toString(selectedEraserWidth));
            } else {
                selectedPenScale = PEN_SCALE_MIN
                        + ratio * (PEN_SCALE_MAX - PEN_SCALE_MIN);
                writeNativePenControl(
                        NOTE_SIZE_FILE,
                        Float.toString(selectedPenScale));
            }
            scheduleToolbarRender(
                    (int) BAR_BOTTOM, (int) PANEL_BOTTOM + 2);
        }

        private void beginNewStroke(MotionEvent event) {
            drawing = true;
            currentStroke = new StrokeCommand(
                    selectedPenTool,
                    selectedPenScale,
                    isEraser(event),
                    selectedEraserWidth);
            beginStrokeDirty();
        }

        private void appendPointAndDraw(float x, float y, float pressure,
                                        float orientation, float tilt) {
            if (currentStroke == null) {
                return;
            }
            InkPoint next = new InkPoint(
                    x, y, pressure, orientation, tilt);
            InkPoint previous = currentStroke.points.isEmpty()
                    ? next
                    : currentStroke.points.get(
                            currentStroke.points.size() - 1);
            currentStroke.points.add(next);
            renderSegment(pageCanvas, currentStroke,
                    previous, next, true);
        }

        private void commitCurrentStroke() {
            if (currentStroke == null || currentStroke.points.isEmpty()) {
                currentStroke = null;
                return;
            }
            boolean undoWasAvailable = canUndo();
            boolean redoWasAvailable = canRedo();
            commands.add(currentStroke);
            redoCommands.clear();
            currentStroke = null;
            if (undoWasAvailable != canUndo()
                    || redoWasAvailable != canRedo()) {
                invalidateUndoRedo();
            }
            markDocumentDirty();
        }

        private boolean canUndo() {
            return !commands.isEmpty();
        }

        private boolean canRedo() {
            return !redoCommands.isEmpty();
        }

        private void undo() {
            if (!canUndo() || drawing) {
                return;
            }
            redoCommands.add(commands.remove(commands.size() - 1));
            rebuildPage();
            markDocumentDirty();
        }

        private void redo() {
            if (!canRedo() || drawing) {
                return;
            }
            commands.add(redoCommands.remove(redoCommands.size() - 1));
            rebuildPage();
            markDocumentDirty();
        }

        private void clearPage() {
            if (pageCanvas == null || drawing) {
                return;
            }
            ClearCommand clear = new ClearCommand();
            commands.add(clear);
            redoCommands.clear();
            clear.draw(pageCanvas);
            refreshAfterDocumentEdit();
            invalidateUndoRedo();
            markDocumentDirty();
        }

        private void rebuildPage() {
            if (pageCanvas == null || basePage == null) {
                return;
            }
            pageCanvas.drawBitmap(basePage, 0f, 0f, null);
            for (NoteCommand command : commands) {
                command.draw(pageCanvas);
            }
            refreshAfterDocumentEdit();
            invalidateUndoRedo();
        }

        private void refreshAfterDocumentEdit() {
            /*
             * The second frame is deliberate. The host bridge records the
             * HWC sequence at which it sees the reset request, then waits for
             * a newer Android frame before removing its immediate-ink layer.
             */
            requestNativeOverlayReset();
            invalidate();
            postDelayed(this::invalidate, 80L);
            postDelayed(this::invalidate, 220L);
        }

        private void invalidateUndoRedo() {
            invalidate((int) undoButton.left - 3, 8,
                    (int) redoButton.right + 3, (int) BAR_BOTTOM);
        }

        private float pressureOf(MotionEvent event, int historySlot) {
            float value = historySlot == 0
                    ? event.getPressure(0)
                    : event.getHistoricalPressure(
                            0, historySlot - 1);
            return value <= 0f ? 0.12f : Math.min(1f, value);
        }

        private float orientationOf(MotionEvent event, int historySlot) {
            float value = historySlot == 0
                    ? event.getOrientation(0)
                    : event.getHistoricalOrientation(
                            0, historySlot - 1);
            float tilt = tiltOf(event, historySlot);
            return tilt < 0.02f ? -0.785398f : value;
        }

        private float tiltOf(MotionEvent event, int historySlot) {
            float value = historySlot == 0
                    ? event.getAxisValue(MotionEvent.AXIS_TILT, 0)
                    : event.getHistoricalAxisValue(
                            MotionEvent.AXIS_TILT,
                            0,
                            historySlot - 1);
            return clamp(value, 0f, 1.570796f);
        }

        private boolean isStylusEvent(MotionEvent event) {
            int tool = event.getToolType(0);
            return tool == MotionEvent.TOOL_TYPE_STYLUS
                    || tool == MotionEvent.TOOL_TYPE_ERASER
                    || (event.getSource() & InputDevice.SOURCE_STYLUS)
                       == InputDevice.SOURCE_STYLUS;
        }

        private boolean isEraser(MotionEvent event) {
            return erasing
                    || event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                    || (event.getButtonState()
                        & (MotionEvent.BUTTON_STYLUS_PRIMARY
                           | MotionEvent.BUTTON_STYLUS_SECONDARY)) != 0;
        }

        private void renderSegment(Canvas target,
                                   StrokeCommand stroke,
                                   InkPoint from,
                                   InkPoint to,
                                   boolean trackDirty) {
            float pressure = (from.pressure + to.pressure) * 0.5f;
            float distance = (float) Math.hypot(
                    to.x - from.x, to.y - from.y);
            boolean strokeStart = distance < 0.01f;
            float width = stroke.eraser
                    ? stroke.eraserWidth
                    : penWidth(stroke.tool, pressure, stroke.penScale,
                            distance, strokeStart);
            float averageTilt = (from.tilt + to.tilt) * 0.5f;
            if (!stroke.eraser && BRUSH.equals(stroke.tool)) {
                width *= 1f + Math.min(0.36f, averageTilt * 0.24f);
            }
            float visualWidth = width;
            if (stroke.eraser) {
                drawRoundSegment(target, from, to, width, PAGE_COLOR);
            } else if (PENCIL.equals(stroke.tool)) {
                drawPencilSegment(
                        target, from, to, width, pressure);
                visualWidth = width * 1.45f;
            } else if (MARKER.equals(stroke.tool)) {
                drawMarkerSegment(
                        target, from, to, width,
                        Color.rgb(35, 35, 33));
                visualWidth = width * 1.2f;
            } else {
                drawRoundSegment(target, from, to, width,
                        Color.rgb(35, 35, 33));
            }

            if (!trackDirty) {
                return;
            }
            int padding =
                    (int) Math.ceil(visualWidth * 0.75f + 6f);
            float left = Math.min(from.x, to.x) - padding;
            float top = Math.min(from.y, to.y) - padding;
            float right = Math.max(from.x, to.x) + padding;
            float bottom = Math.max(from.y, to.y) + padding;
            if (!strokeDirtySet) {
                strokeDirty.set(left, top, right, bottom);
                strokeDirtySet = true;
            } else {
                strokeDirty.union(left, top, right, bottom);
            }
        }

        private void drawRoundSegment(Canvas target,
                                      InkPoint from,
                                      InkPoint to,
                                      float width,
                                      int color) {
            ink.setStyle(Paint.Style.STROKE);
            ink.setColor(color);
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setStrokeWidth(width);
            if (Math.abs(to.x - from.x) < 0.01f
                    && Math.abs(to.y - from.y) < 0.01f) {
                ink.setStyle(Paint.Style.FILL);
                target.drawCircle(to.x, to.y, width * 0.5f, ink);
            } else {
                target.drawLine(from.x, from.y, to.x, to.y, ink);
            }
        }

        private int pencilCoordinateHash(InkPoint point) {
            int value = Math.round(point.x * 4f) * 374761393
                    + Math.round(point.y * 4f) * 668265263;
            value = (value ^ (value >>> 13)) * 1274126177;
            return value ^ (value >>> 16);
        }

        private void drawPencilSegment(Canvas target,
                                       InkPoint from,
                                       InkPoint to,
                                       float width,
                                       float pressure) {
            float dx = to.x - from.x;
            float dy = to.y - from.y;
            float distance = (float) Math.hypot(dx, dy);
            float normalX = 0.707106f;
            float normalY = -0.707106f;
            float averageTilt = (from.tilt + to.tilt) * 0.5f;
            if (averageTilt >= 0.02f) {
                float orientation =
                        (from.orientation + to.orientation) * 0.5f;
                normalX = (float) Math.cos(orientation);
                normalY = (float) Math.sin(orientation);
            } else if (distance >= 0.01f) {
                normalX = -dy / distance;
                normalY = dx / distance;
            }
            int grain = pencilCoordinateHash(to);
            float jitter = (((grain & 0xffff) / 65535f) - 0.5f)
                    * width * 0.32f;
            drawPencilStrand(target, from, to, normalX, normalY,
                    -width * 0.38f + jitter,
                    Math.max(0.42f, width * 0.23f),
                    148 - (int) (pressure * 34f), distance);
            drawPencilStrand(target, from, to, normalX, normalY,
                    jitter * 0.22f,
                    Math.max(0.58f, width * 0.52f),
                    78 - (int) (pressure * 42f), distance);
            drawPencilStrand(target, from, to, normalX, normalY,
                    width * 0.38f + jitter,
                    Math.max(0.42f, width * 0.23f),
                    174 - (int) (pressure * 30f), distance);
        }

        private void drawPencilStrand(Canvas target,
                                      InkPoint from,
                                      InkPoint to,
                                      float normalX,
                                      float normalY,
                                      float offset,
                                      float strandWidth,
                                      int gray,
                                      float distance) {
            float offsetX = normalX * offset;
            float offsetY = normalY * offset;
            ink.setColor(Color.rgb(gray, gray, gray));
            ink.setStrokeCap(Paint.Cap.ROUND);
            ink.setStrokeJoin(Paint.Join.ROUND);
            ink.setStrokeWidth(strandWidth);
            if (distance < 0.01f) {
                ink.setStyle(Paint.Style.FILL);
                target.drawCircle(
                        to.x + offsetX, to.y + offsetY,
                        strandWidth * 0.5f, ink);
            } else {
                ink.setStyle(Paint.Style.STROKE);
                target.drawLine(
                        from.x + offsetX, from.y + offsetY,
                        to.x + offsetX, to.y + offsetY, ink);
            }
        }

        private void drawMarkerSegment(Canvas target,
                                       InkPoint from,
                                       InkPoint to,
                                       float width,
                                       int color) {
            float orientation =
                    (from.orientation + to.orientation) * 0.5f;
            float averageTilt = (from.tilt + to.tilt) * 0.5f;
            if (averageTilt < 0.02f) {
                orientation = -0.785398f;
            }
            float nibLength = width
                    * (0.5f + Math.min(0.12f, averageTilt * 0.08f));
            float nibX = (float) Math.cos(orientation) * nibLength;
            float nibY = (float) Math.sin(orientation) * nibLength;
            float distance = (float) Math.hypot(
                    to.x - from.x, to.y - from.y);
            markerPath.reset();
            if (distance < 0.01f) {
                float sideX = -nibY * 0.18f;
                float sideY = nibX * 0.18f;
                markerPath.moveTo(
                        to.x + nibX + sideX,
                        to.y + nibY + sideY);
                markerPath.lineTo(
                        to.x - nibX + sideX,
                        to.y - nibY + sideY);
                markerPath.lineTo(
                        to.x - nibX - sideX,
                        to.y - nibY - sideY);
                markerPath.lineTo(
                        to.x + nibX - sideX,
                        to.y + nibY - sideY);
            } else {
                markerPath.moveTo(from.x + nibX, from.y + nibY);
                markerPath.lineTo(from.x - nibX, from.y - nibY);
                markerPath.lineTo(to.x - nibX, to.y - nibY);
                markerPath.lineTo(to.x + nibX, to.y + nibY);
            }
            markerPath.close();
            ink.setStyle(Paint.Style.FILL);
            ink.setColor(color);
            target.drawPath(markerPath, ink);
            if (distance >= 0.01f) {
                ink.setStyle(Paint.Style.STROKE);
                ink.setStrokeCap(Paint.Cap.SQUARE);
                ink.setStrokeWidth(Math.max(1.2f, width * 0.16f));
                target.drawLine(
                        to.x - nibX, to.y - nibY,
                        to.x + nibX, to.y + nibY, ink);
            }
        }

        private float penWidth(String tool, float pressure, float scale) {
            return penWidth(tool, pressure, scale, 0f, false);
        }

        private float penWidth(String tool,
                               float pressure,
                               float scale,
                               float segmentDistance,
                               boolean strokeStart) {
            float base;
            if (FINELINER.equals(tool)) {
                base = 2.5f + 1.15f * pressure;
            } else if (PENCIL.equals(tool)) {
                base = 0.70f + 4.55f
                        * (float) Math.pow(pressure, 0.88f);
            } else if (MARKER.equals(tool)) {
                base = 8.5f + 4.5f * pressure;
            } else if (BRUSH.equals(tool)) {
                float speedFactor = clamp(
                        1.15f - segmentDistance / 35f,
                        0.52f, 1.15f);
                float startFactor = strokeStart ? 0.48f : 1f;
                base = (1.1f + 14f
                        * (float) Math.pow(pressure, 0.48f))
                        * speedFactor * startFactor;
            } else {
                base = 1.15f + 5.2f
                        * (float) Math.pow(pressure, 0.72f);
            }
            return base * scale;
        }

        private boolean isKnownPenTool(String tool) {
            for (String value : penTools) {
                if (value.equals(tool)) {
                    return true;
                }
            }
            return false;
        }

        private float clamp(float value, float minimum, float maximum) {
            return Math.max(minimum, Math.min(maximum, value));
        }

        private void beginStrokeDirty() {
            strokeDirtySet = false;
            strokeDirty.setEmpty();
        }

        private void flushStrokeDirty() {
            if (!strokeDirtySet) {
                return;
            }
            invalidate((int) Math.floor(strokeDirty.left),
                    (int) Math.floor(strokeDirty.top),
                    (int) Math.ceil(strokeDirty.right),
                    (int) Math.ceil(strokeDirty.bottom));
            strokeDirtySet = false;
        }

        private void persistToolPreferences() {
            toolPreferences.edit()
                    .putString("pen-tool", selectedPenTool)
                    .putFloat("pen-scale", selectedPenScale)
                    .putFloat("eraser-width", selectedEraserWidth)
                    .putBoolean("eraser-active", erasing)
                    .apply();
        }

        void syncNativeTool() {
            writeNativePenControl(NOTE_TOOL_FILE,
                    erasing ? "erase" : selectedPenTool);
            writeNativePenControl(
                    NOTE_SIZE_FILE, Float.toString(selectedPenScale));
            writeNativePenControl(NOTE_ERASER_SIZE_FILE,
                    Float.toString(selectedEraserWidth));
            writeNativePenControl(NOTE_UI_BOTTOM_FILE,
                    Integer.toString((int) toolbarBottom() + 4));
        }

        private boolean loadVectorCommands(int targetWidth,
                                           int targetHeight) {
            if (noteVectorFile == null || !noteVectorFile.isFile()) {
                return false;
            }
            commands.clear();
            try (DataInputStream input = new DataInputStream(
                    new FileInputStream(noteVectorFile))) {
                if (input.readInt() != VECTOR_MAGIC) {
                    throw new java.io.IOException("invalid note magic");
                }
                int version = input.readInt();
                if (version < 1 || version > VECTOR_VERSION) {
                    throw new java.io.IOException(
                            "unsupported note version " + version);
                }
                int sourceWidth = input.readInt();
                int sourceHeight = input.readInt();
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    throw new java.io.IOException(
                            "invalid note dimensions");
                }
                input.readUTF(); // template is also kept in metadata
                int commandCount = input.readInt();
                if (commandCount < 0 || commandCount > 200_000) {
                    throw new java.io.IOException(
                            "invalid command count " + commandCount);
                }
                float scaleX = targetWidth / (float) sourceWidth;
                float scaleY = targetHeight / (float) sourceHeight;
                float widthScale = Math.min(scaleX, scaleY);
                long totalPoints = 0L;
                for (int commandIndex = 0;
                     commandIndex < commandCount; commandIndex++) {
                    int type = input.readUnsignedByte();
                    if (type == 0) {
                        commands.add(new ClearCommand());
                        continue;
                    }
                    if (type != 1) {
                        throw new java.io.IOException(
                                "invalid command type " + type);
                    }
                    String tool = input.readUTF();
                    if (!isKnownPenTool(tool)) {
                        tool = BALLPOINT;
                    }
                    float penScale = input.readFloat();
                    boolean eraser = input.readBoolean();
                    float eraserWidth = input.readFloat() * widthScale;
                    StrokeCommand stroke = new StrokeCommand(
                            tool, penScale, eraser, eraserWidth);
                    int pointCount = input.readInt();
                    totalPoints += pointCount;
                    if (pointCount < 0 || pointCount > 1_000_000
                            || totalPoints > 2_000_000L) {
                        throw new java.io.IOException(
                                "invalid point count " + pointCount);
                    }
                    for (int pointIndex = 0;
                         pointIndex < pointCount; pointIndex++) {
                        float x = input.readFloat() * scaleX;
                        float y = input.readFloat() * scaleY;
                        float pressure = input.readFloat();
                        float orientation = version >= 2
                                ? input.readFloat() : -0.785398f;
                        float tilt = version >= 2
                                ? input.readFloat() : 0f;
                        stroke.points.add(new InkPoint(
                                x, y, pressure, orientation, tilt));
                    }
                    commands.add(stroke);
                }
                Log.i(TAG, "loaded vector note commands="
                        + commands.size() + " file=" + noteVectorFile);
                return true;
            } catch (Exception error) {
                commands.clear();
                Log.e(TAG, "cannot load vector note "
                        + noteVectorFile, error);
                return false;
            }
        }

        private byte[] serializeVectorCommands() {
            if (!vectorDocument || page == null) {
                return null;
            }
            try {
                ByteArrayOutputStream bytes =
                        new ByteArrayOutputStream(64 * 1024);
                try (DataOutputStream output =
                             new DataOutputStream(bytes)) {
                    output.writeInt(VECTOR_MAGIC);
                    output.writeInt(VECTOR_VERSION);
                    output.writeInt(page.getWidth());
                    output.writeInt(page.getHeight());
                    output.writeUTF(noteTemplate);
                    output.writeInt(commands.size());
                    for (NoteCommand command : commands) {
                        if (command instanceof ClearCommand) {
                            output.writeByte(0);
                            continue;
                        }
                        StrokeCommand stroke = (StrokeCommand) command;
                        output.writeByte(1);
                        output.writeUTF(stroke.tool);
                        output.writeFloat(stroke.penScale);
                        output.writeBoolean(stroke.eraser);
                        output.writeFloat(stroke.eraserWidth);
                        output.writeInt(stroke.points.size());
                        for (InkPoint point : stroke.points) {
                            output.writeFloat(point.x);
                            output.writeFloat(point.y);
                            output.writeFloat(point.pressure);
                            output.writeFloat(point.orientation);
                            output.writeFloat(point.tilt);
                        }
                    }
                    output.flush();
                }
                return bytes.toByteArray();
            } catch (Exception error) {
                Log.e(TAG, "cannot serialize vector note", error);
                return null;
            }
        }

        void saveAsync() {
            if (!documentDirty) {
                return;
            }
            scheduleAutoSave(
                    activityPaused
                            ? PAUSE_SAVE_DELAY_MS
                            : IDLE_SAVE_DELAY_MS);
        }

        private void scheduleAutoSave() {
            scheduleAutoSave(IDLE_SAVE_DELAY_MS);
        }

        private void scheduleAutoSave(long delayMillis) {
            saveHandler.removeCallbacks(deferredSave);
            saveHandler.postDelayed(deferredSave, delayMillis);
        }

        private void cancelPendingAutoSave() {
            saveHandler.removeCallbacks(deferredSave);
        }

        private void markDocumentDirty() {
            documentGeneration++;
            documentDirty = true;
            scheduleAutoSave();
        }

        private void saveIfIdle() {
            if (!documentDirty || page == null) {
                return;
            }
            if (!activityPaused && (drawing || stylusInRange)) {
                scheduleAutoSave(BUSY_SAVE_RETRY_MS);
                return;
            }
            enqueueSaveSnapshot();
        }

        private void enqueueSaveSnapshot() {
            if (page == null || !documentDirty) {
                return;
            }
            final long snapshotGeneration = documentGeneration;
            Bitmap snapshot =
                    page.copy(Bitmap.Config.RGB_565, false);
            byte[] vectorSnapshot = serializeVectorCommands();
            boolean startWorker = false;
            synchronized (saveLock) {
                if (pendingSave != null) {
                    pendingSave.recycle();
                }
                pendingSave = snapshot;
                pendingVectorSave = vectorSnapshot;
                pendingSaveGeneration = snapshotGeneration;
                if (!saveWorkerRunning) {
                    saveWorkerRunning = true;
                    startWorker = true;
                }
            }
            if (startWorker) {
                new Thread(() -> {
                    Process.setThreadPriority(
                            Process.THREAD_PRIORITY_LOWEST);
                    drainSaveQueue();
                }, "paper-note-save").start();
            }
        }

        private void drainSaveQueue() {
            while (true) {
                Bitmap snapshot;
                byte[] vectorSnapshot;
                long snapshotGeneration;
                synchronized (saveLock) {
                    snapshot = pendingSave;
                    vectorSnapshot = pendingVectorSave;
                    snapshotGeneration = pendingSaveGeneration;
                    pendingSave = null;
                    pendingVectorSave = null;
                    if (snapshot == null) {
                        saveWorkerRunning = false;
                        return;
                    }
                }
                if (saveSnapshot(snapshot, vectorSnapshot)) {
                    final long savedGeneration = snapshotGeneration;
                    saveHandler.post(() -> {
                        if (documentGeneration == savedGeneration) {
                            documentDirty = false;
                        }
                    });
                }
            }
        }

        private boolean saveSnapshot(Bitmap snapshot,
                                     byte[] vectorSnapshot) {
            File target = noteFile;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            File temporary = new File(
                    parent, target.getName() + ".tmp");
            try (FileOutputStream output =
                         new FileOutputStream(temporary, false)) {
                if (!snapshot.compress(
                        Bitmap.CompressFormat.PNG, 100, output)) {
                    throw new java.io.IOException(
                            "PNG encoder rejected note snapshot");
                }
                output.flush();
                output.getFD().sync();
            } catch (Exception ignored) {
                snapshot.recycle();
                return false;
            }
            try {
                try {
                    Files.move(temporary.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException
                        unsupported) {
                    Files.move(temporary.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception ignored) {
                // A failed save must never block writing or returning home.
                return false;
            } finally {
                snapshot.recycle();
            }
            if (vectorSnapshot != null && !writeVectorSnapshot(
                    vectorSnapshot)) {
                return false;
            }
            if (!"quick-note".equals(noteId)) {
                noteMetadata.edit()
                        .putString(noteId + ".title", noteTitle)
                        .putString(noteId + ".template", noteTemplate)
                        .putLong(noteId + ".updated",
                                System.currentTimeMillis())
                        .apply();
            }
            return true;
        }

        private boolean writeVectorSnapshot(byte[] data) {
            File target = noteVectorFile;
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) {
                parent.mkdirs();
            }
            File temporary = new File(
                    parent, target.getName() + ".tmp");
            try (FileOutputStream output =
                         new FileOutputStream(temporary, false)) {
                output.write(data);
                output.flush();
                output.getFD().sync();
            } catch (Exception error) {
                Log.e(TAG, "cannot write vector note "
                        + target, error);
                temporary.delete();
                return false;
            }
            try {
                try {
                    Files.move(temporary.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException
                        unsupported) {
                    Files.move(temporary.toPath(), target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                return true;
            } catch (Exception error) {
                Log.e(TAG, "cannot commit vector note "
                        + target, error);
                temporary.delete();
                return false;
            }
        }

        private abstract class NoteCommand {
            abstract void draw(Canvas target);
        }

        private final class ClearCommand extends NoteCommand {
            @Override
            void draw(Canvas target) {
                target.drawColor(PAGE_COLOR);
            }
        }

        private final class StrokeCommand extends NoteCommand {
            final String tool;
            final float penScale;
            final boolean eraser;
            final float eraserWidth;
            final ArrayList<InkPoint> points = new ArrayList<>();

            StrokeCommand(String tool, float penScale,
                          boolean eraser, float eraserWidth) {
                this.tool = tool;
                this.penScale = penScale;
                this.eraser = eraser;
                this.eraserWidth = eraserWidth;
            }

            @Override
            void draw(Canvas target) {
                if (points.isEmpty()) {
                    return;
                }
                InkPoint previous = points.get(0);
                renderSegment(target, this, previous, previous, false);
                for (int index = 1; index < points.size(); index++) {
                    InkPoint next = points.get(index);
                    renderSegment(target, this, previous, next, false);
                    previous = next;
                }
            }
        }

        private final class InkPoint {
            final float x;
            final float y;
            final float pressure;
            final float orientation;
            final float tilt;

            InkPoint(float x, float y, float pressure,
                     float orientation, float tilt) {
                this.x = x;
                this.y = y;
                this.pressure = pressure;
                this.orientation = orientation;
                this.tilt = tilt;
            }
        }
    }
}
