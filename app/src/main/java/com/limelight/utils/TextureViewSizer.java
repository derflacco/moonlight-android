package com.limelight.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.TextureView;
import android.view.View;

/**
 * Keeps a TextureView's buffer size aligned with the current presentation strategy:
 * - Fullscreen-ish: match native presentation size.
 * - Letterboxed/cropped by layout: match view size to avoid compositor scaling.
 *
 * Also throttles redundant calls by caching the last applied size.
 */
public final class TextureViewSizer implements
        TextureView.SurfaceTextureListener, DisplayManager.DisplayListener, View.OnLayoutChangeListener {

    private final Context ctx;
    private final TextureView tv;
    private final Runnable onApplied; // optional callback (e.g., to notify renderer)

    private boolean started = false;
    private int lastW = -1;
    private int lastH = -1;

    public TextureViewSizer(Context ctx, TextureView tv, Runnable onApplied) {
        this.ctx = ctx.getApplicationContext();
        this.tv = tv;
        this.onApplied = onApplied;
    }

    public void start() {
        if (started) return;
        started = true;

        tv.setSurfaceTextureListener(this);
        tv.addOnLayoutChangeListener(this);

        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.registerDisplayListener(this, null);

        applyIfNeeded();
    }

    public void stop() {
        if (!started) return;
        started = false;

        try { tv.setSurfaceTextureListener(null); } catch (Throwable ignored) {}
        try { tv.removeOnLayoutChangeListener(this); } catch (Throwable ignored) {}

        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.unregisterDisplayListener(this);

        lastW = -1;
        lastH = -1;
    }

    private void applyIfNeeded() {
        if (tv == null) return;
        if (tv.getSurfaceTexture() == null) return;

        int[] sz = DisplaySizer.getBestBufferSizePx(tv);
        int w = sz[0], h = sz[1];
        if (w <= 0 || h <= 0) return;

        if (w == lastW && h == lastH) return;

        lastW = w;
        lastH = h;

        try {
            tv.getSurfaceTexture().setDefaultBufferSize(w, h);
        } catch (Throwable ignored) {}

        if (onApplied != null) {
            try { onApplied.run(); } catch (Throwable ignored) {}
        }
    }

    // TextureView.SurfaceTextureListener
    @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int w, int h) { applyIfNeeded(); }
    @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s, int w, int h) { applyIfNeeded(); }
    @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) { return true; }
    @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}

    // DisplayManager.DisplayListener — called on rotation / mode change
    @Override public void onDisplayChanged(int displayId) { applyIfNeeded(); }
    @Override public void onDisplayAdded(int displayId) {}
    @Override public void onDisplayRemoved(int displayId) {}

    // View.OnLayoutChangeListener (e.g., insets / nav bar changes)
    @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                         int ol, int ot, int orr, int ob) {
        applyIfNeeded();
    }
}
