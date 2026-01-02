package com.limelight.binding.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;
import android.util.LongSparseArray;
import com.limelight.BuildConfig;
import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Stereo3DRenderer;
import com.limelight.utils.TrafficStatsHelper;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
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

    // True when new VPS/SPS/PPS has been received since last submission
    private boolean csdDirty = false;

    // --- HDR state for overlays ---
    private volatile boolean hdrActive = false;
    public boolean isHdrActive() { return hdrActive; }
    // CpuWarmUp integration
    private CpuWarmUp cpuWarmUp;
    private boolean cpuWarmUpStarted = false;

// stats
// Decode latency tracking: map PTS(us) -> enqueue time (ns)
    private static final long LATENCY_TRACKING_CLEANUP_THRESHOLD_NS = 30_000_000_000L; // 30 seconds
    private static final int LATENCY_TRACKING_MAX_SIZE = 1000; // Maximum entries in tracking array
    private long lastLatencyTrackingCleanupNs = 0L;

    private final LongSparseArray<Long> enqueueNsByPtsUs = new LongSparseArray<>(64);
    private final Object enqueueNsLock = new Object();

    // Update stats using both decode time (enqueue->dequeue) and end-to-end latency (uptime - PTS)

    private void updateDecodeLatencyStats(long presentationTimeUs) {
        Long enqNs;

        // Thread-safe retrieval and removal
        synchronized (enqueueNsLock) {
            enqNs = enqueueNsByPtsUs.get(presentationTimeUs);
            if (enqNs != null) {
                enqueueNsByPtsUs.remove(presentationTimeUs);
            }
        }

        if (enqNs == null) {
            return;
        }

           long decNs = System.nanoTime() - enqNs;
        long decMs = decNs / 1_000_000L;

        // Also calculate old end-to-end latency for comparison
        long endToEndMs = SystemClock.uptimeMillis() - (presentationTimeUs / 1000L);

        // Update pure decode time stats
        if (decMs >= 0 && decMs < 1000) {
            activeWindowVideoStats.decoderTimeMs += decMs;
        }

        // Update end-to-end latency stats (for backward compatibility and comparison)
        if (endToEndMs >= 0 && endToEndMs < 1000) {
            activeWindowVideoStats.endToEndLatencyMs += endToEndMs;
            if (!USE_FRAME_RENDER_TIME) {
                // Keep backward compatible behavior for totalTimeMs
                activeWindowVideoStats.totalTimeMs += endToEndMs;
            }
        }
    }
    // cleanup method
    private void cleanupOldLatencyTrackingEntries() {
        synchronized (enqueueNsLock) {
            long nowNs = System.nanoTime();
            if (nowNs - lastLatencyTrackingCleanupNs < LATENCY_TRACKING_CLEANUP_THRESHOLD_NS) {
                return;
            }

            int initialSize = enqueueNsByPtsUs.size();

            // Remove entries older than 30 seconds based on enqueue time
            for (int i = enqueueNsByPtsUs.size() - 1; i >= 0; i--) {
                Long enqueueNs = enqueueNsByPtsUs.valueAt(i);
                if (enqueueNs != null && (nowNs - enqueueNs) > LATENCY_TRACKING_CLEANUP_THRESHOLD_NS) {
                    enqueueNsByPtsUs.removeAt(i);
                }
            }

            // Enforce maximum size limit (defensive against accumulation)
            enforceLatencyTrackingSizeLimit();

            lastLatencyTrackingCleanupNs = nowNs;
        }
    }
    // enforce size limits
    private void enforceLatencyTrackingSizeLimit() {
        synchronized (enqueueNsLock) {
            if (enqueueNsByPtsUs.size() > LATENCY_TRACKING_MAX_SIZE) {
                int excess = enqueueNsByPtsUs.size() - LATENCY_TRACKING_MAX_SIZE;
                for (int i = 0; i < excess; i++) {
                    enqueueNsByPtsUs.removeAt(0);
                }
            }
        }
    }

    // end stats //

    // Offload heavy perf overlay formatting off the decode thread
    private final Handler perfOverlayHandler = new Handler(Looper.getMainLooper());
    // Max horizontal shift steps for lite OLED overlay
    private static final int LITE_SHIFT_MAX_SPACES = 8;

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

    private final java.util.concurrent.LinkedBlockingQueue<Integer> asyncInputQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(64);

    private final java.util.concurrent.LinkedBlockingQueue<Integer> asyncOutputQueue =
            new java.util.concurrent.LinkedBlockingQueue<>(8);

    private final android.util.SparseArray<android.media.MediaCodec.BufferInfo> asyncOutInfo =
            new android.util.SparseArray<>(16);

    private boolean preferLowerDelays = false; // Will be set based on frame pacing mode
