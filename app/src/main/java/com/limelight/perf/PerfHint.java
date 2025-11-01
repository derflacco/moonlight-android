package com.limelight.perf;

import android.content.Context;
import android.os.Build;
import android.util.Log;

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
 *
 * This version also logs basic state to help verify if ADPF is really active.
 * It also retries with a minimal TID set if the vendor rejects the multi-TID session.
 */
public final class PerfHint implements AutoCloseable {
    private static final String TAG = "PerfHint";
    private static final boolean DEBUG = false; // set to false to silence
    // Log report() at most every N calls to avoid logspam on 60/120 fps streams
    private static final int REPORT_LOG_EVERY = 120;

    // ---- Session + reflection state ----
    private final Object phm; // android.os.PerformanceHintManager
    private final java.lang.reflect.Method mCreate; // createHintSession(int[], long)
    private final java.lang.reflect.Method mUpdateTarget; // Session.updateTargetWorkDuration(long)
    private final java.lang.reflect.Method mReportActual; // Session.reportActualWorkDuration(long)
    private final java.lang.reflect.Method mClose;        // Session.close()
    private final java.lang.reflect.Method mSetPreferPower; // Session.setPreferPowerEfficiency(boolean) [optional]

    private volatile Object session; // android.os.PerformanceHintManager$Session
    private volatile long targetWorkNs;
    private volatile int[] threadIds;

    // remember last prefer-power value so recreate() keeps the same behavior
    private volatile boolean lastPreferPower = false;

    // counter for rate-limiting report() logs
    private int reportLogCounter = 0;

