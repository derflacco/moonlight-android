package com.limelight.perf;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.Process;

import java.util.Locale;

/**
 * CpuWarmUp: multi-thread warm-up to gently nudge DVFS.
 *
 * The selected profile owns the duty cycle and burst policy. App settings only select the profile,
 * worker count, and CPU tiers; thermal protection is always enabled when a Context is available.
 *
 * Usage:
 *   CpuWarmUp w = new CpuWarmUp();
 *   w.start(context, config);
 *   ...
 *   w.stop();
 */
public final class CpuWarmUp {
    // ---------------- Public configuration ----------------

    public static final class Config {
        // Boost profiles
        public static final int MODE_OFF        = 0;
        public static final int MODE_LITE       = 1;
        public static final int MODE_MEDIUM     = 2;
        public static final int MODE_AGGRESSIVE = 3;

        // Preference keys (must match preferences.xml)
        public static final String KEY_MODE     = "pref_cpu_warmup_boost";
        public static final String KEY_WORKERS  = "pref_cpu_warmup_workers";
        public static final String KEY_CORE_SET = "pref_cpu_warmup_core_set";

        // Parsed config
        public int mode = MODE_OFF;

        // 0 = auto (all available cores); otherwise clamped [1..min(cpu, MAX_AUTO_WORKERS)]
        public int workers = 0;

        // Selected tiers mask (Prime/Big/Little), including combinations. Defaults to all tiers.
        public int coreSetMask = CORESET_ALL;

        public static Config defaults() {
            return new Config();
        }

        public static Config fromPrefs(Context ctx) {
            if (ctx == null) return new Config();

            return fromPrefs(getDefaultPrefs(ctx));
        }

        /** Builds a config from the exact preference view supplied by the caller. */
        public static Config fromPrefs(SharedPreferences p) {
            Config c = new Config();

            if (p == null) return c;

            String mode = getPrefString(p, KEY_MODE, "off");
            c.mode = parseMode(mode);

            // workers (int or string)
            c.workers = getPrefInt(p, KEY_WORKERS, 0);

            // core set (string -> mask)
            String cs = getPrefString(p, KEY_CORE_SET, "all");
            c.coreSetMask = parseCoreSetMask(cs);

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
            if (v == null) return MODE_OFF;
            v = v.trim().toLowerCase(Locale.ROOT);
            if ("0".equals(v) || "off".equals(v) || "disabled".equals(v) || "false".equals(v)) return MODE_OFF;
            if ("1".equals(v) || "lite".equals(v) || "light".equals(v)) return MODE_LITE;
            if ("2".equals(v) || "medium".equals(v) || "normal".equals(v) || "default".equals(v)) return MODE_MEDIUM;
            if ("3".equals(v) || "aggressive".equals(v) || "high".equals(v) || "turbo".equals(v)) return MODE_AGGRESSIVE;
            return MODE_OFF;
        }

