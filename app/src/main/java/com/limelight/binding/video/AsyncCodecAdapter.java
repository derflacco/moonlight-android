package com.limelight.binding.video;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.SparseArray;
import android.util.SparseLongArray;

import com.limelight.LimeLog;

import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * AsyncCodecAdapter: isolates MediaCodec async callback plumbing + queues + BufferInfo pooling.
 * Keeps binder calls out of callback thread (best-effort).
 */
public final class AsyncCodecAdapter {

    public interface OutputRelease {
        void releaseNoRender(MediaCodec codec, int index);
    }

    public interface Callbacks {
        void onOutputFormatChanged(MediaCodec codec, MediaFormat format);
        void onCodecError(MediaCodec codec, MediaCodec.CodecException e);
    }

    private static final int INPUT_Q_CAP = 64;
    private static final int OUTPUT_Q_CAP = 8;
    private static final int RELEASE_Q_CAP = 32;

    // Cap how many releaseOutputBuffer(false) we do per render-loop tick to avoid bursts.
    private static final int RELEASE_DRAIN_CAP = 8;

    private final ArrayBlockingQueue<Integer> inputQueue =
            new ArrayBlockingQueue<>(INPUT_Q_CAP);
    private final ArrayBlockingQueue<Integer> outputQueue =
            new ArrayBlockingQueue<>(OUTPUT_Q_CAP);

    // Indices queued for NO-RENDER release on the renderer thread (avoid binder calls in callback).
    private final ArrayBlockingQueue<Integer> releaseQueue =
            new ArrayBlockingQueue<>(RELEASE_Q_CAP);

    private final SparseArray<MediaCodec.BufferInfo> outInfo =
            new SparseArray<>(16);

    // Output-ready timestamp (ns) per output index, to make decode latency comparable vs sync.
    // Guarded by synchronized(outInfo) together with outInfo (same index lifecycle).
    private final SparseLongArray outReadyNs =
            new SparseLongArray(16);

    // Ready timestamp (ns) for the last index returned by dequeueOutputIndex().
    private volatile long lastOutputReadyNs = 0L;

    // Callback thread drop-policy knob (volatile for cross-thread visibility).
    private volatile boolean preferLowerDelays = false;

    private HandlerThread callbackThread;
    private volatile boolean callbackInstalled = false;
    private MediaCodec attachedCodec;
    // ---- BufferInfo pool (avoid per-frame allocations) ----
    private final ArrayDeque<MediaCodec.BufferInfo> infoPool =
            new ArrayDeque<>(32);

    private MediaCodec.BufferInfo obtainInfo() {
        synchronized (infoPool) {
            MediaCodec.BufferInfo bi = infoPool.pollFirst();
            return (bi != null) ? bi : new MediaCodec.BufferInfo();
        }
    }

    private void recycleInfo(MediaCodec.BufferInfo bi) {
        if (bi == null) return;
        bi.set(0, 0, 0, 0);
        synchronized (infoPool) {
            if (infoPool.size() < 64) {
                infoPool.addFirst(bi);
            }
        }
    }

    public void setPreferLowerDelays(boolean enabled) {
        preferLowerDelays = enabled;
    }

    public long getLastOutputReadyNs() {
        return lastOutputReadyNs;
    }

    public void resetLastOutputReadyNs() {
        lastOutputReadyNs = 0L;
    }

    public HandlerThread getCallbackThread() {
        return callbackThread;
    }

    public int getInputQueueSize() {
        return inputQueue.size();
    }

    public int getOutputQueueSize() {
        return outputQueue.size();
    }

    public boolean isActive() {
        return callbackThread != null && callbackThread.isAlive();
    }

    // Return next input index from async queue (INFO_TRY_AGAIN_LATER == -1).
    public int dequeueInputIndex(int timeoutUs) {
        try {
            Integer idx = inputQueue.poll(timeoutUs, TimeUnit.MICROSECONDS);
            return (idx != null) ? idx : -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        }
    }

