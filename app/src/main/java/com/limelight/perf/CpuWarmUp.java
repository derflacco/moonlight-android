package com.limelight.perf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

/**
 * CpuWarmUp: multi-thread warm-up to gently nudge DVFS.
 *
 * This helper can be driven by app settings (SharedPreferences) so users can tune how aggressive
 * the "boost" is (workers, duty cycle, affinity, bursts, thermal gating, PerfHint override).
 *
 * Added:
 *  - Exposed thread count (KEY_WORKERS)
 *  - Exposed core-tier selection with combinations (KEY_CORE_SET):
 *      all, big, prime, little, big_prime, big_little, prime_little
 *
 * Usage:
 *   CpuWarmUp w = new CpuWarmUp();
 *   w.start(context, adpf, false); // if context != null, will auto-load prefs (or defaults)
 *   ...
 *   w.stop();
 */
public final class CpuWarmUp {
    // ---------------- Public configuration ----------------

    public static final class Config {
        // Boost profiles (keep these coarse; advanced knobs are optional)
        public static final int MODE_OFF        = 0;
        public static final int MODE_LITE       = 1;
        public static final int MODE_MEDIUM     = 2;
        public static final int MODE_AGGRESSIVE = 3;

        // Burst execution policy
        public static final int BURST_WORKERS_ALL          = 0;
        public static final int BURST_WORKERS_PRIMARY_ONLY = 1;

        // Preference keys (must match preferences.xml)
        public static final String KEY_MODE                = "pref_cpu_warmup_boost";
        public static final String KEY_OVERRIDE_PERF_HINT  = "pref_cpu_warmup_override_perf_hint";
        public static final String KEY_AFFINITY            = "pref_cpu_warmup_affinity";
        public static final String KEY_WORKERS             = "pref_cpu_warmup_workers";
        public static final String KEY_THERMALS            = "pref_cpu_warmup_thermals";

        // New: tier/core selection (combinations)
        public static final String KEY_CORE_SET            = "pref_cpu_warmup_core_set";

        // Advanced (optional)
        public static final String KEY_SPIN_US             = "pref_cpu_warmup_spin_us";
        public static final String KEY_SLEEP_MS            = "pref_cpu_warmup_sleep_ms";
        public static final String KEY_BURSTS              = "pref_cpu_warmup_bursts";
        public static final String KEY_BURST_PERIOD_MS     = "pref_cpu_warmup_burst_period_ms";
        public static final String KEY_BURST_SPIN_MS       = "pref_cpu_warmup_burst_spin_ms";
        public static final String KEY_BURST_WORKERS       = "pref_cpu_warmup_burst_workers";

        // Parsed config
        public int mode = MODE_MEDIUM;

        // If true, allow running even when ADPF/PerfHint is active.
        public boolean overridePerfHint = false;

        // 0 = auto (all available cores); otherwise clamped [1..min(cpu, MAX_AUTO_WORKERS)]
        public int workers = 0;

        // Uses CpuWarmUp affinity constants (AFFINITY_*)
        // NOTE: BIG_ONLY is treated as "performance tiers only" (Prime+Big on 3-tier, Big on 2-tier).
        public int affinityMode = AFFINITY_SPREAD;

        // New: selected tiers mask (Prime/Big/Little) with combinations
        // Default: all tiers enabled.
        public int coreSetMask = CORESET_ALL;

        public boolean thermalAware = true;

        // Base duty cycle knobs (0 / negative means "use preset for selected mode")
        public int baseSpinMicros  = 0;
        public int baseSleepMillis = -1;

        // Burst knobs (0 means "use preset for selected mode")
        public boolean burstsEnabled = false;
        public int burstPeriodMs = 0;
        public int burstSpinMs   = 0;
        public int burstWorkersMode = BURST_WORKERS_ALL;

        public static Config defaults() {
            return new Config();
        }

