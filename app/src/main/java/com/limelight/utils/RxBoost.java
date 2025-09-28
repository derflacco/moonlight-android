package com.limelight.utils;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.Process;

/**
 * Boost & pin dei thread RX (UDP/RTP) per ridurre packet loss da saturazione.
 *
 * <p><b>Usage</b> (chiamare quando parte lo streaming, poi opzionale refresher):</p>
 * <pre>
 *   RxBoost.boostRxThreads(context, true);   // oppure false se non vuoi pin sui big cores
 *   RxBoost.scheduleRxRefresh(context, true);
 * </pre>
 *
 * <p>Richiede la presenza di CpuAffinity.java in com.limelight.utils.</p>
 */
public final class RxBoost {
    private RxBoost() {}

    private static boolean isLikelyRxThread(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        return n.contains("rx") || n.contains("recv") || n.contains("udp") ||
                n.contains("rtp") || n.contains("net") || n.contains("gs-recv") ||
                n.contains("gamestream") || n.contains("quic") || n.contains("socket");
    }

    /** Pin e boost dei thread che sembrano RX di rete (best-effort, non richiede root). */
    public static void boostRxThreads(Context ctx, boolean preferBigCores) {
        try {
            int[] big = null;
            if (preferBigCores) {
                try { big = CpuAffinity.detectPerfCpusAvoidPrimeOnly(); } catch (Throwable ignored) {}
                if (big == null || big.length == 0) {
                    try { big = CpuAffinity.detectBigCores(); } catch (Throwable ignored) {}
                }
            }

            int[] tids = CpuAffinity.listTids();
            for (int tid : tids) {
                String name = CpuAffinity.readThreadName(tid);
                if (!isLikelyRxThread(name)) continue;

                // priorità alta
                try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY); } catch (Throwable ignored) {}

                // pin sui big cores, se richiesto e disponibili
                if (preferBigCores && big != null && big.length > 0) {
                    try { CpuAffinity.setAffinityForTid(tid, big); } catch (Throwable ignored) {}
                }
            }

            // PerformanceHint (API 31+): piccolo hint di budget (best-effort)
            if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
                try {
                    PerformanceHintManager phm = ctx.getSystemService(PerformanceHintManager.class);
                    if (phm != null) {
                        int tid = Process.myTid();
                        long targetWorkNs = 1_000_000L; // ~1 ms
                        PerformanceHintManager.Session hs =
                                phm.createHintSession(new int[]{ tid }, targetWorkNs);
                        if (hs != null) {
                            try { hs.updateTargetWorkDuration(targetWorkNs); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** Re-pin/re-boost per qualche secondo per agganciare thread che nascono più tardi. */
    public static void scheduleRxRefresh(final Context ctx, final boolean preferBigCores) {
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    boostRxThreads(ctx, preferBigCores);
                    Thread.sleep(1000);
                } catch (Throwable ignored) {}
            }
        }, "RXBoostRefresher").start();
    }
}
