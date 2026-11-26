/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.ControllerHandler;
import com.limelight.binding.input.overlay.ButtonStyle;
import com.limelight.binding.input.overlay.EditableOverlay;
import com.limelight.binding.input.overlay.LayoutSlots;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

public class VirtualController implements EditableOverlay {
    public static class ControllerInputContext {
//        public short inputMap = 0x0000;
        public int inputMap = 0;
        public byte leftTrigger = 0x00;
        public byte rightTrigger = 0x00;
        public short rightStickX = 0x0000;
        public short rightStickY = 0x0000;
        public short leftStickX = 0x0000;
        public short leftStickY = 0x0000;
    }

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final ControllerHandler controllerHandler;
    private final Context context;
    private final Handler handler;

    private final Runnable delayedRetransmitRunnable = new Runnable() {
        @Override
        public void run() {
            sendControllerInputContextInternal();
        }
    };

    private FrameLayout frame_layout = null;

    ControllerInputContext inputContext = new ControllerInputContext();

    private Button buttonConfigure = null;
    private Runnable onConfigureListener;

    private List<VirtualControllerElement> elements = new ArrayList<>();

    private Vibrator vibrator;

    private final VibrationEffect defaultVibrationEffect;

    private boolean editing = false;
    private boolean shown = true;
    private ButtonStyle buttonStyle;

