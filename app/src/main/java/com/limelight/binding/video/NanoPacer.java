package com.limelight.binding.video;

import android.media.MediaCodec;

import com.limelight.LimeLog;

import java.util.concurrent.locks.LockSupport;

/**
 * NanoPacer: 1:1 pacing detector + cooperative wait/drain helper.
 * Allocation-free on the hot path.
 */
public final class NanoPacer {

    private static final long PARK_MIN_THRESHOLD_NS = 2_000_000L; // 2ms min reliable park
    private static final long SPIN_MAX_NS = 100_000L;             // 100µs max spin
    private static final int PACE_STABILITY_THRESHOLD = 6;
    private static final float TOLERANCE_PERCENT = 0.15f;
    private static final float EMA_ALPHA = 0.2f;
    private static final float SLIP_CORRECTION_ALPHA = 0.05f;     // 5% smoothing

    // PTS jump guard
    private static final long PTS_JUMP_US = 500_000L; // 500ms

    private final Object lock = new Object();

    private volatile boolean oneToOne = false;
    private volatile long intervalNs = 0L;
    private volatile long nextDeadlineNs = 0L;

    private long lastFrameTimeNs = 0L;
    private long emaIntervalNs = 0L;
    private int stableCounter = 0;
    private int unstableCounter = 0;
    private long lastEvaluationNs = 0L;

    // PTS -> monotonic mapping
    private long ptsBaseUs = Long.MIN_VALUE;
    private long monoBaseNs = 0L;
    private long lastPtsUs = Long.MIN_VALUE;

    // Smoothed clock slip (ns)
    private long slipNs = 0L;

    public interface OutputCallbacks {
        int nextOutputIndex(MediaCodec.BufferInfo info, int timeoutUs);
        void releaseOutputBuffer(int index, boolean render);
        void onDequeued(long presentationTimeUs, long dequeueNs);
    }

    // Reusable holder to avoid per-frame allocations
    public static final class LatestOutput {
        public int index = -1;
        public long ptsUs = 0L;
        public int flags = 0;
        public long dequeueNs = 0L;
    }

    public void reset() {
        synchronized (lock) {
            oneToOne = false;
            intervalNs = 0L;
            nextDeadlineNs = 0L;

            lastFrameTimeNs = 0L;
            emaIntervalNs = 0L;
            stableCounter = 0;
            unstableCounter = 0;
            lastEvaluationNs = 0L;

            ptsBaseUs = Long.MIN_VALUE;
            monoBaseNs = 0L;
            lastPtsUs = Long.MIN_VALUE;
            slipNs = 0L;
        }
    }

    // Evaluate pacing state (call every decoded frame)
    public void updatePacingMode(boolean fastVsyncEnabled, int streamTargetFps, int refreshRate) {
        synchronized (lock) {
            if (!fastVsyncEnabled) {
                reset();
                return;
            }

            // Determine target rate (same logic as your getCheckedRate())
            final int rate;
            if (streamTargetFps <= 0 || streamTargetFps > 1000) {
                rate = refreshRate;
            } else {
                rate = (streamTargetFps < refreshRate) ? streamTargetFps : refreshRate;
            }

            if (rate <= 0) {
                reset();
                return;
            }

            final long nowNs = System.nanoTime();
            final long targetIntervalNs = 1_000_000_000L / rate;

            // If rate changes while active, resync immediately
            if (oneToOne && intervalNs != targetIntervalNs) {
                intervalNs = targetIntervalNs;
                nextDeadlineNs = nowNs + targetIntervalNs;
            }

            // Update every ~half frame
            if (lastEvaluationNs != 0L && (nowNs - lastEvaluationNs) < (targetIntervalNs >> 1)) {
                return;
            }
            lastEvaluationNs = nowNs;

            // First frame — initialize baseline
            if (lastFrameTimeNs == 0L) {
                lastFrameTimeNs = nowNs;
                emaIntervalNs = targetIntervalNs;
                return;
            }

            // Calculate real frame interval
            final long intervalSampleNs = nowNs - lastFrameTimeNs;
            lastFrameTimeNs = nowNs;

            // Skip dropped/paused frames
            if (intervalSampleNs > (targetIntervalNs * 2L)) {
                return;
            }

            // EMA: ema += alpha * (x - ema)
            emaIntervalNs += (long) (EMA_ALPHA * (intervalSampleNs - emaIntervalNs));

            final long toleranceNs = (long) (targetIntervalNs * TOLERANCE_PERCENT);
            final boolean matching = Math.abs(emaIntervalNs - targetIntervalNs) <= toleranceNs;

            if (matching) {
                if (stableCounter < PACE_STABILITY_THRESHOLD) stableCounter++;
                unstableCounter = 0;
            } else {
                if (unstableCounter < PACE_STABILITY_THRESHOLD + 2) unstableCounter++;
                stableCounter = 0;
            }

            // State transitions
            if (!oneToOne && stableCounter >= PACE_STABILITY_THRESHOLD) {
                oneToOne = true;
                stableCounter = 0;
                intervalNs = targetIntervalNs;
                nextDeadlineNs = nowNs + targetIntervalNs;

                // Rebase PTS mapping on entry (prevents stale mapping)
                ptsBaseUs = Long.MIN_VALUE;
                monoBaseNs = 0L;
                lastPtsUs = Long.MIN_VALUE;
                slipNs = 0L;

                LimeLog.info("NanoPacer: 1:1 pacing enabled (" + rate + " fps)");
            } else if (oneToOne && unstableCounter >= PACE_STABILITY_THRESHOLD + 2) {
                oneToOne = false;
                unstableCounter = 0;
                intervalNs = 0L;
                nextDeadlineNs = 0L;

                ptsBaseUs = Long.MIN_VALUE;
                monoBaseNs = 0L;
                lastPtsUs = Long.MIN_VALUE;
                slipNs = 0L;

                LimeLog.info("NanoPacer: 1:1 pacing disabled");
            }
        }
    }