        public static Config fromPrefs(Context ctx) {
            Config c = new Config();
            if (ctx == null) return c;

            SharedPreferences p = getDefaultPrefs(ctx);
            if (p == null) return c;

            // mode
            String mode = getPrefString(p, KEY_MODE, "medium");
            c.mode = parseMode(mode);

            c.overridePerfHint = getPrefBoolean(p, KEY_OVERRIDE_PERF_HINT, false);

            // affinity
            String aff = getPrefString(p, KEY_AFFINITY, "spread");
            c.affinityMode = parseAffinity(aff);

            // workers (int or string)
            c.workers = getPrefInt(p, KEY_WORKERS, 0);

            // core set (string -> mask)
            String cs = getPrefString(p, KEY_CORE_SET, "all");
            c.coreSetMask = parseCoreSetMask(cs);

            // Safety: thermals must always be enabled (not user-configurable).
            c.thermalAware = true;

            // advanced knobs
            c.baseSpinMicros  = getPrefInt(p, KEY_SPIN_US, 0);
            c.baseSleepMillis = getPrefInt(p, KEY_SLEEP_MS, -1);

            c.burstsEnabled   = getPrefBoolean(p, KEY_BURSTS, false);
            c.burstPeriodMs   = getPrefInt(p, KEY_BURST_PERIOD_MS, 0);
            c.burstSpinMs     = getPrefInt(p, KEY_BURST_SPIN_MS, 0);

            String bw = getPrefString(p, KEY_BURST_WORKERS, "all");
            c.burstWorkersMode = parseBurstWorkersMode(bw);

            return c;
        }

        private static SharedPreferences getDefaultPrefs(Context ctx) {
            // Try AndroidX PreferenceManager first, then framework PreferenceManager, then the default prefs name.
            try {
                Class<?> pm = Class.forName("androidx.preference.PreferenceManager");
                java.lang.reflect.Method m = pm.getMethod("getDefaultSharedPreferences", Context.class);
                Object r = m.invoke(null, ctx);
                if (r instanceof SharedPreferences) return (SharedPreferences) r;
            } catch (Throwable ignored) {}

            try {
                Class<?> pm = Class.forName("android.preference.PreferenceManager");
                java.lang.reflect.Method m = pm.getMethod("getDefaultSharedPreferences", Context.class);
                Object r = m.invoke(null, ctx);
                if (r instanceof SharedPreferences) return (SharedPreferences) r;
            } catch (Throwable ignored) {}

            try {
                return ctx.getSharedPreferences(ctx.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            } catch (Throwable ignored) {}

            return null;
        }

        private static String getPrefString(SharedPreferences p, String key, String def) {
            try {
                String v = p.getString(key, def);
                return (v != null) ? v : def;
            } catch (ClassCastException cce) {
                // If stored as int/bool, coerce to string
                try {
                    Object o = p.getAll().get(key);
                    return (o != null) ? String.valueOf(o) : def;
                } catch (Throwable ignored) {
                    return def;
                }
            }
        }

        private static boolean getPrefBoolean(SharedPreferences p, String key, boolean def) {
            try {
                return p.getBoolean(key, def);
            } catch (ClassCastException cce) {
                String s = getPrefString(p, key, String.valueOf(def));
                return "1".equals(s) || "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s);
            }
        }

        private static int getPrefInt(SharedPreferences p, String key, int def) {
            try {
                return p.getInt(key, def);
            } catch (ClassCastException cce) {
                String s = getPrefString(p, key, String.valueOf(def));
                try { return Integer.parseInt(s.trim()); }
                catch (Throwable ignored) { return def; }
            }
        }

        private static int parseMode(String v) {
            if (v == null) return MODE_MEDIUM;
            v = v.trim().toLowerCase();
            if ("0".equals(v) || "off".equals(v) || "disabled".equals(v) || "false".equals(v)) return MODE_OFF;
            if ("1".equals(v) || "lite".equals(v) || "light".equals(v)) return MODE_LITE;
            if ("2".equals(v) || "medium".equals(v) || "normal".equals(v) || "default".equals(v)) return MODE_MEDIUM;
            if ("3".equals(v) || "aggressive".equals(v) || "high".equals(v) || "turbo".equals(v)) return MODE_AGGRESSIVE;
            return MODE_MEDIUM;
        }

        private static int parseAffinity(String v) {
            if (v == null) return AFFINITY_SPREAD;
            v = v.trim().toLowerCase();
            if ("0".equals(v) || "big".equals(v) || "big_only".equals(v) || "bigonly".equals(v)) return AFFINITY_BIG_ONLY;
            if ("1".equals(v) || "spread".equals(v) || "auto".equals(v) || "default".equals(v)) return AFFINITY_SPREAD;
            if ("2".equals(v) || "none".equals(v) || "off".equals(v) || "disabled".equals(v)) return AFFINITY_NONE;
            return AFFINITY_SPREAD;
        }

        private static int parseBurstWorkersMode(String v) {
            if (v == null) return BURST_WORKERS_ALL;
            v = v.trim().toLowerCase();
            if ("primary".equals(v) || "primary_only".equals(v) || "single".equals(v)) return BURST_WORKERS_PRIMARY_ONLY;
            return BURST_WORKERS_ALL;
        }

        private static int parseCoreSetMask(String v) {
            if (v == null) return CORESET_ALL;
            v = v.trim().toLowerCase();

            if ("all".equals(v)) return CORESET_ALL;
            if ("big".equals(v)) return CORESET_BIG;
            if ("prime".equals(v)) return CORESET_PRIME;
            if ("little".equals(v)) return CORESET_LITTLE;

            if ("big_prime".equals(v) || "prime_big".equals(v)) return (CORESET_BIG | CORESET_PRIME);
            if ("big_little".equals(v) || "little_big".equals(v)) return (CORESET_BIG | CORESET_LITTLE);
            if ("prime_little".equals(v) || "little_prime".equals(v)) return (CORESET_PRIME | CORESET_LITTLE);

            return CORESET_ALL;
        }

        private Config() {}
    }