    private PerfHint(Object phm,
                     java.lang.reflect.Method create,
                     Object session,
                     java.lang.reflect.Method upd,
                     java.lang.reflect.Method rep,
                     java.lang.reflect.Method cls,
                     java.lang.reflect.Method setPreferPower,
                     int[] tids,
                     long targetWorkNs) {
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

    /** Quick check: does this device even have ADPF? */
    public static boolean isAdpfAvailable(Context ctx) {
        if (ctx == null) return false;
        if (Build.VERSION.SDK_INT < 31) return false;
        try {
            Class<?> phmClass = Class.forName("android.os.PerformanceHintManager");
            Object phm = ctx.getSystemService(phmClass);
            return phm != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Create a hint session for the current thread. Returns null if unsupported or failed. */
    public static PerfHint createForCurrentThread(Context ctx, long targetWorkNs) {
        return createForThreads(ctx, new int[]{ currentTid() }, targetWorkNs);
    }

    /**
     * Create a hint session for the provided thread IDs.
     * If the vendor refuses the list, we retry with current thread only.
     */
    public static PerfHint createForThreads(Context ctx, int[] tids, long targetWorkNs) {
        // 1st attempt: full list
        PerfHint ph = internalCreate(ctx, tids, targetWorkNs, false);
        if (ph != null) {
            return ph;
        }

        // 2nd attempt (fallback): current thread only
        int selfTid = android.os.Process.myTid();
        if (tids == null || tids.length != 1 || tids[0] != selfTid) {
            PerfHint fallback = internalCreate(ctx, new int[]{ selfTid }, targetWorkNs, true);
            if (fallback != null) {
                return fallback;
            }
        }

        return null;
    }

    /**
     * Internal create, used by both the public entry point and the recreate logic.
     * If fallback==true the log will mark it as fallback.
     */
    private static PerfHint internalCreate(Context ctx, int[] tids, long targetWorkNs, boolean fallback) {
        try {
            if (ctx == null || Build.VERSION.SDK_INT < 31) {
                if (DEBUG) Log.d(TAG, "ADPF not available: ctx=" + ctx + " sdk=" + Build.VERSION.SDK_INT
                        + (fallback ? " (fallback)" : ""));
                return null;
            }

            Class<?> phmClass = Class.forName("android.os.PerformanceHintManager");
            Object phm = ctx.getSystemService(phmClass);
            if (phm == null) {
                if (DEBUG) Log.d(TAG, "ADPF not available: PerformanceHintManager is null"
                        + (fallback ? " (fallback)" : ""));
                return null;
            }

            java.lang.reflect.Method create = phmClass.getMethod("createHintSession", int[].class, long.class);
            Object session = create.invoke(phm, tids, targetWorkNs);
            if (session == null) {
                if (DEBUG) {
                    Log.d(TAG, "ADPF: createHintSession returned null"
                            + (fallback ? " (fallback path)" : "")
                            + " tids=" + toString(tids)
                            + " target=" + targetWorkNs);
                }
                return null;
            }

            Class<?> sessClass = session.getClass();
            java.lang.reflect.Method upd = sessClass.getMethod("updateTargetWorkDuration", long.class);
            java.lang.reflect.Method rep = sessClass.getMethod("reportActualWorkDuration", long.class);
            java.lang.reflect.Method cls = sessClass.getMethod("close");

            java.lang.reflect.Method setPrefer = null;
            try {
                setPrefer = sessClass.getMethod("setPreferPowerEfficiency", boolean.class);
            } catch (Throwable ignored) {}

            try { upd.invoke(session, targetWorkNs); } catch (Throwable ignored) {}

            if (DEBUG) {
                Log.d(TAG, "ADPF session created for tids=" + toString(tids)
                        + " target=" + targetWorkNs + " ns"
                        + (fallback ? " (via fallback)" : "")
                        + (setPrefer != null ? " (setPreferPowerEfficiency available)" : ""));
            }

            return new PerfHint(phm, create, session, upd, rep, cls, setPrefer, copyTids(tids), targetWorkNs);
        } catch (Throwable t) {
            if (DEBUG) Log.d(TAG, "ADPF create failed: " + t + (fallback ? " (fallback)" : ""));
            return null;
        }
    }

    /** Update the target work duration (ns). No-op if unavailable. */
    public void updateTarget(long newTargetWorkNs) {
        this.targetWorkNs = newTargetWorkNs;
        Object s = this.session;
        if (s == null) return;
        try {
            mUpdateTarget.invoke(s, newTargetWorkNs);
            if (DEBUG) Log.d(TAG, "ADPF target updated to " + newTargetWorkNs + " ns");
        } catch (Throwable ignored) {}
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
        try {
            mReportActual.invoke(s, d);
            if (DEBUG) {
                int c = ++reportLogCounter;
                if (c >= REPORT_LOG_EVERY) {
                    Log.d(TAG, "ADPF report() d=" + d + " ns");
                    reportLogCounter = 0;
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Optional: prefer power efficiency; set to false during gameplay to favor performance. */
    public void setPreferPowerEfficiency(boolean prefer) {
        this.lastPreferPower = prefer;
        Object s = this.session;
        if (s == null || mSetPreferPower == null) return;
        try {
            mSetPreferPower.invoke(s, prefer);
            if (DEBUG) Log.d(TAG, "ADPF setPreferPowerEfficiency(" + prefer + ")");
        } catch (Throwable ignored) {}
    }

    /**
     * Update the thread IDs associated with this session.
     * If ADPF doesn't expose a setter, we transparently recreate the session.
     */
    public synchronized void updateThreads(int[] newTids) {
        if (newTids == null || newTids.length == 0) return;
        this.threadIds = copyTids(newTids);
        if (DEBUG) Log.d(TAG, "ADPF updateThreads -> " + toString(newTids));
        recreateSession();
    }

    /** Dump current state to log, useful when debugging from the renderer. */
    public void dumpToLog(String tag) {
        Log.d(tag != null ? tag : TAG,
                "PerfHint{active=" + isActive()
                        + ", tids=" + toString(threadIds)
                        + ", target=" + targetWorkNs + " ns"
                        + ", sdk=" + Build.VERSION.SDK_INT
                        + ", preferPower=" + lastPreferPower
                        + "}");
    }

    @Override
    public synchronized void close() {
        try {
            if (session != null) {
                mClose.invoke(session);
                if (DEBUG) Log.d(TAG, "ADPF session closed");
            }
        } catch (Throwable ignored) {
        } finally {
            session = null;
        }
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
        try {
            if (session != null) {
                mClose.invoke(session);
                if (DEBUG) Log.d(TAG, "ADPF old session closed (recreate)");
            }
        } catch (Throwable ignored) {}
        session = null;

        // Try to create with current threadIds first
        try {
            Object s = mCreate.invoke(phm, threadIds, targetWorkNs);
            if (s == null) {
                if (DEBUG) Log.d(TAG, "ADPF recreate failed: create returned null, trying fallback");
                // fallback: current thread only
                int selfTid = android.os.Process.myTid();
                s = mCreate.invoke(phm, new int[]{ selfTid }, targetWorkNs);
                if (s == null) {
                    if (DEBUG) Log.d(TAG, "ADPF recreate fallback failed too");
                    return;
                }
                session = s;
                try { mUpdateTarget.invoke(session, targetWorkNs); } catch (Throwable ignored) {}
                if (mSetPreferPower != null) {
                    try { mSetPreferPower.invoke(session, lastPreferPower); } catch (Throwable ignored) {}
                }
                if (DEBUG) Log.d(TAG, "ADPF session recreated via fallback for tids=[" + selfTid + "]");
                return;
            }
            session = s;
            try { mUpdateTarget.invoke(session, targetWorkNs); } catch (Throwable ignored) {}
            if (mSetPreferPower != null) {
                try { mSetPreferPower.invoke(session, lastPreferPower); } catch (Throwable ignored) {}
            }
            if (DEBUG) Log.d(TAG, "ADPF session recreated for tids=" + toString(threadIds));
        } catch (Throwable ignored) {
            session = null;
            if (DEBUG) Log.d(TAG, "ADPF recreate threw, session cleared");
        }
    }

    // ---- Simple helpers for frame-scoped measurements ----
    /** Call at the start of a frame to get a CPU-time timestamp (current thread). */
    public static long tick() {
        return android.os.Debug.threadCpuTimeNanos();
    }

    /** Report per-thread CPU work duration from tick(). */
    public void tockAndReport(long cpuStartNs) {
        long d = Math.max(0L, android.os.Debug.threadCpuTimeNanos() - cpuStartNs);
        report(d);
    }

    private static String toString(int[] a) {
        if (a == null) return "null";
        if (a.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(a[i]);
            if (i != a.length - 1) sb.append(',');
        }
        sb.append(']');
        return sb.toString();
    }
}
