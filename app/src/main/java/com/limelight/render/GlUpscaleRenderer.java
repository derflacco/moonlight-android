package com.limelight.render;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Process;
import android.view.Surface;
import androidx.annotation.Keep;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.LimeLog;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Choreographer;

/*
 * FidelityFX Super Resolution 1.0 (FSR1) — EASU + RCAS (GLES3 + OES port)
 * Copyright (c) 2021 Advanced Micro Devices, Inc.
 * SPDX-License-Identifier: MIT
 *
 * This renderer adapts the AMD FSR1 reference approach for Android:
 * - MediaCodec decodes into a SurfaceTexture bound to GL_TEXTURE_EXTERNAL_OES.
 * - We apply ONLY the SurfaceTexture transform matrix (no manual flips).
 * - EASU: Edge-Adaptive Spatial Upsampling (reference-style taps & edge guidance).
 * - RCAS: Robust Contrast Adaptive Sharpening (reference-style local clamp).
 *
 * Notes:
 * - SDR/sRGB only. For HDR do tone-map on the server before encode.
 * - A small near-native bypass avoids unnecessary blur when scale≈1x.
 */
public final class GlUpscaleRenderer implements SurfaceTexture.OnFrameAvailableListener {
    // ===== HDR / Direct Present state =====
    // These flags are driven from Game/decoder:
    private volatile boolean hdrActive = false;
    private volatile boolean hdrDirectPresent = false;
    // One-shot warning to avoid log spam when HDR is active without GPU path
    private volatile boolean hdrNoGpuPathWarned = false;

