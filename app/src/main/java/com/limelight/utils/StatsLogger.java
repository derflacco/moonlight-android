package com.limelight.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lightweight stats logger, rate-limited and change-triggered. */
public final class StatsLogger {
    // --- CONFIG ---
    private static final boolean ENABLED = false;         // set false per silenziare in perf/release
    private static final long PRINT_PERIOD_MS = 3000;    // ~3s tra stampe
    private static final int MAX_SUPPRESS = 5;           // stampa comunque almeno ogni ~15s

    // soglie minime di variazione per stampare
    private static final int  MIN_FPS_DELTA = 8;             // fps
    private static final long MIN_DEC_DELTA_NS = 500_000L;   // 0.5 ms
    private static final long MIN_SLIP_DELTA_NS = 1_000_000L;// 1.0 ms
    private static final int  MIN_Q_DELTA = 2;               // depth coda

    // --- CONTATORI 1s/periodo ---
    private static final AtomicInteger frames = new AtomicInteger();

    private static final AtomicLong decSumNs = new AtomicLong();
    private static final AtomicInteger decCount = new AtomicInteger();
    private static final AtomicLong lastDecodeNs = new AtomicLong(); // ultimo campione (ns)

    private static final AtomicLong presentSlipSumNs = new AtomicLong();
    private static final AtomicInteger presentSlipCount = new AtomicInteger();

    private static final AtomicInteger outQMax = new AtomicInteger();
    private static final AtomicInteger outQLast = new AtomicInteger();

    private static final AtomicInteger decoded = new AtomicInteger();
    private static final AtomicInteger drops = new AtomicInteger();

    private static final AtomicLong lastSwapResult = new AtomicLong(); // 1=ok, 0=err

    // --- STATO LOGGER ---
    private static volatile boolean started;

    // ultimi valori stampati (per soppressione)
    private static volatile int  p_lastFps, p_lastIn, p_lastDrops, p_lastQ, p_lastQMax, p_lastSwapOk;
    private static volatile long p_lastAvgDecNs, p_lastAvgSlipNs;
    private static volatile int  suppressCount;

    private StatsLogger() {}

    // ---- API chiamate dal renderer ----
    /** Call when a frame is actually presented to screen. */
    public static void onFramePresented() { frames.incrementAndGet(); }

    /** Back-compat: single sample -> devia su add() per media. */
    public static void setDecodeTimeNs(long ns) { addDecodeTimeNs(ns); }

    /** Accumula decode time sample (ns) per media di periodo. */
    public static void addDecodeTimeNs(long ns) {
        if (ns > 0) {
            lastDecodeNs.set(ns);
            decSumNs.addAndGet(ns);
            decCount.incrementAndGet();
        }
    }

    /** Call quando il decoder produce un frame valido. */
    public static void incDecoded() { decoded.incrementAndGet(); }

    /** Call quando droppi (releaseOutputBuffer(..., false)). */
    public static void incDrop() { drops.incrementAndGet(); }

    /** Aggiorna la profondità della coda output (Balanced). */
    public static void setOutputQueueDepth(int depth) {
        outQLast.set(depth);
        // atomic max
        int prev, next;
        do {
            prev = outQMax.get();
            next = Math.max(prev, depth);
        } while (!outQMax.compareAndSet(prev, next));
    }

    /** Aggiunge lo slip |now - scheduledNs| del present (ns). */
    public static void addPresentSlipNs(long ns) {
        if (ns >= 0) {
            presentSlipSumNs.addAndGet(ns);
            presentSlipCount.incrementAndGet();
        }
    }

    /** Esito ultimo swap/present. */
    public static void setSwapOk(boolean ok) { lastSwapResult.set(ok ? 1 : 0); }

    /** Avvia il logger rate-limited (idempotente). */
    public static void start() {
        if (!ENABLED || started) return;
        started = true;
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (!ENABLED) return;

                // snapshot + reset contatori
                int fps = frames.getAndSet(0);

                long dSum = decSumNs.getAndSet(0);
                int  dCnt = decCount.getAndSet(0);
                long last = lastDecodeNs.get();
                long avgDecNs = (dCnt > 0) ? (dSum / Math.max(1, dCnt)) : last;

                long sSum = presentSlipSumNs.getAndSet(0);
                int  sCnt = presentSlipCount.getAndSet(0);
                long avgSlipNs = (sCnt > 0) ? (sSum / Math.max(1, sCnt)) : 0L;

                int in = decoded.getAndSet(0);
                int dr = drops.getAndSet(0);
                int qMax = outQMax.getAndSet(0);
                int q = outQLast.get();

                int swapOk = (int) lastSwapResult.get();

                // decide se stampare
                boolean changed =
                        Math.abs(fps - p_lastFps) >= MIN_FPS_DELTA ||
                                Math.abs(avgDecNs - p_lastAvgDecNs) >= MIN_DEC_DELTA_NS ||
                                Math.abs(avgSlipNs - p_lastAvgSlipNs) >= MIN_SLIP_DELTA_NS ||
                                Math.abs(q - p_lastQ) >= MIN_Q_DELTA ||
                                qMax > p_lastQMax;

                boolean event = (dr > 0) || (swapOk == 0) || (in == 0 && fps == 0); // cose "interessanti"

                if (!(changed || event) && suppressCount < MAX_SUPPRESS) {
                    suppressCount++;
                } else {
                    suppressCount = 0;
                    // stampa 1 riga compatta
                    Log.d("MoonStats",
                            "fps=" + fps +
                                    " in=" + in +
                                    " drop=" + dr +
                                    " q=" + q + "/" + qMax +
                                    " decodeNs=" + avgDecNs +
                                    " slipNs=" + avgSlipNs +
                                    " swapOk=" + swapOk);

                    // aggiorna baseline
                    p_lastFps = fps;
                    p_lastIn = in;
                    p_lastDrops = dr;
                    p_lastQ = q;
                    p_lastQMax = qMax;
                    p_lastAvgDecNs = avgDecNs;
                    p_lastAvgSlipNs = avgSlipNs;
                    p_lastSwapOk = swapOk;
                }

                h.postDelayed(this, PRINT_PERIOD_MS);
            }
        }, PRINT_PERIOD_MS);
    }
}
