package com.limelight.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

/** Keeps a SurfaceView's buffer size matched to the native display size and updates on rotation/mode changes. */
public final class SurfaceViewSizer implements
        SurfaceHolder.Callback, DisplayManager.DisplayListener, View.OnLayoutChangeListener {

    private final Context ctx;
    private final SurfaceView sv;
    private final Runnable onApplied; // optional callback (e.g., to notify renderer)

    public SurfaceViewSizer(Context ctx, SurfaceView sv, Runnable onApplied) {
        this.ctx = ctx.getApplicationContext();
        this.sv = sv;
        this.onApplied = onApplied;
    }

    public void start() {
        sv.getHolder().addCallback(this);
        sv.addOnLayoutChangeListener(this);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.registerDisplayListener(this, null);
        // Try immediately
        DisplaySizer.applyTo(sv);
        if (onApplied != null) onApplied.run();
    }

    public void stop() {
        sv.getHolder().removeCallback(this);
        sv.removeOnLayoutChangeListener(this);
        DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
        if (dm != null) dm.unregisterDisplayListener(this);
    }

    // SurfaceHolder.Callback
    @Override public void surfaceCreated(SurfaceHolder holder) {
        DisplaySizer.applyTo(sv);
        if (onApplied != null) onApplied.run();
    }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        DisplaySizer.applyTo(sv);
        if (onApplied != null) onApplied.run();
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder) {}

    // DisplayManager.DisplayListener
    @Override public void onDisplayChanged(int displayId) {
        DisplaySizer.applyTo(sv);
        if (onApplied != null) onApplied.run();
    }
    @Override public void onDisplayAdded(int displayId) {}
    @Override public void onDisplayRemoved(int displayId) {}

    // View.OnLayoutChangeListener
    @Override public void onLayoutChange(View v, int l, int t, int r, int b,
                                        int ol, int ot, int orr, int ob) {
        DisplaySizer.applyTo(sv);
        if (onApplied != null) onApplied.run();
    }
}
