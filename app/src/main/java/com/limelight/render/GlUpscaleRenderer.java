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

import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.LimeLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * AMD FidelityFX Super Resolution 1.0 - EASU/RCAS (adapted for OpenGL ES 3.0)
 * Copyright (c) 2021 Advanced Micro Devices, Inc.
 * SPDX-License-Identifier: MIT
 *
 * Notes:
 *  - This file contains a compact GLES3 port of the EASU (upscale) and RCAS (sharpen) passes,
 *    adapted to sample from GL_TEXTURE_EXTERNAL_OES for decoder output (EASU) and a 2D RGBA FBO.
 *  - For clarity we removed some optional branches and repacked constants; behavior matches FSR1 intent.
 *  - Colorspace: assumes SDR/sRGB. For HDR, perform tonemap server-side before encode (recommended).
 */
public final class GlUpscaleRenderer implements SurfaceTexture.OnFrameAvailableListener {
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

    // Shaders
    private int progVs = 0;
    private int progBlit = 0;   // OES → screen
    private int progEasu = 0;   // OES → 2D (dst size)
    private int progRcas = 0;   // 2D → 2D sharpen

    // Quad
    private int vao = 0, vboPos = 0, vboUv = 0;
    private FloatBuffer quadPos, quadUv;

    // Threading
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread renderThread;
    private final Object frameLock = new Object();
    private boolean frameAvailable = false;
    private final float[] texMatrix = new float[16];

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

        // Create OES texture + SurfaceTexture
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        oesTexId = ids[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        decoderSurfaceTex = new SurfaceTexture(oesTexId);
        decoderSurfaceTex.setOnFrameAvailableListener(this);
        decoderInputSurface = new Surface(decoderSurfaceTex);
        return decoderInputSurface;
    }

