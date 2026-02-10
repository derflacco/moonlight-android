package com.limelight.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

/**
 * Keeps a SurfaceView's buffer size aligned with the current presentation strategy:
 * - Fullscreen-ish: match native presentation size.
 * - Letterboxed/cropped by layout: match view size to avoid compositor scaling.
 *
 * Also throttles redundant calls by caching the last applied size.
 */
public final class SurfaceViewSizer implements
        SurfaceHolder.Callback, DisplayManager.DisplayListener, View.OnLayoutChangeListener {

    private final Context ctx;
    private final SurfaceView sv;
    private final Runnable onApplied; // optional callback (e.g., to notify renderer)

    private boolean started = false;
    private int lastW = -1;
    private int lastH = -1;

    public SurfaceViewSizer(Context ctx, SurfaceView sv, Runnable onApplied) {
        this.ctx = ctx.getApplicationContext();
        this.sv = sv;
        this.onApplied = onApplied;
    }

    public void start() {
        if (started) return;
        started = true;

        sv.getHolder().addCallback(this);
        sv.addOnLayoutChangeListener(this);

        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.registerDisplayListener(this, null);

        applyIfNeeded();
    }

    public void stop() {
        if (!started) return;
        started = false;

        try { sv.getHolder().removeCallback(this); } catch (Throwable ignored) {}
        try { sv.removeOnLayoutChangeListener(this); } catch (Throwable ignored) {}

        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.unregisterDisplayListener(this);

        lastW = -1;
        lastH = -1;
    }

    private void applyIfNeeded() {
        if (sv == null) return;

        int[] sz = DisplaySizer.getBestBufferSizePx(sv);
        int w = sz[0], h = sz[1];
        if (w <= 0 || h <= 0) return;

        if (w == lastW && h == lastH) return;

        lastW = w;
        lastH = h;

        try {
            SurfaceHolder holder = sv.getHolder();
            if (holder != null) holder.setFixedSize(w, h);
        } catch (Throwable ignored) {}

        if (onApplied != null) {
            try { onApplied.run(); } catch (Throwable ignored) {}
        }
    }

    // SurfaceHolder.Callback
    @Override public void surfaceCreated(SurfaceHolder holder) { applyIfNeeded(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { applyIfNeeded(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {}

    // DisplayManager.DisplayListener
    @Override public void onDisplayChanged(int displayId) { applyIfNeeded(); }
    @Override public void onDisplayAdded(int displayId) {}
    @Override public void onDisplayRemoved(int displayId) {}

    // View.OnLayoutChangeListener
    @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                         int ol, int ot, int orr, int ob) {
        applyIfNeeded();
    }
}
