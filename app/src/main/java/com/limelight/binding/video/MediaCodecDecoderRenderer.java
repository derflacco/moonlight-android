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



    // True when new VPS/SPS/PPS has been received since last submission
    private boolean csdDirty = false;

    // --- HDR state for overlays ---
    private volatile boolean hdrActive = false;
    public boolean isHdrActive() { return hdrActive; }
    // CpuWarmUp integration
    private CpuWarmUp cpuWarmUp;
    private boolean cpuWarmUpStarted = false;

    // stats
    private final DecodeLatencyTracker decodeLatencyTracker = new DecodeLatencyTracker();

    // Offload heavy perf overlay formatting off the decode thread
    private final Handler perfOverlayHandler = new Handler(Looper.getMainLooper());
    // Throttle overlay updates to reduce main-thread churn
    private static final long PERF_OVERLAY_DISPATCH_INTERVAL_NS = 100_000_000L; // 100 ms
    private long lastPerfOverlayDispatchNs = 0L;
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
    // Async callback mode is selectable at runtime via SharedPreferences.
    // NOTE: MediaCodec's callback mode must be decided before (re)configure, so toggling this
    // will request a decoder restart (not an in-place switch).
    private static final boolean ENABLE_ASYNC_DECODING = true;
    private static final String KEY_ASYNC_DECODE_ENABLED = "checkbox_async_decode";
    private static final long ASYNC_DECODE_POLL_INTERVAL_NS = 500_000_000L; // 500 ms
    // Async drop-policy (latest-only) toggle.
    private static final String KEY_ASYNC_PREFER_LOWER_DELAYS = "checkbox_async_prefer_lower_delays";

    // Current codec mode (must not change while codec is executing).
    private boolean useAsyncCodec = ENABLE_ASYNC_DECODING &&
            (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M);

    // Desired mode requested by UI/overlay (applied on next codec restart/reset).
    private volatile boolean requestedUseAsyncCodec = useAsyncCodec;
    private long nextAsyncDecodePollNs = 0L;

    private final AsyncCodecAdapter asyncCodec = new AsyncCodecAdapter();

    private boolean computeAsyncPreferLowerDelaysFromCurrentPrefs(int effectivePacing) {
        // Default heuristic (NOT tied to immediateFrameDelivery):
        // - Latency-oriented profiles benefit from "latest-only" output behavior.
        // - Smoothness-oriented profiles should keep the bounded queue behavior.
        final boolean def;
        switch (effectivePacing) {
            case PreferenceConfiguration.FRAME_PACING_GPU_RAW:
            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
            case PreferenceConfiguration.FRAME_PACING_WARP:
            case PreferenceConfiguration.FRAME_PACING_WARP2:
                def = true;
                break;
            default:
                def = false;
                break;
        }

        return readBooleanOverlayFirst(KEY_ASYNC_PREFER_LOWER_DELAYS, def);
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
                decodeLatencyTracker.onDequeue(activeWindowVideoStats, presentationTimeUs, dequeueNs, USE_FRAME_RENDER_TIME);
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
    private final PerfOverlayComposer perfOverlayComposer = new PerfOverlayComposer();

    // Fetchinputbuffer utils:
    private long inputDequeueHangStartMs = 0L;
    private int inputTryAgainStreak = 0;
    private void resetInputBufferState() {
        inputTryAgainStreak = 0;
        inputDequeueHangStartMs = 0L;
        nextInputBufferIndex = -1;
        nextInputBuffer = null;
    }

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

        final boolean customEnabled = sp.getBoolean(
                "checkbox_custom_decoder_output_timeout_us",
                false);

        final int dequeueUs = clampInt(
                safeGetInt(sp, "seekbar_decoder_output_timeout_us", 50000),
                0, 50000);

        runtimeOutputDequeueTimeoutCustomEnabled = customEnabled;
        runtimeOutputDequeueTimeoutUs = dequeueUs;
        // Live-update async drop-policy toggle (safe at runtime).
        if (useAsyncCodec) {
            updateAsyncPreferLowerDelaysFromCurrentPrefs(getEffectivePacingForThreadPriorities());
        }

        // Keep the runtime snapshot in sync for code paths that read from PreferenceConfiguration.
        // Drain timeout is fixed to 0 (UI removed).
        if (prefs != null) {
            prefs.decoderOutputDequeueTimeoutCustom = customEnabled;
            prefs.decoderOutputDequeueTimeoutUs = dequeueUs;
        }
    }

    // ---- Runtime async decode toggle (requires decoder restart) ----
    private boolean computeDesiredUseAsyncCodecFromPrefs() {
        // Overlay-first, UI fallback. Default is enabled.
        final boolean userEnabled = readBooleanOverlayFirst(KEY_ASYNC_DECODE_ENABLED, true);
        return ENABLE_ASYNC_DECODING
                && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                && userEnabled;
    }

    private void initAsyncDecodingFromSettings() {
        requestedUseAsyncCodec = computeDesiredUseAsyncCodecFromPrefs();
        useAsyncCodec = requestedUseAsyncCodec; // Safe before codec is configured
        nextAsyncDecodePollNs = 0L;
    }

    private void maybeApplyRuntimeAsyncDecodingRequest() {
        final long nowNs = System.nanoTime();
        if (nowNs < nextAsyncDecodePollNs) {
            return;
        }
        nextAsyncDecodePollNs = nowNs + ASYNC_DECODE_POLL_INTERVAL_NS;

        final boolean desired = computeDesiredUseAsyncCodecFromPrefs();
        if (desired == requestedUseAsyncCodec) {
            return;
        }

        requestedUseAsyncCodec = desired;

        // Apply only via restart/reset. Do NOT flip useAsyncCodec while executing.
        if (!stopping && videoDecoder != null) {
            if (codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_NONE, CR_RECOVERY_TYPE_RESTART) ||
                    codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_RESTART)) {
                LimeLog.info("Async decode mode change requested -> decoder restart (desired=" +
                        (desired ? "async" : "sync") + ")");
            }
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

    private volatile boolean runtimeOutputDequeueTimeoutCustomEnabled = false;
    private volatile int runtimeOutputDequeueTimeoutUs = 50000;

    // Adaptive output dequeue tuning (µs)
    private static final int MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US = 500;
    private static final int MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US = 3000;
    private static final int MAX_SMOOTH_ADAPTIVE_STEP_US = 250;

    // Starts small; auto-tunes at runtime (adaptive modes)
    private volatile int maxSmoothAdaptiveDequeueTimeoutUs = 500;
    private volatile int balancedAdaptiveDequeueTimeoutUs = 500;
    private volatile int capFpsAdaptiveDequeueTimeoutUs = 500;


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
            glUpscaler.release();
            glUpscaler = null;
        }

        this.renderTarget = renderTarget;

        // Re-apply presentation hint to upscaler when render target may change
        if (glUpscaler != null) {
            glUpscaler.setPresentationHint(context);
        }
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
        runtimeOutputDequeueTimeoutCustomEnabled = (prefs != null) && prefs.decoderOutputDequeueTimeoutCustom;
        runtimeOutputDequeueTimeoutUs = (prefs != null) ? prefs.decoderOutputDequeueTimeoutUs : 50000;
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


        // If GL upscaling is enabled, configure decoder to output to the upscaler's input surface.
        Surface __codecSurface = renderTarget;

        if (prefs != null && prefs.videoUpscaleEnable) {
            if (glUpscaler == null) {
                glUpscaler = new GlUpscalerBridge();
            }

            if (glUpscaler.ensureCreated(renderTarget, coldCfg.initialWidth, coldCfg.initialHeight, prefs, context)) {
                final Surface in = glUpscaler.getDecoderInputSurface();
                if (in != null) {
                    __codecSurface = in;
                }
            } else {
                // Hard fallback: upscaler unavailable or init failed
                try { glUpscaler.release(); } catch (Throwable ignored) { }
                glUpscaler = null;
            }
        }

        videoDecoder.configure(format, __codecSurface, null, 0);

        // Enable async callbacks after configure (before start)
        try { attachAsyncCodecIfNeeded(); } catch (Throwable ignored) {}

        // Start GL upscaler loop if present
        if (glUpscaler != null) {
            glUpscaler.start();

            // Apply user VSync setting (checkbox_Vsync)
            applyUpscalerVsyncSettingIfSupported();

            boolean dbg = false;
            if (prefs != null) {
                dbg = prefs.enablePerfOverlayLite
                        && prefs.enablePerfOverlayLiteAdvanced
                        && prefs.videoUpscaleEnable
                        && !prefs.gpuPathMode;
            }
            glUpscaler.setDebugEnabled(dbg);
        }

        coldCfg.configuredFormat = format;

        // After reconfiguration, we must resubmit CSD buffers
        coldCfg.submittedCsd = false;
        coldCfg.vpsBuffers.clear();
        coldCfg.spsBuffers.clear();
        coldCfg.ppsBuffers.clear();

        // Clear per-frame trackers when decoder is reconfigured
        decodeLatencyTracker.clear();
        perfOverlayComposer.reset();


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
        initAsyncDecodingFromSettings();
        return initializeDecoder(false);
    }
    private GlUpscalerBridge glUpscaler;

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

                // Async
                try { asyncCodec.quiesceAndRelease(videoDecoder, asyncOutputRelease); } catch (Throwable ignored) { }

                // Clear decode latency tracking during codec recovery
                decodeLatencyTracker.clear();

                csdDirty = false;
                // If we just need a flush, do so now with all threads quiesced.
                if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH) {
                    LimeLog.warning("Flushing decoder");
                    try {
                        videoDecoder.flush();

                        // Resume only if we are still in FLUSH (no concurrent promotion to RESTART/RESET)
                        if (codecRecoveryType.get() == CR_RECOVERY_TYPE_FLUSH &&
                                useAsyncCodec && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            videoDecoder.start();
                        }

                        // Only clear if we are still in FLUSH
                        codecRecoveryType.compareAndSet(CR_RECOVERY_TYPE_FLUSH, CR_RECOVERY_TYPE_NONE);
                    } catch (IllegalStateException e) {
                        e.printStackTrace();
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
                        // Async \ Sync
                        useAsyncCodec = requestedUseAsyncCodec;

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
                        // Async \ Sync
                        useAsyncCodec = requestedUseAsyncCodec;
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
                    // Async \ Sync
                    useAsyncCodec = requestedUseAsyncCodec;
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
                    maybeApplyRuntimeAsyncDecodingRequest();
                    // Live-tune decoder timeouts from app settings
                    maybeReloadDecoderTimingPrefs();
                    // Drain async no-render releases here to avoid output backpressure and callback-thread binder calls.
                    drainAsyncNoRenderReleaseQueue();

                    // Timeout: 0 for immediate delivery, otherwise user-configurable wait
                    final int pacingMode = (prefs != null)
                            ? prefs.framePacing
                            : PreferenceConfiguration.FRAME_PACING_BALANCED;

                    final boolean isBalanced =
                            (pacingMode == PreferenceConfiguration.FRAME_PACING_BALANCED);
                    final boolean isMaxSmooth =
                            (pacingMode == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS);
                    final boolean isCapFps =
                            (pacingMode == PreferenceConfiguration.FRAME_PACING_CAP_FPS);

                    final boolean isAdaptiveMode = isBalanced || isMaxSmooth || isCapFps;

                    final boolean customTimeoutEnabled = runtimeOutputDequeueTimeoutCustomEnabled;
                    final int uiTimeoutUs = runtimeOutputDequeueTimeoutUs;

                    final int firstOutTimeoutUs;

                    if (prefs != null && prefs.immediateFrameDelivery) {
                        // UI wins globally: force PURE dequeue (0 µs)
                        firstOutTimeoutUs = 0;
                    } else if (isAdaptiveMode) {
                        // Adaptive modes: MAX_SMOOTHNESS / BALANCED / CAP_FPS
                        // If customization is enabled, UI value acts as a cap (0 => pure non-blocking).
                        final int uiCapUsRaw = customTimeoutEnabled ? uiTimeoutUs : MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US;

                        if (uiCapUsRaw <= 0) {
                            firstOutTimeoutUs = 0;
                        } else {
                            final int capUs = Math.max(
                                    MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                    Math.min(MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US, uiCapUsRaw));

                            final int adaptiveUs = isMaxSmooth
                                    ? maxSmoothAdaptiveDequeueTimeoutUs
                                    : (isBalanced ? balancedAdaptiveDequeueTimeoutUs : capFpsAdaptiveDequeueTimeoutUs);

                            final int curUs = Math.max(
                                    MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                    Math.min(capUs, adaptiveUs));

                            firstOutTimeoutUs = curUs;
                        }
                    } else if (!customTimeoutEnabled) {
                        // Built-in policy when customization is OFF.
                        switch (pacingMode) {
                            case PreferenceConfiguration.FRAME_PACING_GPU_RAW:
                            case PreferenceConfiguration.FRAME_PACING_MIN_LATENCY:
                            case PreferenceConfiguration.FRAME_PACING_WARP:
                            case PreferenceConfiguration.FRAME_PACING_WARP2:
                                firstOutTimeoutUs = 500;
                                break;
                            default:
                                // Preserve legacy default behavior for other modes
                                firstOutTimeoutUs = 50000;
                                break;
                        }
                    } else {
                        // Custom enabled: honor UI directly (0 allowed)
                        firstOutTimeoutUs = Math.max(0, uiTimeoutUs);
                    }

                    // Throttle cleanup to avoid performance spikes
                    final long nowNs = System.nanoTime();
                    if (nowNs - lastCleanupNs > CLEANUP_INTERVAL_NS) {
                        decodeLatencyTracker.maybeCleanup();

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

                        // Track dequeue time for the newest output buffer (latest-only)
                            final long nowNsLocal = System.nanoTime();
                            long lastDequeueTimeNs = (useAsyncCodec && asyncCodec.getLastOutputReadyNs() != 0L)
                                    ? asyncCodec.getLastOutputReadyNs()
                                    : nowNsLocal;

                        // Measure decode latency for the first dequeued buffer too
                            try { decodeLatencyTracker.onDequeue(activeWindowVideoStats, presentationTimeUs, lastDequeueTimeNs, USE_FRAME_RENDER_TIME); }
                            catch (Throwable ignored) { }

                            if (!isBalanced) {
                                if (isMaxSmooth || isCapFps) {

                                    // AdaptX Smooth-style: short first dequeue already done (firstOutTimeoutUs),
                                    // then chase newest with non-blocking follow-ups and a soft cap.
                                    int drained = 1; // includes the already dequeued buffer
                                    boolean drainedMultiple = false;

                                    final int drainSoftCap = (firstOutTimeoutUs <= 500) ? 4 : 3;

                                    while (drained < drainSoftCap &&
                                            (outIndex = nextOutputIndex(info, 0)) >= 0) {

                                        final long thisDequeueTimeNs = (useAsyncCodec && asyncCodec.getLastOutputReadyNs() != 0L)
                                                ? asyncCodec.getLastOutputReadyNs()
                                                : nowNsLocal;

                                        try { releaseOutputBufferNoRenderLocked(videoDecoder, lastIndex); }
                                        catch (Throwable ignored) { }

                                        numFramesOut++;
                                        lastIndex = outIndex;
                                        presentationTimeUs = info.presentationTimeUs;
                                        lastFlags = info.flags;

                                        // Measure decode latency for this dequeued buffer
                                        try { decodeLatencyTracker.onDequeue(activeWindowVideoStats, presentationTimeUs, thisDequeueTimeNs, USE_FRAME_RENDER_TIME); }
                                        catch (Throwable ignored) { }

                                        lastDequeueTimeNs = thisDequeueTimeNs;

                                        drained++;
                                        drainedMultiple = true;
                                    }

                                    // Adaptive timeout tuning (MAX_SMOOTHNESS / CAP_FPS), respecting UI cap when customization is enabled.
                                    final int uiCapUsRaw = runtimeOutputDequeueTimeoutCustomEnabled
                                            ? runtimeOutputDequeueTimeoutUs
                                            : MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US;

                                    if (uiCapUsRaw > 0 && prefs != null && !prefs.immediateFrameDelivery) {
                                        final int capUs = Math.max(
                                                MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                                Math.min(MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US, uiCapUsRaw));

                                        final boolean hitSoftCap = (drained >= drainSoftCap);

                                        int adaptiveUs = isMaxSmooth ? maxSmoothAdaptiveDequeueTimeoutUs : capFpsAdaptiveDequeueTimeoutUs;

                                        if (hitSoftCap || drainedMultiple) {
                                            adaptiveUs = Math.max(
                                                    MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                                    Math.min(capUs, adaptiveUs - MAX_SMOOTH_ADAPTIVE_STEP_US));
                                        } else {
                                            adaptiveUs = Math.max(
                                                    MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                                    Math.min(capUs, adaptiveUs + MAX_SMOOTH_ADAPTIVE_STEP_US));
                                        }

                                        if (isMaxSmooth) {
                                            maxSmoothAdaptiveDequeueTimeoutUs = adaptiveUs;
                                        } else {
                                            capFpsAdaptiveDequeueTimeoutUs = adaptiveUs;
                                        }
                                    }

                                } else {
                                    // Existing behavior for other pacing modes
                                    // to avoid large bursts of releaseOutputBuffer() binder calls.
                                    int drained = 1;
                                    final int drainSoftCap = 6;
                                    while (drained < drainSoftCap &&
                                            (outIndex = nextOutputIndex(info, 0)) >= 0) {

                                        final long thisDequeueTimeNs = (useAsyncCodec && asyncCodec.getLastOutputReadyNs() != 0L)
                                                ? asyncCodec.getLastOutputReadyNs()
                                                : nowNsLocal;

                                        try { releaseOutputBufferNoRenderLocked(videoDecoder, lastIndex); }
                                        catch (Throwable ignored) { }

                                        numFramesOut++;
                                        lastIndex = outIndex;
                                        presentationTimeUs = info.presentationTimeUs;
                                        lastFlags = info.flags;

                                        // Measure decode latency per-buffer at dequeue time (more stable samples)
                                        try { decodeLatencyTracker.onDequeue(activeWindowVideoStats, presentationTimeUs, thisDequeueTimeNs, USE_FRAME_RENDER_TIME); }
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
                                // Balanced: latest-only + adaptive tuning, then enqueue for Choreographer (bounded queue)
                                int drained = 1;
                                boolean drainedMultiple = false;

                                final int drainSoftCap = (firstOutTimeoutUs <= 500) ? 4 : 3;

                                while (drained < drainSoftCap &&
                                        (outIndex = nextOutputIndex(info, 0)) >= 0) {

                                    final long thisDequeueTimeNs = (useAsyncCodec && asyncCodec.getLastOutputReadyNs() != 0L)
                                            ? asyncCodec.getLastOutputReadyNs()
                                            : nowNsLocal;

                                    try { releaseOutputBufferNoRenderLocked(videoDecoder, lastIndex); }
                                    catch (Throwable ignored) { }

                                    numFramesOut++;
                                    lastIndex = outIndex;
                                    presentationTimeUs = info.presentationTimeUs;
                                    lastFlags = info.flags;

                                    try { decodeLatencyTracker.onDequeue(activeWindowVideoStats, presentationTimeUs, thisDequeueTimeNs, USE_FRAME_RENDER_TIME); }
                                    catch (Throwable ignored) { }

                                    lastDequeueTimeNs = thisDequeueTimeNs;

                                    drained++;
                                    drainedMultiple = true;
                                }

                                final int uiCapUsRaw = runtimeOutputDequeueTimeoutCustomEnabled
                                        ? runtimeOutputDequeueTimeoutUs
                                        : MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US;

                                if (uiCapUsRaw > 0 && prefs != null && !prefs.immediateFrameDelivery) {
                                    final int capUs = Math.max(
                                            MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                            Math.min(MAX_SMOOTH_ADAPTIVE_MAX_TIMEOUT_US, uiCapUsRaw));

                                    final boolean hitSoftCap = (drained >= drainSoftCap);

                                    int adaptiveUs = balancedAdaptiveDequeueTimeoutUs;

                                    if (hitSoftCap || drainedMultiple) {
                                        adaptiveUs = Math.max(
                                                MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                                Math.min(capUs, adaptiveUs - MAX_SMOOTH_ADAPTIVE_STEP_US));
                                    } else {
                                        adaptiveUs = Math.max(
                                                MAX_SMOOTH_ADAPTIVE_MIN_TIMEOUT_US,
                                                Math.min(capUs, adaptiveUs + MAX_SMOOTH_ADAPTIVE_STEP_US));
                                    }

                                    balancedAdaptiveDequeueTimeoutUs = adaptiveUs;
                                }

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
        decodeLatencyTracker.clear();


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

        // Ensure decoder and any GL upscaler resources are released
        if (glUpscaler != null) {
            glUpscaler.release();
            glUpscaler = null;
        }

        if (videoDecoder != null) {
            try { videoDecoder.release(); } catch (Throwable ignored) {}
            videoDecoder = null;
        }

        perfOverlayComposer.reset();

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
        if (glUpscaler != null) {
            glUpscaler.applyVsyncSetting();
        }
    }

    private void applyUpscalerThreadPrioritiesIfSupported() {
        if (glUpscaler != null) {
            glUpscaler.applyThreadPriorities();
        }
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
        // Keep async callback drop-policy in sync with the active pacing mode + user setting.
        updateAsyncPreferLowerDelaysFromCurrentPrefs(newPacing);

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
        updateAsyncPreferLowerDelaysFromCurrentPrefs(effective);
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
        // Update async drop-policy even when pacing doesn't change (e.g., async toggle change).
        updateAsyncPreferLowerDelaysFromCurrentPrefs(effective);

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

        decodeLatencyTracker.clear();
        perfOverlayComposer.reset();

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
        if (glUpscaler != null) {
            glUpscaler.release();
            glUpscaler = null;
        }
    }

    @Override
    public void cleanup() {

        // Clear decode latency tracking to prevent memory leaks
        decodeLatencyTracker.clear();
        perfOverlayComposer.reset();

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
        drainOutputBufferQueueNoRender();
        outputBufferQueue.clear();

        // Stop CpuWarmUp
        if (cpuWarmUp != null) {
            try {
                cpuWarmUp.stop();
                cpuWarmUpStarted = false;
            } catch (Throwable ignored) {}
        }

        // Ensure decoder and any GL upscaler resources are released
        if (glUpscaler != null) {
            glUpscaler.release();
            glUpscaler = null;
        }
        if (videoDecoder != null) {
            try { videoDecoder.release(); } catch (Throwable ignored) {}
            videoDecoder = null;
        }
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
                    decodeLatencyTracker.onEnqueue(timestampUs);
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

                                final boolean isFsrActive =
                                        prefsSnapshot.videoUpscaleEnable && glUpscaler != null && glUpscaler.isReady();
                                final float fsrWeightMs = isFsrActive ? glUpscaler.getWeightMs() : 0f;

                                final PerfOverlayComposer.Result overlay = perfOverlayComposer.build(
                                        context,
                                        prefsSnapshot,
                                        lastTwoSnapshot,
                                        fpsSnapshot,
                                        decodeTimeMsSnapshot,
                                        rttInfoSnapshot,
                                        decoderSnapshot,
                                        endToEndTimeMsSnapshot,
                                        hdrActiveSnapshot,
                                        coldCfg.initialWidth,
                                        coldCfg.initialHeight,
                                        isFsrActive,
                                        fsrWeightMs
                                );

                                // Notify overlay listener
                                if (perfListener != null && prefsSnapshot.enablePerfOverlay) {
                                    perfListener.onPerfUpdate(overlay.renderedLog);
                                }

                                // Track best decode time at target FPS
                                boolean targetFpsMatched = ((int) fpsSnapshot.totalFps == (int) prefsSnapshot.fps);
                                if (minDecodeTime > decodeTimeMsSnapshot && targetFpsMatched) {
                                    minDecodeTime = decodeTimeMsSnapshot;
                                    minDecodeTimeFullLog = overlay.fullLog;
                                }
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

    // Async Decoding Helpers

    private final AsyncCodecAdapter.OutputRelease asyncOutputRelease = new AsyncCodecAdapter.OutputRelease() {
        @Override
        public void releaseNoRender(MediaCodec codec, int index) {
            try { releaseOutputBufferNoRenderLocked(codec, index); } catch (Throwable ignored) { }
        }
    };

    private final AsyncCodecAdapter.Callbacks asyncCallbacks = new AsyncCodecAdapter.Callbacks() {
        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            try {
                coldCfg.outputFormat = codec.getOutputFormat();
                LimeLog.info("Output format changed (async): " + coldCfg.outputFormat);

                // HDR detection (same logic as existing sync path)
                try {
                    MediaFormat __fmt = coldCfg.outputFormat;
                    int __std = -1, __tr = -1, __rng = -1;
                    try { __std = __fmt.getInteger("color-standard"); } catch (Throwable ignored) {}
                    try { __tr  = __fmt.getInteger("color-transfer"); } catch (Throwable ignored) {}
                    try { __rng = __fmt.getInteger("color-range"); } catch (Throwable ignored) {}

                    boolean __isHdr =
                            (__std == MediaFormat.COLOR_STANDARD_BT2020) &&
                                    (__tr == MediaFormat.COLOR_TRANSFER_ST2084 ||
                                            __tr == MediaFormat.COLOR_TRANSFER_HLG);

                    hdrActive = __isHdr;
                    try { Game.updateHdrWindowMode(__isHdr); } catch (Throwable ignored) {}

                    // (Optional) hdr-static-info extraction remains local if you later forward it to GL.
                    try {
                        java.nio.ByteBuffer __hdr = null;
                        try { __hdr = __fmt.getByteBuffer("hdr-static-info"); } catch (Throwable ignored) {}
                        if (__hdr != null) {
                            __hdr.position(__hdr.position()); // no-op, keep best-effort
                        }
                    } catch (Throwable ignored) { }

                } catch (Throwable ignored) { }

                LimeLog.info("New output format: " + coldCfg.outputFormat);
            } catch (Throwable ignored) { }
        }

        @Override
        public void onCodecError(MediaCodec codec, MediaCodec.CodecException e) {
            try {
                LimeLog.warning("[Video] MediaCodec async error: " + e);
                if (!handleDecoderException(e)) {
                    LimeLog.info("Async error queued for recovery");
                }
            } catch (Throwable t) {
                LimeLog.severe("Error in async error handler: " + t);
            }
        }
    };

    // Keep async callback drop-policy in sync with pacing + its own setting.
    private void updateAsyncPreferLowerDelaysFromCurrentPrefs(int effectivePacing) {
        asyncCodec.setPreferLowerDelays(computeAsyncPreferLowerDelaysFromCurrentPrefs(effectivePacing));
    }

    // Return next input index (async => from queue; sync => dequeueInputBuffer)
    private int nextInputIndex(int timeoutUs) {
        if (!useAsyncCodec || videoDecoder == null) {
            return videoDecoder.dequeueInputBuffer(timeoutUs);
        }
        return asyncCodec.dequeueInputIndex(timeoutUs);
    }

    // Return next output index and fill outInfo (async => from queue; sync => dequeueOutputBuffer)
    private int nextOutputIndex(android.media.MediaCodec.BufferInfo outInfo, int timeoutUs) {
        if (!useAsyncCodec || videoDecoder == null) {
            asyncCodec.resetLastOutputReadyNs();
            return videoDecoder.dequeueOutputBuffer(outInfo, timeoutUs);
        }
        return asyncCodec.dequeueOutputIndex(videoDecoder, outInfo, timeoutUs, asyncOutputRelease);
    }

    private void attachAsyncCodecIfNeeded() {
        if (!useAsyncCodec || videoDecoder == null) return;

        updateAsyncPreferLowerDelaysFromCurrentPrefs(getEffectivePacingForThreadPriorities());

        asyncCodec.attachIfNeeded(
                videoDecoder,
                desiredCodecCallbackOsPriority(getEffectivePacingForThreadPriorities()),
                asyncCallbacks,
                asyncOutputRelease
        );

        // Keep OS priorities coherent after (re)attaching async callback.
        applyVideoThreadPriorities();
    }

    private void detachAsyncCodec() {
        asyncCodec.detach(videoDecoder, asyncOutputRelease);
    }

    public boolean isAsyncDecodingActive() {
        return useAsyncCodec && asyncCodec.isActive();
    }

    public String getAsyncDecodingStatus() {
        return "AsyncDecoding: " + (useAsyncCodec ? "ENABLED" : "DISABLED") +
                " (API " + Build.VERSION.SDK_INT +
                ", callbackThread=" + (asyncCodec.isActive() ? "alive" : "null") +
                ", inputQueue=" + asyncCodec.getInputQueueSize() +
                ", outputQueue=" + asyncCodec.getOutputQueueSize() + ")";
    }

    // Drain queued no-render releases on the renderer thread.
    private void drainAsyncNoRenderReleaseQueue() {
        asyncCodec.drainNoRenderReleaseQueue(videoDecoder, asyncOutputRelease);
    }
   // Async Decoding Helpers End


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
        if (!glUpscaler.isReady()) return false;
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
        final android.os.HandlerThread cb = asyncCodec.getCallbackThread();

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