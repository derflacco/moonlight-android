package com.limelight.binding.video;

import android.content.Context;
import android.view.Surface;

import com.limelight.LimeLog;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Optional GL upscaler bridge via reflection (no hard dependency).
 *
 * Derived from AMD FidelityFX Super Resolution 1.0 (MIT).
 * See third_party/amd-fsr1/LICENSE
 */
public final class GlUpscalerBridge {

    private Object upscaler;                // com.limelight.render.GlUpscaleRenderer (via reflection)
    private Surface decoderInputSurface;    // Surface used as MediaCodec output when upscaling is enabled
    private Surface windowSurfaceRef;
    private static final class Reflect {
        private static volatile java.lang.reflect.Constructor<?> sCtor;
        private static volatile java.lang.reflect.Method sCreateInputSurface;
        private static volatile java.lang.reflect.Method sSetDebugEnabled;
        private static volatile java.lang.reflect.Method sGetOverlayLine;
        private static volatile java.lang.reflect.Method sSetPresentationHint;
        private static volatile java.lang.reflect.Method sSetPresentationSize;

        private static final java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method> sNoArg =
                new java.util.concurrent.ConcurrentHashMap<>(4);

        private static java.lang.reflect.Constructor<?> ctor() throws Throwable {
            java.lang.reflect.Constructor<?> c = sCtor;
            if (c != null) return c;
            synchronized (Reflect.class) {
                c = sCtor;
                if (c == null) {
                    Class<?> cls = Class.forName("com.limelight.render.GlUpscaleRenderer");
                    c = cls.getConstructor(Surface.class, int.class, int.class, PreferenceConfiguration.class);
                    sCtor = c;
                }
            }
            return c;
        }

        private static java.lang.reflect.Method noArg(Object inst, String name) throws Throwable {
            java.lang.reflect.Method m = sNoArg.get(name);
            if (m != null) return m;
            m = inst.getClass().getMethod(name);
            java.lang.reflect.Method prev = sNoArg.putIfAbsent(name, m);
            return (prev != null) ? prev : m;
        }

        private static java.lang.reflect.Method createInputSurface(Object inst) throws Throwable {
            java.lang.reflect.Method m = sCreateInputSurface;
            if (m != null) return m;
            synchronized (Reflect.class) {
                m = sCreateInputSurface;
                if (m == null) {
                    m = inst.getClass().getMethod("createDecoderInputSurface");
                    sCreateInputSurface = m;
                }
            }
            return m;
        }

        private static java.lang.reflect.Method setDebugEnabled(Object inst) throws Throwable {
            java.lang.reflect.Method m = sSetDebugEnabled;
            if (m != null) return m;
            synchronized (Reflect.class) {
                m = sSetDebugEnabled;
                if (m == null) {
                    m = inst.getClass().getMethod("setFsrDebugEnabled", boolean.class);
                    sSetDebugEnabled = m;
                }
            }
            return m;
        }

        private static java.lang.reflect.Method getOverlayLine(Object inst) throws Throwable {
            java.lang.reflect.Method m = sGetOverlayLine;
            if (m != null) return m;
            synchronized (Reflect.class) {
                m = sGetOverlayLine;
                if (m == null) {
                    m = inst.getClass().getMethod("getFsrOverlayLine");
                    sGetOverlayLine = m;
                }
            }
            return m;
        }

        private static java.lang.reflect.Method setPresentationHint(Object inst) throws Throwable {
            java.lang.reflect.Method m = sSetPresentationHint;
            if (m != null) return m;
            synchronized (Reflect.class) {
                m = sSetPresentationHint;
                if (m == null) {
                    m = inst.getClass().getMethod(
                            "setPresentationSizeHintFromContext",
                            android.content.Context.class);
                    sSetPresentationHint = m;
                }
            }
            return m;
        }

        private static java.lang.reflect.Method setPresentationSize(Object inst) throws Throwable {
            java.lang.reflect.Method m = sSetPresentationSize;
            if (m != null) return m;
            synchronized (Reflect.class) {
                m = sSetPresentationSize;
                if (m == null) {
                    m = inst.getClass().getMethod(
                            "setPresentationSizeHint",
                            int.class,
                            int.class);
                    sSetPresentationSize = m;
                }
            }
            return m;
        }
    }

    public boolean isReady() {
        return upscaler != null && decoderInputSurface != null;
    }

    public Surface getDecoderInputSurface() {
        return decoderInputSurface;
    }