        private static int parseCoreSetMask(String v) {
            if (v == null) return CORESET_ALL;
            v = v.trim().toLowerCase(Locale.ROOT);

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

    // Blackhole to keep the synthetic integer work observable to the JIT.
    private static volatile long BH = 0L;

    // Master switch (build-time kill switch if needed)
    private static final boolean ENABLE = true;

    // Default caps (used when prefs are not present / not used)
    // NOTE: 0=auto maps to availableProcessors() clamped by this hard cap.
    private static final int MAX_AUTO_WORKERS = 32;

    // Default base duty-cycle: ~1.5 ms spin + 4 ms sleep ≈ ~27%.
    private static final int DEFAULT_BASE_SPIN_MICROS  = 1_500;
    private static final int DEFAULT_BASE_SLEEP_MILLIS = 4;

    // Default burst every 2 seconds: 10 ms
    private static final int DEFAULT_BURST_PERIOD_MS = 2_000;
    private static final int DEFAULT_BURST_SPIN_MS   = 10;

    // Thermal sampling period
    private static final int THERMAL_SAMPLE_MS = 2_000;
    private static final long THERMAL_RELEASE_MS = 8_000L;

    // Battery temperature thresholds (°C), also used as a secondary thermal signal.
    private static final float T_WARM     = 42.0f;
    private static final float T_HOT      = 45.0f;
    private static final float T_CRITICAL = 48.0f;
    private static final float T_HYSTERESIS = 2.0f;

    // ---- Architecture detection constants ----
    private static final int ARCH_3_TIER = 3;
    private static final int ARCH_2_TIER = 2;

    // Detected architecture (cached)
    private static volatile int detectedArchitecture = -1;

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

    // Tier selection mask (Prime/Big/Little)
    private volatile int coreSetMask = CORESET_ALL;

    private volatile int prioPrime  = DEFAULT_PRIO_PRIME;
    private volatile int prioBig    = DEFAULT_PRIO_BIG;
    private volatile int prioLittle = DEFAULT_PRIO_LITTLE;

    // Base knobs from the selected preset
    private volatile int baseSpinMicros  = DEFAULT_BASE_SPIN_MICROS;
    private volatile int baseSleepMillis = DEFAULT_BASE_SLEEP_MILLIS;

    private volatile boolean burstsEnabled = true;
    private volatile int burstPeriodMs = DEFAULT_BURST_PERIOD_MS;
    private volatile int burstSpinMs   = DEFAULT_BURST_SPIN_MS;
    private volatile boolean burstPrimaryOnly = false;

    // Dynamic thermal-aware knobs (shared by workers)
    private volatile int  spinMicros  = DEFAULT_BASE_SPIN_MICROS;
    private volatile int  sleepMillis = DEFAULT_BASE_SLEEP_MILLIS;
    private volatile boolean skipBursts = false;
    private volatile boolean thermalPaused = false;
    private volatile int lastThermalLevel = -1;
    private final ThermalRecovery platformRecovery = new ThermalRecovery();
    private final ThermalRecovery batteryRecovery = new ThermalRecovery();

    // Optional context for thermal reading (can be null)
    private volatile Context appContext = null;

    // Workers share the sampling deadline; only one sample may be in flight.
    private final java.util.concurrent.atomic.AtomicLong thermalGuard =
            new java.util.concurrent.atomic.AtomicLong(0L);
    private final java.util.concurrent.atomic.AtomicBoolean thermalSampling =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // --- DEBUG (best-effort) ---
    private static void logI(String m) {
        try { com.limelight.LimeLog.info("CpuWarmUp: " + m); }
        catch (Throwable t) { android.util.Log.i("CpuWarmUp", m); }
    }
    private static void logE(String m, Throwable e) {
        try { com.limelight.LimeLog.info("CpuWarmUp ERR: " + m + " (" + e + ")"); }
        catch (Throwable t) { android.util.Log.e("CpuWarmUp", m, e); }
    }

    public synchronized boolean start(Context context, Config config) {
        if (!ENABLE) { logI("start(): ENABLE=false"); return false; }
        if (running) {
            logI("start(): already running; workers=" + workers.size());
            return !workers.isEmpty();
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
            return false;
        }

        applyConfigLocked(cfg);

        // Apply the thermal policy before starting any synthetic load. This avoids a short
        // full-load window when a stream starts on an already hot device.
        if (ctx != null) {
            thermalGuard.set(android.os.SystemClock.uptimeMillis());
            applyThermalPolicy(sampleThermals(ctx));
        }

        running = true;
        workers.clear();

        final int arch = getArchitecture();
        final int effMask = effectiveCoreSetMaskForArch(arch);
        final int selectedCores = selectedCoreCountBestEffort(effMask, arch);
        if (selectedCores > 0) {
            workersCount = Math.min(workersCount, selectedCores);
        }

        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod("describeTiers");
            Object r = m.invoke(null);
            logI("CpuAffinity tiers: " + String.valueOf(r));
        } catch (Throwable ignored) {}

        logI("start(): mode=" + profileName(cfg.mode) +
                " coreSet=" + coreSetName(effMask, arch) +
                " workers=" + workersCount +
                " duty=" + baseSpinMicros + "us/" + baseSleepMillis + "ms" +
                " bursts=" + (burstsEnabled ? "on" : "off") +
                " thermals=" + (ctx != null ? "on" : "off") +
                " arch=" + arch + "-tier");

        for (int i = 0; i < workersCount; i++) {
            final int id = i;
            final int bucket = chooseBucketForWorker(id, arch);

            Thread t = new Thread(() -> {
                try { android.os.Process.setThreadPriority(priorityForBucket(bucket, arch)); }
                catch (Throwable ignored) {}

                final String affinityResult = pinWorkerToBucket(bucket, arch);

                final String original = Thread.currentThread().getName();
                try { Thread.currentThread().setName("CpuWarmUp-" + id + "-" + bucketName(bucket, arch)); }
                catch (Throwable ignored) {}

                long lastBurstMs = android.os.SystemClock.uptimeMillis();
                logI("worker-" + id + " started -> bucket=" + bucketName(bucket, arch) +
                        " affinity=" + affinityResult);

                while (running && !Thread.currentThread().isInterrupted()) {
                    // Any surviving worker may perform the sample. The atomic guard still limits
                    // this to one read per interval and preserves protection if worker-0 fails.
                    final Context thermalContext = appContext;
                    if (thermalContext != null) {
                        long nowMs = android.os.SystemClock.uptimeMillis();
                        long prev  = thermalGuard.get();
                        if (nowMs - prev >= THERMAL_SAMPLE_MS && thermalSampling.compareAndSet(false, true)) {
                            try {
                                // Recheck after acquiring ownership: another worker may have
                                // completed a sample since we read prev.
                                if (nowMs - thermalGuard.get() >= THERMAL_SAMPLE_MS) {
                                    thermalGuard.set(nowMs);
                                    applyThermalPolicy(sampleThermals(thermalContext));
                                }
                            } finally {
                                thermalSampling.set(false);
                            }
                        }
                    }

                    if (thermalPaused) {
                        try { Thread.sleep(100); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        continue;
                    }

                    final long startNs = System.nanoTime();
                    final long targetSpinNs = (long) spinMicros * 1_000L;
                    long sink = (((long) id) + 1L) * 0x9E3779B97F4A7C15L;
                    while ((System.nanoTime() - startNs) < targetSpinNs) {
                        if (!running || Thread.currentThread().isInterrupted()) break;
                        sink ^= sink << 13;
                        sink ^= sink >>> 7;
                        sink ^= sink << 17;
                    }
                    BH = sink;

                    if (sleepMillis > 0) {
                        try { Thread.sleep(sleepMillis); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }

                    final boolean burstAllowedWorker = !burstPrimaryOnly || id == 0;

                    if (burstsEnabled && burstAllowedWorker && !skipBursts) {
                        long now = android.os.SystemClock.uptimeMillis();
                        if ((now - lastBurstMs) >= burstPeriodMs) {
                            final long until = System.nanoTime() + ((long) burstSpinMs * 1_000_000L);

                            long bs = BH ^ (((long) id) << 32);
                            while (System.nanoTime() < until) {
                                if (!running || Thread.currentThread().isInterrupted()) break;
                                bs ^= bs << 13;
                                bs ^= bs >>> 7;
                                bs ^= bs << 17;
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
            try {
                t.start();
                workers.add(t);
            } catch (Throwable e) {
                logE("worker-" + id + " start failed", e);
            }
        }

        if (workers.isEmpty()) {
            running = false;
            appContext = null;
            logI("start(): no workers started");
            return false;
        }

        return true;
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
        final long joinDeadlineMs = android.os.SystemClock.uptimeMillis() + 500L;
        for (Thread t : workers) {
            if (t != null) {
                long remainingMs = joinDeadlineMs - android.os.SystemClock.uptimeMillis();
                if (remainingMs <= 0) break;
                try { t.join(remainingMs); } catch (Throwable e) { logE("join failed", e); }
            }
        }

        workers.clear();
        appContext = null;

        spinMicros  = baseSpinMicros;
        sleepMillis = baseSleepMillis;
        skipBursts  = !burstsEnabled;
        thermalPaused = false;
        lastThermalLevel = -1;
        platformRecovery.reset();
        batteryRecovery.reset();
        thermalGuard.set(0L);

        logI("stop(): done");
    }

    private void applyConfigLocked(Config cfg) {
        platformRecovery.reset();
        batteryRecovery.reset();
        // Load preset (duty-cycle/bursts defaults).
        applyPresetLocked(cfg.mode);

        // Respect user-configured workers (0 = auto/all).
        workersCount = (cfg.workers > 0) ? clampWorkers(cfg.workers) : defaultWorkersAuto();

        // Respect tier selection mask (combinations).
        coreSetMask = clampCoreSetMask(cfg.coreSetMask);

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
                baseSpinMicros  = 750;
                baseSleepMillis = 6;

                burstsEnabled = true;
                burstPrimaryOnly = true;
                burstPeriodMs = 3_000;
                burstSpinMs   = 4;
                break;

            case Config.MODE_AGGRESSIVE:
                // Continuous synthetic load. This asks the governor for peak performance but
                // cannot override kernel/vendor frequency or thermal caps.
                baseSpinMicros  = 10_000;
                baseSleepMillis = 0;

                burstsEnabled = false;
                burstPrimaryOnly = false;
                burstPeriodMs = 1_250;
                burstSpinMs   = 18;
                break;

            case Config.MODE_MEDIUM:
            default:
                baseSpinMicros  = DEFAULT_BASE_SPIN_MICROS;
                baseSleepMillis = DEFAULT_BASE_SLEEP_MILLIS;

                burstsEnabled = true;
                burstPrimaryOnly = false;
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

    private static int selectedCoreCountBestEffort(int mask, int arch) {
        int selected = 0;

        if (arch == ARCH_3_TIER && (mask & CORESET_PRIME) != 0) {
            selected += getPrimeCoreCountBestEffort();
        }
        if ((mask & CORESET_BIG) != 0) {
            selected += getBigCoreCountBestEffort();
        }
        if ((mask & CORESET_LITTLE) != 0) {
            selected += getLittleCoreCountBestEffort();
        }

        return selected;
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
                if (allowPrime && id-- == 0) return 0;
                if (allowBig && id-- == 0) return 1;
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

    private String pinWorkerToBucket(int bucket, int arch) {
        if (arch == ARCH_3_TIER) {
            switch (bucket) {
                case 0:
                    if (tryCallCpuAffinity("pinCurrentThreadToPrimeCoresIf", true)) return "prime-ok";
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return "prime-to-big";
                    break;
                case 1:
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return "big-ok";
                    break;
                case 2:
                    if (tryCallCpuAffinity("pinCurrentThreadToLittleCoresIf", true)) return "little-ok";
                    break;
            }
        } else {
            switch (bucket) {
                case 0:
                    if (tryCallCpuAffinity("pinCurrentThreadToBigCoresIf", true)) return "big-ok";
                    break;
                case 1:
                    if (tryCallCpuAffinity("pinCurrentThreadToLittleCoresIf", true)) return "little-ok";
                    break;
            }
        }

        return tryCallCpuAffinity("pinCurrentThreadToAllCoresIf", true) ? "fallback-all" : "failed";
    }

    private static boolean tryCallCpuAffinity(String methodName, boolean arg) {
        try {
            Class<?> cls = Class.forName("com.limelight.utils.CpuAffinity");
            java.lang.reflect.Method m = cls.getMethod(methodName, boolean.class);
            Object result = m.invoke(null, arg);
            return result instanceof Boolean && (Boolean) result;
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
            try {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                if (pm != null) status = pm.getCurrentThermalStatus();
            } catch (Throwable ignored) {}
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

    private static final class ThermalRecovery {
        int level;
        long lowerSinceMs = -1L;

        int update(int observed, long nowMs) {
            if (observed < 0) {
                // A failed read is not evidence of cooling.
                lowerSinceMs = -1L;
            } else if (observed >= level) {
                level = observed;
                lowerSinceMs = -1L;
            } else {
                if (lowerSinceMs < 0L) lowerSinceMs = nowMs;
                if (nowMs - lowerSinceMs >= THERMAL_RELEASE_MS) {
                    level = observed;
                    lowerSinceMs = -1L;
                }
            }
            return level;
        }

        void reset() {
            level = 0;
            lowerSinceMs = -1L;
        }
    }

    private void applyThermalPolicy(ThermalSnapshot snap) {
        applyThermalPolicy(snap, android.os.SystemClock.uptimeMillis());
    }

    private void applyThermalPolicy(ThermalSnapshot snap, long nowMs) {
        int newSpinMicros = baseSpinMicros;
        int newSleepMs    = baseSleepMillis;
        boolean newSkipBursts = !burstsEnabled;
        boolean newThermalPaused = false;
        int thermalLevel = 0;

        int observedPlatform = snap.thermalStatus < 0 ? -1 :
                Math.min(snap.thermalStatus, PowerManager.THERMAL_STATUS_SEVERE);
        int platformLevel = platformRecovery.update(observedPlatform, nowMs);
        if (platformLevel >= PowerManager.THERMAL_STATUS_SEVERE) {
            // Synthetic load is counterproductive once Android reports severe throttling.
            thermalLevel = 3;
            newThermalPaused = true;
            newSkipBursts = true;
        } else if (platformLevel >= PowerManager.THERMAL_STATUS_MODERATE) {
            thermalLevel = 2;
            newSpinMicros = baseSpinMicros / 2;
            newSleepMs    = Math.max(baseSleepMillis, 10);
            newSkipBursts = true;
        } else if (platformLevel >= PowerManager.THERMAL_STATUS_LIGHT) {
            thermalLevel = 1;
            newSpinMicros = (int) (baseSpinMicros * 0.75f);
            newSleepMs    = Math.max(baseSleepMillis, 5);
            newSkipBursts = true;
        }

        // Some devices report THERMAL_STATUS_NONE permanently, so retain battery temperature
        // as a conservative secondary signal even when the platform API is available.
        int observedBattery = -1;
        if (!Float.isNaN(snap.batteryC)) {
            float t = snap.batteryC;
            if (t >= T_CRITICAL || (batteryRecovery.level >= 3 && t >= T_CRITICAL - T_HYSTERESIS)) {
                observedBattery = 3;
            } else if (t >= T_HOT || (batteryRecovery.level >= 2 && t >= T_HOT - T_HYSTERESIS)) {
                observedBattery = 2;
            } else if (t >= T_WARM || (batteryRecovery.level >= 1 && t >= T_WARM - T_HYSTERESIS)) {
                observedBattery = 1;
            } else {
                observedBattery = 0;
            }
        }
        int batteryLevel = batteryRecovery.update(observedBattery, nowMs);
        if (batteryLevel >= 3) {
            thermalLevel = Math.max(thermalLevel, 3);
            newThermalPaused = true;
            newSkipBursts = true;
        } else if (batteryLevel >= 2) {
            thermalLevel = Math.max(thermalLevel, 2);
            newSpinMicros = Math.min(newSpinMicros, baseSpinMicros / 2);
            newSleepMs    = Math.max(newSleepMs, 40);
            newSkipBursts = true;
        } else if (batteryLevel >= 1) {
            thermalLevel = Math.max(thermalLevel, 1);
            newSpinMicros = Math.min(newSpinMicros, (int) (baseSpinMicros * 0.75f));
            newSleepMs    = Math.max(newSleepMs, 10);
            newSkipBursts = true;
        }

        spinMicros  = clampSpinUs(newSpinMicros);
        sleepMillis = clampSleepMs(newSleepMs);
        skipBursts  = newSkipBursts;
        thermalPaused = newThermalPaused;

        if (lastThermalLevel != thermalLevel) {
            lastThermalLevel = thermalLevel;
            String battery = Float.isNaN(snap.batteryC)
                    ? "n/a"
                    : String.format(Locale.ROOT, "%.1fC", snap.batteryC);
            logI("thermal: level=" + thermalLevelName(thermalLevel) +
                    " status=" + thermalStatusName(snap.thermalStatus) +
                    " battery=" + battery +
                    " duty=" + spinMicros + "us/" + sleepMillis + "ms" +
                    " bursts=" + (skipBursts ? "off" : "on") +
                    " paused=" + thermalPaused);
        }
    }

    private static String thermalLevelName(int level) {
        switch (level) {
            case 1: return "LIGHT";
            case 2: return "MODERATE";
            case 3: return "PAUSED";
            default: return "NORMAL";
        }
    }

    private static String thermalStatusName(int status) {
        switch (status) {
            case PowerManager.THERMAL_STATUS_NONE: return "NONE";
            case PowerManager.THERMAL_STATUS_LIGHT: return "LIGHT";
            case PowerManager.THERMAL_STATUS_MODERATE: return "MODERATE";
            case PowerManager.THERMAL_STATUS_SEVERE: return "SEVERE";
            case PowerManager.THERMAL_STATUS_CRITICAL: return "CRITICAL";
            case PowerManager.THERMAL_STATUS_EMERGENCY: return "EMERGENCY";
            case PowerManager.THERMAL_STATUS_SHUTDOWN: return "SHUTDOWN";
            default: return "UNKNOWN(" + status + ")";
        }
    }

}