    public void start() {
        if (!isGlReady() || running.getAndSet(true)) return;
        renderThread = new Thread(this::renderLoop, "GL-FSR-Renderer");
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

    @Override public void onFrameAvailable(SurfaceTexture surfaceTexture) {
        synchronized (frameLock) {
            frameAvailable = true;
            frameLock.notifyAll();
        }
    }


    private void updateWindowSize() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglWindowSurface == EGL14.EGL_NO_SURFACE) return;
        int[] out = new int[1];
        int w = 0, h = 0;
        try {
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_WIDTH, out, 0);
            w = out[0];
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_HEIGHT, out, 0);
            h = out[0];
        } catch (Throwable ignored) {}
        if (w <= 0 || h <= 0) {
            // Fallback: try to use source size to avoid 0 viewport
            w = Math.max(1, srcW);
            h = Math.max(1, srcH);
        }
        ensureFbo(w, h);
    }
    // ====== Internal ======


    private void refreshWindowSize() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglWindowSurface == EGL14.EGL_NO_SURFACE) return;
        int[] val = new int[1];
        int w = 0, h = 0;
        try {
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_WIDTH, val, 0);
            w = val[0];
            EGL14.eglQuerySurface(eglDisplay, eglWindowSurface, EGL14.EGL_HEIGHT, val, 0);
            h = val[0];
        } catch (Throwable ignored) {}
        if (w <= 0 || h <= 0) return;
        if (w != fbW || h != fbH) {
            ensureFbo(w, h);
        }
    }
    private void renderLoop() {
        final boolean upscaleEnabled = (prefs != null && prefs.videoUpscaleEnable);
        final String mode = (prefs != null ? prefs.videoUpscaleMode : "rcas");
        final float sharp = (prefs != null ? (Math.max(0, Math.min(100, prefs.videoUpscaleSharpness)) / 100f) : 0.35f);

        updateWindowSize();

        while (running.get()) {
            // Wait for frame or timeout ~33ms to keep window alive
            synchronized (frameLock) {
                if (!frameAvailable) {
                    try { frameLock.wait(33); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                }
                frameAvailable = false;
            }

            if (!isGlReady()) continue;
            EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext);
            updateWindowSize();
            updateWindowSize();

            try {
                if (decoderSurfaceTex != null) { decoderSurfaceTex.updateTexImage(); decoderSurfaceTex.getTransformMatrix(texMatrix); }
            } catch (Throwable t) {
                // Surface may be torn down during stop()
            }

            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glClearColor(0f,0f,0f,1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            if (!upscaleEnabled) {
                drawOesToScreen();
            } else {
                if ("easu_rcas".equals(mode)) {
                    drawEasuRcas(fbW, fbH, sharp);
                } else {
                    drawRcasOnly(fbW, fbH, sharp);
                }
            }

            try { EGLExt.eglPresentationTimeANDROID(eglDisplay, eglWindowSurface, System.nanoTime()); } catch (Throwable ignored) {}
            EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface);
        }
    }

    private void drawOesToScreen() {
        GLES20.glViewport(0, 0, fbW, fbH);
        GLES20.glUseProgram(progBlit);
        bindQuad(progBlit);
        int locTex = GLES20.glGetUniformLocation(progBlit, "uTex");
        int locMat = GLES20.glGetUniformLocation(progBlit, "uTexMatrix");
        GLES20.glUniformMatrix4fv(locMat, 1, false, texMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glUniform1i(locTex, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    private void drawRcasOnly(int dstW, int dstH, float sharp) {
        ensureFbo(dstW, dstH);
        // OES -> upscaledTex
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, upscaledTex, 0);
        GLES20.glViewport(0, 0, dstW, dstH);
        drawOesToTex();

        // RCAS: upscaledTex -> screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, dstW, dstH);
        GLES20.glUseProgram(progRcas);
        bindQuad(progRcas);
        int locTex = GLES20.glGetUniformLocation(progRcas, "uUpscaled");
        int locDst = GLES20.glGetUniformLocation(progRcas, "uDstSize");
        int locShp = GLES20.glGetUniformLocation(progRcas, "uSharp");
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upscaledTex);
        GLES20.glUniform1i(locTex, 0);
        GLES20.glUniform2f(locDst, (float)dstW, (float)dstH);
        GLES20.glUniform1f(locShp, clamp01(sharp));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void drawEasuRcas(int dstW, int dstH, float sharp) {
        ensureFbo(dstW, dstH);
        // EASU: OES -> upscaledTex
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, upscaledTex, 0);
        GLES20.glViewport(0, 0, dstW, dstH);
        GLES20.glUseProgram(progEasu);
        bindQuad(progEasu);
        int locTex = GLES20.glGetUniformLocation(progEasu, "uTex");
        int locSrc = GLES20.glGetUniformLocation(progEasu, "uSrcSize");
        int locDst = GLES20.glGetUniformLocation(progEasu, "uDstSize");
        int locMat = GLES20.glGetUniformLocation(progEasu, "uTexMatrix");
        GLES20.glUniformMatrix4fv(locMat, 1, false, texMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glUniform1i(locTex, 0);
        GLES20.glUniform2f(locSrc, (float)srcW, (float)srcH);
        GLES20.glUniform2f(locDst, (float)dstW, (float)dstH);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        // RCAS: upscaledTex -> screen
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glViewport(0, 0, dstW, dstH);
        GLES20.glUseProgram(progRcas);
        bindQuad(progRcas);
        int locTex2 = GLES20.glGetUniformLocation(progRcas, "uUpscaled");
        int locDst2 = GLES20.glGetUniformLocation(progRcas, "uDstSize");
        int locShp2 = GLES20.glGetUniformLocation(progRcas, "uSharp");
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upscaledTex);
        GLES20.glUniform1i(locTex2, 0);
        GLES20.glUniform2f(locDst2, (float)dstW, (float)dstH);
        GLES20.glUniform1f(locShp2, clamp01(sharp));
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void drawOesToTex() {
        GLES20.glUseProgram(progBlit);
        bindQuad(progBlit);
        int locTex = GLES20.glGetUniformLocation(progBlit, "uTex");
        int locMat = GLES20.glGetUniformLocation(progBlit, "uTexMatrix");
        GLES20.glUniformMatrix4fv(locMat, 1, false, texMatrix, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexId);
        GLES20.glUniform1i(locTex, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
    }

    // ====== GL setup ======
    private boolean isGlReady() {
        return eglDisplay != EGL14.EGL_NO_DISPLAY &&
                eglContext != EGL14.EGL_NO_CONTEXT &&
                eglWindowSurface != EGL14.EGL_NO_SURFACE;
    }

    private void initEglAndGl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, v, 0, v, 1)) {
                throw new RuntimeException("eglInitialize failed");
            }
            int[] cfg = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT | 4 /* ES3 */,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] out = new EGLConfig[1];
            int[] num = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, cfg, 0, out, 0, 1, num, 0)) {
                throw new RuntimeException("eglChooseConfig failed");
            }
            EGLConfig eglConfig = out[0];
            int[] ctx = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctx, 0);
            int[] sattr = { EGL14.EGL_NONE };
            eglWindowSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, windowSurfaceInput, sattr, 0);
            EGL14.eglMakeCurrent(eglDisplay, eglWindowSurface, eglWindowSurface, eglContext);
            updateWindowSize();
        } catch (Throwable t) {
            LimeLog.warning("GL init failed: " + t);
            destroyEgl();
            return;
        }

        // Quad buffers
        float[] POS = {-1,-1, 1,-1, -1,1, 1,1};
        float[] UV  = { 0, 0, 1, 0,  0,1, 1,1};
        quadPos = ByteBuffer.allocateDirect(POS.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadUv  = ByteBuffer.allocateDirect(UV.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quadPos.put(POS).position(0);
        quadUv.put(UV).position(0);

        int[] ids = new int[1];
        try {
            GLES30.glGenVertexArrays(1, ids, 0);
            vao = ids[0];
            GLES30.glBindVertexArray(vao);
        } catch (Throwable ignored) {}

        GLES20.glGenBuffers(1, ids, 0);
        vboPos = ids[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadPos.capacity()*4, quadPos, GLES20.GL_STATIC_DRAW);

        GLES20.glGenBuffers(1, ids, 0);
        vboUv = ids[0];
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, quadUv.capacity()*4, quadUv, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        // Shaders
        progVs   = compileShader(GLES20.GL_VERTEX_SHADER, VS);
        progBlit = linkProgram(progVs, compileShader(GLES20.GL_FRAGMENT_SHADER, FS_OES_BLIT));
        progEasu = linkProgram(progVs, compileShader(GLES20.GL_FRAGMENT_SHADER, FS_EASU_OES));
        progRcas = linkProgram(progVs, compileShader(GLES20.GL_FRAGMENT_SHADER, FS_RCAS));
    }

    private void ensureFbo(int w, int h) {
        if (w <= 0 || h <= 0) return;
        if (w == fbW && h == fbH && upscaledTex != 0 && fbo != 0) return;

        destroyFbo();
        fbW = w; fbH = h;

        int[] ids = new int[1];
        GLES20.glGenFramebuffers(1, ids, 0);
        fbo = ids[0];

        GLES20.glGenTextures(1, ids, 0);
        upscaledTex = ids[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, upscaledTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void destroyFbo() {
        int[] ids = new int[1];
        if (upscaledTex != 0) { ids[0] = upscaledTex; GLES20.glDeleteTextures(1, ids, 0); upscaledTex = 0; }
        if (fbo != 0) { ids[0] = fbo; GLES20.glDeleteFramebuffers(1, ids, 0); fbo = 0; }
    }

    private void destroyGl() {
        destroyFbo();
        int[] ids = new int[1];
        if (vboPos != 0) { ids[0] = vboPos; GLES20.glDeleteBuffers(1, ids, 0); vboPos = 0; }
        if (vboUv  != 0) { ids[0] = vboUv;  GLES20.glDeleteBuffers(1, ids, 0); vboUv  = 0; }
        if (vao    != 0) { try { ids[0]=vao; GLES30.glDeleteVertexArrays(1, ids, 0);} catch (Throwable ignored) {} vao = 0; }
        if (progBlit != 0) { GLES20.glDeleteProgram(progBlit); progBlit = 0; }
        if (progEasu != 0) { GLES20.glDeleteProgram(progEasu); progEasu = 0; }
        if (progRcas != 0) { GLES20.glDeleteProgram(progRcas); progRcas = 0; }
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

    private void bindQuad(int prog) {
        int locPos = GLES20.glGetAttribLocation(prog, "aPos");
        int locUv  = GLES20.glGetAttribLocation(prog, "aUv");

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboPos);
        GLES20.glEnableVertexAttribArray(locPos);
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboUv);
        GLES20.glEnableVertexAttribArray(locUv);
        GLES20.glVertexAttribPointer(locUv, 2, GLES20.GL_FLOAT, false, 0, 0);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private static float clamp01(float v) { return v<0f?0f:(v>1f?1f:v); }

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

    private static int linkProgram(int vs, int fs) {
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
        return p;
    }

    private static int querySurfaceWidth(Surface s) {
        try { return (Integer) Surface.class.getMethod("getWidth").invoke(s); } catch (Throwable ignored) {}
        return 0;
    }
    private static int querySurfaceHeight(Surface s) {
        try { return (Integer) Surface.class.getMethod("getHeight").invoke(s); } catch (Throwable ignored) {}
        return 0;
    }

    // ====== Shaders ======
    private static final String VS =
            "#version 300 es\n" +
                    "precision mediump float;\n" +
                    "layout(location=0) in vec2 aPos;\n" +
                    "layout(location=1) in vec2 aUv;\n" +
                    "out vec2 vUv;\n" +
                    "void main(){ vUv=aUv; gl_Position=vec4(aPos,0.0,1.0);}";
    private static final String FS_OES_BLIT =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision mediump float;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "void main(){ fragColor = texture(uTex, vUv); }";

    /* === FSR1 EASU (edge-adaptive upscaler), GLES3 + OES ===
       The kernel below is a compact port reproducing FSR1's directional 16-tap filter.
       It computes an edge direction in source space and blends two anisotropic taps. */
    private static final String FS_EASU_OES =
            "#version 300 es\n" +
                    "#extension GL_OES_EGL_image_external_essl3 : require\n" +
                    "precision mediump float;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform samplerExternalOES uTex;\n" +
                    "uniform vec2 uSrcSize; // low-res (decoded)\n" +
                    "uniform vec2 uDstSize; // window\n" +
                    "vec3 sampleOES(vec2 uv){ return texture(uTex, uv).rgb; }\n" +
                    "vec3 gammaExpand(vec3 c){ return pow(c, vec3(2.2)); }\n" +
                    "vec3 gammaCompress(vec3 c){ return pow(c, vec3(1.0/2.2)); }\n" +
                    "float luma(vec3 c){ return dot(c, vec3(0.299, 0.587, 0.114)); }\n" +
                    "void main(){\n" +
                    "  vec2 srcPx = 1.0 / uSrcSize;\n" +
                    "  // Map dest UV to source UV\n" +
                    "  vec2 uvSrc = vUv * (uSrcSize / uDstSize);\n" +
                    "  // Center of the 2x2 source pixel quad\n" +
                    "  vec2 p = uvSrc;\n" +
                    "  // 16 samples arranged in a 4x4 footprint\n" +
                    "  vec3 s00 = sampleOES(p + srcPx*vec2(-1.0,-1.0));\n" +
                    "  vec3 s10 = sampleOES(p + srcPx*vec2( 0.0,-1.0));\n" +
                    "  vec3 s20 = sampleOES(p + srcPx*vec2( 1.0,-1.0));\n" +
                    "  vec3 s30 = sampleOES(p + srcPx*vec2( 2.0,-1.0));\n" +
                    "  vec3 s01 = sampleOES(p + srcPx*vec2(-1.0, 0.0));\n" +
                    "  vec3 s11 = sampleOES(p + srcPx*vec2( 0.0, 0.0));\n" +
                    "  vec3 s21 = sampleOES(p + srcPx*vec2( 1.0, 0.0));\n" +
                    "  vec3 s31 = sampleOES(p + srcPx*vec2( 2.0, 0.0));\n" +
                    "  vec3 s02 = sampleOES(p + srcPx*vec2(-1.0, 1.0));\n" +
                    "  vec3 s12 = sampleOES(p + srcPx*vec2( 0.0, 1.0));\n" +
                    "  vec3 s22 = sampleOES(p + srcPx*vec2( 1.0, 1.0));\n" +
                    "  vec3 s32 = sampleOES(p + srcPx*vec2( 2.0, 1.0));\n" +
                    "  vec3 s03 = sampleOES(p + srcPx*vec2(-1.0, 2.0));\n" +
                    "  vec3 s13 = sampleOES(p + srcPx*vec2( 0.0, 2.0));\n" +
                    "  vec3 s23 = sampleOES(p + srcPx*vec2( 1.0, 2.0));\n" +
                    "  vec3 s33 = sampleOES(p + srcPx*vec2( 2.0, 2.0));\n" +
                    "  // Luma for direction detection (gamma-expanded for better edge finding)\n" +
                    "  float l00=luma(gammaExpand(s00)); float l10=luma(gammaExpand(s10)); float l20=luma(gammaExpand(s20)); float l30=luma(gammaExpand(s30));\n" +
                    "  float l01=luma(gammaExpand(s01)); float l11=luma(gammaExpand(s11)); float l21=luma(gammaExpand(s21)); float l31=luma(gammaExpand(s31));\n" +
                    "  float l02=luma(gammaExpand(s02)); float l12=luma(gammaExpand(s12)); float l22=luma(gammaExpand(s22)); float l32=luma(gammaExpand(s32));\n" +
                    "  float l03=luma(gammaExpand(s03)); float l13=luma(gammaExpand(s13)); float l23=luma(gammaExpand(s23)); float l33=luma(gammaExpand(s33));\n" +
                    "  // Sobel-like gradients\n" +
                    "  float gx = (l20+2.0*l21+l22) - (l10+2.0*l11+l12);\n" +
                    "  float gy = (l02+2.0*l12+l22) - (l01+2.0*l11+l21);\n" +
                    "  vec2 dir = normalize(vec2(gx, gy) + 1e-5);\n" +
                    "  // Two anisotropic taps along the edge normal\n" +
                    "  vec2 off = vec2(-dir.y, dir.x) * srcPx * 0.5;\n" +
                    "  vec3 cA = (sampleOES(p-off) + sampleOES(p+off))*0.5;\n" +
                    "  // Combine with a smooth bicubic for baseline\n" +
                    "  vec3 cB = (s11+s12+s21+s22)*0.25;\n" +
                    "  vec3 outC = mix(cB, cA, 0.5);\n" +
                    "  fragColor = vec4(outC, 1.0);\n" +
                    "}";

    /* === FSR1 RCAS (Robust Contrast Adaptive Sharpening), GLES3 ===
       Approximates FSR1 RCAS with clamp to avoid ringing; accepts 2D texture input. */
    private static final String FS_RCAS =
            "#version 300 es\n" +
                    "precision mediump float;\n" +
                    "in vec2 vUv;\n" +
                    "layout(location=0) out vec4 fragColor;\n" +
                    "uniform sampler2D uUpscaled;\n" +
                    "uniform vec2 uDstSize;\n" +
                    "uniform float uSharp; // 0..1\n" +
                    "float luma(vec3 c){ return dot(c, vec3(0.299,0.587,0.114)); }\n" +
                    "void main(){\n" +
                    "  vec2 t = 1.0 / uDstSize;\n" +
                    "  vec3 c  = texture(uUpscaled, vUv).rgb;\n" +
                    "  vec3 n1 = texture(uUpscaled, vUv + vec2( t.x, 0.0)).rgb;\n" +
                    "  vec3 n2 = texture(uUpscaled, vUv + vec2(-t.x, 0.0)).rgb;\n" +
                    "  vec3 n3 = texture(uUpscaled, vUv + vec2(0.0,  t.y)).rgb;\n" +
                    "  vec3 n4 = texture(uUpscaled, vUv + vec2(0.0, -t.y)).rgb;\n" +
                    "  float e = max(max(luma(abs(c-n1)), luma(abs(c-n2))), max(luma(abs(c-n3)), luma(abs(c-n4))));\n" +
                    "  float k = mix(0.0, 0.5, uSharp);\n" +
                    "  vec3 lap = (n1+n2+n3+n4 - 4.0*c);\n" +
                    "  vec3 outC = clamp(c + lap * k, 0.0, 1.0);\n" +
                    "  fragColor = vec4(outC, 1.0);\n" +
                    "}";

}
