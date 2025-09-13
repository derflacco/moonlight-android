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

    // ====== High-precision cluster detect (policy→capacity→fallback) ======
    // Avoid pinning to a single PRIME core on tri-clusters. Prefer BIG ∪ PRIME.
    public static int[] detectPerfCpusAvoidPrimeOnly() {
        try {
            // 1) Discover clusters via cpufreq policies
            java.io.File base = new java.io.File("/sys/devices/system/cpu/cpufreq");
            java.io.File[] pols = base.listFiles(new java.io.FilenameFilter() {
                public boolean accept(java.io.File dir, String name) {
                    return name != null && name.startsWith("policy");
                }
            });
            java.util.ArrayList<__Cluster> clusters = new java.util.ArrayList<>();
            if (pols != null) {
                for (java.io.File p : pols) {
                    String rel = __readFirst(p, "related_cpus");   // e.g. "0 1 2 3" or "0-3"
                    String hz  = __readFirst(p, "cpuinfo_max_freq");
                    int[] cpus = __parseCpuList(rel);
                    long maxHz = 0L;
                    try { maxHz = Long.parseLong(hz.trim()) * 1000L; } catch (Throwable ignored) {}
                    if (cpus.length > 0 && maxHz > 0) clusters.add(new __Cluster(cpus, maxHz, "policy"));
                }
            }
            // 2) If nothing found, try capacity per-core and group by max capacity
            if (clusters.isEmpty()) {
                java.util.ArrayList<Integer> all = new java.util.ArrayList<>();
                for (int i = 0; i < 16; i++) { // up to 16 cores safety
                    java.io.File f = new java.io.File("/sys/devices/system/cpu/cpu"+i+"/cpu_capacity");
                    if (!f.exists()) break;
                    long cap = 0L;
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
                        String s = br.readLine();
                        if (s != null) cap = Long.parseLong(s.trim());
                    } catch (Throwable ignored) {}
                    if (cap > 0) all.add(i);
                }
                if (!all.isEmpty()) {
                    // naive: split by half around median capacity
                    // (we only need the top group to represent big/prime)
                    java.util.ArrayList<Integer> top = all; // already ordered by cpu id; it's fine
                    int[] cpus = new int[top.size()];
                    for (int i=0;i<top.size();i++) cpus[i]=top.get(i);
                    clusters.add(new __Cluster(cpus, 1_000_000_000L, "capacity"));
                }
            }
            if (clusters.isEmpty()) {
                __v("AffinityDetect: no clusters found");
                return null;
            }
            clusters.sort((a, b) -> Long.compare(a.maxHz, b.maxHz));
            __Cluster prime = clusters.get(clusters.size() - 1);
            __Cluster big   = (clusters.size() >= 2) ? clusters.get(clusters.size() - 2) : null;
            double ratio = (big != null && big.maxHz > 0) ? ((double)prime.maxHz / (double)big.maxHz) : 1.0;

            int[] chosen;
            if (prime.cpus.length == 1 && big != null && big.cpus.length >= 2) {
                chosen = __union(big.cpus, prime.cpus);
            } else if (ratio < 1.05 && big != null) {
                chosen = __union(big.cpus, prime.cpus);
            } else {
                chosen = prime.cpus;
            }

            // Intersect with thread allowed set
            int[] allowed = __parseCpuList(readAllowedCpuListForCurrentThread());
            chosen = __intersect(chosen, allowed);

            // Guardrails
            if (chosen.length <= 1 && big != null) {
                chosen = __intersect(__union(big.cpus, prime.cpus), allowed);
            }
            if (chosen.length <= 1) {
                __v("AffinityDetect: ambiguous (<=1 core after intersect). Skip pin.");
                return null;
            }

            // If chosen equals allowed (already pinned), skip
            {
                int[] allowedArr = java.util.Arrays.copyOf(allowed, allowed.length);
                int[] chosenArr  = java.util.Arrays.copyOf(chosen, chosen.length);
                java.util.Arrays.sort(allowedArr);
                java.util.Arrays.sort(chosenArr);
                if (allowedArr.length == chosenArr.length && java.util.Arrays.equals(allowedArr, chosenArr)) {
                    __v("AffinityDetect: already pinned. Skip pin.");
                    return null;
                }
            }


            __v("AffinityDetect: method="+prime.method+" clusters="+__dumpClusters(clusters)
                    + " ratio="+String.format(java.util.Locale.US,"%.3f",ratio)
                    + " chosen="+java.util.Arrays.toString(chosen));
            return chosen;
        } catch (Throwable t) {
            return null;
        }
    }

    // ===== Helpers (private) =====
    private static final class __Cluster {
        final int[] cpus; final long maxHz; final String method;
        __Cluster(int[] c, long hz, String m) { this.cpus=c; this.maxHz=hz; this.method=m; }
    }
    private static String __readFirst(java.io.File policyDir, String name) {
        java.io.File f = new java.io.File(policyDir, name);
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(f))) {
            String s = br.readLine(); return (s==null) ? "" : s;
        } catch (Throwable ignored) { return ""; }
    }
    // supports "0 1 2 3", "0-3", "0-3,6-7"
    private static int[] __parseCpuList(String s) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        if (s == null) return new int[0];
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return new int[0];
        String[] toks = trimmed.split("[, ]+");
        for (String tok : toks) {
            if (tok.isEmpty()) continue;
            int dash = tok.indexOf('-');
            if (dash > 0) {
                try {
                    int a = Integer.parseInt(tok.substring(0,dash));
                    int b = Integer.parseInt(tok.substring(dash+1));
                    if (a > b) { int t=a; a=b; b=t; }
                    for (int i=a;i<=b;i++) set.add(i);
                } catch (Throwable ignored) {}
            } else {
                try { set.add(Integer.parseInt(tok)); } catch (Throwable ignored) {}
            }
        }
        int[] out = new int[set.size()];
        int i=0; for (Integer v : set) out[i++]=v;
        return out;
    }
    private static int[] __union(int[] a, int[] b) {
        java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
        for (int x : a) s.add(x); for (int x : b) s.add(x);
        int[] out = new int[s.size()];
        int i=0; for (Integer v : s) out[i++]=v;
        return out;
    }
    private static int[] __intersect(int[] a, int[] b) {
        java.util.HashSet<Integer> sb = new java.util.HashSet<>();
        for (int x : b) sb.add(x);
        java.util.ArrayList<Integer> res = new java.util.ArrayList<>();
        for (int x : a) if (sb.contains(x)) res.add(x);
        int[] out = new int[res.size()];
        for (int i=0;i<res.size();i++) out[i]=res.get(i);
        return out;
    }
    private static String __dumpClusters(java.util.List<__Cluster> cs) {
        StringBuilder sb = new StringBuilder();
        for (__Cluster c : cs) {
            sb.append(java.util.Arrays.toString(c.cpus)).append("@").append(c.maxHz).append(" ");
        }
        return sb.toString().trim();
    }
    private static void __v(String msg) {
        try { com.limelight.LimeLog.info(msg); } catch (Throwable ignored) {}
        try { android.util.Log.i("CpuAffinity", msg); } catch (Throwable ignored) {}
    }

}