package com.limelight.binding.video;

/**
 * Production NVIDIA latency recovery decision gate. No learned per-stream baseline is used.
 *
 * Structural branch: exact mapped debt with a bounded occupancy window plus severity evidence ->
 * bounded stale-output catch-up, max 12 drops, re-evaluated before every drop. Exact-zero samples
 * count against elapsed-time structural occupancy but no longer erase the whole structural episode.
 *
 * Phase branch: only exact lag zero. A phase episode requires residence above a frame-period-derived
 * threshold for a continuous, well-sampled interval. One output is omitted, then the result is
 * verified with a sampled health ratio. Sparse benign residence spikes do not create a false failure
 * or permanently suppress later mid-stream recovery. The phase branch cannot repeat until a
 * genuinely healthy sampled window re-arms it.
 */
final class NvidiaLatencyRecoveryGate {
    static final int MAX_DROPS = 12;
    static final int MAX_ACTIONS_WITHOUT_LONG_HEALTH = 6;

    static final long LIVE_OUTPUT_NS = 500_000_000L;
    static final long STRUCT_MIN_HOLD_NS = 250_000_000L;
    static final long STRUCT_EVIDENCE_NS = 750_000_000L; // frame * ns; +1 ~= 0.75s, +3 ~= 0.25s
    static final long STRUCT_WINDOW_NS = 1_500_000_000L;
    static final int STRUCT_OCCUPANCY_NUM = 3;
    static final int STRUCT_OCCUPANCY_DEN = 4;
    static final int STRUCT_RING_CAPACITY = 512; // >1.5s even at 240 Hz; fail-closed reset on overflow
    static final long POST_CATCHUP_PHASE_GUARD_NS = 500_000_000L;
    static final long PHASE_HOLD_NS = 750_000_000L;
    static final long PHASE_EVIDENCE_MAX_NS = 1_500_000_000L;
    static final long PHASE_VERIFY_NS = 1_500_000_000L;
    static final long PHASE_VERIFY_MIN_NS = 500_000_000L;
    static final long PHASE_REARM_WINDOW_NS = 2_000_000_000L;
    static final int HEALTH_RATIO_NUM = 3;
    static final int HEALTH_RATIO_DEN = 4;
    static final long LONG_HEALTH_RESET_NS = 30_000_000_000L;
    static final long STARTUP_PHASE_GUARD_NS = 1_000_000_000L;

    static final int EVENT_FAILED = 13;
    static final int EVENT_CATCHUP_DROP = 14;
    static final int EVENT_CATCHUP_END = 15;
    static final int EVENT_PHASE_DROP = 16;
    static final int EVENT_PHASE_END = 17;

    static final int TYPE_AUTO_CATCHUP = 2;
    static final int TYPE_AUTO_PHASE = 3;

    static final class Request {
        final long id, epoch;
        final int type;
        Request(long id, long epoch, int type) { this.id = id; this.epoch = epoch; this.type = type; }
    }

    static final class Decision {
        final Request request;
        final boolean drop;
        final int eventKind;
        final int lagFrames;
        final long lagPtsUs;
        final long residenceUs;
        Decision(Request request, boolean drop, int eventKind, int lagFrames, long lagPtsUs, long residenceUs) {
            this.request = request; this.drop = drop; this.eventKind = eventKind;
            this.lagFrames = lagFrames; this.lagPtsUs = lagPtsUs; this.residenceUs = residenceUs;
        }
    }

    private Request activeCatchup;
    private volatile boolean active = true;
    private volatile long epoch = 1, lastOutputNs;
    private volatile int modeMask = 1;

    private long periodNs = 16_666_667L;
    private long periodUs = 16_667L;
    private long minLagPtsUs = 8_333L;
    private long phaseEnterUs = 4_167L;
    private long phaseExitUs = 2_500L;
    private long phaseShallowLagPtsMaxUs = 25_000L;
    private int phaseMinSamples = 30;
    private long continuityGapNs = 50_000_000L;

    private long nextActionId = 1;
    private int actionsWithoutLongHealth;
    private boolean safetyLocked;

