package com.limelight.utils;

import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * CpuAffinity — helpers to pin threads to big cores safely.
 * Backed by libcpuaffinity.so (System.loadLibrary("cpuaffinity")).
 */
public final class CpuAffinity {
    private CpuAffinity() {}

    // ---- State ----
    private static volatile boolean sTriedLoad = false;
    private static volatile boolean sNativeLoaded = false;
    private static volatile int[] sCachedBigCores = null;

    // Watcher (fixed-delay) and filters
    private static volatile ScheduledThreadPoolExecutor sWatcherExec;
    private static volatile ScheduledFuture<?> sWatchFuture;
    private static volatile long sWatcherPeriodMs = 0L;
    private static volatile Pattern sInclude, sExclude;

    // ---- Load native once ----
    private static boolean ensureLoaded() {
        if (sNativeLoaded) return true;
        if (sTriedLoad) return false;
        synchronized (CpuAffinity.class) {
            if (sNativeLoaded) return true;
            if (!sTriedLoad) {
                sTriedLoad = true;
                try {
                    System.loadLibrary("cpuaffinity");
                    sNativeLoaded = true;
                } catch (Throwable t) {
                    sNativeLoaded = false;
                }
            }
        }
        return sNativeLoaded;
    }

    /** @return true if native lib is loaded. */
    public static boolean isNativeLoaded() { return ensureLoaded(); }

    // ---- Core helpers ----
    public static int getCurrentCpu() {
        if (!ensureLoaded()) return -1;
        try { return nativeGetCurrentCpu(); } catch (Throwable t) { return -1; }
    }
    public static int getCurrentCpuOrMinus1() { return getCurrentCpu(); }

    public static String readAllowedCpuListForCurrentThread() {
        if (!ensureLoaded()) return "";
        try { return nativeReadAllowedCpuListForCurrentThread(); } catch (Throwable t) { return ""; }
    }

    public static void setAffinity(int... cpuIds) {
        if (!ensureLoaded() || cpuIds == null || cpuIds.length == 0) return;
        try { nativeSetAffinity(cpuIds); } catch (Throwable ignored) {}
    }

    public static void clearCurrentThreadAffinityAllOnline() {
        if (!ensureLoaded()) return;
        try { nativeClearCurrentThreadAffinityAllOnline(); } catch (Throwable ignored) {}
    }

    // ---- Process / TID helpers ----
    public static int[] listTids() {
        if (!ensureLoaded()) return new int[0];
        try { int[] v = nativeListTids(); return (v != null) ? v : new int[0]; } catch (Throwable t) { return new int[0]; }
    }

    public static String readThreadName(int tid) {
        if (!ensureLoaded()) return "";
        try { return nativeReadThreadName(tid); } catch (Throwable t) { return ""; }
    }

    public static void setAffinityForTid(int tid, int... cpuIds) {
        if (!ensureLoaded() || cpuIds == null || cpuIds.length == 0) return;
        try { nativeSetAffinityForTid(tid, cpuIds); } catch (Throwable ignored) {}
    }

    public static void pinAllThreadsToCores(int... cpuIds) {
        if (!ensureLoaded() || cpuIds == null || cpuIds.length == 0) return;
        try { nativePinAllThreadsToCores(cpuIds); } catch (Throwable ignored) {}
    }

    public static void clearAllThreadsAffinityAllOnline() {
        if (!ensureLoaded()) return;
        try { nativeClearAllThreadsAffinityAllOnline(); } catch (Throwable ignored) {}
    }

    // ---- Big core detection ----
    public static int[] detectBigCores() {
        if (sCachedBigCores != null) return sCachedBigCores;
        if (!ensureLoaded()) return new int[0];
        synchronized (CpuAffinity.class) {
            if (sCachedBigCores != null) return sCachedBigCores;
            try { int[] v = nativeDetectBigCores(); sCachedBigCores = (v != null) ? v : new int[0]; }
            catch (Throwable t) { sCachedBigCores = new int[0]; }
        }
        return sCachedBigCores;
    }
    public static int[] detectBigCoresForDebug() { return detectBigCores(); }

    public static void pinCurrentThreadToBigCoresIf(boolean enabled) {
        if (!enabled) return;
        int[] big = detectBigCores();
        if (big.length > 0) setAffinity(big);
    }

    /** Legacy alias kept for compatibility. */
    public static void clearAffinityAllOnline() { clearCurrentThreadAffinityAllOnline(); }

