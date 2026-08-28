package com.limelight.utils;

import android.app.Activity;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

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

    /** Receives the exact buffer size selected for the presentation Surface. */
    public interface SizeHintSink {
        void setPresentationSizeHint(int width, int height);
    }

    public interface AutoCloser extends Closeable {
        void start();
        void stop();
        @Override
        default void close() throws IOException { stop(); }
    }

    /** Attach sizing to a TextureView and return a controllable handle. */
    public static AutoCloser installForTextureView(final Activity activity,
                                                   final TextureView tv,
                                                   final SizeHintSink sink) {
        final TextureViewSizer sizer = new TextureViewSizer(activity, tv, () -> applyHint(tv, sink));
        return new AutoCloser() {
            @Override public void start() { sizer.start(); }
            @Override public void stop()  { sizer.stop(); }
        };
    }

    /** Attach sizing to a SurfaceView and return a controllable handle. */
    public static AutoCloser installForSurfaceView(final Activity activity,
                                                   final SurfaceView sv,
                                                   final SizeHintSink sink) {
        final SurfaceViewSizer sizer = new SurfaceViewSizer(activity, sv, () -> applyHint(sv, sink));
        return new AutoCloser() {
            @Override public void start() { sizer.start(); }
            @Override public void stop()  { sizer.stop(); }
        };
    }

    /** Push current buffer-size hint to the renderer (best-effort). */
    private static void applyHint(final View targetView, final SizeHintSink sink) {
        if (sink == null) return;
        if (targetView == null) return;

        final int[] sz = DisplaySizer.getBestBufferSizePx(targetView);
        try {
            sink.setPresentationSizeHint(sz[0], sz[1]);
        } catch (Throwable ignored) { /* best-effort only */ }
    }
}
