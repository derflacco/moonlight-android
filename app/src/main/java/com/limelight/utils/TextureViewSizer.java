package com.limelight.utils;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.TextureView;

import java.io.Closeable;
import java.lang.ref.WeakReference;

/**
 * TextureViewSizer
 *
 * Keeps a TextureView's buffer size aligned with the chosen presentation size.
 * This avoids a secondary scale in SurfaceFlinger when post-FSR output already matches display.
 *
 * Usage:
 *   TextureViewSizer sizer = new TextureViewSizer(textureView, listener);
 *   sizer.start();
 *   ...
 *   sizer.close(); // on destroy
 */
public final class TextureViewSizer implements
        TextureView.SurfaceTextureListener,
        DisplayManager.DisplayListener,
        Closeable {

    /** Optional consumer for presentation-size changes. */
    public interface OnPresentationSizeChanged {
        void onPresentationSizeChanged(int width, int height);
    }

    private final WeakReference<TextureView> tvRef;
    private final WeakReference<OnPresentationSizeChanged> listenerRef;
    private final Context appContext;

    private volatile boolean started = false;
    private int lastW = -1, lastH = -1;

    public TextureViewSizer(TextureView tv, OnPresentationSizeChanged listener) {
        if (tv == null) throw new IllegalArgumentException("TextureView is null");
        this.tvRef = new WeakReference<>(tv);
        this.listenerRef = new WeakReference<>(listener);
        this.appContext = tv.getContext().getApplicationContext();
    }

    public void start() {
        if (started) return;
        started = true;

        final TextureView tv = tvRef.get();
        if (tv == null) return;

        // Listen for SurfaceTexture availability and display changes
        tv.setSurfaceTextureListener(this);
        registerDisplayListener();

        // If already available, apply immediately
        if (tv.isAvailable()) {
            applyFor(tv);
        }
    }

    @Override
    public void close() {
        started = false;
        final TextureView tv = tvRef.get();
        if (tv != null && tv.getSurfaceTextureListener() == this) {
            tv.setSurfaceTextureListener(null);
        }
        unregisterDisplayListener();
    }

    // ---- Internals ----

    private void applyFor(TextureView tv) {
        if (tv == null || tv.getSurfaceTexture() == null) return;

        final Display disp = (Build.VERSION.SDK_INT >= 17) ? tv.getDisplay() : null;
        final int[] sz = DisplaySizer.getDisplaySizePx(tv.getContext(), disp);
        final int w = sz[0], h = sz[1];

        if (w <= 0 || h <= 0) return;

        if (w != lastW || h != lastH) {
            tv.getSurfaceTexture().setDefaultBufferSize(w, h);
            lastW = w; lastH = h;

            final OnPresentationSizeChanged lis = listenerRef.get();
            if (lis != null) {
                lis.onPresentationSizeChanged(w, h);
            }
        }
    }

    // ---- TextureView.SurfaceTextureListener ----
    @Override
    public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
        final TextureView tv = tvRef.get();
        applyFor(tv);
    }

    @Override
    public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
        final TextureView tv = tvRef.get();
        applyFor(tv);
    }

    @Override
    public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
        // Leave buffer size as-is; will be re-applied when available again
        return true; // we don't own the texture
    }

    @Override
    public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
        // no-op
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
        final TextureView tv = tvRef.get();
        if (tv != null) applyFor(tv);
    }
}
