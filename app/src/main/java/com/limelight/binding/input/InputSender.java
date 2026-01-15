package com.limelight.binding.input;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance input task scheduler with dedicated thread.
 * Zero GC overhead on hot path, thread-safe with minimal synchronization.
 */
public final class InputSender implements Closeable {
    private static final String TAG = "InputSender";

    // Thread priorities for latency-sensitive work
    public static final int PRIORITY_URGENT_INPUT = Process.THREAD_PRIORITY_URGENT_DISPLAY;
    public static final int PRIORITY_HIGH_INPUT   = Process.THREAD_PRIORITY_DISPLAY;

    // Shutdown configuration
    private static final long SHUTDOWN_TIMEOUT_MS = 500L;
    private static final long SHUTDOWN_CHECK_INTERVAL_MS = 50L;

    // Optimized: volatile handler for low-latency access
    private volatile Handler handler;
    private final HandlerThread thread;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    // Backwards compatibility
    public InputSender(Object unused) {
        this("InputSender", PRIORITY_HIGH_INPUT);
    }

    public InputSender(String name, int priority) {
        if (name == null || name.trim().isEmpty()) {
            name = "InputSender";
        }

        // Create high-priority worker thread
        HandlerThread ht = new HandlerThread(name, priority);
        ht.start();

        // Get looper (blocks until ready)
        Looper looper = ht.getLooper();

        // Set thread priority using thread ID
        try {
            Process.setThreadPriority(ht.getThreadId(), priority);
        } catch (Throwable t) {
            Log.w(TAG, "setThreadPriority failed", t);
        }

        // Use async handler on API 28+ for better throughput
        Handler h;
        if (Build.VERSION.SDK_INT >= 28) {
            h = Handler.createAsync(looper);
        } else {
            h = new Handler(looper);
        }

        // Enforce priority from within thread (fallback)
        h.postAtFrontOfQueue(() -> {
            try {
                Process.setThreadPriority(priority);
            } catch (Throwable ignored) {}
        });

        this.thread = ht;
        this.handler = h;
    }

    /** Check if worker is alive and accepting work */
    public boolean isRunning() {
        if (stopped.get()) return false;

        final Handler h = handler;
        if (h == null) return false;

        final Looper looper = h.getLooper();
        return looper != null && looper.getThread().isAlive();
    }


    /** Post task; returns false if stopped or unavailable */
    public boolean post(Runnable r) {
        return postInternal(r, false, 0);
    }

    /** Post task at front of queue for minimum latency */
    public boolean postAtFront(Runnable r) {
        if (r == null || stopped.get()) return false;

        final Handler h = handler;
        if (h == null) return false;

        try {
            return h.postAtFrontOfQueue(r);
        } catch (Throwable t) {
            // Minimal logging in hot path
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "postAtFront failed", t);
            }
            return false;
        }
    }

    /** Post delayed task */
    public boolean postDelayed(Runnable r, long delayMs) {
        return postInternal(r, true, Math.max(0L, delayMs));
    }

    private boolean postInternal(Runnable r, boolean delayed, long delayMs) {
        if (r == null || stopped.get()) return false;

        final Handler h = handler;
        if (h == null) return false;

        try {
            return delayed ? h.postDelayed(r, delayMs) : h.post(r);
        } catch (Throwable t) {
            // Minimal logging in hot path
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "post operation failed", t);
            }
            return false;
        }
    }

    /** Remove specific pending callbacks */
    public void removeCallbacks(Runnable r) {
        if (r == null) return;

        final Handler h = handler;
        if (h != null) {
            try { // Safety against race conditions
                h.removeCallbacks(r);
            } catch (Throwable ignored) {}
        }
    }

    /** Remove all queued callbacks */
    public void cancelAll() {
        final Handler h = handler;
        if (h != null) {
            try { // Safety against race conditions
                h.removeCallbacksAndMessages(null);
            } catch (Throwable ignored) {}
        }
    }

    /** Gracefully stop worker thread */
    public void shutdown() {
        // Single atomic gate - only first caller proceeds
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        performShutdown();
    }

    private void performShutdown() {
        final Handler currentHandler = handler;
        final Looper looper = currentHandler != null ? currentHandler.getLooper() : null;

        // Prevent new callbacks
        cancelAll();
        handler = null;

        if (looper != null) {
            try {
                looper.quitSafely();
            } catch (Throwable t) {
                Log.w(TAG, "quitSafely failed, trying quit", t);
                try {
                    looper.quit();
                } catch (Throwable t2) {
                    Log.e(TAG, "quit also failed", t2);
                }
            }
        }

        waitForThreadTermination();
    }

    private void waitForThreadTermination() {
        // Avoid deadlock if called from worker thread
        if (Thread.currentThread() == thread) {
            return;
        }

        if (!thread.isAlive()) return;

        final long deadline = SystemClock.uptimeMillis() + SHUTDOWN_TIMEOUT_MS;

        try {
            while (thread.isAlive()) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) break;

                thread.join(Math.min(remaining, SHUTDOWN_CHECK_INTERVAL_MS));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        // Force interrupt if timeout
        if (thread.isAlive()) {
            thread.interrupt();
        }
    }

    /** Get handler for advanced operations */
    public Handler getHandler() {
        return isRunning() ? handler : null;
    }

    /** Get underlying thread */
    public Thread getThread() {
        return thread;
    }

    /** Closeable compatibility */
    @Override
    public void close() {
        shutdown();
    }
}