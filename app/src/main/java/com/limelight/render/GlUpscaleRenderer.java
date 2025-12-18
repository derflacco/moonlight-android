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

import androidx.annotation.Keep;

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.LimeLog;
import com.limelight.Game;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import com.limelight.utils.DisplaySizer;

/*
 * FidelityFX Super Resolution 1.0 (FSR1) — EASU + RCAS (GLES3 + OES port)
 * Copyright (c) 2021 Advanced Micro Devices, Inc.
 * SPDX-License-Identifier: MIT
 *
 * This renderer adapts AMD FSR1 with:
 * - ES3 fast-path + ES2 fallback
 * - SurfaceTexture (OES) input, optional EASU upsample + RCAS sharpen
 * - Single-pass RCAS_OES (ES3) health-checked and auto-fallback to 2D RCAS
 *
 * Improvements in this version:
 * - ES2 fallback (#version 100 shaders) when ES3 unavailable
 * - Robust EGL config (ES3 then ES2) + optional RECORDABLE_ANDROID
 * - Safer init (avoid NPE if decoderSurfaceTex is null)
 * - Complete GL cleanup (incl. RCAS_OES program)
 * - Swap throttling hint (eglSwapInterval(0), best effort)
 * - Fixed-state tweaks (disable DITHER/SCISSOR, UNPACK_ALIGNMENT=1)
 * - Resize changes now force a draw (avoid “stuck” frames after size-only changes)
 * - RCAS_OES health-check waits for first content & uses 2×2 texel steps
 * - Consistent sharpness mapping (single dead-zone constant)
 * - Prefer LINEAR sampling for OES on both up/down-scale to reduce aliasing
 * - Skip EASU when target is downscale (RCAS-only path)
 * - Faster recovery for SurfaceTexture "not attached" by re-attaching once
 * - Immediate EGL/GL reinit on EGL_BAD_SURFACE / EGL_CONTEXT_LOST
 * - Optional avoidance of uTexMatrix rebinds when no new frame landed
 */
public final class GlUpscaleRenderer implements SurfaceTexture.OnFrameAvailableListener {

    // ====== Configuration Constants ======
    private static final float NEAR_NATIVE_THRESHOLD = 0.01f;
    private static final long SIZE_QUERY_INTERVAL_NS = 400_000_000L; // ~0.4s
    private static final int MAX_SWAP_FAIL_STREAK = 8;

    // Sharpness curve constants (UI 0..1 -> internal strength)
    private static final float SHARPNESS_DEADZONE = 0.05f; // <=5% = OFF
    private static final float SHARPNESS_GAMMA = 0.85f;
    private static final float SHARPNESS_EXP_FACTOR = 3.5f;
    // Gamma lift when FSR is active. 1.0 = no change, >1 brightens midtones.
    // Tune this if you want more/less brightness while FSR is on.
    private static final float FSR_GAMMA_COMPENSATION = 1.10f;
// Extra sharpness headroom when stream resolution is <= 50% of output (>= 2.0x upscale).
    private static final float LOW_RES_UPSCALE_RATIO_START = 2.0f; // 2x upscale == src is 50% of dst
    private static final float LOW_RES_UPSCALE_RATIO_FULL  = 3.0f; // fully applied at 3x and above
    private static final float LOW_RES_SHARPNESS_CAP_BONUS = 0.18f; // add up to +0.18 to the cap
    private static final float MAX_INTERNAL_SHARPNESS      = 0.80f; // hard clamp for safety
    // ====== ES version ======
    private boolean isEs3 = false;

    // RCAS_OES health-check state (ES3 only)
    private boolean rcasOesChecked = false;
    private boolean rcasOesHealthy = false;

    // OES extension availability probe
    private boolean hasOesExternal = true;

    // Track when the SurfaceTexture transform matrix actually changed
    private boolean texMatrixDirty = false;

    // Ensure per-program first-use matrix upload
    private boolean blitMatDirty = true, easuMatDirty = true, rcasOesMatDirty = true;

