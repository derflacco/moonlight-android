package com.limelight.utils;

import android.app.Activity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import com.limelight.render.GlUpscaleRenderer;

import java.io.Closeable;
import java.io.IOException;

/**
 * One-call installer that keeps the target view's buffer size matched to the best strategy
 * (presentation size when fullscreen, view size when letterboxed) and notifies GlUpscaleRenderer
 * with current buffer-size hints.
 */
public final class FSRSizerInstaller {

    /** Prevent instantiation. */
    private FSRSizerInstaller() {}

    public interface AutoCloser extends Closeable {
        void start();
        void stop();
        @Override
        default void close() throws IOException { stop(); }
    }

    /** Attach sizing to a TextureView and return a controllable handle. */
    public static AutoCloser installForTextureView(final Activity activity,
                                                   final TextureView tv,
                                                   final GlUpscaleRenderer renderer) {
        final TextureViewSizer sizer = new TextureViewSizer(activity, tv, () -> applyHint(tv, renderer));
        return new AutoCloser() {
            @Override public void start() { sizer.start(); applyHint(tv, renderer); }
            @Override public void stop()  { sizer.stop(); }
        };
    }

    /** Attach sizing to a SurfaceView and return a controllable handle. */
    public static AutoCloser installForSurfaceView(final Activity activity,
                                                   final SurfaceView sv,
                                                   final GlUpscaleRenderer renderer) {
        final SurfaceViewSizer sizer = new SurfaceViewSizer(activity, sv, () -> applyHint(sv, renderer));
        return new AutoCloser() {
            @Override public void start() { sizer.start(); applyHint(sv, renderer); }
            @Override public void stop()  { sizer.stop(); }
        };
    }

    /** Push current buffer-size hint to the renderer (best-effort). */
    private static void applyHint(final View targetView, final GlUpscaleRenderer renderer) {
        if (renderer == null) return;
        if (targetView == null) return;

        final int[] sz = DisplaySizer.getBestBufferSizePx(targetView);
        try {
            renderer.setPresentationSizeHint(sz[0], sz[1]);
        } catch (Throwable ignored) { /* best-effort only */ }
    }
}
