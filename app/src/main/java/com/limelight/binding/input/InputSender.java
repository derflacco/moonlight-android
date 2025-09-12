package com.limelight.binding.input;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.Closeable;

/**
 * Lightweight input task scheduler with a dedicated high-priority HandlerThread.
 * - Zero GC churn on the hot path (no allocations beyond the Runnable provided)
 * - Backpressure-aware: drops posts after shutdown
 * - Safe shutdown with quitSafely() + join()
 *
 * Backwards compatible with the old single-argument constructor used by callers.
 */
public final class InputSender implements Closeable {
    private static final String TAG = "InputSender";

    // Sensible defaults for latency-sensitive input work
    public static final int PRIORITY_URGENT_INPUT = Process.THREAD_PRIORITY_URGENT_DISPLAY;
    public static final int PRIORITY_HIGH_INPUT   = Process.THREAD_PRIORITY_DISPLAY;

    private final HandlerThread thread;
    private volatile Handler handler;
    private volatile boolean stopped = false;

    /** Backwards-compatible ctor kept for existing call sites */
    public InputSender(Object unused) {
        this("InputSender", PRIORITY_URGENT_INPUT);
    }

    public InputSender(String name, int priority) {
        // Use HandlerThread(priority) so the Looper inherits it
        thread = new HandlerThread(name, priority);
        thread.start();

        // Double-set priority in case OEMs ignore the ctor priority
        try {
            Process.setThreadPriority(thread.getThreadId(), priority);
        } catch (Throwable ignored) {}

        handler = new Handler(thread.getLooper());
    }

    /** True if the worker looper is alive and accepting work */
    public boolean isRunning() {
        final Handler h = handler;
        return !stopped && h != null && h.getLooper() != null && h.getLooper().getThread().isAlive();
    }

    /** Post a task; returns false if the worker is stopped or unavailable */
    public boolean post(Runnable r) {
        if (r == null || stopped) return false;
        final Handler h = handler;
        if (h == null) return false;
        try {
            return h.post(r);
        } catch (Throwable t) {
            Log.w(TAG, "post() failed", t);
            return false;
        }
    }

    /** Post a task at the front of the queue for ultra-low-latency events */
    public boolean postAtFront(Runnable r) {
        if (r == null || stopped) return false;
        final Handler h = handler;
        if (h == null) return false;
        try {
            return h.postAtFrontOfQueue(r);
        } catch (Throwable t) {
            Log.w(TAG, "postAtFront() failed", t);
            return false;
        }
    }

    /** Post a delayed task; returns false after shutdown */
    public boolean postDelayed(Runnable r, long delayMs) {
        if (r == null || stopped) return false;
        final Handler h = handler;
        if (h == null) return false;
        try {
            return h.postDelayed(r, Math.max(0L, delayMs));
        } catch (Throwable t) {
            Log.w(TAG, "postDelayed() failed", t);
            return false;
        }
    }

    /** Drop any queued callbacks to prevent stale input after focus loss */
    public void cancelAll() {
        final Handler h = handler;
        if (h != null) {
            try { h.removeCallbacksAndMessages(null); } catch (Throwable ignored) {}
        }
    }

    /**
     * Gracefully stop the worker thread and wait briefly for it to exit.
     * Idempotent and safe to call from any thread.
     */
    public void shutdown() {
        if (stopped) return;
        stopped = true;

        final Looper looper = (handler != null) ? handler.getLooper() : null;
        // Prevent any new callbacks from being enqueued
        cancelAll();
        handler = null;

        try {
            if (looper != null) looper.quitSafely();
        } catch (Throwable t) {
            try { if (looper != null) looper.quit(); } catch (Throwable ignored) {}
        }

        // Join to avoid leaking the thread; keep timeout small to never block app shutdown
        try {
            thread.join(500);
        } catch (Throwable ignored) {}
    }

    /** java.io.Closeable compatibility */
    @Override public void close() {
        shutdown();
    }
}
