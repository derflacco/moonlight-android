package com.limelight.utils;

import android.os.Process;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Arrays;

/**
 * CpuAffinity — minimal helper used ONLY by CpuWarmUp via reflection.
 *
 * Public API kept intentionally tiny:
 *  - getPrimeCoreCount()
 *  - pinCurrentThreadToPrimeCoresIf(boolean)
 *  - pinCurrentThreadToBigCoresIf(boolean)
 *  - pinCurrentThreadToLittleCoresIf(boolean)
 *  - pinCurrentThreadToAllCoresIf(boolean)
 *
 * Detection is pure Java (sysfs /proc). Affinity setting is best-effort via optional JNI lib "cpuaffinity".
 */
public final class CpuAffinity {
    private CpuAffinity() {}

    // ---- Optional native (sched_setaffinity) ----
    private static volatile boolean sTriedLoad = false;
    private static volatile boolean sNativeLoaded = false;

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

    @SuppressWarnings("JniMissingFunction")
    private static native void nativeSetAffinity(int[] cpuIds);

    @SuppressWarnings("JniMissingFunction")
    private static native void nativeClearCurrentThreadAffinityAllOnline();

    private static void setAffinityBestEffort(int[] cpuIds) {
        if (cpuIds == null || cpuIds.length == 0) return;
        if (!ensureLoaded()) return;
        try { nativeSetAffinity(cpuIds); } catch (Throwable ignored) {}
    }

    private static void clearAffinityBestEffort() {
        if (!ensureLoaded()) return;
        try { nativeClearCurrentThreadAffinityAllOnline(); } catch (Throwable ignored) {}
    }

    // ---- CpuWarmUp reflection API ----

    /** Returns PRIME core count if a 3-tier layout is detected; otherwise 0. */
    public static int getPrimeCoreCount() {
        final Tiers t = detectTiersCached();
        return (t != null && t.prime != null) ? t.prime.length : 0;
    }

    public static void pinCurrentThreadToPrimeCoresIf(boolean enabled) {
        if (!enabled) return;
        final Tiers t = detectTiersCached();
        if (t == null || t.prime.length == 0) return;
        setAffinityBestEffort(t.prime);
    }

    public static void pinCurrentThreadToBigCoresIf(boolean enabled) {
        if (!enabled) return;
        final Tiers t = detectTiersCached();
        if (t == null || t.big.length == 0) return;
        setAffinityBestEffort(t.big);
    }

    public static void pinCurrentThreadToLittleCoresIf(boolean enabled) {
        if (!enabled) return;
        final Tiers t = detectTiersCached();
        if (t == null || t.little.length == 0) return;
        setAffinityBestEffort(t.little);
    }

    public static void pinCurrentThreadToAllCoresIf(boolean enabled) {
        if (!enabled) return;
        clearAffinityBestEffort();
    }

    // ---- Minimal tier detection (sysfs + /proc) ----

    private static final class Tiers {
        final int[] prime;   // 3-tier only
        final int[] big;
        final int[] little;

        Tiers(int[] prime, int[] big, int[] little) {
            this.prime = (prime != null) ? prime : new int[0];
            this.big = (big != null) ? big : new int[0];
            this.little = (little != null) ? little : new int[0];
        }
    }

    // Cache tiers (cluster membership is stable for the process lifetime)
    private static volatile Tiers sCachedTiers;

    private static Tiers detectTiersCached() {
        Tiers c = sCachedTiers;
        if (c != null) return c;
        synchronized (CpuAffinity.class) {
            c = sCachedTiers;
            if (c != null) return c;
            c = detectTiersOnce();
            sCachedTiers = (c != null) ? c : new Tiers(new int[0], readAllowedCpusForCurrentThread(), readAllowedCpusForCurrentThread());
            return sCachedTiers;
        }
    }

