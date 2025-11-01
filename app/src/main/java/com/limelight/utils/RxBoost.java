package com.limelight.utils;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Boost & pin likely RX threads (UDP/RTP) to reduce saturation-induced packet loss.
 *
 * Usage (call once when streaming starts, then optional refresher):
 *   RxBoost.boostRxThreads(context, true_or_false);
 *   RxBoost.scheduleRxRefresh(context, true_or_false);
 *
 * Requires CpuAffinity.java already present in com.limelight.utils.
 */
public final class RxBoost {
    private RxBoost() {}

    private static final String TAG = "RxBoost";

    // Less noise by default
    private static volatile boolean sDebugLogCandidates = false;
    private static final boolean LOG_SCAN_SUMMARY = true;

    // Log limit per each scan (each refresher pass)
    private static final int MAX_LOG_PER_SCAN = 6;

    // To silence repeated PHM failures
    private static final long PHM_FAIL_MUTE_MS = 120_000L; // 2 minutes
    private static volatile long sLastPhmFailRealtime = 0L;

    // More targeted: names that in our codebase truly indicate RX/NET
    private static final String[] NAME_HINTS = new String[] {
            "video-recv", "audio-recv", "control-recv",
            "gs-recv", "recv", "rx", "udp", "rtp", "quic",
            "socket", "net", "gamestream"
    };

    // Names to ignore (known non-RX threads that may contain 'net' or similar)
    private static final String[] NAME_EXCLUDES = new String[] {
            "rxboostrefresher", "renderthread", "finalizerwatchdog", "hwui", "hwuiTask",
            "binder", "hwbinder", "HeapTaskDaemon", "ReferenceQueueDaemon"
    };

    // Wchan hints: ONLY true packet reception call-paths
    private static final String[] WCHAN_HINTS = new String[] {
            "udp_recvmsg", "inet_recvmsg", "__skb_wait_for_more_packets", "skb_copy_datagram"
    };

    private static final Set<Integer> sManagedTids = ConcurrentHashMap.newKeySet();

    // PHM (API 31+)
    private static final Object sPhmLock = new Object();
    private static volatile PerformanceHintManager.Session sPhmSession = null;
    private static volatile int[] sPhmSessionTids = null;
    private static final long PHM_TARGET_WORK_NS = 1_000_000L; // ~1 ms for RX

    // Cache "big/perf" CPUs per process to avoid repeated sysfs parsing
    private static volatile int[] sCachedBigCpus = null;

    // Refresher control
    private static volatile boolean sStopRefresh = false;
    private static volatile Thread sRefreshThread = null;

    /** Enable/disable verbose candidate logging at runtime. */
    public static void setDebugLogCandidates(boolean enable) {
        sDebugLogCandidates = enable;
    }

    private static boolean nameLooksRx(String name) {
        if (name == null) return false;
        final String n = name.toLowerCase(Locale.US);
        for (String ex : NAME_EXCLUDES) {
            if (n.contains(ex)) return false;
        }
        for (String h : NAME_HINTS) {
            if (n.contains(h)) return true;
        }
        return false;
    }

    private static boolean wchanLooksRx(String wchan) {
        if (wchan == null) return false;
        final String w = wchan.toLowerCase(Locale.US);
        for (String h : WCHAN_HINTS) {
            if (w.contains(h)) return true;
        }
        return false;
    }

