package com.limelight.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.Closeable;
import java.lang.ref.WeakReference;

/**
 * SurfaceViewSizer
 *
 * Keeps a SurfaceView's Surface buffer (via setFixedSize) aligned with presentation size.
 * Use this only if you truly want the post-FSR render target to match display pixels to avoid
 * SF scaling. If you want layout-driven sizing, call clearFixedSize() to reset to 0x0.
 *
 * Usage:
 *   SurfaceViewSizer sizer = new SurfaceViewSizer(surfaceView, listener);
 *   sizer.start();
 *   ...
 *   sizer.close();
 */
public final class SurfaceViewSizer implements
        SurfaceHolder.Callback,
        DisplayManager.DisplayListener,
        Closeable {

    /** Optional consumer for presentation-size changes. */
    public interface OnPresentationSizeChanged {
        void onPresentationSizeChanged(int width, int height);
    }

    private final WeakReference<SurfaceView> svRef;
    private final WeakReference<OnPresentationSizeChanged> listenerRef;
    private final Context appContext;

    private volatile boolean started = false;
    private int lastW = -1, lastH = -1;

    public SurfaceViewSizer(SurfaceView sv, OnPresentationSizeChanged listener) {
        if (sv == null) throw new IllegalArgumentException("SurfaceView is null");
        this.svRef = new WeakReference<>(sv);
        this.listenerRef = new WeakReference<>(listener);
        this.appContext = sv.getContext().getApplicationContext();
    }

    public void start() {
        if (started) return;
        started = true;

        final SurfaceView sv = svRef.get();
        if (sv == null) return;

        sv.getHolder().addCallback(this);
        registerDisplayListener();

        // If a surface already exists, apply immediately
        if (sv.getHolder().getSurface() != null && sv.getHolder().getSurface().isValid()) {
            applyFor(sv);
        }
    }

    @Override
    public void close() {
        started = false;
        final SurfaceView sv = svRef.get();
        if (sv != null) {
            sv.getHolder().removeCallback(this);
        }
        unregisterDisplayListener();
    }

    // ---- Internals ----
    private void applyFor(SurfaceView sv) {
        if (sv == null) return;

        final Display disp = (Build.VERSION.SDK_INT >= 17) ? sv.getDisplay() : null;
        final int[] sz = DisplaySizer.getDisplaySizePx(sv.getContext(), disp);
        final int w = sz[0], h = sz[1];
        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) {
            sv.getHolder().setFixedSize(w, h);
            lastW = w; lastH = h;

            final OnPresentationSizeChanged lis = listenerRef.get();
            if (lis != null) {
                lis.onPresentationSizeChanged(w, h);
            }
        }
    }

    /** Call to reset fixed size and let layout drive SurfaceView dimensions again. */
    public void clearFixedSize() {
        final SurfaceView sv = svRef.get();
        if (sv != null) {
            sv.getHolder().setFixedSize(0, 0);
            lastW = lastH = -1;
        }
    }

    // ---- SurfaceHolder.Callback ----
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        final SurfaceView sv = svRef.get();
        applyFor(sv);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        final SurfaceView sv = svRef.get();
        applyFor(sv);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Leave as-is; caller can clearFixedSize() if needed
    }

    // ---- DisplayManager.DisplayListener ----
    private void registerDisplayListener() {
        try {
            DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) dm.registerDisplayListener(this, null);
        } catch (Throwable ignored) { }
    }

    private void unregisterDisplayListener() {
        try {
            DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
            if (dm != null) dm.unregisterDisplayListener(this);
        } catch (Throwable ignored) { }
    }

    @Override
    public void onDisplayAdded(int displayId) { /* no-op */ }

    @Override
    public void onDisplayRemoved(int displayId) { /* no-op */ }

    @Override
    public void onDisplayChanged(int displayId) {
        final SurfaceView sv = svRef.get();
        if (sv != null) applyFor(sv);
    }
}
