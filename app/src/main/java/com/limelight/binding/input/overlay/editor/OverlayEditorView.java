package com.limelight.binding.input.overlay.editor;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.binding.input.overlay.ButtonStyle;
import com.limelight.binding.input.overlay.EditableElement;
import com.limelight.binding.input.overlay.EditableOverlay;
import com.limelight.binding.input.overlay.ElementEditState;
import com.limelight.binding.input.overlay.LayoutSlots;
import com.limelight.binding.input.overlay.OverlayEditorSettings;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The input overlay layout editor, shared by the gamepad and keyboard overlays.
 *
 * It is laid over the frame layout that hosts the overlays, either from the settings
 * (standalone editor activity) or while streaming (the overlay's configure button).
 * While it is open, the overlays don't send any input.
 */
public class OverlayEditorView extends FrameLayout implements EditorCanvasView.Callbacks {

    public interface Listener {
        void onEditorClosed(boolean saved);
    }

    private static final String PREF_SNAP = OverlayEditorSettings.EDITOR_SNAP;
    private static final String PREF_GRID = OverlayEditorSettings.EDITOR_GRID;
    /** "auto" (dock where it covers the fewest controls), "top" or "bottom". */
    private static final String PREF_TOOLBAR_POSITION = OverlayEditorSettings.EDITOR_TOOLBAR_POSITION;

    private static final int SIZE_MIN_PERCENT = 30;
    private static final int SIZE_MAX_PERCENT = 300;
    private static final int OPACITY_MIN_PERCENT = 10;

    private final List<EditableOverlay> overlays;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Map<EditableOverlay, EditorHistory> histories = new HashMap<>();
    /** State as last loaded / saved, to know if there are unsaved changes. */
    private final Map<EditableOverlay, LayoutSnapshot> savedStates = new HashMap<>();
    private EditableOverlay active;
    private LayoutSnapshot gestureBefore;
    private LayoutSnapshot sliderBefore;
    private boolean updatingPanel;
    private boolean closed;
    private boolean toolbarAtBottom;

    private EditorCanvasView canvas;
    private View toolbar;
    private TextView tabGamepad, tabKeyboard, slotButton, undoButton, redoButton, addButton;
    private LinearLayout panel;
    private TextView panelTitle, panelVisible, panelLock, panelRemove;
    private View rowSize, rowWidth, rowHeight;
    private SeekBar sizeBar, widthBar, heightBar, opacityBar;
    private TextView sizeValue, widthValue, heightValue, opacityValue;

    /**
     * Opens the editor on top of the frame layout hosting the overlays.
     *
     * @param initial overlay shown first (may be null)
     */
    public static OverlayEditorView open(FrameLayout host, List<EditableOverlay> overlays,
                                         EditableOverlay initial, Listener listener) {
        OverlayEditorView editor = new OverlayEditorView(host.getContext(), overlays, listener);
        host.addView(editor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        editor.start(initial != null ? initial : overlays.get(0));
        return editor;
    }

    private OverlayEditorView(Context context, List<EditableOverlay> overlays, Listener listener) {
        super(context);
        this.overlays = new ArrayList<>(overlays);
        this.listener = listener;
        this.prefs = PreferenceManager.getDefaultSharedPreferences(context);

        setClickable(true);
        setFocusable(true);
        LayoutInflater.from(context).inflate(R.layout.overlay_editor, this, true);
        bindViews();
    }

    // ------------------------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------------------------

    private void bindViews() {
        canvas = findViewById(R.id.oe_canvas);
        canvas.setCallbacks(this);
        canvas.setHint(getContext().getString(R.string.overlay_editor_hint));

        toolbar = findViewById(R.id.oe_toolbar);
        tabGamepad = findViewById(R.id.oe_tab_gamepad);
        tabKeyboard = findViewById(R.id.oe_tab_keyboard);
        slotButton = findViewById(R.id.oe_slot);
        undoButton = findViewById(R.id.oe_undo);
        redoButton = findViewById(R.id.oe_redo);
        addButton = findViewById(R.id.oe_add);

        panel = findViewById(R.id.oe_panel);
        panelTitle = findViewById(R.id.oe_panel_title);
        panelVisible = findViewById(R.id.oe_panel_visible);
        panelLock = findViewById(R.id.oe_panel_lock);
        panelRemove = findViewById(R.id.oe_panel_remove);
        rowSize = findViewById(R.id.oe_row_size);
        rowWidth = findViewById(R.id.oe_row_width);
        rowHeight = findViewById(R.id.oe_row_height);
        sizeBar = findViewById(R.id.oe_size);
        widthBar = findViewById(R.id.oe_width);
        heightBar = findViewById(R.id.oe_height);
        opacityBar = findViewById(R.id.oe_opacity);
        sizeValue = findViewById(R.id.oe_size_value);
        widthValue = findViewById(R.id.oe_width_value);
        heightValue = findViewById(R.id.oe_height_value);
        opacityValue = findViewById(R.id.oe_opacity_value);

        findViewById(R.id.oe_close).setOnClickListener(v -> requestClose());
        findViewById(R.id.oe_save).setOnClickListener(v -> saveAndClose());
        findViewById(R.id.oe_save).setSelected(true);
        tabGamepad.setOnClickListener(v -> switchTo(findOverlay(LayoutSlots.Kind.GAMEPAD)));
        tabKeyboard.setOnClickListener(v -> switchTo(findOverlay(LayoutSlots.Kind.KEYBOARD)));
        slotButton.setOnClickListener(this::showSlotMenu);
        undoButton.setOnClickListener(v -> undo());
        redoButton.setOnClickListener(v -> redo());
        addButton.setOnClickListener(v -> showAddDialog());
        findViewById(R.id.oe_options).setOnClickListener(v -> showOptionsDialog());
        findViewById(R.id.oe_more).setOnClickListener(this::showMoreMenu);

        panelVisible.setOnClickListener(v -> editSelected(e -> e.getEditState().shown = !e.getEditState().shown));
        panelLock.setOnClickListener(v -> editSelected(e -> e.getEditState().lockAspect = !e.getEditState().lockAspect));
        findViewById(R.id.oe_panel_reset).setOnClickListener(v ->
                editSelected(e -> e.getEditState().resetToDefault(e.getView())));
        panelRemove.setOnClickListener(v -> {
            EditableElement element = canvas.getSelected();
            editSelected(e -> e.getEditState().removed = true);
            if (element != null) {
                select(null);
                Toast.makeText(getContext(), getContext().getString(R.string.overlay_editor_removed_toast,
                        element.getDisplayName()), Toast.LENGTH_SHORT).show();
            }
        });
        findViewById(R.id.oe_panel_done).setOnClickListener(v -> select(null));

        setupNudge(R.id.oe_nudge_left, -1, 0);
        setupNudge(R.id.oe_nudge_right, 1, 0);
        setupNudge(R.id.oe_nudge_up, 0, -1);
        setupNudge(R.id.oe_nudge_down, 0, 1);

        alignSliderLabels();

        sizeBar.setMax(SIZE_MAX_PERCENT - SIZE_MIN_PERCENT);
        widthBar.setMax(SIZE_MAX_PERCENT - SIZE_MIN_PERCENT);
        heightBar.setMax(SIZE_MAX_PERCENT - SIZE_MIN_PERCENT);
        opacityBar.setMax(100 - OPACITY_MIN_PERCENT);
        sizeBar.setOnSeekBarChangeListener(new SliderListener(SliderListener.SIZE));
        widthBar.setOnSeekBarChangeListener(new SliderListener(SliderListener.WIDTH));
        heightBar.setOnSeekBarChangeListener(new SliderListener(SliderListener.HEIGHT));
        opacityBar.setOnSeekBarChangeListener(new SliderListener(SliderListener.OPACITY));
    }

    /** Gives every slider label the width of the longest one, so that the sliders line up in any language. */
    private void alignSliderLabels() {
        List<TextView> labels = new ArrayList<>();
        for (View row : new View[]{rowSize, rowWidth, rowHeight, (View) opacityBar.getParent()}) {
            labels.add((TextView) ((ViewGroup) row).getChildAt(0));
        }
        int width = 0;
        for (TextView label : labels) {
            label.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            width = Math.max(width, label.getMeasuredWidth());
        }
        for (TextView label : labels) {
            label.setMinWidth(width);
        }
    }

    private void start(EditableOverlay initial) {
        for (EditableOverlay overlay : overlays) {
            overlay.setEditing(true);
            histories.put(overlay, new EditorHistory());
            savedStates.put(overlay, LayoutSnapshot.capture(overlay));
        }
        tabGamepad.setVisibility(findOverlay(LayoutSlots.Kind.GAMEPAD) != null ? VISIBLE : GONE);
        tabKeyboard.setVisibility(findOverlay(LayoutSlots.Kind.KEYBOARD) != null ? VISIBLE : GONE);
        applyToggles();
        switchTo(initial);
        requestFocus();
    }

    private EditableOverlay findOverlay(LayoutSlots.Kind kind) {
        for (EditableOverlay overlay : overlays) {
            if (overlay.getKind() == kind) {
                return overlay;
            }
        }
        return null;
    }

    private void applyToggles() {
        canvas.setSnapEnabled(prefs.getBoolean(PREF_SNAP, true));
        canvas.setGridEnabled(prefs.getBoolean(PREF_GRID, false));
    }

    private void switchTo(EditableOverlay overlay) {
        if (overlay == null) {
            return;
        }
        active = overlay;
        for (EditableOverlay other : overlays) {
            if (other != overlay) {
                // Only the edited overlay is shown, the other one would just be in the way
                for (EditableElement element : other.getEditableElements()) {
                    element.getView().setVisibility(GONE);
                }
            }
        }
        overlay.setEditing(true);
        canvas.setElements(overlay.getEditableElements());
        canvas.setSelected(null);
        bringToFront();
        updateToolbar();
        updatePanel();
        // The toolbar size is only known once laid out
        post(this::placeToolbar);
    }

    /** Docks the toolbar at the top or the bottom, where it hides the fewest controls. */
    private void placeToolbar() {
        String position = prefs.getString(PREF_TOOLBAR_POSITION, "auto");
        if ("top".equals(position)) {
            toolbarAtBottom = false;
        } else if ("bottom".equals(position)) {
            toolbarAtBottom = true;
        } else if (toolbar.getWidth() > 0 && getHeight() > 0) {
            int margin = dp(8);
            int left = (getWidth() - toolbar.getWidth()) / 2;
            Rect top = new Rect(left, margin, left + toolbar.getWidth(), margin + toolbar.getHeight());
            Rect bottom = new Rect(left, getHeight() - margin - toolbar.getHeight(),
                    left + toolbar.getWidth(), getHeight() - margin);
            toolbarAtBottom = coveredArea(bottom) < coveredArea(top);
        }
        layoutChrome();
    }

    private long coveredArea(Rect area) {
        long covered = 0;
        for (EditableElement element : active.getEditableElements()) {
            if (element.getEditState().removed || element.getView().getVisibility() != VISIBLE) {
                continue;
            }
            Rect r = ElementEditState.getBounds(element.getView());
            if (r.intersect(area)) {
                covered += (long) r.width() * r.height();
            }
        }
        return covered;
    }

    // ------------------------------------------------------------------------------------
    // Toolbar / panel state
    // ------------------------------------------------------------------------------------

    private void updateToolbar() {
        tabGamepad.setSelected(active != null && active.getKind() == LayoutSlots.Kind.GAMEPAD);
        tabKeyboard.setSelected(active != null && active.getKind() == LayoutSlots.Kind.KEYBOARD);

        int slot = LayoutSlots.getActiveSlot(getContext(), active.getKind());
        slotButton.setText(getContext().getString(R.string.overlay_editor_slot_n, slot + 1));

        EditorHistory history = histories.get(active);
        setEnabledLook(undoButton, history.canUndo());
        setEnabledLook(redoButton, history.canRedo());

        boolean removable = false;
        for (EditableElement element : active.getEditableElements()) {
            if (element.isRemovable()) {
                removable = true;
                break;
            }
        }
        addButton.setVisibility(removable ? VISIBLE : GONE);
    }

    private static void setEnabledLook(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.35f);
    }

    private void layoutChrome() {
        boolean toolbarBottom = toolbarAtBottom;
        int margin = dp(8);

        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) toolbar.getLayoutParams();
        tlp.gravity = (toolbarBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        toolbar.setLayoutParams(tlp);

        // Put the properties panel on the opposite side of the selected control
        EditableElement selected = canvas.getSelected();
        boolean panelTop = false;
        if (selected != null) {
            Rect b = ElementEditState.getBounds(selected.getView());
            panelTop = b.exactCenterY() > getHeight() / 2f;
        }
        int toolbarSpace = toolbar.getHeight() + 2 * margin;
        FrameLayout.LayoutParams plp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        plp.gravity = (panelTop ? Gravity.TOP : Gravity.BOTTOM) | Gravity.CENTER_HORIZONTAL;
        plp.topMargin = panelTop && !toolbarBottom ? toolbarSpace : margin;
        plp.bottomMargin = !panelTop && toolbarBottom ? toolbarSpace : margin;
        panel.setLayoutParams(plp);

        // Help text next to the toolbar, where it doesn't hide controls
        int hintOffset = toolbarSpace + dp(14);
        canvas.setHintY(toolbarBottom ? getHeight() - hintOffset : hintOffset);
    }

    private void updatePanel() {
        EditableElement element = canvas.getSelected();
        if (element == null) {
            panel.setVisibility(GONE);
            layoutChrome();
            return;
        }
        panel.setVisibility(VISIBLE);
        ElementEditState s = element.getEditState();

        panelTitle.setText(element.getDisplayName());
        panelVisible.setText(s.shown ? R.string.overlay_editor_shown : R.string.overlay_editor_hidden);
        panelVisible.setCompoundDrawablesWithIntrinsicBounds(0,
                s.shown ? R.drawable.ic_oe_visibility : R.drawable.ic_oe_visibility_off, 0, 0);
        panelVisible.setSelected(!s.shown);
        panelLock.setCompoundDrawablesWithIntrinsicBounds(0,
                s.lockAspect ? R.drawable.ic_oe_lock : R.drawable.ic_oe_lock_open, 0, 0);
        panelLock.setSelected(s.lockAspect);
        panelRemove.setVisibility(element.isRemovable() ? VISIBLE : GONE);

        rowSize.setVisibility(s.lockAspect ? VISIBLE : GONE);
        rowWidth.setVisibility(s.lockAspect ? GONE : VISIBLE);
        rowHeight.setVisibility(s.lockAspect ? GONE : VISIBLE);

        updatePanelValues();
        layoutChrome();
    }

    private void updatePanelValues() {
        EditableElement element = canvas.getSelected();
        if (element == null) {
            return;
        }
        ElementEditState s = element.getEditState();
        Rect b = ElementEditState.getBounds(element.getView());
        int widthPercent = percentOf(b.width(), s.defaultBounds.width());
        int heightPercent = percentOf(b.height(), s.defaultBounds.height());
        int opacityPercent = Math.round(s.opacity * 100);

        updatingPanel = true;
        sizeBar.setProgress(clampPercent(widthPercent) - SIZE_MIN_PERCENT);
        widthBar.setProgress(clampPercent(widthPercent) - SIZE_MIN_PERCENT);
        heightBar.setProgress(clampPercent(heightPercent) - SIZE_MIN_PERCENT);
        opacityBar.setProgress(opacityPercent - OPACITY_MIN_PERCENT);
        updatingPanel = false;

        sizeValue.setText(widthPercent + "%");
        widthValue.setText(widthPercent + "%");
        heightValue.setText(heightPercent + "%");
        opacityValue.setText(opacityPercent + "%");
    }

    private static int percentOf(int value, int reference) {
        return reference > 0 ? Math.round(value * 100f / reference) : 100;
    }

    private static int clampPercent(int percent) {
        return Math.max(SIZE_MIN_PERCENT, Math.min(SIZE_MAX_PERCENT, percent));
    }

    private void fadeChrome(boolean faded) {
        float alpha = faded ? 0.15f : 1f;
        toolbar.animate().alpha(alpha).setDuration(120).start();
        panel.animate().alpha(alpha).setDuration(120).start();
    }

    private void select(EditableElement element) {
        canvas.setSelected(element);
        updatePanel();
    }

    // ------------------------------------------------------------------------------------
    // Editing helpers
    // ------------------------------------------------------------------------------------

    private interface ElementEdit {
        void apply(EditableElement element);
    }

    /** Applies an undoable edit to the selected element. */
    private void editSelected(ElementEdit edit) {
        EditableElement element = canvas.getSelected();
        if (element == null) {
            return;
        }
        LayoutSnapshot before = LayoutSnapshot.capture(active);
        edit.apply(element);
        element.getEditState().applyVisibility(element.getView());
        commit(before);
        updatePanel();
    }

    /** Records an edit in the history (if anything changed). */
    private void commit(LayoutSnapshot before) {
        if (!before.sameAs(LayoutSnapshot.capture(active))) {
            histories.get(active).push(before);
        }
        canvas.invalidate();
        updateToolbar();
    }

    private void undo() {
        LayoutSnapshot state = histories.get(active).undo(LayoutSnapshot.capture(active));
        if (state != null) {
            state.apply(active);
            afterBulkChange();
        }
    }

    private void redo() {
        LayoutSnapshot state = histories.get(active).redo(LayoutSnapshot.capture(active));
        if (state != null) {
            state.apply(active);
            afterBulkChange();
        }
    }

    private void afterBulkChange() {
        EditableElement selected = canvas.getSelected();
        if (selected != null && selected.getEditState().removed) {
            canvas.setSelected(null);
        }
        canvas.invalidate();
        updateToolbar();
        updatePanel();
    }

    /** Resizes an element around its center, keeping it inside the screen. */
    private void resizeAroundCenter(EditableElement element, int width, int height) {
        int min = ElementEditState.minElementSize(getResources().getDisplayMetrics());
        width = Math.max(min, Math.min(width, getWidth()));
        height = Math.max(min, Math.min(height, getHeight()));
        Rect b = ElementEditState.getBounds(element.getView());
        int left = Math.round(b.exactCenterX() - width / 2f);
        int top = Math.round(b.exactCenterY() - height / 2f);
        left = Math.max(0, Math.min(left, getWidth() - width));
        top = Math.max(0, Math.min(top, getHeight() - height));
        ElementEditState.setBounds(element.getView(), left, top, width, height);
        canvas.invalidate();
    }

    private void moveBy(EditableElement element, int dx, int dy) {
        Rect b = ElementEditState.getBounds(element.getView());
        int left = Math.max(0, Math.min(b.left + dx, getWidth() - b.width()));
        int top = Math.max(0, Math.min(b.top + dy, getHeight() - b.height()));
        ElementEditState.setBounds(element.getView(), left, top, b.width(), b.height());
        canvas.invalidate();
    }

    private class SliderListener implements SeekBar.OnSeekBarChangeListener {
        static final int SIZE = 0, WIDTH = 1, HEIGHT = 2, OPACITY = 3;
        private final int kind;

        SliderListener(int kind) {
            this.kind = kind;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            EditableElement element = canvas.getSelected();
            if (updatingPanel || !fromUser || element == null) {
                return;
            }
            boolean standalone = sliderBefore == null;
            LayoutSnapshot before = standalone ? LayoutSnapshot.capture(active) : null;

            ElementEditState s = element.getEditState();
            Rect b = ElementEditState.getBounds(element.getView());
            int percent = progress + SIZE_MIN_PERCENT;
            switch (kind) {
                case SIZE: {
                    // Keep the current ratio
                    float scale = percent / 100f * s.defaultBounds.width() / Math.max(1, b.width());
                    resizeAroundCenter(element, Math.round(b.width() * scale), Math.round(b.height() * scale));
                    break;
                }
                case WIDTH:
                    resizeAroundCenter(element, Math.round(s.defaultBounds.width() * percent / 100f), b.height());
                    break;
                case HEIGHT:
                    resizeAroundCenter(element, b.width(), Math.round(s.defaultBounds.height() * percent / 100f));
                    break;
                default:
                    s.opacity = ElementEditState.clampOpacity((progress + OPACITY_MIN_PERCENT) / 100f);
                    s.applyVisibility(element.getView());
                    break;
            }
            if (standalone) {
                commit(before);
            }
            updatePanelValues();
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
            sliderBefore = LayoutSnapshot.capture(active);
        }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            if (sliderBefore != null) {
                commit(sliderBefore);
                sliderBefore = null;
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupNudge(int viewId, int dirX, int dirY) {
        View view = findViewById(viewId);
        final Runnable[] repeat = new Runnable[1];
        final LayoutSnapshot[] before = new LayoutSnapshot[1];
        view.setOnTouchListener((v, event) -> {
            EditableElement element = canvas.getSelected();
            if (element == null) {
                return false;
            }
            int step = prefs.getBoolean(PREF_GRID, false) ? canvas.getGridStep() : Math.max(1, dp(1));
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    before[0] = LayoutSnapshot.capture(active);
                    moveBy(element, dirX * step, dirY * step);
                    repeat[0] = new Runnable() {
                        @Override
                        public void run() {
                            moveBy(element, dirX * step, dirY * step);
                            handler.postDelayed(this, 50);
                        }
                    };
                    handler.postDelayed(repeat[0], 400);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    handler.removeCallbacks(repeat[0]);
                    if (before[0] != null) {
                        commit(before[0]);
                        before[0] = null;
                    }
                    updatePanel();
                    return true;
                default:
                    return true;
            }
        });
    }

    // ------------------------------------------------------------------------------------
    // Canvas callbacks
    // ------------------------------------------------------------------------------------

    @Override
    public void onSelectionChanged(EditableElement element) {
        updatePanel();
    }

    @Override
    public void onGestureStarted() {
        gestureBefore = LayoutSnapshot.capture(active);
        fadeChrome(true);
    }

    @Override
    public void onGestureUpdated() {
        updatePanelValues();
    }

    @Override
    public void onGestureFinished() {
        fadeChrome(false);
        if (gestureBefore != null) {
            commit(gestureBefore);
            gestureBefore = null;
        }
        updatePanel();
    }

    // ------------------------------------------------------------------------------------
    // Layout slots
    // ------------------------------------------------------------------------------------

    private static final int MENU_SLOT_BASE = 100;
    private static final int MENU_COPY_BASE = 200;

    private void showSlotMenu(View anchor) {
        Context context = getContext();
        LayoutSlots.Kind kind = active.getKind();
        int current = LayoutSlots.getActiveSlot(context, kind);

        PopupMenu popup = new PopupMenu(context, anchor);
        Menu menu = popup.getMenu();
        for (int i = 0; i < LayoutSlots.SLOT_COUNT; i++) {
            String title = context.getString(R.string.overlay_editor_slot_n, i + 1);
            if (i != current && LayoutSlots.isEmpty(context, kind, i)) {
                title += " " + context.getString(R.string.overlay_editor_slot_default_suffix);
            }
            MenuItem item = menu.add(1, MENU_SLOT_BASE + i, i, title);
            item.setCheckable(true);
            item.setChecked(i == current);
        }
        menu.setGroupCheckable(1, true, true);
        SubMenu copy = menu.addSubMenu(2, 0, LayoutSlots.SLOT_COUNT,
                context.getString(R.string.overlay_editor_copy_to));
        for (int i = 0; i < LayoutSlots.SLOT_COUNT; i++) {
            if (i != current) {
                copy.add(2, MENU_COPY_BASE + i, i, context.getString(R.string.overlay_editor_slot_n, i + 1));
            }
        }
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id >= MENU_COPY_BASE) {
                copyToSlot(id - MENU_COPY_BASE);
                return true;
            } else if (id >= MENU_SLOT_BASE) {
                switchSlot(id - MENU_SLOT_BASE);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private boolean isDirty(EditableOverlay overlay) {
        return !LayoutSnapshot.capture(overlay).sameAs(savedStates.get(overlay));
    }

    private void switchSlot(int slot) {
        Context context = getContext();
        if (slot == LayoutSlots.getActiveSlot(context, active.getKind())) {
            return;
        }
        if (!isDirty(active)) {
            loadSlot(slot);
            return;
        }
        int current = LayoutSlots.getActiveSlot(context, active.getKind());
        new AlertDialog.Builder(context)
                .setTitle(R.string.overlay_editor_unsaved_title)
                .setMessage(context.getString(R.string.overlay_editor_unsaved_slot, current + 1))
                .setPositiveButton(R.string.overlay_editor_save, (d, w) -> {
                    saveOverlay(active);
                    loadSlot(slot);
                })
                .setNegativeButton(R.string.overlay_editor_discard, (d, w) -> loadSlot(slot))
                .setNeutralButton(R.string.overlay_editor_keep_editing, null)
                .show();
    }

    private void loadSlot(int slot) {
        LayoutSlots.setActiveSlot(getContext(), active.getKind(), slot);
        reloadOverlay(active, false);
        savedStates.put(active, LayoutSnapshot.capture(active));
        histories.get(active).clear();
        switchTo(active);
        Toast.makeText(getContext(), getContext().getString(R.string.overlay_editor_slot_loaded, slot + 1),
                Toast.LENGTH_SHORT).show();
    }

    private void copyToSlot(int target) {
        Context context = getContext();
        Runnable copy = () -> {
            LayoutSlots.Kind kind = active.getKind();
            int current = LayoutSlots.getActiveSlot(context, kind);
            // Write the layout as currently edited (saved or not) into the target slot
            LayoutSlots.clear(context, kind, target);
            LayoutSlots.setActiveSlot(context, kind, target);
            active.saveLayout();
            LayoutSlots.setActiveSlot(context, kind, current);
            Toast.makeText(context, context.getString(R.string.overlay_editor_copied, target + 1),
                    Toast.LENGTH_SHORT).show();
        };
        if (LayoutSlots.isEmpty(context, active.getKind(), target)) {
            copy.run();
        } else {
            new AlertDialog.Builder(context)
                    .setMessage(context.getString(R.string.overlay_editor_overwrite_slot, target + 1))
                    .setPositiveButton(R.string.overlay_editor_overwrite, (d, w) -> copy.run())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        }
    }

    /**
     * Rebuilds an overlay (new style, slot switch, ...).
     *
     * @param keepEdits re-apply the current (unsaved) element states after rebuilding
     */
    private void reloadOverlay(EditableOverlay overlay, boolean keepEdits) {
        String selectedKey = canvas.getSelected() != null && overlay == active
                ? canvas.getSelected().getEditorKey() : null;
        LayoutSnapshot state = keepEdits ? LayoutSnapshot.capture(overlay) : null;

        overlay.reloadLayout();
        overlay.setEditing(true);
        if (state != null) {
            state.apply(overlay);
        }
        if (overlay != active) {
            for (EditableElement element : overlay.getEditableElements()) {
                element.getView().setVisibility(GONE);
            }
        } else {
            canvas.setElements(overlay.getEditableElements());
            EditableElement reselect = null;
            if (selectedKey != null) {
                for (EditableElement element : overlay.getEditableElements()) {
                    if (element.getEditorKey().equals(selectedKey)) {
                        reselect = element;
                        break;
                    }
                }
            }
            canvas.setSelected(reselect);
        }
        // The overlay re-added its views on top of the editor
        bringToFront();
    }

    // ------------------------------------------------------------------------------------
    // Add / reset
    // ------------------------------------------------------------------------------------

    private void showAddDialog() {
        Context context = getContext();
        final List<EditableElement> removed = new ArrayList<>();
        for (EditableElement element : active.getEditableElements()) {
            if (element.isRemovable() && element.getEditState().removed) {
                removed.add(element);
            }
        }
        if (removed.isEmpty()) {
            Toast.makeText(context, R.string.overlay_editor_nothing_to_add, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[removed.size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = removed.get(i).getDisplayName();
        }
        final boolean[] checked = new boolean[names.length];
        new AlertDialog.Builder(context)
                .setTitle(R.string.overlay_editor_add_title)
                .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(R.string.overlay_editor_add, (d, w) -> {
                    LayoutSnapshot before = LayoutSnapshot.capture(active);
                    EditableElement first = null;
                    for (int i = 0; i < checked.length; i++) {
                        if (!checked[i]) {
                            continue;
                        }
                        EditableElement element = removed.get(i);
                        placeAtFreeSpot(element);
                        if (first == null) {
                            first = element;
                        }
                    }
                    commit(before);
                    if (first != null) {
                        select(first);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Brings back a removed element at a free spot, as close as possible to the screen center. */
    private void placeAtFreeSpot(EditableElement element) {
        ElementEditState s = element.getEditState();
        int min = ElementEditState.minElementSize(getResources().getDisplayMetrics());
        int w = Math.max(min, s.defaultBounds.width());
        int h = Math.max(min, s.defaultBounds.height());

        List<Rect> occupied = new ArrayList<>();
        for (EditableElement other : active.getEditableElements()) {
            if (other != element && !other.getEditState().removed) {
                occupied.add(ElementEditState.getBounds(other.getView()));
            }
        }
        // Don't drop it under the toolbar
        occupied.add(new Rect(toolbar.getLeft(), toolbar.getTop(), toolbar.getRight(), toolbar.getBottom()));

        int step = Math.max(dp(8), canvas.getGridStep() / 2);
        int bestLeft = (getWidth() - w) / 2;
        int bestTop = (getHeight() - h) / 2;
        double bestDistance = Double.MAX_VALUE;
        for (int top = 0; top + h <= getHeight(); top += step) {
            for (int left = 0; left + w <= getWidth(); left += step) {
                Rect candidate = new Rect(left, top, left + w, top + h);
                boolean free = true;
                for (Rect r : occupied) {
                    if (Rect.intersects(r, candidate)) {
                        free = false;
                        break;
                    }
                }
                if (!free) {
                    continue;
                }
                double distance = Math.hypot(candidate.exactCenterX() - getWidth() / 2f,
                        candidate.exactCenterY() - getHeight() / 2f);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestLeft = left;
                    bestTop = top;
                }
            }
        }

        s.removed = false;
        s.shown = true;
        ElementEditState.setBounds(element.getView(), bestLeft, bestTop, w, h);
        s.applyVisibility(element.getView());
    }

    private static final int MORE_SNAP = 1;
    private static final int MORE_GRID = 2;
    private static final int MORE_RESET = 3;
    private static final int MORE_REMOVE_ALL = 4;
    private static final int MORE_TOOLBAR_AUTO = 10;
    private static final int MORE_TOOLBAR_TOP = 11;
    private static final int MORE_TOOLBAR_BOTTOM = 12;

    private void showMoreMenu(View anchor) {
        Context context = getContext();
        PopupMenu popup = new PopupMenu(context, anchor);
        Menu menu = popup.getMenu();
        menu.add(0, MORE_SNAP, 0, R.string.overlay_editor_menu_snap)
                .setCheckable(true).setChecked(prefs.getBoolean(PREF_SNAP, true));
        menu.add(0, MORE_GRID, 1, R.string.overlay_editor_menu_grid)
                .setCheckable(true).setChecked(prefs.getBoolean(PREF_GRID, false));
        menu.add(1, MORE_RESET, 2, R.string.overlay_editor_reset_layout);
        if (addButton.getVisibility() == VISIBLE) {
            menu.add(1, MORE_REMOVE_ALL, 3, R.string.overlay_editor_remove_all);
        }
        SubMenu toolbarMenu = menu.addSubMenu(2, 0, 4, R.string.overlay_editor_menu_toolbar);
        String position = prefs.getString(PREF_TOOLBAR_POSITION, "auto");
        toolbarMenu.add(3, MORE_TOOLBAR_AUTO, 0, R.string.overlay_editor_toolbar_auto).setChecked("auto".equals(position));
        toolbarMenu.add(3, MORE_TOOLBAR_TOP, 1, R.string.overlay_editor_toolbar_top).setChecked("top".equals(position));
        toolbarMenu.add(3, MORE_TOOLBAR_BOTTOM, 2, R.string.overlay_editor_toolbar_bottom).setChecked("bottom".equals(position));
        toolbarMenu.setGroupCheckable(3, true, true);

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MORE_SNAP:
                    prefs.edit().putBoolean(PREF_SNAP, !prefs.getBoolean(PREF_SNAP, true)).apply();
                    applyToggles();
                    return true;
                case MORE_GRID:
                    prefs.edit().putBoolean(PREF_GRID, !prefs.getBoolean(PREF_GRID, false)).apply();
                    applyToggles();
                    return true;
                case MORE_RESET:
                case MORE_REMOVE_ALL: {
                    LayoutSnapshot before = LayoutSnapshot.capture(active);
                    for (EditableElement element : active.getEditableElements()) {
                        if (item.getItemId() == MORE_RESET) {
                            element.getEditState().resetToDefault(element.getView());
                        } else if (element.isRemovable()) {
                            element.getEditState().removed = true;
                            element.getEditState().applyVisibility(element.getView());
                        }
                    }
                    select(null);
                    commit(before);
                    Toast.makeText(context, R.string.overlay_editor_undo_hint, Toast.LENGTH_SHORT).show();
                    return true;
                }
                case MORE_TOOLBAR_AUTO:
                case MORE_TOOLBAR_TOP:
                case MORE_TOOLBAR_BOTTOM:
                    prefs.edit().putString(PREF_TOOLBAR_POSITION,
                            item.getItemId() == MORE_TOOLBAR_TOP ? "top"
                                    : item.getItemId() == MORE_TOOLBAR_BOTTOM ? "bottom" : "auto").apply();
                    placeToolbar();
                    return true;
                default:
                    return false;
            }
        });
        popup.show();
    }

    // ------------------------------------------------------------------------------------
    // Options (style, opacity, ...)
    // ------------------------------------------------------------------------------------

    private void showOptionsDialog() {
        Context context = getContext();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        int pad = dp(20);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(8), pad, 0);

        content.addView(sectionTitle(R.string.overlay_editor_style));
        RadioGroup styles = new RadioGroup(context);
        ButtonStyle currentStyle = ButtonStyle.fromPreferences(context);
        for (ButtonStyle style : ButtonStyle.values()) {
            RadioButton radio = new RadioButton(context);
            radio.setId(View.generateViewId());
            radio.setText(style.labelRes);
            radio.setTag(style);
            styles.addView(radio);
            if (style == currentStyle) {
                styles.check(radio.getId());
            }
        }
        content.addView(styles);

        content.addView(sectionTitle(R.string.overlay_editor_global_opacity));
        final TextView opacityLabel = new TextView(context);
        final SeekBar opacity = new SeekBar(context);
        opacity.setMax(100);
        opacity.setProgress(config.oscOpacity);
        opacityLabel.setText(config.oscOpacity + "%");
        opacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                opacityLabel.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        content.addView(opacity);
        content.addView(opacityLabel);

        content.addView(sectionTitle(R.string.overlay_editor_controls));
        CheckBox guide = checkBox(R.string.overlay_editor_option_guide, config.showGuideButton);
        CheckBox freeSticks = checkBox(R.string.overlay_editor_option_free_sticks, config.enableNewAnalogStick);
        CheckBox squareKeys = checkBox(R.string.overlay_editor_option_square_keys, config.enableKeyboardSquare);
        content.addView(guide);
        content.addView(freeSticks);

        // Opacity of the floating sticks' touch circle
        final TextView freeSticksOpacityLabel = new TextView(context);
        final SeekBar freeSticksOpacity = new SeekBar(context);
        freeSticksOpacity.setMax(100);
        freeSticksOpacity.setProgress(config.enableNewAnalogStickOpacity);
        freeSticksOpacityLabel.setText(context.getString(R.string.overlay_editor_option_free_sticks_opacity,
                config.enableNewAnalogStickOpacity));
        freeSticksOpacityLabel.setPadding(dp(32), 0, 0, 0);
        freeSticksOpacity.setPadding(dp(40), dp(4), dp(16), dp(8));
        freeSticksOpacity.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                freeSticksOpacityLabel.setText(context.getString(R.string.overlay_editor_option_free_sticks_opacity, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        Runnable updateFreeSticks = () -> {
            int visibility = freeSticks.isChecked() ? VISIBLE : GONE;
            freeSticksOpacityLabel.setVisibility(visibility);
            freeSticksOpacity.setVisibility(visibility);
        };
        freeSticks.setOnCheckedChangeListener((button, checked) -> updateFreeSticks.run());
        updateFreeSticks.run();
        content.addView(freeSticksOpacityLabel);
        content.addView(freeSticksOpacity);
        content.addView(squareKeys);

        ScrollView scroll = new ScrollView(context);
        scroll.addView(content);

        new AlertDialog.Builder(context)
                .setTitle(R.string.overlay_editor_options)
                .setView(scroll)
                .setPositiveButton(R.string.overlay_editor_apply, (d, w) -> {
                    RadioButton checkedStyle = styles.findViewById(styles.getCheckedRadioButtonId());
                    if (checkedStyle != null) {
                        ((ButtonStyle) checkedStyle.getTag()).save(context);
                    }
                    prefs.edit()
                            .putInt(OverlayEditorSettings.OPACITY, opacity.getProgress())
                            .putBoolean(OverlayEditorSettings.SHOW_GUIDE_BUTTON, guide.isChecked())
                            .putBoolean(OverlayEditorSettings.FLOATING_STICKS, freeSticks.isChecked())
                            .putInt(OverlayEditorSettings.FLOATING_STICKS_OPACITY, freeSticksOpacity.getProgress())
                            .putBoolean(OverlayEditorSettings.SQUARE_KEYS, squareKeys.isChecked())
                            .commit();
                    for (EditableOverlay overlay : overlays) {
                        reloadOverlay(overlay, true);
                    }
                    switchTo(active);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private TextView sectionTitle(int textRes) {
        TextView title = new TextView(getContext());
        title.setText(textRes);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setAllCaps(true);
        title.setPadding(0, dp(14), 0, dp(4));
        title.setAlpha(0.7f);
        return title;
    }

    private CheckBox checkBox(int textRes, boolean checked) {
        CheckBox box = new CheckBox(getContext());
        box.setText(textRes);
        box.setChecked(checked);
        return box;
    }

    // ------------------------------------------------------------------------------------
    // Closing
    // ------------------------------------------------------------------------------------

    /** @return true, the editor always consumes the back key */
    public boolean onBackPressed() {
        if (canvas.getSelected() != null) {
            select(null);
        } else {
            requestClose();
        }
        return true;
    }

    private boolean anyDirty() {
        for (EditableOverlay overlay : overlays) {
            if (isDirty(overlay)) {
                return true;
            }
        }
        return false;
    }

    public void requestClose() {
        if (!anyDirty()) {
            finish(false);
            return;
        }
        new AlertDialog.Builder(getContext())
                .setTitle(R.string.overlay_editor_unsaved_title)
                .setMessage(R.string.overlay_editor_unsaved_close)
                .setPositiveButton(R.string.overlay_editor_save, (d, w) -> saveAndClose())
                .setNegativeButton(R.string.overlay_editor_discard, (d, w) -> {
                    for (EditableOverlay overlay : overlays) {
                        if (isDirty(overlay)) {
                            overlay.reloadLayout();
                        }
                    }
                    finish(false);
                })
                .setNeutralButton(R.string.overlay_editor_keep_editing, null)
                .show();
    }

    private void saveOverlay(EditableOverlay overlay) {
        overlay.saveLayout();
        savedStates.put(overlay, LayoutSnapshot.capture(overlay));
    }

    public void saveAndClose() {
        for (EditableOverlay overlay : overlays) {
            saveOverlay(overlay);
        }
        Toast.makeText(getContext(), R.string.overlay_editor_saved, Toast.LENGTH_SHORT).show();
        finish(true);
    }

    private void finish(boolean saved) {
        if (closed) {
            return;
        }
        closed = true;
        handler.removeCallbacksAndMessages(null);
        for (EditableOverlay overlay : overlays) {
            overlay.setEditing(false);
        }
        ViewGroup parent = (ViewGroup) getParent();
        if (parent != null) {
            parent.removeView(this);
        }
        if (listener != null) {
            listener.onEditorClosed(saved);
        }
    }

    /** Called by the host when the screen size changed: overlays were rebuilt from the saved layout. */
    public void onHostLayoutRebuilt() {
        for (EditableOverlay overlay : overlays) {
            overlay.setEditing(true);
            savedStates.put(overlay, LayoutSnapshot.capture(overlay));
            histories.get(overlay).clear();
        }
        switchTo(active);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