// ==== End async decoding ====

    private int nextInputBufferIndex = -1;
    private ByteBuffer nextInputBuffer;

    private Context context;
    private Activity activity;
    private MediaCodec videoDecoder;
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

    private float minDecodeTime = Float.MAX_VALUE;
    private String minDecodeTimeFullLog = "";

//    private long lastNetDataNum;
    private volatile long lastNetDataNum;
    private LinkedBlockingQueue<Integer> outputBufferQueue = new LinkedBlockingQueue<>();
    private static final int OUTPUT_BUFFER_QUEUE_LIMIT = 2;
    private long lastRenderedFrameTimeNanos;
    private HandlerThread choreographerHandlerThread;
    private Handler choreographerHandler;

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
        // Do nothing if we're stopping
        if (stopping) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            frameTimeNanos -= activity.getWindowManager().getDefaultDisplay().getAppVsyncOffsetNanos();
        }

        // Don't render unless a new frame is due. This prevents microstutter when streaming
        // at a frame rate that doesn't match the display (such as 60 FPS on 120 Hz).
        long actualFrameTimeDeltaNs = frameTimeNanos - lastRenderedFrameTimeNanos;
        long expectedFrameTimeDeltaNs = 800000000 / refreshRate; // within 80% of the next frame
        if (actualFrameTimeDeltaNs >= expectedFrameTimeDeltaNs) {
            // Render up to one frame when in frame pacing mode.
            //
            // NB: Since the queue limit is 2, we won't starve the decoder of output buffers
            // by holding onto them for too long. This also ensures we will have that 1 extra
            // frame of buffer to smooth over network/rendering jitter.
            Integer nextOutputBuffer = outputBufferQueue.poll();
            if (nextOutputBuffer != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, frameTimeNanos);
                    }
                    else {
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, true);
                    }

                    lastRenderedFrameTimeNanos = frameTimeNanos;
                    activeWindowVideoStats.totalFramesRendered++;
                } catch (IllegalStateException ignored) {
                    try {
                        // Try to avoid leaking the output buffer by releasing it without rendering
                        videoDecoder.releaseOutputBuffer(nextOutputBuffer, false);
                    } catch (IllegalStateException e) {
                        // This will leak nextOutputBuffer, but there's really nothing else we can do
                        e.printStackTrace();
                        handleDecoderException(e);
                    }
                }
            }
        }

        // Attempt codec recovery even if we have nothing to render right now. Recovery can still
        // be required even if the codec died before giving any output.
        doCodecRecoveryIfRequired(CR_FLAG_CHOREOGRAPHER);

        // Request another callback for next frame
        Choreographer.getInstance().postFrameCallback(this);
    }

    private void startChoreographerThread() {
        if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            // Not using Choreographer in this pacing mode
            return;
        }

        // We use a separate thread to avoid any main thread delays from delaying rendering
        choreographerHandlerThread = new HandlerThread("Video - Choreographer", Process.THREAD_PRIORITY_DEFAULT + Process.THREAD_PRIORITY_MORE_FAVORABLE);
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
                BufferInfo info = new BufferInfo();
                final android.media.MediaCodec.BufferInfo lfrInfo = new android.media.MediaCodec.BufferInfo();

                while (!stopping) {

                    // Periodic cleanup of latency tracking data to prevent memory accumulation
                    cleanupOldLatencyTrackingEntries();

                    try {
                        // Try to output a frame
                        int outIndex = nextOutputIndex(info, 50000);
                        if (outIndex >= 0) {
                            long presentationTimeUs = info.presentationTimeUs;
                            int lastIndex = outIndex;

                            numFramesOut++;

                            // Render the latest frame now if frame pacing isn't in balanced mode
                            if (prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
                                // Get the last output buffer in the queue
                                while ((outIndex = nextOutputIndex(info, 0)) >= 0) {
                                    try {
                                        videoDecoder.releaseOutputBuffer(lastIndex, false);
                                    } catch (Throwable ignored) { }
                                    numFramesOut++;
                                    lastIndex = outIndex;
                                    presentationTimeUs = info.presentationTimeUs;
                                }
                                if (lastIndex >= 0) {
                                    try { updateDecodeLatencyStats(presentationTimeUs); } catch (Throwable ignored) {}
                                }
                                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    LimeLog.info("Output EOS received");
                                    // Optional: signal stopping or completion
                                    // stopping = true;
                                    continue;
                                }
// --- Present policy per profilo di pacing ---
                                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW) {
                                    // Immediate present using frame PTS; no decoder-side pacing
                                    if (lastIndex >= 0) {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                            final long tsNs = presentationTimeUs * 1000L;
                                            videoDecoder.releaseOutputBuffer(lastIndex, tsNs);
                                        } else {
                                            videoDecoder.releaseOutputBuffer(lastIndex, /*render*/ true);
                                        }
                                    }
                                }
                                else
                                if (prefs.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS ||
                                        prefs.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
                                    // Never-drop policy (do not hold output buffers)
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        videoDecoder.releaseOutputBuffer(lastIndex, 0);
                                    } else {
                                        videoDecoder.releaseOutputBuffer(lastIndex, true);
                                    }
                                } else {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                        videoDecoder.releaseOutputBuffer(lastIndex, System.nanoTime());
                                    } else {
                                        videoDecoder.releaseOutputBuffer(lastIndex, true);
                                    }
                                }

                                activeWindowVideoStats.totalFramesRendered++;
                            } else {
                                // Balanced: enqueue for Choreographer
                                if (outputBufferQueue.size() == OUTPUT_BUFFER_QUEUE_LIMIT) {
                                    try {
                                        videoDecoder.releaseOutputBuffer(outputBufferQueue.take(), false);
                                    } catch (InterruptedException e) {
                                        return;
                                    } catch (Throwable ignored) { }
                                }
                                outputBufferQueue.add(lastIndex);
                            }
                            // Measure decode latency AT DEQUEUE
                            try { updateDecodeLatencyStats(presentationTimeUs); } catch (Throwable ignored) {}
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                LimeLog.info("Output EOS received");
                                // Optional: signal stopping or completion
                                // stopping = true;
                                continue;
                            }

