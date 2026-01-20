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
        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod("describeTiers");
            Object r = m.invoke(null);
            logI("CpuAffinity tiers: " + String.valueOf(r));
        } catch (Throwable ignored) {}

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

        boolean decided = false;

        // 1) Try CpuAffinity (non-fatal if it throws)
        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");

            // Method: getPrimeCoreCount() (if present)
            try {
                java.lang.reflect.Method m = cls.getMethod("getPrimeCoreCount");
                try {
                    Object result = m.invoke(null);
                    if (result instanceof Integer) {
                        arch = (((Integer) result) > 0) ? ARCH_3_TIER : ARCH_2_TIER;
                        decided = true;
                        logI("Architecture detection (CpuAffinity.getPrimeCoreCount): " + arch + "-tier");
                    }
                } catch (Throwable invokeErr) {
                    // Do NOT fail detection globally. We'll fall back below.
                    logI("Architecture detection: getPrimeCoreCount threw (" +
                            invokeErr.getClass().getSimpleName() + "), falling back");
                }
            } catch (NoSuchMethodException ignored) {
                // No method -> fall back below
            }
        } catch (Throwable t) {
            // Class not found or class init failure -> fall back below
            logI("Architecture detection: CpuAffinity unavailable (" +
                    t.getClass().getSimpleName() + "), falling back");
        }

        // 2) Fallback: infer tiers from cpufreq policy max frequencies (best-effort)
        if (!decided) {
            int inferred = detectArchitectureFromCpufreq();
            if (inferred == ARCH_3_TIER || inferred == ARCH_2_TIER) {
                arch = inferred;
                decided = true;
                logI("Architecture detection (cpufreq): " + arch + "-tier");
            }
        }

        if (!decided) {
            arch = ARCH_2_TIER;
            logI("Architecture detection failed, assuming 2-tier");
        }

        detectedArchitecture = arch;
        return arch;
    }
    private static int detectArchitectureFromCpufreq() {
        try {
            java.io.File dir = new java.io.File("/sys/devices/system/cpu/cpufreq");
            java.io.File[] files = dir.listFiles();
            if (files == null || files.length == 0) return -1;

            java.util.ArrayList<Integer> freqs = new java.util.ArrayList<>(8);

            for (java.io.File f : files) {
                if (f == null) continue;
                String name = f.getName();
                if (name == null || !name.startsWith("policy")) continue;

                // Prefer cpuinfo_max_freq, fallback to scaling_max_freq (kHz)
                Integer mhz = readFirstInt(new java.io.File(f, "cpuinfo_max_freq").getAbsolutePath());
                if (mhz == null) mhz = readFirstInt(new java.io.File(f, "scaling_max_freq").getAbsolutePath());
                if (mhz != null && mhz > 0) freqs.add(mhz);
            }

            if (freqs.size() < 2) return -1;

            java.util.Collections.sort(freqs);

            // Count "distinct" bins with tolerance (kHz). We only need to know if >= 3 bins exist.
            int bins = 0;
            int last = -1;

            for (int i = 0; i < freqs.size(); i++) {
                int v = freqs.get(i);
                if (last < 0) {
                    bins = 1;
                    last = v;
                    continue;
                }

                // Tolerance: 2% or 20 MHz (20_000 kHz) minimum
                int tol = Math.max(20_000, (int) (last * 0.02f));
                if (Math.abs(v - last) > tol) {
                    bins++;
                    last = v;
                    if (bins >= 3) return ARCH_3_TIER;
                }
            }

            // If we got exactly 2 bins, treat as 2-tier; 1 bin -> unknown
            return (bins >= 2) ? ARCH_2_TIER : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static Integer readFirstInt(String path) {
        java.io.BufferedReader br = null;
        try {
            br = new java.io.BufferedReader(new java.io.FileReader(path));
            String s = br.readLine();
            if (s == null) return null;
            s = s.trim();
            if (s.isEmpty()) return null;
            return Integer.parseInt(s);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (br != null) {
                try { br.close(); } catch (Throwable ignored) {}
            }
        }
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
        if (AFFINITY_MODE == AFFINITY_BIG_ONLY) {
            // For both 2-tier and 3-tier, "big only" maps to BIG bucket.
            // 3-tier bucket mapping is (prime=0, big=1, little=2), so BIG is 1.
            return (arch == ARCH_3_TIER) ? 1 : 0;
        }

        if (AFFINITY_MODE != AFFINITY_SPREAD) {
            // Default: behave like BIG_ONLY to be safe.
            return (arch == ARCH_3_TIER) ? 1 : 0;
        }

        // ---- SPREAD: count-aware caps to avoid oversubscription of PRIME/BIG ----
        if (arch == ARCH_3_TIER) {
            int primeCores  = getPrimeCoreCountBestEffort();
            int bigCores    = getBigCoreCountBestEffort();
            int littleCores = getLittleCoreCountBestEffort();

            // Conservative fallbacks if counts are unavailable
            if (primeCores <= 0) primeCores = 1;
            if (bigCores <= 0)   bigCores = 2;
            if (littleCores <= 0) littleCores = Math.max(1, WORKERS - (primeCores + bigCores));

            // Budget workers by real core counts, but always keep at least 1 LITTLE worker.
            int primeWorkers = Math.min(primeCores, Math.max(0, WORKERS - 2));
            int remaining = WORKERS - primeWorkers;

            int bigWorkers = Math.min(bigCores, Math.max(0, remaining - 1));
            int littleWorkers = WORKERS - primeWorkers - bigWorkers;
            if (littleWorkers <= 0) {
                // Force at least 1 little
                littleWorkers = 1;
                if (bigWorkers > 0) bigWorkers--;
                else if (primeWorkers > 0) primeWorkers--;
            }

            // Assign sequentially: [prime][big][little]
            if (id < primeWorkers) return 0;                 // prime
            if (id < primeWorkers + bigWorkers) return 1;    // big
            return 2;                                        // little
        } else {
            // 2-tier (e.g., MTK G99): cap BIG workers to actual big cores; rest LITTLE
            int bigCores = getBigCoreCountBestEffort();
            if (bigCores <= 0) bigCores = 2; // safe default

            int bigWorkers = Math.min(bigCores, Math.max(1, WORKERS - 1)); // keep at least 1 little
            if (id < bigWorkers) return 0; // big
            return 1;                      // little
        }
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

    private static int getBigCoreCountBestEffort() {
        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod("getBigCoreCount");
            Object r = m.invoke(null);
            if (r instanceof Integer) return Math.max(0, (Integer) r);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int getPrimeCoreCountBestEffort() {
        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod("getPrimeCoreCount");
            Object r = m.invoke(null);
            if (r instanceof Integer) return Math.max(0, (Integer) r);
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int getLittleCoreCountBestEffort() {
        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod("getLittleCoreCount");
            Object r = m.invoke(null);
            if (r instanceof Integer) return Math.max(0, (Integer) r);
        } catch (Throwable ignored) {}
        return 0;
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