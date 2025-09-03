package com.limelight.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.TextureView;
import android.view.View;

/** Keeps a TextureView's buffer size matched to the native display size and updates on rotation/mode changes. */
public final class TextureViewSizer implements
        TextureView.SurfaceTextureListener, DisplayManager.DisplayListener, View.OnLayoutChangeListener {

    private final Context ctx;
    private final TextureView tv;
    private final Runnable onApplied; // optional callback (e.g., to notify renderer)

    public TextureViewSizer(Context ctx, TextureView tv, Runnable onApplied) {
        this.ctx = ctx.getApplicationContext();
        this.tv = tv;
        this.onApplied = onApplied;
    }

    public void start() {
        tv.setSurfaceTextureListener(this);
        tv.addOnLayoutChangeListener(this);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.registerDisplayListener(this, null);
        // Try immediately
        DisplaySizer.applyTo(tv);
        if (onApplied != null) onApplied.run();
    }

    public void stop() {
        tv.setSurfaceTextureListener(null);
        tv.removeOnLayoutChangeListener(this);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.unregisterDisplayListener(this);
    }

    // TextureView.SurfaceTextureListener
    @Override public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int w, int h) {
        DisplaySizer.applyTo(tv);
        if (onApplied != null) onApplied.run();
    }
    @Override public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture s, int w, int h) {
        DisplaySizer.applyTo(tv);
        if (onApplied != null) onApplied.run();
    }
    @Override public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) { return true; }
    @Override public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}

    // DisplayManager.DisplayListener — called on rotation / mode change
    @Override public void onDisplayChanged(int displayId) {
        DisplaySizer.applyTo(tv);
        if (onApplied != null) onApplied.run();
    }
    @Override public void onDisplayAdded(int displayId) {}
    @Override public void onDisplayRemoved(int displayId) {}

    // View.OnLayoutChangeListener (e.g., insets / nav bar changes)
    @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                        int ol, int ot, int orr, int ob) {
        DisplaySizer.applyTo(tv);
        if (onApplied != null) onApplied.run();
    }
}
