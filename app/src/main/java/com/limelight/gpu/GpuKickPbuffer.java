package com.limelight.gpu;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Headless EGL pbuffer "GPU kick" for Direct Present or non-GL paths.
 * Create/init on the renderer thread. Call kickOnce() once per presented frame.
 */
public final class GpuKickPbuffer {
    private static final String TAG = "GpuKickPbuffer";
    private static final boolean DEBUG = false;

    private volatile boolean enabled = false;
    private volatile boolean inited = false;

    // Simple debug counter to avoid log spam
    private int debugFrameCounter = 0;
    private static final int DEBUG_LOG_EVERY_N_FRAMES = 60; // es: 1 log al secondo a 60 FPS

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private int program = 0;
    private int vbo = 0;
    private int positionAttr = 0; // we bind 'aPos' to location 0 explicitly

    private static final String VERTEX_SHADER_SOURCE =
            "attribute vec2 aPos;\n" +
                    "void main(){ gl_Position = vec4(aPos, 0.0, 1.0);}";

    private static final String FRAGMENT_SHADER_SOURCE =
            "precision mediump float;\n" +
                    "void main(){ vec3 c=vec3(0.5); c=normalize(c*1.001+0.0001); gl_FragColor=vec4(c,1.0);}";

    // Full-screen big triangle
    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f,  3.0f
    };

    private static final int PBUFFER_WIDTH = 32;
    private static final int PBUFFER_HEIGHT = 32;

    public void setEnabled(boolean enabled) {
        boolean was = this.enabled;
        this.enabled = enabled;
        // If turned OFF while initialized, tear down immediately
        if (!enabled && was && inited) {
            try { teardownAll(); } catch (Throwable ignored) {}
        }
    }
    public boolean isEnabled() { return enabled; }
    public boolean isInitialized() { return inited; }

    /** Initialize EGL and GL resources on THIS thread. */
    public void initOnThisThread() {
        if (!enabled || inited) return;
        try {
            if (!initializeEGL()) return;
            if (!initializeGLResources()) { teardownAll(); return; }
            inited = true;
            if (DEBUG) Log.d(TAG, "initialized");
        } catch (Throwable t) {
            if (DEBUG) Log.e(TAG, "init failed", t);
            teardownAll();
        }
    }

    private boolean initializeEGL() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) { if (DEBUG) Log.e(TAG, "no display"); return false; }

        int[] ver = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) { if (DEBUG) Log.e(TAG, "eglInit failed"); return false; }

        int[] cfgAttrs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,    EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] cfgs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, cfgAttrs, 0, cfgs, 0, 1, num, 0) || num[0] == 0) {
            if (DEBUG) Log.e(TAG, "no config");
            return false;
        }
        EGLConfig cfg = cfgs[0];

        int[] ctxAttrs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, cfg, EGL14.EGL_NO_CONTEXT, ctxAttrs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) { if (DEBUG) Log.e(TAG, "ctx fail"); return false; }

        int[] pbAttrs = { EGL14.EGL_WIDTH, PBUFFER_WIDTH, EGL14.EGL_HEIGHT, PBUFFER_HEIGHT, EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, cfg, pbAttrs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) { if (DEBUG) Log.e(TAG, "pbuffer fail"); return false; }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            if (DEBUG) Log.e(TAG, "makeCurrent fail");
            return false;
        }
        return true;
    }

    private boolean initializeGLResources() {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER_SOURCE);
        if (vs == 0) return false;
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER_SOURCE);
        if (fs == 0) { GLES20.glDeleteShader(vs); return false; }

        program = GLES20.glCreateProgram();
        if (program == 0) { GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs); return false; }

        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);

        // Force 'aPos' to location 0 (prevents -1 on some drivers)
        GLES20.glBindAttribLocation(program, /*index*/0, "aPos");
        positionAttr = 0;

        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);

        int[] linkOK = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkOK, 0);
        if (linkOK[0] == 0) {
            if (DEBUG) Log.e(TAG, "link fail: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            program = 0;
            return false;
        }

        int[] buf = new int[1];
        GLES20.glGenBuffers(1, buf, 0);
        vbo = buf[0];
        if (vbo == 0) return false;

        FloatBuffer fb = ByteBuffer.allocateDirect(TRIANGLE_VERTICES.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(TRIANGLE_VERTICES).position(0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, TRIANGLE_VERTICES.length * 4, fb, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        return true;
    }

    /** Ensure our EGL context/surfaces are current. Returns false if cannot. */
    private boolean ensureCurrent() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY ||
                eglContext == EGL14.EGL_NO_CONTEXT ||
                eglSurface == EGL14.EGL_NO_SURFACE) return false;

        if (EGL14.eglGetCurrentContext() == eglContext &&
                EGL14.eglGetCurrentDisplay() == eglDisplay &&
                EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW) == eglSurface) {
            return true; // already current
        }
        return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
    }

    /** Execute one minimal draw call; call once per presented frame. */
    public void kickOnce() {
        if (!enabled || !inited) return;

        if (DEBUG) {
            debugFrameCounter++;
            if (debugFrameCounter % DEBUG_LOG_EVERY_N_FRAMES == 0) {
                Log.d(TAG, "kickOnce() called, total frames=" + debugFrameCounter);
            }
        }

        try {
            if (!ensureCurrent()) {
                // Context might be lost; try to rebuild once
                if (DEBUG) Log.w(TAG, "context not current; rebuilding");
                teardownAll();
                if (enabled) initOnThisThread();
                if (!inited || !ensureCurrent()) return;
            }

            GLES20.glViewport(0, 0, PBUFFER_WIDTH, PBUFFER_HEIGHT);
            GLES20.glUseProgram(program);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo);
            GLES20.glEnableVertexAttribArray(positionAttr);
            GLES20.glVertexAttribPointer(positionAttr, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
            GLES20.glDisableVertexAttribArray(positionAttr);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glUseProgram(0);
            GLES20.glFinish();
        } catch (Throwable t) {
            if (DEBUG) Log.e(TAG, "kickOnce error", t);
        }
    }

    /** Release all resources; call on the same thread used for initialization. */
    public void release() {
        if (DEBUG) Log.d(TAG, "release()");
        teardownAll();
    }

    private void teardownAll() {
        // Make our context current before deleting GL objects (if possible)
        try { ensureCurrent(); } catch (Throwable ignored) {}
        try {
            if (program != 0) { GLES20.glDeleteProgram(program); program = 0; }
            if (vbo != 0)     { int[] b = { vbo }; GLES20.glDeleteBuffers(1, b, 0); vbo = 0; }
        } catch (Throwable t) {
            if (DEBUG) Log.w(TAG, "GL delete error", t);
        }
        teardownEGL();
        inited = false;
    }

    private void teardownEGL() {
        try {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                // Unbind first
                EGL14.eglMakeCurrent(eglDisplay,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                }
                EGL14.eglTerminate(eglDisplay);
            }
        } catch (Throwable t) {
            if (DEBUG) Log.w(TAG, "EGL teardown error", t);
        } finally {
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglSurface = EGL14.EGL_NO_SURFACE;
            eglContext = EGL14.EGL_NO_CONTEXT;
        }
    }

    private int compileShader(int type, String source) {
        int s = GLES20.glCreateShader(type);
        if (s == 0) return 0;
        GLES20.glShaderSource(s, source);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            if (DEBUG) Log.e(TAG, "shader compile error: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }
}
