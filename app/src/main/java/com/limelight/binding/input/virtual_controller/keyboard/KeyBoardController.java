/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller.keyboard;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.overlay.EditableOverlay;
import com.limelight.binding.input.overlay.LayoutSlots;
import com.limelight.nvstream.NvConnection;
import com.limelight.preferences.PreferenceConfiguration;

import java.util.ArrayList;
import java.util.List;

public class KeyBoardController implements EditableOverlay {

    public boolean shown = false;

    private static final boolean _PRINT_DEBUG_INFORMATION = false;

    private final NvConnection conn;
    private final Context context;
    private final Handler handler;

    private FrameLayout frame_layout = null;

    private Button buttonConfigure = null;
    private Runnable onConfigureListener;

    private Vibrator vibrator;
    private List<keyBoardVirtualControllerElement> elements = new ArrayList<>();

    private boolean editing = false;

    /**
     * @param conn may be null when the overlay is only displayed by the layout editor
     */
    public KeyBoardController(final NvConnection conn, FrameLayout layout, final Context context) {
        this.conn = conn;
        this.frame_layout = layout;
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());

        this.vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);

        // Configure button
        buttonConfigure = new Button(context);
        buttonConfigure.setAlpha(0.5f);
        buttonConfigure.setFocusable(false);
        buttonConfigure.setBackgroundResource(R.drawable.ic_keyboard_setting);
        buttonConfigure.setContentDescription(context.getString(R.string.overlay_editor_title));

        // Add long click listener for moving the configure button
        buttonConfigure.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Toast.makeText(context, context.getString(R.string.keyboard_configure_movable), Toast.LENGTH_SHORT).show();
                buttonConfigure.setTag("movable");
                vibrator.vibrate(100); // Give haptic feedback
                return true;
            }
        });

        // Add touch listener for moving
        buttonConfigure.setOnTouchListener(new View.OnTouchListener() {
            private float dX, dY;
            private float lastTouchX, lastTouchY;
            private boolean isMoving = false;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if ("movable".equals(view.getTag())) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            dX = view.getX() - event.getRawX();
                            dY = view.getY() - event.getRawY();
                            lastTouchX = event.getRawX();
                            lastTouchY = event.getRawY();
                            isMoving = false;
                            break;

                        case MotionEvent.ACTION_MOVE:
                            float newX = event.getRawX() + dX;
                            float newY = event.getRawY() + dY;

                            // Check if actually moving (to differentiate from simple touch)
                            if (Math.abs(event.getRawX() - lastTouchX) > 5 ||
                                Math.abs(event.getRawY() - lastTouchY) > 5) {
                                isMoving = true;
                            }

                            if (isMoving) {
                                // Keep button within screen bounds
                                newX = Math.max(0, Math.min(newX, frame_layout.getWidth() - view.getWidth()));
                                newY = Math.max(0, Math.min(newY, frame_layout.getHeight() - view.getHeight()));

                                view.setX(newX);
                                view.setY(newY);
                            }
                            break;

                        case MotionEvent.ACTION_UP:
                            view.setTag(null);
                            if (!isMoving) {
                                // If not moving, trigger the click event
                                view.performClick();
                            }
                            break;
                    }
                    return true;
                }
                return false;
            }
        });

        buttonConfigure.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (onConfigureListener != null) {
                    onConfigureListener.run();
                }
            }
        });

        refreshLayout();
    }

    /** Called when the user taps the overlay's configure button (opens the layout editor). */
    public void setOnConfigureListener(Runnable listener) {
        this.onConfigureListener = listener;
        updateConfigureButtonVisibility();
    }

    Handler getHandler() {
        return handler;
    }

    public void hide(boolean temporary) {
        for (keyBoardVirtualControllerElement element : elements) {
            element.setVisibility(View.GONE);
        }

        buttonConfigure.setVisibility(View.GONE);
        if (!temporary) {
            shown = false;
        }
    }

    public void hide() {
        hide(false);
    }

    public void show() {
        shown = true;
        showEnabledElements();
        updateConfigureButtonVisibility();
    }

    public void showEnabledElements() {
        for (keyBoardVirtualControllerElement element : elements) {
            element.getEditState().editing = editing;
            element.getEditState().applyVisibility(element);
        }
    }

    private void updateConfigureButtonVisibility() {
        buttonConfigure.setVisibility(shown && !editing && onConfigureListener != null ? View.VISIBLE : View.GONE);
    }

    public void toggleVisibility() {
        if (shown) {
            hide();
        } else {
            show();
        }
    }

    public void removeElements() {
        for (keyBoardVirtualControllerElement element : elements) {
            frame_layout.removeView(element);
        }
        elements.clear();

        frame_layout.removeView(buttonConfigure);
    }

    public void setOpacity(int opacity) {
        for (keyBoardVirtualControllerElement element : elements) {
            element.setOpacity(opacity);
        }
    }

    public void addElement(keyBoardVirtualControllerElement element, int x, int y, int width, int height) {
        elements.add(element);
        FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
        layoutParams.setMargins(x, y, 0, 0);

        element.getEditState().defaultBounds.set(x, y, x + width, y + height);

        frame_layout.addView(element, layoutParams);
    }

    public List<keyBoardVirtualControllerElement> getElements() {
        return elements;
    }

    private static final void _DBG(String text) {
        if (_PRINT_DEBUG_INFORMATION) {
            LimeLog.info("VirtualController: " + text);
        }
    }

    public void refreshLayout() {
        removeElements();

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        int buttonSize = (int) (screen.heightPixels * 0.06f);

        // Configure button at original position
        FrameLayout.LayoutParams configParams = new FrameLayout.LayoutParams(buttonSize, buttonSize);
        configParams.leftMargin = 20 + buttonSize;
        configParams.topMargin = 15;
        frame_layout.addView(buttonConfigure, configParams);

        // Apply default layout
        KeyBoardControllerConfigurationLoader.createDefaultLayout(this, context, conn);
        KeyBoardControllerConfigurationLoader.loadFromPreferences(this, context);

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
        return LayoutSlots.Kind.KEYBOARD;
    }

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.overlay_editor_tab_keyboard);
    }

    @Override
    public List<keyBoardVirtualControllerElement> getEditableElements() {
        return elements;
    }

    @Override
    public void setEditing(boolean editing) {
        this.editing = editing;
        if (shown) {
            showEnabledElements();
        }
        updateConfigureButtonVisibility();
        for (keyBoardVirtualControllerElement element : elements) {
            element.invalidate();
        }
    }

    @Override
    public boolean isEditing() {
        return editing;
    }

    @Override
    public void saveLayout() {
        KeyBoardControllerConfigurationLoader.saveProfile(this, context);
    }

    @Override
    public void reloadLayout() {
        refreshLayout();
    }

    // ------------------------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------------------------

    public void sendKeyEvent(KeyEvent keyEvent) {
        if (editing || Game.instance == null || !Game.instance.connected) {
            return;
        }
        //1-鼠标 0-按键 2-摇杆 3-十字键
        if (keyEvent.getSource() == 1) {
            Game.instance.mouseButtonEvent(keyEvent.getKeyCode(), KeyEvent.ACTION_DOWN == keyEvent.getAction());
        } else {
            Game.instance.onKey(null, keyEvent.getKeyCode(), keyEvent);
        }

        if (keyEvent.getSource() != 2) {
            vibrate(keyEvent.getAction());
        }
    }

    public void sendMouseMove(int x,int y){
        if (editing || Game.instance == null || !Game.instance.connected) {
            return;
        }
        Game.instance.mouseMove(x,y);
    }

    public void vibrate(int action) {
        if (PreferenceConfiguration.readPreferences(context).enableKeyboardVibrate && vibrator.hasVibrator()) {
            switch (action) {
                case KeyEvent.ACTION_DOWN:
                    frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    break;
                case KeyEvent.ACTION_UP:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE);
                    } else {
                        frame_layout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    }
                    break;
                default:
                    frame_layout.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            }
        }
    }
}
