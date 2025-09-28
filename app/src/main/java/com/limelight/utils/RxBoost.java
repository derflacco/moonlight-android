package com.limelight.utils;

import android.content.Context;
import android.os.Build;
import android.os.PerformanceHintManager;
import android.os.Process;
import android.util.Log;

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
    private static final String TAG = "RxBoost";

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
        int pinned = 0;
        int candidates = 0;
        int[] big = null;

        try {
            if (preferBigCores) {
                try { big = CpuAffinity.detectPerfCpusAvoidPrimeOnly(); } catch (Throwable ignored) {}
                if (big == null || big.length == 0) {
                    try { big = CpuAffinity.detectBigCores(); } catch (Throwable ignored) {}
                }
            }

            Log.i(TAG, "start: preferBigCores=" + preferBigCores +
                    " bigCores=" + (big == null ? "[]" : java.util.Arrays.toString(big)));

            int[] tids = CpuAffinity.listTids();
            for (int tid : tids) {
                String name = null;
                try { name = CpuAffinity.readThreadName(tid); } catch (Throwable ignored) {}
                if (!isLikelyRxThread(name)) {
                    continue;
                }

                candidates++;
                Log.i(TAG, "candidate tid=" + tid + " name=" + name);

                // priorità alta
                try {
                    Process.setThreadPriority(tid, Process.THREAD_PRIORITY_URGENT_DISPLAY);
                    Log.i(TAG, " setThreadPriority ok tid=" + tid);
                } catch (Throwable t) {
                    Log.w(TAG, " setThreadPriority failed tid=" + tid + " err=" + t.getClass().getSimpleName());
                }

                // pin sui big cores, se richiesto e disponibili
                boolean pinnedThis = false;
                if (preferBigCores && big != null && big.length > 0) {
                    try {
                        CpuAffinity.setAffinityForTid(tid, big);
                        pinnedThis = true;
                        Log.i(TAG, " setAffinityForTid ok tid=" + tid);
                    } catch (Throwable t) {
                        Log.w(TAG, " setAffinityForTid failed tid=" + tid + " err=" + t.getClass().getSimpleName());
                    }
                }

                if (pinnedThis) pinned++;
            }

            // PerformanceHint (API 31+): usa solo se davvero supportato
            if (Build.VERSION.SDK_INT >= 31 && ctx != null) {
                try {
                    PerformanceHintManager phm = ctx.getSystemService(PerformanceHintManager.class);
                    if (phm != null) {
                        long rateNs = 0L;
                        try { rateNs = phm.getPreferredUpdateRateNanos(); } catch (Throwable ignored) {}
                        if (rateNs > 0L) {
                            int tid = Process.myTid();
                            long targetWorkNs = 1_000_000L; // ~1 ms
                            PerformanceHintManager.Session hs =
                                    phm.createHintSession(new int[]{ tid }, targetWorkNs);
                            if (hs != null) {
                                try { hs.updateTargetWorkDuration(targetWorkNs); } catch (Throwable ignored) {}
                                Log.i(TAG, "PHM: session active (rateNs=" + rateNs + ", targetNs=" + targetWorkNs + ")");
                            } else {
                                Log.i(TAG, "PHM: createHintSession returned null (rateNs=" + rateNs + ")");
                            }
                        } else {
                            Log.i(TAG, "PHM: skipped (rateNs=" + rateNs + ")");
                        }
                    } else {
                        Log.i(TAG, "PHM: not available (manager=null)");
                    }
                } catch (Throwable t) {
                    // Evita spam di stacktrace: log compatto
                    Log.i(TAG, "PHM: not supported (" + t.getClass().getSimpleName() + ")");
                }
            } else {
                Log.i(TAG, "PHM: skipped (sdk=" + Build.VERSION.SDK_INT + ", ctx=" + (ctx != null) + ")");
            }
        } catch (Throwable t) {
            Log.w(TAG, "boostRxThreads: error " + t.getClass().getSimpleName());
        }

        Log.i(TAG, "done: candidates=" + candidates + " pinned=" + pinned);
    }

    /** Re-pin/re-boost per qualche secondo per agganciare thread che nascono più tardi. */
    public static void scheduleRxRefresh(final Context ctx, final boolean preferBigCores) {
        new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                try {
                    boostRxThreads(ctx, preferBigCores);
                    Thread.sleep(1000);
                } catch (Throwable t) {
                    Log.w(TAG, "refresh error: " + t.getClass().getSimpleName());
                }
            }
        }, "RXBoostRefresher").start();
    }
}