    /**
     * Ensure the upscaler instance and its decoder input surface exist.
     * Returns true only when the Surface is ready to be passed to MediaCodec.configure().
     */
    public boolean ensureCreated(Surface windowSurface, int srcW, int srcH,
                                PreferenceConfiguration prefs, Context ctx) {
        if (windowSurface == null || prefs == null) return false;

        // Recreate if the render target surface changed
        if (windowSurfaceRef != null && windowSurfaceRef != windowSurface) {
            release();
        }
        windowSurfaceRef = windowSurface;

        if (upscaler == null) {

            try {
                upscaler = Reflect.ctor().newInstance(windowSurface, srcW, srcH, prefs);
            } catch (Throwable t) {
                LimeLog.warning("GL upscaler unavailable: " + t);
                upscaler = null;
                return false;
            }
        }

        if (decoderInputSurface == null) {
            try {
                Object s = Reflect.createInputSurface(upscaler).invoke(upscaler);
                decoderInputSurface = (Surface) s;
            } catch (Throwable t) {
                LimeLog.warning("GL upscaler input surface unavailable: " + t);
                release();
                return false;
            }

            if (decoderInputSurface == null) {
                release();
                return false;
            }
        }

        setPresentationHint(ctx);
        return true;
    }

    public void setPresentationHint(Context ctx) {
        if (upscaler == null || ctx == null) return;
        try {
            Reflect.setPresentationHint(upscaler).invoke(upscaler, ctx);
        } catch (Throwable ignored) { }
    }

    public void setPresentationSizeHint(int width, int height) {
        if (upscaler == null || width <= 0 || height <= 0) return;
        try {
            Reflect.setPresentationSize(upscaler).invoke(upscaler, width, height);
        } catch (Throwable ignored) { }
    }

    public void start() {
        callNoArg("start");
    }

    public void applyVsyncSetting() {
        callNoArg("applyVsyncSetting");
    }

    public void applyThreadPriorities() {
        callNoArg("applyThreadPriorities");
    }

    public void setDebugEnabled(boolean enabled) {
        if (upscaler == null) return;
        try {
            Reflect.setDebugEnabled(upscaler).invoke(upscaler, enabled);
        } catch (Throwable ignored) { }
    }

    public String getOverlayLine() {
        if (upscaler == null) return "";
        try {
            Object s = Reflect.getOverlayLine(upscaler).invoke(upscaler);
            return (s != null) ? s.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public float getWeightMs() {
        if (!isReady()) return 0f;

        final String line = getOverlayLine();
        if (line == null || line.isEmpty()) return 0f;

        final float ms = parseMaxMsToken(line);
        // Sanity clamp: ignore clearly bogus values
        if (ms <= 0f || ms > 50f) return 0f;

        return ms;
    }

    public void release() {
        // Release upscaler first, then its input surface (best-effort).
        if (upscaler != null) {
            try {
                Reflect.noArg(upscaler, "release").invoke(upscaler);
            } catch (Throwable ignored) { }
            upscaler = null;
        }

        if (decoderInputSurface != null) {
            try { decoderInputSurface.release(); } catch (Throwable ignored) { }
            decoderInputSurface = null;
        }
        windowSurfaceRef = null;
    }

    private void callNoArg(String name) {
        if (upscaler == null) return;
        try {
            Reflect.noArg(upscaler, name).invoke(upscaler);
        } catch (Throwable ignored) { }
    }

    // Parse the maximum "<number>ms" token found in the overlay line (case-insensitive).
    private static float parseMaxMsToken(String s) {
        if (s == null) return 0f;

        float max = 0f;

        for (int i = s.length() - 1; i >= 1; i--) {
            final char cs = s.charAt(i);
            if (cs != 's' && cs != 'S') continue;

            final char cm = s.charAt(i - 1);
            if (cm != 'm' && cm != 'M') continue;

            final int end = i - 1; // exclusive end of number (points to 'm')
            int start = end - 1;
            while (start >= 0) {
                final char c = s.charAt(start);
                if ((c >= '0' && c <= '9') || c == '.') {
                    start--;
                } else {
                    break;
                }
            }
            start++;

            if (start >= end) continue;

            final float v = parseFloatRange(s, start, end);
            if (v > max) max = v;

            // Skip over the parsed number to avoid re-parsing overlapping tokens
            i = start;
        }

        return max;
    }

    private static float parseFloatRange(String s, int start, int endExclusive) {
        float v = 0f;
        float frac = 0.1f;
        boolean seenDot = false;

        for (int i = start; i < endExclusive; i++) {
            final char c = s.charAt(i);
            if (c == '.') {
                if (seenDot) return 0f;
                seenDot = true;
                continue;
            }

            final int d = c - '0';
            if (d < 0 || d > 9) return 0f;

            if (!seenDot) {
                v = (v * 10f) + (float) d;
            } else {
                v += ((float) d) * frac;
                frac *= 0.1f;
            }
        }

        return v;
    }
}