    // ---------------- Runtime state ----------------

    private final java.util.List<Thread> workers = new java.util.ArrayList<>();
    private volatile boolean running = false;

    // Blackhole to prevent JIT from optimizing away FP work
    private static volatile double BH = 0.0;

    // Master switch (build-time kill switch if needed)
    private static final boolean ENABLE = true;

    // Default caps (used when prefs are not present / not used)
    // NOTE: 0=auto maps to availableProcessors() clamped by this hard cap.
    private static final int MAX_AUTO_WORKERS = 32;

    // Default base duty-cycle: ~2.5 ms spin + 2 ms sleep ≈ ~50%
    private static final int DEFAULT_BASE_SPIN_MICROS  = 2_500;
    private static final int DEFAULT_BASE_SLEEP_MILLIS = 2;

    // Default burst every 2 seconds: 10 ms
    private static final int DEFAULT_BURST_PERIOD_MS = 2_000;
    private static final int DEFAULT_BURST_SPIN_MS   = 10;

    // Thermal sampling period
    private static final int THERMAL_SAMPLE_MS = 2_000;

    // Battery temperature thresholds (°C) used as fallback when thermal status is unavailable
    private static final float T_WARM     = 42.0f;
    private static final float T_HOT      = 45.0f;
    private static final float T_CRITICAL = 48.0f;

    // ---- Architecture detection constants ----
    private static final int ARCH_3_TIER = 3;
    private static final int ARCH_2_TIER = 2;

    // Detected architecture (cached)
    private static volatile int detectedArchitecture = -1;

    // ---- Affinity policy ----
    private static final int AFFINITY_BIG_ONLY = 0;
    private static final int AFFINITY_SPREAD   = 1;
    private static final int AFFINITY_NONE     = 2;

    // ---- Tier selection mask (Prime/Big/Little) ----
    private static final int CORESET_PRIME  = 1 << 0;
    private static final int CORESET_BIG    = 1 << 1;
    private static final int CORESET_LITTLE = 1 << 2;
    private static final int CORESET_ALL    = (CORESET_PRIME | CORESET_BIG | CORESET_LITTLE);

    // ---- Thread priorities per bucket ----
    private static final int DEFAULT_PRIO_PRIME  = Process.THREAD_PRIORITY_DEFAULT;
    private static final int DEFAULT_PRIO_BIG    = Process.THREAD_PRIORITY_DEFAULT;
    private static final int DEFAULT_PRIO_LITTLE = Process.THREAD_PRIORITY_DEFAULT;

    // ---- Configured knobs (per-instance) ----
    private volatile int workersCount = defaultWorkersAuto();

    private volatile int affinityMode = AFFINITY_SPREAD;

    // New: tier selection mask (Prime/Big/Little)
    private volatile int coreSetMask = CORESET_ALL;

    private volatile int prioPrime  = DEFAULT_PRIO_PRIME;
    private volatile int prioBig    = DEFAULT_PRIO_BIG;
    private volatile int prioLittle = DEFAULT_PRIO_LITTLE;

    private volatile boolean thermalAware = true;

    // Base knobs (from config / preset)
    private volatile int baseSpinMicros  = DEFAULT_BASE_SPIN_MICROS;
    private volatile int baseSleepMillis = DEFAULT_BASE_SLEEP_MILLIS;

    private volatile boolean burstsEnabled = true;
    private volatile int burstPeriodMs = DEFAULT_BURST_PERIOD_MS;
    private volatile int burstSpinMs   = DEFAULT_BURST_SPIN_MS;
    private volatile int burstWorkersMode = Config.BURST_WORKERS_ALL;

    // Dynamic thermal-aware knobs (shared by workers)
    private volatile int  spinMicros  = DEFAULT_BASE_SPIN_MICROS;
    private volatile int  sleepMillis = DEFAULT_BASE_SLEEP_MILLIS;
    private volatile boolean skipBursts = false;
    private volatile long lastThermalSampleMs = 0L;

