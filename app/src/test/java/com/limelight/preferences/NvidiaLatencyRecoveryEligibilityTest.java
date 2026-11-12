package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class NvidiaLatencyRecoveryEligibilityTest {
    @Test
    public void supportsValidatedPacingModes() {
        assertEquals(0, NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, false, false, false));
        assertEquals(0, NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_GPU_RAW, true, true, false));
        assertEquals(0, NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS, true, false, false));
    }

    @Test
    public void blocksKnownConflictsWithoutChangingUserPreferences() {
        assertNotEquals(0, NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_BALANCED, true, false, false));
        assertNotEquals(0, NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, false, true, false));
        assertNotEquals(0, NvidiaLatencyRecoveryEligibility.staticModeMask(
                PreferenceConfiguration.FRAME_PACING_MIN_LATENCY, true, false, true));
    }
}
