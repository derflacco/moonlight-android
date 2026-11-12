package com.limelight.binding.video;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NvidiaLatencyRecoveryGateTest {
    private static final long FRAME_NS = 16_666_667L;

    private static NvidiaLatencyRecoveryGate configured(long now) {
        NvidiaLatencyRecoveryGate gate = new NvidiaLatencyRecoveryGate();
        gate.mode(0, 60f, now);
        return gate;
    }

    @Test
    public void structuralRecoveryStopsAtExactZero() {
        long start = 10_000_000_000L;
        NvidiaLatencyRecoveryGate gate = configured(start);
        NvidiaLatencyRecoveryGate.Decision decision = null;
        for (int i = 0; i < 40 && decision == null; i++) {
            decision = gate.decide(start + i * FRAME_NS, true, false, true, 3, 50_000, 35_000);
        }
        assertNotNull(decision);
        assertTrue(decision.drop);
        gate.done(decision, true);

        for (int lag = 2; lag >= 1; lag--) {
            NvidiaLatencyRecoveryGate.Decision drop = gate.decide(
                    start + (50 - lag) * FRAME_NS, true, false, true,
                    lag, lag * 17_000L, 30_000);
            assertNotNull(drop);
            assertTrue(drop.drop);
            gate.done(drop, true);
        }

        NvidiaLatencyRecoveryGate.Decision end = gate.decide(
                start + 51 * FRAME_NS, true, false, true, 0, 0, 2_000);
        assertNotNull(end);
        assertFalse(end.drop);
        assertEquals(NvidiaLatencyRecoveryGate.EVENT_CATCHUP_END, end.eventKind);
    }

    @Test
    public void timeWeightedOccupancyRejectsSampleCountFalsePositive() {
        long now = 20_000_000_000L;
        NvidiaLatencyRecoveryGate gate = configured(now);
        NvidiaLatencyRecoveryGate.Decision hit = null;
        for (int cycle = 0; cycle < 100 && hit == null; cycle++) {
            for (int j = 0; j < 4 && hit == null; j++) {
                boolean positive = j < 3; // 75% samples, but only 25% elapsed time positive
                long dt = positive ? 5_000_000L : 45_000_000L;
                if (!(cycle == 0 && j == 0)) now += dt;
                hit = gate.decide(now, true, false, true,
                        positive ? 4 : 0, positive ? 66_000 : 0,
                        positive ? 70_000 : 2_000);
            }
        }
        assertNull(hit);
    }

    @Test
    public void intermittentZeroDoesNotErasePersistentStructuralDebt() {
        long start = 30_000_000_000L;
        NvidiaLatencyRecoveryGate gate = configured(start);
        NvidiaLatencyRecoveryGate.Decision hit = null;
        for (int i = 0; i < 160 && hit == null; i++) {
            boolean zero = (i % 5) == 4;
            hit = gate.decide(start + i * FRAME_NS, true, false, true,
                    zero ? 0 : 1, zero ? 0 : 17_000, zero ? 20_000 : 30_000);
        }
        assertNotNull(hit);
        assertTrue(hit.drop);
        assertEquals(NvidiaLatencyRecoveryGate.EVENT_CATCHUP_DROP, hit.eventKind);
        assertTrue(hit.lagFrames > 0);
    }

    @Test
    public void maxDropLimitRemainsBoundedAtTwelve() {
        long start = 40_000_000_000L;
        NvidiaLatencyRecoveryGate gate = configured(start);
        NvidiaLatencyRecoveryGate.Decision first = null;
        for (int i = 0; i < 40 && first == null; i++) {
            first = gate.decide(start + i * FRAME_NS, true, false, true, 13, 220_000, 230_000);
        }
        assertNotNull(first);
        gate.done(first, true);
        for (int n = 0; n < 11; n++) {
            int lag = 12 - n;
            NvidiaLatencyRecoveryGate.Decision drop = gate.decide(
                    start + (50 + n) * FRAME_NS, true, false, true,
                    lag, lag * 17_000L, lag * 17_000L + 1_000);
            assertNotNull(drop);
            assertTrue(drop.drop);
            gate.done(drop, true);
        }
        NvidiaLatencyRecoveryGate.Decision end = gate.decide(
                start + 62 * FRAME_NS, true, false, true, 1, 17_000, 18_000);
        assertNotNull(end);
        assertFalse(end.drop);
        assertEquals(NvidiaLatencyRecoveryGate.EVENT_CATCHUP_END, end.eventKind);
        assertTrue(gate.result().startsWith("CATCHUP_LIMIT_"));
    }

    @Test
    public void losingEligibilityFencesOldEpisode() {
        long start = 50_000_000_000L;
        NvidiaLatencyRecoveryGate gate = configured(start);
        NvidiaLatencyRecoveryGate.Decision hit = null;
        for (int i = 0; i < 40 && hit == null; i++) {
            hit = gate.decide(start + i * FRAME_NS, true, false, true, 3, 50_000, 35_000);
        }
        assertNotNull(hit);
        gate.done(hit, true);

        gate.mode(1, 60f, start + 45 * FRAME_NS);
        gate.mode(0, 60f, start + 60 * FRAME_NS);
        assertNull(gate.decide(start + 61 * FRAME_NS, true, false, true, 2, 34_000, 35_000));
    }
}