    // Optional context for thermal reading (can be null)
    private volatile Context appContext = null;

    // Only worker-0 samples thermals; guard avoids duplicate sampling
    private final java.util.concurrent.atomic.AtomicLong thermalGuard =
            new java.util.concurrent.atomic.AtomicLong(0L);

    // --- DEBUG (best-effort) ---
    private static void logI(String m) {
        try { com.limelight.LimeLog.info("CpuWarmUp: " + m); }
        catch (Throwable t) { android.util.Log.i("CpuWarmUp", m); }
    }
    private static void logE(String m, Throwable e) {
        try { com.limelight.LimeLog.info("CpuWarmUp ERR: " + m + " (" + e + ")"); }
        catch (Throwable t) { android.util.Log.e("CpuWarmUp", m, e); }
    }

    public synchronized void start(Object perfHint, boolean overridePerfHint) {
        start(null, perfHint, overridePerfHint, null);
    }

    public synchronized void start(Context context, Object perfHint, boolean overridePerfHint) {
        start(context, perfHint, overridePerfHint, null);
    }

    public synchronized void start(Context context, Object perfHint, boolean overridePerfHint, Config config) {
        if (!ENABLE) { logI("start(): ENABLE=false"); return; }
        if (running) {
            logI("start(): already running; workers=" + workers.size());
            return;
        }

        final Context ctx = (context != null) ? context.getApplicationContext() : null;
        this.appContext = ctx;

        Config cfg = config;
        if (cfg == null && ctx != null) {
            cfg = Config.fromPrefs(ctx);
        }
        if (cfg == null) cfg = Config.defaults();

        if (cfg.mode == Config.MODE_OFF) {
            logI("start(): mode=OFF");
            appContext = null;
            return;
        }

        applyConfigLocked(cfg);

        final boolean override = overridePerfHint || cfg.overridePerfHint;

        if (!override && isPerfHintActive(perfHint)) {
            logI("start(): gated by PerfHint active");
            appContext = null;
            return;
        }

        running = true;
        workers.clear();

        final int arch = getArchitecture();
        final int effMask = effectiveCoreSetMaskForArch(arch);

        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod("describeTiers");
            Object r = m.invoke(null);
            logI("CpuAffinity tiers: " + String.valueOf(r));
        } catch (Throwable ignored) {}

        logI("start(): mode=" + profileName(cfg.mode) +
                " affinity=" + modeName(affinityMode) +
                " coreSet=" + coreSetName(effMask, arch) +
                " workers=" + workersCount +
                " bursts=" + (burstsEnabled ? "on" : "off") +
                " thermals=" + (thermalAware && ctx != null ? "on" : "off") +
                " arch=" + arch + "-tier");

        for (int i = 0; i < workersCount; i++) {
            final int id = i;
            final int bucket = chooseBucketForWorker(id, arch);

            Thread t = new Thread(() -> {
                try { android.os.Process.setThreadPriority(priorityForBucket(bucket, arch)); }
                catch (Throwable ignored) {}

                pinWorkerToBucket(bucket, arch);

                final String original = Thread.currentThread().getName();
                try { Thread.currentThread().setName("CpuWarmUp-" + id + "-" + bucketName(bucket, arch)); }
                catch (Throwable ignored) {}

                long lastBurstMs = android.os.SystemClock.uptimeMillis();
                logI("worker-" + id + " started -> bucket=" + bucketName(bucket, arch));

                while (running && !Thread.currentThread().isInterrupted()) {
                    if (id == 0 && thermalAware && appContext != null) {
                        long nowMs = android.os.SystemClock.uptimeMillis();
                        long prev  = thermalGuard.get();
                        if (nowMs - prev >= THERMAL_SAMPLE_MS && thermalGuard.compareAndSet(prev, nowMs)) {
                            lastThermalSampleMs = nowMs;
                            applyThermalPolicy(sampleThermals(appContext));
                        }
                    }

                    final long startNs = System.nanoTime();
                    final long targetSpinNs = (long) spinMicros * 1_000L;
                    double sink = 0.0;
                    while ((System.nanoTime() - startNs) < targetSpinNs) {
                        if (!running || Thread.currentThread().isInterrupted()) break;
                        sink += Math.sin(sink + 1.0);
                    }
                    BH = sink;

                    try { Thread.sleep(sleepMillis); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }

                    final boolean burstAllowedWorker =
                            (burstWorkersMode == Config.BURST_WORKERS_ALL) || (id == 0);

                    if (burstsEnabled && burstAllowedWorker && !skipBursts) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if ((now - lastBurstMs) >= burstPeriodMs) {
                            final long until = System.nanoTime() + ((long) burstSpinMs * 1_000_000L);

                            double bs = BH;
                            while (System.nanoTime() < until) {
                                if (!running || Thread.currentThread().isInterrupted()) break;
                                bs = bs * 1.000001 + 1.0;
                                if (bs > 1e9) bs = 0.0;
                            }
                            BH = bs;

                            lastBurstMs = now;
                        }
                    }
                }

                logI("worker-" + id + " exit (bucket=" + bucketName(bucket, arch) + ")");
                try { Thread.currentThread().setName(original != null ? original : "CpuWarmUp-ended"); }
                catch (Throwable ignored) {}
            }, "CpuWarmUp-" + i);