    private static Tiers detectTiersOnce() {
        // Allowed CPUs for this thread (cpuset/cgroup restrictions)
        final int[] allowed = readAllowedCpusForCurrentThread();
        if (allowed.length == 0) return new Tiers(new int[0], new int[0], new int[0]);

        // 1) Preferred: topology cluster_id / core_group_id
        final Tiers topo = detectTiersFromTopology(allowed);
        if (topo != null) return topo;

        // 2) Fallback: cpufreq policy bins (existing behavior)
        final TreeMap<Long, TreeSet<Integer>> byFreq = new TreeMap<>();
        try {
            File base = new File("/sys/devices/system/cpu/cpufreq");
            File[] pols = base.listFiles((dir, name) -> name != null && name.startsWith("policy"));
            if (pols != null) {
                for (File p : pols) {
                    long maxKHz = readLongFirstOf(
                            new File(p, "cpuinfo_max_freq").getAbsolutePath(),
                            new File(p, "scaling_max_freq").getAbsolutePath()
                    );
                    if (maxKHz <= 0) continue;

                    String rel = readFirstLine(new File(p, "related_cpus").getAbsolutePath());
                    if (rel.isEmpty()) rel = readFirstLine(new File(p, "affected_cpus").getAbsolutePath());
                    if (rel.isEmpty()) rel = readFirstLine(new File(p, "cpus").getAbsolutePath());
                    int[] cpus = parseCpuList(rel);
                    if (cpus.length == 0) continue;

                    TreeSet<Integer> set = byFreq.get(maxKHz);
                    if (set == null) {
                        set = new TreeSet<>();
                        byFreq.put(maxKHz, set);
                    }
                    for (int c : cpus) set.add(c);
                }
            }
        } catch (Throwable ignored) {}

        if (byFreq.isEmpty()) {
            return new Tiers(new int[0], allowed, allowed);
        }

        final ArrayList<Long> freqs = new ArrayList<>(byFreq.keySet());

        TreeSet<Integer> primeSet = new TreeSet<>();
        TreeSet<Integer> bigSet = new TreeSet<>();
        TreeSet<Integer> littleSet = new TreeSet<>();

        if (freqs.size() >= 3) {
            long primeF = freqs.get(freqs.size() - 1);
            long bigF   = freqs.get(freqs.size() - 2);
            primeSet.addAll(byFreq.get(primeF));
            bigSet.addAll(byFreq.get(bigF));
            for (int i = 0; i < freqs.size() - 2; i++) {
                TreeSet<Integer> s = byFreq.get(freqs.get(i));
                if (s != null) littleSet.addAll(s);
            }
        } else if (freqs.size() == 2) {
            long bigF = freqs.get(1);
            long lowF = freqs.get(0);
            bigSet.addAll(byFreq.get(bigF));
            littleSet.addAll(byFreq.get(lowF));
        } else {
            return new Tiers(new int[0], allowed, allowed);
        }

        int[] prime = intersect(toIntArray(primeSet), allowed);
        int[] big   = intersect(toIntArray(bigSet), allowed);
        int[] little= intersect(toIntArray(littleSet), allowed);

        if (big.length == 0) big = allowed;

        if (little.length == 0) {
            int[] perf = union(big, prime);
            little = subtract(allowed, perf);
            if (little.length == 0) little = allowed;
        }

        if (prime.length > 0) {
            big = subtract(big, prime);
            if (big.length == 0) big = allowed;
        }

        return new Tiers(prime, big, little);
    }

