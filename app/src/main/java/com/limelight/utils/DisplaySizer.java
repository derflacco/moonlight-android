package com.limelight.utils;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.WindowManager;
import android.view.WindowMetrics;

/** Computes the native presentation size and applies it to TextureView/SurfaceView. */
public final class DisplaySizer {

    private DisplaySizer() {}

    /** Returns {width, height} in pixels of the native presentation (for current rotation). */
    public static int[] getPresentationSizePx(Context ctx) {
        int w = 0, h = 0;

        // API 30+: maximum window metrics (rotation-aware, minimal system insets)
        try {
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            if (wm != null) {
                WindowMetrics m = wm.getMaximumWindowMetrics();
                Rect b = m.getBounds();
                w = Math.max(w, b.width());
                h = Math.max(h, b.height());
            }
        } catch (Throwable ignored) {}

        // All APIs: real metrics from default display
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            Display d = (dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null);
            if (d != null) {
                DisplayMetrics dmets = new DisplayMetrics();
                d.getRealMetrics(dmets);
                w = Math.max(w, dmets.widthPixels);
                h = Math.max(h, dmets.heightPixels);
            }
        } catch (Throwable ignored) {}

        // Fallback: app resources metrics (may include decor)
        if (w <= 0 || h <= 0) {
            try {
                DisplayMetrics dmets = ctx.getResources().getDisplayMetrics();
                w = Math.max(w, dmets.widthPixels);
                h = Math.max(h, dmets.heightPixels);
            } catch (Throwable ignored) {}
        }

        if (w <= 0 || h <= 0) { w = 1; h = 1; }
        return new int[]{ w, h };
    }

    /** Applies size to TextureView.setDefaultBufferSize(...) when available. */
    public static void applyTo(TextureView tv) {
        if (tv == null) return;
        if (tv.getSurfaceTexture() == null) return;
        Context ctx = tv.getContext();
        int[] sz = getPresentationSizePx(ctx);
        tv.getSurfaceTexture().setDefaultBufferSize(sz[0], sz[1]);
    }

    /** Applies size to SurfaceView.getHolder().setFixedSize(...) */
    public static void applyTo(SurfaceView sv) {
        if (sv == null) return;
        SurfaceHolder h = sv.getHolder();
        if (h == null) return;
        Context ctx = sv.getContext();
        int[] sz = getPresentationSizePx(ctx);
        h.setFixedSize(sz[0], sz[1]);
    }
}