    // Structural detector. Fixed ring keeps the hot path O(1) with no per-frame allocations.
    private final long[] structTimesNs = new long[STRUCT_RING_CAPACITY];
    private final long[] structEvidenceNs = new long[STRUCT_RING_CAPACITY];
    private final long[] structDurationNs = new long[STRUCT_RING_CAPACITY];
    private final boolean[] structPositive = new boolean[STRUCT_RING_CAPACITY];
    private int structHead, structSize;
    private long structLastNs, structEvidenceFrameNs;
    private long structWindowDurationNs, structPositiveDurationNs;
    private int dropsDone;
    private boolean structLimitLock;

    // Phase detector / verification / re-arm.
    private boolean phaseArmed = true;
    private boolean phaseVerifying;
    private boolean phaseFailedLock;
    private long phaseEligibleAfterNs;
    private long phaseStartNs, phaseLastNs;
    private int phaseTotalSamples, phaseZeroSamples, phaseHighZeroSamples;
    private long phaseVerifyDeadlineNs, phaseVerifyStartNs, phaseVerifyLastNs;
    private int phaseVerifySamples, phaseVerifyHealthySamples;
    private long phaseRearmStartNs, phaseRearmLastNs;
    private int phaseRearmSamples, phaseRearmHealthySamples;
    private long longHealthStartNs, longHealthLastNs;
    private int longHealthSamples, longHealthHealthySamples;

    private volatile String result = "IDLE";

    long epoch() { return epoch; }

    synchronized void mode(int mask, float fps, long now) {
        final int previousMask = modeMask;
        modeMask = mask;
        if (fps > 1f) {
            periodNs = Math.max(1_000_000L, (long)(1_000_000_000.0 / fps));
            periodUs = Math.max(1_000L, periodNs / 1000L);
            minLagPtsUs = Math.max(1_000L, periodUs / 2L);
            phaseEnterUs = clamp(periodUs / 4L, 4_000L, 8_000L);
            phaseExitUs = clamp((periodUs * 3L) / 20L, 1_500L, 2_500L);
            phaseShallowLagPtsMaxUs = periodUs + minLagPtsUs; // <= 1.5 frame at nominal cadence
            phaseMinSamples = Math.max(12, (int)Math.ceil(fps * 0.50));
            continuityGapNs = Math.max(50_000_000L, (periodNs * 5L) / 2L);
        }
        if (mask != 0) {
            // Compatibility is an ownership boundary, not merely an evidence pause. If a mode
            // becomes ineligible, no structural catch-up or phase verification may survive and
            // resume later under a different configuration. Safety/action budgets intentionally
            // survive this fence so toggling compatibility cannot bypass them.
            if (previousMask == 0) abortTransientForEligibilityLoss(now);
            else resetEvidence();
        }
        if (phaseEligibleAfterNs == 0) phaseEligibleAfterNs = now + STARTUP_PHASE_GUARD_NS;
    }

    void output(long now) { lastOutputNs = now; }

