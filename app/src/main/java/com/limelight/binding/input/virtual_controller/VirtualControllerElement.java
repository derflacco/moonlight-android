/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;

import com.limelight.binding.input.overlay.ButtonStyle;
import com.limelight.binding.input.overlay.EditableElement;
import com.limelight.binding.input.overlay.ElementEditState;

import org.json.JSONException;
import org.json.JSONObject;

public abstract class VirtualControllerElement extends View implements EditableElement {
    protected static boolean _PRINT_DEBUG_INFORMATION = false;

    public static final int EID_DPAD = 1;
    public static final int EID_LT = 2;
    public static final int EID_RT = 3;
    public static final int EID_LB = 4;
    public static final int EID_RB = 5;
    public static final int EID_A = 6;
    public static final int EID_B = 7;
    public static final int EID_X = 8;
    public static final int EID_Y = 9;
    public static final int EID_BACK = 10;
    public static final int EID_START = 11;
    public static final int EID_LS = 12;
    public static final int EID_RS = 13;
    public static final int EID_LSB = 14;
    public static final int EID_RSB = 15;
    public static final int EID_GDB = 16;
    public static final int EID_TOUCHPAD = 65;

    protected VirtualController virtualController;
    protected final int elementId;

    protected int normalColor = 0xF0888888;
    protected int pressedColor = 0xF07272ED;

    private final ElementEditState editState = new ElementEditState();
    private String displayName = "";

    protected VirtualControllerElement(VirtualController controller, Context context, int elementId) {
        super(context);

        this.virtualController = controller;
        this.elementId = elementId;
    }

    // ------------------------------------------------------------------------------------
    // EditableElement
    // ------------------------------------------------------------------------------------

    @Override
    public View getView() {
        return this;
    }

    @Override
    public String getEditorKey() {
        return Integer.toString(elementId);
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public ElementEditState getEditState() {
        return editState;
    }

    @Override
    public boolean isRemovable() {
        return false;
    }

    public boolean isShownInGame() {
        return editState.shown;
    }

    /** True while the overlay is displayed inside the layout editor. */
    protected boolean isEditorPreview() {
        return editState.editing;
    }

    // ------------------------------------------------------------------------------------
    // Drawing / input
    // ------------------------------------------------------------------------------------

    protected ButtonStyle getButtonStyle() {
        return virtualController.getButtonStyle();
    }

    /** Global overlay opacity as an alpha value (0..255). */
    protected int getOverlayAlpha() {
        return normalColor >>> 24;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        onElementDraw(canvas);
        super.onDraw(canvas);
    }

    protected int getDefaultColor() {
        return normalColor;
    }

    protected int getDefaultStrokeWidth() {
        DisplayMetrics screen = getResources().getDisplayMetrics();
        return (int)(screen.heightPixels*0.004f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Ignore secondary touches on controls
        //
        // NB: We can get an additional pointer down if the user touches a non-StreamView area
        // while also touching an OSC control, even if that pointer down doesn't correspond to
        // an area of the OSC control.
        if (event.getActionIndex() != 0) {
            return true;
        }

        // The layout editor handles every touch while it is open
        if (editState.editing) {
            return false;
        }

        return onElementTouchEvent(event);
    }

    abstract protected void onElementDraw(Canvas canvas);

    abstract public boolean onElementTouchEvent(MotionEvent event);

    protected static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
//            System.out.println(text);
        }
    }

    public void setColors(int normalColor, int pressedColor) {
        this.normalColor = normalColor;
        this.pressedColor = pressedColor;

        invalidate();
    }


    public void setOpacity(int opacity) {
        int hexOpacity = opacity * 255 / 100;
        this.normalColor = (hexOpacity << 24) | (normalColor & 0x00FFFFFF);
        this.pressedColor = (hexOpacity << 24) | (pressedColor & 0x00FFFFFF);

        invalidate();
    }

    protected final float getPercent(float value, float percent) {
        return value / 100 * percent;
    }

    protected final int getCorrectWidth() {
        return getWidth() > getHeight() ? getHeight() : getWidth();
    }

    // ------------------------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------------------------

    public JSONObject getConfiguration() throws JSONException {
        return editState.toJson(this, getResources().getDisplayMetrics());
    }

    public void loadConfiguration(JSONObject configuration) throws JSONException {
        editState.fromJson(this, configuration, getResources().getDisplayMetrics());
    }
}