    // Cooperative nano-pacing: keep draining while waiting to avoid decoder output backpressure.
    // Returns how many extra outputs were drained (and dropped) while waiting.
    public int waitAndDrainLatest(MediaCodec.BufferInfo info, LatestOutput latest, OutputCallbacks cb) {
        if (info == null || latest == null || cb == null) return 0;
        if (!oneToOne) return 0;

        final long framePeriodNs = intervalNs;
        if (framePeriodNs <= 0L) return 0;

        int drainedExtra = 0;

        long targetNs = computeTargetNs(latest.ptsUs, framePeriodNs);

        for (;;) {
            if (!oneToOne) break;

            final long nowNs = System.nanoTime();
            final long remainingNs = targetNs - nowNs;
            if (remainingNs <= 0L) break;

            // Drain any ready outputs without blocking, keep only the newest.
            boolean gotNew = false;
            int outIndex;
            while ((outIndex = cb.nextOutputIndex(info, 0)) >= 0) {
                final long dqNs = System.nanoTime();

                // Drop previous latest buffer
                final int oldIndex = latest.index;
                if (oldIndex >= 0) {
                    try { cb.releaseOutputBuffer(oldIndex, false); } catch (Throwable ignored) { }
                }

                drainedExtra++;
                latest.index = outIndex;
                latest.ptsUs = info.presentationTimeUs;
                latest.flags = info.flags;
                latest.dequeueNs = dqNs;

                try { cb.onDequeued(latest.ptsUs, dqNs); } catch (Throwable ignored) { }

                gotNew = true;
            }

            if (gotNew) {
                // Re-target based on the newest frame (avoid presenting stale)
                targetNs = computeTargetNs(latest.ptsUs, framePeriodNs);
                continue;
            }

            // Wait in small slices so we can keep draining (avoid long blocking)
            if (remainingNs > PARK_MIN_THRESHOLD_NS) {
                final long sliceNs = Math.min(remainingNs - SPIN_MAX_NS, 2_000_000L); // max 2ms slice
                if (sliceNs > 0L) LockSupport.parkNanos(sliceNs);
            } else {
                // Final fine spin
                while (System.nanoTime() < targetNs) { /* fine spin */ }
                break;
            }
        }

        // Advance phase for next frame
        synchronized (lock) {
            nextDeadlineNs = targetNs + framePeriodNs;
        }

        return drainedExtra;
    }

    // Compute target timestamp (ns, monotonic) for a given PTS without blocking.
    private long computeTargetNs(long presentationTimeUs, long framePeriodNs) {
        final long nowNs = System.nanoTime();

        synchronized (lock) {
            // ---- REBASE / DISCONTINUITY ----
            if (ptsBaseUs == Long.MIN_VALUE ||
                    lastPtsUs == Long.MIN_VALUE ||
                    presentationTimeUs < lastPtsUs ||
                    (presentationTimeUs - lastPtsUs) > PTS_JUMP_US) {

                ptsBaseUs = presentationTimeUs;
                monoBaseNs = nowNs;
                lastPtsUs = presentationTimeUs;
                slipNs = 0L;

                if (nextDeadlineNs == 0L) {
                    nextDeadlineNs = nowNs + framePeriodNs;
                }
                return nextDeadlineNs;
            }

            lastPtsUs = presentationTimeUs;

            final long idealNs = monoBaseNs + (presentationTimeUs - ptsBaseUs) * 1000L;

            // Slip correction (low-pass)
            final long errorNs = idealNs - nowNs;
            slipNs += (long) (SLIP_CORRECTION_ALPHA * (errorNs - slipNs));

            // Clamp slip
            if (slipNs > framePeriodNs) slipNs = framePeriodNs;
            else if (slipNs < -framePeriodNs) slipNs = -framePeriodNs;

            long targetNs = idealNs - slipNs;

            // Phase stabilization
            if (nextDeadlineNs != 0L && nextDeadlineNs > targetNs) {
                targetNs = nextDeadlineNs;
            }

            // Late re-phase to avoid backlog accumulation
            if ((targetNs - nowNs) <= -framePeriodNs) {
                targetNs = nowNs + framePeriodNs;
            }

            return targetNs;
        }
    }
}