    synchronized Decision decide(long now, boolean eligible, boolean eosOrConfig,
                                 boolean matched, int lagFrames, long lagPtsUs, long residenceUs) {
        if (!active) return null;
        if (modeMask != 0 || !eligible) {
            abortTransientForEligibilityLoss(now);
            return null;
        }
        if (eosOrConfig || !matched || residenceUs < 0) {
            resetEvidence();
            return null;
        }

        if (activeCatchup != null) {
            final Request r = activeCatchup;
            if (r.epoch != epoch) { activeCatchup = null; return null; }
            if (lagFrames <= 0 || lagPtsUs < minLagPtsUs) {
                activeCatchup = null;
                resetStructural();
                phaseEligibleAfterNs = now + POST_CATCHUP_PHASE_GUARD_NS;
                result = "CATCHUP_DONE_" + r.id + "_D" + dropsDone + "_L" + lagFrames + "_P" + lagPtsUs;
                return new Decision(r, false, EVENT_CATCHUP_END, lagFrames, lagPtsUs, residenceUs);
            }
            if (dropsDone >= MAX_DROPS) {
                activeCatchup = null;
                structLimitLock = true;
                resetStructural();
                phaseEligibleAfterNs = now + POST_CATCHUP_PHASE_GUARD_NS;
                result = "CATCHUP_LIMIT_" + r.id + "_D" + dropsDone + "_L" + lagFrames + "_P" + lagPtsUs;
                return new Decision(r, false, EVENT_CATCHUP_END, lagFrames, lagPtsUs, residenceUs);
            }
            return new Decision(r, true, EVENT_CATCHUP_DROP, lagFrames, lagPtsUs, residenceUs);
        }

        final boolean exactZero = lagFrames == 0 && lagPtsUs == 0;
        final boolean structuralDebt = lagFrames > 0 && lagPtsUs >= minLagPtsUs;
        final boolean shallowOne = lagFrames == 1 && lagPtsUs >= minLagPtsUs && lagPtsUs <= phaseShallowLagPtsMaxUs;
        final boolean deepStructural = structuralDebt && !shallowOne;

        // Structural debt still owns the action first. M4.3 changes only what happens while a
        // *shallow, intermittent* +1 sample is not persistent enough to trigger catch-up: that
        // sample no longer destroys phase evidence. A deep/multi-frame debt still invalidates the
        // phase state immediately.
        if (structuralDebt) {
            if (deepStructural) {
                resetPhaseEvidence();
                resetPhaseVerifyEvidence();
                resetPhaseRearmEvidence();
            }
            if (structLimitLock) {
                updateLongHealth(false, now);
                return null;
            }
            final boolean structuralReady = observeStructuralSample(now, true, lagFrames);
            if (!safetyLocked && structuralReady) {
                final Request r = new Request(nextActionId++, epoch, TYPE_AUTO_CATCHUP);
                activeCatchup = r;
                dropsDone = 0;
                resetPhaseEvidence();
                resetPhaseVerifyEvidence();
                resetPhaseRearmEvidence();
                noteAction();
                result = "CATCHUP_AUTO_" + r.id + "_L" + lagFrames + "_P" + lagPtsUs;
                return new Decision(r, true, EVENT_CATCHUP_DROP, lagFrames, lagPtsUs, residenceUs);
            }
            if (deepStructural) {
                updateLongHealth(false, now);
                return null;
            }
            // shallowOne intentionally falls through to phase verification/re-arm/evidence below.
        } else {
            if (exactZero) {
                if (structLimitLock) {
                    // A real zero is the only condition that clears a catch-up limit lock. Start
                    // a fresh structural window after the lock rather than carrying stale evidence.
                    structLimitLock = false;
                    resetStructural();
                } else {
                    // Exact zero is elapsed-time negative evidence, not an episode reset.
                    observeStructuralSample(now, false, 0);
                }
            } else {
                // Matched-but-noncanonical debt is not safe evidence. Fail closed.
                resetStructural();
                resetPhaseEvidence();
                updateLongHealth(false, now);
                return null;
            }
        }

        final boolean strictHealthy = exactZero && residenceUs <= phaseExitUs;
        final boolean nonPathological = exactZero && residenceUs < phaseEnterUs;
        updateLongHealth(strictHealthy, now);

        // Verification after one automatic phase drop. A shallow +1 sample counts as unhealthy but
        // does not reset the sampled window; deep debt above has already invalidated it.
        if (phaseVerifying) {
            if (phaseVerifyLastNs != 0 && now - phaseVerifyLastNs > continuityGapNs) {
                resetPhaseVerifyEvidence();
            }
            if (phaseVerifyStartNs == 0) phaseVerifyStartNs = now;
            phaseVerifyLastNs = now;
            phaseVerifySamples++;
            if (nonPathological) phaseVerifyHealthySamples++;

            final long verifyHeld = now - phaseVerifyStartNs;
            final boolean enoughVerifySamples = phaseVerifySamples >= phaseMinSamples;
            final boolean verifyHealthyRatio = ratioAtLeast(phaseVerifyHealthySamples, phaseVerifySamples,
                    HEALTH_RATIO_NUM, HEALTH_RATIO_DEN);
            if (verifyHeld >= PHASE_VERIFY_MIN_NS && enoughVerifySamples && verifyHealthyRatio) {
                phaseVerifying = false;
                phaseFailedLock = false;
                resetPhaseVerifyEvidence();
                resetPhaseRearmEvidence();
                result = "PHASE_VERIFY_OK";
            } else if (now >= phaseVerifyDeadlineNs) {
                phaseVerifying = false;
                phaseFailedLock = true;
                resetPhaseVerifyEvidence();
                resetPhaseRearmEvidence();
                result = "PHASE_VERIFY_FAILED_LOCK";
            }
            resetPhaseEvidence();
            return null;
        }

        // Re-arm likewise tolerates isolated shallow +1 samples as unhealthy observations rather
        // than throwing away the whole 2-second window.
        if (!phaseArmed || phaseFailedLock) {
            if (phaseRearmLastNs != 0 && now - phaseRearmLastNs > continuityGapNs) {
                resetPhaseRearmEvidence();
            }
            if (phaseRearmStartNs == 0) phaseRearmStartNs = now;
            phaseRearmLastNs = now;
            phaseRearmSamples++;
            if (nonPathological) phaseRearmHealthySamples++;

            if (now - phaseRearmStartNs >= PHASE_REARM_WINDOW_NS) {
                final boolean enoughRearmSamples = phaseRearmSamples >= phaseMinSamples * 2;
                final boolean rearmHealthyRatio = ratioAtLeast(phaseRearmHealthySamples, phaseRearmSamples,
                        HEALTH_RATIO_NUM, HEALTH_RATIO_DEN);
                if (enoughRearmSamples && rearmHealthyRatio) {
                    phaseArmed = true;
                    phaseFailedLock = false;
                    result = "PHASE_REARM";
                }
                resetPhaseRearmEvidence();
            }
            resetPhaseEvidence();
            return null;
        }

        if (now < phaseEligibleAfterNs || safetyLocked) {
            resetPhaseEvidence();
            return null;
        }

        if (phaseLastNs != 0 && now - phaseLastNs > continuityGapNs) resetPhaseEvidence();
        phaseLastNs = now;

        // A genuinely healthy exact-zero sample is strong evidence that this is not a bad phase.
        if (strictHealthy) {
            resetPhaseEvidence();
            return null;
        }

        if (phaseStartNs == 0) phaseStartNs = now;
        phaseTotalSamples++;
        if (exactZero) {
            phaseZeroSamples++;
            if (residenceUs >= phaseEnterUs) phaseHighZeroSamples++;
        }

        final long held = now - phaseStartNs;
        final boolean enoughZeroRatio = ratioAtLeast(phaseZeroSamples, phaseTotalSamples, HEALTH_RATIO_NUM, HEALTH_RATIO_DEN);
        final boolean highZeroRatio = ratioAtLeast(phaseHighZeroSamples, phaseZeroSamples, HEALTH_RATIO_NUM, HEALTH_RATIO_DEN);
        if (exactZero && residenceUs >= phaseEnterUs && held >= PHASE_HOLD_NS &&
                phaseTotalSamples >= phaseMinSamples && enoughZeroRatio && highZeroRatio) {
            final Request r = new Request(nextActionId++, epoch, TYPE_AUTO_PHASE);
            phaseArmed = false;
            phaseVerifying = true;
            phaseVerifyDeadlineNs = now + PHASE_VERIFY_NS;
            resetPhaseVerifyEvidence();
            resetPhaseRearmEvidence();
            resetPhaseEvidence();
            noteAction();
            result = "PHASE_AUTO_" + r.id + "_R" + residenceUs;
            return new Decision(r, true, EVENT_PHASE_DROP, 0, 0, residenceUs);
        }

        // Fixed upper evidence window prevents an early mixed segment from poisoning phase
        // classification for the rest of the stream. The current sample seeds the next window.
        if (held >= PHASE_EVIDENCE_MAX_NS) {
            final boolean seedZero = exactZero;
            final boolean seedHigh = exactZero && residenceUs >= phaseEnterUs;
            phaseStartNs = now;
            phaseLastNs = now;
            phaseTotalSamples = 1;
            phaseZeroSamples = seedZero ? 1 : 0;
            phaseHighZeroSamples = seedHigh ? 1 : 0;
        }
        return null;
    }

