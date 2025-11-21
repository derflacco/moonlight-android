package com.limelight.binding.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Stereo3DRenderer;
import com.limelight.utils.TrafficStatsHelper;

import android.annotation.SuppressLint;
import android.util.LongSparseArray;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.media.MediaCodec;
import android.os.Bundle;
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


public class MediaCodecDecoderRenderer extends VideoDecoderRenderer implements Choreographer.FrameCallback {
    // Lock-free single-producer/single-consumer ring for output indices
    private static final class SpscRing {
        private final int[] buf;
        private final int capMask;
        private volatile int head = 0; // consumer index
        private volatile int tail = 0; // producer index

        SpscRing(int requestedCapacity) {
            int cap = 1;
            while (cap < requestedCapacity) cap <<= 1; // power-of-two
            this.buf = new int[cap];
            this.capMask = cap - 1;
        }
        boolean offer(int v) {
            final int t = tail + 1;
            // Full if producer would lap consumer
            if ((t - head) > buf.length) return false;
            buf[tail & capMask] = v;
            tail = t;
            return true;
        }
        Integer poll() {
            if (head == tail) return null;
            final int v = buf[head & capMask];
            head++;
            return v;
        }
        int size() { return tail - head; }
        void clear() { head = tail = 0; }
    }
    // Phase-locked PI controller to align scheduled present time to the vsync grid.
// Works in nanoseconds; zero allocations in hot path.
    private static final class PhaseLock {
        private final long vsyncPeriodNs;
        private final long desiredPhaseOffsetNs; // present just before vsync by this guard
        private final double kp, ki;
        private final long slewClampNs;   // clamp for per-frame correction (|u| <= clamp)
        private final long integClampNs;  // clamp for integral term

        private double integ;             // integral accumulator (ns)
        private long lastCorrectionNs;

        PhaseLock(long vsyncPeriodNs, long guardBeforeVsyncNs,
                  double kp, double ki, double slewClampFrac, double integClampFrac) {
            this.vsyncPeriodNs = Math.max(1L, vsyncPeriodNs);
            long guard = Math.max(0, Math.min(guardBeforeVsyncNs, this.vsyncPeriodNs - 100_000L));
            this.desiredPhaseOffsetNs = this.vsyncPeriodNs - guard;
            this.kp = kp;
            this.ki = ki;
            this.slewClampNs = (long) Math.max(50_000L, this.vsyncPeriodNs * Math.abs(slewClampFrac));   // ~3%
            this.integClampNs = (long) Math.max(200_000L, this.vsyncPeriodNs * Math.abs(integClampFrac)); // ~25%
            this.integ = 0.0;
            this.lastCorrectionNs = 0L;

        }
        // Optional feedback from actual render time vs scheduled time.
// Inject a tiny bias into the integral to remove slow drift.
        void onFrameRendered(long scheduledRenderNs, long actualRenderNs) {
            long err = actualRenderNs - scheduledRenderNs; // +late / -early
            double fb = 0.02 * (double) err; // tiny gain
            integ += fb;
            if (integ > integClampNs) integ = integClampNs;
            if (integ < -integClampNs) integ = -integClampNs;
        }

        // Wrap x into [-period/2 .. +period/2]
        private long wrapPhase(long x, long period) {
            long r = x % period;
            if (r < 0) r += period;
            if (r > (period >> 1)) r -= period;
            return r;
        }

        // basePresentNs: your computed target time
        // lastVsyncNs: last Choreographer frameTimeNanos
        // Returns corrected present time.
        long adjust(long basePresentNs, long lastVsyncNs) {
            if (lastVsyncNs == 0L) return basePresentNs;

            long phaseNs = basePresentNs - lastVsyncNs;
            long e = wrapPhase(phaseNs - desiredPhaseOffsetNs, vsyncPeriodNs); // phase error

            double p = kp * (double) e;
            double iCandidate = integ + (ki * (double) e);
            double uCandidate = p + iCandidate;

            // Clamp output
            double uClamped = Math.max(-slewClampNs, Math.min(slewClampNs, uCandidate));

            // Anti-windup: only integrate when not saturating further
            if (uClamped == uCandidate) {
                integ = iCandidate;
                if (integ > integClampNs) integ = integClampNs;
                if (integ < -integClampNs) integ = -integClampNs;
            } else {
                // bleed toward clamped output to reduce bias
                integ += 0.1 * (uClamped - uCandidate);
            }

            lastCorrectionNs = (long) uClamped;
            return basePresentNs - lastCorrectionNs;
        }

        long getLastCorrectionNs() { return lastCorrectionNs; }
        long getDesiredPhaseOffsetNs() { return desiredPhaseOffsetNs; }
    }
    // Map buffer index -> PTS (for balanced queue path)
    private final android.util.SparseLongArray ptsByIndex = new android.util.SparseLongArray(128);

    // Map PTS -> scheduledPresentNs to feed PI loop on actual render callback
    private final android.util.LongSparseArray<Long> scheduledByPtsUs = new android.util.LongSparseArray<>(256);

    // --- Sticky CPU affinity (keep pin alive for whole streaming session) ---
    // We periodically verify that the allowed CPU mask didn't shrink/flip due to cpusets
    // and re-apply pinning to big cores if needed. Lightweight, runs every few seconds.
    private volatile com.limelight.gpu.GpuKickPbuffer gpuKickPbuffer;

    private static final long AFFINITY_REFRESH_NS = 10_000_000_000L; // 10s (was 2s)
    private volatile long lastAffinityRefreshNs = 0L;
    private volatile String lastAllowedMask = null;
    private volatile boolean affinityPinned = false;
      // Latency profile: favor minimal end-to-end delay over absolute smoothness.
    // --- FSR-like upscaler reflection helpers (no hard dependency) ---
    // Derived from AMD FidelityFX Super Resolution 1.0 (MIT). See third_party/amd-fsr1/LICENSE
    private static void __fsrCall(Object upscaler, String method) {
        if (upscaler == null) return;
        try {
            java.lang.reflect.Method m = upscaler.getClass().getMethod(method);
            m.invoke(upscaler);
        } catch (Throwable ignored) {}
    }
    private static Surface __fsrCreateInputSurface(Object upscaler) {
        if (upscaler == null) return null;
        try {
            java.lang.reflect.Method m = upscaler.getClass().getMethod("createDecoderInputSurface");
            Object s = m.invoke(upscaler);
            return (Surface) s;
        } catch (Throwable t) {
            return null;
        }
    }
    private static Object __fsrMaybeCreate(Object existing, Surface windowSurface, int srcW, int srcH, PreferenceConfiguration prefs) {
        if (existing != null) return existing;
        try {
            Class<?> cls = Class.forName("com.limelight.render.GlUpscaleRenderer");
            java.lang.reflect.Constructor<?> c = cls.getConstructor(Surface.class, int.class, int.class, PreferenceConfiguration.class);
            return c.newInstance(windowSurface, srcW, srcH, prefs);
        } catch (Throwable t) {
            LimeLog.warning("GL upscaler unavailable: " + t);
            return null;
        }
    }
    // FSR overlay reflection helpers (appended)
    private static void __fsrSetDebugEnabled(Object upscaler, boolean enabled) {
        if (upscaler == null) return;
        try {
            java.lang.reflect.Method m = upscaler.getClass().getMethod("setFsrDebugEnabled", boolean.class);
            m.invoke(upscaler, enabled);
        } catch (Throwable ignored) {}
    }
    private static String __fsrGetOverlayLine(Object upscaler) {
        if (upscaler == null) return "";
        try {
            java.lang.reflect.Method m = upscaler.getClass().getMethod("getFsrOverlayLine");
            Object s = m.invoke(upscaler);
            return (s != null) ? s.toString() : "";
        } catch (Throwable ignored) { return ""; }
    }
    // Prefix the overlay text with a small, slowly changing number of spaces to nudge its position.
    private static String __applyLiteShift(String text, int spaces) {
        if (text == null || text.isEmpty() || spaces <= 0) return text;
        StringBuilder pfx = new StringBuilder(spaces);
        for (int i = 0; i < spaces; i++) pfx.append(' ');
        return pfx.append(text).toString(); // shift solo prima riga
    }
    // --- end helpers ---
    // --- Frame deadline gating helpers (early drop) ---
    private static long advancePredictedVsync(long predictedNs, long nowNs, long periodNs) {
        if (periodNs <= 0) return nowNs;
        if (predictedNs <= 0) {
            return nowNs + periodNs;
        }
        long delta = nowNs - predictedNs;
        if (delta >= 0) {
            long steps = (delta / periodNs) + 1;
            predictedNs += steps * periodNs;
        }
        return predictedNs;
    }

    private static long computeDeadlineMarginNs(
            long periodNs, double jitterNs, boolean usingDirectPresent, boolean preferLowerDelays) {

        // Base safety margin: tighter for GPU_RAW/Direct Present, looser for GL/compositor paths
        final long base = usingDirectPresent ? 250_000L : 400_000L; // 0.25 ms vs 0.40 ms
        final double scale = preferLowerDelays ? 0.8 : 1.2;

        long margin = base + (long) (Math.max(0.0, jitterNs) * scale);

        final long minMargin = 150_000L; // keep same floor used by present scheduling
        final long maxMargin = periodNs / 3; // don't eat more than ~33% of a frame period

        if (margin < minMargin) margin = minMargin;
        if (margin > maxMargin) margin = maxMargin;

        return margin;
    }

    // Latency profile: favor minimal end-to-end delay over absolute smoothness.
    // Set true to enable a 'latest-only' fast path in the render loop.
    private boolean preferLowerDelays = false;
    // --- HDR state for overlays ---
    private volatile boolean hdrActive = false;
    public boolean isHdrActive() { return hdrActive; }

// Force tight thresholds regardless of device refresh (use vsyncPeriodNs always)
private volatile boolean forceTightThresholds = false;
/** Toggle tight frame pacing thresholds globally. */
public void setForceTightThresholds(boolean v) { this.forceTightThresholds = v; }
// Toggle at runtime if needed
    // Decode latency tracking: map PTS(us) -> enqueue time (ns)
// PTS(us) -> enqueue time (ns), preallocated to avoid frequent resizes
    private final LongSparseArray<Long> enqueueNsByPtsUs = new LongSparseArray<>(256);
    private final Object enqueueNsLock = new Object();


    // Map PTS -> output-dequeue time (ns) to measure post-decode latency accurately
    private final LongSparseArray<Long> dequeueNsByPtsUs = new LongSparseArray<>(512);
    private final Object dequeueNsLock = new Object();

    // Map PTS -> per-frame decoder duration (ms). Saved at dequeue to avoid double counting.
    private final LongSparseArray<Integer> decodeMsByPtsUs = new LongSparseArray<>(512);
    private final Object decodeLock = new Object();
    // When preferLowerDelays = true (PURE LFR/ULL): force non-blocking (0 µs).
// When preferLowerDelays = false (managed): small timeout per profile to stabilize pacing.
    private volatile int preferLowerDelaysTimeoutUs = 0; // default 0 for LFR; policy may override if needed

    public void setPreferLowerDelaysTimeoutUs(int us) {
        this.preferLowerDelaysTimeoutUs = Math.max(0, us); // 0 allowed for LFR
    }

    private int getOutputDequeueTimeoutUs() {
        // PURE LFR (latest-only): use configured timeout (0 µs)
        if (preferLowerDelays) return preferLowerDelaysTimeoutUs;

        if (prefs != null) {
            switch (prefs.framePacing) {
                case PreferenceConfiguration.FRAME_PACING_BALANCED:
                    return 1000;
                case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                    return 2000;
                case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
                    return 1500;
                default:
                    break;
            }
        }
        // Default: small wait to avoid spin on buggy codecs
        return 500;
    }

    // Update stats using real decode time: enqueue->dequeue, instead of uptime - PTS
    private void updateDecodeLatencyStats(long presentationTimeUs) {
        Long enqNs;
        synchronized (enqueueNsLock) {
            enqNs = enqueueNsByPtsUs.get(presentationTimeUs);
            if (enqNs != null) {
                enqueueNsByPtsUs.delete(presentationTimeUs);
            }
        }
        if (enqNs != null) {
            long nowNs = System.nanoTime();
            long decMs = (nowNs - enqNs) / 1_000_000L;
            if (decMs >= 0 && decMs < 1000) {
                activeWindowVideoStats.decoderTimeMs += decMs;
                // Save per-frame decoder duration; postpone adding to total to avoid double counting
                synchronized (decodeLock) {
                    decodeMsByPtsUs.put(presentationTimeUs, (int) decMs);
                    if (decodeMsByPtsUs.size() > 512) decodeMsByPtsUs.removeAt(0);
                }
                // Record dequeue timestamp for this PTS to compute dequeue->render later
                synchronized (dequeueNsLock) {
                    dequeueNsByPtsUs.put(presentationTimeUs, nowNs);
                    if (dequeueNsByPtsUs.size() > 512) dequeueNsByPtsUs.removeAt(0);
                }
            }
        }
    }

    public void setPreferLowerDelays(boolean v) { this.preferLowerDelays = v; }


    // Stats mode
    private static final boolean USE_FRAME_RENDER_TIME = false;
    // When using render-time deltas, drop receive->enqueue from totalTimeMs
    private static final boolean FRAME_RENDER_TIME_ONLY = USE_FRAME_RENDER_TIME;