    // Return next output index and fill outInfoArg (INFO_TRY_AGAIN_LATER == -1).
    // If info is missing, releases the output buffer (no-render) to avoid leaks.
    public int dequeueOutputIndex(MediaCodec codec,
                                 MediaCodec.BufferInfo outInfoArg,
                                 int timeoutUs,
                                 OutputRelease release) {
        if (codec == null) {
            lastOutputReadyNs = 0L;
            return -1;
        }

        try {
            Integer idx = outputQueue.poll(timeoutUs, TimeUnit.MICROSECONDS);
            if (idx == null) {
                lastOutputReadyNs = 0L;
                return -1;
            }

            MediaCodec.BufferInfo bi;
            long readyNs;

            synchronized (outInfo) {
                bi = outInfo.get(idx);
                readyNs = outReadyNs.get(idx, 0L);
                outReadyNs.delete(idx);

                if (bi != null && outInfoArg != null) {
                    outInfoArg.set(bi.offset, bi.size, bi.presentationTimeUs, bi.flags);
                }

                if (bi != null) {
                    outInfo.remove(idx);
                }
            }

            if (bi == null) {
                lastOutputReadyNs = 0L;
                try {
                    if (release != null) {
                        release.releaseNoRender(codec, idx);
                    } else {
                        codec.releaseOutputBuffer(idx, false);
                    }
                } catch (Throwable ignored) { }
                return -1;
            }

            lastOutputReadyNs = readyNs;
            recycleInfo(bi);
            return idx;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastOutputReadyNs = 0L;
            return -1;
        }
    }

    // Drain queued no-render releases on the renderer thread. Bounded to avoid bursts.
    public void drainNoRenderReleaseQueue(MediaCodec codec, OutputRelease release) {
        if (codec == null) {
            while (releaseQueue.poll() != null) { }
            return;
        }

        int n = 0;
        Integer idx;
        while (n < RELEASE_DRAIN_CAP && (idx = releaseQueue.poll()) != null) {
            try {
                if (release != null) {
                    release.releaseNoRender(codec, idx);
                } else {
                    codec.releaseOutputBuffer(idx, false);
                }
            } catch (Throwable ignored) { }
            n++;
        }
    }

