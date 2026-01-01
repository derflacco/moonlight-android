package com.limelight.binding.input;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lightweight input task scheduler with a dedicated high-priority HandlerThread.
 * - Zero GC churn on the hot path (no allocations beyond the Runnable provided)
 * - Backpressure-aware: drops posts after shutdown
 * - Safe shutdown with quitSafely() + join()
 * - Thread-safe operations with reduced synchronization overhead
 *
 * Backwards compatible with the old single-argument constructor used by callers.
 */
public final class InputSender implements Closeable {
    private static final String TAG = "InputSender";

    // Sensible defaults for latency-sensitive input work
    public static final int PRIORITY_URGENT_INPUT = Process.THREAD_PRIORITY_URGENT_DISPLAY;
    public static final int PRIORITY_HIGH_INPUT   = Process.THREAD_PRIORITY_DISPLAY;

    // Shutdown timeout
    private static final long SHUTDOWN_TIMEOUT_MS = 500L;
    private static final long SHUTDOWN_CHECK_INTERVAL_MS = 50L;

    private final HandlerThread thread;
    private volatile Handler handler;
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private final ReentrantLock shutdownLock = new ReentrantLock();
    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    /** Backwards-compatible ctor kept for existing call sites */
    public InputSender(Object unused) {
        this("InputSender", PRIORITY_HIGH_INPUT);
    }

    public InputSender(String name, int priority) {
        if (name == null || name.trim().isEmpty()) {
            name = "InputSender";
        }

        // Create worker with requested priority; HandlerThread applies it in run()
        HandlerThread ht = new HandlerThread(name, priority);
        ht.start();

        // Ensure the Looper is ready; mTid is now valid
        Looper looper = ht.getLooper(); // blocks until looper is prepared

        // Best-effort: set priority by TID (covers OEMs ignoring HandlerThread.mPriority)
        try {
            Process.setThreadPriority(ht.getThreadId(), priority);
        } catch (Throwable t) {
            Log.w(TAG, "setThreadPriority(TID) failed; will set inside thread", t);
        }

        // Prefer async handler to bypass sync barriers (API 28+)
        Handler h = (Build.VERSION.SDK_INT >= 28)
                ? Handler.createAsync(looper)
                : new Handler(looper);

        // Fallback: enforce the same requested priority from within the HandlerThread
        h.postAtFrontOfQueue(() -> {
            try {
                Process.setThreadPriority(priority);
            } catch (Throwable ignored) {}
        });

        this.thread = ht;
        this.handler = h;
    }

    /** True if the worker looper is alive and accepting work */
    public boolean isRunning() {
        return !stopped.get() && isLooperAlive();
    }

    private boolean isLooperAlive() {
        final Handler h = handler;
        if (h == null) return false;

        final Looper looper = h.getLooper();
        return looper != null && looper.getThread().isAlive();
    }

    /** Post a task; returns false if the worker is stopped or unavailable */
    public boolean post(Runnable r) {
        return postInternal(r, false, 0);
    }

    /** Post a task at the front of the queue for ultra-low-latency events */
    public boolean postAtFront(Runnable r) {
        if (r == null || stopped.get()) return false;

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
        return postInternal(r, true, Math.max(0L, delayMs));
    }

    private boolean postInternal(Runnable r, boolean delayed, long delayMs) {
        if (r == null || stopped.get()) return false;

        final Handler h = handler;
        if (h == null) return false;

        try {
            if (delayed) {
                return h.postDelayed(r, delayMs);
            } else {
                return h.post(r);
            }
        } catch (Throwable t) {
            Log.w(TAG, "post operation failed", t);
            return false;
        }
    }

    /** Remove specific pending callbacks */
    public void removeCallbacks(Runnable r) {
        if (r == null) return;

        final Handler h = handler;
        if (h != null) {
            try {
                h.removeCallbacks(r);
            } catch (Throwable t) {
                Log.w(TAG, "removeCallbacks() failed", t);
            }
        }
    }

    /** Drop any queued callbacks to prevent stale input after focus loss */
    public void cancelAll() {
        final Handler h = handler;
        if (h != null) {
            try {
                h.removeCallbacksAndMessages(null);
            } catch (Throwable t) {
                Log.w(TAG, "cancelAll() failed", t);
            }
        }
    }

    /**
     * Gracefully stop the worker thread and wait briefly for it to exit.
     * Idempotent and safe to call from any thread.
     */
    public void shutdown() {
        // Use atomic check to avoid lock contention in common case
        if (!isShuttingDown.compareAndSet(false, true)) {
            return; // Already shutting down or shut down
        }

        stopped.set(true);

        // Use lock to ensure orderly shutdown
        shutdownLock.lock();
        try {
            performShutdown();
        } finally {
            shutdownLock.unlock();
        }
    }

    private void performShutdown() {
        final Handler currentHandler = handler;
        final Looper looper = (currentHandler != null) ? currentHandler.getLooper() : null;

        // Prevent any new callbacks from being enqueued
        cancelAll();
        handler = null;

        if (looper != null) {
            try {
                looper.quitSafely();
            } catch (Throwable t) {
                Log.w(TAG, "quitSafely() failed, trying quit()", t);
                try {
                    looper.quit();
                } catch (Throwable t2) {
                    Log.e(TAG, "quit() also failed", t2);
                }
            }
        }

        // Wait for thread termination with progress checks
        waitForThreadTermination();
    }

    private void waitForThreadTermination() {
        // Avoid deadlock if called from the worker itself
        if (Thread.currentThread() == thread) {
            Log.w(TAG, "shutdown() called on input thread; skipping join");
            return;
        }

        if (!thread.isAlive()) {
            return;
        }

        final long deadline = System.currentTimeMillis() + SHUTDOWN_TIMEOUT_MS;
        boolean interrupted = false;

        try {
            long remaining;
            while ((remaining = deadline - System.currentTimeMillis()) > 0) {
                try {
                    thread.join(Math.min(remaining, SHUTDOWN_CHECK_INTERVAL_MS));
                    // Check if thread actually terminated after the join
                    if (!thread.isAlive()) {
                        return; // Thread terminated successfully
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    // Preserve interrupt status but continue waiting
                    Thread.currentThread().interrupt();
                }
            }

            // If we get here, timeout occurred
            Log.w(TAG, "Thread failed to terminate within timeout, interrupting...");
            try {
                thread.interrupt();
            } catch (SecurityException e) {
                Log.e(TAG, "No permission to interrupt thread", e);
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Get the underlying handler for advanced operations (use with caution) */
    public Handler getHandler() {
        return isRunning() ? handler : null;
    }

    /** Get the underlying thread for monitoring/debugging */
    public Thread getThread() {
        return thread;
    }

    /** java.io.Closeable compatibility */
    @Override
    public void close() {
        shutdown();
    }
}
