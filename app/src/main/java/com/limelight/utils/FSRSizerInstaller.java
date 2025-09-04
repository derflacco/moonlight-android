package com.limelight.utils;

import android.app.Activity;
import android.view.SurfaceView;
import android.view.TextureView;

import com.limelight.render.GlUpscaleRenderer;

import java.io.Closeable;
import java.io.IOException;

/**
 * One-call installer that keeps the target view's buffer size matched to the display size
 * and notifies GlUpscaleRenderer with presentation-size hints.
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
        final TextureViewSizer sizer = new TextureViewSizer(activity, tv, () -> applyHint(activity, renderer));
        return new AutoCloser() {
            @Override public void start() { sizer.start(); applyHint(activity, renderer); }
            @Override public void stop()  { sizer.stop(); }
        };
    }

    /** Attach sizing to a SurfaceView and return a controllable handle. */
    public static AutoCloser installForSurfaceView(final Activity activity,
                                                   final SurfaceView sv,
                                                   final GlUpscaleRenderer renderer) {
        final SurfaceViewSizer sizer = new SurfaceViewSizer(activity, sv, () -> applyHint(activity, renderer));
        return new AutoCloser() {
            @Override public void start() { sizer.start(); applyHint(activity, renderer); }
            @Override public void stop()  { sizer.stop(); }
        };
    }

    /** Push current presentation-size hint to the renderer (best-effort). */
    private static void applyHint(final Activity activity, final GlUpscaleRenderer renderer) {
        if (renderer == null) return;
        final int[] sz = DisplaySizer.getPresentationSizePx(activity);
        try {
            renderer.setPresentationSizeHint(sz[0], sz[1]);
        } catch (Throwable ignored) { /* best-effort only */ }
    }
}