    // Quiescence cleanup used by codec recovery: release outstanding async-owned buffers and clear state.
    // Does NOT detach callbacks or stop the callback thread.
    public void quiesceAndRelease(MediaCodec codec, OutputRelease release) {
        try { inputQueue.clear(); } catch (Throwable ignored) { }

        try {
            if (codec != null) {
                Integer idx;
                while ((idx = outputQueue.poll()) != null) {
                    releaseOutputNoRenderAndRecycleInfo(codec, idx, release);
                }
                while ((idx = releaseQueue.poll()) != null) {
                    try {
                        if (release != null) {
                            release.releaseNoRender(codec, idx);
                        } else {
                            codec.releaseOutputBuffer(idx, false);
                        }
                    } catch (Throwable ignored) { }
                }
            } else {
                try { outputQueue.clear(); } catch (Throwable ignored) { }
                try { releaseQueue.clear(); } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) {
            try { outputQueue.clear(); } catch (Throwable ignored2) { }
            try { releaseQueue.clear(); } catch (Throwable ignored2) { }
        }

        synchronized (outInfo) {
            try { outInfo.clear(); } catch (Throwable ignored) { }
            try { outReadyNs.clear(); } catch (Throwable ignored) { }
        }

        lastOutputReadyNs = 0L;
    }

    public void attachIfNeeded(final MediaCodec codec,
                               final int callbackOsPriority,
                               final Callbacks callbacks,
                               final OutputRelease release) {
        if (codec == null) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;

        // Already attached to this codec instance
        if (callbackInstalled && attachedCodec == codec) {
            return;
        }

        // Codec instance changed: hard detach previous one (defensive)
        if (callbackInstalled && attachedCodec != null && attachedCodec != codec) {
            try { detach(attachedCodec, release); } catch (Throwable ignored) { }
        }

        // Reset state before attaching
        try { inputQueue.clear(); } catch (Throwable ignored) { }
        try { outputQueue.clear(); } catch (Throwable ignored) { }
        try { releaseQueue.clear(); } catch (Throwable ignored) { }
        synchronized (outInfo) {
            try { outInfo.clear(); } catch (Throwable ignored) { }
            try { outReadyNs.clear(); } catch (Throwable ignored) { }
        }
        lastOutputReadyNs = 0L;

        // Ensure callback thread exists and is alive
        if (callbackThread == null || !callbackThread.isAlive()) {
            callbackThread = new HandlerThread("CodecAsync", callbackOsPriority);
            callbackThread.start();
        }

        final Handler cb = new Handler(callbackThread.getLooper());

        codec.setCallback(new MediaCodec.Callback() {

            @Override
            public void onInputBufferAvailable(MediaCodec mc, int index) {
                try {
                    // Never block the MediaCodec callback thread.
                    if (!inputQueue.offer(index)) {
                        inputQueue.poll();
                        inputQueue.offer(index);
                    }
                } catch (Throwable ignored) { }
            }

            @Override
            public void onOutputBufferAvailable(MediaCodec mc, int index, MediaCodec.BufferInfo info) {
                final MediaCodec.BufferInfo copy = obtainInfo();
                copy.set(info.offset, info.size, info.presentationTimeUs, info.flags);

                boolean stored = false;
                try {
                    final long readyNs = System.nanoTime();
                    synchronized (outInfo) {
                        // Defensive: recycle previous BufferInfo if the same index is reused.
                        final MediaCodec.BufferInfo old = outInfo.get(index);
                        if (old != null && old != copy) {
                            recycleInfo(old);
                        }

                        outInfo.put(index, copy);
                        outReadyNs.put(index, readyNs);
                        stored = true;
                    }

                    // Prefer-lower-delays: keep only latest buffer for minimal latency.
                    if (preferLowerDelays) {
                        Integer oldIdx;
                        while ((oldIdx = outputQueue.poll()) != null) {
                            enqueueNoRenderReleaseOrReleaseNow(mc, oldIdx, release);
                        }
                        if (!outputQueue.offer(index)) {
                            enqueueNoRenderReleaseOrReleaseNow(mc, index, release);
                        }
                        return;
                    }

                    // Managed profiles: bounded queue with drop-oldest policy.
                    if (!outputQueue.offer(index)) {
                        final Integer oldIdx = outputQueue.poll();
                        if (oldIdx != null) {
                            enqueueNoRenderReleaseOrReleaseNow(mc, oldIdx, release);
                        }
                        if (!outputQueue.offer(index)) {
                            enqueueNoRenderReleaseOrReleaseNow(mc, index, release);
                        }
                    }
                } catch (Throwable t) {
                    // Make sure we don't leak output buffers or BufferInfo objects.
                    try {
                        if (stored) {
                            enqueueNoRenderReleaseOrReleaseNow(mc, index, release);
                        } else {
                            recycleInfo(copy);
                            try {
                                if (release != null) {
                                    release.releaseNoRender(mc, index);
                                } else {
                                    mc.releaseOutputBuffer(index, false);
                                }
                            } catch (Throwable ignored) { }
                        }
                    } catch (Throwable ignored) { }
                }
            }

            @Override
            public void onOutputFormatChanged(MediaCodec mc, MediaFormat format) {
                try {
                    if (callbacks != null) {
                        callbacks.onOutputFormatChanged(mc, format);
                    }
                } catch (Throwable ignored) { }
            }

            @Override
            public void onError(MediaCodec mc, MediaCodec.CodecException e) {
                try {
                    if (callbacks != null) {
                        callbacks.onCodecError(mc, e);
                    }
                } catch (Throwable ignored) { }
            }
        }, cb);

        attachedCodec = codec;
        callbackInstalled = true;
    }

    public void detach(MediaCodec codec, OutputRelease release) {
        // Avoid join deadlock if detach is accidentally called on the callback thread.
        final HandlerThread cbThread = callbackThread;
        if (cbThread != null && Thread.currentThread() == cbThread) {
            try {
                if (codec != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try { codec.setCallback(null, null); } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }

            try { cbThread.quitSafely(); } catch (Throwable ignored) { }

            attachedCodec = null;
            callbackInstalled = false;
            callbackThread = null;

            try { inputQueue.clear(); } catch (Throwable ignored) { }
            try { outputQueue.clear(); } catch (Throwable ignored) { }
            try { releaseQueue.clear(); } catch (Throwable ignored) { }
            synchronized (outInfo) {
                try { outInfo.clear(); } catch (Throwable ignored) { }
                try { outReadyNs.clear(); } catch (Throwable ignored) { }
            }
            lastOutputReadyNs = 0L;
            return;
        }

        try {
            if (codec != null) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        codec.setCallback(null, null);
                    }
                } catch (Throwable ignored) { }
            }
        } finally {
            // Clear input queue first (safe: indices are not owned by us).
            try { inputQueue.clear(); } catch (Throwable ignored) { }

            // Release outstanding output buffers before clearing queues (avoid BufferQueue stalls).
            try {
                if (codec != null) {
                    Integer idx;
                    while ((idx = outputQueue.poll()) != null) {
                        releaseOutputNoRenderAndRecycleInfo(codec, idx, release);
                    }
                    while ((idx = releaseQueue.poll()) != null) {
                        try {
                            if (release != null) {
                                release.releaseNoRender(codec, idx);
                            } else {
                                codec.releaseOutputBuffer(idx, false);
                            }
                        } catch (Throwable ignored) { }
                    }
                } else {
                    try { outputQueue.clear(); } catch (Throwable ignored) { }
                    try { releaseQueue.clear(); } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) {
                try { outputQueue.clear(); } catch (Throwable ignored2) { }
                try { releaseQueue.clear(); } catch (Throwable ignored2) { }
            }

            synchronized (outInfo) {
                try { outInfo.clear(); } catch (Throwable ignored) { }
                try { outReadyNs.clear(); } catch (Throwable ignored) { }
            }

            lastOutputReadyNs = 0L;

            attachedCodec = null;
            callbackInstalled = false;

            // Stop and wait for callback thread.
            if (callbackThread != null) {
                try {
                    callbackThread.quitSafely();
                    callbackThread.join(1000);
                    if (callbackThread.isAlive()) {
                        LimeLog.warning("Codec callback thread did not terminate in time");
                    }
                } catch (Throwable t) {
                    LimeLog.warning("Error stopping callback thread: " + t);
                } finally {
                    callbackThread = null;
                }
            }
        }
    }

