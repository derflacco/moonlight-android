package com.limelight.binding.video;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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
    // --- Sticky CPU affinity (keep pin alive for whole streaming session) ---
    // We periodically verify that the allowed CPU mask didn't shrink/flip due to cpusets
    // and re-apply pinning to big cores if needed. Lightweight, runs every few seconds.
    private volatile com.limelight.gpu.GpuKickPbuffer gpuKickPbuffer;

    private static final long AFFINITY_REFRESH_NS = 10_000_000_000L; // 10s (was 2s)
    private volatile long lastAffinityRefreshNs = 0L;
    private volatile String lastAllowedMask = null;
    private volatile boolean affinityPinned = false;
    // Display refresh tracking
    private DisplayRefreshManager displayRefreshManager;
// --- FSR-like upscaler reflection helpers (no hard dependency) ---
// Derived from AMD FidelityFX Super Resolution 1.0 (MIT). See third_party/amd-fsr1/LICENSE

    // Cached reflection state to avoid repeated lookups in hot-ish paths
    private static volatile Class<?> sFsrUpscaleClass;
    private static volatile java.lang.reflect.Constructor<?> sFsrUpscaleCtor;
    private static volatile java.lang.reflect.Method sFsrCreateInputSurfaceMethod;
    private static volatile java.lang.reflect.Method sFsrSetDebugEnabledMethod;
    private static volatile java.lang.reflect.Method sFsrGetOverlayLineMethod;

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
            java.lang.reflect.Method m = sFsrCreateInputSurfaceMethod;
            if (m == null) {
                m = upscaler.getClass().getMethod("createDecoderInputSurface");
                sFsrCreateInputSurfaceMethod = m;
            }
            Object s = m.invoke(upscaler);
            return (Surface) s;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object __fsrMaybeCreate(Object existing,
                                           Surface windowSurface,
                                           int srcW,
                                           int srcH,
                                           PreferenceConfiguration prefs) {
        if (existing != null) return existing;
        try {
            Class<?> cls = sFsrUpscaleClass;
            if (cls == null) {
                cls = Class.forName("com.limelight.render.GlUpscaleRenderer");
                sFsrUpscaleClass = cls;
            }
            java.lang.reflect.Constructor<?> c = sFsrUpscaleCtor;
            if (c == null) {
                c = cls.getConstructor(
                        Surface.class,
                        int.class,
                        int.class,
                        PreferenceConfiguration.class);
                sFsrUpscaleCtor = c;
            }
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
            java.lang.reflect.Method m = sFsrSetDebugEnabledMethod;
            if (m == null) {
                m = upscaler.getClass().getMethod("setFsrDebugEnabled", boolean.class);
                sFsrSetDebugEnabledMethod = m;
            }
            m.invoke(upscaler, enabled);
        } catch (Throwable ignored) {}
    }

    private static String __fsrGetOverlayLine(Object upscaler) {
        if (upscaler == null) return "";
        try {
            java.lang.reflect.Method m = sFsrGetOverlayLineMethod;
            if (m == null) {
                m = upscaler.getClass().getMethod("getFsrOverlayLine");
                sFsrGetOverlayLineMethod = m;
            }
            Object s = m.invoke(upscaler);
            return (s != null) ? s.toString() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    // Prefix the overlay text with a small, slowly changing number of spaces to nudge its position.
    private static String __applyLiteShift(String text, int spaces) {
        if (text == null || text.isEmpty() || spaces <= 0) return text;
        StringBuilder pfx = new StringBuilder(spaces);
        for (int i = 0; i < spaces; i++) pfx.append(' ');
        return pfx.append(text).toString(); // shift solo prima riga
    }
    // --- end helpers ---

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
private final LongSparseArray<Long> enqueueNsByPtsUs = new LongSparseArray<>(64);
    private final Object enqueueNsLock = new Object();


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

    // Decode latency stats are only needed when an overlay/logging feature is enabled.
    // Short-circuit here to keep the hot render loop as light as possible.
    private void updateDecodeLatencyStats(long presentationTimeUs) {
        final PreferenceConfiguration p = this.prefs;
        if (p == null ||
                (!p.enablePerfOverlay
                        && !p.enablePerfOverlayLite
                        && !p.enablePerfOverlayMini
                        && !p.enablePerfLogging)) {
            // No overlay / logging -> skip tracking decoder latency entirely
            return;
        }

        Long enqNs;
        synchronized (enqueueNsLock) {
            enqNs = enqueueNsByPtsUs.get(presentationTimeUs);
            if (enqNs != null) {
                enqueueNsByPtsUs.delete(presentationTimeUs);
            }
        }
        if (enqNs != null) {
            long decMs = (System.nanoTime() - enqNs) / 1_000_000L;
            if (decMs >= 0 && decMs < 1000) {
                activeWindowVideoStats.decoderTimeMs += decMs;
                if (!USE_FRAME_RENDER_TIME) {
                    activeWindowVideoStats.totalTimeMs += decMs;
                }
            }
        }
    }

    public void setPreferLowerDelays(boolean v) { this.preferLowerDelays = v; }


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
        long initialExceptionTimestamp;
    }
    // BufferInfo pooling - avoids GC pressure in render loop
    private static class BufferInfoPool {
        private final MediaCodec.BufferInfo[] pool;
        private int available;

        BufferInfoPool(int size) {
            pool = new MediaCodec.BufferInfo[size];
            for (int i = 0; i < size; i++) {
                pool[i] = new MediaCodec.BufferInfo();
            }
            available = size;
        }

        MediaCodec.BufferInfo acquire() {
            return available > 0 ? pool[--available] : new MediaCodec.BufferInfo();
        }

        void release(MediaCodec.BufferInfo info) {
            if (available < pool.length) pool[available++] = info;
        }
    }

    // Shared StringBuilder for overlay - prevents per-frame allocations
    private final StringBuilder overlayBuilder = new StringBuilder(512);

    // Cached reflection methods for FSR - one-time lookup
    private static volatile Method fsrCreateInputSurfaceMethod;
    private static volatile Method fsrSetDebugEnabledMethod;
    private static volatile Method fsrGetOverlayLineMethod;
    private static volatile Constructor<?> glUpscaleConstructor;
    // BufferInfo pool and reusable instance for LFR path
    private final BufferInfoPool bufferInfoPool = new BufferInfoPool(3);
    private final MediaCodec.BufferInfo latestInfo = new MediaCodec.BufferInfo();
    private final ColdCodecConfig coldCfg = new ColdCodecConfig();


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


    private RendererException initialException;
    private long initialExceptionTimestamp;
    private static final int EXCEPTION_REPORT_DELAY_MS = 3000;

    private VideoStats activeWindowVideoStats;
    private VideoStats lastWindowVideoStats;
    private VideoStats globalVideoStats;

    private long lastTimestampUs;
    private int lastFrameNumber;
    private int refreshRate;
    private PreferenceConfiguration prefs;

    private float minDecodeTime = Float.MAX_VALUE;
    private String minDecodeTimeFullLog = "";

    private long lastNetDataNum;
    // Output buffer queue for frames waiting for Choreographer/vsync
// Cap at 3 so we never accumulate too many frames if consumer is late
    private LinkedBlockingQueue<Integer> outputBufferQueue = new LinkedBlockingQueue<>(3);

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
    /**
     * Global presentation gating helper shared across all pacing modes.
     *
     * Objective:
     * - When stream FPS ≈ display refresh rate (e.g., 60 fps on 60 Hz),
     *   target one presentation per VSYNC to avoid 16.7/33.3 ms patterns.
     * - When stream FPS is significantly higher than refresh (e.g., 90/120 fps on 60 Hz),
     *   maintain a tighter threshold (~80% of period) for frame decimation without
     *   double-presenting within the same interval.
     *
     * Uses lastRenderedFrameTimeNanos as single state variable.
     */
    private boolean shouldPresentNow(long nowNs) {
        // First frame: always allow presentation
        if (lastRenderedFrameTimeNanos <= 0L) {
            return true;
        }

        long deltaNs = nowNs - lastRenderedFrameTimeNanos;
        if (deltaNs < 0L) {
            deltaNs = 0L;
        }

        // Display cadence: prefer DisplayRefreshManager, fallback to refreshRate
        float displayHz;
        long vsyncPeriodNs;
        try {
            if (displayRefreshManager != null) {
                displayHz = displayRefreshManager.getRefreshRateHz();
                long candidate = displayRefreshManager.getVsyncPeriodNs();
                if (candidate > 0L) {
                    vsyncPeriodNs = candidate;
                } else {
                    displayHz = (refreshRate > 0 ? (float) refreshRate : 60f);
                    vsyncPeriodNs = (long) (1_000_000_000.0 / Math.max(1f, displayHz));
                }
            } else {
                displayHz = (refreshRate > 0 ? (float) refreshRate : 60f);
                vsyncPeriodNs = (long) (1_000_000_000.0 / Math.max(1f, displayHz));
            }
        } catch (Throwable ignored) {
            displayHz = (refreshRate > 0 ? (float) refreshRate : 60f);
            vsyncPeriodNs = (long) (1_000_000_000.0 / Math.max(1f, displayHz));
        }

        // Stream cadence (targetFps set in setup(...))
        final float tfps = (targetFps > 0f ? targetFps : displayHz);
        final long streamPeriodNs = (long) (1_000_000_000.0 / Math.max(1f, tfps));

        // Consider "near match" if stream period is within ±10% of display period
        final long diff = Math.abs(streamPeriodNs - vsyncPeriodNs);
        final boolean fpsNearDisplay = diff <= (vsyncPeriodNs / 10L);

        // High-FPS on low-Hz (e.g. 90/120 fps on 60 Hz):
        // keep legacy behavior and do not gate by lastRenderedFrameTimeNanos.
        if (streamPeriodNs < vsyncPeriodNs) {
            return true;
        }

        if (fpsNearDisplay) {
            // FPS ≈ Hz (e.g. 60/60): target one present per VSYNC (~90% of period)
            final long thresholdNs = (vsyncPeriodNs * 9L) / 10L;
            return deltaNs >= thresholdNs;
        } else {
            // Other mismatches (e.g. 30 fps on 60 Hz): slightly tighter gate (~80%)
            final long thresholdNs = (vsyncPeriodNs * 8L) / 10L;
            return deltaNs >= thresholdNs;

        }

    }
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
                    glUpscaler = __fsrMaybeCreate((Object) glUpscaler, renderTarget, coldCfg.initialWidth, coldCfg.initialHeight, prefs);
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
        coldCfg.vpsBuffers.clear();
        coldCfg.spsBuffers.clear();
        coldCfg.ppsBuffers.clear();

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
                        if (USE_FRAME_RENDER_TIME) {
                            activeWindowVideoStats.totalTimeMs += delta;
                        }
                    }
                }
            }, null);
        }

        return 0;
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        this.targetFps = (redrawRate > 0 ? (float) redrawRate : 60f);
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
// Input and output buffers are invalidated by stop() and reset().
                nextInputBuffer = null;
                nextInputBufferIndex = -1;
                outputBufferQueue.clear();