    private static Tiers detectTiersFromTopology(int[] allowed) {
        try {
            // Map clusterId -> cpus
            TreeMap<Integer, TreeSet<Integer>> clusters = new TreeMap<>();
            for (int cpu : allowed) {
                int cid = readClusterId(cpu);
                if (cid < 0) continue;
                TreeSet<Integer> set = clusters.get(cid);
                if (set == null) {
                    set = new TreeSet<>();
                    clusters.put(cid, set);
                }
                set.add(cpu);
            }

            if (clusters.size() < 2) return null; // topology not useful (missing or single cluster)

            // Compute max freq per cluster (kHz)
            final class C {
                final int cid;
                final int[] cpus;
                final long maxKHz;
                C(int cid, int[] cpus, long maxKHz) { this.cid = cid; this.cpus = cpus; this.maxKHz = maxKHz; }
            }

            ArrayList<C> list = new ArrayList<>(clusters.size());
            for (Integer cid : clusters.keySet()) {
                int[] cpus = toIntArray(clusters.get(cid));
                long max = 0L;
                for (int c : cpus) {
                    long k = readCpuMaxKHz(c);
                    if (k > max) max = k;
                }
                list.add(new C(cid, cpus, max));
            }

            // Sort by max freq ascending
            list.sort((a, b) -> Long.compare(a.maxKHz, b.maxKHz));

            if (list.size() >= 3) {
                int[] prime = list.get(list.size() - 1).cpus;
                int[] big   = list.get(list.size() - 2).cpus;

                // little = union of the rest
                TreeSet<Integer> littleSet = new TreeSet<>();
                for (int i = 0; i < list.size() - 2; i++) {
                    for (int c : list.get(i).cpus) littleSet.add(c);
                }
                int[] little = toIntArray(littleSet);

                // Guard: if freq data missing, still consider it 3-tier by topology
                if (big.length == 0) big = allowed;
                if (little.length == 0) little = subtract(allowed, union(big, prime));

                return new Tiers(prime, big, (little.length > 0) ? little : allowed);
            } else {
                // 2-tier topology
                int[] big = list.get(list.size() - 1).cpus;
                int[] little = list.get(0).cpus;
                if (big.length == 0) big = allowed;
                if (little.length == 0) little = subtract(allowed, big);
                if (little.length == 0) little = allowed;
                return new Tiers(new int[0], big, little);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readClusterId(int cpu) {
        // cluster_id preferred, core_group_id fallback (some kernels)
        String a = readFirstLine("/sys/devices/system/cpu/cpu" + cpu + "/topology/cluster_id");
        if (!a.isEmpty()) {
            try { return Integer.parseInt(a.trim()); } catch (Throwable ignored) {}
        }
        String b = readFirstLine("/sys/devices/system/cpu/cpu" + cpu + "/topology/core_group_id");
        if (!b.isEmpty()) {
            try { return Integer.parseInt(b.trim()); } catch (Throwable ignored) {}
        }
        return -1;
    }

    private static long readCpuMaxKHz(int cpu) {
        long v = readLong("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/cpuinfo_max_freq");
        if (v > 0) return v;
        return readLong("/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/scaling_max_freq");
    }

    // ---- /proc allowed CPUs ----

    private static int[] readAllowedCpusForCurrentThread() {
        int tid = -1;
        try { tid = Process.myTid(); } catch (Throwable ignored) {}
        if (tid <= 0) return fallbackAllCpus();

        String path = "/proc/self/task/" + tid + "/status";
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Cpus_allowed_list:")) {
                    String v = line.substring("Cpus_allowed_list:".length()).trim();
                    int[] parsed = parseCpuList(v);
                    return (parsed.length > 0) ? parsed : fallbackAllCpus();
                }
            }
        } catch (Throwable ignored) {
            // fall through
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }

        return fallbackAllCpus();
    }

    private static int[] fallbackAllCpus() {
        int n = 0;
        try { n = Runtime.getRuntime().availableProcessors(); } catch (Throwable ignored) {}
        if (n <= 0) n = 8;
        int[] out = new int[n];
        for (int i = 0; i < n; i++) out[i] = i;
        return out;
    }

    // ---- Small IO + set helpers ----