    // Remove BufferInfo for index, recycle it immediately, then queue index for renderer-thread release.
    // Returns true if queued, false if queue is full.
    private boolean enqueueNoRenderRelease(int index) {
        if (index < 0) return true;

        MediaCodec.BufferInfo bi = null;
        try {
            synchronized (outInfo) {
                bi = outInfo.get(index);
                outInfo.remove(index);
                outReadyNs.delete(index);
            }
        } catch (Throwable ignored) { }

        recycleInfo(bi);

        // Best-effort: never block the callback thread.
        return releaseQueue.offer(index);
    }

    // Safety wrapper for callback thread: if queue is full, release immediately to prevent leaks.
    private void enqueueNoRenderReleaseOrReleaseNow(MediaCodec codec, int index, OutputRelease release) {
        if (codec == null || index < 0) return;
        if (!enqueueNoRenderRelease(index)) {
            try {
                if (release != null) {
                    release.releaseNoRender(codec, index);
                } else {
                    codec.releaseOutputBuffer(index, false);
                }
            } catch (Throwable ignored) { }
        }
    }

    private void releaseOutputNoRenderAndRecycleInfo(MediaCodec codec, int index, OutputRelease release) {
        if (codec == null || index < 0) return;

        try {
            if (release != null) {
                release.releaseNoRender(codec, index);
            } else {
                codec.releaseOutputBuffer(index, false);
            }
        } catch (Throwable ignored) { }

        MediaCodec.BufferInfo bi = null;
        try {
            synchronized (outInfo) {
                bi = outInfo.get(index);
                outInfo.remove(index);
                outReadyNs.delete(index);
            }
        } catch (Throwable ignored) { }

        recycleInfo(bi);
    }
}
