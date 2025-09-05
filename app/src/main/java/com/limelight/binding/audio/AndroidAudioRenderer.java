package com.limelight.binding.audio;

import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.audiofx.AudioEffect;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.audio.AudioRenderer;
import com.limelight.nvstream.jni.MoonBridge;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * AndroidAudioRenderer
 *
 * WRITE_NON_BLOCKING path (API >= 23) with a small (~70 ms) ring buffer to smooth brief stalls
 * without inflating end-to-end latency. Respects the 40 ms bound from
 * MoonBridge.getPendingAudioDuration(): if already above, we do not enqueue new audio and only drain,
 * with rate-limited logs. Includes transient error handling and avoids allocations in the hot path.
 *
 * Automatically falls back to the blocking write path on legacy devices.
 */
public class AndroidAudioRenderer implements AudioRenderer {
    private final Context context;
    private final boolean preferLowLatency;
    private final boolean enableAudioFx;
    private boolean smoothAudio;


    private AudioTrack track;

    // Non-blocking capability
    private final boolean nbSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;

    // Ring buffer (short/PCM16)
    private short[] ring;
    private int ringSize;   // in shorts
    private int rHead;      // read index
    private int rTail;      // write index
    private int rCount;     // number of shorts buffered
    private int channels;
    private int sampleRate;
    @SuppressWarnings("unused")
    private int capSamples; // target capacity (~70 ms) in shorts

    // Log rate limiting and error counters
    private long lastBacklogLogNs = 0L;
    private int consecutiveWriteErrors = 0;

    public AndroidAudioRenderer(Context context) {
        this(context, true, false);
    }

    /** Full constructor */
    public AndroidAudioRenderer(Context context, boolean preferLowLatency, boolean enableAudioFx) {
        this.context = context;
        this.preferLowLatency = preferLowLatency;
        this.enableAudioFx = enableAudioFx;
        this.smoothAudio = PreferenceConfiguration.readPreferences(context).smoothAudioPlayback;
    }

    /** Back-compat constructor used by older call sites: (Context, enableAudioFx) */
    public AndroidAudioRenderer(Context context, boolean enableAudioFx) {
        this(context, true, enableAudioFx);
    }

    private static long nowNs() { return System.nanoTime(); }

    private void rbInit(int capacitySamples, int channels, int sampleRate) {
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.capSamples = capacitySamples;

        int desired = Math.max(capacitySamples, channels * 128);
        // Round up to a multiple of frame size (channels)
        desired = (desired + channels - 1) / channels * channels;

        if (ring == null || ring.length != desired) {
            ring = new short[desired];
        }
        ringSize = desired;
        rHead = rTail = rCount = 0;
    }

    private int rbWritable() { return ringSize - rCount; }
    private int rbReadable() { return rCount; }

    private int rbWrite(short[] src, int off, int len) {
        int can = Math.min(len, rbWritable());
        if (can <= 0) return 0;
        int tailToEnd = Math.min(can, ringSize - rTail);
        System.arraycopy(src, off, ring, rTail, tailToEnd);
        int remain = can - tailToEnd;
        if (remain > 0) {
            System.arraycopy(src, off + tailToEnd, ring, 0, remain);
        }
        rTail = (rTail + can) % ringSize;
        rCount += can;
        return can;
    }

    private int rbReadToTrackNonBlocking() {
        if (track == null) return 0;
        int readable = rbReadable();
        if (readable <= 0) return 0;

        int wroteTotal = 0;
        int attempts = 0;
        while (readable > 0 && attempts < 3) {
            int chunk = Math.min(readable, ringSize - rHead);
            int wrote;
            try {
                wrote = track.write(ring, rHead, chunk, AudioTrack.WRITE_NON_BLOCKING);
            } catch (Throwable t) {
                wrote = AudioTrack.ERROR_INVALID_OPERATION;
            }

            if (wrote > 0) {
                rHead = (rHead + wrote) % ringSize;
                rCount -= wrote;
                readable -= wrote;
                wroteTotal += wrote;
                consecutiveWriteErrors = 0;
            } else {
                // 0 or negative/invalid: break to avoid busy spinning
                attempts++;
                break;
            }
        }
        return wroteTotal;
    }

    private AudioTrack createAudioTrack(int channelConfig, int sampleRate, int bufferSize, boolean lowLatency) {
        AudioAttributes.Builder attributesBuilder = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O && lowLatency) {
            try { attributesBuilder.setFlags(AudioAttributes.FLAG_LOW_LATENCY); } catch (Throwable ignored) {}
        }

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build();

