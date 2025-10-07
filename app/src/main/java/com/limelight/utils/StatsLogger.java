package com.limelight.utils;

import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight 1 Hz stats printer to avoid per-frame Log I/O on hot paths. */
public final class StatsLogger {
    // Set false in perf/release if you want no prints at all
    private static final boolean ENABLED = true;

    private static final AtomicInteger frames = new AtomicInteger();

    // Decode time (average over last second)
    private static final AtomicLong decSumNs = new AtomicLong();
    private static final AtomicInteger decCount = new AtomicInteger();
    private static final AtomicLong lastDecodeNs = new AtomicLong(); // last sample (ns)

    // Present slip vs scheduled time (ns), averaged over last second
    private static final AtomicLong presentSlipSumNs = new AtomicLong();
    private static final AtomicInteger presentSlipCount = new AtomicInteger();

    // Output queue depth (balanced path diagnostics)
    private static final AtomicInteger outQMax = new AtomicInteger();
    private static final AtomicInteger outQLast = new AtomicInteger();

    // Decoder outputs and drops per second
    private static final AtomicInteger decoded = new AtomicInteger();
    private static final AtomicInteger drops = new AtomicInteger();

    private static final AtomicLong lastSwapResult = new AtomicLong(); // 1=ok, 0=err

    private static volatile boolean started;

    private StatsLogger() {}

    /** Call from hot path when a frame is actually presented to screen. */
    public static void onFramePresented() { frames.incrementAndGet(); }

    /** Back-compat: keep if someone sets a single sample. */
    public static void setDecodeTimeNs(long ns) { addDecodeTimeNs(ns); }

    /** Accumulate decode time samples (ns) for 1 Hz avg. */
    public static void addDecodeTimeNs(long ns) {
        if (ns > 0) {
            lastDecodeNs.set(ns);
            decSumNs.addAndGet(ns);
            decCount.incrementAndGet();
        }
    }

    /** Track decoder output rate. Call when dequeueOutput gives a real frame. */
    public static void incDecoded() { decoded.incrementAndGet(); }

    /** Track dropped frames (releaseOutputBuffer(..., false)). */
    public static void incDrop() { drops.incrementAndGet(); }

    /** Track output queue depth (update after push/pop). */
    public static void setOutputQueueDepth(int depth) {
        outQLast.set(depth);
        // atomic max
        int prev, next;
        do {
            prev = outQMax.get();
            next = Math.max(prev, depth);
        } while (!outQMax.compareAndSet(prev, next));
    }

    /** Add present timing slip (|now - scheduledNs|). */
    public static void addPresentSlipNs(long ns) {
        if (ns >= 0) {
            presentSlipSumNs.addAndGet(ns);
            presentSlipCount.incrementAndGet();
        }
    }

    /** Optional: swap ok/fail (1/0) for sporadic diagnosis. */
    public static void setSwapOk(boolean ok) { lastSwapResult.set(ok ? 1 : 0); }

    /** Start 1 Hz printing on main Looper (idempotent). */
    public static void start() {
        if (!ENABLED || started) return;
        started = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!ENABLED) return;

                int fps = frames.getAndSet(0);

                long sum = decSumNs.getAndSet(0);
                int cnt = decCount.getAndSet(0);
                long last = lastDecodeNs.get();
                long avgDecNs = (cnt > 0) ? (sum / Math.max(1, cnt)) : last;

                long slipSum = presentSlipSumNs.getAndSet(0);
                int slipCnt = presentSlipCount.getAndSet(0);
                long avgSlipNs = (slipCnt > 0) ? (slipSum / Math.max(1, slipCnt)) : 0L;

                int in = decoded.getAndSet(0);
                int dr = drops.getAndSet(0);
                int qMax = outQMax.getAndSet(0);
                int q = outQLast.get();

                long swapOk = lastSwapResult.get();

                android.util.Log.d(
                        "MoonStats",
                        "fps=" + fps +
                                " in=" + in +
                                " drop=" + dr +
                                " q=" + q + "/" + qMax +
                                " decodeNs=" + avgDecNs +
                                " slipNs=" + avgSlipNs +
                                " swapOk=" + swapOk
                );

                h.postDelayed(this, 1000);
            }
        }, 1000);
    }
}