    // Used on versions < 5.0
    private ByteBuffer[] legacyInputBuffers;

    private MediaCodecInfo avcDecoder;
    private MediaCodecInfo hevcDecoder;
    private MediaCodecInfo av1Decoder;

    private final ArrayList<byte[]> vpsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> spsBuffers = new ArrayList<>();
    private final ArrayList<byte[]> ppsBuffers = new ArrayList<>();
    private boolean submittedCsd;
    private byte[] currentHdrMetadata;

    private int nextInputBufferIndex = -1;
    private ByteBuffer nextInputBuffer;

    private Context context;
    private Activity activity;
    private MediaCodec videoDecoder;
    private Thread rendererThread;
    // CPU warm-up helper (MEDIUM, 8 workers)
    private final com.limelight.perf.CpuWarmUp cpuWarmUp = new com.limelight.perf.CpuWarmUp();

    private boolean needsSpsBitstreamFixup, isExynos4;
    private boolean adaptivePlayback, directSubmit, fusedIdrFrame;
    private boolean constrainedHighProfile;
    private boolean refFrameInvalidationAvc, refFrameInvalidationHevc, refFrameInvalidationAv1;
    private byte optimalSlicesPerFrame;
    private boolean refFrameInvalidationActive;
    private int initialWidth, initialHeight;
    private boolean invertResolution;
    private int videoFormat;
    private Surface renderTarget;
    private volatile boolean stopping;
    private CrashListener crashListener;
    private boolean reportedCrash;
    private int consecutiveCrashCount;
    private String glRenderer;
    private boolean foreground = true;
    private PerfOverlayListener perfListener;
    // ADPF Performance Hint (optional)
    private volatile com.limelight.perf.PerfHint perfHint;

    private long phmWorkStartNs = 0L;
    // Performance Hint Manager session
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

    private MediaFormat inputFormat;
    private MediaFormat outputFormat;
    private MediaFormat configuredFormat;

    private boolean needsBaselineSpsHack;
    private SeqParameterSet savedSps;

    private RendererException initialException;
    private long initialExceptionTimestamp;
    private static final int EXCEPTION_REPORT_DELAY_MS = 3000;

    private VideoStats activeWindowVideoStats;
    private VideoStats lastWindowVideoStats;
    private VideoStats globalVideoStats;

    private long lastTimestampUs;
    private int lastFrameNumber;
    private int refreshRate;
    // --- dPLL state (vsync phase align) ---
    private PhaseLock phaseLock;
    private volatile long lastVsyncNs = 0L; // updated in doFrame()

    private PreferenceConfiguration prefs;

    private float minDecodeTime = Float.MAX_VALUE;
    private String minDecodeTimeFullLog = "";

    private long lastNetDataNum;
    // Output buffer queue for frames waiting for Choreographer/vsync
// Cap at 3 so we never accumulate too many frames if consumer is late
    private final SpscRing outputBufferQueue = new SpscRing(3);

    private static final int OUTPUT_BUFFER_QUEUE_LIMIT_BALANCED = 2;
    private static final int OUTPUT_BUFFER_QUEUE_LIMIT_MAX_SMOOTHNESS = 3;
    private static final int OUTPUT_BUFFER_QUEUE_LIMIT_LL = 1;
    private long lastRenderedFrameTimeNanos;
    private HandlerThread choreographerHandlerThread;
    private Handler choreographerHandler;

    private int numSpsIn;
    private int numPpsIn;
    private int numVpsIn;
    private int numFramesIn;
    private int numFramesOut;

    private float targetFps = 0f; // 0f = auto
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
            MediaCodecInfo.VideoCapabilities.PerformancePoint targetPerfPoint = new MediaCodecInfo.VideoCapabilities.PerformancePoint(initialWidth, initialHeight, Math.round(prefs.fps));
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
                Range<Double> fpsRange = caps.getAchievableFrameRatesFor(initialWidth, initialHeight);
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
        return caps.areSizeAndRateSupported(initialWidth, initialHeight, prefs.fps);
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
                else if (initialWidth > 4096 || initialHeight > 4096) {
                    LimeLog.info("Forcing HEVC enabled for over 4K streaming");
                }
                // Use HEVC if the H.264 decoder is unable to meet the performance point
                else if (avcDecoder != null && decoderCanMeetPerformancePointWithHevcAndNotAvc(hevcDecoderInfo, avcDecoder, prefs)) {
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
                else if (hevcDecoder != null && decoderCanMeetPerformancePointWithAv1AndNotHevc(decoderInfo, hevcDecoder, prefs)) {
                    LimeLog.info("Using non-whitelisted AV1 decoder to meet performance point");
                }
                // Use AV1 if the H.264 decoder is unable to meet the performance point and we have no HEVC decoder
                else if (hevcDecoder == null && decoderCanMeetPerformancePointWithAv1AndNotAvc(decoderInfo, avcDecoder, prefs)) {
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
            if (decoderInputSurfaceForUpscale != null) { try { decoderInputSurfaceForUpscale.release(); } catch (Throwable ignored) {} decoderInputSurfaceForUpscale = null; }
        }
        this.renderTarget = renderTarget;

        // Re-apply presentation hint to upscaler when render target may change
        try { if (glUpscaler != null) {
            java.lang.reflect.Method __m = glUpscaler.getClass().getMethod("setPresentationSizeHintFromContext", android.content.Context.class);
            __m.invoke(glUpscaler, context);
        } } catch (Throwable ignored) {}
}

    public MediaCodecDecoderRenderer(Activity activity, PreferenceConfiguration prefs,
                                     CrashListener crashListener, int consecutiveCrashCount,
                                     boolean meteredData, boolean requestedHdr, boolean invertResolution,
                                     String glRenderer, PerfOverlayListener perfListener) {
        //dumpDecoders();

        this.context = activity;
        this.activity = activity;
        this.prefs = prefs;
        this.crashListener = crashListener;
        this.consecutiveCrashCount = consecutiveCrashCount;
        this.glRenderer = glRenderer;
        this.perfListener = perfListener;
        this.invertResolution = invertResolution;

        this.activeWindowVideoStats = new VideoStats();
        this.lastWindowVideoStats = new VideoStats();
        this.globalVideoStats = new VideoStats();

        avcDecoder = findAvcDecoder();
        if (avcDecoder != null) {
            LimeLog.info("Selected AVC decoder: "+avcDecoder.getName());
        }
        else {
            LimeLog.warning("No AVC decoder found");
        }

        hevcDecoder = findHevcDecoder(prefs, meteredData, requestedHdr);
        if (hevcDecoder != null) {
            LimeLog.info("Selected HEVC decoder: "+hevcDecoder.getName());
        }
        else {
            LimeLog.info("No HEVC decoder found");
        }

        av1Decoder = findAv1Decoder(prefs);
        if (av1Decoder != null) {
            LimeLog.info("Selected AV1 decoder: "+av1Decoder.getName());
        }
        else {
            LimeLog.info("No AV1 decoder found");
        }

        // Set attributes that are queried in getCapabilities(). This must be done here
        // because getCapabilities() may be called before setup() in current versions of the common
        // library. The limitation of this is that we don't know whether we're using HEVC or AVC.
        int avcOptimalSlicesPerFrame = 0;
        int hevcOptimalSlicesPerFrame = 0;
        if (avcDecoder != null) {
            directSubmit = MediaCodecHelper.decoderCanDirectSubmit(avcDecoder.getName());
            refFrameInvalidationAvc = MediaCodecHelper.decoderSupportsRefFrameInvalidationAvc(avcDecoder.getName(), initialHeight);
            avcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(avcDecoder.getName());

            if (directSubmit) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use direct submit");
            }
            if (refFrameInvalidationAvc) {
                LimeLog.info("Decoder "+avcDecoder.getName()+" will use reference frame invalidation for AVC");
            }
            LimeLog.info("Decoder "+avcDecoder.getName()+" wants "+avcOptimalSlicesPerFrame+" slices per frame");
        }

        if (hevcDecoder != null) {
            refFrameInvalidationHevc = MediaCodecHelper.decoderSupportsRefFrameInvalidationHevc(hevcDecoder);
            hevcOptimalSlicesPerFrame = MediaCodecHelper.getDecoderOptimalSlicesPerFrame(hevcDecoder.getName());

            if (refFrameInvalidationHevc) {
                LimeLog.info("Decoder "+hevcDecoder.getName()+" will use reference frame invalidation for HEVC");
            }

            LimeLog.info("Decoder "+hevcDecoder.getName()+" wants "+hevcOptimalSlicesPerFrame+" slices per frame");
        }

        if (av1Decoder != null) {
            refFrameInvalidationAv1 = MediaCodecHelper.decoderSupportsRefFrameInvalidationAv1(av1Decoder);

            if (refFrameInvalidationAv1) {
                LimeLog.info("Decoder "+av1Decoder.getName()+" will use reference frame invalidation for AV1");
            }
        }

        // Use the larger of the two slices per frame preferences
        optimalSlicesPerFrame = (byte)Math.max(avcOptimalSlicesPerFrame, hevcOptimalSlicesPerFrame);
        LimeLog.info("Requesting "+optimalSlicesPerFrame+" slices per frame");