            try { t.setDaemon(true); } catch (Throwable ignored) {}
            try { t.start(); } catch (Throwable e) { logE("worker-" + id + " start failed", e); }
            workers.add(t);
        }
    }

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

        spinMicros  = baseSpinMicros;
        sleepMillis = baseSleepMillis;
        skipBursts  = !burstsEnabled;
        lastThermalSampleMs = 0L;
        thermalGuard.set(0L);

        logI("stop(): done");
    }

    private void applyConfigLocked(Config cfg) {
        // Load preset (duty-cycle/bursts defaults).
        applyPresetLocked(cfg.mode);

        // Respect user-configured workers (0 = auto/all).
        workersCount = (cfg.workers > 0) ? clampWorkers(cfg.workers) : defaultWorkersAuto();

        // Respect affinity policy.
        affinityMode = clampAffinity(cfg.affinityMode);

        // New: respect tier selection mask (combinations).
        coreSetMask = clampCoreSetMask(cfg.coreSetMask);

        thermalAware = true;

        // Advanced knobs (override preset).
        if (cfg.baseSpinMicros > 0) baseSpinMicros = clampSpinUs(cfg.baseSpinMicros);
        if (cfg.baseSleepMillis >= 0) baseSleepMillis = clampSleepMs(cfg.baseSleepMillis);

        burstsEnabled = cfg.burstsEnabled;
        if (cfg.burstPeriodMs > 0) burstPeriodMs = clampBurstPeriodMs(cfg.burstPeriodMs);
        if (cfg.burstSpinMs > 0)   burstSpinMs   = clampBurstSpinMs(cfg.burstSpinMs);

        burstWorkersMode = (cfg.burstWorkersMode == Config.BURST_WORKERS_PRIMARY_ONLY)
                ? Config.BURST_WORKERS_PRIMARY_ONLY
                : Config.BURST_WORKERS_ALL;

        // Priorities unchanged
        prioPrime  = DEFAULT_PRIO_PRIME;
        prioBig    = DEFAULT_PRIO_BIG;
        prioLittle = DEFAULT_PRIO_LITTLE;

        // Apply base -> runtime
        spinMicros  = baseSpinMicros;
        sleepMillis = baseSleepMillis;
        skipBursts  = !burstsEnabled;
    }

    private void applyPresetLocked(int mode) {
        // Default: auto = all available cores (clamped) for every profile.
        final int autoWorkers = defaultWorkersAuto();
        workersCount = autoWorkers;

        switch (mode) {
            case Config.MODE_LITE:
                // Light continuous activity, still across selected tiers.
                baseSpinMicros  = 1_000;
                baseSleepMillis = 4;

                burstsEnabled = true;
                burstWorkersMode = Config.BURST_WORKERS_PRIMARY_ONLY;
                burstPeriodMs = 3_000;
                burstSpinMs   = 4;
                break;

            case Config.MODE_AGGRESSIVE:
                baseSpinMicros  = 4_500;
                baseSleepMillis = 1;

                burstsEnabled = true;
                burstWorkersMode = Config.BURST_WORKERS_ALL;
                burstPeriodMs = 1_250;
                burstSpinMs   = 18;
                break;

            case Config.MODE_MEDIUM:
            default:
                baseSpinMicros  = DEFAULT_BASE_SPIN_MICROS;
                baseSleepMillis = DEFAULT_BASE_SLEEP_MILLIS;

                burstsEnabled = true;
                burstWorkersMode = Config.BURST_WORKERS_ALL;
                burstPeriodMs = DEFAULT_BURST_PERIOD_MS;
                burstSpinMs   = DEFAULT_BURST_SPIN_MS;
                break;
        }

        workersCount    = clampWorkers(workersCount);
        baseSpinMicros  = clampSpinUs(baseSpinMicros);
        baseSleepMillis = clampSleepMs(baseSleepMillis);
        burstPeriodMs   = clampBurstPeriodMs(burstPeriodMs);
        burstSpinMs     = clampBurstSpinMs(burstSpinMs);
    }

    private static String profileName(int mode) {
        switch (mode) {
            case Config.MODE_OFF: return "OFF";
            case Config.MODE_LITE: return "LITE";
            case Config.MODE_MEDIUM: return "MEDIUM";
            case Config.MODE_AGGRESSIVE: return "AGGRESSIVE";
        }
        return "MEDIUM";
    }

    private static int defaultWorkersAuto() {
        int cpu = 1;
        try { cpu = Math.max(1, Runtime.getRuntime().availableProcessors()); }
        catch (Throwable ignored) {}
        int max = Math.max(1, Math.min(MAX_AUTO_WORKERS, cpu));
        return max;
    }

    private static int clampWorkers(int w) {
        int cpu = 1;
        try { cpu = Math.max(1, Runtime.getRuntime().availableProcessors()); }
        catch (Throwable ignored) {}
        int max = Math.max(1, Math.min(MAX_AUTO_WORKERS, cpu));
        return Math.max(1, Math.min(max, w));
    }

    private static int clampAffinity(int m) {
        if (m == AFFINITY_BIG_ONLY || m == AFFINITY_SPREAD || m == AFFINITY_NONE) return m;
        return AFFINITY_SPREAD;
    }

    private static int clampCoreSetMask(int m) {
        int mask = (m & CORESET_ALL);
        if (mask == 0) mask = CORESET_ALL;
        return mask;
    }

    private static int clampSpinUs(int us) {
        return Math.max(100, Math.min(10_000, us));
    }

    private static int clampSleepMs(int ms) {
        return Math.max(0, Math.min(250, ms));
    }

    private static int clampBurstPeriodMs(int ms) {
        return Math.max(250, Math.min(10_000, ms));
    }

    private static int clampBurstSpinMs(int ms) {
        return Math.max(1, Math.min(50, ms));
    }

    private static int normalizeCoreSetMaskForArch(int mask, int arch) {
        mask = clampCoreSetMask(mask);

        if (arch != ARCH_3_TIER) {
            // No prime tier: map prime -> big
            if ((mask & CORESET_PRIME) != 0) {
                mask = (mask | CORESET_BIG) & ~CORESET_PRIME;
            }
        }

        // Safety: avoid empty after normalization
        mask &= CORESET_ALL;
        if (mask == 0) mask = (arch == ARCH_3_TIER) ? CORESET_ALL : (CORESET_BIG | CORESET_LITTLE);
        return mask;
    }

    private int effectiveCoreSetMaskForArch(int arch) {
        int mask = normalizeCoreSetMaskForArch(coreSetMask, arch);

        // Back-compat: "BIG_ONLY" affinity means "performance tiers only"
        if (affinityMode == AFFINITY_BIG_ONLY) {
            mask = (arch == ARCH_3_TIER) ? (CORESET_PRIME | CORESET_BIG) : CORESET_BIG;
            mask = normalizeCoreSetMaskForArch(mask, arch);
        }

        // If prime requested but not actually available, remap prime -> big
        if (arch == ARCH_3_TIER && (mask & CORESET_PRIME) != 0 && getPrimeCoreCountBestEffort() <= 0) {
            mask = (mask & ~CORESET_PRIME) | CORESET_BIG;
        }

        return mask;
    }

    private static String coreSetName(int mask, int arch) {
        mask = normalizeCoreSetMaskForArch(mask, arch);

        if (arch == ARCH_3_TIER) {
            boolean p = (mask & CORESET_PRIME) != 0;
            boolean b = (mask & CORESET_BIG) != 0;
            boolean l = (mask & CORESET_LITTLE) != 0;

            if (p && b && l) return "ALL";
            if (p && b)      return "PRIME+BIG";
            if (p && l)      return "PRIME+LITTLE";
            if (b && l)      return "BIG+LITTLE";
            if (p)           return "PRIME";
            if (b)           return "BIG";
            if (l)           return "LITTLE";
            return "BIG";
        } else {
            boolean b = (mask & CORESET_BIG) != 0;
            boolean l = (mask & CORESET_LITTLE) != 0;
            if (b && l) return "ALL";
            if (b) return "BIG";
            if (l) return "LITTLE";
            return "BIG";
        }
    }

    private static int getArchitecture() {
        if (detectedArchitecture != -1) return detectedArchitecture;

        int arch = ARCH_2_TIER;
        boolean decided = false;

        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
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
                    logI("Architecture detection: getPrimeCoreCount threw (" +
                            invokeErr.getClass().getSimpleName() + "), falling back");
                }
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable t) {
            logI("Architecture detection: CpuAffinity unavailable (" +
                    t.getClass().getSimpleName() + "), falling back");
        }

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

                Integer kHz = readFirstInt(new java.io.File(f, "cpuinfo_max_freq").getAbsolutePath());
                if (kHz == null) kHz = readFirstInt(new java.io.File(f, "scaling_max_freq").getAbsolutePath());
                if (kHz != null && kHz > 0) freqs.add(kHz);
            }

            if (freqs.size() < 2) return -1;

            java.util.Collections.sort(freqs);

            int bins = 0;
            int last = -1;

            for (int i = 0; i < freqs.size(); i++) {
                int v = freqs.get(i);
                if (last < 0) {
                    bins = 1;
                    last = v;
                    continue;
                }

                int tol = Math.max(20_000, (int) (last * 0.02f));
                if (Math.abs(v - last) > tol) {
                    bins++;
                    last = v;
                    if (bins >= 3) return ARCH_3_TIER;
                }
            }

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
            switch (b) {
                case 0: return "big";
                case 1: return "little";
            }
        }
        return "any";
    }

    private int chooseBucketForWorker(int id, int arch) {
        final int totalWorkers = workersCount;
        if (totalWorkers <= 0) return (arch == ARCH_3_TIER) ? 1 : 0;

        // Effective tier set for this run.
        final int mask = effectiveCoreSetMaskForArch(arch);

        if (arch == ARCH_3_TIER) {
            final boolean allowPrime  = (mask & CORESET_PRIME) != 0;
            final boolean allowBig    = (mask & CORESET_BIG) != 0;
            final boolean allowLittle = (mask & CORESET_LITTLE) != 0;

            final int enabled = (allowPrime ? 1 : 0) + (allowBig ? 1 : 0) + (allowLittle ? 1 : 0);
            if (enabled <= 1) {
                if (allowPrime) return 0;
                if (allowBig) return 1;
                return 2;
            }

            // If fewer workers than enabled tiers, prefer performance tiers first.
            if (totalWorkers < enabled) {
                if (allowPrime) return 0;
                if (allowBig) return 1;
                return 2;
            }

            int primeCores  = allowPrime  ? getPrimeCoreCountBestEffort()  : 0;
            int bigCores    = allowBig    ? getBigCoreCountBestEffort()    : 0;
            int littleCores = allowLittle ? getLittleCoreCountBestEffort() : 0;

            if (allowPrime  && primeCores <= 0)  primeCores = 1;
            if (allowBig    && bigCores <= 0)    bigCores = 2;
            if (allowLittle && littleCores <= 0) littleCores = 2;

            // Seed: 1 worker per enabled tier, then distribute remaining by score cores/(assigned+1).
            int primeW  = allowPrime  ? 1 : 0;
            int bigW    = allowBig    ? 1 : 0;
            int littleW = allowLittle ? 1 : 0;

            int remaining = totalWorkers - (primeW + bigW + littleW);
            while (remaining > 0) {
                double pScore = allowPrime  ? ((double) primeCores)  / (primeW + 1.0)  : -1.0;
                double bScore = allowBig    ? ((double) bigCores)    / (bigW + 1.0)    : -1.0;
                double lScore = allowLittle ? ((double) littleCores) / (littleW + 1.0) : -1.0;

                // Prefer higher score; tie-break: prime > big > little
                if (pScore >= bScore && pScore >= lScore) primeW++;
                else if (bScore >= lScore) bigW++;
                else littleW++;

                remaining--;
            }

            // Map id to bucket in stable order: prime, big, little.
            if (id < primeW) return 0;
            id -= primeW;
            if (id < bigW) return 1;
            return 2;
        } else {
            final boolean allowBig    = (mask & CORESET_BIG) != 0;
            final boolean allowLittle = (mask & CORESET_LITTLE) != 0;

            if (allowBig && !allowLittle) return 0;
            if (!allowBig && allowLittle) return 1;

            // both enabled
            int bigCores = getBigCoreCountBestEffort();
            int littleCores = getLittleCoreCountBestEffort();

            if (bigCores <= 0) bigCores = 2;
            if (littleCores <= 0) littleCores = 2;

            if (totalWorkers < 2) return 0; // prefer big

            int bigW = 1;
            int littleW = 1;

            int remaining = totalWorkers - 2;
            while (remaining > 0) {
                double bScore = ((double) bigCores) / (bigW + 1.0);
                double lScore = ((double) littleCores) / (littleW + 1.0);

                if (bScore >= lScore) bigW++;
                else littleW++;

                remaining--;
            }

            if (id < bigW) return 0;
            return 1;
        }
    }

    private int priorityForBucket(int b, int arch) {
        if (arch == ARCH_3_TIER) {
            switch (b) {
                case 0: return prioPrime;
                case 1: return prioBig;
                case 2: return prioLittle;
            }
        } else {
            switch (b) {
                case 0: return prioBig;
                case 1: return prioLittle;
            }
        }
        return prioBig;
    }

    private void pinWorkerToBucket(int bucket, int arch) {
        if (affinityMode == AFFINITY_NONE) return;

        if (arch == ARCH_3_TIER) {
            switch (bucket) {
                case 0:
                    if (tryCallCpuAffinity("pinCurrentThreadToPrimeCoresIf", true)) return;
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return;
                    break;
                case 1:
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return;
                    break;
                case 2:
                    if (tryCallCpuAffinity("pinCurrentThreadToLittleCoresIf", true)) return;
                    break;
            }
        } else {
            switch (bucket) {
                case 0:
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return;
                    break;
                case 1:
                    if (tryCallCpuAffinity("pinCurrentThreadToLittleCoresIf", true)) return;
                    break;
            }
        }

        tryCallCpuAffinity("pinCurrentThreadToAllCoresIf", true);
    }

    private static boolean tryCallCpuAffinity(String methodName, boolean arg) {
        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod(methodName, boolean.class);
            m.invoke(null, arg);
            return true;
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

    private static final class ThermalSnapshot {
        final int thermalStatus;
        final float batteryC;
        ThermalSnapshot(int status, float batt) { this.thermalStatus = status; this.batteryC = batt; }
    }

    private static ThermalSnapshot sampleThermals(Context ctx) {
        int status = -1;
        float battC = Float.NaN;

        if (ctx != null && android.os.Build.VERSION.SDK_INT >= 29) {
            Object svc = null;

            try {
                Class<?> tmClass = Class.forName("android.os.ThermalManager");
                java.lang.reflect.Method getByClass =
                        android.content.Context.class.getMethod("getSystemService", Class.class);
                svc = getByClass.invoke(ctx, tmClass);
            } catch (Throwable ignored) {}

            if (svc == null) {
                try {
                    java.lang.reflect.Method getByString =
                            android.content.Context.class.getMethod("getSystemService", String.class);
                    svc = getByString.invoke(ctx, "thermal");
                } catch (Throwable ignored) {}
            }

            if (svc != null) {
                try {
                    java.lang.reflect.Method m = svc.getClass().getMethod("getCurrentThermalStatus");
                    Object r = m.invoke(svc);
                    if (r instanceof Integer) status = (Integer) r;
                } catch (Throwable ignored) {}
            }
        }

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

    private void applyThermalPolicy(ThermalSnapshot snap) {
        int newSpinMicros = baseSpinMicros;
        int newSleepMs    = baseSleepMillis;
        boolean newSkipBursts = !burstsEnabled;

        if (snap.thermalStatus >= 0) {
            int s = snap.thermalStatus;
            if (s >= 4) {
                newSpinMicros = baseSpinMicros / 4;
                newSleepMs    = Math.max(baseSleepMillis, 100);
                newSkipBursts = true;
            } else if (s >= 3) {
                newSpinMicros = baseSpinMicros / 2;
                newSleepMs    = Math.max(baseSleepMillis, 40);
                newSkipBursts = true;
            } else if (s >= 2) {
                newSpinMicros = (int) (baseSpinMicros * 0.75f);
                newSleepMs    = Math.max(baseSleepMillis, 10);
                newSkipBursts = !burstsEnabled;
            }
        } else {
            if (!java.lang.Float.isNaN(snap.batteryC)) {
                float t = snap.batteryC;
                if (t >= T_CRITICAL) {
                    newSpinMicros = baseSpinMicros / 4;
                    newSleepMs    = Math.max(baseSleepMillis, 100);
                    newSkipBursts = true;
                } else if (t >= T_HOT) {
                    newSpinMicros = baseSpinMicros / 2;
                    newSleepMs    = Math.max(baseSleepMillis, 40);
                    newSkipBursts = true;
                } else if (t >= T_WARM) {
                    newSpinMicros = (int) (baseSpinMicros * 0.75f);
                    newSleepMs    = Math.max(baseSleepMillis, 10);
                    newSkipBursts = !burstsEnabled;
                }
            }
        }

        spinMicros  = clampSpinUs(newSpinMicros);
        sleepMillis = clampSleepMs(newSleepMs);
        skipBursts  = newSkipBursts;
    }

    private static boolean isPerfHintActive(Object perfHint) {
        if (perfHint == null) return false;

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
