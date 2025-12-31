package com.limelight.perf;

import android.os.Process;

/**
 * CpuWarmUp (MEDIUM): multi-thread warm-up to gently nudge DVFS.
 *  - 8 workers (clamped to available cores)
 *  - ~50% duty (2.5 ms spin + 2 ms sleep)
 *  - Light 10 ms burst every 2 s
 *  - Best-effort big-core pin + FOREGROUND thread priority
 *  - PerfHint: pass any object exposing a boolean method named "isActive" (checked via reflection).
 *    If it returns true and overridePerfHint is false, this helper no-ops to avoid fighting ADPF.
 *
 * Thermal-aware: self-throttles when the device is hot (API 29+ thermal status or battery °C fallback).
 *
 * Usage:
 *   CpuWarmUp w = new CpuWarmUp();
 *   w.start(perfHintObject, false);               // legacy (no thermal checks)
 *
 *   // Recommended (thermal-aware):
 *   w.start(context, perfHintObject, false);      // pass Application/Activity Context
 *   ...
 *   w.stop();
 */
public final class CpuWarmUp {
    private final java.util.List<Thread> workers = new java.util.ArrayList<>();
    private volatile boolean running = false;

    // --- DEBUG (best-effort) ---
    private static void logI(String m) {
        try { com.limelight.LimeLog.info("CpuWarmUp: " + m); }
        catch (Throwable t) { android.util.Log.i("CpuWarmUp", m); }
    }
    private static void logE(String m, Throwable e) {
        try { com.limelight.LimeLog.info("CpuWarmUp ERR: " + m + " (" + e + ")"); }
        catch (Throwable t) { android.util.Log.e("CpuWarmUp", m, e); }
    }

    // Blackhole to prevent JIT from optimizing away FP work
    private static volatile double BH = 0.0;

    // Master switch (build-time kill switch if needed)
    private static final boolean ENABLE = true;