        if (consecutiveCrashCount % 2 == 1) {
            refFrameInvalidationAvc = refFrameInvalidationHevc = false;
            LimeLog.warning("Disabling RFI due to previous crash");
        }
    }

    public boolean isHevcSupported() {
        return hevcDecoder != null;
    }

    public boolean isAvcSupported() {
        return avcDecoder != null;
    }

    public boolean isHevcMain10Hdr10Supported() {
        if (hevcDecoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : hevcDecoder.getCapabilitiesForType("video/hevc").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                LimeLog.info("HEVC decoder "+hevcDecoder.getName()+" supports HEVC Main10 HDR10");
                return true;
            }
        }

        return false;
    }

    public boolean isAv1Supported() {
        return av1Decoder != null;
    }

    public boolean isAv1Main10Supported() {
        if (av1Decoder == null) {
            return false;
        }

        for (MediaCodecInfo.CodecProfileLevel profileLevel : av1Decoder.getCapabilitiesForType("video/av01").profileLevels) {
            if (profileLevel.profile == MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10) {
                LimeLog.info("AV1 decoder "+av1Decoder.getName()+" supports AV1 Main 10 HDR10");
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || hevcDecoder != null || av1Decoder != null) {
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
        MediaFormat videoFormat = MediaFormat.createVideoFormat(mimeType, initialWidth, initialHeight);

        // Avoid setting KEY_FRAME_RATE on Lollipop and earlier to reduce compatibility risk
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, refreshRate);
        }

        // Populate keys for adaptive playback
        if (adaptivePlayback) {
            videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, initialWidth);
            videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, initialHeight);
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
            if (currentHdrMetadata != null) {
                ByteBuffer hdrStaticInfo = ByteBuffer.allocate(25).order(ByteOrder.LITTLE_ENDIAN);
                ByteBuffer hdrMetadata = ByteBuffer.wrap(currentHdrMetadata).order(ByteOrder.LITTLE_ENDIAN);

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

        // If FSR-like upscaling is enabled, configure decoder to output to GL upscaler input surface
        Surface __codecSurface = renderTarget;
        if (prefs != null && prefs.videoUpscaleEnable) {
            try {
                if (glUpscaler == null) {
                    glUpscaler = __fsrMaybeCreate((Object) glUpscaler, renderTarget, initialWidth, initialHeight, prefs);
                    decoderInputSurfaceForUpscale = __fsrCreateInputSurface(glUpscaler);
                    // Provide presentation-size hint from Context if available
                    try {
                        java.lang.reflect.Method m = glUpscaler.getClass().getMethod("setPresentationSizeHintFromContext", android.content.Context.class);
                        m.invoke(glUpscaler, context);
                    } catch (Throwable ignored) {}
}
                __codecSurface = decoderInputSurfaceForUpscale;
                if (__codecSurface == null) {
                    __codecSurface = renderTarget;
                }
            } catch (Throwable t) {
                LimeLog.warning("GL upscaler init failed; falling back: " + t);
                try { if (glUpscaler != null) __fsrCall(glUpscaler, "release"); } catch (Throwable ignored) {}
                glUpscaler = null;
                decoderInputSurfaceForUpscale = null;
                __codecSurface = renderTarget;
            }
        }
        videoDecoder.configure(format, __codecSurface, null, 0);

        // Start GL upscaler loop if present
        try { if (glUpscaler != null) __fsrCall(glUpscaler, "start"); } catch (Throwable ignored) {}
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



try {
    MediaCodecInfo __info = (android.os.Build.VERSION.SDK_INT >= 21) ? videoDecoder.getCodecInfo() : null;
    String __name = (__info != null) ? __info.getName() : "<unknown>";
    LimeLog.info("Decoder name: " + __name);
} catch (Throwable t) {
    LimeLog.info("Decoder name: <unavailable>");
}


        configuredFormat = format;

        // After reconfiguration, we must resubmit CSD buffers
        submittedCsd = false;
        vpsBuffers.clear();
        spsBuffers.clear();
        ppsBuffers.clear();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // This will contain the actual accepted input format attributes
            inputFormat = videoDecoder.getInputFormat();
            LimeLog.info("Input format: "+inputFormat);
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
            legacyInputBuffers = videoDecoder.getInputBuffers();
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
            selectedDecoderInfo = avcDecoder;

            if (avcDecoder == null) {
                LimeLog.severe("No available AVC decoder!");
                return -1;
            }

            if (initialWidth > 4096 || initialHeight > 4096) {
                LimeLog.severe("> 4K streaming only supported on HEVC");
                return -1;
            }

            // These fixups only apply to H264 decoders
            needsSpsBitstreamFixup = MediaCodecHelper.decoderNeedsSpsBitstreamRestrictions(selectedDecoderInfo.getName());
            needsBaselineSpsHack = MediaCodecHelper.decoderNeedsBaselineSpsHack(selectedDecoderInfo.getName());
            constrainedHighProfile = MediaCodecHelper.decoderNeedsConstrainedHighProfile(selectedDecoderInfo.getName());
            isExynos4 = MediaCodecHelper.isExynos4Device();
            if (needsSpsBitstreamFixup) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs SPS bitstream restrictions fixup");
            }
            if (needsBaselineSpsHack) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs baseline SPS hack");
            }
            if (constrainedHighProfile) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" needs constrained high profile");
            }
            if (isExynos4) {
                LimeLog.info("Decoder "+selectedDecoderInfo.getName()+" is on Exynos 4");
            }

            refFrameInvalidationActive = refFrameInvalidationAvc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
            mimeType = "video/hevc";
            selectedDecoderInfo = hevcDecoder;

            if (hevcDecoder == null) {
                LimeLog.severe("No available HEVC decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationHevc;
        }
        else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
            mimeType = "video/av01";
            selectedDecoderInfo = av1Decoder;

            if (av1Decoder == null) {
                LimeLog.severe("No available AV1 decoder!");
                return -2;
            }

            refFrameInvalidationActive = refFrameInvalidationAv1;
        }
        else {
            // Unknown format
            LimeLog.severe("Unknown format");
            return -3;
        }
        adaptivePlayback = MediaCodecHelper.decoderSupportsAdaptivePlayback(selectedDecoderInfo, mimeType);
        fusedIdrFrame = MediaCodecHelper.decoderSupportsFusedIdrFrame(selectedDecoderInfo, mimeType);

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoDecoder.setOnFrameRenderedListener(new MediaCodec.OnFrameRenderedListener() {
                @Override
                public void onFrameRendered(MediaCodec mediaCodec, long presentationTimeUs, long renderTimeNanos) {
                    // Optional stats
                    // Compute device-side post-decode latency using dequeue timestamp when available.
                    long postDecodeMs = -1L;
                    Long deqNs = null;
                    Long enqNs = null;
                    Integer decMsSaved = null;
                    try {
                        synchronized (dequeueNsLock) {
                            Long v = dequeueNsByPtsUs.get(presentationTimeUs);
                            if (v != null) {
                                deqNs = v;
                                dequeueNsByPtsUs.delete(presentationTimeUs);
                            }
                        }
                        synchronized (enqueueNsLock) {
                            Long v2 = enqueueNsByPtsUs.get(presentationTimeUs);
                            if (v2 != null) { enqNs = v2; }
                        }
                        synchronized (decodeLock) {
                            Integer v3 = decodeMsByPtsUs.get(presentationTimeUs);
                            if (v3 != null) {
                                decMsSaved = v3;
                                decodeMsByPtsUs.delete(presentationTimeUs);
                            }
                        }
                    } catch (Throwable ignored) { /* best-effort */ }

                    if (deqNs != null) {
                        postDecodeMs = (renderTimeNanos - deqNs) / 1_000_000L; // dequeue -> render
                        if (postDecodeMs >= 0 && postDecodeMs < 1000) {
                            activeWindowVideoStats.totalTimeMs += postDecodeMs;
                        }
                        // Add decoder portion once here
                        if (decMsSaved != null && decMsSaved >= 0 && decMsSaved < 1000) {
                            activeWindowVideoStats.totalTimeMs += decMsSaved;
                        } else if (enqNs != null) {
                            long decGuess = (deqNs - enqNs) / 1_000_000L;
                            if (decGuess >= 0 && decGuess < 1000) {
                                activeWindowVideoStats.totalTimeMs += decGuess;
                            }
                        }
                    } else if (enqNs != null) {
                        // Fallback: enqueue -> render (already includes decode + post-decode)
                        long combinedMs = (renderTimeNanos - enqNs) / 1_000_000L;
                        if (combinedMs >= 0 && combinedMs < 1000) {
                            activeWindowVideoStats.totalTimeMs += combinedMs;
                        }
                        // Do NOT add decMsSaved here (would double count)
                    }

                    // Feed PI loop with actual vs scheduled time (always on)
                    try {
                        Long scheduledNs = null;
                        synchronized (scheduledByPtsUs) {
                            Long v = scheduledByPtsUs.get(presentationTimeUs);
                            if (v != null) {
                                scheduledNs = v;
                                scheduledByPtsUs.remove(presentationTimeUs);
                            }
                        }
                        if (scheduledNs != null && phaseLock != null) {
                            phaseLock.onFrameRendered(scheduledNs, renderTimeNanos);
                        }
                    } catch (Throwable ignored) { /* best-effort */ }
                }
            }, null);
        }

        return 0;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.targetFps = (redrawRate > 0 ? (float) redrawRate : 60f);
        this.initialWidth = invertResolution ? height : width;
        this.initialHeight = invertResolution ? width : height;
        this.videoFormat = format;
        this.refreshRate = redrawRate;

// Init PI dPLL for vsync phase alignment
        initPhaseLockIfNeeded();

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
// Input and output buffers are invalidated by stop() and reset().
                nextInputBuffer = null;
                nextInputBufferIndex = -1;
                outputBufferQueue.clear();
