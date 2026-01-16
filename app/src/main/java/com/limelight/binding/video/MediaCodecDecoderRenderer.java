package com.limelight.binding.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;
import com.limelight.BuildConfig;
import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Stereo3DRenderer;
import com.limelight.utils.TrafficStatsHelper;
import java.util.concurrent.atomic.AtomicLongArray;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.limelight.profiles.ProfilesManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaCodec.CodecException;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.util.Range;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import com.limelight.perf.CpuWarmUp;
import android.os.Looper;
public class MediaCodecDecoderRenderer extends VideoDecoderRenderer implements Choreographer.FrameCallback {

    // --- FSR-like upscaler reflection helpers (no hard dependency) ---
// Derived from AMD FidelityFX Super Resolution 1.0 (MIT). See third_party/amd-fsr1/LICENSE
    private static final class UpscalerReflect {
        private static volatile java.lang.reflect.Constructor<?> sCtor;
        private static volatile java.lang.reflect.Method sCreateInputSurface;
        private static volatile java.lang.reflect.Method sSetDebugEnabled;
        private static volatile java.lang.reflect.Method sGetOverlayLine;
        private static volatile java.lang.reflect.Method sSetPresentationHint;

        private static final java.util.concurrent.ConcurrentHashMap<String, java.lang.reflect.Method> sNoArg =
                new java.util.concurrent.ConcurrentHashMap<>(4);

        private static java.lang.reflect.Constructor<?> ctor() throws Throwable {
            java.lang.reflect.Constructor<?> c = sCtor;
            if (c != null) return c;
            synchronized (UpscalerReflect.class) {
                c = sCtor;
                if (c == null) {
                    Class<?> cls = Class.forName("com.limelight.render.GlUpscaleRenderer");
                    c = cls.getConstructor(Surface.class, int.class, int.class, PreferenceConfiguration.class);
                    sCtor = c;
                }
            }
            return c;
        }

        private static java.lang.reflect.Method noArg(Object upscaler, String name) throws Throwable {
            java.lang.reflect.Method m = sNoArg.get(name);
            if (m != null) return m;
            m = upscaler.getClass().getMethod(name);
            java.lang.reflect.Method prev = sNoArg.putIfAbsent(name, m);
            return (prev != null) ? prev : m;
        }

        private static java.lang.reflect.Method createInputSurface(Object upscaler) throws Throwable {
            java.lang.reflect.Method m = sCreateInputSurface;
            if (m != null) return m;
            synchronized (UpscalerReflect.class) {
                m = sCreateInputSurface;
                if (m == null) {
                    m = upscaler.getClass().getMethod("createDecoderInputSurface");
                    sCreateInputSurface = m;
                }
            }
            return m;
        }

        private static java.lang.reflect.Method setDebugEnabled(Object upscaler) throws Throwable {
            java.lang.reflect.Method m = sSetDebugEnabled;
            if (m != null) return m;
            synchronized (UpscalerReflect.class) {
                m = sSetDebugEnabled;
                if (m == null) {
                    m = upscaler.getClass().getMethod("setFsrDebugEnabled", boolean.class);
                    sSetDebugEnabled = m;
                }
            }
            return m;
        }

        private static java.lang.reflect.Method getOverlayLine(Object upscaler) throws Throwable {
            java.lang.reflect.Method m = sGetOverlayLine;
            if (m != null) return m;
            synchronized (UpscalerReflect.class) {
                m = sGetOverlayLine;
                if (m == null) {
                    m = upscaler.getClass().getMethod("getFsrOverlayLine");
                    sGetOverlayLine = m;
                }
            }
            return m;
        }

        private static java.lang.reflect.Method setPresentationHint(Object upscaler) throws Throwable {
            java.lang.reflect.Method m = sSetPresentationHint;
            if (m != null) return m;
            synchronized (UpscalerReflect.class) {
                m = sSetPresentationHint;
                if (m == null) {
                    m = upscaler.getClass().getMethod(
                            "setPresentationSizeHintFromContext",
                            android.content.Context.class);
                    sSetPresentationHint = m;
                }
            }
            return m;
        }
    }

    private static void __fsrCall(Object upscaler, String method) {
        if (upscaler == null) return;
        try {
            UpscalerReflect.noArg(upscaler, method).invoke(upscaler);
        } catch (Throwable ignored) {}
    }

    private static Surface __fsrCreateInputSurface(Object upscaler) {
        if (upscaler == null) return null;
        try {
            Object s = UpscalerReflect.createInputSurface(upscaler).invoke(upscaler);
            return (Surface) s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object __fsrMaybeCreate(Object existing, Surface windowSurface, int srcW, int srcH, PreferenceConfiguration prefs) {
        if (existing != null) return existing;
        try {
            return UpscalerReflect.ctor().newInstance(windowSurface, srcW, srcH, prefs);
        } catch (Throwable t) {
            LimeLog.warning("GL upscaler unavailable: " + t);
            return null;
        }
    }

    private static void __fsrSetDebugEnabled(Object upscaler, boolean enabled) {
        if (upscaler == null) return;
        try {
            UpscalerReflect.setDebugEnabled(upscaler).invoke(upscaler, enabled);
        } catch (Throwable ignored) {}
    }

    private static void __fsrSetPresentationHint(Object upscaler, android.content.Context ctx) {
        if (upscaler == null || ctx == null) return;
        try {
            UpscalerReflect.setPresentationHint(upscaler).invoke(upscaler, ctx);
        } catch (Throwable ignored) {}
    }


    private static String __fsrGetOverlayLine(Object upscaler) {
        if (upscaler == null) return "";
        try {
            Object s = UpscalerReflect.getOverlayLine(upscaler).invoke(upscaler);
            return (s != null) ? s.toString() : "";
        } catch (Throwable ignored) { return ""; }
    }

    private static float __fsrGetWeightMs(Object upscaler) {
        if (upscaler == null) return 0f;

        final String line = __fsrGetOverlayLine(upscaler);
        if (line == null || line.isEmpty()) return 0f;

        final float ms = __fsrParseMaxMsToken(line);
        // Sanity clamp: ignore clearly bogus values
        if (ms <= 0f || ms > 50f) return 0f;

        return ms;
    }

    // Parse the maximum "<number>ms" token found in the overlay line (case-insensitive).
    private static float __fsrParseMaxMsToken(String s) {
        if (s == null) return 0f;

        float max = 0f;

        for (int i = s.length() - 1; i >= 1; i--) {
            final char cs = s.charAt(i);
            if (cs != 's' && cs != 'S') continue;

            final char cm = s.charAt(i - 1);
            if (cm != 'm' && cm != 'M') continue;

            final int end = i - 1; // exclusive end of number (points to 'm')
            int start = end - 1;
            while (start >= 0) {
                final char c = s.charAt(start);
                if ((c >= '0' && c <= '9') || c == '.') {
                    start--;
                } else {
                    break;
                }
            }
            start++;

            if (start >= end) continue;

            final float v = __fsrParseFloatRange(s, start, end);
            if (v > max) max = v;

            // Skip over the parsed number to avoid re-parsing overlapping tokens
            i = start;
        }

        return max;
    }

    private static float __fsrParseFloatRange(String s, int start, int endExclusive) {
        float v = 0f;
        float frac = 0.1f;
        boolean seenDot = false;

        for (int i = start; i < endExclusive; i++) {
            final char c = s.charAt(i);
            if (c == '.') {
                if (seenDot) return 0f;
                seenDot = true;
                continue;
            }

            final int d = c - '0';
            if (d < 0 || d > 9) return 0f;

            if (!seenDot) {
                v = (v * 10f) + (float) d;
            } else {
                v += ((float) d) * frac;
                frac *= 0.1f;
            }
        }

        return v;
    }


    // --- end helpers ---

    // True when new VPS/SPS/PPS has been received since last submission
    private boolean csdDirty = false;

    // --- HDR state for overlays ---
    private volatile boolean hdrActive = false;
    public boolean isHdrActive() { return hdrActive; }
    // CpuWarmUp integration
    private CpuWarmUp cpuWarmUp;
    private boolean cpuWarmUpStarted = false;

    // stats

    // Decode latency tracking: PTS(us) -> enqueue time (ns), allocation-free (no boxing / no sparse map).
    private static final long LATENCY_TRACKING_CLEANUP_THRESHOLD_NS = 30_000_000_000L; // 30 seconds
    private static final int LATENCY_TRACKING_MAX_SIZE = 384; // ring capacity (small = bounded scan cost)
    private long lastLatencyTrackingCleanupNs = 0L;

    private final PtsEnqueueTracker enqueueNsByPtsUs = new PtsEnqueueTracker(LATENCY_TRACKING_MAX_SIZE);

    // PTS->enqueueNs tracker: fixed-size ring backed by atomic arrays (no per-frame allocations).
    // put() publishes a slot by writing enqNs first, then the pts key last.
    private static final class PtsEnqueueTracker {

        private static final long EMPTY_KEY = Long.MIN_VALUE;

        private final AtomicLongArray ptsUs;
        private final AtomicLongArray enqNs;
        private final int cap;
        private final AtomicInteger head = new AtomicInteger(0);

        PtsEnqueueTracker(int capacity) {
            cap = Math.max(8, capacity);
            ptsUs = new AtomicLongArray(cap);
            enqNs = new AtomicLongArray(cap);
            clear();
        }

        void clear() {
            for (int i = 0; i < cap; i++) {
                ptsUs.set(i, EMPTY_KEY);
                enqNs.set(i, 0L);
            }
            head.set(0);
        }

        void put(long pts, long ns) {
            int idx = head.getAndIncrement();
            if (idx >= cap) {
                // Wrap head in a bounded way (racy is fine, overwrite behavior is allowed).
                // Bring idx back in range without expensive modulo in the common case.
                idx = idx % cap;
                head.set(idx + 1);
            }

            // Publish: write value first, then key last.
            enqNs.set(idx, ns);
            ptsUs.set(idx, pts);
        }

        long take(long pts) {
            // Scan backwards from the newest index to maximize hit probability.
            int h = head.get();
            if (h >= cap) h = h % cap;

            int i = h;
            for (int n = 0; n < cap; n++) {
                i--;
                if (i < 0) i = cap - 1;

                final long k = ptsUs.get(i);
                if (k == pts) {
                    final long v = enqNs.get(i);
                    // Claim the slot
                    if (ptsUs.compareAndSet(i, pts, EMPTY_KEY)) {
                        return v;
                    }
                    // If CAS fails, someone else removed/overwrote; continue scanning.
                }
            }
            return Long.MIN_VALUE;
        }

        void cleanupOld(long nowNs, long thresholdNs) {
            for (int i = 0; i < cap; i++) {
                final long k = ptsUs.get(i);
                if (k != EMPTY_KEY) {
                    final long v = enqNs.get(i);
                    if ((nowNs - v) > thresholdNs) {
                        // Best-effort: clear only if key unchanged.
                        ptsUs.compareAndSet(i, k, EMPTY_KEY);
                    }
                }
            }
        }

        int size() {
            int count = 0;
            for (int i = 0; i < cap; i++) {
                if (ptsUs.get(i) != EMPTY_KEY) count++;
            }
            return count;
        }
    }



    // Update stats using both decode time (enqueue->dequeue) and end-to-end latency (uptime - PTS)

    private void updateDecodeLatencyStats(long presentationTimeUs) {
        updateDecodeLatencyStats(presentationTimeUs, System.nanoTime());
    }

    private void updateDecodeLatencyStats(long presentationTimeUs, long endNs) {
        final long enqNs = enqueueNsByPtsUs.take(presentationTimeUs);

        if (enqNs == Long.MIN_VALUE) {
            activeWindowVideoStats.decoderMisses++;
            return;
        }

        // Use provided end time instead of current System.nanoTime()
        final long decNs = endNs - enqNs;
        final long decMs = decNs / 1_000_000L;

        if (decMs >= 0 && decMs < 1000) {
            activeWindowVideoStats.decoderTimeMs += decMs;
            activeWindowVideoStats.decoderSamples++;
        }

        // Also calculate old end-to-end latency for comparison
        if (!USE_FRAME_RENDER_TIME) {
            final long e2eMs = SystemClock.uptimeMillis() - (presentationTimeUs / 1000L);
            if (e2eMs >= 0 && e2eMs < 1000) {
                activeWindowVideoStats.endToEndLatencyMs += e2eMs;

                // Keep backward-compatible totalTimeMs behavior when render-time is not used
                activeWindowVideoStats.totalTimeMs += e2eMs;
            }
        }
    }


    // cleanup method
    private void cleanupOldLatencyTrackingEntries() {
        final long nowNs = System.nanoTime();
        if (nowNs - lastLatencyTrackingCleanupNs < LATENCY_TRACKING_CLEANUP_THRESHOLD_NS) {
            return;
        }

        enqueueNsByPtsUs.cleanupOld(nowNs, LATENCY_TRACKING_CLEANUP_THRESHOLD_NS);
        lastLatencyTrackingCleanupNs = nowNs;

    }

    // enforce size limits
    private void enforceLatencyTrackingSizeLimit() {
        // No-op: tracker is fixed-size ring (overwrite-on-full).
    }

    // end stats //

    // Offload heavy perf overlay formatting off the decode thread
    private final Handler perfOverlayHandler = new Handler(Looper.getMainLooper());
    // Max horizontal shift steps for lite OLED overlay
    private static final int LITE_SHIFT_MAX_SPACES = 8;

    // Throttle overlay updates to reduce main-thread churn
    private static final long PERF_OVERLAY_DISPATCH_INTERVAL_NS = 100_000_000L; // 100 ms
    private long lastPerfOverlayDispatchNs = 0L;

    // Cached prefixes for Lite overlay OLED shift (0..LITE_SHIFT_MAX_SPACES)
    private static final String[] LITE_SHIFT_PREFIX = new String[LITE_SHIFT_MAX_SPACES + 1];
    static {
        StringBuilder sb = new StringBuilder(LITE_SHIFT_MAX_SPACES);
        LITE_SHIFT_PREFIX[0] = "";
        for (int i = 1; i <= LITE_SHIFT_MAX_SPACES; i++) {
            sb.append(' ');
            LITE_SHIFT_PREFIX[i] = sb.toString();
        }
    }

    private static final boolean USE_FRAME_RENDER_TIME = false;
    private static final boolean FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME && false;

    // ------------------------------------------------------------
    // Cold codec configuration/state (rarely touched in hot path)
    // ------------------------------------------------------------
    private static final class ColdCodecConfig {
        // Used on versions < 5.0
        ByteBuffer[] legacyInputBuffers;

        // Selected decoders (used only during init/recovery)

        MediaCodecInfo avcDecoder;
        MediaCodecInfo hevcDecoder;
        MediaCodecInfo av1Decoder;
        // CSD/HDR buffers (init-only)
        final ArrayList<byte[]> vpsBuffers = new ArrayList<>();
        final ArrayList<byte[]> spsBuffers = new ArrayList<>();
        final ArrayList<byte[]> ppsBuffers = new ArrayList<>();

        boolean submittedCsd;
        byte[] currentHdrMetadata;

        boolean needsSpsBitstreamFixup, isExynos4;
        boolean adaptivePlayback, directSubmit, fusedIdrFrame;
        boolean constrainedHighProfile;
        boolean refFrameInvalidationAvc, refFrameInvalidationHevc, refFrameInvalidationAv1;
        byte optimalSlicesPerFrame;
        boolean refFrameInvalidationActive;

        long initialExceptionTimestamp;
        boolean reportedCrash;

        // Formats (init/reconfigure)
        MediaFormat inputFormat;
        MediaFormat outputFormat;
        MediaFormat configuredFormat;
        // Initial stream geometry / orientation (configure-only)
        int initialWidth;
        int initialHeight;
        boolean invertResolution;
        // SPS hacks (rare)
        boolean needsBaselineSpsHack;
        SeqParameterSet savedSps;
        // Deferred exception reporting (rare)
        RendererException initialException;

    }

    private final ColdCodecConfig coldCfg = new ColdCodecConfig();

    // ColdCfg end

    // ==== Async decoding ====
// Always-on on API 21+; fall back to sync below 21
    private static final boolean ENABLE_ASYNC_DECODING = true;
    private boolean useAsyncCodec = ENABLE_ASYNC_DECODING &&
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M);

    private android.os.HandlerThread codecCallbackThread;

    private final java.util.concurrent.ArrayBlockingQueue<Integer> asyncInputQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(64);
    private final java.util.concurrent.ArrayBlockingQueue<Integer> asyncOutputQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(8);
    // Async: indices queued for NO-RENDER release on the renderer thread (avoid binder calls in callback).
    private final java.util.concurrent.ArrayBlockingQueue<Integer> asyncReleaseQueue =
            new java.util.concurrent.ArrayBlockingQueue<>(32);

    // Cap how many releaseOutputBuffer(false) we do per render-loop tick to avoid bursts.
    private static final int ASYNC_RELEASE_DRAIN_CAP = 8;

    private final android.util.SparseArray<android.media.MediaCodec.BufferInfo> asyncOutInfo =
            new android.util.SparseArray<>(16);

    // Async: output-ready timestamp (ns) per output index, to make decode latency comparable vs sync.
    // Guarded by synchronized(asyncOutInfo) together with asyncOutInfo (same index lifecycle).
    private final android.util.SparseLongArray asyncOutReadyNs =
            new android.util.SparseLongArray(16);


    // Async: ready timestamp (ns) for the last index returned by nextOutputIndex().
    private long lastAsyncOutputReadyNs = 0L;

    private boolean preferLowerDelays = false; // Will be set based on frame pacing mode


    // ---- Async BufferInfo pool (avoid per-frame allocations) ----
    private final java.util.ArrayDeque<android.media.MediaCodec.BufferInfo> asyncInfoPool =
            new java.util.ArrayDeque<>(32);

    private android.media.MediaCodec.BufferInfo obtainAsyncInfo() {
        synchronized (asyncInfoPool) {
            android.media.MediaCodec.BufferInfo bi = asyncInfoPool.pollFirst();
            return (bi != null) ? bi : new android.media.MediaCodec.BufferInfo();
        }
    }

    private void recycleAsyncInfo(android.media.MediaCodec.BufferInfo bi) {
        if (bi == null) return;
        bi.set(0, 0, 0, 0);
        synchronized (asyncInfoPool) {
            if (asyncInfoPool.size() < 64) {
                asyncInfoPool.addFirst(bi);
            }
        }
    }
    private void releaseAsyncOutputNoRenderAndRecycleInfo(MediaCodec codec, int index) {
        if (codec == null || index < 0) return;

        try { releaseOutputBufferNoRenderLocked(codec, index); } catch (Throwable ignored) {}

        android.media.MediaCodec.BufferInfo bi = null;
        try {
            synchronized (asyncOutInfo) {
                bi = asyncOutInfo.get(index);
                asyncOutInfo.remove(index);
                asyncOutReadyNs.delete(index);
            }
        } catch (Throwable ignored) {}

        recycleAsyncInfo(bi);
    }
    // Async: queue an output buffer index for NO-RENDER release on the renderer thread.
    // Always recycles BufferInfo immediately to avoid leaks.
    // Returns true if queued, false if the queue is full.
    private boolean enqueueAsyncNoRenderRelease(int index) {
        if (index < 0) return true;

        android.media.MediaCodec.BufferInfo bi = null;
        try {
            synchronized (asyncOutInfo) {
                bi = asyncOutInfo.get(index);
                asyncOutInfo.remove(index);
                asyncOutReadyNs.delete(index);
            }
        } catch (Throwable ignored) {}

        recycleAsyncInfo(bi);

        // Best-effort: never block the callback thread.
        return asyncReleaseQueue.offer(index);
    }

    // Safety wrapper for the callback thread: if the queue is full, release immediately to prevent leaks.
    private void enqueueAsyncNoRenderReleaseOrReleaseNow(MediaCodec codec, int index) {
        if (codec == null || index < 0) return;
        if (!enqueueAsyncNoRenderRelease(index)) {
            try { releaseOutputBufferNoRenderLocked(codec, index); } catch (Throwable ignored) { }
        }
    }

    // Drain queued no-render releases on the renderer thread.
    // Bounded to avoid large bursts of binder calls in a single tick.
    private void drainAsyncNoRenderReleaseQueue() {
        final MediaCodec codec = videoDecoder;
        if (codec == null) {
            // Drop queued indices if codec is gone.
            while (asyncReleaseQueue.poll() != null) { }
            return;
        }

        int n = 0;
        Integer idx;
        while (n < ASYNC_RELEASE_DRAIN_CAP && (idx = asyncReleaseQueue.poll()) != null) {
            try { releaseOutputBufferNoRenderLocked(codec, idx); } catch (Throwable ignored) { }
            n++;
        }
    }

// ==== End async decoding ====


    // ==== Nano Pacer ====
    private volatile int streamTargetFps = 60;
    private final NanoPacer nanoPacer = new NanoPacer();
    private final NanoPacer.LatestOutput nanoLatest = new NanoPacer.LatestOutput();

    private final NanoPacer.OutputCallbacks nanoPacerCallbacks = new NanoPacer.OutputCallbacks() {
        @Override
        public int nextOutputIndex(android.media.MediaCodec.BufferInfo info, int timeoutUs) {
            return MediaCodecDecoderRenderer.this.nextOutputIndex(info, timeoutUs);
        }

        @Override
        public void releaseOutputBuffer(int index, boolean render) {
            try {
                final MediaCodec codec = MediaCodecDecoderRenderer.this.videoDecoder;
                if (codec != null) {
                    releaseOutputBufferRenderLocked(codec, index, render);
                }
            } catch (Throwable ignored) { }
        }

        @Override
        public void onDequeued(long presentationTimeUs, long dequeueNs) {
            try {
                MediaCodecDecoderRenderer.this.updateDecodeLatencyStats(presentationTimeUs, dequeueNs);
            } catch (Throwable ignored) { }
        }
    };

    // ==== End Nano Pacer  ====


    private int nextInputBufferIndex = -1;
    private ByteBuffer nextInputBuffer;

    private Context context;
    private Activity activity;
    private MediaCodec videoDecoder;

    // Serialize MediaCodec.releaseOutputBuffer() across threads to reduce contention/stutter.
    private final Object releaseOutputBufferLock = new Object();

    private void releaseOutputBufferNoRenderLocked(MediaCodec codec, int index) {
        if (codec == null || index < 0) return;
        synchronized (releaseOutputBufferLock) {
            codec.releaseOutputBuffer(index, false);
        }
    }

    private void releaseOutputBufferRenderLocked(MediaCodec codec, int index, boolean render) {
        if (codec == null || index < 0) return;
        synchronized (releaseOutputBufferLock) {
            codec.releaseOutputBuffer(index, render);
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void releaseOutputBufferAtTimeLockedLollipop(MediaCodec codec, int index, long renderTimeNs) {
        if (codec == null || index < 0) return;
        synchronized (releaseOutputBufferLock) {
            codec.releaseOutputBuffer(index, renderTimeNs);
        }
    }

    private Thread rendererThread;
    private int videoFormat;
    private Surface renderTarget;
    private volatile boolean stopping;
    private CrashListener crashListener;

    private int consecutiveCrashCount;
    private String glRenderer;
    private boolean foreground = true;
    private PerfOverlayListener perfListener;
    // --- OLED burn-in protection for Lite overlay (horizontal pixel/text shift) ---
    private static final long LITE_SHIFT_PERIOD_NS = 30_000_000_000L; // 30s
    // --- OLED "pixel refresh" blink for Lite overlay ---
    // Briefly blanks the Lite overlay to let OLED pixels rest (default: 250ms every 5 minutes).
    private static final long LITE_BLINK_PERIOD_NS = 300_000_000_000L;   // 5 min
    private static final long LITE_BLINK_DURATION_NS = 250_000_000L;     // 250 ms
    private long liteBlinkNextStartNs = 0L;
    private long liteBlinkEndNs = 0L;
    private long liteShiftNextNs = 0L;
    private int liteShiftSpaces = 0; // 0..2
    // end of lite blink\shift

    // Fetchinputbuffer utils:
    private long inputDequeueHangStartMs = 0L;
    private int inputTryAgainStreak = 0;
    private void resetInputBufferState() {
        inputTryAgainStreak = 0;
        inputDequeueHangStartMs = 0L;
        nextInputBufferIndex = -1;
        nextInputBuffer = null;
    }
    //



    // Decoder output timeouts configurable at runtime.

    // Clamp an integer to a closed interval to keep user-configured values within safe bounds.
    private static int clampInt(int v, int min, int max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    // Read an int preference with a fallback for legacy/String-backed values (e.g., older builds or migrations).
    private static int safeGetInt(SharedPreferences sp, String key, int def) {
        try {
            return sp.getInt(key, def);
        } catch (ClassCastException e) {
            try {
                String s = sp.getString(key, null);
                if (s != null) return Integer.parseInt(s.trim());
            } catch (Throwable ignored) { }
            return def;
        }
    }

    // Periodically poll decoder timing preferences to allow live tuning without restarting the stream.
    private void maybeReloadDecoderTimingPrefs() {
        final long nowNs = System.nanoTime();
        if (nowNs < nextDecoderTimingPollNs) {
            return;
        }
        nextDecoderTimingPollNs = nowNs + DECODER_TIMING_POLL_INTERVAL_NS;

        final SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);

        final int dequeueUs = clampInt(
                safeGetInt(sp, "seekbar_decoder_output_timeout_us", 50000),
                0, 50000);

        final int drainUs = clampInt(
                safeGetInt(sp, "seekbar_decoder_output_drain_timeout_us", 0),
                0, 50000);

        runtimeOutputDequeueTimeoutUs = dequeueUs;
        runtimeOutputDrainTimeoutUs = drainUs;

        // Keep the runtime snapshot in sync for code paths that read from PreferenceConfiguration.
        if (prefs != null) {
            prefs.decoderOutputDequeueTimeoutUs = dequeueUs;
            prefs.decoderOutputDrainTimeoutUs = drainUs;
        }
    }
    // END: Decoder output timeouts configurable at runtime.

    private static final int CR_MAX_TRIES = 10;
    private static final int CR_RECOVERY_TYPE_NONE = 0;
    private static final int CR_RECOVERY_TYPE_FLUSH = 1;
    private static final int CR_RECOVERY_TYPE_RESTART = 2;
    private static final int CR_RECOVERY_TYPE_RESET = 3;
    private AtomicInteger codecRecoveryType = new AtomicInteger(CR_RECOVERY_TYPE_NONE);
    private final Object codecRecoveryMonitor = new Object();

    // Each thread that touches the MediaCodec object or any associated buffers must have a flag
    // here and must call doCodecRecoveryIfRequired() on a regular basis.
    private static final int CR_FLAG_INPUT_THREAD = 0x1;
    private static final int CR_FLAG_RENDER_THREAD = 0x2;
    private static final int CR_FLAG_CHOREOGRAPHER = 0x4;
    private static final int CR_FLAG_ALL = CR_FLAG_INPUT_THREAD | CR_FLAG_RENDER_THREAD | CR_FLAG_CHOREOGRAPHER;
    private int codecRecoveryThreadQuiescedFlags = 0;
    private int codecRecoveryAttempts = 0;

    private RendererException initialException;
    private static final int EXCEPTION_REPORT_DELAY_MS = 3000;

    private VideoStats activeWindowVideoStats;
    private VideoStats lastWindowVideoStats;
    private VideoStats globalVideoStats;

    private long lastTimestampUs;
    private int lastFrameNumber;
    private int refreshRate;
    private PreferenceConfiguration prefs;
    // ---- Runtime frame pacing refresh (overlay-first, UI fallback) ----
    private static final long FRAME_PACING_POLL_INTERVAL_NS = 250_000_000L; // 250 ms
    private long nextFramePacingPollNs = 0L;
    private int appliedFramePacing = Integer.MIN_VALUE;

    private float minDecodeTime = Float.MAX_VALUE;
    private String minDecodeTimeFullLog = "";
    // ---- Runtime decoder timing prefs (poll to allow live tuning) ----
    private static final long DECODER_TIMING_POLL_INTERVAL_NS = 250_000_000L; // 250 ms
    private long nextDecoderTimingPollNs = 0L;

    private volatile int runtimeOutputDequeueTimeoutUs = 50000;
    private volatile int runtimeOutputDrainTimeoutUs = 0;
    // MAX_SMOOTHNESS: AdaptX Smooth-style output dequeue tuning (µs)
    private static final int MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US = 500;
    private static final int MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US = 3000;
    private static final int MAX_SMOOTH_ADAPTIVE_STEP_US = 250;

    // Starts small; auto-tunes at runtime (only used for FRAME_PACING_MAX_SMOOTHNESS)
    private volatile int maxSmoothAdaptiveDequeueTimeoutUs = 500;


    //    private long lastNetDataNum;
    private volatile long lastNetDataNum;

    // Balanced pacing queue: bounded + allocation-free per-frame (no LinkedBlockingQueue Node allocations).
    private static final int OUTPUT_BUFFER_QUEUE_LIMIT = 2;
    private final ArrayBlockingQueue<Integer> outputBufferQueue =
            new ArrayBlockingQueue<>(OUTPUT_BUFFER_QUEUE_LIMIT);

    private long lastRenderedFrameTimeNanos;

    private HandlerThread choreographerHandlerThread;
    private Handler choreographerHandler;
    // ---- Balanced (Choreographer) pacing state ----
    private volatile float lastPacingStreamFps = -1f;
    private volatile float lastPacingDisplayHz = -1f;
    private volatile double vsyncsPerFrame = 1.0;
    private volatile double vsyncAccumulator = 0.0;

    private final Runnable repostChoreographerCallback = new Runnable() {
        @Override
        public void run() {
            try {
                Choreographer.getInstance().postFrameCallback(MediaCodecDecoderRenderer.this);
            } catch (Throwable ignored) { }
        }
    };

    private int numSpsIn;
    private int numPpsIn;
    private int numVpsIn;
    private int numFramesIn;
    private int numFramesOut;

    private MediaCodecInfo findAvcDecoder() {
        MediaCodecInfo decoder = MediaCodecHelper.findProbableSafeDecoder("video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        if (decoder == null) {
            decoder = MediaCodecHelper.findFirstDecoder("video/avc");
        }
        return decoder;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private boolean decoderCanMeetPerformancePoint(MediaCodecInfo.VideoCapabilities caps, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaCodecInfo.VideoCapabilities.PerformancePoint targetPerfPoint = new MediaCodecInfo.VideoCapabilities.PerformancePoint(coldCfg.initialWidth, coldCfg.initialHeight, Math.round(prefs.fps));
            List<MediaCodecInfo.VideoCapabilities.PerformancePoint> perfPoints = caps.getSupportedPerformancePoints();
            if (perfPoints != null) {
                for (MediaCodecInfo.VideoCapabilities.PerformancePoint perfPoint : perfPoints) {
                    // If we find a performance point that covers our target, we're good to go
                    if (perfPoint.covers(targetPerfPoint)) {
                        return true;
                    }
                }

                // We had performance point data but none met the specified streaming settings
                return false;
            }

            // Fall-through to try the Android M API if there's no performance point data
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                // We'll ask the decoder what it can do for us at this resolution and see if our
                // requested frame rate falls below or inside the range of achievable frame rates.
                Range<Double> fpsRange = caps.getAchievableFrameRatesFor(coldCfg.initialWidth, coldCfg.initialHeight);
                if (fpsRange != null) {
                    return prefs.fps <= fpsRange.getUpper();
                }

                // Fall-through to try the Android L API if there's no performance point data
            } catch (IllegalArgumentException e) {
                // Video size not supported at any frame rate
                return false;
            }
        }

        // As a last resort, we will use areSizeAndRateSupported() which is explicitly NOT a
        // performance metric, but it can work at least for the purpose of determining if
        // the codec is going to die when given a stream with the specified settings.
        return caps.areSizeAndRateSupported(coldCfg.initialWidth, coldCfg.initialHeight, prefs.fps);
    }

    private boolean decoderCanMeetPerformancePointWithHevcAndNotAvc(MediaCodecInfo hevcDecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
            MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

            return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(hevcCaps, prefs);
        }
        else {
            // No performance data
            return false;
        }
    }

    private boolean decoderCanMeetPerformancePointWithAv1AndNotHevc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo hevcDecoderInfo, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();
            MediaCodecInfo.VideoCapabilities hevcCaps = hevcDecoderInfo.getCapabilitiesForType("video/hevc").getVideoCapabilities();

            return !decoderCanMeetPerformancePoint(hevcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
        }
        else {
            // No performance data
            return false;
        }
    }

