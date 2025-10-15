package com.limelight.perf;

import android.content.Context;

/** Reflection-only wrapper for Android PerformanceHintManager (ADPF).
 *  Works with compileSdk < 31. On devices < 31 or if ADPF is absent, this is a no-op. */
public final class PerfHint implements AutoCloseable {
    private final Object session; // android.os.PerformanceHintManager$Session via reflection
    private final java.lang.reflect.Method mUpdateTarget;
    private final java.lang.reflect.Method mReportActual;
    private final java.lang.reflect.Method mClose;

    private PerfHint(Object session, java.lang.reflect.Method upd, java.lang.reflect.Method rep, java.lang.reflect.Method cls) {
        this.session = session;
        this.mUpdateTarget = upd;
        this.mReportActual = rep;
        this.mClose = cls;
    }
    public boolean isActive() {
        return session != null;
    }
    /** Create a hint session for the current thread. Returns null if unsupported or failed. */
    public static PerfHint createForCurrentThread(Context ctx, long targetWorkNs) {
        try {
            // Require runtime API >= 31
            if (android.os.Build.VERSION.SDK_INT < 31 || ctx == null) return null;

            // Get PerformanceHintManager instance via reflection
            Class<?> phmClass = Class.forName("android.os.PerformanceHintManager");
            Object phm = ctx.getSystemService(phmClass);
            if (phm == null) return null;

            // Locate createHintSession(int[] tids, long targetDurationNanos)
            java.lang.reflect.Method create = phmClass.getMethod("createHintSession", int[].class, long.class);
            int tid = android.os.Process.myTid();
            Object session = create.invoke(phm, new int[]{ tid }, targetWorkNs);
            if (session == null) return null;

            // Methods on Session
            Class<?> sessClass = session.getClass();
            java.lang.reflect.Method upd = sessClass.getMethod("updateTargetWorkDuration", long.class);
            java.lang.reflect.Method rep = sessClass.getMethod("reportActualWorkDuration", long.class);
            java.lang.reflect.Method cls = sessClass.getMethod("close");

            // Set initial target
            try { upd.invoke(session, targetWorkNs); } catch (Throwable ignored) {}

            return new PerfHint(session, upd, rep, cls);
        } catch (Throwable t) {
            return null; // silent fallback
        }
    }

    /** Update the target work duration (ns). No-op if unavailable. */
    public void updateTarget(long targetWorkNs) {
        try { if (session != null) mUpdateTarget.invoke(session, targetWorkNs); } catch (Throwable ignored) {}
    }

    /** Report the actual work duration (ns). No-op if unavailable. */
    public void report(long actualWorkNs) {
        try { if (session != null) mReportActual.invoke(session, actualWorkNs); } catch (Throwable ignored) {}
    }

    @Override public void close() {
        try { if (session != null) mClose.invoke(session); } catch (Throwable ignored) {}
    }
}