// Also drop decode-latency entries tied to the old codec instance
                synchronized (enqueueNsLock) {
                    enqueueNsByPtsUs.clear();
                    synchronized (dequeueNsLock) { dequeueNsByPtsUs.clear(); }
                    synchronized (decodeLock) { decodeMsByPtsUs.clear(); }
                }

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
                        configureAndStartDecoder(configuredFormat);
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
                        configureAndStartDecoder(configuredFormat);
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
                        if (!reportedCrash) {
                            reportedCrash = true;
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
            if (initialException != null) {
                // This isn't the first time we've had an exception processing video
                if (SystemClock.uptimeMillis() - initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    // It's been over 3 seconds and we're still getting exceptions. Throw the original now.
                    if (!reportedCrash) {
                        reportedCrash = true;
                        crashListener.notifyCrash(initialException);
                    }
                    throw initialException;
                }
            }
            else {
                // This is the first exception we've hit
                initialException = new RendererException(this, e);
                initialExceptionTimestamp = SystemClock.uptimeMillis();
            }
        }

        // Not transient
        return false;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // Do nothing if we're stopping
        if (stopping) {
            return;
        }

        // Reduces phase error on some devices. Wrapped in try/catch to avoid vendor quirks.
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) context.getSystemService(android.content.Context.WINDOW_SERVICE);
            if (wm != null) {
                android.view.Display d = wm.getDefaultDisplay();
                if (d != null) {
                    // getAppVsyncOffsetNanos() may not exist on some builds; keep it guarded
                    long appOffsetNs = 0L;
                    try { appOffsetNs = (Long) android.view.Display.class
                            .getMethod("getAppVsyncOffsetNanos")
                            .invoke(d); } catch (Throwable ignored) {}
// Apply app vsync offset first, then keep a coherent last vsync for the PI loop
                    frameTimeNanos -= appOffsetNs;
                    lastVsyncNs = frameTimeNanos; // keep same reference frame for PLL
                }
            }
        } catch (Throwable ignored) {}

        // If AdaptX is active, we only use Choreographer to feed lastVsyncNs and recovery.
        // Skip dequeue/present from this callback; AdaptX presents from the renderer loop.
        if (prefs != null && prefs.framePacing == com.limelight.preferences.PreferenceConfiguration.FRAME_PACING_ADAPTX) {
            doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        // ---- Balanced-only: Choreographer does actual present; other profiles render from their own loop ----
        int pacing = (prefs != null) ? prefs.framePacing
                : com.limelight.preferences.PreferenceConfiguration.FRAME_PACING_BALANCED;

        if (pacing != com.limelight.preferences.PreferenceConfiguration.FRAME_PACING_BALANCED) {
            // Non-Balanced profiles (GPU_RAW, Cap FPS, Max Smoothness, AdaptX renderer, etc.)
            // rely on the renderer thread for present. Here we only keep codec recovery alive.
            doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);
            Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        // ---- Compute display period and gating threshold for Balanced profile ----
        final int rr = (refreshRate > 0) ? refreshRate : 60;
        final long periodNs = 1_000_000_000L / Math.max(1, rr);

        // Gate percentage per profile:
        // - GPU_RAW: render exactly once per period (tight = 100%)
        // - Balanced: ~80% (smooth, avoids double render in the same slot)
        // - Cap FPS: ~85% (a bit more conservative)
        // - Max Smoothness: ~90% (longer gate, favors stability over reactivity)
        // - Warp, Warp 2, Lowest Latency: render exactly once per period (tight = 100%)
        double gatePct;
        if (preferLowerDelays || pacing == com.limelight.preferences.PreferenceConfiguration.FRAME_PACING_GPU_RAW) {
            gatePct = 1.00; // tight
        } else if (pacing == com.limelight.preferences.PreferenceConfiguration.FRAME_PACING_BALANCED) {
            gatePct = 0.80;
        } else if (pacing == com.limelight.preferences.PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            gatePct = 0.85;
        } else if (pacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS) {
            gatePct = 0.90;
        } else { // others: Warp, Warp2, Lower Latency
            gatePct = 1.00;
        }
        final long gateNs = (long) (periodNs * gatePct);

        // ---- Sanity: fix absurd deltas on first tick or timebase jumps ----
        long actualFrameTimeDeltaNs = frameTimeNanos - lastRenderedFrameTimeNanos;
        if (lastRenderedFrameTimeNanos != 0L) {
            if (actualFrameTimeDeltaNs < 0 || actualFrameTimeDeltaNs > 250_000_000L) { // >250 ms not realistic
                lastRenderedFrameTimeNanos = frameTimeNanos - periodNs;
                actualFrameTimeDeltaNs = frameTimeNanos - lastRenderedFrameTimeNanos;
            }
        } else {
            // First frame after start/resume: seed the reference to one period earlier
            lastRenderedFrameTimeNanos = frameTimeNanos - periodNs;
            actualFrameTimeDeltaNs = periodNs;
        }

        // ---- Render gating: skip if we're still inside the gate for this vsync slot ----
        if (actualFrameTimeDeltaNs < gateNs) {
            // Close any open ADPF interval cleanly (no work this slot)
            if (this.perfHint != null && this.perfHint.isActive() && this.phmWorkStartNs != 0L) {
                try { this.perfHint.tockAndReport(this.phmWorkStartNs); } catch (Throwable ignored) {}
                this.phmWorkStartNs = 0L;
            }
            // Attempt codec recovery even if we don't render
            doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);
            // Request next frame
            android.view.Choreographer.getInstance().postFrameCallback(this);
            return;
        }

        // ---- Mark start of CPU work for this frame (ADPF) ----
        if (this.perfHint != null) {
            this.phmWorkStartNs = com.limelight.perf.PerfHint.tick();
        }

        // ---- Render up to one frame when in frame pacing mode ----
        // NB: With queue limit 2, we won't starve the decoder. One extra frame smooths jitter.
        Integer nextOutputBuffer = outputBufferQueue.poll();
        if (nextOutputBuffer != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 21) {
                    long tsNs = frameTimeNanos;
                    if (phaseLock != null && lastVsyncNs != 0L) {
                        tsNs = phaseLock.adjust(tsNs, lastVsyncNs);
                    }

// Record scheduled time for this frame (by PTS) to feed PLL on actual render
                    try {
                        long ptsUs;
                        synchronized (ptsByIndex) {
                            ptsUs = ptsByIndex.get(nextOutputBuffer, -1);
                            if (ptsUs != -1L) {
                                ptsByIndex.delete(nextOutputBuffer);
                            }
                        }
                        if (ptsUs != -1L) {
                            synchronized (scheduledByPtsUs) {
                                scheduledByPtsUs.put(ptsUs, tsNs);
                                if (scheduledByPtsUs.size() > 256) {
                                    scheduledByPtsUs.removeAt(0);
                                }
                            }
                        }

                    } catch (Throwable ignored) {}

                    videoDecoder.releaseOutputBuffer(nextOutputBuffer, tsNs);

                } else {
                    // Legacy immediate render
                    videoDecoder.releaseOutputBuffer(nextOutputBuffer, true);
                }


                gpuKickPresentHook();

                lastRenderedFrameTimeNanos = frameTimeNanos;
                activeWindowVideoStats.totalFramesRendered++;
            } catch (IllegalStateException e) {
                try { handleDecoderException(e); } catch (Throwable ignored) {}
                try { videoDecoder.releaseOutputBuffer(nextOutputBuffer, false); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {
                try { videoDecoder.releaseOutputBuffer(nextOutputBuffer, false); } catch (Throwable ignored2) {}
            } finally {
                // Close ADPF interval on success/error
                if (this.perfHint != null && this.perfHint.isActive() && this.phmWorkStartNs != 0L) {
                    try { this.perfHint.tockAndReport(this.phmWorkStartNs); } catch (Throwable ignored) {}
                    this.phmWorkStartNs = 0L;
                }
            }
        } else {
            // No buffer this vsync: close ADPF interval to avoid bogus long work durations
            if (this.perfHint != null && this.perfHint.isActive() && this.phmWorkStartNs != 0L) {
                try { this.perfHint.tockAndReport(this.phmWorkStartNs); } catch (Throwable ignored) {}
                this.phmWorkStartNs = 0L;
            }
        }

        // Attempt codec recovery even if we have nothing to render right now. Recovery can still
        // be required even if the codec died before giving any output.
        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);

        // Request another callback for next frame
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void startChoreographerThread() {
        if (prefs == null || (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED
                && prefs.framePacing != PreferenceConfiguration.FRAME_PACING_ADAPTX)) {
            // Not using Choreographer in this pacing mode
            return;
        }

        // We use a separate thread to avoid any main thread delays from delaying rendering
        choreographerHandlerThread = new HandlerThread("Video - Choreographer", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        choreographerHandlerThread.start();

        // Start the frame callbacks
        choreographerHandler = new Handler(choreographerHandlerThread.getLooper());
        choreographerHandler.post(new Runnable() {
            @Override
            public void run() {
                Choreographer.getInstance().postFrameCallback(MediaCodecDecoderRenderer.this);
            }
        });
    }

    private void startRendererThread()
    {
        rendererThread = new Thread() {
            @Override
            public void run() {
                // Boost thread priority to reduce decoding latency
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
//* Pin hot threads to big cluster *//
                // Give the renderer thread a recognizable name for /proc and debugging
                try { Thread.currentThread().setName("MoonlightRenderer"); } catch (Throwable ignored) {}
                // --- GPU Kick (adaptive, headless when GL path not used) ---
                final boolean wantGpuKick = (prefs != null && prefs.enableGpuKick);
                boolean usingDirectPresent = false;
                try {
                    usingDirectPresent =
                            (prefs != null && (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW))
                                    || (prefs != null && prefs.gpuPathMode);
                } catch (Throwable ignored) {}

                if (wantGpuKick && android.os.Build.VERSION.SDK_INT >= 17) {
                    // In Direct Present path the app doesn't render via GL; use a headless pbuffer
                    if (usingDirectPresent) {
                        try {
                            gpuKickPbuffer = new com.limelight.gpu.GpuKickPbuffer();
                            gpuKickPbuffer.setEnabled(true);
                            gpuKickPbuffer.initOnThisThread(); // create EGL pbuffer on this thread
                            LimeLog.info("GpuKickPbuffer: initialized (Direct Present)");
                        } catch (Throwable t) {
                            gpuKickPbuffer = null;
                            try { LimeLog.info("GpuKickPbuffer: init failed, disabled: " + t); } catch (Throwable ignored) {}
                        }
                    }
                }

// Track DP state to adapt at runtime
                boolean dpLast = usingDirectPresent;

                // Log TID and current affinity
                try {
                    int tid = android.os.Process.myTid();
                    String allowedBefore = com.limelight.utils.CpuAffinity.readAllowedCpuListForCurrentThread();
                    LimeLog.info("RendererAffinity: tid=" + tid
                            + " allowed_before=" + allowedBefore
                            + " preferBigCores=" + (prefs != null && prefs.preferBigCores));
                } catch (Throwable ignored) {}

                // Best-effort pinning to big cores
                try {
                    if (prefs != null && prefs.preferBigCores) {
                        try {
                            com.limelight.utils.CpuAffinity.pinCurrentThreadToBigCoresIf(true);
                        } catch (Throwable ignored) {}
                        try {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                        } catch (Throwable ignored) {}

                        try {
                            int[] big = com.limelight.utils.CpuAffinity.detectBigCores();
                            if (big != null && big.length > 0) {
                                int[] tids = com.limelight.utils.CpuAffinity.listTids();
                                for (int tid : tids) {
                                    String name = com.limelight.utils.CpuAffinity.readThreadName(tid);
                                    if (name == null) name = "";

                                    // Do not touch Binder/HwBinder
                                    if (name.startsWith("Binder:") || name.startsWith("HwBinder:")) {
                                        continue;
                                    }

                                    boolean isCodec = name.contains("CodecCb") || name.contains("MediaCodec")
                                            || name.contains("CCodec") || name.contains("CodecLooper");
                                    boolean isGL = name.contains("GLThread") || name.contains("RenderThread") || name.contains("Renderer");
                                    boolean isChor = name.contains("Choreographer");

                                    if (!(isCodec || isGL || isChor)) {
                                        continue;
                                    }

                                    int prio = isChor
                                            ? android.os.Process.THREAD_PRIORITY_DISPLAY
                                            : android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY;

                                    try { android.os.Process.setThreadPriority(tid, prio); } catch (Throwable ignored) {}
                                    try { com.limelight.utils.CpuAffinity.setAffinityForTid(tid, big); } catch (Throwable ignored) {}
                                }
                                try { com.limelight.utils.CpuAffinity.startAffinityWatcherWithFixedDelay(5000L); } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
// Log what we tried to set (native detection) + the kernel result
                        int[] bigNative = com.limelight.utils.CpuAffinity.detectBigCoresForDebug();
                        String allowedAfter = com.limelight.utils.CpuAffinity.readAllowedCpuListForCurrentThread();
                        LimeLog.info("RendererAffinity: nativeLoaded=" + com.limelight.utils.CpuAffinity.isNativeLoaded()
                                + " big_native=" + java.util.Arrays.toString(bigNative)
                                + " allowed_after=" + allowedAfter);

                        MediaCodecDecoderRenderer.this.lastAllowedMask = allowedAfter;
                        MediaCodecDecoderRenderer.this.affinityPinned = true;
                        MediaCodecDecoderRenderer.this.lastAffinityRefreshNs = android.os.SystemClock.elapsedRealtimeNanos();

                        int currentCpu = com.limelight.utils.CpuAffinity.getCurrentCpuOrMinus1();
                        LimeLog.info("RendererAffinity: current_cpu=" + currentCpu);
                    }
                } catch (Throwable ignored) {}

// ADPF / PerfHint (API 31+)
                if (android.os.Build.VERSION.SDK_INT >= 31
                        && context != null
                        && prefs != null
                        && prefs.enablePerfHints
                        && com.limelight.perf.PerfHint.isAdpfAvailable(context)) {
                    try {
                        final double fps = Math.max(1.0, (targetFps > 0f ? (double) targetFps : 60.0));
                        final long framePeriodNs = (long) (1_000_000_000.0 / fps);
                        final boolean gpuRaw =
                                (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW);

                        // Renderer wants a tighter target when GPU_RAW is on
                        final long targetWorkNs = gpuRaw
                                ? Math.max(6_000_000L, (long) (framePeriodNs * 0.90))
                                : Math.max(1_000_000L, (long) (framePeriodNs * 0.60));

                        // safe: renderer-only, CpuAffinity already pins the rest
                        MediaCodecDecoderRenderer.this.perfHint =
                                com.limelight.perf.PerfHint.createForCurrentThread(
                                        context,
                                        targetWorkNs
                                );

                        if (MediaCodecDecoderRenderer.this.perfHint != null) {
                            try {
                                MediaCodecDecoderRenderer.this.perfHint.updateTarget(targetWorkNs);
                            } catch (Throwable ignored) {}
                            try {
                                // during gameplay we want performance, not power saving
                                MediaCodecDecoderRenderer.this.perfHint.setPreferPowerEfficiency(false);
                            } catch (Throwable ignored) {}
                            // optional: debug current state
                            MediaCodecDecoderRenderer.this.perfHint.dumpToLog("PHM-Renderer");
                            try {
                                if (prefs != null && prefs.cpuWarmUpEnable && !prefs.cpuWarmUpOverridePerfHint) {
                                    // Once ADPF is active for this session, stop the warm-up to avoid fighting it.
                                    cpuWarmUp.stop();
                                }
                            } catch (Throwable ignored) {}

                        }
                    } catch (Throwable ignored) {
                        // ADPF not available or failed -> no-op
                    }
                }
//* Pin hot threads to big cluster *//

                // Compute display refresh and vsync period once (fallback 60 Hz if unavailable)
                long vsyncPeriodNs;
                float displayHz = 60f;
                try {
                    if (Build.VERSION.SDK_INT >= 17 && context != null) {
                        android.view.Display d = ((android.view.WindowManager) context.getSystemService(android.content.Context.WINDOW_SERVICE)).getDefaultDisplay();
                        if (d != null) displayHz = d.getRefreshRate();
                    }
                } catch (Throwable ignored) {}
                if (displayHz <= 0f) displayHz = 60f;
                vsyncPeriodNs = (long) (1_000_000_000L / displayHz);

                // Stream cadence (targetFps set in setup(...))
                final float tfps = (targetFps > 0f ? targetFps : 60f);
                final long streamPeriodNs = (long) (1_000_000_000.0 / Math.max(1f, tfps));
                /* ADPF: set target based on normalized stream period; single, stable update */
                if (MediaCodecDecoderRenderer.this.perfHint != null
                        && MediaCodecDecoderRenderer.this.perfHint.isActive()) {
                    final boolean gpuRaw = (prefs != null
                            && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW);
                    final long targetNs = gpuRaw
                            ? Math.max(6_000_000L, (long) (streamPeriodNs * 0.90))
                            : Math.max(1_000_000L, (long) (streamPeriodNs * 0.60));
                    try { MediaCodecDecoderRenderer.this.perfHint.updateTarget(targetNs); } catch (Throwable ignored) {}
                }


                // Adaptive period selection to avoid added latency on high-refresh devices
                final boolean highRefresh = displayHz >= 90f;
                final boolean managedMode = (prefs != null && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED);
                // Use stream-aligned thresholds only on lower-refresh screens while in Balanced.
                final long periodNs = forceTightThresholds
                        ? vsyncPeriodNs
                        : ((managedMode && !highRefresh) ? Math.max(vsyncPeriodNs, streamPeriodNs) : vsyncPeriodNs);
boolean isC2Decoder = false;
                try {
                    String decName = videoDecoder.getName();
                    if (decName != null) {
                        isC2Decoder = decName.toLowerCase(java.util.Locale.US).startsWith("c2.");
                    }
                } catch (Throwable ignored) {}

                // Aggressive/adaptive state
                final double MIN_FACTOR = 1.00;
                final double MAX_FACTOR = 1.20;

                long   lastDecoderPtsUs  = 0L;
                long   lastPresentNs     = 0L;
                long   lastDropNs        = 0L;
                int    lateStreak        = 0;
                int    tryAgainStreak    = 0;
                int    recentDrops       = 0;
                // --- Frame deadline gating (early drop) ---
                final int deadlineMissHystFrac = 8; // clear miss if later than period/8
                long predictedVsyncNs = 0L;

// --- Robust Quantile Hybrid (RQH) state ---
// Keep naming compatible with old IJH usage where possible
                final double IJH_INST_WEIGHT = 0.60;  // instant deviation weight (0..1)
                final double IJH_PCTL       = 0.80;   // target quantile of inter-arrival deviations
                final double expectedInterNs = (double) streamPeriodNs; // cadence from stream FPS

// Start with ~10% of period as initial jitter guess
                double ijhJitterNs = (managedMode ? (expectedInterNs * 0.12) : (expectedInterNs * 0.08));

// Online quantile estimator (no arrays, no per-loop sort)
                final EWQuantile ijhQuant = new EWQuantile(
                        IJH_PCTL,
                        Math.max(expectedInterNs * 0.05, 1_000_000.0), // init ~5% period, >=1 ms
                        0.24,   // alphaUp   (faster rise on bursts)
                        0.04    // alphaDn   (slower decay to avoid flapping)
                );

// Reused BufferInfo objects
                final android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
                final android.media.MediaCodec.BufferInfo latestInfo = new android.media.MediaCodec.BufferInfo();

                boolean phmGpuRawLast = (prefs != null
                        && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW);

                while (!stopping) {
                    // Start ADPF work interval for non-Choreographer paths
                    if (MediaCodecDecoderRenderer.this.perfHint != null
                            && MediaCodecDecoderRenderer.this.perfHint.isActive()
                            && MediaCodecDecoderRenderer.this.phmWorkStartNs == 0L) {
                        MediaCodecDecoderRenderer.this.phmWorkStartNs = com.limelight.perf.PerfHint.tick();
                    }

                    // Snapshot prefs once per loop (correct outer reference)
                    final PreferenceConfiguration p = MediaCodecDecoderRenderer.this.prefs;

                    // Runtime disable -> tear down
                    if (gpuKickPbuffer != null && p != null && !p.enableGpuKick) {
                        try { gpuKickPbuffer.release(); } catch (Throwable ignored) {}
                        gpuKickPbuffer = null;
                    }

                    // Runtime DP change -> reinit / release
                    if (p != null && p.enableGpuKick && android.os.Build.VERSION.SDK_INT >= 17) {
                        boolean dpNow =
                                (p.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW)
                                        || p.gpuPathMode;
                        if (dpNow != dpLast) {
                            try {
                                if (gpuKickPbuffer != null) {
                                    gpuKickPbuffer.release();
                                    gpuKickPbuffer = null;
                                }
                            } catch (Throwable ignored) {}

                            if (dpNow) {
                                try {
                                    gpuKickPbuffer = new com.limelight.gpu.GpuKickPbuffer();
                                    gpuKickPbuffer.setEnabled(true);
                                    gpuKickPbuffer.initOnThisThread();
                                    LimeLog.info("GpuKickPbuffer: re-init after DP toggle (now DP=true)");
                                } catch (Throwable t) {
                                    gpuKickPbuffer = null;
                                }
                            } else {
                                try {
                                    LimeLog.info("GpuKickPbuffer: disabled after DP toggle (now DP=false)");
                                } catch (Throwable ignored) {}
                            }

                            dpLast = dpNow;
                        }
                    }
//* Pin hot threads to big cluster *//
                    // Periodic sticky affinity refresh
                    if (p != null && p.preferBigCores) {
                        final long now = android.os.SystemClock.elapsedRealtimeNanos();
                        if (now - lastAffinityRefreshNs >= AFFINITY_REFRESH_NS) {
                            try {
                                String maskBefore = com.limelight.utils.CpuAffinity.readAllowedCpuListForCurrentThread();
                                if (lastAllowedMask == null || !maskBefore.equals(lastAllowedMask)) {
                                    com.limelight.utils.CpuAffinity.pinCurrentThreadToBigCoresIf(true);
                                    String maskAfter = com.limelight.utils.CpuAffinity.readAllowedCpuListForCurrentThread();
                                    if (BuildConfig.DEBUG) {
                                        LimeLog.info("RendererAffinity: refresh_pin allowed_before=" + maskBefore
                                                + " allowed_after=" + maskAfter);
                                    }
                                    lastAllowedMask = maskAfter;
                                }
                            } catch (Throwable ignored) {}
                            lastAffinityRefreshNs = now;
                        }
                    }

                    // ADPF retarget when GPU_RAW toggles at runtime
                    if (MediaCodecDecoderRenderer.this.perfHint != null
                            && MediaCodecDecoderRenderer.this.perfHint.isActive()) {
                        final boolean curGpuRaw = (p != null
                                && p.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW);
                        if (curGpuRaw != phmGpuRawLast) {
                            final long targetNs = curGpuRaw
                                    ? Math.max(6_000_000L, (long) (streamPeriodNs * 0.90))
                                    : Math.max(1_000_000L, (long) (streamPeriodNs * 0.60));
                            try { MediaCodecDecoderRenderer.this.perfHint.updateTarget(targetNs); } catch (Throwable ignored) {}
                            phmGpuRawLast = curGpuRaw;
                            if (BuildConfig.DEBUG) {
                                LimeLog.info("PHM: runtime target update (gpuRaw=" + curGpuRaw + ", targetNs=" + targetNs + ")");
                            }
                        }
                    }

                    // PURE LFR / ULL path
                    if (preferLowerDelays) {
                        try {
                            // Reuse a single BufferInfo to avoid per-loop allocations
                            final android.media.MediaCodec.BufferInfo __tmpInfo = latestInfo;
                            int __idx = videoDecoder.dequeueOutputBuffer(__tmpInfo, 0);
                            int __last = -1;
                            long __lastPtsUs = -1L;

                            // Drain non-blocking; keep only the newest buffer
                            while (__idx >= 0) {
                                final long ptsUs = __tmpInfo.presentationTimeUs;

                                // Measure pure decode time at dequeue (for ALL frames, shown or discarded)
                                try { updateDecodeLatencyStats(ptsUs); } catch (Throwable ignored) {}

                                if (__last >= 0) {
                                    // Drop older buffer without rendering (count as recent drop for adaptive thresholds)
                                    try { videoDecoder.releaseOutputBuffer(__last, false); } catch (Throwable ignored) {}
                                    recentDrops = Math.min(10, recentDrops + 1);
                                }
                                __last = __idx;
                                __lastPtsUs = ptsUs;
                                __idx = videoDecoder.dequeueOutputBuffer(__tmpInfo, 0);
                            }

                            if (__last >= 0) {
                                // Present the newest buffer ASAP (timestamped)
                                if (android.os.Build.VERSION.SDK_INT >= 21) {
                                    final long __nowNs = System.nanoTime();
                                    videoDecoder.releaseOutputBuffer(__last, __nowNs);
                                } else {
                                    videoDecoder.releaseOutputBuffer(__last, true);
                                }
                                gpuKickPresentHook();

                                // Stats update - before jitter calculation to keep timing consistent
                                try {
                                    activeWindowVideoStats.totalFramesRendered++;
                                    if (MediaCodecDecoderRenderer.this.perfHint != null
                                            && MediaCodecDecoderRenderer.this.perfHint.isActive()
                                            && MediaCodecDecoderRenderer.this.phmWorkStartNs != 0L) {
                                        try {
                                            MediaCodecDecoderRenderer.this.perfHint.tockAndReport(MediaCodecDecoderRenderer.this.phmWorkStartNs);
                                        } catch (Throwable ignored) {}
                                        MediaCodecDecoderRenderer.this.phmWorkStartNs = 0L;
                                    }

                                    numFramesOut++;
                                } catch (Throwable ignored) {}

                                // RQH inter-arrival jitter (latest-only) - OPTIMIZED VERSION
                                if (lastDecoderPtsUs > 0 && __lastPtsUs > lastDecoderPtsUs) {
                                    final double sampleNs = (__lastPtsUs - lastDecoderPtsUs) * 1000.0;

                                    // Pre-compute constants to avoid repeated multiplications
                                    final double expectedInterClamp   = expectedInterNs * 0.75;
                                    final double minJitterThreshold   = expectedInterNs * 0.02;
                                    final double maxJitterThreshold   = expectedInterNs * 0.50;

                                    // RQH: instantaneous deviation + online quantile (no arrays, no sort)
                                    final double instDev = Math.min(
                                            Math.abs(sampleNs - expectedInterNs),
                                            expectedInterClamp // clamp extreme outliers
                                    );

                                    // Update the online quantile for the desired percentile
                                    final double pctl = ijhQuant.update(instDev);

                                    // Hybrid jitter: weighted instant + online quantile, then clamp
                                    final double hybrid =
                                            (IJH_INST_WEIGHT * instDev) + ((1.0 - IJH_INST_WEIGHT) * pctl);
                                    ijhJitterNs = Math.max(minJitterThreshold,
                                            Math.min(maxJitterThreshold, hybrid));
                                }
                                // Update PTS after computing jitter to avoid losing the sample
                                lastDecoderPtsUs = __lastPtsUs;
                                continue;
                            }
                        } catch (Throwable ignored) {}
                    }
                    /* /LATEST_ONLY_LOW_LATENCY */

                    try {
                        // Try to output a frame (respect policy and do quick retry within budget)
                        final int policyUs = getOutputDequeueTimeoutUs();

                        final long t0 = System.nanoTime();
                        int outIndex = videoDecoder.dequeueOutputBuffer(info, policyUs);
                        final long elapsedUs = (System.nanoTime() - t0) / 1000L;

                        if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            tryAgainStreak++;
                            final int quickBackoffUs = (tryAgainStreak <= 2) ? 250 : 500;

                            final int remainingUs = (policyUs > 0) ? Math.max(0, policyUs - (int) elapsedUs) : 0;
                            final int backoffUs = Math.min(remainingUs, quickBackoffUs);

                            if (backoffUs > 0) {
                                outIndex = videoDecoder.dequeueOutputBuffer(info, backoffUs);
                            }
                            if (outIndex >= 0) {
                                tryAgainStreak = 0;
                            }
                        } else {
                            tryAgainStreak = 0;
                        }

                        if (outIndex >= 0) {
                            // --- flags to manage statistics in a robust way ---
                            boolean statsUpdated = false;
                            boolean frameDropped = false;

                            long presentationTimeUs = info.presentationTimeUs;
                            int lastIndex = outIndex;
                            long lastPtsUs = presentationTimeUs;
// Track PTS for this codec output index (balanced path will use it)
                            try {
                                synchronized (ptsByIndex) {
                                    ptsByIndex.put(lastIndex, lastPtsUs);
                                }
                            } catch (Throwable ignored) {}


                            numFramesOut++;

                            // Measure decode latency AT DEQUEUE for real frames (skip CSD/EOS)
                            if ((info.flags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG | MediaCodec.BUFFER_FLAG_END_OF_STREAM)) == 0) {
                                try { updateDecodeLatencyStats(presentationTimeUs); } catch (Throwable ignored) {}
                                statsUpdated = true;
                            }

                            // update inter-arrival
                            if (lastDecoderPtsUs != 0L) {
                                long interUs = presentationTimeUs - lastDecoderPtsUs;
                                if (interUs > 0) {
                                    final double sampleNs = interUs * 1000.0;

// RQH: instantaneous deviation + online quantile (no arrays, no sort)
                                    final double instDev = Math.min(
                                            Math.abs(sampleNs - expectedInterNs),
                                            expectedInterNs * 0.75 // clamp extreme outliers
                                    );

// Update the online quantile for the desired percentile
                                    final double pctl = ijhQuant.update(instDev);

// Hybrid jitter: weighted instant + online quantile, then clamp
                                    double hybrid = (IJH_INST_WEIGHT * instDev) + ((1.0 - IJH_INST_WEIGHT) * pctl);
                                    double lo = expectedInterNs * 0.02;  // >= 2% of period
                                    double hi = expectedInterNs * 0.50;  // <= 50% of period
                                    ijhJitterNs = Math.max(lo, Math.min(hi, hybrid));
                                }
                            }
                            lastDecoderPtsUs = presentationTimeUs;

                            final PreferenceConfiguration pNow = MediaCodecDecoderRenderer.this.prefs;

                            // Render the latest frame now if frame pacing isn't in balanced mode
                            if (pNow == null || pNow.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {

                                while ((outIndex = videoDecoder.dequeueOutputBuffer(info, getOutputDequeueTimeoutUs())) >= 0) {
                                    final long newPtsUs = info.presentationTimeUs;
                                    try { updateDecodeLatencyStats(newPtsUs); } catch (Throwable ignored) {}
                                    videoDecoder.releaseOutputBuffer(lastIndex, false);
                                    frameDropped = true; // we're discarding the oldest one

                                    numFramesOut++;
                                    lastIndex = outIndex;
                                    presentationTimeUs = newPtsUs;
                                    lastPtsUs = newPtsUs;
                                }
// --- Present policy per profilo di pacing ---
                                if (pNow != null && pNow.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW) {
                                    // Immediate present using frame PTS; no decoder-side pacing
                                    if (lastIndex >= 0) {
                                        try {
// Always use monotonic now; PTS is not guaranteed to be on the same clock domain
                                            final long nowNs = System.nanoTime();
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                                // Feed-back map also for immediate present path
                                                try {
                                                    synchronized (scheduledByPtsUs) {
                                                        scheduledByPtsUs.put(lastPtsUs, nowNs);
                                                        if (scheduledByPtsUs.size() > 256) {
                                                            scheduledByPtsUs.removeAt(0);
                                                        }
                                                    }
                                                } catch (Throwable ignored) {}

                                                videoDecoder.releaseOutputBuffer(lastIndex, nowNs);
                                                gpuKickPresentHook();
                                            } else {
                                                videoDecoder.releaseOutputBuffer(lastIndex, true);
                                                gpuKickPresentHook();
                                            }
                                            lastPresentNs = nowNs;
                                            lastRenderedFrameTimeNanos = nowNs;
                                            recentDrops = 0;
                                            // FIX: Do NOT call updateDecodeLatencyStats() here:
                                        } catch (IllegalStateException e) {
                                            try { handleDecoderException(e); } catch (Throwable ignored) {}
                                            // Continue the loop; recovery happens via doCodecRecoveryIfRequired()
                                            continue;
                                        } catch (Throwable ignored) {
                                            // Keep running
                                        }
                                    }
                                }
                                else if (pNow != null && pNow.framePacing == PreferenceConfiguration.FRAME_PACING_ADAPTX) {
                                    // AdaptX: auto-adaptive pacing (vsync-aligned with dynamic guard)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        final long nowNs = System.nanoTime();
                                        long frameAgeNs = nowNs - (presentationTimeUs * 1000L);
                                        if (frameAgeNs < 0L) {
                                            frameAgeNs = 0L;
                                        }
                                        // --- Deadline gating: if we already missed the next vsync window, drop early ---
                                        predictedVsyncNs = advancePredictedVsync(predictedVsyncNs, nowNs, periodNs);

                                        final long marginNs = computeDeadlineMarginNs(
                                                periodNs,
                                                ijhJitterNs,
                                                usingDirectPresent,
                                                preferLowerDelays
                                        );
                                        final long deadlineNs = predictedVsyncNs - marginNs;
                                        final long missByNs = nowNs - deadlineNs;
                                        final boolean missDeadline = missByNs > 0;
                                        final boolean clearMiss = missByNs > (periodNs / deadlineMissHystFrac);

                                        if (missDeadline && (lateStreak > 0 || clearMiss)) {
                                            videoDecoder.releaseOutputBuffer(lastIndex, false);
                                            frameDropped = true;
                                            lastDropNs = nowNs;
                                            recentDrops = Math.min(10, recentDrops + 1);
                                            continue;
                                        }

                                        // Pressure from jitter + recent drops (using your ijhJitterNs)
                                        double pressure = Math.min(1.0, (ijhJitterNs / (double) periodNs) + (recentDrops * 0.1));

                                        // Drop threshold between Latency and Smoothness (auto)
                                        double factor = 1.05 + 0.10 * pressure;   // ~1.05x..1.15x
                                        factor = Math.max(1.05, Math.min(1.15, factor));
                                        long dropThresholdNs = (long) (periodNs * factor);

                                        // Drop heuristic (debounce + cooldown)
                                        final long sinceLastPresent = (lastPresentNs == 0L) ? Long.MAX_VALUE : Math.max(0L, nowNs - lastPresentNs);
                                        final boolean dropCooldownOk = (nowNs - lastDropNs) >= (periodNs / 2);
                                        final boolean isLate = frameAgeNs > dropThresholdNs;

                                        if (isLate && dropCooldownOk && sinceLastPresent < (long) (periodNs * 0.50)) {
                                            videoDecoder.releaseOutputBuffer(lastIndex, false);
                                            frameDropped = true;
                                            lastDropNs = nowNs;
                                            recentDrops = Math.min(10, recentDrops + 1);
                                            continue;
                                        }

                                        // Target present: align to vsync with small guard (dPLL)
                                        if (phaseLock == null) { initPhaseLockIfNeeded(); }  // uses refreshRate -> vsyncPeriod (dPLL)
                                        long guardNs = Math.min((long) (periodNs * (preferLowerDelays ? 0.018 : 0.030)), 1_500_000L);
                                        long baseTs = nowNs + guardNs;
                                        if (phaseLock != null && lastVsyncNs != 0L) {
                                            baseTs = phaseLock.adjust(baseTs, lastVsyncNs);
                                        }
                                        long tsNs = Math.max(nowNs + 150_000L, baseTs); // never in the past

                                        // Register scheduled time for feedback (OnFrameRendered) and present
                                        try {
                                            synchronized (scheduledByPtsUs) {
                                                scheduledByPtsUs.put(lastPtsUs, tsNs);
                                                if (scheduledByPtsUs.size() > 256) scheduledByPtsUs.removeAt(0);
                                            }
                                        } catch (Throwable ignored) {}

                                        videoDecoder.releaseOutputBuffer(lastIndex, tsNs);
                                        gpuKickPresentHook();

                                        predictedVsyncNs = advancePredictedVsync(predictedVsyncNs, tsNs, periodNs);
                                        lastPresentNs = tsNs;
                                        recentDrops = Math.max(0, recentDrops - 1);
                                    } else {
                                        videoDecoder.releaseOutputBuffer(lastIndex, true);
                                        gpuKickPresentHook();
                                    }
                                }

                                else if (pNow != null && (pNow.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS
                                        || pNow.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS)) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        final long nowNs = System.nanoTime();
                                        long frameAgeNs = nowNs - (presentationTimeUs * 1000L);
                                        if (frameAgeNs < 0L) {
                                            frameAgeNs = 0L;
                                        }

                                        double pressure = Math.min(1.0, (ijhJitterNs / vsyncPeriodNs) + (recentDrops * 0.1));
                                        double factorSmooth = 1.2 - 0.15 * (1.0 - pressure);
                                        factorSmooth = Math.max(1.05, Math.min(1.2, factorSmooth));

                                        long dropThresholdSmoothNs = (long) (periodNs * factorSmooth);

                                        if (frameAgeNs >= dropThresholdSmoothNs) {
                                            videoDecoder.releaseOutputBuffer(lastIndex, false);
                                            frameDropped = true;
                                            lastDropNs = nowNs;
                                            recentDrops = Math.min(10, recentDrops + 1);
                                            continue;
                                        }
// Feed-back map also for immediate present path
                                        try {
                                            synchronized (scheduledByPtsUs) {
                                                scheduledByPtsUs.put(lastPtsUs, nowNs);
                                                if (scheduledByPtsUs.size() > 256) {
                                                    scheduledByPtsUs.removeAt(0);
                                                }
                                            }
                                        } catch (Throwable ignored) {}

                                        videoDecoder.releaseOutputBuffer(lastIndex, nowNs);
                                        gpuKickPresentHook();

                                        lastPresentNs = nowNs;
                                        recentDrops = Math.max(0, recentDrops - 1);

                                    } else {
                                        if (android.os.Build.VERSION.SDK_INT >= 21) {
                                            long ts = System.nanoTime();
                                            videoDecoder.releaseOutputBuffer(lastIndex, ts);
                                            gpuKickPresentHook();
                                        } else {
                                            videoDecoder.releaseOutputBuffer(lastIndex, true);
                                            gpuKickPresentHook();
                                        }
                                    }
                                }
                                else {
                                    // Latency mode
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        final long nowNs = System.nanoTime();
                                        long frameAgeNs = nowNs - (presentationTimeUs * 1000L);
                                        if (frameAgeNs < 0L) {
                                            frameAgeNs = 0L;
                                        }
                                        // Latency: 1.0..1.15×, debounce = 1, cooldown = 0.5×
                                        double backPressure = Math.min(1.0, (double) tryAgainStreak / 6.0);
                                        double streamHz = Math.max(1.0, (double) tfps);
                                        double mismatch = Math.abs((1_000_000_000.0 / streamHz)
                                                - (1_000_000_000.0 / Math.max(1.0, displayHz))) / vsyncPeriodNs;
                                        mismatch = Math.min(2.0, mismatch);

                                        double factorLatency = 1.02 + 0.13 * (0.5 * (ijhJitterNs / vsyncPeriodNs)
                                                + 0.3 * backPressure
                                                + 0.2 * mismatch);
                                        factorLatency = Math.max(MIN_FACTOR, Math.min(1.15, factorLatency));

                                        long dropThresholdNs = (long) (periodNs * factorLatency);

                                        final long sinceLastPresent = (lastPresentNs == 0L)
                                                ? Long.MAX_VALUE : (nowNs - lastPresentNs);
                                        final boolean dropCooldownOk = (nowNs - lastDropNs) >= (periodNs / 2);
                                        final boolean isLate = frameAgeNs > dropThresholdNs;
                                        lateStreak = isLate ? (lateStreak + 1) : 0;

                                        final boolean shouldDrop =
                                                isLate &&
                                                        (lateStreak >= 1) &&
                                                        (sinceLastPresent < (long) (periodNs * 0.5)) &&
                                                        dropCooldownOk;

                                        if (shouldDrop) {
                                            videoDecoder.releaseOutputBuffer(lastIndex, false);
                                            frameDropped = true;
                                            lastDropNs = nowNs;
                                            recentDrops = Math.min(10, recentDrops + 1);
                                            continue; // stats already recorded at dequeue for this PTS
                                        }
// Feed-back map also for immediate present path
                                        try {
                                            synchronized (scheduledByPtsUs) {
                                                scheduledByPtsUs.put(lastPtsUs, nowNs);
                                                if (scheduledByPtsUs.size() > 256) {
                                                    scheduledByPtsUs.removeAt(0);
                                                }
                                            }
                                        } catch (Throwable ignored) {}

                                        videoDecoder.releaseOutputBuffer(lastIndex, nowNs);
                                        gpuKickPresentHook();

                                        lastPresentNs = nowNs;
                                        if (!isLate) {
                                            lateStreak = 0;
                                        }
                                        recentDrops = Math.max(0, recentDrops - 1);

                                    } else {
                                        if (android.os.Build.VERSION.SDK_INT >= 21) {
                                            long ts = System.nanoTime();
                                            videoDecoder.releaseOutputBuffer(lastIndex, ts);
                                            gpuKickPresentHook();

                                        } else {
                                            videoDecoder.releaseOutputBuffer(lastIndex, true);
                                            gpuKickPresentHook();

                                        }
                                    }
                                }

                                activeWindowVideoStats.totalFramesRendered++;
                                if (MediaCodecDecoderRenderer.this.perfHint != null
                                        && MediaCodecDecoderRenderer.this.phmWorkStartNs != 0L) {
                                    try { MediaCodecDecoderRenderer.this.perfHint.tockAndReport(MediaCodecDecoderRenderer.this.phmWorkStartNs); } catch (Throwable ignored) {}
                                    MediaCodecDecoderRenderer.this.phmWorkStartNs = 0L;

                                }
                            }
                            else {
                                // For balanced frame pacing case, the Choreographer callback will handle rendering.
                                // We just put all frames into the output buffer queue and let it handle things.

                                // Discard the oldest buffer if we've exceeded our limit.
                                //
                                // NB: We have to do this on the producer side because the consumer may not
                                // run for a while (if there is a huge mismatch between stream FPS and display
                                // refresh rate).
                                // Use the same limits defined at class level so producer and consumer stay in sync.
                                final int qLimit;
                                if (pNow != null) {
                                    switch (pNow.framePacing) {
                                        case PreferenceConfiguration.FRAME_PACING_BALANCED:
                                            qLimit = OUTPUT_BUFFER_QUEUE_LIMIT_BALANCED;
                                            break;
                                        case PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS:
                                            qLimit = OUTPUT_BUFFER_QUEUE_LIMIT_MAX_SMOOTHNESS;
                                            break;
                                        case PreferenceConfiguration.FRAME_PACING_CAP_FPS:
                                            qLimit = OUTPUT_BUFFER_QUEUE_LIMIT_BALANCED;
                                            break;
                                        default:
                                            qLimit = OUTPUT_BUFFER_QUEUE_LIMIT_LL;
                                            break;
                                    }
                                } else {
                                    qLimit = OUTPUT_BUFFER_QUEUE_LIMIT_LL;
                                }

                                // Enforce per-profile queue depth
                                while (outputBufferQueue.size() >= qLimit) {
                                    Integer old = outputBufferQueue.poll();
                                    if (old != null) {
                                        try { videoDecoder.releaseOutputBuffer(old, false); } catch (Throwable ignored) {}
                                    } else break;
                                }
// Non bloccare: se l'offer fallisce per race, droppa il più vecchio e riprova una volta
                                if (!outputBufferQueue.offer(lastIndex)) {
                                    Integer old = outputBufferQueue.poll();
                                    if (old != null) { try { videoDecoder.releaseOutputBuffer(old, false); } catch (Throwable ignored) {} }
                                    outputBufferQueue.offer(lastIndex);
                                }
                            }

                            // --- Fallback stats update ---
// If we didn't update the stats in-branch and the frame wasn't dropped,
// and it's a real frame (skip CSD/EOS), update now
                            if (!statsUpdated && !frameDropped) {
                                if ((info.flags & (MediaCodec.BUFFER_FLAG_CODEC_CONFIG | MediaCodec.BUFFER_FLAG_END_OF_STREAM)) == 0) {
                                    updateDecodeLatencyStats(presentationTimeUs);
                                }
                            }

                        } else {
                            switch (outIndex) {
                                case MediaCodec.INFO_TRY_AGAIN_LATER:
                                    if (MediaCodecDecoderRenderer.this.perfHint != null
                                            && MediaCodecDecoderRenderer.this.perfHint.isActive()
                                            && MediaCodecDecoderRenderer.this.phmWorkStartNs != 0L) {
                                        try { MediaCodecDecoderRenderer.this.perfHint.tockAndReport(MediaCodecDecoderRenderer.this.phmWorkStartNs); } catch (Throwable ignored) {}
                                        MediaCodecDecoderRenderer.this.phmWorkStartNs = 0L;
                                    }
                                    if (preferLowerDelays && preferLowerDelaysTimeoutUs == 0) {
                                        // yield leggerissimo per non peggiorare la latenza
                                        try { android.os.Trace.beginSection("ull-yield"); } catch (Throwable ignored) {}
                                        try { Thread.onSpinWait(); } catch (Throwable ignored) {}
                                        try { android.os.Trace.endSection(); } catch (Throwable ignored) {}
                                    }
                                    break;

                                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                                    LimeLog.info("Output format changed");
                                    outputFormat = videoDecoder.getOutputFormat();
                                    try {
                                        android.media.MediaFormat fmt = outputFormat;
                                        int std = -1, tr = -1, rng = -1;
                                        try { std = fmt.getInteger("color-standard"); } catch (Throwable ignored) {}
                                        try { tr  = fmt.getInteger("color-transfer"); } catch (Throwable ignored) {}
                                        try { rng = fmt.getInteger("color-range"); } catch (Throwable ignored) {}
                                        // BT.2020 + (PQ o HLG) => HDR
                                        boolean isHdr =
                                                (std == android.media.MediaFormat.COLOR_STANDARD_BT2020) &&
                                                        (tr  == android.media.MediaFormat.COLOR_TRANSFER_ST2084
                                                                || tr  == android.media.MediaFormat.COLOR_TRANSFER_HLG);
                                        // Update shared flag so overlays/renderer can see it
                                        hdrActive = isHdr;
                                        try { com.limelight.Game.updateHdrWindowMode(isHdr); } catch (Throwable ignored) {}
                                        // Pass HDR static info to GL upscaler if available
                                        java.nio.ByteBuffer hdr = null;
                                        try { hdr = fmt.getByteBuffer("hdr-static-info"); } catch (Throwable ignored) {}
                                        if (hdr != null && hdr.remaining() > 0) {
                                            byte[] hdrArr = new byte[hdr.remaining()];
                                            hdr.get(hdrArr);
                                            // Latch for future reconfig/restart
                                            if (!Arrays.equals(currentHdrMetadata, hdrArr)) {
                                                currentHdrMetadata = hdrArr;
                                        }
                                      }
                                    } catch (Throwable ignored) {}
                                    LimeLog.info("New output format: " + outputFormat);
                                    break;
                                default:
                                    break;
                            }
                        }
                    } catch (IllegalStateException e) {
                        handleDecoderException(e);
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
                    }
                }