        AudioTrack at;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            at = new AudioTrack(attributesBuilder.build(), format, bufferSize,
                    AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
        } else {
            at = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize, AudioTrack.MODE_STREAM);
        }

        try { at.play(); } catch (Throwable ignored) {}
        return at;
    }

    @Override
    public int setup(MoonBridge.AudioConfiguration audioConfiguration, int sampleRate, int samplesPerFrame) {
        int channelConfig;
        switch (audioConfiguration.channelCount) {
            case 2:  channelConfig = AudioFormat.CHANNEL_OUT_STEREO; break;
            case 4:  channelConfig = AudioFormat.CHANNEL_OUT_QUAD; break;
            case 6:  channelConfig = AudioFormat.CHANNEL_OUT_5POINT1; break;
            case 8:  channelConfig = AudioFormat.CHANNEL_OUT_7POINT1_SURROUND; break;
            default: return -2; // Unsupported
        }

        int bytesPerSample = 2; // PCM16
        int bytesPerFrame = bytesPerSample * audioConfiguration.channelCount;

        int minBuf;
        try {
            minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);
        } catch (Throwable t) {
            return -2;
        }
        if (minBuf <= 0) return -2;

        int bufferSize = Math.max(minBuf, bytesPerFrame * samplesPerFrame * 4);

        try {
            track = createAudioTrack(channelConfig, sampleRate, bufferSize, preferLowLatency);
        } catch (Throwable t) {
            track = null;
        }
        if (track == null) return -2;

        // Ring ~70 ms (only when Smooth Audio Playback is ON)
        if (smoothAudio && nbSupported) {
            int capMs = 70;
            int capSamples = (sampleRate * audioConfiguration.channelCount * capMs) / 1000;
            rbInit(capSamples, audioConfiguration.channelCount, sampleRate);
        } else {
            // Ensure ring is cleared when disabled
            ring = null; ringSize = rHead = rTail = rCount = 0; capSamples = 0; this.channels = audioConfiguration.channelCount; this.sampleRate = sampleRate;
        }
        return 0;
    }

    @Override
    public void playDecodedAudio(short[] audioData) {
        if (track == null || audioData == null || audioData.length == 0) return;

        final int pendingMs = MoonBridge.getPendingAudioDuration();

        if (smoothAudio && nbSupported) {
            // Drain first
            rbReadToTrackNonBlocking();

            if (pendingMs >= 40) {
                // Respect 40 ms bound: don't enqueue more; just drain
                final long now = nowNs();
                if (now - lastBacklogLogNs > 500_000_000L) { // 0.5 s
                    LimeLog.info("Too much pending audio data: " + pendingMs + " ms");
                    lastBacklogLogNs = now;
                }
                rbReadToTrackNonBlocking();
                return;
            }

            // Enqueue to ring (drop overflow)
            int wrote = rbWrite(audioData, 0, audioData.length);
            if (wrote < audioData.length) {
                final long now = nowNs();
                if (now - lastBacklogLogNs > 500_000_000L) {
                    LimeLog.info("Audio ring full: dropping " + (audioData.length - wrote) + " samples");
                    lastBacklogLogNs = now;
                }
            }

            // Push to device
            rbReadToTrackNonBlocking();
        } else {
            // Legacy blocking path
            if (pendingMs < 40) {
                try {
                    final int wrote = track.write(audioData, 0, audioData.length);
                    if (wrote < 0) {
                        consecutiveWriteErrors++;
                        if (consecutiveWriteErrors <= 4) {
                            LimeLog.info("AudioTrack.write() error: " + wrote);
                        }
                    } else {
                        consecutiveWriteErrors = 0;
                    }
                } catch (Throwable t) {
                    consecutiveWriteErrors++;
                    if (consecutiveWriteErrors <= 2) {
                        LimeLog.info("AudioTrack.write() unexpected: " + t.getClass().getSimpleName());
                    }
                }
            } else {
                final long now = nowNs();
                if (now - lastBacklogLogNs > 500_000_000L) {
                    LimeLog.info("Too much pending audio data: " + pendingMs + " ms");
                    lastBacklogLogNs = now;
                }
            }
        }
    }

    @Override
    public void start() {
        consecutiveWriteErrors = 0;
        lastBacklogLogNs = 0L;

        if (track != null) {
            try { track.play(); } catch (Throwable ignored) {}
        }

        if (enableAudioFx && track != null) {
            try {
                Intent i = new Intent(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
                i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
                i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
                context.sendBroadcast(i);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void stop() {
        if (enableAudioFx && track != null) {
            try {
                Intent i = new Intent(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
                i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, track.getAudioSessionId());
                i.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName());
                context.sendBroadcast(i);
            } catch (Throwable ignored) {}
        }
        if (track != null) {
            try { track.pause(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
        }
        // Clear ring buffer indices
        rHead = rTail = rCount = 0;
    }

    @Override
    public void cleanup() {
        if (track == null) return;
        try {
            try { track.pause(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
        } finally {
            try { track.release(); } catch (Throwable ignored) {}
            track = null;
        }
        ring = null;
        ringSize = rHead = rTail = rCount = 0;
        capSamples = 0;
    }
}