    // Workers count (clamped to available cores, never < 1)
    private static final int WORKERS = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors()));

    // Base duty-cycle: ~2.5 ms spin + 2 ms sleep ≈ ~50%
    private static final int BASE_SPIN_MICROS  = 2_500;
    private static final int BASE_SLEEP_MILLIS = 2;

    // Light burst every 2 seconds: 10 ms
    private static final int BURST_PERIOD_MS = 2_000;
    private static final int BURST_SPIN_MS   = 10;

    // Thermal sampling period
    private static final int THERMAL_SAMPLE_MS = 2_000;

    // Battery temperature thresholds (°C) used as fallback when thermal status is unavailable
    private static final float T_WARM     = 42.0f;
    private static final float T_HOT      = 45.0f;
    private static final float T_CRITICAL = 48.0f;

    // ---- Architecture detection constants ----
    // Use three-tier (Prime/Big/Little) only if we detect Prime cores
    // Otherwise fall back to two-tier (Big/Little)
    private static final int ARCH_3_TIER = 3;
    private static final int ARCH_2_TIER = 2;

    // Detected architecture (cached)
    private static volatile int detectedArchitecture = -1;

    // ---- Affinity policy ----
    private static final int AFFINITY_BIG_ONLY = 0;
    private static final int AFFINITY_SPREAD   = 1;
    private static final int AFFINITY_NONE     = 2;

    // DEFAULT: spread across clusters based on detected architecture
    private static final int AFFINITY_MODE = AFFINITY_SPREAD;

    // Thread priorities per-bucket (to help scheduler):
    //  bucket 0 (prime/big) -> FOREGROUND
    //  bucket 1 (mid/big)   -> DEFAULT (for 3-tier), or little (for 2-tier)
    //  bucket 2 (little)    -> BACKGROUND (only for 3-tier)
    private static final int PRIO_PRIME   = Process.THREAD_PRIORITY_DEFAULT;
    private static final int PRIO_BIG     = android.os.Process.THREAD_PRIORITY_DEFAULT;
    private static final int PRIO_LITTLE  = Process.THREAD_PRIORITY_DEFAULT;

    // ---- thermal-aware dynamic knobs (per-instance, shared by workers) ----
    private volatile int  spinMicros       = BASE_SPIN_MICROS;   // reduced when hot
    private volatile int  sleepMillis      = BASE_SLEEP_MILLIS;  // increased when hot
    private volatile boolean skipBursts    = false;              // true when hot/critical
    private volatile long lastThermalSampleMs = 0L;

    // Optional context for thermal reading (can be null)
    private volatile android.content.Context appContext = null;

    // Improvement #1: only worker-0 samples thermals; guard avoids duplicate sampling
    private final java.util.concurrent.atomic.AtomicLong thermalGuard =
            new java.util.concurrent.atomic.AtomicLong(0L);

    /**
     * Start warm-up (legacy signature, no thermal checks).
     * @param perfHint may be null; if non-null and exposes boolean isActive(), we skip unless overridePerfHint=true
     * @param overridePerfHint force run even if PerfHint is active
     */
    public synchronized void start(Object perfHint, boolean overridePerfHint) {
        start(null, perfHint, overridePerfHint);
    }

    /**
     * Start warm-up (thermal-aware if context != null).
     * @param context Application/Activity context (use getApplicationContext()), may be null
     * @param perfHint may be null; if non-null and exposes boolean isActive(), we skip unless overridePerfHint=true
     * @param overridePerfHint force run even if PerfHint is active
     */
    public synchronized void start(android.content.Context context, Object perfHint, boolean overridePerfHint) {
        if (!ENABLE) { logI("start(): ENABLE=false"); return; }
        if (running) {
            logI("start(): already running; workers=" + workers.size());
            return;
        }

        // Avoid fighting with an existing ADPF/perf hint
        if (!overridePerfHint && isPerfHintActive(perfHint)) {
            logI("start(): gated by PerfHint active");
            return;
        }

        this.appContext = (context != null) ? context.getApplicationContext() : null;

        running = true;
        workers.clear();

        final int n = WORKERS;
        final int arch = getArchitecture();
        logI("start(): mode=" + modeName(AFFINITY_MODE) + " spawn=" + n + " arch=" + arch + "-tier");

        for (int i = 0; i < n; i++) {
            final int id = i;
            final int bucket = chooseBucketForWorker(id, arch);
            Thread t = new Thread(() -> {
                // Priority by bucket (helps scheduler pick cluster)
                try { android.os.Process.setThreadPriority(priorityForBucket(bucket, arch)); } catch (Throwable ignored) {}

                // Affinity by bucket (best-effort via reflection; falls back automatically)
                pinWorkerToBucket(bucket, arch);

                final String original = Thread.currentThread().getName();
                try { Thread.currentThread().setName("CpuWarmUp-" + id + "-" + bucketName(bucket, arch)); } catch (Throwable ignored) {}

                long lastBurstMs = android.os.SystemClock.uptimeMillis();
                logI("worker-" + id + " started -> bucket=" + bucketName(bucket, arch));

                while (running && !Thread.currentThread().isInterrupted()) {
                    // --- Improvement #1: only worker 0 samples thermals every THERMAL_SAMPLE_MS ---
                    if (id == 0) {
                        long nowMs = android.os.SystemClock.uptimeMillis();
                        long prev  = thermalGuard.get();
                        if (nowMs - prev >= THERMAL_SAMPLE_MS && thermalGuard.compareAndSet(prev, nowMs)) {
                            lastThermalSampleMs = nowMs; // telemetry
                            applyThermalPolicy(sampleThermals(appContext));
                        }
                    }

                    // ---- duty phase ----
                    final long startNs = System.nanoTime();
                    final long targetSpinNs = spinMicros * 1_000L;
                    double sink = 0.0;
                    while ((System.nanoTime() - startNs) < targetSpinNs) {
                        if (!running || Thread.currentThread().isInterrupted()) break;
                        // FP ops to prevent trivial elimination
                        sink += Math.sin(sink + 1.0);
                    }
                    // Anti-JIT blackhole
                    BH = sink;

                    try { Thread.sleep(sleepMillis); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }

                    // bursts
                    if (!skipBursts) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if ((now - lastBurstMs) >= BURST_PERIOD_MS) {
                            final long until = System.nanoTime() + (BURST_SPIN_MS * 1_000_000L);

                            // Improvement #3: simpler FP recurrence instead of sqrt (cheaper & predictable)
                            double bs = BH;
                            while (System.nanoTime() < until) {
                                if (!running || Thread.currentThread().isInterrupted()) break;
                                bs = bs * 1.000001 + 1.0;
                                if (bs > 1e9) bs = 0.0; // keep bounded
                            }
                            BH = bs;

                            lastBurstMs = now;
                        }
                    }
                }

                logI("worker-" + id + " exit (bucket=" + bucketName(bucket, arch) + ")");
                try { Thread.currentThread().setName(original != null ? original : "CpuWarmUp-ended"); } catch (Throwable ignored) {}
            }, "CpuWarmUp-" + i);

            try { t.setDaemon(true); } catch (Throwable ignored) {}
            try { t.start(); } catch (Throwable e) { logE("worker-" + id + " start failed", e); }
            workers.add(t);
        }
    }

    /** Stop warm-up and join threads (best-effort). */
    public synchronized void stop() {
        if (!running && workers.isEmpty()) return;
        logI("stop(): workers=" + workers.size());
        running = false;
        for (Thread t : workers) {
            if (t != null) {
                try { t.interrupt(); } catch (Throwable e) { logE("interrupt failed", e); }
            }
        }
        for (Thread t : workers) {
            if (t != null) {
                try { t.join(300); } catch (Throwable e) { logE("join failed", e); }
            }
        }
        workers.clear();
        appContext = null;
        // Restore defaults for next run
        spinMicros = BASE_SPIN_MICROS;
        sleepMillis = BASE_SLEEP_MILLIS;
        skipBursts = false;
        lastThermalSampleMs = 0L;
        thermalGuard.set(0L);
        logI("stop(): done");
    }

    // ---------------- Architecture detection ----------------

    /**
     * Detect CPU architecture tier count.
     * Returns 3 for Prime/Big/Little, 2 for Big/Little only.
     * Uses reflection to query CpuAffinity helper if available.
     */
    private static int getArchitecture() {
        if (detectedArchitecture != -1) return detectedArchitecture;

        int arch = ARCH_2_TIER; // Conservative default

        try {
            // Try to detect via CpuAffinity helper if available
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");

            // Method 1: Check for Prime core count method
            try {
                java.lang.reflect.Method m = cls.getMethod("getPrimeCoreCount");
                Object result = m.invoke(null);
                if (result instanceof Integer && (Integer)result > 0) {
                    arch = ARCH_3_TIER;
                }
            } catch (NoSuchMethodException e) {
                // Method 2: Try to pin to Prime cores to see if it works
                if (tryCallCpuAffinity("pinCurrentThreadToPrimeCoresIf", true)) {
                    arch = ARCH_3_TIER;
                }
            }

            logI("Architecture detection: " + arch + "-tier");
        } catch (Throwable t) {
            // Fallback: Assume 2-tier for safety
            arch = ARCH_2_TIER;
            logI("Architecture detection failed, assuming 2-tier");
        }

        detectedArchitecture = arch;
        return arch;
    }

    // ---------------- Affinity helpers ----------------

    private static String modeName(int m) {
        switch (m) {
            case AFFINITY_BIG_ONLY: return "BIG_ONLY";
            case AFFINITY_SPREAD:   return "SPREAD";
            case AFFINITY_NONE:     return "NONE";
        }
        return "UNK";
    }

    private static String bucketName(int b, int arch) {
        if (arch == ARCH_3_TIER) {
            switch (b) {
                case 0: return "prime";
                case 1: return "big";
                case 2: return "little";
            }
        } else {
            // 2-tier architecture
            switch (b) {
                case 0: return "big";
                case 1: return "little";
            }
        }
        return "any";
    }

    /** Choose bucket based on architecture tier count. */
    private static int chooseBucketForWorker(int id, int arch) {
        if (AFFINITY_MODE == AFFINITY_SPREAD) {
            if (arch == ARCH_3_TIER) {
                return id % 3; // 3-tier: prime(0), big(1), little(2)
            } else {
                return id % 2; // 2-tier: big(0), little(1)
            }
        }
        if (AFFINITY_MODE == AFFINITY_BIG_ONLY) return 0;
        return 0; // NONE -> treat as "any" (we won't pin)
    }

    private static int priorityForBucket(int b, int arch) {
        if (arch == ARCH_3_TIER) {
            switch (b) {
                case 0: return PRIO_PRIME;
                case 1: return PRIO_BIG;
                case 2: return PRIO_LITTLE;
            }
        } else {
            // 2-tier architecture
            switch (b) {
                case 0: return PRIO_BIG;      // big cores
                case 1: return PRIO_LITTLE;   // little cores
            }
        }
        return PRIO_BIG;
    }

    /** Try to pin current thread according to bucket and architecture. */
    private static void pinWorkerToBucket(int bucket, int arch) {
        if (AFFINITY_MODE == AFFINITY_NONE) return;

        if (arch == ARCH_3_TIER) {
            // 3-tier: Prime/Big/Little
            switch (bucket) {
                case 0: // prime
                    if (tryCallCpuAffinity("pinCurrentThreadToPrimeCoresIf", true)) return;
                    // Fallback to big if prime not available
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return;
                    break;
                case 1: // big
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return;
                    break;
                case 2: // little
                    if (tryCallCpuAffinity("pinCurrentThreadToLittleCoresIf", true)) return;
                    break;
            }
        } else {
            // 2-tier: Big/Little only
            switch (bucket) {
                case 0: // big
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return;
                    break;
                case 1: // little
                    if (tryCallCpuAffinity("pinCurrentThreadToLittleCoresIf", true)) return;
                    break;
            }
        }

        // Fallbacks: try "all cores" (so scheduler can spread), else do nothing
        if (tryCallCpuAffinity("pinCurrentThreadToAllCoresIf", true)) return;

        // Last resort: do nothing (scheduler decides)
    }

    private static boolean tryCallCpuAffinity(String methodName, boolean arg) {
        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod(methodName, boolean.class);
            Object r = m.invoke(null, arg);
            return true; // if we got here, call succeeded
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---------------- Thermal sampling & policy ----------------

    private static final class ThermalSnapshot {
        final int thermalStatus;    // -1 = unknown; otherwise Thermal status [0..6] on API 29+
        final float batteryC;       // NaN if unknown
        ThermalSnapshot(int status, float batt) { this.thermalStatus = status; this.batteryC = batt; }
    }

    /** Read current thermal signals (best-effort, cheap, no permissions required). */
    private static ThermalSnapshot sampleThermals(android.content.Context ctx) {
        int status = -1;
        float battC = Float.NaN;

        // Prefer thermal service (API 29+) without compile-time refs or string constants
        if (ctx != null && android.os.Build.VERSION.SDK_INT >= 29) {
            Object svc = null;

            // 1) Try getSystemService(Class) via reflection using "android.os.ThermalManager"
            try {
                Class<?> tmClass = Class.forName("android.os.ThermalManager");
                java.lang.reflect.Method getByClass =
                        android.content.Context.class.getMethod("getSystemService", Class.class);
                svc = getByClass.invoke(ctx, tmClass);
            } catch (Throwable ignored) {}

            // 2) Fallback: getSystemService(String) via reflection with "thermal"
            if (svc == null) {
                try {
                    java.lang.reflect.Method getByString =
                            android.content.Context.class.getMethod("getSystemService", String.class);
                    svc = getByString.invoke(ctx, "thermal");
                } catch (Throwable ignored) {}
            }

            // 3) Read current thermal status via reflection (0..6), if service obtained
            if (svc != null) {
                try {
                    java.lang.reflect.Method m = svc.getClass().getMethod("getCurrentThermalStatus");
                    Object r = m.invoke(svc);
                    if (r instanceof Integer) status = (Integer) r;
                } catch (Throwable ignored) {}
            }
        }

        // Fallback: battery temperature (tenths of °C -> °C)
        try {
            android.content.IntentFilter f =
                    new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent i = (ctx != null) ? ctx.registerReceiver(null, f) : null;
            if (i != null) {
                int tTenths = i.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                if (tTenths != Integer.MIN_VALUE) battC = tTenths / 10.0f;
            }
        } catch (Throwable ignored) {}

        return new ThermalSnapshot(status, battC);
    }

    /** Map thermal snapshot to internal knobs (spin/sleep/burst). */
    private void applyThermalPolicy(ThermalSnapshot snap) {
        // Default: cool
        int newSpinMicros = BASE_SPIN_MICROS;
        int newSleepMs    = BASE_SLEEP_MILLIS;
        boolean newSkipBursts = false;

        // Statuses on API 29+: NONE=0, LIGHT=1, MODERATE=2, SEVERE=3, CRITICAL=4, EMERGENCY=5, SHUTDOWN=6
        if (snap.thermalStatus >= 0) {
            int s = snap.thermalStatus;
            if (s >= 4) {              // CRITICAL/EMERGENCY/SHUTDOWN
                newSpinMicros = BASE_SPIN_MICROS / 4;
                newSleepMs    = Math.max(BASE_SLEEP_MILLIS, 100);
                newSkipBursts = true;
            } else if (s >= 3) {       // SEVERE
                newSpinMicros = BASE_SPIN_MICROS / 2;
                newSleepMs    = Math.max(BASE_SLEEP_MILLIS, 40);
                newSkipBursts = true;
            } else if (s >= 2) {       // MODERATE
                newSpinMicros = (int) (BASE_SPIN_MICROS * 0.75);
                newSleepMs    = Math.max(BASE_SLEEP_MILLIS, 10);
                newSkipBursts = false;
            }
        } else {
            // Fallback to battery temp thresholds if thermal status was unavailable
            if (!java.lang.Float.isNaN(snap.batteryC)) {
                float t = snap.batteryC;
                if (t >= T_CRITICAL) {
                    newSpinMicros = BASE_SPIN_MICROS / 4;
                    newSleepMs    = Math.max(BASE_SLEEP_MILLIS, 100);
                    newSkipBursts = true;
                } else if (t >= T_HOT) {
                    newSpinMicros = BASE_SPIN_MICROS / 2;
                    newSleepMs    = Math.max(BASE_SLEEP_MILLIS, 40);
                    newSkipBursts = true;
                } else if (t >= T_WARM) {
                    newSpinMicros = (int) (BASE_SPIN_MICROS * 0.75);
                    newSleepMs    = Math.max(BASE_SLEEP_MILLIS, 10);
                    newSkipBursts = false;
                }
            }
        }

        // Publish (volatiles)
        spinMicros  = Math.max(100, newSpinMicros);
        sleepMillis = Math.min(250, Math.max(BASE_SLEEP_MILLIS, newSleepMs));
        skipBursts  = newSkipBursts;
    }

    // -------- PerfHint helpers --------

    private static boolean isPerfHintActive(Object perfHint) {
        if (perfHint == null) return false;
        // Methods we recognize
        final String[] candidates = new String[] { "isActive", "isActiveNow", "isEnabled" };
        for (String name : candidates) {
            try {
                java.lang.reflect.Method m;
                try { m = perfHint.getClass().getMethod(name); }
                catch (NoSuchMethodException nsme) {
                    m = perfHint.getClass().getDeclaredMethod(name);
                    m.setAccessible(true);
                }
                Object r = m.invoke(perfHint);
                if (r instanceof Boolean && (Boolean) r) return true;
            } catch (Throwable ignored) {}
        }
        final String[] fields = new String[] { "active", "enabled" };
        for (String fname : fields) {
            try {
                java.lang.reflect.Field f;
                try { f = perfHint.getClass().getField(fname); }
                catch (NoSuchFieldException nsfe) {
                    f = perfHint.getClass().getDeclaredField(fname);
                    f.setAccessible(true);
                }
                Object r = f.get(perfHint);
                if (r instanceof Boolean && (Boolean) r) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }
}