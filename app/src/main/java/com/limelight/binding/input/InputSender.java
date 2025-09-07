package com.limelight.binding.input;

import android.os.Handler;
import android.os.HandlerThread;

public final class InputSender {
    private final HandlerThread thread;
    private final Handler handler;
    private volatile boolean stopped = false;

    public InputSender(Object unused) {
        thread = new HandlerThread("InputSender");
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    public void post(Runnable r) {
        if (stopped || r == null) return;
        try {
            handler.post(r);
        } catch (Throwable ignored) {}
    }

    public void shutdown() {
        stopped = true;
        try {
            thread.quitSafely();
        } catch (Throwable t) {
            try { thread.quit(); } catch (Throwable ignored) {}
        }
    }
}