//                           // Add delta time to the totals (excluding probable outliers)
//                            long delta = SystemClock.uptimeMillis() - (presentationTimeUs / 1000L);
//                            if (delta >= 0 && delta < 1000) {
//                                activeWindowVideoStats.decoderTimeMs += delta;
//                                if (!USE_FRAME_RENDER_TIME) {
//                                    activeWindowVideoStats.totalTimeMs += delta;
//                                }
//                            }
                        } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            // Only handle in sync mode (async handled in callback)
                            if (!useAsyncCodec) {
                                LimeLog.info("Output format changed (sync)");
                                coldCfg.outputFormat = videoDecoder.getOutputFormat();
                                // HDR detection
                                try {
                                    android.media.MediaFormat __fmt = coldCfg.outputFormat;
                                    int __std = -1, __tr = -1, __rng = -1;
                                    try { __std = __fmt.getInteger("color-standard"); } catch (Throwable ignored) {}
                                    try { __tr  = __fmt.getInteger("color-transfer"); } catch (Throwable ignored) {}
                                    try { __rng = __fmt.getInteger("color-range"); } catch (Throwable ignored) {}
                                    // BT.2020 + (PQ o HLG) => HDR
                                    boolean __isHdr =
                                            (__std == android.media.MediaFormat.COLOR_STANDARD_BT2020) &&
                                                    (__tr  == android.media.MediaFormat.COLOR_TRANSFER_ST2084
                                                            || __tr  == android.media.MediaFormat.COLOR_TRANSFER_HLG);
                                    // Update shared flag so overlays/renderer can see it
                                    hdrActive = __isHdr;
                                    // Notify window color mode (no-op <26)
                                    try { com.limelight.Game.updateHdrWindowMode(__isHdr); } catch (Throwable ignored) {}
                                    // Pass HDR static info to GL upscaler if available
                                    java.nio.ByteBuffer __hdr = null;
                                    try { __hdr = __fmt.getByteBuffer("hdr-static-info"); } catch (Throwable ignored) {}
                                    byte[] __hdrArr = null;
                                    if (__hdr != null && __hdr.remaining() > 0) {
                                        __hdrArr = new byte[__hdr.remaining()];
                                        __hdr.get(__hdrArr);
                                    }
                                } catch (Throwable ignored) {}
                                LimeLog.info("New output format: " + coldCfg.outputFormat);
                            }
                        }
                    } catch (IllegalStateException e) {
                        handleDecoderException(e);
                    } finally {
                        doCodecRecoveryIfRequired(CR_FLAG_RENDER_THREAD);
                    }
                }

            }
        };

        rendererThread.setName("Video - Renderer (MediaCodec)");
        rendererThread.setPriority(Thread.NORM_PRIORITY + 2);
        rendererThread.start();
    }

    private boolean fetchNextInputBuffer() {
        final long startNs = System.nanoTime();
        boolean codecRecovered;

        //Check stopping first to avoid false "Hung" exceptions during shutdown
        if (stopping) {
            return false;
        }

        if (nextInputBuffer != null) {
            // We already have an input buffer
            return true;
        }

        try {
            // If we don't have an input buffer index yet, fetch one now
            if (nextInputBufferIndex < 0 && !stopping) {
                final long t0 = System.nanoTime();
                nextInputBufferIndex = nextInputIndex(4000);
                final long elapsedUs = (System.nanoTime() - t0) / 1_000L;

                // Single quick retry if unavailable
                if (nextInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    final int remainingUs = Math.max(0, 4000 - (int) elapsedUs);
                    final int quickBackoffUs = Math.min(remainingUs, 1000);
                    if (quickBackoffUs > 0) {
                        nextInputBufferIndex = nextInputIndex(quickBackoffUs);
                    }
                }
            }

            // Get the backing ByteBuffer for the input buffer index
            if (nextInputBufferIndex >= 0) {
                // Reset tracking on success
                inputTryAgainStreak = 0;
                inputDequeueHangStartMs = 0L;

                // Using the new getInputBuffer() API on Lollipop allows
                // the framework to do some performance optimizations for us
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    nextInputBuffer = videoDecoder.getInputBuffer(nextInputBufferIndex);
                    if (nextInputBuffer == null) {
                        // Treat null as codec contract violation
                        inputTryAgainStreak = 0;
                        inputDequeueHangStartMs = 0L;
                        nextInputBufferIndex = -1;

                        // Trigger error handler
                        handleDecoderException(new IllegalStateException(
                                "getInputBuffer() returned null for index " + nextInputBufferIndex));
                        return false;
                    }
                    // Ensure clean buffer state on Lollipop+
                    nextInputBuffer.clear();
                } else {
                    nextInputBuffer = coldCfg.legacyInputBuffers[nextInputBufferIndex];
                    // Clear old input data pre-Lollipop
                    nextInputBuffer.clear();
                }
            }
        } catch (IllegalStateException e) {
            // Reset tracking on exception
            inputTryAgainStreak = 0;
            inputDequeueHangStartMs = 0L;
            nextInputBufferIndex = -1;
            nextInputBuffer = null;

            handleDecoderException(e);
            return false;
        } finally {
            codecRecovered = doCodecRecoveryIfRequired(CR_FLAG_INPUT_THREAD);
        }

        // If codec recovery is required, always return false to ensure the caller will request
        // an IDR frame to complete the codec recovery.
        if (codecRecovered) {
            inputTryAgainStreak = 0;
            inputDequeueHangStartMs = 0L;
            nextInputBufferIndex = -1;
            nextInputBuffer = null;
            return false;
        }

        // Hung detection - check if we're still waiting after attempts
        if (nextInputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
            inputTryAgainStreak++;
            final long nowMs = SystemClock.uptimeMillis();

            if (inputDequeueHangStartMs == 0L) {
                inputDequeueHangStartMs = nowMs;
            } else if ((nowMs - inputDequeueHangStartMs) >= 5000 && initialException == null) {
                // Decoder hung for 5 seconds total
                DecoderHungException decoderHungException =
                        new DecoderHungException((int) (nowMs - inputDequeueHangStartMs));
                if (!coldCfg.reportedCrash) {
                    coldCfg.reportedCrash = true;
                    crashListener.notifyCrash(decoderHungException);
                }
                throw new RendererException(this, decoderHungException);
            }

            return false;
        }

        // Log long dequeues (>20ms)
        final long dtNs = System.nanoTime() - startNs;
        if (dtNs >= 20_000_000L) { // 20 ms
            LimeLog.warning("Dequeue input buffer ran long: " + (dtNs / 1_000_000L) + " ms");
        }

        // Return success if buffer obtained
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


        startRendererThread();
        startChoreographerThread();
    }

    // !!! May be called even if setup()/start() fails !!!
    public void prepareForStop() {
        // Let the decoding code know to ignore codec exceptions now
        stopping = true;

        // Clear async queues
        try { detachAsyncCodec(); } catch (Throwable ignored) {}

        // Clear frame pacing queue
        outputBufferQueue.clear();

        // Clear decode latency tracking to prevent memory leaks
        enqueueNsByPtsUs.clear();

        // Clear output buffer queue
        outputBufferQueue.clear();

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
        // May be called already, but we'll call it now to be safe
        prepareForStop();
        // Final CpuWarmUp stop check
        if (cpuWarmUp != null && cpuWarmUpStarted) {
            try {
                cpuWarmUp.stop();
                cpuWarmUpStarted = false;
            } catch (Throwable ignored) {}
        }

        synchronized (enqueueNsLock) {
            enqueueNsByPtsUs.clear();
        }

        // Final async cleanup
        try { detachAsyncCodec(); } catch (Throwable ignored) {}

        // Reset input buffer state to avoid stale state on next start
        resetInputBufferState();

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

        // Clear decode latency tracking to prevent memory leaks
        synchronized (enqueueNsLock) {
            enqueueNsByPtsUs.clear();
        }

        // Ensure async resources are released
        try { detachAsyncCodec(); } catch (Throwable ignored) {}

        // Clear CSD buffers
        coldCfg.vpsBuffers.clear();
        coldCfg.spsBuffers.clear();
        coldCfg.ppsBuffers.clear();

        // Reset input buffer state to avoid stale state on next start
        resetInputBufferState();

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
                    synchronized (enqueueNsLock) {
                        enqueueNsByPtsUs.put(timestampUs, System.nanoTime());
                    }
                }
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

