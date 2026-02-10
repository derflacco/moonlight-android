package com.limelight.utils;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;

/**
 * Computes a good buffer size for video presentation and applies it to TextureView/SurfaceView.
 *
 * Why "best buffer size":
 * - If the view is fullscreen (or close), we match the native presentation size.
 * - If the view is letterboxed/cropped by layout, we match the view size to avoid SurfaceFlinger scaling.
 *
 * This is especially important for HDR: compositor scaling/cropping can force GPU composition or different tone-mapping.
 */
public final class DisplaySizer {

    private DisplaySizer() {}

    // Allow small mismatches due to insets/rounding without treating the view as "not fullscreen".
    private static final int MATCH_DISPLAY_TOLERANCE_PX = 12;

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

    /** Returns {width, height} of the current view size (layout size). Returns {0,0} if unknown. */
    public static int[] getViewSizePx(View v) {
        if (v == null) return new int[]{0, 0};

        int w = 0, h = 0;

        try {
            w = v.getWidth();
            h = v.getHeight();
        } catch (Throwable ignored) {}

        // Fallback to measured size if layout hasn't happened yet.
        if ((w <= 0 || h <= 0)) {
            try {
                w = v.getMeasuredWidth();
                h = v.getMeasuredHeight();
            } catch (Throwable ignored) {}
        }

        if (w <= 0 || h <= 0) return new int[]{0, 0};
        return new int[]{ w, h };
    }

    /**
     * Choose the best buffer size for the given view:
     * - If the view is fullscreen-ish, match the presentation size.
     * - Otherwise, match the view size to avoid compositor scaling.
     */
    public static int[] getBestBufferSizePx(View v) {
        if (v == null) return new int[]{1, 1};

        final Context ctx = v.getContext();
        final int[] pres = getPresentationSizePx(ctx);
        final int pw = pres[0];
        final int ph = pres[1];

        final int[] vsz = getViewSizePx(v);
        final int vw = vsz[0];
        final int vh = vsz[1];

        // If view size is unknown, fall back to presentation size.
        if (vw <= 0 || vh <= 0) return pres;

        // If view is close to presentation size, treat it as fullscreen.
        if (Math.abs(vw - pw) <= MATCH_DISPLAY_TOLERANCE_PX &&
                Math.abs(vh - ph) <= MATCH_DISPLAY_TOLERANCE_PX) {
            return pres;
        }

        // Otherwise match the view size (avoid SurfaceFlinger scaling).
        return new int[]{ vw, vh };
    }

    /** Applies best buffer size to TextureView.setDefaultBufferSize(...). */
    public static void applyTo(TextureView tv) {
        if (tv == null) return;
        if (tv.getSurfaceTexture() == null) return;

        int[] sz = getBestBufferSizePx(tv);
        int w = sz[0], h = sz[1];
        if (w <= 0 || h <= 0) return;

        try {
            tv.getSurfaceTexture().setDefaultBufferSize(w, h);
        } catch (Throwable ignored) {}
    }

    /** Applies best buffer size to SurfaceView.getHolder().setFixedSize(...). */
    public static void applyTo(SurfaceView sv) {
        if (sv == null) return;

        SurfaceHolder h = sv.getHolder();
        if (h == null) return;

        int[] sz = getBestBufferSizePx(sv);
        int w = sz[0], hh = sz[1];
        if (w <= 0 || hh <= 0) return;

        try {
            h.setFixedSize(w, hh);
        } catch (Throwable ignored) {}
    }
}
