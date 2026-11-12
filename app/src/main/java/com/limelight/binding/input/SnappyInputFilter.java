package com.limelight.binding.input;

/**
 * Suppresses insignificant gamepad state updates while preserving state transitions.
 * This class is intentionally allocation-free after construction because it runs on
 * the controller input path.
 */
final class SnappyInputFilter {
    static final int AXIS_DELTA_MIN = 64; // About 0.2% of the signed 16-bit range
    static final int TRIGGER_DELTA_MIN = 2; // Trigger range is 0-255

    private final boolean[] initialized;
    private final short[] lastLeftStickX;
    private final short[] lastLeftStickY;
    private final short[] lastRightStickX;
    private final short[] lastRightStickY;
    private final byte[] lastLeftTrigger;
    private final byte[] lastRightTrigger;
    private final int[] lastInputMap;

    SnappyInputFilter(int controllerCount) {
        if (controllerCount <= 0) {
            throw new IllegalArgumentException("controllerCount must be positive");
        }

        initialized = new boolean[controllerCount];
        lastLeftStickX = new short[controllerCount];
        lastLeftStickY = new short[controllerCount];
        lastRightStickX = new short[controllerCount];
        lastRightStickY = new short[controllerCount];
        lastLeftTrigger = new byte[controllerCount];
        lastRightTrigger = new byte[controllerCount];
        lastInputMap = new int[controllerCount];
    }

    boolean shouldSend(int controllerNumber, int inputMap,
                       byte leftTrigger, byte rightTrigger,
                       short leftStickX, short leftStickY,
                       short rightStickX, short rightStickY) {
        if (!isValidController(controllerNumber)) {
            // Fail open so an unexpected controller number never loses input.
            return true;
        }

        boolean changed = !initialized[controllerNumber]
                || inputMap != lastInputMap[controllerNumber]
                || axisChanged(leftStickX, lastLeftStickX[controllerNumber])
                || axisChanged(leftStickY, lastLeftStickY[controllerNumber])
                || axisChanged(rightStickX, lastRightStickX[controllerNumber])
                || axisChanged(rightStickY, lastRightStickY[controllerNumber])
                || triggerChanged(leftTrigger, lastLeftTrigger[controllerNumber])
                || triggerChanged(rightTrigger, lastRightTrigger[controllerNumber]);

        if (changed) {
            remember(controllerNumber, inputMap, leftTrigger, rightTrigger,
                    leftStickX, leftStickY, rightStickX, rightStickY);
        }

        return changed;
    }

    void reset(int controllerNumber) {
        if (isValidController(controllerNumber)) {
            initialized[controllerNumber] = false;
        }
    }

    private boolean isValidController(int controllerNumber) {
        return controllerNumber >= 0 && controllerNumber < initialized.length;
    }

    private static boolean axisChanged(short current, short previous) {
        return Math.abs((int) current - previous) >= AXIS_DELTA_MIN
                || current == 0 && previous != 0
                || current > 0 && previous < 0
                || current < 0 && previous > 0;
    }

    private static boolean triggerChanged(byte current, byte previous) {
        int currentUnsigned = current & 0xFF;
        int previousUnsigned = previous & 0xFF;
        return Math.abs(currentUnsigned - previousUnsigned) >= TRIGGER_DELTA_MIN
                || currentUnsigned == 0 && previousUnsigned != 0;
    }

    private void remember(int controllerNumber, int inputMap,
                          byte leftTrigger, byte rightTrigger,
                          short leftStickX, short leftStickY,
                          short rightStickX, short rightStickY) {
        initialized[controllerNumber] = true;
        lastInputMap[controllerNumber] = inputMap;
        lastLeftTrigger[controllerNumber] = leftTrigger;
        lastRightTrigger[controllerNumber] = rightTrigger;
        lastLeftStickX[controllerNumber] = leftStickX;
        lastLeftStickY[controllerNumber] = leftStickY;
        lastRightStickX[controllerNumber] = rightStickX;
        lastRightStickY[controllerNumber] = rightStickY;
    }
}