    synchronized void done(Decision d, boolean ok) {
        if (d == null) return;
        if (!ok) {
            if (activeCatchup == d.request) { activeCatchup = null; structLimitLock = true; }
            if (d.eventKind == EVENT_PHASE_DROP) {
                phaseVerifying = false;
                phaseFailedLock = true;
            }
            result = "FAILED_" + d.request.id;
            return;
        }
        if (d.eventKind == EVENT_CATCHUP_DROP) {
            dropsDone++;
            result = "CATCHUP_DROP_" + d.request.id + "_D" + dropsDone + "_L" + d.lagFrames + "_P" + d.lagPtsUs;
        } else if (d.eventKind == EVENT_PHASE_DROP) {
            result = "PHASE_DROP_DONE_" + d.request.id + "_R" + d.residenceUs;
        }
    }

    synchronized void newEpoch(long now) {
        activeCatchup = null;
        dropsDone = 0;
        epoch++;
        modeMask = 1;
        lastOutputNs = 0;
        phaseArmed = true;
        phaseVerifying = false;
        phaseFailedLock = false;
        phaseEligibleAfterNs = now + STARTUP_PHASE_GUARD_NS;
        actionsWithoutLongHealth = 0;
        safetyLocked = false;
        structLimitLock = false;
        resetPhaseVerifyEvidence();
        resetPhaseRearmEvidence();
        resetLongHealthEvidence();
        resetEvidence();
        result = "EPOCH_" + epoch;
    }

