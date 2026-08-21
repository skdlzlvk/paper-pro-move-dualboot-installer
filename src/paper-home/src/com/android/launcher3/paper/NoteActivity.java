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
import android.os.UserManager;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;

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
    private static final int VECTOR_VERSION = 3;
    private static final String NOTE_ACTIVE_FILE = "paper-note-active";
    private static final String NOTE_TOOL_FILE = "paper-note-tool";
    private static final String NOTE_SIZE_FILE = "paper-note-size";
    private static final String NOTE_ERASER_SIZE_FILE =
            "paper-eraser-size";
    private static final String NOTE_UI_BOTTOM_FILE =
            "paper-note-ui-bottom";
    private static final String NOTE_UI_LEFT_FILE =
            "paper-note-ui-left";
    private static final String NOTE_UI_REGIONS_FILE =
            "paper-note-ui-regions";
    private static final String NOTE_OVERLAY_RESET_FILE =
            "paper-note-overlay-reset";
    private static final String NOTE_TOOLBAR_REFRESH_FILE =
            "paper-note-toolbar-refresh";
    private static final String REFRESH_REQUEST_FILE =
            "paper-refresh-request";
    private static final String COLOR_MODE_FILE = "paper-color-mode";
    private NoteView noteView;
    private boolean activityPaused;
    private String noteId;
    private String noteTitle;
    private String noteTemplate;
    private File noteFile;
    private File noteVectorFile;
    private File baseNoteFile;
    private File baseNoteVectorFile;
    private SharedPreferences noteMetadata;

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
        configureDocument();
        noteView = new NoteView();
        PaperSystemBars.setContent(this, noteView);
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
        baseNoteFile = noteFile;
        baseNoteVectorFile = noteVectorFile;
    }

    private void selectPageFiles(int pageIndex) {
        if (pageIndex <= 0) {
            noteFile = baseNoteFile;
            noteVectorFile = baseNoteVectorFile;
            return;
        }
        String rasterStem = baseNoteFile.getName().replaceAll(
                "\\.png$", "");
        String vectorStem = baseNoteVectorFile.getName().replaceAll(
                "\\.pnote$", "");
        String suffix = "-page-" + (pageIndex + 1);
        noteFile = new File(baseNoteFile.getParentFile(),
                rasterStem + suffix + ".png");
        noteVectorFile = new File(baseNoteVectorFile.getParentFile(),
                vectorStem + suffix + ".pnote");
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityPaused = false;
        if (noteView != null) {
            noteView.restoreColorSettleState();
            noteView.syncNativeTool();
        }
        writeNativePenControl(NOTE_ACTIVE_FILE, "active");
    }

    @Override
    protected void onPause() {
        activityPaused = true;
        if (noteView != null) {
            noteView.cancelColorSettleCallbacks(false);
        }
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

    private String readNativePenControl(String name) {
        File control = new File(getFilesDir(), name);
        if (!control.isFile()) return "";
        byte[] value = new byte[48];
        try (FileInputStream input = new FileInputStream(control)) {
            int count = input.read(value);
            return count <= 0 ? "" : new String(
                    value, 0, count, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private void requestNativeOverlayReset() {
        writeNativePenControl(NOTE_OVERLAY_RESET_FILE, "reset");
        writeNativePenControl(REFRESH_REQUEST_FILE, "refresh");
    }

    private void requestToolbarRefresh(int requestedRight,
                                       int requestedBottom) {
        int right = Math.max(1, requestedRight);
        int bottom = Math.max(1, requestedBottom);
        writeNativePenControl(
                NOTE_TOOLBAR_REFRESH_FILE, right + "," + bottom);
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

    private final class NoteView extends View {
        private static final float TOOLBAR_LEFT = 86f;
        private static final float TOOLBAR_TOP = 20f;
        private static final float TOOLBAR_RIGHT = 942f;
        private static final float TOOLBAR_BOTTOM = 106f;
        private static final float PANEL_TOP = TOOLBAR_BOTTOM;
        private static final float PEN_PANEL_LEFT = 178f;
        private static final float PEN_PANEL_RIGHT = 626f;
        private static final float PEN_PANEL_BOTTOM = 884f;
        private static final float ERASER_PANEL_LEFT = 268f;
        private static final float ERASER_PANEL_RIGHT = 716f;
        private static final float ERASER_PANEL_BOTTOM = 424f;
        private static final float MORE_PANEL_LEFT = 548f;
        private static final float MORE_PANEL_RIGHT = 904f;
        private static final float MORE_PANEL_BOTTOM = 1010f;
        private static final float PEN_SCALE_MIN = 0.45f;
        private static final float PEN_SCALE_MAX = 2.20f;
        private static final float ERASER_WIDTH_MIN = 12f;
        private static final float ERASER_WIDTH_MAX = 96f;
        private static final long IDLE_SAVE_DELAY_MS = 6000L;
        private static final long PAUSE_SAVE_DELAY_MS = 250L;
        private static final long BUSY_SAVE_RETRY_MS = 1500L;
        private static final long TOOLBAR_RENDER_DELAY_MS = 110L;
        private static final long PALETTE_COLOR_PREVIEW_DELAY_MS = 180L;
        private static final long PALETTE_COLOR_STAGE_DELAY_MS = 420L;
        private static final long COLOR_SETTLE_IDLE_DELAY_MS = 1000L;
        private static final long COLOR_RESET_STAGE_DELAY_MS = 650L;
        private static final long COLOR_SETTLE_POLL_MS = 250L;
        private static final long COLOR_SETTLE_TIMEOUT_MS = 12000L;
        private static final float UI_HIT_SLOP = 12f;
        private static final int UI_TARGET_NONE = 0;
        private static final int UI_TARGET_BLANK = 1;
        private static final int UI_TARGET_BACK = 2;
        private static final int UI_TARGET_HIDE = 3;
        private static final int UI_TARGET_SELECTION = 4;
        private static final int UI_TARGET_UNDO = 5;
        private static final int UI_TARGET_REDO = 6;
        private static final int UI_TARGET_PAGE = 7;
        private static final int UI_TARGET_PEN = 8;
        private static final int UI_TARGET_ERASER = 9;
        private static final int UI_TARGET_PREVIOUS_PAGE = 10;
        private static final int UI_TARGET_NEXT_PAGE = 11;
        private static final int UI_TARGET_ADD_PAGE = 12;
        private static final int UI_TARGET_PAGE_MODE = 13;
        private static final int UI_TARGET_SLIDER = 14;
        private static final int UI_TARGET_SIZE_BASE = 20;
        private static final int UI_TARGET_PEN_TOOL_BASE = 30;
        private static final int UI_TARGET_COLOR_BASE = 50;
        private static final int UI_TARGET_CLEAR = 70;
        private static final String BALLPOINT = "ballpoint";
        private static final String FINELINER = "fineliner";
        private static final String PENCIL = "pencil";
        private static final String MARKER = "marker";
        private static final String BRUSH = "brush";
        private static final String MECHANICAL = "mechanical";
        private static final String CALLIGRAPHY = "calligraphy";
        private static final String HIGHLIGHTER = "highlighter";
        private static final String SHADER = "shader";
        // Gallery 3 visibly dithers the old warm off-white across the entire
        // color rectangle. Keep Note paper exactly neutral white.
        private static final int PAGE_COLOR = 0xffffffff;
        private static final int DEFAULT_INK_COLOR = 0xff232321;
        private static final int PAGE_MODE_HORIZONTAL = 0;
        private static final int PAGE_MODE_VERTICAL = 1;
        private static final float PAGE_SWIPE_THRESHOLD = 120f;

        private final String[] penTools = {
                BALLPOINT, FINELINER, PENCIL, MARKER, BRUSH, HIGHLIGHTER
        };
        private final int[] inkColors = {
                DEFAULT_INK_COLOR,
                0xff8b8983,
                0xffe36c00,
                0xff145fd0,
                0xffd12b22,
                0xff168044,
                0xffd99f00,
                0xff008eae,
                0xffae297d
        };
        private final String[] penLabels;
        private final String[] colorLabels;
        private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path markerPath = new Path();
        private final RectF backButton = new RectF();
        private final RectF hideToolbarButton = new RectF();
        private final RectF undoButton = new RectF();
        private final RectF redoButton = new RectF();
        private final RectF pageButton = new RectF();
        private final RectF selectionButton = new RectF();
        private final RectF penButton = new RectF();
        private final RectF eraserButton = new RectF();
        private final RectF clearButton = new RectF();
        private final RectF previousPageButton = new RectF();
        private final RectF nextPageButton = new RectF();
        private final RectF addPageButton = new RectF();
        private final RectF pageModeButton = new RectF();
        private final RectF sliderBounds = new RectF();
        private final RectF[] sizeButtons = {
                new RectF(), new RectF(), new RectF()
        };
        private final RectF[] penToolButtons = {
                new RectF(), new RectF(), new RectF(),
                new RectF(), new RectF(), new RectF()
        };
        private final RectF[] colorButtons = {
                new RectF(), new RectF(), new RectF(), new RectF(),
                new RectF(), new RectF(), new RectF(), new RectF(),
                new RectF()
        };
        private final RectF strokeDirty = new RectF();
        private final ArrayList<NoteCommand> commands = new ArrayList<>();
        private final ArrayList<NoteCommand> redoCommands =
                new ArrayList<>();
        private final SharedPreferences toolPreferences;
        private final Handler saveHandler =
                new Handler(Looper.getMainLooper());
        private final Object saveLock = new Object();
        private final ArrayDeque<SaveRequest> saveQueue =
                new ArrayDeque<>();
        private final Runnable deferredSave = this::saveIfIdle;
        private final Runnable deferredToolbarRender =
                this::renderPendingToolbar;
        private final Runnable deferredColorPrepare =
                this::prepareColorSettle;
        private final Runnable deferredColorRequest =
                this::requestPreparedColorSettle;
        private final Runnable deferredColorPoll =
                this::pollColorSettle;
        private final Runnable deferredPaletteColorPreview =
                this::requestPaletteColorPreview;

        private Bitmap page;
        private Bitmap basePage;
        private long documentGeneration;
        private Canvas pageCanvas;
        private StrokeCommand currentStroke;
        private boolean drawing;
        private boolean stylusInRange;
        private boolean strokeDirtySet;
        private boolean erasing;
        private boolean panelExpanded;
        private boolean pagePanelExpanded;
        private boolean toolbarVisible = true;
        private boolean multiTouchGesture;
        private int gesturePointerCount;
        private boolean sliderDragging;
        private boolean fingerTracking;
        private boolean dismissPanelGesture;
        private boolean confirmClear;
        private boolean documentDirty;
        private boolean uiGestureCaptured;
        private boolean nativeUiCaptureActive;
        private boolean colorSettlePending;
        private boolean colorSettlePreparing;
        private boolean colorSettleWindowOpen;
        private boolean paletteColorPreviewPending;
        private boolean paletteColorPreviewActive;
        private boolean palettePreviewAfterRender;
        private int capturedUiTarget = UI_TARGET_NONE;
        private long colorSettleStartedAt;
        private boolean saveWorkerRunning;
        private boolean vectorDocument;
        private int pendingToolbarTop = Integer.MAX_VALUE;
        private int pendingToolbarRight;
        private int pendingToolbarBottom;
        private int toolbarRefreshAfterDrawRight;
        private int toolbarRefreshAfterDrawBottom;
        private int renderedToolbarRight = (int) TOOLBAR_RIGHT;
        private int renderedToolbarBottom = (int) TOOLBAR_BOTTOM + 4;
        private int currentPageIndex;
        private int pageCount;
        private int pageMode;
        private float fingerDownX;
        private float fingerDownY;
        private String selectedPenTool;
        private float selectedPenScale;
        private float selectedEraserWidth;
        private int selectedInkColor;

        NoteView() {
            super(NoteActivity.this);
            penLabels = getResources().getStringArray(R.array.pen_labels);
            colorLabels =
                    getResources().getStringArray(R.array.note_color_labels);
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
            selectedPenTool = normalizeLegacyPenTool(selectedPenTool);
            if (!isKnownPenTool(selectedPenTool)) {
                selectedPenTool = BALLPOINT;
            }
            selectedPenScale = clamp(
                    toolPreferences.getFloat("pen-scale", 1f),
                    PEN_SCALE_MIN, PEN_SCALE_MAX);
            selectedEraserWidth = clamp(
                    toolPreferences.getFloat("eraser-width", 40f),
                    ERASER_WIDTH_MIN, ERASER_WIDTH_MAX);
            selectedInkColor = toolPreferences.getInt(
                    "ink-color", DEFAULT_INK_COLOR);
            selectedInkColor = normalizeLegacyInkColor(selectedInkColor);
            if (!isKnownInkColor(selectedInkColor)) {
                selectedInkColor = DEFAULT_INK_COLOR;
            }
            // A newly opened notebook must always be immediately writable.
            // Width, color and pen style are durable preferences, but the
            // eraser is a transient mode and must not leak from the previous
            // NoteActivity instance.
            erasing = false;
            toolPreferences.edit()
                    .putInt("ink-color", selectedInkColor)
                    .putBoolean("eraser-active", false)
                    .apply();
            pageCount = Math.max(1, noteMetadata.getInt(
                    noteId + ".page-count", 1));
            pageMode = noteMetadata.getInt(
                    noteId + ".page-mode", PAGE_MODE_HORIZONTAL);
            if (pageMode != PAGE_MODE_VERTICAL) {
                pageMode = PAGE_MODE_HORIZONTAL;
            }
            currentPageIndex = 0;
            selectPageFiles(currentPageIndex);
        }

        @Override
        protected void onSizeChanged(int width, int height,
                                     int oldWidth, int oldHeight) {
            loadCurrentPage(width, height);
        }

        private void loadCurrentPage(int width, int height) {
            Bitmap loaded = BitmapFactory.decodeFile(noteFile.getPath());
            if (page != null) {
                page.recycle();
            }
            if (basePage != null) {
                basePage.recycle();
                basePage = null;
            }
            page = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
            pageCanvas = new Canvas(page);
            pageCanvas.drawColor(PAGE_COLOR);
            boolean templateUpgraded = false;
            commands.clear();
            redoCommands.clear();
            vectorDocument = loadVectorCommands(width, height);
            if (vectorDocument) {
                drawTemplate(pageCanvas, noteTemplate, width, height);
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
                // Legacy notes remain a stable raster base. New strokes are
                // still fast and undoable for this session; newly-created
                // notes use the durable vector sidecar below.
                basePage = page.copy(Bitmap.Config.RGB_565, false);
            } else {
                drawTemplate(pageCanvas, noteTemplate, width, height);
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
            if (!toolbarVisible) {
                return 92f;
            }
            return panelExpanded || pagePanelExpanded
                    ? activePanelBottom() : TOOLBAR_BOTTOM;
        }

        private float uiLeft() {
            if (!toolbarVisible) {
                return 92f;
            }
            return panelExpanded || pagePanelExpanded
                    ? activePanelRight() : TOOLBAR_RIGHT;
        }

        private float toolbarRefreshBottom() {
            if (!toolbarVisible) {
                return 96f;
            }
            return panelExpanded || pagePanelExpanded
                    ? activePanelBottom() : TOOLBAR_BOTTOM;
        }

        private float toolbarRefreshRight() {
            if (!toolbarVisible) {
                return 96f;
            }
            return panelExpanded || pagePanelExpanded
                    ? activePanelRight() : TOOLBAR_RIGHT;
        }

        private float activePanelLeft() {
            if (pagePanelExpanded) {
                return MORE_PANEL_LEFT;
            }
            return erasing ? ERASER_PANEL_LEFT : PEN_PANEL_LEFT;
        }

        private float activePanelRight() {
            if (pagePanelExpanded) {
                return MORE_PANEL_RIGHT;
            }
            return erasing ? ERASER_PANEL_RIGHT : PEN_PANEL_RIGHT;
        }

        private float activePanelBottom() {
            if (pagePanelExpanded) {
                return MORE_PANEL_BOTTOM;
            }
            return erasing ? ERASER_PANEL_BOTTOM : PEN_PANEL_BOTTOM;
        }

        private boolean isToolbarPoint(float x, float y) {
            if (!toolbarVisible) {
                return hideToolbarButton.contains(x, y);
            }
            return x >= TOOLBAR_LEFT - 4f
                    && x <= TOOLBAR_RIGHT + 4f
                    && y >= TOOLBAR_TOP - 4f
                    && y <= TOOLBAR_BOTTOM + 4f
                    || ((panelExpanded || pagePanelExpanded)
                        && x >= activePanelLeft() - 4f
                        && x <= activePanelRight() + 4f
                        && y >= PANEL_TOP - 4f
                        && y <= activePanelBottom() + 4f);
        }

        private boolean containsWithSlop(RectF bounds, float x, float y) {
            return x >= bounds.left - UI_HIT_SLOP
                    && x <= bounds.right + UI_HIT_SLOP
                    && y >= bounds.top - UI_HIT_SLOP
                    && y <= bounds.bottom + UI_HIT_SLOP;
        }

        private int hitUiTarget(float x, float y) {
            final float panelX = x - activePanelLeft();
            if (!toolbarVisible) {
                return containsWithSlop(hideToolbarButton, x, y)
                        ? UI_TARGET_HIDE : UI_TARGET_NONE;
            }

            // Exact hits win before hit slop is considered. This keeps two
            // adjacent tool buttons deterministic at their shared edge.
            if (backButton.contains(x, y)) return UI_TARGET_BACK;
            if (hideToolbarButton.contains(x, y)) return UI_TARGET_HIDE;
            if (selectionButton.contains(x, y)) return UI_TARGET_SELECTION;
            if (undoButton.contains(x, y)) return UI_TARGET_UNDO;
            if (redoButton.contains(x, y)) return UI_TARGET_REDO;
            if (pageButton.contains(x, y)) return UI_TARGET_PAGE;
            if (penButton.contains(x, y)) return UI_TARGET_PEN;
            if (eraserButton.contains(x, y)) return UI_TARGET_ERASER;

            if (pagePanelExpanded) {
                if (previousPageButton.contains(panelX, y)) {
                    return UI_TARGET_PREVIOUS_PAGE;
                }
                if (nextPageButton.contains(panelX, y)) {
                    return UI_TARGET_NEXT_PAGE;
                }
                if (addPageButton.contains(panelX, y)) {
                    return UI_TARGET_ADD_PAGE;
                }
                if (pageModeButton.contains(panelX, y)) {
                    return UI_TARGET_PAGE_MODE;
                }
            } else if (panelExpanded) {
                if (sliderBounds.contains(panelX, y)) {
                    return UI_TARGET_SLIDER;
                }
                for (int index = 0; index < sizeButtons.length; index++) {
                    if (sizeButtons[index].contains(panelX, y)) {
                        return UI_TARGET_SIZE_BASE + index;
                    }
                }
                if (!erasing) {
                    for (int index = 0;
                         index < penToolButtons.length; index++) {
                        if (penToolButtons[index].contains(panelX, y)) {
                            return UI_TARGET_PEN_TOOL_BASE + index;
                        }
                    }
                    for (int index = 0;
                         index < colorButtons.length; index++) {
                        if (colorButtons[index].contains(panelX, y)) {
                            return UI_TARGET_COLOR_BASE + index;
                        }
                    }
                } else if (clearButton.contains(panelX, y)) {
                    return UI_TARGET_CLEAR;
                }
            }

            if (containsWithSlop(penButton, x, y)) return UI_TARGET_PEN;
            if (containsWithSlop(eraserButton, x, y)) {
                return UI_TARGET_ERASER;
            }
            return isToolbarPoint(x, y)
                    ? UI_TARGET_BLANK : UI_TARGET_NONE;
        }

        private void beginUiGesture(int target, float x, float y) {
            uiGestureCaptured = true;
            capturedUiTarget = target;
            armNativeUiCapture();
            fingerTracking = false;
            multiTouchGesture = false;
            gesturePointerCount = 0;
            drawing = false;
            currentStroke = null;

            final float panelX = x - activePanelLeft();
            if (target == UI_TARGET_SLIDER) {
                sliderDragging = true;
                updateSlider(panelX);
                return;
            }
            activateUiTarget(target);
        }

        private void finishUiGesture(boolean cancelled, float x) {
            if (capturedUiTarget == UI_TARGET_SLIDER) {
                if (!cancelled) {
                    updateSlider(x - activePanelLeft());
                    persistToolPreferences();
                    panelExpanded = false;
                    syncNativeTool();
                    scheduleToolbarRender(
                            0, (int) activePanelBottom() + 2);
                }
                sliderDragging = false;
            }
            capturedUiTarget = UI_TARGET_NONE;
            uiGestureCaptured = false;
            disarmNativeUiCapture();
        }

        private void armNativeUiCapture() {
            if (nativeUiCaptureActive) return;
            nativeUiCaptureActive = true;
            int width = Math.max(960, getWidth());
            int height = Math.max(1696, getHeight());
            writeNativePenControl(NOTE_UI_REGIONS_FILE,
                    "0,0," + width + "," + height);
        }

        private void disarmNativeUiCapture() {
            if (!nativeUiCaptureActive || uiGestureCaptured) return;
            nativeUiCaptureActive = false;
            writeNativePenControl(NOTE_UI_REGIONS_FILE, nativeUiRegions());
        }

        private void activateUiTarget(int target) {
            if (target == UI_TARGET_BLANK) return;
            if (target == UI_TARGET_BACK) {
                saveAsync();
                finish();
                overridePendingTransition(0, 0);
                return;
            }
            if (target == UI_TARGET_HIDE) {
                toolbarVisible = !toolbarVisible;
                panelExpanded = false;
                pagePanelExpanded = false;
                stopPaletteColorPreviewForWriting();
                sliderDragging = false;
                confirmClear = false;
                syncNativeTool();
                scheduleToolbarRender(0, (int) MORE_PANEL_BOTTOM + 2);
                return;
            }
            if (target == UI_TARGET_SELECTION) {
                panelExpanded = false;
                pagePanelExpanded = false;
                stopPaletteColorPreviewForWriting();
                confirmClear = false;
                syncNativeTool();
                scheduleToolbarRender(0, (int) PEN_PANEL_BOTTOM + 2);
                return;
            }
            if (target == UI_TARGET_UNDO) {
                undo();
                return;
            }
            if (target == UI_TARGET_REDO) {
                redo();
                return;
            }
            if (target == UI_TARGET_PAGE) {
                pagePanelExpanded = !pagePanelExpanded;
                panelExpanded = false;
                stopPaletteColorPreviewForWriting();
                sliderDragging = false;
                confirmClear = false;
                syncNativeTool();
                scheduleToolbarRender(0, (int) MORE_PANEL_BOTTOM + 2);
                return;
            }
            if (target == UI_TARGET_PEN || target == UI_TARGET_ERASER) {
                final boolean requestedEraser = target == UI_TARGET_ERASER;
                final boolean sameTool = erasing == requestedEraser;
                erasing = requestedEraser;
                pagePanelExpanded = false;
                panelExpanded = sameTool ? !panelExpanded : true;
                if (panelExpanded && !erasing) {
                    // Color is needed once when the palette first becomes
                    // visible. Tool, width and color selections repaint the
                    // panel but must not start another slow Gallery 3 pass.
                    palettePreviewAfterRender = true;
                } else {
                    stopPaletteColorPreviewForWriting();
                }
                confirmClear = false;
                persistToolPreferences();
                // Change the native tool at ACTION_DOWN, before another pen
                // sample can ever be interpreted as the previous tool.
                syncNativeTool();
                Log.i(TAG, "tool transition: "
                        + (erasing ? "eraser" : "pen"));
                scheduleToolbarRender(0, (int) (erasing
                        ? ERASER_PANEL_BOTTOM : PEN_PANEL_BOTTOM) + 2);
                return;
            }
            if (target == UI_TARGET_PREVIOUS_PAGE) {
                pagePanelExpanded = false;
                switchToPage(currentPageIndex - 1);
                return;
            }
            if (target == UI_TARGET_NEXT_PAGE) {
                pagePanelExpanded = false;
                switchToPage(currentPageIndex + 1);
                return;
            }
            if (target == UI_TARGET_ADD_PAGE) {
                pagePanelExpanded = false;
                addPage();
                return;
            }
            if (target == UI_TARGET_PAGE_MODE) {
                pageMode = pageMode == PAGE_MODE_HORIZONTAL
                        ? PAGE_MODE_VERTICAL : PAGE_MODE_HORIZONTAL;
                pagePanelExpanded = false;
                persistPagePreferences();
                syncNativeTool();
                scheduleToolbarRender(0, (int) MORE_PANEL_BOTTOM + 2);
                return;
            }
            if (target >= UI_TARGET_SIZE_BASE
                    && target < UI_TARGET_SIZE_BASE + sizeButtons.length) {
                int index = target - UI_TARGET_SIZE_BASE;
                if (erasing) {
                    selectedEraserWidth = index == 0
                            ? 22f : index == 1 ? 40f : 68f;
                } else {
                    selectedPenScale = index == 0
                            ? 0.72f : index == 1 ? 1f : 1.42f;
                }
                persistToolPreferences();
                syncNativeTool();
                scheduleToolbarRender(
                        (int) PANEL_TOP, (int) activePanelBottom() + 2);
                return;
            }
            if (target >= UI_TARGET_PEN_TOOL_BASE
                    && target < UI_TARGET_PEN_TOOL_BASE
                    + penToolButtons.length) {
                selectedPenTool = penTools[target - UI_TARGET_PEN_TOOL_BASE];
                persistToolPreferences();
                syncNativeTool();
                scheduleToolbarRender(
                        (int) PANEL_TOP, (int) PEN_PANEL_BOTTOM + 2);
                return;
            }
            if (target >= UI_TARGET_COLOR_BASE
                    && target < UI_TARGET_COLOR_BASE + colorButtons.length) {
                selectedInkColor = inkColors[target - UI_TARGET_COLOR_BASE];
                persistToolPreferences();
                syncNativeTool();
                scheduleToolbarRender(
                        (int) PANEL_TOP, (int) PEN_PANEL_BOTTOM + 2);
                return;
            }
            if (target == UI_TARGET_CLEAR) {
                if (confirmClear) {
                    clearPage();
                    confirmClear = false;
                    erasing = false;
                    panelExpanded = false;
                    persistToolPreferences();
                    syncNativeTool();
                    scheduleToolbarRender(
                            0, (int) ERASER_PANEL_BOTTOM + 2);
                } else {
                    confirmClear = true;
                    scheduleToolbarRender(
                            (int) PANEL_TOP,
                            (int) ERASER_PANEL_BOTTOM + 2);
                }
            }
        }

        private String nativeUiRegions() {
            if (!toolbarVisible) {
                return "18,18,96,96";
            }
            String toolbar = (int) TOOLBAR_LEFT + ","
                    + (int) TOOLBAR_TOP + ","
                    + (int) TOOLBAR_RIGHT + ","
                    + (int) TOOLBAR_BOTTOM;
            if (!panelExpanded && !pagePanelExpanded) {
                return toolbar;
            }
            return toolbar + ";" + (int) activePanelLeft() + ","
                    + (int) PANEL_TOP + ","
                    + (int) activePanelRight() + ","
                    + (int) activePanelBottom();
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
            final float top = PANEL_TOP + 38f;
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
            canvas.drawRoundRect(bounds, 5f, 5f, ui);
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
            if (toolbarRefreshAfterDrawRight > 0
                    && toolbarRefreshAfterDrawBottom > 0) {
                final int refreshRight = toolbarRefreshAfterDrawRight;
                final int refreshBottom = toolbarRefreshAfterDrawBottom;
                toolbarRefreshAfterDrawRight = 0;
                toolbarRefreshAfterDrawBottom = 0;
                post(() -> requestToolbarRefresh(
                        refreshRight, refreshBottom));
            }
        }

        private void drawToolbar(Canvas canvas) {
            hideToolbarButton.set(18f, 18f, 96f, 96f);
            if (!toolbarVisible) {
                ui.setStyle(Paint.Style.FILL);
                ui.setColor(Color.rgb(253, 253, 250));
                canvas.drawOval(hideToolbarButton, ui);
                ui.setStyle(Paint.Style.STROKE);
                ui.setStrokeWidth(2f);
                ui.setColor(Color.rgb(92, 92, 88));
                canvas.drawOval(hideToolbarButton, ui);
                drawRailIcon(canvas, hideToolbarButton.centerX(),
                        hideToolbarButton.centerY(), 0,
                        Color.rgb(42, 42, 40));
                return;
            }

            ui.setStyle(Paint.Style.FILL);
            ui.setColor(Color.rgb(253, 253, 250));
            canvas.drawRect(TOOLBAR_LEFT, TOOLBAR_TOP,
                    TOOLBAR_RIGHT, TOOLBAR_BOTTOM, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1.25f);
            ui.setColor(Color.rgb(151, 150, 145));
            canvas.drawRect(TOOLBAR_LEFT, TOOLBAR_TOP,
                    TOOLBAR_RIGHT, TOOLBAR_BOTTOM, ui);

            hideToolbarButton.set(TOOLBAR_LEFT, TOOLBAR_TOP,
                    174f, TOOLBAR_BOTTOM);
            penButton.set(174f, TOOLBAR_TOP, 264f, TOOLBAR_BOTTOM);
            eraserButton.set(264f, TOOLBAR_TOP, 354f, TOOLBAR_BOTTOM);
            selectionButton.set(354f, TOOLBAR_TOP, 444f, TOOLBAR_BOTTOM);
            undoButton.set(444f, TOOLBAR_TOP, 534f, TOOLBAR_BOTTOM);
            redoButton.set(534f, TOOLBAR_TOP, 624f, TOOLBAR_BOTTOM);
            pageButton.set(772f, TOOLBAR_TOP, 856f, TOOLBAR_BOTTOM);
            backButton.set(856f, TOOLBAR_TOP,
                    TOOLBAR_RIGHT, TOOLBAR_BOTTOM);

            drawRailButton(canvas, hideToolbarButton, false, true, 0);
            // Tool selection and popup expansion are independent states.
            // Keep the active tool visibly pressed even after its popup has
            // been dismissed, so the user can tell what the Marker will do.
            drawRailButton(canvas, penButton, !erasing, true, 1);
            drawRailButton(canvas, eraserButton, erasing, true, 2);
            drawRailButton(canvas, selectionButton, false, true, 5);
            drawRailButton(canvas, undoButton, false, canUndo(), 3);
            drawRailButton(canvas, redoButton, false, canRedo(), 4);
            drawRailButton(canvas, pageButton,
                    pagePanelExpanded, true, 7);
            drawRailButton(canvas, backButton, false, true, 8);

            if (!panelExpanded && !pagePanelExpanded) {
                return;
            }
            float panelLeft = activePanelLeft();
            float panelRight = activePanelRight();
            float panelBottom = activePanelBottom();
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(Color.rgb(253, 253, 250));
            canvas.drawRect(panelLeft, PANEL_TOP,
                    panelRight, panelBottom, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1.25f);
            ui.setColor(Color.rgb(125, 124, 120));
            canvas.drawRect(panelLeft, PANEL_TOP,
                    panelRight, panelBottom, ui);
            canvas.save();
            canvas.translate(panelLeft, 0f);
            if (pagePanelExpanded) {
                drawPagePanel(canvas);
            } else if (erasing) {
                drawEraserPanel(canvas);
            } else {
                drawPenPanel(canvas);
            }
            canvas.restore();
        }

        private void drawRailButton(Canvas canvas, RectF bounds,
                                    boolean selected, boolean enabled,
                                    int icon) {
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(selected
                    ? Color.rgb(148, 151, 149)
                    : Color.rgb(253, 253, 250));
            canvas.drawRect(bounds, ui);
            int color = !enabled
                    ? Color.rgb(174, 173, 168)
                    : (selected ? Color.WHITE : Color.rgb(42, 42, 40));
            drawRailIcon(canvas, bounds.centerX(), bounds.centerY(),
                    icon, color);
        }

        private void drawRailIcon(Canvas canvas, float cx, float cy,
                                  int icon, int color) {
            ui.setColor(color);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeCap(Paint.Cap.ROUND);
            ui.setStrokeJoin(Paint.Join.ROUND);
            ui.setStrokeWidth(3.2f);
            if (icon == 0) {
                canvas.drawCircle(cx, cy, 20f, ui);
                ui.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy, 3f, ui);
                return;
            }
            if (icon == 1) {
                canvas.save();
                canvas.rotate(-42f, cx, cy);
                canvas.drawRect(cx - 7f, cy - 22f,
                        cx + 7f, cy + 14f, ui);
                canvas.drawLine(cx - 7f, cy + 7f,
                        cx + 7f, cy + 7f, ui);
                canvas.restore();
                return;
            }
            if (icon == 2 || icon == 6) {
                canvas.save();
                canvas.rotate(-38f, cx, cy);
                canvas.drawRoundRect(cx - 15f, cy - 10f,
                        cx + 15f, cy + 10f, 3f, 3f, ui);
                canvas.drawLine(cx + 2f, cy - 10f,
                        cx + 2f, cy + 10f, ui);
                canvas.restore();
                return;
            }
            if (icon == 3 || icon == 4) {
                float start = icon == 3 ? 45f : 135f;
                float sweep = icon == 3 ? 245f : -245f;
                RectF arc = new RectF(cx - 19f, cy - 18f,
                        cx + 19f, cy + 18f);
                canvas.drawArc(arc, start, sweep, false, ui);
                float direction = icon == 3 ? -1f : 1f;
                canvas.drawLine(cx + direction * 18f, cy - 15f,
                        cx + direction * 8f, cy - 17f, ui);
                canvas.drawLine(cx + direction * 18f, cy - 15f,
                        cx + direction * 16f, cy - 5f, ui);
                return;
            }
            if (icon == 5) {
                ui.setStrokeWidth(2.8f);
                float d = 17f;
                float k = 7f;
                canvas.drawLine(cx - d, cy - d, cx - d + k, cy - d, ui);
                canvas.drawLine(cx - d, cy - d, cx - d, cy - d + k, ui);
                canvas.drawLine(cx + d, cy - d, cx + d - k, cy - d, ui);
                canvas.drawLine(cx + d, cy - d, cx + d, cy - d + k, ui);
                canvas.drawLine(cx - d, cy + d, cx - d + k, cy + d, ui);
                canvas.drawLine(cx - d, cy + d, cx - d, cy + d - k, ui);
                canvas.drawLine(cx + d, cy + d, cx + d - k, cy + d, ui);
                canvas.drawLine(cx + d, cy + d, cx + d, cy + d - k, ui);
                return;
            }
            if (icon == 7) {
                ui.setStyle(Paint.Style.FILL);
                canvas.drawCircle(cx, cy - 14f, 3.2f, ui);
                canvas.drawCircle(cx, cy, 3.2f, ui);
                canvas.drawCircle(cx, cy + 14f, 3.2f, ui);
                return;
            }
            if (icon == 8) {
                canvas.drawCircle(cx, cy, 19f, ui);
                canvas.drawLine(cx - 7f, cy - 7f,
                        cx + 7f, cy + 7f, ui);
                canvas.drawLine(cx + 7f, cy - 7f,
                        cx - 7f, cy + 7f, ui);
                return;
            }
        }

        private void drawPenPanel(Canvas canvas) {
            int selectedIndex = selectedPenToolIndex();
            label(canvas, penLabels[selectedIndex],
                    24f, 148f, 21f, false);
            final float left = 24f;
            final float top = 166f;
            final float width = 124f;
            final float height = 112f;
            final float gapX = 12f;
            final float gapY = 12f;
            for (int index = 0; index < penToolButtons.length; index++) {
                int column = index % 3;
                int row = index / 3;
                float x = left + column * (width + gapX);
                float y = top + row * (height + gapY);
                RectF bounds = penToolButtons[index];
                bounds.set(x, y, x + width, y + height);
                boolean selected =
                        selectedPenTool.equals(penTools[index]);
                ui.setStyle(Paint.Style.FILL);
                ui.setColor(selected
                        ? Color.rgb(149, 152, 150)
                        : Color.rgb(253, 253, 250));
                canvas.drawRect(bounds, ui);
                drawPenSample(canvas, bounds, penTools[index],
                        selected ? Color.WHITE : Color.rgb(48, 48, 45));
                ui.setTextSize(15f);
                ui.setFakeBoldText(selected);
                ui.setStyle(Paint.Style.FILL);
                ui.setColor(selected
                        ? Color.WHITE : Color.rgb(48, 48, 45));
                String name = penLabels[index];
                canvas.drawText(name,
                        bounds.centerX() - ui.measureText(name) * 0.5f,
                        bounds.bottom - 13f, ui);
                ui.setFakeBoldText(false);
            }

            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1.2f);
            ui.setColor(Color.rgb(132, 131, 126));
            canvas.drawLine(0f, 456f, PEN_PANEL_RIGHT - PEN_PANEL_LEFT,
                    456f, ui);
            label(canvas, sizeLabel(selectedPenScale),
                    24f, 490f, 19f, false);
            drawSizeChoices(canvas, false, 500f);

            canvas.drawLine(0f, 604f, PEN_PANEL_RIGHT - PEN_PANEL_LEFT,
                    604f, ui);
            final float colorTop = 614f;
            final float colorWidth = 118f;
            final float colorHeight = 82f;
            final float colorGap = 16f;
            for (int index = 0; index < colorButtons.length; index++) {
                RectF bounds = colorButtons[index];
                float x = 24f
                        + (index % 3) * (colorWidth + colorGap);
                float y = colorTop
                        + (index / 3) * (colorHeight + 4f);
                bounds.set(x, y, x + colorWidth, y + colorHeight);
                drawStockColorChoice(canvas, bounds, inkColors[index],
                        colorLabels[index],
                        selectedInkColor == inkColors[index]);
            }
            sliderBounds.setEmpty();
        }

        private void drawEraserPanel(Canvas canvas) {
            label(canvas, getString(R.string.note_eraser),
                    24f, 148f, 21f, false);
            label(canvas, eraserSizeLabel(selectedEraserWidth),
                    24f, 190f, 19f, false);

            final float previewX = 54f;
            final float previewY = 248f;
            final float previewRadius = 6f
                    + (selectedEraserWidth - ERASER_WIDTH_MIN)
                    / (ERASER_WIDTH_MAX - ERASER_WIDTH_MIN) * 22f;
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(Color.rgb(244, 244, 240));
            canvas.drawCircle(previewX, previewY, previewRadius, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(2.2f);
            ui.setColor(Color.rgb(48, 48, 45));
            canvas.drawCircle(previewX, previewY, previewRadius, ui);

            sliderBounds.set(96f, 210f,
                    ERASER_PANEL_RIGHT - ERASER_PANEL_LEFT - 24f, 286f);
            drawSlider(canvas, selectedEraserWidth,
                    ERASER_WIDTH_MIN, ERASER_WIDTH_MAX);
            for (RectF bounds : sizeButtons) {
                bounds.setEmpty();
            }

            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1.2f);
            ui.setColor(Color.rgb(132, 131, 126));
            canvas.drawLine(0f, 320f,
                    ERASER_PANEL_RIGHT - ERASER_PANEL_LEFT, 320f, ui);
            clearButton.set(0f, 320f,
                    ERASER_PANEL_RIGHT - ERASER_PANEL_LEFT, 422f);
            label(canvas, confirmClear
                            ? getString(R.string.note_clear_confirm)
                            : getString(R.string.note_clear_all),
                    74f, 382f, 20f, false);
            drawRailIcon(canvas, 42f, 371f, 6,
                    Color.rgb(48, 48, 45));
        }

        private void drawPagePanel(Canvas canvas) {
            final float width = MORE_PANEL_RIGHT - MORE_PANEL_LEFT;
            drawMenuRow(canvas, getString(R.string.note_typing),
                    106f, 194f, 0);
            drawMenuRow(canvas, getString(R.string.note_layers),
                    194f, 282f, 1);
            drawMenuRow(canvas, getString(R.string.note_convert_text),
                    282f, 370f, 2);
            drawMenuRow(canvas, getString(R.string.note_enable_shapes),
                    370f, 458f, 3);
            drawMenuRow(canvas, getString(R.string.note_page_overview),
                    458f, 546f, 4);
            drawMenuRow(canvas, getString(R.string.note_tags),
                    546f, 634f, 5);
            drawMenuRow(canvas, getString(R.string.note_search),
                    634f, 722f, 6);
            drawMenuRow(canvas, getString(R.string.note_share),
                    722f, 810f, 7);
            drawMenuRow(canvas, getString(R.string.note_settings),
                    810f, 898f, 8);
            addPageButton.set(0f, 898f, width, 1008f);
            drawMenuRow(canvas, getString(R.string.note_add_page),
                    898f, 1008f, 9);
            previousPageButton.setEmpty();
            nextPageButton.setEmpty();
            pageModeButton.setEmpty();
        }

        private void drawPenSample(Canvas canvas, RectF bounds,
                                   String tool, int color) {
            ui.setColor(color);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeCap(Paint.Cap.ROUND);
            ui.setStrokeJoin(Paint.Join.ROUND);
            float left = bounds.left + 20f;
            float right = bounds.right - 20f;
            float y = bounds.top + 43f;
            if (FINELINER.equals(tool)) {
                ui.setStrokeWidth(2.6f);
                canvas.drawLine(left, y + 5f, right, y - 5f, ui);
            } else if (PENCIL.equals(tool)) {
                ui.setStrokeWidth(2.2f);
                canvas.drawLine(left, y + 6f, right, y - 6f, ui);
                ui.setStrokeWidth(1.1f);
                canvas.drawLine(left + 2f, y + 9f,
                        right - 4f, y - 3f, ui);
                canvas.drawLine(left + 5f, y + 3f,
                        right - 1f, y - 9f, ui);
            } else if (MARKER.equals(tool)) {
                ui.setStrokeWidth(11f);
                canvas.drawLine(left, y + 6f, right, y - 6f, ui);
            } else if (HIGHLIGHTER.equals(tool)) {
                ui.setStrokeCap(Paint.Cap.SQUARE);
                ui.setStrokeWidth(18f);
                canvas.drawLine(left, y + 3f, right, y + 3f, ui);
            } else if (BRUSH.equals(tool)) {
                float span = right - left;
                ui.setStrokeWidth(2.2f);
                canvas.drawLine(left, y + 7f,
                        left + span * 0.27f, y + 2f, ui);
                ui.setStrokeWidth(6.5f);
                canvas.drawLine(left + span * 0.27f, y + 2f,
                        left + span * 0.70f, y - 4f, ui);
                ui.setStrokeWidth(3.2f);
                canvas.drawLine(left + span * 0.70f, y - 4f,
                        right, y - 7f, ui);
            } else {
                ui.setStrokeWidth(4.5f);
                canvas.drawLine(left, y + 6f, right, y - 6f, ui);
            }
        }

        private void drawSizeChoices(Canvas canvas, boolean eraser,
                                     float top) {
            final float left = 28f;
            final float width = 108f;
            final float gap = 22f;
            int selected = eraser
                    ? (selectedEraserWidth < 31f ? 0
                    : selectedEraserWidth < 56f ? 1 : 2)
                    : (selectedPenScale < 0.86f ? 0
                    : selectedPenScale < 1.28f ? 1 : 2);
            for (int index = 0; index < sizeButtons.length; index++) {
                RectF bounds = sizeButtons[index];
                float x = left + index * (width + gap);
                bounds.set(x, top, x + width, top + 88f);
                ui.setStyle(Paint.Style.FILL);
                ui.setColor(index == selected
                        ? Color.rgb(53, 58, 68)
                        : Color.rgb(253, 253, 250));
                canvas.drawRect(bounds, ui);
                ui.setStyle(Paint.Style.STROKE);
                ui.setStrokeCap(Paint.Cap.ROUND);
                ui.setStrokeWidth(3f + index * 3f);
                ui.setColor(index == selected
                        ? Color.WHITE : Color.rgb(52, 52, 49));
                canvas.drawLine(bounds.left + 25f, bounds.centerY() + 10f,
                        bounds.right - 25f, bounds.centerY() - 10f, ui);
            }
        }

        private void drawStockColorChoice(Canvas canvas, RectF bounds,
                                          int color, String name,
                                          boolean selected) {
            if (selected) {
                ui.setStyle(Paint.Style.FILL);
                ui.setColor(Color.rgb(53, 58, 68));
                canvas.drawRect(bounds, ui);
            }
            float cx = bounds.centerX();
            float cy = bounds.top + 27f;
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(color);
            canvas.drawCircle(cx, cy, 23f, ui);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(2.2f);
            ui.setColor(selected ? Color.WHITE : Color.rgb(48, 48, 45));
            canvas.drawCircle(cx, cy, 23f, ui);
            ui.setTextSize(16f);
            ui.setFakeBoldText(true);
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(selected ? Color.WHITE : Color.rgb(48, 48, 45));
            canvas.drawText(name,
                    cx - ui.measureText(name) * 0.5f,
                    bounds.bottom - 8f, ui);
            ui.setFakeBoldText(false);
        }

        private void drawMenuRow(Canvas canvas, String text,
                                 float top, float bottom, int icon) {
            final float width = MORE_PANEL_RIGHT - MORE_PANEL_LEFT;
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1.05f);
            ui.setColor(Color.rgb(171, 170, 164));
            if (icon == 4 || icon == 9) {
                canvas.drawLine(0f, top, width, top, ui);
            }
            label(canvas, text, 78f,
                    (top + bottom) * 0.5f + 8f, 19f, false);
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(2.5f);
            ui.setColor(Color.rgb(52, 52, 49));
            float cx = 40f;
            float cy = (top + bottom) * 0.5f;
            if (icon == 4) {
                canvas.drawRect(cx - 14f, cy - 14f,
                        cx - 2f, cy - 2f, ui);
                canvas.drawRect(cx + 3f, cy - 14f,
                        cx + 15f, cy - 2f, ui);
                canvas.drawRect(cx - 14f, cy + 3f,
                        cx - 2f, cy + 15f, ui);
                canvas.drawRect(cx + 3f, cy + 3f,
                        cx + 15f, cy + 15f, ui);
            } else if (icon == 9) {
                canvas.drawRect(cx - 13f, cy - 17f,
                        cx + 10f, cy + 14f, ui);
                canvas.drawLine(cx + 2f, cy + 18f,
                        cx + 18f, cy + 18f, ui);
                canvas.drawLine(cx + 14f, cy + 10f,
                        cx + 14f, cy + 25f, ui);
            } else {
                canvas.drawCircle(cx, cy, 14f, ui);
                canvas.drawLine(cx - 8f, cy + 8f,
                        cx + 8f, cy - 8f, ui);
            }
        }

        private int selectedPenToolIndex() {
            for (int index = 0; index < penTools.length; index++) {
                if (penTools[index].equals(selectedPenTool)) {
                    return index;
                }
            }
            return 0;
        }

        private String sizeLabel(float scale) {
            return getString(scale < 0.86f ? R.string.note_thin
                    : scale < 1.28f ? R.string.note_medium
                    : R.string.note_thick);
        }

        private String eraserSizeLabel(float width) {
            return getString(width < 31f ? R.string.note_thin
                    : width < 56f ? R.string.note_medium
                    : R.string.note_thick);
        }

        private void drawColorSwatch(Canvas canvas, RectF bounds, int color,
                                     boolean selected, int patternIndex) {
            ui.setStyle(Paint.Style.FILL);
            ui.setColor(color);
            canvas.drawRoundRect(bounds, 10f, 10f, ui);

            // A small monochrome code keeps adjacent hues distinguishable
            // before Gallery 3 performs its slower color settle.
            ui.setColor(colorLuminance(color) < 0.48f
                    ? Color.WHITE : Color.rgb(30, 30, 28));
            ui.setStrokeWidth(2f);
            ui.setStyle(Paint.Style.STROKE);
            float middle = bounds.centerY();
            for (int line = 0; line <= patternIndex % 4; line++) {
                float offset = (line - (patternIndex % 4) * 0.5f) * 6f;
                canvas.drawLine(bounds.left + 11f, middle + offset,
                        bounds.right - 11f, middle + offset, ui);
            }
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(selected ? 4f : 1.5f);
            ui.setColor(Color.rgb(38, 38, 35));
            canvas.drawRoundRect(bounds, 10f, 10f, ui);
            if (selected) {
                canvas.drawRoundRect(
                        bounds.left - 4f, bounds.top - 4f,
                        bounds.right + 4f, bounds.bottom + 4f,
                        13f, 13f, ui);
            }
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
            int currentRight = Math.max(
                    1, (int) toolbarRefreshRight());
            int currentBottom = Math.max(
                    1, (int) toolbarRefreshBottom() + 4);
            pendingToolbarTop = Math.min(pendingToolbarTop, top);
            pendingToolbarRight = Math.max(
                    pendingToolbarRight,
                    Math.max(renderedToolbarRight, currentRight));
            pendingToolbarBottom = Math.max(
                    pendingToolbarBottom,
                    Math.max(Math.max(renderedToolbarBottom, currentBottom),
                            bottom));
            renderedToolbarRight = currentRight;
            renderedToolbarBottom = currentBottom;
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
            int right = Math.min(getWidth(),
                    Math.max(1, pendingToolbarRight));
            pendingToolbarTop = Integer.MAX_VALUE;
            pendingToolbarRight = 0;
            pendingToolbarBottom = 0;
            toolbarRefreshAfterDrawRight = Math.max(
                    toolbarRefreshAfterDrawRight, right);
            toolbarRefreshAfterDrawBottom = Math.max(
                    toolbarRefreshAfterDrawBottom, bottom);
            invalidate(0, top, getWidth(), bottom);
            if (palettePreviewAfterRender
                    && panelExpanded && !erasing) {
                palettePreviewAfterRender = false;
                schedulePaletteColorPreview();
            } else if (!panelExpanded || erasing) {
                cancelPaletteColorPreview();
            }
        }

        private void schedulePaletteColorPreview() {
            paletteColorPreviewPending = true;
            saveHandler.removeCallbacks(deferredPaletteColorPreview);
            saveHandler.postDelayed(deferredPaletteColorPreview,
                    PALETTE_COLOR_PREVIEW_DELAY_MS);
        }

        private void cancelPaletteColorPreview() {
            palettePreviewAfterRender = false;
            paletteColorPreviewPending = false;
            saveHandler.removeCallbacks(deferredPaletteColorPreview);
        }

        private void requestPaletteColorPreview() {
            if (!paletteColorPreviewPending || !panelExpanded || erasing
                    || drawing || activityPaused
                    || colorSettlePreparing || colorSettleWindowOpen) {
                return;
            }
            if (stylusInRange) {
                return;
            }
            paletteColorPreviewPending = false;
            paletteColorPreviewActive = true;
            colorSettlePending = true;
            colorSettlePreparing = true;
            /*
             * The palette is normally painted by the fast monochrome path,
             * which makes every hue look like a similar grey dot.  Once the
             * enlarged swatches have reached SurfaceFlinger, briefly release
             * Note ownership and ask the bridge for one Gallery 3 settle.
             * Do not issue the normal full refresh here: it would make merely
             * opening the palette flash the whole page.
            */
            writeNativePenControl(NOTE_OVERLAY_RESET_FILE, "reset");
            // Pre-arm the one-shot while Note still owns pen input.  The
            // bridge may prepare its retained RGB region now, but cannot
            // submit Gallery 3 until requestPreparedColorSettle() briefly
            // removes NOTE_ACTIVE_FILE.  This overlaps the bridge's control
            // poll with the panel-only frame staging below.
            writeNativePenControl(COLOR_MODE_FILE, "once-auto");
            invalidatePalettePreviewRegion();
            /*
             * The bridge consumes an overlay reset on the next HWC frame.
             * A single invalidate can race ahead of its file poll and leave
             * Gallery 3 waiting several seconds for another unrelated frame.
             * These small panel-only frames close that race without flashing
             * or repainting the full note page.
             */
            postDelayed(this::invalidatePalettePreviewRegion, 80L);
            postDelayed(this::invalidatePalettePreviewRegion, 180L);
            postDelayed(this::invalidatePalettePreviewRegion, 300L);
            saveHandler.removeCallbacks(deferredColorRequest);
            saveHandler.postDelayed(deferredColorRequest,
                    PALETTE_COLOR_STAGE_DELAY_MS);
            Log.i(TAG, "palette color preview prepared");
        }

        private void invalidatePalettePreviewRegion() {
            invalidate((int) PEN_PANEL_LEFT, 600,
                    (int) PEN_PANEL_RIGHT, (int) PEN_PANEL_BOTTOM);
        }

        private void stopPaletteColorPreviewForWriting() {
            cancelPaletteColorPreview();
            if (!paletteColorPreviewActive) {
                return;
            }
            saveHandler.removeCallbacks(deferredColorPrepare);
            saveHandler.removeCallbacks(deferredColorRequest);
            saveHandler.removeCallbacks(deferredColorPoll);
            writeNativePenControl(COLOR_MODE_FILE, "auto");
            writeNativePenControl(NOTE_ACTIVE_FILE, "active");
            colorSettlePending = false;
            colorSettlePreparing = false;
            colorSettleWindowOpen = false;
            paletteColorPreviewActive = false;
            Log.i(TAG, "palette preview stopped; native pen ready");
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
                    pauseColorSettleForPen();
                    if (isToolbarPoint(event.getX(), event.getY())) {
                        // Arm before pen-down so the current deployed bridge
                        // reads a full-contact exclusion at stroke start.
                        armNativeUiCapture();
                    } else if (!uiGestureCaptured) {
                        disarmNativeUiCapture();
                    }
                    /*
                     * Hover is not writing.  Keep a pending post-stroke color
                     * settle alive so users do not have to move the Marker
                     * completely away from the panel.  A real ACTION_DOWN
                     * still cancels it before a new stroke starts.
                     */
                    cancelPendingAutoSave();
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    stylusInRange = false;
                    if (!uiGestureCaptured) {
                        disarmNativeUiCapture();
                    }
                    if (documentDirty) {
                        scheduleAutoSave();
                    }
                    if (paletteColorPreviewPending
                            && panelExpanded && !erasing) {
                        schedulePaletteColorPreview();
                    }
                    scheduleColorSettleIfReady();
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
            final float panelX = x - activePanelLeft();
            final int action = event.getActionMasked();

            if (dismissPanelGesture) {
                if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    dismissPanelGesture = false;
                }
                return true;
            }

            /*
             * A toolbar/panel contact is captured from ACTION_DOWN until its
             * matching UP/CANCEL.  It must never fall through into the page
             * stroke pipeline even if the pointer slides outside the button.
             */
            if (action == MotionEvent.ACTION_DOWN) {
                int uiTarget = hitUiTarget(x, y);
                if (uiTarget != UI_TARGET_NONE) {
                    beginUiGesture(uiTarget, x, y);
                    return true;
                }
            }
            if (uiGestureCaptured) {
                if (capturedUiTarget == UI_TARGET_SLIDER
                        && action == MotionEvent.ACTION_MOVE) {
                    updateSlider(panelX);
                }
                if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    finishUiGesture(
                            action == MotionEvent.ACTION_CANCEL, x);
                }
                return true;
            }

            if ((panelExpanded || pagePanelExpanded)
                    && action == MotionEvent.ACTION_DOWN
                    && !isToolbarPoint(x, y)) {
                int previousBottom = (int) toolbarRefreshBottom() + 2;
                panelExpanded = false;
                pagePanelExpanded = false;
                stopPaletteColorPreviewForWriting();
                sliderDragging = false;
                confirmClear = false;
                dismissPanelGesture = true;
                syncNativeTool();
                scheduleToolbarRender(0, previousBottom);
                return true;
            }

            if (!isStylusEvent(event)
                    && action == MotionEvent.ACTION_POINTER_DOWN
                    && !isToolbarPoint(x, y)) {
                multiTouchGesture = true;
                gesturePointerCount = Math.max(
                        gesturePointerCount, event.getPointerCount());
                fingerTracking = false;
                return true;
            }

            if (!isStylusEvent(event)
                    && multiTouchGesture
                    && action == MotionEvent.ACTION_UP) {
                if (gesturePointerCount >= 3) {
                    redo();
                } else if (gesturePointerCount == 2) {
                    undo();
                }
                multiTouchGesture = false;
                gesturePointerCount = 0;
                return true;
            }

            if (isToolbarPoint(x, y) || pageCanvas == null) {
                return true;
            }
            if (!isStylusEvent(event)) {
                if (action == MotionEvent.ACTION_DOWN) {
                    fingerTracking = true;
                    multiTouchGesture = false;
                    gesturePointerCount = 1;
                    fingerDownX = x;
                    fingerDownY = y;
                    return true;
                }
                if (action == MotionEvent.ACTION_UP && fingerTracking) {
                    fingerTracking = false;
                    handlePageGesture(x - fingerDownX, y - fingerDownY);
                    drawing = false;
                    currentStroke = null;
                    return true;
                }
                if (action == MotionEvent.ACTION_CANCEL) {
                    fingerTracking = false;
                    multiTouchGesture = false;
                    gesturePointerCount = 0;
                    drawing = false;
                    currentStroke = null;
                }
                return true;
            }

            stylusInRange = true;
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    disarmNativeUiCapture();
                    pauseColorSettleForPen();
                    cancelPendingAutoSave();
                    requestUnbufferedDispatch(event);
                    beginNewStroke(event);
                    appendPoint(
                            x, y,
                            pressureOf(event, 0),
                            orientationOf(event, 0),
                            tiltOf(event, 0));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    cancelPendingAutoSave();
                    requestUnbufferedDispatch(event);
                    if (!drawing || currentStroke == null) {
                        beginNewStroke(event);
                    }
                    for (int index = 0; index < event.getHistorySize();
                         index++) {
                        appendPoint(
                                event.getHistoricalX(0, index),
                                event.getHistoricalY(0, index),
                                pressureOf(event, index + 1),
                                orientationOf(event, index + 1),
                                tiltOf(event, index + 1));
                    }
                    appendPoint(
                            x, y,
                            pressureOf(event, 0),
                            orientationOf(event, 0),
                            tiltOf(event, 0));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (drawing && currentStroke != null) {
                        appendPoint(
                                x, y,
                                pressureOf(event, 0),
                                orientationOf(event, 0),
                                tiltOf(event, 0));
                        renderCurrentStroke();
                        flushStrokeDirty();
                        commitCurrentStroke();
                    }
                    drawing = false;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (currentStroke != null
                            && !currentStroke.points.isEmpty()) {
                        renderCurrentStroke();
                        flushStrokeDirty();
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
                        Float.toString(effectiveSelectedPenScale()));
            }
            scheduleToolbarRender(
                    0, (int) activePanelBottom() + 2);
        }

        private void handlePageGesture(float deltaX, float deltaY) {
            if (drawing) {
                return;
            }
            if (pageMode == PAGE_MODE_HORIZONTAL) {
                if (Math.abs(deltaX) < PAGE_SWIPE_THRESHOLD
                        || Math.abs(deltaX) < Math.abs(deltaY)) {
                    return;
                }
                switchToPage(currentPageIndex + (deltaX < 0f ? 1 : -1));
            } else {
                if (Math.abs(deltaY) < PAGE_SWIPE_THRESHOLD
                        || Math.abs(deltaY) < Math.abs(deltaX)) {
                    return;
                }
                switchToPage(currentPageIndex + (deltaY < 0f ? 1 : -1));
            }
        }

        private void addPage() {
            if (drawing) {
                return;
            }
            pageCount++;
            persistPagePreferences();
            switchToPage(pageCount - 1);
        }

        private void switchToPage(int targetPage) {
            if (targetPage < 0 || targetPage >= pageCount
                    || targetPage == currentPageIndex || drawing
                    || getWidth() <= 0 || getHeight() <= 0) {
                return;
            }
            cancelPendingAutoSave();
            if (documentDirty) {
                enqueueSaveSnapshot();
            }
            currentPageIndex = targetPage;
            selectPageFiles(currentPageIndex);
            loadCurrentPage(getWidth(), getHeight());
            persistPagePreferences();
            requestNativeOverlayReset();
            syncNativeTool();
            invalidate();
            postDelayed(this::invalidate, 100L);
        }

        private void persistPagePreferences() {
            noteMetadata.edit()
                    .putInt(noteId + ".page-count", pageCount)
                    .putInt(noteId + ".page-mode", pageMode)
                    .apply();
        }

        private void beginNewStroke(MotionEvent event) {
            drawing = true;
            currentStroke = new StrokeCommand(
                    effectivePenTool(selectedPenTool),
                    effectiveSelectedPenScale(),
                    isEraser(event),
                    selectedEraserWidth,
                    selectedInkColor);
            beginStrokeDirty();
        }

        private void appendPoint(float x, float y, float pressure,
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
            trackStrokeDirty(currentStroke, previous, next);
        }

        private void renderCurrentStroke() {
            if (pageCanvas == null || currentStroke == null
                    || currentStroke.points.isEmpty()) {
                return;
            }
            /*
             * Android 12's proven low-latency design gives the physical pen
             * stream to rm-epd-bridge while contact is down. Do the same on
             * Android 16: collect inexpensive vector points here, then draw
             * the durable app bitmap once at lift. Rendering every Pencil or
             * Marker texture sample on the UI thread made SurfaceFlinger and
             * the native panel worker compete for the small SoC and was the
             * main regression from the v31 path.
             *
             * The native overlay remains the live visual feedback. This one
             * batched bitmap update catches Android up before the overlay is
             * ever removed, so undo, save and reload retain the exact styled
             * stroke without producing hundreds of HWC frames per stroke.
             */
            currentStroke.draw(pageCanvas);
        }

        private void trackStrokeDirty(StrokeCommand stroke,
                                      InkPoint from,
                                      InkPoint to) {
            float pressure = (from.pressure + to.pressure) * 0.5f;
            float distance = (float) Math.hypot(
                    to.x - from.x, to.y - from.y);
            boolean strokeStart = distance < 0.01f;
            float width = stroke.eraser
                    ? stroke.eraserWidth
                    : penWidth(stroke.tool, pressure, stroke.penScale,
                            distance, strokeStart);
            if (!stroke.eraser && BRUSH.equals(stroke.tool)) {
                float averageTilt = (from.tilt + to.tilt) * 0.5f;
                width *= 1f + Math.min(0.36f, averageTilt * 0.24f);
            }
            if (!stroke.eraser && PENCIL.equals(stroke.tool)) {
                width *= 1.45f;
            } else if (!stroke.eraser && MARKER.equals(stroke.tool)) {
                width *= 1.2f;
            }
            int padding = (int) Math.ceil(width * 0.75f + 6f);
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

        private void commitCurrentStroke() {
            if (currentStroke == null || currentStroke.points.isEmpty()) {
                currentStroke = null;
                return;
            }
            final boolean needsColorSettle =
                    !currentStroke.eraser
                            && (isChromaticInkColor(currentStroke.color)
                                || HIGHLIGHTER.equals(currentStroke.tool));
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
            if (needsColorSettle) {
                colorSettlePending = true;
                scheduleColorSettleIfReady();
            }
        }

        private boolean isChromaticInkColor(int color) {
            int maximum = Math.max(Color.red(color),
                    Math.max(Color.green(color), Color.blue(color)));
            int minimum = Math.min(Color.red(color),
                    Math.min(Color.green(color), Color.blue(color)));
            return maximum - minimum >= 18;
        }

        private void scheduleColorSettleIfReady() {
            saveHandler.removeCallbacks(deferredColorPrepare);
            if (!colorSettlePending || drawing || stylusInRange
                    || activityPaused || colorSettlePreparing
                    || colorSettleWindowOpen) {
                return;
            }
            saveHandler.postDelayed(
                    deferredColorPrepare, COLOR_SETTLE_IDLE_DELAY_MS);
        }

        private void prepareColorSettle() {
            if (!colorSettlePending || drawing || stylusInRange
                    || activityPaused) {
                return;
            }
            colorSettlePreparing = true;
            /*
             * The Android frame already contains RGB, but native FastPen has
             * a monochrome copy of the same stroke. Clear that overlay first;
             * otherwise it is composited over Gallery 3 as a black stroke.
             */
            requestNativeOverlayReset();
            invalidate();
            postDelayed(this::invalidate, 80L);
            postDelayed(this::invalidate, 220L);
            saveHandler.removeCallbacks(deferredColorRequest);
            saveHandler.postDelayed(
                    deferredColorRequest, COLOR_RESET_STAGE_DELAY_MS);
        }

        private void requestPreparedColorSettle() {
            if (!colorSettlePending || drawing || stylusInRange
                    || activityPaused) {
                colorSettlePreparing = false;
                scheduleColorSettleIfReady();
                return;
            }
            colorSettlePreparing = false;
            colorSettleWindowOpen = true;
            colorSettleStartedAt = System.currentTimeMillis();
            new File(getFilesDir(), NOTE_ACTIVE_FILE).delete();
            writeNativePenControl(COLOR_MODE_FILE, "once-auto");
            saveHandler.removeCallbacks(deferredColorPoll);
            saveHandler.postDelayed(
                    deferredColorPoll, COLOR_SETTLE_POLL_MS);
            Log.i(TAG, "requested color settle after native overlay reset");
        }

        private void pollColorSettle() {
            if (!colorSettleWindowOpen) return;
            String mode = readNativePenControl(COLOR_MODE_FILE);
            boolean timedOut = System.currentTimeMillis()
                    - colorSettleStartedAt >= COLOR_SETTLE_TIMEOUT_MS;
            if ("auto".equals(mode) || timedOut) {
                colorSettleWindowOpen = false;
                colorSettlePending = false;
                paletteColorPreviewActive = false;
                writeNativePenControl(COLOR_MODE_FILE, "auto");
                if (!activityPaused) {
                    writeNativePenControl(NOTE_ACTIVE_FILE, "active");
                }
                Log.i(TAG, timedOut
                        ? "color settle timed out; native pen restored"
                        : "color settle complete; native pen restored");
                return;
            }
            saveHandler.postDelayed(deferredColorPoll, COLOR_SETTLE_POLL_MS);
        }

        private void pauseColorSettleForPen() {
            saveHandler.removeCallbacks(deferredColorPrepare);
            saveHandler.removeCallbacks(deferredColorRequest);
            saveHandler.removeCallbacks(deferredColorPoll);
            if (colorSettlePreparing || colorSettleWindowOpen) {
                writeNativePenControl(COLOR_MODE_FILE, "auto");
                if (!activityPaused) {
                    writeNativePenControl(NOTE_ACTIVE_FILE, "active");
                }
            }
            colorSettlePreparing = false;
            colorSettleWindowOpen = false;
            if (paletteColorPreviewActive) {
                paletteColorPreviewActive = false;
                colorSettlePending = false;
            }
        }

        private void restoreColorSettleState() {
            cancelColorSettleCallbacks(true);
        }

        private void cancelColorSettleCallbacks(boolean restoreNativePen) {
            cancelPaletteColorPreview();
            saveHandler.removeCallbacks(deferredColorPrepare);
            saveHandler.removeCallbacks(deferredColorRequest);
            saveHandler.removeCallbacks(deferredColorPoll);
            writeNativePenControl(COLOR_MODE_FILE, "auto");
            colorSettlePreparing = false;
            colorSettleWindowOpen = false;
            if (restoreNativePen && !activityPaused) {
                writeNativePenControl(NOTE_ACTIVE_FILE, "active");
            }
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
            invalidate((int) undoButton.left - 3,
                    (int) undoButton.top - 3,
                    (int) redoButton.right + 3,
                    (int) redoButton.bottom + 3);
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
                        target, from, to, width, pressure, stroke.color);
                visualWidth = width * 1.45f;
            } else if (MARKER.equals(stroke.tool)) {
                drawMarkerSegment(
                        target, from, to, width,
                        blendWithWhite(stroke.color, 0.42f));
                visualWidth = width * 1.2f;
            } else if (HIGHLIGHTER.equals(stroke.tool)) {
                drawHighlighterSegment(target, from, to, width, stroke.color);
            } else {
                drawRoundSegment(target, from, to, width,
                        stroke.color);
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
                                       float pressure,
                                       int color) {
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
                    148 - (int) (pressure * 34f), distance, color);
            drawPencilStrand(target, from, to, normalX, normalY,
                    jitter * 0.22f,
                    Math.max(0.58f, width * 0.52f),
                    78 - (int) (pressure * 42f), distance, color);
            drawPencilStrand(target, from, to, normalX, normalY,
                    width * 0.38f + jitter,
                    Math.max(0.42f, width * 0.23f),
                    174 - (int) (pressure * 30f), distance, color);
        }

        private void drawPencilStrand(Canvas target,
                                      InkPoint from,
                                      InkPoint to,
                                      float normalX,
                                      float normalY,
                                      float offset,
                                      float strandWidth,
                                      int gray,
                                      float distance,
                                      int color) {
            float offsetX = normalX * offset;
            float offsetY = normalY * offset;
            ink.setColor(tintPencilColor(color, gray));
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

        private void drawHighlighterSegment(Canvas target,
                                            InkPoint from,
                                            InkPoint to,
                                            float width,
                                            int color) {
            int translucentColor = Color.argb(
                    112, Color.red(color), Color.green(color),
                    Color.blue(color));
            ink.setStyle(Paint.Style.STROKE);
            ink.setColor(translucentColor);
            ink.setStrokeCap(Paint.Cap.SQUARE);
            ink.setStrokeJoin(Paint.Join.BEVEL);
            ink.setStrokeWidth(width);
            if (Math.abs(to.x - from.x) < 0.01f
                    && Math.abs(to.y - from.y) < 0.01f) {
                ink.setStyle(Paint.Style.FILL);
                target.drawRect(to.x - width * 0.5f,
                        to.y - width * 0.5f,
                        to.x + width * 0.5f,
                        to.y + width * 0.5f, ink);
            } else {
                target.drawLine(from.x, from.y, to.x, to.y, ink);
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
            } else if (HIGHLIGHTER.equals(tool)) {
                // A highlighter is a broad, pressure-independent chisel.
                base = 26f;
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

        private String normalizeLegacyPenTool(String tool) {
            if (MECHANICAL.equals(tool) || SHADER.equals(tool)) {
                return PENCIL;
            }
            if (CALLIGRAPHY.equals(tool)) {
                return BRUSH;
            }
            return tool;
        }

        private int normalizeLegacyInkColor(int color) {
            if (color == 0xff23528b || color == 0xff154b9c
                    || color == 0xff0b347a) {
                return 0xff145fd0;
            }
            if (color == 0xffa33a34 || color == 0xffad241e
                    || color == 0xff7f1714) {
                return 0xffd12b22;
            }
            if (color == 0xff3f7048 || color == 0xff23743b
                    || color == 0xff15552a) {
                return 0xff168044;
            }
            if (color == 0xffd1b532 || color == 0xffb78a00
                    || color == 0xff805800) {
                return 0xffd99f00;
            }
            if (color == 0xff2f7f9e || color == 0xff087f9c
                    || color == 0xff07546b) {
                return 0xff008eae;
            }
            if (color == 0xff9c4f86 || color == 0xff9b2d78
                    || color == 0xff6f1d59) {
                return 0xffae297d;
            }
            return color;
        }

        private float effectiveSelectedPenScale() {
            if (HIGHLIGHTER.equals(selectedPenTool)) {
                return selectedPenScale;
            }
            if (!isChromaticInkColor(selectedInkColor)) {
                return selectedPenScale;
            }
            // Gallery 3 colored pigment has less apparent edge contrast than
            // black. Keep the UI's Thin/Medium/Thick intent while giving
            // colored strokes enough panel coverage to remain legible.
            float boost = selectedInkColor == 0xffd99f00 ? 1.55f : 1.40f;
            return Math.min(PEN_SCALE_MAX, selectedPenScale * boost);
        }

        private String effectivePenTool(String tool) {
            return normalizeLegacyPenTool(tool);
        }

        private String nativePenTool(String tool) {
            String normalized = normalizeLegacyPenTool(tool);
            // The currently deployed native FastPen bridge has no translucent
            // compositor. Use its broad Marker trace while contact is down;
            // the app replaces it with the durable highlighter stroke at lift.
            return HIGHLIGHTER.equals(normalized) ? MARKER : normalized;
        }

        private boolean isKnownInkColor(int color) {
            for (int value : inkColors) {
                if (value == color) {
                    return true;
                }
            }
            return false;
        }

        private int selectedInkColorIndex() {
            for (int index = 0; index < inkColors.length; index++) {
                if (inkColors[index] == selectedInkColor) {
                    return index;
                }
            }
            return 0;
        }

        private float colorLuminance(int color) {
            return (Color.red(color) * 0.2126f
                    + Color.green(color) * 0.7152f
                    + Color.blue(color) * 0.0722f) / 255f;
        }

        private int blendWithWhite(int color, float whiteAmount) {
            float amount = clamp(whiteAmount, 0f, 1f);
            int red = Math.round(Color.red(color) * (1f - amount)
                    + 255f * amount);
            int green = Math.round(Color.green(color) * (1f - amount)
                    + 255f * amount);
            int blue = Math.round(Color.blue(color) * (1f - amount)
                    + 255f * amount);
            return Color.rgb(red, green, blue);
        }

        private int tintPencilColor(int color, int gray) {
            float whiteAmount = clamp(gray / 255f, 0f, 0.82f);
            return blendWithWhite(color, whiteAmount);
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
                    .putInt("ink-color", selectedInkColor)
                    .putBoolean("eraser-active", erasing)
                    .apply();
        }

        void syncNativeTool() {
            writeNativePenControl(NOTE_TOOL_FILE,
                    erasing ? "erase" : nativePenTool(selectedPenTool));
            writeNativePenControl(
                    NOTE_SIZE_FILE,
                    Float.toString(effectiveSelectedPenScale()));
            writeNativePenControl(NOTE_ERASER_SIZE_FILE,
                    Float.toString(selectedEraserWidth));
            writeNativePenControl(NOTE_UI_BOTTOM_FILE,
                    Integer.toString((int) toolbarBottom() + 4));
            writeNativePenControl(NOTE_UI_LEFT_FILE,
                    Integer.toString((int) uiLeft() + 4));
            writeNativePenControl(NOTE_UI_REGIONS_FILE, nativeUiRegions());
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
                    tool = normalizeLegacyPenTool(tool);
                    if (!isKnownPenTool(tool)) {
                        tool = BALLPOINT;
                    }
                    float penScale = input.readFloat();
                    boolean eraser = input.readBoolean();
                    float eraserWidth = input.readFloat() * widthScale;
                    int color = version >= 3
                            ? input.readInt() : DEFAULT_INK_COLOR;
                    if (!isKnownInkColor(color)) {
                        color = DEFAULT_INK_COLOR;
                    }
                    StrokeCommand stroke = new StrokeCommand(
                            tool, penScale, eraser, eraserWidth, color);
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
                        output.writeInt(stroke.color);
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
            SaveRequest request = new SaveRequest(
                    snapshot, vectorSnapshot, snapshotGeneration,
                    currentPageIndex, noteFile, noteVectorFile);
            boolean startWorker = false;
            synchronized (saveLock) {
                SaveRequest previous = saveQueue.peekLast();
                if (previous != null
                        && previous.rasterTarget.equals(request.rasterTarget)) {
                    saveQueue.removeLast();
                    previous.snapshot.recycle();
                }
                saveQueue.addLast(request);
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
                SaveRequest request;
                synchronized (saveLock) {
                    request = saveQueue.pollFirst();
                    if (request == null) {
                        saveWorkerRunning = false;
                        return;
                    }
                }
                if (saveSnapshot(request)) {
                    final long savedGeneration = request.generation;
                    final int savedPageIndex = request.pageIndex;
                    saveHandler.post(() -> {
                        if (currentPageIndex == savedPageIndex
                                && documentGeneration == savedGeneration) {
                            documentDirty = false;
                        }
                    });
                }
            }
        }

        private boolean saveSnapshot(SaveRequest request) {
            Bitmap snapshot = request.snapshot;
            byte[] vectorSnapshot = request.vectorSnapshot;
            File target = request.rasterTarget;
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
                    vectorSnapshot, request.vectorTarget)) {
                return false;
            }
            SharedPreferences.Editor metadataEdit = noteMetadata.edit()
                    .putInt(noteId + ".page-count", pageCount)
                    .putInt(noteId + ".page-mode", pageMode);
            if (!"quick-note".equals(noteId)) {
                metadataEdit
                        .putString(noteId + ".title", noteTitle)
                        .putString(noteId + ".template", noteTemplate)
                        .putLong(noteId + ".updated",
                                System.currentTimeMillis());
            }
            metadataEdit.apply();
            return true;
        }

        private boolean writeVectorSnapshot(byte[] data, File target) {
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

        private final class SaveRequest {
            final Bitmap snapshot;
            final byte[] vectorSnapshot;
            final long generation;
            final int pageIndex;
            final File rasterTarget;
            final File vectorTarget;

            SaveRequest(Bitmap snapshot, byte[] vectorSnapshot,
                        long generation, int pageIndex,
                        File rasterTarget, File vectorTarget) {
                this.snapshot = snapshot;
                this.vectorSnapshot = vectorSnapshot;
                this.generation = generation;
                this.pageIndex = pageIndex;
                this.rasterTarget = rasterTarget;
                this.vectorTarget = vectorTarget;
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
            final int color;
            final ArrayList<InkPoint> points = new ArrayList<>();

            StrokeCommand(String tool, float penScale,
                          boolean eraser, float eraserWidth, int color) {
                this.tool = tool;
                this.penScale = penScale;
                this.eraser = eraser;
                this.eraserWidth = eraserWidth;
                this.color = color;
            }

            @Override
            void draw(Canvas target) {
                if (points.isEmpty()) {
                    return;
                }
                if (HIGHLIGHTER.equals(tool)) {
                    float width = penWidth(
                            tool, 0.5f, penScale, 0f, false);
                    if (points.size() == 1) {
                        InkPoint point = points.get(0);
                        drawHighlighterSegment(
                                target, point, point, width, color);
                        return;
                    }
                    Path highlighterPath = new Path();
                    InkPoint first = points.get(0);
                    highlighterPath.moveTo(first.x, first.y);
                    for (int index = 1; index < points.size(); index++) {
                        InkPoint point = points.get(index);
                        highlighterPath.lineTo(point.x, point.y);
                    }
                    ink.setStyle(Paint.Style.STROKE);
                    ink.setColor(Color.argb(
                            112, Color.red(color), Color.green(color),
                            Color.blue(color)));
                    ink.setStrokeCap(Paint.Cap.SQUARE);
                    ink.setStrokeJoin(Paint.Join.BEVEL);
                    ink.setStrokeWidth(width);
                    target.drawPath(highlighterPath, ink);
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
