package com.limelight.ui;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

import androidx.appcompat.view.WindowCallbackWrapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Controller navigation shared by the streaming menu and its child dialogs. */
public final class GamepadDialogNavigation {
    public interface SuspensionListener {
        void onSuspendedChanged(boolean suspended);
    }
    private final ArrayList<Navigation> dialogs = new ArrayList<>();
    private final Map<Long, Navigation> pressedKeys = new HashMap<>();
    private final SuspensionListener suspendStream;

    public GamepadDialogNavigation(SuspensionListener suspendStream) {
        this.suspendStream = suspendStream;
    }

    public boolean isOpen() {
        return !dialogs.isEmpty();
    }

    private static boolean isController(InputEvent event) {
        InputDevice device = event.getDevice();
        int sources = device != null ? device.getSources() : event.getSource();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    /** Also used by the accessibility service, which otherwise bypasses dialog windows. */
    public boolean handleKeyEvent(KeyEvent event) {
        long id = ((long) event.getDeviceId() << 32) | (event.getKeyCode() & 0xffffffffL);
        Navigation target = dialogs.isEmpty() ? null : dialogs.get(dialogs.size() - 1);
        Navigation pressed = pressedKeys.get(id);
        if (event.getAction() == KeyEvent.ACTION_UP && pressed != null) {
            pressedKeys.remove(id);
            // Consume the release even if confirmation already closed/replaced the dialog.
            if (pressed == target) target.key(event);
            return true;
        }
        // A held key from a dismissed dialog stays local until its matching release.
        if (target == null && pressed != null) return true;
        if (target == null || !isController(event)) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (pressed == null) pressedKeys.put(id, target);
            if (event.getRepeatCount() == 0 && pressed == null) target.key(event);
        }
        return true; // Other controller buttons never reach the stream or dialog shortcuts.
    }

    public boolean handleMotionEvent(MotionEvent event) {
        if (dialogs.isEmpty() || !isController(event)) return false;
        dialogs.get(dialogs.size() - 1).motion(event);
        return true;
    }

    /** Call immediately after show(); leaves existing dialog listeners untouched. */
    public void attach(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        if (!dialogs.isEmpty()) dialogs.get(dialogs.size() - 1).stopRepeat();
        Navigation navigation = new Navigation(dialog, window);
        window.setCallback(navigation);
        dialogs.add(navigation);
        suspendStream.onSuspendedChanged(true);
        window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                navigation.stopRepeat();
                dialogs.remove(navigation);
                view.removeOnAttachStateChangeListener(this);
                if (dialogs.isEmpty()) suspendStream.onSuspendedChanged(false);
            }
        });
    }

    private final class Navigation extends WindowCallbackWrapper {
        private final AlertDialog dialog;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private int keyDirection, axisDirection, direction;
        private final Runnable repeat = new Runnable() {
            @Override public void run() {
                if (direction == 0 || !dialog.isShowing() || dialogs.isEmpty()
                        || dialogs.get(dialogs.size() - 1) != Navigation.this) return;
                sendDirection(direction);
                handler.postDelayed(this, 120);
            }
        };

        Navigation(AlertDialog dialog, Window window) {
            super(window.getCallback());
            this.dialog = dialog;
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) {
            return handleKeyEvent(event) || super.dispatchKeyEvent(event);
        }

        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return handleMotionEvent(event) || super.dispatchGenericMotionEvent(event);
        }

        private void sendDirection(int key) {
            super.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key));
            super.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key));
        }

        void key(KeyEvent event) {
            int key = event.getKeyCode();
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (key >= KeyEvent.KEYCODE_DPAD_UP && key <= KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) keyDirection = key;
                else if (keyDirection == key) keyDirection = 0;
                updateDirection();
            } else if (!down && key == KeyEvent.KEYCODE_BUTTON_A) {
                sendDirection(KeyEvent.KEYCODE_DPAD_CENTER);
            } else if (!down && (key == KeyEvent.KEYCODE_BUTTON_B || key == KeyEvent.KEYCODE_BACK)) {
                dialog.cancel();
            }
        }

        void motion(MotionEvent event) {
            if ((event.getSource() & InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return;
            float x = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float y = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
                x = event.getAxisValue(MotionEvent.AXIS_X);
                y = event.getAxisValue(MotionEvent.AXIS_Y);
            }
            if (Math.max(Math.abs(x), Math.abs(y)) < 0.5f) axisDirection = 0;
            else if (Math.abs(y) >= Math.abs(x)) {
                axisDirection = y < 0 ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
            } else {
                axisDirection = x < 0 ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            }
            updateDirection();
        }

        private void updateDirection() {
            int next = keyDirection != 0 ? keyDirection : axisDirection;
            if (next == direction) return;
            handler.removeCallbacks(repeat);
            direction = next;
            if (direction != 0) {
                sendDirection(direction);
                handler.postDelayed(repeat, 400);
            }
        }

        void stopRepeat() {
            handler.removeCallbacks(repeat);
            keyDirection = axisDirection = direction = 0;
        }
    }
}