    private static String readFirstLine(String path) {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(path));
            String s = br.readLine();
            return (s != null) ? s.trim() : "";
        } catch (Throwable ignored) {
            return "";
        } finally {
            try { if (br != null) br.close(); } catch (Throwable ignored) {}
        }
    }

    private static long readLongFirstOf(String p1, String p2) {
        long v = readLong(p1);
        if (v > 0) return v;
        return readLong(p2);
    }

    private static long readLong(String path) {
        String s = readFirstLine(path);
        if (s.isEmpty()) return 0;
        try { return Long.parseLong(s.trim()); } catch (Throwable ignored) { return 0; }
    }

    // Supports "0 1 2 3", "0-3", "0-3,6-7"
    private static int[] parseCpuList(String s) {
        TreeSet<Integer> set = new TreeSet<>();
        if (s == null) return new int[0];
        String t = s.trim();
        if (t.isEmpty()) return new int[0];

        // normalize separators
        t = t.replace(',', ' ').replace('\t', ' ');
        String[] toks = t.split(" +");
        for (String tok : toks) {
            if (tok == null || tok.isEmpty()) continue;
            int dash = tok.indexOf('-');
            if (dash > 0) {
                try {
                    int a = Integer.parseInt(tok.substring(0, dash));
                    int b = Integer.parseInt(tok.substring(dash + 1));
                    if (a > b) { int tmp = a; a = b; b = tmp; }
                    for (int i = a; i <= b; i++) set.add(i);
                } catch (Throwable ignored) {}
            } else {
                try { set.add(Integer.parseInt(tok)); } catch (Throwable ignored) {}
            }
        }

        int[] out = new int[set.size()];
        int i = 0;
        for (Integer v : set) out[i++] = v;
        return out;
    }

    private static int[] toIntArray(TreeSet<Integer> set) {
        if (set == null || set.isEmpty()) return new int[0];
        int[] out = new int[set.size()];
        int i = 0;
        for (Integer v : set) out[i++] = v;
        return out;
    }

    private static int[] intersect(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return new int[0];
        HashSet<Integer> sb = new HashSet<>();
        for (int x : b) sb.add(x);
        ArrayList<Integer> res = new ArrayList<>();
        for (int x : a) if (sb.contains(x)) res.add(x);
        int[] out = new int[res.size()];
        for (int i = 0; i < res.size(); i++) out[i] = res.get(i);
        return out;
    }

    private static int[] union(int[] a, int[] b) {
        TreeSet<Integer> s = new TreeSet<>();
        if (a != null) for (int x : a) s.add(x);
        if (b != null) for (int x : b) s.add(x);
        int[] out = new int[s.size()];
        int i = 0;
        for (Integer v : s) out[i++] = v;
        return out;
    }

    private static int[] subtract(int[] a, int[] b) {
        if (a == null || a.length == 0) return new int[0];
        HashSet<Integer> sb = new HashSet<>();
        if (b != null) for (int x : b) sb.add(x);
        ArrayList<Integer> res = new ArrayList<>();
        for (int x : a) if (!sb.contains(x)) res.add(x);
        int[] out = new int[res.size()];
        for (int i = 0; i < res.size(); i++) out[i] = res.get(i);
        return out;
    }
    // ---- Extra CpuWarmUp helpers (public, no Tiers leakage) ----

    /** Returns BIG core count (2 on MTK G99). */
    public static int getBigCoreCount() {
        final int[] v = getBigCpus();
        return (v != null) ? v.length : 0;
    }

    /** Returns LITTLE core count (6 on MTK G99). */
    public static int getLittleCoreCount() {
        final int[] v = getLittleCpus();
        return (v != null) ? v.length : 0;
    }

    /** Returns BIG CPUs (safe copy). */
    public static int[] getBigCpus() {
        final Tiers t = detectTiersCached();
        if (t == null || t.big == null) return new int[0];
        return Arrays.copyOf(t.big, t.big.length);
    }

    /** Returns LITTLE CPUs (safe copy). */
    public static int[] getLittleCpus() {
        final Tiers t = detectTiersCached();
        if (t == null || t.little == null) return new int[0];
        return Arrays.copyOf(t.little, t.little.length);
    }

    /** Returns PRIME CPUs (safe copy). Empty on 2-tier SoCs like MTK G99. */
    public static int[] getPrimeCpus() {
        final Tiers t = detectTiersCached();
        if (t == null || t.prime == null) return new int[0];
        return Arrays.copyOf(t.prime, t.prime.length);
    }

    /** Optional: human-readable tiers for logs/debug. */
    public static String describeTiers() {
        final Tiers t = detectTiersCached();
        if (t == null) return "tiers=null";
        return "prime=" + Arrays.toString(t.prime) +
                " big=" + Arrays.toString(t.big) +
                " little=" + Arrays.toString(t.little);
    }

}
