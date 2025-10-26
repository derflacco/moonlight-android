package com.limelight.utils;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

// imports for grouping & caching
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// watcher lock + safe file access
import java.util.concurrent.locks.ReentrantLock;

import android.os.Build;

/**
 * CpuAffinity — helpers to pin threads to big cores safely.
 * Backed by libcpuaffinity.so (System.loadLibrary("cpuaffinity")).
 *
 * Improvements:
 * - Big-core detection cache with expiry and explicit invalidation
 * - Safer watcher lifecycle (single ReentrantLock, graceful shutdown)
 * - One-shot regex pin utility
 * - Clearer logging and guardrails
  * - Cpuset cache (group -> allowed mask) with TTL to avoid per-TID filesystem reads
 * - Watcher and one-shot pin now operate per cpuset group for consistency and lower overhead
 * - Intersections are computed once per group; all threads in the same group get the same mask
 * - SafeFs wrapper for /proc and /sys reads to avoid crashes on Android 14+ restrictions
 */
public final class CpuAffinity {
    private CpuAffinity() {}

    // ---- State ----
    private static volatile boolean sTriedLoad = false;
    private static volatile boolean sNativeLoaded = false;

    // Back-compat shadow (kept for toString() and old callers that may read it)
    private static volatile int[] sCachedBigCores = null;

    // Watcher (fixed-delay) and filters
    private static volatile ScheduledThreadPoolExecutor sWatcherExec;
    private static volatile ScheduledFuture<?> sWatchFuture;
    private static volatile long sWatcherPeriodMs = 0L;
    private static volatile Pattern sInclude, sExclude;
    // Single lock guarding ALL watcher state transitions (start/stop/isRunning)
    private static final ReentrantLock sWatcherLock = new ReentrantLock();

    // Big-core cache
    private static final AtomicReference<CacheEntry> sBigCoresCache = new AtomicReference<>();
    private static volatile long sCacheExpiryMs = 30_000L; // default 30s

    // NEW: Cpuset cache (group -> parsed allowed CPUs) + TTL
    private static final ConcurrentHashMap<String, __CpusetEntry> sCpusetCache = new ConcurrentHashMap<>();
    private static volatile long sCpusetCacheTtlMs = 2_000L; // default 2s, tweak via setCpusetCacheTtlMs()

    // ---- Config ----
    private static final class Config {
        static final long MIN_WATCHER_INTERVAL_MS = 1000L;   // avoid too-frequent scans
        static final long SHUTDOWN_WAIT_MS        = 5000L;   // await termination timeout
    }

    // Cache entry with timestamp
    private static final class CacheEntry {
        final int[] bigCores;
        final long timestampMs;
        final long expiryMs;

        CacheEntry(int[] bigCores, long expiryMs) {
            this.bigCores = (bigCores != null) ? bigCores.clone() : new int[0];
            this.timestampMs = System.currentTimeMillis();
            this.expiryMs = expiryMs;
        }

        boolean isValid() {
            if (expiryMs <= 0L) return true; // never expire when <= 0
            return (System.currentTimeMillis() - timestampMs) < expiryMs;
        }
    }

