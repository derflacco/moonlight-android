package com.limelight.utils;

import android.app.Activity;
import android.hardware.display.DisplayManager;
import android.view.SurfaceView;
import android.view.TextureView;

import com.limelight.render.GlUpscaleRenderer;

/** One-call installer that keeps the present surface display-sized and notifies GlUpscaleRenderer with hints. */
public final class FSRSizerInstaller {

    public interface AutoCloser extends java.io.Closeable { void start(); void stop(); @Override default void close(){ stop(); } }

    private FSRSizerInstaller(){}

    public static AutoCloser installForTextureView(Activity activity, TextureView tv, GlUpscaleRenderer renderer){
        final TextureViewSizer sizer = new TextureViewSizer(activity, tv, () -> applyHint(activity, renderer));
        return new AutoCloser() {
            @Override public void start() { sizer.start(); applyHint(activity, renderer); }
            @Override public void stop() { sizer.stop(); }
        };
    }

    public static AutoCloser installForSurfaceView(Activity activity, SurfaceView sv, GlUpscaleRenderer renderer){
        final SurfaceViewSizer sizer = new SurfaceViewSizer(activity, sv, () -> applyHint(activity, renderer));
        return new AutoCloser() {
            @Override public void start() { sizer.start(); applyHint(activity, renderer); }
            @Override public void stop() { sizer.stop(); }
        };
    }

    private static void applyHint(Activity activity, GlUpscaleRenderer renderer){
        if (renderer == null) return;
        int[] sz = DisplaySizer.getPresentationSizePx(activity);
        try { renderer.setPresentationSizeHint(sz[0], sz[1]); } catch (Throwable ignored) {}
    }
}
