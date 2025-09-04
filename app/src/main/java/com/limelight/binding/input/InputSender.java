// InputSender.java
package com.limelight.binding.input;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import java.util.concurrent.atomic.AtomicInteger;

public final class InputSender {
    public interface Sink {
        void send(byte type, int a, int b, int c);
    }

    private static final int CAP = 256; // power of two

    private final byte[] t = new byte[CAP];
    private final int[]  a = new int[CAP];
    private final int[]  b = new int[CAP];
    private final int[]  c = new int[CAP];
    private final AtomicInteger head = new AtomicInteger(0);
    private final AtomicInteger tail = new AtomicInteger(0);

    private final HandlerThread thread;
    private final Handler handler;
    private final Sink sink;

    public InputSender(Sink sink) {
        this.sink = sink;
        this.thread = new HandlerThread("InputSender", Process.THREAD_PRIORITY_DISPLAY);
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    public void offer(byte type, int A, int B, int C) {
        final int h = head.get();
        final int n = (h + 1) & (CAP - 1);
        if (n == tail.get()) {
            // ring full: drop newest to avoid latency spikes
            return;
        }
        t[h] = type; a[h] = A; b[h] = B; c[h] = C;
        head.set(n);
        handler.post(this::drainOnce);
    }

    /** Allows posting arbitrary runnables on the same high-priority thread. */
    public void post(Runnable r) {
        handler.post(r);
    }

    private void drainOnce() {
        int tt = tail.get();
        final int hh = head.get();
        while (tt != hh) {
            sink.send(t[tt], a[tt], b[tt], c[tt]);
            tt = (tt + 1) & (CAP - 1);
        }
        tail.set(tt);
    }

    public void shutdown() {
        try {
            thread.quitSafely();
        } catch (Throwable ignored) {
            thread.quit();
        }
    }
}
