package com.limelight.binding.video;

import android.content.Context;
import android.net.TrafficStats;
import android.os.Process;

import com.limelight.R;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.utils.Stereo3DRenderer;
import com.limelight.utils.TrafficStatsHelper;

/**
 * PerfOverlayComposer: builds perf overlay strings (mini/lite/full) and encapsulates
 * OLED shift/blink state + bandwidth delta tracking.
 *
 * Intended to run on the UI thread (the caller decides threading).
 */
public final class PerfOverlayComposer {

    public static final class Result {
        public final String fullLog;      // raw log (no OLED shift/blink)
        public final String renderedLog;  // final overlay (after OLED shift/blink)

        Result(String fullLog, String renderedLog) {
            this.fullLog = (fullLog != null) ? fullLog : "";
            this.renderedLog = (renderedLog != null) ? renderedLog : "";
        }
    }

    // --- OLED burn-in protection for Lite overlay (horizontal shift) ---
    private static final long LITE_SHIFT_PERIOD_NS = 30_000_000_000L; // 30s
    private static final int LITE_SHIFT_MAX_SPACES = 8;

    // --- OLED "pixel refresh" blink for Lite overlay ---
    // Briefly blanks the Lite overlay to let OLED pixels rest (default: 250ms every 5 minutes).
    private static final long LITE_BLINK_PERIOD_NS = 300_000_000_000L;   // 5 min
    private static final long LITE_BLINK_DURATION_NS = 250_000_000L;     // 250 ms

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

    private volatile long lastNetDataNum = 0L;

    private long liteBlinkNextStartNs = 0L;
    private long liteBlinkEndNs = 0L;

    private long liteShiftNextNs = 0L;
    private int liteShiftSpaces = 0; // 0..2 (ping-pong)

    public void reset() {
        lastNetDataNum = 0L;
        liteBlinkNextStartNs = 0L;
        liteBlinkEndNs = 0L;
        liteShiftNextNs = 0L;
        liteShiftSpaces = 0;
    }

    /**
     * Builds overlay strings. Caller decides whether/when to dispatch to PerfOverlayListener.
     */
    public Result build(final Context context,
                        final PreferenceConfiguration prefsSnapshot,
                        final VideoStats lastTwo,
                        final VideoStatsFps fps,
                        final float decodeTimeMs,
                        final long rttInfo,
                        final String decoder,
                        final float endToEndTimeMs,
                        final boolean hdrActive,
                        final int streamW,
                        final int streamH,
                        final boolean isFsrActive,
                        final float fsrWeightMsForE2e) {

        if (context == null || prefsSnapshot == null || lastTwo == null || fps == null) {
            return new Result("", "");
        }

        final float e2eTotalMs = endToEndTimeMs + fsrWeightMsForE2e;

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

            sb.append("\t ");
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
            } catch (Throwable ignored) { }

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
            } catch (Throwable ignored) { }

            // Advanced Lite metrics: received/rendered FPS and HDR status
            if (prefsSnapshot.enablePerfOverlayLiteAdvanced) {
                sb.append("  R:").append((int) fps.renderedFps);
                sb.append("  ").append(hdrActive ? "HDR" : "SDR");
                sb.append(' ').append(getLitePacingGlyph(prefsSnapshot, isFsrActive));
                if (prefsSnapshot.gpuPathMode) {
                    sb.append('G');
                }
            }

            // Server stats (host processing latency, from server) - keep at the end for readability
            if (prefsSnapshot.showServerStats && lastTwo.framesWithHostProcessingLatency > 0) {
                float avgHostMs = ((float) lastTwo.totalHostProcessingLatency / 10f) /
                        (float) lastTwo.framesWithHostProcessingLatency;

                // Base format
                sb.append("  | SRV ");
                sb.append(String.format("%.1f", avgHostMs)).append("ms");

                // Add min/max only when Lite Advanced is enabled
                if (prefsSnapshot.enablePerfOverlayLiteAdvanced) {
                    float minHostMs = (float) lastTwo.minHostProcessingLatency / 10f;
                    float maxHostMs = (float) lastTwo.maxHostProcessingLatency / 10f;
                    sb.append(" (");
                    sb.append(String.format("%.1f", minHostMs)).append("–");
                    sb.append(String.format("%.1f", maxHostMs)).append("ms)");
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
                        streamW + "x" + streamH, fps.totalFps));
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
                        streamW + "x" + streamH, fps.totalFps));
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

        final String fullLog = sb.toString();

        // Apply OLED shift (prepend spaces)
        if (prefsSnapshot.enablePerfOverlayLite && prefsSnapshot.enablePerfOverlayLiteOledShift) {
            try {
                sb.insert(0, LITE_SHIFT_PREFIX[Math.min(LITE_SHIFT_MAX_SPACES, Math.max(0, liteShiftSpaces))]);
            } catch (Throwable ignored) { }
        }

        // Apply OLED blink (clear overlay during blink period)
        if (prefsSnapshot.enablePerfOverlayLite && prefsSnapshot.enablePerfOverlayLiteOledShift) {
            try {
                long now = System.nanoTime();
                if (liteBlinkEndNs > 0L && now < liteBlinkEndNs) {
                    sb.setLength(0);
                    sb.append(' '); // Minimal content for transparent overlay
                }
            } catch (Throwable ignored) { }
        }

        final String renderedLog = sb.toString();
        return new Result(fullLog, renderedLog);
    }

    // Lite pacing glyph
    private static String getLitePacingGlyph(final PreferenceConfiguration p, final boolean isFsrActive) {
        if (p == null) {
            return "?";
        }

        StringBuilder glyph = new StringBuilder(3);

        if (p.framePacing == PreferenceConfiguration.FRAME_PACING_GPU_RAW) {
            glyph.append('R');
        } else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_MIN_LATENCY) {
            glyph.append('L');
        } else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_BALANCED) {
            glyph.append('B');
        } else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_MAX_SMOOTHNESS) {
            glyph.append('S');
        } else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_CAP_FPS) {
            glyph.append('C');
        } else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_WARP) {
            glyph.append('W');
        } else if (p.framePacing == PreferenceConfiguration.FRAME_PACING_WARP2) {
            glyph.append('2');
        } else {
            glyph.append('?');
        }
        // Async decode glyph
        if (p.asyncDecodeEnabled) {
            glyph.append('A');
        }

        if (isFsrActive) {
            glyph.append('U');
        }

        if (p.enableVsync && p.framePacing != PreferenceConfiguration.FRAME_PACING_BALANCED) {
            glyph.append('V');
        }

        if (p.fastVsync) {
            glyph.append('F');
        }

        return glyph.toString();
    }
}
