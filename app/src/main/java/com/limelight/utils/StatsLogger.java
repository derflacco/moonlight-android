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
    private static final AtomicLong lastDecodeNs = new AtomicLong();
    private static final AtomicLong lastSwapResult = new AtomicLong(); // 1=ok, 0=err

    private static volatile boolean started;

    private StatsLogger() {}

    /** Call from hot path when a frame is actually presented to screen. */
    public static void onFramePresented() {
        frames.incrementAndGet();
    }

    /** Optional: update last decode time (ns) from decoder drain. */
    public static void setDecodeTimeNs(long ns) {
        lastDecodeNs.set(ns);
    }

    /** Optional: swap ok/fail (1/0) for sporadic diagnosis. */
    public static void setSwapOk(boolean ok) {
        lastSwapResult.set(ok ? 1 : 0);
    }

    /** Start 1 Hz printing on main Looper (idempotent). */
    public static void start() {
        if (!ENABLED || started) return;
        started = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!ENABLED) return;
                int fps = frames.getAndSet(0);
                long dec = lastDecodeNs.get();
                long swapOk = lastSwapResult.get();
                android.util.Log.d("MoonStats", "fps="+fps+" decodeNs="+dec+" swapOk="+swapOk);
                h.postDelayed(this, 1000);
            }
        }, 1000);
    }
}
