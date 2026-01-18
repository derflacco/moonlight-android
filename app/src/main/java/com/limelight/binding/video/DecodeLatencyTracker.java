package com.limelight.binding.video;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * DecodeLatencyTracker: allocation-free PTS->enqueueNs tracking + stats update helper.
 * Fixed-size ring backed by atomic arrays (no boxing, no Sparse*).
 */
public final class DecodeLatencyTracker {

    private static final long CLEANUP_THRESHOLD_NS = 30_000_000_000L; // 30 seconds
    private static final int MAX_SIZE = 384; // ring capacity (bounded scan cost)

    private long lastCleanupNs = 0L;
    private final PtsEnqueueTracker enqueueNsByPtsUs = new PtsEnqueueTracker(MAX_SIZE);

    public void clear() {
        enqueueNsByPtsUs.clear();
        lastCleanupNs = 0L;
    }

    public void onEnqueue(long ptsUs) {
        enqueueNsByPtsUs.put(ptsUs, System.nanoTime());
    }

    public void onEnqueue(long ptsUs, long enqueueNs) {
        enqueueNsByPtsUs.put(ptsUs, enqueueNs);
    }

    public void onDequeue(VideoStats stats, long presentationTimeUs, boolean useFrameRenderTime) {
        onDequeue(stats, presentationTimeUs, System.nanoTime(), useFrameRenderTime);
    }

    public void onDequeue(VideoStats stats, long presentationTimeUs, long endNs, boolean useFrameRenderTime) {
        if (stats == null) {
            return;
        }

        final long enqNs = enqueueNsByPtsUs.take(presentationTimeUs);
        if (enqNs == Long.MIN_VALUE) {
            stats.decoderMisses++;
            return;
        }

        final long decNs = endNs - enqNs;
        final long decMs = decNs / 1_000_000L;

        if (decMs >= 0 && decMs < 1000) {
            stats.decoderTimeMs += decMs;
            stats.decoderSamples++;
        }

        // Keep old end-to-end latency behavior when render-time is not used
        if (!useFrameRenderTime) {
            final long e2eMs = SystemClock.uptimeMillis() - (presentationTimeUs / 1000L);
            if (e2eMs >= 0 && e2eMs < 1000) {
                stats.endToEndLatencyMs += e2eMs;
                stats.totalTimeMs += e2eMs;
            }
        }
    }

    public void maybeCleanup() {
        final long nowNs = System.nanoTime();
        if (nowNs - lastCleanupNs < CLEANUP_THRESHOLD_NS) {
            return;
        }
        enqueueNsByPtsUs.cleanupOld(nowNs, CLEANUP_THRESHOLD_NS);
        lastCleanupNs = nowNs;
    }

    // PTS->enqueueNs tracker: fixed-size ring backed by atomic arrays (no per-frame allocations).
    // put() publishes a slot by writing enqNs first, then the pts key last.
    private static final class PtsEnqueueTracker {

        private static final long EMPTY_KEY = Long.MIN_VALUE;

        private final AtomicLongArray ptsUs;
        private final AtomicLongArray enqNs;
        private final int cap;
        private final AtomicInteger head = new AtomicInteger(0);

        PtsEnqueueTracker(int capacity) {
            cap = Math.max(8, capacity);
            ptsUs = new AtomicLongArray(cap);
            enqNs = new AtomicLongArray(cap);
            clear();
        }

        void clear() {
            for (int i = 0; i < cap; i++) {
                ptsUs.set(i, EMPTY_KEY);
                enqNs.set(i, 0L);
            }
            head.set(0);
        }

        void put(long pts, long ns) {
            int idx = head.getAndIncrement();
            if (idx >= cap) {
                idx = idx % cap;
                head.set(idx + 1);
            }

            // Publish: write value first, then key last.
            enqNs.set(idx, ns);
            ptsUs.set(idx, pts);
        }

        long take(long pts) {
            int h = head.get();
            if (h >= cap) h = h % cap;

            int i = h;
            for (int n = 0; n < cap; n++) {
                i--;
                if (i < 0) i = cap - 1;

                final long k = ptsUs.get(i);
                if (k == pts) {
                    final long v = enqNs.get(i);
                    if (ptsUs.compareAndSet(i, pts, EMPTY_KEY)) {
                        return v;
                    }
                }
            }
            return Long.MIN_VALUE;
        }

        void cleanupOld(long nowNs, long thresholdNs) {
            for (int i = 0; i < cap; i++) {
                final long k = ptsUs.get(i);
                if (k != EMPTY_KEY) {
                    final long v = enqNs.get(i);
                    if ((nowNs - v) > thresholdNs) {
                        ptsUs.compareAndSet(i, k, EMPTY_KEY);
                    }
                }
            }
        }
    }
}