    private boolean decoderCanMeetPerformancePointWithAv1AndNotAvc(MediaCodecInfo av1DecoderInfo, MediaCodecInfo avcDecoderInfo, PreferenceConfiguration prefs) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            MediaCodecInfo.VideoCapabilities avcCaps = avcDecoderInfo.getCapabilitiesForType("video/avc").getVideoCapabilities();
            MediaCodecInfo.VideoCapabilities av1Caps = av1DecoderInfo.getCapabilitiesForType("video/av01").getVideoCapabilities();

            return !decoderCanMeetPerformancePoint(avcCaps, prefs) && decoderCanMeetPerformancePoint(av1Caps, prefs);
        }
        else {
            // No performance data
            return false;
        }
    }

    private MediaCodecInfo findHevcDecoder(PreferenceConfiguration prefs, boolean meteredNetwork, boolean requestedHdr) {
        // Don't return anything if H.264 is forced
        if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_H264) {
            return null;
        }

        // We don't try the first HEVC decoder. We'd rather fall back to hardware accelerated AVC instead
        //
        // We need HEVC Main profile, so we could pass that constant to findProbableSafeDecoder, however
        // some decoders (at least Qualcomm's Snapdragon 805) don't properly report support
        // for even required levels of HEVC.
        MediaCodecInfo hevcDecoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/hevc", -1);
        if (hevcDecoderInfo != null) {
            if (!MediaCodecHelper.decoderIsWhitelistedForHevc(hevcDecoderInfo)) {
                LimeLog.info("Found HEVC decoder, but it's not whitelisted - "+hevcDecoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_HEVC) {
                    LimeLog.info("Forcing HEVC enabled despite non-whitelisted decoder");
                }
                // HDR implies HEVC forced on, since HEVCMain10HDR10 is required for HDR.
                else if (requestedHdr) {
                    LimeLog.info("Forcing HEVC enabled for HDR streaming");
                }
                // > 4K streaming also requires HEVC, so force it on there too.
                else if (coldCfg.initialWidth > 4096 || coldCfg.initialHeight > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming");
                }
                // Use HEVC if the H.264 decoder is unable to meet the performance point
                else if (coldCfg.avcDecoder != null &&
                        decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, coldCfg.avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted HEVC decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return hevcDecoderInfo;
    }

    private MediaCodecInfo findAv1Decoder(PreferenceConfiguration prefs) {
        // For now, don't use AV1 unless explicitly requested
        if (prefs.videoFormat != PreferenceConfiguration.FormatOption.FORCE_AV1) {
            return null;
        }

        MediaCodecInfo decoderInfo = MediaCodecHelper.findProbableSafeDecoder("video/av01", -1);
        if (decoderInfo != null) {
            if (!MediaCodecHelper.isDecoderWhitelistedForAv1(decoderInfo)) {
                LimeLog.info("Found AV1 decoder, but it's not whitelisted - "+decoderInfo.getName());

                // Force HEVC enabled if the user asked for it
                if (prefs.videoFormat == PreferenceConfiguration.FormatOption.FORCE_AV1) {
                    LimeLog.info("Forcing AV1 enabled despite non-whitelisted decoder");
                }
                // Use AV1 if the HEVC decoder is unable to meet the performance point
                else if (coldCfg.hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, coldCfg.hevcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                // Use AV1 if the H.264 decoder is unable to meet the performance point and we have no HEVC decoder
                else if (coldCfg.hevcDecoder == null && decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, coldCfg.avcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                else {
                    return null;
                }
            }
        }

        return decoderInfo;
    }

    public void setRenderTarget(Surface renderTarget) {
        // Tear down previous upscaler if surface changed
        if (this.renderTarget != null && this.renderTarget != renderTarget && glUpscaler != null) {
            try { __fsrCall(glUpscaler, "release"); } catch (Throwable ignored) {}
            glUpscaler = null;
            if (decoderInputSurfaceForUpscale != null) {
                try { decoderInputSurfaceForUpscale.release(); } catch (Throwable ignored) {}
                decoderInputSurfaceForUpscale = null;
            }
        }

        this.renderTarget = renderTarget;

        // Re-apply presentation hint to upscaler when render target may change
        __fsrSetPresentationHint(glUpscaler, context);
    }

    public MediaCodecDecoderRenderer(Activity activity, PreferenceConfiguration prefs,
                                     CrashListener crashListener, int consecutiveCrashCount,
                                     boolean meteredData, boolean requestedHdr, boolean invertResolution,
                                     String glRenderer, PerfOverlayListener perfListener) {
        //dumpDecoders();
        this.streamTargetFps = (prefs != null) ? (int) prefs.fps : 60;
        this.context = activity;
        this.activity = activity;
        this.prefs = prefs;
        runtimeOutputDequeueTimeoutUs = (prefs != null) ? prefs.decoderOutputDequeueTimeoutUs : 50000;
        runtimeOutputDrainTimeoutUs = (prefs != null) ? prefs.decoderOutputDrainTimeoutUs : 0;
        nextDecoderTimingPollNs = 0L;

        this.crashListener = crashListener;
        this.consecutiveCrashCount = consecutiveCrashCount;
        this.glRenderer = glRenderer;
        this.perfListener = perfListener;
        this.coldCfg.invertResolution = invertResolution;

        this.activeWindowVideoStats = new VideoStats();
        this.lastWindowVideoStats = new VideoStats();
        this.globalVideoStats = new VideoStats();

        coldCfg.avcDecoder = findAvcDecoder();
        if (coldCfg.avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: "+coldCfg.avcDecoder.getName());
        }
        else {
            LimeLog.warning("No AVC decoder found");
        }

        coldCfg.hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr);
        if (coldCfg.hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: "+coldCfg.hevcDecoder.getName());
        }
        else {
            LimeLog.info("No HEVC decoder found");
        }

        coldCfg.av1Decoder = findAv1Decoder(prefs);
        if (coldCfg.av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: "+coldCfg.av1Decoder.getName());
        }
        else {
            LimeLog.info("No AV1 decoder found");
        }
        // Initialize CpuWarmUp
        cpuWarmUp = new CpuWarmUp();
        LimeLog.info("CpuWarmUp initialized; enabled setting: " +
                (prefs != null ? prefs.cpuWarmUpEnable : "prefs null"));
        // Set attributes that are queried in getCapabilities(). This must be done here
        // because getCapabilities() may be called before setup() in current versions of the common
        // library. The limitation of this is that we don't know whether we're using HEVC or AVC.
        int avcOptimalSlicesPerFrame = 0;
        int hevcOptimalSlicesPerFrame = 0;
        if (coldCfg.avcDecoder != null) {
            coldCfg.directSubmit = MediaCodecHelper.decoderCanDirectSubmit(coldCfg.avcDecoder.getName());
            coldCfg.refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(coldCfg.avcDecoder.getName(), coldCfg.initialHeight);
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(coldCfg.avcDecoder.getName());

            if (coldCfg.directSubmit) {
                LimeLog.info("Decoder "+coldCfg.avcDecoder.getName()+" will use direct submit");
            }
            if (coldCfg.refFrameInvalidationAvc) {
                                LimeLog.info("Decoder "+coldCfg.avcDecoder.getName()+" will use reference frame invalidation for AVC");
            }
            LimeLog.info("Decoder "+coldCfg.avcDecoder.getName()+" wants "+avcOptimalSlicesPerFrame+" slices per frame");
        }

        if (coldCfg.hevcDecoder != null) {
            coldCfg.refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(coldCfg.hevcDecoder);
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(coldCfg.hevcDecoder.getName());

            if (coldCfg.refFrameInvalidationHevc) {
                LimeLog.info("Decoder "+coldCfg.hevcDecoder.getName()+" will use reference frame invalidation for HEVC");
            }

            LimeLog.info("Decoder "+coldCfg.hevcDecoder.getName()+" wants "+hevcOptimalSlicesPerFrame+" slices per frame");
        }

        if (coldCfg.av1Decoder != null) {
            coldCfg.refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(coldCfg.av1Decoder);

            if (coldCfg.refFrameInvalidationAv1) {
                LimeLog.info("Decoder "+coldCfg.av1Decoder.getName()+" will use reference frame invalidation for AV1");
            }
        }

        // Use the larger of the two slices per frame preferences
        coldCfg.optimalSlicesPerFrame = (byte)Math.max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame);
        LimeLog.info("Requesting "+coldCfg.optimalSlicesPerFrame+" slices per frame");

        if (consecutiveCrashCount % 2 == 1) {
            coldCfg.refFrameInvalidationAvc = coldCfg.refFrameInvalidationHevc = false;
            LimeLog.warning("Disabling RFI due to previous crash");
        }
    }

    public boolean isHevcSupported() {
        return coldCfg.hevcDecoder != null;
    }

    public boolean isAvcSupported() {
        return coldCfg.avcDecoder != null;
    }

    public boolean isHevcMain10Hdr10Supported() {
        if (coldCfg.hevcDecoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : coldCfg.hevcDecoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                LimeLog.info("HEVC decoder "+coldCfg.hevcDecoder.getName()+" supports HEVC Main10 HDR10");
                return true;
            }
        }

        return false;
    }

    public boolean isAv1Supported() {
        return coldCfg.av1Decoder != null;
    }

    public boolean isAv1Main10Supported() {
        if (coldCfg.av1Decoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : coldCfg.av1Decoder.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                LimeLog.info("AV1 decoder "+coldCfg.av1Decoder.getName()+" supports AV1 Main 10 HDR10");
                return true;
            }
        }

        return false;
    }

    public int getPreferredColorSpace() {
        // Default to Rec 709 which is probably better supported on modern devices.
        //
        // We are sticking to Rec 601 on older devices unless the device has an HEVC decoder
        // to avoid possible regressions (and they are < 5% of installed devices). If we have
        // an HEVC decoder, we will use Rec 709 (even for H.264) since we can't choose a
        // colorspace by codec (and it's probably safe to say a SoC with HEVC decoding is
        // plenty modern enough to handle H.264 VUI colorspace info).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || coldCfg.hevcDecoder != null || coldCfg.av1Decoder != null) {
            return MoonBridge.COLORSPACE_REC_709;
        }
        else {
            return MoonBridge.COLORSPACE_REC_601;
        }
    }

    public int getPreferredColorRange() {
        if (prefs.fullRange) {
            return MoonBridge.COLOR_RANGE_FULL;
        }
        else {
            return MoonBridge.COLOR_RANGE_LIMITED;
        }
    }

    public void notifyVideoForeground() {
        foreground = true;
    }

    public void notifyVideoBackground() {
        foreground = false;
    }

    public int getActiveVideoFormat() {
        return this.videoFormat;
    }

    private MediaFormat createBaseMediaFormat(String mimeType) {
        MediaFormat videoFormat = MediaFormat.createVideoFormat(mimeType, coldCfg.initialWidth, coldCfg.initialHeight);

        // Avoid setting KEY_FRAME_RATE on Lollipop and earlier to reduce compatibility risk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate);
        }

        // Populate keys for adaptive playback
        if (coldCfg.adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, coldCfg.initialWidth);
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, coldCfg.initialHeight);
        }

        // Android 7.0 adds color options to the MediaFormat
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            videoFormat.setInteger(MediaFormat.KEY_COLOR_RANGE,
                    getPreferredColorRange() == MoonBridge.COLOR_RANGE_FULL ?
                            MediaFormat.COLOR_RANGE_FULL : MediaFormat.COLOR_RANGE_LIMITED);

            // If the stream is HDR-capable, the decoder will detect transitions in color standards
            // rather than us hardcoding them into the MediaFormat.
            if ((getActiveVideoFormat() & MoonBridge.VIDEO_FORMAT_MASK_10BIT) == 0) {
                // Set color format keys when not in HDR mode, since we know they won't change
                videoFormat.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                switch (getPreferredColorSpace()) {
                    case MoonBridge.COLORSPACE_REC_601:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC);
                        break;
                    case MoonBridge.COLORSPACE_REC_709:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
                        break;
                    case MoonBridge.COLORSPACE_REC_2020:
                        videoFormat.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020);
                        break;
                }
            }
        }
        return videoFormat;
    }

    private void configureAndStartDecoder(MediaFormat format) {
        // Set HDR metadata if present
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (coldCfg.currentHdrMetadata != null) {
                ByteBuffer hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer hdrMetadata = ByteBuffer.wrap(coldCfg.currentHdrMetadata).order(ByteOrder.LITTLE_ENDIAN);

                // Create a HDMI Dynamic Range and Mastering InfoFrame as defined by CTA-861.3
                hdrStaticInfo.put((byte) 0); // Metadata type
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // RX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // RY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // GX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // GY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // BX
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // BY
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // White X
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // White Y
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max mastering luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Min mastering luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max content luminance
                hdrStaticInfo.putShort(hdrMetadata.getShort()); // Max frame average luminance

                hdrStaticInfo.rewind();
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, hdrStaticInfo);
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                format.removeKey(MediaFormat.KEY_HDR_STATIC_INFO);
            }
        }

        LimeLog.info("Configuring with format: "+format);

        // Enable async callbacks after start
        try { attachAsyncCodecIfNeeded(); } catch (Throwable ignored) {}

        // If FSR-like upscaling is enabled, configure decoder to output to GL upscaler input surface
        Surface __codecSurface = renderTarget;
        if (prefs != null && prefs.videoUpscaleEnable) {
            try {
                if (glUpscaler == null) {
                    glUpscaler = __fsrMaybeCreate((Object)glUpscaler, renderTarget, coldCfg.initialWidth, coldCfg.initialHeight, prefs);
                    decoderInputSurfaceForUpscale = __fsrCreateInputSurface(glUpscaler);
                    // Provide presentation-size hint from Context if available
                    try {
                        java.lang.reflect.Method __m = glUpscaler.getClass().getMethod("setPresentationSizeHintFromContext", android.content.Context.class);
                        __m.invoke(glUpscaler, context);
                    } catch (Throwable ignored) {}
}
                __codecSurface = decoderInputSurfaceForUpscale;
            } catch (Throwable t) {
                LimeLog.warning("GL upscaler init failed; falling back: " + t);
                try { if (glUpscaler != null) __fsrCall(glUpscaler, "release"); } catch (Throwable ignored) {}
                glUpscaler = null; decoderInputSurfaceForUpscale = null;
            }
        }
        videoDecoder.configure(format, __codecSurface, null, 0);

        // Start GL upscaler loop if present
        try { if (glUpscaler != null) __fsrCall(glUpscaler, "start"); } catch (Throwable ignored) {}
        // Apply user VSync setting (checkbox_Vsync)
        applyUpscalerVsyncSettingIfSupported();


        try {
            if (glUpscaler != null) {
                boolean dbg = false;
                if (prefs != null) {
                    dbg = prefs.enablePerfOverlayLite
                            && prefs.enablePerfOverlayLiteAdvanced
                            && prefs.videoUpscaleEnable
                            && !prefs.gpuPathMode;
                }
                __fsrSetDebugEnabled(glUpscaler, dbg);
            }
        } catch (Throwable ignored) {}



        coldCfg.configuredFormat = format;

        // After reconfiguration, we must resubmit CSD buffers
        coldCfg.submittedCsd = false;
        coldCfg.vpsBuffers.clear();
        coldCfg.spsBuffers.clear();
        coldCfg.ppsBuffers.clear();