    synchronized void stop() {
        activeCatchup = null;
        active = false;
        modeMask = 1;
        result = "STOPPED";
    }

    String result() { return result; }

    synchronized boolean isSafetyLocked() { return safetyLocked; }

    synchronized String status(long now) {
        return "active=" + active + " epoch=" + epoch + " modeMask=" + modeMask +
                " maxDrops=" + MAX_DROPS + " minLagPtsUs=" + minLagPtsUs +
                " structPolicy=window1p5s_timeOcc75_severity0p75_hold0p25" +
                " phaseEnterUs=" + phaseEnterUs + " phaseExitUs=" + phaseExitUs +
                " phaseMinSamples=" + phaseMinSamples + " phaseHealthRatio=75pct phaseEvidence=zero75_highZero75_allowShallow1 phaseVerifyPolicy=below_enter_ratio phaseRearmPolicy=below_enter_ratio" +
                " phaseArmed=" + phaseArmed +
                " phaseVerifying=" + phaseVerifying + " phaseFailedLock=" + phaseFailedLock +
                " actionsWithoutLongHealth=" + actionsWithoutLongHealth + " safetyLocked=" + safetyLocked +
                " structLimitLock=" + structLimitLock +
                " recentOutput=" + (lastOutputNs != 0 && now >= lastOutputNs && now - lastOutputNs <= LIVE_OUTPUT_NS) +
                " result=" + result;
    }

    private void noteAction() {
        actionsWithoutLongHealth++;
        if (actionsWithoutLongHealth >= MAX_ACTIONS_WITHOUT_LONG_HEALTH) {
            safetyLocked = true;
            result = "SAFETY_LOCK";
        }
    }

    private void updateLongHealth(boolean healthy, long now) {
        if (longHealthLastNs != 0 && now - longHealthLastNs > continuityGapNs) resetLongHealthEvidence();
        if (longHealthStartNs == 0) longHealthStartNs = now;
        longHealthLastNs = now;
        longHealthSamples++;
        if (healthy) longHealthHealthySamples++;
        if (now - longHealthStartNs >= LONG_HEALTH_RESET_NS) {
            final boolean enoughSamples = longHealthSamples >= phaseMinSamples * 20;
            final boolean healthyRatio = ratioAtLeast(longHealthHealthySamples, longHealthSamples,
                    HEALTH_RATIO_NUM, HEALTH_RATIO_DEN);
            if (enoughSamples && healthyRatio) {
                actionsWithoutLongHealth = 0;
                safetyLocked = false;
            }
            resetLongHealthEvidence();
        }
    }

    private void resetEvidence() { resetStructural(); resetPhaseEvidence(); }

    private void abortTransientForEligibilityLoss(long now) {
        activeCatchup = null;
        dropsDone = 0;
        // A structural limit lock belongs to the debt episode and remains until a real zero or
        // codec epoch reset. Do not let a temporary incompatible mode bypass the max-drop safety.
        phaseArmed = true;
        phaseVerifying = false;
        phaseFailedLock = false;
        phaseEligibleAfterNs = now;
        resetPhaseVerifyEvidence();
        resetPhaseRearmEvidence();
        resetLongHealthEvidence();
        resetEvidence();
        result = "ELIGIBILITY_FENCE";
    }

