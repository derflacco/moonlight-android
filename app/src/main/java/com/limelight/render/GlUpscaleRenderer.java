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
import android.view.Surface;
import android.view.Display;
import android.hardware.display.DisplayManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.graphics.Rect;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

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
    // RCAS_OES health-check state
    private boolean rcasOesChecked = false;
    private boolean rcasOesHealthy = false;


    // ===== FSR Telemetry (lightweight) =====
    private static final class FsrTelemetry {
        boolean enabled = false;
        long frames = 0L;
        // EWMA in ns
        private double easuAvgNs = 0.0;
        private double rcasAvgNs = 0.0;
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

        private long tEasu = 0L, tRcas = 0L;
        void ticEasu() { tEasu = now(); }
        void tocEasu() { easuAvgNs = ewma(easuAvgNs, now() - tEasu); }
        void ticRcas() { tRcas = now(); }
        void tocRcas() { rcasAvgNs = ewma(rcasAvgNs, now() - tRcas); }

        String overlayLine() {
            if ("EASU+RCAS".equals(mode)) {
                return String.format(java.util.Locale.US,
                        "FSR %s | sharp=%.2f | total=%.2fms",
                        mode, sharp, (easuAvgNs>0?easuAvgNs:rcasAvgNs)/1e6);
            } else if ("RCAS_ONLY".equals(mode)) {
                return String.format(java.util.Locale.US,
                        "FSR %s | sharp=%.2f | rcas=%.2fms",
                        mode, sharp, rcasAvgNs/1e6);
            } else {
                return "FSR BYPASS";
            }
        }
        String periodicLine() {
            return String.format(java.util.Locale.US,
                    "FSR[%s] %dx%d -> %dx%d | sharp=%.2f | %s | EASU(avg)=%.2fms RCAS(avg)=%.2fms%s",
                    mode, srcW, srcH, dstW, dstH, sharp, sampling,
                    easuAvgNs/1e6, rcasAvgNs/1e6,
                    (notes==null || notes.isEmpty()) ? "" : (" | " + notes));
        }
    }

    private final FsrTelemetry __fsr = new FsrTelemetry();
    private volatile String __fsrOverlay = "";
    private final Surface windowSurfaceInput;
    private final int srcW, srcH;
    private final PreferenceConfiguration prefs;

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

    // Programs
    private int progVs = 0, progBlit = 0, progEasu = 0, progRcas = 0;

    // Uniform locations
    private int blit_uTex = -1, blit_uTexMat = -1;
    private int easu_uTex = -1, easu_uInvSrcSize = -1, easu_uTexMat = -1;
    private int rcas_uTex = -1, rcas_uInvDst = -1, rcas_uSharp = -1;
    private int progRcasOes = 0, rcasOes_uTex = -1, rcasOes_uInvDst = -1, rcasOes_uSharp = -1, rcasOes_uTexMat = -1;

    // Quad buffers (no VAO)
    private int vboPos = 0, vboUv = 0;
    private FloatBuffer quadPos, quadUv;

    // SurfaceTexture transform
    private final float[] texMatrix = new float[16];

    // Presentation size hint (display-sized buffer), if known
    private volatile int hintOutW = 0, hintOutH = 0;

    // Performance optimizations
    private final int[] tmpIntArray = new int[1]; // Reusable int array

    // ===== Performance state =====
    private int vao = 0;
    private boolean hasVao = false;
    private long lastSizeQueryNs = 0L;
    private static final long SIZE_QUERY_NS = 400_000_000L; // ~0.4s
    private int swapFailStreak = 0;
    private boolean fixedStateApplied = false;

    // Threading
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread renderThread;
    private final Object frameLock = new Object();
    private boolean frameAvailable = false;

    // State cache
    private int curVpW = -1, curVpH = -1;
    private boolean twoDNearest = false, oesNearest = false;

    // Track if render surface size changed since last swap
    private boolean sizeChangedSinceLastSwap = true;

    public GlUpscaleRenderer(android.content.Context context, Surface windowSurface, int srcW, int srcH, PreferenceConfiguration prefs) {
        this(windowSurface, srcW, srcH, prefs);
        try { setPresentationSizeHintFromContext(context); } catch (Throwable ignored) {}
    }

    public GlUpscaleRenderer(Surface windowSurface, int srcW, int srcH, PreferenceConfiguration prefs) {
        this.windowSurfaceInput = windowSurface;
        this.srcW = Math.max(1, srcW);
        this.srcH = Math.max(1, srcH);
        this.prefs = prefs;
        initEglAndGl();
    }

    public Surface createDecoderInputSurface() {
        if (!isGlReady()) return null;
        if (decoderInputSurface != null) return decoderInputSurface;

        GLES20.glGenTextures(1, tmpIntArray, 0);
        oesTexId = tmpIntArray[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        decoderSurfaceTex = new SurfaceTexture(oesTexId);
        try {
            decoderSurfaceTex.setDefaultBufferSize(srcW, srcH);
        } catch (Throwable ignored) {}

        decoderSurfaceTex.setOnFrameAvailableListener(this);
        decoderInputSurface = new Surface(decoderSurfaceTex);
        return decoderInputSurface;
    }

    public void start() {
        if (!isGlReady() || running.getAndSet(true)) return;
        renderThread = new Thread(this::renderLoop, "GL-FSR1-Renderer");
        renderThread.setPriority(Thread.NORM_PRIORITY + 1);
        renderThread.start();
    }

    public void stop() {
        running.set(false);
        if (renderThread != null) {
            try { renderThread.join(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            renderThread = null;
        }
    }

    public void release() {
        stop();
        try {
            if (decoderSurfaceTex != null) {
                decoderSurfaceTex.setOnFrameAvailableListener(null);
                decoderSurfaceTex.release();
                decoderSurfaceTex = null;
            }
            if (decoderInputSurface != null) {
                decoderInputSurface.release();
                decoderInputSurface = null;
            }
        } catch (Throwable ignored) {}

        destroyGl();
        destroyEgl();
    }

    @Override public void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameLock) {
            frameAvailable = true;
            frameLock.notifyAll();
        }
    }

    // ====== Main render loop ======
    private void renderLoop() {
        while (running.get()) {
            boolean newFrameAvailable = false;
            synchronized (frameLock) {
                if (!frameAvailable) {
                    try { frameLock.wait(33); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                newFrameAvailable = frameAvailable;
                frameAvailable = false;
            }

            if (!isGlReady()) continue;
            if (EGL14.eglGetCurrentContext() != eglContext ||
                    EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != eglWindowSurface) {
                EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext);
            }
            if (!fixedStateApplied) { applyFixedState(); }

            boolean didUpdateTex = false;
            try {
                if (decoderSurfaceTex != null && newFrameAvailable) {
                    decoderSurfaceTex.updateTexImage();
                    decoderSurfaceTex.getTransformMatrix(texMatrix);
                    didUpdateTex = true;
                }
            } catch (Throwable t) { /* ignore */ }

            long __now = System.nanoTime();
            if (fbW <= 0 || (__now - lastSizeQueryNs) >= SIZE_QUERY_NS) {
                int oldFbW = fbW, oldFbH = fbH;
                refreshWindowSize();
                if (fbW != oldFbW || fbH != oldFbH) {
                    sizeChangedSinceLastSwap = true;
                }
                lastSizeQueryNs = __now;
            }
            if (fbW <= 0 || fbH <= 0) continue;

            // Skip draw if no new frame and size unchanged (saves GPU cycles)
            if (!didUpdateTex && !sizeChangedSinceLastSwap) {
                continue;
            }

            ensureViewport(fbW, fbH);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            final boolean upscaleEnabled = (prefs != null && prefs.videoUpscaleEnable);
            final String mode = (prefs != null ? prefs.videoUpscaleMode : "rcas");
            final boolean modeNone = "none".equals(mode);
            final boolean modeRcasOnly = "rcas".equals(mode);
            final boolean modeEasuRcas = "easu_rcas".equals(mode);
            final float sharpUser = (prefs != null ? clamp01(prefs.videoUpscaleSharpness / 100f) : 0.35f);

            final int dstTargetW = (hintOutW > 0 ? hintOutW : fbW);
            final int dstTargetH = (hintOutH > 0 ? hintOutH : fbH);
            float scaleX = (float) dstTargetW / (float) srcW;
            float scaleY = (float) dstTargetH / (float) srcH;
            boolean nearNative = Math.abs(Math.min(scaleX, scaleY) - 1.0f) < 0.05f;
            final boolean canUpscaleNow = (fbW != srcW || fbH != srcH);

            // === FSR path selection + telemetry ===
            final float nearThr = 0.05f;

            if (!upscaleEnabled || modeNone) {
                // BYPASS
                if (__fsr.enabled) {
                    __fsr.mode = "BYPASS";
                    __fsr.srcW = srcW; __fsr.srcH = srcH;
                    __fsr.dstW = fbW; __fsr.dstH = fbH;
                    __fsr.sharp = 0f;
                    __fsr.sampling = "bypass";
                    __fsr.notes = "reason=" + (modeNone ? "bypass:mode_none" : "bypass:upscaleDisabled") + " | win=" + fbW + "x" + fbH + " hint=" + dstTargetW + "x" + dstTargetH;
                    __fsrOverlay = __fsr.overlayLine();
                }
                drawOesToScreen();
            } else if (modeEasuRcas && progEasu != 0 && !nearNative) {
                boolean ok;
                ok = drawEasuRcasSafe(fbW, fbH, mapUiSharpToInternal(sharpUser, nearNative));
                if (__fsr.enabled) {
                    __fsr.mode = "EASU+RCAS";
                    __fsr.srcW = srcW; __fsr.srcH = srcH;
                    __fsr.dstW = fbW; __fsr.dstH = fbH;
                    __fsr.sharp = sharpUser;
                    __fsr.sampling = "OES->2D LINEAR + RCAS_2D";
                    __fsr.notes = (ok ? "reason=easu_rcas" : "fallback:easu_rcas_failed")
                            + " | nearNative=" + nearNative + " thr=" + String.format(java.util.Locale.US, "%.2f", nearThr);
                    __fsr.frames++;
                    if ((__fsr.frames % 240L) == 0L) {
                        com.limelight.LimeLog.info(__fsr.periodicLine());
                    }
                    __fsrOverlay = __fsr.overlayLine();
                }
                if (!ok) {
                    drawOesToScreen();
                }
            } else {
                boolean ok;
                long t0 = System.nanoTime();
                float effSharp = mapUiSharpToInternal(sharpUser, nearNative);
                ok = drawRcasOnlySafe(fbW, fbH, effSharp);
                long dt = System.nanoTime() - t0;
                if (__fsr.enabled) {
                    __fsr.mode = "RCAS_ONLY";
                    __fsr.srcW = srcW; __fsr.srcH = srcH;
                    __fsr.dstW = fbW; __fsr.dstH = fbH;
                    __fsr.sharp = effSharp;
                    __fsr.sampling = "OES->2D LINEAR + RCAS_2D";
                    String reason;
                    if (nearNative) reason = "reason=nearNative";
                    else if (srcW == fbW && srcH == fbH) reason = "reason=dstEqSrc";
                    else if (modeRcasOnly) reason = "reason=easu_disabled";
                    else reason = "reason=mode!=easu_rcas";
                    __fsr.notes = (ok ? reason : ("fallback:rcas_only_failed")) +
                            " | nearNative=" + nearNative + " thr=" + String.format(java.util.Locale.US, "%.2f", nearThr);
                    __fsr.rcasAvgNs = (__fsr.rcasAvgNs==0.0) ? dt : (0.2*dt + 0.8*__fsr.rcasAvgNs);
                    __fsr.frames++;
                    if ((__fsr.frames % 240L) == 0L) {
                        com.limelight.LimeLog.info(__fsr.periodicLine());
                    }
                    __fsrOverlay = __fsr.overlayLine();
                }
                if (!ok) {
                    drawOesToScreen();
                }
            }
            try { EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindowSurface, System.nanoTime()); } catch (Throwable ignored) {}
            boolean __swapped = EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface);
            if (!__swapped) {
                int err = EGL14.eglGetError();
                try { com.limelight.LimeLog.warning("FSR: eglSwapBuffers failed err=0x" + Integer.toHexString(err) + " (streak=" + swapFailStreak + ")"); } catch (Throwable ignored) {}
                swapFailStreak++;
                if (windowSurfaceInput == null || !windowSurfaceInput.isValid() || swapFailStreak >= 8) {
                    running.set(false);
                }
                continue;
            } else {
                if (swapFailStreak != 0) swapFailStreak = 0;
                sizeChangedSinceLastSwap = false;
            }
        }
    }

    // ====== Draw operations ======
    private void drawOesToScreen() {
        GLES20.glUseProgram(progBlit);
        bindQuad(progBlit);

        GLES20.glUniformMatrix4fv(blit_uTexMat, 1, false, texMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        setOesFilter(false);
        GLES20.glUniform1i(blit_uTex, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}
    }

    private boolean drawRcasOnlySafe(int dstW, int dstH, float sharp) {
        checkRcasOesHealthOnce(dstW, dstH);
        // Prefer direct OES sharpening when program is available
        if (progRcasOes != 0 && rcasOesHealthy) {
            if (__fsr.enabled) { __fsr.sampling = "RCAS_OES"; }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            ensureViewport(dstW, dstH);
            GLES20.glUseProgram(progRcasOes);
            bindQuad(progRcasOes);

            GLES20.glUniform2f(rcasOes_uInvDst, 1.0f / dstW, 1.0f / dstH);
            GLES20.glUniform1f(rcasOes_uSharp, clamp01(sharp));
            GLES20.glUniformMatrix4fv(rcasOes_uTexMat, 1, false, texMatrix, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            setOesFilter( (dstW > srcW || dstH > srcH) ? false : true );
            GLES20.glUniform1i(rcasOes_uTex, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}
            return true;
        }

        // Fallback: OES -> upscaledTex (NEAREST), then RCAS on 2D
        if (!ensureFbo(dstW, dstH)) return false;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, upscaledTex, 0);
        if (!isFboComplete()) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); return false; }

        ensureViewport(dstW, dstH);
        GLES20.glUseProgram(progBlit);
        bindQuad(progBlit);
        GLES20.glUniformMatrix4fv(blit_uTexMat, 1, false, texMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        setOesFilter( (dstW > srcW || dstH > srcH) ? false : true );
        GLES20.glUniform1i(blit_uTex, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) { __fsr.tocEasu(); }
        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}

        // RCAS: upscaledTex -> screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        ensureViewport(dstW, dstH);
        GLES20.glUseProgram(progRcas);
        bindQuad(progRcas);
        if (__fsr.enabled) { __fsr.ticRcas(); }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upscaledTex);
        setTex2DFilter(true);
        GLES20.glUniform1i(rcas_uTex, 0);
        GLES20.glUniform2f(rcas_uInvDst, 1.0f / Math.max(1, dstW), 1.0f / Math.max(1, dstH));
        GLES20.glUniform1f(rcas_uSharp, clamp01(sharp));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) { __fsr.tocRcas(); }

        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}
        return true;
    }

    private boolean drawEasuRcasSafe(int dstW, int dstH, float sharp) {
        if (__fsr.enabled) { __fsr.sampling = "OES->2D LINEAR + RCAS_2D"; }
        if (!ensureFbo(dstW, dstH)) return false;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, upscaledTex, 0);
        if (!isFboComplete()) { GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); return false; }

        ensureViewport(dstW, dstH);
        // EASU (lite)
        GLES20.glUseProgram(progEasu);
        bindQuad(progEasu);
        if (__fsr.enabled) { __fsr.ticEasu(); }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        setOesFilter(false);

        GLES20.glUniform1i(easu_uTex, 0);
        GLES20.glUniform2f(easu_uInvSrcSize, 1.0f / srcW, 1.0f / srcH);
        GLES20.glUniformMatrix4fv(easu_uTexMat, 1, false, texMatrix, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (__fsr.enabled) { __fsr.tocEasu(); }
        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}

        // RCAS → screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        ensureViewport(dstW, dstH);
        GLES20.glUseProgram(progRcas);
        bindQuad(progRcas);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upscaledTex);
        setTex2DFilter(true);
        GLES20.glUniform1i(rcas_uTex, 0);
        GLES20.glUniform2f(rcas_uInvDst, 1.0f / dstW, 1.0f / dstH);
        GLES20.glUniform1f(rcas_uSharp, clamp01(sharp));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        setTex2DFilter(false);

        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}
        return true;
    }

    // ====== GL setup ======
    private boolean isGlReady() {
        return eglDisplay != EGL14.EGL_NO_DISPLAY &&
                eglContext != EGL14.EGL_NO_CONTEXT &&
                eglWindowSurface != EGL14.EGL_NO_SURFACE;
    }

    private void refreshWindowSize() {
        if (!isGlReady()) return;
        int w = 0, h = 0;
        try {
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_WIDTH,  tmpIntArray, 0);  w = tmpIntArray[0];
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_HEIGHT, tmpIntArray, 0); h = tmpIntArray[0];
        } catch (Throwable ignored) {}
        if (w > 0 && h > 0 && (w != fbW || h != fbH)) {
            createOrResizeFbo(w, h);
        }

        if (w == srcW && h == srcH && (hintOutW > srcW || hintOutH > srcH)) {
            try { com.limelight.LimeLog.warning("FSR: window surface == source ("+w+"x"+h+"), but presentation hint is " + hintOutW + "x" + hintOutH +
                    ". Upscale will be bypassed. Use a display-sized Surface (TextureView.setDefaultBufferSize or SurfaceHolder.setFixedSize)."); } catch (Throwable ignored) {}
        }
    }

    private void ensureViewport(int w, int h) {
        if (w != curVpW || h != curVpH) {
            GLES20.glViewport(0, 0, w, h);
            curVpW = w; curVpH = h;
        }
    }

    private boolean ensureFbo(int w, int h) {
        if (w == fbW && h == fbH && upscaledTex != 0 && fbo != 0) return true;
        createOrResizeFbo(w, h);
        return (upscaledTex != 0 && fbo != 0);
    }

    private void createOrResizeFbo(int w, int h) {
        destroyFbo();
        fbW = w; fbH = h; curVpW = -1; curVpH = -1;

        GLES20.glGenFramebuffers(1, tmpIntArray, 0); fbo = tmpIntArray[0];

        GLES20.glGenTextures(1, tmpIntArray, 0); upscaledTex = tmpIntArray[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upscaledTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

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
        if (upscaledTex != 0) { tmpIntArray[0] = upscaledTex; GLES20.glDeleteTextures(1, tmpIntArray, 0); upscaledTex = 0; }
        if (fbo != 0)        { tmpIntArray[0] = fbo;        GLES20.glDeleteFramebuffers(1, tmpIntArray, 0); fbo = 0; }
    }

    private void initEglAndGl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) throw new RuntimeException("eglInitialize failed");
            int[] cfg = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT | 4 /* ES3 */,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] out = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, cfg, 0, out, 0, 1, num, 0)) throw new RuntimeException("eglChooseConfig failed");
            EGLConfig eglConfig = out[0];
            int[] ctx = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctx, 0);
            int[] sattr = {EGL14.EGL_NONE};
            eglWindowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, windowSurfaceInput, sattr, 0);
            EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext);
        } catch (Throwable t) {
            LimeLog.warning("GL init failed: " + t);
            destroyEgl();
            return;
        }

        // Quad data
        float[] POS = {-1,-1, 1,-1, -1,1, 1,1};
        float[] UV  = { 0, 0, 1, 0,  0,1, 1,1};
        quadPos = ByteBuffer.allocateDirect(POS.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadUv  = ByteBuffer.allocateDirect(UV.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadPos.put(POS).position(0);
        quadUv.put(UV).position(0);

        // VBOs
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
        progBlit = linkProgram(progVs, FS_OES_BLIT);
        progEasu = linkProgram(progVs, FS_EASU);
        progRcas = linkProgram(progVs, FS_RCAS);
        // Try to link OES variant (single-pass RCAS) with specialized VS to precompute steps
        try {
            int vsRcasOes = compileShader(GLES20.GL_VERTEX_SHADER, VS_RCAS_OES);
            progRcasOes = linkProgram(vsRcasOes, "#define USE_OES\n#define RCAS_OES_VS\n" + FS_RCAS);
            rcasOes_uTex    = GLES20.glGetUniformLocation(progRcasOes, "uTexOES");
            rcasOes_uInvDst = GLES20.glGetUniformLocation(progRcasOes, "uInvDstSize");
            rcasOes_uSharp  = GLES20.glGetUniformLocation(progRcasOes, "uSharp");
            rcasOes_uTexMat = GLES20.glGetUniformLocation(progRcasOes, "uTexMatrix");
        } catch (Throwable t) {
            progRcasOes = 0; // keep fallback 2D path
        }
        easu_uTex        = GLES20.glGetUniformLocation(progEasu, "uTex");
        easu_uInvSrcSize = GLES20.glGetUniformLocation(progEasu, "uInvSrcSize");
        easu_uTexMat     = GLES20.glGetUniformLocation(progEasu, "uTexMatrix");
        blit_uTex    = GLES20.glGetUniformLocation(progBlit, "uTex");
        blit_uTexMat = GLES20.glGetUniformLocation(progBlit, "uTexMatrix");
        easu_uTex     = GLES20.glGetUniformLocation(progEasu, "uTex");
        rcas_uTex     = GLES20.glGetUniformLocation(progRcas, "uUpscaled");
        rcas_uInvDst  = GLES20.glGetUniformLocation(progRcas, "uInvDstSize");
        rcas_uSharp   = GLES20.glGetUniformLocation(progRcas, "uSharp");
    }

    private void destroyGl() {
        destroyFbo();
        if (hasVao && vao != 0) { int[] vaoId = new int[]{vao}; try { GLES30.glDeleteVertexArrays(1, vaoId, 0); } catch (Throwable ignored) {} vao = 0; hasVao = false; }
        if (vboPos != 0) { tmpIntArray[0] = vboPos; GLES20.glDeleteBuffers(1, tmpIntArray, 0); vboPos = 0; }
        if (vboUv  != 0) { tmpIntArray[0] = vboUv;  GLES20.glDeleteBuffers(1, tmpIntArray, 0); vboUv  = 0; }
        if (progBlit != 0) { GLES20.glDeleteProgram(progBlit); progBlit = 0; }
        if (progEasu != 0) { GLES20.glDeleteProgram(progEasu); progEasu = 0; }
        if (progRcas != 0) { GLES20.glDeleteProgram(progRcas); progRcas = 0; }
    }

    private void destroyEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
            if (eglWindowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglWindowSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        } catch (Throwable ignored) {}
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglWindowSurface = EGL14.EGL_NO_SURFACE;
    }

    private void bindQuad(int prog) {
        if (hasVao && vao != 0) {
            try { GLES30.glBindVertexArray(vao); return; } catch (Throwable ignored) { /* fallback */ }
        }
        int locPos = 0, locUv = 1;
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos);
        GLES20.glEnableVertexAttribArray(locPos);
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv);
        GLES20.glEnableVertexAttribArray(locUv);
        GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 0, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void applyFixedState() {
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        try { GLES20.glDisable(GLES20.GL_DITHER); } catch (Throwable ignored) {}
        try { GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1); } catch (Throwable ignored) {}
        fixedStateApplied = true;
    }

    private void setTex2DFilter(boolean toNearest) {
        if (twoDNearest == toNearest) return;
        twoDNearest = toNearest;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
    }

    private void setOesFilter(boolean toNearest) {
        if (oesNearest == toNearest) return;
        oesNearest = toNearest;
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    private static float mapUiSharpToInternal(float ui, boolean nearNative) {
        float s = clamp01(ui);
        if (s <= 0.05f) return 0f;
        s = (s - 0.05f) / 0.95f;
        s = (float)(1.0 - Math.exp(-3.0 * s));
        float cap = nearNative ? 0.18f : 0.25f;
        return cap * s;
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
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "uniform int uDoGamma; // 0 = no gamma, 1 = gamma 2.2 out\n" +
                    "void main(){\n" +
                    "  vec2 uv=(uTexMatrix*vec4(vUv,0.0,1.0)).xy;\n" +
                    "  vec3 c = texture(uTex, uv).rgb;\n" +
                    "  if (uDoGamma==1) c = pow(clamp(c,0.0,1.0), vec3(1.0/2.2));\n" +
                    "  fragColor = vec4(c, 1.0);\n" +
                    "}";

    // EASU minimal pass (OES -> 2D FBO)
    private static final String FS_EASU =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision highp float;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform vec2 uInvSrcSize;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "float luma(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }\n" +
                    "void main(){\n" +
                    "  vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;\n" +
                    "  vec3 c  = texture(uTex, uv).rgb;\n" +
                    "  vec3 rx = texture(uTex, uv + vec2(uInvSrcSize.x, 0.0)).rgb;\n" +
                    "  vec3 lx = texture(uTex, uv - vec2(uInvSrcSize.x, 0.0)).rgb;\n" +
                    "  vec3 ty = texture(uTex, uv + vec2(0.0, uInvSrcSize.y)).rgb;\n" +
                    "  vec3 by = texture(uTex, uv - vec2(0.0, uInvSrcSize.y)).rgb;\n" +
                    "  float gx = luma(rx) - luma(lx);\n" +
                    "  float gy = luma(ty) - luma(by);\n" +
                    "  float edge = clamp((abs(gx)+abs(gy))*1.5, 0.0, 1.0);\n" +
                    "  vec3 base = c;\n" +
                    "  vec3 alongX = 0.5*(rx+lx);\n" +
                    "  vec3 alongY = 0.5*(ty+by);\n" +
                    "  float wx = smoothstep(0.1, 0.6, abs(gx));\n" +
                    "  float wy = smoothstep(0.1, 0.6, abs(gy));\n" +
                    "  vec3 guided = mix(alongY, alongX, wx/(wx+wy+1e-5));\n" +
                    "  vec3 up = mix(base, guided, 0.35*edge);\n" +
                    "  fragColor = vec4(clamp(up, 0.0, 1.0), 1.0);\n" +
                    "}";

    // RCAS shader with optimized OES path using precomputed varyings
    private static final String FS_RCAS =
            "#version 300 es\n" +
                    "#ifdef USE_OES\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "#endif\n" +
                    "precision mediump float;\n" +
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
                    "\n" +
                    "float luma(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }\n" +
                    "\n" +
                    "void main(){\n" +
                    "#ifdef USE_OES\n" +
                    "  #ifdef RCAS_OES_VS\n" +
                    "    // Use precomputed varyings from vertex shader (optimized path)\n" +
                    "    vec3 c  = texture(uTexOES, vUv0).rgb;\n" +
                    "    vec3 rx = texture(uTexOES, vUv0 + vStepX).rgb;\n" +
                    "    vec3 lx = texture(uTexOES, vUv0 - vStepX).rgb;\n" +
                    "    vec3 ty = texture(uTexOES, vUv0 + vStepY).rgb;\n" +
                    "    vec3 by = texture(uTexOES, vUv0 - vStepY).rgb;\n" +
                    "  #else\n" +
                    "    // Fallback: compute in fragment shader\n" +
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
                    "  // Standard 2D texture path\n" +
                    "  vec2 texel = uInvDstSize;\n" +
                    "  vec2 uv0 = vUv;\n" +
                    "  vec3 c  = texture(uUpscaled, uv0).rgb;\n" +
                    "  vec3 rx = texture(uUpscaled, uv0 + vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 lx = texture(uUpscaled, uv0 - vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 ty = texture(uUpscaled, uv0 + vec2(0.0, texel.y)).rgb;\n" +
                    "  vec3 by = texture(uUpscaled, uv0 - vec2(0.0, texel.y)).rgb;\n" +
                    "#endif\n" +
                    "\n" +
                    "  // 4-tap unsharp mask (cheap)\n" +
                    "  vec3 blur4 = 0.25*(rx + lx + ty + by);\n" +
                    "  vec3 detail = c - blur4;\n" +
                    "  // soft deadzone for noise + clamp envelope anti-halo\n" +
                    "  vec3 sgn = sign(detail);\n" +
                    "  detail = max(abs(detail) - vec3(1.0/255.0), vec3(0.0)) * sgn;\n" +
                    "  \n" +
                    "  float gx = luma(rx) - luma(lx);\n" +
                    "  float gy = luma(ty) - luma(by);\n" +
                    "  float edgeW = 1.0 / (1.0 + 8.0*(gx*gx + gy*gy)); // cheap, no sqrt\n" +
                    "  float k = 1.2 * clamp(uSharp, 0.0, 1.0);        // no pow()\n" +
                    "  \n" +
                    "  vec3 outc = clamp(c + detail * (k*edgeW), 0.0, 1.0);\n" +
                    "  vec3 lo = min(min(min(lx,rx),ty),by);\n" +
                    "  vec3 hi = max(max(max(lx,rx),ty),by);\n" +
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
            android.view.WindowManager wm = (android.view.WindowManager) ctx.getSystemService(android.view.WindowManager.class);
            if (wm != null) {
                try {
                    android.view.WindowMetrics m = wm.getMaximumWindowMetrics();
                    android.graphics.Rect b = m.getBounds();
                    if (b != null) { w = Math.max(w, b.width()); h = Math.max(h, b.height()); }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
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
                android.util.DisplayMetrics dmets = ctx.getResources().getDisplayMetrics();
                w = Math.max(w, dmets.widthPixels);
                h = Math.max(h, dmets.heightPixels);
            } catch (Throwable ignored) {}
        }
        if (w > 0 && h > 0) {
            setPresentationSizeHint(w, h);
            try { com.limelight.LimeLog.info("FSR: presentation hint (auto) = " + w + "x" + h); } catch (Throwable ignored) {}
        }
    }

    // Draw a tiny frame with RCAS_OES into a 2x2 FBO and read back to verify non-zero output.
    private void checkRcasOesHealthOnce(int dstW, int dstH) {
        if (rcasOesChecked) return;
        rcasOesChecked = true;
        if (progRcasOes == 0) return;

        // Save current GL state
        int[] prevViewport = new int[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, prevViewport, 0);
        int[] prevFbo = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, prevFbo, 0);
        int[] prevTex = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, prevTex, 0);
        int[] prevActiveTex = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE, prevActiveTex, 0);

        int testFbo = 0, testTex = 0;
        try {
            // Create test FBO using reusable array
            GLES20.glGenFramebuffers(1, tmpIntArray, 0);
            testFbo = tmpIntArray[0];

            GLES20.glGenTextures(1, tmpIntArray, 0);
            testTex = tmpIntArray[0];

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, testTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0,
                    GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, testFbo);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, testTex, 0);

            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                rcasOesHealthy = false;
                return;
            }

            GLES20.glViewport(0, 0, 2, 2);
            GLES20.glUseProgram(progRcasOes);
            bindQuad(progRcasOes);

            GLES20.glUniform2f(rcasOes_uInvDst, 1.0f / dstW, 1.0f / dstH);
            GLES20.glUniform1f(rcasOes_uSharp, mapUiSharpToInternal(0.2f, true));
            GLES20.glUniformMatrix4fv(rcasOes_uTexMat, 1, false, texMatrix, 0);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            setOesFilter(true);
            GLES20.glUniform1i(rcasOes_uTex, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocateDirect(2*2*4).order(java.nio.ByteOrder.nativeOrder());
            GLES20.glReadPixels(0, 0, 2, 2, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bb);
            int sum = 0;
            while (bb.hasRemaining()) { sum |= (bb.get() & 0xFF); }
            rcasOesHealthy = (sum != 0);

        } finally {
            // Restore GL state
            GLES20.glActiveTexture(prevActiveTex[0]);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prevTex[0]);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, prevFbo[0]);
            GLES20.glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);

            // Cleanup test resources
            if (testTex != 0) { tmpIntArray[0] = testTex; GLES20.glDeleteTextures(1, tmpIntArray, 0); }
            if (testFbo != 0) { tmpIntArray[0] = testFbo; GLES20.glDeleteFramebuffers(1, tmpIntArray, 0); }
        }

        try { com.limelight.LimeLog.info("RCAS_OES health=" + rcasOesHealthy); } catch (Throwable ignored) {}
    }
}