// Also drop decode-latency entries tied to the old codec instance
                synchronized (enqueueNsLock) {
                    enqueueNsByPtsUs.clear();
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
            if (coldCfg.initialException != null) {
                if (SystemClock.uptimeMillis() - coldCfg.initialExceptionTimestamp >= EXCEPTION_REPORT_DELAY_MS) {
                    crashListener.notifyCrash(coldCfg.initialException);
                    throw coldCfg.initialException;
                }
            } else {
                coldCfg.initialException = new RendererException(this, e);
                coldCfg.initialExceptionTimestamp = SystemClock.uptimeMillis();
            }
        }

        // Not transient
        return false;
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // Exit early if stopping
        if (stopping) {
            return;
        }

        // Use the shared FPS/Hz gate for Choreographer-driven pacing
        final boolean shouldRender = shouldPresentNow(frameTimeNanos);

        if (shouldRender) {
            // Mark start of CPU work for this frame
            if (MediaCodecDecoderRenderer.this.perfHint != null) {
                MediaCodecDecoderRenderer.this.phmWorkStartNs = com.limelight.perf.PerfHint.tick();
            }

            // Render up to one frame when in frame pacing mode.
            //
            // Since the queue limit is 2, we will not starve the decoder of output buffers
            // by holding onto them for too long. This also ensures we have one extra
            // buffered frame to smooth over network/decoder jitter.
            Integer nextOutputBuffer = outputBufferQueue.poll();
            if (nextOutputBuffer != null) {
                try {
                    if (Build.VERSION.SDK_INT >= 21) {
                        // Timestamped release on L+ to align with VSYNC
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos);
                    } else {
                        // Very old devices: immediate render
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, true);
                    }

                    gpuKickPresentHook();

                    // Track when the last frame was actually presented
                    lastRenderedFrameTimeNanos = frameTimeNanos;
                    activeWindowVideoStats.totalFramesRendered++;

                    if (MediaCodecDecoderRenderer.this.perfHint != null
                            && MediaCodecDecoderRenderer.this.perfHint.isActive()
                            && MediaCodecDecoderRenderer.this.phmWorkStartNs != 0L) {
                        try {
                            MediaCodecDecoderRenderer.this.perfHint.tockAndReport(
                                    MediaCodecDecoderRenderer.this.phmWorkStartNs);
                        } catch (Throwable ignored) {
                        }
                        MediaCodecDecoderRenderer.this.phmWorkStartNs = 0L;
                    }
                } catch (IllegalStateException ignored) {
                    try {
                        // Try to avoid leaking the output buffer by releasing it without rendering
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, false);
                    } catch (IllegalStateException e) {
                        // This will leak nextOutputBuffer, but there is nothing else we can do
                        e.printStackTrace();
                        handleDecoderException(e);
                    }
                }
            }
        } else {
            // If we intentionally skip this VSYNC, drain old buffers so we do not build up lag
            while (outputBufferQueue.size() > 1) {
                try {
                    Integer old = outputBufferQueue.poll();
                    if (old != null) {
                        videoDecoder.releaseOutputBuffer(old, false);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // Attempt codec recovery even if we have nothing to render right now.
        // Recovery can still be required even if the codec died before giving any output.
        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);

        // Request another callback for the next frame
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void startChoreographerThread() {
        if (prefs == null || prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
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

                // Compute display refresh and vsync period once using DisplayRefreshManager (fallback 60 Hz)
                if (displayRefreshManager == null && context != null) {
                    try {
                        displayRefreshManager = new DisplayRefreshManager(context);
                    } catch (Throwable t) {
                        LimeLog.warning("DisplayRefreshManager: init failed, falling back to defaults: " + t);
                    }
                }

                final long vsyncPeriodNs;
                final float displayHz;
                if (displayRefreshManager != null) {
                    vsyncPeriodNs = displayRefreshManager.getVsyncPeriodNs();
                    displayHz = displayRefreshManager.getRefreshRateHz();
                } else {
                    vsyncPeriodNs = 16_666_667L; // ~60 Hz
                    displayHz = 60f;
                }

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


                // Adaptive period selection for AdaptX and EWMA-based timing.
                // Goal:
                // - 60/60, 120/120, etc.: thresholds follow the stream cadence.
                // - FPS > Hz (e.g. 120 FPS on 60 Hz): slightly relaxed base period to avoid over-dropping.
                // - FPS < Hz (e.g. 45 FPS on 60 Hz): follow the slower stream cadence.
                final boolean managedMode =
                        (prefs != null && prefs.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED);

                final long periodNs;
                if (forceTightThresholds) {
                    // Tight mode: always lock to VSYNC period
                    periodNs = vsyncPeriodNs;
                } else {
                    final boolean fpsMatchesDisplay =
                            Math.abs((double) streamPeriodNs - (double) vsyncPeriodNs)
                                    < (vsyncPeriodNs * 0.10);

                    final boolean fpsHigherThanDisplay =
                            (streamPeriodNs > 0L && vsyncPeriodNs > 0L && streamPeriodNs < vsyncPeriodNs);
                    final boolean fpsLowerThanDisplay =
                            (streamPeriodNs > 0L && vsyncPeriodNs > 0L && streamPeriodNs > vsyncPeriodNs);

                    if (fpsMatchesDisplay) {
                        // 60/60, 90/90, 120/120: 1:1 frame-to-vsync mapping
                        periodNs = streamPeriodNs;
                    }
                    else if (fpsHigherThanDisplay) {
                        // FPS > Hz (e.g. 120 FPS on 60 Hz):
                        // use a slightly larger base period than VSYNC to reduce drop aggressiveness.
                        final double SCALE = 1.25; // ~25% more than vsync period
                        periodNs = (long) (vsyncPeriodNs * SCALE);
                    }
                    else if (fpsLowerThanDisplay) {
                        // FPS < Hz (e.g. 45 FPS on 60 Hz): follow the slower stream cadence.
                        periodNs = streamPeriodNs;
                    }
                    else {
                        // Fallback: keep VSYNC-based period
                        periodNs = vsyncPeriodNs;
                    }
                }

boolean isC2Decoder = false;
                try {
                    String decName = videoDecoder.getName();
                    if (decName != null) {
                        isC2Decoder = decName.toLowerCase(java.util.Locale.US).startsWith("c2.");
                    }
                } catch (Throwable ignored) {}

                // Aggressive/adaptive state
                final double EWMA_ALPHA = 0.25;
                final double MIN_FACTOR = 1.00;
                final double MAX_FACTOR = 1.20;

                long   lastDecoderPtsUs  = 0L;
                long   lastPresentNs     = 0L;
                long   lastDropNs        = 0L;
                int    lateStreak        = 0;
                int    tryAgainStreak    = 0;
                int    recentDrops       = 0;

                // Timing/jitter state:
                // - ewmaInterArrivalNs    : smoothed PTS inter-arrival (approx. stream period)
                // - ewmaDecodeToPresentNs: smoothed decode->present delay
                // - ewmaJitterNs         : baseline jitter magnitude from inter-arrival
                // - phaseErrorEwmaNs     : smoothed phase error vs target latency (Balanced mode)
                double ewmaInterArrivalNs    = (1_000_000_000.0 / Math.max(1f, tfps));
                double ewmaDecodeToPresentNs = managedMode ? (periodNs * 0.80) : (periodNs * 0.70);
                double ewmaJitterNs          = managedMode ? (periodNs * 0.15) : (periodNs * 0.10);
                double phaseErrorEwmaNs      = 0.0;


                final android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
// Reused by latest-only / low-latency drain to avoid per-loop allocations
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
                                updateDecodeLatencyStats(ptsUs);

                                if (__last >= 0) {
                                    // Drop older buffer without rendering
                                    try { videoDecoder.releaseOutputBuffer(__last, false); } catch (Throwable ignored) {}
                                }

                                __last = __idx;
                                __lastPtsUs = ptsUs;
                                __idx = videoDecoder.dequeueOutputBuffer(__tmpInfo, 0);
                            }

                            if (__last >= 0) {
                                final long __nowNs = System.nanoTime();

                                // Present the newest buffer ASAP (timestamped)
                                if (android.os.Build.VERSION.SDK_INT >= 21) {
                                    videoDecoder.releaseOutputBuffer(__last, __nowNs);
                                    gpuKickPresentHook();
                                } else {
                                    videoDecoder.releaseOutputBuffer(__last, true);
                                    gpuKickPresentHook();
                                }

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
                                    lastDecoderPtsUs = __lastPtsUs;
                                } catch (Throwable ignored) {}

                                // EWMA decode->present
                                if (__lastPtsUs >= 0) {
                                    final long __d2pNs = __nowNs - (__lastPtsUs * 1000L);
                                    ewmaDecodeToPresentNs += 0.25 * (__d2pNs - ewmaDecodeToPresentNs);
                                }

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

                            numFramesOut++;

                            // Measure decode latency AT DEQUEUE
                            try { updateDecodeLatencyStats(presentationTimeUs); } catch (Throwable ignored) {}
                            statsUpdated = true;

                            // Update inter-arrival and jitter baseline
                            if (lastDecoderPtsUs != 0L) {
                                long interUs = presentationTimeUs - lastDecoderPtsUs;
                                if (interUs > 0) {
                                    // Inter-arrival sample in ns
                                    double sampleNs = interUs * 1000.0;

                                    // EWMA of inter-arrival interval (tracks average stream period)
                                    double prevInterNs = ewmaInterArrivalNs;
                                    ewmaInterArrivalNs += EWMA_ALPHA * (sampleNs - ewmaInterArrivalNs);

                                    // Instant deviation vs smoothed inter-arrival
                                    double devNs = Math.abs(sampleNs - prevInterNs);

                                    // Clamp deviation to a small multiple of VSYNC to avoid runaway spikes
                                    if (vsyncPeriodNs > 0L) {
                                        double capNs = vsyncPeriodNs * 2.5;
                                        if (devNs > capNs) {
                                            devNs = capNs;
                                        }
                                    }

                                    // Baseline jitter: slower EWMA so it reflects typical noise level
                                    final double JITTER_ALPHA = 0.18;
                                    ewmaJitterNs += JITTER_ALPHA * (devNs - ewmaJitterNs);
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
                                if (pNow != null && pNow.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW) {
                                    // Immediate present using frame PTS; no decoder-side pacing
                                    if (lastIndex >= 0) {
                                        try {
                                            final long nowNs = System.nanoTime();

                                            if (!shouldPresentNow(nowNs)) {
                                                videoDecoder.releaseOutputBuffer(lastIndex, false);
                                                frameDropped = true;
                                                recentDrops = Math.min(10, recentDrops + 1);
                                                continue;
                                            }

                                            long tsNs = (presentationTimeUs > 0) ? (presentationTimeUs * 1000L) : nowNs;
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                                videoDecoder.releaseOutputBuffer(lastIndex, tsNs);
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
                                            handleDecoderException(e);
                                            return;
                                        } catch (Throwable ignored) {}
                                    }
                                }
                                else if (pNow != null && pNow.framePacing == PreferenceConfiguration.FRAME_PACING_ADAPTX) {
                                    // AdaptX: three per-profile heuristics
                                    // 0 = Smoothness (jitter-baseline scaling, very rare drops)
                                    // 1 = Balanced   (decode-latency guided threshold, no direct jitter)
                                    // 2 = Latency    (legacy-style low-latency threshold, warp-aware)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        final long nowNs = System.nanoTime();

                                        // Base period for thresholds: prefer stream-aware period, fall back to VSYNC/60 Hz
                                        long basePeriodNs = periodNs;
                                        if (basePeriodNs <= 0L && vsyncPeriodNs > 0L) {
                                            basePeriodNs = vsyncPeriodNs;
                                        } else if (basePeriodNs <= 0L) {
                                            basePeriodNs = 16_666_667L; // ~60 Hz
                                        }

                                        // End-to-end frame age (host PTS -> now), clamped to [0, +inf)
                                        long frameAgeNs = nowNs - (presentationTimeUs * 1000L);
                                        if (frameAgeNs < 0L) {
                                            frameAgeNs = 0L;
                                        }

                                        // Current AdaptX mode from prefs: 0 = Smoothness, 1 = Balanced, 2 = Latency
                                        final int adaptxMode = (prefs != null)
                                                ? prefs.adaptxMode
                                                : PreferenceConfiguration.ADAPTX_MODE_BALANCED;
                                        final boolean modeSmooth =
                                                (adaptxMode == PreferenceConfiguration.ADAPTX_MODE_SMOOTHNESS);
                                        final boolean modeLatency =
                                                (adaptxMode == PreferenceConfiguration.ADAPTX_MODE_LATENCY);

                                        // Warp factor: used only for Latency mode
                                        // pNow.framePacingWarpFactor comes from config (0 = off, 2 = x2, 4 = x4)
                                        final int warpFactorRaw = (pNow.framePacingWarpFactor > 0)
                                                ? pNow.framePacingWarpFactor
                                                : 1;
                                        final boolean warpActive = modeLatency && warpFactorRaw > 1;

                                        // Common timing for backlog / cooldown
                                        final long sinceLastPresent = (lastPresentNs == 0L)
                                                ? 0L
                                                : Math.max(0L, nowNs - lastPresentNs);

                                        // Relation between stream FPS and display Hz (±10% ~= match)
                                        final boolean fpsMatchesDisplay;
                                        if (streamPeriodNs > 0L && vsyncPeriodNs > 0L) {
                                            long diff = Math.abs(streamPeriodNs - vsyncPeriodNs);
                                            fpsMatchesDisplay = diff <= (vsyncPeriodNs / 10L);
                                        } else {
                                            fpsMatchesDisplay = false;
                                        }

                                        // Per-profile parameters
                                        final double backlogWindowMul;
                                        final long   cooldownDiv;
                                        final int    requiredLateStreak;
                                        final long   dropBasePeriodNs;
                                        final long   dropThresholdNs;


                                        if (modeSmooth) {
                                            // --- AdaptX Smoothness: rare corrective drops, bias for continuous motion ---
                                            backlogWindowMul   = 2.1;  // still require backlog, but a bit less than before
                                            cooldownDiv        = 3L;   // slow/medium drop cadence
                                            requiredLateStreak = 2;    // allow drops after 2 late frames in a row
                                            dropBasePeriodNs   = basePeriodNs;

                                            // Baseline jitter from inter-arrival EWMA. This tracks typical noise level.
                                            double jitterBaselineNs = ewmaJitterNs;
                                            if (jitterBaselineNs <= 0.0) {
                                                // Fallback: small fraction of the period
                                                jitterBaselineNs = (double) basePeriodNs * 0.12;
                                            }

                                            // Normalized jitter level in [0, 2]:
                                            //  0   ~ almost clean line
                                            //  1   ~ moderate jitter
                                            //  2+  ~ heavy jitter
                                            double jitterNorm = jitterBaselineNs / ((double) basePeriodNs * 0.25);
                                            if (jitterNorm < 0.0) jitterNorm = 0.0;
                                            if (jitterNorm > 2.0) jitterNorm = 2.0;

                                            // New Smoothness threshold:
                                            // - Slightly closer to Balanced than before, so we can drop occasionally.
                                            // - Still always higher than Balanced's range (1.45 .. 1.15), so smoother.
                                            double dropFactorSmooth = 1.50 + 0.15 * jitterNorm; // ≈ 1.50 .. 1.80
                                            if (dropFactorSmooth < 1.35) dropFactorSmooth = 1.35;
                                            if (dropFactorSmooth > 1.85) dropFactorSmooth = 1.85;

                                            dropThresholdNs = (long) ((double) dropBasePeriodNs * dropFactorSmooth);


                                        } else if (modeLatency) {
                                            // --- AdaptX Latency: legacy-style low-latency threshold (warp-aware) ---
                                            backlogWindowMul   = 0.8;  // small backlog window
                                            cooldownDiv        = 2L;   // faster drop cadence
                                            requiredLateStreak = 1;    // drop on first late frame

                                            // For Latency + Warp, deadlines are based on a shorter effective period
                                            if (warpActive) {
                                                dropBasePeriodNs = Math.max(1L, basePeriodNs / warpFactorRaw);
                                            } else {
                                                dropBasePeriodNs = basePeriodNs;
                                            }

                                            // Legacy-style latency threshold: very close to the (possibly warped) period.
                                            // This keeps end-to-end latency low with behaviour similar to the old Latency path.
                                            final double LAT_DROP_FACTOR = 1.04; // ~4% over period
                                            dropThresholdNs = (long) ((double) dropBasePeriodNs * LAT_DROP_FACTOR);

                                        } else {
                                            // --- AdaptX Balanced: decode-latency guided threshold (no direct jitter) ---
                                            // Tuning:
                                            // - FPS ~= Hz (e.g. 60/60): more relaxed, closer to Smoothness.
                                            // - Other cases: original behaviour.

                                            final boolean matchFpsHz = fpsMatchesDisplay;

                                            backlogWindowMul   = matchFpsHz ? 2.1 : 1.9;  // wider window when FPS~=Hz
                                            cooldownDiv        = 3L;                     // medium drop cadence
                                            requiredLateStreak = matchFpsHz ? 3 : 2;     // need 3 late frames at 60/60
                                            dropBasePeriodNs   = basePeriodNs;

                                            // Decode-to-present EWMA: proxy for "typical" end-to-end latency.
                                            double latencyRatio;
                                            if (ewmaDecodeToPresentNs > 0.0 && basePeriodNs > 0L) {
                                                latencyRatio = ewmaDecodeToPresentNs / (double) basePeriodNs;
                                            } else {
                                                // Fallback: assume we want around 0.8 of the period.
                                                latencyRatio = 0.80;
                                            }

                                            // Normalize latencyRatio into [0, 1]:
                                            //  0.0 ~ low latency (<= 0.70 * period)
                                            //  1.0 ~ high latency (>= 1.00 * period)
                                            double latNorm = (latencyRatio - 0.70) / 0.30;
                                            if (latNorm < 0.0) latNorm = 0.0;
                                            if (latNorm > 1.0) latNorm = 1.0;

                                            // Drop pressure from recent history (0..1):
                                            //  0.0 ~ no recent drops
                                            //  1.0 ~ many recent drops
                                            double dropPressure = Math.min(1.0, (double) recentDrops / 6.0);

                                            // Combined drive:
                                            //  - latency (latNorm) pushes towards more aggressive dropping
                                            //  - dropPressure reduces aggressiveness when we already dropped a lot
                                            double drive = 0.65 * latNorm + 0.35 * dropPressure;
                                            if (drive < 0.0) drive = 0.0;
                                            if (drive > 1.0) drive = 1.0;

                                            // Map drive into a drop factor range:
                                            //  FPS ~= Hz: slightly more relaxed (1.55..1.25)
                                            //  other    : original range (1.45..1.15)
                                            final double DROP_FACTOR_HIGH = matchFpsHz ? 1.55 : 1.45;
                                            final double DROP_FACTOR_LOW  = matchFpsHz ? 1.25 : 1.15;

                                            double dropFactorBalanced =
                                                    DROP_FACTOR_HIGH
                                                            - (DROP_FACTOR_HIGH - DROP_FACTOR_LOW) * drive;

                                            dropThresholdNs = (long) (dropBasePeriodNs * dropFactorBalanced);
                                        }


                                        // Cooldown to avoid spamming drops
                                        final boolean dropCooldownOk =
                                                (nowNs - lastDropNs) >= (dropBasePeriodNs / cooldownDiv);

                                        // Late if the frame is older than the per-mode drop threshold
                                        final boolean isLate = frameAgeNs > dropThresholdNs;
                                        if (isLate) {
                                            lateStreak++;
                                        } else {
                                            lateStreak = 0;
                                        }

                                        // Backlog: we recently presented a frame (queue is not starved)
                                        final boolean backlog =
                                                sinceLastPresent < (long) (backlogWindowMul * (double) basePeriodNs);

                                        // Decide whether to drop this frame
                                        final boolean shouldDrop =
                                                isLate &&
                                                        backlog &&
                                                        dropCooldownOk &&
                                                        (lateStreak >= requiredLateStreak);

                                        if (shouldDrop) {
                                            videoDecoder.releaseOutputBuffer(lastIndex, false);
                                            frameDropped = true;
                                            lastDropNs = nowNs;
                                            recentDrops = Math.min(10, recentDrops + 1);
                                            continue; // stats already recorded at dequeue for this PTS
                                        }

                                        // Present path:
                                        // - Smoothness: jitter-baseline scaling, very rare drops
                                        // - Balanced  : decode-latency-guided threshold, stable behaviour
                                        // - Latency   : legacy-style low-latency threshold, warp-aware
                                        videoDecoder.releaseOutputBuffer(lastIndex, nowNs);
                                        gpuKickPresentHook();

                                        lastPresentNs = nowNs;
                                        recentDrops = Math.max(0, recentDrops - 1);
                                    } else {
                                        // Legacy path: no fine-grained timestamps available
                                        videoDecoder.releaseOutputBuffer(lastIndex, true);
                                        gpuKickPresentHook();
                                    }
                                }



                                else if (pNow != null && (pNow.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS
                                        || pNow.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS)) {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        final long nowNs = System.nanoTime();
                                        final long frameAgeNs = nowNs - (presentationTimeUs * 1000L);

                                        double pressure = Math.min(1.0, (ewmaJitterNs / vsyncPeriodNs) + (recentDrops * 0.1));
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
                                    // Latency mode (legacy)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        try {
                                            final long nowNs = System.nanoTime();

                                            // Shared timing gate: avoid presenting too early compared to the last rendered frame
                                            if (!shouldPresentNow(nowNs)) {
                                                videoDecoder.releaseOutputBuffer(lastIndex, false);
                                                frameDropped = true;
                                                recentDrops = Math.min(10, recentDrops + 1);
                                                continue;
                                            }

                                            // Present immediately with a monotonic timestamp
                                            final long tsNs = nowNs;
                                            videoDecoder.releaseOutputBuffer(lastIndex, tsNs);
                                            gpuKickPresentHook();

                                            // Keep timing state consistent with the other paths
                                            lastPresentNs = tsNs;
                                            lastRenderedFrameTimeNanos = tsNs;
                                            recentDrops = Math.max(0, recentDrops - 1);
                                            lateStreak = 0;
                                        } catch (IllegalStateException e) {
                                            handleDecoderException(e);
                                            return;
                                        } catch (Throwable ignored) {
                                        }
                                    } else {
                                        // Legacy immediate render
                                        try {
                                            videoDecoder.releaseOutputBuffer(lastIndex, true);
                                            gpuKickPresentHook();
                                        } catch (IllegalStateException e) {
                                            handleDecoderException(e);
                                            return;
                                        } catch (Throwable ignored) {
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
                                    try {
                                        Integer old = outputBufferQueue.poll();
                                        if (old != null) {
                                            videoDecoder.releaseOutputBuffer(old, false);
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                // NB: in BALANCED we don't present here; stats already updated at dequeue

                                outputBufferQueue.add(lastIndex);
                            }

                            // --- Fallback stats update ---
                            // If we didn't update the stats in-branch and the frame wasn't dropped,
                            if (!statsUpdated && !frameDropped) {
                                updateDecodeLatencyStats(presentationTimeUs);
                            }

                        } else {
                            switch (outIndex) {
                                case MediaCodec.INFO_TRY_AGAIN_LATER:
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
                                            // pass to upscaler if needed
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
            // Pick a shorter dequeue timeout for the RX path to avoid throttling input.
            // We keep a very small timeout for LFR/ULL and 60+ FPS, and a moderately
            // small timeout for lower FPS streams.
            float wantedFps = (targetFps > 0f) ? targetFps : (prefs != null ? prefs.fps : 60f);

            final int dequeueTimeoutUs;
            final boolean ultraLowLatencyRx = (preferLowerDelays || wantedFps >= 55f);
            if (ultraLowLatencyRx) {
                // LFR/ULL active or 60+ FPS streams: maximum snappiness
                dequeueTimeoutUs = 2_000; // 2 ms
            } else {
                // Lower FPS (e.g. 30–50 FPS): still snappy but not as aggressive
                dequeueTimeoutUs = 4_000; // 4 ms
            }

            // If we don't have an input buffer index yet, fetch one now
            while (nextInputBufferIndex < 0 && !stopping) {
                nextInputBufferIndex = videoDecoder.dequeueInputBuffer(dequeueTimeoutUs);
                if (nextInputBufferIndex < 0 && ultraLowLatencyRx) {
                    // Don't sit here forever when running at high frame rates / LFR
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
                    nextInputBuffer = coldCfg.legacyInputBuffers[nextInputBufferIndex];

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

            // Release display refresh listener/manager
            if (displayRefreshManager != null) {
                try {
                    displayRefreshManager.release();
                } catch (Throwable ignored) {
                }
                displayRefreshManager = null;
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

                final StringBuilder sb = overlayBuilder;
                sb.setLength(0);
                if (sb.capacity() < sbCap) {
                    sb.ensureCapacity(sbCap);
                }


                // --- PERF OVERLAY MINI ---
                if (prefs.enablePerfOverlayMini) {
                    if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED) {
                        long netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid())
                                + TrafficStatsHelper.getPackageTxBytes(Process.myUid());
                        if (lastNetDataNum != 0) {
                            float realtimeNetData = (netData - lastNetDataNum) / 1024f;
                            if (realtimeNetData >= 1000) {
                                overlayBuilder.append("BW: ").append(String.format("%.1f", realtimeNetData / 1024f)).append(" M/s\n");
                            } else {
                                overlayBuilder.append("BW: ").append(String.format("%.1f", realtimeNetData)).append(" K/s\n");
                            }
                        }
                        lastNetDataNum = netData;
                    }
                    float plPct = 0f;
                    if (lastTwo.totalFrames > 0) {
                        plPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
                    }
                    overlayBuilder.append("PL: ").append(String.format("%.0f", plPct)).append("%\n");
                    overlayBuilder.append("Net: ").append((int) (rttInfo >> 32))
                            .append("ms | Dec: ").append(String.format("%.1f", decodeTimeMs)).append("ms\n");
                    overlayBuilder.append(String.format("%.2f", fps.totalFps)).append(" FPS");

                }
                // --- PERF OVERLAY LITE ---
                else if (prefs.enablePerfOverlayLite) {
                    if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED) {
                        long netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid())
                                + TrafficStatsHelper.getPackageTxBytes(Process.myUid());
                        if (lastNetDataNum != 0) {
                            overlayBuilder.append(context.getString(R.string.perf_overlay_lite_bandwidth)).append(": ");
                            float realtimeNetData = (netData - lastNetDataNum) / 1024f;
                            if (realtimeNetData >= 1000) {
                                overlayBuilder.append(String.format("%.2f", realtimeNetData / 1024f)).append("M/s\t ");
                            } else {
                                overlayBuilder.append(String.format("%.2f", realtimeNetData)).append("K/s\t ");
                            }
                        }
                        lastNetDataNum = netData;
                    }
//                    sb.append("分辨率：");
//                    sb.append(initialWidth + "x" + initialHeight);
                    overlayBuilder.append(context.getString(R.string.perf_overlay_lite_network_decoding_delay) + ": ");
                    overlayBuilder.append(context.getString(R.string.perf_overlay_lite_net,(int)(rttInfo >> 32)));
                    overlayBuilder.append(" / ");
                    overlayBuilder.append(context.getString(R.string.perf_overlay_lite_dectime, decodeTimeMs));
                    overlayBuilder.append("\t");
                    overlayBuilder.append(context.getString(R.string.perf_overlay_lite_packet_loss)).append(": ");
                    float liteLossPct = 0f;
                    if (lastTwo.totalFrames > 0) {
                        liteLossPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
                    }
                    overlayBuilder.append(context.getString(R.string.perf_overlay_lite_netdrops, liteLossPct));
                    overlayBuilder.append("\t FPS：");
                    overlayBuilder.append(context.getString(R.string.perf_overlay_lite_fps, fps.totalFps));
// __APPLY_LITE_SHIFT + __LITE_BLINK_TIMERS: OLED protection
                    try {
                        if (prefs.enablePerfOverlayLiteOledShift) {
                            long now = System.nanoTime();

                            // Horizontal nudge (shift)
                            if (now >= liteShiftNextNs) {
                                liteShiftNextNs = now + LITE_SHIFT_PERIOD_NS;
                                // Ping-pong 0 -> 1 -> 2 -> 1 -> 0
                                if (liteShiftSpaces == 0) liteShiftSpaces = 1;
                                else if (liteShiftSpaces == 1) liteShiftSpaces = 2;
                                else if (liteShiftSpaces == 2) liteShiftSpaces = 1;
                                else liteShiftSpaces = 0;
                            }

                            // Blink timers
                            if (now >= liteBlinkNextStartNs) {
                                liteBlinkNextStartNs = now + LITE_BLINK_PERIOD_NS;
                                liteBlinkEndNs = now + LITE_BLINK_DURATION_NS;
                            }
                        } else {
                            // OLED protection disabled: reset shift and blink
                            liteShiftSpaces = 0;
                            liteBlinkEndNs = 0L;
                        }
                    } catch (Throwable ignored) {}

                    /* ADV_LITE_START */
                    if (prefs.enablePerfOverlayLiteAdvanced) {
                        // IN (incoming frames per sec) and R (rendered FPS) for the same stats window
                        overlayBuilder.append("  IN:").append((int) fps.receivedFps);
                        overlayBuilder.append("  R:").append((int) fps.renderedFps);
                        // HDR/SDR indicator
                        overlayBuilder.append("  ").append(hdrActive ? "HDR" : "SDR");
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
                        overlayBuilder.append(" ");
                        overlayBuilder.append(context.getString(R.string.perf_overlay_ai_fps));
                        overlayBuilder.append(" ");
                        overlayBuilder.append(Stereo3DRenderer.threeDFps);
                        overlayBuilder.append(" ");
                        overlayBuilder.append(context.getString(R.string.perf_overlay_ai_delegate));
                        overlayBuilder.append(" ");
                        overlayBuilder.append(Stereo3DRenderer.renderer);
                        overlayBuilder.append(" ");
                        overlayBuilder.append(context.getString(R.string.perf_overlay_drawdelay, Stereo3DRenderer.drawDelay));
                    }
                }
                // --- FULL OVERLAY ---
                else {
                    if (Stereo3DRenderer.isActive) {
                        overlayBuilder.append(context.getString(R.string.perf_overlay_streamdetails,
                                coldCfg.initialWidth + "x" + coldCfg.initialHeight, fps.totalFps));
                        overlayBuilder.append('\n');
                        overlayBuilder.append(" ");
                        overlayBuilder.append(context.getString(R.string.perf_overlay_ai_fps));
                        overlayBuilder.append(" ");
                        overlayBuilder.append(Stereo3DRenderer.threeDFps);
                        overlayBuilder.append(" ");
                        overlayBuilder.append(context.getString(R.string.perf_overlay_ai_delegate));
                        overlayBuilder.append(" ");
                        overlayBuilder.append(Stereo3DRenderer.renderer);
                        overlayBuilder.append(" ");
                        overlayBuilder.append(context.getString(R.string.perf_overlay_drawdelay, Stereo3DRenderer.drawDelay));
                    } else {
                        // If GPU renders the frames, the render FPS is the actual drawn and visible fps for the user
                        overlayBuilder.append(context.getString(R.string.perf_overlay_streamdetails,
                                coldCfg.initialWidth + "x" + coldCfg.initialHeight, fps.totalFps));
                    }
                    overlayBuilder.append('\n');
                    overlayBuilder.append(context.getString(R.string.perf_overlay_decoder, decoder)).append('\n');
                    overlayBuilder.append(context.getString(R.string.perf_overlay_incomingfps, fps.receivedFps)).append('\n');
                    overlayBuilder.append(context.getString(R.string.perf_overlay_renderingfps, fps.renderedFps)).append('\n');
                    float fullLossPct = 0f;
                    if (lastTwo.totalFrames > 0) {
                        fullLossPct = (float) lastTwo.framesLost / (float) lastTwo.totalFrames * 100f;
                    }
                    overlayBuilder.append(context.getString(R.string.perf_overlay_netdrops, fullLossPct)).append('\n');
                    if (TrafficStatsHelper.getPackageRxBytes(Process.myUid()) != TrafficStats.UNSUPPORTED) {
                        long netData = TrafficStatsHelper.getPackageRxBytes(Process.myUid())
                                + TrafficStatsHelper.getPackageTxBytes(Process.myUid());
                        if (lastNetDataNum != 0) {
                            overlayBuilder.append(context.getString(R.string.perf_overlay_lite_bandwidth)).append(": ");
                            float realtimeNetData = (netData - lastNetDataNum) / 1024f;
                            if (realtimeNetData >= 1000) {
                                overlayBuilder.append(String.format("%.2f", realtimeNetData / 1024f)).append("M/s\n");
                            } else {
                                overlayBuilder.append(String.format("%.2f", realtimeNetData)).append("K/s\n");
                            }
                        }
                        lastNetDataNum = netData;
                    }
                    overlayBuilder.append(context.getString(R.string.perf_overlay_netlatency,
                            (int) (rttInfo >> 32), (int) rttInfo)).append('\n');
                    if (lastTwo.framesWithHostProcessingLatency > 0) {
                        overlayBuilder.append(context.getString(R.string.perf_overlay_hostprocessinglatency,
                                        (float) lastTwo.minHostProcessingLatency / 10,
                                        (float) lastTwo.maxHostProcessingLatency / 10,
                                        (float) lastTwo.totalHostProcessingLatency / 10 / lastTwo.framesWithHostProcessingLatency))
                                .append('\n');
                    }
                    overlayBuilder.append(context.getString(R.string.perf_overlay_dectime, decodeTimeMs));
                }

// Append FSR overlay line if available
                try {
                    String __fsr = __fsrGetOverlayLine(glUpscaler);
                    if (__fsr != null && !__fsr.isEmpty()) {
                        if (overlayBuilder.length() > 0 && overlayBuilder.charAt(overlayBuilder.length() - 1) != '\n')
                            overlayBuilder.append('\n');
                        overlayBuilder.append(__fsr).append('\n');
                    }
                } catch (Throwable ignored) {}

                String fullLog = overlayBuilder.toString(); // Keep original for stats

// Create separate string for rendering with OLED transformations
                String renderedLog = fullLog;

// Apply OLED shift if enabled
                if (prefs.enablePerfOverlayLite && prefs.enablePerfOverlayLiteOledShift) {
                    renderedLog = __applyLiteShift(renderedLog, liteShiftSpaces);
                }

// Apply OLED blink if enabled (blank during blink period)
                if (prefs.enablePerfOverlayLite && prefs.enablePerfOverlayLiteOledShift) {
                    try {
                        long now = System.nanoTime();
                        if (liteBlinkEndNs > 0L && now < liteBlinkEndNs) {
                            renderedLog = " "; // Single space = minimal content, effectively blank
                        }
                    } catch (Throwable ignored) {}
                }

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
                coldCfg.spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_VPS) {
                numVpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                coldCfg.vpsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            // Only the HEVC SPS hits this path (H.264 is handled above)
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_SPS) {
                numSpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                coldCfg.spsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if (decodeUnitType == MoonBridge.BUFFER_TYPE_PPS) {
                numPpsIn++;

                // Batch this to submit together with other CSD per AOSP docs
                byte[] naluBuffer = new byte[decodeUnitLength];
                System.arraycopy(decodeUnitData, 0, naluBuffer, 0, decodeUnitLength);
                coldCfg.ppsBuffers.add(naluBuffer);
                return MoonBridge.DR_OK;
            }
            else if ((videoFormat & (MoonBridge.VIDEO_FORMAT_MASK_H264 | MoonBridge.VIDEO_FORMAT_MASK_H265)) != 0) {
                // If this is the first CSD blob or we aren't supporting fused IDR frames, we will
                // submit the CSD blob in a separate input buffer for each IDR frame.
                if (!submittedCsd || !coldCfg.fusedIdrFrame) {
                    if (!fetchNextInputBuffer()) {
                        return MoonBridge.DR_NEED_IDR;
                    }

                    // Submit all CSD when we receive the first non-CSD blob in an IDR frame
                    for (byte[] vpsBuffer : coldCfg.vpsBuffers) {
                        nextInputBuffer.put(vpsBuffer);
                    }
                    for (byte[] spsBuffer : coldCfg.spsBuffers) {
                        nextInputBuffer.put(spsBuffer);
                    }
                    for (byte[] ppsBuffer : coldCfg.ppsBuffers) {
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
                for (byte[] vpsBuffer : coldCfg.vpsBuffers) {
                    nextInputBuffer.put(vpsBuffer);
                }
                for (byte[] spsBuffer : coldCfg.spsBuffers) {
                    nextInputBuffer.put(spsBuffer);
                }
                for (byte[] ppsBuffer : coldCfg.ppsBuffers) {
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
            str += "Configured format: "+renderer.configuredFormat+DELIMITER;
            str += "Input format: "+renderer.inputFormat+DELIMITER;
            str += "Output format: "+renderer.outputFormat+DELIMITER;
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

    // Call after presenting a frame to nudge GPU clocks in Direct Present path
    private void gpuKickPresentHook() {
        try {
            if (gpuKickPbuffer != null && gpuKickPbuffer.isEnabled()) {
                gpuKickPbuffer.kickOnce();
            }
        } catch (Throwable ignored) {}
    }
    // Simple display refresh tracking: detect once, optionally update when the display mode changes.
    private static class DisplayRefreshManager {
        private static final long DEFAULT_VSYNC_PERIOD_NS   = 16_666_667L; // ~60 Hz
        private static final long MIN_VALID_VSYNC_NS        = 4_000_000L;  // 250 Hz
        private static final long MAX_VALID_VSYNC_NS        = 50_000_000L; // 20 Hz

        private final Context appContext;

        // Read-mostly fields, read from render thread
        private volatile long vsyncPeriodNs = DEFAULT_VSYNC_PERIOD_NS;
        private volatile float refreshRateHz = 60f;

        // Keep references so we can unregister on release
        private Object displayManagerRef;  // android.hardware.display.DisplayManager
        private Object displayListenerRef; // android.hardware.display.DisplayManager.DisplayListener
        private boolean released = false;

        DisplayRefreshManager(Context context) {
            this.appContext = context.getApplicationContext();
            detectOnce();
            registerDisplayListenerIfSupported();
        }

        // Detect refresh rate once using the default display
        private void detectOnce() {
            long periodNs = DEFAULT_VSYNC_PERIOD_NS;
            float hz = 60f;

            try {
                android.view.WindowManager wm =
                        (android.view.WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    android.view.Display display = wm.getDefaultDisplay();
                    if (display != null) {
                        float refresh = display.getRefreshRate();
                        if (refresh > 1f) {
                            hz = refresh;
                            long candidate = Math.round(1_000_000_000.0 / (double) refresh);
                            if (candidate >= MIN_VALID_VSYNC_NS && candidate <= MAX_VALID_VSYNC_NS) {
                                periodNs = candidate;
                            }

                        }
                    }
                }
            } catch (Throwable t) {
                // Keep defaults on failure
                LimeLog.warning("DisplayRefreshManager: using default vsync, error=" + t);
            }

            vsyncPeriodNs = periodNs;
            refreshRateHz = hz;
            LimeLog.info("DisplayRefreshManager: initial refresh=" + hz + " Hz, period="
                    + (periodNs / 1_000_000L) + " ms");
        }

        @android.annotation.TargetApi(android.os.Build.VERSION_CODES.JELLY_BEAN_MR1)
        private void registerDisplayListenerIfSupported() {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                return;
            }

            try {
                final android.hardware.display.DisplayManager dm =
                        (android.hardware.display.DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
                if (dm == null) {
                    return;
                }

                final android.view.Display initialDisplay =
                        dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
                if (initialDisplay == null) {
                    return;
                }

                final int defaultDisplayId = initialDisplay.getDisplayId();

                android.hardware.display.DisplayManager.DisplayListener listener =
                        new android.hardware.display.DisplayManager.DisplayListener() {
                            @Override
                            public void onDisplayAdded(int displayId) {
                            }

                            @Override
                            public void onDisplayRemoved(int displayId) {
                            }

                            @Override
                            public void onDisplayChanged(int displayId) {
                                if (displayId != defaultDisplayId) {
                                    return;
                                }
                                try {
                                    android.view.Display display = dm.getDisplay(displayId);
                                    if (display == null) {
                                        return;
                                    }

                                    float refresh = display.getRefreshRate();
                                    if (refresh <= 1f) {
                                        return;
                                    }

                                    long candidate = Math.round(1_000_000_000.0 / (double) refresh);
                                    long currentPeriod = vsyncPeriodNs;
                                    float currentRate = refreshRateHz;

                                    boolean periodChanged =
                                            Math.abs(candidate - currentPeriod) > (currentPeriod * 0.01);
                                    boolean rateChanged =
                                            Math.abs(refresh - currentRate) > 0.5f;

                                    if ((periodChanged || rateChanged)
                                            && candidate >= MIN_VALID_VSYNC_NS
                                            && candidate <= MAX_VALID_VSYNC_NS) {
                                        vsyncPeriodNs = candidate;
                                        refreshRateHz = refresh;
                                        LimeLog.info("DisplayRefreshManager: refresh changed to "
                                                + refresh + " Hz");
                                    }
                                } catch (Throwable t) {
                                    LimeLog.warning("DisplayRefreshManager: error in onDisplayChanged: " + t);
                                }
                            }
                        };

                dm.registerDisplayListener(listener, null);
                displayManagerRef = dm;
                displayListenerRef = listener;
            } catch (Throwable t) {
                LimeLog.warning("DisplayRefreshManager: failed to register listener: " + t);
            }
        }

        long getVsyncPeriodNs() {
            return vsyncPeriodNs;
        }

        float getRefreshRateHz() {
            return refreshRateHz;
        }

        void release() {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                return;
            }
            if (released) {
                return;
            }
            released = true;

            try {
                if (displayManagerRef instanceof android.hardware.display.DisplayManager &&
                        displayListenerRef instanceof android.hardware.display.DisplayManager.DisplayListener) {
                    android.hardware.display.DisplayManager dm =
                            (android.hardware.display.DisplayManager) displayManagerRef;
                    android.hardware.display.DisplayManager.DisplayListener listener =
                            (android.hardware.display.DisplayManager.DisplayListener) displayListenerRef;
                    dm.unregisterDisplayListener(listener);
                }
            } catch (Throwable ignored) {
            } finally {
                displayManagerRef = null;
                displayListenerRef = null;
            }
        }
    }

private boolean isMTKDecoderName(String name) {
    if (name == null) return false;
    String n = name.toLowerCase();
    return n.startsWith("c2.mtk") || n.startsWith("omx.mtk");
}

}