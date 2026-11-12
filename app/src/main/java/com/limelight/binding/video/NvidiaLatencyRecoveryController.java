package com.limelight.binding.video;

import com.limelight.LimeLog;

import java.util.Locale;

/**
 * Production controller for opt-in NVIDIA decoder latency recovery.
 *
 * The normal no-action path is O(1), preallocated, and allocation-free. Logging is limited to
 * configuration changes, recovery actions, safety failures, codec epochs, and one session summary.
 */
final class NvidiaLatencyRecoveryController {
    private static final String TAG = "[NvidiaLatencyRecovery]";

    static final int MODE_CODEC_RECOVERY = 1 << 7;
    static final int MODE_STOPPING = 1 << 8;
    static final int MODE_NO_CODEC = 1 << 9;
    static final int MODE_ASYNC_NOT_READY = 1 << 10;

    private final NvidiaLatencyRecoveryGate gate = new NvidiaLatencyRecoveryGate();
    private final OutputDebtTracker tracker = new OutputDebtTracker();
    private final OutputDebtTracker.Observation observation = new OutputDebtTracker.Observation();

    private volatile boolean active = true;
    private volatile int modeMask = Integer.MIN_VALUE;
    private int fpsBits = Integer.MIN_VALUE;

    private volatile long currentPtsUs;
    private volatile long currentEpoch;
    private volatile long currentPacked = OutputDebtTracker.UNMATCHED;
    private volatile long currentResidenceUs = -1;
    private volatile long currentObservationNs;

    private long loggedCatchupId;
    private boolean safetyLockLogged;

    // Action-only counters. They are updated only when a recovery action is produced.
    private int structuralActions;
    private int phaseActions;
    private int maxDropsUsed;
    private int limitHits;
    private int safetyLocks;
    private long countedCatchupId;
    private int currentCatchupDrops;

    static boolean isNvidia(String name) {
        if (name == null) return false;
        final String n = name.toLowerCase(Locale.US);
        return n.startsWith("omx.nvidia.") || n.startsWith("c2.nvidia.");
    }

    static NvidiaLatencyRecoveryController open(String codecName) {
        if (!isNvidia(codecName)) return null;
        final NvidiaLatencyRecoveryController controller = new NvidiaLatencyRecoveryController();
        LimeLog.info(TAG + " CONFIG codec=" + codecName +
                " maxDrops=" + NvidiaLatencyRecoveryGate.MAX_DROPS +
                " structural=window1p5s_timeOcc75_severity0p75_hold0p25" +
                " phase=frame_period_persistence");
        return controller;
    }

    boolean isActive() {
        return active;
    }

    long epoch() {
        return gate.epoch();
    }

    /**
     * Updates dynamic eligibility and frame-period inputs. The renderer may call this each output
     * iteration; the synchronized gate is touched only when mask/FPS actually changes.
     */
    void updateMode(int mask, float fps) {
        if (!active) return;

        final int newFpsBits = Float.floatToIntBits(fps);
        if (mask == modeMask && newFpsBits == fpsBits) return;

        modeMask = mask;
        fpsBits = newFpsBits;
        gate.mode(mask, fps, System.nanoTime());
        LimeLog.info(TAG + " MODE mask=" + mask + " fps=" + fps);
    }

    void queued(long ptsUs, long queueEndNs) {
        if (!active || ptsUs <= 0 || queueEndNs <= 0) return;
        tracker.onQueued(ptsUs, queueEndNs);
    }

    void output(long epoch, long outputReadyNs, long ptsUs) {
        if (!active || outputReadyNs <= 0 || ptsUs <= 0) return;
        gate.output(outputReadyNs);
        currentEpoch = epoch;
        currentPtsUs = ptsUs;
        currentObservationNs = outputReadyNs;
        tracker.observe(ptsUs, outputReadyNs, observation);
        currentPacked = observation.packed;
        currentResidenceUs = observation.residenceUs;
    }

    NvidiaLatencyRecoveryGate.Decision decide(long epoch, long ptsUs, int flags) {
        if (!active) return null;

        final boolean sameOutput = currentEpoch == epoch && currentPtsUs == ptsUs;
        final long packed = sameOutput ? currentPacked : OutputDebtTracker.UNMATCHED;
        final boolean matched = OutputDebtTracker.matched(packed);
        final int lagFrames = matched ? OutputDebtTracker.lagFrames(packed) : -1;
        final long lagPtsUs = matched ? OutputDebtTracker.lagPtsUs(packed) : -1;
        final long residenceUs = sameOutput ? currentResidenceUs : -1;
        final long decisionNs = sameOutput && currentObservationNs > 0
                ? currentObservationNs : System.nanoTime();

        return gate.decide(decisionNs, modeMask == 0, (flags & 6) != 0,
                matched, lagFrames, lagPtsUs, residenceUs);
    }

