package com.limelight.preferences;

/**
 * Shared static compatibility policy for NVIDIA latency recovery.
 *
 * The preference may remain enabled while a stream configuration is incompatible. In that case
 * the renderer does not instantiate the controller and the settings UI reports the inactive reason.
 * Runtime-only conditions (codec restart, stopping, missing decoder, async callback readiness) are
 * intentionally handled by the renderer's dynamic mode mask rather than here.
 */
public final class NvidiaLatencyRecoveryEligibility {
    public static final int MASK_UNSUPPORTED_PACING = 1;
    public static final int MASK_SYNC_LFR = 4;
    public static final int MASK_FAST_VSYNC = 8;

    private NvidiaLatencyRecoveryEligibility() { }

    public static boolean isPacingSupported(int pacing) {
        switch (pacing) {
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
            case PreferenceConfiguration.FRAME_PACING_GPU_RAW:
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                return true;
            default:
                return false;
        }
    }

    public static int staticModeMask(int pacing, boolean asyncCodec,
                                     boolean syncLfrEnabled, boolean fastVsync) {
        int mask = isPacingSupported(pacing) ? 0 : MASK_UNSUPPORTED_PACING;
        // Sync LFR drains stale output itself and conflicts only with synchronous MediaCodec.
        if (!asyncCodec && syncLfrEnabled) mask |= MASK_SYNC_LFR;
        if (fastVsync) mask |= MASK_FAST_VSYNC;
        return mask;
    }

    public static String primaryInactiveReason(int mask) {
        if ((mask & MASK_UNSUPPORTED_PACING) != 0) return "selected frame pacing mode";
        if ((mask & MASK_SYNC_LFR) != 0) return "Sync LFR";
        if ((mask & MASK_FAST_VSYNC) != 0) return "FastVSync";
        return null;
    }
}
