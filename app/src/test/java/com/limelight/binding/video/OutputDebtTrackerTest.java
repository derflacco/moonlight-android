package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OutputDebtTrackerTest {
    @Test
    public void mapsExactPtsAndResetsAcrossEpochs() {
        OutputDebtTracker tracker = new OutputDebtTracker();
        OutputDebtTracker.Observation observation = new OutputDebtTracker.Observation();

        tracker.onQueued(1_000, 1_000_000_000L);
        tracker.onQueued(18_000, 1_010_000_000L);
        tracker.onQueued(35_000, 1_020_000_000L);
        tracker.observe(1_000, 1_030_000_000L, observation);

        assertTrue(OutputDebtTracker.matched(observation.packed));
        assertEquals(2, OutputDebtTracker.lagFrames(observation.packed));
        assertEquals(34_000, OutputDebtTracker.lagPtsUs(observation.packed));
        assertEquals(30_000, observation.residenceUs);

        tracker.newEpoch();
        tracker.observe(35_000, 1_040_000_000L, observation);
        assertFalse(OutputDebtTracker.matched(observation.packed));
    }
}
