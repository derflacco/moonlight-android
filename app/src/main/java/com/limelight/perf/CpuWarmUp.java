package com.limelight.perf;

/**
 * CpuWarmUp (MEDIUM): multi-thread warm-up to gently nudge DVFS.
 * - 8 workers (clamped to available cores)
 * - ~50% duty (2.5 ms spin + 2 ms sleep)
 * - Light 10 ms burst every 2 s
 * - Best-effort big-core pin + FOREGROUND priority
 *
 * PerfHint: pass any object that has a boolean method named "isActive" (via reflection).
 * If it returns true and overridePerfHint is false, this helper no-ops to avoid fighting ADPF.
 *
 * Usage:
 * <pre>
 * CpuWarmUp w = new CpuWarmUp();
 * w.start(perfHintObject, false); // perfHintObject can be null; pass true to override PerfHint
 * ...
 * w.stop();
 * </pre>
 */
public final class CpuWarmUp {
    private final java.util.List<Thread> workers = new java.util.ArrayList<>();
    private volatile boolean running = false;

    // Blackhole to prevent JIT from optimizing away FP work
    private static volatile double BH = 0.0;

    // Master switch (build-time kill switch if needed)
    private static final boolean ENABLE = true;

    // 8 workers (clamped to available cores, never < 1)
    private static final int WORKERS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));

    // Duty-cycle: ~2.5 ms spin + 2 ms sleep ≈ around 50%
    private static final int SPIN_MICROS  = 2_500;
    private static final int SLEEP_MILLIS = 2;

    // Light burst every 2 seconds: 10 ms
    private static final int BURST_PERIOD_MS = 2_000;
    private static final int BURST_SPIN_MS   = 10;

    // High but not "display" priority
    private static final int THREAD_PRIO = android.os.Process.THREAD_PRIORITY_FOREGROUND;

    /**
     * Start warm-up.
     * @param perfHint may be null; if non-null and exposes boolean isActive(), we skip unless overridePerfHint=true
     * @param overridePerfHint force run even if PerfHint is active
     */
    public synchronized void start(Object perfHint, boolean overridePerfHint) {
        if (!ENABLE) return;
        if (running) return;

        // Check PerfHint via reflection (robust: tries multiple method/field names)
        if (!overridePerfHint && isPerfHintActive(perfHint)) {
            return;
        }

        running = true;
        workers.clear();

        for (int i = 0; i < WORKERS; i++) {
            final int id = i;
            Thread t = new Thread(() -> {
                try { android.os.Process.setThreadPriority(THREAD_PRIO); } catch (Throwable ignored) {}
                // Best-effort pin on big cores (no-op if lib missing)
                try { com.limelight.utils.CpuAffinity.pinCurrentThreadToBigCoresIf(true); } catch (Throwable ignored) {}

                final String original = Thread.currentThread().getName();
                try { Thread.currentThread().setName("CpuWarmUp-" + id); } catch (Throwable ignored) {}

                long lastBurstMs = android.os.SystemClock.uptimeMillis();

                while (running && !Thread.currentThread().isInterrupted()) {
                    // ---- duty phase ----
                    final long startNs = System.nanoTime();
                    final long spinNs  = SPIN_MICROS * 1_000L;
                    double sink = 0.0;
                    while ((System.nanoTime() - startNs) < spinNs) {
                        // Quick check to shorten stop latency during spin
                        if (!running || Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        // FP ops to prevent trivial elimination
                        sink += Math.sin(sink + 1.0);
                    }
                    // Anti-JIT blackhole
                    BH = sink;

                    try {
                        Thread.sleep(SLEEP_MILLIS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }

                    // ---- periodic burst ----
                    long now = android.os.SystemClock.uptimeMillis();
                    if ((now - lastBurstMs) >= BURST_PERIOD_MS) {
                        final long until = System.nanoTime() + (BURST_SPIN_MS * 1_000_000L);
                        double bs = BH;
                        while (System.nanoTime() < until) {
                            if (!running || Thread.currentThread().isInterrupted()) {
                                break;
                            }
                            // Keep sqrt argument non-negative to avoid NaN propagation
                            bs += Math.sqrt(Math.abs(bs) + 3.141592653589793);
                        }
                        BH = bs; // blackhole update
                        lastBurstMs = now;
                    }
                }

                // restore original name (best-effort)
                try { Thread.currentThread().setName(original != null ? original : "CpuWarmUp-ended"); } catch (Throwable ignored) {}
            }, "CpuWarmUp-" + i);

            try { t.setDaemon(true); } catch (Throwable ignored) {} // do not block shutdown
            try { t.start(); } catch (Throwable ignored) {}
            workers.add(t);
        }
    }

    /** Stop warm-up and join threads (best-effort). */
    public synchronized void stop() {
        running = false;
        for (Thread t : workers) {
            if (t != null) {
                try { t.interrupt(); } catch (Throwable ignored) {}
            }
        }
        for (Thread t : workers) {
            if (t != null) {
                try { t.join(300); } catch (Throwable ignored) {}
            }
        }
        workers.clear();
    }

    // -------- helpers --------

    private static boolean isPerfHintActive(Object perfHint) {
        if (perfHint == null) return false;
        // Methods we recognize
        final String[] candidates = new String[] { "isActive", "isActiveNow", "isEnabled" };
        for (String name : candidates) {
            try {
                java.lang.reflect.Method m;
                try {
                    m = perfHint.getClass().getMethod(name);
                } catch (NoSuchMethodException nsme) {
                    m = perfHint.getClass().getDeclaredMethod(name);
                    m.setAccessible(true);
                }
                Object r = m.invoke(perfHint);
                if (r instanceof Boolean && (Boolean) r) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        // Fallback to fields named like "active"/"enabled"
        final String[] fields = new String[] { "active", "enabled" };
        for (String fname : fields) {
            try {
                java.lang.reflect.Field f;
                try {
                    f = perfHint.getClass().getField(fname);
                } catch (NoSuchFieldException nsfe) {
                    f = perfHint.getClass().getDeclaredField(fname);
                    f.setAccessible(true);
                }
                Object r = f.get(perfHint);
                if (r instanceof Boolean && (Boolean) r) {
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }
}