    // NEW: cpuset cache entry
    private static final class __CpusetEntry {
        final String effStr;  // raw string (e.g., "0-3,6-7")
        final int[]  mask;    // parsed ints
        final long   tsMs;    // stored at
        __CpusetEntry(String effStr, int[] mask) {
            this.effStr = effStr;
            this.mask = mask;
            this.tsMs = System.currentTimeMillis();
        }
        boolean isValid() {
            return sCpusetCacheTtlMs <= 0L || (System.currentTimeMillis() - tsMs) < sCpusetCacheTtlMs;
        }
    }

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
                    __v("Native library loaded");
                } catch (Throwable t) {
                    sNativeLoaded = false;
                    __v("Failed to load native library: " + t.getMessage());
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
        try {
            nativeSetAffinity(cpuIds);
            __v("Set affinity to " + Arrays.toString(cpuIds));
        } catch (Throwable ignored) {}
    }

    public static void clearCurrentThreadAffinityAllOnline() {
        if (!ensureLoaded()) return;
        try {
            nativeClearCurrentThreadAffinityAllOnline();
            __v("Cleared current thread affinity");
        } catch (Throwable ignored) {}
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
        try {
            nativeSetAffinityForTid(tid, cpuIds);
            __v("Set affinity for tid=" + tid + " -> " + Arrays.toString(cpuIds));
        } catch (Throwable ignored) {}
    }

    public static void pinAllThreadsToCores(int... cpuIds) {
        if (!ensureLoaded() || cpuIds == null || cpuIds.length == 0) return;
        try { nativePinAllThreadsToCores(cpuIds); } catch (Throwable ignored) {}
    }

    public static void clearAllThreadsAffinityAllOnline() {
        if (!ensureLoaded()) return;
        try { nativeClearAllThreadsAffinityAllOnline(); } catch (Throwable ignored) {}
    }

    // ---- Big core detection (+ cache) ----
    public static int[] detectBigCores() {
        // Cache first
        CacheEntry cached = sBigCoresCache.get();
        if (cached != null && cached.isValid()) {
            return cached.bigCores.clone();
        }

        if (!ensureLoaded()) return new int[0];

        synchronized (CpuAffinity.class) {
            cached = sBigCoresCache.get();
            if (cached != null && cached.isValid()) {
                return cached.bigCores.clone();
            }
            int[] result = new int[0];
            try {
                // 1) Native first
                int[] nativeBig = nativeDetectBigCores();
                int[] online = __readOnlineCpus();
                if (nativeBig == null) nativeBig = new int[0];

                // 2) Normalize + guardrails
                nativeBig = __sortedUnique(nativeBig);
                online    = __sortedUnique(online);

                boolean looksUniform = (nativeBig.length == 0) ||
                        __sameSet(nativeBig, online) ||
                        nativeBig.length == online.length;

                // 3) If uniform/empty, fallback to Java detector (BIG ∪ PRIME, avoid PRIME-only)
                if (looksUniform) {
                    int[] javaBig = detectPerfCpusAvoidPrimeOnly();
                    if (javaBig != null && javaBig.length > 1) {
                        result = javaBig;
                        __logOnce("Uniform topology detected from native; using Java fallback: " + Arrays.toString(result));
                    } else {
                        result = new int[0];
                        __logOnce("Uniform topology detected; skipping big-core pinning.");
                    }
                } else {
                    result = nativeBig;
                }

                // 4) NOTE: do NOT intersect with current thread's cpuset here.
                //          Intersection is done later per TID/group to ensure correctness.

                // 5) Save cache and shadow
                if (result == null) result = new int[0];
                sBigCoresCache.set(new CacheEntry(result, sCacheExpiryMs));
                sCachedBigCores = result;

                if (result.length > 0) {
                    __v("Detected big cores: " + Arrays.toString(result));
                } else {
                    __v("Detected big cores: <none>");
                }
                return result.clone();
            } catch (Throwable t) {
                int[] empty = new int[0];
                sBigCoresCache.set(new CacheEntry(empty, sCacheExpiryMs));
                sCachedBigCores = empty;
                return empty;
            }
        }
    }

    /** Force fresh detection next time. */
    public static void clearBigCoresCache() {
        sBigCoresCache.set(null);
        sCachedBigCores = null;
        __v("Big cores cache cleared");
    }

    /** Debug path: bypass cache once by clearing before detect. */
    public static int[] detectBigCoresForDebug() {
        clearBigCoresCache();
        return detectBigCores();
    }

    /** Adjust cache expiry at runtime (<=0 disables expiry). */
    public static void setBigCoresCacheExpiryMs(long expiryMs) {
        sCacheExpiryMs = expiryMs;
        CacheEntry cur = sBigCoresCache.get();
        if (cur != null) {
            // Rewrap existing value with new expiry semantics
            sBigCoresCache.set(new CacheEntry(cur.bigCores, expiryMs));
        }
        __v("Cache expiry set to " + expiryMs + " ms");
    }

    // NEW: configure & clear cpuset cache
    /** Set cpuset cache TTL in milliseconds. Set to 0 to disable expiry. */
    public static void setCpusetCacheTtlMs(long ttlMs) {
        sCpusetCacheTtlMs = Math.max(0L, ttlMs);
        __v("Cpuset cache TTL set to " + sCpusetCacheTtlMs + " ms");
    }

    /** Clear the cpuset cache (group->allowed mask). */
    public static void clearCpusetCache() {
        sCpusetCache.clear();
        __v("Cpuset cache cleared");
    }

    public static void pinCurrentThreadToBigCoresIf(boolean enabled) {
        if (!enabled) return;
        int[] big = detectBigCores();
        if (big.length > 0) setAffinity(big);
    }

    /** Legacy alias kept for compatibility. */
    public static void clearAffinityAllOnline() { clearCurrentThreadAffinityAllOnline(); }

    // ---- Fixed-delay watcher ----

    /** Convenience overload without filters. */
    public static void startAffinityWatcher(long periodMs) {
        sInclude = null; sExclude = null;
        startAffinityWatcherWithFixedDelay(periodMs);
    }

    /** Start watcher with include/exclude regex (case-insensitive). */
    public static void startAffinityWatcher(long periodMs, String includeRegex, String excludeRegex) {
        sInclude = null; sExclude = null;
        try { if (includeRegex != null && !includeRegex.isEmpty()) sInclude = Pattern.compile(includeRegex, Pattern.CASE_INSENSITIVE); } catch (Throwable ignored) {}
        try { if (excludeRegex != null && !excludeRegex.isEmpty()) sExclude = Pattern.compile(excludeRegex, Pattern.CASE_INSENSITIVE); } catch (Throwable ignored) {}
        startAffinityWatcherWithFixedDelay(periodMs);
    }

    public static void startAffinityWatcherWithFixedDelay(long delayMs) {
        if (!ensureLoaded()) return;

        sWatcherLock.lock();
        try {
            if (sWatchFuture != null && !sWatchFuture.isCancelled()) {
                __v("Affinity watcher already running (period=" + sWatcherPeriodMs + " ms)");
                return;
            }
            if (sWatcherExec == null || sWatcherExec.isShutdown()) {
                sWatcherExec = new ScheduledThreadPoolExecutor(1, r -> {
                    Thread t = new Thread(() -> {
                        try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); } catch (Throwable ignored) {}
                        r.run();
                    }, "AffinityWatcher");
                    try { t.setDaemon(true); } catch (Throwable ignored) {}
                    return t;
                });
                try {
                    sWatcherExec.setRemoveOnCancelPolicy(true);
                    sWatcherExec.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
                    sWatcherExec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                    sWatcherExec.allowCoreThreadTimeOut(true);
                } catch (Throwable ignored) {}
            }

            final long p = (delayMs <= 0L) ? 2000L : Math.max(delayMs, Config.MIN_WATCHER_INTERVAL_MS);
            sWatcherPeriodMs = p;
            try {
                sWatchFuture = sWatcherExec.scheduleWithFixedDelay(() -> {
                    try {
                        int[] big = detectBigCores();
                        if (big == null || big.length == 0) return;

                        int[] tids = listTids();

                        // Group candidate TIDs by cpuset group to compute "allowed" once per group
                        HashMap<String, ArrayList<Integer>> byGroup = new HashMap<>();
                        for (int tid : tids) {
                            try {
                                String name = readThreadName(tid);
                                if (name == null) name = "";
                                boolean ok = (sInclude == null) || sInclude.matcher(name).find();
                                if (ok && sExclude != null && sExclude.matcher(name).find()) ok = false;
                                if (!ok) continue;

                                String grp = readCpusetGroupForTid(tid);
                                if (grp == null) grp = "";
                                byGroup.computeIfAbsent(grp, k -> new ArrayList<>()).add(tid);
                            } catch (Throwable ignored) {}
                        }

                        int processed = 0;

                        // For each group, intersect once and apply to all TIDs in the group
                        for (Map.Entry<String, ArrayList<Integer>> e : byGroup.entrySet()) {
                            int[] allowed = __getAllowedMaskForGroupCached(e.getKey());
                            int[] mask = __intersect(big, allowed);
                            // Guardrail: avoid single-core "choke" pins; must be >1 to be useful
                            if (mask.length <= 1) continue;

                            for (int tid : e.getValue()) {
                                try { nativeSetAffinityForTid(tid, mask); processed++; } catch (Throwable ignored) {}
                            }
                        }

                        __v("Watcher tick: processed " + processed + " / " + tids.length + " threads (groups=" + byGroup.size() + ")");
                    } catch (Throwable ignored) {}
                }, p, p, TimeUnit.MILLISECONDS);
                __v("Affinity watcher started (period=" + p + " ms)");
            } catch (Throwable t) {
                __v("Failed to start affinity watcher: " + t.getMessage());
                try { sWatcherExec.shutdownNow(); } catch (Throwable ignored) {}
                sWatcherExec = null;
                sWatchFuture = null;
                sWatcherPeriodMs = 0L;
            }
        } finally {
            sWatcherLock.unlock();
        }
    }

    /** Returns true if the watcher future exists and is not cancelled/done and executor is alive. */
    public static boolean isWatcherRunning() {
        sWatcherLock.lock();
        try {
            ScheduledFuture<?> f = sWatchFuture;
            ScheduledThreadPoolExecutor ex = sWatcherExec;
            return f != null && !f.isCancelled() && !f.isDone() && ex != null && !ex.isShutdown();
        } finally {
            sWatcherLock.unlock();
        }
    }

    public static void stopAffinityWatcher() {
        sWatcherLock.lock();
        try {
            __v("Stopping affinity watcher...");
            // Cancel future
            if (sWatchFuture != null) {
                try { sWatchFuture.cancel(false); } catch (Throwable ignored) {}
                sWatchFuture = null;
            }
            // Shutdown executor and await
            if (sWatcherExec != null) {
                try {
                    sWatcherExec.shutdown();
                    if (!sWatcherExec.awaitTermination(Config.SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        __v("Watcher executor did not terminate in time, forcing shutdown");
                        sWatcherExec.shutdownNow();
                    }
                } catch (Throwable ignored) {
                    try { sWatcherExec.shutdownNow(); } catch (Throwable ignored2) {}
                } finally {
                    sWatcherExec = null;
                }
            }
            sWatcherPeriodMs = 0L;
            sInclude = sExclude = null;
            __v("Affinity watcher stopped");
        } finally {
            sWatcherLock.unlock();
        }
    }

    /** One-shot pin of threads that match include/exclude without running the watcher. */
    public static int pinMatchingThreadsOnce(String includeRegex, String excludeRegex) {
        if (!ensureLoaded()) return 0;
        Pattern inc = null, exc = null;
        try { if (includeRegex != null && !includeRegex.isEmpty()) inc = Pattern.compile(includeRegex, Pattern.CASE_INSENSITIVE); } catch (Throwable ignored) {}
        try { if (excludeRegex != null && !excludeRegex.isEmpty()) exc = Pattern.compile(excludeRegex, Pattern.CASE_INSENSITIVE); } catch (Throwable ignored) {}
        int[] big = detectBigCores();
        if (big == null || big.length == 0) return 0;

        // Group by cpuset, compute intersection once, apply to all
        HashMap<String, ArrayList<Integer>> byGroup = new HashMap<>();
        int[] tids = listTids();
        for (int tid : tids) {
            String name = readThreadName(tid);
            if (name == null) name = "";
            boolean include = (inc == null) || inc.matcher(name).find();
            boolean exclude = (exc != null) && exc.matcher(name).find();
            if (!include || exclude) continue;

            String grp = readCpusetGroupForTid(tid);
            if (grp == null) grp = "";
            byGroup.computeIfAbsent(grp, k -> new ArrayList<>()).add(tid);
        }

        int count = 0;
        for (Map.Entry<String, ArrayList<Integer>> e : byGroup.entrySet()) {
            int[] allowed = __getAllowedMaskForGroupCached(e.getKey());
            int[] mask = __intersect(big, allowed);
            if (mask.length <= 1) continue;
            for (int tid : e.getValue()) {
                try { nativeSetAffinityForTid(tid, mask); count++; } catch (Throwable ignored) {}
            }
        }

        __v("One-shot pin applied to " + count + " threads (groups=" + byGroup.size() + ")");
        return count;
    }

    /** Full cleanup helper (cache + watcher). */
    public static void cleanupAllResources() {
        stopAffinityWatcher();
        clearBigCoresCache();
        clearCpusetCache(); // NEW: also clear cpuset cache
        __v("All resources cleaned up");
    }

    // ---- cpuset debug helpers ----

    /** SAFE: read first line of a file using SafeFs wrapper. */
    private static String readFileFirstLine(String path) {
        return SafeFs.readFirstLine(path);
    }

    /** Returns cpuset group path for a TID, like "/top-app" or "/foreground". */
    public static String readCpusetGroupForTid(int tid) {
        final String cgroupPath = "/proc/self/task/" + tid + "/cgroup";
        StringBuilder sb = new StringBuilder();
        try (java.io.BufferedReader br = SafeFs.newBufferedReader(cgroupPath)) {
            if (br != null) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains("cpuset:")) { sb.append(line.trim()); break; }
                }
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

    @NonNull
    @Override public String toString() {
        // Keep behavior close to legacy while still benefitting from cache
        int[] big = (sCachedBigCores != null) ? sCachedBigCores : detectBigCores();
        return "CpuAffinity(loaded=" + sNativeLoaded + ", big=" + Arrays.toString(big) + ")";
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
                    try (java.io.BufferedReader br = SafeFs.newBufferedReader(f.getAbsolutePath())) {
                        if (br != null) {
                            String s = br.readLine();
                            if (s != null) cap = Long.parseLong(s.trim());
                        }
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
                    return chosen; // NOTE: returning chosen (not null) avoids re-detect loop callers may expect
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
        String p = new java.io.File(policyDir, name).getAbsolutePath();
        return SafeFs.readFirstLine(p);
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
        try { com.limelight.LimeLog.info("[CpuAffinity] " + msg); } catch (Throwable ignored) {}
        // If you also want direct Logcat, enable the line below:
        // try { android.util.Log.i("CpuAffinity", msg); } catch (Throwable ignored) {}
    }

    // Reads online CPU list; fallback to cpuN scan (up to 32)
    private static int[] __readOnlineCpus() {
        String s = readFileFirstLine("/sys/devices/system/cpu/online");
        if (s != null && !s.trim().isEmpty()) {
            return __parseCpuList(s.trim());
        }
        java.util.ArrayList<Integer> list = new java.util.ArrayList<>();
        for (int i = 0; i < 32; i++) {
            java.io.File f = new java.io.File("/sys/devices/system/cpu/cpu" + i);
            if (f.isDirectory()) list.add(i);
        }
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        java.util.Arrays.sort(arr);
        return arr;
    }

    private static int[] __sortedUnique(int[] in) {
        if (in == null || in.length == 0) return new int[0];
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int v : in) set.add(v);
        int[] out = new int[set.size()];
        int i=0; for (Integer v : set) out[i++]=v;
        return out;
    }

    private static boolean __sameSet(int[] a, int[] b) {
        if (a == null || b == null) return false;
        int[] aa = __sortedUnique(a), bb = __sortedUnique(b);
        if (aa.length != bb.length) return false;
        for (int i=0;i<aa.length;i++) if (aa[i]!=bb[i]) return false;
        return true;
    }

    // Intersect with current thread's effective cpuset (kept for other use-cases)
    @SuppressWarnings("unused")
    private static int[] __intersectWithAllowedCpuset(int[] cores) {
        if (cores == null || cores.length == 0) return new int[0];
        try {
            int tid = android.os.Process.myTid();
            String grp = readCpusetGroupForTid(tid);          // e.g. "/top-app"
            String eff = readCpusetEffectiveCpus(grp);        // e.g. "0-3,6-7"
            int[] allowed = __parseCpuList(eff);
            if (allowed.length == 0) return cores;            // no info → leave as is
            int[] out = __intersect(cores, allowed);
            // If intersection is too small, better disable pin (avoid single-core choke)
            return (out.length > 1) ? out : new int[0];
        } catch (Throwable ignored) {
            return cores;
        }
    }

    // NEW: cached fetch of the allowed mask for a cpuset group
    private static int[] __getAllowedMaskForGroupCached(String group) {
        String key = (group == null) ? "" : group;
        if (key.equals("/")) key = "";
        if (key.startsWith("/")) key = key.substring(1);

        __CpusetEntry e = sCpusetCache.get(key);
        if (e != null && e.isValid()) {
            return e.mask.clone();
        }

        String eff = readCpusetEffectiveCpus(key);
        int[] mask = __parseCpuList(eff);

        // If we failed to read now but have a recent cached value, reuse it
        if ((mask == null || mask.length == 0) && e != null && e.isValid()) {
            return e.mask.clone();
        }

        sCpusetCache.put(key, new __CpusetEntry(eff, (mask != null) ? mask : new int[0]));
        return (mask != null) ? mask : new int[0];
    }

    // Log-once for recurring messages
    private static final java.util.concurrent.atomic.AtomicBoolean __onceUniform = new java.util.concurrent.atomic.AtomicBoolean(false);
    private static void __logOnce(String msg) {
        if (__onceUniform.compareAndSet(false, true)) {
            __v(msg);
        }
    }

    // ===== SafeFs wrapper =====
    /**
     * SafeFs centralizes access to /proc and /sys files to minimize crashes on Android 14+.
     * Strategy:
     *  - Prefer java.nio.Files (API 26+) which respects scoped access and throws SecurityException clearly.
     *  - Fallback to classic java.io.
     *  - Optional shell fallback ("cat <file>") disabled by default; enable explicitly if desired.
     *  All methods MUST fail gracefully and never crash the app.
     */
    private static final class SafeFs {
        private static volatile boolean SHELL_FALLBACK_ENABLED = false;

        /** Enable or disable shell fallback globally. Default: false. */
        public static void setShellFallbackEnabled(boolean enabled) {
            SHELL_FALLBACK_ENABLED = enabled;
        }

        /** Read first line of a file or return empty string on failure. */
        static String readFirstLine(String path) {
            if (path == null || path.isEmpty()) return "";
            // Try NIO first (API 26+)
            if (Build.VERSION.SDK_INT >= 26) {
                java.nio.file.Path p = null;
                try {
                    p = java.nio.file.Paths.get(path);
                    try (java.io.BufferedReader br = java.nio.file.Files.newBufferedReader(p)) {
                        String s = br.readLine();
                        if (s != null) return s.trim();
                    }
                } catch (SecurityException se) {
                    // Restricted by platform; try classical IO or shell below
                } catch (Throwable ignored) {}
            }
            // Fallback to java.io
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(path))) {
                String s = br.readLine();
                if (s != null) return s.trim();
            } catch (SecurityException se) {
                // Try shell if allowed
                if (SHELL_FALLBACK_ENABLED) {
                    String s = readFirstLineViaShell(path);
                    if (s != null) return s;
                }
            } catch (Throwable ignored) {
                // Try shell if allowed and io failed
                if (SHELL_FALLBACK_ENABLED) {
                    String s = readFirstLineViaShell(path);
                    if (s != null) return s;
                }
            }
            return "";
        }

        /** Returns a BufferedReader or null on failure, using NIO when possible. */
        static java.io.BufferedReader newBufferedReader(String path) {
            if (path == null || path.isEmpty()) return null;
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    java.nio.file.Path p = java.nio.file.Paths.get(path);
                    return java.nio.file.Files.newBufferedReader(p);
                } catch (SecurityException se) {
                    // fall through to IO
                } catch (Throwable ignored) {}
            }
            try {
                return new java.io.BufferedReader(new java.io.FileReader(path));
            } catch (SecurityException se) {
                // As a last resort, emulate a reader via shell if allowed
                if (SHELL_FALLBACK_ENABLED) {
                    String s = readAllViaShell(path);
                    if (s != null) return new java.io.BufferedReader(new java.io.StringReader(s));
                }
            } catch (Throwable ignored) {}
            return null;
        }

        private static String readFirstLineViaShell(String path) {
            String full = readAllViaShell(path);
            if (full == null) return null;
            int nl = full.indexOf('\n');
            if (nl >= 0) return full.substring(0, nl).trim();
            return full.trim();
        }

        private static String readAllViaShell(String path) {
            java.lang.Process proc = null;
            try {
                // Using sh avoids relying on toybox/busybox presence.
                proc = new ProcessBuilder("/system/bin/sh", "-c", "cat " + escapeShellArg(path))
                        .redirectErrorStream(true)
                        .start();
                try (java.io.InputStream in = proc.getInputStream();
                     java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream()) {
                    byte[] buf = new byte[1024];
                    int r;
                    while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                    // Wait a short time; ignore exit code (still return what we got)
                    try { proc.waitFor(100, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (Throwable ignored) {}
                    String out = bos.toString("UTF-8");
                    if (out != null) out = out.trim();
                    return (out == null || out.isEmpty()) ? null : out;
                }
            } catch (Throwable ignored) {
                return null;
            } finally {
                if (proc != null) {
                    try { proc.destroy(); } catch (Throwable ignored) {}
                }
            }
        }

        private static String escapeShellArg(String s) {
            // very conservative escaping for POSIX sh
            if (s == null) return "''";
            return "'" + s.replace("'", "'\"'\"'") + "'";
        }
    }
}