    void released(NvidiaLatencyRecoveryGate.Decision decision, boolean ok) {
        if (decision == null) return;

        gate.done(decision, ok);

        if (!ok) {
            LimeLog.warning(TAG + " ACTION_FAILED id=" + decision.request.id +
                    " type=" + decision.request.type + " lagF=" + decision.lagFrames +
                    " lagPtsUs=" + decision.lagPtsUs + " residenceUs=" + decision.residenceUs);
            return;
        }

        if (decision.eventKind == NvidiaLatencyRecoveryGate.EVENT_CATCHUP_DROP) {
            if (countedCatchupId != decision.request.id) {
                countedCatchupId = decision.request.id;
                currentCatchupDrops = 0;
                structuralActions++;
            }
            currentCatchupDrops++;

            if (loggedCatchupId != decision.request.id) {
                loggedCatchupId = decision.request.id;
                LimeLog.info(TAG + " STRUCTURAL_BEGIN id=" + decision.request.id +
                        " lagF=" + decision.lagFrames + " lagPtsUs=" + decision.lagPtsUs +
                        " residenceUs=" + decision.residenceUs);
            }
        } else if (decision.eventKind == NvidiaLatencyRecoveryGate.EVENT_CATCHUP_END) {
            maxDropsUsed = Math.max(maxDropsUsed, currentCatchupDrops);
            final String gateResult = gate.result();
            if (gateResult.startsWith("CATCHUP_LIMIT_")) {
                limitHits++;
                LimeLog.warning(TAG + " STRUCTURAL_LIMIT id=" + decision.request.id +
                        " maxDrops=" + NvidiaLatencyRecoveryGate.MAX_DROPS +
                        " lagF=" + decision.lagFrames + " lagPtsUs=" + decision.lagPtsUs +
                        " residenceUs=" + decision.residenceUs);
            } else {
                LimeLog.info(TAG + " STRUCTURAL_END id=" + decision.request.id +
                        " drops=" + currentCatchupDrops +
                        " lagF=" + decision.lagFrames + " lagPtsUs=" + decision.lagPtsUs +
                        " residenceUs=" + decision.residenceUs + " result=" + gateResult);
            }
            loggedCatchupId = 0;
            countedCatchupId = 0;
            currentCatchupDrops = 0;
        } else if (decision.eventKind == NvidiaLatencyRecoveryGate.EVENT_PHASE_DROP) {
            phaseActions++;
            LimeLog.info(TAG + " PHASE_DROP id=" + decision.request.id +
                    " residenceUs=" + decision.residenceUs);
        }

        if (gate.isSafetyLocked()) {
            if (!safetyLockLogged) {
                safetyLockLogged = true;
                safetyLocks++;
                LimeLog.warning(TAG + " SAFETY_LOCK");
            }
        } else {
            safetyLockLogged = false;
        }
    }

    void recoveryBegin(int requestType) {
        if (!active) return;

        tracker.newEpoch();
        final long now = System.nanoTime();
        gate.newEpoch(now);
        // Gate newEpoch() intentionally returns to an ineligible sentinel. Force the next renderer
        // update to re-apply the current mode even when the external mask/FPS did not change.
        modeMask = Integer.MIN_VALUE;
        fpsBits = Integer.MIN_VALUE;
        currentPacked = OutputDebtTracker.UNMATCHED;
        currentResidenceUs = -1;
        currentObservationNs = 0L;
        currentPtsUs = 0L;
        currentEpoch = gate.epoch();
        loggedCatchupId = 0L;
        countedCatchupId = 0L;
        currentCatchupDrops = 0;
        safetyLockLogged = false;

        LimeLog.info(TAG + " CODEC_EPOCH epoch=" + currentEpoch + " recoveryType=" + requestType);
    }

    void stop() {
        if (!active) return;
        active = false;
        maxDropsUsed = Math.max(maxDropsUsed, currentCatchupDrops);
        gate.stop();
        LimeLog.info(TAG + " SUMMARY structuralActions=" + structuralActions +
                " phaseActions=" + phaseActions +
                " maxDropsUsed=" + maxDropsUsed +
                " limitHits=" + limitHits +
                " safetyLocks=" + safetyLocks);
    }
}