    private final ByteBuffer testPixelBuffer = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder());


    // RCAS_OES health-check state
    private boolean rcasOesChecked = false;
    private boolean rcasOesHealthy = false;
    private int rcasOesCheckAttempts = 0;
    private static final int RCAS_OES_CHECK_MAX_ATTEMPTS = 3;

    private int lastProgram = -1;
    private int lastTexture = -1;
    private long eglContextHandle = 0L;
    private long eglWindowSurfaceHandle = 0L;
    private long attachedEglContextHandle = 0L;


    // ===== FSR Telemetry (lightweight) =====
    private static final class FsrTelemetry {
        boolean enabled = false;
        long frames = 0L;

        // CPU-side submission timing (System.nanoTime)
        private double easuCpuAvgNs = 0.0;
        private double rcasCpuAvgNs = 0.0;

        private double blitCpuAvgNs = 0.0;
        // GPU-side timing via timer queries (GL_EXT_disjoint_timer_query)
        private double easuGpuAvgNs = 0.0;
        private double rcasGpuAvgNs = 0.0;

        private double blitGpuAvgNs = 0.0;
        int disjointEvents = 0;

        String mode = "BYPASS";
        float sharp = 0f;
        int srcW = 0, srcH = 0, dstW = 0, dstH = 0;
        String sampling = "";
        String notes = "";

        private static long now() { return System.nanoTime(); }

        private static double ewma(double avg, long sample) {
            final double a = 0.2;
            return (avg == 0.0) ? sample : (a * sample + (1.0 - a) * avg);
        }

        private long tBlit = 0L, tEasu = 0L, tRcas = 0L;

        void ticBlit() { tBlit = now(); }
        void tocBlit() { blitCpuAvgNs = ewma(blitCpuAvgNs, now() - tBlit); }

        void ticEasu() { tEasu = now(); }
        void tocEasu() { easuCpuAvgNs = ewma(easuCpuAvgNs, now() - tEasu); }

        void ticRcas() { tRcas = now(); }
        void tocRcas() { rcasCpuAvgNs = ewma(rcasCpuAvgNs, now() - tRcas); }

        void pushGpuEasu(long gpuNs) { easuGpuAvgNs = ewma(easuGpuAvgNs, gpuNs); }
        void pushGpuRcas(long gpuNs) { rcasGpuAvgNs = ewma(rcasGpuAvgNs, gpuNs); }
        void pushGpuBlit(long gpuNs) { blitGpuAvgNs = ewma(blitGpuAvgNs, gpuNs); }

        private double easuBestNs() { return (easuGpuAvgNs > 0.0) ? easuGpuAvgNs : easuCpuAvgNs; }
        private double rcasBestNs() { return (rcasGpuAvgNs > 0.0) ? rcasGpuAvgNs : rcasCpuAvgNs; }
        private double blitBestNs() { return (blitGpuAvgNs > 0.0) ? blitGpuAvgNs : blitCpuAvgNs; }

        private String timingSrcTagForMode() {
            final boolean includeBlit = (sampling != null && sampling.contains("OES->2D"));
            final boolean easuGpu = (easuGpuAvgNs > 0.0);
            final boolean rcasGpu = (rcasGpuAvgNs > 0.0);
            final boolean blitGpu = includeBlit && (blitGpuAvgNs > 0.0);

            if ("EASU+RCAS".equals(mode)) {
                if (includeBlit) {
                    if (easuGpu && rcasGpu && blitGpu) return "GPU";
                    if (!easuGpu && !rcasGpu && !blitGpu) return "CPU";
                    return "MIX";
                } else {
                    if (easuGpu && rcasGpu) return "GPU";
                    if (!easuGpu && !rcasGpu) return "CPU";
                    return "MIX";
                }
            }

            if ("RCAS_ONLY".equals(mode)) {
                if (includeBlit) {
                    if (rcasGpu && blitGpu) return "GPU";
                    if (!rcasGpu && !blitGpu) return "CPU";
                    return "MIX";
                } else {
                    return rcasGpu ? "GPU" : "CPU";
                }
            }

            return "CPU";
        }

        private final StringBuilder lineSb = new StringBuilder(256);

        String overlayLine() {
            lineSb.setLength(0);

            if ("EASU+RCAS".equals(mode)) {
                final double easuMs  = easuBestNs() / 1e6;
                final double rcasMs  = rcasBestNs() / 1e6;
                final boolean includeBlit = (sampling != null && sampling.contains("OES->2D"));
                final double blitMs  = includeBlit ? (blitBestNs() / 1e6) : 0.0;
                final double totalMs = blitMs + easuMs + rcasMs;
                final String src = timingSrcTagForMode();

                lineSb.append("FSR ").append(mode)
                        .append(" | sharp=");
                appendFixed2(lineSb, sharp);

                if (includeBlit) {
                    lineSb.append(" | BLIT=");
                    appendFixed2(lineSb, blitMs);
                    lineSb.append("ms");
                }

                lineSb.append(" | EASU=");
                appendFixed2(lineSb, easuMs);
                lineSb.append("ms RCAS=");
                appendFixed2(lineSb, rcasMs);
                lineSb.append("ms TOT=");
                appendFixed2(lineSb, totalMs);
                lineSb.append("ms (").append(src).append(')');

                return lineSb.toString();
            }

            if ("RCAS_ONLY".equals(mode)) {
                final double rcasMs = rcasBestNs() / 1e6;
                final boolean includeBlit = (sampling != null && sampling.contains("OES->2D"));
                final double blitMs = includeBlit ? (blitBestNs() / 1e6) : 0.0;
                final double totalMs = blitMs + rcasMs;
                final String src = timingSrcTagForMode();

                lineSb.append("FSR ").append(mode)
                        .append(" | sharp=");
                appendFixed2(lineSb, sharp);

                if (includeBlit) {
                    lineSb.append(" | BLIT=");
                    appendFixed2(lineSb, blitMs);
                    lineSb.append("ms");
                }

                lineSb.append(" | RCAS=");
                appendFixed2(lineSb, rcasMs);
                lineSb.append("ms");
                if (includeBlit) {
                    lineSb.append(" TOT=");
                    appendFixed2(lineSb, totalMs);
                    lineSb.append("ms");
                }
                lineSb.append(" (").append(src).append(')');

                return lineSb.toString();
            }

            return "FSR BYPASS";
        }

        String periodicLine() {
            lineSb.setLength(0);

            final double easuMs = easuBestNs() / 1e6;
            final double rcasMs = rcasBestNs() / 1e6;
            final boolean includeBlit = (sampling != null && sampling.contains("OES->2D"));
            final double blitMs = includeBlit ? (blitBestNs() / 1e6) : 0.0;
            final String src = timingSrcTagForMode();

            lineSb.append("FSR[").append(mode).append('/').append(src).append("] ")
                    .append(srcW).append('x').append(srcH)
                    .append(" -> ")
                    .append(dstW).append('x').append(dstH)
                    .append(" | sharp=");
            appendFixed2(lineSb, sharp);

            lineSb.append(" | ").append(sampling);

            if (includeBlit) {
                lineSb.append(" | BLIT(avg)=");
                appendFixed2(lineSb, blitMs);
                lineSb.append("ms");
            }

            lineSb.append(" | EASU(avg)=");
            appendFixed2(lineSb, easuMs);
            lineSb.append("ms RCAS(avg)=");
            appendFixed2(lineSb, rcasMs);
            lineSb.append("ms | disjoint=").append(disjointEvents);

            if (notes != null && !notes.isEmpty()) {
                lineSb.append(" | ").append(notes);
            }

            return lineSb.toString();
        }
    }

    // ===== GPU timer queries for FSR telemetry (GL_EXT_disjoint_timer_query) =====
    private static final class GpuTimeQueryRing {
        // GL_EXT_disjoint_timer_query constants
        private static final int GL_TIME_ELAPSED_EXT        = 0x88BF;
        private static final int GL_GPU_DISJOINT_EXT        = 0x8FBB;
        private static final int GL_QUERY_RESULT            = 0x8866;
        private static final int GL_QUERY_RESULT_AVAILABLE  = 0x8867;

        private static final int RING = 8;

        private boolean initTried = false;
        private boolean supported = false;
        private boolean useExt = false;

        private final int[] queries = new int[RING];
        private final boolean[] pending = new boolean[RING];
        private int write = 0;
        private int read = 0;

        private boolean localActive = false;
        private static boolean globalActive = false;

        private boolean disjointTripped = false;

        private final int[] tmpInt = new int[1];
        private final long[] tmpLong = new long[1];

        private static boolean sExtLoaded = false;
        private static java.lang.reflect.Method sGenQueriesEXT;
        private static java.lang.reflect.Method sDeleteQueriesEXT;
        private static java.lang.reflect.Method sBeginQueryEXT;
        private static java.lang.reflect.Method sEndQueryEXT;
        private static java.lang.reflect.Method sGetQueryObjectuivEXT;
        private static java.lang.reflect.Method sGetQueryObjectui64vEXT;
        private static java.lang.reflect.Method sGetQueryObjectui64vCore;

        private static void loadExtIfNeeded() {      if (sExtLoaded) return;
            sExtLoaded = true;

            try {
                final Class<?> c = android.opengl.GLES30.class;
                sGenQueriesEXT = c.getMethod("glGenQueriesEXT", int.class, int[].class, int.class);
                sDeleteQueriesEXT = c.getMethod("glDeleteQueriesEXT", int.class, int[].class, int.class);
                sBeginQueryEXT = c.getMethod("glBeginQueryEXT", int.class, int.class);
                sEndQueryEXT = c.getMethod("glEndQueryEXT", int.class);
                sGetQueryObjectuivEXT = c.getMethod("glGetQueryObjectuivEXT", int.class, int.class, int[].class, int.class);
                try {

                    sGetQueryObjectui64vEXT = c.getMethod("glGetQueryObjectui64vEXT", int.class, int.class, long[].class, int.class);
                } catch (Throwable ignored) {
                    sGetQueryObjectui64vEXT = null;
                }
            } catch (Throwable ignored) {
                sGenQueriesEXT = null;
                sDeleteQueriesEXT = null;
                sBeginQueryEXT = null;
                sEndQueryEXT = null;
                sGetQueryObjectuivEXT = null;
                sGetQueryObjectui64vEXT = null;
            }

            try {
                final Class<?> c = android.opengl.GLES30.class;
                sGetQueryObjectui64vCore = c.getMethod("glGetQueryObjectui64v", int.class, int.class, long[].class, int.class);
            } catch (Throwable ignored) {
                sGetQueryObjectui64vCore = null;
            }
        }

        private static boolean hasExt(String ext) {
            // ES2-style extension string
            try {
                final String exts = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_EXTENSIONS);
                if (exts != null && exts.contains(ext)) return true;
            } catch (Throwable ignored) {}

            // ES3-style extensions enumeration
            try {
                final int[] n = new int[1];
                android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_NUM_EXTENSIONS, n, 0);
                for (int i = 0; i < n[0]; i++) {
                    final String e = android.opengl.GLES30.glGetStringi(android.opengl.GLES30.GL_EXTENSIONS, i);
                    if (ext.equals(e)) return true;
                }
            } catch (Throwable ignored) {}

            return false;
        }

        private void initIfNeeded() {
            if (initTried) return;
            initTried = true;

            if (!hasExt("GL_EXT_disjoint_timer_query")) {
                supported = false;
                return;
            }

            // Load EXT entry points once (used for both fallback allocation and ops selection)
            loadExtIfNeeded();

            // Reset state before attempting init
            supported = false;
            useExt = false;
            for (int i = 0; i < RING; i++) {
                queries[i] = 0;
                pending[i] = false;
            }
            write = 0;
            read = 0;
            localActive = false;
            globalActive = false;

            // 1) Allocate query names using core if possible
            try {
                android.opengl.GLES30.glGenQueries(RING, queries, 0);
                supported = (queries[0] != 0);
            } catch (Throwable ignored) {
                supported = false;
            }

            // 2) If core allocation failed or returned 0, fallback to EXT allocation
            if (!supported && sGenQueriesEXT != null) {
                try {
                    sGenQueriesEXT.invoke(null, RING, queries, 0);
                    supported = (queries[0] != 0);
                } catch (Throwable ignored) {
                    supported = false;
                }
            }

            // 3) Prefer EXT query ops if available (more compatible on some stacks),
            //    even if allocation happened via core.
            if (supported) {
                useExt = (sBeginQueryEXT != null && sEndQueryEXT != null && sGetQueryObjectuivEXT != null);
            } else {
                // Keep everything cleared (already reset above)
                for (int i = 0; i < RING; i++) {
                    queries[i] = 0;
                    pending[i] = false;
                }
                write = 0;
                read = 0;
                localActive = false;
                globalActive = false;
            }
        }

        void begin() {
            initIfNeeded();
            if (!supported) return;
            if (localActive || globalActive) return;

            // Avoid overflow: if next slot still pending, try to collect one result; otherwise skip.
            if (pending[write]) {
                pollOneInternal();
                if (pending[write]) return;
            }

            final int q = queries[write];
            if (q == 0) return;

            try {
                if (useExt) {
                    loadExtIfNeeded();
                    if (sBeginQueryEXT != null) {
                        sBeginQueryEXT.invoke(null, GL_TIME_ELAPSED_EXT, q);
                    } else {
                        android.opengl.GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, q);
                    }
                } else {
                    android.opengl.GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, q);
                }

                localActive = true;
                globalActive = true;
            } catch (Throwable ignored) {
                supported = false;
                localActive = false;
                globalActive = false;
            }
        }

        void end() {
            if (!supported) return;
            if (!localActive) return;

            try {
                if (useExt) {
                    loadExtIfNeeded();
                    if (sEndQueryEXT != null) {
                        sEndQueryEXT.invoke(null, GL_TIME_ELAPSED_EXT);
                    } else {
                        android.opengl.GLES30.glEndQuery(GL_TIME_ELAPSED_EXT);
                    }
                } else {
                    android.opengl.GLES30.glEndQuery(GL_TIME_ELAPSED_EXT);
                }
            } catch (Throwable ignored) {
                supported = false;
            } finally {
                pending[write] = true;
                write = (write + 1) % RING;
                localActive = false;
                globalActive = false;
            }
        }

        long pollOne() {
            initIfNeeded();
            if (!supported) return 0L;
            return pollOneInternal();
        }

        boolean consumeDisjointTripped() {
            final boolean v = disjointTripped;
            disjointTripped = false;
            return v;
        }

        private void clearPending() {
            for (int i = 0; i < RING; i++) pending[i] = false;
            write = 0;
            read = 0;
            localActive = false;
            globalActive = false;
        }

        private long pollOneInternal() {
            if (!pending[read]) return 0L;

            // Disjoint invalidates *all* timer query results
            try {
                tmpInt[0] = 0;
                android.opengl.GLES20.glGetIntegerv(GL_GPU_DISJOINT_EXT, tmpInt, 0);
                if (tmpInt[0] != 0) {
                    disjointTripped = true;
                    clearPending();
                    return 0L;
                }
            } catch (Throwable ignored) {}

            final int q = queries[read];
            if (q == 0) {
                pending[read] = false;
                read = (read + 1) % RING;
                return 0L;
            }

            // Non-blocking availability check
            try {
                tmpInt[0] = 0;
                if (useExt) {
                    loadExtIfNeeded();
                    if (sGetQueryObjectuivEXT != null) {
                        sGetQueryObjectuivEXT.invoke(null, q, GL_QUERY_RESULT_AVAILABLE, tmpInt, 0);
                    } else {
                        android.opengl.GLES30.glGetQueryObjectuiv(q, GL_QUERY_RESULT_AVAILABLE, tmpInt, 0);
                    }
                } else {
                    android.opengl.GLES30.glGetQueryObjectuiv(q, GL_QUERY_RESULT_AVAILABLE, tmpInt, 0);
                }

                if (tmpInt[0] == 0) return 0L;
            } catch (Throwable ignored) {
                supported = false;
                return 0L;
            }

            long ns = 0L;

            // Prefer 64-bit query result if available
            try {
                if (useExt) {
                    loadExtIfNeeded();
                    if (sGetQueryObjectui64vEXT != null) {
                        tmpLong[0] = 0L;
                        sGetQueryObjectui64vEXT.invoke(null, q, GL_QUERY_RESULT, tmpLong, 0);
                        ns = tmpLong[0];
                    } else {
                        tmpInt[0] = 0;
                        if (sGetQueryObjectuivEXT != null) {
                            sGetQueryObjectuivEXT.invoke(null, q, GL_QUERY_RESULT, tmpInt, 0);
                        } else {
                            android.opengl.GLES30.glGetQueryObjectuiv(q, GL_QUERY_RESULT, tmpInt, 0);
                        }
                        ns = (tmpInt[0] & 0xFFFFFFFFL);
                    }
                } else if (sGetQueryObjectui64vCore != null) {
                    tmpLong[0] = 0L;
                    sGetQueryObjectui64vCore.invoke(null, q, GL_QUERY_RESULT, tmpLong, 0);
                    ns = tmpLong[0];
                } else {
                    tmpInt[0] = 0;
                    android.opengl.GLES30.glGetQueryObjectuiv(q, GL_QUERY_RESULT, tmpInt, 0);
                    ns = (tmpInt[0] & 0xFFFFFFFFL);
                }
            } catch (Throwable ignored) {
                supported = false;
                return 0L;
            }

            // Consume this slot
            pending[read] = false;
            read = (read + 1) % RING;

            // Avoid negative / nonsense values
            return ns > 0L ? ns : 0L;
        }

        void release() {
            if (!initTried) return;

        // Delete queries if we ever allocated names (even if supported flipped false later)
            final boolean haveAny = (queries[0] != 0);
            if (haveAny) {
                try {
                    if (useExt) {
                        loadExtIfNeeded();
                        if (sDeleteQueriesEXT != null) {
                            sDeleteQueriesEXT.invoke(null, RING, queries, 0);
                        } else {
                            android.opengl.GLES30.glDeleteQueries(RING, queries, 0);
                        }
                    } else {
                        android.opengl.GLES30.glDeleteQueries(RING, queries, 0);
                    }
                } catch (Throwable ignored) {}
            }


            for (int i = 0; i < RING; i++) {
                queries[i] = 0;
                pending[i] = false;
            }

            clearPending();
            supported = false;
            initTried = false;
            useExt = false;
            disjointTripped = false;
        }
    }


    private final FsrTelemetry __fsr = new FsrTelemetry();
    private final GpuTimeQueryRing easuGpuTimer = new GpuTimeQueryRing();
    private final GpuTimeQueryRing rcasGpuTimer = new GpuTimeQueryRing();
    private final GpuTimeQueryRing blitGpuTimer = new GpuTimeQueryRing();
    private volatile String __fsrOverlay = "";
    private final Surface windowSurfaceInput;
    private final int srcW, srcH;
    private final PreferenceConfiguration prefs;
    // Optional context (application context) used for Display.getAppVsyncOffsetNanos() caching
    private volatile android.content.Context appContext = null;

    // EGL
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglWindowSurface = EGL14.EGL_NO_SURFACE;

    // Decoder input
    private int oesTexId = 0;
    private SurfaceTexture decoderSurfaceTex;
    private Surface decoderInputSurface;

    // FBO for intermediate upscaled image
    private int fbo = 0;
    private int upscaledTex = 0;
    private int fbW = 0, fbH = 0;
    private int fboW = 0;
    private int fboH = 0;
    // FBO for staging decoder OES -> 2D (source)
    private int srcFbo = 0;
    private int srcRgbTex = 0;
    private int srcFboW = 0;
    private int srcFboH = 0;

    // Programs

    private int progVs = 0, progBlit = 0, progOesTo2D = 0, progEasuPerf = 0, progEasuBalanced = 0, progEasuQuality = 0,
            progEasuPerf2D = 0, progEasuBalanced2D = 0, progEasuQuality2D = 0, progRcas = 0, progRcasFast = 0;

    // Uniform locations
    private int blit_uTex = -1, blit_uTexMat = -1;
    private int oes2d_uTex = -1;
    private int easuPerf_uTex = -1, easuPerf_uInvSrcSize = -1, easuPerf_uTexMat = -1;
    private int easuQ_uTex = -1, easuQ_uInvSrcSize = -1, easuQ_uTexMat = -1;
    private int easuBal_uTex = -1, easuBal_uInvSrcSize = -1, easuBal_uTexMat = -1;
    private int easuPerf2D_uTex = -1, easuPerf2D_uInvSrcSize = -1, easuPerf2D_uTexMat = -1;
    private int easuBal2D_uTex = -1, easuBal2D_uInvSrcSize = -1, easuBal2D_uTexMat = -1;
    private int easuQ2D_uTex = -1, easuQ2D_uInvSrcSize = -1, easuQ2D_uTexMat = -1;


    private int rcas_uTex = -1, rcas_uInvDst = -1, rcas_uSharp = -1, rcas_uLuma = -1;
    private int rcasFast_uTex = -1, rcasFast_uInvDst = -1, rcasFast_uSharp = -1, rcasFast_uLuma = -1;
    private int progRcasOes = 0, rcasOes_uTex = -1, rcasOes_uInvDst = -1, rcasOes_uSharp = -1, rcasOes_uTexMat = -1, rcasOes_uLuma = -1;
    private int progRcasOesHdr = 0, rcasOesHdr_uTex = -1, rcasOesHdr_uInvDst = -1, rcasOesHdr_uSharp = -1, rcasOesHdr_uTexMat = -1, rcasOesHdr_uLuma = -1;

    // Quad buffers (no VAO)
    private int vboPos = 0, vboUv = 0;
    private FloatBuffer quadPos, quadUv;

    // SurfaceTexture transform
    private final float[] texMatrix = new float[16];

    // Presentation size hint (display-sized buffer), if known
    private volatile int hintOutW = 0, hintOutH = 0;

    // Performance optimizations
    private final int[] tmpIntArray = new int[1]; // Reusable int array
    private final int[] tmpViewportArray = new int[4];
    private final int[] invalidateDefaultFbAttachments = new int[]{GLES30.GL_COLOR};
    private final int[] invalidateColorAttachment = new int[]{GLES30.GL_COLOR_ATTACHMENT0};

    // ===== Performance state =====
    private int vao = 0;
    private boolean hasVao = false;
    private boolean gpuCapsDetected = false;
    private boolean isMaliGpu = false;
    private long lastSizeQueryNs = 0L;
    private static final long SIZE_QUERY_MIN_NS = 400_000_000L;  // 0.4s baseline
    private static final long SIZE_QUERY_MAX_NS = 1_200_000_000L; // 1.2s max backoff

    private long sizeQueryNs = SIZE_QUERY_MIN_NS;
    private int stableSizeQueryCount = 0;
    private boolean lastSizeQueryOk = false;
    private int swapFailStreak = 0;

    // Threading
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread renderThread;
    private volatile boolean useChoreoVsync = false;
    // Backend restarts (switching Choreographer vs manual loop) must never block the caller thread.
    private final AtomicBoolean restartInProgress = new AtomicBoolean(false);

    private void requestBackendRestartAsync() {
        if (!restartInProgress.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                final Thread threadToStop = renderThread;
                stop();

                if (threadToStop != null && threadToStop.isAlive()) {
                    try {
                        threadToStop.join(STOP_JOIN_TIMEOUT_MS + STOP_JOIN_GRACE_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }

                if (renderThread != null && renderThread.isAlive()) {
                    LimeLog.warning("FSR: backend restart aborted (render thread still alive)");
                    return;
                }

                start();
            } finally {
                restartInProgress.set(false);
            }
        }, "GL-FSR1-Restart").start();
    }

    private volatile Handler renderHandler = null;
    // Cached Linux TID for non-HandlerThread renderer (so we can retune OS priority at runtime).
    private volatile int renderTid = 0;

    private int getEffectivePacing() {
        return (prefs != null) ? prefs.framePacing : PreferenceConfiguration.FRAME_PACING_BALANCED;
    }

    private static int desiredGlOsPriority(int pacing, boolean usingChoreoBackend) {
        // If GL is the vsync gate (Choreographer backend), keep it URGENT_DISPLAY.
        if (usingChoreoBackend) {
            return Process.THREAD_PRIORITY_URGENT_DISPLAY;
        }

        // Low-latency modes: keep the upscaler responsive even with VSync off.
        switch (pacing) {
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
            case PreferenceConfiguration.FRAME_PACING_GPU_RAW:
            case PreferenceConfiguration.FRAME_PACING_WARP:
            case PreferenceConfiguration.FRAME_PACING_WARP2:
                return Process.THREAD_PRIORITY_URGENT_DISPLAY;
            default:
                return Process.THREAD_PRIORITY_DISPLAY;
        }
    }

    private void applyGlThreadPriorityNow() {
        final Thread t = renderThread;
        if (t == null || !t.isAlive()) return;

        final int pacing = getEffectivePacing();
        final int osPrio = desiredGlOsPriority(pacing, useChoreoVsync);

        try {
            if (t instanceof HandlerThread) {
                Process.setThreadPriority(((HandlerThread) t).getThreadId(), osPrio);
            } else if (renderTid != 0) {
                Process.setThreadPriority(renderTid, osPrio);
            }
        } catch (Throwable ignored) { }

        try {
            t.setPriority((osPrio == Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    ? (Thread.NORM_PRIORITY + 3)
                    : (Thread.NORM_PRIORITY + 2));
        } catch (Throwable ignored) { }
    }

    @Keep
    @SuppressWarnings("unused") // called via reflection from MediaCodecDecoderRenderer
    public void applyThreadPriorities() {
        applyGlThreadPriorityNow();
    }

    private final Object frameLock = new Object();
    // SurfaceTexture callback coalescing with bounded "pending frames" counter.
    // This behaves like a tiny queue without introducing extra latency.
    private static final int MAX_PENDING_FRAMES = 8;
    private static final int MAX_DRAIN_UPDATETEXIMAGE = 4;

    // Choreographer backend: limit updateTexImage drains per vsync to stabilize frame time.
    // Use 1 for maximum smoothness, 2 for a better latency/smoothness compromise.
    private static final int CHOREO_MAX_DRAIN_UPDATETEXIMAGE = 2;

    // Choreographer late-latch window (sub-ms) to catch frames arriving just after vsync.
    // Choreographer interval estimation (ns). Used only for adaptive drain budgeting.
    private static final long CHOREO_INTERVAL_MIN_NS = 8_000_000L;        // ~120Hz
    private static final long CHOREO_INTERVAL_MAX_NS = 50_000_000L;       // ~20Hz
    private static final long CHOREO_INTERVAL_DEFAULT_NS = 16_666_666L;   // 60Hz

    private volatile long choreoFrameIntervalNs = CHOREO_INTERVAL_DEFAULT_NS;

    // Adaptive drain budget for Choreographer ticks (1..CHOREO_MAX_DRAIN_UPDATETEXIMAGE).
    private volatile int choreoDrainBudget = 1;
    private static final int CHOREO_LATE_LATCH_NS = 600_000; // 0.6ms (must be < 1_000_000)

    // Last Choreographer frame time (System.nanoTime() timebase), used for eglPresentationTimeANDROID.
    private volatile long lastChoreoFrameTimeNs = 0L;

    // Cached app vsync offset (Display.getAppVsyncOffsetNanos) for phase alignment (matches MediaCodecDecoderRenderer).
    private volatile long cachedAppVsyncOffsetNs = 0L;
    private volatile long lastAppVsyncOffsetQueryNs = 0L;
    private static final long APP_VSYNC_OFFSET_QUERY_INTERVAL_NS = 2_000_000_000L; // 2s

    // Choreographer pacing state (matches MediaCodecDecoderRenderer Balanced ratio logic).
    private volatile float lastPacingStreamFps = -1f;
    private volatile float lastPacingDisplayHz = -1f;
    private volatile double vsyncsPerFrame = 1.0;
    private volatile double vsyncAccumulator = 0.0;

    private int pendingFrames = 0;

    // stop()/release() safety: avoid indefinite join() if the render thread gets stuck in driver/EGL
    private static final long STOP_JOIN_TIMEOUT_MS = 1200L;
    private static final long STOP_JOIN_GRACE_MS = 600L;


    // State cache
    private int curVpW = -1, curVpH = -1;
    private boolean twoDNearest = false, oesNearest = false;
    private int lastTex2DFilterTexId = -1;  // per-texture filter cache slot 0 (unit 0)
    private boolean twoDNearest2 = false;
    private int lastTex2DFilterTexId2 = -1; // per-texture filter cache slot 1 (unit 0)

    // Quad bind cache (avoid rebinding attribs/VAO every draw)
    private boolean quadBound = false;
    private int lastBoundVao = 0;

    // EGL extension cache (avoid try/catch per-frame)
    private boolean hasPresentationTimeExt = false;

    // Force swapInterval=0 to prevent eglSwapBuffers() from blocking on vsync.
    private boolean swapIntervalZeroApplied = false;
    private int swapIntervalZeroFailCount = 0;


    // Track if render surface size changed since last swap
    private boolean sizeChangedSinceLastSwap = true;

    // True after the first successful updateTexImage()
    private boolean hasEverUpdatedTex = false;

    // ===== Hot-path caches (reduce per-frame uniform churn / String.format) =====
    private int lastRcasInvDstW = -1, lastRcasInvDstH = -1;
    private float lastRcasSharp = -1f;

    private int lastRcasFastInvDstW = -1, lastRcasFastInvDstH = -1;
    private float lastRcasFastSharp = -1f;


    private int lastRcasOesInvDstW = -1, lastRcasOesInvDstH = -1;
    private float lastRcasOesSharp = -1f;

    private int lastRcasOesHdrInvDstW = -1, lastRcasOesHdrInvDstH = -1;
    private float lastRcasOesHdrSharp = -1f;

    private static final long FSR_OVERLAY_UPDATE_NS = 100_000_000L; // 100ms
    // FSR notes: avoid per-frame allocations (reuse builder + update throttling)
    private static final int FSR_NOTES_EASU_RCAS = 0;
    private static final int FSR_NOTES_RCAS_ONLY = 1;

    private static final int FSR_REASON_NEAR_NATIVE = 0;
    private static final int FSR_REASON_DST_EQ_SRC = 1;
    private static final int FSR_REASON_EASU_DISABLED = 2;
    private static final int FSR_REASON_MODE_NOT_EASU_RCAS = 3;

    private static final int SIMPLE_NOTE_GPU_PATH = 0;
    private static final int SIMPLE_NOTE_HDR_OK = 1;
    private static final int SIMPLE_NOTE_HDR_FALLBACK = 2;
    private static final int SIMPLE_NOTE_BYPASS_MODE_NONE = 3;
    private static final int SIMPLE_NOTE_BYPASS_DISABLED = 4;

    private final StringBuilder fsrNotesSb = new StringBuilder(96);
    private final StringBuilder simpleFsrNotesSb = new StringBuilder(96);
    private long lastFsrNotesUpdateNs = 0L;
    private int lastFsrNotesKind = -1;
    private boolean lastFsrNotesOk = false;
    private int lastFsrNotesPreset = -1;
    private int lastFsrNotesUpX100 = Integer.MIN_VALUE;
    private boolean lastFsrNotesNearNative = false;
    private int lastFsrNotesThrX100 = Integer.MIN_VALUE;
    private int lastFsrNotesReasonId = -1;

    private int lastSimpleFsrNoteKind = -1;
    private boolean lastSimpleFsrAllowSharpen = false;
    private int lastSimpleFsrWinW = -1;
    private int lastSimpleFsrWinH = -1;
    private int lastSimpleFsrHintW = -1;
    private int lastSimpleFsrHintH = -1;

    private long lastFsrOverlayUpdateNs = 0L;
    private String lastFsrOverlayMode = "";
    private float lastFsrOverlaySharp = -1f;

    // GL error state (avoid glGetError() per-frame)
    private boolean glErrorDirty = true;

    private void markGlErrorDirty() {
        glErrorDirty = true;
    }

    // Throttle overlay formatting (String.format) to avoid per-frame allocations when debug is ON
    private void maybeUpdateFsrOverlay() {
        if (!__fsr.enabled) return;

        final long now = System.nanoTime();
        final boolean modeSame = (__fsr.mode != null && __fsr.mode.equals(lastFsrOverlayMode));
        final boolean sharpSame = (Math.abs(__fsr.sharp - lastFsrOverlaySharp) < 0.0005f);

        if (modeSame && sharpSame && (now - lastFsrOverlayUpdateNs) < FSR_OVERLAY_UPDATE_NS) {
            return;
        }

        lastFsrOverlayUpdateNs = now;
        lastFsrOverlayMode = (__fsr.mode != null ? __fsr.mode : "");
        lastFsrOverlaySharp = __fsr.sharp;

        __fsrOverlay = __fsr.overlayLine();
    }

    private void maybeUpdateFsrNotesEasuRcas(boolean ok, int preset, float upRatio) {
        final long now = System.nanoTime();
        final int upX100 = toScaled100(upRatio);

        final boolean same =
                (lastFsrNotesKind == FSR_NOTES_EASU_RCAS) &&
                        (ok == lastFsrNotesOk) &&
                        (preset == lastFsrNotesPreset) &&
                        (upX100 == lastFsrNotesUpX100);

        if (same && (now - lastFsrNotesUpdateNs) < FSR_OVERLAY_UPDATE_NS) {
            return;
        }

        lastFsrNotesUpdateNs = now;
        lastFsrNotesKind = FSR_NOTES_EASU_RCAS;
        lastFsrNotesOk = ok;
        lastFsrNotesPreset = preset;
        lastFsrNotesUpX100 = upX100;

        fsrNotesSb.setLength(0);
        fsrNotesSb.append(ok ? "reason=easu_rcas" : "fallback:easu_rcas_failed");
        fsrNotesSb.append(" easu=").append((preset == 0) ? "P" : (preset == 1 ? "B" : "Q"));
        fsrNotesSb.append(" | preset=").append(preset);
        fsrNotesSb.append(" up=");
        appendFixed2Scaled100(fsrNotesSb, upX100);

        __fsr.notes = fsrNotesSb.toString();
    }

    private void maybeUpdateFsrNotesRcasOnly(boolean ok, int preset, boolean nearNative, float nearThr, int reasonId) {
        final long now = System.nanoTime();
        final int thrX100 = toScaled100(nearThr);

        final boolean same =
                (lastFsrNotesKind == FSR_NOTES_RCAS_ONLY) &&
                        (ok == lastFsrNotesOk) &&
                        (preset == lastFsrNotesPreset) &&
                        (nearNative == lastFsrNotesNearNative) &&
                        (thrX100 == lastFsrNotesThrX100) &&
                        (reasonId == lastFsrNotesReasonId);

        if (same && (now - lastFsrNotesUpdateNs) < FSR_OVERLAY_UPDATE_NS) {
            return;
        }

        lastFsrNotesUpdateNs = now;
        lastFsrNotesKind = FSR_NOTES_RCAS_ONLY;
        lastFsrNotesOk = ok;
        lastFsrNotesPreset = preset;
        lastFsrNotesNearNative = nearNative;
        lastFsrNotesThrX100 = thrX100;
        lastFsrNotesReasonId = reasonId;

        fsrNotesSb.setLength(0);

        if (ok) {
            switch (reasonId) {
                case FSR_REASON_NEAR_NATIVE: fsrNotesSb.append("reason=nearNative"); break;
                case FSR_REASON_DST_EQ_SRC: fsrNotesSb.append("reason=dstEqSrc"); break;
                case FSR_REASON_EASU_DISABLED: fsrNotesSb.append("reason=easu_disabled"); break;
                default: fsrNotesSb.append("reason=mode!=easu_rcas"); break;
            }
        } else {
            fsrNotesSb.append("fallback:rcas_only_failed");
        }

        fsrNotesSb.append(" | preset=").append(preset);
        fsrNotesSb.append(" | nearNative=").append(nearNative);
        fsrNotesSb.append(" thr=");
        appendFixed2Scaled100(fsrNotesSb, thrX100);

        __fsr.notes = fsrNotesSb.toString();
    }

    private void maybeUpdateSimpleFsrNote(int kind, boolean allowSharpen, int winW, int winH, int hintW, int hintH) {
        final boolean same =
                (lastSimpleFsrNoteKind == kind) &&
                        (lastSimpleFsrAllowSharpen == allowSharpen) &&
                        (lastSimpleFsrWinW == winW) &&
                        (lastSimpleFsrWinH == winH) &&
                        (lastSimpleFsrHintW == hintW) &&
                        (lastSimpleFsrHintH == hintH);

        if (same) {
            return;
        }

        lastSimpleFsrNoteKind = kind;
        lastSimpleFsrAllowSharpen = allowSharpen;
        lastSimpleFsrWinW = winW;
        lastSimpleFsrWinH = winH;
        lastSimpleFsrHintW = hintW;
        lastSimpleFsrHintH = hintH;

        simpleFsrNotesSb.setLength(0);
        switch (kind) {
            case SIMPLE_NOTE_GPU_PATH:
                simpleFsrNotesSb.append("reason=gpuPathMode");
                break;
            case SIMPLE_NOTE_HDR_OK:
                simpleFsrNotesSb.append("hdr=1 | luma-only");
                break;
            case SIMPLE_NOTE_HDR_FALLBACK:
                simpleFsrNotesSb.append("hdr=1 | reason=")
                        .append(allowSharpen ? "rcas_unavailable" : "disabled_or_mode_none");
                break;
            case SIMPLE_NOTE_BYPASS_MODE_NONE:
            case SIMPLE_NOTE_BYPASS_DISABLED:
                simpleFsrNotesSb.append("reason=")
                        .append(kind == SIMPLE_NOTE_BYPASS_MODE_NONE ? "bypass:mode_none" : "bypass:upscaleDisabled")
                        .append(" | win=")
                        .append(winW).append('x').append(winH)
                        .append(" hint=")
                        .append(hintW).append('x').append(hintH);
                break;
            default:
                simpleFsrNotesSb.setLength(0);
                break;
        }

        __fsr.notes = simpleFsrNotesSb.toString();
    }

    private void pollFsrGpuTimers() {
        if (!__fsr.enabled) return;

        final long easuNs = easuGpuTimer.pollOne();
        if (easuNs > 0L) {
            __fsr.pushGpuEasu(easuNs);
        }

        final long rcasNs = rcasGpuTimer.pollOne();
        if (rcasNs > 0L) {
            __fsr.pushGpuRcas(rcasNs);
        }

        final long blitNs = blitGpuTimer.pollOne();
        if (blitNs > 0L) {
            __fsr.pushGpuBlit(blitNs);
        }

        if (easuGpuTimer.consumeDisjointTripped() || rcasGpuTimer.consumeDisjointTripped() || blitGpuTimer.consumeDisjointTripped()) {
            __fsr.disjointEvents++;
        }
    }

    // GL binding caches (reduce driver chatter)
    private int activeTexUnit = -1;   // 0 == GL_TEXTURE0
    private int lastTex2D = -1;       // last GL_TEXTURE_2D bound to unit 0
    private int lastFbo = -1;         // last GL_FRAMEBUFFER bound
    // TexMatrix upload cache (avoid glUniformMatrix4fv when matrix unchanged)
    private long texMatrixSerial = 0L;
    private long lastBlitTexMatSerial = -1L;
    private long lastEasuPerfTexMatSerial = -1L;

    private long lastEasuQualityTexMatSerial = -1L;
    private long lastEasuBalTexMatSerial = -1L;
    private long lastRcasOesTexMatSerial = -1L;
    private long lastRcasOesHdrTexMatSerial = -1L;

    public GlUpscaleRenderer(android.content.Context context, Surface windowSurface, int srcW, int srcH, PreferenceConfiguration prefs) {
        this(windowSurface, srcW, srcH, prefs);
        this.appContext = (context != null) ? context.getApplicationContext() : null;
        try { setPresentationSizeHintFromContext(context); } catch (Throwable ignored) {}
    }


    public GlUpscaleRenderer(Surface windowSurface, int srcW, int srcH, PreferenceConfiguration prefs) {
        this.windowSurfaceInput = windowSurface;
        this.srcW = Math.max(1, srcW);
        this.srcH = Math.max(1, srcH);
        this.prefs = prefs;
        this.appContext = null;
    }

    /**
     * Update HDR + Direct Present mode.
     *
     * @param hdrActive      true if the current stream is HDR.
     * @param directPresent  true only when GPU path (prefs.gpuPathMode) is enabled.
     */
    public void setHdrMode(boolean hdrActive, boolean directPresent) {
        if (this.hdrActive == hdrActive && this.hdrDirectPresent == directPresent) {
            return;
        }

        this.hdrActive = hdrActive;
        this.hdrDirectPresent = directPresent;

        if (!hdrActive) {
            hdrNoGpuPathWarned = false;
        }

        // Ensure we render at least once with the new mode
        sizeChangedSinceLastSwap = true;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }


    @Keep
    public Surface createDecoderInputSurface() {
        if (!isGlReady()) {
            synchronized (this) {
                initEglAndGl();
                if (!isGlReady()) return null;
            }
        }
        if (decoderInputSurface != null) return decoderInputSurface;

        if (!ensureEglCurrent()) return null;

        try {
            GLES20.glGenTextures(1, tmpIntArray, 0);
            oesTexId = tmpIntArray[0];

            if (lastTexture != oesTexId) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
                lastTexture = oesTexId;
            }

            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            decoderSurfaceTex = new SurfaceTexture(oesTexId);
            attachedEglContextHandle = safeEglContextHandle(EGL14.eglGetCurrentContext());

            try {
                decoderSurfaceTex.setDefaultBufferSize(srcW, srcH);
            } catch (Throwable ignored) {}

            decoderSurfaceTex.setOnFrameAvailableListener(this);
            decoderInputSurface = new Surface(decoderSurfaceTex);

            // Reset GL trackers — new SurfaceTexture means new OES texture binding
            lastTexture = -1;
            lastProgram = -1;

            // Keep filter cache coherent with the freshly-created OES texture (created as LINEAR)
            oesNearest = false;

            return decoderInputSurface;
        } finally {
            // Do not keep the EGL context current on this (non-render) thread.
            releaseEglCurrent();
        }
    }


    public void start() {
        // Never spawn a second renderer thread: EGLContext cannot be current on two threads.
        final Thread existing = renderThread;
        if (existing != null && existing.isAlive()) {
            // Best-effort wait a bit in case stop() is still unwinding.
            if (Thread.currentThread() != existing) {
                try { existing.join(120L); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            if (existing.isAlive()) {
                LimeLog.warning("FSR: start() skipped because render thread is still alive");
                return;
            } else {
                // Thread is gone: clear stale refs
                renderThread = null;
                renderHandler = null;
                renderTid = 0;
                useChoreoVsync = false;
            }
        }

        if (!isGlReady()) {
            synchronized (this) {
                initEglAndGl();
            }
        }
        if (!isGlReady()) return;

        if (running.getAndSet(true)) return;

        final boolean wantVsync = (prefs != null && prefs.enableVsync);

        // Balanced pacing already runs a Choreographer loop in MediaCodecDecoderRenderer.
        // Avoid a second independent Choreographer loop here.
        final boolean balancedPacing =
                (prefs != null && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED);

        useChoreoVsync = (wantVsync && !balancedPacing);

        if (useChoreoVsync) {
            final int osPrio = desiredGlOsPriority(getEffectivePacing(), true);
            final HandlerThread ht = new HandlerThread(
                    "GL-FSR1-Renderer",
                    osPrio);
            renderThread = ht;
            ht.start();

            renderHandler = new Handler(ht.getLooper());
            renderHandler.post(() -> {
                renderTid = Process.myTid();
                applyGlThreadPriorityNow();
                try {
                    // Reset Choreographer timing + pacing state (match MediaCodecDecoderRenderer behavior)
                    lastChoreoFrameTimeNs = 0L;
                    choreoFrameIntervalNs = CHOREO_INTERVAL_DEFAULT_NS;
                    choreoDrainBudget = 1;
                    lastPacingStreamFps = -1f;
                    lastPacingDisplayHz = -1f;
                    vsyncsPerFrame = 1.0;
                    vsyncAccumulator = 0.0;

                    // Prime cached app vsync offset (optional; match MediaCodecDecoderRenderer)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                        try {
                            final android.content.Context ctx = appContext;
                            android.view.WindowManager wm = null;
                            if (ctx != null) {
                                if (android.os.Build.VERSION.SDK_INT >= 23) {
                                    wm = ctx.getSystemService(android.view.WindowManager.class);
                                } else {
                                    wm = (android.view.WindowManager) ctx.getSystemService(android.content.Context.WINDOW_SERVICE);
                                }
                            }
                            if (wm != null && wm.getDefaultDisplay() != null) {
                                cachedAppVsyncOffsetNs = wm.getDefaultDisplay().getAppVsyncOffsetNanos();
                            } else {
                                cachedAppVsyncOffsetNs = 0L;
                            }
                        } catch (Throwable ignored) {
                            cachedAppVsyncOffsetNs = 0L;
                        }
                        lastAppVsyncOffsetQueryNs = System.nanoTime();
                    }

                    Choreographer.getInstance().postFrameCallback(frameCallback);
                } catch (Throwable t) {
                    LimeLog.warning("Choreographer init failed: " + t);
                }
            });
            return;

        }

        final int osPrio = desiredGlOsPriority(getEffectivePacing(), false);

        renderThread = new Thread(this::renderLoop, "GL-FSR1-Renderer");
        try {
            renderThread.setPriority((osPrio == Process.THREAD_PRIORITY_URGENT_DISPLAY)
                    ? (Thread.NORM_PRIORITY + 3)
                    : (Thread.NORM_PRIORITY + 2));
        } catch (Throwable ignored) {}

        renderThread.start();
    }

    public void stop() {
        running.set(false);

        // Wake any wait in renderFrame() to accelerate shutdown.
        synchronized (frameLock) {
            frameLock.notifyAll();
        }

        if (useChoreoVsync) {
            // Stop from the render thread (no join).
            if (Thread.currentThread() == renderThread) {
                try { Choreographer.getInstance().removeFrameCallback(frameCallback); } catch (Throwable ignored) {}
                try {
                    final android.os.Looper looper = android.os.Looper.myLooper();
                    if (looper != null) looper.quitSafely();
                } catch (Throwable ignored) {}
                renderTid = 0;
                return;
            }

            final Handler h = renderHandler;
            if (h != null) {
                try {
                    h.post(() -> {
                        try { Choreographer.getInstance().removeFrameCallback(frameCallback); } catch (Throwable ignored) {}
                        try {
                            final android.os.Looper looper = android.os.Looper.myLooper();
                            if (looper != null) looper.quitSafely();
                        } catch (Throwable ignored) {}
                    });
                } catch (Throwable ignored) {}
            }

            if (renderThread instanceof HandlerThread) {
                try { ((HandlerThread) renderThread).quitSafely(); } catch (Throwable ignored) {}
            }
        }

        final Thread t = renderThread;
        if (t != null && Thread.currentThread() != t) {
            boolean stopped = false;

            try {
                t.join(STOP_JOIN_TIMEOUT_MS);
                stopped = !t.isAlive();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            if (!stopped) {
                // Best-effort: attempt to break any waits and let the thread unwind.
                try { t.interrupt(); } catch (Throwable ignored) {}

                if (t instanceof HandlerThread) {
                    try { ((HandlerThread) t).quit(); } catch (Throwable ignored) {}
                }

                try {
                    t.join(STOP_JOIN_GRACE_MS);
                    stopped = !t.isAlive();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }

            if (stopped) {
                renderTid = 0;
                renderThread = null;
                renderHandler = null;
                useChoreoVsync = false;
            } else {
                LimeLog.warning("FSR: render thread did not stop within timeout; skipping teardown to avoid EGL/GL races");
                // Keep renderThread reference: release() will detect and abort teardown.
            }
        } else {
            // Already stopped or stopping from the render thread.
            renderTid = 0;
            renderHandler = null;
            useChoreoVsync = false;
        }
    }

    public void release() {
        stop();

        final Thread t = renderThread;
        if (t != null && t.isAlive()) {
            LimeLog.warning("FSR: release() aborted because render thread is still alive (avoiding EGL/GL teardown races)");
            return;
        }

        try {
            if (decoderSurfaceTex != null) {
                decoderSurfaceTex.setOnFrameAvailableListener(null);
                decoderSurfaceTex.release();
                decoderSurfaceTex = null;
                attachedEglContextHandle = 0L;
            }
            if (decoderInputSurface != null) {
                decoderInputSurface.release();
                decoderInputSurface = null;
            }
        } catch (Throwable ignored) {}

        destroyGl();
        destroyEgl();
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameLock) {
            final boolean wasEmpty = (pendingFrames == 0);
            if (pendingFrames < MAX_PENDING_FRAMES) {
                pendingFrames++;
            }
            if (wasEmpty) {
                frameLock.notify();
            }
        }
    }

    // ====== Main render loop ======
    private void renderLoop() {
        renderTid = Process.myTid();
        applyGlThreadPriorityNow();

        try {
            while (running.get()) {
                try {
                    renderFrame(true);
                } catch (Throwable t) {
                    LimeLog.warning("FSR: renderFrame crashed: " + t);
                    markGlErrorDirty();
                    running.set(false);
                }
            }
        } finally {
            // Explicitly release EGL binding on this thread to avoid EGL_BAD_ACCESS on next session.
            try { releaseEglCurrent(); } catch (Throwable ignored) {}
            renderTid = 0;
        }
    }

    private void renderFrame(final boolean allowWait) {

        final boolean fsrEnabled = __fsr.enabled;
        // quick skip if EGL lost
        if (!isGlReady()) {
            synchronized (this) { initEglAndGl(); }
            if (!isGlReady()) return;
        }
// Ensure EGL context + surface are current (fail-fast)
// If eglMakeCurrent fails, do NOT issue any GL calls (prevents SurfaceTexture 0x502 loops).
        final long __curCtxH = safeEglContextHandle(EGL14.eglGetCurrentContext());
        final long __curDrawH = safeEglSurfaceHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
        final long __curReadH = safeEglSurfaceHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_READ));

        final boolean needMakeCurrent =
                (__curCtxH == 0L || __curCtxH != eglContextHandle) ||
                        (__curDrawH != eglWindowSurfaceHandle) ||
                        (__curReadH != eglWindowSurfaceHandle);

        if (needMakeCurrent) {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext)) {
                final int err = EGL14.eglGetError();
                LimeLog.warning("FSR: eglMakeCurrent failed err=0x" + Integer.toHexString(err));
                return;
            }

            // After a new current surface/context bind, force non-blocking swap.
            swapIntervalZeroApplied = false;
            ensureSwapIntervalZero();
            // Cached GL state is invalid after a successful rebind
            lastProgram = -1;
            lastTexture = -1;
            curVpW = -1;
            curVpH = -1;
            twoDNearest = false;
            oesNearest = false;
            lastRcasInvDstW = lastRcasInvDstH = -1;
            lastRcasSharp = -1f;
            lastRcasFastInvDstW = lastRcasFastInvDstH = -1;
            lastRcasFastSharp = -1f;
            lastRcasOesInvDstW = lastRcasOesInvDstH = -1;
            lastRcasOesSharp = -1f;
            lastRcasOesHdrInvDstW = lastRcasOesHdrInvDstH = -1;
            lastRcasOesHdrSharp = -1f;
            quadBound = false;
            lastBoundVao = 0;
            activeTexUnit = -1;
            lastTex2D = -1;
            lastFbo = -1;
            lastBlitTexMatSerial = -1L;
            lastEasuPerfTexMatSerial = -1L;
            lastEasuQualityTexMatSerial = -1L;
            lastRcasOesTexMatSerial = -1L;
            lastRcasOesHdrTexMatSerial = -1L;
            lastEasuBalTexMatSerial = -1L;
            markGlErrorDirty();
        }

        int drainCount;
        synchronized (frameLock) {
            if (allowWait && pendingFrames == 0) {
                try {
                    if (useChoreoVsync) {
                        // Late-latch for Choreographer: wait sub-ms to catch frames arriving just after vsync.
                        frameLock.wait(0L, CHOREO_LATE_LATCH_NS);
                    } else {
                        // Non-choreo loop: coarse wait to avoid busy looping when decoder stalls.
                        frameLock.wait(33);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            final int total = pendingFrames;

// Drain budget for this render tick. Avoid long updateTexImage loops under Choreographer.
// Use a conservative adaptive budget derived from measured Choreographer interval.
            final int maxDrainNow;
            if (useChoreoVsync) {
                // If backlog is building, drain more aggressively to keep latency bounded.
                final int base = Math.min(CHOREO_MAX_DRAIN_UPDATETEXIMAGE, Math.max(1, choreoDrainBudget));
                maxDrainNow = (total >= 3) ? MAX_DRAIN_UPDATETEXIMAGE : Math.min(MAX_DRAIN_UPDATETEXIMAGE, base);
            } else {
                maxDrainNow = MAX_DRAIN_UPDATETEXIMAGE;
            }

            drainCount = Math.min(total, maxDrainNow);

// IMPORTANT: keep remainder so we don't "lose" already-signaled frames when draining is capped.
            pendingFrames = total - drainCount;

        }


        final boolean newFrame = (drainCount > 0);

        boolean didUpdateTex = false;

        try {
            if (decoderSurfaceTex != null && newFrame) {
                if (!ensureSurfaceTextureAttached()) {
                    return; // do not call updateTexImage() when detached
                }

                if (glErrorDirty) {
                    clearGlErrors();
                    glErrorDirty = false;
                }

                // Drain a few pending frames to avoid SurfaceTexture backlog (latest-ish behavior).
                final int loops = Math.min(drainCount, MAX_DRAIN_UPDATETEXIMAGE);
                for (int i = 0; i < loops; i++) {
                    decoderSurfaceTex.updateTexImage();
                }

                decoderSurfaceTex.getTransformMatrix(texMatrix);
                texMatrixSerial++;
                hasEverUpdatedTex = true;
                didUpdateTex = true;
            }
        } catch (Throwable t) {
            LimeLog.warning("updateTexImage failed: " + t);
            markGlErrorDirty();
            return;
        }


        final long nowNs = System.nanoTime();

// Query size immediately on startup, after a forced redraw, or after swap failures.
// Otherwise, back off when stable to reduce eglQuerySurface overhead.
        final boolean forceSizeQuery =
                (fbW <= 0 || fbH <= 0) ||
                        sizeChangedSinceLastSwap ||
                        (swapFailStreak > 0);

        if (forceSizeQuery || (nowNs - lastSizeQueryNs) >= sizeQueryNs) {
            final int prevW = fbW;
            final int prevH = fbH;

            refreshWindowSize(); // sets lastSizeQueryOk
            lastSizeQueryNs = nowNs;

            final boolean changed = (fbW != prevW || fbH != prevH);

            if (!lastSizeQueryOk) {
                sizeQueryNs = SIZE_QUERY_MIN_NS;
                stableSizeQueryCount = 0;
            } else if (changed) {
                sizeChangedSinceLastSwap = true;
                sizeQueryNs = SIZE_QUERY_MIN_NS;
                stableSizeQueryCount = 0;
            } else if (!forceSizeQuery) {
                stableSizeQueryCount++;
                if (stableSizeQueryCount >= 3) {
                    stableSizeQueryCount = 0;
                    sizeQueryNs = Math.min(SIZE_QUERY_MAX_NS, sizeQueryNs * 2L);
                }
            } else {
                // Forced query but stable; keep cadence conservative.
                stableSizeQueryCount = 0;
                sizeQueryNs = Math.max(sizeQueryNs, SIZE_QUERY_MIN_NS);
            }
        }

        if (fbW <= 0 || fbH <= 0) return;

        // Avoid drawing undefined content before the first decoded frame
        if (!hasEverUpdatedTex) {
            return;
        }

        // Avoid re-rendering when no new frame arrived and no forced redraw is needed
        if (!newFrame && !sizeChangedSinceLastSwap) {
            return;
        }

        // Fullscreen draw overwrites all pixels; in fast-bypass modes discard old backbuffer instead of glClear()
        ensureViewport(fbW, fbH);

        final boolean gpuPath = (prefs != null && prefs.gpuPathMode);
        final boolean fastBypassNow = computeFastBypassStatic(prefs);

        if (gpuPath || fastBypassNow) {
            // We fully overwrite the default framebuffer; discard previous contents to avoid LOAD.
            bindFramebufferCached(0);
            invalidateDefaultFramebufferColor();
        } else {
            // Explicit clear helps tile-based GPUs avoid costly backbuffer LOADs.
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        }

        if (gpuPath) {
            if (fsrEnabled) {
                __fsr.mode = "BYPASS";
                __fsr.srcW = srcW;
                __fsr.srcH = srcH;
                __fsr.dstW = fbW;
                __fsr.dstH = fbH;
                __fsr.sharp = 0f;
                __fsr.sampling = "gpuPath";
                maybeUpdateSimpleFsrNote(SIMPLE_NOTE_GPU_PATH, false, 0, 0, 0, 0);
                maybeUpdateFsrOverlay();
            }

            // Safety: ensure we render to default framebuffer
            bindFramebufferCached(0);

            drawOesToScreen(fbW, fbH, srcW, srcH);
            if (swapAndContinue()) {
                sizeChangedSinceLastSwap = false;
            }
            return;
        }


        final boolean upscaleEnabled = (prefs != null && prefs.videoUpscaleEnable);
        final String mode = (prefs != null ? prefs.videoUpscaleMode : "rcas");
        final boolean modeNone = "none".equals(mode);
        final boolean modeRcasOnly = "rcas".equals(mode);
        final boolean modeEasuRcas = "easu_rcas".equals(mode);
        final float sharpUser = (prefs != null ? clamp01(prefs.videoUpscaleSharpness / 100f) : 0.35f);

        // Ultra-thin path: when fastBypassNow is true, we always just blit OES -> screen.
        if (fastBypassNow && didUpdateTex && !sizeChangedSinceLastSwap && oesTexId != 0) {
            bindFramebufferCached(0);
            drawOesToScreen(fbW, fbH, srcW, srcH);
            if (swapAndContinue()) {
                sizeChangedSinceLastSwap = false;
            }
            return;
        }

        // Decide target size for *policy/telemetry*: prefer display hint if provided
        final int dstTargetW = (hintOutW > 0 ? hintOutW : fbW);
        final int dstTargetH = (hintOutH > 0 ? hintOutH : fbH);
        final float scaleX = (float) dstTargetW / (float) srcW;
        final float scaleY = (float) dstTargetH / (float) srcH;
        final float nearThr = 0.05f;
        final boolean nearNative = Math.abs(Math.min(scaleX, scaleY) - 1.0f) < nearThr;
        final boolean canUpscaleNow = (fbW != srcW || fbH != srcH);

        // === FSR path selection + telemetry ===

        // HDR-friendly path: keep it lightweight and color-stable.
        // - Upscaling: rely on bilinear sampling (OES blit to the window surface).
        // - Optional sharpening: hue-preserving luma-only RCAS on OES (no OES->RGBA8 staging).
        if (hdrActive) {
            final boolean allowSharpen = upscaleEnabled && !modeNone;
            final int preset = (prefs != null ? prefs.videoUpscalePreset : 0);

            float effSharp = 0f;
            if (allowSharpen) {
                effSharp = mapUiSharpToInternalRcas(sharpUser, preset, nearNative);
                // Conservative for HDR (reduce halos on highlights).
                effSharp *= 0.75f;
            }

            boolean ok = false;
            if (allowSharpen && effSharp > 0.001f) {
                ok = drawHdrRcasOesLumaSafe(fbW, fbH, effSharp);
            }

            if (fsrEnabled) {
                __fsr.srcW = srcW;
                __fsr.srcH = srcH;
                __fsr.dstW = fbW;
                __fsr.dstH = fbH;
                __fsr.sharp = effSharp;
                __fsr.mode = (ok ? "HDR_RCAS_LUMA" : "HDR_BILINEAR");
                __fsr.sampling = (ok ? "RCAS_OES_HDR_LUMA" : "bypass");
                maybeUpdateSimpleFsrNote(ok ? SIMPLE_NOTE_HDR_OK : SIMPLE_NOTE_HDR_FALLBACK, allowSharpen, 0, 0, 0, 0);
                __fsr.frames++;
                pollFsrGpuTimers();
                if ((__fsr.frames % 240L) == 0L) {
                    LimeLog.info(__fsr.periodicLine());
                }
                maybeUpdateFsrOverlay();
            }

            if (!ok) {
                drawOesToScreen(fbW, fbH, srcW, srcH);
            }

            if (swapAndContinue()) {
                sizeChangedSinceLastSwap = false;
            }
            return;
        }

        // === FSR path selection + telemetry ===
        if (!upscaleEnabled || modeNone) {
            // === BYPASS PATH ===
            if (fsrEnabled) {
                __fsr.mode = "BYPASS";
                __fsr.srcW = srcW;
                __fsr.srcH = srcH;
                __fsr.dstW = fbW;
                __fsr.dstH = fbH;
                __fsr.sharp = 0f;
                __fsr.sampling = "bypass";
                maybeUpdateSimpleFsrNote(modeNone ? SIMPLE_NOTE_BYPASS_MODE_NONE : SIMPLE_NOTE_BYPASS_DISABLED, false, fbW, fbH, dstTargetW, dstTargetH);
                maybeUpdateFsrOverlay();
            }
            bindFramebufferCached(0);
            drawOesToScreen(fbW, fbH, srcW, srcH);
        } else if (modeEasuRcas
                && (progEasuPerf2D != 0 || progEasuBalanced2D != 0 || progEasuQuality2D != 0 || progEasuPerf != 0 || progEasuBalanced != 0 || progEasuQuality != 0)
                && !nearNative
                && canUpscaleNow) {

            final int preset = (prefs != null ? prefs.videoUpscalePreset : 1); // 0..2
            final float upRatio = Math.max(scaleX, scaleY);

            final float effSharp = mapUiSharpToInternalEasuRcasLinear(sharpUser, preset);
            boolean ok = drawEasuRcasSafe(fbW, fbH, effSharp, preset);

            if (fsrEnabled) {
                __fsr.mode = "EASU+RCAS";
                __fsr.srcW = srcW;
                __fsr.srcH = srcH;
                __fsr.dstW = fbW;
                __fsr.dstH = fbH;
                __fsr.sharp = effSharp;
                __fsr.sampling = "OES->2D LINEAR + RCAS_2D";
                maybeUpdateFsrNotesEasuRcas(ok, preset, upRatio);
                __fsr.frames++;
                pollFsrGpuTimers();
                if ((__fsr.frames % 240L) == 0L) {
                    com.limelight.LimeLog.info(__fsr.periodicLine());
                }
                if (fsrEnabled) maybeUpdateFsrOverlay();
            }

            if (!ok) {
                drawOesToScreen(fbW, fbH, srcW, srcH);
            }


        } else {
            // === RCAS-ONLY PATH ===
            final int preset = (prefs != null ? prefs.videoUpscalePreset : 0);
            final float effSharp = mapUiSharpToInternalRcas(sharpUser, preset, nearNative);

            final boolean ok = drawRcasOnlySafe(fbW, fbH, effSharp);

            if (fsrEnabled) {
                __fsr.mode = "RCAS_ONLY";
                __fsr.srcW = srcW;
                __fsr.srcH = srcH;
                __fsr.dstW = fbW;
                __fsr.dstH = fbH;
                __fsr.sharp = effSharp;
                // __fsr.sampling is set by drawRcasOnlySafe(): RCAS_OES or OES->2D LINEAR + RCAS_2D

                int reasonId;
                if (nearNative) reasonId = FSR_REASON_NEAR_NATIVE;
                else if (srcW == fbW && srcH == fbH) reasonId = FSR_REASON_DST_EQ_SRC;
                else if (modeRcasOnly) reasonId = FSR_REASON_EASU_DISABLED;
                else reasonId = FSR_REASON_MODE_NOT_EASU_RCAS;

                maybeUpdateFsrNotesRcasOnly(ok, preset, nearNative, nearThr, reasonId);
                __fsr.frames++;
                pollFsrGpuTimers();
                if ((__fsr.frames % 240L) == 0L) {
                    LimeLog.info(__fsr.periodicLine());
                }
                maybeUpdateFsrOverlay();
            }

            if (!ok) {
                drawOesToScreen(fbW, fbH, srcW, srcH);
            }
        }
        if (swapAndContinue()) {
            sizeChangedSinceLastSwap = false;
        }
    }

    private boolean swapAndContinue() {
        if (hasPresentationTimeExt) {
            try {
                final long presentNs;
                if (useChoreoVsync) {
                    final long t = lastChoreoFrameTimeNs;
                    presentNs = (t != 0L) ? t : System.nanoTime();
                } else {
                    presentNs = System.nanoTime();
                }
                EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindowSurface, presentNs);
            } catch (Throwable ignored) { }
        }

        final boolean ok = EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface);
        if (!ok) {
            final int err = EGL14.eglGetError();
            swapFailStreak++;
            LimeLog.warning("FSR: eglSwapBuffers failed err=0x" + Integer.toHexString(err) + " streak=" + swapFailStreak);

            if (swapFailStreak >= 6) {
                LimeLog.warning("FSR: eglSwapBuffers failing repeatedly, stopping renderer to allow clean restart");
                running.set(false);
            }
        } else {
            swapFailStreak = 0;
        }

        return ok;
    }



    // ====== Draw operations ======
    private void drawOesToScreen(int dstW, int dstH, int srcW, int srcH) {
        UseProgram(progBlit);
        bindQuad(progBlit);

        if (lastBlitTexMatSerial != texMatrixSerial) {
            GLES20.glUniformMatrix4fv(blit_uTexMat, 1, false, texMatrix, 0);
            lastBlitTexMatSerial = texMatrixSerial;
        }

        activeTexture0();
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            lastTexture = oesTexId;
        }
        setOesFilter((dstW == srcW && dstH == srcH));

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }


    private boolean drawHdrRcasOesLumaSafe(int dstW, int dstH, float sharp) {
        if (!hasEverUpdatedTex) return false;
        if (progRcasOesHdr == 0) return false;

        checkRcasOesHealthOnce(dstW, dstH);
        if (!rcasOesHealthy) return false;

        final float s = clamp01(sharp);
        if (s <= 0.001f) return false;

        if (__fsr.enabled) { __fsr.sampling = "RCAS_OES_HDR_LUMA"; }

        bindFramebufferCached(0);
        ensureViewport(dstW, dstH);

        UseProgram(progRcasOesHdr);
        bindQuad(progRcasOesHdr);

        if (dstW != lastRcasOesHdrInvDstW || dstH != lastRcasOesHdrInvDstH) {
            GLES20.glUniform2f(
                    rcasOesHdr_uInvDst,
                    1.0f / (float) Math.max(1, dstW),
                    1.0f / (float) Math.max(1, dstH)
            );
            lastRcasOesHdrInvDstW = dstW;
            lastRcasOesHdrInvDstH = dstH;
        }

        if (Math.abs(s - lastRcasOesHdrSharp) > 0.0005f) {
            GLES20.glUniform1f(rcasOesHdr_uSharp, s);
            lastRcasOesHdrSharp = s;
        }

        if (lastRcasOesHdrTexMatSerial != texMatrixSerial) {
            GLES20.glUniformMatrix4fv(rcasOesHdr_uTexMat, 1, false, texMatrix, 0);
            lastRcasOesHdrTexMatSerial = texMatrixSerial;
        }

        activeTexture0();
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            lastTexture = oesTexId;
        }
        // Always linear in HDR (upscaling/downscaling + preserve gradients)
        setOesFilter(false);

        if (__fsr.enabled) {
            __fsr.ticRcas();
            rcasGpuTimer.begin();
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) {
            rcasGpuTimer.end();
            __fsr.tocRcas();
        }
        return true;
    }



    private boolean drawRcasOnlySafe(int dstW, int dstH, float sharp) {
        if (hasEverUpdatedTex) {
            checkRcasOesHealthOnce(dstW, dstH);
        }

        final float s = clamp01(sharp);

        // HDR: never stage OES -> RGBA8 2D (banding/color shifts). Use OES luma-only RCAS.
        if (hdrActive) {
            if (s <= 0.001f) return false;
            return drawHdrRcasOesLumaSafe(dstW, dstH, s);
        }

        // Prefer direct OES sharpening when program is available
        if (progRcasOes != 0 && rcasOesHealthy) {
            if (__fsr.enabled) { __fsr.sampling = "RCAS_OES"; }

            bindFramebufferCached(0);
            ensureViewport(dstW, dstH);

            UseProgram(progRcasOes);
            bindQuad(progRcasOes);

            if (dstW != lastRcasOesInvDstW || dstH != lastRcasOesInvDstH) {
                GLES20.glUniform2f(
                        rcasOes_uInvDst,
                        1.0f / (float) Math.max(1, dstW),
                        1.0f / (float) Math.max(1, dstH)
                );
                lastRcasOesInvDstW = dstW;
                lastRcasOesInvDstH = dstH;
            }

            if (Math.abs(s - lastRcasOesSharp) > 0.0005f) {
                GLES20.glUniform1f(rcasOes_uSharp, s);
                lastRcasOesSharp = s;
            }

            if (lastRcasOesTexMatSerial != texMatrixSerial) {
                GLES20.glUniformMatrix4fv(rcasOes_uTexMat, 1, false, texMatrix, 0);
                lastRcasOesTexMatSerial = texMatrixSerial;
            }

            activeTexture0();
            if (lastTexture != oesTexId) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
                lastTexture = oesTexId;
            }
            setOesFilter((dstW > srcW || dstH > srcH) ? false : true);

            if (__fsr.enabled) {
                __fsr.ticRcas();
                rcasGpuTimer.begin();
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            if (__fsr.enabled) {
                rcasGpuTimer.end();
                __fsr.tocRcas();
            }
            return true;
        }

        // Fallback: OES -> upscaledTex, then RCAS on 2D
        if (!ensureFbo(dstW, dstH)) return false;

        if (__fsr.enabled) {
            __fsr.sampling = "OES->2D BLIT + RCAS_2D";
        }

        // OES -> upscaledTex (full overwrite, no timing attribution)
        bindFramebufferCached(fbo);
        ensureViewport(dstW, dstH);

        UseProgram(progBlit);
        bindQuad(progBlit);

        if (lastBlitTexMatSerial != texMatrixSerial) {
            GLES20.glUniformMatrix4fv(blit_uTexMat, 1, false, texMatrix, 0);
            lastBlitTexMatSerial = texMatrixSerial;
        }

        activeTexture0();
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            lastTexture = oesTexId;
        }
        setOesFilter((dstW > srcW || dstH > srcH) ? false : true);

        if (__fsr.enabled) {
            __fsr.ticBlit();
            blitGpuTimer.begin();
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) {
            blitGpuTimer.end();
            __fsr.tocBlit();
        }

        // RCAS: upscaledTex -> screen
        bindFramebufferCached(0);
        ensureViewport(dstW, dstH);

        final boolean useFast = (prefs != null && prefs.videoUpscalePreset == 0) && (progRcasFast != 0);
        final int rcasProg = useFast ? progRcasFast : progRcas;

        UseProgram(rcasProg);
        bindQuad(rcasProg);

        activeTexture0();
        bindTex2DCached(upscaledTex);
        setTex2DFilter(true);

        if (useFast) {
            if (dstW != lastRcasFastInvDstW || dstH != lastRcasFastInvDstH) {
                GLES20.glUniform2f(
                        rcasFast_uInvDst,
                        1.0f / (float) Math.max(1, dstW),
                        1.0f / (float) Math.max(1, dstH)
                );
                lastRcasFastInvDstW = dstW;
                lastRcasFastInvDstH = dstH;
            }

            if (Math.abs(s - lastRcasFastSharp) > 0.0005f) {
                GLES20.glUniform1f(rcasFast_uSharp, s);
                lastRcasFastSharp = s;
            }
        } else {
            if (dstW != lastRcasInvDstW || dstH != lastRcasInvDstH) {
                GLES20.glUniform2f(
                        rcas_uInvDst,
                        1.0f / (float) Math.max(1, dstW),
                        1.0f / (float) Math.max(1, dstH)
                );
                lastRcasInvDstW = dstW;
                lastRcasInvDstH = dstH;
            }

            if (Math.abs(s - lastRcasSharp) > 0.0005f) {
                GLES20.glUniform1f(rcas_uSharp, s);
                lastRcasSharp = s;
            }
        }

        if (__fsr.enabled) {
            __fsr.ticRcas();
            rcasGpuTimer.begin();
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) {
            rcasGpuTimer.end();
            __fsr.tocRcas();
        }

        // Keep NEAREST once enabled to avoid 2x glTexParameteri per frame.
        return true;
    }

    private boolean drawEasuRcasSafe(int dstW, int dstH, float sharp, int preset) {
        final boolean have2DEasu = (progEasuPerf2D != 0 || progEasuBalanced2D != 0 || progEasuQuality2D != 0);

        if (__fsr.enabled) {
            __fsr.sampling = have2DEasu ? "OES->2D COPY + EASU_2D + RCAS_2D" : "OES_EASU + RCAS_2D";
        }
        if (!ensureFbo(dstW, dstH)) return false;

        if (preset < 0) preset = 0;
        else if (preset > 2) preset = 2;

        final float s = clamp01(sharp);

        // Stage OES -> 2D once per frame (src resolution). If staging fails, fall back to OES EASU.
        boolean stagedOk = false;
        if (have2DEasu) {
            stagedOk = stageOesTo2D(srcW, srcH);
        }

        // Select EASU program (0=Perf, 1=Balanced, 2=Quality)
        int easuProg = 0;
        int easuTexMatLoc = -1;

        if (stagedOk) {
            if (preset == 2 && progEasuQuality2D != 0) {
                easuProg = progEasuQuality2D;
                easuTexMatLoc = easuQ2D_uTexMat;
            } else if (preset == 1 && progEasuBalanced2D != 0) {
                easuProg = progEasuBalanced2D;
                easuTexMatLoc = easuBal2D_uTexMat;
            } else if (progEasuPerf2D != 0) { // <-- PERF default, NON Mali-only
                easuProg = progEasuPerf2D;
                easuTexMatLoc = easuPerf2D_uTexMat;
            } else {
                stagedOk = false;
            }
        }

        if (!stagedOk) {
            if (preset == 2 && progEasuQuality != 0) {
                easuProg = progEasuQuality;
                easuTexMatLoc = easuQ_uTexMat;
            } else if (preset == 1 && progEasuBalanced != 0) {
                easuProg = progEasuBalanced;
                easuTexMatLoc = easuBal_uTexMat;
            } else if (progEasuPerf != 0) { // <-- PERF default, NON Mali-only
                easuProg = progEasuPerf;
                easuTexMatLoc = easuPerf_uTexMat;
            } else {
                return false;
            }
        }

        // EASU -> FBO
        bindFramebufferCached(fbo);
        ensureViewport(dstW, dstH);

        UseProgram(easuProg);
        bindQuad(easuProg);

        activeTexture0();
        if (stagedOk) {
            bindTex2DCached(srcRgbTex);
            setTex2DFilter(false); // LINEAR, to match previous behavior
        } else {
            if (lastTexture != oesTexId) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
                lastTexture = oesTexId;
            }
            setOesFilter(false);
        }

        if (easuTexMatLoc >= 0) {
            boolean needUpload;
            final boolean isQuality = (easuProg == (stagedOk ? progEasuQuality2D : progEasuQuality));
            final boolean isBalanced = (easuProg == (stagedOk ? progEasuBalanced2D : progEasuBalanced));

            if (isQuality) {
                needUpload = (lastEasuQualityTexMatSerial != texMatrixSerial);
            } else if (isBalanced) {
                needUpload = (lastEasuBalTexMatSerial != texMatrixSerial);
            } else {
                needUpload = (lastEasuPerfTexMatSerial != texMatrixSerial);
            }

            if (needUpload) {
                GLES20.glUniformMatrix4fv(easuTexMatLoc, 1, false, texMatrix, 0);

                if (isQuality) lastEasuQualityTexMatSerial = texMatrixSerial;
                else if (isBalanced) lastEasuBalTexMatSerial = texMatrixSerial;
                else lastEasuPerfTexMatSerial = texMatrixSerial;
            }
        }

        if (__fsr.enabled) {
            __fsr.ticEasu();
            easuGpuTimer.begin();
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) {
            easuGpuTimer.end();
            __fsr.tocEasu();
        }

        // RCAS -> screen
        bindFramebufferCached(0);
        ensureViewport(dstW, dstH);

        final boolean useFast = (preset == 0) && (progRcasFast != 0);
        final int rcasProg = useFast ? progRcasFast : progRcas;

        UseProgram(rcasProg);
        bindQuad(rcasProg);

        activeTexture0();
        bindTex2DCached(upscaledTex);
        setTex2DFilter(true);

        if (useFast) {
            if (dstW != lastRcasFastInvDstW || dstH != lastRcasFastInvDstH) {
                GLES20.glUniform2f(
                        rcasFast_uInvDst,
                        1.0f / (float) Math.max(1, dstW),
                        1.0f / (float) Math.max(1, dstH)
                );
                lastRcasFastInvDstW = dstW;
                lastRcasFastInvDstH = dstH;
            }

            if (Math.abs(s - lastRcasFastSharp) > 0.0005f) {
                GLES20.glUniform1f(rcasFast_uSharp, s);
                lastRcasFastSharp = s;
            }
        } else {
            if (dstW != lastRcasInvDstW || dstH != lastRcasInvDstH) {
                GLES20.glUniform2f(
                        rcas_uInvDst,
                        1.0f / (float) Math.max(1, dstW),
                        1.0f / (float) Math.max(1, dstH)
                );
                lastRcasInvDstW = dstW;
                lastRcasInvDstH = dstH;
            }

            if (Math.abs(s - lastRcasSharp) > 0.0005f) {
                GLES20.glUniform1f(rcas_uSharp, s);
                lastRcasSharp = s;
            }
        }

        if (__fsr.enabled) {
            __fsr.ticRcas();
            rcasGpuTimer.begin();
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) {
            rcasGpuTimer.end();
            __fsr.tocRcas();
        }

        return true;
    }


    // ====== GL setup ======
    private boolean isGlReady() {
        return eglDisplay != EGL14.EGL_NO_DISPLAY &&
                eglContext != EGL14.EGL_NO_CONTEXT &&
                eglWindowSurface != EGL14.EGL_NO_SURFACE;
    }

    private void refreshWindowSize() {
        if (!isGlReady()) {
            lastSizeQueryOk = false;
            return;
        }

        int w = 0, h = 0;
        try {
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_WIDTH,  tmpIntArray, 0);  w = tmpIntArray[0];
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_HEIGHT, tmpIntArray, 0); h = tmpIntArray[0];
        } catch (Throwable ignored) { }

        lastSizeQueryOk = (w > 0 && h > 0);
        if (!lastSizeQueryOk) return;

        if (w != fbW || h != fbH) {
            fbW = w;
            fbH = h;

            // viewport depends on window size
            curVpW = -1;
            curVpH = -1;

            // If FBO exists with old size, drop it; re-create lazily when needed.
            if (fbo != 0 && (fboW != w || fboH != h)) {
                destroyFbo();
            }
        }

        if (w == srcW && h == srcH && (hintOutW > srcW || hintOutH > srcH)) {
            try {
                LimeLog.warning("FSR: window surface == source (" + w + "x" + h + "), but presentation hint is " +
                        hintOutW + "x" + hintOutH +
                        ". Upscale will be bypassed. Use a display-sized Surface (TextureView.setDefaultBufferSize or SurfaceHolder.setFixedSize).");
            } catch (Throwable ignored) { }
        }
    }

    private void ensureViewport(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w != curVpW || h != curVpH) {
            GLES20.glViewport(0, 0, w, h);
            curVpW = w;
            curVpH = h;
        }
    }

    private boolean ensureSrcStageFbo(int w, int h) {
        if (w <= 0 || h <= 0) return false;

        if (srcRgbTex != 0 && srcFbo != 0 && w == srcFboW && h == srcFboH) {
            return true;
        }

        createOrResizeSrcStageFbo(w, h);
        return (srcRgbTex != 0 && srcFbo != 0);
    }

    private void createOrResizeSrcStageFbo(int w, int h) {
        // Delete only stage resources (avoid touching the upscaled FBO)
        if (srcRgbTex != 0) {
            if (lastTex2D == srcRgbTex) lastTex2D = -1;
            invalidateTex2DFilterCache(srcRgbTex);
            tmpIntArray[0] = srcRgbTex;
            GLES20.glDeleteTextures(1, tmpIntArray, 0);
            srcRgbTex = 0;
        }
        if (srcFbo != 0) {
            if (lastFbo == srcFbo) lastFbo = -1;
            tmpIntArray[0] = srcFbo;
            GLES20.glDeleteFramebuffers(1, tmpIntArray, 0);
            srcFbo = 0;
        }

        GLES20.glGenFramebuffers(1, tmpIntArray, 0);
        srcFbo = tmpIntArray[0];

        GLES20.glGenTextures(1, tmpIntArray, 0);
        srcRgbTex = tmpIntArray[0];

        bindTex2DCached(srcRgbTex);
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0,
                GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                null
        );

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        bindFramebufferCached(srcFbo);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                srcRgbTex,
                0
        );

        if (!isFboComplete()) {
            bindFramebufferCached(0);

            if (srcRgbTex != 0) {
                tmpIntArray[0] = srcRgbTex;
                GLES20.glDeleteTextures(1, tmpIntArray, 0);
                srcRgbTex = 0;
            }
            if (srcFbo != 0) {
                tmpIntArray[0] = srcFbo;
                GLES20.glDeleteFramebuffers(1, tmpIntArray, 0);
                srcFbo = 0;
            }
            srcFboW = 0;
            srcFboH = 0;
            return;
        }

        srcFboW = w;
        srcFboH = h;
        bindFramebufferCached(0);
    }

    private boolean stageOesTo2D(int w, int h) {
        if (progOesTo2D == 0 || oesTexId == 0) return false;
        if (!ensureSrcStageFbo(w, h)) return false;

        bindFramebufferCached(srcFbo);
        ensureViewport(w, h);

        UseProgram(progOesTo2D);
        bindQuad(progOesTo2D);

        activeTexture0();
        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            lastTexture = oesTexId;
        }
        setOesFilter(true); // NEAREST: 1:1 staging copy, cheaper and avoids unnecessary blur

        if (__fsr.enabled) {
            __fsr.ticBlit();
            blitGpuTimer.begin();
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) {
            blitGpuTimer.end();
            __fsr.tocBlit();
        }
        return true;
    }

    private boolean ensureFbo(int w, int h) {
        if (w <= 0 || h <= 0) return false;

        if (upscaledTex != 0 && fbo != 0 && w == fboW && h == fboH) {
            return true;
        }

        createOrResizeFbo(w, h);
        return (upscaledTex != 0 && fbo != 0);
    }



    private void createOrResizeFbo(int w, int h) {
        destroyFbo();

        fboW = w;
        fboH = h;

        // Viewport cache invalid (we change targets in the same frame)
        curVpW = -1;
        curVpH = -1;

        GLES20.glGenFramebuffers(1, tmpIntArray, 0);
        fbo = tmpIntArray[0];

        GLES20.glGenTextures(1, tmpIntArray, 0);
        upscaledTex = tmpIntArray[0];

        bindTex2DCached(upscaledTex);
        GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0,
                GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                null
        );
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        bindFramebufferCached(fbo);
        GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                upscaledTex,
                0
        );

        if (!isFboComplete()) {
            bindFramebufferCached(0);
            destroyFbo();
            return;
        }

        bindFramebufferCached(0);

        // Force filter state to be re-applied on next use.
        twoDNearest = false;
    }



    private boolean isFboComplete() {
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            LimeLog.warning("FBO incomplete: 0x" + Integer.toHexString(status));
            return false;
        }
        return true;
    }

    private void destroyFbo() {
        // Stage resources (OES -> 2D)
        if (srcRgbTex != 0) {
            if (lastTex2D == srcRgbTex) lastTex2D = -1;
            invalidateTex2DFilterCache(srcRgbTex);
            tmpIntArray[0] = srcRgbTex;
            GLES20.glDeleteTextures(1, tmpIntArray, 0);
            srcRgbTex = 0;
        }
        if (srcFbo != 0) {
            if (lastFbo == srcFbo) lastFbo = -1;
            tmpIntArray[0] = srcFbo;
            GLES20.glDeleteFramebuffers(1, tmpIntArray, 0);
            srcFbo = 0;
        }
        srcFboW = 0;
        srcFboH = 0;

        // Upscale intermediate resources (EASU output)
        if (upscaledTex != 0) {
            if (lastTex2D == upscaledTex) lastTex2D = -1;
            invalidateTex2DFilterCache(upscaledTex);
            tmpIntArray[0] = upscaledTex;
            GLES20.glDeleteTextures(1, tmpIntArray, 0);
            upscaledTex = 0;
        }
        if (fbo != 0) {
            if (lastFbo == fbo) lastFbo = -1;
            tmpIntArray[0] = fbo;
            GLES20.glDeleteFramebuffers(1, tmpIntArray, 0);
            fbo = 0;
        }

        fboW = 0;
        fboH = 0;
    }
    private void detectGpuCapsOnce() {
        if (gpuCapsDetected) return;
        gpuCapsDetected = true;

        String renderer = null;
        String vendor = null;
        try { renderer = GLES20.glGetString(GLES20.GL_RENDERER); } catch (Throwable ignored) {}
        try { vendor = GLES20.glGetString(GLES20.GL_VENDOR); } catch (Throwable ignored) {}

        final String r = (renderer != null) ? renderer.toLowerCase(java.util.Locale.US) : "";
        final String v = (vendor != null) ? vendor.toLowerCase(java.util.Locale.US) : "";

        // Conservative Mali detection (ARM vendor + Mali renderer).
        isMaliGpu = (r.contains("mali") || v.contains("arm"));
    }

    private void primeStaticUniforms() {
        // Samplers are constant: texture unit 0
        if (progBlit != 0 && blit_uTex >= 0) {
            GLES20.glUseProgram(progBlit);
            GLES20.glUniform1i(blit_uTex, 0);
        }
        if (progOesTo2D != 0 && oes2d_uTex >= 0) {
            GLES20.glUseProgram(progOesTo2D);
            GLES20.glUniform1i(oes2d_uTex, 0);
        }

        if (progEasuPerf != 0) {
            GLES20.glUseProgram(progEasuPerf);
            if (easuPerf_uTex >= 0) {
                GLES20.glUniform1i(easuPerf_uTex, 0);
            }
            if (easuPerf_uInvSrcSize >= 0) {
                GLES20.glUniform2f(
                        easuPerf_uInvSrcSize,
                        1.0f / Math.max(1, srcW),
                        1.0f / Math.max(1, srcH)
                );
            }
        }

        if (progEasuBalanced != 0) {
            GLES20.glUseProgram(progEasuBalanced);
            if (easuBal_uTex >= 0) {
                GLES20.glUniform1i(easuBal_uTex, 0);
            }
            if (easuBal_uInvSrcSize >= 0) {
                GLES20.glUniform2f(
                        easuBal_uInvSrcSize,
                        1.0f / Math.max(1, srcW),
                        1.0f / Math.max(1, srcH)
                );
            }
        }

        if (progEasuQuality != 0) {
            GLES20.glUseProgram(progEasuQuality);
            if (easuQ_uTex >= 0) {
                GLES20.glUniform1i(easuQ_uTex, 0);
            }
            if (easuQ_uInvSrcSize >= 0) {
                GLES20.glUniform2f(
                        easuQ_uInvSrcSize,
                        1.0f / Math.max(1, srcW),
                        1.0f / Math.max(1, srcH)
                );
            }
        }

        if (progEasuPerf2D != 0) {
            GLES20.glUseProgram(progEasuPerf2D);
            if (easuPerf2D_uTex >= 0) GLES20.glUniform1i(easuPerf2D_uTex, 0);
            if (easuPerf2D_uInvSrcSize >= 0) {
                GLES20.glUniform2f(easuPerf2D_uInvSrcSize,
                        1.0f / Math.max(1, srcW),
                        1.0f / Math.max(1, srcH));
            }
        }

        if (progEasuBalanced2D != 0) {
            GLES20.glUseProgram(progEasuBalanced2D);
            if (easuBal2D_uTex >= 0) GLES20.glUniform1i(easuBal2D_uTex, 0);
            if (easuBal2D_uInvSrcSize >= 0) {
                GLES20.glUniform2f(easuBal2D_uInvSrcSize,
                        1.0f / Math.max(1, srcW),
                        1.0f / Math.max(1, srcH));
            }
        }

        if (progEasuQuality2D != 0) {
            GLES20.glUseProgram(progEasuQuality2D);
            if (easuQ2D_uTex >= 0) GLES20.glUniform1i(easuQ2D_uTex, 0);
            if (easuQ2D_uInvSrcSize >= 0) {
                GLES20.glUniform2f(easuQ2D_uInvSrcSize,
                        1.0f / Math.max(1, srcW),
                        1.0f / Math.max(1, srcH));
            }
        }

        if (progRcas != 0 && rcas_uTex >= 0) {
            GLES20.glUseProgram(progRcas);
            GLES20.glUniform1i(rcas_uTex, 0);
            setRcasLumaUniform(rcas_uLuma, false);
        }
        if (progRcasFast != 0 && rcasFast_uTex >= 0) {
            GLES20.glUseProgram(progRcasFast);
            GLES20.glUniform1i(rcasFast_uTex, 0);
            setRcasLumaUniform(rcasFast_uLuma, false);
        }

        if (progRcasOes != 0 && rcasOes_uTex >= 0) {
            GLES20.glUseProgram(progRcasOes);
            GLES20.glUniform1i(rcasOes_uTex, 0);
            setRcasLumaUniform(rcasOes_uLuma, false);
        }

        if (progRcasOesHdr != 0 && rcasOesHdr_uTex >= 0) {
            GLES20.glUseProgram(progRcasOesHdr);
            GLES20.glUniform1i(rcasOesHdr_uTex, 0);
            setRcasLumaUniform(rcasOesHdr_uLuma, true);
        }

        // Force next draw to rebind program via UseProgram()
        lastProgram = -1;
    }
    private void initEglAndGl() {
        if (isGlReady()) return;

        synchronized (this) {
            if (isGlReady()) return;

            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (eglDisplay == EGL14.EGL_NO_DISPLAY)
                    throw new RuntimeException("No EGL display");

                int[] v = new int[2];
                if (!EGL14.eglInitialize(eglDisplay, v, 0, v, 1))
                    throw new RuntimeException("eglInitialize failed");

                // prefer ES3 config if possible, fallback to ES2
                final int EGL_OPENGL_ES3_BIT_KHR = 0x00000040; // from EGL/eglplatform.h
                int renderableType = EGL_OPENGL_ES3_BIT_KHR;
                int[] cfg = {
                        EGL14.EGL_RENDERABLE_TYPE, renderableType,
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                        EGL14.EGL_NONE
                };

                EGLConfig[] out = new EGLConfig[1];
                int[] num = new int[1];
                if (!EGL14.eglChooseConfig(eglDisplay, cfg, 0, out, 0, 1, num, 0) || num[0] <= 0)
                    throw new RuntimeException("eglChooseConfig failed");

                EGLConfig eglConfig = out[0];

                int[] ctx = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
                eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctx, 0);
                if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT)
                    throw new RuntimeException("eglCreateContext failed");

                int[] sattr = {EGL14.EGL_NONE};
                eglWindowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, windowSurfaceInput, sattr, 0);
                if (eglWindowSurface == null || eglWindowSurface == EGL14.EGL_NO_SURFACE)
                    throw new RuntimeException("eglCreateWindowSurface failed");

                if (!EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext))
                    throw new RuntimeException("eglMakeCurrent failed");
                detectGpuCapsOnce();
                // Cache native handles (eglGetCurrent* wrappers are not reference-stable across calls)
                eglContextHandle = safeEglContextHandle(eglContext);
                eglWindowSurfaceHandle = safeEglSurfaceHandle(eglWindowSurface);

                // Force non-blocking swap after (re)binding current surfaces.
                swapIntervalZeroApplied = false;
                ensureSwapIntervalZero();
                // Reset cached GL state for the new EGL context
                lastProgram = -1;
                lastTexture = -1;
                curVpW = -1;
                curVpH = -1;
                twoDNearest = false;
                oesNearest = false;
                activeTexUnit = -1;
                lastTex2D = -1;
                lastFbo = -1;
                lastBlitTexMatSerial = -1L;
                lastEasuPerfTexMatSerial = -1L;
                lastEasuQualityTexMatSerial = -1L;
                lastEasuBalTexMatSerial = -1L;
                lastRcasOesTexMatSerial = -1L;
                lastRcasOesHdrTexMatSerial = -1L;
                lastRcasInvDstW = lastRcasInvDstH = -1;
                lastRcasSharp = -1f;
                lastRcasFastInvDstW = lastRcasFastInvDstH = -1;
                lastRcasFastSharp = -1f;
                lastRcasOesInvDstW = lastRcasOesInvDstH = -1;
                lastRcasOesSharp = -1f;
                lastRcasOesHdrInvDstW = lastRcasOesHdrInvDstH = -1;
                lastRcasOesHdrSharp = -1f;
                // Reset quad binding cache on new context
                quadBound = false;
                lastBoundVao = 0;

                // Cache EGL_ANDROID_presentation_time support once (skip per-frame try/catch)
                try {
                    final String eglExt = EGL14.eglQueryString(eglDisplay, EGL14.EGL_EXTENSIONS);
                    hasPresentationTimeExt = (eglExt != null && eglExt.contains("EGL_ANDROID_presentation_time"));
                } catch (Throwable ignored) {
                    hasPresentationTimeExt = false;
                }


            } catch (Throwable t) {
                LimeLog.warning("GL init failed: " + t);
                destroyEgl();
                return;
            }
            float[] POS = {-1,-1, 1,-1, -1,1, 1,1};
            float[] UV  = { 0, 0, 1, 0,  0,1, 1,1};
            quadPos = ByteBuffer.allocateDirect(POS.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quadUv  = ByteBuffer.allocateDirect(UV.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            quadPos.put(POS).position(0);
            quadUv.put(UV).position(0);

            GLES20.glGenBuffers(1, tmpIntArray, 0); vboPos = tmpIntArray[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadPos.capacity()*4, quadPos, GLES20.GL_STATIC_DRAW);
            GLES20.glGenBuffers(1, tmpIntArray, 0); vboUv = tmpIntArray[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadUv.capacity()*4, quadUv, GLES20.GL_STATIC_DRAW);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            // VAO: pre-bind attributes once
            try {
                int[] vaoId = new int[1];
                GLES30.glGenVertexArrays(1, vaoId, 0);
                vao = vaoId[0];
                GLES30.glBindVertexArray(vao);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos);
                GLES20.glEnableVertexAttribArray(0);
                GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv);
                GLES20.glEnableVertexAttribArray(1);
                GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
                GLES30.glBindVertexArray(0);
                hasVao = (vao != 0);
            } catch (Throwable ignored) { hasVao = false; }
            // Shaders
            progVs   = compileShader(GLES20.GL_VERTEX_SHADER, VS);

            // BLIT is the hot path for FSR mode NONE: pre-apply SurfaceTexture matrix in VS (cheaper than per-fragment).
            final int vsBlit = compileShader(GLES20.GL_VERTEX_SHADER, VS_BLIT_TEXMAT);
            try {
                progBlit = linkProgram(vsBlit, FS_OES_BLIT);
            } finally {
                try { GLES20.glDeleteShader(vsBlit); } catch (Throwable ignored) {}
            }

            progOesTo2D = linkProgram(progVs, FS_OES_TO_2D);

            // EASU programs: keep original OES versions as fallback.
            progEasuPerf = linkProgram(progVs, FS_EASU_PERF);
            progEasuBalanced = linkProgram(progVs, FS_EASU_BALANCED);
            progEasuQuality = linkProgram(progVs, FS_EASU_QUALITY);
            // 2D variants: identical math, but sample from sampler2D (used with OES->2D staging).
            try { progEasuPerf2D = linkProgram(progVs, fsOesTo2D(FS_EASU_PERF)); } catch (Throwable t) { progEasuPerf2D = 0; }
            try { progEasuBalanced2D = linkProgram(progVs, fsOesTo2D(FS_EASU_BALANCED)); } catch (Throwable t) { progEasuBalanced2D = 0; }
            try { progEasuQuality2D = linkProgram(progVs, fsOesTo2D(FS_EASU_QUALITY)); } catch (Throwable t) { progEasuQuality2D = 0; }
            progRcas = linkProgram(progVs, FS_RCAS);
            try { progRcasFast = linkProgram(progVs, FS_RCAS_FAST); } catch (Throwable t) { progRcasFast = 0; }

            // Try to link OES variant (single-pass RCAS) with specialized VS to precompute steps
            int vsRcasOes = 0;
            try {
                vsRcasOes = compileShader(GLES20.GL_VERTEX_SHADER, VS_RCAS_OES);
                progRcasOes = linkProgram(vsRcasOes, "#define USE_OES\n#define RCAS_OES_VS\n" + FS_RCAS);
                rcasOes_uTex    = GLES20.glGetUniformLocation(progRcasOes, "uTexOES");
                rcasOes_uInvDst = GLES20.glGetUniformLocation(progRcasOes, "uInvDstSize");
                rcasOes_uSharp  = GLES20.glGetUniformLocation(progRcasOes, "uSharp");
                rcasOes_uTexMat = GLES20.glGetUniformLocation(progRcasOes, "uTexMatrix");
                rcasOes_uLuma   = GLES20.glGetUniformLocation(progRcasOes, "uLumaCoeffs");

                // HDR-safe hue-preserving RCAS (luma-only), OES path
                try {
                    progRcasOesHdr = linkProgram(vsRcasOes, "#define USE_OES\n#define RCAS_OES_VS\n#define RCAS_LUMA_ONLY\n" + FS_RCAS);
                    rcasOesHdr_uTex    = GLES20.glGetUniformLocation(progRcasOesHdr, "uTexOES");
                    rcasOesHdr_uInvDst = GLES20.glGetUniformLocation(progRcasOesHdr, "uInvDstSize");
                    rcasOesHdr_uSharp  = GLES20.glGetUniformLocation(progRcasOesHdr, "uSharp");
                    rcasOesHdr_uTexMat = GLES20.glGetUniformLocation(progRcasOesHdr, "uTexMatrix");
                    rcasOesHdr_uLuma   = GLES20.glGetUniformLocation(progRcasOesHdr, "uLumaCoeffs");
                } catch (Throwable t2) {
                    progRcasOesHdr = 0;
                }
            } catch (Throwable t) {
                progRcasOes = 0; // keep fallback 2D path
            } finally {
                if (vsRcasOes != 0) {
                    try { GLES20.glDeleteShader(vsRcasOes); } catch (Throwable ignored) {}
                }
            }
            // Vertex shader can be deleted after linking programs (programs keep internal copies)
            if (progVs != 0) {
                try { GLES20.glDeleteShader(progVs); } catch (Throwable ignored) {}
                progVs = 0;
            }
            if (progEasuPerf != 0) {
                easuPerf_uTex        = GLES20.glGetUniformLocation(progEasuPerf, "uTex");
                easuPerf_uInvSrcSize = GLES20.glGetUniformLocation(progEasuPerf, "uInvSrcSize");
                easuPerf_uTexMat     = GLES20.glGetUniformLocation(progEasuPerf, "uTexMatrix");
            }

            if (progEasuQuality != 0) {
                easuQ_uTex        = GLES20.glGetUniformLocation(progEasuQuality, "uTex");
                easuQ_uInvSrcSize = GLES20.glGetUniformLocation(progEasuQuality, "uInvSrcSize");
                easuQ_uTexMat     = GLES20.glGetUniformLocation(progEasuQuality, "uTexMatrix");
            }

            if (progEasuBalanced != 0) {
                easuBal_uTex        = GLES20.glGetUniformLocation(progEasuBalanced, "uTex");
                easuBal_uInvSrcSize = GLES20.glGetUniformLocation(progEasuBalanced, "uInvSrcSize");
                easuBal_uTexMat     = GLES20.glGetUniformLocation(progEasuBalanced, "uTexMatrix");
            }

            blit_uTex    = GLES20.glGetUniformLocation(progBlit, "uTex");
            blit_uTexMat = GLES20.glGetUniformLocation(progBlit, "uTexMatrix");
            if (progOesTo2D != 0) {
                oes2d_uTex = GLES20.glGetUniformLocation(progOesTo2D, "uTex");
            }

            if (progEasuPerf2D != 0) {
                easuPerf2D_uTex        = GLES20.glGetUniformLocation(progEasuPerf2D, "uTex");
                easuPerf2D_uInvSrcSize = GLES20.glGetUniformLocation(progEasuPerf2D, "uInvSrcSize");
                easuPerf2D_uTexMat     = GLES20.glGetUniformLocation(progEasuPerf2D, "uTexMatrix");
            }
            if (progEasuBalanced2D != 0) {
                easuBal2D_uTex        = GLES20.glGetUniformLocation(progEasuBalanced2D, "uTex");
                easuBal2D_uInvSrcSize = GLES20.glGetUniformLocation(progEasuBalanced2D, "uInvSrcSize");
                easuBal2D_uTexMat     = GLES20.glGetUniformLocation(progEasuBalanced2D, "uTexMatrix");
            }
            if (progEasuQuality2D != 0) {
                easuQ2D_uTex        = GLES20.glGetUniformLocation(progEasuQuality2D, "uTex");
                easuQ2D_uInvSrcSize = GLES20.glGetUniformLocation(progEasuQuality2D, "uInvSrcSize");
                easuQ2D_uTexMat     = GLES20.glGetUniformLocation(progEasuQuality2D, "uTexMatrix");
            }
            rcas_uTex     = GLES20.glGetUniformLocation(progRcas, "uUpscaled");
            rcas_uInvDst  = GLES20.glGetUniformLocation(progRcas, "uInvDstSize");
            rcas_uSharp   = GLES20.glGetUniformLocation(progRcas, "uSharp");
            rcas_uLuma    = GLES20.glGetUniformLocation(progRcas, "uLumaCoeffs");
            if (progRcasFast != 0) {
                rcasFast_uTex    = GLES20.glGetUniformLocation(progRcasFast, "uUpscaled");
                rcasFast_uInvDst = GLES20.glGetUniformLocation(progRcasFast, "uInvDstSize");
                rcasFast_uSharp  = GLES20.glGetUniformLocation(progRcasFast, "uSharp");
                rcasFast_uLuma   = GLES20.glGetUniformLocation(progRcasFast, "uLumaCoeffs");
            }

            // Bind sampler uniforms once (program uniforms persist across draws).
            UseProgram(progBlit);
            if (blit_uTex >= 0) {
                GLES20.glUniform1i(blit_uTex, 0);
            }

            // Constant clear color: keep glClear() in render loop.
            GLES20.glClearColor(0f, 0f, 0f, 1f);

            primeStaticUniforms();
            // Apply fixed GL state once per EGL/GL init (no need to re-check in renderLoop).
            applyFixedState();
            releaseEglCurrent();

        }
    }

    private void destroyGl() {
        final boolean current = ensureEglCurrent();

        try {
            if (current) {
                destroyFbo();

                // Delete GPU timer queries (if allocated)
                easuGpuTimer.release();
                rcasGpuTimer.release();
                blitGpuTimer.release();

                if (hasVao && vao != 0) {
                    try {
                        final int[] vaoId = new int[]{vao};
                        GLES30.glDeleteVertexArrays(1, vaoId, 0);
                    } catch (Throwable ignored) {}
                    vao = 0;
                    hasVao = false;
                }

                if (vboPos != 0) {
                    tmpIntArray[0] = vboPos;
                    GLES20.glDeleteBuffers(1, tmpIntArray, 0);
                    vboPos = 0;
                }
                if (vboUv != 0) {
                    tmpIntArray[0] = vboUv;
                    GLES20.glDeleteBuffers(1, tmpIntArray, 0);
                    vboUv = 0;
                }

                if (progBlit != 0) { GLES20.glDeleteProgram(progBlit); progBlit = 0; }
                if (progOesTo2D != 0) { GLES20.glDeleteProgram(progOesTo2D); progOesTo2D = 0; }
                if (progEasuPerf != 0) { GLES20.glDeleteProgram(progEasuPerf); progEasuPerf = 0; }
                if (progEasuQuality != 0) { GLES20.glDeleteProgram(progEasuQuality); progEasuQuality = 0; }
                if (progEasuBalanced != 0) { GLES20.glDeleteProgram(progEasuBalanced); progEasuBalanced = 0; }
                if (progEasuPerf2D != 0) { GLES20.glDeleteProgram(progEasuPerf2D); progEasuPerf2D = 0; }
                if (progEasuBalanced2D != 0) { GLES20.glDeleteProgram(progEasuBalanced2D); progEasuBalanced2D = 0; }
                if (progEasuQuality2D != 0) { GLES20.glDeleteProgram(progEasuQuality2D); progEasuQuality2D = 0; }
                if (progRcas != 0) { GLES20.glDeleteProgram(progRcas); progRcas = 0; }
                if (progRcasFast != 0) { GLES20.glDeleteProgram(progRcasFast); progRcasFast = 0; }
                if (progRcasOes != 0) { GLES20.glDeleteProgram(progRcasOes); progRcasOes = 0; }
                if (progRcasOesHdr != 0) { GLES20.glDeleteProgram(progRcasOesHdr); progRcasOesHdr = 0; }
            } else {
                // Best-effort fallback: drop references even if we cannot bind EGL here.
                // IDs are only valid in the old context; keep state consistent for a clean re-init.
                vao = 0;
                hasVao = false;
                vboPos = 0;
                vboUv = 0;

                progBlit = 0;
                progOesTo2D = 0;
                progEasuPerf = 0;
                progEasuBalanced = 0;
                progEasuQuality = 0;
                progEasuPerf2D = 0;
                progEasuBalanced2D = 0;
                progEasuQuality2D = 0;
                progRcas = 0;
                progRcasOes = 0;
                progRcasOesHdr = 0;

                srcFbo = 0;
                srcRgbTex = 0;
                srcFboW = 0;
                srcFboH = 0;

                fbo = 0;
                upscaledTex = 0;
                fboW = 0;
                fboH = 0;
            }

        } finally {
            // Reset cached GL bindings/state (context resources no longer valid)
            lastProgram = -1;
            lastTexture = -1;
            curVpW = -1;
            curVpH = -1;
            twoDNearest = false;
            oesNearest = false;
            rcasOesChecked = false;
            rcasOesHealthy = false;
            rcasOesCheckAttempts = 0;
            // Avoid drawing stale/undefined content after a GL re-init
            hasEverUpdatedTex = false;

            // Quad bind cache
            quadBound = false;
            lastBoundVao = 0;

            if (current) {
                releaseEglCurrent();
            }
        }
    }

    private void destroyEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglWindowSurface != EGL14.EGL_NO_SURFACE)
                    EGL14.eglDestroySurface(eglDisplay, eglWindowSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT)
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable t) {
            LimeLog.warning("EGL destroy failed: " + t);
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglWindowSurface = EGL14.EGL_NO_SURFACE;
            eglContextHandle = 0L;
            eglWindowSurfaceHandle = 0L;
            attachedEglContextHandle = 0L;
            swapIntervalZeroApplied = false;
        }
    }

    private void bindQuad(int prog) {
        // VAO path: bind only when changed
        if (hasVao && vao != 0) {
            if (lastBoundVao != vao) {
                try {
                    GLES30.glBindVertexArray(vao);
                    lastBoundVao = vao;
                } catch (Throwable ignored) {
                    lastBoundVao = 0; // force fallback
                }
            }
            if (lastBoundVao == vao) {
                quadBound = true;
                return;
            }
        }

        // Non-VAO path: set attrib pointers once per context
        if (quadBound) {
            return;
        }

        final int locPos = 0;
        final int locUv = 1;

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos);
        GLES20.glEnableVertexAttribArray(locPos);
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv);
        GLES20.glEnableVertexAttribArray(locUv);
        GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        quadBound = true;
    }

    private void ensureSwapIntervalZero() {
        if (eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY) return;
        if (swapIntervalZeroApplied) return;

        try {
            final boolean ok = EGL14.eglSwapInterval(eglDisplay, 0);
            if (ok) {
                swapIntervalZeroApplied = true;
                return;
            }

            // Keep retrying later.
            swapIntervalZeroApplied = false;
            if (++swapIntervalZeroFailCount == 1) {
                LimeLog.warning("FSR: eglSwapInterval(0) failed; driver may still block on vsync");
            }
        } catch (Throwable t) {
            swapIntervalZeroApplied = false;
            if (++swapIntervalZeroFailCount == 1) {
                LimeLog.warning("FSR: eglSwapInterval(0) threw; driver may still block on vsync");
            }
        }
    }

    private void applyFixedState() {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_STENCIL_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ZERO);

        try { GLES20.glDisable(GLES20.GL_DITHER); } catch (Throwable ignored) {}
        try { GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1); } catch (Throwable ignored) {}
    }

    private void invalidateTex2DFilterCache(int texId) {
        if (texId <= 0) return;
        invalidateTex2DFilterCache(texId);
        if (lastTex2DFilterTexId2 == texId) lastTex2DFilterTexId2 = -1;
    }

    private void setTex2DFilter(boolean toNearest) {
        // Filter state is per-texture object. Cache by currently bound GL_TEXTURE_2D on unit 0.
        final int texId = lastTex2D;
        if (texId <= 0) return;

        boolean needApply = true;

        if (texId == lastTex2DFilterTexId) {
            needApply = (twoDNearest != toNearest);
            twoDNearest = toNearest;
        } else if (texId == lastTex2DFilterTexId2) {
            needApply = (twoDNearest2 != toNearest);
            twoDNearest2 = toNearest;

            // Promote slot 1 -> slot 0 to avoid ping-pong when alternating two textures.
            final int oldId0 = lastTex2DFilterTexId;
            final boolean oldF0 = twoDNearest;
            lastTex2DFilterTexId = lastTex2DFilterTexId2;
            twoDNearest = twoDNearest2;
            lastTex2DFilterTexId2 = oldId0;
            twoDNearest2 = oldF0;
        } else {
            // Miss: evict slot 1, fill slot 0
            lastTex2DFilterTexId2 = lastTex2DFilterTexId;
            twoDNearest2 = twoDNearest;
            lastTex2DFilterTexId = texId;
            twoDNearest = toNearest;
        }

        if (!needApply) return;

        final int filter = toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter);
    }


    private void setOesFilter(boolean toNearest) {
        if (oesNearest == toNearest) return;
        oesNearest = toNearest;

        if (lastTexture != oesTexId) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            lastTexture = oesTexId;
        }

        final int filter = toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR;
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, filter);
    }

    private static float clamp01(float v) {
        return Math.min(1f, Math.max(0f, v));
    }
    // Luma coefficients (Y) for common primaries.
    // SDR: Rec.709. HDR10: Rec.2020 (Y coefficients are still defined on RGB primaries).
    private static final float LUMA_709_R = 0.2126f;
    private static final float LUMA_709_G = 0.7152f;
    private static final float LUMA_709_B = 0.0722f;

    private static final float LUMA_2020_R = 0.2627f;
    private static final float LUMA_2020_G = 0.6780f;
    private static final float LUMA_2020_B = 0.0593f;

    private void setRcasLumaUniform(int loc, boolean hdr) {
        if (loc < 0) return;
        if (hdr) {
            GLES20.glUniform3f(loc, LUMA_2020_R, LUMA_2020_G, LUMA_2020_B);
        } else {
            GLES20.glUniform3f(loc, LUMA_709_R, LUMA_709_G, LUMA_709_B);
        }
    }
    private static int toScaled100(float v) {
        return (int) (v * 100.0f + (v >= 0f ? 0.5f : -0.5f));
    }

    private static void appendFixed2Scaled100(StringBuilder sb, int scaled100) {
        if (scaled100 < 0) {
            sb.append('-');
            scaled100 = -scaled100;
        }
        final int i = scaled100 / 100;
        final int f = scaled100 % 100;
        sb.append(i);
        sb.append('.');
        if (f < 10) sb.append('0');
        sb.append(f);
    }

    private static void appendFixed2(StringBuilder sb, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            sb.append(v);
            return;
        }
        boolean neg = v < 0.0;
        if (neg) {
            sb.append('-');
            v = -v;
        }
        long scaled = (long) (v * 100.0 + 0.5);
        sb.append(scaled / 100);
        sb.append('.');
        long frac = scaled % 100;
        if (frac < 10) sb.append('0');
        sb.append(frac);
    }

    private static float mapUiSharpToInternal(float ui, boolean nearNative) {
        float s = clamp01(ui);
        if (s <= 0.02f) return 0f;

        // Keep UI sharpness mostly linear. Reduce strength near-native to avoid halos/ringing.
        return nearNative ? (0.55f * s) : s;
    }
    private static float mapUiSharpToInternalEasuRcasLinear(float ui, int preset) {
        float s = clamp01(ui);
        if (s <= 0.02f) return 0f;

        if (preset < 0) preset = 0;
        else if (preset > 2) preset = 2;

        // Linear mapping, but bias strength for softer EASU kernels:
        // - Perf needs more RCAS to compensate softness
        // - Balanced slightly more
        // - Quality unchanged
        final float gain = (preset == 0) ? 1.35f : (preset == 1 ? 1.20f : 1.00f);

        s *= gain;
        return (s > 1f) ? 1f : s; // keep shader-side clamp behavior consistent
    }

    private static float mapUiSharpToInternalRcas(float ui, int preset, boolean nearNative) {
        float s = clamp01(ui);

        // Keep the same curve, but adjust caps by preset.
        if (ui <= 0.05f) return 0f;
        s = (s - 0.05f) / 0.95f;
        s = (float)(1.0 - Math.exp(-3.0 * s));
        if (s <= 0.05f) return 0f;
        s = (s - 0.05f) / 0.95f;
        s = (float)(1.0 - Math.exp(-3.0 * s));

        if (preset < 0) preset = 0;
        else if (preset > 2) preset = 2;

        final float cap;
        if (preset == 0) {
            // Performance: current behavior
            cap = nearNative ? 0.18f : 0.25f;
        } else if (preset == 1) {
            // Balanced: noticeably stronger, still safe
            cap = nearNative ? 0.28f : 0.40f;
        } else {
            // Quality: strongest; near-native kept lower to avoid halos
            cap = nearNative ? 0.36f : 0.55f;
        }

        return cap * s;
    }

    private static String fsOesTo2D(String oesFs) {
        if (oesFs == null) return null;
        String s = oesFs;
        s = s.replace("#extension GL_OES_EGL_image_external_essl3 : require\n", "");
        s = s.replace("precision highp samplerExternalOES;\n", "");
        s = s.replace("samplerExternalOES", "sampler2D");
        return s;
    }

    private static int compileShader(int type, String src) {
        int sh = GLES20.glCreateShader(type);
        GLES20.glShaderSource(sh, src);
        GLES20.glCompileShader(sh);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(sh);
            GLES20.glDeleteShader(sh);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return sh;
    }

    private static int linkProgram(int vs, String fsSrc) {
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glBindAttribLocation(p, 0, "aPos");
        GLES20.glBindAttribLocation(p, 1, "aUv");
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Program link failed: " + log);
        }
        GLES20.glDeleteShader(fs);
        return p;
    }

    // ====== Shaders ======
    private static final String VS =
            "#version 300 es\n" +
                    "precision highp float;\n" +
                    "layout(location=0) in vec2 aPos;\n" +
                    "layout(location=1) in vec2 aUv;\n" +
                    "out vec2 vUv;\n" +
                    "void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0);}";


    // Specialized VS for BLIT: apply SurfaceTexture matrix in vertex stage (reduces per-fragment ALU)
    private static final String VS_BLIT_TEXMAT =
            "#version 300 es\n" +
                    "precision highp float;\n" +
                    "layout(location=0) in vec2 aPos;\n" +
                    "layout(location=1) in vec2 aUv;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "out vec2 vUv;\n" +
                    "void main(){ vUv=(uTexMatrix*vec4(aUv,0.0,1.0)).xy; gl_Position=vec4(aPos,0.0,1.0);}";


    // Specialized VS for RCAS_OES: precompute uv0/stepX/stepY in vertex to reduce per-fragment ALU
    private static final String VS_RCAS_OES =
            "#version 300 es\n" +
                    "precision highp float;\n" +
                    "layout(location=0) in vec2 aPos;\n" +
                    "layout(location=1) in vec2 aUv;\n" +
                    "out vec2 vUv;\n" +
                    "out vec2 vUv0;\n" +
                    "out vec2 vStepX;\n" +
                    "out vec2 vStepY;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "uniform vec2 uInvDstSize;\n" +
                    "void main(){\n" +
                    "  vUv = aUv;\n" +
                    "  vec2 texel = uInvDstSize;\n" +
                    "  vUv0   = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;\n" +
                    "  vStepX = (uTexMatrix * vec4(texel.x, 0.0, 0.0, 0.0)).xy;\n" +
                    "  vStepY = (uTexMatrix * vec4(0.0, texel.y, 0.0, 0.0)).xy;\n" +
                    "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
                    "}";

    private static final String FS_OES_BLIT =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision highp float;\n" +
                    "precision highp samplerExternalOES;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "void main(){ fragColor = texture(uTex, vUv); }";

    private static final String FS_OES_TO_2D =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision highp float;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "void main(){ fragColor = texture(uTex, vUv); }";

    // EASU minimal pass (OES -> 2D FBO)
    private static final String FS_EASU_QUALITY =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision highp float;\n" +
                    "precision highp int;\n" +
                    "precision highp samplerExternalOES;\n" +
                    "\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "uniform vec2 uInvSrcSize;\n" +
                    "\n" +
                    "float luma(vec3 c){ return c.g + 0.5 * (c.r + c.b); }\n" +
                    "\n" +
                    "float APrxLoRcpF1(float a){ return uintBitsToFloat(uint(0x7ef07ebb) - floatBitsToUint(a)); }\n" +
                    "float APrxLoRsqF1(float a){ return uintBitsToFloat(uint(0x5f347d74) - (floatBitsToUint(a) >> uint(1))); }\n" +
                    "float AMin3F1(float x, float y, float z){ return min(x, min(y, z)); }\n" +
                    "float AMax3F1(float x, float y, float z){ return max(x, max(y, z)); }\n" +
                    "\n" +
                    "void FsrEasuTap(\n" +
                    "    inout vec3 aC,\n" +
                    "    inout float aW,\n" +
                    "    vec2 off,\n" +
                    "    vec2 dir,\n" +
                    "    vec2 len,\n" +
                    "    float lob,\n" +
                    "    float clp,\n" +
                    "    vec3 c)\n" +
                    "{\n" +
                    "    vec2 v;\n" +
                    "    v.x = (off.x * ( dir.x)) + (off.y * dir.y);\n" +
                    "    v.y = (off.x * (-dir.y)) + (off.y * dir.x);\n" +
                    "    v *= len;\n" +
                    "    float d2 = v.x * v.x + v.y * v.y;\n" +
                    "    d2 = min(d2, clp);\n" +
                    "    float wB = (2.0 / 5.0) * d2 + -1.0;\n" +
                    "    float wA = lob * d2 + -1.0;\n" +
                    "    wB *= wB;\n" +
                    "    wA *= wA;\n" +
                    "    wB = (25.0 / 16.0) * wB + (-(25.0 / 16.0 - 1.0));\n" +
                    "    float w = wB * wA;\n" +
                    "    aC += c * w;\n" +
                    "    aW += w;\n" +
                    "}\n" +
                    "\n" +
                    "void FsrEasuSet(\n" +
                    "    inout vec2 dir,\n" +
                    "    inout float len,\n" +
                    "    vec2 pp,\n" +
                    "    bool biS, bool biT, bool biU, bool biV,\n" +
                    "    float lA, float lB, float lC, float lD, float lE)\n" +
                    "{\n" +
                    "    float w = 0.0;\n" +
                    "    if (biS) w = (1.0 - pp.x) * (1.0 - pp.y);\n" +
                    "    if (biT) w = (pp.x) * (1.0 - pp.y);\n" +
                    "    if (biU) w = (1.0 - pp.x) * (pp.y);\n" +
                    "    if (biV) w = (pp.x) * (pp.y);\n" +
                    "\n" +
                    "    float dc = lD - lC;\n" +
                    "    float cb = lC - lB;\n" +
                    "    float lenX = max(abs(dc), abs(cb));\n" +
                    "    lenX = APrxLoRcpF1(lenX);\n" +
                    "    float dirX = lD - lB;\n" +
                    "    lenX = clamp(abs(dirX) * lenX, 0.0, 1.0);\n" +
                    "    lenX *= lenX;\n" +
                    "\n" +
                    "    float ec = lE - lC;\n" +
                    "    float ca = lC - lA;\n" +
                    "    float lenY = max(abs(ec), abs(ca));\n" +
                    "    lenY = APrxLoRcpF1(lenY);\n" +
                    "    float dirY = lE - lA;\n" +
                    "    lenY = clamp(abs(dirY) * lenY, 0.0, 1.0);\n" +
                    "    lenY *= lenY;\n" +
                    "\n" +
                    "    dir += vec2(dirX, dirY) * w;\n" +
                    "    len += w * (lenX + lenY);\n" +
                    "}\n" +
                    "\n" +
                    "vec2 sampleUv(vec2 baseUv, vec2 stepX, vec2 stepY, float ox, float oy){\n" +
                    "    return baseUv + stepX * ox + stepY * oy;\n" +
                    "}\n" +
                    "\n" +
                    "void main(){\n" +
                    "    vec2 srcSize = 1.0 / max(uInvSrcSize, vec2(1e-6));\n" +
                    "    vec2 pp = vUv * srcSize - vec2(0.5);\n" +
                    "    vec2 fp = floor(pp);\n" +
                    "    pp -= fp;\n" +
                    "\n" +
                    "    vec2 baseSrcUv = (fp + vec2(0.5)) * uInvSrcSize;\n" +
                    "    vec2 baseUv = (uTexMatrix * vec4(baseSrcUv, 0.0, 1.0)).xy;\n" +
                    "    vec2 stepX = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;\n" +
                    "    vec2 stepY = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;\n" +
                    "\n" +
                    "    vec3 bC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0, -1.0)).rgb;\n" +
                    "    vec3 cC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0, -1.0)).rgb;\n" +
                    "    vec3 eC = texture(uTex, sampleUv(baseUv, stepX, stepY, -1.0,  0.0)).rgb;\n" +
                    "    vec3 fC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  0.0)).rgb;\n" +
                    "    vec3 gC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  0.0)).rgb;\n" +
                    "    vec3 hC = texture(uTex, sampleUv(baseUv, stepX, stepY,  2.0,  0.0)).rgb;\n" +
                    "    vec3 iC = texture(uTex, sampleUv(baseUv, stepX, stepY, -1.0,  1.0)).rgb;\n" +
                    "    vec3 jC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  1.0)).rgb;\n" +
                    "    vec3 kC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  1.0)).rgb;\n" +
                    "    vec3 lC = texture(uTex, sampleUv(baseUv, stepX, stepY,  2.0,  1.0)).rgb;\n" +
                    "    vec3 nC = texture(uTex, sampleUv(baseUv, stepX, stepY,  0.0,  2.0)).rgb;\n" +
                    "    vec3 oC = texture(uTex, sampleUv(baseUv, stepX, stepY,  1.0,  2.0)).rgb;\n" +
                    "\n" +
                    "    float bL = luma(bC);\n" +
                    "    float cL = luma(cC);\n" +
                    "    float eL = luma(eC);\n" +
                    "    float fL = luma(fC);\n" +
                    "    float gL = luma(gC);\n" +
                    "    float hL = luma(hC);\n" +
                    "    float iL = luma(iC);\n" +
                    "    float jL = luma(jC);\n" +
                    "    float kL = luma(kC);\n" +
                    "    float lL = luma(lC);\n" +
                    "    float nL = luma(nC);\n" +
                    "    float oL = luma(oC);\n" +
                    "\n" +
                    "    vec2 dir = vec2(0.0);\n" +
                    "    float len = 0.0;\n" +
                    "    FsrEasuSet(dir, len, pp, true,  false, false, false, bL, eL, fL, gL, jL);\n" +
                    "    FsrEasuSet(dir, len, pp, false, true,  false, false, cL, fL, gL, hL, kL);\n" +
                    "    FsrEasuSet(dir, len, pp, false, false, true,  false, fL, iL, jL, kL, nL);\n" +
                    "    FsrEasuSet(dir, len, pp, false, false, false, true,  gL, jL, kL, lL, oL);\n" +
                    "\n" +
                    "    vec2 dir2 = dir * dir;\n" +
                    "    float dirR = dir2.x + dir2.y;\n" +
                    "    const float DIR_THRESHOLD = 32768.0;\n" +
                    "    bool zro = dirR < (1.0 / DIR_THRESHOLD);\n" +
                    "    dirR = APrxLoRsqF1(dirR);\n" +
                    "    dirR = zro ? 1.0 : dirR;\n" +
                    "    dir.x = zro ? 1.0 : dir.x;\n" +
                    "    dir *= vec2(dirR);\n" +
                    "\n" +
                    "    len = len * 0.5;\n" +
                    "    len *= len;\n" +
                    "\n" +
                    "    float stretch = (dir.x * dir.x + dir.y * dir.y) * APrxLoRcpF1(max(abs(dir.x), abs(dir.y)));\n" +
                    "    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 + -0.5 * len);\n" +
                    "\n" +
                    "    float lob = 0.5 + ((1.0 / 4.0 - 0.04) - 0.5) * len;\n" +
                    "    float clp = APrxLoRcpF1(lob);\n" +
                    "\n" +
                    "    vec3 aC = vec3(0.0);\n" +
                    "    float aW = 0.0;\n" +
                    "\n" +
                    "    FsrEasuTap(aC, aW, vec2( 0.0,-1.0) - pp, dir, len2, lob, clp, bC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 1.0,-1.0) - pp, dir, len2, lob, clp, cC);\n" +
                    "    FsrEasuTap(aC, aW, vec2(-1.0, 1.0) - pp, dir, len2, lob, clp, iC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 0.0, 1.0) - pp, dir, len2, lob, clp, jC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 0.0, 0.0) - pp, dir, len2, lob, clp, fC);\n" +
                    "    FsrEasuTap(aC, aW, vec2(-1.0, 0.0) - pp, dir, len2, lob, clp, eC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 1.0, 1.0) - pp, dir, len2, lob, clp, kC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 2.0, 1.0) - pp, dir, len2, lob, clp, lC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 2.0, 0.0) - pp, dir, len2, lob, clp, hC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 1.0, 0.0) - pp, dir, len2, lob, clp, gC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 1.0, 2.0) - pp, dir, len2, lob, clp, oC);\n" +
                    "    FsrEasuTap(aC, aW, vec2( 0.0, 2.0) - pp, dir, len2, lob, clp, nC);\n" +
                    "\n" +
                    "    vec3 pix = aC / max(aW, 1e-6);\n" +
                    "    vec3 mn = min(min(fC, gC), min(jC, kC));\n" +
                    "    vec3 mx = max(max(fC, gC), max(jC, kC));\n" +
                    "    pix = clamp(pix, mn, mx);\n" +
                    "    fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);\n" +
                    "}\n" +
                    "";

    private static final String FS_EASU_BALANCED =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision highp float;\n" +
                    "precision highp samplerExternalOES;\n" +
                    "\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform vec2 uInvSrcSize;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "\n" +
                    "float luma(vec3 c){ return dot(c, vec3(0.2126, 0.7152, 0.0722)); }\n" +
                    "\n" +
                    "void main(){\n" +
                    "  vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;\n" +
                    "  vec2 stepX = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;\n" +
                    "  vec2 stepY = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;\n" +
                    "\n" +
                    "  vec3 c = texture(uTex, uv).rgb;\n" +
                    "  vec3 l = texture(uTex, uv - stepX).rgb;\n" +
                    "  vec3 r = texture(uTex, uv + stepX).rgb;\n" +
                    "  vec3 d = texture(uTex, uv - stepY).rgb;\n" +
                    "  vec3 u = texture(uTex, uv + stepY).rgb;\n" +
                    "\n" +
                    "  float gx = luma(r) - luma(l);\n" +
                    "  float gy = luma(u) - luma(d);\n" +
                    "  float edge = clamp((abs(gx) + abs(gy)) * 1.25, 0.0, 1.0);\n" +
                    "\n" +
                    "  // Edge direction is perpendicular to gradient\n" +
                    "  vec2 ed = normalize(vec2(gy, -gx) + vec2(1e-6));\n" +
                    "  vec2 duv = ed.x * stepX + ed.y * stepY;\n" +
                    "  vec3 s1 = texture(uTex, uv + duv * 0.5).rgb;\n" +
                    "  vec3 s2 = texture(uTex, uv - duv * 0.5).rgb;\n" +
                    "\n" +
                    "  // Isotropic base (cheap, stable)\n" +
                    "  vec3 iso = (c * 4.0 + l + r + u + d) * (1.0 / 8.0);\n" +
                    "  // Along-edge sampling reduces cross-edge blur\n" +
                    "  vec3 along = 0.5 * (s1 + s2);\n" +
                    "  vec3 pix = mix(iso, along, edge);\n" +
                    "\n" +
                    "  vec3 mn = min(c, min(min(l, r), min(u, d)));\n" +
                    "  vec3 mx = max(c, max(max(l, r), max(u, d)));\n" +
                    "  pix = clamp(pix, mn, mx);\n" +
                    "  fragColor = vec4(clamp(pix, 0.0, 1.0), 1.0);\n" +
                    "}\n";


    private static final String FS_EASU_PERF =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision highp float;\n" +
                    "precision highp samplerExternalOES;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform vec2 uInvSrcSize;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "\n" +
                    "mediump float luma(mediump vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }\n" +
                    "\n" +
                    "void main(){\n" +
                    // Matrix-safe UV and texel steps
                    "  highp vec2 uv    = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;\n" +
                    "  highp vec2 stepX = (uTexMatrix * vec4(uInvSrcSize.x, 0.0, 0.0, 0.0)).xy;\n" +
                    "  highp vec2 stepY = (uTexMatrix * vec4(0.0, uInvSrcSize.y, 0.0, 0.0)).xy;\n" +
                    "\n" +
                    // Base: relies on HW bilinear when sampler is LINEAR (setOesFilter(false))
                    "  mediump vec3 c  = texture(uTex, uv).rgb;\n" +
                    "  mediump vec3 l  = texture(uTex, uv - stepX).rgb;\n" +
                    "  mediump vec3 r  = texture(uTex, uv + stepX).rgb;\n" +
                    "  mediump vec3 t  = texture(uTex, uv + stepY).rgb;\n" +
                    "  mediump vec3 b  = texture(uTex, uv - stepY).rgb;\n" +
                    "\n" +
                    // Luma-only unsharp (prevents “neon” color shifts)
                    "  mediump float lc = luma(c);\n" +
                    "  mediump float ll = luma(l);\n" +
                    "  mediump float lr = luma(r);\n" +
                    "  mediump float lt = luma(t);\n" +
                    "  mediump float lb = luma(b);\n" +
                    "  mediump float low = 0.25 * (ll + lr + lt + lb);\n" +
                    "  mediump float diff = lc - low;\n" +
                    "\n" +
                    // Strength knob (0.08..0.18). Start conservative.
                    "  mediump float k = 0.12;\n" +
                    "  mediump float l2 = lc + diff * k;\n" +
                    "\n" +
                    // Luma limiter to avoid halos/overshoot
                    "  mediump float mn = min(lc, min(min(ll, lr), min(lt, lb)));\n" +
                    "  mediump float mx = max(lc, max(max(ll, lr), max(lt, lb)));\n" +
                    "  l2 = clamp(l2, mn, mx);\n" +
                    "\n" +
                    // Re-apply luma correction without changing chroma too much
                    "  mediump float s = (lc > 1e-4) ? (l2 / lc) : 1.0;\n" +
                    "  mediump vec3 outRgb = clamp(c * s, 0.0, 1.0);\n" +
                    "  fragColor = vec4(outRgb, 1.0);\n" +
                    "}\n";
    // RCAS shader with optimized OES path using precomputed varyings
    private static final String FS_RCAS =
            "#version 300 es\n" +
                    "#ifdef USE_OES\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "#endif\n" +
                    "precision highp float;\n" +
                    "\n" +
                    "in vec2 vUv;\n" +
                    "#if defined(USE_OES) && defined(RCAS_OES_VS)\n" +
                    "in vec2 vUv0;\n" +
                    "in vec2 vStepX;\n" +
                    "in vec2 vStepY;\n" +
                    "#endif\n" +
                    "\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "\n" +
                    "#ifdef USE_OES\n" +
                    "uniform samplerExternalOES uTexOES;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "#else\n" +
                    "uniform sampler2D uUpscaled;\n" +
                    "#endif\n" +
                    "\n" +
                    "uniform vec2  uInvDstSize;\n" +
                    "uniform float uSharp;\n" +
                    "uniform vec3  uLumaCoeffs;\n" +
                    "\n" +
                    "float luma(vec3 c){ return dot(c, uLumaCoeffs); }\n" +
                    "\n" +
                    "void main(){\n" +
                    "#ifdef USE_OES\n" +
                    "  #ifdef RCAS_OES_VS\n" +
                    "    vec3 c  = texture(uTexOES, vUv0).rgb;\n" +
                    "    vec3 rx = texture(uTexOES, vUv0 + vStepX).rgb;\n" +
                    "    vec3 lx = texture(uTexOES, vUv0 - vStepX).rgb;\n" +
                    "    vec3 ty = texture(uTexOES, vUv0 + vStepY).rgb;\n" +
                    "    vec3 by = texture(uTexOES, vUv0 - vStepY).rgb;\n" +
                    "  #else\n" +
                    "    vec2 texel = uInvDstSize;\n" +
                    "    vec2 uv0    = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;\n" +
                    "    vec2 stepX  = (uTexMatrix * vec4(texel.x, 0.0, 0.0, 0.0)).xy;\n" +
                    "    vec2 stepY  = (uTexMatrix * vec4(0.0, texel.y, 0.0, 0.0)).xy;\n" +
                    "    vec3 c  = texture(uTexOES, uv0).rgb;\n" +
                    "    vec3 rx = texture(uTexOES, uv0 + stepX).rgb;\n" +
                    "    vec3 lx = texture(uTexOES, uv0 - stepX).rgb;\n" +
                    "    vec3 ty = texture(uTexOES, uv0 + stepY).rgb;\n" +
                    "    vec3 by = texture(uTexOES, uv0 - stepY).rgb;\n" +
                    "  #endif\n" +
                    "#else\n" +
                    "  vec2 texel = uInvDstSize;\n" +
                    "  vec2 uv0 = vUv;\n" +
                    "  vec3 c  = texture(uUpscaled, uv0).rgb;\n" +
                    "  vec3 rx = texture(uUpscaled, uv0 + vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 lx = texture(uUpscaled, uv0 - vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 ty = texture(uUpscaled, uv0 + vec2(0.0, texel.y)).rgb;\n" +
                    "  vec3 by = texture(uUpscaled, uv0 - vec2(0.0, texel.y)).rgb;\n" +
                    "#endif\n" +
                    "\n" +
                    "  float sharp = clamp(uSharp, 0.0, 1.0);\n" +
                    "  if (sharp <= 0.0001) {\n" +
                    "    fragColor = vec4(c, 1.0);\n" +
                    "    return;\n" +
                    "  }\n" +
                    "\n" +
                    "#ifdef RCAS_LUMA_ONLY\n" +
                    "  // HDR path: keep hue-preserving luma-only sharpening, but make the limiter center-safe.\n" +
                    "  float lc  = luma(c);\n" +
                    "  float lrx = luma(rx);\n" +
                    "  float llx = luma(lx);\n" +
                    "  float lty = luma(ty);\n" +
                    "  float lby = luma(by);\n" +
                    "\n" +
                    "  float blur4  = 0.25 * (lrx + llx + lty + lby);\n" +
                    "  float detail = lc - blur4;\n" +
                    "  float edgeX = lrx - llx;\n" +
                    "  float edgeY = lty - lby;\n" +
                    "  float edgeW = 1.0 / (1.0 + 8.0 * (edgeX * edgeX + edgeY * edgeY));\n" +
                    "  float outL = lc + detail * (1.35 * sharp * edgeW);\n" +
                    "\n" +
                    "  // Include the center in the limiter so isolated fine detail is never crushed.\n" +
                    "  float lo = min(lc, min(min(llx, lrx), min(lty, lby)));\n" +
                    "  float hi = max(lc, max(max(llx, lrx), max(lty, lby)));\n" +
                    "  float pad = 0.01 + 0.04 * sharp;\n" +
                    "  outL = clamp(outL, lo - pad, hi + pad);\n" +
                    "\n" +
                    "  float scale = (lc > 1e-5) ? (outL / lc) : 1.0;\n" +
                    "  fragColor = vec4(clamp(c * scale, 0.0, 1.0), 1.0);\n" +
                    "#else\n" +
                    "  // Reference-style RCAS limiter. Unlike the previous unsharp-mask path, this\n" +
                    "  // derives a safe negative lobe from the local range instead of clamping the\n" +
                    "  // final center pixel to the four-neighbor range.\n" +
                    "  vec3 mn4 = min(min(lx, rx), min(ty, by));\n" +
                    "  vec3 mx4 = max(max(lx, rx), max(ty, by));\n" +
                    "\n" +
                    "  vec3 hitMin = min(mn4, c) / max(4.0 * mx4, vec3(1e-6));\n" +
                    "  vec3 denMax = min(4.0 * mn4 - vec3(4.0), vec3(-1e-6));\n" +
                    "  vec3 hitMax = (vec3(1.0) - max(mx4, c)) / denMax;\n" +
                    "  vec3 lobeRGB = max(-hitMin, hitMax);\n" +
                    "\n" +
                    "  const float RCAS_LIMIT = 0.1875; // 0.25 - 1/16, AMD FSR1 limit.\n" +
                    "  float lobe = max(-RCAS_LIMIT,\n" +
                    "                   min(max(max(lobeRGB.r, lobeRGB.g), lobeRGB.b), 0.0));\n" +
                    "  lobe *= sharp;\n" +
                    "\n" +
                    "  float rcpL = 1.0 / max(4.0 * lobe + 1.0, 1e-5);\n" +
                    "  vec3 outc = (c + lobe * (lx + rx + ty + by)) * rcpL;\n" +
                    "  fragColor = vec4(clamp(outc, 0.0, 1.0), 1.0);\n" +
                    "#endif\n" +
                    "}\n" ;
    // Mali-friendly RCAS (FAST): 3-tap horizontal variant (center + left/right).
    // Used only on Mali and only for preset=Performance to reduce bandwidth/texture fetch cost.
    private static final String FS_RCAS_FAST =
            "#version 300 es\n" +
                    "precision highp float;\n" +
                    "precision highp samplerExternalOES;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform sampler2D uUpscaled;\n" +
                    "uniform vec2  uInvDstSize;\n" +
                    "uniform float uSharp;\n" +
                    "uniform vec3  uLumaCoeffs;\n" +
                    "float luma(vec3 c){ return dot(c, uLumaCoeffs); }\n" +
                    "void main(){\n" +
                    "  vec2 texel = uInvDstSize;\n" +
                    "  vec2 uv0 = vUv;\n" +
                    "  vec3 c  = texture(uUpscaled, uv0).rgb;\n" +
                    "  vec3 rx = texture(uUpscaled, uv0 + vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 lx = texture(uUpscaled, uv0 - vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 blur2 = 0.5*(rx + lx);\n" +
                    "  vec3 detail = c - blur2;\n" +
                    "  vec3 sgn = sign(detail);\n" +
                    "  detail = max(abs(detail) - vec3(1.0/255.0), vec3(0.0)) * sgn;\n" +
                    "  float gx = luma(rx) - luma(lx);\n" +
                    "  float edgeW = 1.0 / (1.0 + 8.0*(gx*gx));\n" +
                    "  float k = 1.8 * clamp(uSharp, 0.0, 1.0);\n" +
                    "  vec3 outc = clamp(c + detail * (k*edgeW), 0.0, 1.0);\n" +
                    "  vec3 lo = min(min(lx,rx), c);\n" +
                    "  vec3 hi = max(max(lx,rx), c);\n" +
                    "  float pad = 0.012 + 0.06*clamp(uSharp,0.0,1.0);\n" +
                    "  outc = clamp(outc, lo - vec3(pad), hi + vec3(pad));\n" +
                    "  fragColor = vec4(outc, 1.0);\n" +
                    "}\n";


    // ===== FSR telemetry controls =====
    public void setFsrDebugEnabled(boolean enabled) { __fsr.enabled = enabled; }
    public String getFsrOverlayLine() { return __fsrOverlay; }

    // Optional: presentation size hint API
    public void setPresentationSizeHint(int w, int h) {
        hintOutW = Math.max(0, w);
        hintOutH = Math.max(0, h);

        // Ensure we render at least once with the new target even if no new frame arrives.
        sizeChangedSinceLastSwap = true;
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    public void setPresentationSizeHintFromDisplay(android.view.Display display) {
        if (display == null) return;
        try {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            display.getRealMetrics(dm);
            setPresentationSizeHint(dm.widthPixels, dm.heightPixels);
        } catch (Throwable ignored) {}
    }
    public void setPresentationSizeHintFromContext(android.content.Context ctx) {
        if (ctx == null) return;
        int w = 0, h = 0;

        try {
            // WindowManager access (API 23+ with fallback)
            android.view.WindowManager wm = null;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                wm = ctx.getSystemService(android.view.WindowManager.class);
            } else {
                wm = (android.view.WindowManager) ctx.getSystemService(android.content.Context.WINDOW_SERVICE);
            }

            if (wm != null) {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    // API 30+: use WindowMetrics
                    try {
                        android.view.WindowMetrics m = wm.getMaximumWindowMetrics();
                        android.graphics.Rect b = m.getBounds();
                        if (b != null) {
                            w = Math.max(w, b.width());
                            h = Math.max(h, b.height());
                        }
                    } catch (Throwable ignored) {}
                } else {
                    // Fallback for API < 30
                    android.view.Display d = wm.getDefaultDisplay();
                    if (d != null) {
                        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                        if (android.os.Build.VERSION.SDK_INT >= 17) {
                            d.getRealMetrics(dm);
                        } else {
                            d.getMetrics(dm);
                        }
                        w = Math.max(w, dm.widthPixels);
                        h = Math.max(h, dm.heightPixels);
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            // DisplayManager fallback
            android.hardware.display.DisplayManager dm = (android.hardware.display.DisplayManager) ctx.getSystemService(android.content.Context.DISPLAY_SERVICE);
            android.view.Display d = (dm != null ? dm.getDisplay(android.view.Display.DEFAULT_DISPLAY) : null);
            if (d != null) {
                android.util.DisplayMetrics dmets = new android.util.DisplayMetrics();
                d.getRealMetrics(dmets);
                w = Math.max(w, dmets.widthPixels);
                h = Math.max(h, dmets.heightPixels);
            }
        } catch (Throwable ignored) {}

        if (w <= 0 || h <= 0) {
            try {
                // Resources fallback
                android.util.DisplayMetrics dmets = ctx.getResources().getDisplayMetrics();
                w = Math.max(w, dmets.widthPixels);
                h = Math.max(h, dmets.heightPixels);
            } catch (Throwable ignored) {}
        }

        if (w > 0 && h > 0) {
            setPresentationSizeHint(w, h);
            try {
                com.limelight.LimeLog.info("FSR: presentation hint (auto) = " + w + "x" + h);
            } catch (Throwable ignored) {}
        }
    }

    // Utility: safely return currently bound framebuffer (0 = default)
    private int getBoundFramebuffer() {
        try {
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, tmpIntArray, 0);
            return tmpIntArray[0];
        } catch (Throwable ignored) {
            return 0;
        }
    }
    // Optimized and safe RCAS_OES health check (low-overhead)
    private void checkRcasOesHealthOnce(int dstW, int dstH) {
        if (rcasOesChecked) return;

        if (progRcasOes == 0) {
            rcasOesHealthy = false;
            rcasOesChecked = true;
            return;
        }

        // Avoid false negatives on the first frames (often black): retry a few times.
        if (rcasOesCheckAttempts >= RCAS_OES_CHECK_MAX_ATTEMPTS) {
            rcasOesChecked = true;
            return;
        }
        rcasOesCheckAttempts++;

        activeTexture0();

        // Save GL state that interacts with your caches
        final int prevFbo = getBoundFramebuffer();

        int prevTex2D = 0;
        try {
            GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, tmpIntArray, 0);
            prevTex2D = tmpIntArray[0];
        } catch (Throwable ignored) {
            prevTex2D = 0;
        }

        final int[] prevViewport = tmpViewportArray;
        try { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0); } catch (Throwable ignored) { }

        int testFbo = 0, testTex = 0;
        boolean ambiguousAllZero = false;

        try {
            // Clear stale errors before probing
            clearGlErrors();

            GLES20.glGenFramebuffers(1, tmpIntArray, 0);
            testFbo = tmpIntArray[0];

            GLES20.glGenTextures(1, tmpIntArray, 0);
            testTex = tmpIntArray[0];

            // Bind test 2D texture via cache-aware helper (keeps lastTex2D coherent)
            bindTex2DCached(testTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

            // Bind test FBO via cache-aware helper (keeps lastFbo coherent)
            bindFramebufferCached(testFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, testTex, 0);

            if (!isFboComplete()) {
                rcasOesHealthy = false;
                rcasOesChecked = true;
                return;
            }

            GLES20.glViewport(0, 0, 2, 2);

            UseProgram(progRcasOes);
            bindQuad(progRcasOes);

            // Use direct uniforms (runs once)
            GLES20.glUniform2f(rcasOes_uInvDst, 1.0f / Math.max(1, dstW), 1.0f / Math.max(1, dstH));
            GLES20.glUniform1f(rcasOes_uSharp, mapUiSharpToInternal(0.2f, true));
            GLES20.glUniformMatrix4fv(rcasOes_uTexMat, 1, false, texMatrix, 0);
            setRcasLumaUniform(rcasOes_uLuma, false);

            if (lastTexture != oesTexId) {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
                lastTexture = oesTexId;
            }

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            ByteBuffer bb = testPixelBuffer;
            bb.clear();
            GLES20.glReadPixels(0, 0, 2, 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);

            int sum = 0;
            for (int i = 0; i < 16; i++) sum |= (bb.get(i) & 0xFF);

            ambiguousAllZero = (sum == 0);
            rcasOesHealthy = !ambiguousAllZero;

            if (hasVao) {
                try {
                    GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, invalidateColorAttachment, 0);
                } catch (Throwable ignored) {}
            }

        } finally {
            // Restore viewport
            try { GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]); } catch (Throwable ignored) { }

            // Keep viewport cache coherent
            curVpW = prevViewport[2];
            curVpH = prevViewport[3];

            // Restore previous FBO using cache-aware helper
            bindFramebufferCached(prevFbo);

            // Restore previous 2D texture binding and keep cache coherent
            bindTex2DCached(prevTex2D);

            // Delete test resources
            if (testTex != 0) {
                tmpIntArray[0] = testTex;
                try { GLES20.glDeleteTextures(1, tmpIntArray, 0); } catch (Throwable ignored) {}
            }
            if (testFbo != 0) {
                tmpIntArray[0] = testFbo;
                try { GLES20.glDeleteFramebuffers(1, tmpIntArray, 0); } catch (Throwable ignored) {}
            }
        }

        // If the frame was likely black, retry later (bounded attempts).
        if (ambiguousAllZero && rcasOesCheckAttempts < RCAS_OES_CHECK_MAX_ATTEMPTS) {
            rcasOesChecked = false;
            rcasOesHealthy = false;
            return;
        }

        rcasOesChecked = true;

        // Force next uniform uploads for OES path (safe)
        lastRcasOesInvDstW = lastRcasOesInvDstH = -1;
        lastRcasOesSharp = -1f;
        lastRcasOesTexMatSerial = -1L;

        LimeLog.info("RCAS_OES health=" + rcasOesHealthy);
    }

    // Cheap check used per-frame (supports hot-reload) to allow the ultra-thin path.
    // True when GPU direct path is forced or FSR is logically disabled.
    private static boolean computeFastBypassStatic(PreferenceConfiguration prefs) {
        if (prefs == null) return false;
        if (prefs.gpuPathMode) return true;
        if (!prefs.videoUpscaleEnable) return true;

        final String mode = prefs.videoUpscaleMode;
        // FSR bypass
        return (mode == null || "none".equals(mode));

    }

    @androidx.annotation.Keep
    @SuppressWarnings("unused") // called via reflection from MediaCodecDecoderRenderer
    public void applyVsyncSetting() {
        final boolean wantVsync = (prefs != null && prefs.enableVsync);

        // Balanced pacing already runs a Choreographer loop in MediaCodecDecoderRenderer.
        // Avoid a second independent Choreographer loop here.
        final boolean balancedPacing =
                (prefs != null && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED);

        final boolean wantChoreoBackend = (wantVsync && !balancedPacing);

        // Do NOT flip backend flags in-place while running.
        // Switching backend requires a controlled stop/start; otherwise we can stall rendering.
        if (running.get() && (wantChoreoBackend != useChoreoVsync)) {
            requestBackendRestartAsync();
            return;
        }

        // Safe when not running (start() will recompute anyway).
        useChoreoVsync = wantChoreoBackend;

        applyGlThreadPriorityNow();

        // Wake render thread (useful for immediate redraw)
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    private void UseProgram(int program) {
        if (lastProgram != program) {
            GLES20.glUseProgram(program);
            lastProgram = program;
        }
    }
    private void activeTexture0() {
        if (activeTexUnit != 0) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            activeTexUnit = 0;
        }
    }

    private void bindFramebufferCached(int fboId) {
        if (lastFbo != fboId) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
            lastFbo = fboId;
        }
    }

    // Hint the driver that we fully overwrite the default framebuffer (avoid backbuffer LOADs vs glClear()).
    private void invalidateDefaultFramebufferColor() {
        try {
            GLES30.glInvalidateFramebuffer(GLES30.GL_FRAMEBUFFER, 1, invalidateDefaultFbAttachments, 0);
        } catch (Throwable ignored) { }
    }

    private void bindTex2DCached(int texId) {

        // This cache is defined for texture unit 0 only.
        activeTexture0();
        if (lastTex2D != texId) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
            lastTex2D = texId;
        }
    }
    // Reattach SurfaceTexture if EGL context changed (prevents updateTexImage() 0x502 on context switches)
    private boolean ensureSurfaceTextureAttached() {
        if (decoderSurfaceTex == null || oesTexId == 0) return false;

        // renderFrame() already ensures EGL current; avoid per-frame eglGetCurrentContext() JNI.
        long curH = eglContextHandle;
        if (curH == 0L) {
            curH = safeEglContextHandle(eglContext);
            eglContextHandle = curH;
        }
        if (curH == 0L) return false;

        if (attachedEglContextHandle == curH) return true;

        try { decoderSurfaceTex.detachFromGLContext(); } catch (Throwable ignored) { }

        try {
            decoderSurfaceTex.attachToGLContext(oesTexId);
            attachedEglContextHandle = curH;

            // Force a clean re-bind path after attach.
            lastTexture = -1;
            lastProgram = -1;

            markGlErrorDirty();
            return true;
        } catch (Throwable t) {
            attachedEglContextHandle = 0L;
            LimeLog.warning("FSR: SurfaceTexture attachToGLContext failed: " + t);
            markGlErrorDirty();
            return false;
        }
    }


    private void clearGlErrors() {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            // Do not log, just clear
        }
    }

    private void checkGlErrorQuiet(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            // Only log non-0x502 errors to avoid spam
            if (error != 0x502) {
                LimeLog.warning(op + ": glError 0x" + Integer.toHexString(error));
            }
        }
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!running.get() || !useChoreoVsync) return;

            // Adjust by app vsync offset (cached; refresh occasionally) to match MediaCodecDecoderRenderer phase behavior.
            long adjustedFrameTimeNs = frameTimeNanos;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                final long nowNsLocal = System.nanoTime();
                if (cachedAppVsyncOffsetNs == 0L ||
                        (nowNsLocal - lastAppVsyncOffsetQueryNs) >= APP_VSYNC_OFFSET_QUERY_INTERVAL_NS) {
                    lastAppVsyncOffsetQueryNs = nowNsLocal;
                    try {
                        final android.content.Context ctx = appContext;
                        android.view.WindowManager wm = null;
                        if (ctx != null) {
                            if (android.os.Build.VERSION.SDK_INT >= 23) {
                                wm = ctx.getSystemService(android.view.WindowManager.class);
                            } else {
                                wm = (android.view.WindowManager) ctx.getSystemService(android.content.Context.WINDOW_SERVICE);
                            }
                        }
                        if (wm != null && wm.getDefaultDisplay() != null) {
                            cachedAppVsyncOffsetNs = wm.getDefaultDisplay().getAppVsyncOffsetNanos();
                        } else {
                            cachedAppVsyncOffsetNs = 0L;
                        }
                    } catch (Throwable ignored) {
                        cachedAppVsyncOffsetNs = 0L;
                    }
                }
                adjustedFrameTimeNs = frameTimeNanos - cachedAppVsyncOffsetNs;
            }

            // Keep the adjusted timestamp to drive eglPresentationTimeANDROID for tighter SurfaceFlinger phase alignment.
            final long prevFrameTime = lastChoreoFrameTimeNs;

            // Guard: ignore duplicate timestamps (rare but possible on some stacks)
            if (prevFrameTime == adjustedFrameTimeNs) {
                if (running.get() && useChoreoVsync) {
                    Choreographer.getInstance().postFrameCallback(this);
                }
                return;
            }

            lastChoreoFrameTimeNs = adjustedFrameTimeNs;

            // Update EWMA interval (filter discontinuities)
            if (prevFrameTime != 0L) {
                final long interval = adjustedFrameTimeNs - prevFrameTime;
                if (interval >= CHOREO_INTERVAL_MIN_NS && interval <= CHOREO_INTERVAL_MAX_NS) {
                    // EWMA alpha=0.2: new = 0.8*old + 0.2*interval
                    choreoFrameIntervalNs = (choreoFrameIntervalNs * 4 + interval) / 5;
                } else {
                    // Large discontinuity (resume/background/jank). Reset to default.
                    choreoFrameIntervalNs = CHOREO_INTERVAL_DEFAULT_NS;
                }
            } else {
                choreoFrameIntervalNs = CHOREO_INTERVAL_DEFAULT_NS;
            }

            // Conservative adaptive drain budget (kept), derived from measured Choreographer interval.
            choreoDrainBudget = calculateAdaptiveDrainConservative(choreoFrameIntervalNs);

            try {
                // GL-VSync pacing:
                // - Tick on every display vsync (Choreographer is the gate).
                // - renderFrame(true) provides a tiny late-latch wait to catch frames arriving just after vsync.
                // - Backlog control is handled in renderFrame() by the drain budgeting.
                renderFrame(true);
            } catch (Throwable t) {
                LimeLog.warning("renderFrame error: " + t);
            }

            if (running.get() && useChoreoVsync) {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };


    private int calculateAdaptiveDrainConservative(long estimatedIntervalNs) {
        // 120/90Hz: keep updateTexImage loops minimal to stabilize frame time.
        if (estimatedIntervalNs <= 14_000_000L) {
            return 1;
        }
        // 60Hz-ish: allow at most 2 (still capped by CHOREO_MAX_DRAIN_UPDATETEXIMAGE).
        return 2;
    }

    private static long safeEglContextHandle(EGLContext ctx) {
        if (ctx == null) return 0L;
        try {
            return ctx.getNativeHandle();
        } catch (Throwable ignored) {
            try {
                return (long) ctx.getHandle();
            } catch (Throwable ignored2) {
                return 0L;
            }
        }
    }

    private static long safeEglSurfaceHandle(EGLSurface s) {
        if (s == null) return 0L;
        try {
            return s.getNativeHandle();
        } catch (Throwable ignored) {
            try {
                return (long) s.getHandle();
            } catch (Throwable ignored2) {
                return 0L;
            }
        }
    }


    private boolean ensureEglCurrent() {
        if (!isGlReady()) return false;

        if (eglContextHandle == 0L) {
            eglContextHandle = safeEglContextHandle(eglContext);
        }
        if (eglWindowSurfaceHandle == 0L) {
            eglWindowSurfaceHandle = safeEglSurfaceHandle(eglWindowSurface);
        }

        final long curCtxH = safeEglContextHandle(EGL14.eglGetCurrentContext());
        final long curDrawH = safeEglSurfaceHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
        final long curReadH = safeEglSurfaceHandle(EGL14.eglGetCurrentSurface(EGL14.EGL_READ));

        if (curCtxH == eglContextHandle && curDrawH == eglWindowSurfaceHandle && curReadH == eglWindowSurfaceHandle) {
            return true;
        }

        final boolean ok = EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext);
        if (!ok) {
            final int err = EGL14.eglGetError();
            LimeLog.warning("FSR: eglMakeCurrent failed err=0x" + Integer.toHexString(err));
            return false;
        }

        // After binding on this thread, enforce non-blocking swap (best effort).
        swapIntervalZeroApplied = false;
        ensureSwapIntervalZero();
        return true;
    }


    private void releaseEglCurrent() {
        if (eglDisplay == null || eglDisplay == EGL14.EGL_NO_DISPLAY) return;

        try {
            if (eglContextHandle == 0L) {
                eglContextHandle = safeEglContextHandle(eglContext);
            }

            final long curCtxH = safeEglContextHandle(EGL14.eglGetCurrentContext());
            if (curCtxH != 0L && curCtxH == eglContextHandle) {
                EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                try { EGL14.eglReleaseThread(); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

}