    // ===== HDR / Direct Present state =====
    // These flags are driven from Game/decoder:
    //  - hdrActive:        true when the current stream is HDR10
    //  - hdrDirectPresent: true when GPU path (direct present) is active
    private volatile boolean hdrActive = false;
    private volatile boolean hdrDirectPresent = false;
    // ===== Direct Present logging =====
    private boolean dpLoggedOn = false;
    private boolean dpLoggedOff = true;
    /**
     * Update HDR + Direct Present mode.
     *
     * @param hdrActive      true if the current stream is HDR.
     * @param directPresent  true only when GPU path (prefs.gpuPathMode) is enabled.
     */
    public void setHdrMode(boolean hdrActive, boolean directPresent) {
        this.hdrActive = hdrActive;
        this.hdrDirectPresent = directPresent;
    }

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
            final double a = 0.2; // smoothing factor
            return (avg == 0.0) ? sample : (a * sample + (1.0 - a) * avg);
        }
        private final java.util.concurrent.atomic.AtomicInteger pendingFrames =
                new java.util.concurrent.atomic.AtomicInteger(0);

        private long tEasu = 0L, tRcas = 0L;
        void ticEasu() { tEasu = now(); }
        void tocEasu() { easuAvgNs = ewma(easuAvgNs, now() - tEasu); }
        void ticRcas() { tRcas = now(); }
        void tocRcas() { rcasAvgNs = ewma(rcasAvgNs, now() - tRcas); }

        String overlayLine() {
            if ("EASU+RCAS".equals(mode)) {
                double easuMs  = easuAvgNs / 1e6;
                double rcasMs  = rcasAvgNs / 1e6;
                double totalMs = (easuAvgNs + rcasAvgNs) / 1e6;
                return String.format(java.util.Locale.US,
                        "FSR %s | sharp=%.2f | EASU=%.2fms RCAS=%.2fms TOT=%.2fms",
                        mode, sharp, easuMs, rcasMs, totalMs);
            } else if ("RCAS_ONLY".equals(mode)) {
                return String.format(java.util.Locale.US,
                        "FSR %s | sharp=%.2f | rcas=%.2fms",
                        mode, sharp, rcasAvgNs / 1e6);
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
    // Cached direct-present
    private final boolean fastBypassStatic;
    // --- GPU Kick (GL path) ---
    private boolean enableGpuKick = false;
    private int kickFbo = 0, kickTex = 0, kickProg = 0, kickVbo = 0;
    private int kickPosLoc = -1; // cache attrib location

    // --- GPU Kick state-safety ---
    private boolean kickHasVAO = false;
    private int kickVao = 0;
    private final int[] kickTmp4 = new int[4];
    private final int[] kickTmp1 = new int[1];

    // EGL
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglWindowSurface = EGL14.EGL_NO_SURFACE;

    // Decoder input
    private int oesTexId = 0;
    private SurfaceTexture decoderSurfaceTex;
    private Surface decoderInputSurface;

    // FBO for upscaled image
    private int fbo = 0;
    private int upscaledTex = 0;
    private int fbW = 0, fbH = 0;

    // Programs
    private int progVs = 0, progBlit = 0, progEasu = 0, progRcas = 0;

    // Uniform locations
    private int blit_uTex = -1, blit_uTexMat = -1;
    private int easu_uTex = -1, easu_uInvSrcSize = -1, easu_uTexMat = -1;
    private int rcas_uTex = -1, rcas_uInvDst = -1, rcas_uSharp = -1, rcas_uGamma = -1;
    private int progRcasOes = 0,
            rcasOes_uTex = -1, rcasOes_uInvDst = -1, rcasOes_uSharp = -1, rcasOes_uGamma = -1, rcasOes_uTexMat = -1;


    // Quad buffers (no VAO in ES2)
    private int vboPos = 0, vboUv = 0;
    private FloatBuffer quadPos, quadUv;

    // SurfaceTexture transform
    private final float[] texMatrix = new float[16];
    // Last frame timestamp from SurfaceTexture (ns)
    private long lastFrameTexTimestampNs = 0L;

    // Presentation size hint
    private volatile int hintOutW = 0, hintOutH = 0;

    // Performance
    private final int[] tmpIntArray = new int[1];

    // ===== Performance state =====
    private int vao = 0;
    private boolean hasVao = false;
    private long lastSizeQueryNs = 0L;
    private int swapFailStreak = 0;
    private boolean fixedStateApplied = false;

    // Track whether size changed since last swap (to force a draw even without a new frame)
    private boolean sizeChangedSinceLastSwap = true;

    // Threading
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread renderThread;
    private final Object frameLock = new Object();
    private final java.util.concurrent.atomic.AtomicInteger pendingFrames = new java.util.concurrent.atomic.AtomicInteger(0);

    private android.os.HandlerThread stCbThread;
    private android.os.Handler stCbHandler;

    // State cache
    private int curVpW = -1, curVpH = -1;
    private boolean twoDNearest = false, oesNearest = false;

    public GlUpscaleRenderer(android.content.Context context, Surface windowSurface, int srcW, int srcH, PreferenceConfiguration prefs) {
        this(windowSurface, srcW, srcH, prefs);
        try { setPresentationSizeHintFromContext(context); } catch (Throwable ignored) {}
    }

    public GlUpscaleRenderer(Surface windowSurface, int srcW, int srcH, PreferenceConfiguration prefs) {
        this.windowSurfaceInput = windowSurface;
        this.srcW = Math.max(1, srcW);
        this.srcH = Math.max(1, srcH);
        this.prefs = prefs;

        // Precompute whether we can always take the ultra-thin OES->screen path.
        this.fastBypassStatic = computeFastBypassStatic(prefs);
    }
    // Decide once, at construction, if this renderer can use the ultra-thin path.
    // True when GPU direct path is forced or FSR mode is logically disabled.
    private static boolean computeFastBypassStatic(PreferenceConfiguration prefs) {
        // Treat disabled upscaling as a full bypass (ultra-thin OES->screen path).
        if (prefs == null) return true;
        if (prefs.gpuPathMode) return true;
        if (!prefs.videoUpscaleEnable) return true;

        final String mode = prefs.videoUpscaleMode;
        return (mode == null || "none".equals(mode));
    }


    /** Optional: hint actual on-screen buffer size. */
    public void setPresentationSizeHint(int w, int h) {
        int W = Math.max(0, w);
        int H = Math.max(0, h);
        // Ensure we render at least once with the new target even if no new frame arrives
        synchronized (frameLock) {
            hintOutW = W;
            hintOutH = H;
            sizeChangedSinceLastSwap = true;
            // Wake renderer if it was idling waiting for a frame
            frameLock.notifyAll();
        }
    }
    /** Called by sizers/installer when the presentation size changes. */
    public void onPresentationSizeChanged(int w, int h) {
        setPresentationSizeHint(w, h);
    }

    /** Aliases for installer/reflection compatibility. */
    public void setPresentationSize(int w, int h) { onPresentationSizeChanged(w, h); }
    public void setOutputSize(int w, int h)       { onPresentationSizeChanged(w, h); }
    public void setRenderTargetSize(int w, int h) { onPresentationSizeChanged(w, h); }

    public void setPresentationSizeHintFromDisplay(android.view.Display display) {
        if (display == null) return;
        try {
            int w = 0, h = 0;
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                android.view.Display.Mode m = display.getMode();
                if (m != null) {
                    w = Math.max(w, m.getPhysicalWidth());
                    h = Math.max(h, m.getPhysicalHeight());
                }
            }
            if (w <= 0 || h <= 0) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                display.getRealMetrics(dm);
                w = Math.max(w, dm.widthPixels);
                h = Math.max(h, dm.heightPixels);
            }
            if (w > 0 && h > 0) setPresentationSizeHint(w, h);
        } catch (Throwable ignored) {}
    }

    /** Derive a sane presentation size hint from Context (multi-API strategy). */
    public void setPresentationSizeHintFromContext(android.content.Context ctx) {
        if (ctx == null) return;
        int[] sz = DisplaySizer.getDisplaySizePx(ctx, null);
        if (sz[0] > 0 && sz[1] > 0) {
            setPresentationSizeHint(sz[0], sz[1]);
            try { LimeLog.info("FSR: presentation hint (auto) = " + sz[0] + "x" + sz[1]); } catch (Throwable ignored) {}
        }
    }

    @Keep
    public Surface createDecoderInputSurface() {
        if (!isGlReady()) {
            initEglAndGl();
            if (!isGlReady()) return null;
        }
        if (decoderInputSurface != null) return decoderInputSurface;

        // Create OES texture + SurfaceTexture
        GLES20.glGenTextures(1, tmpIntArray, 0);
        oesTexId = tmpIntArray[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // OES requires CLAMP_TO_EDGE; set once
        configureOesStaticParams();

        decoderSurfaceTex = new SurfaceTexture(oesTexId);
        try {
            // Avoid extra scaling inside SurfaceFlinger
            final int w = (srcW > 0) ? srcW : 1;
            final int h = (srcH > 0) ? srcH : 1;
            decoderSurfaceTex.setDefaultBufferSize(w, h);
        } catch (Throwable ignored) {}

        // Dedicated callback thread for SurfaceTexture
        if (stCbThread == null) {
            stCbThread = new android.os.HandlerThread("ST-Callback", android.os.Process.THREAD_PRIORITY_DISPLAY);
            stCbThread.start();
            stCbHandler = new android.os.Handler(stCbThread.getLooper());
        }
        decoderSurfaceTex.setOnFrameAvailableListener(this, stCbHandler);

        // MediaCodec will render into this Surface (producer side)
        decoderInputSurface = new Surface(decoderSurfaceTex);

        // Optional: sanity + unbind
        try {
            int err = GLES20.glGetError();
            if (err != GLES20.GL_NO_ERROR) {
                LimeLog.warning("GL error after OES setup: 0x" + Integer.toHexString(err));
            }
        } catch (Throwable ignored) {}
        try { GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0); } catch (Throwable ignored) {}

        // IMPORTANT: release current binding so the render thread can take ownership of this EGL context
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
        } catch (Throwable ignored) {}

        // If the render thread uses a DIFFERENT EGLContext, do this instead on this thread:
        // try { decoderSurfaceTex.detachFromGLContext(); } catch (Throwable ignored) {}
        // ...and on the render thread (after its context is current):
        // try { decoderSurfaceTex.attachToGLContext(oesTexId); } catch (Throwable ignored) {}

        return decoderInputSurface;
    }

    public void start() {
        if (!isGlReady()) { initEglAndGl(); }
        if (!isGlReady() || running.getAndSet(true)) return;
        renderThread = new Thread(this::renderLoop, "GL-FSR1-Renderer");
        renderThread.setPriority(Thread.NORM_PRIORITY + 1);
        renderThread.start();
    }

    public void stop() {
        running.set(false);
        // Wake the wait-loop if sleeping
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
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
                // Stop callback thread
                try {
                    if (stCbThread != null) {
                        stCbThread.quitSafely();
                        try {
                            // Wait a bit to ensure clean shutdown
                            stCbThread.join(500L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                        stCbThread = null;
                        stCbHandler = null;
                    }
                } catch (Throwable ignored2) {}
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
        if (!isGlReady()) { return; }
        // Pure atomic path for backlog + single notify under lock
        pendingFrames.incrementAndGet();
        synchronized (frameLock) {
            frameLock.notifyAll();
        }
    }

    // ====== Render Mode Decision ======
    private enum RenderMode {
        BYPASS,
        RCAS_ONLY,
        EASU_RCAS,
        HDR_DIRECT // HDR-friendly direct present path: thin OES -> screen, no FSR/gamma tricks
    }


    private RenderMode determineRenderMode(boolean upscaleEnabled, String mode, boolean nearNative) {
        boolean hdrActive = false;
        try {
            hdrActive = Game.isHdrStreamActive();
        } catch (Throwable ignored) {}

        boolean gpuPath = (prefs != null && prefs.gpuPathMode);

        // HDR + GPU path => dedicated HDR-direct branch (no FSR, no gamma tricks)
        if (hdrActive && gpuPath) {
            return RenderMode.HDR_DIRECT;
        }

        if (!upscaleEnabled || "none".equals(mode)) {
            return RenderMode.BYPASS;
        } else if ("easu_rcas".equals(mode) && progEasu != 0 && !nearNative) {
            return RenderMode.EASU_RCAS;
        } else {
            return RenderMode.RCAS_ONLY;
        }
    }



    // FSR completely bypassed (no EASU, no RCAS): we can just blit OES -> screen.
    // Used to skip renderFrame() entirely when FSR is logically off.
    private boolean isFsrBypassFastPath(String mode) {
        // If the checkbox is OFF, treat it as a full bypass (no FSR/RCAS work).
        if (prefs == null) return true;
        if (prefs.gpuPathMode) return true;
        if (!prefs.videoUpscaleEnable) return true;

        return (mode == null || "none".equals(mode));
    }

    private boolean isNearNativeScale(int dstW, int dstH) {
        float scaleX = (float) dstW / (float) srcW;
        float scaleY = (float) dstH / (float) srcH;
        return Math.abs(Math.min(scaleX, scaleY) - 1.0f) < NEAR_NATIVE_THRESHOLD;
    }


    // ====== Main Render Loop ======
    private void renderLoop() {
        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); } catch (Throwable ignored) {}

// If upscaling is disabled AND GPU Path is not active, we can stop early.
// GPU Path (Direct Present) still needs the GL pipeline even with scaling disabled.
        if (prefs != null && !prefs.gpuPathMode && !prefs.videoUpscaleEnable) {
            running.set(false);
            return;
        }

        sizeChangedSinceLastSwap = true; // force first draw
        long lastFrameNs = 0L;
        final long maxIdleWaitMs = 33L; // ~30Hz idle sleep
        final long minIdleWaitMs = 5L;  // responsive lower bound

        while (running.get()) {
            // Wait for either a new frame or a size change (with adaptive idle sleep).
            synchronized (frameLock) {
                boolean hasWork = (pendingFrames.get() > 0 || sizeChangedSinceLastSwap);
                if (!hasWork) {
                    long now = System.nanoTime();
                    long deltaMs;
                    if (lastFrameNs == 0L) {
                        deltaMs = maxIdleWaitMs;
                    } else {
                        long elapsedMs = (now - lastFrameNs) / 1_000_000L;
                        deltaMs = Math.max(minIdleWaitMs, Math.min(maxIdleWaitMs, elapsedMs));
                    }

                    try {
                        frameLock.wait(deltaMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            if (!running.get()) break;
            if (!isGlReady()) continue;
            if (!makeCurrent()) continue;

            boolean didUpdateTex = updateTexture();
            boolean valid = refreshWindowSizeIfNeeded();
            if (!valid || fbW <= 0 || fbH <= 0) continue;

            // Skip frame if nothing changed (no new frame, no size change)
            if (!didUpdateTex && !sizeChangedSinceLastSwap) continue;

            ensureViewport(fbW, fbH);
// Direct Present (GPU Path): bypass ALL scaling (FSR/HDR filters/etc), but keep GL pipeline.
            final boolean gpuPathNow = (prefs != null && prefs.gpuPathMode);
            if (gpuPathNow) {
                if (!dpLoggedOn) {
                    dpLoggedOn = true;
                    dpLoggedOff = false;
                    try { LimeLog.info("Direct Present: ON (gpuPathMode=true) -> bypass ALL upscaling"); } catch (Throwable ignored) {}
                }

                drawOesToScreen();
                if (!presentFrame()) break;

                texMatrixDirty = false;
                sizeChangedSinceLastSwap = false;
                lastFrameNs = System.nanoTime();
                continue;
            } else {
                if (!dpLoggedOff) {
                    dpLoggedOff = true;
                    dpLoggedOn = false;
                    try { LimeLog.info("Direct Present: OFF (gpuPathMode=false)"); } catch (Throwable ignored) {}
                }
            }

            // Ultra-thin path: when fastBypassStatic is true, we always just blit OES -> screen.
            if (fastBypassStatic) {
                drawOesToScreen();
                if (!presentFrame()) break;

                texMatrixDirty = false;
                sizeChangedSinceLastSwap = false;
                lastFrameNs = System.nanoTime();
                continue;
            }

            // Full FSR path (EASU/RCAS/RCAS-only) when enabled.
            RenderResult result = renderFrame();
            if (!result.success) {
                // Fallback: direct blit if FSR draw failed.
                drawOesToScreen();
            }

            if (!presentFrame()) {
                // Stop loop on persistent failure / invalid surface.
                break;
            }

            texMatrixDirty = false;
            sizeChangedSinceLastSwap = false;
            lastFrameNs = System.nanoTime();
        }
    }

    private boolean makeCurrent() {
        if (EGL14.eglGetCurrentContext() != eglContext ||
                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) != eglWindowSurface) {
            return EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext);
        }
        return true;
    }

    private boolean updateTexture() {
        boolean didUpdateTex = false;
        try {
            if (decoderSurfaceTex != null) {
                int p = pendingFrames.getAndSet(0);
                if (p > 0) {
                    // Drain backlog and keep the newest frame
                    do { decoderSurfaceTex.updateTexImage(); } while (--p > 0);
                    decoderSurfaceTex.getTransformMatrix(texMatrix);
                    try { lastFrameTexTimestampNs = decoderSurfaceTex.getTimestamp(); } catch (Throwable ignored) {}
                    texMatrixDirty = true;
                    // ensure first-use matrix upload on all programs
                    blitMatDirty = easuMatDirty = rcasOesMatDirty = true;
                    didUpdateTex = true;
                }
            }
        } catch (RuntimeException e) {
            // Fast recovery: re-attach the SurfaceTexture to current context and retry once
            try {
                ensureOesAttachmentAfterReinit();

                // Important: restore buffer size after re-attachment
                try {
                    decoderSurfaceTex.setDefaultBufferSize(srcW, srcH);
                } catch (Throwable ignoredRestore) {}

                decoderSurfaceTex.updateTexImage();
                decoderSurfaceTex.getTransformMatrix(texMatrix);
                try { lastFrameTexTimestampNs = decoderSurfaceTex.getTimestamp(); } catch (Throwable ignored) {}
                texMatrixDirty = true;
                blitMatDirty = easuMatDirty = rcasOesMatDirty = true;
                didUpdateTex = true;
            } catch (Throwable ignored2) {
                try { LimeLog.warning("Texture update retry failed: " + e.getMessage()); } catch (Throwable ignored3) {}
            }
        } catch (Throwable t) {
            try { LimeLog.warning("Texture update failed: " + t.getMessage()); } catch (Throwable ignored) {}
        }
        return didUpdateTex;
    }

    /**
     * Periodically re-query window surface size.
     * If dimensions changed, mark sizeChangedSinceLastSwap=true to force a draw.
     */
    private boolean refreshWindowSizeIfNeeded() {
        long now = System.nanoTime();
        if (fbW <= 0 || (now - lastSizeQueryNs) >= SIZE_QUERY_INTERVAL_NS) {
            int oldW = fbW, oldH = fbH;
            refreshWindowSize();
            lastSizeQueryNs = now;
            if (fbW > 0 && fbH > 0 && (fbW != oldW || fbH != oldH)) {
                sizeChangedSinceLastSwap = true; // ensure we render even without a new frame
            }
            return (fbW > 0 && fbH > 0);
        }
        return true;
    }

    private static class RenderResult {
        final boolean success;
        final String mode;
        RenderResult(boolean success, String mode) { this.success = success; this.mode = mode; }
    }

    private RenderResult renderFrame() {
        final boolean upscaleEnabled = (prefs != null && prefs.videoUpscaleEnable);
        final String mode = (prefs != null ? prefs.videoUpscaleMode : "rcas");
        final float sharpUser = (prefs != null ? clamp01(prefs.videoUpscaleSharpness / 100f) : 0.35f);

        // Policy/telemetry target size (prefer presentation hint if present)
        final int dstTargetW = (hintOutW > 0 ? hintOutW : fbW);
        final int dstTargetH = (hintOutH > 0 ? hintOutH : fbH);
        boolean nearNative = isNearNativeScale(dstTargetW, dstTargetH);
        final boolean isDownscale = (dstTargetW < srcW) || (dstTargetH < srcH);

        RenderMode renderMode = determineRenderMode(upscaleEnabled, mode, nearNative);
        // Guard: EASU is an upsampler; skip it when actually downscaling
        if (isDownscale && renderMode == RenderMode.EASU_RCAS) {
            renderMode = RenderMode.RCAS_ONLY;
        }

        final float upscaleRatio =
                Math.min((float) fbW / (float) srcW, (float) fbH / (float) srcH);

        float effectiveSharpness = mapUiSharpToInternal(sharpUser, nearNative, upscaleRatio);

        boolean success = false;
        String actualMode = "BYPASS";

        switch (renderMode) {
            case BYPASS:
                setupTelemetry("BYPASS", sharpUser, "bypass",
                        "reason=" + ("none".equals(mode) ? "bypass:mode_none" : "bypass:upscaleDisabled"));
                drawOesToScreen();
                success = true;
                actualMode = "BYPASS";
                break;

            case HDR_DIRECT:
                // HDR + GPU path: ultra-thin OES -> screen path, no FSR, no gamma tweaks
                setupTelemetry("HDR_DIRECT", sharpUser, "HDR_OES_BYPASS",
                        "reason=hdr_gpu_path");
                drawOesToScreen();
                success = true;
                actualMode = "HDR_DIRECT";
                break;

            case EASU_RCAS:
                success = drawEasuRcasSafe(fbW, fbH, effectiveSharpness);
                actualMode = "EASU+RCAS";
                setupTelemetry(actualMode, sharpUser, "OES->2D LINEAR + RCAS_2D",
                        (success ? "reason=easu_rcas" : "fallback:easu_rcas_failed") +
                                " | nearNative=" + nearNative);
                break;

            case RCAS_ONLY:
                long startTime = System.nanoTime();
                success = drawRcasOnlySafe(fbW, fbH, effectiveSharpness);
                long duration = System.nanoTime() - startTime;

                String samplingMode = (isEs3 && progRcasOes != 0 && rcasOesHealthy) ? "RCAS_OES"
                        : "OES->2D LINEAR + RCAS_2D";
                String reason;
                if (nearNative) reason = "reason=nearNative";
                else if (srcW == fbW && srcH == fbH) reason = "reason=dstEqSrc";
                else if ("rcas".equals(mode)) reason = "reason=easu_disabled";
                else reason = "reason=mode!=easu_rcas";

                setupTelemetry("RCAS_ONLY", effectiveSharpness, samplingMode,
                        (success ? reason : "fallback:rcas_only_failed") +
                                " | src=" + srcW + "x" + srcH +
                                " dst=" + fbW + "x" + fbH +
                                " nearNative=" + nearNative +
                                " durationMs=" + (duration / 1_000_000.0));
                break;
        }


        if (__fsr.enabled) {
            __fsr.frames++;
            if ((__fsr.frames % 240L) == 0L) {
                try { LimeLog.info(__fsr.periodicLine()); } catch (Throwable ignored) {}
            }
            __fsrOverlay = __fsr.overlayLine();
        }

        return new RenderResult(success, actualMode);
    }

    private void setupTelemetry(String mode, float sharpness, String sampling, String notes) {
        if (!__fsr.enabled) return;
        __fsr.mode = mode;
        __fsr.srcW = srcW;
        __fsr.srcH = srcH;
        __fsr.dstW = fbW;
        __fsr.dstH = fbH;
        __fsr.sharp = sharpness;
        __fsr.sampling = sampling;
        __fsr.notes = notes;
    }

    private boolean presentFrame() {
        final long presentNs = (lastFrameTexTimestampNs > 0L) ? lastFrameTexTimestampNs : System.nanoTime();
        try {
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindowSurface, presentNs);
        } catch (Throwable ignored) {}

        gpuKickOnceGL();
        boolean swapped = EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface);
        if (!swapped) {
            int err = EGL14.eglGetError();
            try { LimeLog.warning("FSR: eglSwapBuffers failed err=0x" + Integer.toHexString(err) + " (streak=" + swapFailStreak + ")"); } catch (Throwable ignored) {}
            // If the Surface itself is invalid, stop gracefully
            if (windowSurfaceInput == null || !windowSurfaceInput.isValid()) {
                running.set(false);
                return false;
            }

            final boolean fatalSurface =
                    (err == EGL14.EGL_BAD_SURFACE /*0x300D*/) ||
                            (err == EGL14.EGL_CONTEXT_LOST /*0x300E*/);

            swapFailStreak++;
            if (fatalSurface || swapFailStreak >= MAX_SWAP_FAIL_STREAK) {
                boolean reinitOk = reinitEglAndGl();
                if (reinitOk) {
                    swapFailStreak = 0;
                    // Force a redraw after reinit
                    sizeChangedSinceLastSwap = true;
                    return true;
                } else {
                    running.set(false);
                    return false;
                }
            }
        } else {
            if (swapFailStreak != 0) swapFailStreak = 0;
        }

        return swapped;
    }

    // ====== Draw Methods ======
    private void drawOesToScreen() {
        GLES20.glUseProgram(progBlit);
        bindQuad(progBlit);

        if (texMatrixDirty || blitMatDirty) {
            GLES20.glUniformMatrix4fv(blit_uTexMat, 1, false, texMatrix, 0);
            blitMatDirty = false;
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        setOesFilter(false); // Prefer LINEAR to reduce aliasing
        GLES20.glUniform1i(blit_uTex, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}
    }

    private boolean drawRcasOnlySafe(int dstW, int dstH, float sharp) {
        // Health-check after first content only
        if (isEs3) checkRcasOesHealthOnce(dstW, dstH);

        // Prefer direct OES sharpening (ES3)
        if (isEs3 && progRcasOes != 0 && rcasOesHealthy) {
            if (__fsr.enabled) { __fsr.sampling = "RCAS_OES"; }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            ensureViewport(dstW, dstH);
            GLES20.glUseProgram(progRcasOes);
            bindQuad(progRcasOes);

            GLES20.glUniform2f(rcasOes_uInvDst, 1.0f / Math.max(1, dstW), 1.0f / Math.max(1, dstH));
            GLES20.glUniform1f(rcasOes_uSharp, clamp01(sharp));
            if (rcasOes_uGamma >= 0) {
                GLES20.glUniform1f(rcasOes_uGamma, getFsrGammaComp());
            }
            if (texMatrixDirty || rcasOesMatDirty) {
                GLES20.glUniformMatrix4fv(rcasOes_uTexMat, 1, false, texMatrix, 0);
                rcasOesMatDirty = false;
            }

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
            setOesFilter(false); // LINEAR for both up/down-scale
            GLES20.glUniform1i(rcasOes_uTex, 0);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

            if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}
            return true;
        }

        // Fallback: OES -> upscaledTex, then RCAS 2D
        if (!ensureFbo(dstW, dstH)) return false;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, upscaledTex, 0);
        if (!isFboComplete()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            return false;
        }

        ensureViewport(dstW, dstH);
        // OES -> 2D
        GLES20.glUseProgram(progBlit);
        bindQuad(progBlit);
        if (texMatrixDirty || blitMatDirty) {
            GLES20.glUniformMatrix4fv(blit_uTexMat, 1, false, texMatrix, 0);
            blitMatDirty = false;
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        setOesFilter(false); // LINEAR in downscale too
        GLES20.glUniform1i(blit_uTex, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}

        // RCAS 2D -> screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        ensureViewport(dstW, dstH);
        GLES20.glUseProgram(progRcas);
        bindQuad(progRcas);
        if (__fsr.enabled) { __fsr.ticRcas(); }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upscaledTex);
        setTex2DFilter(true); // NEAREST on 2D during sharpen (crisper sampling)
        GLES20.glUniform1i(rcas_uTex, 0);
        GLES20.glUniform2f(rcas_uInvDst, 1.0f / Math.max(1, dstW), 1.0f / Math.max(1, dstH));
        GLES20.glUniform1f(rcas_uSharp, clamp01(sharp));
        if (rcas_uGamma >= 0) {
            GLES20.glUniform1f(rcas_uGamma, getFsrGammaComp());
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        setTex2DFilter(false); // restore
        if (__fsr.enabled) { __fsr.tocRcas(); }

        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}
        return true;
    }

    private boolean drawEasuRcasSafe(int dstW, int dstH, float sharp) {
        if (__fsr.enabled) { __fsr.sampling = "OES->2D LINEAR + RCAS_2D"; }
        if (!ensureFbo(dstW, dstH)) return false;

        // Attach and check
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, upscaledTex, 0);
        if (!isFboComplete()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            return false;
        }

        ensureViewport(dstW, dstH);
        // EASU (OES->2D)
        GLES20.glUseProgram(progEasu);
        bindQuad(progEasu);
        if (__fsr.enabled) { __fsr.ticEasu(); }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        setOesFilter(false); // LINEAR

        GLES20.glUniform1i(easu_uTex, 0);
        GLES20.glUniform2f(easu_uInvSrcSize, 1.0f / srcW, 1.0f / srcH);
        if (texMatrixDirty || easuMatDirty) {
            GLES20.glUniformMatrix4fv(easu_uTexMat, 1, false, texMatrix, 0);
            easuMatDirty = false;
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        if (__fsr.enabled) { __fsr.tocEasu(); }
        if (hasVao) try { GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}

        // RCAS → screen
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
        if (rcas_uGamma >= 0) {
            GLES20.glUniform1f(rcas_uGamma, getFsrGammaComp());
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        if (__fsr.enabled) { __fsr.tocRcas(); }

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

        // Warn if using source-sized window while hint suggests larger presentation
        if (w == srcW && h == srcH && (hintOutW > srcW || hintOutH > srcH)) {
            try {
                LimeLog.warning("FSR: window surface == source ("+w+"x"+h+"), but presentation hint is " + hintOutW + "x" + hintOutH +
                        ". Upscale will be bypassed. Use a display-sized Surface (TextureView.setDefaultBufferSize or SurfaceHolder.setFixedSize).");
            } catch (Throwable ignored) {}
        }
    }

    private void ensureViewport(int w, int h) {
        if (w != curVpW || h != curVpH) {
            GLES20.glViewport(0, 0, w, h);
            curVpW = w; curVpH = h;
        }
    }

    private boolean ensureFbo(int w, int h) {
        if (w <= 0 || h <= 0) return false;
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

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, upscaledTex, 0);
        if (!isFboComplete()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            destroyFbo();
            return;
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        twoDNearest = false;
        glCheckError("createOrResizeFbo");
    }

    private boolean isFboComplete() {
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            try { LimeLog.warning("FBO incomplete: 0x" + Integer.toHexString(status)); } catch (Throwable ignored) {}
            return false;
        }
        return true;
    }

    private void destroyFbo() {
        if (upscaledTex != 0) {
            tmpIntArray[0] = upscaledTex;
            GLES20.glDeleteTextures(1, tmpIntArray, 0);
            upscaledTex = 0;
        }
        if (fbo != 0) {
            tmpIntArray[0] = fbo;
            GLES20.glDeleteFramebuffers(1, tmpIntArray, 0);
            fbo = 0;
        }
    }

    private void initEglAndGl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) {
                throw new RuntimeException("eglInitialize failed");
            }

            // Try config that supports ES2 and ES3 (+recordable when available)
            int renderable = EGL14.EGL_OPENGL_ES2_BIT;
            try { renderable |= EGLExt.EGL_OPENGL_ES3_BIT_KHR; } catch (Throwable ignored) {}

            final int EGL_RECORDABLE_ANDROID = 0x3142; // from EGLExt
            int[] cfg = {
                    EGL14.EGL_RENDERABLE_TYPE, renderable,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL_RECORDABLE_ANDROID, 1, // optional hint for video surfaces
                    EGL14.EGL_NONE
            };
            EGLConfig[] out = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, cfg, 0, out, 0, 1, num, 0) || out[0] == null) {
                // Fallback: ES2 only, no recordable flag
                int[] cfg2 = {
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                        EGL14.EGL_NONE
                };
                if (!EGL14.eglChooseConfig(eglDisplay, cfg2, 0, out, 0, 1, num, 0) || out[0] == null) {
                    throw new RuntimeException("eglChooseConfig failed");
                }
            }
            EGLConfig eglConfig = out[0];

            // Try ES3, fallback to ES2
            int[] ctx3 = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctx3, 0);
            if (eglContext == null || eglContext == EGL14.EGL_NO_CONTEXT) {
                int[] ctx2 = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
                eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctx2, 0);
                isEs3 = false;
            } else {
                isEs3 = true;
            }

            int[] sattr = {EGL14.EGL_NONE};
            eglWindowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, windowSurfaceInput, sattr, 0);
            EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext);

            // Best-effort non-blocking swaps (driver may ignore)
            try { EGL14.eglSwapInterval(eglDisplay, 0); } catch (Throwable ignored) {}

            // Extension probe for OES external
            try {
                String exts = GLES20.glGetString(GLES20.GL_EXTENSIONS);
                hasOesExternal = exts != null &&
                        (exts.contains("GL_OES_EGL_image_external_essl3") ||
                                exts.contains("GL_OES_EGL_image_external"));
                if (!hasOesExternal) {
                    try { LimeLog.warning("Missing GL_OES_EGL_image_external; OES path may fail."); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

            // Confirm GL version string
            try {
                String ver = GLES20.glGetString(GLES20.GL_VERSION);
                if (ver != null && ver.startsWith("OpenGL ES 2.")) isEs3 = false;
            } catch (Throwable ignored) {}
            // Basic error probe after context/surface binding
            glCheckError("eglMakeCurrent");

            // Avoid updateTexImage call if SurfaceTexture not ready
            if (decoderSurfaceTex != null) {
                int p = pendingFrames.getAndSet(0);
                if (p > 0) {
                    do { decoderSurfaceTex.updateTexImage(); } while (--p > 0);
                    decoderSurfaceTex.getTransformMatrix(texMatrix);
                    texMatrixDirty = true;
                    blitMatDirty = easuMatDirty = rcasOesMatDirty = true;
                }
            }
        } catch (Throwable t) {
            try { LimeLog.warning("GL init failed: " + t); } catch (Throwable ignored) {}
            destroyEgl();
            return;
        }

        initializeGeometry();
        glCheckError("initializeGeometry");
        initializeShaders();

        // Apply fixed GL state once per EGL/GL init (no need to re-check in renderLoop).
        applyFixedState();

        this.enableGpuKick = (prefs != null && prefs.enableGpuKick);
        initGpuKickIfNeeded();
    }


    private void initializeGeometry() {
        // Fullscreen quad positions/UVs
        float[] POS = {-1,-1, 1,-1, -1,1, 1,1};
        float[] UV  = { 0, 0, 1, 0,  0,1, 1,1};
        quadPos = ByteBuffer.allocateDirect(POS.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadUv  = ByteBuffer.allocateDirect(UV.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadPos.put(POS).position(0);
        quadUv.put(UV).position(0);

        // VBOs (work on ES2/ES3)
        GLES20.glGenBuffers(1, tmpIntArray, 0); vboPos = tmpIntArray[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadPos.capacity()*4, quadPos, GLES20.GL_STATIC_DRAW);

        GLES20.glGenBuffers(1, tmpIntArray, 0); vboUv = tmpIntArray[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadUv.capacity()*4, quadUv, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        // VAO path (ES3 only)
        if (isEs3) {
            try {
                int[] vaoId = new int[1];
                GLES30.glGenVertexArrays(1, vaoId, 0);
                vao = vaoId[0];
                GLES30.glBindVertexArray(vao);
                // aPos @ location 0
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos);
                GLES20.glEnableVertexAttribArray(0);
                GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, 0);
                // aUv @ location 1
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv);
                GLES20.glEnableVertexAttribArray(1);
                GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 0, 0);
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
                GLES30.glBindVertexArray(0);
                hasVao = (vao != 0);
            } catch (Throwable ignored) {
                hasVao = false;
            }
        } else {
            hasVao = false;
        }
    }

    private void initializeShaders() {
        try {
            if (isEs3) {
                progVs   = compileShader(GLES20.GL_VERTEX_SHADER, VS_ES3);
                // On ES3 we use layout(location=...), so no need to bind attribs in link
                progBlit = linkProgram(progVs, FS_OES_BLIT_ES3, /*bindAttribs*/ false);
                progEasu = linkProgram(progVs, FS_EASU_ES3,     /*bindAttribs*/ false);
                progRcas = linkProgram(progVs, FS_RCAS_ES3,     /*bindAttribs*/ false);

                // RCAS_OES single-pass (optional)
                try {
                    int vsRcasOes = compileShader(GLES20.GL_VERTEX_SHADER, VS_RCAS_OES_ES3);
                    progRcasOes = linkProgram(vsRcasOes, "#define USE_OES\n#define RCAS_OES_VS\n" + FS_RCAS_ES3, false);
                    // delete VS object now that program is linked
                    GLES20.glDeleteShader(vsRcasOes);

                    rcasOes_uTex    = GLES20.glGetUniformLocation(progRcasOes, "uTexOES");
                    rcasOes_uInvDst = GLES20.glGetUniformLocation(progRcasOes, "uInvDstSize");
                    rcasOes_uSharp  = GLES20.glGetUniformLocation(progRcasOes, "uSharp");
                    rcasOes_uGamma  = GLES20.glGetUniformLocation(progRcasOes, "uGamma");
                    rcasOes_uTexMat = GLES20.glGetUniformLocation(progRcasOes, "uTexMatrix");

                } catch (Throwable t) {
                    progRcasOes = 0; // keep 2D fallback
                }

                // VS can be deleted after programs are linked
                if (progVs != 0) { GLES20.glDeleteShader(progVs); progVs = 0; }
            } else {
                // ES2 fallback — requires explicit attribute binding
                progVs   = compileShader(GLES20.GL_VERTEX_SHADER, VS_ES2);
                progBlit = linkProgram(progVs, FS_OES_BLIT_ES2, /*bindAttribs*/ true);
                progEasu = linkProgram(progVs, FS_EASU_ES2,     /*bindAttribs*/ true);
                progRcas = linkProgram(progVs, FS_RCAS_ES2,     /*bindAttribs*/ true);
                // VS can be deleted after programs are linked
                if (progVs != 0) { GLES20.glDeleteShader(progVs); progVs = 0; }
                progRcasOes = 0; // not available on ES2
            }
        } catch (Throwable t) {
            // Hard fallback: try to at least keep blit working
            try { LimeLog.warning("Shader init encountered error, falling back to BLIT-only: " + t); } catch (Throwable ignored) {}
            try {
                if (progBlit == 0) {
                    if (isEs3) {
                        progVs   = compileShader(GLES20.GL_VERTEX_SHADER, VS_ES3);
                        progBlit = linkProgram(progVs, FS_OES_BLIT_ES3, false);
                        if (progVs != 0) { GLES20.glDeleteShader(progVs); progVs = 0; }
                    } else {
                        progVs   = compileShader(GLES20.GL_VERTEX_SHADER, VS_ES2);
                        progBlit = linkProgram(progVs, FS_OES_BLIT_ES2, true);
                        if (progVs != 0) { GLES20.glDeleteShader(progVs); progVs = 0; }
                    }
                }
            } catch (Throwable ignored) {}
        }
        glCheckError("initializeShaders");

        // Resolve uniforms (only for successfully linked programs)
        if (progBlit != 0) {
            blit_uTex    = GLES20.glGetUniformLocation(progBlit, "uTex");
            blit_uTexMat = GLES20.glGetUniformLocation(progBlit, "uTexMatrix");
        }
        if (progEasu != 0) {
            easu_uTex        = GLES20.glGetUniformLocation(progEasu, "uTex");
            easu_uInvSrcSize = GLES20.glGetUniformLocation(progEasu, "uInvSrcSize");
            easu_uTexMat     = GLES20.glGetUniformLocation(progEasu, "uTexMatrix");
        }
        if (progRcas != 0) {
            rcas_uTex     = GLES20.glGetUniformLocation(progRcas, "uUpscaled");
            rcas_uInvDst  = GLES20.glGetUniformLocation(progRcas, "uInvDstSize");
            rcas_uSharp   = GLES20.glGetUniformLocation(progRcas, "uSharp");
            rcas_uGamma   = GLES20.glGetUniformLocation(progRcas, "uGamma");
        }

        // ensure first-use matrix upload
        blitMatDirty = easuMatDirty = rcasOesMatDirty = true;
    }

    private void destroyGl() {
        releaseGpuKick();
        destroyFbo();
        if (hasVao && vao != 0) {
            int[] vaoId = new int[]{vao};
            try { GLES30.glDeleteVertexArrays(1, vaoId, 0); } catch (Throwable ignored) {}
            vao = 0; hasVao = false;
        }
        if (vboPos != 0) { tmpIntArray[0] = vboPos; GLES20.glDeleteBuffers(1, tmpIntArray, 0); vboPos = 0; }
        if (vboUv  != 0) { tmpIntArray[0] = vboUv;  GLES20.glDeleteBuffers(1, tmpIntArray, 0); vboUv  = 0; }
        if (progBlit != 0) { GLES20.glDeleteProgram(progBlit); progBlit = 0; }
        if (progEasu != 0) { GLES20.glDeleteProgram(progEasu); progEasu = 0; }
        if (progRcas != 0) { GLES20.glDeleteProgram(progRcas); progRcas = 0; }
        if (progRcasOes != 0) { GLES20.glDeleteProgram(progRcasOes); progRcasOes = 0; }
        // Note: we don't track/delete vertex shaders separately; programs own them once linked
    }

    private void destroyEgl() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            }
            if (eglWindowSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglWindowSurface);
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
            }
            EGL14.eglTerminate(eglDisplay);
        } catch (Throwable ignored) {}
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglWindowSurface = EGL14.EGL_NO_SURFACE;
    }

    private void bindQuad(int /*unused*/ prog) {
        if (hasVao && vao != 0) {
            try { GLES30.glBindVertexArray(vao); return; } catch (Throwable ignored) { /* fallback */ }
        }
        // VBO fallback (ES2/ES3): attributes at locations 0/1
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
        // Small GL optimizations for video
        try { GLES20.glDisable(GLES20.GL_DITHER); } catch (Throwable ignored) {}
        try { GLES20.glDisable(GLES20.GL_SCISSOR_TEST); } catch (Throwable ignored) {}
        try { GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1); } catch (Throwable ignored) {}
        fixedStateApplied = true;
    }

    private void setTex2DFilter(boolean toNearest) {
        if (twoDNearest == toNearest) return;
        twoDNearest = toNearest;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
    }

    // Only MIN/MAG here; WRAP set once at creation/reattach
    private void setOesFilter(boolean toNearest) {
        if (oesNearest == toNearest) return;
        oesNearest = toNearest;
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, toNearest ? GLES20.GL_NEAREST : GLES20.GL_LINEAR);
    }

    private void configureOesStaticParams() {
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
    // Returns gamma compensation factor to apply in final RCAS pass.
    // Only active when FSR is enabled and mode is not "none".
    //
    // IMPORTANT:
    //  - For HDR + Direct Present (GPU path), we MUST NOT touch gamma.
    //    The decoder + display pipeline already handle the HDR transfer function.
    private float getFsrGammaComp() {
        boolean hdrActive = false;
        try {
            hdrActive = Game.isHdrStreamActive();
        } catch (Throwable ignored) {}

        boolean gpuPath = (prefs != null && prefs.gpuPathMode);

        // In HDR + GPU direct path, we must not alter gamma.
        if (hdrActive && gpuPath) {
            return 1.0f;
        }

        if (prefs == null || !prefs.videoUpscaleEnable) return 1.0f;
        final String m = prefs.videoUpscaleMode;
        if (m == null || "none".equals(m)) return 1.0f;
        return FSR_GAMMA_COMPENSATION;
    }



    // Map UI sharpness (0..1) -> internal RCAS strength.
    private static float mapUiSharpToInternal(float ui, boolean nearNative, float upscaleRatio) {
        float s = clamp01(ui);
        if (s <= SHARPNESS_DEADZONE) return 0f;             // dead-zone
        s = (s - SHARPNESS_DEADZONE) / (1.0f - SHARPNESS_DEADZONE); // rebase to 0..1
        s = (float) (1.0 - Math.exp(-SHARPNESS_EXP_FACTOR * s));    // eased
        s = (float) Math.pow(s, SHARPNESS_GAMMA);                   // emphasize highs

        // Base caps (existing behavior)
        float cap = nearNative ? 0.32f : 0.55f;

        // If src is <50% of dst (upscaleRatio > 2.0), allow stronger sharpening progressively.
        if (upscaleRatio >= LOW_RES_UPSCALE_RATIO_START) {
            float t = (upscaleRatio - LOW_RES_UPSCALE_RATIO_START) /
                    (LOW_RES_UPSCALE_RATIO_FULL - LOW_RES_UPSCALE_RATIO_START);
            t = clamp01(t);
            cap = Math.min(MAX_INTERNAL_SHARPNESS, cap + (LOW_RES_SHARPNESS_CAP_BONUS * t));
        }

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

    /** Link helper: optionally bind attributes for ES2 (no layout qualifiers). */
    private static int linkProgram(int vs, String fsSrc, boolean bindAttribs) {
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        if (bindAttribs) {
            GLES20.glBindAttribLocation(p, 0, "aPos");
            GLES20.glBindAttribLocation(p, 1, "aUv");
        }
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
    // --- ES3 variants ---
    private static final String VS_ES3 =
            "#version 300 es\n" +
                    "precision highp float;\n" +
                    "layout(location=0) in vec2 aPos;\n" +
                    "layout(location=1) in vec2 aUv;\n" +
                    "out vec2 vUv;\n" +
                    "void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0);}";

    // Specialized VS for RCAS_OES (precompute steps to save ALU in FS)
    private static final String VS_RCAS_OES_ES3 =
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

    private static final String FS_OES_BLIT_ES3 =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision highp float;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "void main(){\n" +
                    "  vec2 uv=(uTexMatrix*vec4(vUv,0.0,1.0)).xy;\n" +
                    "  vec3 c = texture(uTex, uv).rgb;\n" +
                    "  fragColor = vec4(c, 1.0);\n" +
                    "}";

    private static final String FS_EASU_ES3 =
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

    private static final String FS_RCAS_ES3 =
            "#version 300 es\n" +
                    "#ifdef USE_OES\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "#endif\n" +
                    "precision highp float;\n" + // highp to reduce banding
                    "#ifdef USE_OES\n" +
                    "#ifdef RCAS_OES_VS\n" +
                    "in vec2 vUv0;\n" +
                    "in vec2 vStepX;\n" +
                    "in vec2 vStepY;\n" +
                    "#endif\n" +
                    "#endif\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "#ifdef USE_OES\n" +
                    "uniform samplerExternalOES uTexOES;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "#else\n" +
                    "uniform sampler2D uUpscaled;\n" +
                    "#endif\n" +
                    "uniform vec2  uInvDstSize;\n" +
                    "uniform float uSharp;\n" +
                    "uniform float uGamma;\n" +
                    "float luma(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }\n" +
                    "#ifdef USE_OES\n" +
                    "void main(){\n" +
                    "  vec2 texel = uInvDstSize;\n" +
                    "#ifdef RCAS_OES_VS\n" +
                    "  vec2 uv0   = vUv0;\n" +
                    "  vec2 stepX = vStepX;\n" +
                    "  vec2 stepY = vStepY;\n" +
                    "#else\n" +
                    "  vec2 uv0   = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;\n" +
                    "  vec2 stepX = (uTexMatrix * vec4(texel.x, 0.0, 0.0, 0.0)).xy;\n" +
                    "  vec2 stepY = (uTexMatrix * vec4(0.0, texel.y, 0.0, 0.0)).xy;\n" +
                    "#endif\n" +
                    "  vec3 c  = texture(uTexOES, uv0).rgb;\n" +
                    "  vec3 rx = texture(uTexOES, uv0 + stepX).rgb;\n" +
                    "  vec3 lx = texture(uTexOES, uv0 - stepX).rgb;\n" +
                    "  vec3 ty = texture(uTexOES, uv0 + stepY).rgb;\n" +
                    "  vec3 by = texture(uTexOES, uv0 - stepY).rgb;\n" +
                    "#else\n" +
                    "void main(){\n" +
                    "  vec2 texel = uInvDstSize;\n" +
                    "  vec2 uv0 = vUv;\n" +
                    "  vec3 c  = texture(uUpscaled, uv0).rgb;\n" +
                    "  vec3 rx = texture(uUpscaled, uv0 + vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 lx = texture(uUpscaled, uv0 - vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 ty = texture(uUpscaled, uv0 + vec2(0.0, texel.y)).rgb;\n" +
                    "  vec3 by = texture(uUpscaled, uv0 - vec2(0.0, texel.y)).rgb;\n" +
                    "#endif\n" +
                    "  vec3 blur4 = 0.25*(rx + lx + ty + by);\n" +
                    "  vec3 detail = c - blur4;\n" +
                    "  vec3 sgn = sign(detail);\n" +
                    "  detail = max(abs(detail) - vec3(1.0/255.0), vec3(0.0)) * sgn;\n" +
                    "  float gx = luma(rx) - luma(lx);\n" +
                    "  float gy = luma(ty) - luma(by);\n" +
                    "  float edgeW = 1.0 / (1.0 + 8.0*(gx*gx + gy*gy));\n" +
                    "  float k = 1.2 * clamp(uSharp, 0.0, 1.0);\n" +
                    "  vec3 outc = clamp(c + detail * (k*edgeW), 0.0, 1.0);\n" +
                    "  vec3 lo = min(min(min(lx,rx),ty),by);\n" +
                    "  vec3 hi = max(max(max(lx,rx),ty),by);\n" +
                    "  float pad = 0.012 + 0.06*clamp(uSharp,0.0,1.0);\n" +
                    "  outc = clamp(outc, lo - vec3(pad), hi + vec3(pad));\n" +
                    "  // Gamma compensation to fix low gamma / dark midtones when FSR is active\n" +
                    "  outc = pow(outc, vec3(1.0 / max(uGamma, 0.001)));\n" +
                    "  fragColor = vec4(outc, 1.0);\n" +
                    "}";

    // --- ES2 variants (fallback) ---
    private static final String VS_ES2 =
            "precision highp float;\n" +
                    "attribute vec2 aPos;\n" +
                    "attribute vec2 aUv;\n" +
                    "varying vec2 vUv;\n" +
                    "void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0);}";

    private static final String FS_OES_BLIT_ES2 =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vUv;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "void main(){\n" +
                    "  vec2 uv=(uTexMatrix*vec4(vUv,0.0,1.0)).xy;\n" +
                    "  vec3 c = texture2D(uTex, uv).rgb;\n" +
                    "  gl_FragColor = vec4(c, 1.0);\n" +
                    "}";

    private static final String FS_EASU_ES2 =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "varying vec2 vUv;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform vec2 uInvSrcSize;\n" +
                    "uniform mat4 uTexMatrix;\n" +
                    "float luma(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }\n" +
                    "void main(){\n" +
                    "  vec2 uv = (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy;\n" +
                    "  vec3 c  = texture2D(uTex, uv).rgb;\n" +
                    "  vec3 rx = texture2D(uTex, uv + vec2(uInvSrcSize.x, 0.0)).rgb;\n" +
                    "  vec3 lx = texture2D(uTex, uv - vec2(uInvSrcSize.x, 0.0)).rgb;\n" +
                    "  vec3 ty = texture2D(uTex, uv + vec2(0.0, uInvSrcSize.y)).rgb;\n" +
                    "  vec3 by = texture2D(uTex, uv - vec2(0.0, uInvSrcSize.y)).rgb;\n" +
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
                    "  gl_FragColor = vec4(clamp(up, 0.0, 1.0), 1.0);\n" +
                    "}";

    private static final String FS_RCAS_ES2 =
            "precision mediump float;\n" +
                    "varying vec2 vUv;\n" +
                    "uniform sampler2D uUpscaled;\n" +
                    "uniform vec2  uInvDstSize;\n" +
                    "uniform float uSharp;\n" +
                    "uniform float uGamma;\n" +
                    "float luma(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }\n" +
                    "void main(){\n" +
                    "  vec2 texel = uInvDstSize;\n" +
                    "  vec2 uv0 = vUv;\n" +
                    "  vec3 c  = texture2D(uUpscaled, uv0).rgb;\n" +
                    "  vec3 rx = texture2D(uUpscaled, uv0 + vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 lx = texture2D(uUpscaled, uv0 - vec2(texel.x, 0.0)).rgb;\n" +
                    "  vec3 ty = texture2D(uUpscaled, uv0 + vec2(0.0, texel.y)).rgb;\n" +
                    "  vec3 by = texture2D(uUpscaled, uv0 - vec2(0.0, texel.y)).rgb;\n" +
                    "  vec3 blur4 = 0.25*(rx + lx + ty + by);\n" +
                    "  vec3 detail = c - blur4;\n" +
                    "  vec3 sgn = sign(detail);\n" +
                    "  detail = max(abs(detail) - vec3(1.0/255.0), vec3(0.0)) * sgn;\n" +
                    "  float gx = luma(rx) - luma(lx);\n" +
                    "  float gy = luma(ty) - luma(by);\n" +
                    "  float edgeW = 1.0 / (1.0 + 8.0*(gx*gx + gy*gy));\n" +
                    "  float k = 1.2 * clamp(uSharp, 0.0, 1.0);\n" +
                    "  vec3 outc = clamp(c + detail * (k*edgeW), 0.0, 1.0);\n" +
                    "  vec3 lo = min(min(min(lx,rx),ty),by);\n" +
                    "  vec3 hi = max(max(max(lx,rx),ty),by);\n" +
                    "  float pad = 0.012 + 0.06*clamp(uSharp,0.0,1.0);\n" +
                    "  outc = clamp(outc, lo - vec3(pad), hi + vec3(pad));\n" +
                    "  // Gamma compensation to fix low gamma / dark midtones when FSR is active\n" +
                    "  outc = pow(outc, vec3(1.0 / max(uGamma, 0.001)));\n" +
                    "  gl_FragColor = vec4(outc, 1.0);\n" +
                    "}";

    // ===== FSR telemetry controls =====
    public void setFsrDebugEnabled(boolean enabled) { __fsr.enabled = enabled; }
    public String getFsrOverlayLine() { return __fsrOverlay; }

    /**
     * RCAS_OES health-check (ES3): render into a 2x2 FBO and verify non-zero output.
     * Runs only after first content is latched (to avoid false negatives).
     */
    private void checkRcasOesHealthOnce(int /*dstW*/ _dstW, int /*dstH*/ _dstH) {
        if (!isEs3) return;
        if (rcasOesChecked) return;
        if (progRcasOes == 0) return;
        if (lastFrameTexTimestampNs == 0L) return; // wait until we have content

        rcasOesChecked = true;

        int[] fboId = new int[1];
        int[] texId = new int[1];
        GLES20.glGenFramebuffers(1, fboId, 0);
        GLES20.glGenTextures(1, texId, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 2, 2, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texId[0], 0);
        if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteTextures(1, texId, 0);
            GLES20.glDeleteFramebuffers(1, fboId, 0);
            return;
        }

        GLES20.glViewport(0, 0, 2, 2);
        GLES20.glUseProgram(progRcasOes);
        bindQuad(progRcasOes);
        // For a 2x2 FBO, 1/width = 0.5, 1/height = 0.5
        GLES20.glUniform2f(rcasOes_uInvDst, 0.5f, 0.5f);
        GLES20.glUniform1f(rcasOes_uSharp, mapUiSharpToInternal(0.2f, true, 1.0f));
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

        // Restore OES filter to LINEAR after the test
        setOesFilter(false);

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteTextures(1, texId, 0);
        GLES20.glDeleteFramebuffers(1, fboId, 0);

        try { LimeLog.info("RCAS_OES health=" + rcasOesHealthy); } catch (Throwable ignored) {}

    }

    // --- GPU Kick helpers (GL path) ---
    private void initGpuKickIfNeeded() {
        if (!enableGpuKick) return;
        int[] ids = new int[1];

        // Detect GLES30/VAO availability
        kickHasVAO = false;
        try {
            android.opengl.GLES30.glGetString(android.opengl.GLES30.GL_VERSION);
            kickHasVAO = true;
        } catch (Throwable ignored) {}

        // Tiny texture + FBO
        GLES20.glGenTextures(1, ids, 0);
        kickTex = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, kickTex);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 8, 8, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        GLES20.glGenFramebuffers(1, ids, 0);
        kickFbo = ids[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, kickFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, kickTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // Minimal shader
        final String vs = "attribute vec2 aPos; void main(){ gl_Position=vec4(aPos,0.0,1.0);}";
        final String fs = "precision mediump float; void main(){ vec3 c=vec3(0.5); c=normalize(c*1.001+0.0001); gl_FragColor=vec4(c,1.0);}";

        int v = compileKickShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = compileKickShader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) {
            // Disable kick path gracefully on shader failure
            try { if (v != 0) GLES20.glDeleteShader(v); } catch (Throwable ignored) {}
            try { if (f != 0) GLES20.glDeleteShader(f); } catch (Throwable ignored) {}
            kickProg = 0;
            return;
        }

        kickProg = GLES20.glCreateProgram();
        GLES20.glAttachShader(kickProg, v);
        GLES20.glAttachShader(kickProg, f);
        GLES20.glLinkProgram(kickProg);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(kickProg, GLES20.GL_LINK_STATUS, ok, 0);
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        if (ok[0] == 0) {
            try { GLES20.glDeleteProgram(kickProg); } catch (Throwable ignored) {}
            kickProg = 0;
            return;
        }

        // Cache attribute location once
        kickPosLoc = GLES20.glGetAttribLocation(kickProg, "aPos");

        // Big triangle VBO
        float[] tri = new float[]{ -1f,-1f, 3f,-1f, -1f,3f };
        GLES20.glGenBuffers(1, ids, 0);
        kickVbo = ids[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, kickVbo);
        java.nio.FloatBuffer fb = java.nio.ByteBuffer.allocateDirect(tri.length*4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(tri).flip();
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, tri.length*4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        // Our own VAO if available
        if (kickHasVAO) {
            try {
                int[] vaoId = new int[1];
                android.opengl.GLES30.glGenVertexArrays(1, vaoId, 0);
                kickVao = vaoId[0];
            } catch (Throwable ignored) { kickHasVAO = false; kickVao = 0; }
        }
    }

    private void gpuKickOnceGL() {
        if (!enableGpuKick || kickFbo == 0 || kickProg == 0) return;

        // Save state
        GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, kickTmp1, 0);
        int oldFbo = kickTmp1[0];

        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, kickTmp4, 0);
        int vpX = kickTmp4[0], vpY = kickTmp4[1], vpW = kickTmp4[2], vpH = kickTmp4[3];

        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM, kickTmp1, 0);
        int oldProg = kickTmp1[0];

        GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING, kickTmp1, 0);
        int oldArrayBuf = kickTmp1[0];

        int oldVao = 0;
        if (kickHasVAO) {
            try {
                android.opengl.GLES30.glGetIntegerv(android.opengl.GLES30.GL_VERTEX_ARRAY_BINDING, kickTmp1, 0);
                oldVao = kickTmp1[0];
            } catch (Throwable ignored) { kickHasVAO = false; oldVao = 0; }
        }

        try {
            // Tiny draw
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, kickFbo);
            GLES20.glViewport(0, 0, 8, 8);
            GLES20.glUseProgram(kickProg);

            if (kickHasVAO) {
                try { android.opengl.GLES30.glBindVertexArray(kickVao); } catch (Throwable ignored) {}
            }

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, kickVbo);
            GLES20.glEnableVertexAttribArray(kickPosLoc);
            GLES20.glVertexAttribPointer(kickPosLoc, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            GLES20.glDisableVertexAttribArray(kickPosLoc);

            if (kickHasVAO) {
                try { android.opengl.GLES30.glBindVertexArray(0); } catch (Throwable ignored) {}
            }

            GLES20.glFlush();
        } finally {
            // Restore state
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, oldArrayBuf);
            GLES20.glUseProgram(oldProg);
            GLES20.glViewport(vpX, vpY, vpW, vpH);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, oldFbo);
            if (kickHasVAO) {
                try { android.opengl.GLES30.glBindVertexArray(oldVao); } catch (Throwable ignored) {}
            }
        }
    }

    private void releaseGpuKick() {
        try {
            if (kickHasVAO && kickVao != 0) {
                int[] vao = { kickVao };
                try { android.opengl.GLES30.glDeleteVertexArrays(1, vao, 0); } catch (Throwable ignored) {}
                kickVao = 0; kickHasVAO = false;
            }
            if (kickVbo != 0) { int[] b = { kickVbo }; GLES20.glDeleteBuffers(1, b, 0); kickVbo = 0; }
            if (kickProg != 0) { GLES20.glDeleteProgram(kickProg); kickProg = 0; }
            if (kickTex != 0) { int[] t = { kickTex }; GLES20.glDeleteTextures(1, t, 0); kickTex = 0; }
            if (kickFbo != 0) { int[] f = { kickFbo }; GLES20.glDeleteFramebuffers(1, f, 0); kickFbo = 0; }
        } catch (Throwable ignored) {}
    }

    private static int compileKickShader(int type, String src){
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    // --- GL error helper (drain all errors) ---
    private void glCheckError(String where) {
        try {
            int err;
            while ((err = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
                LimeLog.warning("GL error after " + where + ": 0x" + Integer.toHexString(err));
            }
        } catch (Throwable ignored) {}
    }

    // Re-attach decoder SurfaceTexture to the new GL context with a fresh OES texture
    private void ensureOesAttachmentAfterReinit() {
        if (decoderSurfaceTex == null) return;
        // Create a new external texture name in the *new* context
        GLES20.glGenTextures(1, tmpIntArray, 0);
        oesTexId = tmpIntArray[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        configureOesStaticParams(); // WRAP set once
        try {
            decoderSurfaceTex.attachToGLContext(oesTexId);
        } catch (RuntimeException alreadyAttached) {
            // If the ST still thinks it's attached somewhere, detach and try again
            try {
                decoderSurfaceTex.detachFromGLContext();
                decoderSurfaceTex.attachToGLContext(oesTexId);
            } catch (Throwable t) {
                try { LimeLog.warning("FSR: ST reattach failed: " + t.getMessage()); } catch (Throwable ignored) {}
            }
        }
    }

    // --- EGL/GL recovery path on persistent swap failures ---
    private boolean reinitEglAndGl() {
        try { destroyGl(); } catch (Throwable ignored) {}
        try { destroyEgl(); } catch (Throwable ignored) {}
        initEglAndGl();
        if (!isGlReady()) return false;
        // After recreating the context, the OES texture name is gone.
        // Recreate and reattach SurfaceTexture -> current context.
        ensureOesAttachmentAfterReinit();
        try {
            if (decoderSurfaceTex != null) {
                decoderSurfaceTex.setDefaultBufferSize(srcW, srcH);
            }
        } catch (Throwable ignored) {}
        blitMatDirty = easuMatDirty = rcasOesMatDirty = true;
        texMatrixDirty = true;
        // Wake loop so we don't idle post-reinit
        synchronized (frameLock) { frameLock.notifyAll(); }
        return true;
    }
}