// Calculate both latency metrics for display
                float decodeTimeMs = 0f;
                float endToEndTimeMs;

                if (lastTwo.totalFramesReceived > 0) {
                    decodeTimeMs = (float) lastTwo.decoderTimeMs / (float) lastTwo.totalFramesReceived;
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

                    perfOverlayHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            // Capture additional UI-safe values before processing
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
                sb.append(context.getString(R.string.perf_overlay_lite_e2e, endToEndTimeMs));
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
                if (prefsSnapshot.gpuPathMode) {
                    sb.append('G');
                }
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
            sb.append(context.getString(R.string.perf_overlay_e2etime, endToEndTimeMs));
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
                sb.insert(0, new String(new char[Math.max(0, liteShiftSpaces)]).replace('\0', ' '));
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
            return videoDecoder.dequeueOutputBuffer(outInfo, timeoutUs);
        }
        try {
            Integer idx = asyncOutputQueue.poll(timeoutUs, java.util.concurrent.TimeUnit.MICROSECONDS);
            if (idx == null) return -1;
            synchronized (asyncOutInfo) {
                android.media.MediaCodec.BufferInfo bi = asyncOutInfo.get(idx);
                if (bi == null) {
                    // Already cleaned up, buffer likely released
                    return -1;
                }
                if (outInfo != null) {
                    outInfo.set(bi.offset, bi.size, bi.presentationTimeUs, bi.flags);
                }
                asyncOutInfo.remove(idx);
            }
            return idx;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }
    private void attachAsyncCodecIfNeeded() {
        if (!useAsyncCodec || videoDecoder == null) return;

        preferLowerDelays = (prefs != null &&
                prefs.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED);

        if (codecCallbackThread == null) {
            codecCallbackThread = new android.os.HandlerThread(
                    "CodecAsync", android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            codecCallbackThread.start();
        }
        android.os.Handler cb = new android.os.Handler(codecCallbackThread.getLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            videoDecoder.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(MediaCodec codec, int index) {
                    try {
                        asyncInputQueue.put(index);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable ignored) { }
                }


                @Override
                public void onOutputBufferAvailable(MediaCodec codec, int index,
                                                    BufferInfo info) {
                    try {
                        synchronized (asyncOutInfo) {
                            asyncOutInfo.put(index, cloneInfo(info));
                        }

                        // LFR/ULL path: keep only latest buffer
                        if (preferLowerDelays) {
                            Integer old;
                            while ((old = asyncOutputQueue.poll()) != null) {
                                try {
                                    codec.releaseOutputBuffer(old, false);
                                } catch (Throwable ignored) {}
                                synchronized (asyncOutInfo) {
                                    asyncOutInfo.remove(old);
                                }
                            }
                            if (!asyncOutputQueue.offer(index)) {
                                try {
                                    codec.releaseOutputBuffer(index, false);
                                } catch (Throwable ignored) {}
                                synchronized (asyncOutInfo) {
                                    asyncOutInfo.remove(index);
                                }
                            }
                            return;
                        }

                        // Managed profiles: bounded queue with drop-oldest policy
                        if (!asyncOutputQueue.offer(index)) {
                            Integer old = asyncOutputQueue.poll();
                            if (old != null) {
                                try {
                                    codec.releaseOutputBuffer(old, false);
                                } catch (Throwable ignored) {}
                                synchronized (asyncOutInfo) {
                                    asyncOutInfo.remove(old);
                                }
                            }
                            if (!asyncOutputQueue.offer(index)) {
                                try {
                                    codec.releaseOutputBuffer(index, false);
                                } catch (Throwable ignored) {}
                                synchronized (asyncOutInfo) {
                                    asyncOutInfo.remove(index);
                                }
                            }
                        }
                    } catch (Throwable ignored) { }
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
            // Clear queues first
            try { asyncInputQueue.clear(); } catch (Throwable ignored) {}
            try { asyncOutputQueue.clear(); } catch (Throwable ignored) {}

            // Clear asyncOutInfo with synchronization
            synchronized (asyncOutInfo) {
                try { asyncOutInfo.clear(); } catch (Throwable ignored) {}
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

        StringBuilder glyph = new StringBuilder(2);

        // GpuRaw
        if (p.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW) {
            glyph.append('R');
        }
        // Latency
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY) {
            glyph.append('L');
        }
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED) {
            glyph.append('B');
        }
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS) {
            glyph.append('S');
        }
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            glyph.append('C');
        }
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_WARP) {
            glyph.append('W');
        }
        else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_WARP2) {
            glyph.append('2'); // W2 per differenziare
        }
        else {
            glyph.append('?');
        }

        // Aggiungi 'U' se FSR è attivo
        if (isFsrActive) {
            glyph.append('U');
        }

        return glyph.toString();
    }

}