//* Pin hot threads to big cluster *//
// Close PerfHint session and restore affinity
                try {
                    if (MediaCodecDecoderRenderer.this.perfHint != null
                            && MediaCodecDecoderRenderer.this.perfHint.isActive()) {
                        LimeLog.info("PHM: session closed");
                    }
                    if (MediaCodecDecoderRenderer.this.perfHint != null) {
                        MediaCodecDecoderRenderer.this.perfHint.close();
                        MediaCodecDecoderRenderer.this.perfHint = null;
                    }
                } catch (Throwable ignored) {}

                try {
                    com.limelight.utils.CpuAffinity.clearAllThreadsAffinityAllOnline();
                    MediaCodecDecoderRenderer.this.affinityPinned = false;
                    MediaCodecDecoderRenderer.this.lastAllowedMask = null;
                    MediaCodecDecoderRenderer.this.lastAffinityRefreshNs = 0L;
                    LimeLog.info("RendererAffinity: cleared to all online CPUs");
                    String cleared = com.limelight.utils.CpuAffinity.readAllowedCpuListForCurrentThread();
                    LimeLog.info("RendererAffinity: cleared_mask=" + cleared);
                } catch (Throwable ignored) {}
                //* Pin hot threads to big cluster *//
            }
        };
        rendererThread.setName("Video - Renderer (MediaCodec)");
        rendererThread.setPriority(Thread.NORM_PRIORITY + 2);
        rendererThread.start();
    }
    private boolean fetchNextInputBuffer() {
        long startTime;
        boolean codecRecovered;

        if (nextInputBuffer != null) {
            // We already have an input buffer
            return true;
        }

        startTime = SystemClock.uptimeMillis();

        try {
            // Pick a shorter dequeue timeout for high-FPS streams to avoid throttling the RX path
            int dequeueTimeoutUs = 10_000; // default = 10 ms
            float wantedFps = (targetFps > 0f) ? targetFps : (prefs != null ? prefs.fps : 60f);
            boolean ultraLowLatency = (preferLowerDelays || wantedFps >= 100f);
            if (ultraLowLatency) {
                // keep RX snappy for 100/120 fps or LFR/ULL
                dequeueTimeoutUs = 2_000; // 2 ms
            }

            // If we don't have an input buffer index yet, fetch one now
            while (nextInputBufferIndex < 0 && !stopping) {
                nextInputBufferIndex = videoDecoder.dequeueInputBuffer(dequeueTimeoutUs);
                if (nextInputBufferIndex < 0 && ultraLowLatency) {
                    // Don't sit here forever when running at high frame rates
                    break;
                }
            }

            // Get the backing ByteBuffer for the input buffer index
            if (nextInputBufferIndex >= 0) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                    if (nextInputBuffer == null) {
                        // Not a valid dequeued buffer, try again next frame
                        nextInputBufferIndex = -1;
                    }
                } else {
                    nextInputBuffer = legacyInputBuffers[nextInputBufferIndex];

                    // Clear old input data pre-Lollipop
                    nextInputBuffer.clear();
                }
            }
        } catch (IllegalStateException e) {
            handleDecoderException(e);
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        int deltaMs = (int)(SystemClock.uptimeMillis() - startTime);

        if (deltaMs >= 20) {
            LimeLog.warning("Dequeue input buffer ran long: " + deltaMs + " ms");
        }

        if (nextInputBuffer == null) {
            // We've been hung for 5 seconds and no other exception was reported,
            // so generate a decoder hung exception
            if (deltaMs >= 5000 && initialException == null) {
                DecoderHungException decoderHungException = new DecoderHungException(deltaMs);
                if (!reportedCrash) {
                    reportedCrash = true;
                    crashListener.notifyCrash(decoderHungException);
                }
                throw new RendererException(this, decoderHungException);
            }

            return false;
        }

        return true;
    }


    @Override
    public void start() {
        startRendererThread();
        startChoreographerThread();
        // CPU warm-up (skips if PerfHint reports active unless override=true)
        final android.content.Context ctx =
                (this.context != null) ? this.context.getApplicationContext() : null;

        try { cpuWarmUp.stop(); } catch (Throwable ignored) {}

        try {
            final boolean enable = (prefs != null && prefs.cpuWarmUpEnable);
            if (!enable) {

                return;
            }

            final boolean override = (prefs != null && prefs.cpuWarmUpOverridePerfHint);
            final Object hintRef = override ? null : this.perfHint;

            // Avvio warm-up (idempotente). Con ctx!=null hai i controlli termici.
            cpuWarmUp.start(ctx, hintRef, override);
        } catch (Throwable ignored) {}
    }

    // !!! May be called even if setup()/start() fails !!!
    public void prepareForStop() {
        // Let the decoding code know to ignore codec exceptions now
        stopping = true;
// Stop CpuWarmUp immediately
        try { cpuWarmUp.stop(); } catch (Throwable ignored) {}

        // Halt the rendering thread
        if (rendererThread != null) {
            rendererThread.interrupt();
        }


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
                    // Don't allow any further messages to be queued
                    choreographerHandlerThread.quit();

                    // Deregister the frame callback (if registered)
                    Choreographer.getInstance().removeFrameCallback(MediaCodecDecoderRenderer.this);
                }
            });
        }
    }

    @Override
    public void stop() {
        prepareForStop();

        // Wait for the Choreographer looper to shut down (if we have one)
        if (choreographerHandlerThread != null) {
            try {
                choreographerHandlerThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();

                // InterruptedException clears the thread's interrupt status. Since we can't
                // handle that here, we will re-interrupt the thread to set the interrupt
                // status back to true.
                Thread.currentThread().interrupt();
            }
        }

        // Wait for the renderer thread to shut down
        try {
            rendererThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();

            // InterruptedException clears the thread's interrupt status. Since we can't
            // handle that here, we will re-interrupt the thread to set the interrupt
            // status back to true.
            Thread.currentThread().interrupt();
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
        try { if (this.perfHint != null) { this.perfHint.close(); this.perfHint = null; } } catch (Throwable ignored) {}

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
            if (currentHdrMetadata != null && (!enabled || hdrMetadata == null)) {
                currentHdrMetadata = null;
            }
            else if (enabled && hdrMetadata != null && !Arrays.equals(currentHdrMetadata, hdrMetadata)) {
                currentHdrMetadata = hdrMetadata;
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
            synchronized (enqueueNsLock) {
                enqueueNsByPtsUs.put(timestampUs, System.nanoTime());
            }

            // We need a new buffer now
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
        } catch (IllegalStateException e) {
            if (handleDecoderException(e)) {
                // We encountered a transient error. In this case, just hold onto the buffer
                // (to avoid leaking it), clear it, and keep it for the next frame. We'll return
                // false to trigger an IDR frame to recover.
                nextInputBuffer.clear();
            }
            else {
                // We encountered a non-transient error. In this case, we will simply leak the
                // buffer because we cannot be sure we will ever succeed in queuing it.
                nextInputBufferIndex = -1;
                nextInputBuffer = null;
            }
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            return false;
        }

        // Fetch a new input buffer now while we have some time between frames
        // to have it ready immediately when the next frame arrives.
        //
        // We must propagate the return value here in order to properly handle
        // codec recovery happening in fetchNextInputBuffer(). If we don't, we'll
        // never get an IDR frame to complete the recovery process.
        return fetchNextInputBuffer();
    }

    private void doProfileSpecificSpsPatching(SeqParameterSet sps) {
        // Some devices benefit from setting constraint flags 4 & 5 to make this Constrained
        // High Profile which allows the decoder to assume there will be no B-frames and
        // reduce delay and buffering accordingly. Some devices (Marvell, Exynos 4) don't
        // like it so we only set them on devices that are confirmed to benefit from it.
        if (sps.profileIdc == 100 && constrainedHighProfile) {
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
            vpsBuffers.clear();
            spsBuffers.clear();
            ppsBuffers.clear();
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
                    decoder = (avcDecoder != null) ? avcDecoder.getName() : "(avc-null)";
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_H265) != 0) {
                    decoder = (hevcDecoder != null) ? hevcDecoder.getName() : "(hevc-null)";
                } else if ((videoFormat & MoonBridge.VIDEO_FORMAT_MASK_AV1) != 0) {
                    decoder = (av1Decoder != null) ? av1Decoder.getName() : "(av1-null)";
                } else {
                    decoder = "(unknown)";
                }

                float decodeTimeMs = 0f;
                if (lastTwo.totalFramesReceived > 0) {
                    decodeTimeMs = (float) lastTwo.decoderTimeMs / (float) lastTwo.totalFramesReceived;
                }
                long rttInfo = MoonBridge.getEstimatedRttInfo();

                // Pre-size to reduce reallocations based on overlay flavor
                final int sbCap;
                if (prefs != null && prefs.enablePerfOverlayMini) {
                    sbCap = 96;
                } else if (prefs != null && prefs.enablePerfOverlayLite) {
                    sbCap = 192;
                } else {
                    sbCap = 384;
                }
                StringBuilder sb = new StringBuilder(sbCap);

                // --- PERF OVERLAY MINI ---
                if (prefs.enablePerfOverlayMini) {
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
                    float plPct = 0f;
                    if (lastTwo.totalFrames > 0) {
                        plPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
                    }
                    sb.append("PL: ").append(String.format("%.0f", plPct)).append("%\n");
                    sb.append("Net: ").append((int) (rttInfo >> 32))
                            .append("ms | Dec: ").append(String.format("%.1f", decodeTimeMs)).append("ms\n");
                    sb.append(String.format("%.2f", fps.totalFps)).append(" FPS");

                }
                // --- PERF OVERLAY LITE ---
                else if (prefs.enablePerfOverlayLite) {
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
//                    sb.append("分辨率：");
//                    sb.append(initialWidth + "x" + initialHeight);
                    sb.append(context.getString(R.string.perf_overlay_lite_network_decoding_delay) + ": ");
                    sb.append(context.getString(R.string.perf_overlay_lite_net,(int)(rttInfo >> 32)));
                    sb.append(" / ");
                    sb.append(context.getString(R.string.perf_overlay_lite_dectime, decodeTimeMs));
                    sb.append("\t");
                    sb.append(context.getString(R.string.perf_overlay_lite_packet_loss)).append(": ");
                    float liteLossPct = 0f;
                    if (lastTwo.totalFrames > 0) {
                        liteLossPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
                    }
                    sb.append(context.getString(R.string.perf_overlay_lite_netdrops, liteLossPct));
                    sb.append("\t FPS：");
                    sb.append(context.getString(R.string.perf_overlay_lite_fps, fps.totalFps));
                    // __APPLY_LITE_SHIFT: OLED protection (horizontal nudge)
                    try {
                        if (prefs.enablePerfOverlayLiteOledShift) {
                            long now = System.nanoTime();
                            if (now >= liteShiftNextNs) {
                                liteShiftNextNs = now + LITE_SHIFT_PERIOD_NS;
                                // Ping-pong 0 -> 1 -> 2 -> 1 -> 0
                                if (liteShiftSpaces == 0) liteShiftSpaces = 1;
                                else if (liteShiftSpaces == 1) liteShiftSpaces = 2;
                                else if (liteShiftSpaces == 2) liteShiftSpaces = 1;
                                else liteShiftSpaces = 0;
                            }
                        } else {
                            liteShiftSpaces = 0;
                        }
                    } catch (Throwable ignored) {}
                    // __LITE_BLINK_TIMERS: update blink schedule (only if OLED protection enabled)
                    try {
                        if (prefs.enablePerfOverlayLiteOledShift) {
                            long now = System.nanoTime();
                            if (now >= liteBlinkNextStartNs) {
                                liteBlinkNextStartNs = now + LITE_BLINK_PERIOD_NS;
                                liteBlinkEndNs = now + LITE_BLINK_DURATION_NS;
                            }
                        } else {
                            liteBlinkEndNs = 0L;
                        }
                    } catch (Throwable ignored) {}

                    /* ADV_LITE_START */
                    if (prefs.enablePerfOverlayLiteAdvanced) {
                        // IN (incoming frames per sec) and R (rendered FPS) for the same stats window
                        sb.append("  IN:").append((int) fps.receivedFps);
                        sb.append("  R:").append((int) fps.renderedFps);
                        // HDR/SDR indicator
                        sb.append("  ").append(hdrActive ? "HDR" : "SDR");
                    }
                    /* ADV_LITE_END */

/*                    // Also show per-window incoming and rendered FPS (same window of 'lastTwo')
                    // IN = frames received per second; R = frames rendered per second
                    sb.append("  IN:");
                    sb.append((int) fps.receivedFps);
                    sb.append("  R:");
                    sb.append((int) fps.renderedFps);
                    // Show SDR/HDR mode in Perf Lite
                    sb.append("  ").append(hdrActive ? "HDR" : "SDR");*/
                    if(Stereo3DRenderer.isActive) {
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
                    if (Stereo3DRenderer.isActive) {
                        sb.append(context.getString(R.string.perf_overlay_streamdetails,
                                initialWidth + "x" + initialHeight, fps.totalFps));
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
                        // If GPU renders the frames, the render FPS is the actual drawn and visible fps for the user
                        sb.append(context.getString(R.string.perf_overlay_streamdetails,
                                initialWidth + "x" + initialHeight, fps.totalFps));
                    }
                    sb.append('\n');
                    sb.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n');
                    sb.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n');
                    float fullLossPct = 0f;
                    if (lastTwo.totalFrames > 0) {
                        fullLossPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
                    }
                    sb.append(context.getString(R.string.perf_overlay_netdrops, fullLossPct)).append('\n');
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
                    sb.append(context.getString(R.string.perf_overlay_netlatency,
                            (int) (rttInfo >> 32), (int) rttInfo)).append('\n');
                    if (lastTwo.framesWithHostProcessingLatency > 0) {
                        sb.append(context.getString(R.string.perf_overlay_hostprocessinglatency,
                                        (float) lastTwo.minHostProcessingLatency / 10,
                                        (float) lastTwo.maxHostProcessingLatency / 10,
                                        (float) lastTwo.totalHostProcessingLatency / 10 / lastTwo.framesWithHostProcessingLatency))
                                .append('\n');
                    }
                    sb.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs));
                }

                // Append FSR overlay line if available
                try {
                    String __fsr = __fsrGetOverlayLine(glUpscaler);
                    if (__fsr != null && !__fsr.isEmpty()) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
                        sb.append(__fsr).append('\n');
                    }
                } catch (Throwable ignored) {}

                String fullLog = sb.toString();

                if (prefs.enablePerfOverlayLite && prefs.enablePerfOverlayLiteOledShift) {
                    try {
                        sb.insert(0, new String(new char[Math.max(0, liteShiftSpaces)]).replace('\0', ' '));
                    } catch (Throwable ignored) {}
                }
                // __LITE_BLINK_APPLY: if within blink window, blank lite overlay; else keep current content.
                if (prefs.enablePerfOverlayLite && prefs.enablePerfOverlayLiteOledShift) {
                    try {
                        long now = System.nanoTime();
                        if (liteBlinkEndNs > 0L && now < liteBlinkEndNs) {
                            sb.setLength(0);
                            sb.append(' '); // minimal content -> effectively black/transparent
                        } else {
                            // Keep existing content (shift may have added leading spaces earlier)
                        }
                    } catch (Throwable ignored) {}
                }

                String rawLog = fullLog;         // prima delle trasformazioni
                String renderedLog = sb.toString(); // dopo shift/blink ecc.

                if (perfListener != null && prefs.enablePerfOverlay) {
                    perfListener.onPerfUpdate(renderedLog);
                }

                // Best latency is only met at requested highest fps, rest can be ignored
                boolean targetFpsMatched = ((int) fps.totalFps == (int) prefs.fps);
                if (minDecodeTime > decodeTimeMs && targetFpsMatched) {
                    minDecodeTime = decodeTimeMs;
                    minDecodeTimeFullLog = fullLog;
}

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
                if (!refFrameInvalidationActive) {
                    if (initialWidth <= 720 && initialHeight <= 480 && refreshRate <= 60) {
                        // Max 5 buffered frames at 720x480x60
                        LimeLog.info("Patching level_idc to 31");
                        sps.levelIdc = 31;
                    }
                    else if (initialWidth <= 1280 && initialHeight <= 720 && refreshRate <= 60) {
                        // Max 5 buffered frames at 1280x720x60
                        LimeLog.info("Patching level_idc to 32");
                        sps.levelIdc = 32;
                    }
                    else if (initialWidth <= 1920 && initialHeight <= 1080 && refreshRate <= 60) {
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
                if (!refFrameInvalidationActive) {
                    LimeLog.info("Patching num_ref_frames in SPS");
                    sps.numRefFrames = 1;
                }

                // GFE 2.5.11 changed the SPS to add additional extensions. Some devices don't like these
                // so we remove them here on old devices unless these devices also support HEVC.
                // See getPreferredColorSpace() for further information.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O &&
                        sps.vuiParams != null &&
                        hevcDecoder == null &&
                        av1Decoder == null) {
                    sps.vuiParams.videoSignalTypePresentFlag = false;
                    sps.vuiParams.colourDescriptionPresentFlag = false;
                    sps.vuiParams.chromaLocInfoPresentFlag = false;
                }

                // Some older devices used to choke on a bitstream restrictions, so we won't provide them
                // unless explicitly whitelisted. For newer devices, leave the bitstream restrictions present.
                if (needsSpsBitstreamFixup || isExynos4 || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                if (needsBaselineSpsHack) {
                    LimeLog.info("Hacking SPS to baseline");
                    sps.profileIdc = 66;
                    savedSps = sps;
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
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                vpsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            // Only the HEVC SPS hits this path (H.264 is handled above)
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                ppsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if ((videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_H264 | MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                // If this is the first CSD blob or we aren't supporting fused IDR frames, we will
                // submit the CSD blob in a separate input buffer for each IDR frame.
                if (!submittedCsd || !fusedIdrFrame) {
                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Submit all CSD when we receive the first non-CSD blob in an IDR frame
                    for (byte[] vpsBuffer : vpsBuffers) {
                        nextInputBuffer.put(vpsBuffer);
                    }
                    for (byte[] spsBuffer : spsBuffers) {
                        nextInputBuffer.put(spsBuffer);
                    }
                    for (byte[] ppsBuffer : ppsBuffers) {
                        nextInputBuffer.put(ppsBuffer);
                    }

                    if (!queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Remember that we already submitted CSD for this frame, so we don't do it
                    // again in the fused IDR case below.
                    csdSubmittedForThisFrame = true;

                    // Remember that we submitted CSD globally for this MediaCodec instance
                    submittedCsd = true;

                    if (needsBaselineSpsHack) {
                        needsBaselineSpsHack = false;

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
            if (fusedIdrFrame && !csdSubmittedForThisFrame) {
                for (byte[] vpsBuffer : vpsBuffers) {
                    nextInputBuffer.put(vpsBuffer);
                }
                for (byte[] spsBuffer : spsBuffers) {
                    nextInputBuffer.put(spsBuffer);
                }
                for (byte[] ppsBuffer : ppsBuffers) {
                    nextInputBuffer.put(ppsBuffer);
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
            if (!reportedCrash) {
                reportedCrash = true;
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
        savedSps.profileIdc = 100;

        // Patch the SPS constraint flags
        doProfileSpecificSpsPatching(savedSps);

        // The H264Utils.writeSPS function safely handles
        // Annex B NALUs (including NALUs with escape sequences)
        ByteBuffer escapedNalu = H264Utils.writeSPS(savedSps, 128);
        nextInputBuffer.put(escapedNalu);

        // No need for the SPS anymore
        savedSps = null;

        // Queue the new SPS
        return queueNextInputBuffer(0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG);
    }

    @Override
    public int getCapabilities() {
        int capabilities = 0;

        // Request the optimal number of slices per frame for this decoder
        capabilities |= MoonBridge.CAPABILITY_SLICES_PER_FRAME(optimalSlicesPerFrame);

        // Enable reference frame invalidation on supported hardware
        if (refFrameInvalidationAvc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AVC;
        }
        if (refFrameInvalidationHevc) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC;
        }
        if (refFrameInvalidationAv1) {
            capabilities |= MoonBridge.CAPABILITY_REFERENCE_FRAME_INVALIDATION_AV1;
        }

        // Enable direct submit on supported hardware
        if (directSubmit) {
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
            else if (renderer.numFramesIn > 0 && renderer.outputFormat == null) {
                str = "PreOutputConfigError";
            }
            else if (renderer.outputFormat != null && renderer.numFramesOut == 0) {
                str = "PreOutputError";
            }
            else if (renderer.numFramesOut <= renderer.refreshRate * 30) {
                str = "EarlyOutputError";
            }
            else {
                str = "ErrorWhileStreaming";
            }

            str += "Format: "+String.format("%x", renderer.videoFormat)+DELIMITER;
            str += "AVC Decoder: "+((renderer.avcDecoder != null) ? renderer.avcDecoder.getName():"(none)")+DELIMITER;
            str += "HEVC Decoder: "+((renderer.hevcDecoder != null) ? renderer.hevcDecoder.getName():"(none)")+DELIMITER;
            str += "AV1 Decoder: "+((renderer.av1Decoder != null) ? renderer.av1Decoder.getName():"(none)")+DELIMITER;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.avcDecoder != null) {
                Range<Integer> avcWidthRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getSupportedWidths();
                str += "AVC supported width range: "+avcWidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> avcFpsRange = renderer.avcDecoder.getCapabilitiesForType("video/avc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "AVC achievable FPS range: "+avcFpsRange+DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "AVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.hevcDecoder != null) {
                Range<Integer> hevcWidthRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getSupportedWidths();
                str += "HEVC supported width range: "+hevcWidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> hevcFpsRange = renderer.hevcDecoder.getCapabilitiesForType("video/hevc").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "HEVC achievable FPS range: " + hevcFpsRange + DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "HEVC achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && renderer.av1Decoder != null) {
                Range<Integer> av1WidthRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getSupportedWidths();
                str += "AV1 supported width range: "+av1WidthRange+DELIMITER;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        Range<Double> av1FpsRange = renderer.av1Decoder.getCapabilitiesForType("video/av01").getVideoCapabilities().getAchievableFrameRatesFor(renderer.initialWidth, renderer.initialHeight);
                        str += "AV1 achievable FPS range: " + av1FpsRange + DELIMITER;
                    } catch (IllegalArgumentException e) {
                        str += "AV1 achievable FPS range: UNSUPPORTED!"+DELIMITER;
                    }
                }
            }
            str += "Configured format: "+renderer.configuredFormat+DELIMITER;
            str += "Input format: "+renderer.inputFormat+DELIMITER;
            str += "Output format: "+renderer.outputFormat+DELIMITER;
            str += "Adaptive playback: "+renderer.adaptivePlayback+DELIMITER;
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
            str += "RFI active: "+renderer.refFrameInvalidationActive+DELIMITER;
            str += "Using modern SPS patching: "+(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)+DELIMITER;
            str += "Fused IDR frames: "+renderer.fusedIdrFrame+DELIMITER;
            str += "Video dimensions: "+renderer.initialWidth+"x"+renderer.initialHeight+DELIMITER;
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

    // Call after presenting a frame to nudge GPU clocks in Direct Present path
    private void gpuKickPresentHook() {
        try {
            if (gpuKickPbuffer != null && gpuKickPbuffer.isEnabled()) {
                gpuKickPbuffer.kickOnce();
            }
        } catch (Throwable ignored) {}
    }
    // Initialize PI dPLL from current refresh rate (fallback to display rate if needed)
    private void initPhaseLockIfNeeded() {
        try {
            final float hz = (refreshRate > 0) ? (float) refreshRate : getDisplayRefreshRateSafe();
            final long vsyncPeriodNs = (long) (1_000_000_000.0 / Math.max(30.0f, hz));

            // Present slightly before vsync: ~3% of period (capped ~1.5 ms)
            final long guardBeforeVsyncNs = Math.min((long) (vsyncPeriodNs * 0.03), 1_500_000L);

            // Stable defaults: brisk phase correction, slow drift removal
            final double kp = 0.25;
            final double ki = 0.02;

            // Output clamp ~3% of period; integral clamp ~25% of period
            phaseLock = new PhaseLock(vsyncPeriodNs, guardBeforeVsyncNs, kp, ki, 0.03, 0.25);
        } catch (Throwable ignored) {}
    }
    // Online exponentially-weighted quantile estimator (O(1) per update)
// Tracks a target quantile 'p' with asymmetric learning rates.
    private static final class EWQuantile {
        private final double p;
        private final double alphaUp;
        private final double alphaDn;
        private double q;

        EWQuantile(double p, double init, double alphaUp, double alphaDn) {
            this.p = Math.max(0.01, Math.min(0.99, p));
            this.q = Math.max(0.0, init);
            this.alphaUp = alphaUp;   // how fast we move up when x > q
            this.alphaDn = alphaDn;   // how fast we move down when x < q
        }

        // Update with a new sample and return the current quantile estimate
        double update(double x) {
            final double err = x - q;
            if (err >= 0) {
                // Move up faster for upper quantiles (1 - p)
                q += (alphaUp * (1.0 - p)) * err;
            } else {
                // Move down more conservatively for upper quantiles (p)
                q += (alphaDn * p) * err;
            }
            return q;
        }

        double value() { return q; }
    }

    private float getDisplayRefreshRateSafe() {
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) context.getSystemService(android.content.Context.WINDOW_SERVICE);
            android.view.Display d = (wm != null) ? wm.getDefaultDisplay() : null;
            float hz = (d != null) ? d.getRefreshRate() : 60f;
            if (hz < 30f || hz > 1000f) hz = 60f;
            return hz;
        } catch (Throwable t) {
            return 60f;
        }
    }

    private boolean isMTKDecoderName(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.startsWith("c2.mtk") || n.startsWith("omx.mtk");
    }

}