package com.limelight.utils;

import android.content.Context;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;

/**
 * DisplaySizer — utilities to determine the best presentation size for rendering.
 *
 * Design goals:
 * - Prefer the physical display mode (pixel-accurate, rotation-aware).
 * - Fall back gracefully on older APIs and resource metrics.
 * - Be safe for multi-display/virtual-display by accepting a target Display.
 *
 * All methods are pure and side-effect free.
 */
public final class DisplaySizer {
    private DisplaySizer() {}

    /** Returns {width, height} in physical pixels for the target display (best effort). */
    public static int[] getDisplaySizePx(Context ctx, Display targetDisplay) {
        int w = 0, h = 0;

        // 1) Prefer physical mode on API 23+
        try {
            if (targetDisplay != null && Build.VERSION.SDK_INT >= 23) {
                Display.Mode m = targetDisplay.getMode();
                if (m != null) {
                    w = Math.max(w, m.getPhysicalWidth());
                    h = Math.max(h, m.getPhysicalHeight());
                }
            }
        } catch (Throwable ignored) { }

        // 2) Fallback to getRealMetrics from the target display
        try {
            Display d = (targetDisplay != null) ? targetDisplay : getDefaultDisplay(ctx);
            if (d != null) {
                DisplayMetrics dm = new DisplayMetrics();
                // Although deprecated, this still gives raw pixel bounds including system areas.
                d.getRealMetrics(dm);
                w = Math.max(w, dm.widthPixels);
                h = Math.max(h, dm.heightPixels);
            }
        } catch (Throwable ignored) { }

        // 3) API 30+: current window bounds minus insets (useful for windowed/VD cases)
        try {
            if ((w <= 0 || h <= 0) && Build.VERSION.SDK_INT >= 30) {
                WindowManager wm = ctx.getSystemService(WindowManager.class);
                if (wm != null) {
                    WindowMetrics cur = wm.getCurrentWindowMetrics();
                    Rect b = cur.getBounds();
                    int ww = b.width();
                    int hh = b.height();

                    WindowInsets wi = cur.getWindowInsets();
                    Insets in = wi.getInsetsIgnoringVisibility(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    ww -= (in.left + in.right);
                    hh -= (in.top + in.bottom);

                    w = Math.max(w, ww);
                    h = Math.max(h, hh);
                }
            }
        } catch (Throwable ignored) { }

        // 4) Last resort: app resource metrics
        if (w <= 0 || h <= 0) {
            try {
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                w = Math.max(w, dm.widthPixels);
                h = Math.max(h, dm.heightPixels);
            } catch (Throwable ignored) { }
        }

        if (w <= 0 || h <= 0) { w = 1; h = 1; }
        return new int[]{ w, h };
    }

    private static Display getDefaultDisplay(Context ctx) {
        try {
            DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            return (dm != null) ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
