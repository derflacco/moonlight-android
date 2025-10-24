package com.limelight.perf;

import android.content.Context;

/**
 * Reflection-only wrapper for Android PerformanceHintManager (ADPF).
 * Works down to compileSdk < 31. On devices < 31 or if ADPF is absent, this is a no-op.
 *
 * Improvements over the previous version:
 * - Multi-thread sessions (decoder + renderer in one group).
 * - Optional setPreferPowerEfficiency(false) (if available).
 * - Safe clamping for report() to avoid poisoning DVFS model.
 * - Helpers for target from display Hz and simple CPU work measurement.
 * - Ability to update thread IDs by transparently recreating the session.
 */
public final class PerfHint implements AutoCloseable {
    // ---- Session + reflection state ----
    private final Context appContext;
    private final Object phm; // android.os.PerformanceHintManager
    private final java.lang.reflect.Method mCreate; // createHintSession(int[], long)
    private final java.lang.reflect.Method mUpdateTarget; // Session.updateTargetWorkDuration(long)
    private final java.lang.reflect.Method mReportActual; // Session.reportActualWorkDuration(long)
    private final java.lang.reflect.Method mClose;        // Session.close()
    private final java.lang.reflect.Method mSetPreferPower; // Session.setPreferPowerEfficiency(boolean) [optional]

    private volatile Object session; // android.os.PerformanceHintManager$Session
    private volatile long targetWorkNs;
    private volatile int[] threadIds;

    private PerfHint(Context ctx,
                     Object phm,
                     java.lang.reflect.Method create,
                     Object session,
                     java.lang.reflect.Method upd,
                     java.lang.reflect.Method rep,
                     java.lang.reflect.Method cls,
                     java.lang.reflect.Method setPreferPower,
                     int[] tids,
                     long targetWorkNs) {
        this.appContext = ctx.getApplicationContext();
        this.phm = phm;
        this.mCreate = create;
        this.session = session;
        this.mUpdateTarget = upd;
        this.mReportActual = rep;
        this.mClose = cls;
        this.mSetPreferPower = setPreferPower;
        this.threadIds = tids;
        this.targetWorkNs = targetWorkNs;
    }

    /** True if a live session exists. */
    public boolean isActive() {
        return session != null;
    }

    /** Convenience: current thread TID. */
    public static int currentTid() {
        return android.os.Process.myTid();
    }

    /** Suggest a reasonable target from display Hz: ~vsync/4. */
    public static long targetFromHz(float displayHz) {
        float hz = (displayHz > 0f ? displayHz : 60f);
        long vsyncNs = (long) (1_000_000_000L / hz);
        return Math.max(500_000L, vsyncNs / 4); // clamp to >=0.5 ms
    }

    /** Create a hint session for the current thread. Returns null if unsupported or failed. */
    public static PerfHint createForCurrentThread(Context ctx, long targetWorkNs) {
        return createForThreads(ctx, new int[]{ currentTid() }, targetWorkNs);
    }

    /** Create a hint session for the provided thread IDs. Returns null if unsupported or failed. */
    public static PerfHint createForThreads(Context ctx, int[] tids, long targetWorkNs) {
        try {
            if (ctx == null || android.os.Build.VERSION.SDK_INT < 31) return null;

            // Obtain PerformanceHintManager via reflection
            Class<?> phmClass = Class.forName("android.os.PerformanceHintManager");
            Object phm = ctx.getSystemService(phmClass);
            if (phm == null) return null;

            // Locate createHintSession(int[] tids, long targetDurationNanos)
            java.lang.reflect.Method create = phmClass.getMethod("createHintSession", int[].class, long.class);
            Object session = create.invoke(phm, tids, targetWorkNs);
            if (session == null) return null;

            // Methods on Session
            Class<?> sessClass = session.getClass();
            java.lang.reflect.Method upd = sessClass.getMethod("updateTargetWorkDuration", long.class);
            java.lang.reflect.Method rep = sessClass.getMethod("reportActualWorkDuration", long.class);
            java.lang.reflect.Method cls = sessClass.getMethod("close");

            // Optional: setPreferPowerEfficiency(boolean) (available since API 31, but reflect anyway)
            java.lang.reflect.Method setPrefer = null;
            try {
                setPrefer = sessClass.getMethod("setPreferPowerEfficiency", boolean.class);
            } catch (Throwable ignored) {}

            // Initialize target
            try { upd.invoke(session, targetWorkNs); } catch (Throwable ignored) {}

            return new PerfHint(ctx, phm, create, session, upd, rep, cls, setPrefer, copyTids(tids), targetWorkNs);
        } catch (Throwable t) {
            return null; // silent fallback
        }
    }

    /** Update the target work duration (ns). No-op if unavailable. */
    public void updateTarget(long newTargetWorkNs) {
        this.targetWorkNs = newTargetWorkNs;
        Object s = this.session;
        if (s == null) return;
        try { mUpdateTarget.invoke(s, newTargetWorkNs); } catch (Throwable ignored) {}
    }

    /** Convenience: update target from display Hz (~vsync/4). */
    public void updateTargetFromHz(float displayHz) {
        updateTarget(targetFromHz(displayHz));
    }

    /**
     * Report the actual work duration (ns). Values are clamped to a sane range
     * to avoid poisoning the model on mis-measurements.
     */
    public void report(long actualWorkNs) {
        Object s = this.session;
        if (s == null) return;
        // Clamp between 100 µs and 20 ms
        long d = Math.max(100_000L, Math.min(actualWorkNs, 20_000_000L));
        try { mReportActual.invoke(s, d); } catch (Throwable ignored) {}
    }

    /** Optional: prefer power efficiency; set to false during gameplay to favor performance. */
    public void setPreferPowerEfficiency(boolean prefer) {
        Object s = this.session;
        if (s == null || mSetPreferPower == null) return;
        try { mSetPreferPower.invoke(s, prefer); } catch (Throwable ignored) {}
    }

    /**
     * Update the thread IDs associated with this session.
     * If ADPF doesn't expose a setter, we transparently recreate the session.
     */
    public synchronized void updateThreads(int[] newTids) {
        if (newTids == null || newTids.length == 0) return;
        this.threadIds = copyTids(newTids);
        // Recreate the session with the new thread IDs (portable approach).
        recreateSession();
    }

    @Override public synchronized void close() {
        try { if (session != null) mClose.invoke(session); } catch (Throwable ignored) {}
        finally { session = null; }
    }

    // ---- Internals ----

    private static int[] copyTids(int[] src) {
        int[] out = new int[src.length];
        System.arraycopy(src, 0, out, 0, src.length);
        return out;
    }

    /** Recreate the session (e.g., after changing thread IDs). */
    private void recreateSession() {
        // Close old
        try { if (session != null) mClose.invoke(session); } catch (Throwable ignored) {}
        session = null;

        // Create new
        try {
            Object s = mCreate.invoke(phm, threadIds, targetWorkNs);
            if (s == null) return;
            session = s;

            // Re-bind methods might not be necessary if same class; keep stored ones
            try { mUpdateTarget.invoke(session, targetWorkNs); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
            session = null;
        }
    }

    // ---- Simple helpers for frame-scoped measurements ----

    /** Call at the start of a frame to get a timestamp. */
    public static long tick() {
        return System.nanoTime();
    }

    /** Report CPU work duration from tick(). */
    public void tockAndReport(long tickNs) {
        long d = Math.max(0L, System.nanoTime() - tickNs);
        report(d);
    }
}
