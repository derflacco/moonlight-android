package com.limelight.overlay;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import com.limelight.InputOverlayEditorActivity;
import com.limelight.R;
import com.limelight.TestLogSuppressor;
import com.limelight.binding.input.overlay.ButtonStyle;
import com.limelight.binding.input.overlay.EditableElement;
import com.limelight.binding.input.overlay.ElementEditState;
import com.limelight.binding.input.overlay.LayoutSlots;
import com.limelight.binding.input.overlay.editor.EditorCanvasView;
import com.limelight.binding.input.virtual_controller.VirtualControllerElement;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Drives the standalone overlay editor: selection, drag, undo, save, styles.
 * Screenshots are written to app/build/overlay-editor-shots for visual review.
 */
@Config(sdk = {33}, qualifiers = "w891dp-h411dp-land-xxhdpi",
        shadows = {com.limelight.shadows.ShadowMoonBridge.class, com.limelight.shadows.ShadowGameManager.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner.class)
public class OverlayEditorUiTest {
    private static final File SHOTS = new File("build/overlay-editor-shots");

    @BeforeClass
    public static void suppressLogs() {
        TestLogSuppressor.install();
        SHOTS.mkdirs();
    }

    @Before
    public void resetPrefs() {
        Context context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
        for (int i = 0; i < LayoutSlots.SLOT_COUNT; i++) {
            LayoutSlots.clear(context, LayoutSlots.Kind.GAMEPAD, i);
            LayoutSlots.clear(context, LayoutSlots.Kind.KEYBOARD, i);
        }
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static void shot(Activity activity, String name) throws Exception {
        idle();
        View root = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        // Downscale to keep files small
        Bitmap small = Bitmap.createScaledBitmap(bitmap, root.getWidth() / 2, root.getHeight() / 2, true);
        try (FileOutputStream out = new FileOutputStream(new File(SHOTS, name + ".png"))) {
            small.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    private static void collect(View view, List<EditableElement> out) {
        if (view instanceof EditableElement) {
            out.add((EditableElement) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), out);
            }
        }
    }

    private static EditableElement gamepadElement(Activity activity, int elementId) {
        List<EditableElement> all = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), all);
        for (EditableElement element : all) {
            if (element instanceof VirtualControllerElement && element.getEditorKey().equals(Integer.toString(elementId))) {
                return element;
            }
        }
        return null;
    }

    private static void touch(View view, int action, float x, float y, long downTime) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }

    private static void tap(View view, float x, float y) {
        long down = SystemClock.uptimeMillis();
        touch(view, MotionEvent.ACTION_DOWN, x, y, down);
        touch(view, MotionEvent.ACTION_UP, x, y, down);
        idle();
    }

    private static void drag(View view, float fromX, float fromY, float toX, float toY) {
        long down = SystemClock.uptimeMillis();
        touch(view, MotionEvent.ACTION_DOWN, fromX, fromY, down);
        int steps = 12;
        for (int i = 1; i <= steps; i++) {
            touch(view, MotionEvent.ACTION_MOVE, fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps, down);
        }
        touch(view, MotionEvent.ACTION_UP, toX, toY, down);
        idle();
    }

    @Test
    public void editSaveAndReload() throws Exception {
        ActivityController<InputOverlayEditorActivity> controller =
                Robolectric.buildActivity(InputOverlayEditorActivity.class).setup().visible();
        Activity activity = controller.get();
        idle();
        shot(activity, "01_gamepad_classic");

        EditorCanvasView canvas = activity.findViewById(R.id.oe_canvas);
        assertNotNull(canvas);

        // Select the A button
        EditableElement a = gamepadElement(activity, VirtualControllerElement.EID_A);
        assertNotNull(a);
        Rect start = ElementEditState.getBounds(a.getView());
        tap(canvas, start.exactCenterX(), start.exactCenterY());
        assertEquals(a, canvas.getSelected());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.oe_panel).getVisibility());
        shot(activity, "02_selected");

        // Drag it towards the middle of the screen
        drag(canvas, start.exactCenterX(), start.exactCenterY(), start.exactCenterX() - 600, start.exactCenterY() + 250);
        Rect moved = ElementEditState.getBounds(a.getView());
        assertNotEquals(start, moved);
        assertEquals(start.width(), moved.width());
        shot(activity, "03_dragged");

        // Resize from the bottom right handle: ratio is locked for face buttons
        drag(canvas, moved.right, moved.bottom, moved.right + 120, moved.bottom + 40);
        Rect resized = ElementEditState.getBounds(a.getView());
        assertTrue(resized.width() > moved.width());
        assertEquals(resized.width(), resized.height(), 1);
        shot(activity, "04_resized");

        // Hide it (stays visible, faded, in the editor)
        activity.findViewById(R.id.oe_panel_visible).performClick();
        idle();
        assertFalse(a.getEditState().shown);
        assertEquals(View.VISIBLE, a.getView().getVisibility());
        shot(activity, "05_hidden");

        // Undo restores visibility, then undo the resize
        activity.findViewById(R.id.oe_undo).performClick();
        idle();
        assertTrue(a.getEditState().shown);
        activity.findViewById(R.id.oe_undo).performClick();
        idle();
        assertEquals(moved, ElementEditState.getBounds(a.getView()));
        activity.findViewById(R.id.oe_redo).performClick();
        idle();
        assertEquals(resized, ElementEditState.getBounds(a.getView()));

        // Tap on empty space clears the selection
        tap(canvas, canvas.getWidth() / 2f, 10);
        assertNull(canvas.getSelected());

        // Keyboard overlay tab
        activity.findViewById(R.id.oe_tab_keyboard).performClick();
        idle();
        assertEquals(View.GONE, a.getView().getVisibility());
        shot(activity, "06_keyboard_tab");

        // Remove every key, then the add button brings one back
        activity.findViewById(R.id.oe_tab_gamepad).performClick();
        idle();

        // Save
        activity.findViewById(R.id.oe_save).performClick();
        idle();
        assertTrue(activity.isFinishing());

        // The saved layout uses the new format and is read back
        Context context = ApplicationProvider.getApplicationContext();
        String saved = LayoutSlots.openActive(context, LayoutSlots.Kind.GAMEPAD)
                .getString(Integer.toString(VirtualControllerElement.EID_A), null);
        assertNotNull(saved);
        assertEquals(2, new JSONObject(saved).getInt("v"));

        Activity reopened = Robolectric.buildActivity(InputOverlayEditorActivity.class).setup().visible().get();
        idle();
        EditableElement reloaded = gamepadElement(reopened, VirtualControllerElement.EID_A);
        Rect reloadedBounds = ElementEditState.getBounds(reloaded.getView());
        assertEquals(resized.left, reloadedBounds.left, 2);
        assertEquals(resized.top, reloadedBounds.top, 2);
        assertEquals(resized.width(), reloadedBounds.width(), 2);
    }

    @Test
    public void slotsAreIndependent() throws Exception {
        Activity activity = Robolectric.buildActivity(InputOverlayEditorActivity.class).setup().visible().get();
        idle();
        EditorCanvasView canvas = activity.findViewById(R.id.oe_canvas);
        EditableElement a = gamepadElement(activity, VirtualControllerElement.EID_A);
        Rect start = ElementEditState.getBounds(a.getView());
        drag(canvas, start.exactCenterX(), start.exactCenterY(), start.exactCenterX() - 400, start.exactCenterY());
        activity.findViewById(R.id.oe_save).performClick();
        idle();

        // Slot 2 still has the default layout
        Context context = ApplicationProvider.getApplicationContext();
        LayoutSlots.setActiveSlot(context, LayoutSlots.Kind.GAMEPAD, 1);
        Activity second = Robolectric.buildActivity(InputOverlayEditorActivity.class).setup().visible().get();
        idle();
        assertEquals(start, ElementEditState.getBounds(gamepadElement(second, VirtualControllerElement.EID_A).getView()));
    }

    private static View findByTag(View view, Object tag) {
        if (tag.equals(view.getTag())) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByTag(group.getChildAt(i), tag);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void keyboardRemoveAndAddBack() throws Exception {
        Activity activity = Robolectric.buildActivity(InputOverlayEditorActivity.class).setup().visible().get();
        idle();
        activity.findViewById(R.id.oe_tab_keyboard).performClick();
        idle();
        EditorCanvasView canvas = activity.findViewById(R.id.oe_canvas);

        List<EditableElement> all = new ArrayList<>();
        collect(activity.getWindow().getDecorView(), all);
        EditableElement key = null;
        for (EditableElement element : all) {
            if (element.isRemovable() && element.getView().getVisibility() == View.VISIBLE) {
                key = element;
                break;
            }
        }
        assertNotNull(key);

        Rect b = ElementEditState.getBounds(key.getView());
        tap(canvas, b.exactCenterX(), b.exactCenterY());
        assertEquals(key, canvas.getSelected());
        activity.findViewById(R.id.oe_panel_remove).performClick();
        idle();
        assertTrue(key.getEditState().removed);
        assertEquals(View.GONE, key.getView().getVisibility());
        assertNull(canvas.getSelected());

        // Add it back from the "Add" dialog
        activity.findViewById(R.id.oe_add).performClick();
        idle();
        android.app.AlertDialog dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        shadowOf(dialog).clickOnItem(0);
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        idle();
        assertFalse(key.getEditState().removed);
        assertEquals(View.VISIBLE, key.getView().getVisibility());
        assertEquals(key, canvas.getSelected());
        shot(activity, "07_keyboard_key_added_back");
    }

    @Test
    public void changingStyleKeepsUnsavedEdits() throws Exception {
        Activity activity = Robolectric.buildActivity(InputOverlayEditorActivity.class).setup().visible().get();
        idle();
        EditorCanvasView canvas = activity.findViewById(R.id.oe_canvas);
        EditableElement a = gamepadElement(activity, VirtualControllerElement.EID_A);
        Rect start = ElementEditState.getBounds(a.getView());
        drag(canvas, start.exactCenterX(), start.exactCenterY(), start.exactCenterX() - 500, start.exactCenterY() + 200);
        Rect moved = ElementEditState.getBounds(a.getView());

        activity.findViewById(R.id.oe_options).performClick();
        idle();
        android.app.AlertDialog dialog = (android.app.AlertDialog) org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        View radio = findByTag(dialog.getWindow().getDecorView(), ButtonStyle.PLAYSTATION);
        assertNotNull(radio);
        radio.performClick();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
        idle();

        Context context = ApplicationProvider.getApplicationContext();
        assertEquals(ButtonStyle.PLAYSTATION, ButtonStyle.fromPreferences(context));
        // Elements were rebuilt in the new style, the unsaved move is kept
        EditableElement rebuilt = gamepadElement(activity, VirtualControllerElement.EID_A);
        assertNotEquals(a, rebuilt);
        assertEquals(moved, ElementEditState.getBounds(rebuilt.getView()));
        assertEquals("Cross", rebuilt.getDisplayName());
        // ...and is still undoable
        activity.findViewById(R.id.oe_undo).performClick();
        idle();
        assertEquals(start, ElementEditState.getBounds(rebuilt.getView()));
        shot(activity, "08_style_changed_then_undo");
    }

    @Test
    public void styles() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        for (ButtonStyle style : ButtonStyle.values()) {
            style.save(context);
            Activity activity = Robolectric.buildActivity(InputOverlayEditorActivity.class).setup().visible().get();
            idle();
            // Close the editor chrome out of the way: select nothing, draw
            shot(activity, "style_" + style.value);
            activity.finish();
        }
    }
}