    private boolean observeStructuralSample(long now, boolean positive, int lagFrames) {
        if (structLastNs != 0 && now - structLastNs > continuityGapNs) resetStructural();
        long dt = structLastNs == 0 ? periodNs : Math.min(Math.max(0L, now - structLastNs), continuityGapNs);
        structLastNs = now;

        // Capacity should never be reached for the 1.5 s window at supported display rates. If it
        // is, fail closed by forgetting old evidence instead of silently changing the ratio.
        if (structSize == STRUCT_RING_CAPACITY) {
            resetStructural();
            structLastNs = now;
            dt = periodNs;
        }

        final long evidence = positive ? saturatingMultiply(lagFrames, dt) : 0L;
        final int tail = (structHead + structSize) % STRUCT_RING_CAPACITY;
        structTimesNs[tail] = now;
        structEvidenceNs[tail] = evidence;
        structDurationNs[tail] = dt;
        structPositive[tail] = positive;
        structSize++;
        structWindowDurationNs = saturatingAdd(structWindowDurationNs, dt);
        if (positive) structPositiveDurationNs = saturatingAdd(structPositiveDurationNs, dt);
        structEvidenceFrameNs = saturatingAdd(structEvidenceFrameNs, evidence);

        final long cutoff = now - STRUCT_WINDOW_NS;
        while (structSize > 0 && structTimesNs[structHead] < cutoff) {
            final long oldDuration = structDurationNs[structHead];
            structWindowDurationNs = Math.max(0L, structWindowDurationNs - oldDuration);
            if (structPositive[structHead]) {
                structPositiveDurationNs = Math.max(0L, structPositiveDurationNs - oldDuration);
            }
            structEvidenceFrameNs = Math.max(0L, structEvidenceFrameNs - structEvidenceNs[structHead]);
            structHead = (structHead + 1) % STRUCT_RING_CAPACITY;
            structSize--;
        }

        if (!positive || structSize <= 0) return false;
        final long held = now - structTimesNs[structHead];
        final boolean occupancy = ratioAtLeastLong(structPositiveDurationNs, structWindowDurationNs,
                STRUCT_OCCUPANCY_NUM, STRUCT_OCCUPANCY_DEN);
        return held >= STRUCT_MIN_HOLD_NS && occupancy && structEvidenceFrameNs >= STRUCT_EVIDENCE_NS;
    }

    private void resetStructural() {
        structHead = 0; structSize = 0;
        structLastNs = 0; structEvidenceFrameNs = 0;
        structWindowDurationNs = 0; structPositiveDurationNs = 0;
    }
    private void resetPhaseEvidence() { phaseStartNs = 0; phaseLastNs = 0; phaseTotalSamples = 0; phaseZeroSamples = 0; phaseHighZeroSamples = 0; }
    private void resetPhaseVerifyEvidence() {
        phaseVerifyStartNs = 0; phaseVerifyLastNs = 0; phaseVerifySamples = 0; phaseVerifyHealthySamples = 0;
    }
    private void resetPhaseRearmEvidence() {
        phaseRearmStartNs = 0; phaseRearmLastNs = 0; phaseRearmSamples = 0; phaseRearmHealthySamples = 0;
    }
    private void resetLongHealthEvidence() {
        longHealthStartNs = 0; longHealthLastNs = 0; longHealthSamples = 0; longHealthHealthySamples = 0;
    }

    private static boolean ratioAtLeast(int good, int total, int num, int den) {
        return total > 0 && (long)good * den >= (long)total * num;
    }
    private static boolean ratioAtLeastLong(long good, long total, int num, int den) {
        if (total <= 0 || good < 0) return false;
        // Values are bounded to a 1.5 s window, so these products are far below overflow.
        return good * den >= total * num;
    }
    private static long clamp(long v, long lo, long hi) { return Math.max(lo, Math.min(hi, v)); }
    private static long saturatingMultiply(int a, long b) {
        if (a <= 0 || b <= 0) return 0;
        if (b > Long.MAX_VALUE / a) return Long.MAX_VALUE;
        return b * a;
    }
    private static long saturatingAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        return a + b;
    }
}