    /**
     * @param controllerHandler may be null when the overlay is only displayed by the layout editor
     */
    public VirtualController(final ControllerHandler controllerHandler, FrameLayout layout, final Context context) {
        this.controllerHandler = controllerHandler;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        this.buttonStyle = ButtonStyle.fromPreferences(context);

        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            defaultVibrationEffect = VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE);
        } else {
            defaultVibrationEffect = null;
        }

        buttonConfigure = new Button(context);
        buttonConfigure.setAlpha(0.25f);
        buttonConfigure.setFocusable(false);
        buttonConfigure.setBackgroundResource(R.drawable.ic_settings);
        buttonConfigure.setContentDescription(context.getString(R.string.overlay_editor_title));
        buttonConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onConfigureListener != null) {
                    onConfigureListener.run();
                }
            }
        });
    }

    /** Called when the user taps the overlay's configure button (opens the layout editor). */
    public void setOnConfigureListener(Runnable listener) {
        this.onConfigureListener = listener;
        updateConfigureButtonVisibility();
    }

    Handler getHandler() {
        return handler;
    }

    public ButtonStyle getButtonStyle() {
        return buttonStyle;
    }

    public void hide() {
        shown = false;
        for (VirtualControllerElement element : elements) {
            element.setVisibility(View.GONE);
        }

        buttonConfigure.setVisibility(View.GONE);
    }

    public void show() {
        shown = true;
        showEnabledElements();
        updateConfigureButtonVisibility();
    }

    public boolean isShown() {
        return shown;
    }

    public int switchShowHide() {
        if (shown) {
            hide();
            return 0;
        } else {
            show();
            return 1;
        }
    }

    public void showEnabledElements() {
        for (VirtualControllerElement element : elements) {
            element.getEditState().editing = editing;
            element.getEditState().applyVisibility(element);
        }
    }

    private void updateConfigureButtonVisibility() {
        buttonConfigure.setVisibility(shown && !editing && onConfigureListener != null ? View.VISIBLE : View.GONE);
    }

    public void removeElements() {
        for (VirtualControllerElement element : elements) {
            frame_layout.removeView(element);
        }
        elements.clear();

        frame_layout.removeView(buttonConfigure);
    }

    public void setOpacity(int opacity) {
        for (VirtualControllerElement element : elements) {
            element.setOpacity(opacity);
        }
    }


    public void addElement(VirtualControllerElement element, int x, int y, int width, int height) {
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);

        element.getEditState().defaultBounds.set(x, y, x + width, y + height);

        frame_layout.addView(element, layoutParams);
    }

    public List<VirtualControllerElement> getElements() {
        return elements;
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        removeElements();

        buttonStyle = ButtonStyle.fromPreferences(context);

        DisplayMetrics screen = context.getResources().getDisplayMetrics();

        int buttonSize = (int)(screen.heightPixels*0.06f);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        params.leftMargin = 15;
        params.topMargin = 15;
        frame_layout.addView(buttonConfigure, params);

        // Start with the default layout
        VirtualControllerConfigurationLoader.createDefaultLayout(this, context);

        // Apply user preferences onto the default layout
        VirtualControllerConfigurationLoader.loadFromPreferences(this, context);

        if (shown) {
            show();
        } else {
            hide();
        }
    }

    // ------------------------------------------------------------------------------------
    // EditableOverlay
    // ------------------------------------------------------------------------------------

    @Override
    public LayoutSlots.Kind getKind() {
        return LayoutSlots.Kind.GAMEPAD;
    }

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.overlay_editor_tab_gamepad);
    }

    @Override
    public List<VirtualControllerElement> getEditableElements() {
        return elements;
    }

    @Override
    public void setEditing(boolean editing) {
        this.editing = editing;
        if (editing) {
            // Release anything that may be held when the editor opens
            inputContext = new ControllerInputContext();
            sendControllerInputContextInternal();
        }
        if (shown) {
            showEnabledElements();
        }
        updateConfigureButtonVisibility();
        for (VirtualControllerElement element : elements) {
            element.invalidate();
        }
    }

    @Override
    public boolean isEditing() {
        return editing;
    }

    @Override
    public void saveLayout() {
        VirtualControllerConfigurationLoader.saveProfile(this, context);
    }

    @Override
    public void reloadLayout() {
        refreshLayout();
    }

    // ------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------

    public ControllerInputContext getControllerInputContext() {
        return inputContext;
    }

    private void sendControllerInputContextInternal() {
        _DBG("INPUT_MAP + " + inputContext.inputMap);
        _DBG("LEFT_TRIGGER " + inputContext.leftTrigger);
        _DBG("RIGHT_TRIGGER " + inputContext.rightTrigger);
        _DBG("LEFT STICK X: " + inputContext.leftStickX + " Y: " + inputContext.leftStickY);
        _DBG("RIGHT STICK X: " + inputContext.rightStickX + " Y: " + inputContext.rightStickY);

        if (controllerHandler != null) {
            controllerHandler.reportOscState(
                    inputContext.inputMap,
                    inputContext.leftStickX,
                    inputContext.leftStickY,
                    inputContext.rightStickX,
                    inputContext.rightStickY,
                    inputContext.leftTrigger,
                    inputContext.rightTrigger
            );
        }
    }

    public void sendControllerInputContext(long vibrationDuration, int vibrationAmplitude) {
        if (controllerHandler == null) {
            // Preview only (layout editor)
            return;
        }

        // Cancel retransmissions of prior gamepad inputs
        handler.removeCallbacks(delayedRetransmitRunnable);

        sendControllerInputContextInternal();
        if (frame_layout != null && PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                VibrationEffect effect;
                if (vibrationDuration == 0) {
                    effect = defaultVibrationEffect;
                } else {
                    effect = VibrationEffect.createOneShot(vibrationDuration, vibrationAmplitude);
                }
                vibrator.vibrate(effect);
            } else {
                if (vibrationDuration == 0) {
                    vibrationDuration = 10;
                }
                vibrator.vibrate(vibrationDuration);
            }
        }
        // HACK: GFE sometimes discards gamepad packets when they are received
        // very shortly after another. This can be critical if an axis zeroing packet
        // is lost and causes an analog stick to get stuck. To avoid this, we retransmit
        // the gamepad state a few times unless another input event happens before then.
        handler.postDelayed(delayedRetransmitRunnable, 25);
        handler.postDelayed(delayedRetransmitRunnable, 50);
        handler.postDelayed(delayedRetransmitRunnable, 75);
    }

    public void sendControllerInputContext() {
        sendControllerInputContext(0, 0);
    }
}
