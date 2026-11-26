/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

import com.limelight.R;
import com.limelight.binding.input.overlay.ButtonStyle;
import com.limelight.binding.input.overlay.GamepadButton;
import com.limelight.binding.input.overlay.GamepadStyleRenderer;
import com.limelight.binding.input.overlay.LayoutSlots;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

public class VirtualControllerConfigurationLoader {
    /** File of the first gamepad layout slot (kept for compatibility). */
    public static final String OSC_PREFERENCE = "OSC";

    // The default controls are specified using a grid of 128*72 cells at 16:9
    private static int screenScale(int units, int height) {
        return (int) (((float) height / (float) 72) * (float) units);
    }

    private static DigitalPad createDigitalPad(
            final VirtualController controller,
            final Context context) {

        DigitalPad digitalPad = new DigitalPad(controller, context);
        digitalPad.setDisplayName(context.getString(R.string.overlay_element_dpad));
        digitalPad.addDigitalPadListener(new DigitalPad.DigitalPadListener() {
            @Override
            public void onDirectionChange(int direction) {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();

                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    inputContext.inputMap |= ControllerPacket.LEFT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.LEFT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    inputContext.inputMap |= ControllerPacket.RIGHT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.RIGHT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_UP) != 0) {
                    inputContext.inputMap |= ControllerPacket.UP_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.UP_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    inputContext.inputMap |= ControllerPacket.DOWN_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.DOWN_FLAG;
                }

                controller.sendControllerInputContext(10, 0x22);
            }
        });

        return digitalPad;
    }

    private static DigitalButton createDigitalButton(
            final int elementId,
            final GamepadButton function,
            final int keyShort,
            final int keyLong,
            final int layer,
            final String text,
            final int icon,
            final int iconPress,
            final VirtualController controller,
            final Context context) {
        DigitalButton button = new DigitalButton(controller, elementId, layer, context);
        button.setText(text);
        button.setIcon(icon);
        button.setIconPress(iconPress);
        button.setFunction(function);
        button.setDisplayName(GamepadStyleRenderer.buttonName(controller.getButtonStyle(), function));
        button.addDigitalButtonListener(new DigitalButton.DigitalButtonListener() {
            @Override
            public void onClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyShort;

                controller.sendControllerInputContext();
            }

            @Override
            public void onLongClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyLong;

                controller.sendControllerInputContext();
            }

            @Override
            public void onRelease() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap &= ~keyShort;
                inputContext.inputMap &= ~keyLong;

                controller.sendControllerInputContext();
            }
        });

        return button;
    }

    private static DigitalButton createLeftTrigger(
            final int layer,
            final String text,
            final int icon,
            final int iconPress,
            final VirtualController controller,
            final Context context) {
        LeftTrigger button = new LeftTrigger(controller, layer, context);
        button.setText(text);
        button.setIcon(icon);
        button.setIconPress(iconPress);
        button.setFunction(GamepadButton.LT);
        button.setDisplayName(GamepadStyleRenderer.buttonName(controller.getButtonStyle(), GamepadButton.LT));
        return button;
    }

    private static DigitalButton createRightTrigger(
            final int layer,
            final String text,
            final int icon,
            final int iconPress,
            final VirtualController controller,
            final Context context) {
        RightTrigger button = new RightTrigger(controller, layer, context);
        button.setText(text);
        button.setIcon(icon);
        button.setIconPress(iconPress);
        button.setFunction(GamepadButton.RT);
        button.setDisplayName(GamepadStyleRenderer.buttonName(controller.getButtonStyle(), GamepadButton.RT));
        return button;
    }

    private static VirtualControllerElement createStick(
            final boolean left,
            final boolean free,
            final VirtualController controller,
            final Context context) {
        VirtualControllerElement stick;
        if (free) {
            stick = left ? new LeftAnalogStickFree(controller, context) : new RightAnalogStickFree(controller, context);
        } else {
            stick = left ? new LeftAnalogStick(controller, context) : new RightAnalogStick(controller, context);
        }
        stick.setDisplayName(context.getString(left ? R.string.overlay_element_left_stick : R.string.overlay_element_right_stick));
        return stick;
    }


    private static final int TRIGGER_L_BASE_X = 1;
    private static final int TRIGGER_R_BASE_X = 92;
    private static final int TRIGGER_DISTANCE = 23;
    private static final int TRIGGER_BASE_Y = 31;
    private static final int TRIGGER_WIDTH = 12;
    private static final int TRIGGER_HEIGHT = 9;

    // Face buttons are defined based on the Y button (button number 9)
    private static final int BUTTON_BASE_X = 106;
    private static final int BUTTON_BASE_Y = 1;
    private static final int BUTTON_SIZE = 10;

    private static final int DPAD_BASE_X = 4;
    private static final int DPAD_BASE_Y = 41;
    private static final int DPAD_SIZE = 30;

    private static final int ANALOG_L_BASE_X = 6;
    private static final int ANALOG_L_BASE_Y = 4;
    private static final int ANALOG_R_BASE_X = 98;
    private static final int ANALOG_R_BASE_Y = 42;
    private static final int ANALOG_SIZE = 26;

    private static final int L3_R3_BASE_Y = 60;

    private static final int START_X = 83;
    private static final int BACK_X = 34;
    private static final int START_BACK_Y = 64;
    private static final int START_BACK_WIDTH = 12;
    private static final int START_BACK_HEIGHT = 7;

    // Make the Guide Menu be in the center of START and BACK menu
    private static final int GUIDE_X = START_X-BACK_X;
    private static final int GUIDE_Y = START_BACK_Y;

    public static void createDefaultLayout(final VirtualController controller, final Context context) {

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);

        // Displace controls on the right by this amount of pixels to account for different aspect ratios
        int rightDisplacement = screen.widthPixels - screen.heightPixels * 16 / 9;

        int height = screen.heightPixels;

        // NOTE: Some of these getPercent() expressions seem like they can be combined
        // into a single call. Due to floating point rounding, this isn't actually possible.

        controller.addElement(createDigitalPad(controller, context),
                screenScale(DPAD_BASE_X, height),
                screenScale(DPAD_BASE_Y, height),
                screenScale(DPAD_SIZE, height),
                screenScale(DPAD_SIZE, height)
        );

        controller.addElement(createDigitalButton(
                VirtualControllerElement.EID_A,
                !config.flipFaceButtons ? GamepadButton.FACE_A : GamepadButton.FACE_B,
                !config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                !config.flipFaceButtons ? "A" : "B", R.drawable.facebutton_a,R.drawable.facebutton_a_press, controller, context),
                screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + 2 * BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
        );

        controller.addElement(createDigitalButton(
                VirtualControllerElement.EID_B,
                config.flipFaceButtons ? GamepadButton.FACE_A : GamepadButton.FACE_B,
                config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                config.flipFaceButtons ? "A" : "B", R.drawable.facebutton_b,R.drawable.facebutton_b_press, controller, context),
                screenScale(BUTTON_BASE_X + BUTTON_SIZE, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
        );

        controller.addElement(createDigitalButton(
                VirtualControllerElement.EID_X,
                !config.flipFaceButtons ? GamepadButton.FACE_X : GamepadButton.FACE_Y,
                !config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                !config.flipFaceButtons ? "X" : "Y", R.drawable.facebutton_x,R.drawable.facebutton_x_press, controller, context),
                screenScale(BUTTON_BASE_X - BUTTON_SIZE, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
        );

        controller.addElement(createDigitalButton(
                VirtualControllerElement.EID_Y,
                config.flipFaceButtons ? GamepadButton.FACE_X : GamepadButton.FACE_Y,
                config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                config.flipFaceButtons ? "X" : "Y", R.drawable.facebutton_y,R.drawable.facebutton_y_press, controller, context),
                screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                screenScale(BUTTON_BASE_Y, height),
                screenScale(BUTTON_SIZE, height),
                screenScale(BUTTON_SIZE, height)
        );

        controller.addElement(createLeftTrigger(
                1, "LT", R.drawable.facebutton_zl,R.drawable.facebutton_zl_press, controller, context),
                screenScale(TRIGGER_L_BASE_X, height),
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
        );

        controller.addElement(createRightTrigger(
                1, "RT", R.drawable.facebutton_zr,R.drawable.facebutton_zr_press, controller, context),
                screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
        );

        controller.addElement(createDigitalButton(
                VirtualControllerElement.EID_LB, GamepadButton.LB,
                ControllerPacket.LB_FLAG, 0, 1, "LB", R.drawable.facebutton_l,R.drawable.facebutton_l_press, controller, context),
                screenScale(TRIGGER_L_BASE_X + TRIGGER_DISTANCE, height),
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
        );

        controller.addElement(createDigitalButton(
                VirtualControllerElement.EID_RB, GamepadButton.RB,
                ControllerPacket.RB_FLAG, 0, 1, "RB", R.drawable.facebutton_r,R.drawable.facebutton_r_press, controller, context),
                screenScale(TRIGGER_R_BASE_X, height) + rightDisplacement,
                screenScale(TRIGGER_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
        );

        controller.addElement(createStick(true, config.enableNewAnalogStick, controller, context),
                screenScale(ANALOG_L_BASE_X, height),
                screenScale(ANALOG_L_BASE_Y, height),
                screenScale(ANALOG_SIZE, height),
                screenScale(ANALOG_SIZE, height)
        );

        controller.addElement(createStick(false, config.enableNewAnalogStick, controller, context),
                screenScale(ANALOG_R_BASE_X, height) + rightDisplacement,
                screenScale(ANALOG_R_BASE_Y, height),
                screenScale(ANALOG_SIZE, height),
                screenScale(ANALOG_SIZE, height)
        );

        controller.addElement(createDigitalButton(
                VirtualControllerElement.EID_BACK, GamepadButton.BACK,
                ControllerPacket.BACK_FLAG, 0, 2, "BACK", R.drawable.facebutton_minus,R.drawable.facebutton_minus_press, controller, context),
                screenScale(BACK_X, height),
                screenScale(START_BACK_Y, height),
                screenScale(START_BACK_WIDTH, height),
                screenScale(START_BACK_HEIGHT, height)
        );

        controller.addElement(createDigitalButton(
                VirtualControllerElement.EID_START, GamepadButton.START,
                ControllerPacket.PLAY_FLAG, 0, 3, "START", R.drawable.facebutton_plus,R.drawable.facebutton_plus_press, controller, context),
                screenScale(START_X, height) + rightDisplacement,
                screenScale(START_BACK_Y, height),
                screenScale(START_BACK_WIDTH, height),
                screenScale(START_BACK_HEIGHT, height)
        );

        controller.addElement(createDigitalButton(
                        VirtualControllerElement.EID_LSB, GamepadButton.L3,
                        ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", R.drawable.facebutton_l3,R.drawable.facebutton_l3_press, controller, context),
                screenScale(TRIGGER_L_BASE_X, height),
                screenScale(L3_R3_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
        );

        controller.addElement(createDigitalButton(
                        VirtualControllerElement.EID_RSB, GamepadButton.R3,
                        ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", R.drawable.facebutton_r3,R.drawable.facebutton_r3_press, controller, context),
                screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                screenScale(L3_R3_BASE_Y, height),
                screenScale(TRIGGER_WIDTH, height),
                screenScale(TRIGGER_HEIGHT, height)
        );

        controller.addElement(createDigitalButton(
                        VirtualControllerElement.EID_TOUCHPAD, GamepadButton.TOUCHPAD,
                        ControllerPacket.TOUCHPAD_FLAG, 0, 1, "Trackpad", R.drawable.facebutton_touchpad_press,R.drawable.facebutton_touchpad, controller, context),
                screenScale(50, height),
                screenScale(50, height),
                screenScale(20, height),
                screenScale(12, height)
        );

        if(config.showGuideButton){
            controller.addElement(createDigitalButton(VirtualControllerElement.EID_GDB, GamepadButton.GUIDE,
                            ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 1, "GUIDE", -1, -1, controller, context),
                    screenScale(GUIDE_X, height)+ rightDisplacement,
                    screenScale(GUIDE_Y, height),
                    screenScale(START_BACK_WIDTH, height),
                    screenScale(START_BACK_HEIGHT, height)
            );
        }

        // Round controls keep their proportions by default when resized
        for (VirtualControllerElement element : controller.getElements()) {
            boolean round = element instanceof DigitalPad
                    || element instanceof AnalogStick
                    || element instanceof AnalogStickFree
                    || element.elementId == VirtualControllerElement.EID_A
                    || element.elementId == VirtualControllerElement.EID_B
                    || element.elementId == VirtualControllerElement.EID_X
                    || element.elementId == VirtualControllerElement.EID_Y;
            element.getEditState().defaultLockAspect = round;
            element.getEditState().lockAspect = round;
        }

        controller.setOpacity(config.oscOpacity);
    }

    public static void saveProfile(final VirtualController controller,
                                   final Context context) {
        SharedPreferences.Editor prefEditor = LayoutSlots.openActive(context, LayoutSlots.Kind.GAMEPAD).edit();

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;
            try {
                prefEditor.putString(prefKey, element.getConfiguration().toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        prefEditor.apply();
    }

    public static void loadFromPreferences(final VirtualController controller, final Context context) {
        SharedPreferences pref = LayoutSlots.openActive(context, LayoutSlots.Kind.GAMEPAD);

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;

            String jsonConfig = pref.getString(prefKey, null);
            if (jsonConfig != null) {
                try {
                    element.loadConfiguration(new JSONObject(jsonConfig));
                } catch (JSONException e) {
                    e.printStackTrace();

                    // Remove the corrupt element from the preferences
                    pref.edit().remove(prefKey).apply();
                }
            }
        }
    }
}