    // ---- Fixed-delay watcher (no burst on cached→uncached) ----
    public static synchronized void startAffinityWatcherWithFixedDelay(long delayMs) {
        if (!ensureLoaded()) return;
        if (sWatchFuture != null) return;
        if (sWatcherExec == null || sWatcherExec.isShutdown()) {
            sWatcherExec = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "AffinityWatcher");
                try { t.setDaemon(true); } catch (Throwable ignored) {}
                try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
                return t;
            });
            try {
                sWatcherExec.setRemoveOnCancelPolicy(true);
                sWatcherExec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
                sWatcherExec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                sWatcherExec.allowCoreThreadTimeOut(true);
            } catch (Throwable ignored) {}
        }
        final long p = (delayMs <= 0L) ? 2000L : delayMs;
        sWatcherPeriodMs = p;
        sWatchFuture = sWatcherExec.scheduleWithFixedDelay(() -> {
            try {
                int[] big = detectBigCores();
                if (big == null || big.length == 0) return;
                int[] tids = listTids();
                for (int tid : tids) {
                    try {
                        String name = readThreadName(tid);
                        if (name == null) name = "";
                        boolean ok = (sInclude == null) || sInclude.matcher(name).find();
                        if (ok && sExclude != null && sExclude.matcher(name).find()) ok = false;
                        if (ok) nativeSetAffinityForTid(tid, big);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }, p, p, TimeUnit.MILLISECONDS);
    }

    /** Convenience overload without filters. */
    public static synchronized void startAffinityWatcher(long periodMs) {
        sInclude = null; sExclude = null;
        startAffinityWatcherWithFixedDelay(periodMs);
    }

    /** Start watcher with include/exclude regex (case-insensitive). */
    public static synchronized void startAffinityWatcher(long periodMs, String includeRegex, String excludeRegex) {
        sInclude = null; sExclude = null;
        try { if (includeRegex != null && !includeRegex.isEmpty()) sInclude = Pattern.compile(includeRegex, Pattern.CASE_INSENSITIVE); } catch (Throwable ignored) {}
        try { if (excludeRegex != null && !excludeRegex.isEmpty()) sExclude = Pattern.compile(excludeRegex, Pattern.CASE_INSENSITIVE); } catch (Throwable ignored) {}
        startAffinityWatcherWithFixedDelay(periodMs);
    }

    public static synchronized void stopAffinityWatcher() {
        if (sWatchFuture != null) {
            try { sWatchFuture.cancel(true); } catch (Throwable ignored) {}
            sWatchFuture = null;
        }
        if (sWatcherExec != null) {
            try { sWatcherExec.purge(); } catch (Throwable ignored) {}
            try { sWatcherExec.shutdownNow(); } catch (Throwable ignored) {}
            sWatcherExec = null;
        }
        sWatcherPeriodMs = 0L;
        sInclude = sExclude = null;
    }

    // ---- cpuset debug helpers ----
    private static String readFileFirstLine(String path) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader(path));
            String s = br.readLine();
            return (s != null) ? s.trim() : "";
        } catch (Throwable ignored) {
            return "";
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    /** Returns cpuset group path for a TID, like "/top-app" or "/foreground". */
    public static String readCpusetGroupForTid(int tid) {
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader("/proc/self/task/" + tid + "/cgroup"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.contains("cpuset:")) { sb.append(line.trim()); break; }
            }
        } catch (Throwable ignored) {}
        String cg = sb.toString();
        int idx = cg.indexOf("cpuset:");
        if (idx >= 0) {
            String sub = cg.substring(idx + "cpuset:".length()).trim();
            return sub.isEmpty() ? "/" : sub;
        }
        return cg;
    }

    /** Reads /dev/cpuset/<group>/effective_cpus (or cpus). */
    public static String readCpusetEffectiveCpus(String group) {
        if (group == null || group.isEmpty() || "/".equals(group)) group = "";
        if (group.startsWith("/")) group = group.substring(1);
        String base = "/dev/cpuset" + (group.isEmpty() ? "" : ("/" + group));
        String eff = readFileFirstLine(base + "/effective_cpus");
        if (eff.isEmpty()) eff = readFileFirstLine(base + "/cpus");
        return eff;
    }

    @Override public String toString() {
        return "CpuAffinity(loaded=" + sNativeLoaded + ", big=" + Arrays.toString(sCachedBigCores) + ")";
    }

    // ===== Native declarations =====
    @SuppressWarnings("JniMissingFunction") private static native int nativeGetCurrentCpu();
    @SuppressWarnings("JniMissingFunction") private static native String nativeReadAllowedCpuListForCurrentThread();
    @SuppressWarnings("JniMissingFunction") private static native int[] nativeDetectBigCores();
    @SuppressWarnings("JniMissingFunction") private static native void nativeSetAffinity(int[] cpuIds);
    @SuppressWarnings("JniMissingFunction") private static native void nativeClearCurrentThreadAffinityAllOnline();
    @SuppressWarnings("JniMissingFunction") private static native void nativePinAllThreadsToCores(int[] cpuIds);
    @SuppressWarnings("JniMissingFunction") private static native void nativeClearAllThreadsAffinityAllOnline();
    @SuppressWarnings("JniMissingFunction") private static native int[] nativeListTids();
    @SuppressWarnings("JniMissingFunction") private static native String nativeReadThreadName(int tid);
    @SuppressWarnings("JniMissingFunction") private static native void nativeSetAffinityForTid(int tid, int[] cpuIds);
    @SuppressWarnings("JniMissingFunction") private static native void nativeClearAffinityForTidAllOnline(int tid);
}