// Clear decode latency tracking when decoder is reconfigured
        enqueueNsByPtsUs.clear();
        csdDirty = false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // This will contain the actual accepted input format attributes
            coldCfg.inputFormat = videoDecoder.getInputFormat();
            LimeLog.info("Input format: "+coldCfg.inputFormat);
        }

        videoDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);

        // Start the decoder
        videoDecoder.start();
        MediaCodecHelper.applyFrameworkLowLatencyPostStart(videoDecoder);
// Diagnostics: dump negotiated input/output formats and check vendor keys acceptance
try {
    MediaFormat __inF = videoDecoder.getInputFormat();
    MediaFormat __outF = videoDecoder.getOutputFormat();
    LimeLog.info("Decoder input format: " + (__inF != null ? __inF.toString() : "<null>"));
    LimeLog.info("Decoder output format: " + (__outF != null ? __outF.toString() : "<null>"));
} catch (Throwable t) {
    LimeLog.info("Decoder formats unavailable after start");
}


        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            coldCfg.legacyInputBuffers = videoDecoder.getInputBuffers();
        }
    }

    private boolean tryConfigureDecoder(MediaCodecInfo selectedDecoderInfo, MediaFormat format, boolean throwOnCodecError) {
        boolean configured = false;
        try {
            videoDecoder = MediaCodec.createByCodecName(selectedDecoderInfo.getName());
            configureAndStartDecoder(format);
            LimeLog.info("Using codec " + selectedDecoderInfo.getName() + " for hardware decoding " + format.getString(MediaFormat.KEY_MIME));
            configured = true;
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IllegalStateException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw e;
            }
        } catch (IOException e) {
            e.printStackTrace();
            if (throwOnCodecError) {
                throw new RuntimeException(e);
            }
        } finally {
            if (!configured && videoDecoder != null) {
                videoDecoder.release();
                videoDecoder = null;
            }
        }
        return configured;
    }

    public int initializeDecoder(boolean throwOnCodecError) {
        String mimeType;
        MediaCodecInfo selectedDecoderInfo;

        if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
            mimeType = "video/avc";
            selectedDecoderInfo = coldCfg.avcDecoder;

            if (coldCfg.avcDecoder == null) {
                LimeLog.severe("No available AVC decoder!");
                return -1;
            }

            if (coldCfg.initialWidth > 4096 || coldCfg.initialHeight > 4096) {
                LimeLog.severe("> 4K streaming only supported on HEVC");
                return -1;
            }

            // These fixups only apply to H264 decoders
            coldCfg.needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.getName());
            coldCfg.needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.getName());
            coldCfg.constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.getName());
            coldCfg.isExynos4 = MediaCodecHelper.isExynos4Device();
            if (coldCfg.needsSpsBitstreamFixup) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs SPS bitstream restrictions fixup");
            }
            if (coldCfg.needsBaselineSpsHack) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs baseline SPS hack");
            }
            if (coldCfg.constrainedHighProfile) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs constrained high profile");
            }
            if (coldCfg.isExynos4) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" is on Exynos 4");
            }

            coldCfg.refFrameInvalidationActive = coldCfg.refFrameInvalidationAvc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mimeType = "video/hevc";
            selectedDecoderInfo = coldCfg.hevcDecoder;

            if (coldCfg.hevcDecoder == null) {
                LimeLog.severe("No available HEVC decoder!");
                return -2;
            }

            coldCfg.refFrameInvalidationActive = coldCfg.refFrameInvalidationHevc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mimeType = "video/av01";
            selectedDecoderInfo = coldCfg.av1Decoder;

            if (coldCfg.av1Decoder == null) {
                LimeLog.severe("No available AV1 decoder!");
                return -2;
            }

            coldCfg.refFrameInvalidationActive = coldCfg.refFrameInvalidationAv1;
        }
        else {
            // Unknown format
            LimeLog.severe("Unknown format");
            return -3;
        }
        coldCfg.adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType);
        coldCfg.fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType);

        for (int tryNumber = 0;; tryNumber++) {
            LimeLog.info("Decoder configuration try: "+tryNumber);

            MediaFormat mediaFormat = createBaseMediaFormat(mimeType);
            // This will try low latency options until we find one that works (or we give up).
            boolean newFormat = MediaCodecHelper.setDecoderLowLatencyOptions(mediaFormat, selectedDecoderInfo, prefs.enableUltraLowLatency, tryNumber);
            //todo 色彩格式
//            MediaCodecInfo.CodecCapabilities codecCapabilities = selectedDecoderInfo.getCapabilitiesForType(mimeType);
//            int[] colorFormats=codecCapabilities.colorFormats;
//            for (int colorFormat : colorFormats) {
//                LimeLog.info("Decoder configuration colorFormats: "+colorFormat);
//            }
            // Throw the underlying codec exception on the last attempt if the caller requested it
            if (tryConfigureDecoder(selectedDecoderInfo, mediaFormat, !newFormat && throwOnCodecError)) {
                // Success!
                break;
            }

            if (!newFormat) {
                // We couldn't even configure a decoder without any low latency options
                return -5;
            }
        }

        if (USE_FRAME_RENDER_TIME && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoDecoder.setOnFrameRenderedListener(new MediaCodec.OnFrameRenderedListener() {
                @Override
                public void onFrameRendered(MediaCodec mediaCodec, long presentationTimeUs, long renderTimeNanos) {
                    long delta = (renderTimeNanos / 1000000L) - (presentationTimeUs / 1000);
                    if (delta >= 0 && delta < 1000) {
                        // totalTimeMs is the render-time based end-to-end metric
                        activeWindowVideoStats.totalTimeMs += delta;

                        // keep endToEndLatencyMs in sync with what the overlay expects
                        activeWindowVideoStats.endToEndLatencyMs += delta;
                    }

                }
            }, null);
        }

        return 0;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.coldCfg.initialWidth = coldCfg.invertResolution ? height : width;
        this.coldCfg.initialHeight = coldCfg.invertResolution ? width : height;
        this.videoFormat = format;
        this.refreshRate = redrawRate;

        return initializeDecoder(false);
    }
    private Object glUpscaler; // usato via reflection
    private android.view.Surface decoderInputSurfaceForUpscale;


    // All threads that interact with the MediaCodec instance must call this function regularly!
    private boolean doCodecRecoveryIfRequired(int quiescenceFlag) {
        // NB: We cannot check 'stopping' here because we could end up bailing in a partially
        // quiesced state that will cause the quiesced threads to never wake up.
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            // Common case
            return false;
        }

        // We need some sort of recovery, so quiesce all threads before starting that
        synchronized (codecRecoveryMonitor) {
            if (choreographerHandlerThread == null) {
                // If we have no choreographer thread, we can just mark that as quiesced right now.
                codecRecoveryThreadQuiescedFlags |= CR_FLAG_CHOREOGRAPHER;
            }

            codecRecoveryThreadQuiescedFlags |= quiescenceFlag;

            // This is the final thread to quiesce, so let's perform the codec recovery now.
            if (codecRecoveryThreadQuiescedFlags == CR_FLAG_ALL) {
                // Input and output buffers are invalidated by stop() and reset().
                nextInputBuffer = null;
                nextInputBufferIndex = -1;
                outputBufferQueue.clear();
                asyncInputQueue.clear();
                asyncOutputQueue.clear();
                synchronized (asyncOutInfo) {
                    asyncOutInfo.clear();
                    asyncOutReadyNs.clear();
                }
                // Clear decode latency tracking during codec recovery
                enqueueNsByPtsUs.clear();
                csdDirty = false;
                // If we just need a flush, do so now with all threads quiesced.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    LimeLog.warning("Flushing decoder");
                    try {
                        videoDecoder.flush();
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESTART);
                    }
                }

                // We don't count flushes as codec recovery attempts
                if (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    codecRecoveryAttempts++;
                    LimeLog.info("Codec recovery attempt: "+codecRecoveryAttempts);
                }

                // For "recoverable" exceptions, we can just stop, reconfigure, and restart.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESTART) {
                    LimeLog.warning("Trying to restart decoder after CodecException");
                    try {
                        videoDecoder.stop();
                        configureAndStartDecoder(coldCfg.configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the restart, let's use a bigger hammer
                        // and try a reset instead.
                        codecRecoveryType.set(CR_RECOVERY_TYPE_RESET);
                    }
                }

                // For "non-recoverable" exceptions on L+, we can call reset() to recover
                // without having to recreate the entire decoder again.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    LimeLog.warning("Trying to reset decoder after CodecException");
                    try {
                        videoDecoder.reset();
                        configureAndStartDecoder(coldCfg.configuredFormat);
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();

                        // Something went wrong during the reset, we'll have to resort to
                        // releasing and recreating the decoder now.
                    }
                }

                // If we _still_ haven't managed to recover, go for the nuclear option and just
                // throw away the old decoder and reinitialize a new one from scratch.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_RESET) {
                    LimeLog.warning("Trying to recreate decoder after CodecException");
                    videoDecoder.release();

                    try {
                        int err = initializeDecoder(true);
                        if (err != 0) {
                            throw new IllegalStateException("Decoder reset failed: " + err);
                        }
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalArgumentException e) {
                        e.printStackTrace();

                        // Our Surface is probably invalid, so just stop
                        stopping = true;
                        codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        // If we failed to recover after all of these attempts, just crash
                        if (!coldCfg.reportedCrash) {
                            coldCfg.reportedCrash = true;
                            crashListener.notifyCrash(e);
                        }
                        throw new RendererException(this, e);
                    }
                }

                // Wake all quiesced threads and allow them to begin work again
                codecRecoveryThreadQuiescedFlags = 0;
                codecRecoveryMonitor.notifyAll();

            }
            else {
                // If we haven't quiesced all threads yet, wait to be signalled after recovery.
                // The final thread to be quiesced will handle the codec recovery.
                while (codecRecoveryType.get() != CR_RECOVERY_TYPE_NONE) {
                    try {
                        LimeLog.info("Waiting to quiesce decoder threads: "+codecRecoveryThreadQuiescedFlags);
                        codecRecoveryMonitor.wait(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();

                        // InterruptedException clears the thread's interrupt status. Since we can't
                        // handle that here, we will re-interrupt the thread to set the interrupt
                        // status back to true.
                        Thread.currentThread().interrupt();

                        break;
                    }
                }
            }
        }

        return true;
    }

    // Returns true if the exception is transient
    private boolean handleDecoderException(IllegalStateException e) {
        // Eat decoder exceptions if we're in the process of stopping
        if (stopping) {
            return false;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && e instanceof CodecException) {
            CodecException codecExc = (CodecException) e;

            if (codecExc.isTransient()) {
                // We'll let transient exceptions go
                LimeLog.warning(codecExc.getDiagnosticInfo());
                return true;
            }

            LimeLog.severe(codecExc.getDiagnosticInfo());

            // We can attempt a recovery or reset at this stage to try to start decoding again
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                // If the exception is non-recoverable or we already require a reset, perform a reset.
                // If we have no prior unrecoverable failure, we will try a restart instead.
                if (codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder requires restart for recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                        LimeLog.info("Decoder flush promoted to restart for recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET && codecRecoveryType.get() != CR_RECOVERY_TYPE_RESTART) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }
                else if (!codecExc.isRecoverable()) {
                    if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder requires reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder flush promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                        LimeLog.info("Decoder restart promoted to reset for non-recoverable CodecException");
                        e.printStackTrace();
                    }
                    else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                        throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                    }
                }

                // The recovery will take place when all threads reach doCodecRecoveryIfRequired().
                return false;
            }
        }
        else {
            // IllegalStateException was primarily used prior to the introduction of CodecException.
            // Recovery from this requires a full decoder reset.
            //
            // NB: CodecException is an IllegalStateException, so we must check for it first.
            if (codecRecoveryAttempts < CR_MAX_TRIES) {
                if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder requires reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder flush promoted to reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_RESTART, CR_RECOVERY_TYPE_RESET)) {
                    LimeLog.info("Decoder restart promoted to reset for IllegalStateException");
                    e.printStackTrace();
                }
                else if (codecRecoveryType.get() != CR_RECOVERY_TYPE_RESET) {
                    throw new IllegalStateException("Unexpected codec recovery type: " + codecRecoveryType.get());
                }

                return false;
            }
        }

        // Only throw if we're not in the middle of codec recovery
        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_NONE) {
            //
            // There seems to be a race condition with decoder/surface teardown causing some
            // decoders to to throw IllegalStateExceptions even before 'stopping' is set.
            // To workaround this while allowing real exceptions to propagate, we will eat the
            // first exception. If we are still receiving exceptions 3 seconds later, we will
            // throw the original exception again.
            //
            if (coldCfg.initialException != null) {
                if (SystemClock.uptimeMillis() - coldCfg.initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    if (!coldCfg.reportedCrash) {
                        coldCfg.reportedCrash = true;
                        crashListener.notifyCrash(initialException);
                    }
                    throw coldCfg.initialException;
                }
            } else {
                initialException = new RendererException(this, e);
                coldCfg.initialExceptionTimestamp = SystemClock.uptimeMillis();
            }
        }

        // Not transient
        return false;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (stopping) {
            return;
        }

        // If the Choreographer thread was stopped, do not re-post callbacks.
        if (choreographerHandlerThread == null) {
            return;
        }

        // Only pace/present via Choreographer in Balanced mode.
        final boolean isBalanced =
                (prefs != null && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED);

        if (!isBalanced) {
            // Attempt codec recovery even if we are not presenting right now.
            doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);
            return;
        }

        // Stream FPS if available, otherwise fall back to last known stream target.
        final float streamFps =
                (prefs != null && prefs.fps > 0)
                        ? prefs.fps
                        : (streamTargetFps > 0 ? (float) streamTargetFps : 60f);

        final float displayHz =
                (refreshRate > 0) ? (float) refreshRate : 60f;

        // Recompute ratio when inputs change.
        if (Math.abs(streamFps - lastPacingStreamFps) > 0.01f ||
                Math.abs(displayHz - lastPacingDisplayHz) > 0.01f) {
            lastPacingStreamFps = streamFps;
            lastPacingDisplayHz = displayHz;

            vsyncAccumulator = 0.0;
            if (streamFps > 0.01f && displayHz > 0.01f) {
                vsyncsPerFrame = (double) displayHz / (double) streamFps;
            } else {
                vsyncsPerFrame = 1.0;
            }

            // Clamp to sane bounds
            if (vsyncsPerFrame < 0.25) vsyncsPerFrame = 0.25;
            if (vsyncsPerFrame > 8.0) vsyncsPerFrame = 8.0;
        }

        boolean shouldRenderThisVsync = true;

        // If stream is slower than display, distribute frames across vsyncs (e.g., 90Hz/60fps -> 1,2,1,2...).
        if (vsyncsPerFrame > 1.02) {
            vsyncAccumulator += 1.0;
            if (vsyncAccumulator + 1e-9 < vsyncsPerFrame) {
                shouldRenderThisVsync = false;
            } else {
                vsyncAccumulator -= vsyncsPerFrame;
            }
        } else {
            // Stream >= display: render every vsync (decoder-side dropping is handled by queue limit).
            vsyncAccumulator = 0.0;
        }

        if (shouldRenderThisVsync) {
            Integer nextOutputBuffer = outputBufferQueue.poll();
            if (nextOutputBuffer != null) {
                final MediaCodec codec = videoDecoder;
                if (codec != null) {
                    boolean released = false;
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            releaseOutputBufferAtTimeLockedLollipop(codec, nextOutputBuffer, frameTimeNanos);
                        } else {
                            releaseOutputBufferRenderLocked(codec, nextOutputBuffer, true);
                        }
                        released = true;
                    } catch (IllegalStateException ignored) {
                        // handled below
                    }

                    if (released) {
                        lastRenderedFrameTimeNanos = frameTimeNanos;
                        if (activeWindowVideoStats != null) {
                            activeWindowVideoStats.totalFramesRendered++;
                        }
                    } else {
                        try {
                            // Try to avoid leaking the output buffer by releasing it without rendering
                            releaseOutputBufferNoRenderLocked(codec, nextOutputBuffer);
                        } catch (IllegalStateException e) {
                            e.printStackTrace();
                            handleDecoderException(e);
                        }
                    }
                }
            } else {
                // No buffer ready: avoid drifting phase while decoder is starved.
                if (vsyncsPerFrame > 1.02) {
                    vsyncAccumulator = 0.0;
                }
            }
        }


        // Attempt codec recovery even if we have nothing to render right now.
        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);

        // Request another callback for next frame (unless stopped concurrently).
        if (!stopping && choreographerHandler != null && choreographerHandlerThread != null) {
            // Avoid per-frame allocations: repost directly when already on the Choreographer looper.
            if (Looper.myLooper() == choreographerHandler.getLooper()) {
                try {
                    Choreographer.getInstance().postFrameCallback(this);
                } catch (Throwable ignored) { }
            } else {
                choreographerHandler.post(repostChoreographerCallback);
            }
        }
    }

    private void startChoreographerThread() {
        if (prefs == null || prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            return;
        }

        if (choreographerHandlerThread == null) {
            choreographerHandlerThread = new HandlerThread(
                    "Video - Choreographer",
                    Process.THREAD_PRIORITY_DISPLAY
            );
            choreographerHandlerThread.start();
            choreographerHandler = new Handler(choreographerHandlerThread.getLooper());
        } else if (choreographerHandler == null) {
            choreographerHandler = new Handler(choreographerHandlerThread.getLooper());
        }

        // Reset pacing state on entry (avoid carrying phase from previous modes)
        lastPacingStreamFps = -1f;
        lastPacingDisplayHz = -1f;
        vsyncsPerFrame = 1.0;
        vsyncAccumulator = 0.0;

        // Start callbacks (no appVsyncOffset adjustment)
        if (choreographerHandler != null) {
            // Avoid enqueueing multiple reposts if startChoreographerThread() is called repeatedly.
            choreographerHandler.removeCallbacks(repostChoreographerCallback);
            choreographerHandler.post(repostChoreographerCallback);
        }
    }


    private void stopChoreographerThread() {
        final Handler h = choreographerHandler;
        final HandlerThread ht = choreographerHandlerThread;

        // Mark as absent immediately so recovery logic won't wait for it
        choreographerHandler = null;
        choreographerHandlerThread = null;

        if (h == null || ht == null) {
            return;
        }

        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    h.removeCallbacks(repostChoreographerCallback);
                } catch (Throwable ignored) { }

                try {
                    Choreographer.getInstance().removeFrameCallback(MediaCodecDecoderRenderer.this);
                } catch (Throwable ignored) {
                }

                try {
                    ht.quitSafely();
                } catch (Throwable t) {
                    try {
                        ht.quit();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }


    private void startRendererThread() {
        rendererThread = new Thread() {
            @Override
            public void run() {
                rendererTid = Process.myTid();
                applyVideoThreadPriorities();
                BufferInfo info = new BufferInfo();
                final android.media.MediaCodec.BufferInfo lfrInfo =
                        new android.media.MediaCodec.BufferInfo();

                // Cleanup throttling to avoid GC spikes
                long lastCleanupNs = System.nanoTime();
                final long CLEANUP_INTERVAL_NS = 1_000_000_000L; // 1 second

                while (!stopping) {

                    // Apply settings changes while streaming (frame pacing hot-reload)
                    maybeApplyRuntimeFramePacing();

                    // Live-tune decoder timeouts from app settings
                    maybeReloadDecoderTimingPrefs();
                    // Drain async no-render releases here to avoid output backpressure and callback-thread binder calls.
                    drainAsyncNoRenderReleaseQueue();

                    // Timeout: 0 for immediate delivery, otherwise user-configurable wait
                    final int decodeTimeout = (prefs != null && prefs.immediateFrameDelivery)
                            ? 0
                            : runtimeOutputDequeueTimeoutUs;

                    // Max Smooth Special Policy (UI precedence + cap)
                    final boolean isMaxSmooth =
                            (prefs != null && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS);

                    final int firstOutTimeoutUs;
                    if (prefs != null && prefs.immediateFrameDelivery) {
                        // UI wins globally: force PURE dequeue (0 µs)
                        firstOutTimeoutUs = 0;
                    } else if (isMaxSmooth) {
                        // UI cap wins for MAX_SMOOTHNESS:
                        // - if UI timeout == 0 -> PURE (0 µs)
                        // - else adaptive clamped to [MIN..min(MAX, UI)]
                        final int uiCapUs = runtimeOutputDequeueTimeoutUs;

                        if (uiCapUs <= 0) {
                            firstOutTimeoutUs = 0;
                        } else {
                            final int capUs = Math.max(
                                    MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                    Math.min(MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US, uiCapUs));

                            final int curUs = Math.max(
                                    MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                    Math.min(capUs, maxSmoothAdaptiveDequeueTimeoutUs));

                            firstOutTimeoutUs = curUs;
                        }
                    } else {
                        firstOutTimeoutUs = decodeTimeout;
                    }


                    // Throttle cleanup to avoid performance spikes
                    final long nowNs = System.nanoTime();
                    if (nowNs - lastCleanupNs > CLEANUP_INTERVAL_NS) {
                        cleanupOldLatencyTrackingEntries();
                        lastCleanupNs = nowNs;
                    }

                    try {
                        // Attempt to retrieve next output buffer
                        int outIndex = nextOutputIndex(info, firstOutTimeoutUs);
                        if (outIndex >= 0) {
                            long presentationTimeUs = info.presentationTimeUs;
                            int lastFlags = info.flags;
                            int lastIndex = outIndex;

                            numFramesOut++;

                            final boolean isBalanced =
                                    (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED);

                        // Track dequeue time for the newest output buffer (latest-only)
                            final long nowNsLocal = System.nanoTime();
                            long lastDequeueTimeNs = (useAsyncCodec && lastAsyncOutputReadyNs != 0L)
                                    ? lastAsyncOutputReadyNs
                                    : nowNsLocal;

                        // Measure decode latency for the first dequeued buffer too
                            try { updateDecodeLatencyStats(presentationTimeUs, lastDequeueTimeNs); }
                            catch (Throwable ignored) { }

                            if (!isBalanced) {
                                if (isMaxSmooth) {

                                    // AdaptX Smooth-style: short first dequeue already done (firstOutTimeoutUs),
                                    // then chase newest with non-blocking follow-ups and a soft cap.
                                    int drained = 1; // includes the already dequeued buffer
                                    boolean drainedMultiple = false;

                                    final int drainSoftCap = (firstOutTimeoutUs <= 500) ? 4 : 3;

                                    while (drained < drainSoftCap &&
                                            (outIndex = nextOutputIndex(info, 0)) >= 0) {

                                        final long thisDequeueTimeNs = (useAsyncCodec && lastAsyncOutputReadyNs != 0L)
                                                ? lastAsyncOutputReadyNs
                                                : nowNsLocal;


                                        try { releaseOutputBufferNoRenderLocked(videoDecoder, lastIndex); }
                                        catch (Throwable ignored) { }

                                        numFramesOut++;
                                        lastIndex = outIndex;
                                        presentationTimeUs = info.presentationTimeUs;
                                        lastFlags = info.flags;

                                        // Measure decode latency for this dequeued buffer
                                        try { updateDecodeLatencyStats(presentationTimeUs, thisDequeueTimeNs); }
                                        catch (Throwable ignored) { }

                                        lastDequeueTimeNs = thisDequeueTimeNs;

                                        drained++;
                                        drainedMultiple = true;
                                    }

                                    // Adaptive timeout tuning (MAX_SMOOTHNESS only), respecting UI cap.
                                    final int uiCapUs = runtimeOutputDequeueTimeoutUs;

                                    if (uiCapUs > 0 && !prefs.immediateFrameDelivery) {
                                        final int capUs = Math.max(
                                                MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                                Math.min(MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US, uiCapUs));

                                        final boolean hitSoftCap = (drained >= drainSoftCap);

                                        // If we often hit the soft cap (multiple frames ready), reduce timeout (be more aggressive).
                                        // If we usually don't drain multiples, increase timeout (less polling).
                                        if (hitSoftCap || drainedMultiple) {
                                            maxSmoothAdaptiveDequeueTimeoutUs = Math.max(
                                                    MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                                    Math.min(capUs, maxSmoothAdaptiveDequeueTimeoutUs - MAX_SMOOTH_ADAPTIVE_STEP_US));
                                        } else {
                                            maxSmoothAdaptiveDequeueTimeoutUs = Math.max(
                                                    MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                                    Math.min(capUs, maxSmoothAdaptiveDequeueTimeoutUs + MAX_SMOOTH_ADAPTIVE_STEP_US));
                                        }
                                    }

                                } else {
                                    // Existing behavior for other pacing modes
                                   // to avoid large bursts of releaseOutputBuffer() binder calls.
                                    int drained = 1;
                                    final int drainSoftCap = 6;
                                    while (drained < drainSoftCap &&
                                            (outIndex = nextOutputIndex(info, runtimeOutputDrainTimeoutUs)) >= 0) {
                                        final long thisDequeueTimeNs = (useAsyncCodec && lastAsyncOutputReadyNs != 0L)
                                                ? lastAsyncOutputReadyNs
                                                : nowNsLocal;

                                        try { releaseOutputBufferNoRenderLocked(videoDecoder, lastIndex); }
                                        catch (Throwable ignored) { }

                                        numFramesOut++;
                                        lastIndex = outIndex;
                                        presentationTimeUs = info.presentationTimeUs;
                                        lastFlags = info.flags;

                                        // Measure decode latency per-buffer at dequeue time (more stable samples)
                                        try { updateDecodeLatencyStats(presentationTimeUs, thisDequeueTimeNs); }
                                        catch (Throwable ignored) { }

                                        lastDequeueTimeNs = thisDequeueTimeNs;
                                        drained++;
                                    }
                                }

                                final boolean eos =
                                        (lastFlags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                                final boolean useNanoPacer = (prefs != null && prefs.fastVsync);

                                if (useNanoPacer) {
                                    nanoPacer.updatePacingMode(
                                            true,
                                            MediaCodecDecoderRenderer.this.streamTargetFps,
                                            refreshRate
                                    );

                                    // Cooperative nano-pacer: drain while waiting to avoid output backpressure
                                    nanoLatest.index = lastIndex;
                                    nanoLatest.ptsUs = presentationTimeUs;
                                    nanoLatest.flags = lastFlags;
                                    nanoLatest.dequeueNs = lastDequeueTimeNs;

                                    numFramesOut += nanoPacer.waitAndDrainLatest(info, nanoLatest, nanoPacerCallbacks);

                                    // Pull back the possibly-updated newest output
                                    lastIndex = nanoLatest.index;
                                    presentationTimeUs = nanoLatest.ptsUs;
                                    lastFlags = nanoLatest.flags;
                                    lastDequeueTimeNs = nanoLatest.dequeueNs;
                                }
                                // Present/release newest buffer
                                releaseBufferAccordingToMode(lastIndex, presentationTimeUs);

                                activeWindowVideoStats.totalFramesRendered++;

                                // EOS must be handled only after the last buffer is released
                                if (eos) {
                                    LimeLog.info("Output EOS received");
                                    continue;
                                }
                            } else {
                                // Balanced: enqueue for Choreographer (bounded queue)
                                    // Non-blocking trimming to avoid size()/take() races
                                while (outputBufferQueue.size() >= OUTPUT_BUFFER_QUEUE_LIMIT) {
                                    Integer old = outputBufferQueue.poll();
                                    if (old == null) {
                                        break;
                                    }
                                    try {
                                        releaseOutputBufferNoRenderLocked(videoDecoder, old);
                                    } catch (Throwable ignored) { }
                                }

                                if (!outputBufferQueue.offer(lastIndex)) {
                                    // Should be rare (single producer), but never leak output buffers.
                                    try { releaseOutputBufferNoRenderLocked(videoDecoder, lastIndex); }
                                    catch (Throwable ignored) { }
                                }

                                final boolean eos =
                                        (lastFlags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;

                                // EOS after enqueue
                                if (eos) {
                                    LimeLog.info("Output EOS received");
                                    continue;
                                }
                            }

                            // Legacy end-to-end timing intentionally disabled here
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // Only handle in sync mode (async handled in callback)
                            if (!useAsyncCodec) {
                                handleOutputFormatChangeSync();
                            }

                        } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            // Non-blocking output dequeue (0us) must not busy spin.
                            if (firstOutTimeoutUs == 0) {
                                inputNonBlockingBackoff(); // reuse existing 0.2ms backoff
                            }
                        }
                    } catch (IllegalStateException e) {
                        handleDecoderException(e);
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
                    }
                }
                // Avoid stale Linux TID reuse after thread exit.
                rendererTid = 0;
            }
        };

        rendererThread.setName("Video - Renderer (MediaCodec)");
        rendererThread.setPriority(Thread.NORM_PRIORITY + 2);
        rendererThread.start();
    }

    private boolean fetchNextInputBuffer() {
        final long startNs = System.nanoTime();
        boolean codecRecovered;

        // Check stopping first to avoid false "Hung" exceptions during shutdown
        if (stopping) {
            return false;
        }

        // Prefetched buffer fast path (validate invariant: buffer implies valid index)
        if (nextInputBuffer != null) {
            if (nextInputBufferIndex >= 0) {
                inputTryAgainStreak = 0;
                inputDequeueHangStartMs = 0L;
                return true;
            }

            // Inconsistent state: drop the buffer and refetch normally
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
        } else if (nextInputBufferIndex >= 0) {
            // Inconsistent state: index without buffer
            nextInputBufferIndex = -1;
            inputTryAgainStreak = 0;
            inputDequeueHangStartMs = 0L;
        }

        final int dequeueTimeoutUs = getInputDequeueTimeoutUs();
        IllegalStateException pendingException = null;

        try {
            // If we don't have an input buffer index yet, fetch one now
            if (nextInputBuffer == null && nextInputBufferIndex < 0 && !stopping) {
                final long t0 = System.nanoTime();
                nextInputBufferIndex = nextInputIndex(dequeueTimeoutUs);
                final long elapsedUs = (System.nanoTime() - t0) / 1_000L;

                if (nextInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (dequeueTimeoutUs == 0) {
                        // Non-blocking path: avoid busy spin
                        inputNonBlockingBackoff();
                        // Reset to -1 so next call will retry, and avoid hung detection.
                        nextInputBufferIndex = -1;
                        return false;
                    } else {
                        // Blocking path: allow a single quick retry if budget remains
                        final int remainingUs = Math.max(0, dequeueTimeoutUs - (int) elapsedUs);
                        final int quickBackoffUs = Math.min(remainingUs, 1000);
                        if (quickBackoffUs > 0) {
                            nextInputBufferIndex = nextInputIndex(quickBackoffUs);
                        }
                    }
                }
            }

            // Get the backing ByteBuffer for the input buffer index
            if (nextInputBufferIndex >= 0) {
                // Reset tracking on success
                inputTryAgainStreak = 0;
                inputDequeueHangStartMs = 0L;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                    if (nextInputBuffer == null) {
                        // Fix B: preserve the real index in logs
                        final int badIndex = nextInputBufferIndex;

                        // Reset state before throwing
                        nextInputBufferIndex = -1;
                        nextInputBuffer = null;
                        inputTryAgainStreak = 0;
                        inputDequeueHangStartMs = 0L;

                        throw new IllegalStateException("getInputBuffer() returned null for index " + badIndex);
                    }
                    nextInputBuffer.clear();
                } else {
                    nextInputBuffer = coldCfg.legacyInputBuffers[nextInputBufferIndex];
                    nextInputBuffer.clear();
                }
            }
        } catch (IllegalStateException e) {
            // Defer handling until after codec recovery check
            pendingException = e;

            inputTryAgainStreak = 0;
            inputDequeueHangStartMs = 0L;
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request an IDR frame.
        if (codecRecovered) {
            inputTryAgainStreak = 0;
            inputDequeueHangStartMs = 0L;
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
            return false;
        }

        // Handle decoder exception (after recovery check)
        if (pendingException != null) {
            handleDecoderException(pendingException);
            return false;
        }

        // Hung detection - check if we're still waiting after attempts (blocking path only)
        if (nextInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            inputTryAgainStreak++;
            final long nowMs = SystemClock.uptimeMillis();

            if (inputDequeueHangStartMs == 0L) {
                inputDequeueHangStartMs = nowMs;
            } else if ((nowMs - inputDequeueHangStartMs) >= 5000 && initialException == null) {
                DecoderHungException decoderHungException =
                        new DecoderHungException((int) (nowMs - inputDequeueHangStartMs));
                if (!coldCfg.reportedCrash) {
                    coldCfg.reportedCrash = true;
                    crashListener.notifyCrash(decoderHungException);
                }
                throw new RendererException(this, decoderHungException);
            }

            // Reset for next iteration
            nextInputBufferIndex = -1;

            return false;
        }

        // Log long dequeues (>20ms)
        final long dtNs = System.nanoTime() - startNs;
        if (dtNs >= 20_000_000L) {
            LimeLog.warning("Dequeue input buffer ran long: " + (dtNs / 1_000_000L) + " ms");
        }

        return nextInputBuffer != null;
    }


    @Override
    public void start() {


        // Start CPU warm-up if enabled (independent of preferBigCores)
        if (prefs != null && prefs.cpuWarmUpEnable && cpuWarmUp != null && !cpuWarmUpStarted) {
            try {
                cpuWarmUp.start(activity, null, false);
                cpuWarmUpStarted = true;
                LimeLog.info("CpuWarmUp started");
            } catch (Throwable t) {
                LimeLog.warning("CpuWarmUp start failed: " + t);
            }
        }

        // Ensure initial frame pacing reflects settings (overlay-first, UI fallback)
        initFramePacingFromSettings();
        startRendererThread();
        startChoreographerThread();
    }

    // !!! May be called even if setup()/start() fails !!!
    public void prepareForStop() {
        // Let the decoding code know to ignore codec exceptions now
        stopping = true;

        // Stop async callbacks first to avoid new buffers arriving while tearing down
        try { detachAsyncCodec(); } catch (Throwable ignored) {}

        // IMPORTANT: Do not clear the output queue without releasing buffers.
        // Otherwise we "lose" codec output buffers and risk stalls/hangs during stop/recovery.
        drainOutputBufferQueueNoRender();

        // Clear decode latency tracking to prevent memory leaks
        enqueueNsByPtsUs.clear();

        // Stop CPU warm-up
        if (cpuWarmUp != null && cpuWarmUpStarted) {
            try {
                cpuWarmUp.stop();
                cpuWarmUpStarted = false;
                LimeLog.info("CpuWarmUp stopped");
            } catch (Throwable t) {
                LimeLog.warning("CpuWarmUp stop failed: " + t);
            }
        }

        // Halt the rendering thread
        if (rendererThread != null) {
            rendererThread.interrupt();
        }

        // Reset input buffer state to avoid stale state on next start
        resetInputBufferState();

        // Reset NanoPacer
        nanoPacer.reset();

        // Stop FSR upscaler ASAP to avoid rendering to an abandoned BufferQueue
        try { if (glUpscaler != null) { __fsrCall(glUpscaler, "release"); } } catch (Throwable ignored) {}
        glUpscaler = null;
        if (decoderInputSurfaceForUpscale != null) {
            try { decoderInputSurfaceForUpscale.release(); } catch (Throwable ignored) {}
            decoderInputSurfaceForUpscale = null;
        }
        // Stop any active codec recovery operations
        synchronized (codecRecoveryMonitor) {
            codecRecoveryType.set(CR_RECOVERY_TYPE_NONE);
            codecRecoveryMonitor.notifyAll();
        }

        // Post a quit message to the Choreographer looper (if we have one)
        if (choreographerHandler != null) {
            choreographerHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (choreographerHandlerThread != null) {
                            choreographerHandlerThread.quit();
                        }
                    } catch (Throwable ignored) {}

                    // Deregister the frame callback (if registered)
                    try {
                        Choreographer.getInstance().removeFrameCallback(MediaCodecDecoderRenderer.this);
                    } catch (Throwable ignored) {}
                }
            });
        }
    }

    private static int mapFramePacingNameToMode(String v) {
        if (v == null) return PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;

        if ("latency".equals(v)) return PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
        if ("balanced".equals(v)) return PreferenceConfiguration.FRAME_PACING_BALANCED;
        if ("cap-fps".equals(v)) return PreferenceConfiguration.FRAME_PACING_CAP_FPS;
        if ("smoothness".equals(v)) return PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS;
        if ("gpu-raw".equals(v)) return PreferenceConfiguration.FRAME_PACING_GPU_RAW;
        if ("warp".equals(v)) return PreferenceConfiguration.FRAME_PACING_WARP;
        if ("warp2".equals(v)) return PreferenceConfiguration.FRAME_PACING_WARP2;

        return PreferenceConfiguration.FRAME_PACING_MIN_LATENCY;
    }

    private String readFramePacingNameOverlayFirst() {
        try {
            final SharedPreferences overlay = ProfilesManager.getInstance().getOverlayingSharedPreferences(context);
            if (overlay != null && overlay.contains("frame_pacing")) {
                final String v = overlay.getString("frame_pacing", "latency");
                if (v != null) return v;
            }

            final SharedPreferences ui = PreferenceManager.getDefaultSharedPreferences(context);
            final String v2 = (ui != null) ? ui.getString("frame_pacing", "latency") : "latency";
            return (v2 != null) ? v2 : "latency";
        } catch (Throwable ignored) {
            return "latency";
        }
    }

    private int readFramePacingModeOverlayFirst() {
        return mapFramePacingNameToMode(readFramePacingNameOverlayFirst());
    }
    private boolean readBooleanOverlayFirst(String key, boolean def) {
        try {
            final SharedPreferences overlay = ProfilesManager.getInstance().getOverlayingSharedPreferences(context);
            if (overlay != null && overlay.contains(key)) {
                return overlay.getBoolean(key, def);
            }
        } catch (Throwable ignored) { }

        try {
            final SharedPreferences ui = PreferenceManager.getDefaultSharedPreferences(context);
            if (ui != null) {
                return ui.getBoolean(key, def);
            }
        } catch (Throwable ignored) { }

        return def;
    }

    private void applyUpscalerVsyncSettingIfSupported() {
        // Cached no-arg reflection through UpscalerReflect.noArg()
        __fsrCall(glUpscaler, "applyVsyncSetting");
    }
    private void applyUpscalerThreadPrioritiesIfSupported() {
        // Cached no-arg reflection through UpscalerReflect.noArg()
        __fsrCall(glUpscaler, "applyThreadPriorities");
    }

    private void drainOutputBufferQueueNoRender() {
        Integer idx;
        while ((idx = outputBufferQueue.poll()) != null) {
            try {
                if (videoDecoder != null) {
                                                         releaseOutputBufferNoRenderLocked(videoDecoder, idx);

                }
            } catch (Throwable ignored) { }
        }
    }

    private void applyFramePacingTransition(int oldPacing, int newPacing) {
        if (prefs != null) {
            prefs.framePacing = newPacing;
        }

        // Leaving Balanced: release queued buffers immediately and stop Choreographer.
        if (oldPacing == PreferenceConfiguration.FRAME_PACING_BALANCED &&
                newPacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {

            drainOutputBufferQueueNoRender();
            lastRenderedFrameTimeNanos = 0L;

            lastPacingStreamFps = -1f;
            lastPacingDisplayHz = -1f;
            vsyncsPerFrame = 1.0;
            vsyncAccumulator = 0.0;

            stopChoreographerThread();
        }

        // Entering Balanced: clear queue, reset state, and start Choreographer.
        if (newPacing == PreferenceConfiguration.FRAME_PACING_BALANCED) {
            outputBufferQueue.clear();
            lastRenderedFrameTimeNanos = 0L;

            lastPacingStreamFps = -1f;
            lastPacingDisplayHz = -1f;
            vsyncsPerFrame = 1.0;
            vsyncAccumulator = 0.0;

            startChoreographerThread();
        }

        // Reset pacing state so deadlines/counters don't carry across modes
        nanoPacer.reset();
        // Re-tune OS thread priorities for the new pacing mode.
        applyVideoThreadPriorities();
        applyUpscalerThreadPrioritiesIfSupported();

    }


    private void initFramePacingFromSettings() {
        final int selected = readFramePacingModeOverlayFirst();

        boolean requestedVsync = readBooleanOverlayFirst("checkbox_Vsync", false);
        final boolean requestedFastVsync = readBooleanOverlayFirst("checkbox_fastVsync", false);

        // Enforce mutual exclusion (FastVSync wins)
        if (requestedFastVsync && requestedVsync) {
            requestedVsync = false;
        }

        // Balanced wins over standard VSync
        final boolean forcedDisableVsync =
                (selected == PreferenceConfiguration.FRAME_PACING_BALANCED && requestedVsync);
        if (forcedDisableVsync) {
            requestedVsync = false;
        }

        boolean enableVsyncChanged = false;
        boolean fastVsyncChanged = false;
        if (prefs != null) {
            if (prefs.fastVsync != requestedFastVsync) {
                prefs.fastVsync = requestedFastVsync;
                fastVsyncChanged = true;
            }

            if (prefs.enableVsync != requestedVsync) {
                prefs.enableVsync = requestedVsync;
                enableVsyncChanged = true;
            }
        }

        if (forcedDisableVsync) {
            com.limelight.LimeLog.info("Disabling VSync because Balanced pacing is active");
        }

        // IMPORTANT: apply on enableVsync OR fastVsync change
        if (enableVsyncChanged || fastVsyncChanged) {
            applyUpscalerVsyncSettingIfSupported();

            // VSync backend (and thus who is the vsync gate) can change without pacing changing.
            applyVideoThreadPriorities();
            applyUpscalerThreadPrioritiesIfSupported();
        }


        // Respect user pacing choice
        final int effective = selected;

        appliedFramePacing = effective;
        nextFramePacingPollNs = 0L;

        if (prefs != null) {
            prefs.framePacing = effective;
        }
    }


    private void maybeApplyRuntimeFramePacing() {
        final long nowNs = System.nanoTime();
        if (nowNs < nextFramePacingPollNs) {
            return;
        }
        nextFramePacingPollNs = nowNs + FRAME_PACING_POLL_INTERVAL_NS;

        final int selected = readFramePacingModeOverlayFirst();

        // Live reload VSync / FastVSync (overlay-first)
        boolean requestedVsync = readBooleanOverlayFirst("checkbox_Vsync", false);
        final boolean requestedFastVsync = readBooleanOverlayFirst("checkbox_fastVsync", false);

        // Enforce mutual exclusion (FastVSync wins)
        if (requestedFastVsync && requestedVsync) {
            requestedVsync = false;
        }

        // Balanced wins over standard VSync
        final boolean forcedDisableVsync =
                (selected == PreferenceConfiguration.FRAME_PACING_BALANCED && requestedVsync);
        if (forcedDisableVsync) {
            requestedVsync = false;
        }

        boolean enableVsyncChanged = false;
        boolean fastVsyncChanged = false;
        if (prefs != null) {
            if (prefs.fastVsync != requestedFastVsync) {
                prefs.fastVsync = requestedFastVsync;
                fastVsyncChanged = true;
            }

            if (prefs.enableVsync != requestedVsync) {
                prefs.enableVsync = requestedVsync;
                enableVsyncChanged = true;
            }
        }

        if (forcedDisableVsync && enableVsyncChanged) {
            com.limelight.LimeLog.info("Disabling VSync because Balanced pacing is active (runtime check)");
        }

        // IMPORTANT: apply on enableVsync OR fastVsync change
        if (enableVsyncChanged || fastVsyncChanged) {
            applyUpscalerVsyncSettingIfSupported();
        }

        final int effective = selected;

        if (effective == appliedFramePacing) {
            return;
        }

        applyFramePacingTransition(appliedFramePacing, effective);
        appliedFramePacing = effective;
    }




    @Override
    public void stop() {
        // May be called already, but we'll call it now to be safe
        prepareForStop();
        // Final CpuWarmUp stop check
        if (cpuWarmUp != null && cpuWarmUpStarted) {
            try {
                cpuWarmUp.stop();
                cpuWarmUpStarted = false;
            } catch (Throwable ignored) {}
        }

        enqueueNsByPtsUs.clear();

        // Final async cleanup
        try { detachAsyncCodec(); } catch (Throwable ignored) {}

        // Release any queued output buffers (safety)
        drainOutputBufferQueueNoRender();
        // Reset input buffer state to avoid stale state on next start
        resetInputBufferState();

        // Reset NanoPacer
        nanoPacer.reset();

        // Wait for the Choreographer looper to shut down (bounded)
        final Thread choreo = choreographerHandlerThread;
        if (choreo != null) {
            try {
                choreo.join(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
                Thread.currentThread().interrupt();
            }

            if (choreo.isAlive()) {
                try { choreo.interrupt(); } catch (Throwable ignored) {}
                try {
                    choreo.join(250);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                }
                if (choreo.isAlive()) {
                    LimeLog.warning("Choreographer thread did not terminate in time");
                }
            }
        }

        // Wait for the renderer thread to shut down (bounded)
        final Thread rt = rendererThread;
        if (rt != null) {
            try {
                rt.join(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }

            if (rt.isAlive()) {
                try { rt.interrupt(); } catch (Throwable ignored) {}
                try {
                    rt.join(250);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Thread.currentThread().interrupt();
                }
                if (rt.isAlive()) {
                    LimeLog.warning("Renderer thread did not terminate in time");
                }
            }
        }

        // Final safety: ensure GL upscaler is torn down
        try { if (glUpscaler != null) { __fsrCall(glUpscaler, "release"); } } catch (Throwable ignored) {}
        glUpscaler = null;
        if (decoderInputSurfaceForUpscale != null) {
            try { decoderInputSurfaceForUpscale.release(); } catch (Throwable ignored) {}
            decoderInputSurfaceForUpscale = null;
        }

    }

    @Override
    public void cleanup() {

        // Clear decode latency tracking to prevent memory leaks
        enqueueNsByPtsUs.clear();

        // Ensure async resources are released
        try { detachAsyncCodec(); } catch (Throwable ignored) {}

        // Clear CSD buffers
        coldCfg.vpsBuffers.clear();
        coldCfg.spsBuffers.clear();
        coldCfg.ppsBuffers.clear();

        // Reset input buffer state to avoid stale state on next start
        resetInputBufferState();

        // Reset NanoPacer
        nanoPacer.reset();

        // Clear output buffer queue
        outputBufferQueue.clear();

        // Stop CpuWarmUp
        if (cpuWarmUp != null) {
            try {
                cpuWarmUp.stop();
                cpuWarmUpStarted = false;
            } catch (Throwable ignored) {}
        }

        // Ensure decoder and any GL upscaler resources are released
        try { if (glUpscaler != null) { __fsrCall(glUpscaler, "release"); } } catch (Throwable ignored) {}
        glUpscaler = null;
        if (decoderInputSurfaceForUpscale != null) {
            try { decoderInputSurfaceForUpscale.release(); } catch (Throwable ignored) {}
            decoderInputSurfaceForUpscale = null;
        }
        videoDecoder.release();
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        // HDR metadata is only supported in Android 7.0 and later, so don't bother
        // restarting the codec on anything earlier than that.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (coldCfg.currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
                coldCfg.currentHdrMetadata = null;
            }
            else if (enabled && hdrMetadata != null && !Arrays.equals(coldCfg.currentHdrMetadata, hdrMetadata)) {
                coldCfg.currentHdrMetadata = hdrMetadata;
            }
            else {
                // Nothing to do
                return;
            }

            // If we reach this point, we need to restart the MediaCodec instance to
            // pick up the HDR metadata change. This will happen on the next input
            // or output buffer.

            // HACK: Reset codec recovery attempt counter, since this is an expected "recovery"
            codecRecoveryAttempts = 0;

            // Promote None/Flush to Restart and leave Reset alone
            if (!codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART)) {
                codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART);
            }
        }
    }

    private boolean queueNextInputBuffer(long timestampUs, int codecFlags) {
        boolean codecRecovered;

        try {
            videoDecoder.queueInputBuffer(nextInputBufferIndex,
                    0, nextInputBuffer.position(),
                    timestampUs, codecFlags);

            // Track enqueue time for this PTS
            if (timestampUs != 0) { // Don't track config buffers with timestamp 0
            // Validate realistic PTS range.
            // Discard negative or distant future timestamps.
                long currentTimeUs = System.currentTimeMillis() * 1000L;
                if (timestampUs > 0 && timestampUs < (currentTimeUs + 3600_000_000L)) {
                    enqueueNsByPtsUs.put(timestampUs, System.nanoTime());
                }
            }

            // We need a new buffer now
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
        } catch (IllegalStateException e) {
            if (handleDecoderException(e)) {
                // Transient error: keep the buffer to avoid leaking it, and clear it for reuse.
                if (nextInputBuffer != null) {
                    nextInputBuffer.clear();
                }
            } else {
                // Non-transient error: discard state; we can't reliably queue this buffer anymore.
                nextInputBufferIndex = -1;
                nextInputBuffer = null;
            }
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request an IDR.
        if (codecRecovered) {
            return false;
        }

        // Best-effort prefetch: ok if no buffer is available yet.
        // Only propagate "false" to trigger IDR/recovery when needed.
        if (!prefetchNextInputBuffer()) {
            return false;
        }

        return true;
    }

    private void doProfileSpecificSpsPatching(SeqParameterSet sps) {
        // Some devices benefit from setting constraint flags 4 & 5 to make this Constrained
        // High Profile which allows the decoder to assume there will be no B-frames and
        // reduce delay and buffering accordingly. Some devices (Marvell, Exynos 4) don't
        // like it so we only set them on devices that are confirmed to benefit from it.
        if (sps.profileIdc == 100 && coldCfg.constrainedHighProfile) {
            LimeLog.info("Setting constraint set flags for constrained high profile");
            sps.constraintSet4Flag = true;
            sps.constraintSet5Flag = true;
        }
        else {
            // Force the constraints unset otherwise (some may be set by default)
            sps.constraintSet4Flag = false;
            sps.constraintSet5Flag = false;
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeMs, long enqueueTimeMs) {
        if (stopping) {
            // Don't bother if we're stopping
            return MoonBridge.DR_OK;
        }

        if (lastFrameNumber == 0) {
            activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
        } else if (frameNumber != lastFrameNumber && frameNumber != lastFrameNumber + 1) {
            // We can receive the same "frame" multiple times if it's an IDR frame.
            // In that case, each frame start NALU is submitted independently.
            activeWindowVideoStats.framesLost += frameNumber - lastFrameNumber - 1;
            activeWindowVideoStats.totalFrames += frameNumber - lastFrameNumber - 1;
            activeWindowVideoStats.frameLossEvents++;
        }

        // Reset CSD data for each IDR frame
        if (lastFrameNumber != frameNumber && frameType == MoonBridge.FRAME_TYPE_IDR) {
            coldCfg.vpsBuffers.clear();
            coldCfg.spsBuffers.clear();
            coldCfg.ppsBuffers.clear();
        }

        lastFrameNumber = frameNumber;

        // Flip stats windows roughly every second
        if (SystemClock.uptimeMillis() >= activeWindowVideoStats.measurementStartTimestamp + 1000) {
            // Fast path: no overlay/logging at all → just rotate stats
            if (prefs == null ||
                    (!prefs.enablePerfOverlay
                            && !prefs.enablePerfOverlayLite
                            && !prefs.enablePerfOverlayMini
                            && !prefs.enablePerfLogging)) {
                globalVideoStats.add(activeWindowVideoStats);
                lastWindowVideoStats.copy(activeWindowVideoStats);
                activeWindowVideoStats.clear();
                activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
            } else {
                VideoStats lastTwo = new VideoStats();
                lastTwo.add(lastWindowVideoStats);
                lastTwo.add(activeWindowVideoStats);
                VideoStatsFps fps = lastTwo.getFps();
                String decoder;

                if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                    decoder = (coldCfg.avcDecoder != null) ? coldCfg.avcDecoder.getName() : "(avc-null)";
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                    decoder = (coldCfg.hevcDecoder != null) ? coldCfg.hevcDecoder.getName() : "(hevc-null)";
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                    decoder = (coldCfg.av1Decoder != null) ? coldCfg.av1Decoder.getName() : "(av1-null)";
                } else {
                    decoder = "(unknown)";
                }

// Calculate both latency metrics for display
                float decodeTimeMs;
                float endToEndTimeMs;

                final long decodeDenom = (lastTwo.decoderSamples > 0)
                        ? lastTwo.decoderSamples
                        : lastTwo.totalFramesReceived;

                if (decodeDenom > 0) {
                    decodeTimeMs = (float) lastTwo.decoderTimeMs / (float) decodeDenom;
                } else {
                    decodeTimeMs = 0f;
                }

                if (lastTwo.totalFramesReceived > 0) {
                    endToEndTimeMs = (float) lastTwo.endToEndLatencyMs / (float) lastTwo.totalFramesReceived;
                } else {
                    endToEndTimeMs = 0f;
                }

                long rttInfo = MoonBridge.getEstimatedRttInfo();

// Snapshot performance-critical values for UI thread processing
                final PreferenceConfiguration prefsSnapshot = prefs;
                final VideoStats lastTwoSnapshot = lastTwo;
                final VideoStatsFps fpsSnapshot = fps;
                final float decodeTimeMsSnapshot = decodeTimeMs;
                final long rttInfoSnapshot = rttInfo;
                final String decoderSnapshot = decoder;

// Offload overlay formatting to UI thread if any overlay/logging is enabled
                if (prefsSnapshot != null &&
                        (prefsSnapshot.enablePerfOverlay
                                || prefsSnapshot.enablePerfOverlayLite
                                || prefsSnapshot.enablePerfOverlayMini
                                || prefsSnapshot.enablePerfLogging)) {

                    final long overlayNowNs = System.nanoTime();
                    if ((overlayNowNs - lastPerfOverlayDispatchNs) >= PERF_OVERLAY_DISPATCH_INTERVAL_NS) {
                        lastPerfOverlayDispatchNs = overlayNowNs;

                        perfOverlayHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                final float endToEndTimeMsSnapshot = endToEndTimeMs;
                                final boolean hdrActiveSnapshot = hdrActive;

                                buildAndDispatchPerfOverlay(
                                        prefsSnapshot,
                                        lastTwoSnapshot,
                                        fpsSnapshot,
                                        decodeTimeMsSnapshot,
                                        rttInfoSnapshot,
                                        decoderSnapshot,
                                        endToEndTimeMsSnapshot,
                                        hdrActiveSnapshot
                                );
                            }
                        });
                    }
                }

                // Rotate stats on decoder thread (always)

                globalVideoStats.add(activeWindowVideoStats);
                lastWindowVideoStats.copy(activeWindowVideoStats);
                activeWindowVideoStats.clear();
                activeWindowVideoStats.measurementStartTimestamp = SystemClock.uptimeMillis();
            }
        }

        boolean csdSubmittedForThisFrame = false;

        // IDR frames require special handling for CSD buffer submission
        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            // H264 SPS
            if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS && (videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H264) != 0) {
                numSpsIn++;

                ByteBuffer spsBuf = ByteBuffer.wrap(decodeUnitData);
                int startSeqLen = decodeUnitData[2] == 0x01 ? 3 : 4;

                // Skip to the start of the NALU data
                spsBuf.position(startSeqLen + 1);

                // The H264Utils.readSPS function safely handles
                // Annex B NALUs (including NALUs with escape sequences)
                SeqParameterSet sps = H264Utils.readSPS(spsBuf);

                // Some decoders rely on H264 level to decide how many buffers are needed
                // Since we only need one frame buffered, we'll set the level as low as we can
                // for known resolution combinations. Reference frame invalidation may need
                // these, so leave them be for those decoders.
                if (!coldCfg.refFrameInvalidationActive) {
                    if (coldCfg.initialWidth <= 720 && coldCfg.initialHeight <= 480 && refreshRate <= 60) {
                        // Max 5 buffered frames at 720x480x60
                        LimeLog.info("Patching level_idc to 31");
                        sps.levelIdc = 31;
                    }
                    else if (coldCfg.initialWidth <= 1280 && coldCfg.initialHeight <= 720 && refreshRate <= 60) {
                        // Max 5 buffered frames at 1280x720x60
                        LimeLog.info("Patching level_idc to 32");
                        sps.levelIdc = 32;
                    }
                    else if (coldCfg.initialWidth <= 1920 && coldCfg.initialHeight <= 1080 && refreshRate <= 60) {
                        // Max 4 buffered frames at 1920x1080x64
                        LimeLog.info("Patching level_idc to 42");
                        sps.levelIdc = 42;
                    }
                    else {
                        // Leave the profile alone (currently 5.0)
                    }
                }

                // TI OMAP4 requires a reference frame count of 1 to decode successfully. Exynos 4
                // also requires this fixup.
                //
                // I'm doing this fixup for all devices because I haven't seen any devices that
                // this causes issues for. At worst, it seems to do nothing and at best it fixes
                // issues with video lag, hangs, and crashes.
                //
                // It does break reference frame invalidation, so we will not do that for decoders
                // where we've enabled reference frame invalidation.
                if (!coldCfg.refFrameInvalidationActive) {
                    LimeLog.info("Patching num_ref_frames in SPS");
                    sps.numRefFrames = 1;
                }

                // GFE 2.5.11 changed the SPS to add additional extensions. Some devices don't like these
                // so we remove them here on old devices unless these devices also support HEVC.
                // See getPreferredColorSpace() for further information.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                        sps.vuiParams != null &&
                        coldCfg.hevcDecoder == null &&
                        coldCfg.av1Decoder == null) {
                    sps.vuiParams.videoSignalTypePresentFlag = false;
                    sps.vuiParams.colourDescriptionPresentFlag = false;
                    sps.vuiParams.chromaLocInfoPresentFlag = false;
                }

                // Some older devices used to choke on a bitstream restrictions, so we won't provide them
                // unless explicitly whitelisted. For newer devices, leave the bitstream restrictions present.
                if (coldCfg.needsSpsBitstreamFixup || coldCfg.isExynos4 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // The SPS that comes in the current H264 bytestream doesn't set bitstream_restriction_flag
                    // or max_dec_frame_buffering which increases decoding latency on Tegra.

                    // If the encoder didn't include VUI parameters in the SPS, add them now
                    if (sps.vuiParams == null) {
                        LimeLog.info("Adding VUI parameters");
                        sps.vuiParams = new VUIParameters();
                    }

                    // GFE 2.5.11 started sending bitstream restrictions
                    if (sps.vuiParams.bitstreamRestriction == null) {
                        LimeLog.info("Adding bitstream restrictions");
                        sps.vuiParams.bitstreamRestriction = new VUIParameters.BitstreamRestriction();
                        sps.vuiParams.bitstreamRestriction.motionVectorsOverPicBoundariesFlag = true;
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
                        sps.vuiParams.bitstreamRestriction.log2MaxMvLengthHorizontal = 16;
                        sps.vuiParams.bitstreamRestriction.log2MaxMvLengthVertical = 16;
                        sps.vuiParams.bitstreamRestriction.numReorderFrames = 0;
                    }
                    else {
                        LimeLog.info("Patching bitstream restrictions");
                    }

                    // Some devices throw errors if maxDecFrameBuffering < numRefFrames
                    sps.vuiParams.bitstreamRestriction.maxDecFrameBuffering = sps.numRefFrames;

                    // These values are the defaults for the fields, but they are more aggressive
                    // than what GFE sends in 2.5.11, but it doesn't seem to cause picture problems.
                    // We'll leave these alone for "modern" devices just in case they care.
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        sps.vuiParams.bitstreamRestriction.maxBytesPerPicDenom = 2;
                        sps.vuiParams.bitstreamRestriction.maxBitsPerMbDenom = 1;
                    }

                    // log2_max_mv_length_horizontal and log2_max_mv_length_vertical are set to more
                    // conservative values by GFE 2.5.11. We'll let those values stand.
                }
                else if (sps.vuiParams != null) {
                    // Devices that didn't/couldn't get bitstream restrictions before GFE 2.5.11
                    // will continue to not receive them now
                    sps.vuiParams.bitstreamRestriction = null;
                }

                // If we need to hack this SPS to say we're baseline, do so now
                if (coldCfg.needsBaselineSpsHack) {
                    LimeLog.info("Hacking SPS to baseline");
                    sps.profileIdc = 66;
                    coldCfg.savedSps = sps;
                }

                // Patch the SPS constraint flags
                doProfileSpecificSpsPatching(sps);

                // The H264Utils.writeSPS function safely handles
                // Annex B NALUs (including NALUs with escape sequences)
                ByteBuffer escapedNalu = H264Utils.writeSPS(sps, decodeUnitLength);

                // Construct the patched SPS
                byte[] naluBuffer = new byte[startSeqLen + 1 + escapedNalu.limit()];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, startSeqLen + 1);
                escapedNalu.get(naluBuffer, startSeqLen + 1, escapedNalu.limit());

                // Batch this to submit together with other CSD per AOSP docs
                coldCfg.spsBuffers.clear();
                coldCfg.spsBuffers.add(naluBuffer);
                csdDirty = true;
                return MoonBridge.DR_OK;

            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                coldCfg.vpsBuffers.clear();
                coldCfg.vpsBuffers.add(naluBuffer);
                csdDirty = true;
                return MoonBridge.DR_OK;
            }
            // Only the HEVC SPS hits this path (H.264 is handled above)
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                coldCfg.spsBuffers.add(naluBuffer);
                csdDirty = true;
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                coldCfg.ppsBuffers.clear();
                coldCfg.ppsBuffers.add(naluBuffer);
                csdDirty = true;
                return MoonBridge.DR_OK;

            }
            else if ((videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_H264 | MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                // If this is the first CSD blob or we aren't supporting fused IDR frames, we will
                // submit the CSD blob in a separate input buffer for each IDR frame.
                if (!coldCfg.submittedCsd || (!coldCfg.fusedIdrFrame && csdDirty)) {
                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Start clean (API>=21 clear is also enforced in fetchNextInputBuffer, but keep it explicit here)
                    nextInputBuffer.clear();

                    int csdBytes = 0;
                    for (byte[] b : coldCfg.vpsBuffers) csdBytes += b.length;
                    for (byte[] b : coldCfg.spsBuffers) csdBytes += b.length;
                    for (byte[] b : coldCfg.ppsBuffers) csdBytes += b.length;

                    if (csdBytes > nextInputBuffer.remaining()) {
                        LimeLog.info("CSD too large for one input buffer: " + csdBytes + " > " + nextInputBuffer.remaining());
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Submit all CSD when we receive the first non-CSD blob in an IDR frame
                    for (byte[] vpsBuffer : coldCfg.vpsBuffers) nextInputBuffer.put(vpsBuffer);
                    for (byte[] spsBuffer : coldCfg.spsBuffers) nextInputBuffer.put(spsBuffer);
                    for (byte[] ppsBuffer : coldCfg.ppsBuffers) nextInputBuffer.put(ppsBuffer);

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Remember that we already submitted CSD for this frame, so we don't do it
                    // again in the fused IDR case below.
                    csdSubmittedForThisFrame = true;

                    // Remember that we submitted CSD globally for this MediaCodec instance
                    coldCfg.submittedCsd = true;
                    csdDirty = false;

                    // If we are not using fused IDR frames, we don't need to keep per-IDR CSD around
                    if (!coldCfg.fusedIdrFrame) {
                        coldCfg.vpsBuffers.clear();
                        coldCfg.spsBuffers.clear();
                        coldCfg.ppsBuffers.clear();
                    }

                    if (coldCfg.needsBaselineSpsHack) {
                        coldCfg.needsBaselineSpsHack = false;

                        if (!replaySps()) {
                            return MoonBridge.DR_NEED_IDR;
                        }

                        LimeLog.info("SPS replay complete");
                    }
                }
            }
        }

        if (frameHostProcessingLatency != 0) {
            if (activeWindowVideoStats.minHostProcessingLatency != 0) {
                activeWindowVideoStats.minHostProcessingLatency = (char) Math.min(activeWindowVideoStats.minHostProcessingLatency, frameHostProcessingLatency);
            } else {
                activeWindowVideoStats.minHostProcessingLatency = frameHostProcessingLatency;
            }
            activeWindowVideoStats.framesWithHostProcessingLatency += 1;
        }
        activeWindowVideoStats.maxHostProcessingLatency = (char) Math.max(activeWindowVideoStats.maxHostProcessingLatency, frameHostProcessingLatency);
        activeWindowVideoStats.totalHostProcessingLatency += frameHostProcessingLatency;

        activeWindowVideoStats.totalFramesReceived++;
        activeWindowVideoStats.totalFrames++;

        if (!FRAME_RENDER_TIME_ONLY) {
            // Count time from first packet received to enqueue time as receive time
            // We will count DU queue time as part of decoding, because it is directly
            // caused by a slow decoder.
            activeWindowVideoStats.totalTimeMs += enqueueTimeMs - receiveTimeMs;
        }

        if (!fetchNextInputBuffer()) {
            return MoonBridge.DR_NEED_IDR;
        }

        int codecFlags = 0;

        if (frameType == MoonBridge.FRAME_TYPE_IDR) {
            codecFlags |= MediaCodec.BUFFER_FLAG_SYNC_FRAME;

            // If we are using fused IDR frames, submit the CSD with each IDR frame
            if (coldCfg.fusedIdrFrame && !csdSubmittedForThisFrame) {
                int csdBytes = 0;
                for (byte[] b : coldCfg.vpsBuffers) csdBytes += b.length;
                for (byte[] b : coldCfg.spsBuffers) csdBytes += b.length;
                for (byte[] b : coldCfg.ppsBuffers) csdBytes += b.length;

                // Ensure there is room for CSD + this decode unit in the same input buffer
                if (csdBytes + decodeUnitLength > nextInputBuffer.remaining()) {
                    // Fallback: submit CSD as codec-config first, then fetch a fresh buffer for the IDR payload
                    nextInputBuffer.clear();

                    if (csdBytes > nextInputBuffer.remaining()) {
                        LimeLog.info("Fused CSD too large for input buffer: " + csdBytes + " > " + nextInputBuffer.remaining());
                        return MoonBridge.DR_NEED_IDR;
                    }

                    for (byte[] vpsBuffer : coldCfg.vpsBuffers) nextInputBuffer.put(vpsBuffer);
                    for (byte[] spsBuffer : coldCfg.spsBuffers) nextInputBuffer.put(spsBuffer);
                    for (byte[] ppsBuffer : coldCfg.ppsBuffers) nextInputBuffer.put(ppsBuffer);

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    csdSubmittedForThisFrame = true;
                    coldCfg.submittedCsd = true;
                    csdDirty = false;

                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR;
                    }
                } else {
                    for (byte[] vpsBuffer : coldCfg.vpsBuffers) nextInputBuffer.put(vpsBuffer);
                    for (byte[] spsBuffer : coldCfg.spsBuffers) nextInputBuffer.put(spsBuffer);
                    for (byte[] ppsBuffer : coldCfg.ppsBuffers) nextInputBuffer.put(ppsBuffer);
                    csdDirty = false;
                }
            }
        }

        long timestampUs = enqueueTimeMs * 1000;
        if (timestampUs <= lastTimestampUs) {
            // We can't submit multiple buffers with the same timestamp
            // so bump it up by one before queuing
            timestampUs = lastTimestampUs + 1;
        }
        lastTimestampUs = timestampUs;

        numFramesIn++;

        if (decodeUnitLength > nextInputBuffer.limit() - nextInputBuffer.position()) {
            IllegalArgumentException exception = new IllegalArgumentException(
                    "Decode unit length "+decodeUnitLength+" too large for input buffer "+nextInputBuffer.limit());
            if (!coldCfg.reportedCrash) {
                coldCfg.reportedCrash = true;
                crashListener.notifyCrash(exception);
            }
            throw new RendererException(this, exception);
        }

        // Copy data from our buffer list into the input buffer
        nextInputBuffer.put(decodeUnitData, 0, decodeUnitLength);

        if (!queueNextInputBuffer(timestampUs, codecFlags)) {
            return MoonBridge.DR_NEED_IDR;
        }

        return MoonBridge.DR_OK;
    }

    private boolean replaySps() {
        if (!fetchNextInputBuffer()) {
            return false;
        }

        // Write the Annex B header
        nextInputBuffer.put(new byte[]{0x00, 0x00, 0x00, 0x01, 0x67});

        // Switch the H264 profile back to high
        coldCfg.savedSps.profileIdc = 100;

        // Patch the SPS constraint flags
        doProfileSpecificSpsPatching(coldCfg.savedSps);

        // The H264Utils.writeSPS function safely handles
        // Annex B NALUs (including NALUs with escape sequences)
        ByteBuffer escapedNalu = H264Utils.writeSPS(coldCfg.savedSps, 128);
        nextInputBuffer.put(escapedNalu);

        // No need for the SPS anymore
        coldCfg.savedSps = null;

        // Queue the new SPS
        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
    }

    @Override
    public int getCapabilities() {
        int capabilities = 0;

        // Request the optimal number of slices per frame for this decoder
        capabilities |= MoonBridge.CAPABILITY_SLICES_PER_FRAME(coldCfg.optimalSlicesPerFrame);

        // Enable reference frame invalidation on supported hardware
        if (coldCfg.refFrameInvalidationAvc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC;
        }
        if (coldCfg.refFrameInvalidationHevc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC;
        }
        if (coldCfg.refFrameInvalidationAv1) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1;
        }

        // Enable direct submit on supported hardware
        if (coldCfg.directSubmit) {
            capabilities |= MoonBridge.CAPABILITY_DIRECT_SUBMIT;
        }

        return capabilities;
    }

    public int getAverageEndToEndLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int)(globalVideoStats.totalTimeMs / globalVideoStats.totalFramesReceived);
    }
    //  returns pure decoder latency (enqueue->dequeue)
    public int getAveragePureDecoderLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int)(globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived);
    }

    //  returns old end-to-end latency using the new field
    public int getAverageOldEndToEndLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int)(globalVideoStats.endToEndLatencyMs / globalVideoStats.totalFramesReceived);
    }
    public int getAverageDecoderLatency() {
        if (globalVideoStats.totalFramesReceived == 0) {
            return 0;
        }
        return (int)(globalVideoStats.decoderTimeMs / globalVideoStats.totalFramesReceived);
    }

    public Boolean performanceWasTracked() {
        return minDecodeTime < Float.MAX_VALUE;
    }

    @SuppressLint("DefaultLocale")
    public String getMinDecoderLatency() {
        return String.format("%1$.2f", minDecodeTime);
    }

    public String getMinDecoderLatencyFullLog() {
        return minDecodeTimeFullLog;
    }

    static class DecoderHungException extends RuntimeException {
        private int hangTimeMs;

        DecoderHungException(int hangTimeMs) {
            this.hangTimeMs = hangTimeMs;
        }

        public String toString() {
            String str = "";

            str += "Hang time: "+hangTimeMs+" ms"+ RendererException.DELIMITER;
            str += super.toString();

            return str;
        }
    }

    static class RendererException extends RuntimeException {
        private static final long serialVersionUID = 8985937536997012406L;
        protected static final String DELIMITER = BuildConfig.DEBUG ? "\n" : " | ";

        private String text;

        RendererException(MediaCodecDecoderRenderer renderer, Exception e) {
            this.text = generateText(renderer, e);
        }

        public String toString() {
            return text;
        }

        private String generateText(MediaCodecDecoderRenderer renderer, Exception originalException) {
            String str;

            if (renderer.numVpsIn == 0 && renderer.numSpsIn == 0 && renderer.numPpsIn == 0) {
                str = "PreSPSError";
            }
            else if (renderer.numSpsIn > 0 && renderer.numPpsIn == 0) {
                str = "PrePPSError";
            }
            else if (renderer.numPpsIn > 0 && renderer.numFramesIn == 0) {
                str = "PreIFrameError";
            }
            else if (renderer.numFramesIn > 0 && renderer.coldCfg.outputFormat == null) {
                str = "PreOutputConfigError";
            }
            else if (renderer.coldCfg.outputFormat != null && renderer.numFramesOut == 0) {
                str = "PreOutputError";
            }
            else if (renderer.numFramesOut <= renderer.refreshRate * 30) {
                str = "EarlyOutputError";
            }
            else {
                str = "ErrorWhileStreaming";
            }

            str += "Format: "+String.format("%x", renderer.videoFormat)+DELIMITER;
            str += "AVC Decoder: "+((renderer.coldCfg.avcDecoder != null) ? renderer.coldCfg.avcDecoder.getName():"(none)")+DELIMITER;
            str += "HEVC Decoder: "+((renderer.coldCfg.hevcDecoder != null) ? renderer.coldCfg.hevcDecoder.getName():"(none)")+DELIMITER;
            str += "AV1 Decoder: "+((renderer.coldCfg.av1Decoder != null) ? renderer.coldCfg.av1Decoder.getName():"(none)")+DELIMITER;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.coldCfg.avcDecoder != null) {
                Range<Integer> avcWidthRange = renderer.coldCfg.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();
                str += "AVC supported width range: "+avcWidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> avcFpsRange = renderer.coldCfg.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.coldCfg.initialWidth, renderer.coldCfg.initialHeight);
                        str += "AVC achievable FPS range: "+avcFpsRange+DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "AVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.coldCfg.hevcDecoder != null) {
                Range<Integer> hevcWidthRange = renderer.coldCfg.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();
                str += "HEVC supported width range: "+hevcWidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> hevcFpsRange = renderer.coldCfg.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.coldCfg.initialWidth, renderer.coldCfg.initialHeight);
                        str += "HEVC achievable FPS range: " + hevcFpsRange + DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "HEVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.coldCfg.av1Decoder != null) {
                Range<Integer> av1WidthRange = renderer.coldCfg.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getSupportedWidths();
                str += "AV1 supported width range: "+av1WidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> av1FpsRange = renderer.coldCfg.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getAchievableFrameRatesFor(renderer.coldCfg.initialWidth, renderer.coldCfg.initialHeight);
                        str += "AV1 achievable FPS range: " + av1FpsRange + DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "AV1 achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            str += "Configured format: "+renderer.coldCfg.configuredFormat+DELIMITER;
            str += "Input format: "+renderer.coldCfg.inputFormat+DELIMITER;
            str += "Output format: "+renderer.coldCfg.outputFormat+DELIMITER;
            str += "Adaptive playback: "+renderer.coldCfg.adaptivePlayback+DELIMITER;
            str += "GL Renderer: "+renderer.glRenderer+DELIMITER;
            //str += "Build fingerprint: "+Build.FINGERPRINT+DELIMITER;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                str += "SOC: "+Build.SOC_MANUFACTURER+" - "+Build.SOC_MODEL+DELIMITER;
                str += "Performance class: "+Build.VERSION.MEDIA_PERFORMANCE_CLASS+DELIMITER;
                /*str += "Vendor params: ";
                List<String> params = renderer.videoDecoder.getSupportedVendorParameters();
                if (params.isEmpty()) {
                    str += "NONE";
                }
                else {
                    for (String param : params) {
                        str += param + " ";
                    }
                }
                str += DELIMITER;*/
            }
            str += "Consecutive crashes: "+renderer.consecutiveCrashCount+DELIMITER;
            str += "RFI active: "+renderer.coldCfg.refFrameInvalidationActive+DELIMITER;
            str += "Using modern SPS patching: "+(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)+DELIMITER;
            str += "Fused IDR frames: "+renderer.coldCfg.fusedIdrFrame+DELIMITER;
            str += "Video dimensions: "+renderer.coldCfg.initialWidth+"x"+renderer.coldCfg.initialHeight+DELIMITER;
            str += "FPS target: "+renderer.refreshRate+DELIMITER;
            str += "Bitrate: "+renderer.prefs.bitrate+" Kbps"+DELIMITER;
            str += "CSD stats: "+renderer.numVpsIn+", "+renderer.numSpsIn+", "+renderer.numPpsIn+DELIMITER;
            str += "Frames in-out: "+renderer.numFramesIn+", "+renderer.numFramesOut+DELIMITER;
            str += "Total frames received: "+renderer.globalVideoStats.totalFramesReceived+DELIMITER;
            str += "Total frames rendered: "+renderer.globalVideoStats.totalFramesRendered+DELIMITER;
            str += "Frame losses: "+renderer.globalVideoStats.framesLost+" in "+renderer.globalVideoStats.frameLossEvents+" loss events"+DELIMITER;
            str += "Average end-to-end client latency: "+renderer.getAverageEndToEndLatency()+"ms"+DELIMITER;
            str += "Average hardware decoder latency: "+renderer.getAverageDecoderLatency()+"ms"+DELIMITER;
            str += "Frame pacing mode: "+renderer.prefs.framePacing+DELIMITER;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                if (originalException instanceof CodecException) {
                    CodecException ce = (CodecException) originalException;

                    str += "Diagnostic Info: "+ce.getDiagnosticInfo()+DELIMITER;
                    str += "Recoverable: "+ce.isRecoverable()+DELIMITER;
                    str += "Transient: "+ce.isTransient()+DELIMITER;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        str += "Codec Error Code: "+ce.getErrorCode()+DELIMITER;
                    }
                }
            }

            str += originalException.toString();

            return str;
        }
    }
    /**
     * Heavy perf overlay formatting.
     * Runs on the UI/main thread via perfOverlayHandler to avoid blocking the decode loop.
     */
    /**
     * Builds performance overlay string with minimal allocations.
     * Called on UI thread via perfOverlayHandler to avoid blocking decode loop.
     */
    private void buildAndDispatchPerfOverlay(final PreferenceConfiguration prefsSnapshot,
                                             final VideoStats lastTwo,
                                             final VideoStatsFps fps,
                                             final float decodeTimeMs,
                                             final long rttInfo,
                                             final String decoder,
                                             final float endToEndTimeMs,
                                             final boolean hdrActive) {
        if (prefsSnapshot == null) return;

        // Pre-size StringBuilder based on overlay type to reduce allocations
        final int sbCap;
        if (prefsSnapshot.enablePerfOverlayMini) {
            sbCap = 96;
        } else if (prefsSnapshot.enablePerfOverlayLite) {
            sbCap = 192;
        } else {
            sbCap = 384;
        }

        StringBuilder sb = new StringBuilder(sbCap);

        final boolean __fsrActiveForE2e = prefsSnapshot.videoUpscaleEnable && glUpscaler != null;
        final float __fsrWeightMsForE2e = __fsrActiveForE2e ? __fsrGetWeightMs(glUpscaler) : 0f;
        final float e2eTotalMs = endToEndTimeMs + __fsrWeightMsForE2e;

        // --- MINI OVERLAY ---
        if (prefsSnapshot.enablePerfOverlayMini) {
            // Network bandwidth
            if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED) {
                long netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid())
                        + TrafficStatsHelper.getPackageTxBytes(Process.myUid());
                if (lastNetDataNum != 0) {
                    float realtimeNetData = (netData - lastNetDataNum) / 1024f;
                    if (realtimeNetData >= 1000) {
                        sb.append("BW: ").append(String.format("%.1f", realtimeNetData / 1024f)).append(" M/s\n");
                    } else {
                        sb.append("BW: ").append(String.format("%.1f", realtimeNetData)).append(" K/s\n");
                    }
                }
                lastNetDataNum = netData;
            }

            // Packet loss percentage
            float plPct = 0f;
            if (lastTwo.totalFrames > 0) {
                plPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
            }
            sb.append("PL: ").append(String.format("%.0f", plPct)).append("%\n");

            // Network latency and decode time
            sb.append("Net: ").append((int) (rttInfo >> 32))
                    .append("ms | Dec: ").append(String.format("%.1f", decodeTimeMs)).append("ms\n");

            // FPS
            sb.append(String.format("%.2f", fps.totalFps)).append(" FPS");
        }
        // --- LITE OVERLAY ---
        else if (prefsSnapshot.enablePerfOverlayLite) {
            // Network bandwidth with localization
            if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED) {
                long netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid())
                        + TrafficStatsHelper.getPackageTxBytes(Process.myUid());
                if (lastNetDataNum != 0) {
                    sb.append(context.getString(R.string.perf_overlay_lite_bandwidth)).append(": ");
                    float realtimeNetData = (netData - lastNetDataNum) / 1024f;
                    if (realtimeNetData >= 1000) {
                        sb.append(String.format("%.2f", realtimeNetData / 1024f)).append("M/s\t ");
                    } else {
                        sb.append(String.format("%.2f", realtimeNetData)).append("K/s\t ");
                    }
                }
                lastNetDataNum = netData;
            }

            // Network latency and decode time
            sb.append(context.getString(R.string.perf_overlay_lite_network_decoding_delay)).append(": ");
            sb.append(context.getString(R.string.perf_overlay_lite_net, (int) (rttInfo >> 32)));
            sb.append(" / ");
            sb.append(context.getString(R.string.perf_overlay_lite_dectime, decodeTimeMs));


            sb.append("\t");
            sb.append(" ");

            // Packet loss percentage
            sb.append(context.getString(R.string.perf_overlay_lite_packet_loss)).append(": ");
            float liteLossPct = 0f;
            if (lastTwo.totalFrames > 0) {
                liteLossPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
            }
            sb.append(context.getString(R.string.perf_overlay_lite_netdrops, liteLossPct));

            // Advanced Lite: end-to-end latency
            if (prefsSnapshot.enablePerfOverlayLiteAdvanced) {
                sb.append(" / ");
                sb.append(context.getString(R.string.perf_overlay_lite_e2e, e2eTotalMs));
                sb.append("  ");
            }

            // FPS
            sb.append("\t FPS：");
            sb.append(context.getString(R.string.perf_overlay_lite_fps, fps.totalFps));

            // OLED protection: horizontal shifting
            try {
                if (prefsSnapshot.enablePerfOverlayLiteOledShift) {
                    long now = System.nanoTime();
                    if (now >= liteShiftNextNs) {
                        liteShiftNextNs = now + LITE_SHIFT_PERIOD_NS;
                        // Ping-pong shift: 0 → 1 → 2 → 1 → 0
                        if (liteShiftSpaces == 0) liteShiftSpaces = 1;
                        else if (liteShiftSpaces == 1) liteShiftSpaces = 2;
                        else if (liteShiftSpaces == 2) liteShiftSpaces = 1;
                        else liteShiftSpaces = 0;
                    }
                } else {
                    liteShiftSpaces = 0;
                }
            } catch (Throwable ignored) {}

            // OLED protection: blinking
            try {
                if (prefsSnapshot.enablePerfOverlayLiteOledShift) {
                    long now = System.nanoTime();
                    if (now >= liteBlinkNextStartNs) {
                        liteBlinkNextStartNs = now + LITE_BLINK_PERIOD_NS;
                        liteBlinkEndNs = now + LITE_BLINK_DURATION_NS;
                    }
                } else {
                    liteBlinkEndNs = 0L;
                }
            } catch (Throwable ignored) {}

            // Advanced Lite metrics: received/rendered FPS and HDR status
            if (prefsSnapshot.enablePerfOverlayLiteAdvanced) {
                sb.append("  R:").append((int) fps.renderedFps);
                sb.append("  ").append(hdrActive ? "HDR" : "SDR");
                // Single compact token: [R|L|B|S|C|W|2] + optional [U] per FSR + optional [F] per GPU Path
                boolean isFsrActive = prefsSnapshot.videoUpscaleEnable && glUpscaler != null;
                sb.append(' ').append(getLitePacingGlyph(prefsSnapshot, isFsrActive));
                //not needed in this branch, we are forcing it anyway
/*                if (prefsSnapshot.gpuPathMode) {
                    sb.append('G');
                }*/
            }


            // Stereo 3D renderer info if active
            if (Stereo3DRenderer.isActive) {
                sb.append(" ");
                sb.append(context.getString(R.string.perf_overlay_ai_fps));
                sb.append(" ");
                sb.append(Stereo3DRenderer.threeDFps);
                sb.append(" ");
                sb.append(context.getString(R.string.perf_overlay_ai_delegate));
                sb.append(" ");
                sb.append(Stereo3DRenderer.renderer);
                sb.append(" ");
                sb.append(context.getString(R.string.perf_overlay_drawdelay, Stereo3DRenderer.drawDelay));
            }
        }
        // --- FULL OVERLAY ---
        else {
            // Stream resolution and FPS
            if (Stereo3DRenderer.isActive) {
                sb.append(context.getString(R.string.perf_overlay_streamdetails,
                        coldCfg.initialWidth + "x" + coldCfg.initialHeight, fps.totalFps));
                sb.append('\n');
                sb.append(" ");
                sb.append(context.getString(R.string.perf_overlay_ai_fps));
                sb.append(" ");
                sb.append(Stereo3DRenderer.threeDFps);
                sb.append(" ");
                sb.append(context.getString(R.string.perf_overlay_ai_delegate));
                sb.append(" ");
                sb.append(Stereo3DRenderer.renderer);
                sb.append(" ");
                sb.append(context.getString(R.string.perf_overlay_drawdelay, Stereo3DRenderer.drawDelay));
            } else {
                sb.append(context.getString(R.string.perf_overlay_streamdetails,
                        coldCfg.initialWidth + "x" + coldCfg.initialHeight, fps.totalFps));
            }

            sb.append('\n');
            sb.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n');
            sb.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n');
            sb.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n');

            // Packet loss
            float fullLossPct = 0f;
            if (lastTwo.totalFrames > 0) {
                fullLossPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
            }
            sb.append(context.getString(R.string.perf_overlay_netdrops, fullLossPct)).append('\n');

            // Network bandwidth
            if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED) {
                long netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid())
                        + TrafficStatsHelper.getPackageTxBytes(Process.myUid());
                if (lastNetDataNum != 0) {
                    sb.append(context.getString(R.string.perf_overlay_lite_bandwidth)).append(": ");
                    float realtimeNetData = (netData - lastNetDataNum) / 1024f;
                    if (realtimeNetData >= 1000) {
                        sb.append(String.format("%.2f", realtimeNetData / 1024f)).append("M/s\n");
                    } else {
                        sb.append(String.format("%.2f", realtimeNetData)).append("K/s\n");
                    }
                }
                lastNetDataNum = netData;
            }

            // Network latency (min/current)
            sb.append(context.getString(R.string.perf_overlay_netlatency,
                    (int) (rttInfo >> 32), (int) rttInfo)).append('\n');

            // Host processing latency stats
            if (lastTwo.framesWithHostProcessingLatency > 0) {
                sb.append(context.getString(R.string.perf_overlay_hostprocessinglatency,
                        (float) lastTwo.minHostProcessingLatency / 10,
                        (float) lastTwo.maxHostProcessingLatency / 10,
                        (float) lastTwo.totalHostProcessingLatency / 10 /
                                lastTwo.framesWithHostProcessingLatency)).append('\n');
            }

            // Decode time and end-to-end latency
            sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs));
            sb.append(context.getString(R.string.perf_overlay_lite_e2e, e2eTotalMs));
        }

