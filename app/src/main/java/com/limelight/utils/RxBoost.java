package com.limelight.utils;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.Process;

/**
 * Boost & pin likely RX threads (UDP/RTP) to reduce saturation-induced packet loss.
 *
 * Usage (call once when streaming starts, then optional refresher):
 *   RxBoost.boostRxThreads(context, /*preferBigCores=*/ true_or_false);
 *   RxBoost.scheduleRxRefresh(context, /*preferBigCores=*/ true_or_false);
 *
 * Requires CpuAffinity.java already present in com.limelight.utils.
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

    /** Pin and boost threads that look like network RX (best-effort, safe on non-root). */
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

                // Raise scheduling priority
                try { Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY); } catch (Throwable ignored) {}

                // Bind to big cores if requested and available
                if (preferBigCores && big != null && big.length > 0) {
                    try { CpuAffinity.setAffinityForTid(tid, big); } catch (Throwable ignored) {}
                }
            }

            // PerformanceHint (API 31+): give scheduler a small budget hint (best-effort)
            if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
                try {
                    PerformanceHintManager phm = ctx.getSystemService(PerformanceHintManager.class);
                    if (phm != null) {
                        int tid = Process.myTid();
                        long targetWorkNs = 1_000_000L; // ~1 ms
                        PerformanceHintManager.Session hs = phm.createHintSession(new int[]{ tid }, targetWorkNs);
                        if (hs != null) {
                            try { hs.updateTargetWorkDuration(targetWorkNs); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /** Re-pin and re-boost a few times to catch threads that spawn later. */
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