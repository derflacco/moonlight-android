package com.limelight.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

/**
 * Keeps a small, non-transparent UI region changing while a stream is active.
 *
 * Some Android TV firmware takes an unstable direct-composition path when the
 * video SurfaceView is the only visible content. A regularly changing UI pixel
 * keeps the app's UI layer participating in composition without requiring the
 * performance overlay (and its statistics work) to remain enabled.
 */
public final class CompositorHeartbeat {
    static final long INTERVAL_MS = 100L;
    static final int COLOR_PHASE_ONE = 0xFF000000;
    static final int COLOR_PHASE_TWO = 0xFF010101;

    private final View heartbeatView;
    private final Handler handler;
    private boolean running;
    private boolean alternatePhase;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }

            heartbeatView.setBackgroundColor(alternatePhase ? COLOR_PHASE_TWO : COLOR_PHASE_ONE);
            alternatePhase = !alternatePhase;
            heartbeatView.invalidate();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    public CompositorHeartbeat(View heartbeatView) {
        if (heartbeatView == null) {
            throw new IllegalArgumentException("heartbeatView must not be null");
        }

        this.heartbeatView = heartbeatView;
        this.handler = new Handler(Looper.getMainLooper());
    }

    public void start() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::start);
            return;
        }

        if (running) {
            return;
        }

        running = true;
        heartbeatView.setVisibility(View.VISIBLE);
        heartbeat.run();
    }

    public void stop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(this::stop);
            return;
        }

        running = false;
        handler.removeCallbacks(heartbeat);
        heartbeatView.setVisibility(View.GONE);
    }
}
