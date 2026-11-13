package com.limelight.binding.input.overlay;

/**
 * The gamepad function a button sends, used to pick its look in each {@link ButtonStyle}.
 * Face buttons are named after the Xbox button they send (A = bottom position).
 */
public enum GamepadButton {
    FACE_A, FACE_B, FACE_X, FACE_Y,
    LB, RB, LT, RT,
    BACK, START, GUIDE,
    L3, R3,
    TOUCHPAD
}