    private static String readFirstLine(File f) {
        try {
            if (!f.exists()) return null;
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String s = br.readLine();
                if (s == null) return null;
                s = s.trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Best-effort read of the kernel wait channel for a thread
    private static String readWchan(int tid) {
        try {
            File f = new File("/proc/self/task/" + tid + "/wchan");
            String s = readFirstLine(f);
            if (s == null || "0".equals(s)) return null; // alcuni kernel espongono "0"
            return s;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // Fallback: leggi il nome dal /proc se CpuAffinity.readThreadName() fallisce
    private static String readThreadNameComm(int tid) {
        try {
            return readFirstLine(new File("/proc/self/task/" + tid + "/comm"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Parser semplice per liste CPU in formati "0 1 2 3" o "6-7". */
    private static int[] parseCpuList(String txt) {
        try {
            if (txt == null) return null;
            String[] toks = txt.trim().split("[,\\s]+");
            ArrayList<Integer> out = new ArrayList<>();
            for (String t : toks) {
                if (t.isEmpty()) continue;
                if (t.contains("-")) {
                    String[] ab = t.split("-");
                    if (ab.length == 2) {
                        int a = Integer.parseInt(ab[0]);
                        int b = Integer.parseInt(ab[1]);
                        if (a <= b) for (int i = a; i <= b; i++) out.add(i);
                    }
                } else {
                    out.add(Integer.parseInt(t));
                }
            }
            if (out.isEmpty()) return null;
            // dedup + sort
            Set<Integer> set = new HashSet<>(out);
            int[] arr = new int[set.size()];
            int i = 0;
            for (int v : set) arr[i++] = v;
            Arrays.sort(arr);
            return arr;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Seleziona i core del cluster con la freq massima via cpufreq/policy* */
    private static int[] detectFastClusterFromCpufreq() {
        try {
            File root = new File("/sys/devices/system/cpu/cpufreq");
            File[] pols = root.listFiles(new FileFilter() {
                @Override public boolean accept(File pathname) {
                    String n = pathname.getName();
                    return pathname.isDirectory() && n.startsWith("policy");
                }
            });
            if (pols == null || pols.length == 0) return null;

            long bestHz = -1;
            int[] best = null;

            for (File p : pols) {
                String sMax = readFirstLine(new File(p, "cpuinfo_max_freq"));
                long hz = -1;
                try { if (sMax != null) hz = Long.parseLong(sMax); } catch (Throwable ignored) {}
                if (hz <= 0) continue;

                int[] cpus = parseCpuList(readFirstLine(new File(p, "related_cpus")));
                if (cpus == null) cpus = parseCpuList(readFirstLine(new File(p, "affected_cpus")));
                if (cpus == null || cpus.length == 0) continue;

                if (hz > bestHz) {
                    bestHz = hz;
                    best = cpus;
                }
            }
            if (sDebugLogCandidates) {
                Log.i(TAG, "cpufreq probe: bestHz=" + bestHz + " cpus=" + (best != null ? Arrays.toString(best) : "<none>"));
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sameTidSet(int[] a, int[] b) {
        if (a == null || b == null) return false;
        if (a.length != b.length) return false;
        Set<Integer> set = new HashSet<>();
        for (int x : a) set.add(x);
        for (int y : b) if (!set.contains(y)) return false;
        return true;
    }

    private static void ensurePhmSession(Context ctx, int[] tids) {
        if (Build.VERSION.SDK_INT < 31 || ctx == null || tids == null || tids.length == 0) return;
        try {
            final long now = SystemClock.elapsedRealtime();

            synchronized (sPhmLock) {
                // if we already detected that PowerHAL is not available, don't spam every second
                if (sPhmSession == null && (now - sLastPhmFailRealtime) < PHM_FAIL_MUTE_MS) {
                    return;
                }

                PerformanceHintManager phm = ctx.getSystemService(PerformanceHintManager.class);
                if (phm == null) {
                    // no PHM → don't spam
                    sLastPhmFailRealtime = now;
                    return;
                }

                boolean recreate = (sPhmSession == null) || !sameTidSet(sPhmSessionTids, tids);
                if (recreate) {
                    try { if (sPhmSession != null) sPhmSession.close(); } catch (Throwable ignored) {}
                    try {
                        sPhmSession = phm.createHintSession(tids, PHM_TARGET_WORK_NS);
                    } catch (Throwable t) {
                        sPhmSession = null; // PowerHAL not supported on many devices
                    }
                    sPhmSessionTids = (sPhmSession != null) ? tids.clone() : null;

                    if (sPhmSession != null) {
                        try { sPhmSession.updateTargetWorkDuration(PHM_TARGET_WORK_NS); } catch (Throwable ignored) {}
                        if (sDebugLogCandidates) Log.i(TAG, "PHM session created tids=" + tids.length);
                    } else {
                        // mark failure and silence for a while
                        sLastPhmFailRealtime = now;
                        if (sDebugLogCandidates) Log.i(TAG, "PHM session failed tids=" + tids.length + " (muted for a while)");
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static int[] pickBigOrPerfCpus(boolean preferBigCores) {
        if (!preferBigCores) return null;

        // Cache per evitare probe ripetute
        int[] cached = sCachedBigCpus;
        if (cached != null && cached.length > 0) {
            return cached;
        }

        // 1) tentativo robusto: cluster più veloce da cpufreq
        int[] big = detectFastClusterFromCpufreq();

        // 2) fallback sugli helper esistenti
        if (big == null || big.length == 0) {
            try { big = CpuAffinity.detectPerfCpusAvoidPrimeOnly(); } catch (Throwable ignored) {}
        }
        if (big == null || big.length == 0) {
            try { big = CpuAffinity.detectBigCores(); } catch (Throwable ignored) {}
        }

        if (sDebugLogCandidates) {
            Log.i(TAG, "Big/perf CPUs chosen: " + (big != null ? Arrays.toString(big) : "<none>"));
        }
        if (big != null && big.length > 0) {
            sCachedBigCpus = big.clone();
            return big;
        }
        return null;
    }

    // Rimuove TID non più esistenti dal set gestito (riduce rumore PHM/log)
    private static void pruneDeadTids() {
        try {
            ArrayList<Integer> toRemove = new ArrayList<>();
            for (int tid : sManagedTids) {
                File taskDir = new File("/proc/self/task/" + tid);
                if (!taskDir.exists()) {
                    toRemove.add(tid);
                }
            }
            if (!toRemove.isEmpty()) {
                sManagedTids.removeAll(toRemove);
            }
        } catch (Throwable ignored) {}
    }

    // Best-effort read thread name (prefer CpuAffinity helper, fallback a /proc)
    private static String getThreadName(int tid) {
        String name = null;
        try { name = CpuAffinity.readThreadName(tid); } catch (Throwable ignored) {}
        if (name == null) {
            name = readThreadNameComm(tid);
        }
        return name;
    }

    /** Pin and boost threads that look like network RX (best-effort, safe on non-root). */
    public static void boostRxThreads(Context ctx, boolean preferBigCores) {
        try {
            pruneDeadTids();

            int[] big = pickBigOrPerfCpus(preferBigCores);
            int[] tids = CpuAffinity.listTids();

            // maximum log for this scan
            final boolean verbose = sDebugLogCandidates;
            int logBudget = verbose ? MAX_LOG_PER_SCAN : 0;

            int totalTids = 0;
            int matchCount = 0;
            int newManagedCount = 0;
            int alreadyManagedCount = 0;

            for (int tid : tids) {
                if (tid <= 0) continue;
                totalTids++;

                // Avoid rework
                boolean already = sManagedTids.contains(tid);

                // Gather name and, if needed, wchan
                String name = getThreadName(tid);

                boolean matchByName = nameLooksRx(name);
                String wchan = null;
                boolean matchByWchan = false;

                // Read wchan only if needed or if we still have log budget
                if (!matchByName) {
                    wchan = readWchan(tid);
                    matchByWchan = wchanLooksRx(wchan);
                } else if (verbose && logBudget > 0) {
                    wchan = readWchan(tid);
                }

                boolean match = matchByName || matchByWchan;
                // we already have a name match, but also log wchan if there's budget
                if (match) {
                    matchCount++;
                    if (already) {
                        alreadyManagedCount++;
                    }
                }

                // verbose logs
                if (verbose && logBudget > 0) {
                    Log.i(TAG, "RX? tid=" + tid +
                            " name='" + (name != null ? name : "?") + "'" +
                            " wchan='" + (wchan != null ? wchan : "?") + "'" +
                            " -> " + (match ? "MATCH" : "skip") +
                            (already ? " (already managed)" : ""));
                    logBudget--;
                }

                if (!match || already) {
                    continue;
                }

                // Increase priority
                try {
                    Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY);
                } catch (Throwable ignored) {}

                // Pin to big cores (if available)
                if (big != null) {
                    try { CpuAffinity.setAffinityForTid(tid, big); } catch (Throwable ignored) {}
                }

                sManagedTids.add(tid);
                newManagedCount++;

                Log.i(TAG, "RX BOOST tid=" + tid +
                        " name='" + (name != null ? name : "?") + "'" +
                        (big != null ? (" pin=" + Arrays.toString(big)) : " pin=<none>"));
            }

            // Build/refresh a PHM session with the union of all managed RX tids
            if (!sManagedTids.isEmpty()) {
                int[] all = new int[sManagedTids.size()];
                int i = 0; for (int t : sManagedTids) all[i++] = t;
                ensurePhmSession(ctx, all);
            }

            // summary always visibile
            if (LOG_SCAN_SUMMARY) {
                Log.i(TAG, "RX scan: total=" + totalTids
                        + " match=" + matchCount
                        + " new=" + newManagedCount
                        + " already=" + alreadyManagedCount
                        + " managed_total=" + sManagedTids.size());
            }

        } catch (Throwable ignored) {}
    }

    /** Re-pin and re-boost a few times to catch threads that spawn later. */
    public static void scheduleRxRefresh(final Context ctx, final boolean preferBigCores) {
        sStopRefresh = false;
        Thread t = new Thread(() -> {
            for (int i = 0; i < 10 && !sStopRefresh; i++) {
                try {
                    if (sDebugLogCandidates) Log.i(TAG, "RX refresh pass " + (i + 1) + "/10");
                    boostRxThreads(ctx, preferBigCores);
                    for (int s = 0; s < 10 && !sStopRefresh; s++) {
                        Thread.sleep(100); // 1s totale, con early-stop reattivo
                    }
                } catch (Throwable ignored) {}
            }
        }, "RXBoostRefresher");
        try { t.setPriority(Thread.MAX_PRIORITY); } catch (Throwable ignored) {}
        try { t.setDaemon(true); } catch (Throwable ignored) {}
        sRefreshThread = t;
        t.start();
    }

    /** Optional: call at stream end to drop state and close PHM session. */
    public static void cleanup() {
        // Stop the refresh
        sStopRefresh = true;
        try {
            Thread r = sRefreshThread;
            if (r != null) {
                sRefreshThread = null;
         // We don't block: it's a daemon and terminates on its own
            }
        } catch (Throwable ignored) {}

        sManagedTids.clear();
        synchronized (sPhmLock) {
            try { if (sPhmSession != null) sPhmSession.close(); } catch (Throwable ignored) {}
            sPhmSession = null;
            sPhmSessionTids = null;
        }
        if (sDebugLogCandidates) Log.i(TAG, "Cleanup complete");
    }
}