/*        // Append FSR upscaler info if available
        try {
            String __fsr = __fsrGetOverlayLine(glUpscaler);
            if (__fsr != null && !__fsr.isEmpty()) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                sb.append(__fsr).append('\n');
            }
        } catch (Throwable ignored) {}*/

        String fullLog = sb.toString();

        // Apply OLED shift (prepend spaces)
        if (prefsSnapshot.enablePerfOverlayLite && prefsSnapshot.enablePerfOverlayLiteOledShift) {
            try {
                sb.insert(0, LITE_SHIFT_PREFIX[Math.min(LITE_SHIFT_MAX_SPACES, Math.max(0, liteShiftSpaces))]);
            } catch (Throwable ignored) {}
        }

        // Apply OLED blink (clear overlay during blink period)
        if (prefsSnapshot.enablePerfOverlayLite && prefsSnapshot.enablePerfOverlayLiteOledShift) {
            try {
                long now = System.nanoTime();
                if (liteBlinkEndNs > 0L && now < liteBlinkEndNs) {
                    sb.setLength(0);
                    sb.append(' '); // Minimal content for transparent overlay
                }
            } catch (Throwable ignored) {}
        }

        String rawLog = fullLog;         // Before OLED transformations
        String renderedLog = sb.toString(); // After shift/blink

        // Notify overlay listener
        if (perfListener != null && prefsSnapshot.enablePerfOverlay) {
            perfListener.onPerfUpdate(renderedLog);
        }

        // Track best decode time at target FPS
        boolean targetFpsMatched = ((int) fps.totalFps == (int) prefsSnapshot.fps);
        if (minDecodeTime > decodeTimeMs && targetFpsMatched) {
            minDecodeTime = decodeTimeMs;
            minDecodeTimeFullLog = fullLog;
        }
    }

    // Async Decoding Helpers
    // Async decoding helper methods
    private static android.media.MediaCodec.BufferInfo cloneInfo(android.media.MediaCodec.BufferInfo src) {
        android.media.MediaCodec.BufferInfo dst = new android.media.MediaCodec.BufferInfo();
        if (src != null) {
            dst.set(src.offset, src.size, src.presentationTimeUs, src.flags);
        }
        return dst;
    }

    // Return next input index (async => from queue; sync => dequeueInputBuffer)
    private int nextInputIndex(int timeoutUs) {
        if (!useAsyncCodec || videoDecoder == null) {
            return videoDecoder.dequeueInputBuffer(timeoutUs);
        }
        try {
            Integer idx = asyncInputQueue.poll(timeoutUs, java.util.concurrent.TimeUnit.MICROSECONDS);
            return (idx != null) ? idx : -1; // -1 == INFO_TRY_AGAIN_LATER
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    // Return next output index and fill outInfo (async => from queue; sync => dequeueOutputBuffer)
    private int nextOutputIndex(android.media.MediaCodec.BufferInfo outInfo, int timeoutUs) {
        if (!useAsyncCodec || videoDecoder == null) {
            lastAsyncOutputReadyNs = 0L;
            return videoDecoder.dequeueOutputBuffer(outInfo, timeoutUs);
        }

        try {
            Integer idx = asyncOutputQueue.poll(timeoutUs, java.util.concurrent.TimeUnit.MICROSECONDS);
            if (idx == null) {
                lastAsyncOutputReadyNs = 0L;
                return -1;
            }

            android.media.MediaCodec.BufferInfo bi;
            long readyNs;

            synchronized (asyncOutInfo) {
                bi = asyncOutInfo.get(idx);
                readyNs = asyncOutReadyNs.get(idx, 0L);
                asyncOutReadyNs.delete(idx);

                if (bi != null) {
                    if (outInfo != null) {
                        outInfo.set(bi.offset, bi.size, bi.presentationTimeUs, bi.flags);
                    }
                    asyncOutInfo.remove(idx);
                }
            }

            if (bi == null) {
                lastAsyncOutputReadyNs = 0L;
                // Info missing: safest is to release the output buffer (no-render) to avoid leaks
                try { releaseOutputBufferNoRenderLocked(videoDecoder, idx); } catch (Throwable ignored) { }
                return -1;
            }

            lastAsyncOutputReadyNs = readyNs;

            recycleAsyncInfo(bi);
            return idx;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastAsyncOutputReadyNs = 0L;
            return -1;
        }
    }

    private void attachAsyncCodecIfNeeded() {
        if (!useAsyncCodec || videoDecoder == null) return;

        preferLowerDelays = (prefs != null && (
                prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY ||
                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW ||
                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_WARP ||
                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_WARP2 ||
                        prefs.immediateFrameDelivery));

        if (codecCallbackThread == null) {
            codecCallbackThread = new android.os.HandlerThread(
                    "CodecAsync", desiredCodecCallbackOsPriority(getEffectivePacingForThreadPriorities()));
            codecCallbackThread.start();

// Make sure OS priorities are coherent after starting callback thread.
            applyVideoThreadPriorities();

        }
        android.os.Handler cb = new android.os.Handler(codecCallbackThread.getLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoDecoder.setCallback(new MediaCodec.Callback() {

                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                    try {
                        // Never block the MediaCodec callback thread
                        if (!asyncInputQueue.offer(index)) {
                            // Queue full: drop one queued index to make room
                            asyncInputQueue.poll();
                            asyncInputQueue.offer(index);
                        }
                    } catch (Throwable ignored) { }
                }


                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index, BufferInfo info) {
                    // Defensive copy; the system reuses 'info' for the next frame
                    final android.media.MediaCodec.BufferInfo copy = obtainAsyncInfo();
                    copy.set(info.offset, info.size, info.presentationTimeUs, info.flags);

                    boolean stored = false;
                    try {
                        // Store info for the consumer thread (also capture output-ready timestamp for latency stats)
                        final long readyNs = System.nanoTime();
                        synchronized (asyncOutInfo) {
                            asyncOutInfo.put(index, copy);
                            asyncOutReadyNs.put(index, readyNs);
                            stored = true;
                        }

                        // Keep only latest buffer for minimal latency (no binder calls in callback)
                        if (preferLowerDelays) {
                            Integer old;
                            while ((old = asyncOutputQueue.poll()) != null) {
                                enqueueAsyncNoRenderReleaseOrReleaseNow(codec, old);
                            }
                            if (!asyncOutputQueue.offer(index)) {
                                enqueueAsyncNoRenderReleaseOrReleaseNow(codec, index);
                            }
                            return;
                        }

                        // Managed profiles: bounded queue with drop-oldest policy (no binder calls in callback)
                        if (!asyncOutputQueue.offer(index)) {
                            final Integer old = asyncOutputQueue.poll();
                            if (old != null) {
                                enqueueAsyncNoRenderReleaseOrReleaseNow(codec, old);
                            }
                            if (!asyncOutputQueue.offer(index)) {
                                enqueueAsyncNoRenderReleaseOrReleaseNow(codec, index);
                            }
                        }
                    } catch (Throwable t) {
                        // Make sure we don't leak output buffers or BufferInfo objects
                        try {
                            if (stored) {
                                enqueueAsyncNoRenderReleaseOrReleaseNow(codec, index);
                            } else {
                                recycleAsyncInfo(copy);
                                try { releaseOutputBufferNoRenderLocked(codec, index); } catch (Throwable ignored) { }
                            }
                        } catch (Throwable ignored) { }
                    }
                }


                @Override
                public void onOutputFormatChanged(MediaCodec codec,
                                                  MediaFormat format) {
                    try {
                        coldCfg.outputFormat = codec.getOutputFormat();
                        LimeLog.info("Output format changed (async): " + coldCfg.outputFormat);

                        // HDR detection (copy from existing sync code)
                        try {
                            MediaFormat __fmt = coldCfg.outputFormat;
                            int __std = -1, __tr = -1, __rng = -1;
                            try { __std = __fmt.getInteger("color-standard"); } catch (Throwable ignored) {}
                            try { __tr  = __fmt.getInteger("color-transfer"); } catch (Throwable ignored) {}
                            try { __rng = __fmt.getInteger("color-range"); } catch (Throwable ignored) {}
                            // BT.2020 + (PQ o HLG) => HDR
                            boolean __isHdr =
                                    (__std == MediaFormat.COLOR_STANDARD_BT2020) &&
                                            (__tr  == MediaFormat.COLOR_TRANSFER_ST2084
                                                    || __tr  == MediaFormat.COLOR_TRANSFER_HLG);
                            // Update shared flag so overlays/renderer can see it
                            hdrActive = __isHdr;
                            // Notify window color mode (no-op <26)
                            try { Game.updateHdrWindowMode(__isHdr); } catch (Throwable ignored) {}
                            // Pass HDR static info to GL upscaler if available
                            ByteBuffer __hdr = null;
                            try { __hdr = __fmt.getByteBuffer("hdr-static-info"); } catch (Throwable ignored) {}
                            byte[] __hdrArr = null;
                            if (__hdr != null && __hdr.remaining() > 0) {
                                __hdrArr = new byte[__hdr.remaining()];
                                __hdr.get(__hdrArr);
                            }
                        } catch (Throwable ignored) {}
                        LimeLog.info("New output format: " + coldCfg.outputFormat);
                    } catch (Throwable ignored) { }
                }

                @Override
                public void onError(MediaCodec codec,
                                    CodecException e) {
                    try {
                        LimeLog.warning("[Video] MediaCodec async error: " + e);

                        // Integrate with existing recovery mechanism
                        // CodecException extends IllegalStateException, compatible with handleDecoderException
                        if (!handleDecoderException(e)) {
                            // Non-transient error requires recovery
                            // Recovery will be handled when threads call doCodecRecoveryIfRequired()
                            LimeLog.info("Async error queued for recovery");
                        }
                    } catch (Throwable t) {
                        LimeLog.severe("Error in async error handler: " + t);
                    }
                }
            }, cb);
        }
    }

    private void detachAsyncCodec() {
        try {
            if (videoDecoder != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        videoDecoder.setCallback(null, null);
                    }
                } catch (Throwable ignored) {}
            }
        } finally {
            // Clear input queue first (safe: indices are not owned by us)
            try { asyncInputQueue.clear(); } catch (Throwable ignored) {}

            // Release outstanding async output buffers before clearing queues (avoid BufferQueue stalls).
            try {
                final MediaCodec codec = videoDecoder;
                if (codec != null) {
                    Integer idx;
                    while ((idx = asyncOutputQueue.poll()) != null) {
                        releaseAsyncOutputNoRenderAndRecycleInfo(codec, idx);
                    }
                    while ((idx = asyncReleaseQueue.poll()) != null) {
                        try { releaseOutputBufferNoRenderLocked(codec, idx); } catch (Throwable ignored) { }
                    }
                } else {
                    asyncOutputQueue.clear();
                    asyncReleaseQueue.clear();
                }
            } catch (Throwable ignored) {
                try { asyncOutputQueue.clear(); } catch (Throwable ignored2) {}
                try { asyncReleaseQueue.clear(); } catch (Throwable ignored2) {}
            }


            // Clear asyncOutInfo with synchronization
            synchronized (asyncOutInfo) {
                try { asyncOutInfo.clear(); } catch (Throwable ignored) {}
                try { asyncOutReadyNs.clear(); } catch (Throwable ignored) {}
            }

            // Safely stop and wait for callback thread
            if (codecCallbackThread != null) {
                try {
                    codecCallbackThread.quitSafely();
                    // Wait for thread to finish (max 1 second)
                    codecCallbackThread.join(1000);
                    if (codecCallbackThread.isAlive()) {
                        LimeLog.warning("Codec callback thread did not terminate in time");
                    }
                } catch (Throwable t) {
                    LimeLog.warning("Error stopping callback thread: " + t);
                } finally {
                    codecCallbackThread = null;
                }
            }
        }
    }

    public boolean isAsyncDecodingActive() {
        return useAsyncCodec && codecCallbackThread != null && codecCallbackThread.isAlive();
    }

    public String getAsyncDecodingStatus() {
        return "AsyncDecoding: " + (useAsyncCodec ? "ENABLED" : "DISABLED") +
                " (API " + Build.VERSION.SDK_INT +
                ", callbackThread=" + (codecCallbackThread != null ? "alive" : "null") +
                ", inputQueue=" + asyncInputQueue.size() +
                ", outputQueue=" + asyncOutputQueue.size() + ")";
    }
    // Async Decoding Helpers End

    // Lite pacing glyph
    private static String getLitePacingGlyph(final PreferenceConfiguration p, final boolean isFsrActive) {
        if (p == null) {
            return "?";
        }

        StringBuilder glyph = new StringBuilder(3);

        // GPU Raw
        if (p.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW) {
            glyph.append('R');
        }
        // Min Latency
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY) {
            glyph.append('L');
        }
        // Balanced
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED) {
            glyph.append('B');
        }
        // Smoothness
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS) {
            glyph.append('S');
        }
        // FPS Cap
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            glyph.append('C');
        }
        // Warp modes
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_WARP) {
            glyph.append('W');
        }
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_WARP2) {
            glyph.append('2'); // W2 per differenziare
        }
        else {
            glyph.append('?');
        }

        // Add 'U' if FSR is active
        if (isFsrActive) {
            glyph.append('U');
        }

        // Add 'V' if standard VSync is enabled
        if (p.enableVsync && p.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            glyph.append('V');
        }

        // Add 'F' if FastVSync is enabled
        if (p.fastVsync) {
            glyph.append('F');
        }

        return glyph.toString();
    }


    // Helper method for buffer release based on mode
    private void releaseBufferAccordingToMode(int bufferIndex, long presentationTimeUs) {
        if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                final long tsNs = System.nanoTime();
                releaseOutputBufferAtTimeLockedLollipop(videoDecoder, bufferIndex, tsNs);

            } else {
                releaseOutputBufferRenderLocked(videoDecoder, bufferIndex, true);
            }
        } else if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            // Never-drop policy (do not hold output buffers)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                releaseOutputBufferAtTimeLockedLollipop(videoDecoder, bufferIndex, 0L);

            } else {
                releaseOutputBufferRenderLocked(videoDecoder, bufferIndex, true);
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                releaseOutputBufferAtTimeLockedLollipop(videoDecoder, bufferIndex, System.nanoTime());
            } else {
                releaseOutputBufferRenderLocked(videoDecoder, bufferIndex, true);
            }
        }
    }

    // Output format change handler (extracted for readability)
    private void handleOutputFormatChangeSync() {
        LimeLog.info("Output format changed (sync)");
        coldCfg.outputFormat = videoDecoder.getOutputFormat();

        // HDR detection
        try {
            android.media.MediaFormat fmt = coldCfg.outputFormat;
            int std = -1, tr = -1, rng = -1;
            try { std = fmt.getInteger("color-standard"); } catch (Throwable ignored) {}
            try { tr  = fmt.getInteger("color-transfer"); } catch (Throwable ignored) {}
            try { rng = fmt.getInteger("color-range"); } catch (Throwable ignored) {}

            // BT.2020 + (PQ or HLG) => HDR
            boolean isHdr = (std == android.media.MediaFormat.COLOR_STANDARD_BT2020) &&
                    (tr  == android.media.MediaFormat.COLOR_TRANSFER_ST2084 ||
                            tr  == android.media.MediaFormat.COLOR_TRANSFER_HLG);

            hdrActive = isHdr;

            // Notify window color mode
            try { com.limelight.Game.updateHdrWindowMode(isHdr); } catch (Throwable ignored) {}

            // Pass HDR static info to GL upscaler if available
            java.nio.ByteBuffer hdr = null;
            try { hdr = fmt.getByteBuffer("hdr-static-info"); } catch (Throwable ignored) {}
            byte[] hdrArr = null;
            if (hdr != null && hdr.remaining() > 0) {
                hdrArr = new byte[hdr.remaining()];
                hdr.get(hdrArr);
            }
        } catch (Throwable ignored) {}

        LimeLog.info("New output format: " + coldCfg.outputFormat);
    }

    // Non-blocking best-effort prefetch to avoid stalling the input thread.
    // Returns false only when codec recovery (or a hard decoder exception) requires the caller to request an IDR.
    private boolean prefetchNextInputBuffer() {
        if (stopping) {
            return false;
        }

        if (nextInputBuffer != null) {
            return true;
        }

        IllegalStateException pendingException = null;
        try {
            // Best-effort: never block here.
            if (nextInputBufferIndex < 0) {
                nextInputBufferIndex = nextInputIndex(0); // 0us = non-blocking

                if (nextInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    // Explicit reset to avoid confusion between "no buffer yet" and "MediaCodec error"
                    nextInputBufferIndex = -1;
                    // Avoid busy spin when no buffer is available yet.
                    inputNonBlockingBackoff();
                    // Not an error: leave state as-is and let fetch() retry when needed.
                    return true;
                }
            }

            if (nextInputBufferIndex >= 0) {
                // Reset tracking on success
                inputTryAgainStreak = 0;
                inputDequeueHangStartMs = 0L;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                    if (nextInputBuffer == null) {
                        final int badIndex = nextInputBufferIndex;

                        nextInputBufferIndex = -1;
                        nextInputBuffer = null;
                        throw new IllegalStateException("getInputBuffer() returned null for index " + badIndex);
                    }
                    nextInputBuffer.clear();
                } else {
                    nextInputBuffer = coldCfg.legacyInputBuffers[nextInputBufferIndex];
                    nextInputBuffer.clear();
                }
            }
        } catch (IllegalStateException e) {
            pendingException = e;
            // Do not start hung tracking here; treat as hard decoder exception
            inputTryAgainStreak = 0;
            inputDequeueHangStartMs = 0L;
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
        }

        // Always evaluate recovery before returning.
        if (doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD)) {
            inputTryAgainStreak = 0;
            inputDequeueHangStartMs = 0L;
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
            return false;
        }

        if (pendingException != null) {
            handleDecoderException(pendingException);
            return false;
        }

        // Best-effort: it's OK if no buffer is available right now.
        // The real fetchNextInputBuffer() will try again when the buffer is actually needed.
        return true;
    }

    // Derive input dequeue timeout from the *effective* policy, not from pacing-profile flags.
    private int getInputDequeueTimeoutUs() {
        final PreferenceConfiguration p = prefs;

        final float wantedFps =
                (p != null && p.fps > 0) ? (float) p.fps :
                        (refreshRate > 0f ? refreshRate : 60f);

        final boolean immediate = (p != null && p.immediateFrameDelivery);

        if (immediate) {
            return 0; // non-blocking
        } else if (wantedFps >= 120f) {
            return 2000;
        } else if (wantedFps >= 90f) {
            return 3000;
        } else {
            return 4000;
        }
    }

    // Backoff used only when dequeue timeout is non-blocking (0us) to avoid busy spinning.
    private static void inputNonBlockingBackoff() {
        Thread.yield();
        java.util.concurrent.locks.LockSupport.parkNanos(200_000L); // 0.2 ms
    }
    // Backoff used only when output dequeue timeout is non-blocking (0us) to avoid busy spinning.
    private static void outputNonBlockingBackoff() {
        Thread.yield();
        java.util.concurrent.locks.LockSupport.parkNanos(200_000L); // 0.2 ms
    }

    // ---- Thread priority tuning (runtime) ----
    private volatile int rendererTid = 0;

    private int getEffectivePacingForThreadPriorities() {
        // Prefer prefs (current intent), but keep applied if it matches (avoid stale override).
        final int prefsPacing = (prefs != null) ? prefs.framePacing : PreferenceConfiguration.FRAME_PACING_BALANCED;
        final int applied = appliedFramePacing;
        if (applied != Integer.MIN_VALUE && applied == prefsPacing) {
            return applied;
        }
        return prefsPacing;
    }

    private boolean isGlVsyncGateActiveForPriorities(int pacing) {
        // If upscaler is active and GL is running its own Choreographer backend (VSync ON, not Balanced),
        // GL becomes the vsync gate. In that case, keep the decoder renderer at DISPLAY to avoid contention.
        if (glUpscaler == null || prefs == null) return false;
        if (!prefs.videoUpscaleEnable) return false;
        if (prefs.gpuPathMode) return false;
        if (!prefs.enableVsync) return false;
        return (pacing != PreferenceConfiguration.FRAME_PACING_BALANCED);
    }

    private int desiredRendererOsPriority(int pacing) {
        // If GL is the vsync gate, do not fight it unless user explicitly forced "immediate".
        if (isGlVsyncGateActiveForPriorities(pacing) && !(prefs != null && prefs.immediateFrameDelivery)) {
            return Process.THREAD_PRIORITY_DISPLAY;
        }

        if (prefs != null && prefs.immediateFrameDelivery) {
            return Process.THREAD_PRIORITY_URGENT_DISPLAY;
        }

        switch (pacing) {
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
            case PreferenceConfiguration.FRAME_PACING_GPU_RAW:
            case PreferenceConfiguration.FRAME_PACING_WARP:
            case PreferenceConfiguration.FRAME_PACING_WARP2:
                return Process.THREAD_PRIORITY_URGENT_DISPLAY;

            case PreferenceConfiguration.FRAME_PACING_BALANCED:
            case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
            case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
            default:
                return Process.THREAD_PRIORITY_DISPLAY;
        }
    }

    private static int desiredCodecCallbackOsPriority(int pacing) {
        // Keep callbacks below renderer/choreo. This thread must not preempt the pipeline.
        return Process.THREAD_PRIORITY_DISPLAY;
    }

    private void applyVideoThreadPriorities() {
        final int pacing = getEffectivePacingForThreadPriorities();

        final int rendererPrio = desiredRendererOsPriority(pacing);
        final int codecPrio = desiredCodecCallbackOsPriority(pacing);
        final int choreoPrio = rendererPrio;

        // Renderer (OS)
        final int tid = rendererTid;
        if (tid != 0) {
            try { Process.setThreadPriority(tid, rendererPrio); } catch (Throwable ignored) { }
        }

        // Renderer (Java hint)
        final Thread rt = rendererThread;
        if (rt != null) {
            try {
                rt.setPriority((rendererPrio == Process.THREAD_PRIORITY_URGENT_DISPLAY)
                        ? (Thread.NORM_PRIORITY + 3)
                        : (Thread.NORM_PRIORITY + 2));
            } catch (Throwable ignored) { }
        }

        // Codec async callback (OS)
        final android.os.HandlerThread cb = codecCallbackThread;
        if (cb != null) {
            try { Process.setThreadPriority(cb.getThreadId(), codecPrio); } catch (Throwable ignored) { }
        }

        // Choreographer (OS)
        final HandlerThread choreo = choreographerHandlerThread;
        if (choreo != null) {
            try { Process.setThreadPriority(choreo.getThreadId(), choreoPrio); } catch (Throwable ignored) { }
        }
    }

}